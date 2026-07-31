package com.phillipchin.webrtctunnel.viewmodel

import android.net.Uri
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.model.NetworkPolicyStatus
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.network.NetworkPolicyManager
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val NO_NETWORK =
    NetworkPolicyStatus(
        networkType = NetworkType.NoNetwork,
        isMetered = false,
        allowedByDefault = false,
        allowedByUserPolicy = false,
        tunnelAllowed = false,
    )

private class BlockedIoDispatcher : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor()
    val dispatcher: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()
    private val release = CountDownLatch(1)

    init {
        val entered = CountDownLatch(1)
        executor.execute {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "blocked diagnostics IO was never released" }
        }
        check(entered.await(5, TimeUnit.SECONDS)) { "diagnostics IO blocker did not start" }
    }

    fun release() {
        release.countDown()
    }

    override fun close() {
        release()
        dispatcher.close()
    }
}

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
        BlockedIoDispatcher().use { blockedIo ->
            val blockedDeps =
                AppDependencies(
                    context = app,
                    nativeBridgeFactory = { recordingBridge },
                    configRepository = configRepository,
                    networkPolicyManager = NetworkPolicyManager { NetworkType.UnmeteredWifi to false },
                    identityRepository = deps.identityRepository,
                    dispatchers = realIoTestDispatchers(blockedIo.dispatcher),
                )
            val viewModel = LogsViewModel(blockedDeps)
            val firstOutput = File(app.filesDir, "diagnostics_first.txt")
            val secondOutput = File(app.filesDir, "diagnostics_second.txt")

            viewModel.exportDiagnostics(firstOutput.absolutePath, NO_NETWORK)
            assertTrue("first export must claim admission before launch", viewModel.isBusy.value)
            viewModel.exportDiagnostics(secondOutput.absolutePath, NO_NETWORK)
            assertFalse("second concurrent export must be rejected before IO", secondOutput.exists())

            blockedIo.release()
            awaitCondition { viewModel.message.value != null }
            assertTrue(firstOutput.exists())
            assertFalse("rejected export must never be written", secondOutput.exists())
        }
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

    // FIX8 P1-004-C: delegates to the one shared bounded-polling helper; fully qualified
    // because this file's own wrapper is (deliberately, per the shared helper's naming
    // convention) also named `awaitCondition`.
    private fun awaitCondition(predicate: () -> Boolean) {
        com.phillipchin.webrtctunnel.awaitCondition(predicate = predicate)
    }
}
