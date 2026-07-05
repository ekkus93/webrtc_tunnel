use super::support::*;

fn sample_config(base: &std::path::Path) -> AppConfig {
    AppConfig {
        format: "p2ptunnel-config-v3".to_owned(),
        node: NodeConfig {
            peer_id: "answer-office".parse().expect("peer id"),
            role: NodeRole::Answer,
        },
        peer: None,
        paths: p2p_core::PathConfig {
            identity: base.join("identity"),
            authorized_keys: base.join("authorized_keys"),
            state_dir: base.join("state"),
            log_dir: base.join("state/log"),
        },
        broker: BrokerConfig {
            url: "mqtts://broker.example:8883".to_owned(),
            client_id: "answer-office".to_owned(),
            topic_prefix: "p2ptunnel".to_owned(),
            username: "answer-office".to_owned(),
            password_file: base.join("password"),
            qos: 1,
            keepalive_secs: 30,
            clean_session: true,
            connect_timeout_secs: 5,
            session_expiry_secs: 0,
            tls: BrokerTlsConfig {
                ca_file: base.join("ca.pem"),
                client_cert_file: PathBuf::new(),
                client_key_file: PathBuf::new(),
                insecure_skip_verify: false,
            },
        },
        webrtc: WebRtcConfig {
            stun_urls: vec!["stun:stun.l.google.com:19302".to_owned()],
            enable_trickle_ice: true,
            enable_ice_restart: true,
            android_ice_mode: Default::default(),
            advertised_local_ipv4: None,
            ice_checking_timeout_ms: p2p_core::DEFAULT_ICE_CHECKING_TIMEOUT_MS,
        },
        tunnel: TunnelConfig {
            read_chunk_size: 1024,
            local_eof_grace_ms: 250,
            remote_eof_grace_ms: 250,
            data_plane_probe_timeout_ms: 5000,
            data_plane_heartbeat_interval_ms: 5000,
            data_plane_heartbeat_max_misses: 3,
        },
        forwards: vec![ForwardRule {
            id: "ssh".to_owned(),
            offer: None,
            answer: Some(ForwardAnswerConfig {
                target_host: "127.0.0.1".to_owned(),
                target_port: 22,
                allow_remote_peers: vec!["offer-home".parse().expect("peer id")],
            }),
        }],
        reconnect: ReconnectConfig {
            enable_auto_reconnect: true,
            strategy: "ice_then_renegotiate".to_owned(),
            ice_restart_timeout_secs: 8,
            renegotiate_timeout_secs: 20,
            backoff_initial_ms: 1000,
            backoff_max_ms: 30_000,
            backoff_multiplier: 2.0,
            jitter_ratio: 0.2,
            max_attempts: 0,
            hold_local_client_during_reconnect: false,
            local_client_hold_secs: 0,
        },
        security: SecurityConfig {
            require_mqtt_tls: true,
            require_message_encryption: true,
            require_message_signatures: true,
            require_authorized_keys: true,
            max_clock_skew_secs: 120,
            max_message_age_secs: 300,
            replay_cache_size: 64,
            reject_unknown_config_keys: true,
            refuse_world_readable_identity: true,
            refuse_world_writable_paths: true,
        },
        logging: LoggingConfig {
            level: "info".to_owned(),
            format: "text".to_owned(),
            file_logging: false,
            stdout_logging: true,
            log_file: base.join("state/p2ptunnel.log"),
            redact_secrets: true,
            redact_sdp: true,
            redact_candidates: true,
            log_rotation: "none".to_owned(),
        },
        health: HealthConfig {
            status_socket: PathBuf::new(),
            write_status_file: true,
            status_file: base.join("state/status.json"),
        },
    }
}

#[test]
fn build_mqtt_options_uses_custom_tls_transport() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    std::fs::write(temp_dir.path().join("password"), "secret\n").expect("password");
    std::fs::write(
        temp_dir.path().join("ca.pem"),
        "-----BEGIN CERTIFICATE-----\nZm9v\n-----END CERTIFICATE-----\n",
    )
    .expect("ca");
    let config = sample_config(temp_dir.path());

    let (options, _qos, _topic) = build_mqtt_options(&config).expect("options build");
    assert!(matches!(options.transport(), Transport::Tls(_)));
}

#[test]
fn build_mqtt_options_supports_anonymous_broker_auth() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    std::fs::write(
        temp_dir.path().join("ca.pem"),
        "-----BEGIN CERTIFICATE-----\nZm9v\n-----END CERTIFICATE-----\n",
    )
    .expect("ca");
    let mut config = sample_config(temp_dir.path());
    config.broker.username.clear();
    config.broker.password_file = PathBuf::new();

    let (options, _qos, _topic) = build_mqtt_options(&config).expect("options build");
    assert!(options.credentials().is_none());
}

#[test]
fn build_mqtt_options_supports_username_only_auth() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    std::fs::write(
        temp_dir.path().join("ca.pem"),
        "-----BEGIN CERTIFICATE-----\nZm9v\n-----END CERTIFICATE-----\n",
    )
    .expect("ca");
    let mut config = sample_config(temp_dir.path());
    config.broker.password_file = PathBuf::new();

    let (options, _qos, _topic) = build_mqtt_options(&config).expect("options build");
    let credentials = options.credentials().expect("credentials");
    assert_eq!(credentials.username, "answer-office");
    assert!(credentials.password.is_empty());
}

#[test]
fn build_mqtt_options_supports_default_tls_roots_when_ca_file_is_empty() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    std::fs::write(temp_dir.path().join("password"), "secret\n").expect("password");
    let mut config = sample_config(temp_dir.path());
    config.broker.tls.ca_file = PathBuf::new();

    let (options, _qos, _topic) = build_mqtt_options(&config).expect("options build");
    assert!(matches!(options.transport(), Transport::Tls(_)));
}

#[test]
fn default_roots_tls_config_trusts_nonempty_webpki_root_set() {
    // Guards against shipping an empty trust store (the Android UnknownIssuer
    // bug): the compiled-in Mozilla root set must be present.
    assert!(!webpki_roots::TLS_SERVER_ROOTS.is_empty());
    // Building the config must not panic (resolves a crypto provider).
    let _config = default_roots_tls_config();
}

#[test]
fn build_mqtt_options_rejects_client_cert_without_ca_when_using_default_roots() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    std::fs::write(temp_dir.path().join("password"), "secret\n").expect("password");
    std::fs::write(temp_dir.path().join("client.crt"), "client cert").expect("client cert");
    std::fs::write(temp_dir.path().join("client.key"), "client key").expect("client key");
    let mut config = sample_config(temp_dir.path());
    config.broker.tls.ca_file = PathBuf::new();
    config.broker.tls.client_cert_file = temp_dir.path().join("client.crt");
    config.broker.tls.client_key_file = temp_dir.path().join("client.key");

    assert!(matches!(
        build_mqtt_options(&config),
        Err(SignalingError::Protocol(message))
            if message.contains("requires broker.tls.ca_file")
    ));
}

#[test]
fn build_mqtt_options_rejects_password_without_username() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    std::fs::write(temp_dir.path().join("password"), "secret\n").expect("password");
    std::fs::write(
        temp_dir.path().join("ca.pem"),
        "-----BEGIN CERTIFICATE-----\nZm9v\n-----END CERTIFICATE-----\n",
    )
    .expect("ca");
    let mut config = sample_config(temp_dir.path());
    config.broker.username.clear();

    assert!(matches!(
        build_mqtt_options(&config),
        Err(SignalingError::Protocol(message))
            if message.contains("password_file requires broker.username")
    ));
}

#[test]
fn build_mqtt_options_rejects_unsupported_connect_timeout() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    std::fs::write(temp_dir.path().join("password"), "secret\n").expect("password");
    std::fs::write(
        temp_dir.path().join("ca.pem"),
        "-----BEGIN CERTIFICATE-----\nZm9v\n-----END CERTIFICATE-----\n",
    )
    .expect("ca");
    let mut config = sample_config(temp_dir.path());
    config.broker.connect_timeout_secs = 10;

    assert!(matches!(
        build_mqtt_options(&config),
        Err(SignalingError::Protocol(message))
            if message.contains("connect_timeout_secs")
    ));
}

#[test]
fn build_mqtt_options_rejects_unsupported_session_expiry() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    std::fs::write(temp_dir.path().join("password"), "secret\n").expect("password");
    std::fs::write(
        temp_dir.path().join("ca.pem"),
        "-----BEGIN CERTIFICATE-----\nZm9v\n-----END CERTIFICATE-----\n",
    )
    .expect("ca");
    let mut config = sample_config(temp_dir.path());
    config.broker.session_expiry_secs = 30;

    assert!(matches!(
        build_mqtt_options(&config),
        Err(SignalingError::Protocol(message))
            if message.contains("session_expiry_secs")
    ));
}

#[test]
fn build_mqtt_options_rejects_invalid_qos() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    std::fs::write(temp_dir.path().join("password"), "secret\n").expect("password");
    std::fs::write(
        temp_dir.path().join("ca.pem"),
        "-----BEGIN CERTIFICATE-----\nZm9v\n-----END CERTIFICATE-----\n",
    )
    .expect("ca");
    let mut config = sample_config(temp_dir.path());
    config.broker.qos = 3;

    assert!(matches!(
        build_mqtt_options(&config),
        Err(SignalingError::Protocol(message))
            if message.contains("unsupported MQTT QoS 3")
    ));
}

#[test]
fn build_mqtt_options_rejects_disabling_require_mqtt_tls() {
    // Not a "falls back to a plain transport" case: v1 hard-requires TLS, so
    // disabling it at the security-config level is rejected outright by this layer
    // too (defense in depth alongside the config-level check).
    let temp_dir = tempfile::tempdir().expect("temp dir");
    std::fs::write(temp_dir.path().join("password"), "secret\n").expect("password");
    std::fs::write(
        temp_dir.path().join("ca.pem"),
        "-----BEGIN CERTIFICATE-----\nZm9v\n-----END CERTIFICATE-----\n",
    )
    .expect("ca");
    let mut config = sample_config(temp_dir.path());
    config.security.require_mqtt_tls = false;

    assert!(matches!(
        build_mqtt_options(&config),
        Err(SignalingError::Protocol(message))
            if message.contains("require_mqtt_tls must remain enabled")
    ));
}

#[test]
fn build_mqtt_options_rejects_non_mqtts_scheme() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    std::fs::write(temp_dir.path().join("password"), "secret\n").expect("password");
    std::fs::write(
        temp_dir.path().join("ca.pem"),
        "-----BEGIN CERTIFICATE-----\nZm9v\n-----END CERTIFICATE-----\n",
    )
    .expect("ca");
    let mut config = sample_config(temp_dir.path());
    config.broker.url = "mqtt://broker.example:1883".to_owned();

    assert!(matches!(
        build_mqtt_options(&config),
        Err(SignalingError::Protocol(message))
            if message.contains("must use mqtts://")
    ));
}

#[test]
fn build_mqtt_options_rejects_insecure_skip_verify() {
    // Defense in depth: this layer independently rejects insecure_skip_verify even
    // though config-level validation also enforces it.
    let temp_dir = tempfile::tempdir().expect("temp dir");
    std::fs::write(temp_dir.path().join("password"), "secret\n").expect("password");
    std::fs::write(
        temp_dir.path().join("ca.pem"),
        "-----BEGIN CERTIFICATE-----\nZm9v\n-----END CERTIFICATE-----\n",
    )
    .expect("ca");
    let mut config = sample_config(temp_dir.path());
    config.broker.tls.insecure_skip_verify = true;

    assert!(matches!(
        build_mqtt_options(&config),
        Err(SignalingError::Protocol(message))
            if message.contains("insecure_skip_verify is unsupported in v1")
    ));
}

#[test]
fn build_mqtt_options_missing_password_file_names_path() {
    let temp_dir = tempfile::tempdir().expect("temp dir");
    std::fs::write(
        temp_dir.path().join("ca.pem"),
        "-----BEGIN CERTIFICATE-----\nZm9v\n-----END CERTIFICATE-----\n",
    )
    .expect("ca");
    let mut config = sample_config(temp_dir.path());
    let missing_password = temp_dir.path().join("missing-password");
    config.broker.password_file = missing_password.clone();

    let error = build_mqtt_options(&config).expect_err("missing password file should fail");

    assert!(error.to_string().contains(missing_password.to_string_lossy().as_ref()));
}
