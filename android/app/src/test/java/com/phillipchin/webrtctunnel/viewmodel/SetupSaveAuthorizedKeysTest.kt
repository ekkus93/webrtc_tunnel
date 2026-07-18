package com.phillipchin.webrtctunnel.viewmodel

import android.os.Looper
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.ValidationResult
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
 * FIX6 regression guard: the native config validator requires the config's `authorized_keys`
 * file to exist (the config references it by path), so a remote public identity must be written
 * to `authorized_keys` BEFORE config validation, not only at commit-time. The B-2c "validate
 * without mutating" change moved that write to commit (after validation), so a first-time
 * remote-peer setup could never validate — a ship-breaking regression caught on-device by the
 * emulator smoke test and missed by the mock validator (which doesn't check file existence).
 */
@RunWith(RobolectricTestRunner::class)
class SetupSaveAuthorizedKeysTest : AppViewModelTestBase() {
    @Test
    fun remotePublicIdentityIsWrittenToAuthorizedKeysBeforeConfigValidation() {
        File(app.filesDir, "authorized_keys").delete()
        // Mirror the native validator: config validation fails unless authorized_keys exists.
        recordingBridge.validateConfigWithIdentityHook = {
            val ak = File(app.filesDir, "authorized_keys")
            if (ak.exists() && ak.readText().isNotBlank()) {
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
        assertTrue("authorized_keys must be written", File(app.filesDir, "authorized_keys").exists())
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
