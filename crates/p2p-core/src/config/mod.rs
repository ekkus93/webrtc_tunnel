use std::collections::HashSet;
use std::env;
use std::fs;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::error::ConfigError;
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

impl AppConfig {
    pub fn load_from_file(path: &Path) -> Result<Self, ConfigError> {
        Self::load_from_file_with_options(path, ConfigValidationOptions::standard())
    }

    pub fn load_from_file_with_identity_override(path: &Path) -> Result<Self, ConfigError> {
        Self::load_from_file_with_options(path, ConfigValidationOptions::with_identity_override())
    }

    fn load_from_file_with_options(
        path: &Path,
        options: ConfigValidationOptions,
    ) -> Result<Self, ConfigError> {
        let content =
            fs::read_to_string(path).map_err(|error| ConfigError::io_path(path, error))?;
        let mut config: Self = toml::from_str(&content)?;
        config.expand_paths()?;
        config.validate_with_options(options)?;
        Ok(config)
    }

    pub fn expand_paths(&mut self) -> Result<(), ConfigError> {
        self.paths.identity = expand_home(&self.paths.identity)?;
        self.paths.authorized_keys = expand_home(&self.paths.authorized_keys)?;
        self.paths.state_dir = expand_home(&self.paths.state_dir)?;
        self.paths.log_dir = expand_home(&self.paths.log_dir)?;
        self.broker.password_file = expand_home(&self.broker.password_file)?;
        self.broker.tls.ca_file = expand_optional_path(&self.broker.tls.ca_file)?;
        self.broker.tls.client_cert_file = expand_optional_path(&self.broker.tls.client_cert_file)?;
        self.broker.tls.client_key_file = expand_optional_path(&self.broker.tls.client_key_file)?;
        self.logging.log_file = expand_home(&self.logging.log_file)?;
        self.health.status_socket = expand_optional_path(&self.health.status_socket)?;
        self.health.status_file = expand_home(&self.health.status_file)?;
        Ok(())
    }

    pub fn validate(&self) -> Result<(), ConfigError> {
        self.validate_with_options(ConfigValidationOptions::standard())
    }

    pub fn validate_with_identity_override(&self) -> Result<(), ConfigError> {
        self.validate_with_options(ConfigValidationOptions::with_identity_override())
    }

    fn validate_with_options(&self, options: ConfigValidationOptions) -> Result<(), ConfigError> {
        if self.format != "p2ptunnel-config-v3" {
            return Err(ConfigError::InvalidConfig(format!(
                "unsupported config format '{}'",
                self.format
            )));
        }

        if !self.security.require_mqtt_tls {
            return Err(ConfigError::InvalidConfig(
                "security.require_mqtt_tls must remain enabled in v0.2".to_owned(),
            ));
        }
        if !self.broker.url.starts_with("mqtts://") {
            return Err(ConfigError::InvalidConfig(
                "broker.url must use mqtts:// when TLS is required".to_owned(),
            ));
        }
        if !self.security.require_message_encryption {
            return Err(ConfigError::InvalidConfig(
                "security.require_message_encryption must remain enabled in v0.2".to_owned(),
            ));
        }
        if !self.security.require_message_signatures {
            return Err(ConfigError::InvalidConfig(
                "security.require_message_signatures must remain enabled in v0.2".to_owned(),
            ));
        }
        if self.security.replay_cache_size == 0 {
            return Err(ConfigError::InvalidConfig(
                "security.replay_cache_size must be greater than zero".to_owned(),
            ));
        }
        if !self.security.require_authorized_keys {
            return Err(ConfigError::InvalidConfig(
                "security.require_authorized_keys must remain enabled in v0.2".to_owned(),
            ));
        }
        if !self.security.reject_unknown_config_keys {
            return Err(ConfigError::InvalidConfig(
                "security.reject_unknown_config_keys must remain enabled in v0.2".to_owned(),
            ));
        }
        if !self.security.refuse_world_readable_identity {
            return Err(ConfigError::InvalidConfig(
                "security.refuse_world_readable_identity must remain enabled in v0.2".to_owned(),
            ));
        }
        if !self.security.refuse_world_writable_paths {
            return Err(ConfigError::InvalidConfig(
                "security.refuse_world_writable_paths must remain enabled in v0.2".to_owned(),
            ));
        }
        if self.broker.connect_timeout_secs != 5 {
            return Err(ConfigError::InvalidConfig(
                "broker.connect_timeout_secs must remain 5 in v0.2 because the current MQTT transport does not expose a configurable connect timeout"
                    .to_owned(),
            ));
        }
        if self.broker.session_expiry_secs != 0 {
            return Err(ConfigError::InvalidConfig(
                "broker.session_expiry_secs must remain 0 in v0.2 because the current signaling transport uses MQTT v4 semantics"
                    .to_owned(),
            ));
        }
        if self.broker.username.is_empty() && !self.broker.password_file.as_os_str().is_empty() {
            return Err(ConfigError::InvalidConfig(
                "broker.password_file requires broker.username in v0.2".to_owned(),
            ));
        }
        if self.broker.url.starts_with("mqtts://") {
            if self.broker.tls.insecure_skip_verify {
                return Err(ConfigError::InvalidConfig(
                    "broker.tls.insecure_skip_verify is unsupported in v0.2".to_owned(),
                ));
            }
            let client_cert_set = !self.broker.tls.client_cert_file.as_os_str().is_empty();
            let client_key_set = !self.broker.tls.client_key_file.as_os_str().is_empty();
            if client_cert_set != client_key_set {
                return Err(ConfigError::InvalidConfig(
                    "broker TLS client certificate and key must be configured together".to_owned(),
                ));
            }
        }

        if !self.paths.authorized_keys.is_file() {
            return Err(ConfigError::InvalidConfig(format!(
                "authorized_keys file '{}' does not exist",
                self.paths.authorized_keys.display()
            )));
        }
        if self.logging.log_rotation != "none" {
            return Err(ConfigError::InvalidConfig(
                "logging.log_rotation is unsupported in v0.2; use 'none'".to_owned(),
            ));
        }
        if !self.health.status_socket.as_os_str().is_empty() {
            return Err(ConfigError::InvalidConfig(
                "health.status_socket is unsupported in v0.2".to_owned(),
            ));
        }
        if self.reconnect.hold_local_client_during_reconnect {
            return Err(ConfigError::InvalidConfig(
                "reconnect.hold_local_client_during_reconnect is unsupported in v0.2".to_owned(),
            ));
        }
        if self.reconnect.local_client_hold_secs != 0 {
            return Err(ConfigError::InvalidConfig(
                "reconnect.local_client_hold_secs is unsupported in v0.2".to_owned(),
            ));
        }
        if options.require_identity_file {
            validate_required_file(&self.paths.identity, "identity")?;
            validate_non_world_writable(&self.paths.identity, "paths.identity")?;
        }
        validate_required_file(&self.paths.authorized_keys, "authorized_keys")?;
        validate_optional_file(
            &self.broker.tls.ca_file,
            "broker.tls.ca_file",
            !self.broker.tls.ca_file.as_os_str().is_empty(),
        )?;
        validate_optional_file(
            &self.broker.password_file,
            "broker.password_file",
            !self.broker.password_file.as_os_str().is_empty(),
        )?;
        validate_optional_file(
            &self.broker.tls.client_cert_file,
            "broker.tls.client_cert_file",
            !self.broker.tls.client_cert_file.as_os_str().is_empty(),
        )?;
        validate_optional_file(
            &self.broker.tls.client_key_file,
            "broker.tls.client_key_file",
            !self.broker.tls.client_key_file.as_os_str().is_empty(),
        )?;
        validate_non_world_writable(&self.paths.authorized_keys, "paths.authorized_keys")?;
        validate_non_world_writable(&self.paths.state_dir, "paths.state_dir")?;
        validate_non_world_writable(&self.paths.log_dir, "paths.log_dir")?;
        validate_non_world_writable(&self.logging.log_file, "logging.log_file")?;
        validate_non_world_writable(&self.health.status_file, "health.status_file")?;
        if !self.broker.tls.ca_file.as_os_str().is_empty() {
            validate_non_world_writable(&self.broker.tls.ca_file, "broker.tls.ca_file")?;
        }
        if !self.broker.password_file.as_os_str().is_empty() {
            validate_non_world_writable(&self.broker.password_file, "broker.password_file")?;
        }
        if !self.broker.tls.client_cert_file.as_os_str().is_empty() {
            validate_non_world_writable(
                &self.broker.tls.client_cert_file,
                "broker.tls.client_cert_file",
            )?;
        }
        if !self.broker.tls.client_key_file.as_os_str().is_empty() {
            validate_non_world_writable(
                &self.broker.tls.client_key_file,
                "broker.tls.client_key_file",
            )?;
        }

        self.validate_forwards()?;

        Ok(())
    }

    fn validate_forwards(&self) -> Result<(), ConfigError> {
        if self.forwards.is_empty() {
            return Err(ConfigError::InvalidConfig(
                "at least one [[forwards]] rule is required".to_owned(),
            ));
        }

        let mut ids = HashSet::new();
        let mut offer_binds = HashSet::new();
        for forward in &self.forwards {
            validate_forward_id(&forward.id)?;
            if !ids.insert(forward.id.clone()) {
                return Err(ConfigError::InvalidConfig(format!(
                    "duplicate forward id '{}'",
                    forward.id
                )));
            }

            match self.node.role {
                NodeRole::Offer => {
                    let Some(offer) = &forward.offer else {
                        return Err(ConfigError::InvalidConfig(format!(
                            "forward '{}' requires [forwards.offer] for offer role",
                            forward.id
                        )));
                    };
                    validate_listen_host(&offer.listen_host, &forward.id)?;
                    if offer.listen_port == 0 {
                        return Err(ConfigError::InvalidConfig(format!(
                            "forward '{}' listen_port must be non-zero",
                            forward.id
                        )));
                    }
                    if !offer_binds.insert((offer.listen_host.clone(), offer.listen_port)) {
                        return Err(ConfigError::InvalidConfig(format!(
                            "duplicate offer listen socket '{}:{}'",
                            offer.listen_host, offer.listen_port
                        )));
                    }
                }
                NodeRole::Answer => {
                    let Some(answer) = &forward.answer else {
                        return Err(ConfigError::InvalidConfig(format!(
                            "forward '{}' requires [forwards.answer] for answer role",
                            forward.id
                        )));
                    };
                    if answer.target_host.is_empty() {
                        return Err(ConfigError::InvalidConfig(format!(
                            "forward '{}' target_host must be set",
                            forward.id
                        )));
                    }
                    if answer.target_port == 0 {
                        return Err(ConfigError::InvalidConfig(format!(
                            "forward '{}' target_port must be non-zero",
                            forward.id
                        )));
                    }
                    if answer.allow_remote_peers.is_empty() {
                        return Err(ConfigError::InvalidConfig(format!(
                            "forward '{}' allow_remote_peers must not be empty",
                            forward.id
                        )));
                    }
                    if answer.allow_remote_peers.iter().any(|peer| peer.as_str() == "*") {
                        return Err(ConfigError::InvalidConfig(format!(
                            "forward '{}' allow_remote_peers must use explicit peer IDs",
                            forward.id
                        )));
                    }
                }
            }
        }

        if matches!(self.node.role, NodeRole::Offer) {
            let Some(peer) = &self.peer else {
                return Err(ConfigError::InvalidConfig(
                    "[peer].remote_peer_id must be set for offer role".to_owned(),
                ));
            };
            if peer.remote_peer_id.as_str().is_empty() {
                return Err(ConfigError::InvalidConfig(
                    "[peer].remote_peer_id must be set for offer role".to_owned(),
                ));
            }
        }

        Ok(())
    }

    pub fn validate_identity_peer(&self, peer_id: &PeerId) -> Result<(), ConfigError> {
        if self.node.peer_id != *peer_id {
            return Err(ConfigError::InvalidConfig(format!(
                "config peer_id '{}' does not match identity peer_id '{}'",
                self.node.peer_id, peer_id
            )));
        }
        Ok(())
    }

    pub fn ensure_runtime_dirs(&self) -> Result<(), ConfigError> {
        fs::create_dir_all(&self.paths.state_dir)
            .map_err(|error| ConfigError::io_path(&self.paths.state_dir, error))?;
        fs::create_dir_all(&self.paths.log_dir)
            .map_err(|error| ConfigError::io_path(&self.paths.log_dir, error))?;

        if self.logging.file_logging {
            if let Some(parent) = self.logging.log_file.parent() {
                fs::create_dir_all(parent).map_err(|error| ConfigError::io_path(parent, error))?;
            }
        }

        if self.health.write_status_file {
            if let Some(parent) = self.health.status_file.parent() {
                fs::create_dir_all(parent).map_err(|error| ConfigError::io_path(parent, error))?;
            }
        }

        Ok(())
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

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct WebRtcConfig {
    pub stun_urls: Vec<String>,
    pub enable_trickle_ice: bool,
    pub enable_ice_restart: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct TunnelConfig {
    pub read_chunk_size: usize,
    pub local_eof_grace_ms: u64,
    pub remote_eof_grace_ms: u64,
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

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct OfferForwardBind {
    pub forward_id: String,
    pub listen_host: String,
    pub listen_port: u16,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct TargetAddr {
    pub host: String,
    pub port: u16,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum ForwardLookupError {
    UnknownForward,
    ForbiddenForward,
    MissingOfferConfig,
    MissingAnswerConfig,
}

#[derive(Clone, Debug)]
pub struct ForwardTable {
    by_id: std::collections::HashMap<String, ForwardRule>,
}

impl ForwardTable {
    pub fn new(forwards: &[ForwardRule]) -> Self {
        Self {
            by_id: forwards.iter().map(|forward| (forward.id.clone(), forward.clone())).collect(),
        }
    }

    pub fn get(&self, forward_id: &str) -> Option<&ForwardRule> {
        self.by_id.get(forward_id)
    }

    pub fn offer_listeners(&self) -> Result<Vec<OfferForwardBind>, ForwardLookupError> {
        let mut listeners = Vec::new();
        for forward in self.by_id.values() {
            let offer = forward.offer.as_ref().ok_or(ForwardLookupError::MissingOfferConfig)?;
            listeners.push(OfferForwardBind {
                forward_id: forward.id.clone(),
                listen_host: offer.listen_host.clone(),
                listen_port: offer.listen_port,
            });
        }
        listeners.sort_by(|left, right| left.forward_id.cmp(&right.forward_id));
        Ok(listeners)
    }

    pub fn target_for(
        &self,
        forward_id: &str,
        remote_peer_id: &PeerId,
    ) -> Result<TargetAddr, ForwardLookupError> {
        let forward = self.by_id.get(forward_id).ok_or(ForwardLookupError::UnknownForward)?;
        let answer = forward.answer.as_ref().ok_or(ForwardLookupError::MissingAnswerConfig)?;
        if !answer.allow_remote_peers.contains(remote_peer_id) {
            return Err(ForwardLookupError::ForbiddenForward);
        }
        Ok(TargetAddr { host: answer.target_host.clone(), port: answer.target_port })
    }
}

fn validate_forward_id(id: &str) -> Result<(), ConfigError> {
    if id.is_empty() {
        return Err(ConfigError::InvalidConfig("forward id must not be empty".to_owned()));
    }
    if id.len() > 64 {
        return Err(ConfigError::InvalidConfig(format!("forward id '{id}' exceeds 64 characters")));
    }
    if !id.bytes().all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_' | b'.')) {
        return Err(ConfigError::InvalidConfig(format!(
            "forward id '{id}' contains invalid characters"
        )));
    }
    Ok(())
}

fn validate_listen_host(host: &str, forward_id: &str) -> Result<(), ConfigError> {
    if host.is_empty() {
        return Err(ConfigError::InvalidConfig(format!(
            "forward '{forward_id}' listen_host must be set"
        )));
    }
    Ok(())
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

pub fn expand_home(path: &Path) -> Result<PathBuf, ConfigError> {
    let path_string = path.to_string_lossy();
    if !path_string.starts_with("~/") {
        return Ok(path.to_path_buf());
    }

    let home = env::var_os("HOME").ok_or_else(|| {
        ConfigError::InvalidConfig("HOME environment variable is not set".to_owned())
    })?;

    let relative = path_string.trim_start_matches("~/");
    Ok(PathBuf::from(home).join(relative))
}

fn expand_optional_path(path: &Path) -> Result<PathBuf, ConfigError> {
    if path.as_os_str().is_empty() {
        return Ok(PathBuf::new());
    }

    expand_home(path)
}

fn validate_required_file(path: &Path, field_name: &'static str) -> Result<(), ConfigError> {
    validate_optional_file(path, field_name, true)
}

fn validate_optional_file(
    path: &Path,
    field_name: &'static str,
    required: bool,
) -> Result<(), ConfigError> {
    if path.as_os_str().is_empty() {
        if required {
            return Err(ConfigError::InvalidConfig(format!("{field_name} must be set")));
        }
        return Ok(());
    }
    if !path.is_file() {
        return Err(ConfigError::InvalidConfig(format!(
            "{field_name} file '{}' does not exist",
            path.display()
        )));
    }
    Ok(())
}

#[cfg(unix)]
fn validate_non_world_writable(path: &Path, field_name: &'static str) -> Result<(), ConfigError> {
    use std::os::unix::fs::PermissionsExt;

    if path.as_os_str().is_empty() {
        return Ok(());
    }

    let mut candidate = path;
    while !candidate.exists() {
        candidate = candidate.parent().ok_or_else(|| {
            ConfigError::InvalidConfig(format!(
                "{field_name} must be inside an existing directory for path security checks"
            ))
        })?;
    }

    let metadata =
        fs::metadata(candidate).map_err(|error| ConfigError::io_path(candidate, error))?;
    if metadata.permissions().mode() & 0o002 != 0 {
        return Err(ConfigError::InvalidConfig(format!(
            "{field_name} path '{}' must not be world-writable",
            candidate.display()
        )));
    }
    Ok(())
}

#[cfg(not(unix))]
fn validate_non_world_writable(_path: &Path, _field_name: &'static str) -> Result<(), ConfigError> {
    Ok(())
}

#[cfg(test)]
mod tests;
