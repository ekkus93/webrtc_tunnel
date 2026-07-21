//! The offer daemon's core run loop: startup (config/peer validation, listener
//! bind, accept-runtime spawn), the steady-state select loop (local client accept,
//! idle signaling, shutdown), and the single funnel-point teardown/finalizer every
//! exit path goes through.

use p2p_core::AppConfig;
use p2p_crypto::{AuthorizedKeys, IdentityFile};
use p2p_signaling::{MessageBody, SignalCodec};
use tokio::time::Instant;

use crate::DaemonError;
use crate::ShutdownToken;
use crate::config::*;
use crate::error::is_offer_infrastructure_failure;
use crate::messages::*;
use crate::signaling::*;
use crate::status::*;
use crate::types::*;

use super::accept::{
    bind_offer_listeners, spawn_offer_accept_loops, stop_and_join_offer_accept_runtime,
};
use super::cooldown::{self, ProbeFailureCooldown};
use super::session::{OfferSessionIo, run_offer_session};

#[cfg(any(test, debug_assertions))]
use super::test_support::OfferDaemonTestHooks;

pub(super) async fn run_offer_daemon_inner<T: DaemonSignalingTransport>(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    transport: &mut T,
    #[cfg(any(test, debug_assertions))] test_hooks: Option<OfferDaemonTestHooks>,
    #[cfg(not(any(test, debug_assertions)))] _test_hooks: Option<()>,
    status_sink: Option<tokio::sync::watch::Sender<DaemonStatus>>,
    mut shutdown: ShutdownToken,
) -> Result<(), DaemonError> {
    #[cfg(any(test, debug_assertions))]
    let OfferDaemonTestHooks {
        session_hook,
        worker_fault_hook,
        mut loop_top_barrier,
        status_audit,
        mut recovery_barrier,
    } = test_hooks.unwrap_or_default();
    validate_config_authorized_peers(&config, &authorized_keys)?;
    let codec = SignalCodec::new(
        &local_identity,
        &authorized_keys,
        config.security.max_clock_skew_secs,
        config.security.max_message_age_secs,
    );
    transport.subscribe_own_topic().await?;

    #[cfg(any(test, debug_assertions))]
    let status = match (status_sink, status_audit) {
        (Some(sink), Some(audit)) => StatusWriter::with_sink_and_audit(&config, sink, audit),
        (Some(sink), None) => StatusWriter::with_sink(&config, sink),
        (None, Some(audit)) => StatusWriter::with_audit(&config, audit),
        (None, None) => StatusWriter::new(&config),
    };
    #[cfg(not(any(test, debug_assertions)))]
    let status = match status_sink {
        Some(sink) => StatusWriter::with_sink(&config, sink),
        None => StatusWriter::new(&config),
    };
    let mut runtime = DaemonRuntimeState::new_connected_with_shutdown(shutdown.clone());
    let mut ctx = RuntimeContext { config: &config, status: &status, runtime: &mut runtime };
    write_steady_state_status(&ctx).await;

    let (listeners, forward_statuses) = bind_offer_listeners(&config).await?;
    ctx.runtime.forward_statuses = forward_statuses;
    write_steady_state_status(&ctx).await;

    // Every fallible, immutable config/peer lookup happens before the accept runtime
    // starts, so no `?` after this point can bypass the post-worker-start finalizer.
    let remote_peer_id = offer_remote_peer_id(&config)?;
    let remote = authorized_keys
        .get_by_peer_id(&remote_peer_id)
        .cloned()
        .ok_or_else(|| DaemonError::MissingAuthorizedPeer(remote_peer_id.to_string()))?;

    let (mut accept_runtime, worker_abort_handles) =
        spawn_offer_accept_loops(listeners, shutdown.clone());
    #[cfg(any(test, debug_assertions))]
    if let Some(hook) = worker_fault_hook {
        let _ = hook.send(worker_abort_handles);
    }
    #[cfg(not(any(test, debug_assertions)))]
    let _ = worker_abort_handles;
    let mut replay_cache = p2p_signaling::ReplayCache::new(config.security.replay_cache_size);
    let mut probe_cooldown = ProbeFailureCooldown::new();

    // Startup is only truthfully complete once the broker is subscribed, the remote
    // peer is authorized (both checked above), at least one listener is bound, and
    // the accept runtime has started. Only past this point may ordinary status
    // writes report a waiting/serving state.
    ctx.runtime.phase = DaemonRuntimePhase::Running;

    let run_result: Result<(), DaemonError> = async {
        loop {
            #[cfg(any(test, debug_assertions))]
            if let Some(barrier) = loop_top_barrier.as_mut() {
                barrier.enter_and_wait_for_release().await;
            }

            if shutdown.is_shutdown_requested() {
                break Ok(());
            }

            write_steady_state_status(&ctx).await;
            tokio::select! {
                biased;

                _ = shutdown.cancelled() => {
                    tracing::info!("offer daemon shutdown requested");
                    break Ok(());
                }

                exit = accept_runtime.worker_exits.recv() => {
                    let Some(exit) = exit else {
                        // All monitors already finished and dropped their exit_tx handle;
                        // only expected once shutdown has begun.
                        if shutdown.is_shutdown_requested() {
                            break Ok(());
                        }
                        break Err(DaemonError::Logging(
                            "offer accept-worker supervisor channel closed unexpectedly".to_owned(),
                        ));
                    };

                    if shutdown.is_shutdown_requested() {
                        tracing::debug!(
                            forward_id = %exit.forward_id,
                            outcome = ?exit.outcome,
                            "offer accept worker exited during shutdown"
                        );
                        continue;
                    }

                    break Err(DaemonError::OfferAcceptWorkerFailed {
                        forward_id: exit.forward_id,
                        reason: format!("{:?}", exit.outcome),
                    });
                }

                client = accept_runtime.accepted_clients.recv() => {
                    let Some(client) = client else {
                        if shutdown.is_shutdown_requested() {
                            break Ok(());
                        }
                        break Err(DaemonError::Logging(
                            "all offer accept workers stopped unexpectedly".to_owned(),
                        ));
                    };

                    let client = match client {
                        Ok(client) => client,
                        Err(error) => break Err(error.into()),
                    };

                    // Second admission gate: select readiness raced with shutdown, so
                    // re-check now that we actually hold a client.
                    if shutdown.is_shutdown_requested() {
                        drop(client);
                        break Ok(());
                    }

                    // If the data plane recently failed its probe, refuse new clients during the
                    // cooldown instead of re-running negotiate+probe (which would hot-loop).
                    if let Some(remaining) = probe_cooldown.remaining(Instant::now()) {
                        tracing::warn!(
                            remote_peer_id = %remote.peer_id,
                            cooldown_remaining = ?remaining,
                            "data-plane probe recently failed; dropping local client during cooldown",
                        );
                        drop(client);
                        continue;
                    }
                    tracing::info!("accepted local client and entering busy offer session state");
                    let result =
                        run_offer_session(
                            &config,
                            &codec,
                            transport,
                            &mut ctx,
                            OfferSessionIo {
                                client,
                                accepted_clients: &mut accept_runtime.accepted_clients,
                                worker_exits: &mut accept_runtime.worker_exits,
                                remote: &remote,
                                #[cfg(any(test, debug_assertions))]
                                session_hook: session_hook.clone(),
                            },
                            shutdown.clone(),
                        )
                        .await;
                    if shutdown.is_shutdown_requested() {
                        if let Err(error) = &result {
                            tracing::warn!(
                                reason = %error,
                                "offer session ended with error during shutdown"
                            );
                        }
                        break Ok(());
                    }

                    // Infrastructure failures (an accept worker or its monitor dying
                    // unexpectedly) are never an ordinary session outcome: a second,
                    // still-healthy forward's worker must not mask the first's death by
                    // letting cooldown/recovery treat this as recoverable session
                    // turbulence. Skip straight to daemon-fatal finalization instead.
                    match result {
                        Err(error) if is_offer_infrastructure_failure(&error) => {
                            tracing::error!(
                                reason = %error,
                                "offer runtime infrastructure failed during active session",
                            );
                            break Err(error);
                        }
                        ordinary_result => {
                            if cooldown::session_outcome_enters_cooldown(&ordinary_result) {
                                let wait = probe_cooldown.record_failure(Instant::now());
                                tracing::warn!(
                                    remote_peer_id = %remote.peer_id,
                                    cooldown = ?wait,
                                    "entering data-plane probe-failure cooldown before accepting new clients",
                                );
                            } else {
                                probe_cooldown.reset();
                            }
                            // P0-005 test-only barrier: no-op in production (field is
                            // None). The local shutdown check above (on session
                            // return) has already run for this iteration and won't
                            // run again before recover_daemon_after_session, so a test
                            // holding here and requesting shutdown isolates the
                            // central token-aware status gate as the only defense.
                            #[cfg(any(test, debug_assertions))]
                            if let Some(barrier) = recovery_barrier.as_mut() {
                                barrier.enter_and_wait_for_release().await;
                            }
                            recover_daemon_after_session(&ctx, ordinary_result).await;
                            tracing::info!("offer daemon returned to waiting state");
                        }
                    }
                }

                payload = poll_idle_signal_payload(&mut ctx, transport) => {
                    if shutdown.is_shutdown_requested() {
                        break Ok(());
                    }

                    let Some(payload) = payload else {
                        continue;
                    };

                    tracing::debug!(
                        payload_len = payload.len(),
                        role = ?config.node.role,
                        "received signaling payload while waiting for local client"
                    );

                    let decode_result =
                        decode_idle_signaling_message(&codec, &payload, &mut replay_cache);
                    let (envelope, message, sender) = match decode_result {
                        Ok(decoded) => decoded,
                        Err(error) => {
                            tracing::warn!(reason = %error, "rejecting signaling message");
                            continue;
                        }
                    };

                    tracing::debug!(
                        session_id = %message.session_id,
                        sender_peer_id = %sender.peer_id,
                        sender_kid = %envelope.sender_kid,
                        message_type = ?message.message_type,
                        role = ?config.node.role,
                        "decoded idle signaling message"
                    );

                    match &message.body {
                        MessageBody::Hello(_) => {
                            tracing::info!("received optional hello from {}", sender.peer_id);
                        }
                        _ => {
                            tracing::warn!("ignoring unexpected idle message {:?}", message.message_type);
                        }
                    }
                }
            }
        }
    }
    .await;

    // Every post-start exit — clean shutdown, an unexpected worker/channel failure,
    // or a session error observed during shutdown — funnels through this single
    // finalizer: enter Draining, stop/join the accept workers, enter Closed, and
    // always attempt the terminal status write. No `?` between here and the
    // function's return can bypass this.
    //
    // FIX7 P0-008/RESPONSES item 4: captured *before* `shutdown.request_shutdown()`
    // below unconditionally marks the token as requested. Reading the token after
    // that point could never distinguish a genuine requested shutdown from an
    // unexpected clean loop exit, defeating the invariant check in
    // `merge_offer_run_and_cleanup_results`.
    let shutdown_requested_at_loop_exit = shutdown.is_shutdown_requested();
    ctx.runtime.phase = DaemonRuntimePhase::Draining;
    shutdown.request_shutdown();

    let cleanup_result = stop_and_join_offer_accept_runtime(accept_runtime.monitors).await;

    ctx.runtime.phase = DaemonRuntimePhase::Closed;
    let closed_result = write_offer_closed_status(&mut ctx).await;

    merge_offer_run_and_cleanup_results(
        run_result,
        cleanup_result,
        closed_result,
        shutdown_requested_at_loop_exit,
    )
}

/// Preserves the primary run-loop error (if any) as the daemon's final result; a
/// monitor-cleanup failure is only surfaced when the run loop itself otherwise
/// succeeded, and a terminal-status write failure only when both the run loop and
/// cleanup otherwise succeeded. Every error not returned is still logged (not
/// silently dropped) as secondary context.
///
/// FIX7 P0-008/RESPONSES item 4: when the run loop, cleanup, and terminal status
/// write all succeed, that is only a genuine cooperative shutdown if
/// [`shutdown_requested_at_loop_exit`] is true. A clean exit with no primary error
/// and no shutdown request is an invariant violation — the loop's own `Ok(())`
/// exits are all shutdown-gated today, so this should be unreachable, but folding
/// it into success here would turn a future accidental early return or
/// worker-supervisor defect into a false clean shutdown.
pub(crate) fn merge_offer_run_and_cleanup_results(
    run_result: Result<(), DaemonError>,
    cleanup_result: Result<(), DaemonError>,
    closed_result: Result<(), DaemonError>,
    shutdown_requested_at_loop_exit: bool,
) -> Result<(), DaemonError> {
    match run_result {
        Err(primary) => {
            if let Err(error) = cleanup_result {
                tracing::error!(reason = %error, "offer accept-worker cleanup also failed");
            }
            if let Err(error) = closed_result {
                tracing::error!(reason = %error, "offer terminal status also failed");
            }
            Err(primary)
        }
        Ok(()) => match cleanup_result {
            Err(primary) => {
                if let Err(error) = closed_result {
                    tracing::error!(reason = %error, "offer terminal status also failed");
                }
                Err(primary)
            }
            Ok(()) => match closed_result {
                Err(error) => Err(error),
                Ok(()) => {
                    if shutdown_requested_at_loop_exit {
                        Ok(())
                    } else {
                        Err(DaemonError::Logging(
                            "offer daemon exited without a shutdown request".to_owned(),
                        ))
                    }
                }
            },
        },
    }
}
