package com.phillipchin.webrtctunnel.viewmodel

import android.os.Looper
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.ValidationResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.io.File

/**
 * FIX6 regression guard, updated for FIX7 P0-003: the native config validator requires the
 * config's referenced `authorized_keys` file to exist. Before FIX7 that meant writing the
 * proposed key to the LIVE file before validation; P0-003 instead validates against an isolated
 * workspace copy (never the live file) and only commits the live file afterward, through the
 * transactional coordinator. This test now asserts the FIX7 contract directly: validation must
 * succeed even though the live file never existed during it, and the live file must exist with
 * the new key only after a successful save — never before.
 */
@RunWith(RobolectricTestRunner::class)
class SetupSaveAuthorizedKeysTest : AppViewModelTestBase() {
    @Test
    fun remotePublicIdentityIsCommittedToAuthorizedKeysOnlyAfterSuccessfulSaveNotDuringValidation() {
        val liveAuthorizedKeys = File(app.filesDir, "authorized_keys")
        liveAuthorizedKeys.delete()
        var liveFileExistedDuringValidation = false
        // Mirrors the real native validator: it requires the config's OWN referenced
        // authorized_keys path (an isolated workspace copy under FIX7 P0-003, not the live
        // path) to exist and be non-blank. It also records whether the LIVE file existed at the
        // moment of validation, proving validation never touched it.
        recordingBridge.validateConfigWithIdentityHook = { configPath ->
            liveFileExistedDuringValidation = liveFileExistedDuringValidation || liveAuthorizedKeys.exists()
            val referencedPath =
                Regex("""authorized_keys\s*=\s*"([^"]*)"""")
                    .find(File(configPath).readText())
                    ?.groupValues
                    ?.get(1)
            val referenced = referencedPath?.let(::File)
            if (referenced != null && referenced.exists() && referenced.readText().isNotBlank()) {
                ValidationResult(true, null)
            } else {
                ValidationResult(false, "authorized_keys file does not exist")
            }
        }
        val viewModel = SetupViewModel(deps)
        driveWizardToReviewWithRemoteIdentity(viewModel)

        viewModel.save.saveAndApplyConfig()

        val state = awaitState(viewModel) { it.saveResult != null || it.errorMessage != null }
        assertEquals("save must succeed: ${state.errorMessage}", "Configuration saved", state.saveResult)
        assertFalse(
            "validation must never see the live authorized_keys file — it must be untouched " +
                "until the transactional commit",
            liveFileExistedDuringValidation,
        )
        assertTrue("authorized_keys must be committed live after a successful save", liveAuthorizedKeys.exists())
        assertTrue(liveAuthorizedKeys.readText().contains("kid peer"))
    }

    private fun driveWizardToReviewWithRemoteIdentity(viewModel: SetupViewModel) {
        val identityFile =
            File(app.filesDir, "auth_keys_regression_identity.toml").apply {
                writeText("peer_id = \"android-phone\"\nsecret = \"abc\"")
            }
        deps.forwardsStore.saveForwards(
            listOf(ForwardConfig(id = "svc", name = "svc", localPort = 8080, remoteForwardId = "svc", enabled = true)),
        )
        recordingBridge.validationResult = ValidationResult(true, null)
        viewModel.setImportIdentityPath(identityFile.absolutePath)
        viewModel.setImportPublicIdentity("kid peer")
        viewModel.setInput(
            viewModel.state.value.input.copy(brokerHost = "broker.local", remotePeerId = "remote-peer"),
        )
        while (viewModel.state.value.currentStep != SetupStep.Review) {
            viewModel.goNext()
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
}
