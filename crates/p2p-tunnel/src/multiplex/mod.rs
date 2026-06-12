mod state;

use std::collections::HashMap;

use p2p_core::{
    FailureCode, ForwardLookupError, ForwardTable, PeerId, TunnelConfig, TunnelFrameType,
};
use p2p_webrtc::{DataChannelEvent, DataChannelHandle};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::mpsc;
use tokio::task::JoinHandle;

use state::{
    ANSWER_TARGET_CONNECT_TIMEOUT, RuntimeStream, StreamRuntimeEvent, TargetConnectResult,
    TcpWriteCommand,
};
pub use state::{
    DEFAULT_STREAM_QUEUE_MESSAGES, DEFAULT_WRITER_QUEUE_MESSAGES, StreamIdAllocator,
    StreamLifecycle, StreamManager, StreamState,
};

use crate::{ErrorPayload, OfferClient, OpenPayload, TunnelError, TunnelFrame, TunnelFrameCodec};

// The daemon uses these runtime functions directly. They are the production owner
// for stream allocation, stream state, per-stream task cancellation, writer
// failure propagation, frame dispatch, and session teardown.
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

pub async fn run_multiplex_answer(
    data_channel: DataChannelHandle,
    tunnel_config: &TunnelConfig,
    forward_table: ForwardTable,
    remote_peer_id: PeerId,
) -> Result<(), TunnelError> {
    let (frame_tx, frame_rx) = mpsc::channel(DEFAULT_WRITER_QUEUE_MESSAGES);
    let (writer_failure_tx, mut writer_failure_rx) = mpsc::channel(1);
    let writer = spawn_writer_only(data_channel.clone(), frame_rx, writer_failure_tx);
    let mut manager = StreamManager::new();
    let mut streams: HashMap<u32, RuntimeStream> = HashMap::new();
    let (tcp_frame_tx, mut tcp_frame_rx) = mpsc::channel(DEFAULT_WRITER_QUEUE_MESSAGES);
    let (stream_event_tx, mut stream_event_rx) = mpsc::channel(DEFAULT_WRITER_QUEUE_MESSAGES);
    let (target_connect_tx, mut target_connect_rx) = mpsc::channel(DEFAULT_STREAM_QUEUE_MESSAGES);

    let result = loop {
        tokio::select! {
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
            }
            target_result = target_connect_rx.recv() => {
                let Some(target_result) = target_result else {
                    continue;
                };
                handle_target_connect_result(
                    target_result,
                    tunnel_config,
                    &frame_tx,
                    &tcp_frame_tx,
                    &mut manager,
                    &mut streams,
                    &stream_event_tx,
                ).await?;
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
                        handle_answer_frame(
                            frame,
                            &forward_table,
                            &remote_peer_id,
                            &frame_tx,
                            &target_connect_tx,
                            &mut manager,
                            &mut streams,
                        ).await?;
                    }
                    Some(DataChannelEvent::Closed) | None => break Ok(()),
                    Some(DataChannelEvent::Open) => {}
                }
            }
        }
    };

    cleanup_all_streams(&mut manager, &mut streams);
    writer.abort();
    result
}

async fn register_offer_client(
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
    frame_tx
        .send(TunnelFrame::open(stream_id, OpenPayload { forward_id })?)
        .await
        .map_err(|_| TunnelError::WriterClosed)
}

// Borrowed session I/O shared by the offer-side frame handler: the tunnel
// config plus the channels it writes frames and stream events to.
struct OfferIo<'a> {
    tunnel_config: &'a TunnelConfig,
    frame_tx: &'a mpsc::Sender<TunnelFrame>,
    tcp_frame_tx: &'a mpsc::Sender<TunnelFrame>,
    stream_event_tx: &'a mpsc::Sender<StreamRuntimeEvent>,
}

async fn handle_offer_frame(
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
            opening_streams.remove(&frame.stream_id);
            close_stream(frame.stream_id, manager, streams).await?;
        }
        TunnelFrameType::Error => {
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

async fn handle_answer_frame(
    frame: TunnelFrame,
    forward_table: &ForwardTable,
    remote_peer_id: &PeerId,
    frame_tx: &mpsc::Sender<TunnelFrame>,
    target_connect_tx: &mpsc::Sender<TargetConnectResult>,
    manager: &mut StreamManager,
    streams: &mut HashMap<u32, RuntimeStream>,
) -> Result<(), TunnelError> {
    match frame.frame_type {
        TunnelFrameType::Open => {
            if manager.get(frame.stream_id).is_ok() {
                send_stream_error(
                    frame_tx,
                    frame.stream_id,
                    "stream_already_exists",
                    "stream already exists",
                )
                .await?;
                return Ok(());
            }
            let open = match frame.open_payload() {
                Ok(open) => open,
                Err(error) => {
                    tracing::debug!(
                        stream_id = frame.stream_id,
                        %error,
                        "rejecting malformed OPEN payload"
                    );
                    send_stream_error(
                        frame_tx,
                        frame.stream_id,
                        "protocol_error",
                        "malformed OPEN payload",
                    )
                    .await?;
                    return Ok(());
                }
            };
            let target = match forward_table.target_for(&open.forward_id, remote_peer_id) {
                Ok(target) => target,
                Err(ForwardLookupError::UnknownForward) => {
                    send_stream_error(
                        frame_tx,
                        frame.stream_id,
                        "unknown_forward",
                        "unknown forward",
                    )
                    .await?;
                    return Ok(());
                }
                Err(ForwardLookupError::ForbiddenForward) => {
                    send_stream_error(
                        frame_tx,
                        frame.stream_id,
                        "forbidden_forward",
                        "forward forbidden",
                    )
                    .await?;
                    return Ok(());
                }
                Err(_) => {
                    send_stream_error(
                        frame_tx,
                        frame.stream_id,
                        "protocol_error",
                        "invalid forward",
                    )
                    .await?;
                    return Ok(());
                }
            };
            manager.register(StreamState {
                stream_id: frame.stream_id,
                forward_id: open.forward_id.clone(),
                lifecycle: StreamLifecycle::Opening,
                remote_peer_id: remote_peer_id.clone(),
            })?;
            let connect_tx = target_connect_tx.clone();
            let stream_id = frame.stream_id;
            let forward_id = open.forward_id;
            let task = tokio::spawn(async move {
                let result = tokio::time::timeout(
                    ANSWER_TARGET_CONNECT_TIMEOUT,
                    TcpStream::connect((target.host.as_str(), target.port)),
                )
                .await
                .map_err(|_| "target connect timed out".to_owned())
                .and_then(|connect_result| {
                    connect_result.map_err(|error| format!("target connect failed: {error}"))
                });
                let _ =
                    connect_tx.send(TargetConnectResult { stream_id, forward_id, result }).await;
            });
            streams.insert(frame.stream_id, RuntimeStream::opening(task));
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
            close_stream(frame.stream_id, manager, streams).await?;
        }
        TunnelFrameType::Error => {
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

async fn handle_target_connect_result(
    target_result: TargetConnectResult,
    tunnel_config: &TunnelConfig,
    frame_tx: &mpsc::Sender<TunnelFrame>,
    tcp_frame_tx: &mpsc::Sender<TunnelFrame>,
    manager: &mut StreamManager,
    streams: &mut HashMap<u32, RuntimeStream>,
    stream_event_tx: &mpsc::Sender<StreamRuntimeEvent>,
) -> Result<(), TunnelError> {
    let Ok(stream_state) = manager.get_mut(target_result.stream_id) else {
        return Ok(());
    };
    if stream_state.lifecycle != StreamLifecycle::Opening {
        return Ok(());
    }

    match target_result.result {
        Ok(stream) => {
            stream_state.lifecycle = StreamLifecycle::Open;
            stream_state.forward_id = target_result.forward_id;
            let runtime_stream = spawn_tcp_bridge(
                target_result.stream_id,
                stream,
                tunnel_config,
                tcp_frame_tx,
                stream_event_tx,
            );
            streams.insert(target_result.stream_id, runtime_stream);
            frame_tx
                .send(TunnelFrame::open_ack(target_result.stream_id))
                .await
                .map_err(|_| TunnelError::WriterClosed)?;
        }
        Err(error) => {
            manager.remove(target_result.stream_id);
            streams.remove(&target_result.stream_id);
            tracing::debug!(stream_id = target_result.stream_id, error, "target connect failed");
            send_stream_error(
                frame_tx,
                target_result.stream_id,
                FailureCode::TargetConnectFailed.as_str(),
                "target connect failed",
            )
            .await?;
        }
    }
    Ok(())
}

fn spawn_tcp_bridge(
    stream_id: u32,
    stream: TcpStream,
    tunnel_config: &TunnelConfig,
    tcp_frame_tx: &mpsc::Sender<TunnelFrame>,
    stream_event_tx: &mpsc::Sender<StreamRuntimeEvent>,
) -> RuntimeStream {
    let (mut reader, mut writer) = stream.into_split();
    let (write_tx, mut write_rx) = mpsc::channel::<TcpWriteCommand>(DEFAULT_STREAM_QUEUE_MESSAGES);
    let read_frame_tx = tcp_frame_tx.clone();
    let read_event_tx = stream_event_tx.clone();
    let read_chunk_size = tunnel_config.read_chunk_size;
    let read_task = tokio::spawn(async move {
        let mut buffer = vec![0_u8; read_chunk_size];
        loop {
            match reader.read(&mut buffer).await {
                Ok(0) => {
                    let _ = read_frame_tx.send(TunnelFrame::close(stream_id)).await;
                    let _ = read_event_tx.send(StreamRuntimeEvent::LocalEof { stream_id }).await;
                    break;
                }
                Ok(read) => {
                    if read_frame_tx
                        .send(TunnelFrame::data(stream_id, buffer[..read].to_vec()))
                        .await
                        .is_err()
                    {
                        break;
                    }
                }
                Err(error) => {
                    let _ = read_frame_tx
                        .send(
                            TunnelFrame::error(
                                stream_id,
                                ErrorPayload {
                                    code: "local_io_error".to_owned(),
                                    message: "local tcp read failed".to_owned(),
                                },
                            )
                            .expect("static error payload should encode"),
                        )
                        .await;
                    let _ = read_event_tx
                        .send(StreamRuntimeEvent::LocalIoError {
                            stream_id,
                            message: format!("local tcp read failed: {error}"),
                            notify_peer: false,
                        })
                        .await;
                    break;
                }
            }
        }
    });
    let write_event_tx = stream_event_tx.clone();
    let write_task = tokio::spawn(async move {
        while let Some(command) = write_rx.recv().await {
            match command {
                TcpWriteCommand::Data(payload) => {
                    if let Err(error) = writer.write_all(&payload).await {
                        let _ = write_event_tx
                            .send(StreamRuntimeEvent::LocalIoError {
                                stream_id,
                                message: format!("local tcp write failed: {error}"),
                                notify_peer: true,
                            })
                            .await;
                        break;
                    }
                }
                TcpWriteCommand::Close => {
                    let _ = writer.shutdown().await;
                    break;
                }
            }
        }
    });
    RuntimeStream::open(write_tx, vec![read_task, write_task])
}

async fn handle_stream_runtime_event(
    event: StreamRuntimeEvent,
    frame_tx: &mpsc::Sender<TunnelFrame>,
    manager: &mut StreamManager,
    streams: &mut HashMap<u32, RuntimeStream>,
) -> Result<(), TunnelError> {
    match event {
        StreamRuntimeEvent::LocalEof { stream_id } => {
            close_stream(stream_id, manager, streams).await?;
        }
        StreamRuntimeEvent::LocalIoError { stream_id, message, notify_peer } => {
            if notify_peer && (manager.get(stream_id).is_ok() || streams.contains_key(&stream_id)) {
                send_stream_error(frame_tx, stream_id, "local_io_error", &message).await?;
            }
            close_stream(stream_id, manager, streams).await?;
        }
    }
    Ok(())
}

async fn handle_closed_stream_queue(
    stream_id: u32,
    frame_tx: &mpsc::Sender<TunnelFrame>,
    manager: &mut StreamManager,
    streams: &mut HashMap<u32, RuntimeStream>,
) -> Result<(), TunnelError> {
    tracing::debug!(stream_id, "stream write queue closed");
    if manager.get(stream_id).is_ok() || streams.contains_key(&stream_id) {
        send_stream_error(frame_tx, stream_id, "local_io_error", "stream write queue closed")
            .await?;
    }
    close_stream(stream_id, manager, streams).await
}

async fn close_stream(
    stream_id: u32,
    manager: &mut StreamManager,
    streams: &mut HashMap<u32, RuntimeStream>,
) -> Result<(), TunnelError> {
    manager.remove(stream_id);
    if let Some(stream) = streams.remove(&stream_id) {
        stream.close().await;
    }
    Ok(())
}

fn cleanup_all_streams(manager: &mut StreamManager, streams: &mut HashMap<u32, RuntimeStream>) {
    manager.clear();
    for (_, stream) in streams.drain() {
        stream.abort_all();
    }
}

async fn send_stream_error(
    frame_tx: &mpsc::Sender<TunnelFrame>,
    stream_id: u32,
    code: &str,
    message: &str,
) -> Result<(), TunnelError> {
    frame_tx
        .send(TunnelFrame::error(
            stream_id,
            ErrorPayload { code: code.to_owned(), message: message.to_owned() },
        )?)
        .await
        .map_err(|_| TunnelError::WriterClosed)
}

fn spawn_writer_only(
    data_channel: DataChannelHandle,
    mut outbound_rx: mpsc::Receiver<TunnelFrame>,
    failure_tx: mpsc::Sender<TunnelError>,
) -> JoinHandle<Result<(), TunnelError>> {
    tokio::spawn(async move {
        while let Some(frame) = outbound_rx.recv().await {
            let encoded = match TunnelFrameCodec::encode(&frame) {
                Ok(encoded) => encoded,
                Err(error) => {
                    let _ = failure_tx.send(error).await;
                    return Err(TunnelError::WriterClosed);
                }
            };
            if let Err(error) = data_channel.send(&encoded).await {
                let tunnel_error = TunnelError::WebRtc(error);
                let _ = failure_tx.send(tunnel_error).await;
                return Err(TunnelError::WriterClosed);
            }
        }
        Ok(())
    })
}

#[cfg(test)]
mod tests;
