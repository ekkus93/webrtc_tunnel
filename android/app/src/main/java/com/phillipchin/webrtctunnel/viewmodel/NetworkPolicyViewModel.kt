package com.phillipchin.webrtctunnel.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.SensitiveDataRedactor
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class NetworkPolicyViewModel(private val deps: AppDependencies) : ViewModel() {
    val networkStatus =
        combine(deps.networkPolicyManager.status, deps.configRepository.preferences) { _, prefs ->
            deps.networkPolicyManager.evaluateWithPolicy(prefs.allowMetered)
        }
    val preferences = deps.configRepository.preferences

    fun savePreferences(updated: com.phillipchin.webrtctunnel.model.AndroidAppPreferences) {
        viewModelScope.launch {
            val result = deps.configRepository.savePreferences(updated)
            result.fold(
                onSuccess = {
                    deps.snackbar.show("Network policy updated")
                },
                onFailure = { error ->
                    deps.snackbar.show(
                        SensitiveDataRedactor.redactText(
                            error.message
                                ?: "Failed to update network policy",
                        ),
                    )
                },
            )
        }
    }
}
