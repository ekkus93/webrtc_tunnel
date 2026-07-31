package com.phillipchin.webrtctunnel.viewmodel

import android.net.Uri
import com.phillipchin.webrtctunnel.awaitCondition
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.IdentityValidationResult
import com.phillipchin.webrtctunnel.model.SetupConfigInput
import com.phillipchin.webrtctunnel.model.ValidationResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
        awaitCondition(currentValue = { viewModel.state.value }, predicate = predicate, description = "setup state")

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
        assertFalse(deps.identityRepository.hasEncryptedIdentity)
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
        assertFalse(deps.identityRepository.hasEncryptedIdentity)
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
        assertFalse(deps.identityRepository.hasEncryptedIdentity)
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

    // FIX8 P0-001-E: abandoning setup after touching every draft-only surface (identity
    // generation, a forward edit) must leave every authoritative file byte-exact/presence
    // unchanged — cancel() discards the draft; it never commits anything.
    @Test
    fun abandoningSetupWizardLeavesEveryAuthoritativeFileByteExact() {
        val authorizedKeysFile = File(app.filesDir, "authorized_keys")
        val forwardsFile = File(app.filesDir, "forwards.json")
        val configFile = File(app.filesDir, "config.toml")
        val setupInputFile = File(app.filesDir, "setup_input.json")

        // Construct first and let the ViewModel's own baseline load settle (it seeds a
        // missing forwards.json with defaults on first read, same as any other caller) —
        // the "before" snapshot must be taken after that legitimate one-time seeding, not
        // before it, so this test isolates the effect of the setup actions themselves.
        val viewModel = SetupViewModel(deps)
        awaitCondition(currentValue = { viewModel.loadState.value }, predicate = { it is SetupLoadState.Ready })
        val before =
            listOf(identityFile, publicFile, authorizedKeysFile, forwardsFile, configFile, setupInputFile)
                .associateWith { snapshot(it) }

        viewModel.setInput(viewModel.state.value.input.copy(localPeerId = "abandon-peer"))
        viewModel.identity.generateIdentity()
        awaitState(viewModel) { it.saveResult == "Identity generated" }
        viewModel.forwardsEditor.upsertForward(
            ForwardConfig(
                id = "abandoned",
                name = "abandoned",
                localPort = 7777,
                remoteForwardId = "abandoned",
                enabled = true,
            ),
        )
        awaitState(viewModel) { it.saveResult == "Forward draft updated" }

        viewModel.cancel()

        before.forEach { (file, snap) -> assertUnchanged(snap, file, file.name) }
        assertNull("cancel must drop the identity draft", viewModel.identityDraft.copyForSave())
    }

    @Test
    fun successfulFinalSaveWipesAndClearsDraft() {
        val viewModel = SetupViewModel(deps)
        prepareReviewFromGeneratedDraft(viewModel)

        viewModel.save.saveAndApplyConfig()
        awaitState(viewModel) { it.saveResult == "Configuration saved" }

        assertNull("successful save must clear the draft", viewModel.identityDraft.copyForSave())
        assertTrue("save must persist the identity", deps.identityRepository.hasEncryptedIdentity)
    }

    // FIX8 P0-001-E: named failedFinalSaveWipesAttemptCopyButRetainsRetryableDraft. The failed
    // attempt's own resolved-identity bytes (validateAndCommit's `finally` wipes
    // `identity.privateIdentity` — a copy from `identityDraft.copyForSave()`, not the live draft
    // itself) are gone, but the LIVE draft survives — proven not just by its presence but by
    // actually retrying: a second save, with validation now passing, must succeed using the
    // SAME retained draft.
    @Test
    fun failedFinalSaveWipesAttemptCopyButRetainsRetryableDraft() {
        val viewModel = SetupViewModel(deps)
        prepareReviewFromGeneratedDraft(viewModel)
        // Force the native config validation to fail so the transaction never commits.
        recordingBridge.validationResult = ValidationResult(false, "config invalid")

        viewModel.save.saveAndApplyConfig()
        awaitState(viewModel) { it.errorMessage != null }

        val retained = viewModel.identityDraft.copyForSave()
        assertNotNull("a failed save must retain the draft for retry", retained)
        assertFalse(
            "the failed attempt's own resolved-identity copy must be wiped, not just abandoned",
            retained!!.privateIdentity.all { it == 0.toByte() },
        )
        assertFalse("a failed save must not persist the identity", deps.identityRepository.hasEncryptedIdentity)

        // Retry: validation now passes, using the SAME retained draft.
        recordingBridge.validationResult = ValidationResult(true, null)
        viewModel.save.saveAndApplyConfig()
        val state = awaitState(viewModel) { it.saveResult == "Configuration saved" }

        assertEquals(null, state.errorMessage)
        assertTrue("the retry must actually persist the identity", deps.identityRepository.hasEncryptedIdentity)
        assertNull("a successful retry must clear the draft", viewModel.identityDraft.copyForSave())
    }

    @Test
    fun pathFileReplacementAfterValidationCannotChangeCommittedIdentity() {
        val identityFile =
            File(app.filesDir, "toctou_identity.toml").apply {
                writeText("peer_id = \"android-phone\"\nsecret = \"original\"")
            }
        recordingBridge.privateIdentityValidationResult =
            IdentityValidationResult(
                valid = true,
                canonicalPublicIdentity = "canon-original",
                canonicalPrivateIdentity = "canon-original-private",
                peerId = "android-phone",
            )
        val viewModel = SetupViewModel(deps)
        viewModel.setImportIdentityPath(identityFile.absolutePath)
        viewModel.identity.importIdentityFromPath()
        awaitState(viewModel) { it.saveResult == "Identity imported" }

        // FIX8 P0-001-D: replace the file on disk (and what the bridge would now return) AFTER
        // the import validated and populated the draft, but BEFORE final save. The save path
        // must never re-read the path, so this replacement must have zero effect on what
        // actually gets committed.
        identityFile.writeText("peer_id = \"android-phone\"\nsecret = \"replaced-after-validation\"")
        recordingBridge.privateIdentityValidationResult =
            IdentityValidationResult(
                valid = true,
                canonicalPublicIdentity = "canon-REPLACED",
                canonicalPrivateIdentity = "canon-REPLACED-private",
                peerId = "android-phone",
            )

        deps.forwardsStore.saveForwards(
            listOf(ForwardConfig(id = "svc", name = "svc", localPort = 8080, remoteForwardId = "svc", enabled = true)),
        )
        recordingBridge.validationResult = ValidationResult(true, null)
        viewModel.setImportPublicIdentity("kid peer")
        viewModel.setInput(
            viewModel.state.value.input.copy(brokerHost = "broker.local", remotePeerId = "remote-peer"),
        )
        while (viewModel.state.value.currentStep != SetupStep.Review) {
            val before = viewModel.state.value.currentStep
            viewModel.goNext()
            if (viewModel.state.value.currentStep == before) break
        }

        viewModel.save.saveAndApplyConfig()
        awaitState(viewModel) { it.saveResult == "Configuration saved" }

        assertEquals("canon-original", deps.identityRepository.readPublicIdentity())
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
