//! Config and identity builders: a fully-populated sample `AppConfig` (and its
//! per-peer/per-forward variants), authorized-keys construction, identity cloning,
//! unique temp paths, and a process-wide unused-port allocator.

use std::path::PathBuf;
use std::sync::atomic::{AtomicU16, AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

use p2p_core::{
    AppConfig, BrokerConfig, BrokerTlsConfig, DEFAULT_ICE_CHECKING_TIMEOUT_MS, ForwardAnswerConfig,
    ForwardOfferConfig, ForwardRule, HealthConfig, LoggingConfig, NodeConfig, NodeRole, PathConfig,
    PeerConfig, ReconnectConfig, SecurityConfig, TunnelConfig, WebRtcConfig,
};
use p2p_crypto::{AuthorizedKeys, GeneratedIdentity, IdentityFile};

// FIX7 P0-010: a process-wide counter guarantees uniqueness on its own, so a clock read
// failure degrades to a 0 timestamp component (harmless here — this path only needs to be
// unique, not a real timestamp) instead of panicking the test harness.
static UNIQUE_PATH_COUNTER: AtomicU64 = AtomicU64::new(0);

pub(crate) fn unique_path(name: &str) -> PathBuf {
    let suffix = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_nanos())
        .unwrap_or(0);
    let counter = UNIQUE_PATH_COUNTER.fetch_add(1, Ordering::Relaxed);
    std::env::temp_dir().join(format!("p2ptunnel-{name}-{suffix}-{counter}"))
}

pub(crate) fn sample_config(
    role: NodeRole,
    status_file: PathBuf,
    listen_port: u16,
    target_port: u16,
) -> AppConfig {
    let peer_id = match role {
        NodeRole::Offer => "offer-home",
        NodeRole::Answer => "answer-office",
    };
    sample_config_for(role, status_file, listen_port, target_port, peer_id, vec!["offer-home"])
}

pub(crate) fn sample_config_for(
    role: NodeRole,
    status_file: PathBuf,
    listen_port: u16,
    target_port: u16,
    peer_id: &str,
    allow_remote_peers: Vec<&str>,
) -> AppConfig {
    let peer_id: p2p_core::PeerId = peer_id.parse().expect("peer id");
    let client_id = peer_id.to_string();

    AppConfig {
        format: "p2ptunnel-config-v3".to_owned(),
        node: NodeConfig { peer_id, role },
        peer: Some(PeerConfig { remote_peer_id: "answer-office".parse().expect("answer peer id") }),
        paths: PathConfig {
            identity: PathBuf::from("/tmp/identity"),
            authorized_keys: PathBuf::from("/tmp/authorized_keys"),
            state_dir: PathBuf::from("/tmp/p2ptunnel-state"),
            log_dir: PathBuf::from("/tmp/p2ptunnel-log"),
        },
        broker: BrokerConfig {
            url: "mqtts://in-memory.invalid:8883".to_owned(),
            client_id,
            topic_prefix: "p2ptunnel-tests".to_owned(),
            username: String::new(),
            password_file: PathBuf::new(),
            qos: 1,
            keepalive_secs: 30,
            clean_session: false,
            connect_timeout_secs: 5,
            session_expiry_secs: 0,
            tls: BrokerTlsConfig {
                ca_file: PathBuf::from("/etc/ssl/certs/ca-certificates.crt"),
                client_cert_file: PathBuf::new(),
                client_key_file: PathBuf::new(),
                insecure_skip_verify: false,
            },
        },
        webrtc: WebRtcConfig {
            stun_urls: Vec::new(),
            enable_trickle_ice: false,
            enable_ice_restart: true,
            android_ice_mode: Default::default(),
            advertised_local_ipv4: None,
            ice_checking_timeout_ms: DEFAULT_ICE_CHECKING_TIMEOUT_MS,
        },
        tunnel: TunnelConfig {
            read_chunk_size: 16_384,
            local_eof_grace_ms: 250,
            remote_eof_grace_ms: 250,
            data_plane_probe_timeout_ms: 5000,
            data_plane_heartbeat_interval_ms: 5000,
            data_plane_heartbeat_max_misses: 3,
        },
        forwards: vec![ForwardRule {
            id: "ssh".to_owned(),
            offer: Some(ForwardOfferConfig { listen_host: "127.0.0.1".to_owned(), listen_port }),
            answer: Some(ForwardAnswerConfig {
                target_host: "127.0.0.1".to_owned(),
                target_port,
                allow_remote_peers: allow_remote_peers
                    .into_iter()
                    .map(|peer_id| peer_id.parse().expect("allowed peer id"))
                    .collect(),
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
            jitter_ratio: 0.20,
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
            replay_cache_size: 10_000,
            reject_unknown_config_keys: true,
            refuse_world_readable_identity: true,
            refuse_world_writable_paths: true,
        },
        logging: LoggingConfig {
            level: "info".to_owned(),
            format: "text".to_owned(),
            file_logging: false,
            stdout_logging: false,
            log_file: PathBuf::from("/tmp/p2ptunnel.log"),
            redact_secrets: true,
            redact_sdp: true,
            redact_candidates: true,
            log_rotation: "none".to_owned(),
        },
        health: HealthConfig {
            status_socket: PathBuf::new(),
            write_status_file: true,
            status_file,
        },
    }
}

pub(crate) fn authorized_keys_for(remote: &GeneratedIdentity) -> AuthorizedKeys {
    AuthorizedKeys::parse(&remote.public_identity.render()).expect("authorized keys should parse")
}

pub(crate) fn authorized_keys_for_many(remotes: &[&GeneratedIdentity]) -> AuthorizedKeys {
    let content = remotes
        .iter()
        .map(|identity| identity.public_identity.render())
        .collect::<Vec<_>>()
        .join("\n");
    AuthorizedKeys::parse(&content).expect("authorized keys should parse")
}

pub(crate) fn unused_local_port() -> u16 {
    static NEXT_TEST_PORT: AtomicU16 = AtomicU16::new(30_000);
    loop {
        let port = NEXT_TEST_PORT.fetch_add(1, Ordering::SeqCst);
        assert!(port < 60_000, "test port range exhausted");
        if std::net::TcpListener::bind(("127.0.0.1", port)).is_ok() {
            return port;
        }
    }
}

pub(crate) fn add_offer_forward(
    config: &mut AppConfig,
    id: &str,
    listen_port: u16,
    target_port: u16,
) {
    config.forwards.push(ForwardRule {
        id: id.to_owned(),
        offer: Some(ForwardOfferConfig { listen_host: "127.0.0.1".to_owned(), listen_port }),
        answer: Some(ForwardAnswerConfig {
            target_host: "127.0.0.1".to_owned(),
            target_port,
            allow_remote_peers: vec![config.node.peer_id.clone()],
        }),
    });
}

pub(crate) fn add_answer_forward(
    config: &mut AppConfig,
    id: &str,
    target_port: u16,
    allow_remote_peer: &str,
) {
    config.forwards.push(ForwardRule {
        id: id.to_owned(),
        offer: Some(ForwardOfferConfig {
            listen_host: "127.0.0.1".to_owned(),
            listen_port: unused_local_port(),
        }),
        answer: Some(ForwardAnswerConfig {
            target_host: "127.0.0.1".to_owned(),
            target_port,
            allow_remote_peers: vec![allow_remote_peer.parse().expect("allowed peer id")],
        }),
    });
}

pub(crate) fn clone_identity(identity: &IdentityFile) -> IdentityFile {
    IdentityFile::from_toml(&identity.render_toml()).expect("identity clone should parse")
}
