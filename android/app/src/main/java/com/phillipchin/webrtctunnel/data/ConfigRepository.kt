package com.phillipchin.webrtctunnel.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.SetupConfigInput
import com.phillipchin.webrtctunnel.model.ValidationResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

val Context.dataStore by preferencesDataStore(name = "android_app_prefs")

class ConfigRepository(private val context: Context) {
    private val configFile: File get() = File(context.filesDir, "config.toml")
    private val forwardsFile: File get() = File(context.filesDir, "forwards.json")
    private val setupInputFile: File get() = File(context.filesDir, "setup_input.json")
    val configPath: String get() = configFile.absolutePath

    val preferences: Flow<AndroidAppPreferences> =
        context.dataStore.data.map { prefs ->
            prefs.toAppPreferences()
        }

    suspend fun savePreferences(update: AndroidAppPreferences) {
        context.dataStore.edit { prefs ->
            prefs[Keys.allowMetered] = update.allowMetered
            prefs[Keys.resumeOnUnmetered] = update.resumeOnUnmetered
            prefs[Keys.showMeteredWarning] = update.showMeteredWarning
            prefs[Keys.startTunnelWhenAppOpens] = update.startTunnelWhenAppOpens
            prefs[Keys.debugLogsEnabled] = update.debugLogsEnabled
            prefs[Keys.advancedSettingsEnabled] = update.advancedSettingsEnabled
            prefs.remove(Keys.pauseOnMetered)
        }
    }

    fun ensureDefaultConfig(contents: String) {
        if (!configFile.exists()) {
            configFile.parentFile?.mkdirs()
            configFile.writeText(contents)
        }
    }

    fun defaultConfigTemplate(): String =
        """
        # Generated for Android app-private storage.
        format = "p2ptunnel-config-v3"

        [node]
        peer_id = "android-phone"
        role = "offer"

        [paths]
        identity = ${tomlString(File(context.filesDir, "runtime/identity.toml").absolutePath)}
        authorized_keys = ${tomlString(File(context.filesDir, "authorized_keys").absolutePath)}
        state_dir = ${tomlString(File(context.filesDir, "state").absolutePath)}
        log_dir = ${tomlString(File(context.filesDir, "state/log").absolutePath)}

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
        log_file = ${tomlString(File(context.filesDir, "state/log/p2ptunnel.log").absolutePath)}
        redact_secrets = true
        redact_sdp = true
        redact_candidates = true
        log_rotation = "none"

        [health]
        status_socket = ""
        write_status_file = true
        status_file = ${tomlString(File(context.filesDir, "state/status.json").absolutePath)}
        """.trimIndent()

    fun readConfig(): String = configFile.takeIf { it.exists() }?.readText().orEmpty()

    fun writeConfig(contents: String) {
        configFile.parentFile?.mkdirs()
        configFile.writeText(contents)
    }

    fun writeConfigAtomically(contents: String) {
        configFile.parentFile?.mkdirs()
        val temp = File(configFile.parentFile, "${configFile.name}.tmp")
        temp.writeText(contents)
        Files.move(
            temp.toPath(),
            configFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    fun loadForwards(): List<ForwardConfig> {
        if (!forwardsFile.exists()) {
            val defaults =
                listOf(
                    ForwardConfig(
                        id = "llama",
                        name = "Llama server",
                        localHost = "127.0.0.1",
                        localPort = 8080,
                        remoteForwardId = "llama",
                        enabled = true,
                    ),
                )
            saveForwards(defaults)
            return defaults
        }
        return runCatching {
            Json.decodeFromString<List<ForwardConfig>>(forwardsFile.readText())
        }.getOrElse { emptyList() }
    }

    fun saveForwards(forwards: List<ForwardConfig>) {
        forwardsFile.parentFile?.mkdirs()
        forwardsFile.writeText(Json.encodeToString(forwards))
    }

    fun saveSetupInput(input: SetupConfigInput) {
        setupInputFile.parentFile?.mkdirs()
        setupInputFile.writeText(Json.encodeToString(input))
    }

    fun loadSetupInput(): SetupConfigInput {
        if (!setupInputFile.exists()) {
            return SetupConfigInput()
        }
        return runCatching { Json.decodeFromString<SetupConfigInput>(setupInputFile.readText()) }
            .getOrElse { SetupConfigInput() }
    }

    fun upsertForward(forward: ForwardConfig): ValidationResult {
        val updated =
            loadForwards().toMutableList().apply {
                val index = indexOfFirst { it.id == forward.id }
                if (index >= 0) {
                    set(index, forward)
                } else {
                    add(forward)
                }
            }
        val error = validateForwards(updated)
        if (error != null) {
            return ValidationResult(false, error)
        }
        saveForwards(updated)
        return ValidationResult(true, null)
    }

    fun deleteForward(forwardId: String) {
        saveForwards(loadForwards().filterNot { it.id == forwardId })
    }

    fun validateForwards(forwards: List<ForwardConfig>): String? {
        val duplicateId = forwards.groupBy { it.id }.entries.firstOrNull { it.value.size > 1 }?.key
        if (duplicateId != null) {
            return "Duplicate forward id: $duplicateId"
        }
        val enabled = forwards.filter { it.enabled }
        val missingName = enabled.firstOrNull { it.name.trim().isBlank() }
        if (missingName != null) {
            return "Forward name is required"
        }
        val duplicatePort = enabled.groupBy { it.localPort }.entries.firstOrNull { it.value.size > 1 }?.key
        if (duplicatePort != null) {
            return "Duplicate local port: $duplicatePort"
        }
        val duplicateRemoteForwardId =
            enabled
                .groupBy { it.remoteForwardId.trim() }
                .entries
                .firstOrNull { it.key.isNotBlank() && it.value.size > 1 }
                ?.key
        if (duplicateRemoteForwardId != null) {
            return "Duplicate remote forward ID: $duplicateRemoteForwardId"
        }
        val missingRemote = enabled.firstOrNull { it.remoteForwardId.isBlank() }
        if (missingRemote != null) {
            return "Remote forward ID is required"
        }
        val invalidPort = enabled.firstOrNull { it.localPort !in 1..65535 }
        if (invalidPort != null) {
            return "Port must be between 1 and 65535"
        }
        val invalidHost = enabled.firstOrNull { it.localHost != "127.0.0.1" && it.localHost != "localhost" }
        if (invalidHost != null) {
            return "Non-localhost bind requires advanced warning"
        }
        return null
    }

    fun renderOfferConfig(
        input: SetupConfigInput,
        forwards: List<ForwardConfig>,
    ): String {
        val forwardsToml =
            forwards.joinToString(separator = "\n\n") { forward ->
                """
                [[forwards]]
                id = ${tomlString(forward.remoteForwardId)}

                [forwards.offer]
                listen_host = ${tomlString(forward.localHost)}
                listen_port = ${forward.localPort}
                """.trimIndent()
            }
        val username = input.brokerUsername
        val passwordFile = resolveBrokerPasswordFile(input)
        val scheme = if (input.brokerUseTls) "mqtts" else "mqtt"
        return """
            format = "p2ptunnel-config-v3"

            [node]
            peer_id = ${tomlString(input.localPeerId)}
            role = "offer"

            [paths]
            identity = ${tomlString(File(context.filesDir, "runtime/identity.toml").absolutePath)}
            authorized_keys = ${tomlString(File(context.filesDir, "authorized_keys").absolutePath)}
            state_dir = ${tomlString(File(context.filesDir, "state").absolutePath)}
            log_dir = ${tomlString(File(context.filesDir, "state/log").absolutePath)}

            [broker]
            url = ${tomlString("$scheme://${input.brokerHost}:${input.brokerPort}")}
            client_id = ${tomlString(input.localPeerId)}
            topic_prefix = ${tomlString(input.topicPrefix)}
            username = ${tomlString(username)}
            password_file = ${tomlString(passwordFile)}
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

            $forwardsToml

            [peer]
            remote_peer_id = ${tomlString(input.remotePeerId)}

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
            log_file = ${tomlString(File(context.filesDir, "state/log/p2ptunnel.log").absolutePath)}
            redact_secrets = true
            redact_sdp = true
            redact_candidates = true
            log_rotation = "none"

            [health]
            status_socket = ""
            write_status_file = true
            status_file = ${tomlString(File(context.filesDir, "state/status.json").absolutePath)}
            """.trimIndent()
    }

    fun redactConfig(config: String): String {
        return SensitiveDataRedactor.redactText(config)
    }

    private object Keys {
        val allowMetered = booleanPreferencesKey("allow_metered")
        val pauseOnMetered = booleanPreferencesKey("pause_on_metered")
        val resumeOnUnmetered = booleanPreferencesKey("resume_on_unmetered")
        val showMeteredWarning = booleanPreferencesKey("show_metered_warning")
        val startTunnelWhenAppOpens = booleanPreferencesKey("start_tunnel_when_app_opens")
        val debugLogsEnabled = booleanPreferencesKey("debug_logs_enabled")
        val advancedSettingsEnabled = booleanPreferencesKey("advanced_settings_enabled")
    }

    private fun Preferences.toAppPreferences() =
        AndroidAppPreferences(
            allowMetered = this[Keys.allowMetered] ?: false,
            resumeOnUnmetered = this[Keys.resumeOnUnmetered] ?: true,
            showMeteredWarning = this[Keys.showMeteredWarning] ?: true,
            startTunnelWhenAppOpens = this[Keys.startTunnelWhenAppOpens] ?: false,
            debugLogsEnabled = this[Keys.debugLogsEnabled] ?: false,
            advancedSettingsEnabled = this[Keys.advancedSettingsEnabled] ?: false,
        )

    private fun resolveBrokerPasswordFile(input: SetupConfigInput): String {
        val advancedPath = input.brokerPasswordFile.trim()
        if (advancedPath.isNotBlank()) {
            return advancedPath
        }
        val password = input.brokerPassword
        if (password.isBlank()) {
            return ""
        }
        val passwordFile = File(context.filesDir, "runtime/mqtt_password.txt")
        passwordFile.parentFile?.mkdirs()
        passwordFile.writeText(password)
        return passwordFile.absolutePath
    }

    private fun tomlString(value: String): String {
        val escaped =
            buildString(value.length + 2) {
                append('"')
                value.forEach { ch ->
                    when (ch) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> append(ch)
                    }
                }
                append('"')
            }
        return escaped
    }
}
