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
#[derive(Clone)]
pub struct OfferSessionTestHandle {
    pub session_id: p2p_core::SessionId,
    pub ice_state_injector: p2p_webrtc::IceStateInjectorForTests,
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
        session_hook,
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
    #[cfg(any(test, debug_assertions))] session_hook: Option<
        mpsc::UnboundedSender<OfferSessionTestHandle>,
    >,
    #[cfg(not(any(test, debug_assertions)))] _session_hook: Option<()>,
    status_sink: Option<tokio::sync::watch::Sender<DaemonStatus>>,
    mut shutdown: ShutdownToken,
) -> Result<(), DaemonError> {
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
    let mut accept_runtime = spawn_offer_accept_loops(listeners, shutdown.clone());
    let mut replay_cache = p2p_signaling::ReplayCache::new(config.security.replay_cache_size);
    let remote_peer_id = offer_remote_peer_id(&config)?;
    let remote = authorized_keys
        .get_by_peer_id(&remote_peer_id)
        .cloned()
        .ok_or_else(|| DaemonError::MissingAuthorizedPeer(remote_peer_id.to_string()))?;
    let mut probe_cooldown = ProbeFailureCooldown::new();

    // Startup is only truthfully complete once the broker is subscribed, the remote
    // peer is authorized (both checked above), at least one listener is bound, and
    // the accept runtime has started. Only past this point may ordinary status
    // writes report a waiting/serving state.
    ctx.runtime.phase = DaemonRuntimePhase::Running;

    let run_result: Result<(), DaemonError> = async {
        loop {
            write_steady_state_status(&ctx).await;
            tokio::select! {
                biased;

                _ = shutdown.cancelled() => {
                    tracing::info!("offer daemon shutdown requested");
                    break Ok(());
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
                    if cooldown::session_outcome_enters_cooldown(&result) {
                        let wait = probe_cooldown.record_failure(Instant::now());
                        tracing::warn!(
                            remote_peer_id = %remote.peer_id,
                            cooldown = ?wait,
                            "entering data-plane probe-failure cooldown before accepting new clients",
                        );
                    } else {
                        probe_cooldown.reset();
                    }
                    recover_daemon_after_session(&ctx, result).await;
                    tracing::info!("offer daemon returned to waiting state");
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

    join_offer_accept_tasks(accept_runtime.tasks).await;

    ctx.runtime.phase = DaemonRuntimePhase::Closed;
    write_offer_closed_status(&mut ctx).await;

    run_result
}

/// Owns every offer accept-loop task handle alongside the receiver they feed, so
/// shutdown can stop and join them deterministically instead of discarding the
/// `JoinHandle`s (which made listener-port release non-deterministic).
struct OfferAcceptRuntime {
    accepted_clients: mpsc::Receiver<Result<OfferClient, p2p_tunnel::TunnelError>>,
    tasks: Vec<tokio::task::JoinHandle<()>>,
}

/// Await every offer accept-loop task and log (rather than silently discard) any
/// task that failed to join, per the spec's "no quiet listener-task loss" rule.
async fn join_offer_accept_tasks(tasks: Vec<tokio::task::JoinHandle<()>>) {
    for task in tasks {
        if let Err(error) = task.await {
            tracing::warn!(reason = %error, "offer accept task failed while stopping");
        }
    }
}

#[cfg(test)]
pub(crate) fn spawn_offer_accept_loop(
    listener: OfferListener,
) -> mpsc::Receiver<Result<OfferClient, p2p_tunnel::TunnelError>> {
    spawn_offer_accept_loops(vec![listener], ShutdownToken::new()).accepted_clients
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

fn spawn_offer_accept_loops(
    listeners: Vec<OfferListener>,
    shutdown: ShutdownToken,
) -> OfferAcceptRuntime {
    let (tx, rx) = mpsc::channel(64);
    let mut tasks = Vec::with_capacity(listeners.len());
    for listener in listeners {
        let tx = tx.clone();
        let mut task_shutdown = shutdown.clone();
        tasks.push(tokio::spawn(async move {
            loop {
                tokio::select! {
                    _ = task_shutdown.cancelled() => {
                        tracing::debug!(
                            forward_id = listener.forward_id(),
                            "offer accept loop stopping"
                        );
                        return;
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
                                Err(mpsc::error::TrySendError::Closed(_)) => return,
                                Err(mpsc::error::TrySendError::Full(Err(_))) => {}
                            },
                            Err(error) => {
                                tracing::warn!(reason = %error, "offer accept loop hit recoverable listener error");
                                tokio::select! {
                                    _ = task_shutdown.cancelled() => return,
                                    _ = sleep(DAEMON_RUNTIME_RETRY_DELAY) => {}
                                }
                            }
                        }
                    }
                }
            }
        }));
    }
    drop(tx);
    OfferAcceptRuntime { accepted_clients: rx, tasks }
}
