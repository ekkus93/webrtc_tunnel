package com.phillipchin.webrtctunnel.viewmodel

import android.net.Uri
import android.os.Looper
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.IdentityValidationResult
import com.phillipchin.webrtctunnel.model.SetupConfigInput
import com.phillipchin.webrtctunnel.model.ValidationResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.io.File

/**
 * FIX8 P0-001-B/D/E: proves the setup wizard's identity actions are draft-only and that the
 * draft private bytes have exact, wiped ownership. Every "does not mutate" test snapshots the
 * exact bytes/presence of the identity files before the action and byte-compares afterward.
 *
 * (Forward-mutation no-op tests live with P0-001-C / P0-004, where draft forwards land.)
 */
@RunWith(RobolectricTestRunner::class)
class SetupWizardNoIdentityMutationTest : AppViewModelTestBase() {
    private val identityFile: File get() = File(app.filesDir, "identity")
    private val publicFile: File get() = File(app.filesDir, "identity.pub")

    private data class FileSnapshot(val existed: Boolean, val bytes: ByteArray?)

    private fun snapshot(file: File) =
        if (file.exists()) FileSnapshot(true, file.readBytes()) else FileSnapshot(false, null)

    private fun assertUnchanged(
        before: FileSnapshot,
        file: File,
        label: String,
    ) {
        val after = snapshot(file)
        assertEquals("$label existence changed", before.existed, after.existed)
        if (before.existed) {
            assertArrayEquals("$label bytes changed", before.bytes, after.bytes)
        }
    }

    private fun awaitState(
        viewModel: SetupViewModel,
        predicate: (SetupWizardState) -> Boolean,
    ): SetupWizardState =
        runBlocking {
            withTimeout(5_000) {
                var matched: SetupWizardState? = null
                while (matched == null) {
                    val current = viewModel.state.value
                    if (predicate(current)) {
                        matched = current
                    } else {
                        Shadows.shadowOf(Looper.getMainLooper()).idle()
                        delay(10)
                    }
                }
                matched
            }
        }

    @Test
    fun setupWizardGenerateDoesNotMutateLiveIdentityBeforeFinalSave() {
        val idBefore = snapshot(identityFile)
        val pubBefore = snapshot(publicFile)
        val viewModel = SetupViewModel(deps)
        viewModel.setInput(viewModel.state.value.input.copy(localPeerId = "generated-peer"))

        viewModel.identity.generateIdentity()
        awaitState(viewModel) { it.saveResult == "Identity generated" }

        assertUnchanged(idBefore, identityFile, "encrypted identity")
        assertUnchanged(pubBefore, publicFile, "public identity")
        assertFalse(deps.identityRepository.hasEncryptedIdentity())
        assertNotNull("generation must populate the draft", viewModel.identityDraft.copyForSave())
    }

    @Test
    fun setupWizardUriImportDoesNotMutateLiveIdentityBeforeFinalSave() {
        val file =
            File(app.filesDir, "uri_identity.toml").apply { writeText("peer_id = \"android-phone\"") }
        val idBefore = snapshot(identityFile)
        val pubBefore = snapshot(publicFile)
        val viewModel = SetupViewModel(deps)

        viewModel.identity.importIdentityFromUri(Uri.fromFile(file))
        awaitState(viewModel) { it.saveResult == "Identity imported" }

        assertUnchanged(idBefore, identityFile, "encrypted identity")
        assertUnchanged(pubBefore, publicFile, "public identity")
        assertFalse(deps.identityRepository.hasEncryptedIdentity())
        assertNotNull("URI import must populate the draft", viewModel.identityDraft.copyForSave())
    }

    @Test
    fun setupWizardPathImportDoesNotMutateLiveIdentityBeforeFinalSave() {
        val file =
            File(app.filesDir, "path_identity.toml").apply { writeText("peer_id = \"android-phone\"") }
        val idBefore = snapshot(identityFile)
        val pubBefore = snapshot(publicFile)
        val viewModel = SetupViewModel(deps)
        viewModel.setImportIdentityPath(file.absolutePath)

        viewModel.identity.importIdentityFromPath()
        awaitState(viewModel) { it.saveResult == "Identity imported" }

        assertUnchanged(idBefore, identityFile, "encrypted identity")
        assertUnchanged(pubBefore, publicFile, "public identity")
        assertFalse(deps.identityRepository.hasEncryptedIdentity())
        assertNotNull("path import must populate the draft", viewModel.identityDraft.copyForSave())
    }

    @Test
    fun missingCanonicalPublicIdentityFailsWithoutFallback() {
        recordingBridge.privateIdentityValidationResult =
            IdentityValidationResult(
                valid = true,
                canonicalPublicIdentity = null,
                canonicalPrivateIdentity = "priv",
                peerId = "android-phone",
            )
        val file =
            File(app.filesDir, "no_public.toml").apply { writeText("peer_id = \"android-phone\"") }
        val viewModel = SetupViewModel(deps)

        viewModel.identity.importIdentityFromUri(Uri.fromFile(file))
        val state = awaitState(viewModel) { it.errorMessage != null }

        assertNull(
            "no draft may be populated when a canonical field is missing",
            viewModel.identityDraft.copyForSave(),
        )
        assertTrue(
            "must fail closed on missing canonical public identity, got: ${state.errorMessage}",
            state.errorMessage?.contains("canonical public identity") == true,
        )
        assertEquals("", deps.identityRepository.readPublicIdentity())
    }

    @Test
    fun missingGeneratedPeerIdFailsWithoutPriorPeerFallback() {
        recordingBridge.generateIdentityResult =
            IdentityValidationResult(
                valid = true,
                canonicalPublicIdentity = "canon",
                canonicalPrivateIdentity = "priv",
                peerId = null,
            )
        val viewModel = SetupViewModel(deps)
        viewModel.setInput(viewModel.state.value.input.copy(localPeerId = "prior-peer"))

        viewModel.identity.generateIdentity()
        val state = awaitState(viewModel) { it.errorMessage != null }

        assertNull(
            "no draft may be populated when the generated peer id is missing",
            viewModel.identityDraft.copyForSave(),
        )
        assertEquals("Identity generation returned incomplete data", state.errorMessage)
        // The prior peer id must NOT be silently adopted as the identity's peer id.
        assertEquals("prior-peer", state.input.localPeerId)
        assertNull(state.identityPeerId)
    }

    @Test
    fun replacingDraftIdentityWipesPreviousPrivateBytes() {
        val viewModel = SetupViewModel(deps)
        viewModel.setInput(viewModel.state.value.input.copy(localPeerId = "peer-one"))
        viewModel.identity.generateIdentity()
        awaitState(viewModel) { it.saveResult == "Identity generated" }
        val firstLiveBytes = viewModel.identityDraft.peekLivePrivateBytesForTest()!!.copyOf().size
        val firstRef = viewModel.identityDraft.peekLivePrivateBytesForTest()!!

        // A second generation replaces the draft and must wipe the first array.
        viewModel.setInput(viewModel.state.value.input.copy(localPeerId = "peer-two"))
        viewModel.identity.generateIdentity()
        awaitState(viewModel) { it.identityPeerId == "peer-two" }

        assertArrayEquals("previous draft bytes must be wiped on replace", ByteArray(firstLiveBytes), firstRef)
    }

    @Test
    fun setupViewModelClearWipesDraftPrivateBytesOnCancel() {
        val viewModel = SetupViewModel(deps)
        viewModel.setInput(viewModel.state.value.input.copy(localPeerId = "peer"))
        viewModel.identity.generateIdentity()
        awaitState(viewModel) { it.saveResult == "Identity generated" }
        val liveRef = viewModel.identityDraft.peekLivePrivateBytesForTest()!!
        val size = liveRef.size

        viewModel.cancel()

        assertArrayEquals("cancel must wipe draft private bytes", ByteArray(size), liveRef)
        assertNull("cancel must drop the draft", viewModel.identityDraft.copyForSave())
    }

    @Test
    fun successfulFinalSaveWipesAndClearsDraft() {
        val viewModel = SetupViewModel(deps)
        prepareReviewFromGeneratedDraft(viewModel)

        viewModel.save.saveAndApplyConfig()
        awaitState(viewModel) { it.saveResult == "Configuration saved" }

        assertNull("successful save must clear the draft", viewModel.identityDraft.copyForSave())
        assertTrue("save must persist the identity", deps.identityRepository.hasEncryptedIdentity())
    }

    @Test
    fun failedFinalSaveRetainsRetryableDraft() {
        val viewModel = SetupViewModel(deps)
        prepareReviewFromGeneratedDraft(viewModel)
        // Force the native config validation to fail so the transaction never commits.
        recordingBridge.validationResult = ValidationResult(false, "config invalid")

        viewModel.save.saveAndApplyConfig()
        awaitState(viewModel) { it.errorMessage != null }

        assertNotNull("a failed save must retain the draft for retry", viewModel.identityDraft.copyForSave())
        assertFalse("a failed save must not persist the identity", deps.identityRepository.hasEncryptedIdentity())
    }

    /** Drives the wizard to a valid Review state whose identity comes from a generated draft. */
    private fun prepareReviewFromGeneratedDraft(viewModel: SetupViewModel) {
        deps.forwardsStore.saveForwards(
            listOf(ForwardConfig(id = "svc", name = "svc", localPort = 8080, remoteForwardId = "svc", enabled = true)),
        )
        recordingBridge.validationResult = ValidationResult(true, null)
        viewModel.setInput(
            SetupConfigInput(
                localPeerId = "android-phone",
                brokerHost = "broker.local",
                remotePeerId = "remote-peer",
            ),
        )
        viewModel.identity.generateIdentity()
        awaitState(viewModel) { it.saveResult == "Identity generated" }
        viewModel.setImportPublicIdentity("kid peer")
        viewModel.setInput(
            viewModel.state.value.input.copy(
                localPeerId = "android-phone",
                brokerHost = "broker.local",
                remotePeerId = "remote-peer",
            ),
        )
        while (viewModel.state.value.currentStep != SetupStep.Review) {
            val before = viewModel.state.value.currentStep
            viewModel.goNext()
            awaitState(viewModel) { !it.isBusy }
            if (viewModel.state.value.currentStep == before) break
        }
    }
}
