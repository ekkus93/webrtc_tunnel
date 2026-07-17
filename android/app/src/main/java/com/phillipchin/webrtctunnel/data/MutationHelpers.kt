package com.phillipchin.webrtctunnel.data

import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Runs an authoritative mutation and converts recoverable failures into [Result.failure],
 * while letting cancellation and fatal errors escape (FIX6 P0-005-A / INV-003).
 *
 * This exists because `runCatching` catches [CancellationException] and turns it into a
 * normal failure value, so a cancelled save/import/reset would carry on into rollback,
 * state publication, or a user-visible error message instead of terminating promptly.
 *
 * Do not use this where the caller must distinguish specific exception types, or where
 * rollback has to run in a non-cancellable section — write an explicit try/catch there.
 */
internal suspend inline fun <T> mutationResult(crossinline block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

/**
 * Creates a unique candidate file for validation-before-commit flows (FIX6 INV-012).
 *
 * Every candidate gets its own path: the previous fixed names (`config-candidate.toml`,
 * `config-import-candidate.toml`, `config-forwards-candidate.toml`) meant two concurrent
 * operations shared one file, so one could overwrite or delete the other's candidate and
 * commit stale content.
 */
internal fun createCandidateFile(
    cacheDir: File,
    prefix: String,
): File {
    cacheDir.mkdirs()
    return Files.createTempFile(cacheDir.toPath(), prefix, ".toml").toFile()
}

/**
 * Deletes a candidate file, returning the failure instead of throwing.
 *
 * Cleanup failure must never replace the primary validation/write outcome, so callers
 * consume this separately rather than letting it escape from a `finally` (FIX6 INV-013).
 */
internal fun deleteCandidateFileSafely(file: File): Result<Unit> =
    try {
        Files.deleteIfExists(file.toPath())
        Result.success(Unit)
    } catch (error: IOException) {
        Result.failure(error)
    }
