//! P0-001: the daemon runtime phase gate on ordinary (non-terminal) status writes.
//!
//! Proves that `write_steady_state_status`/`write_answer_registry_status` (which
//! route through the shared `write_daemon_status`/`write_answer_status` helpers)
//! stay silent whenever the daemon is not truthfully `Running`, and only resume
//! writing once it is — covering the full lifecycle from before startup completes
//! through shutdown and into the terminal `Closed` state.

use p2p_core::NodeRole;

use super::support::*;

async fn assert_status_file_absent(path: &std::path::Path) {
    assert!(
        tokio::fs::metadata(path).await.is_err(),
        "status file should not have been created while phase gate suppresses writes"
    );
}

#[tokio::test]
async fn offer_steady_state_write_is_suppressed_before_running() {
    let mut config = sample_config();
    let (path, writer) = status_writer_for_test(&mut config, "offer-phase-starting");
    let mut runtime = connected_runtime();
    runtime.phase = DaemonRuntimePhase::Starting;
    let ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };

    write_steady_state_status(&ctx).await;

    assert_status_file_absent(&path).await;
}

#[tokio::test]
async fn offer_steady_state_write_is_suppressed_while_draining_and_closed() {
    let mut config = sample_config();
    let (path, writer) = status_writer_for_test(&mut config, "offer-phase-draining");
    let mut runtime = connected_runtime();
    runtime.phase = DaemonRuntimePhase::Draining;
    let ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };
    write_steady_state_status(&ctx).await;
    assert_status_file_absent(&path).await;

    runtime.phase = DaemonRuntimePhase::Closed;
    let ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };
    write_steady_state_status(&ctx).await;
    assert_status_file_absent(&path).await;
}

#[tokio::test]
async fn offer_steady_state_write_succeeds_once_running() {
    let mut config = sample_config();
    let (path, writer) = status_writer_for_test(&mut config, "offer-phase-running");
    let mut runtime = connected_runtime();
    runtime.phase = DaemonRuntimePhase::Running;
    let ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };

    write_steady_state_status(&ctx).await;

    let status = read_status_file(&path).await;
    assert_eq!(status["current_state"], "waiting_for_local_client");
    let _ = tokio::fs::remove_file(&path).await;
}

#[tokio::test]
async fn answer_registry_write_is_suppressed_before_running() {
    let mut config = sample_config();
    config.node.role = NodeRole::Answer;
    let (path, writer) = status_writer_for_test(&mut config, "answer-phase-starting");
    let mut runtime = connected_runtime();
    runtime.phase = DaemonRuntimePhase::Starting;
    let ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };
    let sessions = std::collections::HashMap::new();

    write_answer_registry_status(&ctx, &sessions).await;

    assert_status_file_absent(&path).await;
}

#[tokio::test]
async fn answer_registry_write_is_suppressed_while_draining_and_closed() {
    let mut config = sample_config();
    config.node.role = NodeRole::Answer;
    let (path, writer) = status_writer_for_test(&mut config, "answer-phase-draining");
    let mut runtime = connected_runtime();
    runtime.phase = DaemonRuntimePhase::Draining;
    let sessions = std::collections::HashMap::new();

    let ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };
    write_answer_registry_status(&ctx, &sessions).await;
    assert_status_file_absent(&path).await;

    runtime.phase = DaemonRuntimePhase::Closed;
    let ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };
    write_answer_registry_status(&ctx, &sessions).await;
    assert_status_file_absent(&path).await;
}

#[tokio::test]
async fn answer_registry_write_succeeds_once_running() {
    let mut config = sample_config();
    config.node.role = NodeRole::Answer;
    let (path, writer) = status_writer_for_test(&mut config, "answer-phase-running");
    let mut runtime = connected_runtime();
    runtime.phase = DaemonRuntimePhase::Running;
    let sessions = std::collections::HashMap::new();
    let ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };

    write_answer_registry_status(&ctx, &sessions).await;

    let status = read_status_file(&path).await;
    assert_eq!(status["current_state"], "serving");
    let _ = tokio::fs::remove_file(&path).await;
}

#[tokio::test]
async fn transport_recovery_status_write_is_suppressed_while_draining() {
    // mark_transport_usable/unusable both route through write_daemon_status, so the
    // phase gate must suppress them too — otherwise a publish/poll result arriving
    // during drain could resurrect an ordinary runtime state right before the
    // terminal Closed write.
    let mut config = sample_config();
    let (path, writer) = status_writer_for_test(&mut config, "offer-phase-transport-drain");
    let mut runtime = connected_runtime();
    runtime.phase = DaemonRuntimePhase::Draining;
    runtime.mqtt_connected = false;
    let mut ctx = RuntimeContext { config: &config, status: &writer, runtime: &mut runtime };

    mark_transport_usable(
        &mut ctx,
        StatusSnapshot {
            active_session_id: None,
            current_state: steady_state_for_role(&NodeRole::Offer),
        },
    )
    .await;

    assert_status_file_absent(&path).await;
}
