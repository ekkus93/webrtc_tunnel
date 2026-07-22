package com.phillipchin.webrtctunnel.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.CandidateCleanupException
import com.phillipchin.webrtctunnel.data.ConfigurationAdmission
import com.phillipchin.webrtctunnel.data.ConfigurationOperation
import com.phillipchin.webrtctunnel.data.ForwardConfigurationResult
import com.phillipchin.webrtctunnel.data.ForwardConfigurationRollbackStageResult
import com.phillipchin.webrtctunnel.data.OperationFailure
import com.phillipchin.webrtctunnel.data.SensitiveDataRedactor
import com.phillipchin.webrtctunnel.data.deleteCandidateFileSafely
import com.phillipchin.webrtctunnel.data.loadSetupInputResult
import com.phillipchin.webrtctunnel.data.renderOfferConfig
import com.phillipchin.webrtctunnel.data.resolveBrokerPasswordPath
import com.phillipchin.webrtctunnel.data.withCandidateFile
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
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

private const val LOCAL_PORT_TEST_TIMEOUT_MS = 1200

/**
 * [deleteCandidateFile] is injectable (FIX7 P1-001-C/P1-001-E) so tests can force the
 * forward-activation candidate cleanup to fail with a fake instead of a flaky filesystem
 * permission trick — production always uses the real [deleteCandidateFileSafely].
 */
class ForwardsViewModel(
    private val deps: AppDependencies,
    private val ioDispatcher: CoroutineDispatcher = deps.dispatchers.io,
    private val deleteCandidateFile: (File) -> Result<Unit> = ::deleteCandidateFileSafely,
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

    init {
        // FIX7 P1-003-B: ForwardsRepository no longer reads its baseline at construction
        // (that was main-thread I/O) — the first real load now happens here, off the main
        // thread, instead of relying on a caller to trigger reload() manually.
        reload()
    }

    /**
     * Record a result and surface it through the app-wide snackbar. [failure] is the durable
     * P1-008 copy: a non-null value on a failed mutation (surviving a missing snackbar collector)
     * or null on success (clearing any prior failure). These messages are already redacted at
     * their source (FIX7 P1-004-C) — this does not re-redact.
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
        runForwardMutation {
            val current = deps.forwardsRepository.current()
            val proposed =
                current.toMutableList().apply {
                    val index = indexOfFirst { it.id == forward.id }
                    if (index >= 0) set(index, forward) else add(forward)
                }
            applyForwardChange(proposed, "Forward saved")
        }
    }

    fun deleteForward(forwardId: String) {
        runForwardMutation {
            val proposed = deps.forwardsRepository.current().filterNot { it.id == forwardId }
            applyForwardChange(proposed, "Forward deleted")
        }
    }

    /**
     * FIX8 P0-005-C/D: builds the proposed list in memory, validates it and renders+native-
     * validates its candidate config entirely before any authoritative mutation — no receipt is
     * created before validation — then commits both forwards and config atomically through
     * [ForwardConfigurationCoordinator]. Replaces the previous mutate-forwards-then-roll-back-
     * via-receipt-if-config-fails pattern (CRITICAL-4): a forward mutation is no longer visible
     * on disk/in-memory at all until the whole activation has succeeded.
     */
    private suspend fun applyForwardChange(
        proposed: List<ForwardConfig>,
        successMessage: String,
    ) {
        val structuralError = deps.forwardsStore.validateForwards(proposed)
        if (structuralError != null) {
            report(structuralError, OperationFailure("forward_mutation_failed", structuralError))
            return
        }

        // FIX8 P0-005-D: both the render/native-validate step and the coordinator's own apply()
        // must run on ioDispatcher, not the caller's (Main) dispatcher — both suspend inside real
        // file I/O (and, in tests, a fake that blocks a real thread), which must never run on the
        // main thread. One withContext boundary (not one per step) so there is only a single
        // Main<->IO dispatch hop for the whole activation.
        val outcome =
            withContext(ioDispatcher) {
                when (val rendered = renderAndValidateCandidate(deps, proposed, deleteCandidateFile)) {
                    is CandidateRenderResult.Failed -> ForwardActivationOutcome.RenderFailed(rendered.message)
                    is CandidateRenderResult.Ready ->
                        ForwardActivationOutcome.Applied(
                            deps.forwardConfigurationCoordinator.apply(proposed, rendered.candidate),
                        )
                }
            }

        when (outcome) {
            is ForwardActivationOutcome.RenderFailed ->
                report(outcome.message, OperationFailure("forward_mutation_failed", outcome.message))
            is ForwardActivationOutcome.Applied ->
                when (val result = outcome.result) {
                    is ForwardConfigurationResult.Success -> report(successMessage)
                    is ForwardConfigurationResult.Failed -> reportForwardConfigurationFailure(result)
                }
        }
    }

    private fun reportForwardConfigurationFailure(result: ForwardConfigurationResult.Failed) {
        val rollbackIncomplete = result.rollback.any { it is ForwardConfigurationRollbackStageResult.Failure }
        val rollbackSkipped = result.rollback.any { it is ForwardConfigurationRollbackStageResult.Skipped }
        val code =
            when {
                rollbackIncomplete -> "forward_activation_rollback_incomplete"
                rollbackSkipped -> "forward_activation_forwards_changed_again"
                else -> "forward_activation_failed"
            }
        val message =
            when {
                rollbackIncomplete ->
                    "Forward activation failed and could not be fully rolled back ($code): ${result.reason}"
                rollbackSkipped ->
                    "Forward activation failed, but forwards changed again before rollback ran, so the " +
                        "newer forwards were kept ($code): ${result.reason}"
                else -> "Forward activation failed and was rolled back ($code): ${result.reason}"
            }
        report(message, OperationFailure(code, message))
    }

    // FIX7 P0-001-C: admission is the single cross-feature coordinator spanning the whole
    // mutation+activation+rollback sequence, not a local mutex — a concurrent setup save/config
    // import/reset must also be rejected, not just another forward mutation.
    private fun runForwardMutation(transaction: suspend () -> Unit) {
        viewModelScope.launch {
            when (
                val admission =
                    deps.configurationMutationCoordinator.tryRun(ConfigurationOperation.ForwardMutation) {
                        _isBusy.value = true
                        try {
                            transaction()
                        } finally {
                            _isBusy.value = false
                        }
                    }
            ) {
                is ConfigurationAdmission.Busy -> {
                    val message = "Another configuration operation is already in progress: ${admission.active}"
                    report(message, OperationFailure("configuration_operation_busy", message))
                }
                is ConfigurationAdmission.Completed -> Unit
            }
        }
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

    // FIX7 P1-005-B: explicit cancellation-first try/catch, not runCatching — a TCP connect
    // probe, same category the TODO explicitly names for the broker probe (never catch
    // Throwable through runCatching here); the failure message is redacted below.
    fun testLocalPort(forward: ForwardConfig) {
        viewModelScope.launch(ioDispatcher) {
            // Connect to the configured local host (blank falls back to loopback),
            // and report the host actually tested rather than a hardcoded address.
            val host = forward.localHost.trim().ifBlank { "127.0.0.1" }
            val resultMessage =
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(host, forward.localPort), LOCAL_PORT_TEST_TIMEOUT_MS)
                    }
                    "Local port test succeeded for $host:${forward.localPort}"
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    "Local port test failed for $host:${forward.localPort}: ${error.message}"
                }
            report(SensitiveDataRedactor.redactText(resultMessage))
        }
    }
}

/** Outcome of [ForwardsViewModel.applyForwardChange]'s single ioDispatcher-bound section: either
 * the render/native-validate step failed (the coordinator was never called), or it was and the
 * coordinator's own result determines success/failure. */
private sealed interface ForwardActivationOutcome {
    data class RenderFailed(val message: String) : ForwardActivationOutcome

    data class Applied(val result: ForwardConfigurationResult) : ForwardActivationOutcome
}

/** Result of rendering and native-validating a forwards-config candidate (FIX8 P0-005-C): either
 * the validated candidate string (cleanup already succeeded) or a user-facing failure message. */
private sealed interface CandidateRenderResult {
    data class Ready(val candidate: String) : CandidateRenderResult

    data class Failed(val message: String) : CandidateRenderResult
}

/** Result of resolving setup input/preferences and rendering the (not-yet-validated) candidate
 * config text — split out of [renderAndValidateCandidate] so its own two validity gates (corrupt
 * setup draft, missing broker password) don't count against that function's return-count budget. */
private sealed interface CandidatePreparation {
    data class Ready(val candidate: String) : CandidatePreparation

    data class Failed(val message: String) : CandidatePreparation
}

private suspend fun prepareCandidateConfig(
    deps: AppDependencies,
    proposed: List<ForwardConfig>,
): CandidatePreparation {
    val inputResult = deps.configRepository.loadSetupInputResult()
    return if (inputResult.isFailure) {
        CandidatePreparation.Failed("Saved setup is corrupt; re-run setup before changing forwards")
    } else {
        val input = inputResult.getOrThrow()
        val enabledForwards = proposed.filter { it.enabled }
        val prefs = deps.configRepository.preferences.first()
        // FIX7 P0-003-E: reference the existing authoritative broker secret path without
        // rewriting it — forward activation never mutates identity/authorized_keys/broker secret.
        val brokerPasswordPath = resolveBrokerPasswordPath(input, deps.brokerSecretRepository.path)
        val missingBrokerPassword = missingBrokerPasswordFailure(deps, brokerPasswordPath)
        if (missingBrokerPassword != null) {
            CandidatePreparation.Failed(missingBrokerPassword.message ?: "Broker password file missing")
        } else {
            CandidatePreparation.Ready(
                deps.configRepository.renderOfferConfig(
                    input,
                    enabledForwards,
                    prefs.debugLogsEnabled,
                    prefs.androidIceMode,
                    brokerPasswordPath,
                ),
            )
        }
    }
}

// FIX7 P1-004-C: extracted to top level so its throw doesn't count against
// regenerateWithValidatedCandidate's detekt ThrowsCount budget. Identity absent vs.
// present-but-unreadable differ: only the former falls back to identity-less validation; an
// unreadable present identity is a visible failure (P1-001), with a fixed safe message — the
// raw underlying error is attached only as [cause], never surfaced as diagnostic text.
private fun readIdentityBytesOrThrow(deps: AppDependencies): ByteArray? =
    if (deps.identityRepository.hasEncryptedIdentity) {
        try {
            deps.identityRepository.readPrivateIdentityPlaintext()
        } catch (error: Exception) {
            throw IdentityUnreadableException("Identity exists but could not be loaded", error)
        }
    } else {
        null
    }

/**
 * FIX8 P0-005-C: renders a candidate config from the proposed (not-yet-committed) forwards list
 * and native-validates it against an isolated candidate file — no repository is mutated here.
 * The candidate is only returned [CandidateRenderResult.Ready] once cleanup has already
 * succeeded (FIX8 P0-005-A pattern); the caller performs the one authoritative mutation
 * (of both forwards and config) afterward, through [ForwardConfigurationCoordinator].
 */
private suspend fun renderAndValidateCandidate(
    deps: AppDependencies,
    proposed: List<ForwardConfig>,
    deleteCandidateFile: (File) -> Result<Unit>,
): CandidateRenderResult {
    val candidate =
        when (val prepared = prepareCandidateConfig(deps, proposed)) {
            is CandidatePreparation.Failed -> return CandidateRenderResult.Failed(prepared.message)
            is CandidatePreparation.Ready -> prepared.candidate
        }
    // FIX7 P1-001-C: withCandidateFile composes the unique candidate file's cleanup with the
    // block's own outcome, so a cleanup-only failure (validation succeeded, temp file couldn't
    // be deleted) can never be silently discarded — it surfaces as a CandidateCleanupException
    // instead. FIX8 P0-005-A: no mutation happens inside this block at all anymore.
    var identity: ByteArray? = null
    return try {
        withCandidateFile(deps.context.cacheDir, "forwards-config-", deleteCandidateFile) { temp ->
            identity = validateCandidateOrThrow(deps, temp, candidate)
        }
        // Candidate cleanup has already succeeded at this point.
        CandidateRenderResult.Ready(candidate)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        mapCandidateFailure(error)
    } finally {
        // Wipe the plaintext identity buffer regardless of success/failure/cleanup outcome.
        identity?.fill(0)
    }
}

/**
 * Writes [candidate] to [temp] and native-validates it, returning the identity bytes used (if
 * any) so the caller can wipe them. Identity absent vs. present-but-unreadable differ: only the
 * former falls back to identity-less validation; an unreadable present identity is a visible
 * failure (P1-001). A real validation failure must THROW here (not return normally) so
 * [withCandidateFile]'s cleanup composition can tell it apart from a genuine success.
 */
private fun validateCandidateOrThrow(
    deps: AppDependencies,
    temp: File,
    candidate: String,
): ByteArray? {
    val identityBytes = readIdentityBytesOrThrow(deps)
    temp.parentFile?.mkdirs()
    temp.writeText(candidate)
    val result =
        if (identityBytes != null) {
            deps.identityValidation.validateConfigWithIdentity(temp.absolutePath, identityBytes)
        } else {
            deps.identityValidation.validateConfig(temp.absolutePath)
        }
    if (!result.valid) {
        throw ForwardsRegenerationFailedException(result.message ?: "Config validation failed")
    }
    return identityBytes
}

/** Maps a failure out of [renderAndValidateCandidate]'s candidate-file block to a user-facing
 * [CandidateRenderResult.Failed] — kept separate so the distinct failure messages don't count
 * against [renderAndValidateCandidate]'s own cyclomatic-complexity budget. */
private fun mapCandidateFailure(error: Exception): CandidateRenderResult.Failed =
    when (error) {
        is ForwardsRegenerationFailedException ->
            CandidateRenderResult.Failed(error.message ?: "Config validation failed")
        is IdentityUnreadableException ->
            CandidateRenderResult.Failed(error.message ?: "Identity unreadable")
        is CandidateCleanupException ->
            CandidateRenderResult.Failed(
                "Temporary candidate file could not be removed (candidate_cleanup_failed): " +
                    SensitiveDataRedactor.redactText(error.cause?.message ?: "unknown cleanup failure"),
            )
        // FIX7 P1-004-C: redact — an unexpected exception's raw message is not known-safe.
        else ->
            CandidateRenderResult.Failed(
                SensitiveDataRedactor.redactText(error.message ?: "Failed to regenerate config"),
            )
    }

/** Signals a real validation failure (as opposed to a genuine success) out of the
 * [withCandidateFile] block in [renderAndValidateCandidate], so its cleanup composition
 * can tell a real failure apart from a cleanup-only failure on top of an actual success
 * (FIX7 P1-001-C). */
private class ForwardsRegenerationFailedException(message: String) : Exception(message)

/** FIX7 P1-004-C: a stored identity that exists but could not be read/decrypted — carries only
 * a fixed, already-safe message so the underlying read/decrypt error's raw text is never
 * threaded through to a durable OperationFailure/snackbar. The original error is attached as
 * [cause] (not lost) but never read for its message. */
private class IdentityUnreadableException(message: String, cause: Throwable) : Exception(message, cause)

// FIX7 P0-003-E: the managed broker-secret path is expected to already exist when configured;
// a missing file means setup persistence and forward activation have drifted apart, which must
// fail visibly rather than silently render a config referencing a nonexistent file. Top-level
// (not a class member) to keep ForwardsViewModel under detekt's TooManyFunctions threshold.
private fun missingBrokerPasswordFailure(
    deps: AppDependencies,
    brokerPasswordPath: String?,
): ValidationResult? =
    if (brokerPasswordPath == deps.brokerSecretRepository.path && !File(brokerPasswordPath).exists()) {
        ValidationResult(
            false,
            "Broker password is configured but the managed secret file is missing; re-run setup to restore it",
        )
    } else {
        null
    }
