package com.phillipchin.webrtctunnel.viewmodel

import android.os.Looper
import com.phillipchin.webrtctunnel.data.ResetResult
import com.phillipchin.webrtctunnel.data.ResetStage
import com.phillipchin.webrtctunnel.data.RollbackStageResult
import com.phillipchin.webrtctunnel.model.ValidationResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun statusDiagnosticsErrorJsonEscapesSpecialCharactersAndRedactsSecrets() {
        val message = "quote \" backslash \\ newline \n password: \"secret sentinel\""
        val json = statusDiagnosticsErrorJson(message)

        // Must parse as real JSON, not a hand-concatenated string that merely looks like one.
        val parsed = Json.parseToJsonElement(json).jsonObject
        val fieldValue = parsed["status_json_error"]?.jsonPrimitive?.content
        assertTrue(fieldValue != null)
        assertFalse(fieldValue!!.contains("sentinel"))
        assertFalse(fieldValue.contains("secret"))
        // The quote/backslash/newline from the original message survive redaction and
        // round-trip correctly once escaped/re-parsed (i.e. they weren't corrupted or
        // used to break out of the JSON string).
        assertTrue(fieldValue.contains("quote \""))
        assertTrue(fieldValue.contains("backslash \\"))
        assertTrue(fieldValue.contains("newline \n"))
    }

    // FIX6 P1-008: a reset failure must survive in durable ViewModel state, not only in a
    // one-shot snackbar, so it is renderable with no collector subscribed.
    @Test
    fun resetRollbackFailureRemainsInStateWithoutSnackbarCollector() =
        runBlocking {
            val viewModel = SettingsViewModel(deps)
            // No snackbar collector is subscribed here.
            app.filesDir.setWritable(false)
            try {
                viewModel.resetConfiguration()
                withTimeout(5_000) {
                    while (viewModel.uiState.value.lastOperationFailure == null) {
                        Shadows.shadowOf(Looper.getMainLooper()).idle()
                        yield()
                    }
                }
            } finally {
                app.filesDir.setWritable(true)
            }
            assertNotNull("the reset failure must be kept in state", viewModel.uiState.value.lastOperationFailure)
        }

    @Test
    fun resetConfigurationFailurePreservesErrorDetailInSnackbar() =
        runBlocking {
            val viewModel = SettingsViewModel(deps)
            val messages = mutableListOf<String>()
            val collector = launch { deps.snackbar.messages.collect { messages.add(it) } }
            yield() // let the collector actually subscribe before resetConfiguration() emits

            // Force a real persistence failure rather than asserting against a mock.
            app.filesDir.setWritable(false)
            try {
                viewModel.resetConfiguration()
                withTimeout(5_000) {
                    while (messages.isEmpty()) {
                        Shadows.shadowOf(Looper.getMainLooper()).idle()
                        yield()
                    }
                }
            } finally {
                app.filesDir.setWritable(true)
            }
            collector.cancel()

            val message = messages.first()
            // A Config-stage write failure mutates nothing, so rollback is empty and the code is
            // the cleanly-failed one (P1-002-D).
            assertTrue("expected the visible reset code, got: $message", message.startsWith("[reset_failed]"))
            assertTrue("the failure detail must be preserved", message.contains("Reset failed at"))
            assertTrue("the underlying failure reason must not be discarded", message.contains(":"))
        }

    @Test
    fun rollbackFailureUsesDistinctVisibleCode() {
        val cleanFailure =
            ResetResult.Failed(
                failedStage = ResetStage.Config,
                cause = "write failed",
                rollback = listOf(RollbackStageResult.Success(ResetStage.SetupInput)),
            )
        val incompleteRollback =
            ResetResult.Failed(
                failedStage = ResetStage.Forwards,
                cause = "forwards failed",
                rollback =
                    listOf(
                        RollbackStageResult.Success(ResetStage.SetupInput),
                        RollbackStageResult.Failure(ResetStage.Config, "delete failed"),
                    ),
            )

        assertEquals("reset_failed", resetFailureVisibleCode(cleanFailure))
        assertEquals("reset_rollback_incomplete", resetFailureVisibleCode(incompleteRollback))
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
