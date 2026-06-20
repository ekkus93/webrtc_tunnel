mod forward;
mod paths;
mod validate;

use std::path::PathBuf;

use serde::{Deserialize, Serialize};

pub use forward::{ForwardLookupError, ForwardTable, OfferForwardBind, TargetAddr};
pub use paths::expand_home;

use crate::ids::PeerId;

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum NodeRole {
    Offer,
    Answer,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AppConfig {
    pub format: String,
    pub node: NodeConfig,
    pub peer: Option<PeerConfig>,
    pub paths: PathConfig,
    pub broker: BrokerConfig,
    pub webrtc: WebRtcConfig,
    pub tunnel: TunnelConfig,
    pub forwards: Vec<ForwardRule>,
    pub reconnect: ReconnectConfig,
    pub security: SecurityConfig,
    pub logging: LoggingConfig,
    pub health: HealthConfig,
}

#[derive(Clone, Copy, Debug)]
pub struct ConfigValidationOptions {
    pub require_identity_file: bool,
}

impl ConfigValidationOptions {
    pub const fn standard() -> Self {
        Self { require_identity_file: true }
    }

    pub const fn with_identity_override() -> Self {
        Self { require_identity_file: false }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct NodeConfig {
    pub peer_id: PeerId,
    pub role: NodeRole,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PeerConfig {
    pub remote_peer_id: PeerId,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PathConfig {
    pub identity: PathBuf,
    pub authorized_keys: PathBuf,
    pub state_dir: PathBuf,
    pub log_dir: PathBuf,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct BrokerConfig {
    pub url: String,
    pub client_id: String,
    pub topic_prefix: String,
    pub username: String,
    pub password_file: PathBuf,
    pub qos: u8,
    pub keepalive_secs: u16,
    pub clean_session: bool,
    pub connect_timeout_secs: u16,
    pub session_expiry_secs: u32,
    pub tls: BrokerTlsConfig,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct BrokerTlsConfig {
    #[serde(default)]
    pub ca_file: PathBuf,
    pub client_cert_file: PathBuf,
    pub client_key_file: PathBuf,
    pub insecure_skip_verify: bool,
}

/// ICE candidate-gathering strategy selector.
///
/// Historical name: this controls whether WebRTC uses the native/default
/// `SettingEngine` or the `Net::Ifs` vnet fallback that works around restricted
/// interface enumeration on Android 11+. Despite the name, it is honored on **all**
/// platforms (the vnet fallback is already runtime-selected by interface-enumeration
/// success, not by `#[cfg(target_os = "android")]`) so desktop integration tests can
/// force `native`/`vnet` too.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize, Default)]
#[serde(rename_all = "lowercase")]
pub enum AndroidIceMode {
    /// Use native enumeration if it yields a usable address, else fall back to vnet.
    #[default]
    Auto,
    /// Always use the native/default setting engine; never call `set_vnet`. Fails loudly
    /// (no fallback) if enumeration yields no usable candidate.
    Native,
    /// Always force the `Net::Ifs` vnet fallback; fail loudly if a fallback local IPv4
    /// cannot be constructed. Never silently falls back to native.
    Vnet,
    /// Like `vnet` (inject the local IPv4 as the host-candidate address) but route all ICE
    /// traffic through a single UDP socket bound to `0.0.0.0` (webrtc UDP mux) instead of a
    /// socket bound to the specific interface IP. This advertises the real interface IP as
    /// the host candidate while sending/receiving on an unbound socket, so Android's per-
    /// network routing (`netd`/fwmark) applies normally. Experiment for the Android→remote
    /// data-plane black-hole where a specific-IP-bound socket's egress is dropped. Fails
    /// loudly if a fallback local IPv4 cannot be constructed. (No server-reflexive candidate
    /// is gathered in muxed mode.)
    #[serde(rename = "vnet_mux")]
    VnetMux,
}

pub const fn default_android_ice_mode() -> AndroidIceMode {
    AndroidIceMode::Auto
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct WebRtcConfig {
    pub stun_urls: Vec<String>,
    pub enable_trickle_ice: bool,
    pub enable_ice_restart: bool,
    #[serde(default = "default_android_ice_mode")]
    pub android_ice_mode: AndroidIceMode,
}

/// Lower bound for the post-DCEP data-plane probe timeout (ms). A zero or tiny value
/// would fire a spurious timeout before any round trip could complete.
pub const MIN_DATA_PLANE_PROBE_TIMEOUT_MS: u64 = 100;
/// Default data-plane probe timeout (ms).
pub const DEFAULT_DATA_PLANE_PROBE_TIMEOUT_MS: u64 = 5000;
/// Upper bound for the data-plane probe timeout (ms). Past this a stalled session would
/// wedge a local client for an unreasonable time before failing fast.
pub const MAX_DATA_PLANE_PROBE_TIMEOUT_MS: u64 = 60000;

pub const fn default_data_plane_probe_timeout_ms() -> u64 {
    DEFAULT_DATA_PLANE_PROBE_TIMEOUT_MS
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct TunnelConfig {
    pub read_chunk_size: usize,
    pub local_eof_grace_ms: u64,
    pub remote_eof_grace_ms: u64,
    #[serde(default = "default_data_plane_probe_timeout_ms")]
    pub data_plane_probe_timeout_ms: u64,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ForwardRule {
    pub id: String,
    pub offer: Option<ForwardOfferConfig>,
    pub answer: Option<ForwardAnswerConfig>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ForwardOfferConfig {
    pub listen_host: String,
    pub listen_port: u16,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ForwardAnswerConfig {
    pub target_host: String,
    pub target_port: u16,
    pub allow_remote_peers: Vec<PeerId>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ReconnectConfig {
    pub enable_auto_reconnect: bool,
    pub strategy: String,
    pub ice_restart_timeout_secs: u16,
    pub renegotiate_timeout_secs: u16,
    pub backoff_initial_ms: u64,
    pub backoff_max_ms: u64,
    pub backoff_multiplier: f64,
    pub jitter_ratio: f64,
    pub max_attempts: u32,
    pub hold_local_client_during_reconnect: bool,
    pub local_client_hold_secs: u16,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SecurityConfig {
    pub require_mqtt_tls: bool,
    pub require_message_encryption: bool,
    pub require_message_signatures: bool,
    pub require_authorized_keys: bool,
    pub max_clock_skew_secs: u64,
    pub max_message_age_secs: u64,
    pub replay_cache_size: usize,
    pub reject_unknown_config_keys: bool,
    pub refuse_world_readable_identity: bool,
    pub refuse_world_writable_paths: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LoggingConfig {
    pub level: String,
    pub format: String,
    pub file_logging: bool,
    pub stdout_logging: bool,
    pub log_file: PathBuf,
    pub redact_secrets: bool,
    pub redact_sdp: bool,
    pub redact_candidates: bool,
    pub log_rotation: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct HealthConfig {
    pub status_socket: PathBuf,
    pub write_status_file: bool,
    pub status_file: PathBuf,
}

#[cfg(test)]
mod tests;
