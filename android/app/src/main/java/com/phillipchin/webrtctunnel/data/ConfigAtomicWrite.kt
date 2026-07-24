package com.phillipchin.webrtctunnel.data

import androidx.annotation.CheckResult
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * P1-006/FIX8 P0-003-C: file operations for the one same-directory temp-plus-atomic-move
 * primitive ([atomicReplaceBytesWith]), injectable so the temp-cleanup-inside-Result paths are
 * testable with a fake instead of flaky filesystem permission tricks.
 */
internal interface AtomicConfigFileOps {
    fun createTempFile(
        dir: Path,
        prefix: String,
        suffix: String,
    ): Path

    fun writeBytes(
        temp: Path,
        bytes: ByteArray,
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

    override fun writeBytes(
        temp: Path,
        bytes: ByteArray,
    ) {
        Files.write(temp, bytes)
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
 * P1-006: config's atomic write, expressed in terms of the shared byte-level
 * [atomicReplaceBytesWith] primitive (FIX8 P0-003-C) so config.toml and every other
 * authoritative file (setup_input.json, restores) go through the same temp-plus-atomic-move
 * logic instead of two parallel implementations.
 */
@CheckResult
internal fun writeConfigAtomicallyWith(
    configFile: File,
    contents: String,
    ops: AtomicConfigFileOps,
): Result<Unit> = atomicReplaceBytesWith(configFile, contents.toByteArray(), ops)

/**
 * FIX8 P0-003-C: the one same-directory temp-plus-atomic-move byte-replacement primitive for
 * this repository's authoritative files (config.toml, setup_input.json, and — via
 * [postMoveVerify] — broker-secret-style callers that must additionally enforce/verify
 * owner-only permissions after the move; not yet adopted there — see P0-008).
 *
 * Uses [Files.createDirectories] (checked) rather than an ignored `mkdirs()`, writes raw bytes
 * (not a UTF-8 String) so exact snapshots round-trip without corruption, catches every ordinary
 * [Exception] (not just [IOException] — a [SecurityException] from a denied file operation must
 * surface as a failure too) and composes temp cleanup into the returned [Result] rather than
 * silently dropping it, and preserves cancellation. [ops] is injectable so tests can force a
 * write/move/cleanup failure with a fake instead of a flaky filesystem permission trick.
 */
@CheckResult
internal fun atomicReplaceBytesWith(
    destination: File,
    bytes: ByteArray,
    ops: AtomicConfigFileOps,
    postMoveVerify: (File) -> Unit = {},
): Result<Unit> {
    val parentDir = destination.parentFile
    val tempResult: Result<Path> =
        if (parentDir == null) {
            Result.failure(IOException("${destination.name} has no parent directory"))
        } else {
            try {
                Files.createDirectories(parentDir.toPath())
                Result.success(ops.createTempFile(parentDir.toPath(), ".${destination.name}.tmp-", ".partial"))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
        }
    return tempResult.fold(
        onSuccess = { temp -> finishAtomicWrite(destination, bytes, ops, temp, postMoveVerify) },
        onFailure = { error -> Result.failure(error) },
    )
}

/** Performs the write+move then composes the temp-cleanup outcome into the returned [Result]. */
private fun finishAtomicWrite(
    destination: File,
    bytes: ByteArray,
    ops: AtomicConfigFileOps,
    temp: Path,
    postMoveVerify: (File) -> Unit,
): Result<Unit> {
    val primaryResult: Result<Unit> =
        try {
            ops.writeBytes(temp, bytes)
            try {
                ops.atomicMove(temp, destination.toPath())
            } catch (e: AtomicMoveNotSupportedException) {
                // FIX8 P1-003-C: never log the raw Throwable — an AtomicMoveNotSupportedException's
                // own message includes the raw source/destination paths, and Log.d's stack-trace
                // formatting would print them; only a fixed, redacted summary is safe to log.
                android.util.Log.d(
                    "ConfigRepository",
                    "Atomic move unavailable, falling back: " +
                        SensitiveDataRedactor.redactText(e.message ?: "no detail"),
                )
                ops.plainMove(temp, destination.toPath())
            }
            postMoveVerify(destination)
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            deleteTempOrNull(ops, temp)?.let(cancelled::addSuppressed)
            throw cancelled
        } catch (error: Exception) {
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
): Exception? =
    try {
        ops.deleteIfExists(temp)
        null
    } catch (error: Exception) {
        error
    }
