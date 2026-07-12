package com.phillipchin.webrtctunnel.data

import androidx.test.core.app.ApplicationProvider
import com.phillipchin.webrtctunnel.model.LogEvent
import com.phillipchin.webrtctunnel.model.NetworkPolicyStatus
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.model.ServiceState
import com.phillipchin.webrtctunnel.model.TunnelMode
import com.phillipchin.webrtctunnel.model.TunnelStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DiagnosticsRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var configRepository: ConfigRepository
    private lateinit var diagnosticsRepository: DiagnosticsRepository

    @Before
    fun setUp() {
        configRepository = ConfigRepository(context)
        diagnosticsRepository = DiagnosticsRepository(context, configRepository)
        File(context.filesDir, "config.toml").delete()
    }

    @Test
    fun exportRedactedDiagnosticsRedactsSecretsAndCandidates() {
        kotlinx.coroutines.runBlocking {
            configRepository.writeConfig(
                """
                [broker]
                username = "admin"
                password_file = "/tmp/pass"
                """.trimIndent(),
            )
        }
        val output = File(context.filesDir, "diag.txt")
        output.delete()
        val result =
            diagnosticsRepository.exportRedactedDiagnostics(
                outputPath = output.absolutePath,
                status =
                    TunnelStatus(
                        serviceState = ServiceState.Connected,
                        mode = TunnelMode.Offer,
                        localPeerId = "android-phone",
                    ),
                logs = listOf(LogEvent(1L, "info", "sdp=foo candidate=bar password=abc token=xyz")),
                networkStatus = NetworkPolicyStatus(NetworkType.UnmeteredWifi, false, true, true, true, null),
            )
        assertTrue(result.isSuccess)
        val text = output.readText()
        assertTrue(text.contains("***REDACTED***"))
        assertFalse(text.contains("password=abc"))
        assertFalse(text.contains("candidate=bar"))
    }

    @Test
    fun buildPayloadReturnsRedactedTextForSharing() {
        val payload =
            diagnosticsRepository.buildRedactedDiagnosticsPayload(
                status =
                    TunnelStatus(
                        serviceState = ServiceState.Error,
                        mode = TunnelMode.Offer,
                        localPeerId = "android-phone",
                    ),
                logs = listOf(LogEvent(1L, "error", "password=abc token=xyz")),
                networkStatus = NetworkPolicyStatus(NetworkType.UnmeteredWifi, false, true, true, true, null),
            )
        assertTrue(payload.contains("***REDACTED***"))
        assertFalse(payload.contains("password=abc"))
    }
}
