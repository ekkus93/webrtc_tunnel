package com.phillipchin.webrtctunnel.viewmodel

import android.os.Looper
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

@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest : AppViewModelTestBase() {
    @Test
    fun settingsViewModelDelegatesValidation() {
        val viewModel = SettingsViewModel(deps)
        recordingBridge.validationResult = ValidationResult(true, "ok")
        assertEquals(ValidationResult(true, "ok"), viewModel.validateConfig())
    }

    @Test
    fun settingsViewModelReadsPublicIdentityExactlyOnce() {
        var readCount = 0
        val viewModel =
            SettingsViewModel(
                deps = deps,
                loadPublicIdentity = {
                    readCount += 1
                    "peer_id = \"android-phone\""
                },
            )
        awaitSettingsState(viewModel) { it.publicIdentity != null }
        assertEquals(1, readCount)
    }

    @Test
    fun settingsViewModelLoadsPublicIdentityIntoState() {
        deps.identityRepository.storeEncryptedIdentity("private".toByteArray(), "peer_id = \"android-phone\"")
        val viewModel = SettingsViewModel(deps)
        val state = awaitSettingsState(viewModel) { it.publicIdentity != null }
        assertEquals("peer_id = \"android-phone\"", state.publicIdentity)
        assertEquals(null, state.publicIdentityLoadError)
    }

    @Test
    fun settingsViewModelHandlesMissingPublicIdentity() {
        val viewModel = SettingsViewModel(deps)
        val state = awaitSettingsState(viewModel) { it.publicIdentity == null && it.publicIdentityLoadError == null }
        assertEquals(null, state.publicIdentity)
        assertEquals(null, state.publicIdentityLoadError)
    }

    @Test
    fun settingsViewModelHandlesPublicIdentityReadError() {
        val viewModel =
            SettingsViewModel(
                deps = deps,
                loadPublicIdentity = { throw IllegalStateException("identity read failed") },
            )
        val state = awaitSettingsState(viewModel) { it.publicIdentityLoadError != null }
        assertTrue(state.publicIdentityLoadError?.isNotBlank() == true)
        assertEquals(null, state.publicIdentity)
    }

    private fun awaitSettingsState(
        viewModel: SettingsViewModel,
        predicate: (SettingsUiState) -> Boolean,
    ): SettingsUiState =
        runBlocking {
            withTimeout(5_000) {
                var matched: SettingsUiState? = null
                while (true) {
                    val current = viewModel.uiState.value
                    if (predicate(current)) {
                        matched = current
                        break
                    }
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
                matched ?: error("Timed out waiting for settings state")
            }
        }
}
