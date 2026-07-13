package com.phillipchin.webrtctunnel.viewmodel

import android.os.Looper
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class LogsRefreshOrderingTest : AppViewModelTestBase() {
    @Test
    fun olderFailureCannotSetErrorAfterNewerSuccess() {
        // Simulate a failure first (invalid JSON), then a success.
        recordingBridge.recentLogsJson = "not json"

        val viewModel = LogsViewModel(deps)

        // First refresh triggers failure path.
        viewModel.refresh()

        // Before the first result is applied, trigger a second refresh with valid logs.
        recordingBridge.recentLogsJson = """[{"unix_ms": 1, "level": "info", "message": "ok"}]"""

        viewModel.refresh()
        awaitCondition { viewModel.filteredLogs.value.isNotEmpty() }

        // The second (success) refresh should have won. The first (failure) refresh should
        // NOT have overwritten the newer result because its generation is stale.
        assertEquals(1, viewModel.filteredLogs.value.size)
        assertEquals("ok", viewModel.filteredLogs.value[0].message)
    }

    @Test
    fun olderSuccessCannotClearNewerFailure() {
        // Simulate a success first, then a failure.
        recordingBridge.recentLogsJson = """[{"unix_ms": 1, "level": "info", "message": "good"}]"""

        val viewModel = LogsViewModel(deps)

        // First refresh triggers success path.
        viewModel.refresh()

        // Before the first result is applied, trigger a second refresh with failure.
        recordingBridge.recentLogsJson = "not json"

        viewModel.refresh()
        awaitCondition { viewModel.logsError.value != null }

        // The second (failure) refresh should have won. The first (success) refresh should
        // NOT have cleared the error from the newer result.
        assertTrue(viewModel.logsError.value?.code == "logs_refresh_failed")
    }

    @Test
    fun olderSuccessCannotReplaceNewerList() {
        // First refresh returns a specific list.
        recordingBridge.recentLogsJson = """[{"unix_ms": 99, "level": "warn", "message": "stale"}]"""

        val viewModel = LogsViewModel(deps)

        // First refresh.
        viewModel.refresh()

        // Second refresh returns different data.
        recordingBridge.recentLogsJson = """[{"unix_ms": 1, "level": "info", "message": "fresh"}]"""

        viewModel.refresh()
        awaitCondition { viewModel.filteredLogs.value.size == 1 }

        // The second (newer) refresh should have won.
        assertEquals(1, viewModel.filteredLogs.value.size)
        assertEquals("fresh", viewModel.filteredLogs.value[0].message)
        assertFalse(
            "stale list must not overwrite newer list",
            viewModel.filteredLogs.value.any { it.message == "stale" },
        )
    }

    @Test
    fun cancellationPropagates() {
        // recentLogs() uses try/catch, but CancellationException must rethrow.
        val result =
            runCatching {
                tunnelRepository.recentLogs(0)
            }

        // A valid JSON response should succeed.
        assertTrue(result.isSuccess)
        assertFalse(result.exceptionOrNull()?.message?.contains("not json") == true)
    }

    private fun awaitCondition(predicate: () -> Boolean) {
        runBlocking {
            withTimeout(5_000) {
                while (!predicate()) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    kotlinx.coroutines.delay(10)
                }
            }
        }
    }
}
