package com.phillipchin.webrtctunnel.viewmodel

import android.net.Uri
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.security.readPrivateIdentityFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Identity import/generate/validate slice of the setup wizard. All identity disk /
 * ContentResolver / native validation runs on the IO dispatcher inside a busy-guarded
 * coroutine, so the wizard's main thread is never blocked.
 */
class SetupIdentityController(
    private val deps: AppDependencies,
    private val access: WizardStateAccess,
    private val scope: CoroutineScope,
) {
    fun loadStoredIdentity() =
        launchBusy {
            val publicIdentity = withContext(deps.dispatchers.io) { deps.identityRepository.readPublicIdentity() }
            if (publicIdentity.isNotBlank()) {
                access.applyState(access.state().copy(localPublicIdentity = publicIdentity))
            }
        }

    fun importIdentityFromPath() =
        launchBusy {
            val current = access.state()
            val trimmed = current.importIdentityPath.trim()
            if (trimmed.isBlank()) {
                access.applyState(current.copy(errorMessage = "Choose an identity file path to import"))
                return@launchBusy
            }
            // FIX7 P1-005-B: explicit cancellation-first try/catch, not runCatching — this
            // calls the native validation bridge.
            val resolved =
                withContext(deps.dispatchers.io) {
                    try {
                        val privateIdentity = readPrivateIdentityFile(trimmed).getOrThrow()
                        val validated = deps.identityValidation.validatePrivateIdentity(privateIdentity)
                        require(validated.valid) { validated.message ?: "Invalid private identity" }
                        val peerId = validated.peerId ?: throw IllegalArgumentException("Missing identity peer id")
                        Result.success(peerId to validated.canonicalPublicIdentity.orEmpty())
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Result.failure(error)
                    }
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

    fun importIdentityFromUri(uri: Uri) =
        launchBusy {
            val current = access.state()
            // FIX7 P1-005-B: explicit cancellation-first try/catch, not runCatching — this
            // calls the native validation bridge and persists the identity (mutation).
            val resolved =
                withContext(deps.dispatchers.io) {
                    try {
                        val privateIdentity =
                            deps.context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                                ?: error("Unable to read private identity from selected URI")
                        val validated = deps.identityValidation.validatePrivateIdentity(privateIdentity)
                        require(validated.valid) { validated.message ?: "Invalid private identity" }
                        val canonicalPrivate = validated.canonicalPrivateIdentity ?: privateIdentity
                        val canonicalPublic = validated.canonicalPublicIdentity.orEmpty()
                        val peerId = validated.peerId ?: throw IllegalArgumentException("Missing identity peer id")
                        deps.identityRepository.storeEncryptedIdentity(canonicalPrivate.toByteArray(), canonicalPublic)
                        Result.success(peerId to canonicalPublic)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Result.failure(error)
                    }
                }
            resolved.onSuccess { (peerId, canonicalPublic) ->
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
                    current.copy(errorMessage = it.message ?: "Invalid private identity file", saveResult = null),
                )
            }
        }

    fun validateRemotePublicIdentity() =
        launchBusy {
            val current = access.state()
            access.applyState(resolveRemotePublicIdentity(current, current.importPublicIdentity.trim()))
        }

    fun importPublicIdentityFromUri(uri: Uri) =
        launchBusy {
            val current = access.state()
            // FIX7 P1-005-B: safe as runCatching — a pure content-URI text read (no native
            // call, no persistence in this block), so it cannot swallow a fatal Error or a
            // laundered CancellationException that matters here.
            val text =
                withContext(deps.dispatchers.io) {
                    runCatching {
                        deps.context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                            ?: error("Unable to read remote public identity from selected URI")
                    }
                }
            text.onSuccess { value ->
                val withText =
                    current.copy(importPublicIdentity = value, remoteIdentityPeerId = null, errorMessage = null)
                access.applyState(resolveRemotePublicIdentity(withText, value.trim()))
            }.onFailure {
                access.applyState(current.copy(errorMessage = it.message ?: "Failed importing remote public identity"))
            }
        }

    fun generateIdentity() =
        launchBusy {
            val current = access.state()
            val generated =
                withContext(deps.dispatchers.io) { deps.identityValidation.generateIdentity(current.input.localPeerId) }
            val privateIdentity = generated.canonicalPrivateIdentity
            val publicIdentity = generated.canonicalPublicIdentity
            when {
                !generated.valid ->
                    access.applyState(current.copy(errorMessage = generated.message ?: "Identity generation failed"))
                privateIdentity.isNullOrBlank() || publicIdentity.isNullOrBlank() ->
                    access.applyState(current.copy(errorMessage = "Identity generation returned incomplete data"))
                else -> {
                    withContext(deps.dispatchers.io) {
                        deps.identityRepository.storeEncryptedIdentity(privateIdentity.toByteArray(), publicIdentity)
                    }
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
        }

    private suspend fun resolveRemotePublicIdentity(
        current: SetupWizardState,
        value: String,
    ): SetupWizardState {
        if (value.isBlank()) {
            return current.copy(remoteIdentityPeerId = null, errorMessage = "Remote public identity is required")
        }
        val validated = withContext(deps.dispatchers.io) { deps.identityValidation.validatePublicIdentity(value) }
        return when {
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
