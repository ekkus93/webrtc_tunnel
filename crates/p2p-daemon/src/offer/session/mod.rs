//! Offer-side peer session: dials the remote with an SDP offer and runs the single
//! multiplexed bridge loop (data channel, ICE, signaling, ack retries) for the
//! accepted local client. Inbound session-frame dispatch lives in [`inbound`] and
//! the transparent ICE-restart/renegotiate reconnect path in [`reconnect`]; this
//! module wires them together and drives the per-session select loop.

use std::future::Future;
use std::pin::Pin;
use std::time::Duration;

use p2p_core::{AppConfig, DaemonState, FailureCode, SessionId};
use p2p_crypto::AuthorizedKey;
use p2p_signaling::{CloseBody, InnerMessageBuilder, MessageBody, OfferBody, SignalCodec};
use p2p_tunnel::OfferClient;
use p2p_webrtc::{IceConnectionState, WebRtcPeer};
use tokio::sync::mpsc;
use tokio::time::{Instant, interval, sleep_until};

use crate::DaemonError;
use crate::ShutdownToken;
use crate::messages::*;
use crate::predicates::*;
use crate::signaling::*;
use crate::types::*;

mod inbound;
mod reconnect;

#[cfg(not(test))]
use reconnect::attempt_offer_reconnect;
// Under test, re-export (and still use internally) so the reconnect orchestration can be
// unit-tested for its disabled / attempt-exhaustion branches via the crate-root glob.
#[cfg(test)]
pub(crate) use reconnect::attempt_offer_reconnect;

/// Bound on how long an offer session may wait for the WebRTC data channel to open
/// for the *first* time (after the local client is accepted and the offer/answer +
/// ICE handshake run). If the channel never opens — observed on Android where the
/// SCTP data plane stalls after DCEP — the session would otherwise wait forever,
/// holding the single local-client slot and leaving the daemon "tunnel-ish" but
/// serving nothing. On timeout the session tears down and the daemon returns to its
/// listening/serving steady state. Healthy negotiation completes in a few seconds, so
/// this generous bound never trips a working flow; it only rescues a wedged one.
#[cfg(not(test))]
const FIRST_DATA_CHANNEL_OPEN_TIMEOUT: Duration = Duration::from_secs(30);
#[cfg(test)]
const FIRST_DATA_CHANNEL_OPEN_TIMEOUT: Duration = Duration::from_millis(250);

// Used by `run_offer_session` below and re-exported for the daemon unit tests.
pub(crate) use inbound::process_offer_session_payload;

// Session helpers the daemon unit tests reach through `offer::session::` (via the
// crate-root cfg(test) re-export glob).
#[cfg(test)]
pub(crate) use inbound::{
    handle_offer_session_message, maybe_ack_duplicate_active_session_message,
};

use super::OfferAcceptTaskExit;
#[cfg(any(test, debug_assertions))]
use super::OfferSessionTestEvent;
#[cfg(any(test, debug_assertions))]
use super::OfferSessionTestHandle;
pub(crate) struct OfferSessionIo<'a> {
    pub(crate) client: OfferClient,
    pub(crate) accepted_clients:
        &'a mut mpsc::Receiver<Result<OfferClient, p2p_tunnel::TunnelError>>,
    /// Independently observed accept-worker completion (see
    /// `OfferAcceptRuntime::worker_exits`), so an active session notices a worker
    /// dying just as promptly as the idle daemon loop does.
    pub(crate) worker_exits: &'a mut mpsc::UnboundedReceiver<OfferAcceptTaskExit>,
    pub(crate) remote: &'a AuthorizedKey,
    #[cfg(any(test, debug_assertions))]
    pub(crate) session_hook: Option<mpsc::UnboundedSender<OfferSessionTestHandle>>,
}

type OfferAcceptedClients<'a> =
    &'a mut mpsc::Receiver<Result<OfferClient, p2p_tunnel::TunnelError>>;

type OfferBridgeFuture<'a> = Pin<
    Box<
        dyn Future<Output = (Result<(), p2p_tunnel::TunnelError>, OfferAcceptedClients<'a>)>
            + Send
            + 'a,
    >,
>;

/// In-flight data-plane probe future stored across select-loop iterations.
type OfferProbeFuture = Pin<Box<dyn Future<Output = Result<(), p2p_tunnel::TunnelError>> + Send>>;

pub(crate) async fn run_offer_session<'a, T: DaemonSignalingTransport>(
    config: &'a AppConfig,
    codec: &SignalCodec<'_>,
    transport: &mut T,
    ctx: &mut RuntimeContext<'_>,
    io: OfferSessionIo<'a>,
    mut shutdown: ShutdownToken,
) -> Result<(), DaemonError> {
    let remote = io.remote;
    let peer = WebRtcPeer::new(&config.webrtc).await?;
    let session_id = SessionId::random();
    let mut session =
        ActiveSession::new(session_id, remote.clone(), peer, config.security.replay_cache_size);

    write_daemon_status(
        ctx,
        StatusSnapshot {
            active_session_id: Some(session.session_id),
            current_state: DaemonState::Negotiating,
        },
    )
    .await;

    tracing::debug!(
        session_id = %session.session_id,
        remote_peer_id = %remote.peer_id,
        "starting offer session and publishing hello"
    );

    publish_message(
        ctx,
        codec,
        transport,
        StatusSnapshot {
            active_session_id: Some(session.session_id),
            current_state: DaemonState::Negotiating,
        },
        None,
        remote,
        OutgoingSignal {
            message: build_hello_message(
                &config.node.peer_id,
                &remote.peer_id,
                session.session_id,
                "offer",
            ),
            response: false,
        },
    )
    .await?;

    let data_channel = session.peer.create_data_channel().await?;
    session.data_channel = Some(data_channel.clone());
    let offer_sdp = session.peer.create_offer().await?;
    tracing::debug!(
        session_id = %session.session_id,
        remote_peer_id = %remote.peer_id,
        sdp_len = offer_sdp.len(),
        "created local offer and publishing signaling offer"
    );
    publish_message(
        ctx,
        codec,
        transport,
        StatusSnapshot {
            active_session_id: Some(session.session_id),
            current_state: DaemonState::Negotiating,
        },
        Some(&mut session.signaling),
        remote,
        OutgoingSignal {
            message: InnerMessageBuilder::new(
                session.session_id,
                config.node.peer_id.clone(),
                session.remote_peer_id.clone(),
            )
            .build(MessageBody::Offer(OfferBody { sdp: offer_sdp })),
            response: false,
        },
    )
    .await?;

    #[cfg(any(test, debug_assertions))]
    let test_event_tx: Option<mpsc::UnboundedSender<OfferSessionTestEvent>> =
        if let Some(session_hook) = io.session_hook {
            let (test_event_tx, test_event_rx) = mpsc::unbounded_channel();
            let _ = session_hook.send(OfferSessionTestHandle {
                session_id: session.session_id,
                ice_state_injector: session.peer.ice_state_injector_for_tests(),
                test_events: test_event_rx,
            });
            Some(test_event_tx)
        } else {
            None
        };
    #[cfg(not(any(test, debug_assertions)))]
    let test_event_tx: Option<()> = None;

    let mut tick = interval(Duration::from_secs(1));
    let mut pending_client = Some(io.client);
    let mut accepted_clients = Some(io.accepted_clients);
    let worker_exits = io.worker_exits;
    let mut offer_bridge: Option<OfferBridgeFuture<'a>> = None;
    // Absolute deadline for the first data-channel open. Only enforced until the bridge
    // first goes active; a working tunnel disables it so a live stream is never killed.
    let first_data_channel_deadline = Instant::now() + FIRST_DATA_CHANNEL_OPEN_TIMEOUT;
    let mut bridge_ever_active = false;
    // Post-DCEP data-plane probe: once the channel opens we send a tunnel Ping and require
    // a Pong before bridging, so a silently-black-holed data plane fails fast instead of
    // hanging the local client at zero bytes. The probe runs as a cancel-safe select arm
    // (it races ICE failure below) and is the sole consumer of the channel until it
    // resolves; `run_multiplex_offer` only takes over after `probe_succeeded`.
    let mut offer_probe: Option<OfferProbeFuture> = None;
    let mut probe_succeeded = false;
    let result = async {
        loop {
            // Once the data channel first opens, gate bridging on an application-level
            // data-plane round trip. Start the probe (it resolves in the select arm below).
            if pending_client.is_some()
                && !probe_succeeded
                && offer_probe.is_none()
                && offer_bridge.is_none()
                && session.data_channel.as_ref().is_some_and(|channel| channel.is_open())
            {
                let channel =
                    session.data_channel.clone().ok_or(DaemonError::MissingDataChannel)?;
                let probe_timeout =
                    Duration::from_millis(config.tunnel.data_plane_probe_timeout_ms);
                tracing::debug!(
                    session_id = %session.session_id,
                    remote_peer_id = %session.remote_peer_id,
                    timeout = ?probe_timeout,
                    "data channel open; probing data plane before bridging",
                );
                // Surface the probe window so the UI does not imply full readiness before the
                // application-level round trip succeeds.
                write_daemon_status(
                    ctx,
                    StatusSnapshot {
                        active_session_id: Some(session.session_id),
                        current_state: DaemonState::ProbingDataPlane,
                    },
                )
                .await;
                offer_probe = Some(Box::pin(async move {
                    p2p_tunnel::probe_data_plane(&channel, probe_timeout).await
                }));
            }

            // Start the bridge only after the probe confirms the data plane round-trips.
            if pending_client.is_some() && probe_succeeded && offer_bridge.is_none() {
                write_daemon_status(
                    ctx,
                    StatusSnapshot {
                        active_session_id: Some(session.session_id),
                        current_state: DaemonState::TunnelOpen,
                    },
                )
                .await;
                bridge_ever_active = true;
                session.bridge_state = BridgeSessionState::Active;
                let channel =
                    session.data_channel.clone().ok_or(DaemonError::MissingDataChannel)?;
                let active_clients = accepted_clients.take().ok_or_else(|| {
                    DaemonError::Logging(
                        "offer session lost accepted-client queue while bridge was starting"
                            .to_owned(),
                    )
                })?;
                let client = pending_client.take().ok_or(DaemonError::MissingDataChannel)?;
                offer_bridge = Some(Box::pin(async move {
                    let result =
                        p2p_tunnel::run_multiplex_offer(channel, &config.tunnel, client, active_clients)
                            .await;
                    (result, active_clients)
                }));
            }
            tokio::select! {
                _ = shutdown.cancelled() => {
                    tracing::info!(
                        session_id = %session.session_id,
                        remote_peer_id = %session.remote_peer_id,
                        "offer session shutdown requested"
                    );
                    return Ok(());
                }
                exit = worker_exits.recv() => {
                    let Some(exit) = exit else {
                        return Err(DaemonError::Logging(
                            "offer accept-worker supervisor channel closed unexpectedly".to_owned(),
                        ));
                    };

                    if shutdown.is_shutdown_requested() {
                        tracing::debug!(
                            forward_id = %exit.forward_id,
                            outcome = ?exit.outcome,
                            "offer accept worker exited during shutdown"
                        );
                        return Ok(());
                    }

                    return Err(DaemonError::OfferAcceptWorkerFailed {
                        forward_id: exit.forward_id,
                        reason: format!("{:?}", exit.outcome),
                    });
                }
                _ = sleep_until(first_data_channel_deadline),
                    if !bridge_ever_active
                        && offer_bridge.is_none()
                        && offer_probe.is_none()
                        && !probe_succeeded =>
                {
                    tracing::warn!(
                        session_id = %session.session_id,
                        remote_peer_id = %session.remote_peer_id,
                        timeout = ?FIRST_DATA_CHANNEL_OPEN_TIMEOUT,
                        "data channel did not open before deadline; tearing down wedged offer session",
                    );
                    return Err(DaemonError::DataChannelOpenTimeout(FIRST_DATA_CHANNEL_OPEN_TIMEOUT));
                }
                _ = tick.tick() => {
                    retry_pending_acks(
                        ctx,
                        transport,
                        StatusSnapshot {
                            active_session_id: Some(session.session_id),
                            current_state: session.state,
                        },
                        &mut session,
                    )
                    .await?;
                    if !session.signaling.ack_tracker.expired().is_empty() {
                        return Err(DaemonError::AckTimeout);
                    }
                }
                payload = poll_session_signal_payload(
                    ctx,
                    transport,
                    StatusSnapshot {
                        active_session_id: Some(session.session_id),
                        current_state: session.state,
                    },
                ) => {
                    if let Some(payload) = payload? {
                        process_offer_session_payload(
                            ctx,
                            codec,
                            transport,
                            remote,
                            &mut session,
                            &payload,
                        )
                        .await?;
                    }
                }
                candidate = session.peer.next_local_candidate() => {
                    if let Some(candidate) = candidate {
                        send_local_candidate(
                            ctx,
                            codec,
                            transport,
                            &mut session,
                            remote,
                            candidate,
                        )
                        .await?;
                    }
                }
                ice_state = session.peer.next_ice_state() => {
                    if let Some(ice_state) = ice_state {
                        if matches!(ice_state, IceConnectionState::Failed | IceConnectionState::Disconnected) {
                            offer_bridge = None;
                            // Drop any in-flight probe and require a fresh one after a
                            // successful reconnect, since the data plane just broke.
                            offer_probe = None;
                            probe_succeeded = false;
                            if let Some(handle) = session.bridge_handle.take() {
                                handle.abort();
                            }
                            if session.bridge_state == BridgeSessionState::Active {
                                publish_message(
                                    ctx,
                                    codec,
                                    transport,
                                    StatusSnapshot {
                                        active_session_id: Some(session.session_id),
                                        current_state: session.state,
                                    },
                                    Some(&mut session.signaling),
                                    remote,
                                    OutgoingSignal {
                                        message: build_error_message(
                                            &config.node.peer_id,
                                            &session.remote_peer_id,
                                            session.session_id,
                                            FailureCode::IceFailed,
                                            "ice connection failed",
                                        ),
                                        response: false,
                                    },
                                ).await?;
                                // In v1 a live tunnel failure ends the current local client/session.
                                session.bridge_state = BridgeSessionState::Closed;
                                return Err(DaemonError::IceFailed(ice_state));
                            }
                            session.bridge_state = BridgeSessionState::Reconnecting;
                            let reconnected = if should_attempt_offer_reconnect(config, pending_client.is_some(), session.bridge_state) {
                                tokio::select! {
                                    result = attempt_offer_reconnect(
                                        ctx,
                                        codec,
                                        transport,
                                        &mut session,
                                        remote,
                                        test_event_tx.as_ref(),
                                    ) => result?,
                                    _ = shutdown.cancelled() => {
                                        tracing::info!(
                                            session_id = %session.session_id,
                                            remote_peer_id = %session.remote_peer_id,
                                            "offer reconnect interrupted by shutdown"
                                        );
                                        return Ok(());
                                    }
                                }
                            } else {
                                false
                            };
                            if reconnected {
                                session.bridge_state = BridgeSessionState::Pending;
                                continue;
                            }
                            publish_message(
                                ctx,
                                codec,
                                transport,
                                StatusSnapshot {
                                    active_session_id: Some(session.session_id),
                                    current_state: session.state,
                                },
                                Some(&mut session.signaling),
                                remote,
                                OutgoingSignal {
                                    message: build_error_message(
                                        &config.node.peer_id,
                                        &session.remote_peer_id,
                                        session.session_id,
                                        FailureCode::IceFailed,
                                        "ice connection failed",
                                    ),
                                    response: false,
                                },
                            ).await?;
                            session.bridge_state = BridgeSessionState::Closed;
                            return Err(DaemonError::IceFailed(ice_state));
                        }
                    }
                }
                bridge_result = async {
                    let handle = session.bridge_handle.as_mut().expect("guarded by select");
                    handle.await
                }, if session.bridge_handle.is_some() => {
                    let result = bridge_result
                        .map_err(|error| DaemonError::Logging(format!("bridge task join error: {error}")))?;
                    session.bridge_handle = None;
                    session.bridge_state = BridgeSessionState::Closed;
                    let _ = publish_message(
                        ctx,
                        codec,
                        transport,
                        StatusSnapshot {
                            active_session_id: Some(session.session_id),
                            current_state: session.state,
                        },
                        Some(&mut session.signaling),
                        remote,
                        OutgoingSignal {
                            message: InnerMessageBuilder::new(
                                session.session_id,
                                config.node.peer_id.clone(),
                                session.remote_peer_id.clone(),
                            )
                            .build(MessageBody::Close(CloseBody {
                                reason_code: "session_closed".to_owned(),
                                message: None,
                            })),
                            response: false,
                        },
                    )
                    .await;
                    result?;
                    return Ok(());
                }
                probe_result = async {
                    let probe = offer_probe.as_mut().expect("guarded by select");
                    probe.as_mut().await
                }, if offer_probe.is_some() => {
                    offer_probe = None;
                    match probe_result {
                        Ok(()) => {
                            // Data plane round-trips; the bridge-start block runs next iteration.
                            probe_succeeded = true;
                        }
                        Err(error) => {
                            tracing::warn!(
                                session_id = %session.session_id,
                                remote_peer_id = %session.remote_peer_id,
                                reason = %error,
                                "data-plane probe failed after data channel open; tearing down session",
                            );
                            return Err(DaemonError::DataPlaneProbeFailed(error));
                        }
                    }
                }
                bridge_result = async {
                    let bridge = offer_bridge.as_mut().expect("guarded by select");
                    bridge.as_mut().await
                }, if offer_bridge.is_some() => {
                    offer_bridge = None;
                    let (bridge_result, returned_clients) = bridge_result;
                    accepted_clients = Some(returned_clients);
                    session.bridge_state = BridgeSessionState::Closed;
                    let _ = publish_message(
                        ctx,
                        codec,
                        transport,
                        StatusSnapshot {
                            active_session_id: Some(session.session_id),
                            current_state: session.state,
                        },
                        Some(&mut session.signaling),
                        remote,
                        OutgoingSignal {
                            message: InnerMessageBuilder::new(
                                session.session_id,
                                config.node.peer_id.clone(),
                                session.remote_peer_id.clone(),
                            )
                            .build(MessageBody::Close(CloseBody {
                                reason_code: "session_closed".to_owned(),
                                message: None,
                            })),
                            response: false,
                        },
                    )
                    .await;
                    bridge_result?;
                    return Ok(());
                }
            }
        }
    }
    .await;

    if let Err(error) = &result {
        tracing::warn!(reason = %error, session_id = %session.session_id, "offer session failed");
    }
    cleanup_active_session(&mut session).await;
    result
}
