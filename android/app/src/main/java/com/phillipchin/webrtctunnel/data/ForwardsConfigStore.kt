package com.phillipchin.webrtctunnel.data

import android.content.Context
import android.util.Log
import androidx.annotation.CheckResult
import com.phillipchin.webrtctunnel.model.ForwardConfig
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private const val MAX_PORT = 65535
private const val TAG = "ForwardsConfigStore"

/** Distinguishes *why* a forwards-storage operation failed, so callers can react (and word a
 * message) differently for "disk unreadable" vs. "file contents are garbage" vs. "disk
 * unwritable" instead of collapsing all three into one generic "corrupt" message (P1-003). */
sealed class ForwardsConfigException(
    message: String,
    cause: Throwable,
) : Exception(message, cause)

class ForwardsReadException(cause: Throwable) :
    ForwardsConfigException("Unable to read forwards configuration", cause)

class ForwardsParseException(cause: Throwable) :
    ForwardsConfigException("Unable to parse forwards configuration", cause)

class ForwardsWriteException(cause: Throwable) :
    ForwardsConfigException("Unable to write forwards configuration", cause)

/** Shared by [ForwardsConfigStore] and `ForwardsRepository` (same module) to turn a forwards
 * storage failure into a message that names the actual failure instead of always saying
 * "corrupt" — permission-denied and disk-full are not corruption. */
internal fun describeForwardsFailure(error: Throwable): String =
    when (error) {
        is ForwardsReadException -> "Unable to read saved forwards; check storage permissions."
        is ForwardsParseException -> "Saved forwards file is corrupt."
        is ForwardsWriteException -> "Unable to save forwards; check available storage."
        // FIX7 P1-004-C: an unrecognized failure type's raw message is not known-safe —
        // redact before it can reach a durable OperationFailure/snackbar.
        else -> SensitiveDataRedactor.redactText(error.message ?: "Forwards operation failed")
    }

/**
 * Low-level persistence + validation for the configured local forwards.
 * Extracted so the coordinator can be tested with a fake that throws on specific operations.
 */
interface ForwardsStore {
    @CheckResult
    fun loadForwardsResult(): Result<List<ForwardConfig>>

    fun saveForwards(forwards: List<ForwardConfig>)

    fun validateForwards(forwards: List<ForwardConfig>): String?

    /**
     * FIX8 P0-004-A: exact byte-level snapshot of the underlying storage — distinguishes
     * absent, present-and-empty, and present-and-non-empty, so [ForwardsRepository] can restore
     * exactly this state rather than a re-serialized (and therefore only equivalent, not
     * necessarily byte-identical) list.
     */
    @CheckResult
    fun captureExactSnapshot(): Result<ExactFileSnapshot>

    /** Restores the underlying storage to exactly [snapshot]'s prior state. */
    @CheckResult
    fun restoreExactSnapshot(snapshot: ExactFileSnapshot): Result<Unit>
}

/**
 * Concrete [ForwardsStore] implementation that persists forwards to `forwards.json`
 * in the app's files directory.
 */
class ForwardsConfigStore(
    private val context: Context,
    // FIX7 P1-005-A: injectable seams so a test can force the move/cleanup steps to fail
    // deterministically instead of a flaky filesystem permission trick.
    private val moveIntoPlace: (File, File) -> Unit = ::atomicMoveOrReplace,
    private val deleteTempFile: (File) -> Boolean = File::delete,
) : ForwardsStore {
    private val forwardsFile: File get() = File(context.filesDir, "forwards.json")

    // Seeded on a clean install. The remoteForwardId of each entry must match a forward `id`
    // on the answer peer — they are the contract between the two sides. These mirror the
    // repo's canonical example configs (docs/examples/{offer,answer}-config.toml), which both
    // define `ssh` (-> :22) and `web-ui` (-> :8080), so a fresh install interoperates with an
    // answer set up per the docs instead of seeding an id that matches neither.
    private fun defaultForwards(): List<ForwardConfig> =
        listOf(
            ForwardConfig(
                id = "ssh",
                name = "SSH",
                localHost = "127.0.0.1",
                localPort = 2222,
                remoteForwardId = "ssh",
                enabled = true,
            ),
            ForwardConfig(
                id = "web-ui",
                name = "Web UI",
                localHost = "127.0.0.1",
                localPort = 8080,
                remoteForwardId = "web-ui",
                enabled = true,
            ),
        )

    /**
     * Load forwards, distinguishing a corrupt file (failure) from a legitimately
     * empty/missing one (success). On a missing file the defaults are seeded and
     * returned; on corrupt JSON the error is logged and surfaced so callers can keep
     * their existing in-memory list rather than silently erasing it. Read, parse, and
     * write failures are distinguished via [ForwardsConfigException] subtypes rather
     * than collapsed into one "corrupt" outcome (P1-003).
     */
    override fun loadForwardsResult(): Result<List<ForwardConfig>> =
        if (!forwardsFile.exists()) {
            // FIX7 P1-005-B: explicit cancellation-first try/catch, not runCatching — this
            // branch writes forwards.json (seeds defaults), a real mutation.
            try {
                val defaults = defaultForwards()
                saveForwards(defaults)
                Result.success(defaults)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
        } else {
            // FIX8 P1-003-A: explicit catch, not runCatching — readAndDecodeForwards() is a pure
            // synchronous read + JSON decode (already distinguishes its own read/parse exception
            // types internally; no native call, no mutation) and cannot observe a
            // CancellationException, but runCatching would also swallow a fatal Error.
            try {
                Result.success(readAndDecodeForwards())
            } catch (error: Exception) {
                Result.failure(error)
            }
        }.onFailure { error ->
            // FIX8 P1-003-C: never log the raw Throwable — Log.w's stack-trace formatting would
            // print its full cause chain, which for these types wraps a raw IOException/
            // SerializationException whose message may include a file path or content. Each
            // ForwardsConfigException subtype already carries a fixed message; only its safe
            // cause message (redacted) is appended.
            val redactedCause =
                SensitiveDataRedactor.redactText((error as? ForwardsConfigException)?.cause?.message ?: "unknown")
            when (error) {
                is ForwardsReadException -> Log.w(TAG, "Failed to read forwards.json: $redactedCause")
                is ForwardsParseException ->
                    Log.w(
                        TAG,
                        "forwards.json is corrupt; keeping existing forwards instead of erasing: $redactedCause",
                    )
                is ForwardsWriteException -> Log.w(TAG, "Failed to seed default forwards.json: $redactedCause")
                else -> Log.w(TAG, "Unexpected forwards.json failure: $redactedCause")
            }
        }

    private fun readAndDecodeForwards(): List<ForwardConfig> {
        val text =
            try {
                forwardsFile.readText()
            } catch (error: IOException) {
                throw ForwardsReadException(error)
            }
        return try {
            Json.decodeFromString(text)
        } catch (error: SerializationException) {
            throw ForwardsParseException(error)
        }
    }

    /**
     * Atomically replace forwards.json: write a temp file in the same directory and
     * move it into place (atomic when the filesystem supports it, replace otherwise).
     * Never direct-writes the destination, so a crash mid-write cannot truncate it.
     * Any failure here is a [ForwardsWriteException], never a bare I/O exception, so
     * callers can distinguish a write failure from a read/parse one (P1-003).
     *
     * FIX7 P1-005-A: the temp file's cleanup result is checked, not discarded. A cleanup
     * failure after an otherwise-successful save still surfaces as a failure — a leftover
     * temp file is unaccounted-for state, so "saved" must not be reported without a
     * guarantee it was actually cleaned up. If the primary write/move itself failed, that
     * failure is what's thrown; a cleanup failure on top of it is attached as suppressed
     * rather than replacing or discarding it.
     */
    override fun saveForwards(forwards: List<ForwardConfig>) {
        var temp: File? = null
        val primaryFailure =
            try {
                val dir = forwardsFile.parentFile
                // FIX8 P0-008-C: checked, not an ignored mkdirs() Boolean.
                dir?.let { Files.createDirectories(it.toPath()) }
                val created = File.createTempFile("forwards", ".json.tmp", dir)
                temp = created
                created.writeText(Json.encodeToString(forwards))
                moveIntoPlace(created, forwardsFile)
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // FIX8 P0-008-C: catches every ordinary exception (not just IOException) — a
                // SecurityException from a denied file operation must surface as a failure too.
                ForwardsWriteException(error)
            }
        // A successful move already removed temp; only a failed move can leave it behind.
        // Attempting deletion either way is safe — a missing/never-created file is not
        // itself a cleanup failure.
        val cleanupFailed = temp?.let { it.exists() && !deleteTempFile(it) } ?: false
        val cleanupFailure =
            if (cleanupFailed) {
                ForwardsWriteException(IOException("Config saved but failed to remove temporary forwards file"))
            } else {
                null
            }
        throwComposedFailureIfAny(primaryFailure, cleanupFailure)
    }

    /**
     * FIX8 P0-004-A: reuses the same exact-byte-snapshot primitive [ConfigRepository] and
     * setup-input use (P0-003), so `forwards.json` gets the identical absent/empty/exact-bytes
     * distinction rather than a bespoke re-implementation.
     */
    @CheckResult
    override fun captureExactSnapshot(): Result<ExactFileSnapshot> = captureExactFileSnapshot(forwardsFile)

    /**
     * Restores `forwards.json` via the same atomic replace-or-delete primitive
     * [ConfigRepository] uses for config.toml/setup_input.json restores.
     */
    @CheckResult
    override fun restoreExactSnapshot(snapshot: ExactFileSnapshot): Result<Unit> =
        restoreExactFileSnapshot("forwards", forwardsFile, snapshot) { file, bytes ->
            atomicReplaceBytes(file, bytes)
        }

    override fun validateForwards(forwards: List<ForwardConfig>): String? {
        val duplicateId = forwards.groupBy { it.id }.entries.firstOrNull { it.value.size > 1 }?.key
        val enabled = forwards.filter { it.enabled }
        val duplicatePort = enabled.groupBy { it.localPort }.entries.firstOrNull { it.value.size > 1 }?.key
        val duplicateRemoteForwardId =
            enabled
                .groupBy { it.remoteForwardId.trim() }
                .entries
                .firstOrNull { it.key.isNotBlank() && it.value.size > 1 }
                ?.key
        return when {
            duplicateId != null -> "Duplicate forward id: $duplicateId"
            enabled.any { it.name.trim().isBlank() } -> "Forward name is required"
            duplicatePort != null -> "Duplicate local port: $duplicatePort"
            duplicateRemoteForwardId != null -> "Duplicate remote forward ID: $duplicateRemoteForwardId"
            enabled.any { it.remoteForwardId.isBlank() } -> "Remote forward ID is required"
            enabled.any { it.localPort !in 1..MAX_PORT } -> "Port must be between 1 and 65535"
            enabled.any { it.localHost != "127.0.0.1" && it.localHost != "localhost" } ->
                "Non-localhost bind requires advanced warning"
            else -> null
        }
    }
}

// FIX7 P1-005-A: the real move implementation, kept top-level so it lives only inside a
// parameter default (DI) — the seam ForwardsConfigStore's constructor exposes for tests.
private fun atomicMoveOrReplace(
    source: File,
    destination: File,
) {
    try {
        Files.move(
            source.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
