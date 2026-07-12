package com.phillipchin.webrtctunnel.data

import android.content.Context
import com.phillipchin.webrtctunnel.model.LogEvent
import com.phillipchin.webrtctunnel.model.NetworkPolicyStatus
import com.phillipchin.webrtctunnel.model.TunnelStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class DiagnosticsRepository(
    private val context: Context,
    private val configRepository: ConfigRepository,
) {
    fun buildRedactedDiagnosticsPayload(
        status: TunnelStatus,
        logs: List<LogEvent>,
        networkStatus: NetworkPolicyStatus,
    ): String =
        buildString {
            appendLine("app_version=${context.packageManager.getPackageInfo(context.packageName, 0).versionName}")
            appendLine("rust_library=p2p_mobile")
            appendLine("status_json=${Json.encodeToString(SensitiveDataRedactor.redactStatus(status))}")
            appendLine("network_json=${Json.encodeToString(networkStatus)}")
            appendLine("config_redacted=${SensitiveDataRedactor.redactText(configRepository.readConfig())}")
            appendLine("recent_logs_redacted=${Json.encodeToString(logs.map(SensitiveDataRedactor::redactLogEvent))}")
        }

    fun exportRedactedDiagnostics(
        outputPath: String,
        status: TunnelStatus,
        logs: List<LogEvent>,
        networkStatus: NetworkPolicyStatus,
    ): Result<Unit> =
        runCatching {
            val output = File(outputPath)
            output.parentFile?.mkdirs()
            output.writeText(buildRedactedDiagnosticsPayload(status, logs, networkStatus))
        }
}
