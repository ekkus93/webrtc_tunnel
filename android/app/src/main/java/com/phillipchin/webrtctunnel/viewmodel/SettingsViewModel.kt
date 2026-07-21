package com.phillipchin.webrtctunnel.viewmodel

import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.ConfigurationAdmission
import com.phillipchin.webrtctunnel.data.ConfigurationOperation
import com.phillipchin.webrtctunnel.data.OperationFailure
import com.phillipchin.webrtctunnel.data.ResetResult
import com.phillipchin.webrtctunnel.data.ResetRollbackException
import com.phillipchin.webrtctunnel.data.RollbackStageResult
import com.phillipchin.webrtctunnel.data.SensitiveDataRedactor
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
private data class StatusDiagnosticsError(
    @SerialName("status_json_error") val statusJsonError: String,
)

// Extracted so a test can force the statusJson() error path with a specific message
// (quotes/backslashes/newlines/secrets) without depending on the status object
// actually failing to serialize.
internal fun statusDiagnosticsErrorJson(message: String?): String =
    Json.encodeToString(
        StatusDiagnosticsError(
            statusJsonError = SensitiveDataRedactor.redactText(message ?: "unknown status serialization failure"),
        ),
    )

data class SettingsUiState(
    val publicIdentity: String? = null,
    val publicIdentityLoadError: String? = null,
    val configValidationMessage: String? = null,
    val configValid: Boolean? = null,
    val isValidatingConfig: Boolean = false,
    // P1-008: the last failed mutating operation, kept in state so it survives without a
    // snackbar collector (e.g. across recreation). Cleared on the next successful operation.
    val lastOperationFailure: OperationFailure? = null,
)

class SettingsViewModel(
    private val deps: AppDependencies,
    private val loadPublicIdentity: suspend () -> String = {
        withContext(deps.dispatchers.io) { deps.identityRepository.readPublicIdentity() }
    },
) : ViewModel() {
    val preferences = deps.configRepository.preferences
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        refreshPublicIdentity()
    }

    fun validateConfig() {
        if (_uiState.value.isValidatingConfig) return
        viewModelScope.launch {
            _uiState.value =
                _uiState.value.copy(isValidatingConfig = true, configValidationMessage = null, configValid = null)
            // FIX7 P1-005-B: explicit cancellation-first try/catch, not runCatching — this
            // calls the native validation bridge.
            val result =
                withContext(deps.dispatchers.io) {
                    try {
                        Result.success(deps.identityValidation.validateConfig(deps.configRepository.configPath))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Result.failure(error)
                    }
                }
            val valid = result.map { it.valid }.getOrDefault(false)
            val message =
                result.fold(
                    onSuccess = {
                        if (it.valid) "Configuration is valid." else (it.message ?: "Configuration is invalid.")
                    },
                    onFailure = { it.message ?: "Validation failed." },
                )
            _uiState.value =
                _uiState.value.copy(
                    isValidatingConfig = false,
                    configValid = valid,
                    configValidationMessage = SensitiveDataRedactor.redactText(message),
                )
        }
    }

    // P1-016: Surface preference-write failures.
    fun savePreferences(updated: AndroidAppPreferences) {
        viewModelScope.launch {
            deps.configRepository.savePreferences(updated).fold(
                onSuccess = {
                    clearOperationFailure()
                    deps.snackbar.show("Preferences saved")
                },
                onFailure = { error ->
                    val message =
                        "Preferences save failed: ${SensitiveDataRedactor.redactText(error.message ?: "unknown error")}"
                    publishOperationFailure("preferences_save_failed", message)
                },
            )
        }
    }

    // P1-008: record a durable, redacted failure in state and mirror it to the snackbar. The
    // snackbar is convenience only; the state copy survives a missing/late collector.
    private fun publishOperationFailure(
        code: String,
        message: String,
    ) {
        _uiState.value = _uiState.value.copy(lastOperationFailure = OperationFailure(code, message))
        deps.snackbar.show(message)
    }

    private fun clearOperationFailure() {
        if (_uiState.value.lastOperationFailure != null) {
            _uiState.value = _uiState.value.copy(lastOperationFailure = null)
        }
    }

    // FIX7 P1-005-B: explicit cancellation-first try/catch, not runCatching — loadPublicIdentity
    // is a suspend call; runCatching's Throwable-catching could silently convert a real
    // coroutine cancellation into an ordinary load failure.
    fun refreshPublicIdentity() {
        viewModelScope.launch {
            try {
                val publicIdentity = loadPublicIdentity().ifBlank { null }
                _uiState.value =
                    _uiState.value.copy(
                        publicIdentity = publicIdentity,
                        publicIdentityLoadError = null,
                    )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.value =
                    _uiState.value.copy(
                        publicIdentity = null,
                        publicIdentityLoadError =
                            SensitiveDataRedactor.redactText(
                                error.message ?: "Unable to load local public identity",
                            ),
                    )
            }
        }
    }

    /** Real status JSON on success, or a JSON object carrying a redacted error message —
     * never a bare `"{}"`, which would be indistinguishable from a legitimately idle/empty
     * status. Used for both the "copy status JSON" clipboard action and diagnostics share.
     *
     * FIX7 P1-005-B: safe as runCatching — a pure in-memory encode of an already-held
     * StateFlow value (no native call, no file/suspend involvement), so it cannot swallow
     * a fatal Error or a laundered CancellationException that matters here. */
    fun statusJson(): String =
        runCatching {
            Json.encodeToString(SensitiveDataRedactor.redactStatus(deps.tunnelRepository.status.value))
        }.getOrElse { error -> statusDiagnosticsErrorJson(error.message) }

    /** Redacted config file contents, or an explicit marker distinguishing "no config file
     * yet" (expected before setup completes) from "config file present but unreadable/failed
     * to redact" — never a bare empty string for either case. Used for both the "copy redacted
     * config" clipboard action and diagnostics share. */
    suspend fun redactedConfig(): String =
        withContext(deps.dispatchers.io) {
            val file = File(deps.configRepository.configPath)
            if (!file.exists()) return@withContext "(no config file present)"
            // FIX7 P1-005-B: safe as runCatching — a synchronous file read + redact with no
            // suspend calls inside the lambda itself (the outer withContext already handled
            // dispatching), no native call, and read-only.
            runCatching { SensitiveDataRedactor.redactText(file.readText()) }
                .getOrElse { error ->
                    "(config read/redaction failed: " +
                        SensitiveDataRedactor.redactText(error.message ?: "unknown error") +
                        ")"
                }
        }

    // P2-003: Uses TransactionalResetCoordinator for atomic multi-file reset.
    // FIX7 P0-001-C: admission is the single cross-feature coordinator around the whole reset
    // transaction, not just a per-screen guard — a concurrent setup save/import/forward
    // mutation must also be rejected while a reset is in flight, and vice versa.
    fun resetConfiguration() {
        viewModelScope.launch {
            // FIX7 P0-005-C/D: a cancelled reset reports no normal success/failure — except the
            // one required diagnostic when TransactionalResetCoordinator's own cancellation-path
            // rollback (attached as ResetRollbackExceptions suppressed on the cancellation)
            // could not fully restore an earlier stage.
            try {
                when (
                    val admission =
                        deps.configurationMutationCoordinator.tryRun(ConfigurationOperation.ConfigurationReset) {
                            withContext(deps.dispatchers.io) { deps.transactionalResetCoordinator.resetConfiguration() }
                        }
                ) {
                    is ConfigurationAdmission.Busy -> {
                        val message = "Another configuration operation is already in progress: ${admission.active}"
                        publishOperationFailure("configuration_operation_busy", message)
                    }
                    is ConfigurationAdmission.Completed -> handleResetResult(admission.value)
                }
            } catch (cancelled: CancellationException) {
                resetCancelledRollbackIncompleteMessage(cancelled)?.let { message ->
                    publishOperationFailure("reset_cancelled_rollback_incomplete", message)
                }
                throw cancelled
            }
        }
    }

    private fun handleResetResult(result: ResetResult) {
        when (result) {
            is ResetResult.Success -> {
                clearOperationFailure()
                deps.snackbar.show("Configuration reset")
            }
            is ResetResult.Failed -> {
                val rollbackSummary =
                    result.rollback.joinToString("; ") {
                        when (it) {
                            is RollbackStageResult.Success ->
                                "${it.stage.name}: rollback_ok"
                            is RollbackStageResult.Failure ->
                                "${it.stage.name}: ${it.reason}"
                        }
                    }
                // P1-002-D: partial rollback must be visibly distinct from a cleanly
                // rolled-back reset failure, so the user knows persistent state may be
                // inconsistent rather than restored.
                val code = resetFailureVisibleCode(result)
                val summary =
                    "[$code] Reset failed at ${result.failedStage.name}: ${result.cause}\n" +
                        "Rollback: $rollbackSummary"
                Log.e("SettingsViewModel", summary)
                // P1-008: durable, so the reset failure survives without a snackbar collector.
                publishOperationFailure(code, summary)
            }
        }
    }

    suspend fun diagnosticsShareIntent(): Intent {
        val payload =
            buildString {
                appendLine("status_json=${statusJson()}")
                appendLine("config_redacted=${redactedConfig()}")
            }
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "WebRTC Tunnel diagnostics (redacted)")
            putExtra(Intent.EXTRA_TEXT, payload)
        }
    }
}

/**
 * P1-002-D: the user-visible code for a failed reset. `reset_rollback_incomplete` when any
 * rollback stage failed (persistent state may be inconsistent), otherwise `reset_failed` (the
 * prior state was cleanly restored). Top-level so it is testable without the ViewModel harness.
 */
internal fun resetFailureVisibleCode(result: ResetResult.Failed): String =
    if (result.rollback.any { it is RollbackStageResult.Failure }) {
        "reset_rollback_incomplete"
    } else {
        "reset_failed"
    }

/**
 * FIX7 P0-005-D: the one required cancellation diagnostic — null when the cancelled reset's own
 * rollback fully restored every earlier stage (no message to show), or a durable message when
 * [TransactionalResetCoordinator]'s cancellation-path rollback (attached as [ResetRollbackException]s
 * suppressed on [cancelled]) could not. Top-level (not a [SettingsViewModel] member) to keep that
 * class under detekt's TooManyFunctions threshold; also testable without the ViewModel harness.
 */
internal fun resetCancelledRollbackIncompleteMessage(cancelled: CancellationException): String? {
    val incompleteStages = cancelled.suppressedExceptions.filterIsInstance<ResetRollbackException>().map { it.stage }
    if (incompleteStages.isEmpty()) {
        return null
    }
    return "Reset was cancelled and could not be fully rolled back: $incompleteStages"
}
