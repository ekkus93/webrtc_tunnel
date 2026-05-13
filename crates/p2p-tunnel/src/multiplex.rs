use std::collections::HashMap;

use p2p_core::{
    FailureCode, ForwardLookupError, ForwardTable, PeerId, TunnelConfig, TunnelFrameType,
};
use p2p_webrtc::{DataChannelEvent, DataChannelHandle};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::{mpsc, oneshot};
use tokio::task::JoinHandle;

use crate::{ErrorPayload, OfferClient, OpenPayload, TunnelError, TunnelFrame, TunnelFrameCodec};

pub const DEFAULT_STREAM_QUEUE_MESSAGES: usize = 64;
pub const DEFAULT_WRITER_QUEUE_MESSAGES: usize = 256;

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

struct RuntimeStream {
    write_tx: mpsc::Sender<TcpWriteCommand>,
}

#[derive(Debug, Default)]
pub struct StreamIdAllocator {
    next: u32,
}

pub async fn run_multiplex_offer(
    data_channel: DataChannelHandle,
    tunnel_config: &TunnelConfig,
    initial_client: OfferClient,
    accepted_clients: &mut mpsc::Receiver<Result<OfferClient, TunnelError>>,
) -> Result<(), TunnelError> {
    let (frame_tx, frame_rx) = mpsc::channel(DEFAULT_WRITER_QUEUE_MESSAGES);
    let writer = spawn_writer_only(data_channel.clone(), frame_rx);
    let mut manager = StreamManager::new();
    let mut streams: HashMap<u32, RuntimeStream> = HashMap::new();
    let mut opening_streams: HashMap<u32, TcpStream> = HashMap::new();
    let (tcp_frame_tx, mut tcp_frame_rx) = mpsc::channel(DEFAULT_WRITER_QUEUE_MESSAGES);

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
    let writer = spawn_writer_only(data_channel.clone(), frame_rx);
    let mut manager = StreamManager::new();
    let mut streams: HashMap<u32, RuntimeStream> = HashMap::new();
    let (tcp_frame_tx, mut tcp_frame_rx) = mpsc::channel(DEFAULT_WRITER_QUEUE_MESSAGES);

    let result = loop {
        tokio::select! {
            frame = tcp_frame_rx.recv() => {
                let Some(frame) = frame else {
                    continue;
                };
                frame_tx.send(frame).await.map_err(|_| TunnelError::WriterClosed)?;
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
) -> Result<(), TunnelError> {
    match frame.frame_type {
        TunnelFrameType::Open => {
            let stream_id = frame.stream_id;
            let Some(stream) = opening_streams.remove(&stream_id) else {
                return Ok(());
            };
            manager.get_mut(stream_id)?.lifecycle = StreamLifecycle::Open;
            let runtime_stream = spawn_tcp_bridge(stream_id, stream, tunnel_config, tcp_frame_tx);
            streams.insert(stream_id, runtime_stream);
        }
        TunnelFrameType::Data => {
            if let Some(stream) = streams.get(&frame.stream_id) {
                stream
                    .write_tx
                    .send(TcpWriteCommand::Data(frame.payload))
                    .await
                    .map_err(|_| TunnelError::StreamNotFound(frame.stream_id))?;
            } else {
                send_stream_error(
                    frame_tx,
                    frame.stream_id,
                    "stream_not_found",
                    "stream not found",
                )
                .await?;
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
    tunnel_config: &TunnelConfig,
    forward_table: &ForwardTable,
    remote_peer_id: &PeerId,
    frame_tx: &mpsc::Sender<TunnelFrame>,
    tcp_frame_tx: &mpsc::Sender<TunnelFrame>,
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
            match TcpStream::connect((target.host.as_str(), target.port)).await {
                Ok(stream) => {
                    manager.register(StreamState {
                        stream_id: frame.stream_id,
                        forward_id: open.forward_id,
                        lifecycle: StreamLifecycle::Open,
                        remote_peer_id: remote_peer_id.clone(),
                    })?;
                    let runtime_stream =
                        spawn_tcp_bridge(frame.stream_id, stream, tunnel_config, tcp_frame_tx);
                    streams.insert(frame.stream_id, runtime_stream);
                    frame_tx
                        .send(TunnelFrame::open_ack(frame.stream_id))
                        .await
                        .map_err(|_| TunnelError::WriterClosed)?;
                }
                Err(_) => {
                    send_stream_error(
                        frame_tx,
                        frame.stream_id,
                        FailureCode::TargetConnectFailed.as_str(),
                        "target connect failed",
                    )
                    .await?;
                }
            }
        }
        TunnelFrameType::Data => {
            if let Some(stream) = streams.get(&frame.stream_id) {
                stream
                    .write_tx
                    .send(TcpWriteCommand::Data(frame.payload))
                    .await
                    .map_err(|_| TunnelError::StreamNotFound(frame.stream_id))?;
            } else {
                send_stream_error(
                    frame_tx,
                    frame.stream_id,
                    "stream_not_found",
                    "stream not found",
                )
                .await?;
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

fn spawn_tcp_bridge(
    stream_id: u32,
    stream: TcpStream,
    tunnel_config: &TunnelConfig,
    tcp_frame_tx: &mpsc::Sender<TunnelFrame>,
) -> RuntimeStream {
    let (mut reader, mut writer) = stream.into_split();
    let (write_tx, mut write_rx) = mpsc::channel::<TcpWriteCommand>(DEFAULT_STREAM_QUEUE_MESSAGES);
    let read_frame_tx = tcp_frame_tx.clone();
    let read_chunk_size = tunnel_config.read_chunk_size;
    tokio::spawn(async move {
        let mut buffer = vec![0_u8; read_chunk_size];
        loop {
            match reader.read(&mut buffer).await {
                Ok(0) => {
                    let _ = read_frame_tx.send(TunnelFrame::close(stream_id)).await;
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
                Err(_) => {
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
                    break;
                }
            }
        }
    });
    tokio::spawn(async move {
        while let Some(command) = write_rx.recv().await {
            match command {
                TcpWriteCommand::Data(payload) => {
                    if writer.write_all(&payload).await.is_err() {
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
    RuntimeStream { write_tx }
}

async fn close_stream(
    stream_id: u32,
    manager: &mut StreamManager,
    streams: &mut HashMap<u32, RuntimeStream>,
) -> Result<(), TunnelError> {
    manager.remove(stream_id);
    if let Some(stream) = streams.remove(&stream_id) {
        let _ = stream.write_tx.send(TcpWriteCommand::Close).await;
    }
    Ok(())
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
}

pub struct MultiplexedTunnel {
    forward_table: ForwardTable,
    streams: StreamManager,
    outbound_tx: mpsc::Sender<TunnelFrame>,
    writer_handle: JoinHandle<Result<(), TunnelError>>,
    writer_failure_rx: oneshot::Receiver<TunnelError>,
}

impl MultiplexedTunnel {
    pub fn new(data_channel: DataChannelHandle, forward_table: ForwardTable) -> Self {
        let (outbound_tx, outbound_rx) = mpsc::channel(DEFAULT_WRITER_QUEUE_MESSAGES);
        let (failure_tx, writer_failure_rx) = oneshot::channel();
        let writer_handle = spawn_writer(data_channel, outbound_rx, failure_tx);
        Self {
            forward_table,
            streams: StreamManager::new(),
            outbound_tx,
            writer_handle,
            writer_failure_rx,
        }
    }

    pub fn forward_table(&self) -> &ForwardTable {
        &self.forward_table
    }

    pub fn streams(&self) -> &StreamManager {
        &self.streams
    }

    pub fn streams_mut(&mut self) -> &mut StreamManager {
        &mut self.streams
    }

    pub async fn send_open(&self, stream_id: u32, forward_id: String) -> Result<(), TunnelError> {
        self.enqueue(TunnelFrame::open(stream_id, OpenPayload { forward_id })?).await
    }

    pub async fn send_open_ack(&self, stream_id: u32) -> Result<(), TunnelError> {
        self.enqueue(TunnelFrame::open_ack(stream_id)).await
    }

    pub async fn send_data(&self, stream_id: u32, payload: Vec<u8>) -> Result<(), TunnelError> {
        self.enqueue(TunnelFrame::data(stream_id, payload)).await
    }

    pub async fn send_close(&self, stream_id: u32) -> Result<(), TunnelError> {
        self.enqueue(TunnelFrame::close(stream_id)).await
    }

    pub async fn send_error(
        &self,
        stream_id: u32,
        payload: ErrorPayload,
    ) -> Result<(), TunnelError> {
        self.enqueue(TunnelFrame::error(stream_id, payload)?).await
    }

    async fn enqueue(&self, frame: TunnelFrame) -> Result<(), TunnelError> {
        self.outbound_tx.send(frame).await.map_err(|_| TunnelError::WriterClosed)
    }

    pub fn abort_writer(&self) {
        self.writer_handle.abort();
    }

    pub fn try_writer_failure(&mut self) -> Option<TunnelError> {
        self.writer_failure_rx.try_recv().ok()
    }
}

fn spawn_writer(
    data_channel: DataChannelHandle,
    mut outbound_rx: mpsc::Receiver<TunnelFrame>,
    failure_tx: oneshot::Sender<TunnelError>,
) -> JoinHandle<Result<(), TunnelError>> {
    tokio::spawn(async move {
        while let Some(frame) = outbound_rx.recv().await {
            let encoded = TunnelFrameCodec::encode(&frame)?;
            if let Err(error) = data_channel.send(&encoded).await {
                let tunnel_error = TunnelError::WebRtc(error);
                let _ = failure_tx.send(TunnelError::DataChannelClosed);
                return Err(tunnel_error);
            }
        }
        Ok(())
    })
}

fn spawn_writer_only(
    data_channel: DataChannelHandle,
    mut outbound_rx: mpsc::Receiver<TunnelFrame>,
) -> JoinHandle<Result<(), TunnelError>> {
    tokio::spawn(async move {
        while let Some(frame) = outbound_rx.recv().await {
            let encoded = TunnelFrameCodec::encode(&frame)?;
            data_channel.send(&encoded).await?;
        }
        Ok(())
    })
}

#[cfg(test)]
mod tests {
    use p2p_core::{ForwardAnswerConfig, ForwardOfferConfig, ForwardRule, ForwardTable};

    use super::{StreamIdAllocator, StreamLifecycle, StreamManager, StreamState};
    use crate::TunnelError;

    fn stream(stream_id: u32, forward_id: &str) -> StreamState {
        StreamState {
            stream_id,
            forward_id: forward_id.to_owned(),
            lifecycle: StreamLifecycle::Opening,
            remote_peer_id: "offer-home".parse().expect("peer id"),
        }
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
}
