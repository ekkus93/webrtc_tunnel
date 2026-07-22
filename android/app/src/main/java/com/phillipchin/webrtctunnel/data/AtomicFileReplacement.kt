package com.phillipchin.webrtctunnel.data

import kotlinx.coroutines.CancellationException
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * FIX8 P0-003-C: the one generic same-directory temp-plus-atomic-move byte-replacement
 * primitive for this repository's authoritative files (config.toml, setup_input.json, and —
 * via [postMoveVerify] — broker-secret-style callers that must additionally enforce/verify
 * owner-only permissions after the move; not yet adopted there — see P0-008). Throws on
 * failure so it composes directly with [restoreExactFileSnapshot]'s
 * `atomicReplace: (File, ByteArray) -> Unit` callback shape.
 *
 * Uses [Files.createDirectories] (checked) rather than an ignored `mkdirs()`, writes raw bytes
 * (not a UTF-8 String) so exact snapshots round-trip without corruption, catches every ordinary
 * exception and composes temp cleanup into the thrown failure via [throwComposedFailureIfAny]
 * (never silently dropped), and preserves cancellation.
 */
internal fun atomicReplaceBytes(
    destination: File,
    bytes: ByteArray,
    postMoveVerify: (File) -> Unit = {},
) {
    val parentDir = destination.parentFile ?: error("${destination.name} has no parent directory")
    Files.createDirectories(parentDir.toPath())
    val temp = Files.createTempFile(parentDir.toPath(), ".${destination.name}.tmp-", ".partial")
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
            postMoveVerify(destination)
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
