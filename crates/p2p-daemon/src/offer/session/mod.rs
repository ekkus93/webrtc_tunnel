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
use tokio::time::interval;

use crate::DaemonError;
use crate::messages::*;
use crate::predicates::*;
use crate::signaling::*;
use crate::types::*;

mod inbound;
mod reconnect;

use reconnect::attempt_offer_reconnect;

// Used by `run_offer_session` below and re-exported for the daemon unit tests.
pub(crate) use inbound::process_offer_session_payload;

// Session helpers the daemon unit tests reach through `offer::session::` (via the
// crate-root cfg(test) re-export glob).
#[cfg(test)]
pub(crate) use inbound::{
    handle_offer_session_message, maybe_ack_duplicate_active_session_message,
};

#[cfg(any(test, debug_assertions))]
use super::OfferSessionTestHandle;
pub(crate) struct OfferSessionIo<'a> {
    pub(crate) client: OfferClient,
    pub(crate) accepted_clients:
        &'a mut mpsc::Receiver<Result<OfferClient, p2p_tunnel::TunnelError>>,
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

pub(crate) async fn run_offer_session<'a, T: DaemonSignalingTransport>(
    config: &'a AppConfig,
    codec: &SignalCodec<'_>,
    transport: &mut T,
    ctx: &mut RuntimeContext<'_>,
    io: OfferSessionIo<'a>,
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
    if let Some(session_hook) = io.session_hook {
        let _ = session_hook.send(OfferSessionTestHandle {
            session_id: session.session_id,
            ice_state_injector: session.peer.ice_state_injector_for_tests(),
        });
    }

    let mut tick = interval(Duration::from_secs(1));
    let mut pending_client = Some(io.client);
    let mut accepted_clients = Some(io.accepted_clients);
    let mut offer_bridge: Option<OfferBridgeFuture<'a>> = None;
    let result = async {
        loop {
            if pending_client.is_some()
                && session.data_channel.as_ref().is_some_and(|channel| channel.is_open())
                && offer_bridge.is_none()
            {
                write_daemon_status(
                    ctx,
                    StatusSnapshot {
                        active_session_id: Some(session.session_id),
                        current_state: DaemonState::TunnelOpen,
                    },
                )
                .await;
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
                            if should_attempt_offer_reconnect(config, pending_client.is_some(), session.bridge_state)
                                && attempt_offer_reconnect(
                                    ctx,
                                    codec,
                                    transport,
                                    &mut session,
                                    remote,
                                )
                                .await?
                            {
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
