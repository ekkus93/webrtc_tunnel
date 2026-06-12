package com.phillipchin.webrtctunnel.viewmodel

import com.phillipchin.webrtctunnel.TunnelNativeBridge
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.model.IdentityValidationResult
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.model.ValidationResult
import com.phillipchin.webrtctunnel.network.NetworkPolicyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ImportExportViewModelTest : AppViewModelTestBase() {
    @Test
    fun importExportViewModelRequiresConfirmationForRawConfigExport() {
        val vm = ImportExportViewModel(deps)
        val output = File(app.filesDir, "raw-config-export.toml")
        vm.updateState { it.copy(configExportPath = output.absolutePath) }
        vm.exportConfig(confirmSensitive = false)
        assertTrue(vm.state.value.resultMessage?.contains("requires explicit confirmation") == true)
    }

    @Test
    fun importExportViewModelDeletesTempFileOnSuccessAndFailure() {
        val vm = ImportExportViewModel(deps)
        val tempFile = File(app.cacheDir, "config-import-candidate.toml")
        tempFile.delete()
        val baseline = configRepository.readConfig()
        val validFile = File(app.filesDir, "valid-import-config.toml").apply { writeText(baseline) }

        vm.updateState { it.copy(configImportPath = validFile.absolutePath) }
        recordingBridge.validationResult = ValidationResult(true, null)
        vm.importConfig()
        assertTrue(!tempFile.exists())
        assertEquals(baseline, configRepository.readConfig())

        val invalidFile = File(app.filesDir, "invalid-import-config.toml").apply { writeText("not valid toml") }
        vm.updateState { it.copy(configImportPath = invalidFile.absolutePath) }
        recordingBridge.validationResult = ValidationResult(false, "invalid config")
        vm.importConfig()
        assertTrue(!tempFile.exists())
        assertEquals(baseline, configRepository.readConfig())
        assertNotNull(vm.state.value.resultMessage)
    }

    @Test
    fun importExportViewModelDeletesTempFileOnThrownValidationError() {
        val throwingBridge =
            object : TunnelNativeBridge {
                override fun startOffer(
                    configPath: String,
                    identityBytes: ByteArray?,
                ) = Result.success(Unit)

                override fun startAnswer(configPath: String) = Result.success(Unit)

                override fun stop() = Result.success(Unit)

                override fun getStatusJson(): String = recordingBridge.getStatusJson()

                override fun getRecentLogsJson(maxEvents: Int): String = "[]"

                override fun validateConfig(configPath: String): ValidationResult = error("boom")

                override fun validateConfigWithIdentity(
                    configPath: String,
                    identityBytes: ByteArray,
                ): ValidationResult = error("boom")

                override fun validatePrivateIdentity(identityToml: String): IdentityValidationResult =
                    IdentityValidationResult(
                        valid = true,
                        canonicalPublicIdentity = "canon",
                        canonicalPrivateIdentity = identityToml,
                        peerId = "android-phone",
                    )

                override fun validatePublicIdentity(line: String): IdentityValidationResult =
                    IdentityValidationResult(
                        valid = true,
                        canonicalPublicIdentity = line.trim(),
                        peerId = "remote-peer",
                    )

                override fun generateIdentity(peerId: String): IdentityValidationResult =
                    IdentityValidationResult(
                        valid = true,
                        canonicalPublicIdentity = "canon",
                        canonicalPrivateIdentity = "private",
                        peerId = peerId,
                    )
            }
        val throwingDeps =
            AppDependencies(
                context = app,
                nativeBridgeFactory = { throwingBridge },
                configRepository = configRepository,
                networkPolicyManager =
                    NetworkPolicyManager {
                        NetworkType.UnmeteredWifi to false
                    },
                identityRepository = deps.identityRepository,
            )
        val vm = ImportExportViewModel(throwingDeps)
        val tempFile = File(app.cacheDir, "config-import-candidate.toml")
        tempFile.delete()
        val configFile =
            File(
                app.filesDir,
                "exception-import-config.toml",
            ).apply { writeText(configRepository.readConfig()) }
        vm.updateState { it.copy(configImportPath = configFile.absolutePath) }
        vm.importConfig()
        assertTrue(!tempFile.exists())
        assertTrue(vm.state.value.resultMessage?.contains("boom") == true)
    }
}
