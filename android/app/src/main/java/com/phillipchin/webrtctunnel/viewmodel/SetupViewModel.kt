package com.phillipchin.webrtctunnel.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.ForwardsLoadState
import com.phillipchin.webrtctunnel.data.SensitiveDataRedactor
import com.phillipchin.webrtctunnel.data.loadSetupInputResult
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtunnel.model.SetupConfigInput
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

/**
 * Whether the setup wizard's baseline draft (saved setup input, stored identity, forwards) has
 * finished loading (FIX8 P1-001-B) — mirrors [ForwardsLoadState]'s shape. `Next`/final save must
 * block while `Initializing` or `Failed`: a corrupt saved draft must never be silently treated as
 * "no saved draft" and let through with defaults.
 */
sealed interface SetupLoadState {
    data object Initializing : SetupLoadState

    data object Ready : SetupLoadState

    data class Failed(val message: String) : SetupLoadState
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
    ) -> Result<Unit> = { deps.configRepository.savePreferences(it) },
) : ViewModel() {
    private val _state = MutableStateFlow(SetupWizardState())
    val state: StateFlow<SetupWizardState> = _state.asStateFlow()
    private val _forwards = MutableStateFlow(emptyList<ForwardConfig>())
    val forwards: StateFlow<List<ForwardConfig>> = _forwards.asStateFlow()
    private val steps = SetupStep.entries
    val networkStatus =
        combine(deps.networkPolicyManager.status, state) { _, wizardState ->
            // FIX7 P1-004-B: same defensive wrapper as NetworkPolicyViewModel — an
            // evaluateWithPolicy exception must never terminate this combine's flow.
            evaluateNetworkPolicySafely { deps.networkPolicyManager.evaluateWithPolicy(wizardState.input.allowMetered) }
        }
    val preferences = deps.configRepository.preferences

    // FIX8 P1-001-A: the one shared setup-local admission gate every controller's asynchronous
    // action routes through — `internal` (not private) so tests can assert admission/rejection
    // directly via `activeOperationForTest()`.
    internal val operations = SetupOperationCoordinator()

    // FIX8 P1-001-B: Initializing until the setup-input/identity/forwards baselines have all been
    // read; Next/final save block while this is not Ready (see canAdvance/SetupSaveController).
    private val _loadState = MutableStateFlow<SetupLoadState>(SetupLoadState.Initializing)
    val loadState: StateFlow<SetupLoadState> = _loadState.asStateFlow()

    // internal (not private): same-module tests exercise SetupOperationCoordinator directly
    // through this (e.g. to occupy admission deterministically) rather than racing real
    // dispatcher timing.
    internal val stateAccess =
        WizardStateAccess(
            state = { _state.value },
            forwards = { _forwards.value },
            applyState = ::applyState,
            setForwards = { _forwards.value = it },
            operations = operations,
            loadState = { _loadState.value },
        )

    // FIX8 P0-001-A/B: the wizard's generated/imported private identity lives here, never in
    // authoritative storage or observable UI state, until the final setup transaction. `internal`
    // (not `private`) only so same-module tests can assert its byte/wiping lifecycle (spec §8).
    internal val identityDraft = SetupIdentityDraft()

    internal val save =
        SetupSaveController(
            deps = deps,
            scope = viewModelScope,
            loadPreferences = loadPreferences,
            persistPreferences = persistPreferences,
            access = stateAccess,
            identityDraft = identityDraft,
        )

    internal val identity = SetupIdentityController(deps, stateAccess, viewModelScope, identityDraft)

    internal val forwardsEditor = SetupForwardsController(deps, stateAccess, viewModelScope)

    init {
        // FIX8 P1-001-B: no synchronous file I/O here — the setup-input read/decode (previously a
        // direct blocking call in this constructor) now happens inside a coroutine, IO-dispatched,
        // alongside the already-async identity/forwards baselines, under BaselineLoad admission so
        // nothing else can run concurrently with it. loadState publishes Ready only once all three
        // baselines are coherent; a corrupt/unreadable setup-input file is a durable Failed state
        // (setup_draft_load_failed), never silently treated as "no saved draft".
        viewModelScope.launch {
            operations.runGuarded(stateAccess, SetupDraftOperation.BaselineLoad) {
                loadSetupWizardBaseline(deps, stateAccess, identity, forwardsEditor, _loadState)
            }
        }
    }

    // Stamps `canAdvance` from the incoming state; the four setters below feed their `.copy(...)`
    // straight in (no separate `updateState` helper, which would count against TooManyFunctions).
    // FIX8 P1-001-A: `isBusy` is always stamped from the coordinator's actual admission ownership
    // here — the single write path every controller uses — never independently toggled.
    private fun applyState(newState: SetupWizardState) {
        _state.value =
            newState.copy(isBusy = operations.isBusy, canAdvance = canAdvance(deps, newState, _forwards.value))
    }

    fun setInput(update: SetupConfigInput) {
        applyState(
            _state.value.copy(
                input = update,
                errorMessage = null,
                saveResult = null,
                brokerTestMessage = null,
            ),
        )
    }

    fun setImportIdentityPath(path: String) {
        applyState(_state.value.copy(importIdentityPath = path, errorMessage = null, saveResult = null))
    }

    fun setImportPublicIdentity(value: String) {
        applyState(
            _state.value.copy(
                importPublicIdentity = value,
                remoteIdentityPeerId = null,
                errorMessage = null,
            ),
        )
    }

    fun setAdvancedExpanded(expanded: Boolean) {
        applyState(_state.value.copy(advancedExpanded = expanded))
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
        // FIX8 P0-001-A: abandoning setup wipes the draft private bytes and discards the
        // draft forwards, then asynchronously reloads the untouched authoritative baseline.
        // FIX8 P1-001-A: invalidate() marks any still-in-flight setup operation stale before the
        // reset, so its eventual completion cannot overwrite this freshly-reset state — cancel
        // must always be able to interrupt an in-flight operation, so it does not itself go
        // through admission (it would otherwise be rejected as busy).
        operations.invalidate()
        identityDraft.clear()
        applyState(SetupWizardState())
        forwardsEditor.refreshForwards()
    }

    override fun onCleared() {
        // FIX8 P0-001-A: ViewModel teardown must not leave draft private bytes in memory.
        identityDraft.clear()
        super.onCleared()
    }

    fun goNext() {
        // FIX8 P1-001-B: block navigation while the setup baseline has not finished loading —
        // a corrupt/unreadable saved draft must not let the user advance past defaults it never
        // actually confirmed.
        if (_loadState.value !is SetupLoadState.Ready) {
            applyState(_state.value.copy(errorMessage = setupLoadNotReadyMessage(_loadState.value)))
            return
        }
        if (operations.isBusy) return
        viewModelScope.launch {
            operations.runGuarded(stateAccess, SetupDraftOperation.ValidationNavigation) { token ->
                val current = _state.value
                // Step validation can call native code; keep it off the main thread.
                val validationError =
                    withContext(deps.dispatchers.io) { validateStep(deps, current.currentStep, current) }
                // FIX9 P0-001: a cancel() during this suspend call must not resurrect stale
                // step/error state over whatever the reset already published.
                if (!token.isFresh()) return@runGuarded
                if (validationError != null) {
                    token.publishIfFresh { applyState(current.copy(errorMessage = validationError)) }
                    return@runGuarded
                }
                val index = steps.indexOf(current.currentStep)
                if (index < steps.lastIndex) {
                    token.publishIfFresh {
                        applyState(current.copy(currentStep = steps[index + 1], errorMessage = null))
                    }
                }
            }
        }
    }

    fun canAdvanceFromCurrentStep(): Boolean {
        return _state.value.canAdvance
    }
}
