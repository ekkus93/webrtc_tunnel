package com.phillipchin.webrtctunnel.viewmodel

import android.net.Uri
import com.phillipchin.webrtctunnel.data.AppDependencies

/** Identity import/generate/validate slice of the setup wizard, split from SetupViewModel. */
class SetupIdentityController(
    private val deps: AppDependencies,
    private val access: WizardStateAccess,
) {
    fun loadStoredIdentity() {
        val publicIdentity = deps.identityRepository.readPublicIdentity()
        if (publicIdentity.isNotBlank()) {
            access.applyState(access.state().copy(localPublicIdentity = publicIdentity))
        }
    }

    fun importIdentityFromPath() {
        val current = access.state()
        val trimmed = current.importIdentityPath.trim()
        if (trimmed.isBlank()) {
            access.applyState(current.copy(errorMessage = "Choose an identity file path to import"))
            return
        }
        val resolved =
            runCatching {
                val privateIdentity = deps.identityRepository.readPrivateIdentityFile(trimmed).getOrThrow()
                val validated = deps.identityValidation.validatePrivateIdentity(privateIdentity)
                require(validated.valid) { validated.message ?: "Invalid private identity" }
                val peerId = validated.peerId ?: throw IllegalArgumentException("Missing identity peer id")
                val canonicalPublic = validated.canonicalPublicIdentity.orEmpty()
                peerId to canonicalPublic
            }
        resolved.onSuccess { (peerId, canonicalPublic) ->
            access.applyState(
                current.copy(
                    importIdentityPath = trimmed,
                    identityPeerId = peerId,
                    localPublicIdentity = canonicalPublic,
                    input = current.input.copy(localPeerId = peerId),
                    errorMessage = null,
                    saveResult = "Identity imported",
                ),
            )
        }.onFailure {
            access.applyState(
                current.copy(
                    identityPeerId = null,
                    localPublicIdentity = "",
                    errorMessage = it.message ?: "Invalid private identity file",
                    saveResult = null,
                ),
            )
        }
    }

    fun importIdentityFromUri(uri: Uri) {
        val current = access.state()
        val resolved =
            runCatching {
                val privateIdentity =
                    deps.context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("Unable to read private identity from selected URI")
                val validated = deps.identityValidation.validatePrivateIdentity(privateIdentity)
                require(validated.valid) { validated.message ?: "Invalid private identity" }
                val canonicalPrivate = validated.canonicalPrivateIdentity ?: privateIdentity
                val canonicalPublic = validated.canonicalPublicIdentity.orEmpty()
                val peerId = validated.peerId ?: throw IllegalArgumentException("Missing identity peer id")
                deps.identityRepository.storeEncryptedIdentity(canonicalPrivate.toByteArray(), canonicalPublic)
                Triple(peerId, canonicalPublic, canonicalPrivate)
            }
        resolved.onSuccess { (peerId, canonicalPublic, _) ->
            access.applyState(
                current.copy(
                    identityPeerId = peerId,
                    localPublicIdentity = canonicalPublic,
                    input = current.input.copy(localPeerId = peerId),
                    importIdentityPath = "",
                    errorMessage = null,
                    saveResult = "Identity imported",
                ),
            )
        }.onFailure {
            access.applyState(
                current.copy(
                    errorMessage = it.message ?: "Invalid private identity file",
                    saveResult = null,
                ),
            )
        }
    }

    fun validateRemotePublicIdentity() {
        val current = access.state()
        val value = current.importPublicIdentity.trim()
        if (value.isBlank()) {
            access.applyState(
                current.copy(remoteIdentityPeerId = null, errorMessage = "Remote public identity is required"),
            )
            return
        }

        val validated = deps.identityValidation.validatePublicIdentity(value)
        val updated =
            when {
                !validated.valid ->
                    current.copy(
                        remoteIdentityPeerId = null,
                        errorMessage = validated.message ?: "Invalid remote public identity",
                    )
                validated.peerId == current.input.localPeerId ->
                    current.copy(
                        remoteIdentityPeerId = null,
                        errorMessage = "Remote public identity cannot match local identity",
                    )
                else ->
                    current.copy(
                        importPublicIdentity = validated.canonicalPublicIdentity ?: value,
                        remoteIdentityPeerId = validated.peerId,
                        errorMessage = null,
                        saveResult = "Remote public identity validated",
                    )
            }
        access.applyState(updated)
    }

    fun importPublicIdentityFromUri(uri: Uri) {
        runCatching {
            deps.context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Unable to read remote public identity from selected URI")
        }.onSuccess { text ->
            access.applyState(
                access.state().copy(
                    importPublicIdentity = text,
                    remoteIdentityPeerId = null,
                    errorMessage = null,
                ),
            )
            validateRemotePublicIdentity()
        }.onFailure {
            access.applyState(
                access.state().copy(errorMessage = it.message ?: "Failed importing remote public identity"),
            )
        }
    }

    fun generateIdentity() {
        val current = access.state()
        val generated = deps.identityValidation.generateIdentity(current.input.localPeerId)
        if (!generated.valid) {
            access.applyState(current.copy(errorMessage = generated.message ?: "Identity generation failed"))
            return
        }
        val privateIdentity = generated.canonicalPrivateIdentity
        val publicIdentity = generated.canonicalPublicIdentity
        if (privateIdentity.isNullOrBlank() || publicIdentity.isNullOrBlank()) {
            access.applyState(current.copy(errorMessage = "Identity generation returned incomplete data"))
            return
        }
        deps.identityRepository.storeEncryptedIdentity(privateIdentity.toByteArray(), publicIdentity)
        val peerId = generated.peerId ?: current.input.localPeerId
        access.applyState(
            current.copy(
                input = current.input.copy(localPeerId = peerId),
                localPublicIdentity = publicIdentity,
                identityPeerId = peerId,
                errorMessage = null,
                saveResult = "Identity generated",
            ),
        )
    }
}
