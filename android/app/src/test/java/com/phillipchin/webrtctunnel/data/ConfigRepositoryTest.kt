package com.phillipchin.webrtctunnel.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.SetupConfigInput
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
    private lateinit var forwardsStore: ForwardsConfigStore

    @Before
    fun setUp() {
        repository = ConfigRepository(context)
        forwardsStore = ForwardsConfigStore(context)
        File(context.filesDir, "config.toml").delete()
        File(context.filesDir, "forwards.json").delete()
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
    fun defaultTemplateInjectsDataPlaneFields() {
        val template = repository.defaultConfigTemplate()
        // Release/default builds emit the strict "vnet_mux" ICE mode, the probe timeout, and
        // the mid-session heartbeat knobs.
        assertTrue(template.contains("android_ice_mode = \"vnet_mux\""))
        assertTrue(template.contains("data_plane_probe_timeout_ms = 5000"))
        assertTrue(template.contains("data_plane_heartbeat_interval_ms = 5000"))
        assertTrue(template.contains("data_plane_heartbeat_max_misses = 3"))
    }

    @Test
    fun normalizeAndroidIceModeAcceptsValidModes() {
        assertEquals("auto", normalizeAndroidIceMode("auto"))
        assertEquals("native", normalizeAndroidIceMode("native"))
        assertEquals("vnet", normalizeAndroidIceMode("vnet"))
        assertEquals("vnet_mux", normalizeAndroidIceMode("vnet_mux"))
        // Case-insensitive and whitespace-tolerant.
        assertEquals("vnet", normalizeAndroidIceMode("  VNET \n"))
        assertEquals("vnet_mux", normalizeAndroidIceMode("  VNET_MUX \n"))
    }

    @Test
    fun normalizeAndroidIceModeFallsBackToStrictDefaultOnInvalidInput() {
        // Invalid/absent input must resolve to the strict default (vnet_mux), never a
        // best-effort path that could pick native ICE on Android.
        assertEquals("vnet_mux", normalizeAndroidIceMode(null))
        assertEquals("vnet_mux", normalizeAndroidIceMode(""))
        assertEquals("vnet_mux", normalizeAndroidIceMode("turn"))
        assertEquals("vnet_mux", normalizeAndroidIceMode("vnet; rm -rf"))
    }

    @Test
    fun upsertAdvertisedLocalIpv4InsertsAfterIceMode() {
        val config =
            """
            [webrtc]
            stun_urls = ["stun:stun.l.google.com:19302"]
            android_ice_mode = "vnet_mux"

            [tunnel]
            read_chunk_size = 16384
            """.trimIndent()
        val updated = upsertAdvertisedLocalIpv4(config, "10.1.3.11")
        assertTrue(updated.contains("advertised_local_ipv4 = \"10.1.3.11\""))
        // The injected line sits inside [webrtc], right after android_ice_mode.
        val iceIdx = updated.indexOf("android_ice_mode")
        val addrIdx = updated.indexOf("advertised_local_ipv4")
        val tunnelIdx = updated.indexOf("[tunnel]")
        assertTrue(addrIdx in (iceIdx + 1) until tunnelIdx)
    }

    @Test
    fun upsertAdvertisedLocalIpv4ReplacesExistingLine() {
        val config =
            """
            [webrtc]
            android_ice_mode = "vnet_mux"
            advertised_local_ipv4 = "10.0.0.1"
            """.trimIndent()
        val updated = upsertAdvertisedLocalIpv4(config, "192.168.5.20")
        assertTrue(updated.contains("advertised_local_ipv4 = \"192.168.5.20\""))
        assertFalse(updated.contains("10.0.0.1"))
        // Exactly one advertised line remains.
        assertEquals(1, updated.lines().count { it.contains("advertised_local_ipv4") })
    }

    @Test
    fun upsertAdvertisedLocalIpv4NullRemovesLine() {
        val config =
            """
            [webrtc]
            android_ice_mode = "vnet_mux"
            advertised_local_ipv4 = "10.0.0.1"
            """.trimIndent()
        val updated = upsertAdvertisedLocalIpv4(config, null)
        assertFalse(updated.contains("advertised_local_ipv4"))
    }

    @Test
    fun refreshAdvertisedAddressRewritesActiveConfig() {
        repository.writeConfig(
            """
            [webrtc]
            android_ice_mode = "vnet_mux"
            """.trimIndent(),
        )
        repository.refreshAdvertisedAddress("10.1.3.11")
        assertTrue(repository.readConfig().contains("advertised_local_ipv4 = \"10.1.3.11\""))
        repository.refreshAdvertisedAddress(null)
        assertFalse(repository.readConfig().contains("advertised_local_ipv4"))
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
    fun preferencesDefaultValuesAreSafe() =
        runBlocking {
            val prefs = repository.preferences.first()
            assertEquals(
                AndroidAppPreferences(
                    allowMetered = false,
                    resumeOnUnmetered = true,
                    showMeteredWarning = true,
                    debugLogsEnabled = false,
                ),
                prefs,
            )
        }

    @Test
    fun savePreferencesPersistsAllFields() =
        runBlocking {
            val update =
                AndroidAppPreferences(
                    allowMetered = true,
                    resumeOnUnmetered = false,
                    showMeteredWarning = false,
                    debugLogsEnabled = true,
                )
            repository.savePreferences(update)
            assertEquals(update, repository.preferences.first())
        }

    @Test
    fun partialPreferenceStateFallsBackToDefaults() =
        runBlocking {
            context.dataStore.edit { preferences ->
                preferences[booleanPreferencesKey("allow_metered")] = true
                preferences.remove(booleanPreferencesKey("pause_on_metered"))
            }
            val prefs = repository.preferences.first()
            assertTrue(prefs.allowMetered)
            assertTrue(prefs.resumeOnUnmetered)
        }

    @Test
    fun latestWriteWins() {
        repository.writeConfig("first")
        repository.writeConfig("second")
        assertEquals("second", repository.readConfig())
        assertFalse(repository.readConfig().contains("first"))
    }

    @Test
    fun atomicWriteReplacesConfig() {
        repository.writeConfig("before")
        repository.writeConfigAtomically("after")
        assertEquals("after", repository.readConfig())
    }

    @Test
    fun forwardsValidationRejectsDuplicateEnabledPorts() {
        val forwards =
            listOf(
                ForwardConfig(id = "a", name = "a", localPort = 9000, remoteForwardId = "a", enabled = true),
                ForwardConfig(id = "b", name = "b", localPort = 9000, remoteForwardId = "b", enabled = true),
            )
        assertTrue(forwardsStore.validateForwards(forwards)?.contains("Duplicate local port") == true)
    }

    @Test
    fun forwardsValidationRejectsBlankEnabledForwardName() {
        val forwards =
            listOf(
                ForwardConfig(id = "a", name = "", localPort = 9000, remoteForwardId = "a", enabled = true),
            )
        assertEquals("Forward name is required", forwardsStore.validateForwards(forwards))
    }

    @Test
    fun forwardsValidationRejectsDuplicateEnabledRemoteForwardIds() {
        val forwards =
            listOf(
                ForwardConfig(id = "a", name = "a", localPort = 9000, remoteForwardId = "llama", enabled = true),
                ForwardConfig(id = "b", name = "b", localPort = 9001, remoteForwardId = "llama", enabled = true),
            )
        assertEquals("Duplicate remote forward ID: llama", forwardsStore.validateForwards(forwards))
    }

    @Test
    fun forwardsValidationAllowsDuplicateRemoteForwardIdWhenOneDisabled() {
        val forwards =
            listOf(
                ForwardConfig(id = "a", name = "a", localPort = 9000, remoteForwardId = "llama", enabled = true),
                ForwardConfig(id = "b", name = "b", localPort = 9001, remoteForwardId = "llama", enabled = false),
            )
        assertEquals(null, forwardsStore.validateForwards(forwards))
    }

    @Test
    fun forwardsRoundTripPersistsJson() {
        val forwards =
            listOf(
                ForwardConfig(
                    id = "svc",
                    name = "Service",
                    localHost = "127.0.0.1",
                    localPort = 18080,
                    remoteForwardId = "svc",
                ),
            )
        forwardsStore.saveForwards(forwards)
        assertEquals(forwards, forwardsStore.loadForwards())
    }

    @Test
    fun renderOfferConfigIncludesForwardAndPeer() {
        val input =
            SetupConfigInput(
                localPeerId = "android-peer",
                brokerHost = "broker.local",
                remotePeerId = "desktop-peer",
            )
        val text =
            repository.renderOfferConfig(
                input,
                listOf(ForwardConfig(id = "llama", name = "Llama", localPort = 8080, remoteForwardId = "llama")),
            )
        assertTrue(text.contains("url = \"mqtts://broker.local:8883\""))
        assertTrue(text.contains("remote_peer_id = \"desktop-peer\""))
        assertTrue(text.contains("listen_port = 8080"))
    }

    @Test
    fun renderOfferConfigDefaultsToInfoLogLevel() {
        val text =
            repository.renderOfferConfig(
                SetupConfigInput(localPeerId = "android-peer", brokerHost = "broker.local"),
                listOf(ForwardConfig(id = "llama", name = "Llama", localPort = 8080, remoteForwardId = "llama")),
            )
        assertTrue(text.contains("level = \"info\""))
        assertFalse(text.contains("level = \"debug\""))
    }

    @Test
    fun renderOfferConfigUsesDebugLogLevelWhenEnabled() {
        val text =
            repository.renderOfferConfig(
                SetupConfigInput(localPeerId = "android-peer", brokerHost = "broker.local"),
                listOf(ForwardConfig(id = "llama", name = "Llama", localPort = 8080, remoteForwardId = "llama")),
                debugLogs = true,
            )
        assertTrue(text.contains("level = \"debug\""))
        assertFalse(text.contains("level = \"info\""))
    }

    @Test
    fun renderOfferConfigEscapesInjectedTomlStrings() {
        val input =
            SetupConfigInput(
                localPeerId = "android\"peer",
                brokerHost = "broker.local\"\\n[[forwards]]\\nid = \"evil\"",
                remotePeerId = "desktop\"peer",
                topicPrefix = "topic\nprefix",
            )
        val text =
            repository.renderOfferConfig(
                input,
                listOf(
                    ForwardConfig(
                        id = "llama",
                        name = "Llama",
                        localPort = 8080,
                        remoteForwardId = "llama\"inject",
                    ),
                ),
            )
        assertTrue(text.contains("topic_prefix = \"topic\\nprefix\""))
        assertTrue(text.contains("id = \"llama\\\"inject\""))
        assertFalse(text.contains("\n[[forwards]]\nid = \"evil\""))
    }

    @Test
    fun setupInputRoundTripPersistsState() {
        val input =
            SetupConfigInput(
                localPeerId = "android-peer",
                brokerHost = "broker.local",
                remotePeerId = "desktop-peer",
                allowMetered = true,
            )
        repository.saveSetupInput(input)
        assertEquals(input, repository.loadSetupInput())
    }
}
