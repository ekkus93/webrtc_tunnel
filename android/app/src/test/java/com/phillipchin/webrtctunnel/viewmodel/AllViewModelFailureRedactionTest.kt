package com.phillipchin.webrtctunnel.viewmodel

import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.ConfigRepository
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.IdentityValidationResult
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.model.ValidationResult
import com.phillipchin.webrtctunnel.network.NetworkPolicyManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

private const val SECRET = "hunter2-sentinel-98765"
private const val SECRET_MESSAGE = "operation failed password=$SECRET"
private const val PATH_SENTINEL = "sentinel-a1b2c3-private-segment"

/**
 * FIX7 P1-004-D: `allMutatingViewModelFailureStatesRejectSecretSentinel` — a single secret
 * sentinel, injected via each ViewModel's most natural failing dependency, must never reach
 * that ViewModel's durable failure state verbatim. Covers the boundaries FIX7 P1-004-C
 * specifically hardened: network preference save (NetworkPolicyViewModel), import/export
 * (ImportExportViewModel), and setup save / private identity import (SetupSaveController).
 */
@RunWith(RobolectricTestRunner::class)
class AllViewModelFailureRedactionTest : AppViewModelTestBase() {
    @Test
    fun allMutatingViewModelFailureStatesRejectSecretSentinel() =
        runBlocking {
            assertNetworkPolicyPreferenceFailureRedactsSecret()
            assertImportExportFailureRedactsSecret()
            assertSetupSavePrivateIdentityImportFailureRedactsSecret()
        }

    @Test
    fun rawPrivatePathSentinelNeverAppearsInProductionDiagnosticStateOrLogs() =
        runBlocking {
            // FIX8 P1-003-D: importIdentityFromPath's failure path
            // (SetupIdentityController -> readPrivateIdentityFile -> redactSecretValues) must
            // never let a raw private file path reach UI state — redactSecretValues (unlike
            // redactText) has no generic path-scrubbing rule, so the production fix (FIX8
            // P1-003-C/D) is to never embed the path in readPrivateIdentityFile's own exception
            // message in the first place.
            val testDeps =
                AppDependencies(
                    context = app,
                    nativeBridgeFactory = { recordingBridge },
                    configRepository = configRepository,
                    networkPolicyManager = NetworkPolicyManager { NetworkType.UnmeteredWifi to false },
                    identityRepository = deps.identityRepository,
                    dispatchers = inlineTestDispatchers(),
                )
            val vm = SetupViewModel(testDeps)
            val missingPath = File(app.filesDir, "$PATH_SENTINEL/identity.toml").absolutePath
            vm.setImportIdentityPath(missingPath)
            vm.setInput(vm.state.value.input.copy(localPeerId = "android-phone"))

            vm.identity.importIdentityFromPath()
            awaitCondition { vm.state.value.errorMessage != null }

            assertFalse(
                "SetupViewModel import errorMessage must not contain the raw private file path",
                vm.state.value.errorMessage.orEmpty().contains(PATH_SENTINEL),
            )
        }

    private fun assertNetworkPolicyPreferenceFailureRedactsSecret() {
        val failingRepository =
            object : ConfigRepository(app) {
                override suspend fun savePreferences(update: AndroidAppPreferences): Result<Unit> =
                    Result.failure(RuntimeException(SECRET_MESSAGE))
            }
        val testDeps =
            AppDependencies(
                context = app,
                nativeBridgeFactory = { recordingBridge },
                configRepository = failingRepository,
                networkPolicyManager = NetworkPolicyManager { NetworkType.UnmeteredWifi to false },
                identityRepository = deps.identityRepository,
                dispatchers = inlineTestDispatchers(),
            )
        val vm = NetworkPolicyViewModel(testDeps)

        vm.savePreferences(AndroidAppPreferences())
        awaitCondition { vm.uiState.value.lastOperationFailure != null }

        assertFalse(
            "NetworkPolicyViewModel durable failure must not contain the raw secret",
            vm.uiState.value.lastOperationFailure?.message.orEmpty().contains(SECRET),
        )
    }

    private fun assertImportExportFailureRedactsSecret() {
        val failingRepository =
            object : ConfigRepository(app) {
                override val replaceConfigTransactionally: suspend (String) -> Result<Unit> = {
                    Result.failure(RuntimeException(SECRET_MESSAGE))
                }
            }
        val testDeps =
            AppDependencies(
                context = app,
                nativeBridgeFactory = { recordingBridge },
                configRepository = failingRepository,
                networkPolicyManager = NetworkPolicyManager { NetworkType.UnmeteredWifi to false },
                identityRepository = deps.identityRepository,
                dispatchers = inlineTestDispatchers(),
            )
        val vm = ImportExportViewModel(testDeps)
        val importFile =
            File(app.filesDir, "redaction-sentinel-config.toml").apply { writeText("format = \"x\"\n") }
        vm.updateState { it.copy(configImportPath = importFile.absolutePath) }
        recordingBridge.validationResult = ValidationResult(true, null)

        vm.importConfig()
        awaitCondition { vm.state.value.lastOperationFailure != null }

        assertFalse(
            "ImportExportViewModel durable failure must not contain the raw secret",
            vm.state.value.lastOperationFailure?.message.orEmpty().contains(SECRET),
        )
        assertFalse(
            "ImportExportViewModel resultMessage must not contain the raw secret",
            vm.state.value.resultMessage.orEmpty().contains(SECRET),
        )
    }

    private fun assertSetupSavePrivateIdentityImportFailureRedactsSecret() {
        val testDeps =
            AppDependencies(
                context = app,
                nativeBridgeFactory = { recordingBridge },
                configRepository = configRepository,
                networkPolicyManager = NetworkPolicyManager { NetworkType.UnmeteredWifi to false },
                identityRepository = deps.identityRepository,
                dispatchers = inlineTestDispatchers(),
            )
        val vm = SetupViewModel(testDeps)
        // A private-identity import whose native validation fails with a message the test
        // bridge is configured to echo back containing the sentinel. FIX8 P0-001-B: the
        // import action itself (not a save-time re-read, which no longer exists) is where
        // this failure message is now produced and must be redacted before reaching UI state.
        recordingBridge.privateIdentityValidationResult =
            IdentityValidationResult(
                valid = false,
                message = SECRET_MESSAGE,
            )
        val importFile =
            File(app.filesDir, "redaction-sentinel-identity.toml").apply {
                writeText("[identity]\npeer_id = \"android-phone\"\n")
            }
        vm.setImportIdentityPath(importFile.absolutePath)
        vm.setInput(vm.state.value.input.copy(localPeerId = "android-phone"))

        vm.identity.importIdentityFromPath()
        awaitCondition { vm.state.value.errorMessage != null }

        assertFalse(
            "SetupViewModel import errorMessage must not contain the raw secret",
            vm.state.value.errorMessage.orEmpty().contains(SECRET),
        )
    }

    // FIX8 P1-004-C: delegates to the one shared bounded-polling helper; fully qualified
    // because this file's own wrapper is (deliberately, per the shared helper's naming
    // convention) also named `awaitCondition`.
    private fun awaitCondition(predicate: () -> Boolean) {
        com.phillipchin.webrtctunnel.awaitCondition(predicate = predicate)
    }
}
