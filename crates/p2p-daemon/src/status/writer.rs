//! [`StatusWriter`]: broadcasts/persists [`DaemonStatus`] updates to an optional
//! `watch` sink, an optional test/debug audit log, and (if enabled) the status file.

use p2p_core::AppConfig;

use crate::DaemonError;

use super::atomic::write_atomic;
use super::types::DaemonStatus;
#[cfg(any(test, debug_assertions))]
use super::types::StatusAuditLog;

pub struct StatusWriter {
    pub(super) enabled: bool,
    pub(super) path: std::path::PathBuf,
    /// Optional latest-value sink. When present, every `DaemonStatus` is broadcast
    /// here in addition to (or instead of) being written to the status file. This
    /// is how the Android runtime observes real daemon status; the desktop CLI
    /// leaves it `None` and is unaffected.
    pub(super) sink: Option<tokio::sync::watch::Sender<DaemonStatus>>,
    /// Optional non-coalescing test/debug audit recorder — see [`StatusAuditLog`].
    /// Always `None` in ordinary production use.
    #[cfg(any(test, debug_assertions))]
    pub(super) audit: Option<StatusAuditLog>,
}

impl StatusWriter {
    pub fn new(config: &AppConfig) -> Self {
        Self {
            enabled: config.health.write_status_file,
            path: config.health.status_file.clone(),
            sink: None,
            #[cfg(any(test, debug_assertions))]
            audit: None,
        }
    }

    pub fn with_sink(config: &AppConfig, sink: tokio::sync::watch::Sender<DaemonStatus>) -> Self {
        Self {
            enabled: config.health.write_status_file,
            path: config.health.status_file.clone(),
            sink: Some(sink),
            #[cfg(any(test, debug_assertions))]
            audit: None,
        }
    }

    /// Test/debug-only: records every write attempt to `audit` in addition to the
    /// usual file/sink behavior. See [`StatusAuditLog`].
    #[cfg(any(test, debug_assertions))]
    pub fn with_audit(config: &AppConfig, audit: StatusAuditLog) -> Self {
        Self {
            enabled: config.health.write_status_file,
            path: config.health.status_file.clone(),
            sink: None,
            audit: Some(audit),
        }
    }

    /// Test/debug-only: both a latest-state `watch` sink and a non-coalescing
    /// audit recorder, for tests that need to assert on both.
    #[cfg(any(test, debug_assertions))]
    pub fn with_sink_and_audit(
        config: &AppConfig,
        sink: tokio::sync::watch::Sender<DaemonStatus>,
        audit: StatusAuditLog,
    ) -> Self {
        Self {
            enabled: config.health.write_status_file,
            path: config.health.status_file.clone(),
            sink: Some(sink),
            audit: Some(audit),
        }
    }

    pub async fn write(&self, status: DaemonStatus) -> Result<(), DaemonError> {
        // Record every attempted write before anything else, so the audit log is
        // a complete history regardless of whether the sink/file steps below
        // succeed, get skipped (disabled), or coalesce.
        #[cfg(any(test, debug_assertions))]
        if let Some(audit) = &self.audit {
            audit.record(status.clone());
        }
        // Broadcast to the sink first so observers see updates even when status-file
        // writing is disabled. A closed receiver is not an error for the daemon.
        if let Some(sink) = &self.sink {
            let _ = sink.send(status.clone());
        }
        if !self.enabled {
            return Ok(());
        }
        let json = serde_json::to_vec_pretty(&status)
            .map_err(|error| DaemonError::Logging(error.to_string()))?;
        write_atomic(&self.path, &json).await?;
        Ok(())
    }
}
