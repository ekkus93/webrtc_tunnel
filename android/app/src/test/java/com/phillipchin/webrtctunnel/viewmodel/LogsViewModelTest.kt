package com.phillipchin.webrtctunnel.viewmodel

import android.net.Uri
import android.os.Looper
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.model.NetworkPolicyStatus
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.network.NetworkPolicyManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.io.File

private val NO_NETWORK =
    NetworkPolicyStatus(
        networkType = NetworkType.NoNetwork,
        isMetered = false,
        allowedByDefault = false,
        allowedByUserPolicy = false,
        tunnelAllowed = false,
    )

@RunWith(RobolectricTestRunner::class)
class LogsViewModelTest : AppViewModelTestBase() {
    @Test
    fun exportDiagnosticsSuccessWritesFileAndReportsSuccessMessage() {
        val viewModel = LogsViewModel(deps)
        val outputFile = File(app.filesDir, "diagnostics.txt")

        viewModel.exportDiagnostics(outputFile.absolutePath, NO_NETWORK)

        awaitCondition { viewModel.message.value != null }
        assertEquals("Diagnostics exported", viewModel.message.value)
        assertFalse(viewModel.isBusy.value)
        assertTrue(outputFile.readText().contains("rust_library=p2p_mobile"))
    }

    @Test
    fun exportDiagnosticsFailureReportsClearMessageWithoutCrashing() {
        val viewModel = LogsViewModel(deps)
        // A path that is an existing directory: File.writeText on it fails, exercising the
        // runCatching-equivalent failure branch (exportRedactedDiagnostics itself wraps its
        // body in runCatching) without touching real filesystem permissions.
        val directoryAsOutputPath = app.filesDir.absolutePath

        viewModel.exportDiagnostics(directoryAsOutputPath, NO_NETWORK)

        awaitCondition { viewModel.message.value != null }
        assertFalse(viewModel.isBusy.value)
        assertTrue(viewModel.message.value != "Diagnostics exported")
    }

    @Test
    fun exportDiagnosticsToUriSuccessWritesToDestination() {
        val viewModel = LogsViewModel(deps)
        val outputFile = File(app.filesDir, "diagnostics_via_uri.txt")

        viewModel.exportDiagnosticsToUri(Uri.fromFile(outputFile), NO_NETWORK)

        awaitCondition { viewModel.message.value != null }
        assertEquals("Diagnostics exported", viewModel.message.value)
        assertFalse(viewModel.isBusy.value)
        assertTrue(outputFile.readText().contains("rust_library=p2p_mobile"))
    }

    @Test
    fun exportDiagnosticsToUriWithUnopenableDestinationReportsErrorWithoutCrashing() {
        val viewModel = LogsViewModel(deps)
        // Parent directory doesn't exist, so ContentResolver.openOutputStream throws — this
        // exercises the runCatching error path around openOutputStream rather than the
        // `?: error(...)` null branch, since Robolectric's file-Uri resolver throws rather
        // than returning null for an unopenable destination.
        val unopenableFile = File(app.filesDir, "no_such_subdir/diagnostics.txt")

        viewModel.exportDiagnosticsToUri(Uri.fromFile(unopenableFile), NO_NETWORK)

        awaitCondition { viewModel.message.value != null }
        assertFalse(viewModel.isBusy.value)
        assertTrue(viewModel.message.value != "Diagnostics exported")
    }

    @Test
    fun concurrentExportIsRejectedWhileOneIsAlreadyInFlight() {
        // The shared `deps` fixture uses fully inline (Unconfined) dispatchers, so
        // exportDiagnostics() would run start-to-finish before a second call could ever
        // observe isBusy == true. Use real IO dispatchers here so `withContext(io)`
        // genuinely suspends the launch at that point, giving us a window to fire the
        // second call while the first is still in flight.
        val realIoDeps =
            AppDependencies(
                context = app,
                nativeBridgeFactory = { recordingBridge },
                configRepository = configRepository,
                networkPolicyManager = NetworkPolicyManager { NetworkType.UnmeteredWifi to false },
                identityRepository = deps.identityRepository,
                dispatchers = realIoTestDispatchers(),
            )
        val viewModel = LogsViewModel(realIoDeps)
        val firstOutput = File(app.filesDir, "diagnostics_first.txt")
        val secondOutput = File(app.filesDir, "diagnostics_second.txt")

        viewModel.exportDiagnostics(firstOutput.absolutePath, NO_NETWORK)
        assertTrue("first export should set isBusy before yielding to the IO dispatcher", viewModel.isBusy.value)
        viewModel.exportDiagnostics(secondOutput.absolutePath, NO_NETWORK)

        awaitCondition { viewModel.message.value != null }
        assertTrue(firstOutput.exists())
        assertFalse("second concurrent export must be ignored, not written", secondOutput.exists())
    }

    @Test
    fun filteredLogsAppliesCaseInsensitiveLevelFilter() {
        recordingBridge.recentLogsJson =
            """
            [
              {"unix_ms": 1, "level": "INFO", "message": "started"},
              {"unix_ms": 2, "level": "error", "message": "boom"},
              {"unix_ms": 3, "level": "Error", "message": "boom again"}
            ]
            """.trimIndent()
        val viewModel = LogsViewModel(deps)

        viewModel.refresh()
        awaitCondition { viewModel.filteredLogs.value.size == 3 }

        viewModel.setFilter("error")
        awaitCondition { viewModel.filteredLogs.value.size == 2 }
        assertTrue(viewModel.filteredLogs.value.all { it.level.equals("error", ignoreCase = true) })
    }

    private fun awaitCondition(predicate: () -> Boolean) {
        runBlocking {
            withTimeout(5_000) {
                while (!predicate()) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
        }
    }
}
