package com.phillipchin.webrtctunnel.viewmodel

import com.phillipchin.webrtctunnel.data.AppDependencies
import java.io.File

enum class ImportKind(val label: String) {
    Config("Config"),
    PrivateIdentity("Private identity"),
    PublicIdentity("Public identity"),
}

/** Import/export operations (validation + file IO) split out of ImportExportViewModel. */
class ImportExportService(private val deps: AppDependencies) {
    fun importContent(
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

    private fun importConfigContent(candidate: String) {
        val temp = File(deps.context.cacheDir, "config-import-candidate.toml")
        temp.parentFile?.mkdirs()
        val identity = runCatching { deps.identityRepository.readPrivateIdentityPlaintext() }.getOrNull()
        try {
            temp.writeText(candidate)
            val validation =
                if (identity != null && identity.isNotEmpty()) {
                    deps.identityValidation.validateConfigWithIdentity(temp.absolutePath, identity)
                } else {
                    deps.identityValidation.validateConfig(temp.absolutePath)
                }
            require(validation.valid) { validation.message ?: "Config validation failed" }
            deps.configRepository.writeConfigAtomically(candidate)
        } finally {
            identity?.fill(0)
            temp.delete()
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
