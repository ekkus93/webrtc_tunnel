package com.phillipchin.webrtctunnel.viewmodel

import android.os.Looper
import com.phillipchin.webrtctunnel.TunnelNativeBridge
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.ConfigRepository
import com.phillipchin.webrtctunnel.model.IdentityValidationResult
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.model.ValidationResult
import com.phillipchin.webrtctunnel.network.NetworkPolicyManager
import com.phillipchin.webrtctunnel.security.IdentityCrypto
import com.phillipchin.webrtctunnel.security.IdentityRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ImportExportViewModelTest : AppViewModelTestBase() {
    @Test
    fun importCancellationIsNotReportedAsFailure() {
        // FIX6 P0-005: the op runner wraps a suspend block; a cancellation must propagate,
        // not fall through to a failure snackbar/resultMessage as though it were an error.
        val cancellingConfigRepo =
            object : ConfigRepository(app) {
                override suspend fun writeConfigAtomically(contents: String): Result<Unit> =
                    throw CancellationException("cancelled during import write")
            }
        val cancellingDeps =
            AppDependencies(
                context = app,
                nativeBridgeFactory = { recordingBridge },
                configRepository = cancellingConfigRepo,
                networkPolicyManager = NetworkPolicyManager { NetworkType.UnmeteredWifi to false },
                identityRepository = deps.identityRepository,
                dispatchers = inlineTestDispatchers(),
            )
        val vm = ImportExportViewModel(cancellingDeps)
        val importFile = File(app.filesDir, "cancel-import-config.toml").apply { writeText("format = \"x\"\n") }
        vm.updateState { it.copy(configImportPath = importFile.absolutePath) }
        recordingBridge.validationResult = ValidationResult(true, null)

        vm.importConfig()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertNull(
            "a cancelled import must not produce a failure result message",
            vm.state.value.resultMessage,
        )
    }

    // FIX7 P1-001-B: cancellation must also clear isBusy and leave no durable failure record —
    // not just avoid an ordinary result message.
    @Test
    fun cancelledImportClearsBusyAndEmitsNoOrdinaryResult() {
        val cancellingConfigRepo =
            object : ConfigRepository(app) {
                override suspend fun writeConfigAtomically(contents: String): Result<Unit> =
                    throw CancellationException("cancelled during import write")
            }
        val cancellingDeps =
            AppDependencies(
                context = app,
                nativeBridgeFactory = { recordingBridge },
                configRepository = cancellingConfigRepo,
                networkPolicyManager = NetworkPolicyManager { NetworkType.UnmeteredWifi to false },
                identityRepository = deps.identityRepository,
                dispatchers = inlineTestDispatchers(),
            )
        val vm = ImportExportViewModel(cancellingDeps)
        val importFile =
            File(app.filesDir, "cancel-clears-busy-config.toml").apply { writeText("format = \"x\"\n") }
        vm.updateState { it.copy(configImportPath = importFile.absolutePath) }
        recordingBridge.validationResult = ValidationResult(true, null)

        vm.importConfig()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertFalse("a cancelled import must clear isBusy", vm.state.value.isBusy)
        assertNull(
            "a cancelled import must not produce an ordinary result message",
            vm.state.value.resultMessage,
        )
        assertNull(
            "a cancelled import must not produce a durable failure record",
            vm.state.value.lastOperationFailure,
        )
    }

    // FIX7 P1-001-A: a second import while one is already in flight must be visibly rejected —
    // never silently dropped.
    @Test
    fun secondConfigImportIsRejectedVisiblyWithActiveOperation() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val blockingConfigRepo =
                object : ConfigRepository(app) {
                    override suspend fun writeConfigAtomically(contents: String): Result<Unit> {
                        gate.await()
                        return Result.success(Unit)
                    }
                }
            val blockingDeps =
                AppDependencies(
                    context = app,
                    nativeBridgeFactory = { recordingBridge },
                    configRepository = blockingConfigRepo,
                    networkPolicyManager = NetworkPolicyManager { NetworkType.UnmeteredWifi to false },
                    identityRepository = deps.identityRepository,
                    dispatchers = inlineTestDispatchers(),
                )
            val vm = ImportExportViewModel(blockingDeps)
            val importFile =
                File(app.filesDir, "second-import-blocking-config.toml").apply { writeText("format = \"x\"\n") }
            vm.updateState { it.copy(configImportPath = importFile.absolutePath) }
            recordingBridge.validationResult = ValidationResult(true, null)

            vm.importConfig()
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            vm.importConfig()
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            assertEquals(
                "a second import while one is active must be rejected with the durable " +
                    "configuration_operation_busy code",
                "configuration_operation_busy",
                vm.state.value.lastOperationFailure?.code,
            )
            assertTrue(
                "the busy rejection must be visible in the result message",
                vm.state.value.resultMessage?.contains("already in progress") == true,
            )

            gate.complete(Unit)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }

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

    // FIX6 P1-008: a config-import failure must survive in durable state with no snackbar collector.
    @Test
    fun configImportFailureRemainsInStateWithoutSnackbarCollector() {
        val vm = ImportExportViewModel(deps)
        val invalidFile = File(app.filesDir, "durable-invalid-import.toml").apply { writeText("not valid toml") }
        vm.updateState { it.copy(configImportPath = invalidFile.absolutePath) }
        recordingBridge.validationResult = ValidationResult(false, "invalid config")

        vm.importConfig()

        assertNotNull("the import failure must be kept in state", vm.state.value.lastOperationFailure)
    }

    @Test
    fun importExportViewModelUsesIdentityAwareValidationWhenIdentityReadable() {
        deps.identityRepository.storeEncryptedIdentity("private-identity-bytes".toByteArray(), "canon-pub")
        val vm = ImportExportViewModel(deps)
        val configFile =
            File(app.filesDir, "identity-aware-import-config.toml").apply {
                writeText(configRepository.readConfig())
            }
        vm.updateState { it.copy(configImportPath = configFile.absolutePath) }
        recordingBridge.validationResult = ValidationResult(true, null)

        vm.importConfig()

        assertEquals(1, recordingBridge.validateConfigWithIdentityCalls)
        assertEquals(0, recordingBridge.validateConfigCalls)
    }

    @Test
    fun importExportViewModelUsesIdentityLessValidationWhenNoIdentity() {
        assertTrue(!deps.identityRepository.hasEncryptedIdentity())
        val vm = ImportExportViewModel(deps)
        val configFile =
            File(app.filesDir, "identity-less-import-config.toml").apply {
                writeText(configRepository.readConfig())
            }
        vm.updateState { it.copy(configImportPath = configFile.absolutePath) }
        recordingBridge.validationResult = ValidationResult(true, null)

        vm.importConfig()

        assertEquals(0, recordingBridge.validateConfigWithIdentityCalls)
        assertEquals(1, recordingBridge.validateConfigCalls)
    }

    @Test
    fun importExportViewModelSurfacesVisibleFailureWhenIdentityPresentButUnreadable() {
        val unreadableIdentityRepository =
            IdentityRepository(
                app,
                object : IdentityCrypto {
                    override fun encrypt(plaintext: ByteArray): ByteArray = plaintext

                    override fun decrypt(payload: ByteArray): ByteArray = error("decrypt boom")
                },
            )
        unreadableIdentityRepository.storeEncryptedIdentity("garbage".toByteArray(), "canon-pub")
        val brokenDeps =
            AppDependencies(
                context = app,
                nativeBridgeFactory = { recordingBridge },
                configRepository = configRepository,
                networkPolicyManager =
                    NetworkPolicyManager {
                        NetworkType.UnmeteredWifi to false
                    },
                identityRepository = unreadableIdentityRepository,
                dispatchers = deps.dispatchers,
            )
        val vm = ImportExportViewModel(brokenDeps)
        val configFile =
            File(app.filesDir, "unreadable-identity-import-config.toml").apply {
                writeText(configRepository.readConfig())
            }
        vm.updateState { it.copy(configImportPath = configFile.absolutePath) }
        recordingBridge.validationResult = ValidationResult(true, null)

        vm.importConfig()

        assertEquals(0, recordingBridge.validateConfigWithIdentityCalls)
        assertEquals(0, recordingBridge.validateConfigCalls)
        assertTrue(vm.state.value.resultMessage?.contains("Identity exists but could not be loaded") == true)
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
                dispatchers = deps.dispatchers,
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
