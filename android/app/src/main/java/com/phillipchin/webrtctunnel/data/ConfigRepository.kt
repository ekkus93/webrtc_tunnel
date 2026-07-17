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
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

val Context.dataStore by preferencesDataStore(name = "android_app_prefs")

open class ConfigRepository(private val context: Context) {
    private val configFile: File get() = File(context.filesDir, "config.toml")
    private val setupInputFile: File get() = File(context.filesDir, "setup_input.json")

    // P1-007: Single write mutex for all config.toml writers to serialize atomic writes.
    private val writeMutex = Mutex()
    val configPath: String get() = configFile.absolutePath

    // P1-002: open so tests can inject a preference-read failure (e.g. for
    // TunnelForegroundService.handlePolicyAllowed() diagnostic coverage).
    open val preferences: Flow<AndroidAppPreferences>
        get() =
            context.dataStore.data.map { prefs ->
                prefs.toAppPreferences()
            }

    // P1-016: Wrap preference writes so failures are visible.
    open suspend fun savePreferences(update: AndroidAppPreferences): Result<Unit> {
        var result = Result.success(Unit)
        try {
            context.dataStore.edit { prefs ->
                prefs[Keys.allowMetered] = update.allowMetered
                prefs[Keys.resumeOnUnmetered] = update.resumeOnUnmetered
                prefs[Keys.showMeteredWarning] = update.showMeteredWarning
                prefs[Keys.debugLogsEnabled] = update.debugLogsEnabled
                prefs[Keys.advancedSettingsEnabled] = update.advancedSettingsEnabled
                prefs[Keys.androidIceMode] = normalizeAndroidIceMode(update.androidIceMode)
                prefs.remove(Keys.pauseOnMetered)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: IllegalStateException) {
            result = Result.failure(e)
        } catch (e: IOException) {
            result = Result.failure(e)
        }
        return result
    }

    /**
     * Ensures a default config exists, returning the outcome (FIX6 P0-001-A / INV-010).
     *
     * The existence check and the write happen under the same [writeMutex]. Previously the
     * check sat outside the lock, so another writer could create the config between the
     * check and the write and have the default overwrite it — the serialization comment
     * claimed a guarantee the code did not provide.
     *
     * Calls [writeConfigAtomicallyLocked] directly rather than [writeConfigAtomically]:
     * the latter takes [writeMutex], which is not reentrant and would deadlock here.
     */
    open suspend fun ensureDefaultConfig(contents: String): Result<Unit> =
        writeMutex.withLock {
            if (configFile.exists()) {
                Result.success(Unit)
            } else {
                writeConfigAtomicallyLocked(configFile, contents)
            }
        }

    val defaultConfigTemplate: String
        get() =
            buildDefaultConfigTemplate(
                context.filesDir,
                ConfigRenderOptions(androidIceMode = resolveAndroidIceMode(DEFAULT_ANDROID_ICE_MODE)),
            )

    fun readConfig(): String = configFile.takeIf { it.exists() }?.readText().orEmpty()

    /**
     * P1-003: Check if config file exists (distinct from blank contents) for transactional
     * reset snapshot accuracy.
     */
    internal val configFileExists: Boolean get() = configFile.exists()

    /**
     * Prepare the active config for a tunnel start by surgically rewriting the two
     * network-dependent `[webrtc]` fields: `android_ice_mode` (the user's chosen [iceMode], or
     * the debug `getprop` override) and `advertised_local_ipv4` ([advertisedIpv4], or removed
     * when null so a strict `vnet_mux` start fails loudly rather than advertising a stale
     * address). Each edit touches only its own line, so both are key-safe on an already-rendered
     * config. No-op when no config exists yet; changes take effect on the next engine build
     * (tunnel restart), since the ICE mode is fixed when the WebRTC engine is built.
     *
     * Returns [Result.success] on success, [Result.failure] if the config write fails,
     * so startup can abort rather than proceeding with a stale or wrong config.
     */
    open suspend fun prepareActiveConfigForStart(
        iceMode: String,
        advertisedIpv4: String?,
    ): Result<Unit> {
        return writeMutex.withLock {
            val current = readConfig()
            if (current.isBlank()) {
                return@withLock Result.success(Unit)
            }
            val withIceMode = upsertAndroidIceMode(current, resolveAndroidIceMode(iceMode))
            writeConfigAtomicallyLocked(
                configFile,
                upsertAdvertisedLocalIpv4(withIceMode, advertisedIpv4),
            )
        }
    }

    /**
     * Write config contents through the atomic writer so all writes are serialized.
     * Routes through [writeConfigAtomically] to prevent direct file writes that bypass
     * the mutex serialization (P1-007).
     */
    suspend fun writeConfig(contents: String): Result<Unit> = writeConfigAtomically(contents)

    /**
     * P1-007: Atomic write with unique temp file under [writeMutex].
     * All config writers go through this single serialized boundary.
     * Returns Result.success(Unit) on success, Result.failure(...) on failure.
     *
     * P1-004/P1-005: open so tests can inject a transactional-reset Config-stage
     * reset/rollback failure without needing a real filesystem-permission scenario.
     */
    open suspend fun writeConfigAtomically(contents: String): Result<Unit> =
        writeMutex.withLock {
            writeConfigAtomicallyLocked(configFile, contents)
        }

    /**
     * Internal: delete config file for transactional reset rollback.
     * Used when the config file was absent before reset and a later stage failed,
     * so rollback must restore the absent state (not leave a stale config behind).
     * Returns Result.success(Unit) on success, Result.failure(...) on failure.
     *
     * P1-006: open so tests can inject a genuine transactional-reset delete-rollback
     * failure instead of only ever exercising the success path.
     */
    internal open suspend fun deleteConfigFileForTransactionalReset(): Result<Unit> =
        writeMutex.withLock {
            try {
                Files.deleteIfExists(configFile.toPath())
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                Result.failure(error)
            }
        }

    // P1-001: open so tests can inject a failure/cancellation for transactional-reset
    // setup-input mutation/rollback path coverage.
    open fun saveSetupInput(input: SetupConfigInput) {
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
): Result<Unit> {
    configFile.parentFile?.mkdirs()
    var temp: Path? = null
    return try {
        temp =
            Files.createTempFile(
                configFile.parentFile?.toPath(),
                "config.toml.tmp-",
                ".partial",
            )
        val tempPath = temp ?: error("temp not assigned")
        tempPath.toFile().writeText(contents)
        try {
            Files.move(
                tempPath,
                configFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: AtomicMoveNotSupportedException) {
            // Fallback when ATOMIC_MOVE is not supported on the filesystem
            android.util.Log.d("ConfigRepository", "Atomic move unavailable, falling back", e)
            Files.move(
                tempPath,
                configFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    } finally {
        // Clean up temp file if it still exists (move succeeded or failed)
        temp?.let { Files.deleteIfExists(it) }
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
