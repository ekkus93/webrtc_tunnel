package com.phillipchin.webrtctunnel.data

import androidx.annotation.CheckResult
import kotlinx.coroutines.CancellationException
import java.io.File
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
@CheckResult
internal suspend inline fun <T> mutationResult(crossinline block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

/**
 * FIX7 P1-005-B: shared "which failure wins" decision for the primary-write-plus-temp-cleanup
 * composition used by [com.phillipchin.webrtctunnel.data.ForwardsConfigStore],
 * [com.phillipchin.webrtctunnel.data.BrokerSecretRepository], the setup-input snapshot
 * restore, and [com.phillipchin.webrtctunnel.security.IdentityRepository]'s atomic replace.
 * A cleanup failure on top of a primary failure is attached as suppressed rather than
 * replacing or discarding it; a cleanup failure after an otherwise-successful primary still
 * throws. Extracted to a single throw site so each caller's own function stays under
 * detekt's `ThrowsCount` threshold.
 */
internal fun throwComposedFailureIfAny(
    primaryFailure: Exception?,
    cleanupFailure: Exception?,
) {
    val toThrow =
        when {
            primaryFailure != null && cleanupFailure != null -> {
                primaryFailure.addSuppressed(cleanupFailure)
                primaryFailure
            }
            primaryFailure != null -> primaryFailure
            cleanupFailure != null -> cleanupFailure
            else -> null
        }
    if (toThrow != null) throw toThrow
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
    // FIX8 P0-008-C: checked, not an ignored mkdirs() Boolean.
    Files.createDirectories(cacheDir.toPath())
    return Files.createTempFile(cacheDir.toPath(), prefix, ".toml").toFile()
}

/**
 * Deletes a candidate file, returning the failure instead of throwing.
 *
 * Cleanup failure must never replace the primary validation/write outcome, so callers
 * consume this separately rather than letting it escape from a `finally` (FIX6 INV-013).
 */
@CheckResult
internal fun deleteCandidateFileSafely(file: File): Result<Unit> =
    try {
        Files.deleteIfExists(file.toPath())
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        // FIX8 P0-008-C: catches every ordinary exception (not just IOException) — a
        // SecurityException from a denied delete must surface as a failure too.
        Result.failure(error)
    }

/** Thrown when a scoped candidate/workspace cleanup fails and there was no primary
 * failure/cancellation to attach it to as suppressed (FIX7 P0-002-C / INV-010). The message is
 * a fixed safe string; the real cause is the cleanup exception, attached as [cause]. */
internal class CandidateCleanupException(
    message: String,
    cause: Throwable,
) : Exception(message, cause)

/** Outcome of [block] in [withCleanupComposition], captured so mandatory cleanup can run
 * regardless of how [block] exited — including a fatal [Error] — before the exact original
 * throwable (never a converted/re-wrapped one) is rethrown (FIX8 P0-008-B). */
private sealed interface ScopedOutcome<out T> {
    data class Value<T>(val value: T) : ScopedOutcome<T>

    data class Failure(val throwable: Throwable) : ScopedOutcome<Nothing>
}

/**
 * Composes a scoped resource's cleanup with the block's own outcome so a caller cannot forget
 * to consume the cleanup result (FIX7 P0-002-C / INV-010):
 * - primary failure/cancellation/fatal `Error` + cleanup failure: primary is preserved, cleanup
 *   is attached as suppressed;
 * - primary success + ordinary cleanup failure: the overall call fails with
 *   [CandidateCleanupException];
 * - primary success + fatal cleanup `Error`: that same `Error` propagates;
 * - cleanup success: the primary outcome (value or exception) passes through unchanged, as the
 *   exact original throwable instance — never re-wrapped.
 *
 * FIX8 P0-008-B: a fatal `Error` from [block] (e.g. `OutOfMemoryError`) must still run cleanup
 * before propagating, since a skipped cleanup can leave a secret-bearing candidate file behind.
 * Rather than one broad `catch (Throwable)`, [CancellationException], [Error], and [Exception]
 * are caught in three explicit clauses — together exhaustive over the whole `Throwable`
 * hierarchy — so this stays a normal, specific catch by detekt's `TooGenericExceptionCaught`
 * rule; every captured throwable is rethrown unchanged immediately below, never swallowed or
 * converted.
 */
private inline fun <T> withCleanupComposition(
    cleanup: () -> Result<Unit>,
    block: () -> T,
): T {
    // Not a finally block (detekt forbids throwing from finally): the primary outcome is
    // captured first, cleanup always runs next, and only then do we decide what to
    // return/throw — so a successful outcome can still be converted to a cleanup failure.
    val outcome: ScopedOutcome<T> =
        try {
            ScopedOutcome.Value(block())
        } catch (cancelled: CancellationException) {
            ScopedOutcome.Failure(cancelled)
        } catch (fatal: Error) {
            ScopedOutcome.Failure(fatal)
        } catch (primary: Exception) {
            ScopedOutcome.Failure(primary)
        }

    val cleanupFailure: Throwable? =
        try {
            cleanup().exceptionOrNull()
        } catch (cancelled: CancellationException) {
            cancelled
        } catch (fatal: Error) {
            fatal
        } catch (failure: Exception) {
            failure
        }

    val toThrow: Throwable? =
        when {
            outcome is ScopedOutcome.Failure -> {
                cleanupFailure?.let(outcome.throwable::addSuppressed)
                outcome.throwable
            }
            cleanupFailure == null -> null
            // A cancellation or fatal Error from cleanup itself must propagate unchanged, the
            // same as one from the primary block above — never wrapped into
            // CandidateCleanupException, which is reserved for an ordinary cleanup failure.
            cleanupFailure is CancellationException || cleanupFailure is Error -> cleanupFailure
            else ->
                CandidateCleanupException("Failed to remove temporary configuration candidate", cleanupFailure)
        }

    if (toThrow != null) throw toThrow
    check(outcome is ScopedOutcome.Value)
    return outcome.value
}

/**
 * Runs [block] against a freshly created unique candidate file, composing its cleanup with the
 * block's outcome via [withCleanupComposition] so no caller can accidentally discard a cleanup
 * failure (FIX7 P0-002-C).
 *
 * [deleteCandidate] is injectable (the same seam [withTemporaryDirectory]'s [deleteRecursively]
 * uses) so tests can force a cleanup failure with a fake instead of a flaky filesystem
 * permission trick — FIX7 P1-001-C.
 */
internal suspend fun <T> withCandidateFile(
    cacheDir: File,
    prefix: String,
    deleteCandidate: (File) -> Result<Unit> = ::deleteCandidateFileSafely,
    block: suspend (File) -> T,
): T {
    val candidate = createCandidateFile(cacheDir, prefix)
    return withCleanupComposition(cleanup = { deleteCandidate(candidate) }) { block(candidate) }
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
    // FIX8 P0-008-C: checked, not an ignored mkdirs() Boolean.
    Files.createDirectories(cacheDir.toPath())
    val root = Files.createTempDirectory(cacheDir.toPath(), prefix).toFile()
    return withCleanupComposition(cleanup = { deleteRecursively(root) }) { block(root) }
}

/** Deletes [root] and everything under it, returning the failure instead of throwing. */
@CheckResult
internal fun deleteDirectoryRecursivelySafely(root: File): Result<Unit> =
    try {
        root.walkBottomUp().forEach { entry ->
            Files.deleteIfExists(entry.toPath())
        }
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        // FIX8 P0-008-C: catches every ordinary exception (not just IOException) — a
        // SecurityException from a denied delete must surface as a failure too.
        Result.failure(error)
    }
