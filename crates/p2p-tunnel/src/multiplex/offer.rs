//! Offer-side multiplex loop: registers locally-accepted clients as new streams,
//! drives the data-channel/stream-event/writer select loop, and dispatches inbound
//! frames (OPEN-ack, DATA, CLOSE, ERROR, PING/PONG) for the offer role.

use std::collections::HashMap;

use p2p_core::{TunnelConfig, TunnelFrameType};
use p2p_webrtc::{DataChannelEvent, DataChannelHandle};
use tokio::net::TcpStream;
use tokio::sync::mpsc;

use super::state::*;
use super::stream::*;
use crate::{OfferClient, OpenPayload, TunnelError, TunnelFrame, TunnelFrameCodec};
pub async fn run_multiplex_offer(
    data_channel: DataChannelHandle,
    tunnel_config: &TunnelConfig,
    initial_client: OfferClient,
    accepted_clients: &mut mpsc::Receiver<Result<OfferClient, TunnelError>>,
) -> Result<(), TunnelError> {
    let (frame_tx, frame_rx) = mpsc::channel(DEFAULT_WRITER_QUEUE_MESSAGES);
    let (writer_failure_tx, mut writer_failure_rx) = mpsc::channel(1);
    let writer = spawn_writer_only(data_channel.clone(), frame_rx, writer_failure_tx);
    let mut manager = StreamManager::new();
    let mut streams: HashMap<u32, RuntimeStream> = HashMap::new();
    let mut opening_streams: HashMap<u32, TcpStream> = HashMap::new();
    let (tcp_frame_tx, mut tcp_frame_rx) = mpsc::channel(DEFAULT_WRITER_QUEUE_MESSAGES);
    let (stream_event_tx, mut stream_event_rx) = mpsc::channel(DEFAULT_WRITER_QUEUE_MESSAGES);

    register_offer_client(
        initial_client,
        tunnel_config,
        &frame_tx,
        &tcp_frame_tx,
        &mut manager,
        &mut opening_streams,
    )
    .await?;

    let mut accepting_clients = true;
    let result = loop {
        tokio::select! {
            client = accepted_clients.recv(), if accepting_clients => {
                let Some(client) = client else {
                    accepting_clients = false;
                    if manager.active_count() == 0 && opening_streams.is_empty() && streams.is_empty() {
                        break Ok(());
                    }
                    continue;
                };
                let client = client?;
                register_offer_client(
                    client,
                    tunnel_config,
                    &frame_tx,
                    &tcp_frame_tx,
                    &mut manager,
                    &mut opening_streams,
                ).await?;
            }
            frame = tcp_frame_rx.recv() => {
                let Some(frame) = frame else {
                    continue;
                };
                frame_tx.send(frame).await.map_err(|_| TunnelError::WriterClosed)?;
            }
            stream_event = stream_event_rx.recv() => {
                let Some(stream_event) = stream_event else {
                    continue;
                };
                handle_stream_runtime_event(stream_event, &frame_tx, &mut manager, &mut streams).await?;
                if !accepting_clients && manager.active_count() == 0 && opening_streams.is_empty() && streams.is_empty() {
                    break Ok(());
                }
            }
            writer_error = writer_failure_rx.recv() => {
                let Some(writer_error) = writer_error else {
                    break Err(TunnelError::WriterClosed);
                };
                break Err(writer_error);
            }
            event = data_channel.next_event() => {
                match event {
                    Some(DataChannelEvent::Message(payload)) => {
                        let frame = TunnelFrameCodec::decode(&payload)?;
                        handle_offer_frame(
                            frame,
                            &OfferIo {
                                tunnel_config,
                                frame_tx: &frame_tx,
                                tcp_frame_tx: &tcp_frame_tx,
                                stream_event_tx: &stream_event_tx,
                            },
                            &mut manager,
                            &mut opening_streams,
                            &mut streams,
                        ).await?;
                        if !accepting_clients && manager.active_count() == 0 && opening_streams.is_empty() && streams.is_empty() {
                            break Ok(());
                        }
                    }
                    Some(DataChannelEvent::Closed) | None => break Ok(()),
                    Some(DataChannelEvent::Open) => {}
                }
            }
        }
    };

    cleanup_all_streams(&mut manager, &mut streams);
    opening_streams.clear();
    writer.abort();
    result
}

pub(crate) async fn register_offer_client(
    mut client: OfferClient,
    _tunnel_config: &TunnelConfig,
    frame_tx: &mpsc::Sender<TunnelFrame>,
    _tcp_frame_tx: &mpsc::Sender<TunnelFrame>,
    manager: &mut StreamManager,
    opening_streams: &mut HashMap<u32, TcpStream>,
) -> Result<(), TunnelError> {
    let stream_id = manager.allocate_stream_id()?;
    let forward_id = client.forward_id().to_owned();
    let stream = client.take_stream()?;
    manager.register(StreamState {
        stream_id,
        forward_id: forward_id.clone(),
        lifecycle: StreamLifecycle::Opening,
        remote_peer_id: "answer".parse().map_err(|error| {
            TunnelError::InvalidFrame(format!("internal peer id parse failed: {error}"))
        })?,
    })?;
    opening_streams.insert(stream_id, stream);
    // Redacted (id/forward only, no payload): proves the offer issued the OPEN.
    tracing::debug!(stream_id, forward_id = %forward_id, "offer sending OPEN frame");
    frame_tx
        .send(TunnelFrame::open(stream_id, OpenPayload { forward_id })?)
        .await
        .map_err(|_| TunnelError::WriterClosed)
}

pub(crate) struct OfferIo<'a> {
    pub(crate) tunnel_config: &'a TunnelConfig,
    pub(crate) frame_tx: &'a mpsc::Sender<TunnelFrame>,
    pub(crate) tcp_frame_tx: &'a mpsc::Sender<TunnelFrame>,
    pub(crate) stream_event_tx: &'a mpsc::Sender<StreamRuntimeEvent>,
}

pub(crate) async fn handle_offer_frame(
    frame: TunnelFrame,
    io: &OfferIo<'_>,
    manager: &mut StreamManager,
    opening_streams: &mut HashMap<u32, TcpStream>,
    streams: &mut HashMap<u32, RuntimeStream>,
) -> Result<(), TunnelError> {
    let &OfferIo { tunnel_config, frame_tx, tcp_frame_tx, stream_event_tx } = io;
    match frame.frame_type {
        TunnelFrameType::Open => {
            let stream_id = frame.stream_id;
            let Some(stream) = opening_streams.remove(&stream_id) else {
                return Ok(());
            };
            if !frame.payload.is_empty() {
                manager.remove(stream_id);
                send_stream_error(
                    frame_tx,
                    stream_id,
                    "protocol_error",
                    "OPEN ACK payload must be empty",
                )
                .await?;
                return Ok(());
            }
            manager.get_mut(stream_id)?.lifecycle = StreamLifecycle::Open;
            tracing::debug!(stream_id, "offer received OPEN ack; bridging local TCP stream");
            let runtime_stream =
                spawn_tcp_bridge(stream_id, stream, tunnel_config, tcp_frame_tx, stream_event_tx);
            streams.insert(stream_id, runtime_stream);
        }
        TunnelFrameType::Data => {
            if let Some(stream) = streams.get(&frame.stream_id) {
                let Some(write_tx) = stream.write_tx().cloned() else {
                    tracing::debug!(
                        stream_id = frame.stream_id,
                        "ignoring DATA for opening stream"
                    );
                    return Ok(());
                };
                match write_tx.try_send(TcpWriteCommand::Data(frame.payload)) {
                    Ok(()) => {}
                    Err(mpsc::error::TrySendError::Full(_)) => {
                        send_stream_error(
                            frame_tx,
                            frame.stream_id,
                            "queue_overflow",
                            "stream write queue overflow",
                        )
                        .await?;
                        close_stream(frame.stream_id, manager, streams).await?;
                    }
                    Err(mpsc::error::TrySendError::Closed(_)) => {
                        handle_closed_stream_queue(frame.stream_id, frame_tx, manager, streams)
                            .await?;
                    }
                }
            } else {
                tracing::debug!(stream_id = frame.stream_id, "ignoring DATA for unknown stream");
            }
        }
        TunnelFrameType::Close => {
            tracing::debug!(stream_id = frame.stream_id, "offer received CLOSE; closing stream");
            opening_streams.remove(&frame.stream_id);
            close_stream(frame.stream_id, manager, streams).await?;
        }
        TunnelFrameType::Error => {
            tracing::debug!(stream_id = frame.stream_id, "offer received ERROR; closing stream");
            opening_streams.remove(&frame.stream_id);
            close_stream(frame.stream_id, manager, streams).await?;
        }
        TunnelFrameType::Ping => {
            frame_tx
                .send(TunnelFrame::pong(frame.payload))
                .await
                .map_err(|_| TunnelError::WriterClosed)?;
        }
        TunnelFrameType::Pong => {}
    }
    Ok(())
}
