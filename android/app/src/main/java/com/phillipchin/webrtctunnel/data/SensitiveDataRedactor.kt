package com.phillipchin.webrtctunnel.data

import com.phillipchin.webrtctunnel.model.LogEvent
import com.phillipchin.webrtctunnel.model.TunnelError
import com.phillipchin.webrtctunnel.model.TunnelStatus

object SensitiveDataRedactor {
    // Matches a known secret field name followed by its value, whether that value
    // is double-quoted, single-quoted, or a bare unquoted token — a value-only
    // regex like `\S+` stops at the first space, leaving the rest of a quoted
    // multi-word secret (e.g. `password: "alpha secret sentinel"`) unredacted.
    private val secretFieldRegex =
        Regex(
            """(?im)\b(password(?:[_ -][\w-]+)?|token(?:[_ -][\w-]+)?|api[_ -]?key|""" +
                """kex[_ -]?secret|signing[_ -]?key)\b\s*[:=]\s*("[^"]*"|'[^']*'|[^,\s]+)""",
        )

    // P1-009-A: broader structured-field coverage — any key whose name contains a secret word
    // (password/token/api_key/secret/private_key), quoted or bare, in TOML/JSON/kv form. Keeps
    // only the field label so a JSON `"client_secret": "x"` or TOML `identity_private_key = "x"`
    // cannot leak its value.
    private val structuredSecretRegex =
        Regex(
            """(?im)(["']?[A-Za-z0-9_.-]*""" +
                """(?:password|token|api[_-]?key|secret|private[_-]?key)""" +
                """[A-Za-z0-9_.-]*["']?\s*[:=]\s*)("[^"]*"|'[^']*'|[^,\s}\]]+)""",
        )

    fun redactText(input: String): String {
        return input
            .replace(Regex("""(?im)^\s*sign\.private\s*=\s*".*"$"""), "sign.private = \"***REDACTED***\"")
            .replace(Regex("""(?im)^\s*kex\.private\s*=\s*".*"$"""), "kex.private = \"***REDACTED***\"")
            .replace(
                Regex("""(?is)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----"""),
                "***REDACTED_PRIVATE_KEY_BLOCK***",
            )
            .replace(secretFieldRegex) { match -> "${canonicalSecretFieldName(match.groupValues[1])}=***REDACTED***" }
            // P1-009-A: after the canonical-name pass, catch remaining structured secret fields,
            // preserving only the label so no other secret fragment is exposed.
            .replace(structuredSecretRegex) { match -> "${match.groupValues[1]}***REDACTED***" }
            .replace(Regex("""(?i)\bbearer\s+[A-Za-z0-9\-\._~\+/]+=*"""), "Bearer ***REDACTED***")
            .replace(Regex("""(?i)\bBasic\s+[A-Za-z0-9+/=]+"""), "Basic ***REDACTED***")
            .replace(
                Regex("""(?i)\b(mqtts?)://([^:@/\s]+):([^@/\s]+)@"""),
            ) { match -> "${match.groupValues[1]}://***REDACTED***:***REDACTED***@" }
            .replace(Regex("""(?is)\bsdp\s*[:=]\s*.*?(?:\r?\n\r?\n|$)"""), "sdp=***REDACTED***\n")
            .replace(Regex("""(?im)\bcandidate\s*[:=]\s*.*$"""), "candidate=***REDACTED***")
            .replace(Regex("""(?im)\bdecrypted[_\s-]?payload\s*[:=]\s*.*$"""), "decrypted_payload=***REDACTED***")
            .replace(Regex("""(?im)\bforwarded[_\s-]?data\s*[:=]\s*.*$"""), "forwarded_data=***REDACTED***")
            .replace(Regex("""(?im)(/[^/\s]+/)*identity(\.toml|\.enc)?"""), "***REDACTED_IDENTITY_PATH***")
    }

    // The regex's field-name group captures whatever variant actually matched (e.g.
    // "password_file", "api-key", "kex secret"); normalize to a fixed canonical name
    // per family rather than leaking the matched variant into the replacement.
    private fun canonicalSecretFieldName(matchedFieldName: String): String {
        val field = matchedFieldName.lowercase()
        return when {
            field.startsWith("password") -> "password"
            field.startsWith("token") -> "token"
            field.startsWith("api") -> "api_key"
            field.startsWith("kex") -> "kex_secret"
            field.startsWith("signing") -> "signing_key"
            else -> field
        }
    }

    fun redactLogEvent(event: LogEvent): LogEvent = event.copy(message = redactText(event.message))

    fun redactStatus(status: TunnelStatus): TunnelStatus =
        status.copy(
            lastError = status.lastError?.redacted(),
            lastCleanupError = status.lastCleanupError?.redacted(),
        )

    private fun TunnelError.redacted(): TunnelError =
        copy(
            message = redactText(message),
            details = details?.let(::redactText),
        )
}
