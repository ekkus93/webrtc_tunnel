package com.phillipchin.webrtctunnel.data

import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.SetupConfigInput
import java.io.File

/** Valid `android_ice_mode` values; anything else falls back to [DEFAULT_ANDROID_ICE_MODE]. */
internal val VALID_ANDROID_ICE_MODES = setOf("auto", "native", "vnet", "vnet_mux")

/**
 * Default ICE mode for Android-generated configs: the strict, proven `vnet_mux` path (UDP
 * mux + advertise the injected local IPv4). `auto` is best-effort/diagnostic only and may
 * select the black-holing native path on Android, so it is never the default — set it
 * explicitly via the `debug.p2p.android_ice_mode` override if needed.
 */
internal const val DEFAULT_ANDROID_ICE_MODE = "vnet_mux"

/** Render-time options for the config templates. */
internal data class ConfigRenderOptions(
    val debugLogs: Boolean = false,
    val androidIceMode: String = DEFAULT_ANDROID_ICE_MODE,
)

/**
 * Normalize a raw (possibly-null, possibly-untrusted debug) `android_ice_mode` value to one
 * of [VALID_ANDROID_ICE_MODES], defaulting to [DEFAULT_ANDROID_ICE_MODE]. Pure and
 * test-only-aware: invalid input never produces an invalid config.
 */
internal fun normalizeAndroidIceMode(raw: String?): String {
    val trimmed = raw?.trim()?.lowercase().orEmpty()
    return if (trimmed in VALID_ANDROID_ICE_MODES) trimmed else DEFAULT_ANDROID_ICE_MODE
}

// Config sections that are identical across the default and offer templates (apart from the
// injected `android_ice_mode`). Shared so both renderers stay short and in sync.
private fun tlsWebrtcTunnelSections(androidIceMode: String): String =
    """
    [broker.tls]
    client_cert_file = ""
    client_key_file = ""
    insecure_skip_verify = false

    [webrtc]
    stun_urls = ["stun:stun.l.google.com:19302"]
    enable_trickle_ice = true
    enable_ice_restart = true
    android_ice_mode = "$androidIceMode"

    [tunnel]
    read_chunk_size = 16384
    local_eof_grace_ms = 250
    remote_eof_grace_ms = 250
    data_plane_probe_timeout_ms = 5000
    data_plane_heartbeat_interval_ms = 5000
    data_plane_heartbeat_max_misses = 3
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

/**
 * Insert or replace the `advertised_local_ipv4` line in the `[webrtc]` section of a config
 * TOML. Passing `null` removes any existing line, so a strict `vnet_mux` start with no
 * available address fails loudly in native rather than advertising a stale address. Pure and
 * test-friendly: anchors on the `android_ice_mode` line (always emitted by the templates),
 * falling back to the `[webrtc]` header, and preserves that anchor's indentation. Returns the
 * config unchanged if no anchor is found.
 */
internal fun upsertAdvertisedLocalIpv4(
    configToml: String,
    address: String?,
): String {
    val lines = configToml.lines().toMutableList()
    lines.removeAll { it.trimStart().startsWith("advertised_local_ipv4") }
    if (address != null) {
        val anchor =
            lines.indexOfFirst { it.trimStart().startsWith("android_ice_mode") }
                .takeIf { it >= 0 }
                ?: lines.indexOfFirst { it.trimStart() == "[webrtc]" }
        if (anchor >= 0) {
            val indent = lines[anchor].takeWhile { it == ' ' || it == '\t' }
            lines.add(anchor + 1, "${indent}advertised_local_ipv4 = ${tomlString(address)}")
        }
    }
    return lines.joinToString("\n")
}

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

private fun loggingHealthSections(
    filesDir: File,
    debugLogs: Boolean,
): String =
    """
    [logging]
    level = ${if (debugLogs) "\"debug\"" else "\"info\""}
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

internal fun buildDefaultConfigTemplate(
    filesDir: File,
    options: ConfigRenderOptions = ConfigRenderOptions(),
): String =
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
        tlsWebrtcTunnelSections(options.androidIceMode),
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
        loggingHealthSections(filesDir, options.debugLogs),
    ).joinToString("\n\n")

internal fun buildOfferConfig(
    input: SetupConfigInput,
    forwards: List<ForwardConfig>,
    filesDir: File,
    passwordFile: String,
    options: ConfigRenderOptions = ConfigRenderOptions(),
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
        tlsWebrtcTunnelSections(options.androidIceMode),
        listOf(forwardsToml, peerSection).joinToString("\n\n"),
        STATIC_RECONNECT_SECURITY_SECTIONS,
        loggingHealthSections(filesDir, options.debugLogs),
    ).joinToString("\n\n")
}
