package com.phillipchin.webrtctunnel.data

import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

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
 * Internal: atomic config write without acquiring the mutex (caller must hold [ConfigRepository]'s
 * fileMutex).
 */
internal fun writeConfigAtomicallyLocked(
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
