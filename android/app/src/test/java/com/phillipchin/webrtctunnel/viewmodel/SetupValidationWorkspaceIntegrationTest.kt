package com.phillipchin.webrtctunnel.viewmodel

import android.os.Looper
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.ValidationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.io.File

/**
 * FIX7 P0-003-F: validation-integration tests using byte snapshots of every live file, the actual
 * replacement for the misleading FIX6 validation test (see [SetupSaveAuthorizedKeysTest] for the
 * end-to-end "commits only after success" contract).
 */
@RunWith(RobolectricTestRunner::class)
class SetupValidationWorkspaceIntegrationTest : AppViewModelTestBase() {
    private fun liveFiles(): List<File> =
        listOf(
            File(app.filesDir, "identity.enc"),
            File(app.filesDir, "identity.pub"),
            File(app.filesDir, "authorized_keys"),
            File(app.filesDir, "runtime/mqtt_password.txt"),
            File(app.filesDir, "setup_input.json"),
            File(app.filesDir, "config.toml"),
        )

    private fun snapshotLiveFiles(): Map<String, ByteArray?> =
        liveFiles().associate { it.absolutePath to (if (it.exists()) it.readBytes() else null) }

    private fun driveWizardToReview(
        viewModel: SetupViewModel,
        brokerPassword: String = "",
    ): File {
        val identityFile =
            File(app.filesDir, "workspace_integration_identity.toml").apply {
                writeText("peer_id = \"android-phone\"\nsecret = \"plaintext-private-marker-xyz\"")
            }
        deps.forwardsStore.saveForwards(
            listOf(ForwardConfig(id = "svc", name = "svc", localPort = 8080, remoteForwardId = "svc", enabled = true)),
        )
        recordingBridge.validationResult = ValidationResult(true, null)
        viewModel.setImportIdentityPath(identityFile.absolutePath)
        viewModel.setImportPublicIdentity("kid peer")
        viewModel.setInput(
            viewModel.state.value.input.copy(
                brokerHost = "broker.local",
                remotePeerId = "remote-peer",
                brokerPassword = brokerPassword,
            ),
        )
        while (viewModel.state.value.currentStep != SetupStep.Review) {
            viewModel.goNext()
        }
        return identityFile
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
    fun setupValidationFailureDoesNotMutateLiveIdentityAuthorizedKeysSecretSetupPreferencesOrConfig() =
        runBlocking {
            recordingBridge.validateConfigWithIdentityHook = { ValidationResult(false, "forced validation failure") }
            val viewModel = SetupViewModel(deps)
            driveWizardToReview(viewModel, brokerPassword = "s3cret")
            val prefsBefore = deps.configRepository.preferences.first()
            val before = snapshotLiveFiles()

            viewModel.save.saveAndApplyConfig()
            val state = awaitState(viewModel) { it.errorMessage != null || it.saveResult != null }

            assertEquals("expected a validation failure, not success", null, state.saveResult)
            assertTrue(state.errorMessage != null)
            val after = snapshotLiveFiles()
            before.forEach { (path, bytes) ->
                assertTrue(
                    "$path must be byte-for-byte/absence-for-absence unchanged after a failed validation",
                    bytes.contentEqualsOrBothNull(after[path]),
                )
            }
            assertEquals(prefsBefore, deps.configRepository.preferences.first())
        }

    @Test
    fun setupValidationCancellationDoesNotMutateLiveState() {
        // Deterministic cancellation (matching SetupSaveControllerTest's own
        // configWriteCancellationPropagatesAndDoesNotReportFailureOrSuccess technique): throw
        // CancellationException directly from the native validation call site rather than
        // racing a real Job.cancel(), since the outer coroutine machinery (withContext,
        // withCleanupComposition) already correctly propagates it either way. A cancelled save
        // reports neither success nor failure, so — as in that existing test — we cannot poll
        // for a completion condition that structurally never becomes true; instead let the
        // cancelled coroutine settle for a bounded number of cycles and assert state directly.
        recordingBridge.validateConfigWithIdentityHook = { throw CancellationException("cancelled mid-validation") }
        val viewModel = SetupViewModel(deps)
        driveWizardToReview(viewModel, brokerPassword = "s3cret")
        val before = snapshotLiveFiles()

        viewModel.save.saveAndApplyConfig()
        runBlocking {
            repeat(20) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                delay(5)
            }
        }

        // Cancellation must report neither success nor failure (P0-001-B semantics) and must
        // not have mutated any live file.
        assertNull(viewModel.state.value.saveResult)
        assertNull(viewModel.state.value.errorMessage)
        val after = snapshotLiveFiles()
        before.forEach { (path, bytes) ->
            assertTrue(
                "$path must be unchanged after a cancelled validation",
                bytes.contentEqualsOrBothNull(after[path]),
            )
        }
    }

    @Test
    fun setupValidationWorkspaceContainsProposedAuthorizedKeyButLiveAuthorizedKeysDoesNot() =
        runBlocking {
            val liveAuthorizedKeys = File(app.filesDir, "authorized_keys")
            var workspaceContainedKey = false
            var liveContainedKeyDuringValidation = false
            recordingBridge.validateConfigWithIdentityHook = { configPath ->
                val configDir = File(configPath).parentFile
                val workspaceAuthorizedKeys = File(configDir, "authorized_keys")
                workspaceContainedKey =
                    workspaceAuthorizedKeys.exists() && workspaceAuthorizedKeys.readText().contains("kid peer")
                liveContainedKeyDuringValidation =
                    liveAuthorizedKeys.exists() && liveAuthorizedKeys.readText().contains("kid peer")
                ValidationResult(true, null)
            }
            val viewModel = SetupViewModel(deps)
            driveWizardToReview(viewModel)

            viewModel.save.saveAndApplyConfig()
            val state = awaitState(viewModel) { it.saveResult != null || it.errorMessage != null }

            assertEquals("save must succeed: ${state.errorMessage}", "Configuration saved", state.saveResult)
            assertTrue("the workspace authorized_keys copy must contain the proposed key", workspaceContainedKey)
            assertFalse(
                "the live authorized_keys file must not contain the key during validation",
                liveContainedKeyDuringValidation,
            )
        }

    @Test
    fun setupValidationNeverWritesPlaintextPrivateIdentityToDisk() =
        runBlocking {
            var scannedAnyFile = false
            var foundPlaintext = false
            recordingBridge.validateConfigWithIdentityHook = { configPath ->
                val workspaceRoot = File(configPath).parentFile
                workspaceRoot?.walkTopDown()?.filter { it.isFile }?.forEach { file ->
                    scannedAnyFile = true
                    if (file.readText().contains("plaintext-private-marker-xyz")) {
                        foundPlaintext = true
                    }
                }
                ValidationResult(true, null)
            }
            val viewModel = SetupViewModel(deps)
            driveWizardToReview(viewModel)

            viewModel.save.saveAndApplyConfig()
            val state = awaitState(viewModel) { it.saveResult != null || it.errorMessage != null }

            assertEquals("save must succeed: ${state.errorMessage}", "Configuration saved", state.saveResult)
            assertTrue("the hook must have actually scanned workspace files", scannedAnyFile)
            assertFalse("plaintext private identity must never appear in the workspace", foundPlaintext)
        }
}

private fun ByteArray?.contentEqualsOrBothNull(other: ByteArray?): Boolean =
    when {
        this == null && other == null -> true
        this == null || other == null -> false
        else -> this.contentEquals(other)
    }
