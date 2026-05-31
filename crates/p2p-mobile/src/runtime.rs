use std::collections::VecDeque;
use std::path::Path;
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

use p2p_core::{AppConfig, NodeRole};
use p2p_crypto::{AuthorizedKeys, IdentityFile};
use serde::{Deserialize, Serialize};
use tokio::runtime::Runtime;
use tokio::task::JoinHandle;

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AndroidTunnelMode {
    #[default]
    Offer,
    Answer,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AndroidRuntimeState {
    #[default]
    Stopped,
    Starting,
    Running,
    Stopping,
    Error,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct AndroidRuntimeStatus {
    pub state: AndroidRuntimeState,
    pub mode: Option<AndroidTunnelMode>,
    pub config_path: Option<String>,
    pub last_error: Option<String>,
    pub started_at_unix_ms: Option<u64>,
    pub active: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct AndroidLogEvent {
    pub unix_ms: u64,
    pub level: String,
    pub message: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct AndroidValidationResult {
    pub valid: bool,
    pub message: Option<String>,
}

struct RuntimeInner {
    state: AndroidRuntimeStatus,
    logs: VecDeque<AndroidLogEvent>,
    task: Option<JoinHandle<()>>,
    runtime: Option<Runtime>,
}

fn record_start_error(inner: &mut RuntimeInner, message: String) -> String {
    inner.state.state = AndroidRuntimeState::Error;
    inner.state.active = false;
    inner.state.last_error = Some(message.clone());
    inner.logs.push_back(AndroidLogEvent {
        unix_ms: unix_ms(),
        level: "error".to_owned(),
        message: message.clone(),
    });
    while inner.logs.len() > 256 {
        inner.logs.pop_front();
    }
    message
}

impl Default for RuntimeInner {
    fn default() -> Self {
        Self {
            state: AndroidRuntimeStatus::default(),
            logs: VecDeque::with_capacity(256),
            task: None,
            runtime: None,
        }
    }
}

#[derive(Clone, Default)]
pub struct AndroidTunnelController {
    inner: Arc<Mutex<RuntimeInner>>,
}

impl AndroidTunnelController {
    pub fn new() -> Self {
        Self { inner: Arc::new(Mutex::new(RuntimeInner::default())) }
    }

    pub fn validate_config(config_path: impl AsRef<Path>) -> AndroidValidationResult {
        match AppConfig::load_from_file(config_path.as_ref()) {
            Ok(_) => AndroidValidationResult { valid: true, message: None },
            Err(error) => {
                AndroidValidationResult { valid: false, message: Some(error.to_string()) }
            }
        }
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
            return Err(record_start_error(&mut inner, "runtime already running".to_owned()));
        }

        let config_path = config_path.to_owned();
        let config = AppConfig::load_from_file(Path::new(&config_path))
            .map_err(|error| record_start_error(&mut inner, error.to_string()))?;
        let identity = match identity_toml {
            Some(identity_toml) => IdentityFile::from_toml(identity_toml)
                .map_err(|error| record_start_error(&mut inner, error.to_string()))?,
            None => IdentityFile::from_file(&config.paths.identity)
                .map_err(|error| record_start_error(&mut inner, error.to_string()))?,
        };
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

        let runtime =
            Runtime::new().map_err(|error| record_start_error(&mut inner, error.to_string()))?;
        let log_state = Arc::clone(&self.inner);
        let config_clone = config.clone();
        let task = runtime.spawn(async move {
            let result = match mode {
                AndroidTunnelMode::Offer => {
                    p2p_daemon::run_offer_daemon(config_clone, identity, authorized_keys).await
                }
                AndroidTunnelMode::Answer => {
                    p2p_daemon::run_answer_daemon(config_clone, identity, authorized_keys).await
                }
            };
            if let Err(error) = result {
                if let Ok(mut inner) = log_state.lock() {
                    inner.state.state = AndroidRuntimeState::Error;
                    inner.state.last_error = Some(error.to_string());
                    inner.state.active = false;
                    inner.logs.push_back(AndroidLogEvent {
                        unix_ms: unix_ms(),
                        level: "error".to_owned(),
                        message: error.to_string(),
                    });
                    while inner.logs.len() > 256 {
                        inner.logs.pop_front();
                    }
                    inner.task = None;
                }
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
        inner.logs.push_back(AndroidLogEvent {
            unix_ms: unix_ms(),
            level: "info".to_owned(),
            message: "runtime started".to_owned(),
        });
        while inner.logs.len() > 256 {
            inner.logs.pop_front();
        }
        Ok(())
    }

    pub fn stop(&self) {
        if let Ok(mut inner) = self.inner.lock() {
            if let Some(task) = inner.task.take() {
                task.abort();
            }
            inner.runtime = None;
            inner.state.state = AndroidRuntimeState::Stopped;
            inner.state.mode = None;
            inner.state.active = false;
            if inner.state.last_error.is_none() {
                inner.state.last_error = None;
            }
            inner.logs.push_back(AndroidLogEvent {
                unix_ms: unix_ms(),
                level: "info".to_owned(),
                message: "runtime stopped".to_owned(),
            });
            while inner.logs.len() > 256 {
                inner.logs.pop_front();
            }
        }
    }

    pub fn status(&self) -> AndroidRuntimeStatus {
        self.inner.lock().map(|inner| inner.state.clone()).unwrap_or(AndroidRuntimeStatus {
            state: AndroidRuntimeState::Error,
            mode: None,
            config_path: None,
            last_error: Some("runtime mutex poisoned".to_owned()),
            started_at_unix_ms: None,
            active: false,
        })
    }

    pub fn recent_logs(&self, max_events: usize) -> Vec<AndroidLogEvent> {
        let max_events = max_events.max(1);
        self.inner
            .lock()
            .map(|inner| inner.logs.iter().rev().take(max_events).cloned().collect())
            .unwrap_or_default()
    }

    pub fn last_error(&self) -> Option<String> {
        self.inner.lock().ok().and_then(|inner| inner.state.last_error.clone())
    }
}

fn unix_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis() as u64)
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use p2p_crypto::generate_identity;

    #[test]
    fn validate_config_reports_missing_file() {
        let result = AndroidTunnelController::validate_config("/definitely/missing/config.toml");
        assert!(!result.valid);
        assert!(result.message.is_some());
    }

    #[test]
    fn status_before_start_is_stopped() {
        let controller = AndroidTunnelController::new();
        let status = controller.status();
        assert_eq!(status.state, AndroidRuntimeState::Stopped);
        assert!(!status.active);
    }

    #[test]
    fn stop_before_start_is_safe() {
        let controller = AndroidTunnelController::new();
        controller.stop();
        assert_eq!(controller.status().state, AndroidRuntimeState::Stopped);
    }

    #[test]
    fn double_stop_is_safe() {
        let controller = AndroidTunnelController::new();
        controller.stop();
        controller.stop();
        assert_eq!(controller.status().state, AndroidRuntimeState::Stopped);
    }

    #[test]
    fn recent_logs_json_shape_is_stable() {
        let controller = AndroidTunnelController::new();
        let logs = controller.recent_logs(10);
        assert!(logs.is_empty());
        let _ = generate_identity("android-test").expect("identity");
    }
}
