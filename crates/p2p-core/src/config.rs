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

impl AppConfig {
    pub fn load_from_file(path: &Path) -> Result<Self, ConfigError> {
        let content =
            fs::read_to_string(path).map_err(|error| ConfigError::io_path(path, error))?;
        let mut config: Self = toml::from_str(&content)?;
        config.expand_paths()?;
        config.validate()?;
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
        if self.format != "p2ptunnel-config-v2" {
            return Err(ConfigError::InvalidConfig(format!(
                "unsupported config format '{}'",
                self.format
            )));
        }

        if !self.security.require_mqtt_tls {
            return Err(ConfigError::InvalidConfig(
                "security.require_mqtt_tls must remain enabled in v2".to_owned(),
            ));
        }
        if !self.broker.url.starts_with("mqtts://") {
            return Err(ConfigError::InvalidConfig(
                "broker.url must use mqtts:// when TLS is required".to_owned(),
            ));
        }
        if !self.security.require_message_encryption {
            return Err(ConfigError::InvalidConfig(
                "security.require_message_encryption must remain enabled in v2".to_owned(),
            ));
        }
        if !self.security.require_message_signatures {
            return Err(ConfigError::InvalidConfig(
                "security.require_message_signatures must remain enabled in v2".to_owned(),
            ));
        }
        if self.security.replay_cache_size == 0 {
            return Err(ConfigError::InvalidConfig(
                "security.replay_cache_size must be greater than zero".to_owned(),
            ));
        }
        if !self.security.require_authorized_keys {
            return Err(ConfigError::InvalidConfig(
                "security.require_authorized_keys must remain enabled in v2".to_owned(),
            ));
        }
        if !self.security.reject_unknown_config_keys {
            return Err(ConfigError::InvalidConfig(
                "security.reject_unknown_config_keys must remain enabled in v2".to_owned(),
            ));
        }
        if !self.security.refuse_world_readable_identity {
            return Err(ConfigError::InvalidConfig(
                "security.refuse_world_readable_identity must remain enabled in v2".to_owned(),
            ));
        }
        if !self.security.refuse_world_writable_paths {
            return Err(ConfigError::InvalidConfig(
                "security.refuse_world_writable_paths must remain enabled in v2".to_owned(),
            ));
        }
        if self.broker.connect_timeout_secs != 5 {
            return Err(ConfigError::InvalidConfig(
                "broker.connect_timeout_secs must remain 5 in v2 because the current MQTT transport does not expose a configurable connect timeout"
                    .to_owned(),
            ));
        }
        if self.broker.session_expiry_secs != 0 {
            return Err(ConfigError::InvalidConfig(
                "broker.session_expiry_secs must remain 0 in v2 because the current signaling transport uses MQTT v4 semantics"
                    .to_owned(),
            ));
        }
        if self.broker.username.is_empty() && !self.broker.password_file.as_os_str().is_empty() {
            return Err(ConfigError::InvalidConfig(
                "broker.password_file requires broker.username in v2".to_owned(),
            ));
        }
        if self.broker.url.starts_with("mqtts://") {
            if self.broker.tls.ca_file.as_os_str().is_empty() {
                return Err(ConfigError::InvalidConfig(
                    "broker.tls.ca_file must be set for mqtts:// brokers".to_owned(),
                ));
            }
            if self.broker.tls.insecure_skip_verify {
                return Err(ConfigError::InvalidConfig(
                    "broker.tls.insecure_skip_verify is unsupported in v2".to_owned(),
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
                "logging.log_rotation is unsupported in v2; use 'none'".to_owned(),
            ));
        }
        if !self.health.status_socket.as_os_str().is_empty() {
            return Err(ConfigError::InvalidConfig(
                "health.status_socket is unsupported in v2".to_owned(),
            ));
        }
        if self.reconnect.hold_local_client_during_reconnect {
            return Err(ConfigError::InvalidConfig(
                "reconnect.hold_local_client_during_reconnect is unsupported in v2".to_owned(),
            ));
        }
        if self.reconnect.local_client_hold_secs != 0 {
            return Err(ConfigError::InvalidConfig(
                "reconnect.local_client_hold_secs is unsupported in v2".to_owned(),
            ));
        }
        validate_required_file(&self.paths.identity, "identity")?;
        validate_required_file(&self.paths.authorized_keys, "authorized_keys")?;
        validate_required_file(&self.broker.tls.ca_file, "broker.tls.ca_file")?;
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
        validate_non_world_writable(&self.paths.identity, "paths.identity")?;
        validate_non_world_writable(&self.paths.authorized_keys, "paths.authorized_keys")?;
        validate_non_world_writable(&self.paths.state_dir, "paths.state_dir")?;
        validate_non_world_writable(&self.paths.log_dir, "paths.log_dir")?;
        validate_non_world_writable(&self.logging.log_file, "logging.log_file")?;
        validate_non_world_writable(&self.health.status_file, "health.status_file")?;
        validate_non_world_writable(&self.broker.tls.ca_file, "broker.tls.ca_file")?;
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
mod tests {
    use std::fs;
    use std::path::Path;

    use super::{AppConfig, expand_home};

    fn sample_config(config_dir: &Path, state_dir: &Path) -> String {
        format!(
            r#"
format = "p2ptunnel-config-v2"

[node]
peer_id = "answer-office"
role = "answer"

[paths]
identity = "{identity}"
authorized_keys = "{authorized_keys}"
state_dir = "{state_dir}"
log_dir = "{log_dir}"

[broker]
url = "mqtts://mqtt.example.com:8883"
client_id = "answer-office"
topic_prefix = "p2ptunnel"
username = "answer-office"
password_file = "{password_file}"
qos = 1
keepalive_secs = 30
clean_session = false
connect_timeout_secs = 5
session_expiry_secs = 0

[broker.tls]
ca_file = "{ca_file}"
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

[[forwards]]
id = "ssh"

[forwards.answer]
target_host = "127.0.0.1"
target_port = 22
allow_remote_peers = ["offer-home"]

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

[logging]
level = "info"
format = "text"
file_logging = true
stdout_logging = true
log_file = "{log_file}"
redact_secrets = true
redact_sdp = true
redact_candidates = true
log_rotation = "none"

[health]
status_socket = ""
write_status_file = true
status_file = "{status_file}"
"#,
            identity = config_dir.join("identity").display(),
            authorized_keys = config_dir.join("authorized_keys").display(),
            state_dir = state_dir.display(),
            log_dir = state_dir.join("log").display(),
            password_file = config_dir.join("mqtt_password").display(),
            ca_file = config_dir.join("ca.crt").display(),
            log_file = state_dir.join("log/p2ptunnel.log").display(),
            status_file = state_dir.join("status.json").display(),
        )
    }

    fn offer_config(config_dir: &Path, state_dir: &Path) -> String {
        sample_config(config_dir, state_dir)
            .replace("peer_id = \"answer-office\"\nrole = \"answer\"", "peer_id = \"offer-home\"\nrole = \"offer\"")
            .replace("[paths]", "[peer]\nremote_peer_id = \"answer-office\"\n\n[paths]")
            .replace(
                "[forwards.answer]\ntarget_host = \"127.0.0.1\"\ntarget_port = 22\nallow_remote_peers = [\"offer-home\"]",
                "[forwards.offer]\nlisten_host = \"127.0.0.1\"\nlisten_port = 2223",
            )
    }

    fn append_answer_forward(config: String, id: &str, target_port: u16) -> String {
        config.replace(
            "[reconnect]",
            &format!(
                "[[forwards]]\nid = \"{id}\"\n\n[forwards.answer]\ntarget_host = \"127.0.0.1\"\ntarget_port = {target_port}\nallow_remote_peers = [\"offer-home\"]\n\n[reconnect]"
            ),
        )
    }

    fn append_offer_forward(config: String, id: &str, listen_port: u16) -> String {
        config.replace(
            "[reconnect]",
            &format!(
                "[[forwards]]\nid = \"{id}\"\n\n[forwards.offer]\nlisten_host = \"127.0.0.1\"\nlisten_port = {listen_port}\n\n[reconnect]"
            ),
        )
    }

    fn write_required_files(config_dir: &Path) {
        fs::write(config_dir.join("identity"), "peer_id = \"answer-office\"\n").expect("identity");
        fs::write(config_dir.join("authorized_keys"), "").expect("write auth keys");
        fs::write(config_dir.join("mqtt_password"), "secret\n").expect("password");
        fs::write(
            config_dir.join("ca.crt"),
            "-----BEGIN CERTIFICATE-----\nZm9v\n-----END CERTIFICATE-----\n",
        )
        .expect("ca");
    }

    fn render_documented_sample(sample: &str, config_dir: &Path, state_dir: &Path) -> String {
        sample
            .replace("__IDENTITY__", &config_dir.join("identity").display().to_string())
            .replace(
                "__AUTHORIZED_KEYS__",
                &config_dir.join("authorized_keys").display().to_string(),
            )
            .replace("__STATE_DIR__", &state_dir.display().to_string())
            .replace("__LOG_DIR__", &state_dir.join("log").display().to_string())
            .replace("__CA_FILE__", &config_dir.join("ca.crt").display().to_string())
            .replace("__LOG_FILE__", &state_dir.join("log/p2ptunnel.log").display().to_string())
            .replace("__STATUS_FILE__", &state_dir.join("status.json").display().to_string())
    }

    #[test]
    fn config_loads_and_parses() {
        let temp_dir = tempfile::tempdir().expect("temp dir");
        let config_dir = temp_dir.path().join("config");
        let state_dir = temp_dir.path().join("state");
        fs::create_dir_all(&config_dir).expect("create config dir");
        fs::create_dir_all(state_dir.join("log")).expect("create state dir");
        write_required_files(&config_dir);

        let config_path = temp_dir.path().join("config.toml");
        fs::write(&config_path, sample_config(&config_dir, &state_dir)).expect("write config");

        let config = AppConfig::load_from_file(&config_path).expect("config should load");
        assert_eq!(config.paths.identity, config_dir.join("identity"));
    }

    #[test]
    fn documented_sample_configs_parse_and_validate() {
        let temp_dir = tempfile::tempdir().expect("temp dir");
        let config_dir = temp_dir.path().join("config");
        let state_dir = temp_dir.path().join("state");
        fs::create_dir_all(&config_dir).expect("create config dir");
        write_required_files(&config_dir);

        for sample in [
            include_str!("../../../docs/examples/offer-config.toml"),
            include_str!("../../../docs/examples/answer-config.toml"),
        ] {
            let content = render_documented_sample(sample, &config_dir, &state_dir);
            let mut config: AppConfig = toml::from_str(&content).expect("sample should parse");
            config.expand_paths().expect("sample paths should expand");
            config.validate().expect("sample should validate");
        }
    }

    #[test]
    fn config_loads_when_runtime_dirs_are_missing() {
        let temp_dir = tempfile::tempdir().expect("temp dir");
        let config_dir = temp_dir.path().join("config");
        let state_dir = temp_dir.path().join("state");
        fs::create_dir_all(&config_dir).expect("create config dir");
        write_required_files(&config_dir);

        let config_path = temp_dir.path().join("config.toml");
        fs::write(&config_path, sample_config(&config_dir, &state_dir)).expect("write config");

        let config = AppConfig::load_from_file(&config_path).expect("config should load");
        assert!(!config.paths.state_dir.exists());
        assert!(!config.paths.log_dir.exists());
    }

    #[test]
    fn ensure_runtime_dirs_creates_missing_directories() {
        let temp_dir = tempfile::tempdir().expect("temp dir");
        let config_dir = temp_dir.path().join("config");
        let state_dir = temp_dir.path().join("state");
        fs::create_dir_all(&config_dir).expect("create config dir");
        write_required_files(&config_dir);

        let config_path = temp_dir.path().join("config.toml");
        fs::write(&config_path, sample_config(&config_dir, &state_dir)).expect("write config");

        let config = AppConfig::load_from_file(&config_path).expect("config should load");
        config.ensure_runtime_dirs().expect("runtime dirs should be created");

        assert!(config.paths.state_dir.is_dir());
        assert!(config.paths.log_dir.is_dir());
        assert!(config.logging.log_file.parent().expect("log parent").is_dir());
        assert!(config.health.status_file.parent().expect("status parent").is_dir());
    }

    #[test]
    fn config_rejects_empty_answer_allowlist() {
        let temp_dir = tempfile::tempdir().expect("temp dir");
        let config_dir = temp_dir.path().join("config");
        let state_dir = temp_dir.path().join("state");
        fs::create_dir_all(&config_dir).expect("create config dir");
        fs::create_dir_all(state_dir.join("log")).expect("create state dir");
        write_required_files(&config_dir);

        let config = sample_config(&config_dir, &state_dir)
            .replace("allow_remote_peers = [\"offer-home\"]", "allow_remote_peers = []");
        let config_path = temp_dir.path().join("config.toml");
        fs::write(&config_path, config).expect("write config");
        assert!(AppConfig::load_from_file(&config_path).is_err());
    }

    #[test]
    fn config_accepts_answer_config_with_two_forwards() {
        let temp_dir = tempfile::tempdir().expect("temp dir");
        let config_dir = temp_dir.path().join("config");
        let state_dir = temp_dir.path().join("state");
        fs::create_dir_all(&config_dir).expect("create config dir");
        fs::create_dir_all(state_dir.join("log")).expect("create state dir");
        write_required_files(&config_dir);

        let config = append_answer_forward(sample_config(&config_dir, &state_dir), "web-ui", 8080);
        let config_path = temp_dir.path().join("config.toml");
        fs::write(&config_path, config).expect("write config");

        let loaded = AppConfig::load_from_file(&config_path).expect("config should load");
        assert_eq!(loaded.forwards.len(), 2);
    }

    #[test]
    fn config_accepts_offer_config_with_two_forwards() {
        let temp_dir = tempfile::tempdir().expect("temp dir");
        let config_dir = temp_dir.path().join("config");
        let state_dir = temp_dir.path().join("state");
        fs::create_dir_all(&config_dir).expect("create config dir");
        fs::create_dir_all(state_dir.join("log")).expect("create state dir");
        write_required_files(&config_dir);

        let config = append_offer_forward(offer_config(&config_dir, &state_dir), "web-ui", 8080);
        let config_path = temp_dir.path().join("config.toml");
        fs::write(&config_path, config).expect("write config");

        let loaded = AppConfig::load_from_file(&config_path).expect("config should load");
        assert_eq!(loaded.forwards.len(), 2);
    }

    #[test]
    fn config_rejects_duplicate_forward_ids() {
        let temp_dir = tempfile::tempdir().expect("temp dir");
        let config_dir = temp_dir.path().join("config");
        let state_dir = temp_dir.path().join("state");
        fs::create_dir_all(&config_dir).expect("create config dir");
        fs::create_dir_all(state_dir.join("log")).expect("create state dir");
        write_required_files(&config_dir);

        let config = append_answer_forward(sample_config(&config_dir, &state_dir), "ssh", 8080);
        let config_path = temp_dir.path().join("config.toml");
        fs::write(&config_path, config).expect("write config");
        assert!(AppConfig::load_from_file(&config_path).is_err());
    }

    #[test]
    fn config_rejects_duplicate_offer_listen_sockets() {
        let temp_dir = tempfile::tempdir().expect("temp dir");
        let config_dir = temp_dir.path().join("config");
        let state_dir = temp_dir.path().join("state");
        fs::create_dir_all(&config_dir).expect("create config dir");
        fs::create_dir_all(state_dir.join("log")).expect("create state dir");
        write_required_files(&config_dir);

        let config = append_offer_forward(offer_config(&config_dir, &state_dir), "web-ui", 2223);
        let config_path = temp_dir.path().join("config.toml");
        fs::write(&config_path, config).expect("write config");
        assert!(AppConfig::load_from_file(&config_path).is_err());
    }

    #[test]
    fn config_rejects_invalid_forward_ids() {
        let temp_dir = tempfile::tempdir().expect("temp dir");
        let config_dir = temp_dir.path().join("config");
        let state_dir = temp_dir.path().join("state");
        fs::create_dir_all(&config_dir).expect("create config dir");
        fs::create_dir_all(state_dir.join("log")).expect("create state dir");
        write_required_files(&config_dir);

        for invalid in ["", "bad/id", "bad:id", "bad id", "bad\\id"] {
            let config = sample_config(&config_dir, &state_dir)
                .replace("id = \"ssh\"", &format!("id = \"{invalid}\""));
            let config_path = temp_dir.path().join("config.toml");
            fs::write(&config_path, config).expect("write config");
            assert!(AppConfig::load_from_file(&config_path).is_err(), "{invalid}");
        }
    }

    #[test]
    fn config_rejects_missing_role_specific_forward_ports() {
        let temp_dir = tempfile::tempdir().expect("temp dir");
        let config_dir = temp_dir.path().join("config");
        let state_dir = temp_dir.path().join("state");
        fs::create_dir_all(&config_dir).expect("create config dir");
        fs::create_dir_all(state_dir.join("log")).expect("create state dir");
        write_required_files(&config_dir);

        for config in [
            offer_config(&config_dir, &state_dir).replace("listen_port = 2223\n", ""),
            sample_config(&config_dir, &state_dir).replace("target_port = 22\n", ""),
        ] {
            let config_path = temp_dir.path().join("config.toml");
            fs::write(&config_path, config).expect("write config");
            assert!(AppConfig::load_from_file(&config_path).is_err());
        }
    }

    #[test]
    fn old_single_forward_config_is_rejected() {
        let temp_dir = tempfile::tempdir().expect("temp dir");
        let config_dir = temp_dir.path().join("config");
        let state_dir = temp_dir.path().join("state");
        fs::create_dir_all(&config_dir).expect("create config dir");
        fs::create_dir_all(state_dir.join("log")).expect("create state dir");
        write_required_files(&config_dir);

        let config = sample_config(&config_dir, &state_dir).replace(
            "[tunnel]\nread_chunk_size = 16384",
            "[tunnel]\nread_chunk_size = 16384\n\n[tunnel.offer]\nlisten_host = \"127.0.0.1\"\nlisten_port = 2223\nremote_peer_id = \"answer-office\"",
        );
        let config_path = temp_dir.path().join("config.toml");
        fs::write(&config_path, config).expect("write config");
        assert!(AppConfig::load_from_file(&config_path).is_err());
    }

    #[test]
    fn config_rejects_unsupported_session_expiry() {
        let temp_dir = tempfile::tempdir().expect("temp dir");
        let config_dir = temp_dir.path().join("config");
        let state_dir = temp_dir.path().join("state");
        fs::create_dir_all(&config_dir).expect("create config dir");
        fs::create_dir_all(state_dir.join("log")).expect("create state dir");
        write_required_files(&config_dir);

        let config = sample_config(&config_dir, &state_dir)
            .replace("session_expiry_secs = 0", "session_expiry_secs = 60");
        let config_path = temp_dir.path().join("config.toml");
        fs::write(&config_path, config).expect("write config");
        assert!(AppConfig::load_from_file(&config_path).is_err());
    }

    #[test]
    fn config_rejects_partial_broker_client_auth() {
        let temp_dir = tempfile::tempdir().expect("temp dir");
        let config_dir = temp_dir.path().join("config");
        let state_dir = temp_dir.path().join("state");
        fs::create_dir_all(&config_dir).expect("create config dir");
        fs::create_dir_all(state_dir.join("log")).expect("create state dir");
        write_required_files(&config_dir);

        let config = sample_config(&config_dir, &state_dir)
            .replace("client_key_file = \"\"", "client_key_file = \"/tmp/client.key\"");
        let config_path = temp_dir.path().join("config.toml");
        fs::write(&config_path, config).expect("write config");
        assert!(AppConfig::load_from_file(&config_path).is_err());
    }

    #[test]
    fn config_rejects_unsupported_connect_timeout() {
        let temp_dir = tempfile::tempdir().expect("temp dir");
        let config_dir = temp_dir.path().join("config");
        let state_dir = temp_dir.path().join("state");
        fs::create_dir_all(&config_dir).expect("create config dir");
        fs::create_dir_all(state_dir.join("log")).expect("create state dir");
        write_required_files(&config_dir);

        let config = sample_config(&config_dir, &state_dir)
            .replace("connect_timeout_secs = 5", "connect_timeout_secs = 10");
        let config_path = temp_dir.path().join("config.toml");
        fs::write(&config_path, config).expect("write config");
        assert!(AppConfig::load_from_file(&config_path).is_err());
    }

    #[test]
    fn expand_home_uses_current_home_directory() {
        let home = std::env::var_os("HOME").expect("HOME should be set for tests");
        let expanded = expand_home(Path::new("~/example")).expect("path should expand");
        assert_eq!(expanded, std::path::PathBuf::from(home).join("example"));
    }

    #[test]
    fn config_allows_anonymous_broker_auth() {
        let temp_dir = tempfile::tempdir().expect("temp dir");
        let config_dir = temp_dir.path().join("config");
        let state_dir = temp_dir.path().join("state");
        fs::create_dir_all(&config_dir).expect("create config dir");
        fs::create_dir_all(state_dir.join("log")).expect("create state dir");
        write_required_files(&config_dir);

        let config = sample_config(&config_dir, &state_dir)
            .replace("username = \"answer-office\"", "username = \"\"")
            .replace(
                &format!("password_file = \"{}\"", config_dir.join("mqtt_password").display()),
                "password_file = \"\"",
            );
        let config_path = temp_dir.path().join("config.toml");
        fs::write(&config_path, config).expect("write config");
        AppConfig::load_from_file(&config_path).expect("anonymous config");
    }

    #[test]
    fn config_allows_username_only_broker_auth() {
        let temp_dir = tempfile::tempdir().expect("temp dir");
        let config_dir = temp_dir.path().join("config");
        let state_dir = temp_dir.path().join("state");
        fs::create_dir_all(&config_dir).expect("create config dir");
        fs::create_dir_all(state_dir.join("log")).expect("create state dir");
        write_required_files(&config_dir);

        let config = sample_config(&config_dir, &state_dir).replace(
            &format!("password_file = \"{}\"", config_dir.join("mqtt_password").display()),
            "password_file = \"\"",
        );
        let config_path = temp_dir.path().join("config.toml");
        fs::write(&config_path, config).expect("write config");
        AppConfig::load_from_file(&config_path).expect("username-only config");
    }

    #[test]
    fn config_rejects_password_without_username() {
        let temp_dir = tempfile::tempdir().expect("temp dir");
        let config_dir = temp_dir.path().join("config");
        let state_dir = temp_dir.path().join("state");
        fs::create_dir_all(&config_dir).expect("create config dir");
        fs::create_dir_all(state_dir.join("log")).expect("create state dir");
        write_required_files(&config_dir);

        let config = sample_config(&config_dir, &state_dir)
            .replace("username = \"answer-office\"", "username = \"\"");
        let config_path = temp_dir.path().join("config.toml");
        fs::write(&config_path, config).expect("write config");
        assert!(AppConfig::load_from_file(&config_path).is_err());
    }

    #[test]
    fn config_rejects_dead_knobs() {
        let temp_dir = tempfile::tempdir().expect("temp dir");
        let config_dir = temp_dir.path().join("config");
        let state_dir = temp_dir.path().join("state");
        fs::create_dir_all(&config_dir).expect("create config dir");
        fs::create_dir_all(state_dir.join("log")).expect("create state dir");
        write_required_files(&config_dir);

        for (from, to) in [
            ("log_rotation = \"none\"", "log_rotation = \"daily\""),
            ("status_socket = \"\"", "status_socket = \"/tmp/p2ptunnel.sock\""),
            (
                "hold_local_client_during_reconnect = false",
                "hold_local_client_during_reconnect = true",
            ),
            ("local_client_hold_secs = 0", "local_client_hold_secs = 5"),
        ] {
            let config = sample_config(&config_dir, &state_dir).replace(from, to);
            let config_path = temp_dir.path().join("config.toml");
            fs::write(&config_path, config).expect("write config");
            assert!(AppConfig::load_from_file(&config_path).is_err(), "{to}");
        }
    }

    #[test]
    fn config_rejects_removed_v1_knobs() {
        let temp_dir = tempfile::tempdir().expect("temp dir");
        let config_dir = temp_dir.path().join("config");
        let state_dir = temp_dir.path().join("state");
        fs::create_dir_all(&config_dir).expect("create config dir");
        fs::create_dir_all(state_dir.join("log")).expect("create state dir");
        write_required_files(&config_dir);

        for (anchor, extra) in [
            ("stun_urls = [\"stun:stun.l.google.com:19302\"]", "\nice_gather_timeout_secs = 15"),
            ("enable_trickle_ice = true", "\nice_connection_timeout_secs = 20"),
            ("enable_ice_restart = true", "\nmax_message_size = 262144"),
            ("remote_eof_grace_ms = 250", "\nframe_version = 1"),
            ("read_chunk_size = 16384", "\nwrite_buffer_limit = 262144"),
            ("target_port = 22", "\nauto_open = true"),
            ("[health]", "\nheartbeat_interval_secs = 10\nping_timeout_secs = 30"),
        ] {
            let config =
                sample_config(&config_dir, &state_dir).replace(anchor, &format!("{anchor}{extra}"));
            let config_path = temp_dir.path().join("config.toml");
            fs::write(&config_path, config).expect("write config");
            assert!(AppConfig::load_from_file(&config_path).is_err(), "{extra}");
        }
    }

    #[test]
    fn missing_config_file_error_names_path() {
        let temp_dir = tempfile::tempdir().expect("temp dir");
        let path = temp_dir.path().join("missing-config.toml");

        let error = AppConfig::load_from_file(&path).expect_err("config load should fail");

        assert!(error.to_string().contains(path.to_string_lossy().as_ref()));
    }
}
