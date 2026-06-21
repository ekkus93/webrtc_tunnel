package com.phillipchin.webrtctunnel.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.SetupConfigInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val MAX_PORT = 65535

enum class SetupStep {
    Mode,
    Identity,
    Broker,
    Peer,
    Forwards,
    NetworkPolicy,
    Review,
}

data class SetupWizardState(
    val currentStep: SetupStep = SetupStep.Mode,
    val input: SetupConfigInput = SetupConfigInput(),
    val importIdentityPath: String = "",
    val importPublicIdentity: String = "",
    val localPublicIdentity: String = "",
    val identityPeerId: String? = null,
    val remoteIdentityPeerId: String? = null,
    val brokerTestMessage: String? = null,
    val advancedExpanded: Boolean = false,
    val canAdvance: Boolean = false,
    val errorMessage: String? = null,
    val saveResult: String? = null,
    val isBusy: Boolean = false,
)

class SetupViewModel(
    private val deps: AppDependencies,
    private val loadPreferences: suspend () -> AndroidAppPreferences = { deps.configRepository.preferences.first() },
    private val persistPreferences: suspend (
        AndroidAppPreferences,
    ) -> Unit = { deps.configRepository.savePreferences(it) },
) : ViewModel() {
    private val _state = MutableStateFlow(SetupWizardState())
    val state: StateFlow<SetupWizardState> = _state.asStateFlow()
    private val _forwards = MutableStateFlow(emptyList<ForwardConfig>())
    val forwards: StateFlow<List<ForwardConfig>> = _forwards.asStateFlow()
    private val steps = SetupStep.entries
    val networkStatus =
        combine(deps.networkPolicyManager.status, state) { _, wizardState ->
            deps.networkPolicyManager.evaluateWithPolicy(wizardState.input.allowMetered)
        }
    val preferences = deps.configRepository.preferences

    private val stateAccess =
        WizardStateAccess(
            state = { _state.value },
            forwards = { _forwards.value },
            applyState = ::applyState,
            setForwards = { _forwards.value = it },
        )

    val save =
        SetupSaveController(
            deps = deps,
            scope = viewModelScope,
            loadPreferences = loadPreferences,
            persistPreferences = persistPreferences,
            access = stateAccess,
        )

    val identity = SetupIdentityController(deps, stateAccess, viewModelScope)

    val forwardsEditor = SetupForwardsController(deps, stateAccess, viewModelScope)

    init {
        loadStoredSetupInput(deps, stateAccess)
        identity.loadStoredIdentity()
        forwardsEditor.refreshForwards()
    }

    private fun applyState(newState: SetupWizardState) {
        _state.value = newState.copy(canAdvance = canAdvance(deps, newState, _forwards.value))
    }

    private fun updateState(transform: (SetupWizardState) -> SetupWizardState) {
        val updated = transform(_state.value)
        _state.value = updated.copy(canAdvance = canAdvance(deps, updated, _forwards.value))
    }

    fun setInput(update: SetupConfigInput) {
        updateState { current ->
            current.copy(
                input = update,
                errorMessage = null,
                saveResult = null,
                brokerTestMessage = null,
            )
        }
    }

    fun setImportIdentityPath(path: String) {
        updateState { current -> current.copy(importIdentityPath = path, errorMessage = null, saveResult = null) }
    }

    fun setImportPublicIdentity(value: String) {
        updateState { current ->
            current.copy(
                importPublicIdentity = value,
                remoteIdentityPeerId = null,
                errorMessage = null,
            )
        }
    }

    fun setAdvancedExpanded(expanded: Boolean) {
        updateState { current -> current.copy(advancedExpanded = expanded) }
    }

    fun goBack() {
        val current = _state.value.currentStep
        val index = steps.indexOf(current)
        if (index > 0) {
            _state.value =
                _state.value
                    .copy(currentStep = steps[index - 1], errorMessage = null)
                    .withCanAdvance(deps, _forwards.value)
        }
    }

    fun cancel() {
        _state.value = SetupWizardState()
        forwardsEditor.refreshForwards()
    }

    fun goNext() {
        if (_state.value.isBusy) return
        viewModelScope.launch {
            val current = _state.value
            _state.value = current.copy(isBusy = true)
            try {
                // Step validation can call native code; keep it off the main thread.
                val validationError =
                    withContext(deps.dispatchers.io) { validateStep(deps, current.currentStep, current) }
                if (validationError != null) {
                    _state.value =
                        current.copy(errorMessage = validationError).withCanAdvance(deps, _forwards.value)
                    return@launch
                }
                val index = steps.indexOf(current.currentStep)
                if (index < steps.lastIndex) {
                    _state.value =
                        current
                            .copy(currentStep = steps[index + 1], errorMessage = null)
                            .withCanAdvance(deps, _forwards.value)
                }
            } finally {
                _state.value = _state.value.copy(isBusy = false)
            }
        }
    }

    fun canAdvanceFromCurrentStep(): Boolean {
        return _state.value.canAdvance
    }
}

private fun SetupWizardState.withCanAdvance(
    deps: AppDependencies,
    forwards: List<ForwardConfig>,
): SetupWizardState = copy(canAdvance = canAdvance(deps, this, forwards))

private fun canAdvance(
    deps: AppDependencies,
    state: SetupWizardState,
    forwards: List<ForwardConfig>,
): Boolean =
    when (state.currentStep) {
        SetupStep.Mode -> true
        SetupStep.Identity -> state.localPublicIdentity.isNotBlank() || state.importIdentityPath.isNotBlank()
        SetupStep.Broker -> brokerInputReady(state.input)
        SetupStep.Peer -> peerInputReady(state)
        SetupStep.Forwards -> forwardsReady(deps, forwards)
        SetupStep.NetworkPolicy -> true
        SetupStep.Review -> brokerInputReady(state.input) && peerInputReady(state) && forwardsReady(deps, forwards)
    }

private fun brokerInputReady(input: SetupConfigInput): Boolean =
    input.brokerHost.isNotBlank() && input.brokerPort in 1..MAX_PORT

private fun peerInputReady(state: SetupWizardState): Boolean =
    state.input.remotePeerId.isNotBlank() && state.importPublicIdentity.isNotBlank()

private fun forwardsReady(
    deps: AppDependencies,
    forwards: List<ForwardConfig>,
): Boolean =
    // Mirror validateForwardsStep: the step is only advanceable with at least one *enabled*
    // forward, so the Next button reflects the same rule that save-time validation enforces
    // (otherwise the button enables but advancing is rejected).
    forwards.isNotEmpty() &&
        forwards.any { it.enabled } &&
        deps.forwardsStore.validateForwards(forwards) == null

private fun loadStoredSetupInput(
    deps: AppDependencies,
    access: WizardStateAccess,
) {
    // A corrupt draft yields null here (not reset defaults), so the wizard simply does not
    // prefill rather than silently overwriting the user's saved values with blanks.
    val saved = deps.configRepository.loadSetupInputResult().getOrNull() ?: return
    if (saved.brokerHost.isNotBlank() || saved.remotePeerId.isNotBlank()) {
        access.applyState(access.state().copy(input = saved))
    }
}
