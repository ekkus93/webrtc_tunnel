package com.phillipchin.webrtctunnel.viewmodel

import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.model.ForwardConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * FIX8 P0-001-C: forward CRUD/validation slice of the setup wizard. Every edit changes only the
 * in-memory wizard draft ([access]'s `forwards`/`setForwards`) — the authoritative
 * `ForwardsRepository`/forwards.json is untouched until the final setup transaction commits
 * (`SetupPersistenceCoordinator.persist`, which already takes `access.forwards()` as the
 * forwards stage to write). Abandoning setup after editing a forward must leave the real
 * forwards file byte-exact unchanged — a direct `upsertWithReceipt`/`deleteWithReceipt` call
 * here (the pre-P0-001-C shape) would commit immediately and violate that.
 */
internal class SetupForwardsController(
    private val deps: AppDependencies,
    private val access: WizardStateAccess,
    private val scope: CoroutineScope,
) {
    fun validateForwardDraft(
        draft: ForwardConfig,
        currentForwards: List<ForwardConfig>,
    ): String? = deps.forwardsStore.validateForwards(withUpsert(currentForwards, draft))

    /** FIX8 P1-001-B: awaited directly (not launched) as part of [SetupViewModel]'s
     * BaselineLoad-guarded init sequence — never routed through [access]'s coordinator itself,
     * since that admission is already held by the caller for the whole baseline load. */
    suspend fun refreshForwardsBaseline() {
        deps.forwardsRepository.refresh()
        access.setForwards(deps.forwardsRepository.current())
        access.applyState(access.state())
    }

    fun refreshForwards() {
        scope.launch { refreshForwardsBaseline() }
    }

    fun upsertForward(forward: ForwardConfig) {
        launchForwardEdit { token ->
            val after = withUpsert(access.forwards(), forward)
            val error = deps.forwardsStore.validateForwards(after)
            if (error != null) {
                token.publishIfFresh { access.applyState(access.state().copy(errorMessage = error, saveResult = null)) }
                return@launchForwardEdit
            }
            token.publishIfFresh {
                access.setForwards(after)
                access.applyState(access.state().copy(errorMessage = null, saveResult = "Forward draft updated"))
            }
        }
    }

    fun deleteForward(forwardId: String) {
        launchForwardEdit { token ->
            val after = access.forwards().filterNot { it.id == forwardId }
            token.publishIfFresh {
                access.setForwards(after)
                access.applyState(access.state().copy(errorMessage = null, saveResult = "Forward draft removed"))
            }
        }
    }

    private fun withUpsert(
        current: List<ForwardConfig>,
        forward: ForwardConfig,
    ): List<ForwardConfig> =
        current.map { if (it.id == forward.id) forward else it }.let { candidates ->
            if (candidates.none { it.id == forward.id }) candidates + forward else candidates
        }

    /** FIX8 P1-001-A/C: replaces the old per-controller `launchBusy` — routes through the shared
     * setup-local coordinator (so isBusy is derived from real admission ownership, not toggled
     * independently) with a cancellation-first/redacted-catch safety net for any exception
     * [block] does not already handle itself. Also gates on [WizardStateAccess.loadState]: an
     * edit against an incoherent baseline (P1-001-B) must be rejected the same way a final save
     * already is, now that edits no longer route through ForwardsRepository's own
     * blockedMutationReason check for this.
     */
    private fun launchForwardEdit(block: suspend (SetupOperationToken) -> Unit) {
        scope.launch {
            access.operations.runGuarded(access, SetupDraftOperation.ForwardEdit) { token ->
                if (access.loadState() !is SetupLoadState.Ready) {
                    token.publishIfFresh {
                        access.applyState(
                            access.state().copy(
                                errorMessage = setupLoadNotReadyMessage(access.loadState()),
                                saveResult = null,
                            ),
                        )
                    }
                    return@runGuarded
                }
                block(token)
            }
        }
    }
}
