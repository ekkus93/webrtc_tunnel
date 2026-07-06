//! Transparent offer-side reconnect: on ICE failure, backs off and tries an
//! in-place ICE restart first, then a full renegotiate (fresh peer/session),
//! waiting for the data channel to reopen before handing control back to the
//! session loop. Honors the configured strategy, timeouts, and attempt limits.

use std::time::Duration;

use p2p_core::{DaemonState, SessionId};
use p2p_crypto::AuthorizedKey;
use p2p_signaling::{InnerMessageBuilder, MessageBody, OfferBody, SignalCodec};
use p2p_webrtc::{IceConnectionState, WebRtcPeer};
use tokio::time::interval;

use super::process_offer_session_payload;
use crate::DaemonError;
use crate::config::*;
#[cfg(any(test, debug_assertions))]
use crate::offer::OfferSessionTestEvent;
use crate::predicates::*;
use crate::signaling::*;
use crate::types::*;

pub(crate) async fn attempt_offer_reconnect<T: DaemonSignalingTransport>(
    ctx: &mut RuntimeContext<'_>,
    codec: &SignalCodec<'_>,
    transport: &mut T,
    session: &mut ActiveSession,
    remote: &AuthorizedKey,
    #[cfg(any(test, debug_assertions))] test_events: Option<
        &tokio::sync::mpsc::UnboundedSender<OfferSessionTestEvent>,
    >,
    #[cfg(not(any(test, debug_assertions)))] _test_events: Option<&()>,
) -> Result<bool, DaemonError> {
    if !ctx.config.reconnect.enable_auto_reconnect {
        return Ok(false);
    }

    let max_attempts = ctx.config.reconnect.max_attempts;
    let mut attempt = 0;
    while should_continue_reconnect_attempt(max_attempts, attempt) {
        session.state = DaemonState::Backoff;
        write_daemon_status(
            ctx,
            StatusSnapshot {
                active_session_id: Some(session.session_id),
                current_state: session.state,
            },
        )
        .await;
        let delay = compute_backoff_delay(ctx.config, attempt);
        #[cfg(any(test, debug_assertions))]
        if let Some(test_events) = test_events {
            let _ = test_events.send(OfferSessionTestEvent::ReconnectBackoffStarted {
                session_id: session.session_id,
                delay,
            });
        }
        tokio::time::sleep(delay).await;

        if ctx.config.webrtc.enable_ice_restart && can_attempt_same_session_ice_restart(session) {
            session.state = DaemonState::IceRestarting;
            write_daemon_status(
                ctx,
                StatusSnapshot {
                    active_session_id: Some(session.session_id),
                    current_state: session.state,
                },
            )
            .await;
            if reconnect_with_offer(ctx, codec, transport, session, remote, true).await? {
                session.state = DaemonState::ConnectingDataChannel;
                return Ok(true);
            }
        }

        session.state = DaemonState::Renegotiating;
        write_daemon_status(
            ctx,
            StatusSnapshot {
                active_session_id: Some(session.session_id),
                current_state: session.state,
            },
        )
        .await;
        if reconnect_with_offer(ctx, codec, transport, session, remote, false).await? {
            session.state = DaemonState::ConnectingDataChannel;
            return Ok(true);
        }
        attempt = attempt.saturating_add(1);
    }

    Ok(false)
}

async fn reconnect_with_offer<T: DaemonSignalingTransport>(
    ctx: &mut RuntimeContext<'_>,
    codec: &SignalCodec<'_>,
    transport: &mut T,
    session: &mut ActiveSession,
    remote: &AuthorizedKey,
    ice_restart: bool,
) -> Result<bool, DaemonError> {
    if ice_restart {
        let offer_sdp = session.peer.create_offer_with_restart(true).await?;
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
                message: InnerMessageBuilder::new(
                    session.session_id,
                    ctx.config.node.peer_id.clone(),
                    session.remote_peer_id.clone(),
                )
                .build(MessageBody::Offer(OfferBody { sdp: offer_sdp })),
                response: false,
            },
        )
        .await?;
        wait_for_offer_reconnect_response(
            ctx,
            codec,
            transport,
            session,
            remote,
            Duration::from_secs(u64::from(ctx.config.reconnect.ice_restart_timeout_secs)),
        )
        .await
    } else {
        let peer = WebRtcPeer::new(&ctx.config.webrtc).await?;
        let data_channel = peer.create_data_channel().await?;
        let new_session_id = SessionId::random();
        let mut replacement = ActiveSession::new(
            new_session_id,
            remote.clone(),
            peer,
            ctx.config.security.replay_cache_size,
        );
        replacement.data_channel = Some(data_channel);
        let offer_sdp = replacement.peer.create_offer().await?;
        publish_message(
            ctx,
            codec,
            transport,
            StatusSnapshot {
                active_session_id: Some(replacement.session_id),
                current_state: session.state,
            },
            Some(&mut replacement.signaling),
            remote,
            OutgoingSignal {
                message: InnerMessageBuilder::new(
                    replacement.session_id,
                    ctx.config.node.peer_id.clone(),
                    replacement.remote_peer_id.clone(),
                )
                .build(MessageBody::Offer(OfferBody { sdp: offer_sdp })),
                response: false,
            },
        )
        .await?;
        if wait_for_offer_reconnect_response(
            ctx,
            codec,
            transport,
            &mut replacement,
            remote,
            Duration::from_secs(u64::from(ctx.config.reconnect.renegotiate_timeout_secs)),
        )
        .await?
        {
            if let Err(error) = session.peer.close().await {
                tracing::warn!(
                    reason = %error,
                    session_id = %session.session_id,
                    "failed to close superseded session's peer connection during reconnect"
                );
            }
            *session = replacement;
            return Ok(true);
        }
        Ok(false)
    }
}

async fn wait_for_offer_reconnect_response<T: DaemonSignalingTransport>(
    ctx: &mut RuntimeContext<'_>,
    codec: &SignalCodec<'_>,
    transport: &mut T,
    session: &mut ActiveSession,
    remote: &AuthorizedKey,
    timeout: Duration,
) -> Result<bool, DaemonError> {
    let deadline = tokio::time::Instant::now() + timeout;
    let mut tick = interval(Duration::from_millis(250));
    loop {
        if session.data_channel.as_ref().is_some_and(|channel| channel.is_open()) {
            return Ok(true);
        }
        if tokio::time::Instant::now() >= deadline {
            return Ok(false);
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
                    session,
                )
                .await?;
                if !session.signaling.ack_tracker.expired().is_empty() {
                    return Ok(false);
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
                        session,
                        &payload,
                    )
                    .await?;
                    if session
                        .data_channel
                        .as_ref()
                        .is_some_and(|channel| channel.is_open())
                    {
                        return Ok(true);
                    }
                }
            }
            candidate = session.peer.next_local_candidate() => {
                if let Some(candidate) = candidate {
                    send_local_candidate(ctx, codec, transport, session, remote, candidate).await?;
                }
            }
            ice_state = session.peer.next_ice_state() => {
                if let Some(ice_state) = ice_state {
                    match ice_state {
                        IceConnectionState::Connected | IceConnectionState::Completed => return Ok(true),
                        IceConnectionState::Failed => return Ok(false),
                        _ => {}
                    }
                }
            }
        }
    }
}
