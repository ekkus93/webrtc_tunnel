package com.phillipchin.webrtctunnel.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.phillipchin.webrtctunnel.BuildConfig
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.SetupConfigInput
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
        buildDefaultConfigTemplate(
            context.filesDir,
            ConfigRenderOptions(androidIceMode = debugAndroidIceModeOverride()),
        )

    fun readConfig(): String = configFile.takeIf { it.exists() }?.readText().orEmpty()

    /**
     * Refresh the `advertised_local_ipv4` field in the active config with a freshly-resolved
     * address (or remove it when [address] is null) so a strict `vnet_mux` start advertises
     * the current network's host candidate. No-op when no config exists yet.
     */
    fun refreshAdvertisedAddress(address: String?) {
        val current = readConfig()
        if (current.isBlank()) {
            return
        }
        writeConfigAtomically(upsertAdvertisedLocalIpv4(current, address))
    }

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

    fun saveSetupInput(input: SetupConfigInput) {
        setupInputFile.parentFile?.mkdirs()
        setupInputFile.writeText(Json.encodeToString(input))
    }

    /**
     * Load the saved setup draft, distinguishing a corrupt file (failure) from a
     * legitimately missing one (success with fresh defaults). A corrupt existing draft must
     * NOT silently reset to defaults — callers surface the failure so the user can repair or
     * re-run setup rather than losing their saved values invisibly.
     */
    fun loadSetupInputResult(): Result<SetupConfigInput> {
        if (!setupInputFile.exists()) {
            return Result.success(SetupConfigInput())
        }
        return runCatching { Json.decodeFromString<SetupConfigInput>(setupInputFile.readText()) }
    }

    fun renderOfferConfig(
        input: SetupConfigInput,
        forwards: List<ForwardConfig>,
        debugLogs: Boolean = false,
    ): String =
        buildOfferConfig(
            input,
            forwards,
            context.filesDir,
            resolveBrokerPasswordFile(input, context.filesDir),
            ConfigRenderOptions(debugLogs = debugLogs, androidIceMode = debugAndroidIceModeOverride()),
        )
}

/**
 * Debug/test-only `android_ice_mode` override read from the `debug.p2p.android_ice_mode`
 * system property (e.g. `adb shell setprop debug.p2p.android_ice_mode vnet`). Returns
 * [DEFAULT_ANDROID_ICE_MODE] in release builds, when the property is unset, or when it holds
 * anything other than a valid mode. This is device-agnostic — it works on emulators and
 * physical devices, and (unlike patching app-private config) survives the SELinux
 * restriction on `run-as` writes. Read at config-render time, so it must be set before the
 * wizard saves the config.
 */
private fun debugAndroidIceModeOverride(): String {
    if (!BuildConfig.DEBUG) {
        return DEFAULT_ANDROID_ICE_MODE
    }
    val raw =
        runCatching {
            ProcessBuilder("getprop", "debug.p2p.android_ice_mode")
                .redirectErrorStream(true)
                .start()
                .inputStream
                .bufferedReader()
                .use { reader -> reader.readLine() }
        }.getOrNull()
    return normalizeAndroidIceMode(raw)
}

private object Keys {
    val allowMetered = booleanPreferencesKey("allow_metered")
    val pauseOnMetered = booleanPreferencesKey("pause_on_metered")
    val resumeOnUnmetered = booleanPreferencesKey("resume_on_unmetered")
    val showMeteredWarning = booleanPreferencesKey("show_metered_warning")
    val debugLogsEnabled = booleanPreferencesKey("debug_logs_enabled")
    val advancedSettingsEnabled = booleanPreferencesKey("advanced_settings_enabled")
}

private fun Preferences.toAppPreferences() =
    AndroidAppPreferences(
        allowMetered = this[Keys.allowMetered] ?: false,
        resumeOnUnmetered = this[Keys.resumeOnUnmetered] ?: true,
        showMeteredWarning = this[Keys.showMeteredWarning] ?: true,
        debugLogsEnabled = this[Keys.debugLogsEnabled] ?: false,
        advancedSettingsEnabled = this[Keys.advancedSettingsEnabled] ?: false,
    )

private fun resolveBrokerPasswordFile(
    input: SetupConfigInput,
    filesDir: File,
): String {
    val advancedPath = input.brokerPasswordFile.trim()
    val password = input.brokerPassword
    return when {
        advancedPath.isNotBlank() -> advancedPath
        password.isBlank() -> ""
        else -> {
            val passwordFile = File(filesDir, "runtime/mqtt_password.txt")
            passwordFile.parentFile?.mkdirs()
            passwordFile.writeText(password)
            passwordFile.absolutePath
        }
    }
}
