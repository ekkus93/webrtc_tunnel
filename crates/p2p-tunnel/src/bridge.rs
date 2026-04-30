use std::pin::Pin;
use std::str;
use std::time::Duration;

use p2p_core::{FailureCode, TunnelConfig, TunnelFrameType};
use p2p_webrtc::{DataChannelEvent, DataChannelHandle};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::mpsc;
use tokio::time::Sleep;

use crate::{AnswerTargetConnector, TunnelError, TunnelFrame, TunnelFrameCodec};

enum TcpReadEvent {
    Data(Vec<u8>),
    Eof,
    Error(std::io::Error),
}

pub struct TunnelBridge {
    data_channel: DataChannelHandle,
    config: TunnelConfig,
}

impl TunnelBridge {
    pub fn new(data_channel: DataChannelHandle, config: &TunnelConfig) -> Self {
        Self { data_channel, config: config.clone() }
    }

    pub async fn run_offer(self, stream: TcpStream) -> Result<(), TunnelError> {
        self.send_frame(TunnelFrame::open()).await?;
        loop {
            match self.next_frame().await? {
                TunnelFrame { frame_type: TunnelFrameType::Open, .. } => {
                    return self.bridge_stream(stream).await;
                }
                TunnelFrame { frame_type: TunnelFrameType::Ping, payload, .. } => {
                    self.send_frame(TunnelFrame::pong(payload)).await?;
                }
                TunnelFrame { frame_type: TunnelFrameType::Pong, .. } => {}
                TunnelFrame { frame_type: TunnelFrameType::Error, payload, .. } => {
                    return Err(parse_remote_failure(&payload));
                }
                frame => return Err(TunnelError::UnexpectedFrame(frame.frame_type)),
            }
        }
    }

    pub async fn run_answer(self, connector: &AnswerTargetConnector) -> Result<(), TunnelError> {
        loop {
            match self.next_frame().await? {
                TunnelFrame { frame_type: TunnelFrameType::Open, .. } => break,
                TunnelFrame { frame_type: TunnelFrameType::Ping, payload, .. } => {
                    self.send_frame(TunnelFrame::pong(payload)).await?;
                }
                TunnelFrame { frame_type: TunnelFrameType::Pong, .. } => {}
                frame => return Err(TunnelError::UnexpectedFrame(frame.frame_type)),
            }
        }

        let stream = match connector.connect_target().await {
            Ok(stream) => stream,
            Err(error) => {
                let _ = self.send_frame(TunnelFrame::error(FailureCode::TargetConnectFailed)).await;
                return Err(error);
            }
        };

        self.send_frame(TunnelFrame::open()).await?;
        self.bridge_stream(stream).await
    }

    async fn bridge_stream(self, stream: TcpStream) -> Result<(), TunnelError> {
        let (mut reader, mut writer) = stream.into_split();
        let (tcp_tx, mut tcp_rx) = mpsc::channel(8);
        let read_chunk_size = self.config.read_chunk_size;

        tokio::spawn(async move {
            let mut buffer = vec![0_u8; read_chunk_size];
            loop {
                match reader.read(&mut buffer).await {
                    Ok(0) => {
                        let _ = tcp_tx.send(TcpReadEvent::Eof).await;
                        break;
                    }
                    Ok(read) => {
                        let _ = tcp_tx.send(TcpReadEvent::Data(buffer[..read].to_vec())).await;
                    }
                    Err(error) => {
                        let _ = tcp_tx.send(TcpReadEvent::Error(error)).await;
                        break;
                    }
                }
            }
        });

        let mut local_eof_sent = false;
        let local_eof_grace = Duration::from_millis(self.config.local_eof_grace_ms);
        let remote_eof_grace = Duration::from_millis(self.config.remote_eof_grace_ms);
        let mut local_eof_deadline: Option<Pin<Box<Sleep>>> = None;
        let mut remote_close_deadline: Option<Pin<Box<Sleep>>> = None;

        loop {
            tokio::select! {
                tcp_event = tcp_rx.recv(), if remote_close_deadline.is_none() => {
                    match tcp_event {
                        Some(TcpReadEvent::Data(payload)) => {
                            self.send_frame(TunnelFrame::data(payload)).await?;
                        }
                        Some(TcpReadEvent::Eof) if !local_eof_sent => {
                            self.send_frame(TunnelFrame::close()).await?;
                            local_eof_sent = true;
                            local_eof_deadline = Some(Box::pin(tokio::time::sleep(local_eof_grace)));
                        }
                        Some(TcpReadEvent::Eof) => {}
                        Some(TcpReadEvent::Error(error)) => {
                            let _ = self.send_frame(TunnelFrame::error(FailureCode::ProtocolError)).await;
                            return Err(TunnelError::Io(error));
                        }
                        None => {}
                    }
                }
                frame = self.next_frame() => {
                    match frame? {
                        TunnelFrame { frame_type: TunnelFrameType::Data, payload, .. } => {
                            writer.write_all(&payload).await?;
                            writer.flush().await?;
                        }
                        TunnelFrame { frame_type: TunnelFrameType::Close, .. } => {
                            writer.shutdown().await?;
                            if local_eof_sent {
                                return Ok(());
                            }
                            remote_close_deadline = Some(Box::pin(tokio::time::sleep(remote_eof_grace)));
                        }
                        TunnelFrame { frame_type: TunnelFrameType::Error, payload, .. } => {
                            return Err(parse_remote_failure(&payload));
                        }
                        TunnelFrame { frame_type: TunnelFrameType::Ping, payload, .. } => {
                            self.send_frame(TunnelFrame::pong(payload)).await?;
                        }
                        TunnelFrame { frame_type: TunnelFrameType::Pong, .. } => {}
                        TunnelFrame { frame_type: TunnelFrameType::Open, .. } => {}
                    }
                }
                _ = async { if let Some(deadline) = &mut local_eof_deadline { deadline.await } }, if local_eof_deadline.is_some() => {
                    return Ok(());
                }
                _ = async { if let Some(deadline) = &mut remote_close_deadline { deadline.await } }, if remote_close_deadline.is_some() => {
                    return Ok(());
                }
            }
        }
    }

    async fn next_frame(&self) -> Result<TunnelFrame, TunnelError> {
        loop {
            match self.data_channel.next_event().await {
                Some(DataChannelEvent::Message(payload)) => {
                    return TunnelFrameCodec::decode(&payload);
                }
                Some(DataChannelEvent::Closed) | None => {
                    return Err(TunnelError::DataChannelClosed);
                }
                Some(DataChannelEvent::Open) => continue,
            }
        }
    }

    async fn send_frame(&self, frame: TunnelFrame) -> Result<(), TunnelError> {
        let encoded = TunnelFrameCodec::encode(&frame)?;
        let _ = self.data_channel.send(&encoded).await?;
        Ok(())
    }
}

fn parse_remote_failure(payload: &[u8]) -> TunnelError {
    match str::from_utf8(payload) {
        Ok(text) => {
            let (code, detail) = match text.split_once(':') {
                Some((code, detail)) => (code, Some(detail.to_owned())),
                None => (text, None),
            };
            TunnelError::RemoteFailure { code: parse_failure_code(code), detail }
        }
        Err(_) => TunnelError::InvalidFrame("remote error payload was not valid utf-8".to_owned()),
    }
}

fn parse_failure_code(code: &str) -> FailureCode {
    match code {
        "ice_failed" => FailureCode::IceFailed,
        "ice_timeout" => FailureCode::IceTimeout,
        "peer_connection_closed" => FailureCode::PeerConnectionClosed,
        "unauthorized_peer" => FailureCode::UnauthorizedPeer,
        "decrypt_failed" => FailureCode::DecryptFailed,
        "signature_invalid" => FailureCode::SignatureInvalid,
        "replay_detected" => FailureCode::ReplayDetected,
        "target_connect_failed" => FailureCode::TargetConnectFailed,
        "busy" => FailureCode::Busy,
        _ => FailureCode::ProtocolError,
    }
}
