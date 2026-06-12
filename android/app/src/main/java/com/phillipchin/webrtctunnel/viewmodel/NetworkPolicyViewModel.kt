package com.phillipchin.webrtctunnel.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phillipchin.webrtctunnel.data.AppDependencies
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class NetworkPolicyViewModel(private val deps: AppDependencies) : ViewModel() {
    val networkStatus =
        combine(deps.networkPolicyManager.status, deps.configRepository.preferences) { _, prefs ->
            deps.networkPolicyManager.evaluateWithPolicy(prefs.allowMetered)
        }
    val preferences = deps.configRepository.preferences

    fun savePreferences(updated: com.phillipchin.webrtctunnel.model.AndroidAppPreferences) {
        viewModelScope.launch { deps.configRepository.savePreferences(updated) }
    }
}
