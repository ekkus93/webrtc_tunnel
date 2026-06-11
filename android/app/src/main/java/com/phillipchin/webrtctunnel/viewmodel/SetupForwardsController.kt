package com.phillipchin.webrtctunnel.viewmodel

import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.ValidationResult

/** Forward CRUD/validation slice of the setup wizard, split from SetupViewModel. */
class SetupForwardsController(
    private val deps: AppDependencies,
    private val access: WizardStateAccess,
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

    fun loadSavedForwards(): List<ForwardConfig> = deps.forwardsStore.loadForwards()

    fun refreshForwards() {
        access.setForwards(deps.forwardsStore.loadForwards())
        access.applyState(access.state())
    }

    fun upsertForward(forward: ForwardConfig): ValidationResult {
        val result = deps.forwardsStore.upsertForward(forward)
        if (!result.valid) {
            access.applyState(access.state().copy(errorMessage = result.message ?: "Forward update failed"))
            return result
        }
        refreshForwards()
        access.applyState(access.state().copy(errorMessage = null, saveResult = "Forward saved"))
        return result
    }

    fun deleteForward(forwardId: String) {
        deps.forwardsStore.deleteForward(forwardId)
        refreshForwards()
        access.applyState(access.state().copy(errorMessage = null, saveResult = "Forward deleted"))
    }
}
