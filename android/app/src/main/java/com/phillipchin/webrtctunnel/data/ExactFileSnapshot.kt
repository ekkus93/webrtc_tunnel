package com.phillipchin.webrtctunnel.data

import androidx.annotation.CheckResult
import kotlinx.coroutines.CancellationException
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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

/**
 * Snapshot of the setup-input file for transactional rollback (FIX6 P0-003). Distinguishes
 * an absent file from a blank/present one so restore can recreate the exact prior state.
 */
data class SetupInputSnapshot(
    val existed: Boolean,
    val contents: String?,
)

/**
 * FIX6 P0-003: capture the exact prior setup-input file state (distinguishing absent from
 * blank/corrupt) so a failed setup transaction can restore it precisely. Top-level (not a
 * [ConfigRepository] member) to keep that class under detekt's TooManyFunctions threshold, and
 * colocated here (not in ConfigRepository.kt) to keep that FILE under the same threshold.
 */
fun captureSetupInputSnapshot(setupInputFile: File): SetupInputSnapshot =
    if (setupInputFile.exists()) {
        SetupInputSnapshot(existed = true, contents = setupInputFile.readText())
    } else {
        SetupInputSnapshot(existed = false, contents = null)
    }

/** Restore setup-input to a captured [snapshot], recreating the absent state exactly. */
fun restoreSetupInputSnapshot(
    setupInputFile: File,
    snapshot: SetupInputSnapshot,
) {
    if (snapshot.existed) {
        setupInputFile.parentFile?.mkdirs()
        setupInputFile.writeText(snapshot.contents.orEmpty())
    } else {
        setupInputFile.delete()
    }
}

/**
 * FIX7 P0-005-A: exact byte-level setup-input snapshot for [TransactionalResetCoordinator], using
 * [ExactFileSnapshot] instead of the [SetupInputSnapshot]/[captureSetupInputSnapshot] pair above,
 * so an absent file is distinguishable from a default-valued one (CRITICAL-3). [readBytes] mirrors
 * [BrokerSecretRepository]'s same-purpose seam: it lets a test observe the exact byte array this
 * snapshot captured, to prove [TransactionalResetCoordinator] wipes it once the transaction
 * finishes (FIX7 P0-005-E `resetSnapshotSecretBytesAreWiped`) without a filesystem trick.
 */
internal fun captureSetupInputFileSnapshot(
    setupInputFile: File,
    readBytes: (File) -> ByteArray = File::readBytes,
): Result<ExactFileSnapshot> =
    try {
        Result.success(
            if (setupInputFile.exists()) {
                ExactFileSnapshot(existed = true, bytes = readBytes(setupInputFile))
            } else {
                ExactFileSnapshot(existed = false, bytes = null)
            },
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

/** Same unique-temp-file-plus-move pattern as [BrokerSecretRepository]'s atomic replace, without
 * the owner-only permission step — `setup_input.json` isn't restricted differently from its
 * normal [ConfigRepository.saveSetupInput] writes, so restoring it shouldn't change that.
 * internal (not private): called from [ConfigRepository.restoreSetupInputFileSnapshot] in the
 * sibling file. */
internal fun setupInputAtomicReplace(
    destination: File,
    bytes: ByteArray,
) {
    destination.parentFile?.mkdirs()
    val temp = Files.createTempFile(destination.parentFile?.toPath(), "${destination.name}.tmp-", ".partial")
    // FIX7 P1-005-B: the temp file's cleanup result is checked, not discarded (the previous
    // `finally { runCatching { ... } }` dropped it entirely, worse than the other two
    // temp-cleanup sites in this codebase which at least logged). A cleanup failure on top
    // of a primary failure is attached as suppressed, never silently lost; a cleanup failure
    // after an otherwise-successful replace still surfaces as a failure.
    val primaryFailure =
        try {
            Files.write(temp, bytes)
            try {
                Files.move(
                    temp,
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (error: AtomicMoveNotSupportedException) {
                android.util.Log.d("ConfigRepository", "Atomic move unavailable, falling back", error)
                Files.move(temp, destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            error
        }
    val cleanupFailure =
        try {
            Files.deleteIfExists(temp)
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            error
        }
    throwComposedFailureIfAny(primaryFailure, cleanupFailure)
}
