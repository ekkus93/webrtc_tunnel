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
import java.nio.file.Files

val Context.dataStore by preferencesDataStore(name = "android_app_prefs")

internal class ConfigFilesSnapshot(
    val config: ExactFileSnapshot,
    val setupInput: ExactFileSnapshot,
) {
    fun wipeSecrets() = setupInput.wipe()
}

class ConfigReplaceRollbackIncompleteException(
    val writeFailure: Throwable,
    val restoreFailure: Throwable,
) : Exception("Config replace failed and the on-disk self-restore also failed", writeFailure)

open class ConfigRepository internal constructor(
    private val context: Context,
    private val atomicFileOps: AtomicConfigFileOps = RealAtomicConfigFileOps,
) {
    private val configFile: File get() = File(context.filesDir, "config.toml")
    private val setupInputFile: File get() = File(context.filesDir, "setup_input.json")
    private val fileMutex = Mutex()
    val configPath: String get() = configFile.absolutePath

    open val preferences: Flow<AndroidAppPreferences>
        get() =
            context.dataStore.data.map { prefs ->
                prefs.toAppPreferences()
            }

    @CheckResult
    open suspend fun savePreferences(update: AndroidAppPreferences): Result<Unit> =
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
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }

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

    open val configContents: String get() = configFile.takeIf { it.exists() }?.readText().orEmpty()

    internal val configFileExists: Boolean get() = configFile.exists()

    @get:CheckResult
    internal open val captureConfigSnapshotForReset: Result<ExactFileSnapshot>
        get() = captureExactFileSnapshot(configFile)

    @CheckResult
    open suspend fun prepareActiveConfigForStart(
        iceMode: String,
        advertisedIpv4: String?,
    ): Result<Unit> =
        fileMutex.withLock {
            try {
                val current = if (configFile.exists()) configFile.readText() else ""
                if (current.isBlank()) {
                    return@withLock Result.success(Unit)
                }
                val withIceMode = upsertAndroidIceMode(current, resolveAndroidIceMode(iceMode))
                writeConfigAtomicallyWith(
                    configFile,
                    upsertAdvertisedLocalIpv4(withIceMode, advertisedIpv4),
                    atomicFileOps,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
        }

    @CheckResult
    open suspend fun writeConfigAtomically(contents: String): Result<Unit> =
        fileMutex.withLock {
            writeConfigAtomicallyWith(configFile, contents, atomicFileOps)
        }

    internal open val replaceConfigTransactionally: suspend (String) -> Result<Unit> = { contents ->
        fileMutex.withLock {
            val priorSnapshot =
                try {
                    captureExactFileSnapshot(configFile).getOrThrow()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    return@withLock Result.failure(error)
                }
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

    open fun saveSetupInput(input: SetupConfigInput) {
        setupInputFile.parentFile?.let { Files.createDirectories(it.toPath()) }
        setupInputFile.writeText(Json.encodeToString(input))
    }

    @CheckResult
    internal open suspend fun saveSetupInputAtomically(input: SetupConfigInput): Result<Unit> =
        fileMutex.withLock {
            mutationResult {
                atomicReplaceBytes(setupInputFile, Json.encodeToString(input).encodeToByteArray(), atomicFileOps)
            }
        }

    internal val setupInputFileForSnapshot: File get() = setupInputFile

    @CheckResult
    internal open suspend fun restoreSetupInputFileSnapshot(snapshot: ExactFileSnapshot): Result<Unit> =
        fileMutex.withLock {
            restoreExactFileSnapshot("setup input", setupInputFile, snapshot) { file, bytes ->
                atomicReplaceBytes(file, bytes, atomicFileOps)
            }
        }

    @CheckResult
    internal open suspend fun restoreConfigSnapshot(snapshot: ExactFileSnapshot): Result<Unit> =
        fileMutex.withLock {
            restoreExactFileSnapshot("config", configFile, snapshot) { file, bytes ->
                atomicReplaceBytes(file, bytes, atomicFileOps)
            }
        }

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

    internal val filesDir: File get() = context.filesDir
}

@CheckResult
fun ConfigRepository.loadSetupInputResult(): Result<SetupConfigInput> {
    val setupInputFile = setupInputFileForSnapshot
    if (!setupInputFile.exists()) {
        return Result.success(SetupConfigInput())
    }
    return try {
        Result.success(Json.decodeFromString<SetupConfigInput>(setupInputFile.readText()))
    } catch (error: Exception) {
        Result.failure(error)
    }
}

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

@CheckResult
suspend fun ConfigRepository.writeConfig(contents: String): Result<Unit> = writeConfigAtomically(contents)

internal fun resolveAndroidIceMode(userPreference: String): String =
    debugAndroidIceModeOverrideOrNull() ?: normalizeAndroidIceMode(userPreference)

private fun debugAndroidIceModeOverrideOrNull(): String? {
    if (!BuildConfig.DEBUG) {
        return null
    }
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
