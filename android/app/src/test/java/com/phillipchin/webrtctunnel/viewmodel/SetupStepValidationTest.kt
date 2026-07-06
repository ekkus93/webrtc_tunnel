package com.phillipchin.webrtctunnel.viewmodel

import com.phillipchin.webrtctunnel.model.IdentityValidationResult
import com.phillipchin.webrtctunnel.model.SetupConfigInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SetupStepValidationTest : AppViewModelTestBase() {
    private fun brokerState(port: Int) =
        SetupWizardState(input = SetupConfigInput(brokerHost = "broker.local", brokerPort = port))

    @Test
    fun brokerPortZeroIsRejected() {
        assertEquals(
            "Broker port must be between 1 and 65535",
            validateStep(deps, SetupStep.Broker, brokerState(0)),
        )
    }

    @Test
    fun brokerPortAboveMaxIsRejected() {
        assertEquals(
            "Broker port must be between 1 and 65535",
            validateStep(deps, SetupStep.Broker, brokerState(65536)),
        )
    }

    @Test
    fun brokerPortLowerBoundaryIsAccepted() {
        assertNull(validateStep(deps, SetupStep.Broker, brokerState(1)))
    }

    @Test
    fun brokerPortUpperBoundaryIsAccepted() {
        assertNull(validateStep(deps, SetupStep.Broker, brokerState(65535)))
    }

    private fun peerState() =
        SetupWizardState(
            input = SetupConfigInput(localPeerId = "android-phone", remotePeerId = "remote-peer"),
            importPublicIdentity = "p2ptunnel-ed25519 peer_id=remote-peer sign_pub=AA kex_pub=BB",
        )

    @Test
    fun remoteIdentitySameAsLocalIdentityIsRejected() {
        recordingBridge.publicIdentityValidationResult =
            IdentityValidationResult(valid = true, peerId = "android-phone")

        assertEquals(
            "Remote identity cannot be the same as local identity",
            validateStep(deps, SetupStep.Peer, peerState()),
        )
    }

    @Test
    fun remoteIdentityDifferentFromLocalIdentityPassesThisCheck() {
        recordingBridge.publicIdentityValidationResult =
            IdentityValidationResult(valid = true, peerId = "remote-peer")

        assertNull(validateStep(deps, SetupStep.Peer, peerState()))
    }

    @Test
    fun forwardsStepReportsStorageErrorForCorruptFileInsteadOfNoForwards() {
        // A corrupt forwards.json must surface as a storage/config error, not be
        // silently treated as an empty list (which would misreport as "no forwards
        // configured" instead of the real problem).
        File(app.filesDir, "forwards.json").writeText("{not valid json")

        val message = validateStep(deps, SetupStep.Forwards, SetupWizardState())

        assertTrue(message != null)
        assertTrue(message != "Enable at least one forward")
        assertTrue(message!!.contains("Unable to read forwards configuration"))
    }

    @Test
    fun forwardsStepReportsStorageErrorForUnreadableFileInsteadOfNoForwards() {
        val file = File(app.filesDir, "forwards.json")
        file.writeText("[]")
        file.setReadable(false)
        try {
            val message = validateStep(deps, SetupStep.Forwards, SetupWizardState())

            assertTrue(message != null)
            assertTrue(message != "Enable at least one forward")
            assertTrue(message!!.contains("Unable to read forwards configuration"))
        } finally {
            file.setReadable(true)
        }
    }

    @Test
    fun forwardsStepStillReportsNoForwardsForARealEmptyList() {
        deps.forwardsStore.saveForwards(emptyList())

        assertEquals("Enable at least one forward", validateStep(deps, SetupStep.Forwards, SetupWizardState()))
    }
}
