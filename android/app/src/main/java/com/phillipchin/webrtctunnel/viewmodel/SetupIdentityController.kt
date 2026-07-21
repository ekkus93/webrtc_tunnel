package com.phillipchin.webrtctunnel.viewmodel

import android.net.Uri
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.model.IdentityValidationResult
import com.phillipchin.webrtctunnel.security.readPrivateIdentityFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Identity import/generate/validate slice of the setup wizard. All identity disk /
 * ContentResolver / native validation runs on the IO dispatcher inside a busy-guarded
 * coroutine, so the wizard's main thread is never blocked.
 *
 * FIX8 P0-001-B: import/generate are draft-only. A validated identity's canonical
 * private bytes go into the ViewModel-owned [SetupIdentityDraft]; NOTHING is written to
 * `IdentityRepository` until the final setup transaction (see [SetupSaveController]).
 * Required canonical fields (private, public, peer ID) fail closed — no `orEmpty()` or
 * prior/source peer-ID fallback.
 */
internal class SetupIdentityController(
    private val deps: AppDependencies,
    private val access: WizardStateAccess,
    private val scope: CoroutineScope,
    private val identityDraft: SetupIdentityDraft,
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
            // calls the native validation bridge. FIX8 P0-001-B: populate the draft, do not
            // persist; the final save uses the draft (no path re-read / TOCTOU).
            val resolved =
                withContext(deps.dispatchers.io) {
                    try {
                        val privateIdentity = readPrivateIdentityFile(trimmed).getOrThrow()
                        val validated = deps.identityValidation.validatePrivateIdentity(privateIdentity)
                        require(validated.valid) { validated.message ?: "Invalid private identity" }
                        Result.success(requireCanonicalIdentity(validated))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Result.failure(error)
                    }
                }
            resolved.onSuccess { replacement ->
                identityDraft.replace(replacement.privateIdentity, replacement.publicIdentity, replacement.peerId)
                access.applyState(
                    current.copy(
                        importIdentityPath = trimmed,
                        identityPeerId = replacement.peerId,
                        localPublicIdentity = replacement.publicIdentity,
                        input = current.input.copy(localPeerId = replacement.peerId),
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
            // calls the native validation bridge. FIX8 P0-001-B: draft-only, no persistence.
            val resolved =
                withContext(deps.dispatchers.io) {
                    try {
                        val privateIdentity =
                            deps.context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                                ?: error("Unable to read private identity from selected URI")
                        val validated = deps.identityValidation.validatePrivateIdentity(privateIdentity)
                        require(validated.valid) { validated.message ?: "Invalid private identity" }
                        Result.success(requireCanonicalIdentity(validated))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Result.failure(error)
                    }
                }
            resolved.onSuccess { replacement ->
                identityDraft.replace(replacement.privateIdentity, replacement.publicIdentity, replacement.peerId)
                access.applyState(
                    current.copy(
                        identityPeerId = replacement.peerId,
                        localPublicIdentity = replacement.publicIdentity,
                        input = current.input.copy(localPeerId = replacement.peerId),
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

    fun validateRemotePublicIdentity() =
        launchBusy {
            val current = access.state()
            access.applyState(resolveRemotePublicIdentity(current, current.importPublicIdentity.trim()))
        }

    fun importPublicIdentityFromUri(uri: Uri) =
        launchBusy {
            val current = access.state()
            // FIX8 P1-001-C: a pure content-URI text read — explicit cancellation-first
            // try/catch(Exception), never runCatching (which would also swallow fatal Error).
            val text =
                withContext(deps.dispatchers.io) {
                    try {
                        val value =
                            deps.context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                                ?: error("Unable to read remote public identity from selected URI")
                        Result.success(value)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Result.failure(error)
                    }
                }
            text.onSuccess { value ->
                val withText =
                    current.copy(importPublicIdentity = value, remoteIdentityPeerId = null, errorMessage = null)
                access.applyState(resolveRemotePublicIdentity(withText, value.trim()))
            }.onFailure {
                access.applyState(
                    current.copy(
                        errorMessage = it.message ?: "Failed importing remote public identity",
                    ),
                )
            }
        }

    fun generateIdentity() =
        launchBusy {
            val current = access.state()
            val generated =
                withContext(deps.dispatchers.io) { deps.identityValidation.generateIdentity(current.input.localPeerId) }
            val privateIdentity = generated.canonicalPrivateIdentity
            val publicIdentity = generated.canonicalPublicIdentity
            val peerId = generated.peerId
            when {
                !generated.valid ->
                    access.applyState(current.copy(errorMessage = generated.message ?: "Identity generation failed"))
                // FIX8 P0-001-B: fail closed on any missing canonical field (including peer ID) —
                // no `generated.peerId ?: current.input.localPeerId` fallback.
                privateIdentity.isNullOrBlank() || publicIdentity.isNullOrBlank() || peerId.isNullOrBlank() ->
                    access.applyState(current.copy(errorMessage = "Identity generation returned incomplete data"))
                else -> {
                    // FIX8 P0-001-B: draft-only — do NOT call storeEncryptedIdentity here.
                    identityDraft.replace(privateIdentity.encodeToByteArray(), publicIdentity, peerId)
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

    /**
     * FIX8 P0-001-B: canonicalizes a validated import result into an owned
     * [DraftIdentityReplacement], failing closed (with a fixed message) when any required
     * canonical field is absent. The private identity is transferred as a fresh byte array
     * the caller hands to [SetupIdentityDraft]; the bridge's canonical private String is not
     * retained here.
     */
    private fun requireCanonicalIdentity(validated: IdentityValidationResult): DraftIdentityReplacement {
        val canonicalPrivate =
            requireNotNull(validated.canonicalPrivateIdentity) {
                "Identity validation returned no canonical private identity"
            }
        val canonicalPublic =
            requireNotNull(validated.canonicalPublicIdentity) {
                "Identity validation returned no canonical public identity"
            }
        val peerId = requireNotNull(validated.peerId) { "Identity validation returned no peer ID" }
        require(canonicalPrivate.isNotBlank()) { "Identity validation returned a blank canonical private identity" }
        require(canonicalPublic.isNotBlank()) { "Identity validation returned a blank canonical public identity" }
        require(peerId.isNotBlank()) { "Identity validation returned a blank peer ID" }
        return DraftIdentityReplacement(canonicalPrivate.encodeToByteArray(), canonicalPublic, peerId)
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
