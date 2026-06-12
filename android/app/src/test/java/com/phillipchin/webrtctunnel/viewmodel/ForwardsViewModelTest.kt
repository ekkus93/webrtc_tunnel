package com.phillipchin.webrtctunnel.viewmodel

import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.ValidationResult
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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

        assertEquals("Forward saved", vm.message.value)
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

        assertTrue(vm.message.value?.contains("bad config") == true)
        assertTrue(vm.forwards.value.none { it.id == "web" })
        assertFalse(vm.isBusy.value)
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
}
