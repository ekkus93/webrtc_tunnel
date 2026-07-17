package com.phillipchin.webrtctunnel.viewmodel

import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.SensitiveDataRedactor
import com.phillipchin.webrtctunnel.data.createCandidateFile
import com.phillipchin.webrtctunnel.data.deleteCandidateFileSafely
import java.io.IOException

enum class ImportKind(val label: String) {
    Config("Config"),
    PrivateIdentity("Private identity"),
    PublicIdentity("Public identity"),
}

/** Import/export operations (validation + file IO) split out of ImportExportViewModel. */
class ImportExportService(private val deps: AppDependencies) {
    suspend fun importContent(
        kind: ImportKind,
        content: String,
    ) {
        when (kind) {
            ImportKind.Config -> importConfigContent(content)
            ImportKind.PrivateIdentity -> importPrivateIdentityContent(content)
            ImportKind.PublicIdentity -> importPublicIdentityLine(content)
        }
    }

    fun configForExport(confirmSensitive: Boolean): String {
        require(confirmSensitive) { "Raw config export requires explicit confirmation" }
        return deps.configRepository.readConfig()
    }

    private suspend fun importConfigContent(candidate: String) {
        // Unique candidate file (FIX6 INV-012): the previous fixed name let a concurrent
        // import overwrite/delete this operation's candidate. try/finally (no catch) lets
        // cancellation and other failures propagate as visible failures while still wiping
        // the identity buffer and removing the candidate.
        val temp = createCandidateFile(deps.context.cacheDir, "import-config-")
        var identity: ByteArray? = null
        try {
            // Identity absence and identity-present-but-unreadable are different states:
            // only the former falls back to identity-less validation. A present but
            // unreadable identity surfaces as a visible failure rather than a silent
            // downgrade. Explicit try/catch (not runCatching): readPrivateIdentityPlaintext
            // is non-suspending, so this cannot encounter coroutine cancellation, and
            // catching Exception keeps the specific, useful diagnostic.
            identity =
                if (deps.identityRepository.hasEncryptedIdentity()) {
                    try {
                        deps.identityRepository.readPrivateIdentityPlaintext()
                    } catch (error: Exception) {
                        error("Identity exists but could not be loaded: ${error.message}")
                    }
                } else {
                    null
                }
            temp.writeText(candidate)
            val validation =
                if (identity != null) {
                    deps.identityValidation.validateConfigWithIdentity(temp.absolutePath, identity)
                } else {
                    deps.identityValidation.validateConfig(temp.absolutePath)
                }
            require(validation.valid) { validation.message ?: "Config validation failed" }
            // FIX6 P0-001-C: consume the write result. Discarding it reported "Config
            // imported" even when the atomic write failed. The message is redacted at the
            // throw site because a raw file-I/O message can carry secret-bearing paths.
            deps.configRepository
                .writeConfigAtomically(candidate)
                .getOrElse { error ->
                    throw IOException(
                        SensitiveDataRedactor.redactText(
                            error.message ?: "Failed to persist imported config",
                        ),
                    )
                }
        } finally {
            identity?.fill(0)
            deleteCandidateFileSafely(temp)
        }
    }

    private fun importPrivateIdentityContent(privateIdentity: String) {
        val validated = deps.identityValidation.validatePrivateIdentity(privateIdentity)
        require(validated.valid) { validated.message ?: "Invalid private identity" }
        deps.identityRepository.storeEncryptedIdentity(
            (validated.canonicalPrivateIdentity ?: privateIdentity).toByteArray(),
            validated.canonicalPublicIdentity ?: throw IllegalArgumentException("Missing canonical public identity"),
        )
    }

    private fun importPublicIdentityLine(line: String) {
        val validated = deps.identityValidation.validatePublicIdentity(line)
        require(validated.valid) { validated.message ?: "Invalid public identity" }
        deps.identityRepository.appendAuthorizedPublicIdentity(
            validated.canonicalPublicIdentity ?: line.trim(),
        ).getOrThrow()
    }
}
