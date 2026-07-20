package com.phillipchin.webrtctunnel.data

import androidx.annotation.CheckResult
import kotlinx.coroutines.CancellationException
import java.io.File
import java.nio.file.Files

/**
 * Exact prior state of one file for transactional rollback (FIX7 P0-002 / INV-004).
 *
 * A parsed/default model value is not sufficient for exact restoration: [existed] and [bytes]
 * together distinguish absent, present-and-empty, and present-and-non-empty, and rollback must
 * reproduce exactly that state rather than a plausible substitute.
 */
class ExactFileSnapshot internal constructor(
    val existed: Boolean,
    val bytes: ByteArray?,
) {
    /** Wipes captured bytes in place once the transaction no longer needs them. Callers owning
     * a secret-bearing snapshot (e.g. a broker password) must invoke this; public/non-secret
     * snapshots (e.g. config, public identity) do not need to. */
    fun wipe() {
        bytes?.fill(0)
    }
}

/**
 * Captures [file]'s exact current state. A read failure is returned, not thrown, so the caller
 * can abort the parent transaction before any mutation rather than starting a commit whose
 * rollback state is unknown.
 */
internal fun captureExactFileSnapshot(file: File): Result<ExactFileSnapshot> =
    try {
        Result.success(
            if (file.exists()) {
                ExactFileSnapshot(existed = true, bytes = file.readBytes())
            } else {
                ExactFileSnapshot(existed = false, bytes = null)
            },
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

/**
 * Restores [file] to exactly [snapshot]'s prior state: a present snapshot is written back via
 * [atomicReplace] (the caller's atomic same-directory-temp-plus-move implementation); an absent
 * snapshot is restored by deleting the file, not by leaving whatever a failed write left behind.
 *
 * The result must be consumed — a discarded restore failure can silently leave rollback
 * incomplete while callers believe prior state was restored, so this is [CheckResult]-annotated
 * for static enforcement (P2-002).
 */
@CheckResult
internal fun restoreExactFileSnapshot(
    logicalName: String,
    file: File,
    snapshot: ExactFileSnapshot,
    atomicReplace: (File, ByteArray) -> Unit,
): Result<Unit> =
    try {
        if (snapshot.existed) {
            atomicReplace(
                file,
                requireNotNull(snapshot.bytes) { "$logicalName snapshot bytes are missing" },
            )
        } else {
            Files.deleteIfExists(file.toPath())
        }
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
