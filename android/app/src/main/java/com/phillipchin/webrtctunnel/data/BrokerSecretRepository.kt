package com.phillipchin.webrtctunnel.data

import android.content.Context
import android.system.Os
import android.util.Log
import androidx.annotation.CheckResult
import kotlinx.coroutines.CancellationException
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private const val BROKER_SECRET_TAG = "BrokerSecretRepository"

/** Thrown when broker-secret owner-only permission enforcement or verification fails
 * (FIX8 P0-008-A). The message is a fixed, safe identifier — never the raw `Os` error or a
 * file path — so a caller can map this to a visible `broker_secret_permissions_failed` result
 * without redacting anything itself. The original `Os` failure is preserved as [cause] (not
 * lost) but never read for its message. */
class BrokerSecretPermissionException(cause: Throwable? = null) : Exception("broker_secret_permissions_failed", cause)

/**
 * FIX8 P0-008-A: enforces and verifies owner-only (`0600`) permissions on a broker-secret file,
 * injectable so JVM tests can use a deterministic fake instead of depending on Robolectric's
 * `Os.chmod`/`Os.stat` shadow behavior matching real Linux semantics.
 */
internal interface BrokerSecretPermissionEnforcer {
    /** Sets owner-only permissions on [file] and verifies they took effect. Throws
     * [BrokerSecretPermissionException] on any chmod/stat failure or a verification mismatch —
     * never silently proceeds with wider permissions in effect. */
    fun enforceOwnerOnly(file: File)
}

internal object RealBrokerSecretPermissionEnforcer : BrokerSecretPermissionEnforcer {
    // Octal 0600: owner read+write, no group/other access.
    private const val OWNER_READ_WRITE_MODE = 0x180

    // Mask for the permission-bits portion of st_mode (the low 9 bits: owner/group/other rwx).
    private const val PERMISSION_BITS_MASK = 0x1FF

    override fun enforceOwnerOnly(file: File) {
        try {
            Os.chmod(file.absolutePath, OWNER_READ_WRITE_MODE)
            val actual = Os.stat(file.absolutePath).st_mode and PERMISSION_BITS_MASK
            check(actual == OWNER_READ_WRITE_MODE)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw BrokerSecretPermissionException(error)
        }
    }
}

/**
 * Owns `runtime/mqtt_password.txt`, the one authoritative location for a persisted broker
 * password (FIX7 P0-003-B / INV-006). Config rendering must never write this file as a side
 * effect (CRITICAL-6); only this repository mutates it, under one lock, using a unique
 * same-directory temp file plus atomic replacement and owner-only permissions.
 */
class BrokerSecretRepository internal constructor(
    context: Context,
    // FIX8 P0-008-A: injectable so tests can force a deterministic permission
    // enforcement/verification failure without depending on Robolectric's Os shadow behavior.
    private val permissionEnforcer: BrokerSecretPermissionEnforcer = RealBrokerSecretPermissionEnforcer,
    private val atomicReplace: (File, ByteArray) -> Unit = { file, bytes ->
        atomicReplaceBrokerSecret(file, bytes, permissionEnforcer)
    },
    // Same testability seam as [atomicReplace]: lets a test observe (and later zero-check) the
    // exact byte array a snapshot captured, without a filesystem trick — used to prove a
    // secret-bearing snapshot's bytes are wiped once a transaction finishes (FIX7 P0-004-F).
    private val readBytes: (File) -> ByteArray = File::readBytes,
) {
    private val lock = Any()
    private val passwordFile = File(context.filesDir, "runtime/mqtt_password.txt")

    /** The managed path a renderer should reference when [com.phillipchin.webrtctunnel.model
     * .SetupConfigInput] carries a plaintext password with no explicit "advanced" password-file
     * override. Fixed regardless of whether the file currently exists — callers must persist
     * before rendering a config that references it. */
    val path: String = passwordFile.absolutePath

    fun captureSnapshot(): Result<ExactFileSnapshot> =
        synchronized(lock) {
            try {
                Result.success(
                    if (passwordFile.exists()) {
                        ExactFileSnapshot(existed = true, bytes = readBytes(passwordFile))
                    } else {
                        ExactFileSnapshot(existed = false, bytes = null)
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
        }

    /** Writes [password] as the managed secret, or removes it entirely when null/blank (the
     * "no password file" state). Result must be consumed — a discarded failure here would let a
     * caller believe a stale or empty password file is in effect. */
    @CheckResult
    fun persist(password: String?): Result<Unit> =
        synchronized(lock) {
            try {
                if (password.isNullOrEmpty()) {
                    Files.deleteIfExists(passwordFile.toPath())
                } else {
                    passwordFile.parentFile?.let { Files.createDirectories(it.toPath()) }
                    atomicReplace(passwordFile, password.encodeToByteArray())
                }
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
        }

    // FIX8 P0-008-A: restore also enforces/verifies owner-only permissions — a rolled-back
    // secret must never end up less protected than a freshly persisted one.
    @CheckResult
    fun restore(snapshot: ExactFileSnapshot): Result<Unit> =
        synchronized(lock) {
            try {
                restoreExactFileSnapshot("broker password", passwordFile, snapshot, atomicReplace).getOrThrow()
                if (snapshot.existed) {
                    permissionEnforcer.enforceOwnerOnly(passwordFile)
                }
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
        }
}

/** Same unique-temp-file-plus-move pattern as `IdentityRepository`'s atomic replace, plus
 * owner-only permissions enforced on the temp file before the plaintext secret is written to it
 * (so the secret is never briefly exposed with wider-than-owner permissions, even transiently)
 * and verified again on the destination after the move (FIX8 P0-008-A).
 *
 * FIX7 P1-005-B/A: the temp file's cleanup result is checked, not discarded — a cleanup
 * failure is logged (redacted) and now also surfaces as a failure rather than being
 * swallowed, since a leftover temp file may hold the broker secret in plaintext. A cleanup
 * failure on top of a primary failure is attached as suppressed rather than replacing or
 * discarding it. */
private fun atomicReplaceBrokerSecret(
    destination: File,
    bytes: ByteArray,
    permissionEnforcer: BrokerSecretPermissionEnforcer,
) {
    destination.parentFile?.let { Files.createDirectories(it.toPath()) }
    val temp = Files.createTempFile(destination.parentFile?.toPath(), "${destination.name}.tmp-", ".partial")
    val primaryFailure =
        try {
            permissionEnforcer.enforceOwnerOnly(temp.toFile())
            Files.write(temp, bytes)
            try {
                Files.move(
                    temp,
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (error: AtomicMoveNotSupportedException) {
                // FIX8 P0-008-C: never log the raw Throwable — Log.w's stack-trace formatting
                // could otherwise surface a path or other detail beyond this redacted summary.
                Log.w(
                    BROKER_SECRET_TAG,
                    "Atomic broker secret move unavailable; using fallback replacement (" +
                        "${SensitiveDataRedactor.redactText(error.message ?: "no detail")})",
                )
                Files.move(temp, destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            permissionEnforcer.enforceOwnerOnly(destination)
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
            Log.w(
                BROKER_SECRET_TAG,
                "Broker secret temp cleanup failed: ${
                    SensitiveDataRedactor.redactText(error.message ?: "unknown cleanup failure")
                }",
            )
            error
        }
    throwComposedFailureIfAny(primaryFailure, cleanupFailure)
}
