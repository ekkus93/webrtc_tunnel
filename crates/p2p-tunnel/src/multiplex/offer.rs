//! Offer-side multiplex loop: registers locally-accepted clients as new streams,
//! drives the data-channel/stream-event/writer select loop, and dispatches inbound
//! frames (OPEN-ack, DATA, CLOSE, ERROR, PING/PONG) for the offer role.

use std::collections::HashMap;
use std::time::Duration;

use p2p_core::{TunnelConfig, TunnelFrameType};
use p2p_webrtc::{DataChannelEvent, DataChannelHandle};
use tokio::net::TcpStream;
use tokio::sync::mpsc;
use tokio::time::{Instant, Interval, MissedTickBehavior, interval_at};

use super::state::*;
use super::stream::*;
use crate::{OfferClient, OpenPayload, TunnelError, TunnelFrame, TunnelFrameCodec};

/// How often the offer sends a data-plane heartbeat `Ping` while bridging.
const HEARTBEAT_INTERVAL: Duration = Duration::from_secs(5);
/// Consecutive unacknowledged heartbeats that mark the data plane dead. The startup probe
/// only gates the *first* transition to bridging; this catches a path that dies *mid*-session
/// (e.g. a NAT rebinding) while the WebRTC data channel still reports "open" and ICE consent
/// still passes, so nothing else tears it down. On loss the bridge returns an error, the
/// session ends, and the next local client rebuilds a fresh session (with its own probe).
const HEARTBEAT_MAX_MISSES: u32 = 3;

/// Outcome of a heartbeat tick.
enum HeartbeatAction {
    /// Send this `Ping` frame; a matching `Pong` must arrive before the next tick.
    Send(TunnelFrame),
    /// `missed` consecutive heartbeats went unacknowledged — the data plane is dead.
    Dead(u32),
}

/// Tracks the offer's periodic data-plane liveness check over the shared control stream.
/// Nonces are a monotonic counter (the answer echoes the `Ping` payload in its `Pong`).
struct OfferHeartbeat {
    ticker: Interval,
    counter: u64,
    /// Counter value of the in-flight heartbeat, cleared when its `Pong` returns.
    outstanding: Option<u64>,
    misses: u32,
}

impl OfferHeartbeat {
    fn new() -> Self {
        // First heartbeat fires one interval out (not immediately): the startup probe just
        // verified the data plane at t=0, and deferring also keeps sub-interval unit tests
        // from ever seeing a heartbeat frame.
        let mut ticker = interval_at(Instant::now() + HEARTBEAT_INTERVAL, HEARTBEAT_INTERVAL);
        // If the select loop is briefly busy, don't fire a catch-up burst of pings.
        ticker.set_missed_tick_behavior(MissedTickBehavior::Delay);
        Self { ticker, counter: 0, outstanding: None, misses: 0 }
    }

    /// Advance one interval: an unacked prior heartbeat is a miss; otherwise emit a new one.
    fn on_tick(&mut self) -> HeartbeatAction {
        if self.outstanding.is_some() {
            self.misses += 1;
            if self.misses >= HEARTBEAT_MAX_MISSES {
                return HeartbeatAction::Dead(self.misses);
            }
        }
        self.counter = self.counter.wrapping_add(1);
        // Marked outstanding up front: if the writer is too backed up to even accept the
        // ping (`try_send` Full), it stays outstanding and is counted as a miss next tick.
        self.outstanding = Some(self.counter);
        HeartbeatAction::Send(TunnelFrame::ping(self.counter.to_le_bytes().to_vec()))
    }

    /// Clear the in-flight heartbeat if `payload` matches it.
    fn on_pong(&mut self, payload: &[u8]) {
        if let Some(expected) = self.outstanding {
            if payload == expected.to_le_bytes() {
                self.outstanding = None;
                self.misses = 0;
            }
        }
    }
}

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
    let mut heartbeat = OfferHeartbeat::new();
    let result = loop {
        tokio::select! {
            _ = heartbeat.ticker.tick() => {
                match heartbeat.on_tick() {
                    HeartbeatAction::Dead(missed) => {
                        tracing::warn!(
                            target: "tunnel",
                            missed,
                            "data-plane heartbeat lost mid-session; tearing down bridge so the next client rebuilds",
                        );
                        break Err(TunnelError::DataPlaneHeartbeatLost { missed });
                    }
                    HeartbeatAction::Send(ping) => match frame_tx.try_send(ping) {
                        Ok(()) => {}
                        // Writer backed up: leave the heartbeat outstanding (counts as a miss).
                        Err(mpsc::error::TrySendError::Full(_)) => {}
                        Err(mpsc::error::TrySendError::Closed(_)) => break Err(TunnelError::WriterClosed),
                    },
                }
            }
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
                        // Acknowledge our own heartbeat without involving the stream tables;
                        // every other frame (including inbound Ping) goes to the dispatcher.
                        if frame.frame_type == TunnelFrameType::Pong {
                            heartbeat.on_pong(&frame.payload);
                            continue;
                        }
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

#[cfg(test)]
mod heartbeat_tests {
    use super::{HEARTBEAT_MAX_MISSES, HeartbeatAction, OfferHeartbeat};
    use p2p_core::TunnelFrameType;

    fn expect_send(action: HeartbeatAction) -> Vec<u8> {
        match action {
            HeartbeatAction::Send(frame) => {
                assert_eq!(frame.frame_type, TunnelFrameType::Ping);
                frame.payload
            }
            HeartbeatAction::Dead(n) => panic!("expected Send, got Dead({n})"),
        }
    }

    #[tokio::test]
    async fn acked_heartbeat_resets_and_keeps_running() {
        let mut hb = OfferHeartbeat::new();
        let p1 = expect_send(hb.on_tick());
        assert_eq!(p1, 1u64.to_le_bytes());
        assert_eq!(hb.misses, 0);
        hb.on_pong(&p1);
        assert!(hb.outstanding.is_none());
        // After an ack, the next tick is a fresh nonce with no accrued miss.
        let p2 = expect_send(hb.on_tick());
        assert_eq!(p2, 2u64.to_le_bytes());
        assert_eq!(hb.misses, 0);
    }

    #[tokio::test]
    async fn unacked_heartbeats_eventually_report_dead() {
        let mut hb = OfferHeartbeat::new();
        let _ = expect_send(hb.on_tick());
        // Without a Pong, each tick accrues a miss until the threshold.
        for expected_miss in 1..HEARTBEAT_MAX_MISSES {
            match hb.on_tick() {
                HeartbeatAction::Send(_) => assert_eq!(hb.misses, expected_miss),
                HeartbeatAction::Dead(n) => panic!("died early at miss {n}"),
            }
        }
        match hb.on_tick() {
            HeartbeatAction::Dead(missed) => assert_eq!(missed, HEARTBEAT_MAX_MISSES),
            HeartbeatAction::Send(_) => panic!("expected Dead at the miss threshold"),
        }
    }

    #[tokio::test]
    async fn mismatched_pong_does_not_clear_outstanding() {
        let mut hb = OfferHeartbeat::new();
        let _ = expect_send(hb.on_tick());
        hb.on_pong(&[9u8; 8]);
        assert!(hb.outstanding.is_some());
        let _ = hb.on_tick();
        assert_eq!(hb.misses, 1);
    }
}
