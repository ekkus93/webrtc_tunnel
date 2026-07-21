package com.phillipchin.webrtctunnel.viewmodel

import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.SensitiveDataRedactor
import com.phillipchin.webrtctunnel.data.deleteCandidateFileSafely
import com.phillipchin.webrtctunnel.data.withCandidateFile
import java.io.File
import java.io.IOException

enum class ImportKind(val label: String) {
    Config("Config"),
    PrivateIdentity("Private identity"),
    PublicIdentity("Public identity"),
}

/**
 * Import/export operations (validation + file IO) split out of ImportExportViewModel.
 *
 * [deleteCandidateFile] is injectable (FIX7 P1-001-C/P1-001-E) so tests can force the
 * config-import candidate cleanup to fail with a fake instead of a flaky filesystem
 * permission trick — production always uses the real [deleteCandidateFileSafely].
 */
class ImportExportService(
    private val deps: AppDependencies,
    private val deleteCandidateFile: (File) -> Result<Unit> = ::deleteCandidateFileSafely,
) {
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
        // FIX7 P1-001-C: withCandidateFile composes the unique candidate file's cleanup with
        // the block's own outcome, so a cleanup-only failure (write succeeded, temp file
        // couldn't be deleted) can never be silently discarded — it surfaces as a
        // CandidateCleanupException instead (FIX6 INV-012/FIX7 P0-002-C).
        var identity: ByteArray? = null
        try {
            withCandidateFile(deps.context.cacheDir, "import-config-", deleteCandidateFile) { temp ->
                // Identity absence and identity-present-but-unreadable are different states:
                // only the former falls back to identity-less validation. A present but
                // unreadable identity surfaces as a visible failure rather than a silent
                // downgrade. Explicit try/catch (not runCatching): readPrivateIdentityPlaintext
                // is non-suspending, so this cannot encounter coroutine cancellation, and
                // catching Exception keeps the specific, useful diagnostic.
                val identityBytes =
                    if (deps.identityRepository.hasEncryptedIdentity()) {
                        try {
                            deps.identityRepository.readPrivateIdentityPlaintext()
                        } catch (error: Exception) {
                            // FIX7 P1-004-C: a fixed safe message, not the raw underlying
                            // error — identity-read failures must never echo unredacted text.
                            error("Identity exists but could not be loaded")
                        }
                    } else {
                        null
                    }
                identity = identityBytes
                temp.writeText(candidate)
                val validation =
                    if (identityBytes != null) {
                        deps.identityValidation.validateConfigWithIdentity(temp.absolutePath, identityBytes)
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
            }
        } finally {
            // FIX7 P1-001-D: wipe the plaintext identity buffer regardless of outcome
            // (success, validation failure, write failure, cleanup failure, or cancellation).
            identity?.fill(0)
        }
    }

    private fun importPrivateIdentityContent(privateIdentity: String) {
        val validated = deps.identityValidation.validatePrivateIdentity(privateIdentity)
        require(validated.valid) { validated.message ?: "Invalid private identity" }
        // FIX7 P1-001-D: hold the canonical private bytes in a nullable variable and wipe them
        // in finally regardless of outcome — storeEncryptedIdentity does not take ownership of
        // (and does not wipe) the buffer it is given.
        var canonicalBytes: ByteArray? = null
        try {
            canonicalBytes = (validated.canonicalPrivateIdentity ?: privateIdentity).toByteArray()
            deps.identityRepository.storeEncryptedIdentity(
                canonicalBytes,
                validated.canonicalPublicIdentity
                    ?: throw IllegalArgumentException("Missing canonical public identity"),
            )
        } finally {
            canonicalBytes?.fill(0)
        }
    }

    private fun importPublicIdentityLine(line: String) {
        val validated = deps.identityValidation.validatePublicIdentity(line)
        require(validated.valid) { validated.message ?: "Invalid public identity" }
        deps.identityRepository.appendAuthorizedPublicIdentity(
            validated.canonicalPublicIdentity ?: line.trim(),
        ).getOrThrow()
    }
}
