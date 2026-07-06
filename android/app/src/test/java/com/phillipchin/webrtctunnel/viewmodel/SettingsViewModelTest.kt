package com.phillipchin.webrtctunnel.viewmodel

import android.os.Looper
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

@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest : AppViewModelTestBase() {
    @Test
    fun settingsViewModelValidateConfigReportsValid() {
        val viewModel = SettingsViewModel(deps)
        recordingBridge.validationResult = ValidationResult(true, "ok")
        viewModel.validateConfig()
        val state = viewModel.uiState.value
        assertEquals(true, state.configValid)
        assertEquals(false, state.isValidatingConfig)
        assertTrue(state.configValidationMessage?.contains("valid", ignoreCase = true) == true)
    }

    @Test
    fun settingsViewModelValidateConfigReportsInvalid() {
        val viewModel = SettingsViewModel(deps)
        recordingBridge.validationResult = ValidationResult(false, "missing broker host")
        viewModel.validateConfig()
        val state = viewModel.uiState.value
        assertEquals(false, state.configValid)
        assertTrue(state.configValidationMessage?.isNotBlank() == true)
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

    @Test
    fun statusJsonReturnsParseableJsonForTheDefaultStatus() {
        val viewModel = SettingsViewModel(deps)
        val json = viewModel.statusJson()
        assertTrue(json.startsWith("{"))
        assertFalse(json.contains("status_json_error"))
    }

    @Test
    fun redactedConfigReportsAnExplicitMarkerWhenNoConfigFileExistsYet() =
        runBlocking {
            File(configRepository.configPath).delete()
            val viewModel = SettingsViewModel(deps)
            val redacted = viewModel.redactedConfig()
            assertEquals("(no config file present)", redacted)
        }

    @Test
    fun redactedConfigReturnsRedactedContentWhenTheFileExists() =
        runBlocking {
            val file = File(configRepository.configPath)
            file.parentFile?.mkdirs()
            file.writeText("password=hunter2\nbroker_url=mqtts://example.com:8883")
            val viewModel = SettingsViewModel(deps)
            val redacted = viewModel.redactedConfig()
            assertTrue(redacted.contains("password=***REDACTED***"))
            assertFalse(redacted.contains("hunter2"))
        }

    @Test
    fun redactedConfigReportsAnExplicitErrorWhenTheConfigPathCannotBeRead() =
        runBlocking {
            // A directory at the config path makes `readText()` fail deterministically
            // (IsADirectoryException), distinguishing this from the "missing" case above
            // without relying on filesystem permission behavior.
            val file = File(configRepository.configPath)
            file.delete()
            file.mkdirs()
            val viewModel = SettingsViewModel(deps)
            val redacted = viewModel.redactedConfig()
            assertTrue(redacted.startsWith("(config read/redaction failed:"))
        }

    @Test
    fun diagnosticsShareIntentPayloadDistinguishesMissingConfigFromStatus() =
        runBlocking {
            File(configRepository.configPath).delete()
            val viewModel = SettingsViewModel(deps)
            val intent = viewModel.diagnosticsShareIntent()
            val payload = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
            assertTrue(payload?.contains("status_json={") == true)
            assertTrue(payload?.contains("config_redacted=(no config file present)") == true)
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
