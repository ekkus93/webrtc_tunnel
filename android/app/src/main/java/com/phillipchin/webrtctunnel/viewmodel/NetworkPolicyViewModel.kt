package com.phillipchin.webrtctunnel.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.OperationFailure
import com.phillipchin.webrtctunnel.data.SensitiveDataRedactor
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.NetworkPolicyStatus
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.network.NetworkPolicyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private const val TAG = "NetworkPolicyViewModel"

data class NetworkPolicyUiState(
    // FIX7 P1-004-A: the last failed mutating operation, kept in state so it survives
    // without a snackbar collector (e.g. across recreation). Cleared on the next success.
    val lastOperationFailure: OperationFailure? = null,
)

/**
 * FIX7 P1-004-B: runs [evaluate] and, if it throws, fails closed to the canonical blocked
 * Unknown status rather than letting the exception propagate and terminate whatever Flow
 * this runs inside (e.g. `combine`, which cancels its collection on an uncaught throw).
 * Top-level so it is directly testable without constructing a NetworkPolicyManager fake.
 */
internal fun evaluateNetworkPolicySafely(evaluate: () -> NetworkPolicyStatus): NetworkPolicyStatus =
    try {
        evaluate()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.e(
            TAG,
            "Network policy evaluation failed: " +
                SensitiveDataRedactor.redactText(error.message ?: "unknown evaluation error"),
        )
        NetworkPolicyManager.evaluate(NetworkType.Unknown to false, allowMetered = false)
    }

class NetworkPolicyViewModel(private val deps: AppDependencies) : ViewModel() {
    private val _uiState = MutableStateFlow(NetworkPolicyUiState())
    val uiState: StateFlow<NetworkPolicyUiState> = _uiState.asStateFlow()

    val networkStatus =
        combine(deps.networkPolicyManager.status, deps.configRepository.preferences) { _, prefs ->
            evaluateNetworkPolicySafely { deps.networkPolicyManager.evaluateWithPolicy(prefs.allowMetered) }
        }
    val preferences = deps.configRepository.preferences

    fun savePreferences(updated: AndroidAppPreferences) {
        viewModelScope.launch {
            deps.configRepository.savePreferences(updated).fold(
                onSuccess = {
                    clearOperationFailure()
                    deps.snackbar.show("Network policy updated")
                },
                onFailure = { error ->
                    val message =
                        "Failed to update network policy: " +
                            SensitiveDataRedactor.redactText(error.message ?: "unknown error")
                    publishOperationFailure("network_preference_save_failed", message)
                },
            )
        }
    }

    // FIX7 P1-004-A: record a durable, redacted failure in state and mirror it to the
    // snackbar. The snackbar is convenience only; the state copy survives a missing collector.
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
}
