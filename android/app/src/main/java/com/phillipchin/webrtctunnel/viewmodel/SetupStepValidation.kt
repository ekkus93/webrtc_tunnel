package com.phillipchin.webrtctunnel.viewmodel

import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.SensitiveDataRedactor
import com.phillipchin.webrtctunnel.model.SetupConfigInput

internal fun validateStep(
    deps: AppDependencies,
    step: SetupStep,
    state: SetupWizardState,
): String? =
    when (step) {
        SetupStep.Mode -> null
        SetupStep.Identity -> validateIdentityStep(deps, state)
        SetupStep.Broker -> validateBrokerStep(state.input)
        SetupStep.Peer -> validatePeerStep(deps, state)
        SetupStep.Forwards -> validateForwardsStep(deps)
        SetupStep.NetworkPolicy -> null
        SetupStep.Review -> validateReviewStep(deps, state)
    }

private fun validateIdentityStep(
    deps: AppDependencies,
    state: SetupWizardState,
): String? {
    val hasStored = deps.identityRepository.hasEncryptedIdentity()
    return if (!hasStored && state.importIdentityPath.isBlank() && state.localPublicIdentity.isBlank()) {
        "Import or generate a private identity to continue"
    } else {
        null
    }
}

private fun validateBrokerStep(input: SetupConfigInput): String? =
    when {
        input.brokerHost.isBlank() -> "Broker host is required"
        input.brokerPort !in 1..MAX_PORT -> "Broker port must be between 1 and 65535"
        else -> null
    }

private fun validatePeerStep(
    deps: AppDependencies,
    state: SetupWizardState,
): String? {
    val input = state.input
    return if (input.remotePeerId.isBlank()) {
        "Remote peer id is required"
    } else if (state.importPublicIdentity.isBlank()) {
        "Remote public identity is required"
    } else {
        val validated = deps.identityValidation.validatePublicIdentity(state.importPublicIdentity)
        when {
            !validated.valid -> validated.message ?: "Invalid remote public identity"
            validated.peerId == input.localPeerId -> "Remote identity cannot be the same as local identity"
            validated.peerId != input.remotePeerId ->
                "Remote peer ID must match imported public identity peer ID (${validated.peerId})"
            else -> null
        }
    }
}

private fun validateForwardsStep(deps: AppDependencies): String? =
    deps.forwardsStore.loadForwardsResult().fold(
        onSuccess = { forwards ->
            deps.forwardsStore.validateForwards(forwards)
                ?: if (forwards.none { it.enabled }) {
                    "Enable at least one forward"
                } else {
                    null
                }
        },
        onFailure = { error ->
            SensitiveDataRedactor.redactText(
                "Unable to read forwards configuration: ${error.message ?: "unknown storage error"}",
            )
        },
    )

private fun validateReviewStep(
    deps: AppDependencies,
    state: SetupWizardState,
): String? =
    validateStep(deps, SetupStep.Identity, state)
        ?: validateStep(deps, SetupStep.Broker, state)
        ?: validateStep(deps, SetupStep.Peer, state)
        ?: validateStep(deps, SetupStep.Forwards, state)
        ?: state.identityPeerId?.let { identityPeerId ->
            if (identityPeerId != state.input.localPeerId) {
                "Local peer ID must match private identity peer ID ($identityPeerId)"
            } else {
                null
            }
        }
