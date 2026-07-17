package com.phillipchin.webrtctunnel.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.ForwardsMutationBlocked
import com.phillipchin.webrtctunnel.data.ForwardsMutationReceipt
import com.phillipchin.webrtctunnel.data.ForwardsRevisionMismatchException
import com.phillipchin.webrtctunnel.data.SensitiveDataRedactor
import com.phillipchin.webrtctunnel.data.describeForwardsFailure
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.TunnelStatus
import com.phillipchin.webrtctunnel.model.ValidationResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

private const val LOCAL_PORT_TEST_TIMEOUT_MS = 1200

class ForwardsViewModel(
    private val deps: AppDependencies,
    private val ioDispatcher: CoroutineDispatcher = deps.dispatchers.io,
) : ViewModel() {
    val status: StateFlow<TunnelStatus> = deps.tunnelRepository.status

    // Observe the shared single source of truth so edits made on any screen are reflected.
    val forwards: StateFlow<List<ForwardConfig>> = deps.forwardsRepository.forwards

    // A corrupt/unreadable saved forwards file must be visible, not rendered as a
    // legitimately empty list (P1-002).
    val loadError: StateFlow<String?> = deps.forwardsRepository.loadError
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    /** Record a result for this screen and surface it through the app-wide snackbar. */
    private fun report(message: String) {
        _message.value = message
        deps.snackbar.show(message)
    }

    fun reload() {
        viewModelScope.launch { deps.forwardsRepository.refresh() }
    }

    fun saveForward(forward: ForwardConfig) {
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            try {
                // P1-001: Use receipt-based atomic upsert.
                val receipt: ForwardsMutationReceipt =
                    deps.forwardsRepository.upsertWithReceipt(forward).getOrElse { error ->
                        report(mapMutationError(error))
                        return@launch
                    }

                val sync = withContext(ioDispatcher) { regenerateActiveConfig() }
                if (sync.valid) {
                    report("Forward saved")
                } else {
                    // Config sync failed — attempt to rollback the mutation via receipt.
                    rollbackWithReceipt(receipt, sync.message ?: "Forward update failed")
                }
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun deleteForward(forwardId: String) {
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            try {
                // P1-001: Use receipt-based atomic delete.
                val receipt: ForwardsMutationReceipt =
                    deps.forwardsRepository.deleteWithReceipt(forwardId).getOrElse { error ->
                        report(mapMutationError(error))
                        return@launch
                    }

                val sync = withContext(ioDispatcher) { regenerateActiveConfig() }
                if (sync.valid) {
                    report("Forward deleted")
                } else {
                    // Config sync failed — attempt to rollback the mutation via receipt.
                    rollbackWithReceipt(receipt, sync.message ?: "Forward delete failed")
                }
            } finally {
                _isBusy.value = false
            }
        }
    }

    /** Maps a forwards mutation error to a user-visible message. */
    private fun mapMutationError(error: Throwable): String {
        return when (error) {
            is ForwardsMutationBlocked -> error.message ?: "Forwards mutation blocked"
            else -> describeForwardsFailure(error)
        }
    }

    /**
     * P1-001: Rolls the mutation back using the [receipt].
     * If the rollback fails due to a revision mismatch (a newer mutation happened),
     * that is preserved. Otherwise, the rollback failure is reported.
     */
    private suspend fun rollbackWithReceipt(
        receipt: ForwardsMutationReceipt,
        syncFailureMessage: String,
    ) {
        deps.forwardsRepository.rollbackReceipt(receipt).fold(
            onSuccess = {
                report(syncFailureMessage)
            },
            onFailure = { rollbackError ->
                when (rollbackError) {
                    is ForwardsRevisionMismatchException -> {
                        // Revision changed: newer mutation happened, don't overwrite it.
                        report(
                            "Activation failed. Automatic rollback was skipped because " +
                                "forwards changed again. The newer changes were left untouched.",
                        )
                    }
                    else -> {
                        val rollbackMessage = describeForwardsFailure(rollbackError)
                        report(
                            "$syncFailureMessage. Rollback also failed; the forward change " +
                                "remains saved but was not activated: $rollbackMessage",
                        )
                    }
                }
            },
        )
    }

    fun validateForwardDraft(
        draft: ForwardConfig,
        currentForwards: List<ForwardConfig>,
    ): String? {
        val updated =
            currentForwards.map { if (it.id == draft.id) draft else it }.let { candidates ->
                if (candidates.none { it.id == draft.id }) candidates + draft else candidates
            }
        return deps.forwardsStore.validateForwards(updated)
    }

    fun testLocalPort(forward: ForwardConfig) {
        viewModelScope.launch(ioDispatcher) {
            // Connect to the configured local host (blank falls back to loopback),
            // and report the host actually tested rather than a hardcoded address.
            val host = forward.localHost.trim().ifBlank { "127.0.0.1" }
            val resultMessage =
                runCatching {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(host, forward.localPort), LOCAL_PORT_TEST_TIMEOUT_MS)
                    }
                    "Local port test succeeded for $host:${forward.localPort}"
                }.getOrElse {
                    "Local port test failed for $host:${forward.localPort}: ${it.message}"
                }
            report(SensitiveDataRedactor.redactText(resultMessage))
        }
    }

    private suspend fun regenerateActiveConfig(): ValidationResult {
        // A corrupt setup draft must block config regeneration rather than silently rendering
        // a config from reset defaults.
        val input =
            deps.configRepository.loadSetupInputResult().getOrElse {
                return ValidationResult(false, "Saved setup is corrupt; re-run setup before changing forwards")
            }
        val forwards = deps.forwardsRepository.current().filter { it.enabled }
        val prefs = deps.configRepository.preferences.first()
        val candidate =
            deps.configRepository.renderOfferConfig(
                input,
                forwards,
                prefs.debugLogsEnabled,
                prefs.androidIceMode,
            )
        val temp = File(deps.context.cacheDir, "config-forwards-candidate.toml")
        return runCatching {
            // Identity absence and identity-present-but-unreadable are different states: only
            // the former may fall back to identity-less validation. A read/decrypt failure on a
            // present identity must surface as a visible failure, not silently downgrade (P1-001).
            val identity =
                if (deps.identityRepository.hasEncryptedIdentity()) {
                    runCatching { deps.identityRepository.readPrivateIdentityPlaintext() }
                        .getOrElse { error("Identity exists but could not be loaded: ${it.message}") }
                } else {
                    null
                }
            try {
                temp.parentFile?.mkdirs()
                temp.writeText(candidate)
                val result =
                    if (identity != null) {
                        deps.identityValidation.validateConfigWithIdentity(temp.absolutePath, identity)
                    } else {
                        deps.identityValidation.validateConfig(temp.absolutePath)
                    }
                // FIX6 P0-001-D: a failed config commit must invalidate the result so the
                // caller rolls the forward mutation back via its receipt. Previously the
                // write result was discarded, so a failed write still returned the valid
                // validation and reported "Forward saved" while config.toml was unchanged.
                if (!result.valid) {
                    result
                } else {
                    deps.configRepository
                        .writeConfigAtomically(candidate)
                        .fold(
                            onSuccess = { result },
                            onFailure = { error ->
                                ValidationResult(
                                    valid = false,
                                    message =
                                        SensitiveDataRedactor.redactText(
                                            error.message ?: "Failed to write active config",
                                        ),
                                )
                            },
                        )
                }
            } finally {
                // Wipe the plaintext identity buffer regardless of success/failure.
                identity?.fill(0)
                temp.delete()
            }
        }.getOrElse { ValidationResult(false, it.message) }
    }
}
