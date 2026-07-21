package com.phillipchin.webrtctunnel.data

import com.phillipchin.webrtctunnel.model.NativeRuntimeStatusDto
import com.phillipchin.webrtctunnel.model.ServiceState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * FIX7 P1-002-A: an invalid-status branch (decode failure, unknown native mode) must clear
 * stale live-connection truth exactly like a terminal state does — it must never let a
 * previous Connected status's remote peer, active session count, or MQTT-connected flag keep
 * showing once the native status itself could not be trusted. Split out from
 * [TunnelRepositoryTest] to keep that class under detekt's `LargeClass` threshold.
 */
@RunWith(RobolectricTestRunner::class)
class TunnelRepositoryInvalidStatusTest {
    private lateinit var bridge: RecordingBridge
    private lateinit var repository: TunnelRepository

    @Before
    fun setUp() {
        bridge = RecordingBridge()
        repository = TunnelRepository(bridge)
    }

    @Test
    fun decodeFailureClearsPreviousRemotePeerSessionAndMqttTruth() {
        bridge.statusPayload =
            Json.encodeToString(
                NativeRuntimeStatusDto(
                    state = "running",
                    mode = "offer",
                    active = true,
                    activeSessionCount = 1,
                    remotePeerId = "peer-1",
                    mqttConnected = true,
                ),
            )
        repository.refreshStatus()
        assertEquals(ServiceState.Connected, repository.status.value.serviceState)
        assertEquals("peer-1", repository.status.value.remotePeerId)

        bridge.statusPayload = "{bad-json"
        repository.refreshStatus()

        val status = repository.status.value
        assertEquals(ServiceState.Error, status.serviceState)
        assertNull(
            "a decode failure must not keep showing the previous status's remote peer",
            status.remotePeerId,
        )
        assertEquals(0, status.activeSessionCount)
        assertFalse(
            "a decode failure must not keep showing the previous status's MQTT-connected flag",
            status.mqttConnected,
        )
    }

    @Test
    fun unknownNativeModeClearsPreviousRemotePeerSessionAndMqttTruth() {
        bridge.statusPayload =
            Json.encodeToString(
                NativeRuntimeStatusDto(
                    state = "running",
                    mode = "offer",
                    active = true,
                    activeSessionCount = 1,
                    remotePeerId = "peer-1",
                    mqttConnected = true,
                ),
            )
        repository.refreshStatus()
        assertEquals(ServiceState.Connected, repository.status.value.serviceState)
        assertEquals("peer-1", repository.status.value.remotePeerId)

        bridge.statusPayload =
            """{"state":"running","mode":"future_unknown_mode","active":true}"""
        repository.refreshStatus()

        val status = repository.status.value
        assertEquals(ServiceState.Error, status.serviceState)
        assertEquals("native_status_schema_error", status.lastError?.code)
        assertNull(
            "an unknown native mode must not keep showing the previous status's remote peer",
            status.remotePeerId,
        )
        assertEquals(0, status.activeSessionCount)
        assertFalse(
            "an unknown native mode must not keep showing the previous status's MQTT-connected flag",
            status.mqttConnected,
        )
    }

    @Test
    fun newValidStatusAfterInvalidStatusUsesOnlyNewFields() {
        bridge.statusPayload =
            Json.encodeToString(
                NativeRuntimeStatusDto(
                    state = "running",
                    mode = "offer",
                    active = true,
                    activeSessionCount = 1,
                    remotePeerId = "peer-1",
                    mqttConnected = true,
                ),
            )
        repository.refreshStatus()

        bridge.statusPayload = "{bad-json"
        repository.refreshStatus()
        assertEquals(ServiceState.Error, repository.status.value.serviceState)

        bridge.statusPayload =
            Json.encodeToString(
                NativeRuntimeStatusDto(
                    state = "running",
                    mode = "offer",
                    active = true,
                    activeSessionCount = 1,
                    remotePeerId = "peer-2",
                    mqttConnected = false,
                ),
            )
        repository.refreshStatus()

        val status = repository.status.value
        assertEquals(ServiceState.Connected, status.serviceState)
        assertEquals(
            "a new valid status must use its own remote peer, never a value resurrected " +
                "from before the invalid status",
            "peer-2",
            status.remotePeerId,
        )
        assertFalse(status.mqttConnected)
    }
}
