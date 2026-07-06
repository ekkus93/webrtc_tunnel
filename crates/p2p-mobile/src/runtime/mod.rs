//! Android tunnel runtime: a thread-safe controller that owns the embedded Tokio
//! runtime and the offer/answer daemon task, exposing a small lifecycle surface
//! (start/stop/status/logs) to the FFI layer. The serializable data model lives in
//! [`types`], the mutable state and its maintenance helpers in [`state`], and the
//! stateless validation entry points in [`validate`].

use std::path::Path;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use p2p_core::{AppConfig, DaemonState, NodeRole};
use p2p_crypto::{AuthorizedKeys, IdentityFile};
use p2p_daemon::{DaemonStatus, ShutdownToken};
use tokio::runtime::Runtime;

mod log_bridge;
mod state;
mod types;
mod validate;

use log_bridge::install_tracing_once;
use state::{RuntimeInner, push_log, record_start_error, reset_runtime_metadata, unix_ms};

// Used by the C-ABI layer to timestamp a synthetic log event when `recent_logs`
// cannot be read due to mutex poison.
pub(crate) use state::unix_ms as bridge_unix_ms;

pub use types::{
    AndroidForwardRuntimeStatus, AndroidIceInfo, AndroidLogEvent, AndroidRuntimeState,
    AndroidRuntimeStatus, AndroidTunnelMode, AndroidValidationResult,
};

// Used by the unit-test module's `super::*` (it asserts the daemon→UI mapping).
#[cfg(test)]
use types::android_state_from_daemon;

/// Bound on how long `stop()` waits for the daemon to shut down cooperatively
/// before falling back to an explicit forced abort. The daemon's own cleanup
/// (WebRTC peer close, listener release, final status write) is normally
/// sub-second; this only guards against a wedged shutdown blocking the FFI
/// caller indefinitely.
const STOP_GRACE_PERIOD: Duration = Duration::from_secs(10);

/// How the runtime task actually finished when `stop()` was called. Distinct
/// from a plain `()` so a forced abort or a join failure can never be reported
/// to Kotlin as a clean stop — see [`AndroidTunnelController::stop`].
#[derive(Clone, Debug, Eq, PartialEq)]
enum StopOutcome {
    /// The daemon task observed the shutdown request and finished on its own
    /// within the grace period.
    Graceful,
    /// Nothing was running (duplicate/no-op stop); always safe.
    NotRunning,
    /// The task never finished within the grace period and had to be aborted.
    /// Not a clean stop: the daemon did not run its own cleanup to completion.
    ForcedAbort { grace_period: Duration },
    /// The task handle failed to join (the spawned future panicked or was
    /// otherwise cancelled) before or instead of finishing normally.
    TaskJoinFailed { reason: String },
}

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
        // Capture the ICE path decision so the UI can show which path is active.
        inner.state.ice =
            Some(AndroidIceInfo::from_decision(p2p_webrtc::describe_ice_decision(&config.webrtc)));
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
        install_tracing_once(inner.logs.clone(), &config.logging.level)
            .map_err(|error| record_start_error(&mut inner, error))?;

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
        let shutdown = ShutdownToken::new();
        let daemon_shutdown = shutdown.clone();
        let task = runtime.spawn(async move {
            let result = match mode {
                AndroidTunnelMode::Offer => {
                    p2p_daemon::run_offer_daemon_with_status_and_shutdown(
                        config_clone,
                        identity,
                        authorized_keys,
                        status_tx,
                        daemon_shutdown,
                    )
                    .await
                }
                AndroidTunnelMode::Answer => {
                    p2p_daemon::run_answer_daemon_with_shutdown(
                        config_clone,
                        identity,
                        authorized_keys,
                        daemon_shutdown,
                    )
                    .await
                }
            };
            match log_state.lock() {
                Ok(mut inner) => {
                    match result {
                        Ok(()) => {
                            inner.state.state = AndroidRuntimeState::Stopped;
                            inner.state.mode = None;
                            inner.state.active = false;
                            inner.state.config_path = None;
                            inner.state.last_error = None;
                            reset_runtime_metadata(&mut inner.state);
                            push_log(
                                &inner.logs,
                                AndroidLogEvent {
                                    unix_ms: unix_ms(),
                                    level: "info".to_owned(),
                                    message: "runtime completed".to_owned(),
                                },
                            );
                        }
                        Err(error) => {
                            inner.state.state = AndroidRuntimeState::Error;
                            inner.state.last_error = Some(error.to_string());
                            inner.state.active = false;
                            // Preserve config_path for diagnostics; clear uptime/measured fields.
                            reset_runtime_metadata(&mut inner.state);
                            push_log(
                                &inner.logs,
                                AndroidLogEvent {
                                    unix_ms: unix_ms(),
                                    level: "error".to_owned(),
                                    message: error.to_string(),
                                },
                            );
                        }
                    }
                    inner.task = None;
                }
                // The state mutex is poisoned, so it cannot be updated here; log through
                // `tracing` instead, which routes into `LogBuffer`'s own independent mutex
                // rather than this one, so the completion is still Kotlin-visible.
                Err(_) => {
                    tracing::error!(
                        ?result,
                        "runtime completion callback observed a poisoned state mutex; \
                         runtime state was not updated"
                    );
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
        inner.shutdown = Some(shutdown);
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
        push_log(
            &inner.logs,
            AndroidLogEvent {
                unix_ms: unix_ms(),
                level: "info".to_owned(),
                message: "runtime started".to_owned(),
            },
        );
        Ok(())
    }

    /// Stops the runtime, returning an error rather than silently succeeding when
    /// the stop was not actually clean (forced abort after the grace period, or a
    /// task join failure) — a Kotlin caller must be able to tell "stopped
    /// gracefully" apart from "gave up and forced it".
    pub fn stop(&self) -> Result<(), String> {
        match self.stop_with_grace_period(STOP_GRACE_PERIOD)? {
            StopOutcome::Graceful | StopOutcome::NotRunning => Ok(()),
            StopOutcome::ForcedAbort { grace_period } => {
                Err(format!("runtime required forced abort after {grace_period:?}"))
            }
            StopOutcome::TaskJoinFailed { reason } => {
                Err(format!("runtime task join failed: {reason}"))
            }
        }
    }

    /// Core of [`Self::stop`], parameterized on the grace period so tests can
    /// exercise the forced-abort fallback without a real multi-second wait.
    fn stop_with_grace_period(&self, grace_period: Duration) -> Result<StopOutcome, String> {
        // Take ownership of the shutdown token/task/runtime out of the mutex first,
        // so the bounded wait below never holds the lock — the daemon's own
        // completion handler (which runs when the task finishes) needs to acquire
        // this same mutex, and holding it here would deadlock that handler for the
        // entire wait.
        let (shutdown, task, runtime) = {
            let mut inner = self.inner.lock().map_err(|_| "runtime mutex poisoned".to_owned())?;
            (inner.shutdown.take(), inner.task.take(), inner.runtime.take())
        };

        if let Some(shutdown) = &shutdown {
            shutdown.request_shutdown();
        }

        let (Some(task), Some(runtime)) = (task, runtime) else {
            // Nothing was running: a duplicate/no-op stop must not overwrite
            // whatever state (including a previous forced-abort diagnostic) is
            // already there.
            return Ok(StopOutcome::NotRunning);
        };

        let abort_handle = task.abort_handle();
        // `tokio::time::timeout` must be constructed inside the runtime's async
        // context (it registers with the timer driver immediately) — wrap it in
        // an async block rather than constructing it as a `block_on` argument,
        // which would evaluate it before the runtime context is entered.
        let outcome =
            runtime.block_on(async move { tokio::time::timeout(grace_period, task).await });
        // Dropping the runtime here is safe/fast: by this point the daemon task
        // has either finished cooperatively, failed to join, or been force-aborted.
        drop(runtime);

        let stop_outcome = match outcome {
            Ok(Ok(())) => StopOutcome::Graceful,
            Ok(Err(join_error)) => {
                tracing::error!(reason = %join_error, "runtime task join failed during stop");
                StopOutcome::TaskJoinFailed { reason: join_error.to_string() }
            }
            Err(_elapsed) => {
                tracing::error!(
                    grace_period = ?grace_period,
                    "runtime did not shut down cooperatively within the grace period; \
                     forcing abort (this is not a clean stop)"
                );
                abort_handle.abort();
                StopOutcome::ForcedAbort { grace_period }
            }
        };

        let mut inner = self.inner.lock().map_err(|_| "runtime mutex poisoned".to_owned())?;
        inner.status_rx = None;
        inner.forward_config = Vec::new();
        inner.state.active = false;
        match &stop_outcome {
            StopOutcome::Graceful => {
                inner.state.state = AndroidRuntimeState::Stopped;
                inner.state.mode = None;
                // Clean stop: clear stale metadata so the UI shows no stale uptime,
                // error, config path, or session/forward state after stopping.
                inner.state.config_path = None;
                inner.state.last_error = None;
                reset_runtime_metadata(&mut inner.state);
                push_log(
                    &inner.logs,
                    AndroidLogEvent {
                        unix_ms: unix_ms(),
                        level: "info".to_owned(),
                        message: "runtime stopped".to_owned(),
                    },
                );
            }
            StopOutcome::ForcedAbort { grace_period } => {
                let message = format!("runtime required forced abort after {grace_period:?}");
                inner.state.state = AndroidRuntimeState::Error;
                inner.state.last_error = Some(message.clone());
                // Preserve config_path for diagnostics.
                reset_runtime_metadata(&mut inner.state);
                push_log(
                    &inner.logs,
                    AndroidLogEvent { unix_ms: unix_ms(), level: "error".to_owned(), message },
                );
            }
            StopOutcome::TaskJoinFailed { reason } => {
                let message = format!("runtime task join failed: {reason}");
                inner.state.state = AndroidRuntimeState::Error;
                inner.state.last_error = Some(message.clone());
                reset_runtime_metadata(&mut inner.state);
                push_log(
                    &inner.logs,
                    AndroidLogEvent { unix_ms: unix_ms(), level: "error".to_owned(), message },
                );
            }
            StopOutcome::NotRunning => unreachable!("handled by the early return above"),
        }
        Ok(stop_outcome)
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
            ice: None,
        })
    }

    pub fn recent_logs(&self, max_events: usize) -> Result<Vec<AndroidLogEvent>, String> {
        let inner = self.inner.lock().map_err(|_| "runtime mutex poisoned".to_owned())?;
        inner.logs.recent(max_events)
    }

    pub fn last_error(&self) -> Option<String> {
        match self.inner.lock() {
            Ok(inner) => inner.state.last_error.clone(),
            Err(_) => Some("runtime mutex poisoned".to_owned()),
        }
    }

    /// Record a failure that happened at the JNI/C-ABI boundary, before the controller could
    /// run (e.g. a null/invalid config path or non-UTF-8 identity). Stores only `last_error`
    /// so `last_error()` surfaces the real cause to Kotlin instead of "unknown error"; the
    /// runtime state is left untouched because nothing actually started.
    pub fn record_bridge_error(&self, message: String) -> Result<(), String> {
        let mut inner = self.inner.lock().map_err(|_| "runtime mutex poisoned".to_owned())?;
        inner.state.last_error = Some(message);
        Ok(())
    }

    /// Test-only seam: poisons the state mutex by panicking while holding the lock on
    /// another thread, so poison-path behavior (here and in the FFI layer) can be
    /// exercised deterministically.
    #[cfg(test)]
    pub(crate) fn poison_state_mutex_for_test(&self) {
        let inner = Arc::clone(&self.inner);
        let result = std::thread::spawn(move || {
            let _guard = inner.lock().expect("mutex is not yet poisoned");
            panic!("deliberately poisoning the state mutex for a test");
        })
        .join();
        assert!(result.is_err(), "spawned thread should have panicked");
    }
}

#[cfg(test)]
mod tests;
