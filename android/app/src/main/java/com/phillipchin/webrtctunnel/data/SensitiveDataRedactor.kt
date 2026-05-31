package com.phillipchin.webrtctunnel.data

import com.phillipchin.webrtctunnel.model.LogEvent
import com.phillipchin.webrtctunnel.model.TunnelError
import com.phillipchin.webrtctunnel.model.TunnelStatus

object SensitiveDataRedactor {
    fun redactText(input: String): String {
        return input
            .replace(Regex("""(?im)^\s*sign\.private\s*=\s*".*"$"""), "sign.private = \"***REDACTED***\"")
            .replace(Regex("""(?im)^\s*kex\.private\s*=\s*".*"$"""), "kex.private = \"***REDACTED***\"")
            .replace(Regex("""(?is)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----"""), "***REDACTED_PRIVATE_KEY_BLOCK***")
            .replace(Regex("""(?i)\bpassword[^,\s]*\s*=\s*\S+"""), "password=***REDACTED***")
            .replace(Regex("""(?i)\btoken[^,\s]*\s*=\s*\S+"""), "token=***REDACTED***")
            .replace(Regex("""(?i)\bbearer\s+[A-Za-z0-9\-\._~\+/]+=*"""), "Bearer ***REDACTED***")
            .replace(Regex("""(?i)\bapi[_-]?key[^,\s]*\s*=\s*\S+"""), "api_key=***REDACTED***")
            .replace(Regex("""(?i)\bmqtts?://([^:@/\s]+):([^@/\s]+)@"""), "mqtts://***REDACTED***:***REDACTED***@")
            .replace(Regex("""(?is)\bsdp\s*[:=]\s*.*?(?:\n\n|$)"""), "sdp=***REDACTED***\n")
            .replace(Regex("""(?im)\bcandidate\s*[:=]\s*.*$"""), "candidate=***REDACTED***")
            .replace(Regex("""(?im)\bdecrypted[_\s-]?payload\s*[:=]\s*.*$"""), "decrypted_payload=***REDACTED***")
            .replace(Regex("""(?im)\bforwarded[_\s-]?data\s*[:=]\s*.*$"""), "forwarded_data=***REDACTED***")
            .replace(Regex("""(?im)\bkex_secret\s*=\s*.*$"""), "kex_secret=***REDACTED***")
            .replace(Regex("""(?im)\bsigning_key\s*=\s*.*$"""), "signing_key=***REDACTED***")
            .replace(Regex("""(?im)(/[^/\s]+/)*identity(\.toml|\.enc)?"""), "***REDACTED_IDENTITY_PATH***")
    }

    fun redactLogEvent(event: LogEvent): LogEvent = event.copy(message = redactText(event.message))

    fun redactStatus(status: TunnelStatus): TunnelStatus = status.copy(
        lastError = status.lastError?.redacted(),
    )

    private fun TunnelError.redacted(): TunnelError = copy(
        message = redactText(message),
        details = details?.let(::redactText),
    )
}
