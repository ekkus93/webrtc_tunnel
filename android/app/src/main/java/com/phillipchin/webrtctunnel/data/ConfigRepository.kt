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

private const val MAX_PORT = 65535

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

    fun defaultConfigTemplate(): String = buildDefaultConfigTemplate(context.filesDir)

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
        val enabled = forwards.filter { it.enabled }
        val duplicatePort = enabled.groupBy { it.localPort }.entries.firstOrNull { it.value.size > 1 }?.key
        val duplicateRemoteForwardId =
            enabled
                .groupBy { it.remoteForwardId.trim() }
                .entries
                .firstOrNull { it.key.isNotBlank() && it.value.size > 1 }
                ?.key
        return when {
            duplicateId != null -> "Duplicate forward id: $duplicateId"
            enabled.any { it.name.trim().isBlank() } -> "Forward name is required"
            duplicatePort != null -> "Duplicate local port: $duplicatePort"
            duplicateRemoteForwardId != null -> "Duplicate remote forward ID: $duplicateRemoteForwardId"
            enabled.any { it.remoteForwardId.isBlank() } -> "Remote forward ID is required"
            enabled.any { it.localPort !in 1..MAX_PORT } -> "Port must be between 1 and 65535"
            enabled.any { it.localHost != "127.0.0.1" && it.localHost != "localhost" } ->
                "Non-localhost bind requires advanced warning"
            else -> null
        }
    }

    fun renderOfferConfig(
        input: SetupConfigInput,
        forwards: List<ForwardConfig>,
    ): String = buildOfferConfig(input, forwards, context.filesDir, resolveBrokerPasswordFile(input))

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
        val password = input.brokerPassword
        return when {
            advancedPath.isNotBlank() -> advancedPath
            password.isBlank() -> ""
            else -> {
                val passwordFile = File(context.filesDir, "runtime/mqtt_password.txt")
                passwordFile.parentFile?.mkdirs()
                passwordFile.writeText(password)
                passwordFile.absolutePath
            }
        }
    }
}
