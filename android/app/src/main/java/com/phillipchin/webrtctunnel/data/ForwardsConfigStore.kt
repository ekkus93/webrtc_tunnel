package com.phillipchin.webrtctunnel.data

import android.content.Context
import android.util.Log
import com.phillipchin.webrtctunnel.model.ForwardConfig
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
        else -> error.message ?: "Forwards operation failed"
    }

/**
 * Low-level persistence + validation for the configured local forwards. Mutation /
 * in-memory source-of-truth lives in [ForwardsRepository]; this class only loads,
 * saves (atomically), and validates.
 */
class ForwardsConfigStore(private val context: Context) {
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
    fun loadForwardsResult(): Result<List<ForwardConfig>> =
        if (!forwardsFile.exists()) {
            runCatching {
                val defaults = defaultForwards()
                saveForwards(defaults)
                defaults
            }
        } else {
            runCatching { readAndDecodeForwards() }
        }.onFailure { error ->
            when (error) {
                is ForwardsReadException -> Log.w(TAG, "Failed to read forwards.json", error)
                is ForwardsParseException ->
                    Log.w(TAG, "forwards.json is corrupt; keeping existing forwards instead of erasing", error)
                is ForwardsWriteException -> Log.w(TAG, "Failed to seed default forwards.json", error)
                else -> Log.w(TAG, "Unexpected forwards.json failure", error)
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
     */
    fun saveForwards(forwards: List<ForwardConfig>) {
        try {
            val dir = forwardsFile.parentFile
            dir?.mkdirs()
            val temp = File.createTempFile("forwards", ".json.tmp", dir)
            try {
                temp.writeText(Json.encodeToString(forwards))
                try {
                    Files.move(
                        temp.toPath(),
                        forwardsFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temp.toPath(), forwardsFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                temp.delete()
            }
        } catch (error: IOException) {
            throw ForwardsWriteException(error)
        }
    }

    fun validateForwards(forwards: List<ForwardConfig>): String? {
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
