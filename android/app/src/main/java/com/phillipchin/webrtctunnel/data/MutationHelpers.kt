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

/** Thrown when a scoped candidate/workspace cleanup fails and there was no primary
 * failure/cancellation to attach it to as suppressed (FIX7 P0-002-C / INV-010). The message is
 * a fixed safe string; the real cause is the cleanup exception, attached as [cause]. */
internal class CandidateCleanupException(
    message: String,
    cause: Throwable,
) : Exception(message, cause)

/**
 * Composes a scoped resource's cleanup with the block's own outcome so a caller cannot forget
 * to consume the cleanup result (FIX7 P0-002-C / INV-010):
 * - primary failure/cancellation + cleanup failure: primary is preserved, cleanup is attached
 *   as suppressed;
 * - primary success + cleanup failure: the overall call fails with [CandidateCleanupException];
 * - cleanup success: the primary outcome (value or exception) passes through unchanged.
 */
private inline fun <T> withCleanupComposition(
    cleanup: () -> Result<Unit>,
    block: () -> T,
): T {
    // Not a finally block (detekt forbids throwing from finally): the primary outcome is
    // captured as a Result first, cleanup always runs next, and only then do we decide what to
    // return/throw — so a successful outcome can still be converted to a cleanup failure.
    val outcome: Result<T> =
        try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            Result.failure(cancelled)
        } catch (error: Exception) {
            Result.failure(error)
        }

    val cleanupFailure = cleanup().exceptionOrNull()
    if (cleanupFailure != null) {
        val primary = outcome.exceptionOrNull()
        if (primary != null) {
            primary.addSuppressed(cleanupFailure)
        } else {
            throw CandidateCleanupException("Failed to remove temporary configuration candidate", cleanupFailure)
        }
    }

    return outcome.getOrThrow()
}

/**
 * Runs [block] against a freshly created unique candidate file, composing its cleanup with the
 * block's outcome via [withCleanupComposition] so no caller can accidentally discard a cleanup
 * failure (FIX7 P0-002-C).
 */
internal suspend fun <T> withCandidateFile(
    cacheDir: File,
    prefix: String,
    block: suspend (File) -> T,
): T {
    val candidate = createCandidateFile(cacheDir, prefix)
    return withCleanupComposition(cleanup = { deleteCandidateFileSafely(candidate) }) { block(candidate) }
}

/**
 * Runs [block] against a freshly created unique temporary directory, composing its recursive
 * cleanup with the block's outcome via [withCleanupComposition] — the same composition rules
 * `withCandidateFile` uses, for workspace-style callers (e.g. setup validation, P0-003) that need
 * a directory rather than a single file.
 *
 * [deleteRecursively] is injectable (an [AtomicConfigFileOps]-style seam) so tests can force a
 * cleanup failure with a fake instead of a flaky filesystem permission trick — a normal recursive
 * delete empties a directory bottom-up before removing it, so there is no portable
 * non-permission-based way to make the real implementation fail.
 */
internal suspend fun <T> withTemporaryDirectory(
    cacheDir: File,
    prefix: String,
    deleteRecursively: (File) -> Result<Unit> = ::deleteDirectoryRecursivelySafely,
    block: suspend (File) -> T,
): T {
    cacheDir.mkdirs()
    val root = Files.createTempDirectory(cacheDir.toPath(), prefix).toFile()
    return withCleanupComposition(cleanup = { deleteRecursively(root) }) { block(root) }
}

/** Deletes [root] and everything under it, returning the failure instead of throwing. */
internal fun deleteDirectoryRecursivelySafely(root: File): Result<Unit> =
    try {
        root.walkBottomUp().forEach { entry ->
            Files.deleteIfExists(entry.toPath())
        }
        Result.success(Unit)
    } catch (error: IOException) {
        Result.failure(error)
    }
