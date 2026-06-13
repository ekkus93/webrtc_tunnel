//! Loading and validation for [`AppConfig`]: TOML deserialization, `~/` path
//! expansion, the v0.3 security/format invariant checks, forward-table validation,
//! identity-peer checks, and runtime-directory creation.

use std::collections::HashSet;
use std::fs;
use std::path::Path;

use crate::error::ConfigError;
use crate::ids::PeerId;

use super::forward::{validate_forward_id, validate_listen_host};
use super::paths::{
    expand_home, expand_optional_path, validate_non_world_writable, validate_optional_file,
    validate_required_file,
};
use super::{AppConfig, ConfigValidationOptions, NodeRole};
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
