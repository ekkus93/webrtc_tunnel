package com.phillipchin.webrtctunnel.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.phillipchin.webrtctunnel.BuildConfig
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.SetupConfigInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

val Context.dataStore by preferencesDataStore(name = "android_app_prefs")

open class ConfigRepository(private val context: Context) {
    private val configFile: File get() = File(context.filesDir, "config.toml")
    private val setupInputFile: File get() = File(context.filesDir, "setup_input.json")

    // P1-007: Single write mutex for all config.toml writers to serialize atomic writes.
    private val writeMutex = Mutex()
    val configPath: String get() = configFile.absolutePath

    val preferences: Flow<AndroidAppPreferences> =
        context.dataStore.data.map { prefs ->
            prefs.toAppPreferences()
        }

    // P1-016: Wrap preference writes so failures are visible.
    open suspend fun savePreferences(update: AndroidAppPreferences): Result<Unit> {
        return try {
            context.dataStore.edit { prefs ->
                prefs[Keys.allowMetered] = update.allowMetered
                prefs[Keys.resumeOnUnmetered] = update.resumeOnUnmetered
                prefs[Keys.showMeteredWarning] = update.showMeteredWarning
                prefs[Keys.debugLogsEnabled] = update.debugLogsEnabled
                prefs[Keys.advancedSettingsEnabled] = update.advancedSettingsEnabled
                prefs[Keys.androidIceMode] = normalizeAndroidIceMode(update.androidIceMode)
                prefs.remove(Keys.pauseOnMetered)
            }
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    /**
     * P1-007: Ensures a default config exists by writing through the serialized atomic
     * writer so the first write cannot race with a later [writeConfigAtomically] call.
     */
    suspend fun ensureDefaultConfig(contents: String) {
        if (!configFile.exists()) {
            writeConfigAtomically(contents)
        }
    }

    fun defaultConfigTemplate(): String =
        buildDefaultConfigTemplate(
            context.filesDir,
            ConfigRenderOptions(androidIceMode = resolveAndroidIceMode(DEFAULT_ANDROID_ICE_MODE)),
        )

    fun readConfig(): String = configFile.takeIf { it.exists() }?.readText().orEmpty()

    /**
     * Prepare the active config for a tunnel start by surgically rewriting the two
     * network-dependent `[webrtc]` fields: `android_ice_mode` (the user's chosen [iceMode], or
     * the debug `getprop` override) and `advertised_local_ipv4` ([advertisedIpv4], or removed
     * when null so a strict `vnet_mux` start fails loudly rather than advertising a stale
     * address). Each edit touches only its own line, so both are key-safe on an already-rendered
     * config. No-op when no config exists yet; changes take effect on the next engine build
     * (tunnel restart), since the ICE mode is fixed when the WebRTC engine is built.
     */
    suspend fun prepareActiveConfigForStart(
        iceMode: String,
        advertisedIpv4: String?,
    ) {
        writeMutex.withLock {
            val current = readConfig()
            if (current.isBlank()) {
                return@withLock
            }
            val withIceMode = upsertAndroidIceMode(current, resolveAndroidIceMode(iceMode))
            writeConfigAtomicallyLocked(
                configFile,
                upsertAdvertisedLocalIpv4(withIceMode, advertisedIpv4),
            ).onFailure { error ->
                // Config write failure is non-fatal for startup preparation
                // (the tunnel will log the error but continue)
                android.util.Log.w("ConfigRepository", "Failed to prepare active config", error)
            }
        }
    }

    fun writeConfig(contents: String) {
        configFile.parentFile?.mkdirs()
        configFile.writeText(contents)
    }

    /**
     * P1-007: Atomic write with unique temp file under [writeMutex].
     * All config writers go through this single serialized boundary.
     * Returns Result.success(Unit) on success, Result.failure(...) on failure.
     */
    suspend fun writeConfigAtomically(contents: String): Result<Unit> =
        writeMutex.withLock {
            writeConfigAtomicallyLocked(configFile, contents)
        }

    /**
     * Internal: delete config file for transactional reset rollback.
     * Used when the config file was absent before reset and a later stage failed,
     * so rollback must restore the absent state (not leave a stale config behind).
     * Returns Result.success(Unit) on success, Result.failure(...) on failure.
     */
    internal suspend fun deleteConfigFileForTransactionalReset(): Result<Unit> =
        writeMutex.withLock {
            runCatching<Unit> {
                configFile.delete()
            }
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
        androidIceMode: String = DEFAULT_ANDROID_ICE_MODE,
    ): String =
        buildOfferConfig(
            input,
            forwards,
            context.filesDir,
            resolveBrokerPasswordFile(input, context.filesDir),
            ConfigRenderOptions(
                debugLogs = debugLogs,
                androidIceMode = resolveAndroidIceMode(androidIceMode),
            ),
        )
}

/**
 * Internal: atomic config write without acquiring the mutex (caller must hold [writeMutex]).
 */
private fun writeConfigAtomicallyLocked(
    configFile: File,
    contents: String,
): Result<Unit> =
    runCatching {
        configFile.parentFile?.mkdirs()
        val temp =
            Files.createTempFile(
                configFile.parentFile?.toPath(),
                "config.toml.tmp-",
                ".partial",
            )
        try {
            temp.toFile().writeText(contents)
            try {
                Files.move(
                    temp,
                    configFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (unsupported: AtomicMoveNotSupportedException) {
                // Fallback when ATOMIC_MOVE is not supported on the filesystem
                Files.move(
                    temp,
                    configFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            // Clean up temp file if it still exists (move succeeded or failed)
            Files.deleteIfExists(temp)
        }
    }

/**
 * Resolve the effective `android_ice_mode`: the debug `getprop` override wins when present
 * (so the E2E harness can force a mode), otherwise the user's chosen [userPreference]
 * (normalized). This is the single chokepoint every render/apply path goes through.
 */
private fun resolveAndroidIceMode(userPreference: String): String =
    debugAndroidIceModeOverrideOrNull() ?: normalizeAndroidIceMode(userPreference)

/**
 * Debug/test-only `android_ice_mode` override read from the `debug.p2p.android_ice_mode`
 * system property (e.g. `adb shell setprop debug.p2p.android_ice_mode vnet`). Returns `null`
 * in release builds, when the property is unset, or when it holds anything other than a valid
 * mode — meaning "no override, defer to the user preference". Device-agnostic (works on
 * emulators and physical devices) and survives the SELinux restriction on `run-as` writes.
 */
private fun debugAndroidIceModeOverrideOrNull(): String? {
    if (!BuildConfig.DEBUG) {
        return null
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
    val trimmed = raw?.trim()?.lowercase().orEmpty()
    return if (trimmed in VALID_ANDROID_ICE_MODES) trimmed else null
}

private object Keys {
    val allowMetered = booleanPreferencesKey("allow_metered")
    val pauseOnMetered = booleanPreferencesKey("pause_on_metered")
    val resumeOnUnmetered = booleanPreferencesKey("resume_on_unmetered")
    val showMeteredWarning = booleanPreferencesKey("show_metered_warning")
    val debugLogsEnabled = booleanPreferencesKey("debug_logs_enabled")
    val advancedSettingsEnabled = booleanPreferencesKey("advanced_settings_enabled")
    val androidIceMode = stringPreferencesKey("android_ice_mode")
}

private fun Preferences.toAppPreferences() =
    AndroidAppPreferences(
        allowMetered = this[Keys.allowMetered] ?: false,
        resumeOnUnmetered = this[Keys.resumeOnUnmetered] ?: true,
        showMeteredWarning = this[Keys.showMeteredWarning] ?: true,
        debugLogsEnabled = this[Keys.debugLogsEnabled] ?: false,
        advancedSettingsEnabled = this[Keys.advancedSettingsEnabled] ?: false,
        androidIceMode = normalizeAndroidIceMode(this[Keys.androidIceMode]),
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
