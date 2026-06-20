//! Shared fixtures for the multiplex unit-test suite.
//!
//! The grouped test files pull these in via `use super::support::*`. The
//! multiplex-internal symbols and crate types the tests exercise are re-exported
//! here too, so each group file needs only the single glob import.

pub(super) use std::collections::HashMap;
pub(super) use std::future;
pub(super) use std::time::Duration;

pub(super) use p2p_core::{
    ForwardAnswerConfig, ForwardOfferConfig, ForwardRule, ForwardTable, TunnelConfig,
    TunnelFrameType, WebRtcConfig,
};
pub(super) use p2p_webrtc::WebRtcPeer;
pub(super) use tokio::io::{AsyncReadExt, AsyncWriteExt};
pub(super) use tokio::net::{TcpListener, TcpStream};
pub(super) use tokio::sync::{mpsc, oneshot};
pub(super) use tokio::time::timeout;

pub(super) use super::super::{
    OfferIo, RuntimeStream, StreamIdAllocator, StreamLifecycle, StreamManager, StreamRuntimeEvent,
    StreamState, TcpWriteCommand, cleanup_all_streams, close_stream, handle_answer_frame,
    handle_offer_frame, handle_stream_runtime_event, register_offer_client, run_multiplex_answer,
    run_multiplex_offer, spawn_tcp_bridge, spawn_writer_only,
};
pub(super) use crate::{ErrorPayload, OfferClient, OpenPayload, TunnelError, TunnelFrame};

pub(super) fn stream(stream_id: u32, forward_id: &str) -> StreamState {
    StreamState {
        stream_id,
        forward_id: forward_id.to_owned(),
        lifecycle: StreamLifecycle::Opening,
        remote_peer_id: "offer-home".parse().expect("peer id"),
    }
}

pub(super) fn sample_tunnel_config() -> TunnelConfig {
    TunnelConfig {
        read_chunk_size: 16_384,
        local_eof_grace_ms: 250,
        remote_eof_grace_ms: 250,
        data_plane_probe_timeout_ms: 5000,
        data_plane_heartbeat_interval_ms: 5000,
        data_plane_heartbeat_max_misses: 3,
    }
}

pub(super) fn sample_webrtc_config() -> WebRtcConfig {
    WebRtcConfig {
        stun_urls: Vec::new(),
        enable_trickle_ice: false,
        enable_ice_restart: true,
        android_ice_mode: Default::default(),
        advertised_local_ipv4: None,
    }
}

pub(super) fn forward_table(target_port: u16) -> ForwardTable {
    ForwardTable::new(&[ForwardRule {
        id: "ssh".to_owned(),
        offer: Some(ForwardOfferConfig { listen_host: "127.0.0.1".to_owned(), listen_port: 2223 }),
        answer: Some(ForwardAnswerConfig {
            target_host: "127.0.0.1".to_owned(),
            target_port,
            allow_remote_peers: vec!["offer-home".parse().expect("peer id")],
        }),
    }])
}

pub(super) fn forward_table_with_target(target_host: &str, target_port: u16) -> ForwardTable {
    ForwardTable::new(&[ForwardRule {
        id: "ssh".to_owned(),
        offer: Some(ForwardOfferConfig { listen_host: "127.0.0.1".to_owned(), listen_port: 2223 }),
        answer: Some(ForwardAnswerConfig {
            target_host: target_host.to_owned(),
            target_port,
            allow_remote_peers: vec!["offer-home".parse().expect("peer id")],
        }),
    }])
}

pub(super) fn multi_forward_table(ssh_port: u16, web_port: u16) -> ForwardTable {
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

pub(super) async fn connected_channels()
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

pub(super) struct DropNotifier(pub(super) Option<oneshot::Sender<()>>);

impl Drop for DropNotifier {
    fn drop(&mut self) {
        if let Some(tx) = self.0.take() {
            let _ = tx.send(());
        }
    }
}
