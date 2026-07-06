//! Configuration overrides, role/state policy, and forward-table accessors.
//!
//! These helpers operate purely on [`AppConfig`] (plus authorized-key validation)
//! and carry no daemon runtime state, so they live apart from the session machinery.

use std::env;
use std::time::Duration;

use p2p_core::{AppConfig, ConfigError, DaemonState, NodeRole, PeerId};
use p2p_crypto::AuthorizedKeys;

use crate::DaemonError;
pub fn apply_env_overrides(config: &mut AppConfig) -> Result<(), ConfigError> {
    apply_override_pairs(config, env::vars())
}

pub fn apply_offer_overrides(config: &mut AppConfig, broker_url: Option<String>) {
    if let Some(broker_url) = broker_url {
        config.broker.url = broker_url;
    }
}

pub fn apply_answer_overrides(config: &mut AppConfig, broker_url: Option<String>) {
    if let Some(broker_url) = broker_url {
        config.broker.url = broker_url;
    }
}

pub fn compute_backoff_delay(config: &AppConfig, attempt: u32) -> Duration {
    let base_ms = if attempt == 0 {
        config.reconnect.backoff_initial_ms
    } else {
        let multiplier =
            config.reconnect.backoff_multiplier.powi(i32::try_from(attempt).unwrap_or(i32::MAX));
        (config.reconnect.backoff_initial_ms as f64 * multiplier)
            .min(config.reconnect.backoff_max_ms as f64) as u64
    };
    let jitter_window = ((base_ms as f64) * config.reconnect.jitter_ratio).round() as i64;
    let jitter = if jitter_window == 0 {
        0
    } else {
        let mut rng = rand_core::OsRng;
        use rand_core::RngCore;
        let span = u64::try_from(jitter_window * 2 + 1).unwrap_or(1);
        i64::try_from(rng.next_u64() % span).unwrap_or(0) - jitter_window
    };
    Duration::from_millis(base_ms.saturating_add_signed(jitter))
}

pub(crate) fn steady_state_for_role(role: &NodeRole) -> DaemonState {
    match role {
        NodeRole::Offer => DaemonState::WaitingForLocalClient,
        NodeRole::Answer => DaemonState::Serving,
    }
}

pub(crate) fn offer_remote_peer_id(config: &AppConfig) -> Result<PeerId, DaemonError> {
    config.peer.as_ref().map(|peer| peer.remote_peer_id.clone()).ok_or_else(|| {
        DaemonError::Config(ConfigError::InvalidConfig(
            "[peer].remote_peer_id must be set for offer role".to_owned(),
        ))
    })
}

pub(crate) fn validate_config_authorized_peers(
    config: &AppConfig,
    authorized_keys: &AuthorizedKeys,
) -> Result<(), DaemonError> {
    for peer_id in config.required_authorized_peer_ids()? {
        if authorized_keys.get_by_peer_id(peer_id).is_none() {
            return Err(DaemonError::MissingAuthorizedPeer(peer_id.to_string()));
        }
    }
    Ok(())
}

#[cfg(test)]
pub(crate) fn first_offer_forward(
    config: &AppConfig,
) -> Result<(&str, &p2p_core::ForwardOfferConfig), DaemonError> {
    config
        .forwards
        .iter()
        .find_map(|forward| forward.offer.as_ref().map(|offer| (forward.id.as_str(), offer)))
        .ok_or_else(|| {
            DaemonError::Config(ConfigError::InvalidConfig(
                "at least one [forwards.offer] rule is required".to_owned(),
            ))
        })
}

#[cfg(test)]
pub(crate) fn first_answer_forward(
    config: &AppConfig,
) -> Result<&p2p_core::ForwardAnswerConfig, DaemonError> {
    config.forwards.iter().find_map(|forward| forward.answer.as_ref()).ok_or_else(|| {
        DaemonError::Config(ConfigError::InvalidConfig(
            "at least one [forwards.answer] rule is required".to_owned(),
        ))
    })
}

#[cfg(test)]
pub(crate) fn first_offer_forward_mut(
    config: &mut AppConfig,
) -> Option<&mut p2p_core::ForwardOfferConfig> {
    config.forwards.iter_mut().find_map(|forward| forward.offer.as_mut())
}

#[cfg(test)]
pub(crate) fn first_answer_forward_mut(
    config: &mut AppConfig,
) -> Option<&mut p2p_core::ForwardAnswerConfig> {
    config.forwards.iter_mut().find_map(|forward| forward.answer.as_mut())
}

pub(crate) fn apply_override_pairs(
    config: &mut AppConfig,
    overrides: impl IntoIterator<Item = (String, String)>,
) -> Result<(), ConfigError> {
    for (key, value) in overrides {
        match key.as_str() {
            "P2PTUNNEL_BROKER_URL" => config.broker.url = value,
            "P2PTUNNEL_BROKER_USERNAME" => config.broker.username = value,
            "P2PTUNNEL_BROKER_PASSWORD_FILE" => config.broker.password_file = value.into(),
            "P2PTUNNEL_LISTEN_PORT" => {
                return Err(legacy_forward_env_error(&key, "[forwards.offer].listen_port"));
            }
            "P2PTUNNEL_TARGET_HOST" => {
                return Err(legacy_forward_env_error(&key, "[forwards.answer].target_host"));
            }
            "P2PTUNNEL_TARGET_PORT" => {
                return Err(legacy_forward_env_error(&key, "[forwards.answer].target_port"));
            }
            _ => {}
        }
    }
    Ok(())
}

fn legacy_forward_env_error(name: &str, replacement: &str) -> ConfigError {
    ConfigError::InvalidConfig(format!(
        "{name} is no longer supported in v0.2 config. Use {replacement} in config.toml instead."
    ))
}
