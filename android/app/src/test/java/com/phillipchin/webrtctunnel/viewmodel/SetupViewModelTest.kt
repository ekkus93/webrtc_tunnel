package com.phillipchin.webrtctunnel.viewmodel

import com.phillipchin.webrtctunnel.TunnelForegroundService
import com.phillipchin.webrtctunnel.awaitCondition
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.SetupConfigInput
import com.phillipchin.webrtctunnel.model.ValidationResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class SetupViewModelTest : AppViewModelTestBase() {
    @Test
    fun setupViewModelDelegatesValidationAndSave() {
        val viewModel = SetupViewModel(deps)
        prepareValidReviewState(viewModel)
        viewModel.save.saveAndApplyConfig()
        awaitSetupState(viewModel) { it.saveResult == "Configuration saved" }
        assertTrue(configRepository.configContents.contains("broker.local"))
        assertEquals(null, Shadows.shadowOf(app).nextStartedService)
    }

    @Test
    fun setupViewModelBlocksNextWhenBrokerInvalid() {
        val viewModel = SetupViewModel(deps)
        val identityFile =
            File(app.filesDir, "incoming_identity_for_validation.toml").apply {
                writeText("peer_id = \"android-phone\"\nsecret = \"abc\"")
            }
        viewModel.goNext()
        viewModel.setImportIdentityPath(identityFile.absolutePath)
        viewModel.goNext()
        viewModel.setInput(viewModel.state.value.input.copy(brokerHost = "", brokerPort = 0))
        viewModel.goNext()
        assertEquals(SetupStep.Broker, viewModel.state.value.currentStep)
        assertTrue(viewModel.state.value.errorMessage?.contains("Broker host") == true)
    }

    @Test
    fun setupViewModelBlocksSaveWhenLocalPeerIdMismatchesIdentityPeerId() {
        val viewModel = SetupViewModel(deps)
        val identityFile =
            File(app.filesDir, "incoming_identity_peer_mismatch.toml").apply {
                writeText("peer_id = \"android-phone\"\nsecret = \"abc\"")
            }
        deps.forwardsStore.saveForwards(
            listOf(ForwardConfig(id = "svc", name = "svc", localPort = 8080, remoteForwardId = "svc", enabled = true)),
        )
        viewModel.setImportIdentityPath(identityFile.absolutePath)
        viewModel.identity.importIdentityFromPath()
        viewModel.setImportPublicIdentity("kid peer")
        viewModel.identity.validateRemotePublicIdentity()
        viewModel.setInput(
            viewModel.state.value.input.copy(
                localPeerId = "different-peer",
                brokerHost = "broker.local",
                remotePeerId = "remote-peer",
            ),
        )
        while (viewModel.state.value.currentStep != SetupStep.Review) {
            viewModel.goNext()
        }
        viewModel.save.saveAndApplyConfig()
        val state = awaitSetupState(viewModel) { it.errorMessage != null }
        assertTrue(state.errorMessage?.contains("Local peer ID must match private identity peer ID") == true)
    }

    @Test
    fun setupViewModelBlocksStartWhenRemotePeerDoesNotMatchPublicIdentityPeerId() {
        val viewModel = SetupViewModel(deps)
        val identityFile =
            File(app.filesDir, "incoming_identity_remote_mismatch.toml").apply {
                writeText("peer_id = \"android-phone\"\nsecret = \"abc\"")
            }
        deps.forwardsStore.saveForwards(
            listOf(ForwardConfig(id = "svc", name = "svc", localPort = 8080, remoteForwardId = "svc", enabled = true)),
        )
        viewModel.setImportIdentityPath(identityFile.absolutePath)
        // FIX8 P0-001-D: the save path no longer re-reads importIdentityPath — the draft is
        // populated by actually running the import action (draft-only, no live mutation).
        viewModel.identity.importIdentityFromPath()
        viewModel.setImportPublicIdentity("kid peer")
        viewModel.setInput(
            viewModel.state.value.input.copy(
                localPeerId = "android-phone",
                brokerHost = "broker.local",
                remotePeerId = "remote-peer",
            ),
        )
        while (viewModel.state.value.currentStep != SetupStep.Review) {
            viewModel.goNext()
        }
        viewModel.setInput(viewModel.state.value.input.copy(remotePeerId = "desktop-peer"))
        viewModel.save.startTunnelFromReview()
        val state = awaitSetupState(viewModel) { it.errorMessage != null }
        assertTrue(state.errorMessage?.contains("Remote peer ID must match imported public identity peer ID") == true)
        assertEquals(null, Shadows.shadowOf(app).nextStartedService)
    }

    @Test
    fun setupViewModelStartTunnelWaitsForPreferenceSave() {
        val gate = CompletableDeferred<Unit>()
        val viewModel =
            SetupViewModel(
                deps,
                persistPreferences = {
                    gate.await()
                    deps.configRepository.savePreferences(it)
                },
            )
        prepareValidReviewState(viewModel)
        viewModel.save.startTunnelFromReview()
        assertEquals(null, Shadows.shadowOf(app).nextStartedService)
        gate.complete(Unit)
        val state = awaitSetupState(viewModel) { it.saveResult == "Tunnel start requested" }
        assertEquals("Tunnel start requested", state.saveResult)
        assertEquals(TunnelForegroundService.ACTION_START_OFFER, Shadows.shadowOf(app).nextStartedService.action)
    }

    // FIX8 P0-002-C: validation/config rendering and the persisted SetupPersistenceRequest must
    // derive from the SAME preference read, not two separate reads that could observe different
    // values. Proven by counting `loadPreferences` invocations for one successful save: exactly
    // one, not the two the old design (render-time read + a second commitSetup-time read) made.
    @Test
    fun setupValidationAndCommitUseSamePreferenceSnapshot() {
        val loadCount = AtomicInteger(0)
        val viewModel =
            SetupViewModel(
                deps,
                loadPreferences = {
                    loadCount.incrementAndGet()
                    deps.configRepository.preferences.first()
                },
            )
        prepareValidReviewState(viewModel)

        viewModel.save.saveAndApplyConfig()
        awaitSetupState(viewModel) { it.saveResult == "Configuration saved" }

        assertEquals(
            "the preference snapshot used for rendering and persistence must be read exactly once",
            1,
            loadCount.get(),
        )
    }

    @Test
    fun setupViewModelFailedPreferenceSavePreventsStartAndShowsError() {
        val viewModel =
            SetupViewModel(
                deps,
                persistPreferences = { throw IllegalStateException("prefs save failed") },
            )
        prepareValidReviewState(viewModel)
        viewModel.save.startTunnelFromReview()
        val state = awaitSetupState(viewModel) { it.errorMessage != null }
        assertTrue(state.errorMessage?.contains("prefs save failed") == true)
        assertEquals(null, Shadows.shadowOf(app).nextStartedService)
    }

    // FIX6 P1-005-C: two rapid setup saves must not overlap — the second is rejected visibly
    // (atomic operation lock), so only one save runs the persistence.
    @Test
    fun twoRapidSetupSavesOnlyOneOperationRuns() {
        val gate = CompletableDeferred<Unit>()
        val prefsWrites = AtomicInteger(0)
        val viewModel =
            SetupViewModel(
                deps,
                persistPreferences = {
                    prefsWrites.incrementAndGet()
                    gate.await()
                    deps.configRepository.savePreferences(it)
                },
            )
        prepareValidReviewState(viewModel)

        viewModel.save.saveAndApplyConfig() // acquires the lock, blocks at the gated preference write
        viewModel.save.saveAndApplyConfig() // must be rejected while the first is in flight

        val rejected = awaitSetupState(viewModel) { it.errorMessage?.contains("already in progress") == true }
        assertTrue(rejected.errorMessage!!.contains("already in progress"))

        gate.complete(Unit)
        val done = awaitSetupState(viewModel) { it.saveResult == "Configuration saved" }
        assertEquals("Configuration saved", done.saveResult)
        assertEquals("only one save may run the persistence", 1, prefsWrites.get())
    }

    @Test
    fun setupViewModelSuccessfulStartRequestsServiceOnce() {
        val viewModel = SetupViewModel(deps)
        prepareValidReviewState(viewModel)
        viewModel.save.startTunnelFromReview()
        val state = awaitSetupState(viewModel) { it.saveResult == "Tunnel start requested" }
        assertEquals("Tunnel start requested", state.saveResult)
        assertEquals(TunnelForegroundService.ACTION_START_OFFER, Shadows.shadowOf(app).nextStartedService.action)
        assertEquals(null, Shadows.shadowOf(app).nextStartedService)
    }

    @Test
    fun setupViewModelFailedConfigValidationPreventsStartAndShowsError() {
        val viewModel = SetupViewModel(deps)
        prepareValidReviewState(viewModel)
        recordingBridge.validationResult = ValidationResult(false, "invalid review config")

        viewModel.save.startTunnelFromReview()

        val state = awaitSetupState(viewModel) { it.errorMessage != null }
        assertTrue(state.errorMessage?.contains("invalid review config") == true)
        assertEquals(null, Shadows.shadowOf(app).nextStartedService)
    }

    // --- Step navigation ---

    @Test
    fun goNextFromModeAdvancesToIdentity() {
        val viewModel = SetupViewModel(deps)
        assertEquals(SetupStep.Mode, viewModel.state.value.currentStep)
        viewModel.goNext()
        assertEquals(SetupStep.Identity, viewModel.state.value.currentStep)
        assertEquals(null, viewModel.state.value.errorMessage)
    }

    @Test
    fun goBackAtFirstStepIsNoOp() {
        val viewModel = SetupViewModel(deps)
        assertEquals(SetupStep.Mode, viewModel.state.value.currentStep)
        viewModel.goBack()
        assertEquals(SetupStep.Mode, viewModel.state.value.currentStep)
    }

    @Test
    fun goBackReturnsToPreviousStepAndClearsError() {
        val viewModel = SetupViewModel(deps)
        viewModel.goNext() // Mode -> Identity
        viewModel.goNext() // blocked at Identity (no identity), error set
        assertEquals(SetupStep.Identity, viewModel.state.value.currentStep)
        assertTrue(viewModel.state.value.errorMessage != null)

        viewModel.goBack()
        assertEquals(SetupStep.Mode, viewModel.state.value.currentStep)
        assertEquals(null, viewModel.state.value.errorMessage)
    }

    @Test
    fun identityStepBlocksAdvanceWithoutIdentity() {
        val viewModel = SetupViewModel(deps)
        viewModel.goNext() // Mode -> Identity
        viewModel.goNext() // attempt Identity -> Broker
        assertEquals(SetupStep.Identity, viewModel.state.value.currentStep)
        assertTrue(
            viewModel.state.value.errorMessage?.contains("Import or generate a private identity") == true,
        )
    }

    @Test
    fun peerStepBlocksAdvanceWhenRemotePeerIdBlank() {
        val viewModel = SetupViewModel(deps)
        viewModel.setImportIdentityPath(navIdentityFile("peer_block").absolutePath)
        viewModel.setInput(viewModel.state.value.input.copy(brokerHost = "broker.local", remotePeerId = ""))
        advanceTo(viewModel, SetupStep.Peer)
        assertEquals(SetupStep.Peer, viewModel.state.value.currentStep)

        viewModel.goNext() // attempt Peer -> Forwards
        assertEquals(SetupStep.Peer, viewModel.state.value.currentStep)
        assertTrue(viewModel.state.value.errorMessage?.contains("Remote peer id is required") == true)
    }

    @Test
    fun forwardsStepBlocksAdvanceWhenNoEnabledForward() {
        val viewModel = newValidViewModel("forwards_block")
        deps.forwardsStore.saveForwards(emptyList()) // clear the seeded default forward
        advanceTo(viewModel, SetupStep.Forwards)
        assertEquals(SetupStep.Forwards, viewModel.state.value.currentStep)

        viewModel.goNext() // attempt Forwards -> NetworkPolicy with no forwards saved
        assertEquals(SetupStep.Forwards, viewModel.state.value.currentStep)
        assertTrue(viewModel.state.value.errorMessage?.contains("Enable at least one forward") == true)
    }

    @Test
    fun forwardsStepCannotAdvanceUntilAForwardIsEnabled() {
        val viewModel = newValidViewModel("forwards_canadvance")
        deps.forwardsStore.saveForwards(
            listOf(ForwardConfig(id = "svc", name = "svc", localPort = 8080, remoteForwardId = "svc", enabled = false)),
        )
        viewModel.forwardsEditor.refreshForwards()
        advanceTo(viewModel, SetupStep.Forwards)
        assertEquals(SetupStep.Forwards, viewModel.state.value.currentStep)
        // canAdvance must mirror save-time validation: disabled-only forwards block the step.
        assertTrue(!viewModel.canAdvanceFromCurrentStep())

        deps.forwardsStore.saveForwards(
            listOf(ForwardConfig(id = "svc", name = "svc", localPort = 8080, remoteForwardId = "svc", enabled = true)),
        )
        viewModel.forwardsEditor.refreshForwards()
        assertTrue(viewModel.canAdvanceFromCurrentStep())
    }

    // FIX8 P0-001-C/E: deleteForward/upsertForward now change only the in-memory wizard draft —
    // there is no longer a real persistence write during a live setup edit for one to fail, so
    // this test (previously named deleteForwardFailurePreservesErrorAndDoesNotClaimSuccess,
    // which forced ForwardsConfigStore.saveForwards' temp-file creation to fail via a read-only
    // files dir) no longer has a production path to exercise. Replaced with the named P0-001-E
    // test proving the actual P0-001-C invariant: the real forwards.json AND config.toml must be
    // byte-exact/presence untouched by a setup-wizard delete, even though the in-memory draft
    // does reflect it — proving the edit truly never reaches ForwardsRepository/ConfigRepository/
    // disk before final save.
    @Test
    fun setupWizardForwardDeleteDoesNotMutateLiveForwardsOrConfigBeforeFinalSave() {
        val viewModel = SetupViewModel(deps)
        viewModel.forwardsEditor.refreshForwards()
        awaitSetupState(viewModel) { viewModel.forwards.value.isNotEmpty() }
        val existing = viewModel.forwards.value.first()
        val forwardsFile = File(app.filesDir, "forwards.json")
        val configFile = File(app.filesDir, "config.toml")
        val priorForwardsBytes = forwardsFile.readBytes()
        val configExistedBefore = configFile.exists()

        viewModel.forwardsEditor.deleteForward(existing.id)

        val state = awaitSetupState(viewModel) { it.saveResult == "Forward draft removed" }
        assertEquals(null, state.errorMessage)
        assertTrue(
            "the wizard draft must reflect the delete",
            viewModel.forwards.value.none { it.id == existing.id },
        )
        assertArrayEquals(
            "a setup-wizard delete must never write the authoritative forwards.json before final save",
            priorForwardsBytes,
            forwardsFile.readBytes(),
        )
        assertEquals(
            "a setup-wizard forward edit must never create/remove config.toml before final save",
            configExistedBefore,
            configFile.exists(),
        )
        assertTrue(
            "the authoritative repository's own in-memory state must also be untouched",
            deps.forwardsRepository.current().any { it.id == existing.id },
        )
    }

    // FIX8 P0-001-C/E: symmetric to the delete-side test above, for upsertForward.
    @Test
    fun setupWizardForwardUpsertDoesNotMutateLiveForwardsOrConfigBeforeFinalSave() {
        val viewModel = SetupViewModel(deps)
        viewModel.forwardsEditor.refreshForwards()
        awaitSetupState(viewModel) { viewModel.forwards.value.isNotEmpty() }
        val forwardsFile = File(app.filesDir, "forwards.json")
        val configFile = File(app.filesDir, "config.toml")
        val priorForwardsBytes = forwardsFile.readBytes()
        val configExistedBefore = configFile.exists()
        val newForward =
            ForwardConfig(
                id = "new-svc",
                name = "new-svc",
                localPort = 9999,
                remoteForwardId = "new-svc",
                enabled = true,
            )

        viewModel.forwardsEditor.upsertForward(newForward)

        val state = awaitSetupState(viewModel) { it.saveResult == "Forward draft updated" }
        assertEquals(null, state.errorMessage)
        assertTrue(
            "the wizard draft must reflect the upsert",
            viewModel.forwards.value.any { it.id == "new-svc" },
        )
        assertArrayEquals(
            "a setup-wizard upsert must never write the authoritative forwards.json before final save",
            priorForwardsBytes,
            forwardsFile.readBytes(),
        )
        assertEquals(
            "a setup-wizard forward edit must never create/remove config.toml before final save",
            configExistedBefore,
            configFile.exists(),
        )
        assertTrue(
            "the authoritative repository's own in-memory state must also be untouched",
            deps.forwardsRepository.current().none { it.id == "new-svc" },
        )
    }

    @Test
    fun deleteForwardSuccessStillReportsSuccess() {
        val viewModel = SetupViewModel(deps)
        viewModel.forwardsEditor.refreshForwards()
        awaitSetupState(viewModel) { viewModel.forwards.value.isNotEmpty() }
        val existing = viewModel.forwards.value.first()

        viewModel.forwardsEditor.deleteForward(existing.id)

        val state = awaitSetupState(viewModel) { it.saveResult == "Forward draft removed" }
        assertEquals(null, state.errorMessage)
        assertTrue(viewModel.forwards.value.none { it.id == existing.id })
    }

    @Test
    fun goNextClearsPreviousErrorOnSuccessfulAdvance() {
        val viewModel = newValidViewModel("clear_error")
        deps.forwardsStore.saveForwards(emptyList()) // clear the seeded default forward
        advanceTo(viewModel, SetupStep.Forwards)
        viewModel.goNext() // blocked: no enabled forward
        assertEquals(SetupStep.Forwards, viewModel.state.value.currentStep)
        assertTrue(viewModel.state.value.errorMessage != null)

        // Fixing the underlying condition outside the wizard setters must not clear the error on its own.
        saveEnabledForward()
        assertTrue(viewModel.state.value.errorMessage != null)

        viewModel.goNext() // now passes
        assertEquals(SetupStep.NetworkPolicy, viewModel.state.value.currentStep)
        assertEquals(null, viewModel.state.value.errorMessage)
    }

    @Test
    fun goNextOnReviewStepIsNoOp() {
        val viewModel = newValidViewModel("review_noop")
        saveEnabledForward()
        advanceTo(viewModel, SetupStep.Review)
        assertEquals(SetupStep.Review, viewModel.state.value.currentStep)

        viewModel.goNext()
        assertEquals(SetupStep.Review, viewModel.state.value.currentStep)
        assertEquals(null, viewModel.state.value.errorMessage)
    }

    @Test
    fun missingSetupInputFileLeavesNoErrorAndDefaultInput() {
        val setupInputFile = File(app.filesDir, "setup_input.json")
        assertTrue(!setupInputFile.exists())

        val viewModel = SetupViewModel(deps)

        assertEquals(null, viewModel.state.value.errorMessage)
        assertEquals(SetupConfigInput().brokerHost, viewModel.state.value.input.brokerHost)
        assertEquals("", viewModel.state.value.input.remotePeerId)
    }

    @Test
    fun validSetupInputFilePrefillsInputWithoutError() {
        val setupInputFile = File(app.filesDir, "setup_input.json")
        deps.configRepository.saveSetupInput(
            SetupConfigInput(brokerHost = "saved-broker.local", remotePeerId = "saved-remote-peer"),
        )
        assertTrue(setupInputFile.exists())

        val viewModel = SetupViewModel(deps)

        assertEquals(null, viewModel.state.value.errorMessage)
        assertEquals("saved-broker.local", viewModel.state.value.input.brokerHost)
        assertEquals("saved-remote-peer", viewModel.state.value.input.remotePeerId)
    }

    @Test
    fun corruptSetupInputFileShowsVisibleErrorAndDefaultInput() {
        val setupInputFile = File(app.filesDir, "setup_input.json")
        setupInputFile.parentFile?.mkdirs()
        setupInputFile.writeText("{ corrupt json")

        val viewModel = SetupViewModel(deps)

        assertTrue(viewModel.state.value.errorMessage != null)
        // Must not silently present a blank draft the user could then unknowingly save
        // over the actual (corrupt) saved one.
        assertEquals(SetupConfigInput().brokerHost, viewModel.state.value.input.brokerHost)
        assertEquals("", viewModel.state.value.input.remotePeerId)
    }

    @Test
    fun corruptSetupInputFileBytesAreUnchangedAfterViewModelInit() {
        val setupInputFile = File(app.filesDir, "setup_input.json")
        setupInputFile.parentFile?.mkdirs()
        val corruptContent = "{ corrupt json"
        setupInputFile.writeText(corruptContent)

        SetupViewModel(deps)

        assertEquals(corruptContent, setupInputFile.readText())
    }

    @Test
    fun sequentialGoNextVisitsEveryStepInOrder() {
        val viewModel = newValidViewModel("sequence")
        saveEnabledForward()

        val visited = mutableListOf(viewModel.state.value.currentStep)
        repeat(SetupStep.entries.size - 1) {
            viewModel.goNext()
            visited.add(viewModel.state.value.currentStep)
        }

        assertEquals(
            listOf(
                SetupStep.Mode,
                SetupStep.Identity,
                SetupStep.Broker,
                SetupStep.Peer,
                SetupStep.Forwards,
                SetupStep.NetworkPolicy,
                SetupStep.Review,
            ),
            visited,
        )
        assertEquals(null, viewModel.state.value.errorMessage)
    }

    @Test
    fun goBackFromReviewWalksBackToMode() {
        val viewModel = newValidViewModel("back_sequence")
        saveEnabledForward()
        advanceTo(viewModel, SetupStep.Review)
        assertEquals(SetupStep.Review, viewModel.state.value.currentStep)

        val visited = mutableListOf(viewModel.state.value.currentStep)
        repeat(SetupStep.entries.size - 1) {
            viewModel.goBack()
            visited.add(viewModel.state.value.currentStep)
        }

        assertEquals(
            listOf(
                SetupStep.Review,
                SetupStep.NetworkPolicy,
                SetupStep.Forwards,
                SetupStep.Peer,
                SetupStep.Broker,
                SetupStep.Identity,
                SetupStep.Mode,
            ),
            visited,
        )
    }

    private fun navIdentityFile(tag: String): File =
        File(app.filesDir, "nav_identity_$tag.toml").apply {
            writeText("peer_id = \"android-phone\"\nsecret = \"abc\"")
        }

    private fun newValidViewModel(tag: String): SetupViewModel {
        val viewModel = SetupViewModel(deps)
        viewModel.setImportIdentityPath(navIdentityFile(tag).absolutePath)
        viewModel.setImportPublicIdentity("kid peer")
        viewModel.setInput(
            viewModel.state.value.input.copy(
                brokerHost = "broker.local",
                remotePeerId = "remote-peer",
            ),
        )
        return viewModel
    }

    private fun saveEnabledForward() {
        deps.forwardsStore.saveForwards(
            listOf(ForwardConfig(id = "svc", name = "svc", localPort = 8080, remoteForwardId = "svc", enabled = true)),
        )
    }

    private fun advanceTo(
        viewModel: SetupViewModel,
        target: SetupStep,
    ) {
        var guard = 0
        while (viewModel.state.value.currentStep != target && guard < SetupStep.entries.size) {
            val before = viewModel.state.value.currentStep
            viewModel.goNext()
            if (viewModel.state.value.currentStep == before) break // blocked by validation
            guard += 1
        }
    }

    private fun prepareValidReviewState(viewModel: SetupViewModel) {
        val identityFile =
            File(app.filesDir, "incoming_identity.toml").apply {
                writeText("peer_id = \"android-phone\"\nsecret = \"abc\"")
            }
        val forward = ForwardConfig(id = "svc", name = "svc", localPort = 8080, remoteForwardId = "svc", enabled = true)
        deps.forwardsStore.saveForwards(listOf(forward))
        recordingBridge.validationResult = ValidationResult(true, null)
        viewModel.setImportIdentityPath(identityFile.absolutePath)
        // FIX8 P0-001-D: the save path no longer re-reads importIdentityPath — the draft is
        // populated by actually running the import action (draft-only, no live mutation).
        viewModel.identity.importIdentityFromPath()
        viewModel.setImportPublicIdentity("kid peer")
        viewModel.setInput(
            viewModel.state.value.input.copy(
                brokerHost = "broker.local",
                remotePeerId = "remote-peer",
            ),
        )
        while (viewModel.state.value.currentStep != SetupStep.Review) {
            viewModel.goNext()
        }
    }

    private fun awaitSetupState(
        viewModel: SetupViewModel,
        predicate: (SetupWizardState) -> Boolean,
    ): SetupWizardState =
        awaitCondition(currentValue = { viewModel.state.value }, predicate = predicate, description = "setup state")
}
