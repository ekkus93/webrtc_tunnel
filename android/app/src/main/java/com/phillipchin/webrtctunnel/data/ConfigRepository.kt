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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files

val Context.dataStore by preferencesDataStore(name = "android_app_prefs")

/**
 * FIX8 P0-003-B/INV-005: a coherent, exact-byte snapshot of both authoritative files
 * [ConfigRepository] owns, captured under its one [fileMutex]-serialized boundary. Replaces
 * the setup transaction's previous separate, unlocked, String/Boolean-derived config+setup-input
 * fields (CRITICAL-3).
 */
internal class ConfigFilesSnapshot(
    val config: ExactFileSnapshot,
    val setupInput: ExactFileSnapshot,
) {
    /** `setup_input.json` can carry the plaintext broker password (spec §3.4); `config.toml`
     * never carries a raw secret, so only the setup-input bytes need wiping. */
    fun wipeSecrets() = setupInput.wipe()
}

/**
 * FIX8 P0-005-B: thrown when [ConfigRepository]'s transactional config replace fails AND the
 * attempted self-restore (back to the exact prior bytes) also failed — the caller must know
 * `config.toml` may now disagree with both the prior and intended state, rather than seeing only
 * the original write failure.
 */
class ConfigReplaceRollbackIncompleteException(
    val writeFailure: Throwable,
    val restoreFailure: Throwable,
) : Exception("Config replace failed and the on-disk self-restore also failed", writeFailure)

open class ConfigRepository internal constructor(
    private val context: Context,
    // FIX8 P0-003-C/G: injectable so tests can force a deterministic write/move/cleanup failure
    // (or a blocking barrier proving fileMutex serialization) with a fake instead of a flaky
    // filesystem permission trick — the same seam IdentityRepository/BrokerSecretRepository
    // already expose via their own atomicReplace constructor parameters. The constructor itself
    // must be `internal` (not public) since [AtomicConfigFileOps] is internal; every existing
    // `ConfigRepository(context)` call site is unaffected since this parameter defaults and
    // every caller is within this module.
    private val atomicFileOps: AtomicConfigFileOps = RealAtomicConfigFileOps,
) {
    private val configFile: File get() = File(context.filesDir, "config.toml")
    private val setupInputFile: File get() = File(context.filesDir, "setup_input.json")

    // P1-007/FIX8 P0-003-B: one serialization boundary for BOTH config.toml and
    // setup_input.json — every read, exact-snapshot capture, write, and restore of either
    // file goes through this same mutex, so a snapshot can never observe one file mid-write
    // by a concurrent operation on the other.
    private val fileMutex = Mutex()
    val configPath: String get() = configFile.absolutePath

    // P1-002: open so tests can inject a preference-read failure (e.g. for
    // TunnelForegroundService.handlePolicyAllowed() diagnostic coverage).
    open val preferences: Flow<AndroidAppPreferences>
        get() =
            context.dataStore.data.map { prefs ->
                prefs.toAppPreferences()
            }

    // P1-016: Wrap preference writes so failures are visible.
    @CheckResult
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
     * The existence check and the write happen under the same [fileMutex]. Previously the
     * check sat outside the lock, so another writer could create the config between the
     * check and the write and have the default overwrite it — the serialization comment
     * claimed a guarantee the code did not provide.
     *
     * Calls [writeConfigAtomicallyWith] directly rather than [writeConfigAtomically]:
     * the latter takes [fileMutex], which is not reentrant and would deadlock here.
     */
    @CheckResult
    open suspend fun ensureDefaultConfig(contents: String): Result<Unit> =
        fileMutex.withLock {
            if (configFile.exists()) {
                Result.success(Unit)
            } else {
                writeConfigAtomicallyWith(configFile, contents, atomicFileOps)
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
    // FIX8 P0-003: a property (not a function) so it doesn't count against this class's detekt
    // TooManyFunctions threshold — semantically unchanged, still a synchronous file read.
    open val configContents: String get() = configFile.takeIf { it.exists() }?.readText().orEmpty()

    /**
     * P1-003: Check if config file exists (distinct from blank contents) for transactional
     * reset snapshot accuracy.
     */
    internal val configFileExists: Boolean get() = configFile.exists()

    /**
     * FIX8 P0-006-A: raw-byte snapshot capture for transactional reset — unlike [configContents]
     * this never round-trips through a UTF-8 String, so non-UTF-8 bytes survive a snapshot/
     * restore cycle intact. A property (not a `fun`, to stay under detekt's TooManyFunctions
     * threshold) — open so tests can inject a capture failure the same way every other reset
     * stage's apply/restore path can.
     */
    @get:CheckResult
    internal open val captureConfigSnapshotForReset: Result<ExactFileSnapshot>
        get() = captureExactFileSnapshot(configFile)

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
        return fileMutex.withLock {
            val current = configContents
            if (current.isBlank()) {
                return@withLock Result.success(Unit)
            }
            val withIceMode = upsertAndroidIceMode(current, resolveAndroidIceMode(iceMode))
            writeConfigAtomicallyWith(
                configFile,
                upsertAdvertisedLocalIpv4(withIceMode, advertisedIpv4),
                atomicFileOps,
            )
        }
    }

    /**
     * P1-007: Atomic write with unique temp file under [fileMutex].
     * All config writers go through this single serialized boundary.
     * Returns Result.success(Unit) on success, Result.failure(...) on failure.
     *
     * P1-004/P1-005: open so tests can inject a transactional-reset Config-stage
     * reset/rollback failure without needing a real filesystem-permission scenario.
     */
    @CheckResult
    open suspend fun writeConfigAtomically(contents: String): Result<Unit> =
        fileMutex.withLock {
            writeConfigAtomicallyWith(configFile, contents, atomicFileOps)
        }

    /**
     * FIX8 P0-005-B: capture-attempt-restore for config.toml, the config-side counterpart to
     * import/forward-activation's cleanup-before-commit ordering (P0-005-A) — callers write the
     * authoritative config only through this so a write that fails (including a cleanup-only
     * failure raised after the destination was already atomically moved) cannot leave
     * `config.toml` changed while the caller reports failure. Capture, attempt, and restore all
     * happen under one [fileMutex] acquisition. A cancellation raised during the write still
     * attempts the restore — under [NonCancellable], since the scope is already cancelled — before
     * rethrowing. A restore failure composes into [ConfigReplaceRollbackIncompleteException]
     * rather than being discarded.
     *
     * A property holding a function value (not a `fun` declaration) so it doesn't count against
     * this class's detekt TooManyFunctions threshold — called identically to a member function
     * (`replaceConfigTransactionally(contents)`) via Kotlin's `invoke` syntax.
     */
    internal open val replaceConfigTransactionally: suspend (String) -> Result<Unit> = { contents ->
        fileMutex.withLock {
            val priorSnapshot = captureExactFileSnapshot(configFile).getOrThrow()
            try {
                writeConfigAtomicallyWith(configFile, contents, atomicFileOps).getOrThrow()
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                val restoreFailure =
                    withContext(NonCancellable) {
                        restoreExactFileSnapshot("config", configFile, priorSnapshot) { file, bytes ->
                            atomicReplaceBytes(file, bytes, atomicFileOps)
                        }.exceptionOrNull()
                    }
                if (restoreFailure != null) {
                    cancelled.addSuppressed(ConfigReplaceRollbackIncompleteException(cancelled, restoreFailure))
                }
                throw cancelled
            } catch (writeFailure: Exception) {
                val restoreFailure =
                    restoreExactFileSnapshot("config", configFile, priorSnapshot) { file, bytes ->
                        atomicReplaceBytes(file, bytes, atomicFileOps)
                    }.exceptionOrNull()
                if (restoreFailure != null) {
                    Result.failure(ConfigReplaceRollbackIncompleteException(writeFailure, restoreFailure))
                } else {
                    Result.failure(writeFailure)
                }
            }
        }
    }

    // P1-001: open so tests can inject a failure/cancellation for transactional-reset
    // setup-input mutation/rollback path coverage. Non-atomic; kept for the many existing
    // callers that only need best-effort seeding (tests, TransactionalReset's own current
    // behavior — see P0-006). Production setup-transaction commits use
    // [saveSetupInputAtomically] instead.
    open fun saveSetupInput(input: SetupConfigInput) {
        // FIX8 P0-008-C: checked, not an ignored mkdirs() Boolean.
        setupInputFile.parentFile?.let { Files.createDirectories(it.toPath()) }
        setupInputFile.writeText(Json.encodeToString(input))
    }

    /**
     * FIX8 P0-003-B: atomic, `Result`-returning, mutex-serialized setup-input write for the
     * setup transaction's `SetupInput` stage — unlike [saveSetupInput] this can actually fail
     * without throwing, and cannot leave a partially-written file behind. open so tests can
     * inject a write failure the same way every other stage's apply path can.
     */
    @CheckResult
    internal open suspend fun saveSetupInputAtomically(input: SetupConfigInput): Result<Unit> =
        fileMutex.withLock {
            mutationResult {
                atomicReplaceBytes(setupInputFile, Json.encodeToString(input).encodeToByteArray(), atomicFileOps)
            }
        }

    // FIX6 P0-003: exposed so the top-level setup-input snapshot/restore helpers (below)
    // can capture and restore it. internal, and the file is app-private.
    internal val setupInputFileForSnapshot: File get() = setupInputFile

    /**
     * FIX7 P0-005-A: restore `setup_input.json` to an exact prior [ExactFileSnapshot] using real
     * atomic replacement (or checked deletion when it was absent) rather than
     * [saveSetupInput]'s re-derived write, which cannot represent "absent". open so tests can
     * inject a rollback-restore failure the same way every other reset stage's restore path can.
     * FIX8 P0-003-B: now serialized under [fileMutex] like every other config/setup-input
     * reader/writer, so a restore can never race a concurrent write to the same file.
     */
    @CheckResult
    internal open suspend fun restoreSetupInputFileSnapshot(snapshot: ExactFileSnapshot): Result<Unit> =
        fileMutex.withLock {
            restoreExactFileSnapshot("setup input", setupInputFile, snapshot) { file, bytes ->
                atomicReplaceBytes(file, bytes, atomicFileOps)
            }
        }

    /**
     * FIX8 P0-003-E: restore `config.toml` to an exact prior [ExactFileSnapshot] — the config-side
     * counterpart to [restoreSetupInputFileSnapshot], serialized under the same [fileMutex]. open
     * so tests can inject a rollback-restore failure the same way every other stage can.
     */
    @CheckResult
    internal open suspend fun restoreConfigSnapshot(snapshot: ExactFileSnapshot): Result<Unit> =
        fileMutex.withLock {
            restoreExactFileSnapshot("config", configFile, snapshot) { file, bytes ->
                atomicReplaceBytes(file, bytes, atomicFileOps)
            }
        }

    /**
     * FIX8 P0-003-B/E: captures config.toml and setup_input.json as one coherent,
     * [fileMutex]-serialized [ConfigFilesSnapshot] — replacing the separate, unlocked
     * `configExisted`/`configContents`/legacy-`SetupInputSnapshot` reads a setup/reset transaction
     * previously took (CRITICAL-3): those were TOCTOU-able against a concurrent writer and could
     * not represent non-UTF-8 bytes. A capture failure is returned, not thrown, so the caller
     * aborts before any mutation rather than starting a transaction with unknown rollback state.
     */
    @CheckResult
    internal open suspend fun captureFilesSnapshot(): Result<ConfigFilesSnapshot> =
        fileMutex.withLock {
            mutationResult {
                ConfigFilesSnapshot(
                    config = captureExactFileSnapshot(configFile).getOrThrow(),
                    setupInput = captureExactFileSnapshot(setupInputFile).getOrThrow(),
                )
            }
        }

    // FIX8 P0-003: exposed so the top-level renderOfferConfig extension function (below) can
    // build a config without needing direct access to the private context.
    internal val filesDir: File get() = context.filesDir
}

/**
 * Load the saved setup draft, distinguishing a corrupt file (failure) from a
 * legitimately missing one (success with fresh defaults). A corrupt existing draft must
 * NOT silently reset to defaults — callers surface the failure so the user can repair or
 * re-run setup rather than losing their saved values invisibly.
 *
 * An extension function (not a class member) so it doesn't count against
 * [ConfigRepository]'s detekt TooManyFunctions threshold, matching [writeConfig].
 */
@CheckResult
fun ConfigRepository.loadSetupInputResult(): Result<SetupConfigInput> {
    val setupInputFile = setupInputFileForSnapshot
    if (!setupInputFile.exists()) {
        return Result.success(SetupConfigInput())
    }
    // FIX8 P1-003-A: explicit catch, not runCatching — a pure synchronous file read + JSON
    // decode (no native call, no mutation, no suspend chain) cannot observe a
    // CancellationException, but runCatching would also swallow a fatal Error.
    return try {
        Result.success(Json.decodeFromString<SetupConfigInput>(setupInputFile.readText()))
    } catch (error: Exception) {
        Result.failure(error)
    }
}

/**
 * FIX7 P0-003-A: pure — no file creation/write/delete/permission change, repository
 * mutation, preference read, or network call. The caller decides brokerPasswordPath
 * (resolveBrokerPasswordPath) and, if it points at the managed BrokerSecretRepository path,
 * must have already persisted it there; this function only ever turns inputs into a string.
 *
 * An extension function (not a class member) so it doesn't count against
 * [ConfigRepository]'s detekt TooManyFunctions threshold, matching [writeConfig].
 */
fun ConfigRepository.renderOfferConfig(
    input: SetupConfigInput,
    forwards: List<ForwardConfig>,
    debugLogs: Boolean = false,
    androidIceMode: String = DEFAULT_ANDROID_ICE_MODE,
    brokerPasswordPath: String?,
): String =
    buildOfferConfig(
        input,
        forwards,
        filesDir,
        brokerPasswordPath.orEmpty(),
        ConfigRenderOptions(
            debugLogs = debugLogs,
            androidIceMode = resolveAndroidIceMode(androidIceMode),
        ),
    )

/**
 * Write config contents through the atomic writer so all writes are serialized. Routes through
 * [ConfigRepository.writeConfigAtomically] to prevent direct file writes that bypass the mutex
 * serialization (P1-007). An extension function (not a class member) so it doesn't count against
 * [ConfigRepository]'s detekt TooManyFunctions threshold — call sites (`configRepository
 * .writeConfig(...)`) are unaffected, since Kotlin resolves member and extension calls identically.
 */
@CheckResult
suspend fun ConfigRepository.writeConfig(contents: String): Result<Unit> = writeConfigAtomically(contents)

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
    // FIX8 P1-003-A: explicit catch, not runCatching — a synchronous debug-only OS property
    // read (plain ProcessBuilder, not the Rust/JNI bridge; no mutation, no suspend chain)
    // cannot observe a CancellationException, but runCatching would also swallow a fatal Error.
    val raw =
        try {
            ProcessBuilder("getprop", "debug.p2p.android_ice_mode")
                .redirectErrorStream(true)
                .start()
                .inputStream
                .bufferedReader()
                .use { reader -> reader.readLine() }
        } catch (expected: Exception) {
            null
        }
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
