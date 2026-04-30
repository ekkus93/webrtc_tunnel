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

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use p2p_core::{
        FailureCode, TunnelAnswerConfig, TunnelConfig, TunnelOfferConfig, WebRtcConfig,
    };
    use p2p_webrtc::WebRtcPeer;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::{TcpListener, TcpStream};
    use tokio::time::timeout;

    use super::TunnelBridge;
    use crate::{AnswerTargetConnector, TunnelError};

    fn sample_tunnel_config() -> TunnelConfig {
        TunnelConfig {
            stream_id: 1,
            read_chunk_size: 16_384,
            local_eof_grace_ms: 250,
            remote_eof_grace_ms: 250,
            offer: TunnelOfferConfig {
                listen_host: "127.0.0.1".to_owned(),
                listen_port: 0,
                remote_peer_id: "answer-office".parse().expect("peer id"),
            },
            answer: TunnelAnswerConfig {
                target_host: "127.0.0.1".to_owned(),
                target_port: 0,
                allow_remote_peers: vec!["offer-home".parse().expect("peer id")],
            },
        }
    }

    fn sample_webrtc_config() -> WebRtcConfig {
        WebRtcConfig {
            stun_urls: Vec::new(),
            enable_trickle_ice: false,
            enable_ice_restart: true,
        }
    }

    fn answer_connector(port: u16) -> AnswerTargetConnector {
        AnswerTargetConnector::new(&TunnelAnswerConfig {
            target_host: "127.0.0.1".to_owned(),
            target_port: port,
            allow_remote_peers: vec!["offer-home".parse().expect("peer id")],
        })
    }

    async fn connected_channels() -> (WebRtcPeer, WebRtcPeer, p2p_webrtc::DataChannelHandle, p2p_webrtc::DataChannelHandle) {
        let offer_peer = WebRtcPeer::new(&sample_webrtc_config())
            .await
            .expect("offer peer should build");
        let answer_peer = WebRtcPeer::new(&sample_webrtc_config())
            .await
            .expect("answer peer should build");

        let offer_channel = offer_peer
            .create_data_channel()
            .await
            .expect("offer data channel should build");
        let offer_sdp = offer_peer.create_offer().await.expect("offer SDP should build");
        answer_peer
            .apply_remote_offer(&offer_sdp)
            .await
            .expect("answer should accept offer");
        let answer_sdp = answer_peer.create_answer().await.expect("answer SDP should build");
        offer_peer
            .apply_remote_answer(&answer_sdp)
            .await
            .expect("offer should accept answer");

        let answer_channel = timeout(Duration::from_secs(10), answer_peer.next_incoming_data_channel())
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

    #[tokio::test]
    async fn tunnel_open_handshake_bridges_bytes_after_target_connect() {
        let (offer_peer, answer_peer, offer_channel, answer_channel) = connected_channels().await;

        let target_listener = TcpListener::bind(("127.0.0.1", 0))
            .await
            .expect("target listener should bind");
        let connector = answer_connector(
            target_listener
                .local_addr()
                .expect("target local addr")
                .port(),
        );

        let target_task = tokio::spawn(async move {
            let (mut target_stream, _) = target_listener.accept().await.expect("target accept");
            let mut received = [0_u8; 4];
            target_stream.read_exact(&mut received).await.expect("target read");
            assert_eq!(&received, b"ping");
            target_stream.write_all(b"pong").await.expect("target write");
            target_stream.shutdown().await.expect("target shutdown");
        });

        let local_listener = TcpListener::bind(("127.0.0.1", 0))
            .await
            .expect("local listener should bind");
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

        let answer_task = tokio::spawn(async move {
            TunnelBridge::new(answer_channel, &sample_tunnel_config())
                .run_answer(&connector)
                .await
        });
        let offer_task = tokio::spawn(async move {
            TunnelBridge::new(offer_channel, &sample_tunnel_config())
                .run_offer(offer_stream)
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

        assert!(matches!(
            timeout(Duration::from_secs(10), offer_task)
                .await
                .expect("offer bridge should finish")
                .expect("offer bridge join should succeed"),
            Ok(())
        ));
        assert!(matches!(
            timeout(Duration::from_secs(10), answer_task)
                .await
                .expect("answer bridge should finish")
                .expect("answer bridge join should succeed"),
            Ok(())
        ));

        offer_peer.close().await.expect("offer peer should close");
        answer_peer.close().await.expect("answer peer should close");
    }

    #[tokio::test]
    async fn target_connect_failure_is_reported_back_to_offer_bridge() {
        let (offer_peer, answer_peer, offer_channel, answer_channel) = connected_channels().await;

        let probe = TcpListener::bind(("127.0.0.1", 0)).await.expect("probe should bind");
        let connector = answer_connector(probe.local_addr().expect("probe addr").port());
        drop(probe);

        let local_listener = TcpListener::bind(("127.0.0.1", 0))
            .await
            .expect("local listener should bind");
        let local_addr = local_listener.local_addr().expect("local addr");
        let client = TcpStream::connect(local_addr).await.expect("client connect");
        let (offer_stream, _) = local_listener.accept().await.expect("offer accept");

        let answer_task = tokio::spawn(async move {
            TunnelBridge::new(answer_channel, &sample_tunnel_config())
                .run_answer(&connector)
                .await
        });

        let offer_result = timeout(Duration::from_secs(10), async move {
            TunnelBridge::new(offer_channel, &sample_tunnel_config())
                .run_offer(offer_stream)
                .await
        })
        .await
        .expect("offer bridge should finish");

        assert!(matches!(
            offer_result,
            Err(TunnelError::RemoteFailure { code: FailureCode::TargetConnectFailed, .. })
        ));
        assert!(matches!(
            timeout(Duration::from_secs(10), answer_task)
                .await
                .expect("answer bridge should finish")
                .expect("answer bridge join should succeed"),
            Err(TunnelError::TargetConnectFailed(_))
        ));

        drop(client);
        offer_peer.close().await.expect("offer peer should close");
        answer_peer.close().await.expect("answer peer should close");
    }
}
