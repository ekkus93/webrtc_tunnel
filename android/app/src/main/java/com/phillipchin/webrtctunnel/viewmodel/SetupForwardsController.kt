package com.phillipchin.webrtctunnel.viewmodel

import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.model.ForwardConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Forward CRUD/validation slice of the setup wizard. Mutations go through the shared
 * ForwardsRepository (off the main thread, corrupt-safe) and mirror the result into the
 * wizard's forwards state so Home/Forwards and the wizard stay in sync.
 */
class SetupForwardsController(
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

    fun refreshForwards() {
        scope.launch {
            deps.forwardsRepository.refresh()
            access.setForwards(deps.forwardsRepository.current())
            access.applyState(access.state())
        }
    }

    fun upsertForward(forward: ForwardConfig) {
        launchBusy {
            val result = deps.forwardsRepository.upsert(forward)
            access.setForwards(deps.forwardsRepository.current())
            if (!result.validationResult.valid) {
                access.applyState(
                    access.state().copy(
                        errorMessage = result.validationResult.message ?: "Forward update failed",
                    ),
                )
            } else {
                access.applyState(access.state().copy(errorMessage = null, saveResult = "Forward saved"))
            }
        }
    }

    fun deleteForward(forwardId: String) {
        launchBusy {
            val result = deps.forwardsRepository.delete(forwardId)
            access.setForwards(deps.forwardsRepository.current())
            if (!result.validationResult.valid) {
                access.applyState(
                    access.state().copy(
                        errorMessage = result.validationResult.message ?: "Failed to delete forward",
                        saveResult = null,
                    ),
                )
            } else {
                access.applyState(access.state().copy(errorMessage = null, saveResult = "Forward deleted"))
            }
        }
    }

    private fun launchBusy(block: suspend () -> Unit) {
        scope.launch {
            access.applyState(access.state().copy(isBusy = true))
            try {
                block()
            } finally {
                access.applyState(access.state().copy(isBusy = false))
            }
        }
    }
}
