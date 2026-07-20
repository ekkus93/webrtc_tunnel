package com.phillipchin.webrtctunnel.data

import android.content.Context
import androidx.annotation.CheckResult
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

    // P1-002: open so tests can inject a snapshot-read failure/cancellation for the
    // transactional-reset capture path without needing a filesystem-corruption scenario.
    open fun readConfig(): String = configFile.takeIf { it.exists() }?.readText().orEmpty()

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
    @CheckResult
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
     * P1-007: Atomic write with unique temp file under [writeMutex].
     * All config writers go through this single serialized boundary.
     * Returns Result.success(Unit) on success, Result.failure(...) on failure.
     *
     * P1-004/P1-005: open so tests can inject a transactional-reset Config-stage
     * reset/rollback failure without needing a real filesystem-permission scenario.
     */
    @CheckResult
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
    @CheckResult
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

    // FIX6 P0-003: exposed so the top-level setup-input snapshot/restore helpers (below)
    // can capture and restore it. internal, and the file is app-private.
    internal val setupInputFileForSnapshot: File get() = setupInputFile

    /**
     * FIX7 P0-005-A: restore `setup_input.json` to an exact prior [ExactFileSnapshot] using real
     * atomic replacement (or checked deletion when it was absent) rather than
     * [saveSetupInput]'s re-derived write, which cannot represent "absent". open so tests can
     * inject a rollback-restore failure the same way every other reset stage's restore path can.
     */
    @CheckResult
    internal open fun restoreSetupInputFileSnapshot(snapshot: ExactFileSnapshot): Result<Unit> =
        restoreExactFileSnapshot("setup input", setupInputFile, snapshot, ::setupInputAtomicReplace)

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

    // FIX7 P0-003-A: pure — no file creation/write/delete/permission change, repository
    // mutation, preference read, or network call. The caller decides brokerPasswordPath
    // (resolveBrokerPasswordPath) and, if it points at the managed BrokerSecretRepository path,
    // must have already persisted it there; this function only ever turns inputs into a string.
    fun renderOfferConfig(
        input: SetupConfigInput,
        forwards: List<ForwardConfig>,
        debugLogs: Boolean = false,
        androidIceMode: String = DEFAULT_ANDROID_ICE_MODE,
        brokerPasswordPath: String?,
    ): String =
        buildOfferConfig(
            input,
            forwards,
            context.filesDir,
            brokerPasswordPath.orEmpty(),
            ConfigRenderOptions(
                debugLogs = debugLogs,
                androidIceMode = resolveAndroidIceMode(androidIceMode),
            ),
        )
}

/**
 * Write config contents through the atomic writer so all writes are serialized. Routes through
 * [ConfigRepository.writeConfigAtomically] to prevent direct file writes that bypass the mutex
 * serialization (P1-007). An extension function (not a class member) so it doesn't count against
 * [ConfigRepository]'s detekt TooManyFunctions threshold — call sites (`configRepository
 * .writeConfig(...)`) are unaffected, since Kotlin resolves member and extension calls identically.
 */
suspend fun ConfigRepository.writeConfig(contents: String): Result<Unit> = writeConfigAtomically(contents)

/**
 * P1-006: file operations for the atomic config write, injectable so the temp-cleanup-inside-Result
 * paths are testable with a fake instead of flaky filesystem permission tricks.
 */
internal interface AtomicConfigFileOps {
    fun createTempFile(
        dir: Path,
        prefix: String,
        suffix: String,
    ): Path

    fun writeText(
        temp: Path,
        contents: String,
    )

    /** Atomic move; may throw [AtomicMoveNotSupportedException] on filesystems that lack it. */
    fun atomicMove(
        temp: Path,
        destination: Path,
    )

    fun plainMove(
        temp: Path,
        destination: Path,
    )

    /** Deletes the temp file; may throw [IOException]. */
    fun deleteIfExists(temp: Path)
}

internal object RealAtomicConfigFileOps : AtomicConfigFileOps {
    override fun createTempFile(
        dir: Path,
        prefix: String,
        suffix: String,
    ): Path = Files.createTempFile(dir, prefix, suffix)

    override fun writeText(
        temp: Path,
        contents: String,
    ) {
        temp.toFile().writeText(contents)
    }

    override fun atomicMove(
        temp: Path,
        destination: Path,
    ) {
        Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun plainMove(
        temp: Path,
        destination: Path,
    ) {
        Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun deleteIfExists(temp: Path) {
        Files.deleteIfExists(temp)
    }
}

/**
 * Internal: atomic config write without acquiring the mutex (caller must hold [writeMutex]).
 */
private fun writeConfigAtomicallyLocked(
    configFile: File,
    contents: String,
): Result<Unit> = writeConfigAtomicallyWith(configFile, contents, RealAtomicConfigFileOps)

/**
 * P1-006: the atomic write with temp cleanup kept INSIDE the returned [Result]. A cleanup failure
 * never overwrites a primary failure (it is attached as suppressed); a cleanup failure after a
 * successful move surfaces as a failure; cancellation is rethrown with the cleanup error
 * suppressed. The visible atomic-move → plain-move fallback is preserved.
 */
internal fun writeConfigAtomicallyWith(
    configFile: File,
    contents: String,
    ops: AtomicConfigFileOps,
): Result<Unit> {
    configFile.parentFile?.mkdirs()
    val temp =
        try {
            val dir = configFile.parentFile?.toPath() ?: throw IOException("Config file has no parent dir")
            ops.createTempFile(dir, "config.toml.tmp-", ".partial")
        } catch (error: IOException) {
            return Result.failure(error)
        }
    return finishAtomicWrite(configFile, contents, ops, temp)
}

/** Performs the write+move then composes the temp-cleanup outcome into the returned [Result]. */
private fun finishAtomicWrite(
    configFile: File,
    contents: String,
    ops: AtomicConfigFileOps,
    temp: Path,
): Result<Unit> {
    val primaryResult: Result<Unit> =
        try {
            ops.writeText(temp, contents)
            try {
                ops.atomicMove(temp, configFile.toPath())
            } catch (e: AtomicMoveNotSupportedException) {
                android.util.Log.d("ConfigRepository", "Atomic move unavailable, falling back", e)
                ops.plainMove(temp, configFile.toPath())
            }
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            deleteTempOrNull(ops, temp)?.let(cancelled::addSuppressed)
            throw cancelled
        } catch (error: IOException) {
            Result.failure(error)
        }

    val cleanupError = deleteTempOrNull(ops, temp) ?: return primaryResult
    val primaryError = primaryResult.exceptionOrNull()
    primaryError?.addSuppressed(cleanupError)
    return primaryError?.let { primaryResult } ?: Result.failure(cleanupError)
}

private fun deleteTempOrNull(
    ops: AtomicConfigFileOps,
    temp: Path,
): IOException? =
    try {
        ops.deleteIfExists(temp)
        null
    } catch (error: IOException) {
        error
    }

/**
 * Resolve the effective `android_ice_mode`: the debug `getprop` override wins when present
 * (so the E2E harness can force a mode), otherwise the user's chosen [userPreference]
 * (normalized). This is the single chokepoint every render/apply path goes through.
 */
internal fun resolveAndroidIceMode(userPreference: String): String =
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

/**
 * Decides the effective broker password path with no I/O (FIX7 P0-003-A): the user's explicit
 * "advanced" path always wins; otherwise, if a password was entered, [managedPath] (the caller's
 * already-persisted [BrokerSecretRepository.path]) is used; otherwise there is no password file.
 */
fun resolveBrokerPasswordPath(
    input: SetupConfigInput,
    managedPath: String,
): String? {
    val advancedPath = input.brokerPasswordFile.trim()
    return when {
        advancedPath.isNotBlank() -> advancedPath
        input.brokerPassword.isBlank() -> null
        else -> managedPath
    }
}
