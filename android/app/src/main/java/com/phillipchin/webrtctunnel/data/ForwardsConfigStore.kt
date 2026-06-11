package com.phillipchin.webrtctunnel.data

import android.content.Context
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.ValidationResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

private const val MAX_PORT = 65535

/** Persistence + validation for the configured local forwards (split from ConfigRepository). */
class ForwardsConfigStore(private val context: Context) {
    private val forwardsFile: File get() = File(context.filesDir, "forwards.json")

    fun loadForwards(): List<ForwardConfig> {
        if (!forwardsFile.exists()) {
            val defaults =
                listOf(
                    ForwardConfig(
                        id = "llama",
                        name = "Llama server",
                        localHost = "127.0.0.1",
                        localPort = 8080,
                        remoteForwardId = "llama",
                        enabled = true,
                    ),
                )
            saveForwards(defaults)
            return defaults
        }
        return runCatching {
            Json.decodeFromString<List<ForwardConfig>>(forwardsFile.readText())
        }.getOrElse { emptyList() }
    }

    fun saveForwards(forwards: List<ForwardConfig>) {
        forwardsFile.parentFile?.mkdirs()
        forwardsFile.writeText(Json.encodeToString(forwards))
    }

    fun upsertForward(forward: ForwardConfig): ValidationResult {
        val updated =
            loadForwards().toMutableList().apply {
                val index = indexOfFirst { it.id == forward.id }
                if (index >= 0) {
                    set(index, forward)
                } else {
                    add(forward)
                }
            }
        val error = validateForwards(updated)
        if (error != null) {
            return ValidationResult(false, error)
        }
        saveForwards(updated)
        return ValidationResult(true, null)
    }

    fun deleteForward(forwardId: String) {
        saveForwards(loadForwards().filterNot { it.id == forwardId })
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
