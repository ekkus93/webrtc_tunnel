package com.phillipchin.webrtctunnel.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.ForwardsRevisionMismatchException
import com.phillipchin.webrtctunnel.data.ForwardsSnapshot
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
                val beforeSnapshot = deps.forwardsRepository.snapshot()
                val mutationResult = deps.forwardsRepository.upsert(forward)
                report(
                    if (!mutationResult.validationResult.valid) {
                        mutationResult.validationResult.message ?: "Forward update failed"
                    } else {
                        val sync = withContext(ioDispatcher) { regenerateActiveConfig() }
                        if (!sync.valid) {
                            rollbackAfterConfigSyncFailure(
                                beforeSnapshot,
                                mutationResult.revision,
                                sync,
                                "Forward update failed",
                            )
                        } else {
                            "Forward saved"
                        }
                    },
                )
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
                val beforeSnapshot = deps.forwardsRepository.snapshot()
                val mutationResult = deps.forwardsRepository.delete(forwardId)
                report(
                    if (!mutationResult.validationResult.valid) {
                        mutationResult.validationResult.message ?: "Forward delete failed"
                    } else {
                        val sync = withContext(ioDispatcher) { regenerateActiveConfig() }
                        if (!sync.valid) {
                            rollbackAfterConfigSyncFailure(
                                beforeSnapshot,
                                mutationResult.revision,
                                sync,
                                "Forward delete failed",
                            )
                        } else {
                            "Forward deleted"
                        }
                    },
                )
            } finally {
                _isBusy.value = false
            }
        }
    }

    /**
     * Rolls the in-memory/persisted forwards list back to [snapshot] after [syncFailure] blocked
     * activating the change, and reports whichever failure(s) actually occurred (P0-004): a
     * rollback failure must never be silently ignored, since it means the saved forwards file
     * and the active config have now diverged.
     */
    private suspend fun rollbackAfterConfigSyncFailure(
        snapshot: ForwardsSnapshot,
        mutationRevision: Long,
        syncFailure: ValidationResult,
        fallbackMessage: String,
    ): String {
        val original = syncFailure.message ?: fallbackMessage
        // Rollback targets the revision AFTER the mutation, so the check passes when
        // no newer concurrent mutation happened. The forwards to restore are from the
        // snapshot taken BEFORE the mutation.
        return deps.forwardsRepository.saveIfRevisionMatches(
            expectedRevision = mutationRevision,
            forwards = snapshot.forwards,
        ).fold(
            onSuccess = { original },
            onFailure = { rollbackError ->
                when (rollbackError) {
                    is ForwardsRevisionMismatchException -> {
                        // Revision changed: newer mutation happened, don't overwrite it.
                        "Activation failed. Automatic rollback was skipped because " +
                            "forwards changed again. The newer changes were left untouched."
                    }
                    else -> {
                        val rollbackMessage = describeForwardsFailure(rollbackError)
                        "$original. Rollback also failed; the forward change remains saved " +
                            "but was not activated: $rollbackMessage"
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
                if (result.valid) {
                    deps.configRepository.writeConfigAtomically(candidate)
                }
                result
            } finally {
                // Wipe the plaintext identity buffer regardless of success/failure.
                identity?.fill(0)
                temp.delete()
            }
        }.getOrElse { ValidationResult(false, it.message) }
    }
}
