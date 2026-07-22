package com.phillipchin.webrtctunnel.ui

import com.phillipchin.webrtctunnel.model.LogEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * FIX8 P0-010-A/D: an [LogEvent] with no established timestamp (`unixMs == null`, e.g. a
 * diagnostic-clock read failure on the native side) must never display or export as a bare "0"
 * — which would misleadingly read as a real (Unix-epoch) time — and must instead use a fixed,
 * non-numeric placeholder.
 */
class LogsScreenTimestampTest {
    // logsScreenDisplaysTimeUnavailableForNullTimestamp
    @Test
    fun logsScreenDisplaysTimeUnavailableForNullTimestamp() {
        val formatted = formatLogTimestamp(unixMs = null, unavailableText = "time unavailable")
        assertEquals("time unavailable", formatted)
    }

    @Test
    fun logsScreenFormatsARealTimestampNormally() {
        val formatted = formatLogTimestamp(unixMs = 0L, unavailableText = "time unavailable")
        // A genuine timestamp of exactly the Unix epoch is a real (if unusual) value, distinct
        // from "no timestamp at all" — it must still format as a clock time, not the fallback.
        assertFalse(formatted == "time unavailable")
    }

    // logExportNeverPrintsZeroForUnavailableTimestamp
    @Test
    fun logExportNeverPrintsZeroForUnavailableTimestamp() {
        val logs =
            listOf(
                LogEvent(unixMs = null, level = "error", message = "clock unavailable"),
                LogEvent(unixMs = 1_700_000_000_000L, level = "info", message = "ok"),
            )
        val text = redactedLogsText(logs, unavailableText = "time unavailable")
        val lines = text.lines()
        assertEquals(2, lines.size)
        assertEquals("time unavailable error clock unavailable", lines[0])
        assertFalse(
            "an unavailable timestamp must never be exported as a bare 0",
            lines[0].startsWith("0 "),
        )
        assertEquals("1700000000000 info ok", lines[1])
    }
}
