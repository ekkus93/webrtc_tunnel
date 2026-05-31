package com.phillipchin.webrtctunnel.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ConfigRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var repository: ConfigRepository

    @Before
    fun setUp() {
        repository = ConfigRepository(context)
        File(context.filesDir, "config.toml").delete()
        runBlocking {
            context.dataStore.edit { preferences -> preferences.clear() }
        }
    }

    @Test
    fun ensureDefaultConfigCreatesFileWhenMissing() {
        repository.ensureDefaultConfig("abc")
        assertEquals("abc", repository.readConfig())
    }

    @Test
    fun ensureDefaultConfigDoesNotOverwriteExistingFile() {
        repository.writeConfig("existing")
        repository.ensureDefaultConfig("default")
        assertEquals("existing", repository.readConfig())
    }

    @Test
    fun defaultTemplateContainsRequiredSections() {
        val template = repository.defaultConfigTemplate()
        assertTrue(template.contains("format = \"p2ptunnel-config-v3\""))
        assertTrue(template.contains("[broker]"))
        assertTrue(template.contains("[security]"))
        assertTrue(template.contains("[logging]"))
        assertFalse(template.contains("~/.config"))
        assertFalse(template.contains("~/.local"))
        assertFalse(template.contains("/etc/ssl/certs"))
        assertTrue(template.contains(context.filesDir.absolutePath))
    }

    @Test
    fun writeAndReadConfigRoundTrip() {
        val contents = "format = \"p2ptunnel-config-v3\"\n[node]\npeer_id=\"x\""
        repository.writeConfig(contents)
        assertEquals(contents, repository.readConfig())
        assertTrue(repository.configPath.startsWith(context.filesDir.absolutePath))
    }

    @Test
    fun readConfigReturnsEmptyWhenMissing() {
        assertEquals("", repository.readConfig())
    }

    @Test
    fun preferencesDefaultValuesAreSafe() = runBlocking {
        val prefs = repository.preferences.first()
        assertEquals(
            AndroidAppPreferences(
                allowMetered = false,
                pauseOnMetered = true,
                resumeOnUnmetered = true,
                showMeteredWarning = true,
                startTunnelWhenAppOpens = false,
                debugLogsEnabled = false,
            ),
            prefs,
        )
    }

    @Test
    fun savePreferencesPersistsAllFields() = runBlocking {
        val update = AndroidAppPreferences(
            allowMetered = true,
            pauseOnMetered = false,
            resumeOnUnmetered = false,
            showMeteredWarning = false,
            startTunnelWhenAppOpens = true,
            debugLogsEnabled = true,
        )
        repository.savePreferences(update)
        assertEquals(update, repository.preferences.first())
    }

    @Test
    fun partialPreferenceStateFallsBackToDefaults() = runBlocking {
        context.dataStore.edit { preferences ->
            preferences[booleanPreferencesKey("allow_metered")] = true
            preferences.remove(booleanPreferencesKey("pause_on_metered"))
        }
        val prefs = repository.preferences.first()
        assertTrue(prefs.allowMetered)
        assertTrue(prefs.pauseOnMetered)
    }

    @Test
    fun latestWriteWins() {
        repository.writeConfig("first")
        repository.writeConfig("second")
        assertEquals("second", repository.readConfig())
        assertFalse(repository.readConfig().contains("first"))
    }
}
