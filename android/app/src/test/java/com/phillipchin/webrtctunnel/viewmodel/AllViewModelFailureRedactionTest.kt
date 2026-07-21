package com.phillipchin.webrtctunnel.viewmodel

import android.os.Looper
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.ConfigRepository
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.IdentityValidationResult
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.model.ValidationResult
import com.phillipchin.webrtctunnel.network.NetworkPolicyManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.io.File

private const val SECRET = "hunter2-sentinel-98765"
private const val SECRET_MESSAGE = "operation failed password=$SECRET"

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

    private suspend fun assertNetworkPolicyPreferenceFailureRedactsSecret() {
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
        withTimeout(5_000) {
            while (vm.uiState.value.lastOperationFailure == null) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                yield()
            }
        }

        assertFalse(
            "NetworkPolicyViewModel durable failure must not contain the raw secret",
            vm.uiState.value.lastOperationFailure?.message.orEmpty().contains(SECRET),
        )
    }

    private suspend fun assertImportExportFailureRedactsSecret() {
        val failingRepository =
            object : ConfigRepository(app) {
                override suspend fun writeConfigAtomically(contents: String): Result<Unit> =
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
        val vm = ImportExportViewModel(testDeps)
        val importFile =
            File(app.filesDir, "redaction-sentinel-config.toml").apply { writeText("format = \"x\"\n") }
        vm.updateState { it.copy(configImportPath = importFile.absolutePath) }
        recordingBridge.validationResult = ValidationResult(true, null)

        vm.importConfig()
        withTimeout(5_000) {
            while (vm.state.value.lastOperationFailure == null) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                yield()
            }
        }

        assertFalse(
            "ImportExportViewModel durable failure must not contain the raw secret",
            vm.state.value.lastOperationFailure?.message.orEmpty().contains(SECRET),
        )
        assertFalse(
            "ImportExportViewModel resultMessage must not contain the raw secret",
            vm.state.value.resultMessage.orEmpty().contains(SECRET),
        )
    }

    private suspend fun assertSetupSavePrivateIdentityImportFailureRedactsSecret() {
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
        // bridge is configured to echo back containing the sentinel.
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

        vm.save.saveAndApplyConfig()
        withTimeout(5_000) {
            while (vm.state.value.errorMessage == null) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                yield()
            }
        }

        assertFalse(
            "SetupViewModel save errorMessage must not contain the raw secret",
            vm.state.value.errorMessage.orEmpty().contains(SECRET),
        )
    }
}
