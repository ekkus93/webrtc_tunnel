use std::collections::HashMap;
use std::time::Duration;

use p2p_core::{
    FailureCode, ForwardLookupError, ForwardTable, PeerId, TunnelConfig, TunnelFrameType,
};
use p2p_webrtc::{DataChannelEvent, DataChannelHandle};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::mpsc;
use tokio::task::JoinHandle;

use crate::{ErrorPayload, OfferClient, OpenPayload, TunnelError, TunnelFrame, TunnelFrameCodec};

pub const DEFAULT_STREAM_QUEUE_MESSAGES: usize = 64;
pub const DEFAULT_WRITER_QUEUE_MESSAGES: usize = 256;
pub const ANSWER_TARGET_CONNECT_TIMEOUT: Duration = Duration::from_secs(10);

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum StreamLifecycle {
    Opening,
    Open,
    LocalClosing,
    RemoteClosing,
    Closed,
    Failed,
}

#[derive(Debug)]
pub struct StreamState {
    pub stream_id: u32,
    pub forward_id: String,
    pub lifecycle: StreamLifecycle,
    pub remote_peer_id: PeerId,
}

enum TcpWriteCommand {
    Data(Vec<u8>),
    Close,
}

enum StreamRuntimeEvent {
    LocalEof { stream_id: u32 },
    LocalIoError { stream_id: u32, message: String, notify_peer: bool },
}

struct RuntimeStream {
    write_tx: Option<mpsc::Sender<TcpWriteCommand>>,
    tasks: Vec<JoinHandle<()>>,
}

impl RuntimeStream {
    fn opening(task: JoinHandle<()>) -> Self {
        Self { write_tx: None, tasks: vec![task] }
    }

    fn open(write_tx: mpsc::Sender<TcpWriteCommand>, tasks: Vec<JoinHandle<()>>) -> Self {
        Self { write_tx: Some(write_tx), tasks }
    }

    fn write_tx(&self) -> Option<&mpsc::Sender<TcpWriteCommand>> {
        self.write_tx.as_ref()
    }

    async fn close(mut self) {
        if let Some(read_task) = self.tasks.first() {
            read_task.abort();
        }
        let close_queued = self
            .write_tx
            .take()
            .is_some_and(|write_tx| write_tx.try_send(TcpWriteCommand::Close).is_ok());
        if self.tasks.len() > 1 {
            let mut write_task = self.tasks.swap_remove(1);
            if close_queued {
                tokio::select! {
                    _ = &mut write_task => {}
                    _ = tokio::time::sleep(Duration::from_millis(250)) => {
                        write_task.abort();
                    }
                }
            } else {
                write_task.abort();
            }
        }
        for task in &self.tasks {
            task.abort();
        }
    }

    fn abort_all(mut self) {
        self.write_tx.take();
        for task in &self.tasks {
            task.abort();
        }
    }
}

impl Drop for RuntimeStream {
    fn drop(&mut self) {
        for task in &self.tasks {
            task.abort();
        }
    }
}

struct TargetConnectResult {
    stream_id: u32,
    forward_id: String,
    result: Result<TcpStream, String>,
}

#[derive(Debug, Default)]
pub struct StreamIdAllocator {
    next: u32,
}

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

    let result = loop {
        tokio::select! {
            client = accepted_clients.recv() => {
                let Some(client) = client else {
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
                            tunnel_config,
                            &frame_tx,
                            &tcp_frame_tx,
                            &mut manager,
                            &mut opening_streams,
                            &mut streams,
                            &stream_event_tx,
                        ).await?;
                        if manager.active_count() == 0 && opening_streams.is_empty() && streams.is_empty() {
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
                            tunnel_config,
                            &forward_table,
                            &remote_peer_id,
                            &frame_tx,
                            &tcp_frame_tx,
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

#[allow(clippy::too_many_arguments)]
async fn handle_offer_frame(
    frame: TunnelFrame,
    tunnel_config: &TunnelConfig,
    frame_tx: &mpsc::Sender<TunnelFrame>,
    tcp_frame_tx: &mpsc::Sender<TunnelFrame>,
    manager: &mut StreamManager,
    opening_streams: &mut HashMap<u32, TcpStream>,
    streams: &mut HashMap<u32, RuntimeStream>,
    stream_event_tx: &mpsc::Sender<StreamRuntimeEvent>,
) -> Result<(), TunnelError> {
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

#[allow(clippy::too_many_arguments)]
async fn handle_answer_frame(
    frame: TunnelFrame,
    _tunnel_config: &TunnelConfig,
    forward_table: &ForwardTable,
    remote_peer_id: &PeerId,
    frame_tx: &mpsc::Sender<TunnelFrame>,
    _tcp_frame_tx: &mpsc::Sender<TunnelFrame>,
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
            let open = frame.open_payload()?;
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

impl StreamIdAllocator {
    pub fn new() -> Self {
        Self { next: 1 }
    }

    pub fn allocate(&mut self) -> Result<u32, TunnelError> {
        if self.next == 0 {
            return Err(TunnelError::StreamIdExhausted);
        }
        let stream_id = self.next;
        self.next = self.next.checked_add(1).unwrap_or(0);
        Ok(stream_id)
    }
}

#[derive(Debug, Default)]
pub struct StreamManager {
    allocator: StreamIdAllocator,
    streams: HashMap<u32, StreamState>,
}

impl StreamManager {
    pub fn new() -> Self {
        Self { allocator: StreamIdAllocator::new(), streams: HashMap::new() }
    }

    pub fn allocate_stream_id(&mut self) -> Result<u32, TunnelError> {
        self.allocator.allocate()
    }

    pub fn register(&mut self, stream: StreamState) -> Result<(), TunnelError> {
        if stream.stream_id == 0 {
            return Err(TunnelError::ReservedStreamId);
        }
        if self.streams.contains_key(&stream.stream_id) {
            return Err(TunnelError::StreamAlreadyExists(stream.stream_id));
        }
        self.streams.insert(stream.stream_id, stream);
        Ok(())
    }

    pub fn get(&self, stream_id: u32) -> Result<&StreamState, TunnelError> {
        self.streams.get(&stream_id).ok_or(TunnelError::StreamNotFound(stream_id))
    }

    pub fn get_mut(&mut self, stream_id: u32) -> Result<&mut StreamState, TunnelError> {
        self.streams.get_mut(&stream_id).ok_or(TunnelError::StreamNotFound(stream_id))
    }

    pub fn remove(&mut self, stream_id: u32) -> Option<StreamState> {
        self.streams.remove(&stream_id)
    }

    pub fn active_count(&self) -> usize {
        self.streams.len()
    }

    pub fn clear(&mut self) {
        self.streams.clear();
    }
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
mod tests {
    use std::collections::HashMap;
    use std::future;
    use std::time::Duration;

    use p2p_core::{
        ForwardAnswerConfig, ForwardOfferConfig, ForwardRule, ForwardTable, TunnelConfig,
        TunnelFrameType, WebRtcConfig,
    };
    use p2p_webrtc::WebRtcPeer;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::{TcpListener, TcpStream};
    use tokio::sync::{mpsc, oneshot};
    use tokio::time::timeout;

    use super::{
        RuntimeStream, StreamIdAllocator, StreamLifecycle, StreamManager, StreamRuntimeEvent,
        StreamState, TcpWriteCommand, cleanup_all_streams, close_stream, handle_answer_frame,
        handle_offer_frame, handle_stream_runtime_event, run_multiplex_answer, run_multiplex_offer,
        spawn_tcp_bridge, spawn_writer_only,
    };
    use crate::{ErrorPayload, OfferClient, OpenPayload, TunnelError, TunnelFrame};

    fn stream(stream_id: u32, forward_id: &str) -> StreamState {
        StreamState {
            stream_id,
            forward_id: forward_id.to_owned(),
            lifecycle: StreamLifecycle::Opening,
            remote_peer_id: "offer-home".parse().expect("peer id"),
        }
    }

    fn sample_tunnel_config() -> TunnelConfig {
        TunnelConfig { read_chunk_size: 16_384, local_eof_grace_ms: 250, remote_eof_grace_ms: 250 }
    }

    fn sample_webrtc_config() -> WebRtcConfig {
        WebRtcConfig { stun_urls: Vec::new(), enable_trickle_ice: false, enable_ice_restart: true }
    }

    fn forward_table(target_port: u16) -> ForwardTable {
        ForwardTable::new(&[ForwardRule {
            id: "ssh".to_owned(),
            offer: Some(ForwardOfferConfig {
                listen_host: "127.0.0.1".to_owned(),
                listen_port: 2223,
            }),
            answer: Some(ForwardAnswerConfig {
                target_host: "127.0.0.1".to_owned(),
                target_port,
                allow_remote_peers: vec!["offer-home".parse().expect("peer id")],
            }),
        }])
    }

    fn forward_table_with_target(target_host: &str, target_port: u16) -> ForwardTable {
        ForwardTable::new(&[ForwardRule {
            id: "ssh".to_owned(),
            offer: Some(ForwardOfferConfig {
                listen_host: "127.0.0.1".to_owned(),
                listen_port: 2223,
            }),
            answer: Some(ForwardAnswerConfig {
                target_host: target_host.to_owned(),
                target_port,
                allow_remote_peers: vec!["offer-home".parse().expect("peer id")],
            }),
        }])
    }

    fn multi_forward_table(ssh_port: u16, web_port: u16) -> ForwardTable {
        ForwardTable::new(&[
            ForwardRule {
                id: "ssh".to_owned(),
                offer: Some(ForwardOfferConfig {
                    listen_host: "127.0.0.1".to_owned(),
                    listen_port: 2223,
                }),
                answer: Some(ForwardAnswerConfig {
                    target_host: "127.0.0.1".to_owned(),
                    target_port: ssh_port,
                    allow_remote_peers: vec!["offer-home".parse().expect("peer id")],
                }),
            },
            ForwardRule {
                id: "web-ui".to_owned(),
                offer: Some(ForwardOfferConfig {
                    listen_host: "127.0.0.1".to_owned(),
                    listen_port: 8080,
                }),
                answer: Some(ForwardAnswerConfig {
                    target_host: "127.0.0.1".to_owned(),
                    target_port: web_port,
                    allow_remote_peers: vec!["offer-home".parse().expect("peer id")],
                }),
            },
        ])
    }

    async fn connected_channels()
    -> (WebRtcPeer, WebRtcPeer, p2p_webrtc::DataChannelHandle, p2p_webrtc::DataChannelHandle) {
        let offer_peer =
            WebRtcPeer::new(&sample_webrtc_config()).await.expect("offer peer should build");
        let answer_peer =
            WebRtcPeer::new(&sample_webrtc_config()).await.expect("answer peer should build");

        let offer_channel =
            offer_peer.create_data_channel().await.expect("offer data channel should build");
        let offer_sdp = offer_peer.create_offer().await.expect("offer SDP should build");
        answer_peer.apply_remote_offer(&offer_sdp).await.expect("answer should accept offer");
        let answer_sdp = answer_peer.create_answer().await.expect("answer SDP should build");
        offer_peer.apply_remote_answer(&answer_sdp).await.expect("offer should accept answer");

        let answer_channel =
            timeout(Duration::from_secs(10), answer_peer.next_incoming_data_channel())
                .await
                .expect("incoming data channel should arrive")
                .expect("incoming data channel stream should yield")
                .expect("incoming data channel should be accepted");

        offer_channel
            .wait_for_open(Duration::from_secs(10))
            .await
            .expect("offer data channel should open");

        (offer_peer, answer_peer, offer_channel, answer_channel)
    }

    #[test]
    fn stream_id_allocator_starts_at_one_and_does_not_reuse() {
        let mut allocator = StreamIdAllocator::new();
        assert_eq!(allocator.allocate().expect("stream 1"), 1);
        assert_eq!(allocator.allocate().expect("stream 2"), 2);
        assert_eq!(allocator.allocate().expect("stream 3"), 3);
    }

    #[test]
    fn stream_manager_rejects_duplicate_registration() {
        let mut manager = StreamManager::new();
        manager.register(stream(1, "ssh")).expect("first register");
        assert!(matches!(
            manager.register(stream(1, "ssh")),
            Err(TunnelError::StreamAlreadyExists(1))
        ));
    }

    #[test]
    fn stream_manager_rejects_stream_zero() {
        let mut manager = StreamManager::new();
        assert!(matches!(manager.register(stream(0, "ssh")), Err(TunnelError::ReservedStreamId)));
    }

    #[test]
    fn closing_one_stream_does_not_remove_another() {
        let mut manager = StreamManager::new();
        manager.register(stream(1, "ssh")).expect("stream 1");
        manager.register(stream(2, "web-ui")).expect("stream 2");
        manager.remove(1).expect("stream 1 removed");
        assert_eq!(manager.active_count(), 1);
        assert_eq!(manager.get(2).expect("stream 2").forward_id, "web-ui");
    }

    #[test]
    fn unknown_stream_lookup_returns_error() {
        let manager = StreamManager::new();
        assert!(matches!(manager.get(99), Err(TunnelError::StreamNotFound(99))));
    }

    #[test]
    fn forward_table_lookups_targets_and_permissions() {
        let table = ForwardTable::new(&[
            ForwardRule {
                id: "ssh".to_owned(),
                offer: Some(ForwardOfferConfig {
                    listen_host: "127.0.0.1".to_owned(),
                    listen_port: 2223,
                }),
                answer: Some(ForwardAnswerConfig {
                    target_host: "127.0.0.1".to_owned(),
                    target_port: 22,
                    allow_remote_peers: vec!["offer-home".parse().expect("peer id")],
                }),
            },
            ForwardRule {
                id: "web-ui".to_owned(),
                offer: Some(ForwardOfferConfig {
                    listen_host: "127.0.0.1".to_owned(),
                    listen_port: 8080,
                }),
                answer: Some(ForwardAnswerConfig {
                    target_host: "127.0.0.1".to_owned(),
                    target_port: 8080,
                    allow_remote_peers: vec!["offer-home".parse().expect("peer id")],
                }),
            },
        ]);

        let target =
            table.target_for("web-ui", &"offer-home".parse().expect("peer id")).expect("target");
        assert_eq!(target.port, 8080);
        assert!(table.target_for("missing", &"offer-home".parse().expect("peer id")).is_err());
        assert!(table.target_for("ssh", &"other-peer".parse().expect("peer id")).is_err());
    }

    #[tokio::test]
    async fn multiplex_open_handshake_bridges_bytes_after_target_connect() {
        let (offer_peer, answer_peer, offer_channel, answer_channel) = connected_channels().await;

        let target_listener =
            TcpListener::bind(("127.0.0.1", 0)).await.expect("target listener should bind");
        let table = forward_table(target_listener.local_addr().expect("target local addr").port());

        let target_task = tokio::spawn(async move {
            let (mut target_stream, _) = target_listener.accept().await.expect("target accept");
            let mut received = [0_u8; 4];
            target_stream.read_exact(&mut received).await.expect("target read");
            assert_eq!(&received, b"ping");
            target_stream.write_all(b"pong").await.expect("target write");
            target_stream.shutdown().await.expect("target shutdown");
        });

        let local_listener =
            TcpListener::bind(("127.0.0.1", 0)).await.expect("local listener should bind");
        let local_addr = local_listener.local_addr().expect("local addr");
        let client_task = tokio::spawn(async move {
            let mut client = TcpStream::connect(local_addr).await.expect("client connect");
            client.write_all(b"ping").await.expect("client write");
            let mut response = [0_u8; 4];
            client.read_exact(&mut response).await.expect("client read");
            assert_eq!(&response, b"pong");
            client.shutdown().await.expect("client shutdown");
        });
        let (offer_stream, _) = local_listener.accept().await.expect("offer accept");

        let answer_tunnel = sample_tunnel_config();
        let answer_task = tokio::spawn(async move {
            run_multiplex_answer(
                answer_channel,
                &answer_tunnel,
                table,
                "offer-home".parse().expect("peer id"),
            )
            .await
        });
        let offer_tunnel = sample_tunnel_config();
        let offer_task = tokio::spawn(async move {
            let (_tx, mut rx) = mpsc::channel(1);
            run_multiplex_offer(
                offer_channel,
                &offer_tunnel,
                OfferClient::new("ssh", offer_stream),
                &mut rx,
            )
            .await
        });

        timeout(Duration::from_secs(10), client_task)
            .await
            .expect("client task should finish")
            .expect("client task should succeed");
        timeout(Duration::from_secs(10), target_task)
            .await
            .expect("target task should finish")
            .expect("target task should succeed");
        timeout(Duration::from_secs(10), offer_task)
            .await
            .expect("offer mux should finish")
            .expect("offer mux join should succeed")
            .expect("offer mux should succeed");

        offer_peer.close().await.expect("offer peer should close");
        answer_peer.close().await.expect("answer peer should close");
        answer_task.abort();
    }

    #[tokio::test]
    async fn target_connect_failure_closes_only_failed_offer_stream() {
        let (offer_peer, answer_peer, offer_channel, answer_channel) = connected_channels().await;

        let probe = TcpListener::bind(("127.0.0.1", 0)).await.expect("probe should bind");
        let table = forward_table(probe.local_addr().expect("probe addr").port());
        drop(probe);

        let local_listener =
            TcpListener::bind(("127.0.0.1", 0)).await.expect("local listener should bind");
        let local_addr = local_listener.local_addr().expect("local addr");
        let mut client = TcpStream::connect(local_addr).await.expect("client connect");
        let (offer_stream, _) = local_listener.accept().await.expect("offer accept");

        let answer_tunnel = sample_tunnel_config();
        let answer_task = tokio::spawn(async move {
            run_multiplex_answer(
                answer_channel,
                &answer_tunnel,
                table,
                "offer-home".parse().expect("peer id"),
            )
            .await
        });
        let offer_tunnel = sample_tunnel_config();
        let offer_result = timeout(Duration::from_secs(10), async move {
            let (_tx, mut rx) = mpsc::channel(1);
            run_multiplex_offer(
                offer_channel,
                &offer_tunnel,
                OfferClient::new("ssh", offer_stream),
                &mut rx,
            )
            .await
        })
        .await
        .expect("offer mux should finish");

        assert!(offer_result.is_ok());
        let mut buffer = [0_u8; 1];
        let read = timeout(Duration::from_secs(10), client.read(&mut buffer))
            .await
            .expect("client read should finish")
            .expect("client read should succeed");
        assert_eq!(read, 0);

        offer_peer.close().await.expect("offer peer should close");
        answer_peer.close().await.expect("answer peer should close");
        answer_task.abort();
    }

    #[tokio::test]
    async fn two_forwards_share_one_data_channel_with_isolated_streams() {
        let (offer_peer, answer_peer, offer_channel, answer_channel) = connected_channels().await;

        let ssh_target = TcpListener::bind(("127.0.0.1", 0)).await.expect("ssh target should bind");
        let web_target = TcpListener::bind(("127.0.0.1", 0)).await.expect("web target should bind");
        let table = multi_forward_table(
            ssh_target.local_addr().expect("ssh target addr").port(),
            web_target.local_addr().expect("web target addr").port(),
        );

        let ssh_target_task = tokio::spawn(async move {
            let (mut target_stream, _) = ssh_target.accept().await.expect("ssh target accept");
            let mut received = [0_u8; 3];
            target_stream.read_exact(&mut received).await.expect("ssh target read");
            assert_eq!(&received, b"ssh");
            target_stream.write_all(b"SSH").await.expect("ssh target write");
            target_stream.shutdown().await.expect("ssh target shutdown");
        });
        let web_target_task = tokio::spawn(async move {
            let (mut target_stream, _) = web_target.accept().await.expect("web target accept");
            let mut received = [0_u8; 3];
            target_stream.read_exact(&mut received).await.expect("web target read");
            assert_eq!(&received, b"web");
            target_stream.write_all(b"WEB").await.expect("web target write");
            target_stream.shutdown().await.expect("web target shutdown");
        });

        let ssh_listener =
            TcpListener::bind(("127.0.0.1", 0)).await.expect("ssh listener should bind");
        let web_listener =
            TcpListener::bind(("127.0.0.1", 0)).await.expect("web listener should bind");
        let ssh_addr = ssh_listener.local_addr().expect("ssh local addr");
        let web_addr = web_listener.local_addr().expect("web local addr");

        let ssh_client_task = tokio::spawn(async move {
            let mut client = TcpStream::connect(ssh_addr).await.expect("ssh client connect");
            client.write_all(b"ssh").await.expect("ssh client write");
            let mut response = [0_u8; 3];
            client.read_exact(&mut response).await.expect("ssh client read");
            assert_eq!(&response, b"SSH");
            client.shutdown().await.expect("ssh client shutdown");
        });
        let web_client_task = tokio::spawn(async move {
            let mut client = TcpStream::connect(web_addr).await.expect("web client connect");
            client.write_all(b"web").await.expect("web client write");
            let mut response = [0_u8; 3];
            client.read_exact(&mut response).await.expect("web client read");
            assert_eq!(&response, b"WEB");
            client.shutdown().await.expect("web client shutdown");
        });

        let (ssh_stream, _) = ssh_listener.accept().await.expect("ssh accept");
        let (web_stream, _) = web_listener.accept().await.expect("web accept");
        let (tx, mut rx) = mpsc::channel(4);
        tx.send(Ok(OfferClient::new("web-ui", web_stream))).await.expect("queue web client");
        drop(tx);

        let answer_tunnel = sample_tunnel_config();
        let answer_task = tokio::spawn(async move {
            run_multiplex_answer(
                answer_channel,
                &answer_tunnel,
                table,
                "offer-home".parse().expect("peer id"),
            )
            .await
        });
        let offer_tunnel = sample_tunnel_config();
        let offer_task = tokio::spawn(async move {
            run_multiplex_offer(
                offer_channel,
                &offer_tunnel,
                OfferClient::new("ssh", ssh_stream),
                &mut rx,
            )
            .await
        });

        timeout(Duration::from_secs(10), ssh_client_task)
            .await
            .expect("ssh client should finish")
            .expect("ssh client task should succeed");
        timeout(Duration::from_secs(10), web_client_task)
            .await
            .expect("web client should finish")
            .expect("web client task should succeed");
        timeout(Duration::from_secs(10), ssh_target_task)
            .await
            .expect("ssh target should finish")
            .expect("ssh target task should succeed");
        timeout(Duration::from_secs(10), web_target_task)
            .await
            .expect("web target should finish")
            .expect("web target task should succeed");
        timeout(Duration::from_secs(10), offer_task)
            .await
            .expect("offer mux should finish")
            .expect("offer mux join should succeed")
            .expect("offer mux should succeed");

        offer_peer.close().await.expect("offer peer should close");
        answer_peer.close().await.expect("answer peer should close");
        answer_task.abort();
    }

    #[tokio::test]
    async fn browser_like_multiple_streams_on_one_forward_complete_independently() {
        const CLIENTS: usize = 5;
        let (offer_peer, answer_peer, offer_channel, answer_channel) = connected_channels().await;

        let target_listener =
            TcpListener::bind(("127.0.0.1", 0)).await.expect("target listener should bind");
        let table = forward_table(target_listener.local_addr().expect("target local addr").port());
        let target_task = tokio::spawn(async move {
            for _ in 0..CLIENTS {
                let (mut target_stream, _) = target_listener.accept().await.expect("target accept");
                tokio::spawn(async move {
                    let mut received = [0_u8; 4];
                    target_stream.read_exact(&mut received).await.expect("target read");
                    assert_eq!(&received, b"ping");
                    target_stream.write_all(b"pong").await.expect("target write");
                    target_stream.shutdown().await.expect("target shutdown");
                });
            }
        });

        let local_listener =
            TcpListener::bind(("127.0.0.1", 0)).await.expect("local listener should bind");
        let local_addr = local_listener.local_addr().expect("local addr");
        let client_tasks = (0..CLIENTS)
            .map(|_| {
                tokio::spawn(async move {
                    let mut client = TcpStream::connect(local_addr).await.expect("client connect");
                    client.write_all(b"ping").await.expect("client write");
                    let mut response = [0_u8; 4];
                    client.read_exact(&mut response).await.expect("client read");
                    assert_eq!(&response, b"pong");
                    client.shutdown().await.expect("client shutdown");
                })
            })
            .collect::<Vec<_>>();

        let (first_stream, _) = local_listener.accept().await.expect("first accept");
        let (tx, mut rx) = mpsc::channel(CLIENTS);
        let accept_task = tokio::spawn(async move {
            for _ in 1..CLIENTS {
                let (stream, _) = local_listener.accept().await.expect("extra accept");
                tx.send(Ok(OfferClient::new("ssh", stream))).await.expect("queue extra client");
            }
        });

        let answer_tunnel = sample_tunnel_config();
        let answer_task = tokio::spawn(async move {
            run_multiplex_answer(
                answer_channel,
                &answer_tunnel,
                table,
                "offer-home".parse().expect("peer id"),
            )
            .await
        });
        let offer_tunnel = sample_tunnel_config();
        let offer_task = tokio::spawn(async move {
            run_multiplex_offer(
                offer_channel,
                &offer_tunnel,
                OfferClient::new("ssh", first_stream),
                &mut rx,
            )
            .await
        });

        for client_task in client_tasks {
            timeout(Duration::from_secs(10), client_task)
                .await
                .expect("client should finish")
                .expect("client task should succeed");
        }
        timeout(Duration::from_secs(10), accept_task)
            .await
            .expect("accept task should finish")
            .expect("accept task should succeed");
        timeout(Duration::from_secs(10), target_task)
            .await
            .expect("target task should finish")
            .expect("target task should succeed");
        timeout(Duration::from_secs(10), offer_task)
            .await
            .expect("offer mux should finish")
            .expect("offer mux join should succeed")
            .expect("offer mux should succeed");

        offer_peer.close().await.expect("offer peer should close");
        answer_peer.close().await.expect("answer peer should close");
        answer_task.abort();
    }

    #[tokio::test]
    async fn offer_open_ack_transitions_opening_stream_to_open() {
        let listener = TcpListener::bind(("127.0.0.1", 0)).await.expect("listener");
        let client = TcpStream::connect(listener.local_addr().expect("local addr"))
            .await
            .expect("client connect");
        let (tcp_stream, _) = listener.accept().await.expect("accept");
        let mut manager = StreamManager::new();
        manager.register(stream(1, "ssh")).expect("register stream");
        let mut opening_streams = HashMap::from([(1_u32, tcp_stream)]);
        let mut streams = HashMap::new();
        let (frame_tx, _frame_rx) = mpsc::channel(4);
        let (tcp_frame_tx, _tcp_frame_rx) = mpsc::channel(4);
        let (stream_event_tx, _stream_event_rx) = mpsc::channel(4);

        handle_offer_frame(
            TunnelFrame::open_ack(1),
            &sample_tunnel_config(),
            &frame_tx,
            &tcp_frame_tx,
            &mut manager,
            &mut opening_streams,
            &mut streams,
            &stream_event_tx,
        )
        .await
        .expect("ack should handle");

        assert_eq!(manager.get(1).expect("stream").lifecycle, StreamLifecycle::Open);
        assert!(opening_streams.is_empty());
        assert!(streams.contains_key(&1));
        drop(client);
    }

    #[tokio::test]
    async fn offer_open_ack_rejects_non_empty_payload() {
        let listener = TcpListener::bind(("127.0.0.1", 0)).await.expect("listener");
        let client = TcpStream::connect(listener.local_addr().expect("local addr"))
            .await
            .expect("client connect");
        let (tcp_stream, _) = listener.accept().await.expect("accept");
        let mut manager = StreamManager::new();
        manager.register(stream(1, "ssh")).expect("register stream");
        let mut opening_streams = HashMap::from([(1_u32, tcp_stream)]);
        let mut streams = HashMap::new();
        let (frame_tx, mut frame_rx) = mpsc::channel(4);
        let (tcp_frame_tx, _tcp_frame_rx) = mpsc::channel(4);
        let (stream_event_tx, _stream_event_rx) = mpsc::channel(4);

        handle_offer_frame(
            TunnelFrame::open(1, OpenPayload { forward_id: "ssh".to_owned() })
                .expect("malformed ack frame"),
            &sample_tunnel_config(),
            &frame_tx,
            &tcp_frame_tx,
            &mut manager,
            &mut opening_streams,
            &mut streams,
            &stream_event_tx,
        )
        .await
        .expect("malformed ack should be handled as stream error");

        let error = frame_rx.recv().await.expect("protocol error");
        assert_eq!(error.stream_id, 1);
        assert_eq!(error.error_payload().expect("error payload").code, "protocol_error");
        assert!(manager.get(1).is_err());
        assert!(opening_streams.is_empty());
        assert!(streams.is_empty());
        drop(client);
    }

    #[tokio::test]
    async fn duplicate_empty_open_ack_is_ignored() {
        let mut manager = StreamManager::new();
        manager.register(stream(1, "ssh")).expect("register stream");
        manager.get_mut(1).expect("stream").lifecycle = StreamLifecycle::Open;
        let mut opening_streams = HashMap::new();
        let mut streams = HashMap::new();
        let (frame_tx, mut frame_rx) = mpsc::channel(4);
        let (tcp_frame_tx, _tcp_frame_rx) = mpsc::channel(4);
        let (stream_event_tx, _stream_event_rx) = mpsc::channel(4);

        handle_offer_frame(
            TunnelFrame::open_ack(1),
            &sample_tunnel_config(),
            &frame_tx,
            &tcp_frame_tx,
            &mut manager,
            &mut opening_streams,
            &mut streams,
            &stream_event_tx,
        )
        .await
        .expect("duplicate ack should be ignored");

        assert_eq!(manager.get(1).expect("stream").lifecycle, StreamLifecycle::Open);
        assert!(frame_rx.try_recv().is_err());
    }

    struct DropNotifier(Option<oneshot::Sender<()>>);

    impl Drop for DropNotifier {
        fn drop(&mut self) {
            if let Some(tx) = self.0.take() {
                let _ = tx.send(());
            }
        }
    }

    #[tokio::test]
    async fn closing_stream_cancels_owned_tasks() {
        let (read_started_tx, read_started_rx) = oneshot::channel();
        let (read_done_tx, read_done_rx) = oneshot::channel();
        let read_task = tokio::spawn(async move {
            let _notify = DropNotifier(Some(read_done_tx));
            let _ = read_started_tx.send(());
            future::pending::<()>().await;
        });
        let (write_started_tx, write_started_rx) = oneshot::channel();
        let (write_done_tx, write_done_rx) = oneshot::channel();
        let (write_tx, mut write_rx) = mpsc::channel(1);
        let write_task = tokio::spawn(async move {
            let _notify = DropNotifier(Some(write_done_tx));
            let _ = write_started_tx.send(());
            let _ = write_rx.recv().await;
        });
        read_started_rx.await.expect("read task started");
        write_started_rx.await.expect("write task started");
        let mut manager = StreamManager::new();
        manager.register(stream(1, "ssh")).expect("register");
        let mut streams =
            HashMap::from([(1_u32, RuntimeStream::open(write_tx, vec![read_task, write_task]))]);

        close_stream(1, &mut manager, &mut streams).await.expect("close stream");

        timeout(Duration::from_secs(1), read_done_rx)
            .await
            .expect("read task should stop")
            .expect("read notify");
        timeout(Duration::from_secs(1), write_done_rx)
            .await
            .expect("write task should stop")
            .expect("write notify");
        assert_eq!(manager.active_count(), 0);
        assert!(streams.is_empty());
    }

    #[tokio::test]
    async fn local_tcp_eof_sends_close_and_removes_only_that_stream() {
        let listener = TcpListener::bind(("127.0.0.1", 0)).await.expect("listener");
        let client = TcpStream::connect(listener.local_addr().expect("local addr"))
            .await
            .expect("client connect");
        let (stream_a, _) = listener.accept().await.expect("accept stream a");
        let (tcp_frame_tx, mut tcp_frame_rx) = mpsc::channel(4);
        let (stream_event_tx, mut stream_event_rx) = mpsc::channel(4);
        let runtime_stream =
            spawn_tcp_bridge(1, stream_a, &sample_tunnel_config(), &tcp_frame_tx, &stream_event_tx);
        let (other_tx, _other_rx) = mpsc::channel(1);
        let mut manager = StreamManager::new();
        manager.register(stream(1, "ssh")).expect("stream 1");
        manager.get_mut(1).expect("stream 1").lifecycle = StreamLifecycle::Open;
        manager.register(stream(2, "web-ui")).expect("stream 2");
        manager.get_mut(2).expect("stream 2").lifecycle = StreamLifecycle::Open;
        let mut streams = HashMap::from([
            (1_u32, runtime_stream),
            (2_u32, RuntimeStream::open(other_tx, Vec::new())),
        ]);
        let (frame_tx, mut frame_rx) = mpsc::channel(4);

        drop(client);
        let close = timeout(Duration::from_secs(1), tcp_frame_rx.recv())
            .await
            .expect("close frame should arrive")
            .expect("close frame should be sent");
        assert_eq!(close.frame_type, TunnelFrameType::Close);
        assert_eq!(close.stream_id, 1);
        let event = timeout(Duration::from_secs(1), stream_event_rx.recv())
            .await
            .expect("local EOF event should arrive")
            .expect("local EOF event should be sent");
        handle_stream_runtime_event(event, &frame_tx, &mut manager, &mut streams)
            .await
            .expect("local EOF cleanup should succeed");

        assert!(manager.get(1).is_err());
        assert!(manager.get(2).is_ok());
        assert!(!streams.contains_key(&1));
        assert!(streams.contains_key(&2));
        assert!(frame_rx.try_recv().is_err());
        close_stream(1, &mut manager, &mut streams)
            .await
            .expect("duplicate cleanup should be harmless");
        assert!(manager.get(2).is_ok());
    }

    #[tokio::test]
    async fn local_io_error_event_sends_error_and_removes_only_that_stream() {
        let (stream_a_tx, _stream_a_rx) = mpsc::channel(1);
        let (stream_b_tx, _stream_b_rx) = mpsc::channel(1);
        let mut manager = StreamManager::new();
        manager.register(stream(1, "ssh")).expect("stream 1");
        manager.get_mut(1).expect("stream 1").lifecycle = StreamLifecycle::Open;
        manager.register(stream(2, "web-ui")).expect("stream 2");
        manager.get_mut(2).expect("stream 2").lifecycle = StreamLifecycle::Open;
        let mut streams = HashMap::from([
            (1_u32, RuntimeStream::open(stream_a_tx, Vec::new())),
            (2_u32, RuntimeStream::open(stream_b_tx, Vec::new())),
        ]);
        let (frame_tx, mut frame_rx) = mpsc::channel(4);

        handle_stream_runtime_event(
            StreamRuntimeEvent::LocalIoError {
                stream_id: 1,
                message: "local tcp write failed: broken pipe".to_owned(),
                notify_peer: true,
            },
            &frame_tx,
            &mut manager,
            &mut streams,
        )
        .await
        .expect("local I/O failure should be stream-local");

        let error = frame_rx.recv().await.expect("local I/O error frame");
        assert_eq!(error.stream_id, 1);
        assert_eq!(error.error_payload().expect("error payload").code, "local_io_error");
        assert!(manager.get(1).is_err());
        assert!(manager.get(2).is_ok());
        assert!(!streams.contains_key(&1));
        assert!(streams.contains_key(&2));
    }

    #[tokio::test]
    async fn closed_stream_write_queue_is_stream_local() {
        let mut manager = StreamManager::new();
        manager.register(stream(1, "ssh")).expect("stream 1");
        manager.get_mut(1).expect("stream 1").lifecycle = StreamLifecycle::Open;
        manager.register(stream(2, "web-ui")).expect("stream 2");
        manager.get_mut(2).expect("stream 2").lifecycle = StreamLifecycle::Open;
        let (closed_tx, closed_rx) = mpsc::channel(1);
        drop(closed_rx);
        let (other_tx, mut other_rx) = mpsc::channel(1);
        let mut streams = HashMap::from([
            (1_u32, RuntimeStream::open(closed_tx, Vec::new())),
            (2_u32, RuntimeStream::open(other_tx, Vec::new())),
        ]);
        let mut opening_streams = HashMap::new();
        let (frame_tx, mut frame_rx) = mpsc::channel(4);
        let (tcp_frame_tx, _tcp_frame_rx) = mpsc::channel(4);
        let (stream_event_tx, _stream_event_rx) = mpsc::channel(4);

        handle_offer_frame(
            TunnelFrame::data(1, b"payload".to_vec()),
            &sample_tunnel_config(),
            &frame_tx,
            &tcp_frame_tx,
            &mut manager,
            &mut opening_streams,
            &mut streams,
            &stream_event_tx,
        )
        .await
        .expect("closed queue should be stream-local");

        let error = frame_rx.recv().await.expect("closed queue error");
        assert_eq!(error.stream_id, 1);
        assert_eq!(error.error_payload().expect("error payload").code, "local_io_error");
        assert!(manager.get(1).is_err());
        assert!(manager.get(2).is_ok());
        assert!(!streams.contains_key(&1));
        assert!(streams.contains_key(&2));

        handle_offer_frame(
            TunnelFrame::data(2, b"still-open".to_vec()),
            &sample_tunnel_config(),
            &frame_tx,
            &tcp_frame_tx,
            &mut manager,
            &mut opening_streams,
            &mut streams,
            &stream_event_tx,
        )
        .await
        .expect("other stream should remain usable");
        assert!(
            matches!(other_rx.recv().await, Some(TcpWriteCommand::Data(payload)) if payload == b"still-open")
        );
    }

    #[tokio::test]
    async fn late_data_close_and_error_after_cleanup_are_harmless() {
        let mut manager = StreamManager::new();
        let mut opening_streams = HashMap::new();
        let mut streams = HashMap::new();
        let (frame_tx, mut frame_rx) = mpsc::channel(4);
        let (tcp_frame_tx, _tcp_frame_rx) = mpsc::channel(4);
        let (stream_event_tx, _stream_event_rx) = mpsc::channel(4);

        for frame in [
            TunnelFrame::data(1, b"late".to_vec()),
            TunnelFrame::close(1),
            TunnelFrame::error(
                1,
                ErrorPayload { code: "local_io_error".to_owned(), message: "late".to_owned() },
            )
            .expect("error frame"),
        ] {
            handle_offer_frame(
                frame,
                &sample_tunnel_config(),
                &frame_tx,
                &tcp_frame_tx,
                &mut manager,
                &mut opening_streams,
                &mut streams,
                &stream_event_tx,
            )
            .await
            .expect("late frame should be harmless");
        }

        assert_eq!(manager.active_count(), 0);
        assert!(streams.is_empty());
        assert!(frame_rx.try_recv().is_err());
    }

    #[tokio::test]
    async fn session_cleanup_drains_and_aborts_stream_tasks() {
        let (read_started_tx, read_started_rx) = oneshot::channel();
        let (read_done_tx, read_done_rx) = oneshot::channel();
        let read_task = tokio::spawn(async move {
            let _notify = DropNotifier(Some(read_done_tx));
            let _ = read_started_tx.send(());
            future::pending::<()>().await;
        });
        let (write_started_tx, write_started_rx) = oneshot::channel();
        let (write_done_tx, write_done_rx) = oneshot::channel();
        let (write_tx, mut write_rx) = mpsc::channel(1);
        let write_task = tokio::spawn(async move {
            let _notify = DropNotifier(Some(write_done_tx));
            let _ = write_started_tx.send(());
            let _ = write_rx.recv().await;
        });
        read_started_rx.await.expect("read task started");
        write_started_rx.await.expect("write task started");
        let mut manager = StreamManager::new();
        manager.register(stream(1, "ssh")).expect("stream 1");
        let mut streams =
            HashMap::from([(1_u32, RuntimeStream::open(write_tx, vec![read_task, write_task]))]);

        cleanup_all_streams(&mut manager, &mut streams);
        cleanup_all_streams(&mut manager, &mut streams);

        timeout(Duration::from_secs(1), read_done_rx)
            .await
            .expect("read task should abort")
            .expect("read task drop notify");
        timeout(Duration::from_secs(1), write_done_rx)
            .await
            .expect("write task should abort")
            .expect("write task drop notify");
        assert_eq!(manager.active_count(), 0);
        assert!(streams.is_empty());
    }

    #[tokio::test]
    async fn answer_open_starts_target_connect_without_blocking_dispatcher() {
        let table = forward_table_with_target("10.255.255.1", 65_000);
        let mut manager = StreamManager::new();
        let mut streams = HashMap::new();
        let (frame_tx, _frame_rx) = mpsc::channel(4);
        let (tcp_frame_tx, _tcp_frame_rx) = mpsc::channel(4);
        let (target_connect_tx, _target_connect_rx) = mpsc::channel(4);

        timeout(
            Duration::from_millis(100),
            handle_answer_frame(
                TunnelFrame::open(7, OpenPayload { forward_id: "ssh".to_owned() })
                    .expect("open frame"),
                &sample_tunnel_config(),
                &table,
                &"offer-home".parse().expect("peer id"),
                &frame_tx,
                &tcp_frame_tx,
                &target_connect_tx,
                &mut manager,
                &mut streams,
            ),
        )
        .await
        .expect("OPEN handling must not wait for target connect")
        .expect("OPEN should be accepted");

        assert_eq!(manager.get(7).expect("stream").lifecycle, StreamLifecycle::Opening);
        assert!(streams.contains_key(&7));
        close_stream(7, &mut manager, &mut streams).await.expect("abort pending connect");
    }

    #[tokio::test]
    async fn writer_encode_failure_notifies_runtime() {
        let (offer_peer, answer_peer, offer_channel, _answer_channel) = connected_channels().await;
        let (frame_tx, frame_rx) = mpsc::channel(1);
        let (failure_tx, mut failure_rx) = mpsc::channel(1);
        let writer = spawn_writer_only(offer_channel, frame_rx, failure_tx);

        frame_tx
            .send(TunnelFrame::new(TunnelFrameType::Data, 0, Vec::new()))
            .await
            .expect("send invalid frame to writer");
        let error = timeout(Duration::from_secs(1), failure_rx.recv())
            .await
            .expect("writer failure should be reported")
            .expect("writer failure should be present");
        assert!(matches!(error, TunnelError::ReservedStreamId));

        writer.abort();
        offer_peer.close().await.expect("offer close");
        answer_peer.close().await.expect("answer close");
    }

    #[tokio::test]
    async fn data_to_unknown_stream_is_ignored() {
        let mut manager = StreamManager::new();
        let mut opening_streams = HashMap::new();
        let mut streams = HashMap::new();
        let (frame_tx, mut frame_rx) = mpsc::channel(4);
        let (tcp_frame_tx, _tcp_frame_rx) = mpsc::channel(4);
        let (stream_event_tx, _stream_event_rx) = mpsc::channel(4);

        handle_offer_frame(
            TunnelFrame::data(99, b"lost".to_vec()),
            &sample_tunnel_config(),
            &frame_tx,
            &tcp_frame_tx,
            &mut manager,
            &mut opening_streams,
            &mut streams,
            &stream_event_tx,
        )
        .await
        .expect("unknown data should be ignored");

        assert!(frame_rx.try_recv().is_err());
    }

    #[tokio::test]
    async fn duplicate_close_is_harmless() {
        let mut manager = StreamManager::new();
        manager.register(stream(1, "ssh")).expect("register stream");
        let mut opening_streams = HashMap::new();
        let mut streams = HashMap::new();
        let (frame_tx, _frame_rx) = mpsc::channel(4);
        let (tcp_frame_tx, _tcp_frame_rx) = mpsc::channel(4);
        let (stream_event_tx, _stream_event_rx) = mpsc::channel(4);

        for _ in 0..2 {
            handle_offer_frame(
                TunnelFrame::close(1),
                &sample_tunnel_config(),
                &frame_tx,
                &tcp_frame_tx,
                &mut manager,
                &mut opening_streams,
                &mut streams,
                &stream_event_tx,
            )
            .await
            .expect("close should be idempotent");
        }

        assert_eq!(manager.active_count(), 0);
    }

    #[tokio::test]
    async fn queue_overflow_fails_only_target_stream() {
        let mut manager = StreamManager::new();
        manager.register(stream(1, "ssh")).expect("stream 1");
        manager.register(stream(2, "web-ui")).expect("stream 2");
        let (full_tx, mut full_rx) = mpsc::channel(1);
        assert!(full_tx.try_send(TcpWriteCommand::Data(b"queued".to_vec())).is_ok());
        let (other_tx, _other_rx) = mpsc::channel(1);
        let mut streams = HashMap::from([
            (1_u32, RuntimeStream::open(full_tx, Vec::new())),
            (2_u32, RuntimeStream::open(other_tx, Vec::new())),
        ]);
        let mut opening_streams = HashMap::new();
        let (frame_tx, mut frame_rx) = mpsc::channel(4);
        let (tcp_frame_tx, _tcp_frame_rx) = mpsc::channel(4);
        let (stream_event_tx, _stream_event_rx) = mpsc::channel(4);

        handle_offer_frame(
            TunnelFrame::data(1, b"overflow".to_vec()),
            &sample_tunnel_config(),
            &frame_tx,
            &tcp_frame_tx,
            &mut manager,
            &mut opening_streams,
            &mut streams,
            &stream_event_tx,
        )
        .await
        .expect("overflow should be stream-local");

        let error = frame_rx.recv().await.expect("queue overflow error");
        assert_eq!(error.stream_id, 1);
        assert_eq!(error.error_payload().expect("error payload").code, "queue_overflow");
        assert!(manager.get(1).is_err());
        assert!(manager.get(2).is_ok());
        assert!(streams.contains_key(&2));
        assert!(matches!(full_rx.recv().await, Some(TcpWriteCommand::Data(_))));
    }
}
