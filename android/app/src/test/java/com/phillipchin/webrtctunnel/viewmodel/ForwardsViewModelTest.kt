package com.phillipchin.webrtctunnel.viewmodel

import android.os.Looper
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.model.ValidationResult
import com.phillipchin.webrtctunnel.network.NetworkPolicyManager
import com.phillipchin.webrtctunnel.security.IdentityCrypto
import com.phillipchin.webrtctunnel.security.IdentityRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.net.ServerSocket

@RunWith(RobolectricTestRunner::class)
class ForwardsViewModelTest : AppViewModelTestBase() {
    @Test
    fun forwardsViewModelSaveAddsForwardAndReportsResult() {
        val vm = ForwardsViewModel(deps)
        recordingBridge.validationResult = ValidationResult(true, null)
        val forward =
            ForwardConfig(id = "web", name = "web", localPort = 9090, remoteForwardId = "web", enabled = true)

        vm.saveForward(forward)

        // The save path now reads the debug-logs preference from DataStore (async),
        // so await the result while idling the looper rather than asserting synchronously.
        awaitMessage(vm) { it == "Forward saved" }
        assertTrue(vm.forwards.value.any { it.id == "web" })
        assertFalse(vm.isBusy.value)
    }

    @Test
    fun forwardsViewModelSaveRollsBackOnInvalidConfig() {
        val vm = ForwardsViewModel(deps)
        recordingBridge.validationResult = ValidationResult(false, "bad config")
        val forward =
            ForwardConfig(id = "web", name = "web", localPort = 9090, remoteForwardId = "web", enabled = true)

        vm.saveForward(forward)

        awaitMessage(vm) { it?.contains("bad config") == true }
        assertTrue(vm.forwards.value.none { it.id == "web" })
        assertFalse(vm.isBusy.value)
    }

    @Test
    fun forwardsViewModelSaveUsesIdentityAwareValidationWhenIdentityReadable() {
        deps.identityRepository.storeEncryptedIdentity("private-identity-bytes".toByteArray(), "canon-pub")
        val vm = ForwardsViewModel(deps)
        recordingBridge.validationResult = ValidationResult(true, null)
        val forward =
            ForwardConfig(id = "web", name = "web", localPort = 9090, remoteForwardId = "web", enabled = true)

        vm.saveForward(forward)

        awaitMessage(vm) { it == "Forward saved" }
        assertEquals(1, recordingBridge.validateConfigWithIdentityCalls)
        assertEquals(0, recordingBridge.validateConfigCalls)
    }

    @Test
    fun forwardsViewModelSaveUsesIdentityLessValidationWhenNoIdentity() {
        assertFalse(deps.identityRepository.hasEncryptedIdentity())
        val vm = ForwardsViewModel(deps)
        recordingBridge.validationResult = ValidationResult(true, null)
        val forward =
            ForwardConfig(id = "web", name = "web", localPort = 9090, remoteForwardId = "web", enabled = true)

        vm.saveForward(forward)

        awaitMessage(vm) { it == "Forward saved" }
        assertEquals(0, recordingBridge.validateConfigWithIdentityCalls)
        assertEquals(1, recordingBridge.validateConfigCalls)
    }

    @Test
    fun forwardsViewModelSaveReportsVisibleFailureWhenIdentityPresentButUnreadable() {
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
        val vm = ForwardsViewModel(brokenDeps)
        recordingBridge.validationResult = ValidationResult(true, null)
        val forward =
            ForwardConfig(id = "web", name = "web", localPort = 9090, remoteForwardId = "web", enabled = true)

        vm.saveForward(forward)

        awaitMessage(vm) { it?.contains("Identity exists but could not be loaded") == true }
        assertEquals(0, recordingBridge.validateConfigWithIdentityCalls)
        assertEquals(0, recordingBridge.validateConfigCalls)
        assertTrue(vm.forwards.value.none { it.id == "web" })
    }

    @Test
    fun forwardsViewModelTestLocalPortReportsSuccessAndFailure() {
        runBlocking {
            val server = ServerSocket(0)
            val successVm = ForwardsViewModel(deps)
            val successForward =
                ForwardConfig(
                    id = "svc-open",
                    name = "svc-open",
                    localHost = "127.0.0.1",
                    localPort = server.localPort,
                    remoteForwardId = "svc-open",
                    enabled = true,
                )
            val successMessage =
                async {
                    withTimeout(5_000) {
                        successVm.message.first { it?.contains("succeeded") == true }
                    }
                }
            successVm.testLocalPort(successForward)
            assertTrue(successMessage.await()?.contains("succeeded") == true)
            server.close()

            val failureVm = ForwardsViewModel(deps)
            val failureForward = successForward.copy(id = "svc-closed", localPort = successForward.localPort)
            val failureMessage =
                async {
                    withTimeout(5_000) {
                        failureVm.message.first { it?.contains("failed") == true }
                    }
                }
            failureVm.testLocalPort(failureForward)
            assertTrue(failureMessage.await()?.contains("failed") == true)
        }
    }

    // Drive the Robolectric main looper while waiting for an async save result, so
    // viewModelScope coroutines actually run instead of sitting queued.
    private fun awaitMessage(
        vm: ForwardsViewModel,
        predicate: (String?) -> Boolean,
    ) {
        runBlocking {
            withTimeout(5_000) {
                while (!predicate(vm.message.value)) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
        }
    }
}
