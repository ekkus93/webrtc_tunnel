//! Status-file polling and predicates: wait until a daemon's JSON status file
//! satisfies a condition (state, session count, peer presence, mqtt connectivity),
//! plus the predicate builders and a schema-consistency assertion.

use std::path::Path;
use std::time::Duration;

use tokio::time::sleep;

use super::transport::{TransportFaultControl, TransportTrace};

/// Reads and parses a status file directly, with no polling/waiting. Intended for
/// asserting terminal state right after a daemon task has already been joined (so
/// the file is known to be in its final form), not for observing in-flight state.
pub(crate) async fn read_status_file(path: &Path) -> serde_json::Value {
    let content = tokio::fs::read_to_string(path).await.expect("status file should exist");
    serde_json::from_str(&content).expect("valid status json")
}

pub(crate) async fn wait_for_status(path: &Path, expected_state: &str) -> serde_json::Value {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(10);
    loop {
        if let Ok(content) = tokio::fs::read_to_string(path).await {
            if let Ok(json) = serde_json::from_str::<serde_json::Value>(&content) {
                if json["current_state"] == expected_state {
                    return json;
                }
            }
        }
        assert!(
            tokio::time::Instant::now() < deadline,
            "status {expected_state} not observed in time"
        );
        sleep(Duration::from_millis(50)).await;
    }
}

pub(crate) async fn wait_for_session_count(
    path: &Path,
    expected_count: usize,
) -> serde_json::Value {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(10);
    loop {
        if let Ok(content) = tokio::fs::read_to_string(path).await {
            if let Ok(json) = serde_json::from_str::<serde_json::Value>(&content) {
                if json["active_session_count"] == expected_count {
                    return json;
                }
            }
        }
        assert!(
            tokio::time::Instant::now() < deadline,
            "active_session_count {expected_count} not observed in time"
        );
        sleep(Duration::from_millis(50)).await;
    }
}

pub(crate) async fn wait_for_status_matching(
    path: &Path,
    description: &str,
    predicate: impl Fn(&serde_json::Value) -> bool,
) -> serde_json::Value {
    wait_for_status_matching_with_timeout(path, description, predicate, Duration::from_secs(10))
        .await
}

pub(crate) async fn wait_for_status_matching_with_timeout(
    path: &Path,
    description: &str,
    predicate: impl Fn(&serde_json::Value) -> bool,
    timeout_duration: Duration,
) -> serde_json::Value {
    let deadline = tokio::time::Instant::now() + timeout_duration;
    loop {
        if let Ok(content) = tokio::fs::read_to_string(path).await
            && let Ok(json) = serde_json::from_str::<serde_json::Value>(&content)
            && predicate(&json)
        {
            return json;
        }
        assert!(
            tokio::time::Instant::now() < deadline,
            "status condition {description} not observed in time"
        );
        sleep(Duration::from_millis(50)).await;
    }
}

pub(crate) async fn wait_for_mqtt_disconnected_after_poll_failure(
    control: &TransportFaultControl,
    peer_id: &str,
    path: &Path,
    description: &str,
    timeout_duration: Duration,
) -> serde_json::Value {
    let deadline = tokio::time::Instant::now() + timeout_duration;
    loop {
        control.inject_poll_failure(peer_id);
        if let Ok(content) = tokio::fs::read_to_string(path).await
            && let Ok(json) = serde_json::from_str::<serde_json::Value>(&content)
            && mqtt_connected_is(false)(&json)
        {
            return json;
        }
        assert!(
            tokio::time::Instant::now() < deadline,
            "status condition {description} not observed in time"
        );
        sleep(Duration::from_millis(100)).await;
    }
}

pub(crate) async fn wait_for_failed_publish_attempt(
    trace: &TransportTrace,
    from_peer_id: &str,
    to_peer_id: &str,
) {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(10);
    loop {
        if trace.attempts().iter().any(|attempt| {
            attempt.from_peer_id == from_peer_id
                && attempt.to_peer_id == to_peer_id
                && !attempt.delivered
        }) {
            return;
        }
        assert!(
            tokio::time::Instant::now() < deadline,
            "failed publish attempt from {from_peer_id} to {to_peer_id} not observed in time"
        );
        sleep(Duration::from_millis(50)).await;
    }
}

pub(crate) fn session_count_is(expected_count: usize) -> impl Fn(&serde_json::Value) -> bool {
    move |status| status["active_session_count"] == expected_count
}

pub(crate) fn mqtt_connected_is(expected: bool) -> impl Fn(&serde_json::Value) -> bool {
    move |status| status["mqtt_connected"] == expected
}

pub(crate) fn has_remote_peer(remote_peer_id: &'static str) -> impl Fn(&serde_json::Value) -> bool {
    move |status| {
        status["sessions"].as_array().is_some_and(|sessions| {
            sessions.iter().any(|session| session["remote_peer_id"] == remote_peer_id)
        })
    }
}

pub(crate) fn lacks_remote_peer(
    remote_peer_id: &'static str,
) -> impl Fn(&serde_json::Value) -> bool {
    move |status| {
        status["sessions"].as_array().is_some_and(|sessions| {
            !sessions.iter().any(|session| session["remote_peer_id"] == remote_peer_id)
        })
    }
}

pub(crate) fn current_state_is(
    expected_state: &'static str,
) -> impl Fn(&serde_json::Value) -> bool {
    move |status| status["current_state"] == expected_state
}

pub(crate) fn configured_forwards_include(
    expected_forward_id: &'static str,
) -> impl Fn(&serde_json::Value) -> bool {
    move |status| {
        status["configured_forwards"]
            .as_array()
            .is_some_and(|forwards| forwards.iter().any(|forward| forward == expected_forward_id))
    }
}

pub(crate) fn assert_status_schema_is_consistent(status: &serde_json::Value) {
    let sessions = status["sessions"].as_array().expect("sessions should be an array");
    assert_eq!(status["active_session_count"], sessions.len());
    assert!(
        status.get("active_stream_count").is_none(),
        "status must not expose misleading active_stream_count"
    );
    assert!(
        status.get("open_forward_ids").is_none(),
        "status must not expose misleading open_forward_ids"
    );
    assert!(matches!(
        status["current_state"].as_str(),
        Some(
            "idle"
                | "listening"
                | "connecting_signaling"
                | "connecting_webrtc"
                | "connecting_data_channel"
                | "tunnel_open"
                | "serving"
                | "failed"
                | "closed"
        )
    ));
}
