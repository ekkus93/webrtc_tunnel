package com.phillipchin.webrtctunnel.viewmodel

import com.phillipchin.webrtctunnel.model.ForwardConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.ServerSocket

@RunWith(RobolectricTestRunner::class)
class ForwardsViewModelTest : AppViewModelTestBase() {
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
