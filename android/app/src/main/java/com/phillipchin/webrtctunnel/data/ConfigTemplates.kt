package com.phillipchin.webrtctunnel.data

import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.SetupConfigInput
import java.io.File

// Config sections that are byte-identical across the default and offer templates.
// Kept as top-level constants (verbatim) so both renderers share them and stay short.
private val STATIC_TLS_WEBRTC_TUNNEL_SECTIONS =
    """
    [broker.tls]
    client_cert_file = ""
    client_key_file = ""
    insecure_skip_verify = false

    [webrtc]
    stun_urls = ["stun:stun.l.google.com:19302"]
    enable_trickle_ice = true
    enable_ice_restart = true

    [tunnel]
    read_chunk_size = 16384
    local_eof_grace_ms = 250
    remote_eof_grace_ms = 250
    """.trimIndent()

private val STATIC_RECONNECT_SECURITY_SECTIONS =
    """
    [reconnect]
    enable_auto_reconnect = true
    strategy = "ice_then_renegotiate"
    ice_restart_timeout_secs = 8
    renegotiate_timeout_secs = 20
    backoff_initial_ms = 1000
    backoff_max_ms = 30000
    backoff_multiplier = 2.0
    jitter_ratio = 0.20
    max_attempts = 0
    hold_local_client_during_reconnect = false
    local_client_hold_secs = 0

    [security]
    require_mqtt_tls = true
    require_message_encryption = true
    require_message_signatures = true
    require_authorized_keys = true
    max_clock_skew_secs = 120
    max_message_age_secs = 300
    replay_cache_size = 10000
    reject_unknown_config_keys = true
    refuse_world_readable_identity = true
    refuse_world_writable_paths = true
    """.trimIndent()

internal fun tomlString(value: String): String =
    buildString(value.length + 2) {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }

private fun pathsSection(filesDir: File): String =
    """
    [paths]
    identity = ${tomlString(File(filesDir, "runtime/identity.toml").absolutePath)}
    authorized_keys = ${tomlString(File(filesDir, "authorized_keys").absolutePath)}
    state_dir = ${tomlString(File(filesDir, "state").absolutePath)}
    log_dir = ${tomlString(File(filesDir, "state/log").absolutePath)}
    """.trimIndent()

private fun loggingHealthSections(filesDir: File): String =
    """
    [logging]
    level = "info"
    format = "text"
    file_logging = true
    stdout_logging = true
    log_file = ${tomlString(File(filesDir, "state/log/p2ptunnel.log").absolutePath)}
    redact_secrets = true
    redact_sdp = true
    redact_candidates = true
    log_rotation = "none"

    [health]
    status_socket = ""
    write_status_file = true
    status_file = ${tomlString(File(filesDir, "state/status.json").absolutePath)}
    """.trimIndent()

internal fun buildDefaultConfigTemplate(filesDir: File): String =
    listOf(
        """
        # Generated for Android app-private storage.
        format = "p2ptunnel-config-v3"

        [node]
        peer_id = "android-phone"
        role = "offer"
        """.trimIndent(),
        pathsSection(filesDir),
        """
        [broker]
        url = "mqtts://broker.example.com:8883"
        client_id = "android-phone"
        topic_prefix = "p2ptunnel"
        username = ""
        password_file = ""
        qos = 1
        keepalive_secs = 30
        clean_session = false
        connect_timeout_secs = 5
        session_expiry_secs = 0
        """.trimIndent(),
        STATIC_TLS_WEBRTC_TUNNEL_SECTIONS,
        """
        [[forwards]]
        id = "llama"

        [forwards.offer]
        listen_host = "127.0.0.1"
        listen_port = 8080

        [peer]
        remote_peer_id = "home-server"
        """.trimIndent(),
        STATIC_RECONNECT_SECURITY_SECTIONS,
        loggingHealthSections(filesDir),
    ).joinToString("\n\n")

internal fun buildOfferConfig(
    input: SetupConfigInput,
    forwards: List<ForwardConfig>,
    filesDir: File,
    passwordFile: String,
): String {
    val scheme = if (input.brokerUseTls) "mqtts" else "mqtt"
    val forwardsToml =
        forwards.joinToString(separator = "\n\n") { forward ->
            """
            [[forwards]]
            id = ${tomlString(forward.remoteForwardId)}

            [forwards.offer]
            listen_host = ${tomlString(forward.localHost)}
            listen_port = ${forward.localPort}
            """.trimIndent()
        }
    val peerSection =
        """
        [peer]
        remote_peer_id = ${tomlString(input.remotePeerId)}
        """.trimIndent()
    return listOf(
        """
        format = "p2ptunnel-config-v3"

        [node]
        peer_id = ${tomlString(input.localPeerId)}
        role = "offer"
        """.trimIndent(),
        pathsSection(filesDir),
        """
        [broker]
        url = ${tomlString("$scheme://${input.brokerHost}:${input.brokerPort}")}
        client_id = ${tomlString(input.localPeerId)}
        topic_prefix = ${tomlString(input.topicPrefix)}
        username = ${tomlString(input.brokerUsername)}
        password_file = ${tomlString(passwordFile)}
        qos = 1
        keepalive_secs = 30
        clean_session = false
        connect_timeout_secs = 5
        session_expiry_secs = 0
        """.trimIndent(),
        STATIC_TLS_WEBRTC_TUNNEL_SECTIONS,
        listOf(forwardsToml, peerSection).joinToString("\n\n"),
        STATIC_RECONNECT_SECURITY_SECTIONS,
        loggingHealthSections(filesDir),
    ).joinToString("\n\n")
}
