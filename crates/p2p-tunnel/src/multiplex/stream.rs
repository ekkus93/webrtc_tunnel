//! Shared per-stream runtime/bridge layer used by both the offer and answer loops.
//!
//! Spawns the local TCP read/write tasks for a stream, the data-channel writer
//! task, and centralizes stream teardown (close, error notification, queue-closed
//! handling, and whole-session cleanup) over the [`StreamManager`] and the live
//! [`RuntimeStream`] table.

use std::collections::HashMap;

use p2p_core::TunnelConfig;
use p2p_webrtc::DataChannelHandle;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::mpsc;
use tokio::task::JoinHandle;

use super::state::{
    DEFAULT_STREAM_QUEUE_MESSAGES, RuntimeStream, StreamManager, StreamRuntimeEvent,
    TcpWriteCommand,
};
use crate::{ErrorPayload, TunnelError, TunnelFrame, TunnelFrameCodec};
pub(crate) fn spawn_tcp_bridge(
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
                    // Both sends below: a closed receiver here means the session's stream
                    // manager/data channel already tore down (normal end-of-session race
                    // with this stream's own EOF), so a failed send is expected and not
                    // worth logging per-stream.
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
                    // The read failure itself is reported to the stream event loop below
                    // (which is what actually surfaces it in daemon/session diagnostics);
                    // both sends here failing only means the receiver already tore down,
                    // same as the EOF case above.
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
                        // Surfaced by handle_stream_runtime_event's LocalIoError handling; a
                        // failed send here just means the receiver already tore down.
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
                    // Best-effort: we are closing this stream regardless of the outcome,
                    // and a half-close failing here (e.g. the peer already reset the TCP
                    // connection) is a routine, non-actionable network condition.
                    let _ = writer.shutdown().await;
                    break;
                }
            }
        }
    });
    RuntimeStream::open(write_tx, vec![read_task, write_task])
}

pub(crate) async fn handle_stream_runtime_event(
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
            // The single choke point for every local TCP read/write failure across all
            // streams, so this is where the underlying error becomes visible — the
            // originating read/write task only carries it this far via the event channel.
            tracing::warn!(stream_id, reason = %message, "local stream I/O failed; closing stream");
            if notify_peer && (manager.get(stream_id).is_ok() || streams.contains_key(&stream_id)) {
                send_stream_error(frame_tx, stream_id, "local_io_error", &message).await?;
            }
            close_stream(stream_id, manager, streams).await?;
        }
    }
    Ok(())
}

pub(crate) async fn handle_closed_stream_queue(
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

pub(crate) async fn close_stream(
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

pub(crate) fn cleanup_all_streams(
    manager: &mut StreamManager,
    streams: &mut HashMap<u32, RuntimeStream>,
) {
    manager.clear();
    for (_, stream) in streams.drain() {
        stream.abort_all();
    }
}

pub(crate) async fn send_stream_error(
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

pub(crate) fn spawn_writer_only(
    data_channel: DataChannelHandle,
    mut outbound_rx: mpsc::Receiver<TunnelFrame>,
    failure_tx: mpsc::Sender<TunnelError>,
) -> JoinHandle<Result<(), TunnelError>> {
    tokio::spawn(async move {
        while let Some(frame) = outbound_rx.recv().await {
            let frame_type = frame.frame_type;
            let stream_id = frame.stream_id;
            let encoded = match TunnelFrameCodec::encode(&frame) {
                Ok(encoded) => encoded,
                Err(error) => {
                    // Redacted: frame type/stream id/error only, never payload bytes.
                    tracing::warn!(
                        frame_type = ?frame_type,
                        stream_id,
                        reason = %error,
                        "failed to encode tunnel frame; closing writer",
                    );
                    // Already logged above; this best-effort forward to the supervisor
                    // only fails if it is already shutting down for its own reasons.
                    let _ = failure_tx.send(error).await;
                    return Err(TunnelError::WriterClosed);
                }
            };
            if let Err(error) = data_channel.send(&encoded).await {
                // The data-channel send path failing is a prime diagnostic signal
                // (e.g. the Android SCTP data-plane stall): surface it, redacted.
                tracing::warn!(
                    frame_type = ?frame_type,
                    stream_id,
                    encoded_len = encoded.len(),
                    reason = %error,
                    "data channel send failed; closing writer",
                );
                let tunnel_error = TunnelError::WebRtc(error);
                // Already logged above; see the encode-failure comment for why a failed
                // forward here is not itself worth logging.
                let _ = failure_tx.send(tunnel_error).await;
                return Err(TunnelError::WriterClosed);
            }
        }
        Ok(())
    })
}
