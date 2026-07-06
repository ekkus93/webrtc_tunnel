//! Offer-role daemon: binds local listeners, dials the configured remote peer,
//! runs a single multiplexed peer session at a time, and transparently attempts
//! ICE-restart reconnects before returning to the waiting-for-local-client steady
//! state. Startup/security failures are fatal; transport turbulence is recoverable.

use p2p_core::{AppConfig, ConfigError, ForwardOfferConfig, ForwardTable};
use p2p_crypto::{AuthorizedKeys, IdentityFile};
use p2p_signaling::{MessageBody, MqttSignalingTransport, SignalCodec};
use p2p_tunnel::{OfferClient, OfferListener};
use tokio::sync::mpsc;
use tokio::time::{Instant, sleep};

use crate::DaemonError;
use crate::ShutdownToken;
use crate::config::*;
use crate::error::is_offer_infrastructure_failure;
use crate::messages::*;
use crate::signaling::*;
use crate::status::*;
use crate::types::*;

mod cooldown;
mod session;

use cooldown::ProbeFailureCooldown;

use session::{OfferSessionIo, run_offer_session};

// Session helpers the daemon unit tests reach through `super::` (via the crate-root
// cfg(test) re-export glob).
#[cfg(test)]
pub(crate) use session::{
    attempt_offer_reconnect, handle_offer_session_message,
    maybe_ack_duplicate_active_session_message, process_offer_session_payload,
};

#[cfg(any(test, debug_assertions))]
pub struct OfferSessionTestHandle {
    pub session_id: p2p_core::SessionId,
    pub ice_state_injector: p2p_webrtc::IceStateInjectorForTests,
    /// Deterministic lifecycle events (currently: reconnect/backoff transitions),
    /// so tests can observe the actual state instead of guessing it with a sleep.
    pub test_events: mpsc::UnboundedReceiver<OfferSessionTestEvent>,
}

/// Deterministic offer-session lifecycle events, observed by tests instead of a
/// timing sleep.
#[cfg(any(test, debug_assertions))]
#[derive(Clone, Debug)]
pub enum OfferSessionTestEvent {
    ReconnectBackoffStarted { session_id: p2p_core::SessionId, delay: std::time::Duration },
}

/// Bundles the offer daemon's test-only observation hooks so `run_offer_daemon_inner`
/// stays under Clippy's argument-count lint as test seams accumulate.
#[cfg(any(test, debug_assertions))]
#[derive(Default)]
struct OfferDaemonTestHooks {
    session_hook: Option<mpsc::UnboundedSender<OfferSessionTestHandle>>,
    worker_fault_hook: Option<mpsc::UnboundedSender<Vec<tokio::task::AbortHandle>>>,
    /// Fires at the very top of the run loop, before the P0-005 shutdown gate and
    /// the ordinary steady-state write, every iteration (not just once) — an
    /// ordinary session outcome can bring the loop back to top more than once
    /// before a test gets a chance to land shutdown in the gap.
    loop_top_barrier: Option<OfferLoopTopBarrier>,
}

/// A repeatable rendezvous at the top of the offer run loop (see
/// [`OfferDaemonTestHooks::loop_top_barrier`]), letting a test force
/// `shutdown.request_shutdown()` to land in the exact window between an ordinary
/// session outcome bringing the loop back to its top and the next steady-state
/// write, instead of racing real scheduler timing (P0-005/P0-010). A broken
/// channel on either side is a test-harness bug, not something to continue past
/// silently — see P1-004.
#[cfg(any(test, debug_assertions))]
pub struct OfferLoopTopBarrier {
    entered_tx: mpsc::Sender<()>,
    release_rx: mpsc::Receiver<()>,
}

#[cfg(any(test, debug_assertions))]
impl OfferLoopTopBarrier {
    pub fn new() -> (Self, OfferLoopTopBarrierEntered, OfferLoopTopBarrierRelease) {
        let (entered_tx, entered_rx) = mpsc::channel(1);
        let (release_tx, release_rx) = mpsc::channel(1);
        (
            Self { entered_tx, release_rx },
            OfferLoopTopBarrierEntered { entered_rx },
            OfferLoopTopBarrierRelease { release_tx },
        )
    }

    async fn enter_and_wait_for_release(&mut self) {
        self.entered_tx.send(()).await.expect("offer loop-top barrier observer must remain alive");
        self.release_rx
            .recv()
            .await
            .expect("offer loop-top barrier release sender must remain alive");
    }
}

#[cfg(any(test, debug_assertions))]
pub struct OfferLoopTopBarrierEntered {
    entered_rx: mpsc::Receiver<()>,
}

#[cfg(any(test, debug_assertions))]
impl OfferLoopTopBarrierEntered {
    pub async fn wait(&mut self) {
        self.entered_rx
            .recv()
            .await
            .expect("offer loop-top barrier must not be dropped before entering");
    }
}

#[cfg(any(test, debug_assertions))]
pub struct OfferLoopTopBarrierRelease {
    release_tx: mpsc::Sender<()>,
}

#[cfg(any(test, debug_assertions))]
impl OfferLoopTopBarrierRelease {
    pub async fn release(&self) {
        self.release_tx.send(()).await.expect("offer loop-top barrier observer must remain alive");
    }
}

pub async fn run_offer_daemon(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
) -> Result<(), DaemonError> {
    run_offer_daemon_with_shutdown(config, local_identity, authorized_keys, ShutdownToken::new())
        .await
}

pub async fn run_offer_daemon_with_shutdown(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    shutdown: ShutdownToken,
) -> Result<(), DaemonError> {
    let transport = MqttSignalingTransport::connect(&config)?;
    run_offer_daemon_with_transport_and_shutdown(
        config,
        local_identity,
        authorized_keys,
        transport,
        shutdown,
    )
    .await
}

pub async fn run_offer_daemon_with_transport<T: DaemonSignalingTransport>(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    transport: T,
) -> Result<(), DaemonError> {
    run_offer_daemon_with_transport_and_shutdown(
        config,
        local_identity,
        authorized_keys,
        transport,
        ShutdownToken::new(),
    )
    .await
}

pub async fn run_offer_daemon_with_transport_and_shutdown<T: DaemonSignalingTransport>(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    transport: T,
    shutdown: ShutdownToken,
) -> Result<(), DaemonError> {
    #[cfg(any(test, debug_assertions))]
    {
        run_offer_daemon_with_transport_and_test_hook_and_shutdown(
            config,
            local_identity,
            authorized_keys,
            transport,
            None,
            shutdown,
        )
        .await
    }

    #[cfg(not(any(test, debug_assertions)))]
    {
        let mut transport = transport;
        run_offer_daemon_inner(
            config,
            local_identity,
            authorized_keys,
            &mut transport,
            None,
            None,
            shutdown,
        )
        .await
    }
}

/// Offer daemon entry point that streams live `DaemonStatus` to `status_sink` in
/// addition to the usual status-file behavior. Used by the Android runtime so the
/// UI reflects real daemon/connection state. Behaves identically to
/// [`run_offer_daemon`] otherwise.
pub async fn run_offer_daemon_with_status(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    status_sink: tokio::sync::watch::Sender<DaemonStatus>,
) -> Result<(), DaemonError> {
    run_offer_daemon_with_status_and_shutdown(
        config,
        local_identity,
        authorized_keys,
        status_sink,
        ShutdownToken::new(),
    )
    .await
}

pub async fn run_offer_daemon_with_status_and_shutdown(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    status_sink: tokio::sync::watch::Sender<DaemonStatus>,
    shutdown: ShutdownToken,
) -> Result<(), DaemonError> {
    let mut transport = MqttSignalingTransport::connect(&config)?;
    run_offer_daemon_inner(
        config,
        local_identity,
        authorized_keys,
        &mut transport,
        None,
        Some(status_sink),
        shutdown,
    )
    .await
}

#[cfg(any(test, debug_assertions))]
pub async fn run_offer_daemon_with_transport_and_test_hook<T: DaemonSignalingTransport>(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    transport: T,
    session_hook: Option<mpsc::UnboundedSender<OfferSessionTestHandle>>,
) -> Result<(), DaemonError> {
    run_offer_daemon_with_transport_and_test_hook_and_shutdown(
        config,
        local_identity,
        authorized_keys,
        transport,
        session_hook,
        ShutdownToken::new(),
    )
    .await
}

/// Combines the session-hook test seam with shutdown cancellation, so lifecycle
/// tests can deterministically observe in-progress session/reconnect state (via
/// `session_hook`) and then trigger shutdown at that observed moment, rather than
/// racing real-time sleeps against the two-node harness.
#[cfg(any(test, debug_assertions))]
pub async fn run_offer_daemon_with_transport_and_test_hook_and_shutdown<
    T: DaemonSignalingTransport,
>(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    mut transport: T,
    session_hook: Option<mpsc::UnboundedSender<OfferSessionTestHandle>>,
    shutdown: ShutdownToken,
) -> Result<(), DaemonError> {
    run_offer_daemon_inner(
        config,
        local_identity,
        authorized_keys,
        &mut transport,
        Some(OfferDaemonTestHooks {
            session_hook,
            worker_fault_hook: None,
            loop_top_barrier: None,
        }),
        None,
        shutdown,
    )
    .await
}

/// Combines a test transport with the [`OfferLoopTopBarrier`] test hook and the
/// live `DaemonStatus` sink, so a test can deterministically force
/// `shutdown.request_shutdown()` to land in the exact window between an ordinary
/// session outcome returning the run loop to its top and the next steady-state
/// write, while observing every status transition to prove none escapes
/// (P0-005/P0-010).
#[cfg(any(test, debug_assertions))]
pub async fn run_offer_daemon_with_loop_top_barrier_and_shutdown<T: DaemonSignalingTransport>(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    mut transport: T,
    loop_top_barrier: OfferLoopTopBarrier,
    status_sink: tokio::sync::watch::Sender<DaemonStatus>,
    shutdown: ShutdownToken,
) -> Result<(), DaemonError> {
    run_offer_daemon_inner(
        config,
        local_identity,
        authorized_keys,
        &mut transport,
        Some(OfferDaemonTestHooks {
            session_hook: None,
            worker_fault_hook: None,
            loop_top_barrier: Some(loop_top_barrier),
        }),
        Some(status_sink),
        shutdown,
    )
    .await
}

/// Combines a test transport with the live `DaemonStatus` sink (see
/// [`run_offer_daemon_with_status_and_shutdown`]), so a lifecycle test can observe
/// every status transition — not just periodic file-poll samples — against an
/// in-memory transport instead of a real broker connection (P0-010).
#[cfg(any(test, debug_assertions))]
pub async fn run_offer_daemon_with_transport_and_status_and_shutdown<
    T: DaemonSignalingTransport,
>(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    mut transport: T,
    status_sink: tokio::sync::watch::Sender<DaemonStatus>,
    shutdown: ShutdownToken,
) -> Result<(), DaemonError> {
    run_offer_daemon_inner(
        config,
        local_identity,
        authorized_keys,
        &mut transport,
        None,
        Some(status_sink),
        shutdown,
    )
    .await
}

/// Like [`run_offer_daemon_with_transport_and_test_hook_and_shutdown`], but also
/// hands back the accept-worker `AbortHandle`s (via `worker_fault_hook`) once the
/// accept runtime has started, so a lifecycle test can deterministically force one
/// worker to fail — during idle waiting or mid-session — and observe that the
/// daemon treats it as fatal (see P0-003/P0-016).
#[cfg(any(test, debug_assertions))]
pub async fn run_offer_daemon_with_worker_fault_hook_and_shutdown<T: DaemonSignalingTransport>(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    mut transport: T,
    worker_fault_hook: mpsc::UnboundedSender<Vec<tokio::task::AbortHandle>>,
    shutdown: ShutdownToken,
) -> Result<(), DaemonError> {
    run_offer_daemon_inner(
        config,
        local_identity,
        authorized_keys,
        &mut transport,
        Some(OfferDaemonTestHooks {
            session_hook: None,
            worker_fault_hook: Some(worker_fault_hook),
            loop_top_barrier: None,
        }),
        None,
        shutdown,
    )
    .await
}

async fn run_offer_daemon_inner<T: DaemonSignalingTransport>(
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
    let OfferDaemonTestHooks { session_hook, worker_fault_hook, mut loop_top_barrier } =
        test_hooks.unwrap_or_default();
    validate_config_authorized_peers(&config, &authorized_keys)?;
    let codec = SignalCodec::new(
        &local_identity,
        &authorized_keys,
        config.security.max_clock_skew_secs,
        config.security.max_message_age_secs,
    );
    transport.subscribe_own_topic().await?;

    let status = match status_sink {
        Some(sink) => StatusWriter::with_sink(&config, sink),
        None => StatusWriter::new(&config),
    };
    let mut runtime = DaemonRuntimeState::new_connected();
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
    ctx.runtime.phase = DaemonRuntimePhase::Draining;
    shutdown.request_shutdown();

    let cleanup_result = stop_and_join_offer_accept_runtime(accept_runtime.monitors).await;

    ctx.runtime.phase = DaemonRuntimePhase::Closed;
    let closed_result = write_offer_closed_status(&mut ctx).await;

    merge_offer_run_and_cleanup_results(run_result, cleanup_result, closed_result)
}

/// Preserves the primary run-loop error (if any) as the daemon's final result; a
/// monitor-cleanup failure is only surfaced when the run loop itself otherwise
/// succeeded, and a terminal-status write failure only when both the run loop and
/// cleanup otherwise succeeded. Every error not returned is still logged (not
/// silently dropped) as secondary context.
fn merge_offer_run_and_cleanup_results(
    run_result: Result<(), DaemonError>,
    cleanup_result: Result<(), DaemonError>,
    closed_result: Result<(), DaemonError>,
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
            Ok(()) => closed_result,
        },
    }
}

/// Owns every offer accept-loop task handle alongside the receiver they feed, so
/// shutdown can stop and join them deterministically instead of discarding the
/// `JoinHandle`s (which made listener-port release non-deterministic).
struct OfferAcceptRuntime {
    accepted_clients: mpsc::Receiver<Result<OfferClient, p2p_tunnel::TunnelError>>,
    /// Independently observed accept-worker completion, fed by `monitors` below.
    /// After successful bind/start, an unexpected exit here (panic or unexpected
    /// return while the daemon has not requested shutdown) is daemon-fatal: the
    /// worker holding a bound listener died silently, but nothing else would ever
    /// notice, leaving status falsely `Listening`/`WaitingForLocalClient`.
    worker_exits: mpsc::UnboundedReceiver<OfferAcceptTaskExit>,
    monitors: Vec<OfferAcceptMonitor>,
}

/// One accept-loop task's monitor, identified by forward id so an unexpected join
/// failure during cleanup can name which forward's worker it was.
struct OfferAcceptMonitor {
    forward_id: String,
    handle: tokio::task::JoinHandle<()>,
}

/// Why an offer accept-loop task returned normally (i.e. did not panic).
#[derive(Debug)]
pub(crate) enum OfferAcceptLoopExitReason {
    /// Cooperative shutdown was observed; expected during daemon teardown.
    Shutdown,
    /// The outbound client queue's receiver was dropped; not expected while the
    /// daemon owns `accepted_clients` for the runtime's lifetime.
    ClientQueueClosed,
}

/// One accept-loop task's completion, independently observed by its monitor task
/// rather than self-reported, so a panic is never silently invisible.
#[derive(Debug)]
pub(crate) struct OfferAcceptTaskExit {
    pub(crate) forward_id: String,
    pub(crate) outcome: Result<OfferAcceptLoopExitReason, String>,
}

/// Await every offer accept-loop monitor task. An unexpected `JoinError` (panic —
/// the monitor tasks themselves never return early any other way) is a cleanup
/// error, not a warning-and-succeed: silently swallowing it would mean a lost
/// listener task is never actually reported anywhere. The first such failure is
/// returned; any further ones are logged as secondary context.
async fn stop_and_join_offer_accept_runtime(
    monitors: Vec<OfferAcceptMonitor>,
) -> Result<(), DaemonError> {
    let mut primary_cleanup_error: Option<DaemonError> = None;

    for monitor in monitors {
        if let Err(error) = monitor.handle.await {
            let failure = DaemonError::OfferAcceptMonitorJoinFailed {
                forward_id: monitor.forward_id,
                reason: error.to_string(),
            };

            if primary_cleanup_error.is_none() {
                primary_cleanup_error = Some(failure);
            } else {
                tracing::error!(reason = %failure, "additional offer monitor cleanup failure");
            }
        }
    }

    match primary_cleanup_error {
        Some(error) => Err(error),
        None => Ok(()),
    }
}

#[cfg(test)]
pub(crate) fn spawn_offer_accept_loop(
    listener: OfferListener,
) -> mpsc::Receiver<Result<OfferClient, p2p_tunnel::TunnelError>> {
    spawn_offer_accept_loops(vec![listener], ShutdownToken::new()).0.accepted_clients
}

/// Bind a local TCP listener for each configured offer forward. Individual forwards
/// that fail to bind are recorded as `Error` (soft-fail) so one bad forward does not
/// take down the others; the per-forward outcomes are returned alongside the bound
/// listeners. It is still a daemon-level error if forwards are configured but none
/// could bind.
pub(crate) async fn bind_offer_listeners(
    config: &AppConfig,
) -> Result<(Vec<OfferListener>, Vec<ForwardRuntimeStatus>), DaemonError> {
    let table = ForwardTable::new(&config.forwards);
    let mut listeners = Vec::new();
    let mut statuses = Vec::new();
    for bind in table.offer_listeners().map_err(|error| {
        DaemonError::Config(ConfigError::InvalidConfig(format!(
            "invalid offer forward listeners: {error:?}"
        )))
    })? {
        let forward_id = bind.forward_id.to_string();
        let offer =
            ForwardOfferConfig { listen_host: bind.listen_host, listen_port: bind.listen_port };
        match OfferListener::bind(bind.forward_id, &offer).await {
            Ok(listener) => {
                tracing::info!(
                    forward_id = listener.forward_id(),
                    local_addr = %listener.local_addr()?,
                    "listening for local forward clients"
                );
                statuses.push(ForwardRuntimeStatus::listening(forward_id));
                listeners.push(listener);
            }
            Err(error) => {
                tracing::warn!(
                    forward_id = %forward_id,
                    reason = %error,
                    "failed to bind local forward listener; marking forward as error"
                );
                statuses.push(ForwardRuntimeStatus::error(forward_id, error.to_string()));
            }
        }
    }
    if !statuses.is_empty() && listeners.is_empty() {
        return Err(DaemonError::Config(ConfigError::InvalidConfig(
            "no offer forward listeners could be bound".to_owned(),
        )));
    }
    Ok((listeners, statuses))
}

/// One accept listener's loop: forwards accepted clients into `tx`, retrying past
/// recoverable listener errors, until shutdown is observed or the receiving end of
/// `tx` disappears. Returns (rather than silently exits) so its monitor task can
/// report completion independently — see [`OfferAcceptRuntime::worker_exits`].
async fn run_offer_accept_loop(
    listener: OfferListener,
    tx: mpsc::Sender<Result<OfferClient, p2p_tunnel::TunnelError>>,
    mut shutdown: ShutdownToken,
) -> OfferAcceptLoopExitReason {
    loop {
        tokio::select! {
            _ = shutdown.cancelled() => {
                tracing::debug!(
                    forward_id = listener.forward_id(),
                    "offer accept loop stopping"
                );
                return OfferAcceptLoopExitReason::Shutdown;
            }
            accepted = listener.accept_client() => {
                match accepted {
                    Ok(accepted) => match tx.try_send(Ok(accepted)) {
                        Ok(()) => {}
                        Err(mpsc::error::TrySendError::Full(Ok(dropped))) => {
                            tracing::warn!(
                                forward_id = dropped.forward_id(),
                                "offer pending client queue is full; closing local client"
                            );
                        }
                        Err(mpsc::error::TrySendError::Closed(_)) => {
                            return OfferAcceptLoopExitReason::ClientQueueClosed;
                        }
                        Err(mpsc::error::TrySendError::Full(Err(_))) => {}
                    },
                    Err(error) => {
                        tracing::warn!(reason = %error, "offer accept loop hit recoverable listener error");
                        tokio::select! {
                            _ = shutdown.cancelled() => return OfferAcceptLoopExitReason::Shutdown,
                            _ = sleep(DAEMON_RUNTIME_RETRY_DELAY) => {}
                        }
                    }
                }
            }
        }
    }
}

/// Spawns the accept-loop workers and their independent completion monitors.
/// Also returns each worker's [`tokio::task::AbortHandle`] (decoupled from the
/// `JoinHandle` the monitor awaits) so `#[cfg(any(test, debug_assertions))]` test
/// hooks can deterministically force one worker to fail — aborting a task and a
/// genuine panic are indistinguishable to the monitor, both surface as `Err` on
/// `worker.await`, so this exercises the exact same fatal-supervision path.
fn spawn_offer_accept_loops(
    listeners: Vec<OfferListener>,
    shutdown: ShutdownToken,
) -> (OfferAcceptRuntime, Vec<tokio::task::AbortHandle>) {
    let (tx, rx) = mpsc::channel(64);
    let (exit_tx, exit_rx) = mpsc::unbounded_channel();
    let mut monitors = Vec::with_capacity(listeners.len());
    let mut abort_handles = Vec::with_capacity(listeners.len());
    for listener in listeners {
        let forward_id = listener.forward_id().to_owned();
        let monitor_forward_id = forward_id.clone();
        let tx = tx.clone();
        let task_shutdown = shutdown.clone();
        let exit_tx = exit_tx.clone();
        let worker = tokio::spawn(run_offer_accept_loop(listener, tx, task_shutdown));
        abort_handles.push(worker.abort_handle());
        let handle = tokio::spawn(async move {
            let outcome = match worker.await {
                Ok(reason) => Ok(reason),
                Err(error) => Err(error.to_string()),
            };
            if exit_tx
                .send(OfferAcceptTaskExit { forward_id: forward_id.clone(), outcome })
                .is_err()
            {
                tracing::error!(
                    forward_id = %forward_id,
                    "offer accept worker exit could not be delivered to supervisor",
                );
            }
        });
        monitors.push(OfferAcceptMonitor { forward_id: monitor_forward_id, handle });
    }
    drop(tx);
    drop(exit_tx);
    (OfferAcceptRuntime { accepted_clients: rx, worker_exits: exit_rx, monitors }, abort_handles)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn stop_and_join_reports_ok_when_every_monitor_joins_cleanly() {
        let monitors = vec![
            OfferAcceptMonitor { forward_id: "a".to_owned(), handle: tokio::spawn(async {}) },
            OfferAcceptMonitor { forward_id: "b".to_owned(), handle: tokio::spawn(async {}) },
        ];

        stop_and_join_offer_accept_runtime(monitors)
            .await
            .expect("every monitor joining cleanly must not be an error");
    }

    #[tokio::test]
    async fn stop_and_join_returns_monitor_join_failure_instead_of_warning_and_success() {
        let panicking = tokio::spawn(async { panic!("simulated monitor panic") });
        // Give the panic a chance to actually land before we join it, so this isn't
        // relying on join() itself racing the panic.
        while !panicking.is_finished() {
            tokio::task::yield_now().await;
        }
        let monitors = vec![OfferAcceptMonitor { forward_id: "ssh".to_owned(), handle: panicking }];

        let result = stop_and_join_offer_accept_runtime(monitors).await;
        match result {
            Err(DaemonError::OfferAcceptMonitorJoinFailed { forward_id, reason }) => {
                assert_eq!(forward_id, "ssh");
                assert!(reason.contains("simulated monitor panic"), "reason was: {reason}");
            }
            other => panic!(
                "a panicked monitor must surface as OfferAcceptMonitorJoinFailed, not a \
                 warning-and-success, got {other:?}"
            ),
        }
    }

    #[tokio::test]
    async fn stop_and_join_reports_the_first_failure_and_still_joins_the_rest() {
        let panicking = tokio::spawn(async { panic!("first monitor panic") });
        while !panicking.is_finished() {
            tokio::task::yield_now().await;
        }
        let also_panicking = tokio::spawn(async { panic!("second monitor panic") });
        while !also_panicking.is_finished() {
            tokio::task::yield_now().await;
        }
        let monitors = vec![
            OfferAcceptMonitor { forward_id: "first".to_owned(), handle: panicking },
            OfferAcceptMonitor { forward_id: "second".to_owned(), handle: also_panicking },
        ];

        let result = stop_and_join_offer_accept_runtime(monitors).await;
        match result {
            Err(DaemonError::OfferAcceptMonitorJoinFailed { forward_id, .. }) => {
                assert_eq!(forward_id, "first", "the first failure encountered must be primary");
            }
            other => panic!("expected the first monitor's join failure, got {other:?}"),
        }
    }
}
