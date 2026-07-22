package com.phillipchin.webrtctunnel.viewmodel

import android.net.Uri
import android.os.Looper
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.IdentityValidationResult
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.model.ValidationResult
import com.phillipchin.webrtctunnel.network.NetworkPolicyManager
import com.phillipchin.webrtctunnel.security.IdentityCrypto
import com.phillipchin.webrtctunnel.security.IdentityRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.io.File

/**
 * SetupIdentityController: generateIdentity / importIdentityFromUri / importPublicIdentityFromUri
 * (P1-001, FIX8 P0-001-B draft-only behavior). Split out of SetupViewModelTest to stay under
 * detekt's LargeClass threshold.
 */
@RunWith(RobolectricTestRunner::class)
class SetupIdentityActionsTest : AppViewModelTestBase() {
    @Test
    fun generateIdentitySucceedsAndPopulatesDraftWithoutPersisting() {
        val viewModel = SetupViewModel(deps)
        viewModel.setInput(viewModel.state.value.input.copy(localPeerId = "generated-peer"))

        viewModel.identity.generateIdentity()

        val state = awaitSetupState(viewModel) { it.saveResult == "Identity generated" }
        assertEquals("generated-peer", state.identityPeerId)
        assertEquals("canon", state.localPublicIdentity)
        assertEquals(null, state.errorMessage)
        assertEquals(false, state.isBusy)
        // FIX8 P0-001-B: generation is draft-only — nothing is persisted before final save.
        assertEquals("", deps.identityRepository.readPublicIdentity())
        assertEquals(false, deps.identityRepository.hasEncryptedIdentity)
    }

    @Test
    fun generateIdentityFailureSurfacesBridgeMessageAndClearsBusy() {
        recordingBridge.generateIdentityResult =
            IdentityValidationResult(valid = false, message = "boom")
        val viewModel = SetupViewModel(deps)

        viewModel.identity.generateIdentity()

        val state = awaitSetupState(viewModel) { it.errorMessage != null }
        assertEquals("boom", state.errorMessage)
        assertEquals(false, state.isBusy)
        assertEquals(null, state.saveResult)
    }

    @Test
    fun generateIdentityWithIncompleteDataReportsError() {
        // Bridge reports success but omits the actual key material — the controller must
        // not treat this as a valid identity.
        recordingBridge.generateIdentityResult =
            IdentityValidationResult(
                valid = true,
                canonicalPublicIdentity = null,
                canonicalPrivateIdentity = null,
                peerId = "generated-peer",
            )
        val viewModel = SetupViewModel(deps)

        viewModel.identity.generateIdentity()

        val state = awaitSetupState(viewModel) { it.errorMessage != null }
        assertEquals("Identity generation returned incomplete data", state.errorMessage)
        assertEquals(false, state.isBusy)
    }

    @Test
    fun importIdentityFromUriSucceedsAndPopulatesDraftWithoutPersisting() {
        val file =
            File(app.filesDir, "uri_identity_success.toml").apply {
                writeText("peer_id = \"android-phone\"\nsecret = \"abc\"")
            }
        val viewModel = SetupViewModel(deps)

        viewModel.identity.importIdentityFromUri(Uri.fromFile(file))

        val state = awaitSetupState(viewModel) { it.saveResult == "Identity imported" }
        assertEquals("android-phone", state.identityPeerId)
        assertEquals("canon", state.localPublicIdentity)
        assertEquals(null, state.errorMessage)
        assertEquals(false, state.isBusy)
        // FIX8 P0-001-B: URI import is draft-only — nothing is persisted before final save.
        assertEquals("", deps.identityRepository.readPublicIdentity())
        assertEquals(false, deps.identityRepository.hasEncryptedIdentity)
    }

    @Test
    fun saveReportsStoredIdentityUnreadableDistinctlyFromMissing() {
        // A stored (not freshly-imported) identity that fails to load/validate must not be
        // reported as "missing" — that would tell the user their identity vanished, when it
        // actually exists but is broken (P1-007, following the same absent-vs-unreadable
        // distinction established for ForwardsViewModel/ImportExportService in P1-001).
        val unreadableIdentityRepository =
            IdentityRepository(
                app,
                object : IdentityCrypto {
                    override fun encrypt(plaintext: ByteArray): ByteArray = plaintext

                    override fun decrypt(payload: ByteArray): ByteArray = error("decrypt boom")
                },
            )
        unreadableIdentityRepository.storeEncryptedIdentity("garbage".toByteArray(), "canon-pub")
        val brokenDeps =
            AppDependencies(
                context = app,
                nativeBridgeFactory = { recordingBridge },
                configRepository = configRepository,
                networkPolicyManager =
                    NetworkPolicyManager {
                        NetworkType.UnmeteredWifi to false
                    },
                identityRepository = unreadableIdentityRepository,
                dispatchers = deps.dispatchers,
            )
        val viewModel = SetupViewModel(brokenDeps)
        val forward = ForwardConfig(id = "svc", name = "svc", localPort = 8080, remoteForwardId = "svc", enabled = true)
        brokenDeps.forwardsStore.saveForwards(listOf(forward))
        recordingBridge.validationResult = ValidationResult(true, null)
        // No importIdentityPath set: the wizard's Identity-step gate only checks presence
        // (hasEncryptedIdentity()), which is true here, so it lets the wizard reach Review
        // relying on the already-stored identity.
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

        viewModel.save.saveAndApplyConfig()

        val state = awaitSetupState(viewModel) { it.errorMessage != null }
        assertTrue(
            "expected a message distinguishing 'unreadable' from 'missing', got: ${state.errorMessage}",
            state.errorMessage?.contains("could not be loaded") == true ||
                state.errorMessage?.contains("invalid") == true,
        )
        assertTrue(
            "must not claim the identity is simply missing when it actually exists but is broken",
            state.errorMessage?.contains("Missing encrypted identity") != true,
        )
    }

    @Test
    fun importIdentityFromUriWithUnreadableUriReportsErrorWithoutCrashing() {
        val missingFile = File(app.filesDir, "does_not_exist_identity.toml")
        val viewModel = SetupViewModel(deps)

        viewModel.identity.importIdentityFromUri(Uri.fromFile(missingFile))

        val state = awaitSetupState(viewModel) { it.errorMessage != null }
        assertEquals(false, state.isBusy)
        assertEquals(null, state.saveResult)
    }

    @Test
    fun importIdentityFromUriWithInvalidContentReportsBridgeMessage() {
        recordingBridge.privateIdentityValidationResult =
            IdentityValidationResult(valid = false, message = "not a real identity")
        val file =
            File(app.filesDir, "uri_identity_invalid.toml").apply {
                writeText("garbage")
            }
        val viewModel = SetupViewModel(deps)

        viewModel.identity.importIdentityFromUri(Uri.fromFile(file))

        val state = awaitSetupState(viewModel) { it.errorMessage != null }
        assertEquals("not a real identity", state.errorMessage)
        assertEquals(false, state.isBusy)
    }

    @Test
    fun importPublicIdentityFromUriSucceeds() {
        val file =
            File(app.filesDir, "uri_public_identity_success.toml").apply {
                writeText("p2ptunnel-ed25519 peer_id=remote-peer sign_pub=AAAA kex_pub=BBBB")
            }
        val viewModel = SetupViewModel(deps)

        viewModel.identity.importPublicIdentityFromUri(Uri.fromFile(file))

        val state = awaitSetupState(viewModel) { it.saveResult == "Remote public identity validated" }
        assertEquals("remote-peer", state.remoteIdentityPeerId)
        assertEquals(null, state.errorMessage)
        assertEquals(false, state.isBusy)
    }

    @Test
    fun importPublicIdentityFromUriWithUnreadableUriReportsErrorWithoutCrashing() {
        val missingFile = File(app.filesDir, "does_not_exist_public_identity.toml")
        val viewModel = SetupViewModel(deps)

        viewModel.identity.importPublicIdentityFromUri(Uri.fromFile(missingFile))

        val state = awaitSetupState(viewModel) { it.errorMessage != null }
        assertEquals(false, state.isBusy)
        assertEquals(null, state.saveResult)
    }

    @Test
    fun isBusyReturnsToFalseAfterEachIdentityOperation() {
        // SetupIdentityController's launchBusy has no re-entrancy guard (unlike e.g.
        // AndroidTunnelController.start()) — it only tracks the busy flag around a single
        // operation's lifetime. This pins down the actual contract: isBusy is false again
        // once each operation (success or failure) settles.
        val viewModel = SetupViewModel(deps)
        assertEquals(false, viewModel.state.value.isBusy)

        viewModel.identity.generateIdentity()
        assertEquals(false, awaitSetupState(viewModel) { it.saveResult != null || it.errorMessage != null }.isBusy)
    }

    private fun awaitSetupState(
        viewModel: SetupViewModel,
        predicate: (SetupWizardState) -> Boolean,
    ): SetupWizardState =
        runBlocking {
            withTimeout(5_000) {
                var matched: SetupWizardState? = null
                while (true) {
                    val current = viewModel.state.value
                    if (predicate(current)) {
                        matched = current
                        break
                    }
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
                matched ?: error("Timed out waiting for setup state")
            }
        }
}
