package com.phillipchin.webrtctunnel.data

import android.content.Context
import android.util.Log
import androidx.annotation.CheckResult
import kotlinx.coroutines.CancellationException
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private const val BROKER_SECRET_TAG = "BrokerSecretRepository"

/**
 * Owns `runtime/mqtt_password.txt`, the one authoritative location for a persisted broker
 * password (FIX7 P0-003-B / INV-006). Config rendering must never write this file as a side
 * effect (CRITICAL-6); only this repository mutates it, under one lock, using a unique
 * same-directory temp file plus atomic replacement and owner-only permissions.
 */
class BrokerSecretRepository(
    context: Context,
    private val atomicReplace: (File, ByteArray) -> Unit = ::atomicReplaceBrokerSecret,
) {
    private val lock = Any()
    private val passwordFile = File(context.filesDir, "runtime/mqtt_password.txt")

    /** The managed path a renderer should reference when [com.phillipchin.webrtctunnel.model
     * .SetupConfigInput] carries a plaintext password with no explicit "advanced" password-file
     * override. Fixed regardless of whether the file currently exists — callers must persist
     * before rendering a config that references it. */
    val path: String = passwordFile.absolutePath

    fun captureSnapshot(): Result<ExactFileSnapshot> = synchronized(lock) { captureExactFileSnapshot(passwordFile) }

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
                    passwordFile.parentFile?.mkdirs()
                    atomicReplace(passwordFile, password.encodeToByteArray())
                }
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
        }

    @CheckResult
    fun restore(snapshot: ExactFileSnapshot): Result<Unit> =
        synchronized(lock) {
            restoreExactFileSnapshot("broker password", passwordFile, snapshot, atomicReplace)
        }
}

private fun restrictToOwnerOnly(file: File) {
    file.setReadable(false, false)
    file.setReadable(true, true)
    file.setWritable(false, false)
    file.setWritable(true, true)
}

/** Same unique-temp-file-plus-move pattern as `IdentityRepository`'s atomic replace, plus
 * owner-only permissions once the secret is in place. Temp cleanup failure is logged (redacted)
 * and swallowed rather than masking the primary write outcome. */
private fun atomicReplaceBrokerSecret(
    destination: File,
    bytes: ByteArray,
) {
    destination.parentFile?.mkdirs()
    val temp = Files.createTempFile(destination.parentFile?.toPath(), "${destination.name}.tmp-", ".partial")
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
            Log.w(BROKER_SECRET_TAG, "Atomic broker secret move unavailable; using replacement", error)
            Files.move(temp, destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        restrictToOwnerOnly(destination)
    } finally {
        runCatching { Files.deleteIfExists(temp) }
            .onFailure { cleanup ->
                Log.w(
                    BROKER_SECRET_TAG,
                    "Broker secret temp cleanup failed: ${
                        SensitiveDataRedactor.redactText(cleanup.message ?: "unknown cleanup failure")
                    }",
                )
            }
    }
}
