use std::fs;
use std::path::PathBuf;

use p2p_core::{AppConfig, DaemonState, NodeRole, PeerId, SessionId};
use serde::Serialize;

use crate::DaemonError;

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
pub struct DaemonStatus {
    pub peer_id: PeerId,
    pub role: NodeRole,
    pub mqtt_connected: bool,
    pub active_session_id: Option<String>,
    pub current_state: DaemonState,
    pub active_session_count: usize,
    pub session_capacity: usize,
    pub sessions: Vec<SessionStatus>,
    pub configured_forwards: Vec<String>,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
pub struct SessionStatus {
    pub session_id: String,
    pub remote_peer_id: PeerId,
    pub state: DaemonState,
    pub data_channel_open: bool,
    pub configured_forward_ids: Vec<String>,
}

impl SessionStatus {
    pub fn new(
        session_id: SessionId,
        remote_peer_id: PeerId,
        state: DaemonState,
        data_channel_open: bool,
        configured_forward_ids: Vec<String>,
    ) -> Self {
        Self {
            session_id: session_id.to_string(),
            remote_peer_id,
            state,
            data_channel_open,
            configured_forward_ids,
        }
    }
}

impl DaemonStatus {
    pub fn new(
        peer_id: PeerId,
        role: NodeRole,
        mqtt_connected: bool,
        active_session_id: Option<SessionId>,
        current_state: DaemonState,
        configured_forwards: Vec<String>,
    ) -> Self {
        let sessions = active_session_id
            .map(|id| {
                vec![SessionStatus::new(
                    id,
                    peer_id.clone(),
                    current_state,
                    matches!(current_state, DaemonState::TunnelOpen),
                    configured_forwards.clone(),
                )]
            })
            .unwrap_or_default();
        Self::with_sessions(
            peer_id,
            role,
            mqtt_connected,
            current_state,
            configured_forwards,
            1,
            sessions,
        )
    }

    pub fn with_sessions(
        peer_id: PeerId,
        role: NodeRole,
        mqtt_connected: bool,
        current_state: DaemonState,
        configured_forwards: Vec<String>,
        session_capacity: usize,
        sessions: Vec<SessionStatus>,
    ) -> Self {
        let active_session_count = sessions.len();
        let active_session_id = (sessions.len() == 1).then(|| sessions[0].session_id.clone());
        Self {
            peer_id,
            role,
            mqtt_connected,
            active_session_id,
            current_state,
            active_session_count,
            session_capacity,
            sessions,
            configured_forwards,
        }
    }
}

pub struct StatusWriter {
    enabled: bool,
    path: PathBuf,
}

impl StatusWriter {
    pub fn new(config: &AppConfig) -> Self {
        Self { enabled: config.health.write_status_file, path: config.health.status_file.clone() }
    }

    pub async fn write(&self, status: DaemonStatus) -> Result<(), DaemonError> {
        if !self.enabled {
            return Ok(());
        }
        if let Some(parent) = self.path.parent() {
            fs::create_dir_all(parent)?;
        }
        let json = serde_json::to_vec_pretty(&status)
            .map_err(|error| DaemonError::Logging(error.to_string()))?;
        tokio::fs::write(&self.path, json).await?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use std::path::PathBuf;

    use p2p_core::{DaemonState, NodeRole};

    use super::{DaemonStatus, SessionStatus, StatusWriter};

    #[tokio::test]
    async fn writes_status_json_without_secrets() {
        let temp_path =
            std::env::temp_dir().join(format!("p2ptunnel-status-{}.json", std::process::id()));
        let writer = StatusWriter { enabled: true, path: temp_path.clone() };
        writer
            .write(DaemonStatus::new(
                "offer-home".parse().expect("peer id"),
                NodeRole::Offer,
                true,
                Some(p2p_core::SessionId::new([7_u8; 16])),
                DaemonState::Idle,
                vec!["ssh".to_owned(), "web-ui".to_owned()],
            ))
            .await
            .expect("status file should write");
        let content = tokio::fs::read_to_string(&temp_path).await.expect("status file should read");
        assert!(content.contains("\"peer_id\""));
        assert!(content.contains("\"configured_forwards\""));
        assert!(content.contains("\"active_session_count\""));
        assert!(content.contains("\"sessions\""));
        assert!(content.contains("\"ssh\""));
        assert!(!content.contains("private"));
        let _ = tokio::fs::remove_file(PathBuf::from(&temp_path)).await;
    }

    #[tokio::test]
    async fn writes_multi_session_status_json() {
        let temp_path = std::env::temp_dir()
            .join(format!("p2ptunnel-status-multi-{}.json", std::process::id()));
        let writer = StatusWriter { enabled: true, path: temp_path.clone() };
        writer
            .write(DaemonStatus::with_sessions(
                "answer-office".parse().expect("peer id"),
                NodeRole::Answer,
                true,
                DaemonState::Idle,
                vec!["ssh".to_owned()],
                16,
                vec![SessionStatus::new(
                    p2p_core::SessionId::new([8_u8; 16]),
                    "offer-home".parse().expect("remote peer id"),
                    DaemonState::TunnelOpen,
                    true,
                    vec!["ssh".to_owned()],
                )],
            ))
            .await
            .expect("status file should write");
        let content = tokio::fs::read_to_string(&temp_path).await.expect("status file should read");
        assert!(content.contains("\"active_session_count\": 1"));
        assert!(content.contains("\"session_capacity\": 16"));
        assert!(content.contains("\"remote_peer_id\""));
        let _ = tokio::fs::remove_file(PathBuf::from(&temp_path)).await;
    }
}
