//! Multiplex data model: per-stream lifecycle/state, the stream-id allocator and
//! stream manager (public, used directly by the daemon), and the internal runtime
//! types (per-stream task handles, TCP write commands, runtime events, and
//! target-connect results) shared by the offer/answer loops and the bridge layer.

use std::collections::HashMap;
use std::time::Duration;

use p2p_core::PeerId;
use tokio::net::TcpStream;
use tokio::sync::mpsc;
use tokio::task::JoinHandle;

use crate::TunnelError;
pub const DEFAULT_STREAM_QUEUE_MESSAGES: usize = 64;

pub const DEFAULT_WRITER_QUEUE_MESSAGES: usize = 256;

pub(crate) const ANSWER_TARGET_CONNECT_TIMEOUT: Duration = Duration::from_secs(10);

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

pub(crate) enum TcpWriteCommand {
    Data(Vec<u8>),
    Close,
}

pub(crate) enum StreamRuntimeEvent {
    LocalEof { stream_id: u32 },
    LocalIoError { stream_id: u32, message: String, notify_peer: bool },
}

pub(crate) struct RuntimeStream {
    write_tx: Option<mpsc::Sender<TcpWriteCommand>>,
    tasks: Vec<JoinHandle<()>>,
}

impl RuntimeStream {
    pub(crate) fn opening(task: JoinHandle<()>) -> Self {
        Self { write_tx: None, tasks: vec![task] }
    }

    pub(crate) fn open(
        write_tx: mpsc::Sender<TcpWriteCommand>,
        tasks: Vec<JoinHandle<()>>,
    ) -> Self {
        Self { write_tx: Some(write_tx), tasks }
    }

    pub(crate) fn write_tx(&self) -> Option<&mpsc::Sender<TcpWriteCommand>> {
        self.write_tx.as_ref()
    }

    pub(crate) async fn close(mut self) {
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

    pub(crate) fn abort_all(mut self) {
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

pub(crate) struct TargetConnectResult {
    pub(crate) stream_id: u32,
    pub(crate) forward_id: String,
    pub(crate) result: Result<TcpStream, String>,
}

#[derive(Debug, Default)]
pub struct StreamIdAllocator {
    next: u32,
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
