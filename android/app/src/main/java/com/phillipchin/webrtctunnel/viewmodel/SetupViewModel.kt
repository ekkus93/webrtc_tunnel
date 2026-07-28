package com.phillipchin.webrtctunnel.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.ForwardsLoadState
import com.phillipchin.webrtctunnel.data.SensitiveDataRedactor
import com.phillipchin.webrtctunnel.data.loadSetupInputResult
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

internal fun setupLoadNotReadyMessage(state: SetupLoadState): String =
    when (state) {
        SetupLoadState.Initializing -> "Setup baseline is still loading (setup_draft_operation_busy)"
        is SetupLoadState.Failed -> state.message
        SetupLoadState.Ready -> "Setup baseline is ready"
    }

/**
 * FIX8 P1-001-B: reads the setup-input file (IO-dispatched, off the main thread — never
 * synchronously in the ViewModel constructor) and awaits the already-async identity/forwards
 * baselines, publishing [SetupLoadState.Ready] only once all three are coherent, or a durable
 * [SetupLoadState.Failed] (`setup_draft_load_failed`) if any could not be read. A corrupt/
 * unreadable setup-input file is never silently treated as "no saved draft" — the file itself is
 * left untouched, and no content from it is included in the error.
 */
private suspend fun loadSetupWizardBaseline(
    deps: AppDependencies,
    access: WizardStateAccess,
    identity: SetupIdentityController,
    forwardsEditor: SetupForwardsController,
    loadState: MutableStateFlow<SetupLoadState>,
) {
    val setupInputResult = withContext(deps.dispatchers.io) { deps.configRepository.loadSetupInputResult() }
    identity.loadStoredIdentityBaseline()
    forwardsEditor.refreshForwardsBaseline()
    val forwardsLoadState = deps.forwardsRepository.loadState.value
    when {
        setupInputResult.isFailure -> {
            val message =
                "Saved setup could not be loaded (setup_draft_load_failed). " +
                    "The existing saved draft was left untouched."
            loadState.value = SetupLoadState.Failed(message)
            access.applyState(access.state().copy(errorMessage = message))
        }
        forwardsLoadState is ForwardsLoadState.Failed -> {
            val message =
                "Saved forwards could not be loaded (setup_draft_load_failed): " +
                    SensitiveDataRedactor.redactText(forwardsLoadState.message)
            loadState.value = SetupLoadState.Failed(message)
            access.applyState(access.state().copy(errorMessage = message))
        }
        else -> {
            setupInputResult.onSuccess { saved ->
                if (saved.brokerHost.isNotBlank() || saved.remotePeerId.isNotBlank()) {
                    access.applyState(access.state().copy(input = saved))
                }
            }
            loadState.value = SetupLoadState.Ready
        }
    }
}
