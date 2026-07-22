package com.phillipchin.webrtctunnel.viewmodel

import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.ForwardsMutationBlocked
import com.phillipchin.webrtctunnel.data.ForwardsRevisionMismatchException
import com.phillipchin.webrtctunnel.data.describeForwardsFailure
import com.phillipchin.webrtctunnel.model.ForwardConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Forward CRUD/validation slice of the setup wizard. Mutations go through the shared
 * ForwardsRepository (off the main thread, corrupt-safe) and mirror the result into the
 * wizard's forwards state so Home/Forwards and the wizard stay in sync.
 */
internal class SetupForwardsController(
    private val deps: AppDependencies,
    private val access: WizardStateAccess,
    private val scope: CoroutineScope,
) {
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
        launchForwardEdit {
            // P1-001: Use receipt-based atomic upsert.
            deps.forwardsRepository.upsertWithReceipt(forward).fold(
                onSuccess = {
                    access.setForwards(deps.forwardsRepository.current())
                    access.applyState(access.state().copy(errorMessage = null, saveResult = "Forward saved"))
                },
                onFailure = { error ->
                    access.applyState(
                        access.state().copy(
                            errorMessage = mapForwardsError(error),
                            saveResult = null,
                        ),
                    )
                },
            )
        }
    }

    fun deleteForward(forwardId: String) {
        launchForwardEdit {
            // P1-001: Use receipt-based atomic delete.
            deps.forwardsRepository.deleteWithReceipt(forwardId).fold(
                onSuccess = {
                    access.setForwards(deps.forwardsRepository.current())
                    access.applyState(access.state().copy(errorMessage = null, saveResult = "Forward deleted"))
                },
                onFailure = { error ->
                    access.applyState(
                        access.state().copy(
                            errorMessage = mapForwardsError(error),
                            saveResult = null,
                        ),
                    )
                },
            )
        }
    }

    /** Maps a forwards mutation error to a user-visible message. */
    private fun mapForwardsError(error: Throwable): String {
        return when (error) {
            is ForwardsMutationBlocked -> error.message ?: "Forwards mutation blocked"
            is ForwardsRevisionMismatchException -> "Forwards changed concurrently; change discarded"
            else -> describeForwardsFailure(error)
        }
    }

    /** FIX8 P1-001-A/C: replaces the old per-controller `launchBusy` — routes through the shared
     * setup-local coordinator (so isBusy is derived from real admission ownership, not toggled
     * independently) with a cancellation-first/redacted-catch safety net for any exception
     * [block] does not already handle itself. */
    private fun launchForwardEdit(block: suspend () -> Unit) {
        scope.launch {
            access.operations.runGuarded(access, SetupDraftOperation.ForwardEdit) { block() }
        }
    }
}
