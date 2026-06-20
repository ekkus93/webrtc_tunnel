//! Android tunnel runtime: a thread-safe controller that owns the embedded Tokio
//! runtime and the offer/answer daemon task, exposing a small lifecycle surface
//! (start/stop/status/logs) to the FFI layer. The serializable data model lives in
//! [`types`], the mutable state and its maintenance helpers in [`state`], and the
//! stateless validation entry points in [`validate`].

use std::path::Path;
use std::sync::{Arc, Mutex};

use p2p_core::{AppConfig, DaemonState, NodeRole};
use p2p_crypto::{AuthorizedKeys, IdentityFile};
use p2p_daemon::DaemonStatus;
use tokio::runtime::Runtime;

mod log_bridge;
mod state;
mod types;
mod validate;

use log_bridge::install_tracing_once;
use state::{RuntimeInner, record_start_error, reset_runtime_metadata, unix_ms};

pub use types::{
    AndroidForwardRuntimeStatus, AndroidLogEvent, AndroidRuntimeState, AndroidRuntimeStatus,
    AndroidTunnelMode, AndroidValidationResult,
};

// Used by the unit-test module's `super::*` (it asserts the daemon→UI mapping).
#[cfg(test)]
use types::android_state_from_daemon;

#[derive(Clone, Default)]
pub struct AndroidTunnelController {
    inner: Arc<Mutex<RuntimeInner>>,
}

impl AndroidTunnelController {
    pub fn new() -> Self {
        Self { inner: Arc::new(Mutex::new(RuntimeInner::default())) }
    }

    pub fn start_offer(&self, config_path: &str) -> Result<(), String> {
        self.start(AndroidTunnelMode::Offer, config_path, None)
    }

    pub fn start_offer_with_identity(
        &self,
        config_path: &str,
        identity_toml: &str,
    ) -> Result<(), String> {
        self.start(AndroidTunnelMode::Offer, config_path, Some(identity_toml))
    }

    pub fn start_answer(&self, config_path: &str) -> Result<(), String> {
        self.start(AndroidTunnelMode::Answer, config_path, None)
    }

    fn start(
        &self,
        mode: AndroidTunnelMode,
        config_path: &str,
        identity_toml: Option<&str>,
    ) -> Result<(), String> {
        let mut inner = self.inner.lock().map_err(|_| "runtime mutex poisoned".to_owned())?;
        if inner.state.active {
            // A duplicate start must not corrupt the live runtime: reject without
            // routing through record_start_error (which would set Error + active=false).
            return Err("runtime already running".to_owned());
        }

        let config_path = config_path.to_owned();
        let config = if identity_toml.is_some() {
            AppConfig::load_from_file_with_identity_override(Path::new(&config_path))
                .map_err(|error| record_start_error(&mut inner, error.to_string()))?
        } else {
            AppConfig::load_from_file(Path::new(&config_path))
                .map_err(|error| record_start_error(&mut inner, error.to_string()))?
        };
        let identity = match identity_toml {
            Some(identity_toml) => IdentityFile::from_toml(identity_toml)
                .map_err(|error| record_start_error(&mut inner, error.to_string()))?,
            None => IdentityFile::from_file(&config.paths.identity)
                .map_err(|error| record_start_error(&mut inner, error.to_string()))?,
        };
        config
            .validate_identity_peer(&identity.peer_id)
            .map_err(|error| record_start_error(&mut inner, error.to_string()))?;
        let authorized_keys = AuthorizedKeys::from_file(&config.paths.authorized_keys)
            .map_err(|error| record_start_error(&mut inner, error.to_string()))?;
        match (mode, &config.node.role) {
            (AndroidTunnelMode::Offer, NodeRole::Offer)
            | (AndroidTunnelMode::Answer, NodeRole::Answer) => {}
            (AndroidTunnelMode::Offer, NodeRole::Answer) => {
                return Err(record_start_error(
                    &mut inner,
                    "config role is answer but offer mode was requested".to_owned(),
                ));
            }
            (AndroidTunnelMode::Answer, NodeRole::Offer) => {
                return Err(record_start_error(
                    &mut inner,
                    "config role is offer but answer mode was requested".to_owned(),
                ));
            }
        }

        // Route the shared daemon/WebRTC `tracing` output (including ICE diagnostics)
        // into the in-app log feed. No-op after the first start, so the configured
        // level is fixed for the process lifetime — set `logging.level = "debug"` and
        // `logging.redact_candidates = false` before the first start to capture ICE
        // candidate details for diagnosis.
        install_tracing_once(inner.logs.clone(), &config.logging.level);

        let runtime =
            Runtime::new().map_err(|error| record_start_error(&mut inner, error.to_string()))?;
        let log_state = Arc::clone(&self.inner);
        let config_clone = config.clone();
        // Seed the status channel with a pre-connection snapshot so `status()` has a
        // valid value before the daemon emits its first real status. Only the offer
        // daemon streams live status; answer mode is disabled on Android.
        let status_seed = DaemonStatus::new(
            config.node.peer_id.clone(),
            config.node.role.clone(),
            false,
            None,
            DaemonState::Idle,
            config.forwards.iter().map(|forward| forward.id.clone()).collect(),
        );
        let (status_tx, status_rx) = tokio::sync::watch::channel(status_seed);
        let task = runtime.spawn(async move {
            let result = match mode {
                AndroidTunnelMode::Offer => {
                    p2p_daemon::run_offer_daemon_with_status(
                        config_clone,
                        identity,
                        authorized_keys,
                        status_tx,
                    )
                    .await
                }
                AndroidTunnelMode::Answer => {
                    p2p_daemon::run_answer_daemon(config_clone, identity, authorized_keys).await
                }
            };
            if let Ok(mut inner) = log_state.lock() {
                match result {
                    Ok(()) => {
                        inner.state.state = AndroidRuntimeState::Stopped;
                        inner.state.mode = None;
                        inner.state.active = false;
                        inner.state.config_path = None;
                        inner.state.last_error = None;
                        reset_runtime_metadata(&mut inner.state);
                        inner.logs.push(AndroidLogEvent {
                            unix_ms: unix_ms(),
                            level: "info".to_owned(),
                            message: "runtime completed".to_owned(),
                        });
                    }
                    Err(error) => {
                        inner.state.state = AndroidRuntimeState::Error;
                        inner.state.last_error = Some(error.to_string());
                        inner.state.active = false;
                        // Preserve config_path for diagnostics; clear uptime/measured fields.
                        reset_runtime_metadata(&mut inner.state);
                        inner.logs.push(AndroidLogEvent {
                            unix_ms: unix_ms(),
                            level: "error".to_owned(),
                            message: error.to_string(),
                        });
                    }
                }
                inner.task = None;
            }
        });

        inner.state.state = AndroidRuntimeState::Running;
        inner.state.mode = Some(mode);
        inner.state.config_path = Some(config_path);
        inner.state.last_error = None;
        inner.state.started_at_unix_ms = Some(unix_ms());
        inner.state.active = true;
        inner.task = Some(task);
        inner.runtime = Some(runtime);
        inner.status_rx = Some(status_rx);
        inner.forward_config = config
            .forwards
            .iter()
            .filter_map(|forward| {
                forward
                    .offer
                    .as_ref()
                    .map(|offer| (forward.id.clone(), offer.listen_host.clone(), offer.listen_port))
            })
            .collect();
        inner.logs.push(AndroidLogEvent {
            unix_ms: unix_ms(),
            level: "info".to_owned(),
            message: "runtime started".to_owned(),
        });
        Ok(())
    }

    pub fn stop(&self) {
        if let Ok(mut inner) = self.inner.lock() {
            if let Some(task) = inner.task.take() {
                task.abort();
            }
            inner.runtime = None;
            inner.status_rx = None;
            inner.forward_config = Vec::new();
            inner.state.state = AndroidRuntimeState::Stopped;
            inner.state.mode = None;
            inner.state.active = false;
            // Clean stop: clear stale metadata so the UI shows no stale uptime, error,
            // config path, or session/forward state after stopping.
            inner.state.config_path = None;
            inner.state.last_error = None;
            reset_runtime_metadata(&mut inner.state);
            inner.logs.push(AndroidLogEvent {
                unix_ms: unix_ms(),
                level: "info".to_owned(),
                message: "runtime stopped".to_owned(),
            });
        }
    }

    pub fn status(&self) -> AndroidRuntimeStatus {
        self.inner.lock().map(|inner| inner.snapshot_status()).unwrap_or(AndroidRuntimeStatus {
            state: AndroidRuntimeState::Error,
            mode: None,
            config_path: None,
            last_error: Some("runtime mutex poisoned".to_owned()),
            started_at_unix_ms: None,
            active: false,
            mqtt_connected: false,
            active_session_count: 0,
            session_capacity: None,
            remote_peer_id: None,
            forwards: Vec::new(),
        })
    }

    pub fn recent_logs(&self, max_events: usize) -> Vec<AndroidLogEvent> {
        self.inner.lock().map(|inner| inner.logs.recent(max_events)).unwrap_or_default()
    }

    pub fn last_error(&self) -> Option<String> {
        self.inner.lock().ok().and_then(|inner| inner.state.last_error.clone())
    }

    /// Record a failure that happened at the JNI/C-ABI boundary, before the controller could
    /// run (e.g. a null/invalid config path or non-UTF-8 identity). Stores only `last_error`
    /// so `last_error()` surfaces the real cause to Kotlin instead of "unknown error"; the
    /// runtime state is left untouched because nothing actually started.
    pub fn record_bridge_error(&self, message: String) {
        if let Ok(mut inner) = self.inner.lock() {
            inner.state.last_error = Some(message);
        }
    }
}

#[cfg(test)]
mod tests;
