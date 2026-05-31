package com.phillipchin.webrtctunnel.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

val Context.dataStore by preferencesDataStore(name = "android_app_prefs")

class ConfigRepository(private val context: Context) {
    private val configFile: File get() = File(context.filesDir, "config.toml")
    val configPath: String get() = configFile.absolutePath

    val preferences: Flow<AndroidAppPreferences> = context.dataStore.data.map { prefs ->
        prefs.toAppPreferences()
    }

    suspend fun savePreferences(update: AndroidAppPreferences) {
        context.dataStore.edit { prefs ->
            prefs[Keys.allowMetered] = update.allowMetered
            prefs[Keys.pauseOnMetered] = update.pauseOnMetered
            prefs[Keys.resumeOnUnmetered] = update.resumeOnUnmetered
            prefs[Keys.showMeteredWarning] = update.showMeteredWarning
            prefs[Keys.startTunnelWhenAppOpens] = update.startTunnelWhenAppOpens
            prefs[Keys.debugLogsEnabled] = update.debugLogsEnabled
        }
    }

    fun ensureDefaultConfig(contents: String) {
        if (!configFile.exists()) {
            configFile.parentFile?.mkdirs()
            configFile.writeText(contents)
        }
    }

    fun defaultConfigTemplate(): String = """
        # Generated for Android app-private storage.
        format = "p2ptunnel-config-v3"

        [node]
        peer_id = "android-phone"
        role = "offer"

        [paths]
        identity = "${File(context.filesDir, "runtime/identity.toml").absolutePath}"
        authorized_keys = "${File(context.filesDir, "authorized_keys").absolutePath}"
        state_dir = "${File(context.filesDir, "state").absolutePath}"
        log_dir = "${File(context.filesDir, "state/log").absolutePath}"

        [broker]
        url = "mqtts://broker.example.com:8883"
        client_id = "android-phone"
        topic_prefix = "p2ptunnel"
        username = ""
        password_file = ""
        qos = 1
        keepalive_secs = 30
        clean_session = false
        connect_timeout_secs = 5
        session_expiry_secs = 0

        [broker.tls]
        client_cert_file = ""
        client_key_file = ""
        insecure_skip_verify = false

        [webrtc]
        stun_urls = ["stun:stun.l.google.com:19302"]
        enable_trickle_ice = true
        enable_ice_restart = true

        [tunnel]
        read_chunk_size = 16384
        local_eof_grace_ms = 250
        remote_eof_grace_ms = 250

        [[forwards]]
        id = "llama"

        [forwards.offer]
        listen_host = "127.0.0.1"
        listen_port = 8080

        [peer]
        remote_peer_id = "home-server"

        [reconnect]
        enable_auto_reconnect = true
        strategy = "ice_then_renegotiate"
        ice_restart_timeout_secs = 8
        renegotiate_timeout_secs = 20
        backoff_initial_ms = 1000
        backoff_max_ms = 30000
        backoff_multiplier = 2.0
        jitter_ratio = 0.20
        max_attempts = 0
        hold_local_client_during_reconnect = false
        local_client_hold_secs = 0

        [security]
        require_mqtt_tls = true
        require_message_encryption = true
        require_message_signatures = true
        require_authorized_keys = true
        max_clock_skew_secs = 120
        max_message_age_secs = 300
        replay_cache_size = 10000
        reject_unknown_config_keys = true
        refuse_world_readable_identity = true
        refuse_world_writable_paths = true

        [logging]
        level = "info"
        format = "text"
        file_logging = true
        stdout_logging = true
        log_file = "${File(context.filesDir, "state/log/p2ptunnel.log").absolutePath}"
        redact_secrets = true
        redact_sdp = true
        redact_candidates = true
        log_rotation = "none"

        [health]
        status_socket = ""
        write_status_file = true
        status_file = "${File(context.filesDir, "state/status.json").absolutePath}"
    """.trimIndent()

    fun readConfig(): String = configFile.takeIf { it.exists() }?.readText().orEmpty()

    fun writeConfig(contents: String) {
        configFile.parentFile?.mkdirs()
        configFile.writeText(contents)
    }

    private object Keys {
        val allowMetered = booleanPreferencesKey("allow_metered")
        val pauseOnMetered = booleanPreferencesKey("pause_on_metered")
        val resumeOnUnmetered = booleanPreferencesKey("resume_on_unmetered")
        val showMeteredWarning = booleanPreferencesKey("show_metered_warning")
        val startTunnelWhenAppOpens = booleanPreferencesKey("start_tunnel_when_app_opens")
        val debugLogsEnabled = booleanPreferencesKey("debug_logs_enabled")
    }

    private fun Preferences.toAppPreferences() = AndroidAppPreferences(
        allowMetered = this[Keys.allowMetered] ?: false,
        pauseOnMetered = this[Keys.pauseOnMetered] ?: true,
        resumeOnUnmetered = this[Keys.resumeOnUnmetered] ?: true,
        showMeteredWarning = this[Keys.showMeteredWarning] ?: true,
        startTunnelWhenAppOpens = this[Keys.startTunnelWhenAppOpens] ?: false,
        debugLogsEnabled = this[Keys.debugLogsEnabled] ?: false,
    )
}
