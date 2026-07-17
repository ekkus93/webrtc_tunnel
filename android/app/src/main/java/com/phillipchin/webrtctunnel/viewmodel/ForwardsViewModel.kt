package com.phillipchin.webrtctunnel.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.ForwardsMutationBlocked
import com.phillipchin.webrtctunnel.data.ForwardsMutationReceipt
import com.phillipchin.webrtctunnel.data.ForwardsRevisionMismatchException
import com.phillipchin.webrtctunnel.data.OperationFailure
import com.phillipchin.webrtctunnel.data.SensitiveDataRedactor
import com.phillipchin.webrtctunnel.data.createCandidateFile
import com.phillipchin.webrtctunnel.data.deleteCandidateFileSafely
import com.phillipchin.webrtctunnel.data.describeForwardsFailure
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.TunnelStatus
import com.phillipchin.webrtctunnel.model.ValidationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
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

    // P1-008: the last failed mutation, kept in state so a forward failure survives without a
    // snackbar collector. Cleared on the next successful mutation.
    private val _lastOperationFailure = MutableStateFlow<OperationFailure?>(null)
    val lastOperationFailure: StateFlow<OperationFailure?> = _lastOperationFailure.asStateFlow()
    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    // P1-005-C: an atomic busy guard so two rapid mutations cannot both pass a check-before-launch
    // busy read, race their config regeneration, and activate a stale config. _isBusy remains for
    // UI state only.
    private val operationMutex = Mutex()

    /**
     * Record a result and surface it through the app-wide snackbar. [failure] is the durable
     * P1-008 copy: a non-null value on a failed mutation (surviving a missing snackbar collector)
     * or null on success (clearing any prior failure). These messages are already redacted at
     * their source — the config-write path redacts; the identity-unreadable diagnostic is kept
     * verbatim by design — so this does not re-redact (expanding redaction is P1-009).
     */
    private fun report(
        message: String,
        failure: OperationFailure? = null,
    ) {
        _lastOperationFailure.value = failure
        _message.value = message
        deps.snackbar.show(message)
    }

    fun reload() {
        viewModelScope.launch { deps.forwardsRepository.refresh() }
    }

    fun saveForward(forward: ForwardConfig) {
        viewModelScope.launch {
            if (!operationMutex.tryLock()) {
                report("A forward operation is already in progress", _lastOperationFailure.value)
                return@launch
            }
            _isBusy.value = true
            try {
                // P1-001: Use receipt-based atomic upsert.
                val receipt: ForwardsMutationReceipt =
                    deps.forwardsRepository.upsertWithReceipt(forward).getOrElse { error ->
                        val message = mapMutationError(error)
                        report(message, OperationFailure("forward_mutation_failed", message))
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
                operationMutex.unlock()
            }
        }
    }

    fun deleteForward(forwardId: String) {
        viewModelScope.launch {
            if (!operationMutex.tryLock()) {
                report("A forward operation is already in progress", _lastOperationFailure.value)
                return@launch
            }
            _isBusy.value = true
            try {
                // P1-001: Use receipt-based atomic delete.
                val receipt: ForwardsMutationReceipt =
                    deps.forwardsRepository.deleteWithReceipt(forwardId).getOrElse { error ->
                        val message = mapMutationError(error)
                        report(message, OperationFailure("forward_mutation_failed", message))
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
                operationMutex.unlock()
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
                report(syncFailureMessage, OperationFailure("forward_activation_failed", syncFailureMessage))
            },
            onFailure = { rollbackError ->
                when (rollbackError) {
                    is ForwardsRevisionMismatchException -> {
                        // Revision changed: newer mutation happened, don't overwrite it.
                        val message =
                            "Activation failed. Automatic rollback was skipped because " +
                                "forwards changed again. The newer changes were left untouched."
                        report(message, OperationFailure("forward_rollback_skipped", message))
                    }
                    else -> {
                        val rollbackMessage = describeForwardsFailure(rollbackError)
                        val message =
                            "$syncFailureMessage. Rollback also failed; the forward change " +
                                "remains saved but was not activated: $rollbackMessage"
                        report(message, OperationFailure("forward_rollback_incomplete", message))
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
        // P1-005: a unique candidate file per validation so two concurrent forward mutations
        // can never share (and clobber) one fixed candidate path.
        val temp = createCandidateFile(deps.context.cacheDir, "forwards-config-")
        // FIX6 P0-005: explicit try/catch (not runCatching) — it wraps the suspend
        // writeConfigAtomically, so a cancellation must propagate, not become an invalid result.
        return try {
            // Identity absent vs. present-but-unreadable differ: only the former falls back to
            // identity-less validation; an unreadable present identity is a visible failure (P1-001).
            val identity =
                if (deps.identityRepository.hasEncryptedIdentity()) {
                    try {
                        deps.identityRepository.readPrivateIdentityPlaintext()
                    } catch (error: Exception) {
                        error("Identity exists but could not be loaded: ${error.message}")
                    }
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
                commitRegeneratedConfig(candidate, result)
            } finally {
                // Wipe the plaintext identity buffer regardless of success/failure.
                identity?.fill(0)
                deleteCandidateFileSafely(temp)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Message kept as-is to preserve existing behavior (e.g. the identity-unreadable
            // diagnostic); holistic redaction of these ViewModel messages is P1-009. The
            // write-failure path already redacts its own message.
            ValidationResult(false, error.message ?: "Failed to regenerate config")
        }
    }

    // FIX6 P0-001-D: a failed config commit invalidates the result so the caller rolls the
    // forward mutation back, rather than reporting a false "saved".
    private suspend fun commitRegeneratedConfig(
        candidate: String,
        validation: ValidationResult,
    ): ValidationResult =
        if (!validation.valid) {
            validation
        } else {
            deps.configRepository.writeConfigAtomically(candidate).fold(
                onSuccess = { validation },
                onFailure = { error ->
                    ValidationResult(
                        valid = false,
                        message = SensitiveDataRedactor.redactText(error.message ?: "Failed to write active config"),
                    )
                },
            )
        }
}
