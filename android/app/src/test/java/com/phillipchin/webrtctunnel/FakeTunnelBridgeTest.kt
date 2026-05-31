package com.phillipchin.webrtctunnel

import com.phillipchin.webrtctunnel.model.NativeLogEventDto
import com.phillipchin.webrtctunnel.model.NativeRuntimeStatusDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeTunnelBridgeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun fakeBridgeReturnsStatusJson() {
        val bridge = FakeTunnelBridge()
        val status = json.decodeFromString(NativeRuntimeStatusDto.serializer(), bridge.getStatusJson())
        assertEquals("stopped", status.state)
        assertEquals("offer", status.mode)
    }

    @Test
    fun fakeBridgeReturnsLogsJson() {
        val bridge = FakeTunnelBridge()
        val logs = json.decodeFromString<List<NativeLogEventDto>>(bridge.getRecentLogsJson(2))
        assertTrue(logs.isNotEmpty())
    }
}
