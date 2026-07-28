package com.phillipchin.webrtctunnel.viewmodel

import android.net.Uri
import com.phillipchin.webrtunnel.data.AppDependencies
import com.phillipchin.webrtunnel.data.SensitiveDataRedactor
import com.phillipchin.webrtunnel.model.IdentityValidationResult
import com.phillipchin.webrtunnel.security.readPrivateIdentityFile
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
    /** FIX8 P1-001-B: awaited directly (not launched) as part of [SetupViewModel]'s
     * BaselineLoad-guarded init sequence — never routed through [access]'s coordinator itself,
     * since that admission is already held by the caller for the whole baseline load. */
    suspend fun loadStoredIdentityBaseline() {
        val publicIdentity = withContext(deps.dispatchers.io) { deps.identityRepository.readPublicIdentity() }
        if (publicIdentity.isNotBlank()) {
            access.applyState(access.state().copy(localPublicIdentity = publicIdentity))
        }
    }

    fun importIdentityFromPath() =
        launchIdentityAction { token ->
            val current = access.state()
            val trimmed = current.importIdentityPath.trim()
            if (trimmed.isBlank()) {
                token.publishIfFresh {
                    access.applyState(current.copy(errorMessage = "Choose an identity file path to import"))
                }
                return@launchIdentityAction
            }
            val resolved = resolvePrivateIdentityFromPath(trimmed)
            publishPathIdentityImportResult(token, current, trimmed, resolved)
        }

    fun importIdentityFromUri(uri: Uri) =
        launchIdentityAction { token ->
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
                val published =
                    token.publishIfFresh {
                        identityDraft.replace(
                            replacement.privateIdentity,
                            replacement.publicIdentity,
                            replacement.peerId,
                        )
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
                    }
                if (!published) {
                    replacement.wipe()
                }
            }.onFailure {
                token.publishIfFresh {
                    access.applyState(
                        current.copy(
                            errorMessage =
                                SensitiveDataRedactor.redactSecretValues(
                                    it.message ?: "Invalid private identity file",
                                ),
                            saveResult = null,
                        ),
                    )
                }
            }
        }

    fun validateRemotePublicIdentity() =
        launchIdentityAction { token ->
            val current = access.state()
            val resolved = resolveRemotePublicIdentity(deps, current, current.importPublicIdentity.trim())
            token.publishIfFresh { access.applyState(resolved) }
        }

    fun importPublicIdentityFromUri(uri: Uri) =
        launchIdentityAction { token ->
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
                val resolved = resolveRemotePublicIdentity(deps, withText, value.trim())
                token.publishIfFresh { access.applyState(resolved) }
            }.onFailure {
                token.publishIfFresh {
                    access.applyState(
                        current.copy(
                            errorMessage =
                                SensitiveDataRedactor.redactSecretValues(
                                    it.message ?: "Failed importing remote public identity",
                                ),
                        ),
                    )
                }
            }
        }

    fun generateIdentity() =
        launchIdentityAction { token ->
            val current = access.state()
            val generated =
                withContext(deps.dispatchers.io) { deps.identityValidation.generateIdentity(current.input.localPeerId) }
            val privateIdentity = generated.canonicalPrivateIdentity
            val publicIdentity = generated.canonicalPublicIdentity
            val peerId = generated.peerId
            when {
                // FIX8 P1-001-C: generated.message comes from the native bridge — redact it
                // like every other native-validation message before it reaches UI state.
                !generated.valid ->
                    token.publishIfFresh {
                        access.applyState(
                            current.copy(
                                errorMessage =
                                    SensitiveDataRedactor.redactText(generated.message ?: "Identity generation failed"),
                            ),
                        )
                    }
                // FIX8 P0-001-B: fail closed on any missing canonical field (including peer ID) —
                // no `generated.peerId ?: current.input.localPeerId` fallback.
                privateIdentity.isNullOrBlank() || publicIdentity.isNullOrBlank() || peerId.isNullOrBlank() ->
                    token.publishIfFresh {
                        access.applyState(current.copy(errorMessage = "Identity generation returned incomplete data"))
                    }
                else -> {
                    // FIX8 P0-001-B: draft-only — do NOT call storeEncryptedIdentity here.
                    token.publishIfFresh {
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
        }

    private suspend fun resolvePrivateIdentityFromPath(path: String): Result<DraftIdentityReplacement> =
        withContext(deps.dispatchers.io) {
            try {
                val privateIdentity = readPrivateIdentityFile(path).getOrThrow()
                val validated = deps.identityValidation.validatePrivateIdentity(privateIdentity)
                require(validated.valid) { validated.message ?: "Invalid private identity" }
                Result.success(requireCanonicalIdentity(validated))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
        }

    private fun publishPathIdentityImportResult(
        token: SetupOperationToken,
        current: SetupWizardState,
        trimmedPath: String,
        resolved: Result<DraftIdentityReplacement>,
    ) {
        resolved.onSuccess { replacement ->
            publishImportedPathIdentity(token, current, trimmedPath, replacement)
        }.onFailure { error ->
            publishPrivateIdentityImportFailure(token, current, error)
        }
    }

    private fun publishImportedPathIdentity(
        token: SetupOperationToken,
        current: SetupWizardState,
        trimmedPath: String,
        replacement: DraftIdentityReplacement,
    ) {
        val published =
            token.publishIfFresh {
                identityDraft.replace(
                    replacement.privateIdentity,
                    replacement.publicIdentity,
                    replacement.peerId,
                )
                access.applyState(
                    current.copy(
                        importIdentityPath = trimmedPath,
                        identityPeerId = replacement.peerId,
                        localPublicIdentity = replacement.publicIdentity,
                        input = current.input.copy(localPeerId = replacement.peerId),
                        errorMessage = null,
                        saveResult = "Identity imported",
                    ),
                )
            }
        if (!published) {
            replacement.wipe()
        }
    }

    private fun publishPrivateIdentityImportFailure(
        token: SetupOperationToken,
        current: SetupWizardState,
        error: Throwable,
    ) {
        token.publishIfFresh {
            access.applyState(
                current.copy(
                    identityPeerId = null,
                    localPublicIdentity = "",
                    errorMessage =
                        SensitiveDataRedactor.redactSecretValues(
                            error.message ?: "Invalid private identity file",
                        ),
                    saveResult = null,
                ),
            )
        }
    }

    /** FIX8 P1-001-A/C: replaces the old per-controller `launchBusy` — routes through the shared
     * setup-local coordinator (so isBusy is derived from real admission ownership, not toggled
     * independently) with a cancellation-first/redacted-catch safety net for any exception
     * [block] does not already handle itself. */
    private fun launchIdentityAction(block: suspend (SetupOperationToken) -> Unit) {
        scope.launch {
            access.operations.runGuarded(access, SetupDraftOperation.IdentityAction) { token -> block(token) }
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
    deps: AppDependencies,
    current: SetupWizardState,
    value: String,
): SetupWizardState {
    if (value.isBlank()) {
        return current.copy(remoteIdentityPeerId = null, errorMessage = "Remote public identity is required")
    }
    val validated = withContext(deps.dispatchers.io) { deps.identityValidation.validatePublicIdentity(value) }
    return when {
        // FIX8 P1-001-C: validated.message comes from the native bridge — redact it like
        // every other native-validation message before it reaches UI state.
        !validated.valid ->
            current.copy(
                remoteIdentityPeerId = null,
                errorMessage = SensitiveDataRedactor.redactText(validated.message ?: "Invalid remote public identity"),
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
