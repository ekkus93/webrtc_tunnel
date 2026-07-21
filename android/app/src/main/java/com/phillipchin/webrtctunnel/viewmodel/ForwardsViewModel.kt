package com.phillipchin.webrtctunnel.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.CandidateCleanupException
import com.phillipchin.webrtctunnel.data.ConfigRepository
import com.phillipchin.webrtctunnel.data.ConfigurationAdmission
import com.phillipchin.webrtctunnel.data.ConfigurationOperation
import com.phillipchin.webrtctunnel.data.ForwardsMutationBlocked
import com.phillipchin.webrtctunnel.data.ForwardsMutationReceipt
import com.phillipchin.webrtctunnel.data.ForwardsRevisionMismatchException
import com.phillipchin.webrtctunnel.data.OperationFailure
import com.phillipchin.webrtctunnel.data.SensitiveDataRedactor
import com.phillipchin.webrtctunnel.data.deleteCandidateFileSafely
import com.phillipchin.webrtctunnel.data.describeForwardsFailure
import com.phillipchin.webrtctunnel.data.resolveBrokerPasswordPath
import com.phillipchin.webrtctunnel.data.withCandidateFile
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.SetupConfigInput
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
            // P1-001: Use receipt-based atomic upsert.
            val receipt: ForwardsMutationReceipt =
                deps.forwardsRepository.upsertWithReceipt(forward).getOrElse { error ->
                    val message = mapMutationError(error)
                    report(message, OperationFailure("forward_mutation_failed", message))
                    return@runForwardMutation
                }

            val sync = withContext(ioDispatcher) { regenerateActiveConfig() }
            if (sync.valid) {
                report("Forward saved")
            } else {
                // Config sync failed — attempt to rollback the mutation via receipt.
                rollbackWithReceipt(receipt, sync.message ?: "Forward update failed")
            }
        }
    }

    fun deleteForward(forwardId: String) {
        runForwardMutation {
            // P1-001: Use receipt-based atomic delete.
            val receipt: ForwardsMutationReceipt =
                deps.forwardsRepository.deleteWithReceipt(forwardId).getOrElse { error ->
                    val message = mapMutationError(error)
                    report(message, OperationFailure("forward_mutation_failed", message))
                    return@runForwardMutation
                }

            val sync = withContext(ioDispatcher) { regenerateActiveConfig() }
            if (sync.valid) {
                report("Forward deleted")
            } else {
                // Config sync failed — attempt to rollback the mutation via receipt.
                rollbackWithReceipt(receipt, sync.message ?: "Forward delete failed")
            }
        }
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

    private suspend fun regenerateActiveConfig(): ValidationResult {
        // A corrupt setup draft must block config regeneration rather than silently rendering
        // a config from reset defaults.
        val input =
            deps.configRepository.loadSetupInputResult().getOrElse {
                return ValidationResult(false, "Saved setup is corrupt; re-run setup before changing forwards")
            }
        val forwards = deps.forwardsRepository.current().filter { it.enabled }
        val prefs = deps.configRepository.preferences.first()
        // FIX7 P0-003-E: reference the existing authoritative broker secret path without
        // rewriting it — forward activation never mutates identity/authorized_keys/broker
        // secret. If setup configured a password but the managed file is missing, fail visibly
        // instead of silently rendering a config that points at a nonexistent file. Folded into
        // one `?:` with the render below (rather than its own early return) to stay under
        // detekt's ReturnCount threshold.
        val brokerPasswordPath = resolveBrokerPasswordPath(input, deps.brokerSecretRepository.path)
        // Combined into one `?:` (rather than its own early return) so the function keeps
        // exactly two `return` statements total for detekt's ReturnCount threshold: this early
        // corrupt-input check above, and this one. The success path is a top-level function
        // (rather than an inline `run { ... }` lambda) to keep nesting under detekt's
        // NestedBlockDepth threshold.
        return missingBrokerPasswordFailure(deps, brokerPasswordPath)
            ?: regenerateWithValidatedCandidate(
                deps,
                RegenerationInputs(input, forwards, prefs, brokerPasswordPath),
                deleteCandidateFile,
            )
    }
}

// FIX7 P1-001-C: bundles regenerateWithValidatedCandidate's render inputs so adding
// deleteCandidateFile (the injectable cleanup seam) doesn't push the function over detekt's
// LongParameterList threshold.
private data class RegenerationInputs(
    val input: SetupConfigInput,
    val forwards: List<ForwardConfig>,
    val prefs: com.phillipchin.webrtctunnel.model.AndroidAppPreferences,
    val brokerPasswordPath: String?,
)

// FIX7 P1-004-C: extracted to top level so its throw doesn't count against
// regenerateWithValidatedCandidate's detekt ThrowsCount budget. Identity absent vs.
// present-but-unreadable differ: only the former falls back to identity-less validation; an
// unreadable present identity is a visible failure (P1-001), with a fixed safe message — the
// raw underlying error is attached only as [cause], never surfaced as diagnostic text.
private fun readIdentityBytesOrThrow(deps: AppDependencies): ByteArray? =
    if (deps.identityRepository.hasEncryptedIdentity()) {
        try {
            deps.identityRepository.readPrivateIdentityPlaintext()
        } catch (error: Exception) {
            throw IdentityUnreadableException("Identity exists but could not be loaded", error)
        }
    } else {
        null
    }

// FIX7 P0-003-E: the actual render+validate+commit path, extracted to top level (rather than an
// inline `run { ... }` lambda inside regenerateActiveConfig) to keep that function's nesting
// depth under detekt's NestedBlockDepth threshold.
private suspend fun regenerateWithValidatedCandidate(
    deps: AppDependencies,
    inputs: RegenerationInputs,
    deleteCandidateFile: (File) -> Result<Unit>,
): ValidationResult {
    val candidate =
        deps.configRepository.renderOfferConfig(
            inputs.input,
            inputs.forwards,
            inputs.prefs.debugLogsEnabled,
            inputs.prefs.androidIceMode,
            inputs.brokerPasswordPath,
        )
    // FIX7 P1-001-C: withCandidateFile composes the unique candidate file's cleanup with the
    // block's own outcome (P1-005/FIX6 INV-012 unique-per-validation naming preserved), so a
    // cleanup-only failure (write succeeded, temp file couldn't be deleted) can never be
    // silently discarded — it surfaces as a CandidateCleanupException instead. Treated the same
    // as any other post-write failure below: the already-committed config write is rolled back
    // via the receipt mechanism, since a leftover secret-bearing candidate file is serious enough
    // that "saved" must not be reported without a guarantee it was actually cleaned up.
    var identity: ByteArray? = null
    // FIX6 P0-005: explicit try/catch (not runCatching) — it wraps the suspend
    // writeConfigAtomically, so a cancellation must propagate, not become an invalid
    // result.
    return try {
        withCandidateFile(deps.context.cacheDir, "forwards-config-", deleteCandidateFile) { temp ->
            // Identity absent vs. present-but-unreadable differ: only the former falls back
            // to identity-less validation; an unreadable present identity is a visible
            // failure (P1-001).
            val identityBytes = readIdentityBytesOrThrow(deps)
            identity = identityBytes
            temp.parentFile?.mkdirs()
            temp.writeText(candidate)
            val result =
                if (identityBytes != null) {
                    deps.identityValidation.validateConfigWithIdentity(temp.absolutePath, identityBytes)
                } else {
                    deps.identityValidation.validateConfig(temp.absolutePath)
                }
            val committed = commitRegeneratedForwardsConfig(deps.configRepository, candidate, result)
            // FIX7 P1-001-C: a real validation/write failure must THROW here (not return
            // normally) so withCandidateFile's cleanup composition can tell it apart from a
            // genuine success — otherwise a cleanup failure on top of a real failure would
            // silently replace the real failure's message with a generic cleanup one instead
            // of preserving it (P1-001-E).
            if (!committed.valid) {
                throw ForwardsRegenerationFailedException(committed.message ?: "Failed to regenerate config")
            }
            committed
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (regenerationFailure: ForwardsRegenerationFailedException) {
        ValidationResult(false, regenerationFailure.message)
    } catch (identityUnreadable: IdentityUnreadableException) {
        ValidationResult(false, identityUnreadable.message)
    } catch (cleanupFailure: CandidateCleanupException) {
        ValidationResult(
            false,
            "Config saved but the temporary candidate file could not be removed " +
                "(candidate_cleanup_failed): " +
                SensitiveDataRedactor.redactText(cleanupFailure.cause?.message ?: "unknown cleanup failure"),
        )
    } catch (error: Exception) {
        // FIX7 P1-004-C: redact — an unexpected exception's raw message is not known-safe.
        ValidationResult(false, SensitiveDataRedactor.redactText(error.message ?: "Failed to regenerate config"))
    } finally {
        // Wipe the plaintext identity buffer regardless of success/failure/cleanup outcome.
        identity?.fill(0)
    }
}

/** Signals a real validation/write failure (as opposed to a genuine success) out of the
 * [withCandidateFile] block in [regenerateWithValidatedCandidate], so its cleanup composition
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

// FIX6 P0-001-D: a failed config commit invalidates the result so the caller rolls the forward
// mutation back, rather than reporting a false "saved". Top-level (not a class member) to keep
// regenerateActiveConfig's own length under the detekt LongMethod threshold.
private suspend fun commitRegeneratedForwardsConfig(
    configRepository: ConfigRepository,
    candidate: String,
    validation: ValidationResult,
): ValidationResult =
    if (!validation.valid) {
        validation
    } else {
        configRepository.writeConfigAtomically(candidate).fold(
            onSuccess = { validation },
            onFailure = { error ->
                ValidationResult(
                    valid = false,
                    message = SensitiveDataRedactor.redactText(error.message ?: "Failed to write active config"),
                )
            },
        )
    }
