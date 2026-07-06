//! Per-peer answer session task: owns one negotiated WebRTC session, pumps its
//! signaling/data-channel/ack loop, handles in-session offers (busy replies and
//! same-peer pending-session replacement), and reports status/results back to the
//! answer daemon through the session event channel.

use std::time::Duration;

use p2p_core::{AppConfig, DaemonState, FailureCode, ForwardTable, MsgId, SessionId};
use p2p_crypto::{AuthorizedKey, kid_from_signing_key};
use p2p_signaling::{
    AckBody, AnswerBody, CloseBody, DecodedSignal, EndOfCandidatesBody, IceCandidateBody,
    InnerMessage, InnerMessageBuilder, MessageBody, OfferBody, ReplayStatus, SignalCodec,
};
use p2p_webrtc::{DataChannelHandle, IceCandidateSignal, IceConnectionState, WebRtcPeer};
use tokio::sync::mpsc;
use tokio::time::interval;

use crate::DaemonError;
use crate::ShutdownToken;
use crate::busy::*;
use crate::messages::*;
use crate::signaling::*;
use crate::types::*;

use super::IncomingOffer;
pub(crate) async fn handle_answer_session_message(
    message: &InnerMessage,
    session: &mut ActiveSession,
) -> Result<(), DaemonError> {
    match &message.body {
        MessageBody::Ack(AckBody { ack_msg_id }) => {
            session.signaling.ack_tracker.acknowledge(&p2p_core::MsgId::new(*ack_msg_id));
        }
        MessageBody::IceCandidate(body) => {
            session.peer.add_remote_candidate(candidate_from_body(body)).await?;
        }
        MessageBody::EndOfCandidates(_) => {}
        MessageBody::Close(body) => {
            return Err(DaemonError::RemoteClosed(body.reason_code.clone()));
        }
        MessageBody::Error(body) => {
            return Err(DaemonError::RemoteError(body.code.clone(), body.message.clone()));
        }
        _ => {
            tracing::warn!("ignoring unexpected session message {:?}", message.message_type);
        }
    }
    Ok(())
}

pub(crate) async fn run_answer_session_task(
    deps: AnswerSessionTaskDeps,
    mut inbound: mpsc::Receiver<DecodedSignal>,
    generation: SessionGeneration,
    mut session: ActiveSession,
    shutdown: ShutdownToken,
) -> AnswerSessionTaskResult {
    let result =
        run_answer_session_task_inner(&deps, &mut inbound, generation, &mut session, shutdown)
            .await;
    if let Err(error) = &result {
        tracing::warn!(
            reason = %error,
            session_id = %session.session_id,
            remote_peer_id = %session.remote_peer_id,
            "answer session failed"
        );
    }
    cleanup_active_session(&mut session).await;
    AnswerSessionTaskResult { final_session_id: session.session_id, result }
}

async fn run_answer_session_task_inner(
    deps: &AnswerSessionTaskDeps,
    inbound: &mut mpsc::Receiver<DecodedSignal>,
    generation: SessionGeneration,
    session: &mut ActiveSession,
    mut shutdown: ShutdownToken,
) -> Result<(), DaemonError> {
    let AnswerSessionTaskDeps { config, local_identity, authorized_keys, event_tx } = deps;
    let codec = SignalCodec::new(
        local_identity,
        authorized_keys,
        config.security.max_clock_skew_secs,
        config.security.max_message_age_secs,
    );
    send_answer_session_status(config, event_tx, generation, session).await?;

    let mut tick = interval(Duration::from_secs(1));
    loop {
        tokio::select! {
            _ = shutdown.cancelled() => {
                tracing::info!(
                    session_id = %session.session_id,
                    remote_peer_id = %session.remote_peer_id,
                    "answer session shutdown requested"
                );
                return Ok(());
            }
            _ = tick.tick() => {
                retry_pending_answer_session_acks(config, event_tx, generation, session).await?;
                if !session.signaling.ack_tracker.expired().is_empty() {
                    return Err(DaemonError::AckTimeout);
                }
            }
            signal = inbound.recv() => {
                let Some(signal) = signal else {
                    return Ok(());
                };
                process_answer_session_signal(config, &codec, event_tx, generation, session, signal).await?;
            }
            candidate = session.peer.next_local_candidate() => {
                if let Some(candidate) = candidate {
                    send_answer_session_local_candidate(config, event_tx, generation, session, candidate).await?;
                }
            }
            incoming = session.peer.next_incoming_data_channel(), if session.data_channel.is_none() => {
                handle_answer_incoming_data_channel(session, incoming, config)?;
                send_answer_session_status(config, event_tx, generation, session).await?;
            }
            ice_state = session.peer.next_ice_state() => {
                if let Some(ice_state) = ice_state {
                    if matches!(ice_state, IceConnectionState::Failed | IceConnectionState::Disconnected) {
                        publish_from_answer_session(
                            config,
                            event_tx,
                            session,
                            generation,
                            session.remote_authorized.clone(),
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
                            true,
                        )
                        .await?;
                        if let Some(handle) = session.bridge_handle.take() {
                            handle.abort();
                        }
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
                send_answer_session_status(config, event_tx, generation, session).await?;
                if let Err(p2p_tunnel::TunnelError::TargetConnectFailed(message)) = &result {
                    let _ = publish_from_answer_session(
                        config,
                        event_tx,
                        session,
                        generation,
                        session.remote_authorized.clone(),
                        OutgoingSignal {
                            message: build_error_message(
                                &config.node.peer_id,
                                &session.remote_peer_id,
                                session.session_id,
                                FailureCode::TargetConnectFailed,
                                message,
                            ),
                            response: false,
                        },
                        true,
                    )
                    .await;
                }
                let _ = publish_from_answer_session(
                    config,
                    event_tx,
                    session,
                    generation,
                    session.remote_authorized.clone(),
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
                    true,
                )
                .await;
                result?;
                return Ok(());
            }
        }
    }
}

async fn send_answer_session_status(
    config: &AppConfig,
    event_tx: &mpsc::Sender<AnswerSessionEvent>,
    generation: SessionGeneration,
    session: &ActiveSession,
) -> Result<(), DaemonError> {
    event_tx
        .send(AnswerSessionEvent::Status(SessionStatusSnapshot::from_session(
            config, session, generation,
        )))
        .await
        .map_err(|_| DaemonError::Logging("answer session event loop stopped".to_owned()))
}

async fn publish_from_answer_session(
    config: &AppConfig,
    event_tx: &mpsc::Sender<AnswerSessionEvent>,
    session: &mut ActiveSession,
    generation: SessionGeneration,
    recipient: AuthorizedKey,
    outgoing: OutgoingSignal,
    track_ack: bool,
) -> Result<(), DaemonError> {
    if let Some(published) = request_session_publish(
        event_tx,
        recipient,
        outgoing,
        track_ack,
        SessionStatusSnapshot::from_session(config, session, generation),
    )
    .await?
    {
        session.signaling.ack_tracker.register(
            published.msg_id,
            published.message_type,
            published.payload,
            current_time_ms(),
        );
    }
    Ok(())
}

async fn retry_pending_answer_session_acks(
    config: &AppConfig,
    event_tx: &mpsc::Sender<AnswerSessionEvent>,
    generation: SessionGeneration,
    session: &mut ActiveSession,
) -> Result<(), DaemonError> {
    let mut retries = session.signaling.ack_tracker.retry_due(current_time_ms());
    while let Some((_msg_id, payload)) = retries.pop() {
        request_raw_session_publish(
            event_tx,
            session.remote_peer_id.clone(),
            payload,
            SessionStatusSnapshot::from_session(config, session, generation),
        )
        .await?;
    }
    Ok(())
}

async fn send_answer_session_local_candidate(
    config: &AppConfig,
    event_tx: &mpsc::Sender<AnswerSessionEvent>,
    generation: SessionGeneration,
    session: &mut ActiveSession,
    candidate: IceCandidateSignal,
) -> Result<(), DaemonError> {
    let body = if let Some(candidate_line) = candidate.candidate {
        tracing::debug!(
            target: "ice",
            session_id = %session.session_id,
            remote_peer_id = %session.remote_peer_id,
            candidate = %crate::candidate_log_summary(&config.logging, &candidate_line),
            "gathered local ICE candidate",
        );
        MessageBody::IceCandidate(IceCandidateBody {
            candidate: Some(candidate_line),
            sdp_mid: candidate.sdp_mid,
            sdp_mline_index: candidate.sdp_mline_index,
        })
    } else {
        tracing::debug!(
            target: "ice",
            session_id = %session.session_id,
            remote_peer_id = %session.remote_peer_id,
            "local ICE gathering complete (end-of-candidates)",
        );
        MessageBody::EndOfCandidates(EndOfCandidatesBody::default())
    };
    publish_from_answer_session(
        config,
        event_tx,
        session,
        generation,
        session.remote_authorized.clone(),
        OutgoingSignal {
            message: InnerMessageBuilder::new(
                session.session_id,
                config.node.peer_id.clone(),
                session.remote_peer_id.clone(),
            )
            .build(body),
            response: false,
        },
        true,
    )
    .await
}

pub(crate) async fn process_answer_session_signal(
    config: &AppConfig,
    codec: &SignalCodec<'_>,
    event_tx: &mpsc::Sender<AnswerSessionEvent>,
    generation: SessionGeneration,
    session: &mut ActiveSession,
    signal: DecodedSignal,
) -> Result<(), DaemonError> {
    let DecodedSignal { envelope, message, sender, replay_status } = signal;
    if sender.peer_id != session.remote_peer_id {
        tracing::warn!(
            peer_id = %sender.peer_id,
            expected_peer_id = %session.remote_peer_id,
            session_id = %session.session_id,
            "ignoring message from unexpected peer"
        );
        return Ok(());
    }
    if replay_status == ReplayStatus::DuplicateDifferentSession {
        tracing::warn!(
            session_id = %message.session_id,
            remote_peer_id = %session.remote_peer_id,
            "ignoring signaling message with duplicate msg_id for a different session"
        );
        return Ok(());
    }
    if replay_status == ReplayStatus::DuplicateSameSession
        && !session.duplicate_active_acks.record_if_new(envelope.msg_id)
    {
        tracing::info!(
            session_id = %message.session_id,
            duplicate_msg_id = %envelope.msg_id,
            "suppressing repeated duplicate active-session re-ack"
        );
        return Ok(());
    }
    if message.message_type.requires_ack() {
        publish_from_answer_session(
            config,
            event_tx,
            session,
            generation,
            sender.clone(),
            OutgoingSignal {
                message: codec.build_ack(
                    sender.peer_id.clone(),
                    message.session_id,
                    envelope.msg_id,
                ),
                response: true,
            },
            false,
        )
        .await?;
    }
    if replay_status == ReplayStatus::DuplicateSameSession {
        tracing::info!(
            session_id = %message.session_id,
            duplicate_msg_id = %envelope.msg_id,
            "re-acknowledged duplicate active-session signaling message"
        );
        return Ok(());
    }
    if let MessageBody::Offer(offer) = message.body.clone() {
        if message.session_id == session.session_id {
            handle_active_answer_offer_via_events(config, event_tx, generation, session, &offer)
                .await?;
        } else {
            maybe_replace_pending_same_peer_session(
                config,
                event_tx,
                generation,
                session,
                IncomingOffer { envelope, message, sender, offer: &offer },
            )
            .await?;
        }
    } else {
        if message.session_id != session.session_id {
            tracing::warn!(
                session_id = %message.session_id,
                active_session_id = %session.session_id,
                "ignoring non-offer signaling message for a different session"
            );
            return Ok(());
        }
        handle_answer_session_message(&message, session).await?;
    }
    send_answer_session_status(config, event_tx, generation, session).await?;
    Ok(())
}

async fn maybe_replace_pending_same_peer_session(
    config: &AppConfig,
    event_tx: &mpsc::Sender<AnswerSessionEvent>,
    generation: SessionGeneration,
    session: &mut ActiveSession,
    incoming: IncomingOffer<'_>,
) -> Result<(), DaemonError> {
    let IncomingOffer { envelope, message, sender, offer } = incoming;
    // v0.3 permits same-peer replacement only while the existing session has not
    // reached data-channel/tunnel activity. Unrelated second active sessions are
    // rejected with encrypted busy and must not disturb other peers.
    if session.bridge_state != BridgeSessionState::Pending {
        publish_busy_for_same_peer_offer(
            config,
            event_tx,
            generation,
            session,
            &sender,
            message.session_id,
            envelope.msg_id,
        )
        .await?;
        return Ok(());
    }

    if let Some(handle) = session.bridge_handle.take() {
        handle.abort();
        let _ = handle.await;
    }
    session.data_channel = None;
    let _ = session.peer.close().await;

    let peer = WebRtcPeer::new(&config.webrtc).await?;
    peer.apply_remote_offer(&offer.sdp).await?;
    let mut replacement = ActiveSession::new(
        message.session_id,
        sender.clone(),
        peer,
        config.security.replay_cache_size,
    );
    let answer_sdp = replacement.peer.create_answer().await?;
    let replacement_session_id = replacement.session_id;
    let replacement_remote = replacement.remote_authorized.clone();
    let replacement_remote_peer_id = replacement.remote_peer_id.clone();
    publish_from_answer_session(
        config,
        event_tx,
        &mut replacement,
        generation,
        replacement_remote,
        OutgoingSignal {
            message: InnerMessageBuilder::new(
                replacement_session_id,
                config.node.peer_id.clone(),
                replacement_remote_peer_id,
            )
            .build(MessageBody::Answer(AnswerBody { sdp: answer_sdp })),
            response: false,
        },
        true,
    )
    .await?;
    replacement.state = DaemonState::ConnectingDataChannel;
    let old_session_id = session.session_id;
    *session = replacement;
    let status = SessionStatusSnapshot::from_session(config, session, generation);
    event_tx
        .send(AnswerSessionEvent::Replaced {
            old_session_id,
            new_session_id: session.session_id,
            remote_peer_id: session.remote_peer_id.clone(),
            generation,
            status,
        })
        .await
        .map_err(|_| DaemonError::Logging("answer session event loop stopped".to_owned()))?;
    Ok(())
}

async fn publish_busy_for_same_peer_offer(
    config: &AppConfig,
    event_tx: &mpsc::Sender<AnswerSessionEvent>,
    generation: SessionGeneration,
    session: &mut ActiveSession,
    sender: &AuthorizedKey,
    rejected_session_id: SessionId,
    msg_id: MsgId,
) -> Result<(), DaemonError> {
    let key = ActiveBusyOfferKey {
        sender_kid: kid_from_signing_key(&sender.public_identity.sign_public),
        msg_id,
    };
    if !session.active_busy_offers.record_if_new(key) {
        return Ok(());
    }
    publish_from_answer_session(
        config,
        event_tx,
        session,
        generation,
        sender.clone(),
        OutgoingSignal {
            message: build_error_message(
                &config.node.peer_id,
                &sender.peer_id,
                rejected_session_id,
                FailureCode::Busy,
                "answer daemon already has an active session for this peer",
            ),
            response: true,
        },
        false,
    )
    .await
}

async fn handle_active_answer_offer_via_events(
    config: &AppConfig,
    event_tx: &mpsc::Sender<AnswerSessionEvent>,
    generation: SessionGeneration,
    session: &mut ActiveSession,
    offer: &OfferBody,
) -> Result<(), DaemonError> {
    session.state = DaemonState::Negotiating;
    send_answer_session_status(config, event_tx, generation, session).await?;
    session.peer.apply_remote_offer(&offer.sdp).await?;
    let answer_sdp = session.peer.create_answer().await?;
    publish_from_answer_session(
        config,
        event_tx,
        session,
        generation,
        session.remote_authorized.clone(),
        OutgoingSignal {
            message: InnerMessageBuilder::new(
                session.session_id,
                config.node.peer_id.clone(),
                session.remote_peer_id.clone(),
            )
            .build(MessageBody::Answer(AnswerBody { sdp: answer_sdp })),
            response: false,
        },
        true,
    )
    .await?;
    session.state = DaemonState::ConnectingDataChannel;
    send_answer_session_status(config, event_tx, generation, session).await?;
    Ok(())
}

pub(crate) fn handle_answer_incoming_data_channel(
    session: &mut ActiveSession,
    incoming: Option<Result<DataChannelHandle, p2p_webrtc::WebRtcError>>,
    config: &AppConfig,
) -> Result<(), DaemonError> {
    if let Some(channel) = incoming {
        let channel = channel?;
        session.data_channel = Some(channel.clone());
        let tunnel = config.tunnel.clone();
        let forward_table = ForwardTable::new(&config.forwards);
        let remote_peer_id = session.remote_peer_id.clone();
        // The channel is open and the bridge is about to serve the data plane (answering the
        // offer's round-trip probe). Report ProbingDataPlane rather than leaving the stale
        // ConnectingDataChannel, so the status never implies full readiness too early. The
        // answer has a single serving loop and cannot observe the offer's probe completion,
        // so this is its serving state; the offer's TunnelOpen is the authoritative signal.
        session.state = DaemonState::ProbingDataPlane;
        session.bridge_state = BridgeSessionState::Active;
        session.bridge_handle = Some(tokio::spawn(async move {
            p2p_tunnel::run_multiplex_answer(channel, &tunnel, forward_table, remote_peer_id).await
        }));
    }
    Ok(())
}
