use std::time::Duration;

use p2p_core::{
    ForwardAnswerConfig, ForwardOfferConfig, ForwardRule, ForwardTable, TunnelConfig,
    TunnelFrameType, WebRtcConfig,
};
use p2p_tunnel::{OpenPayload, TunnelFrame, TunnelFrameCodec, run_multiplex_answer};
use p2p_webrtc::{DataChannelEvent, WebRtcPeer};
use tokio::time::timeout;

fn sample_webrtc_config() -> WebRtcConfig {
    WebRtcConfig { stun_urls: Vec::new(), enable_trickle_ice: false, enable_ice_restart: true }
}

fn sample_tunnel_config() -> TunnelConfig {
    TunnelConfig { read_chunk_size: 16_384, local_eof_grace_ms: 250, remote_eof_grace_ms: 250 }
}

fn forward_table_for_peer(target_port: u16, allowed_peer: &str) -> ForwardTable {
    ForwardTable::new(&[ForwardRule {
        id: "ssh".to_owned(),
        offer: Some(ForwardOfferConfig { listen_host: "127.0.0.1".to_owned(), listen_port: 2223 }),
        answer: Some(ForwardAnswerConfig {
            target_host: "127.0.0.1".to_owned(),
            target_port,
            allow_remote_peers: vec![allowed_peer.parse().expect("peer id")],
        }),
    }])
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

async fn recv_frame(channel: &p2p_webrtc::DataChannelHandle) -> TunnelFrame {
    loop {
        match timeout(Duration::from_secs(5), channel.next_event())
            .await
            .expect("event should arrive within timeout")
            .expect("channel should not close before frame arrives")
        {
            DataChannelEvent::Message(bytes) => {
                return TunnelFrameCodec::decode(&bytes).expect("received frame must decode");
            }
            DataChannelEvent::Open => continue,
            DataChannelEvent::Closed => panic!("channel closed unexpectedly"),
        }
    }
}

// ── Phase 3.1: Unknown forward_id returns stream-local error ──────────────────

#[tokio::test]
async fn unknown_forward_id_returns_stream_local_error_without_killing_session() {
    let (offer_peer, answer_peer, offer_channel, answer_channel) = connected_channels().await;
    let tunnel_cfg = sample_tunnel_config();
    let table = forward_table_for_peer(8765, "offer-home");

    let answer_task = tokio::spawn(async move {
        run_multiplex_answer(
            answer_channel,
            &tunnel_cfg,
            table,
            "offer-home".parse().expect("peer id"),
        )
        .await
    });

    // Send OPEN with an unknown forward_id
    let open_frame = TunnelFrame::open(1, OpenPayload { forward_id: "does-not-exist".to_owned() })
        .expect("open frame should build");
    let encoded = TunnelFrameCodec::encode(&open_frame).expect("encode should succeed");
    offer_channel.send(&encoded).await.expect("send should succeed");

    // Expect an error frame back for stream 1
    let error_frame = recv_frame(&offer_channel).await;
    assert_eq!(error_frame.stream_id, 1);
    assert_eq!(error_frame.frame_type, TunnelFrameType::Error);
    let payload = error_frame.error_payload().expect("must have error payload");
    assert_eq!(payload.code, "unknown_forward");

    // WebRTC session must still be alive: send another OPEN on stream 2 for an unknown
    // forward_id and expect another stream-local error — not a closed channel.
    let open2 = TunnelFrame::open(2, OpenPayload { forward_id: "also-missing".to_owned() })
        .expect("second open frame");
    let encoded2 = TunnelFrameCodec::encode(&open2).expect("encode");
    offer_channel.send(&encoded2).await.expect("send stream 2");
    let error2 = recv_frame(&offer_channel).await;
    assert_eq!(error2.stream_id, 2);
    assert_eq!(error2.frame_type, TunnelFrameType::Error);

    answer_task.abort();
    offer_peer.close().await.expect("offer peer should close");
    answer_peer.close().await.expect("answer peer should close");
}

// ── Phase 3.2: Unauthorized peer returns forbidden_forward error ──────────────

#[tokio::test]
async fn unauthorized_peer_open_returns_forbidden_stream_error_without_killing_session() {
    let (offer_peer, answer_peer, offer_channel, answer_channel) = connected_channels().await;
    let tunnel_cfg = sample_tunnel_config();
    // Table allows "authorized-home", not "rogue-peer"
    let table = forward_table_for_peer(8766, "authorized-home");

    let answer_task = tokio::spawn(async move {
        run_multiplex_answer(
            answer_channel,
            &tunnel_cfg,
            table,
            "rogue-peer".parse().expect("peer id"),
        )
        .await
    });

    let open_frame = TunnelFrame::open(1, OpenPayload { forward_id: "ssh".to_owned() })
        .expect("open frame should build");
    let encoded = TunnelFrameCodec::encode(&open_frame).expect("encode");
    offer_channel.send(&encoded).await.expect("send");

    let error_frame = recv_frame(&offer_channel).await;
    assert_eq!(error_frame.stream_id, 1);
    assert_eq!(error_frame.frame_type, TunnelFrameType::Error);
    let payload = error_frame.error_payload().expect("must have error payload");
    assert_eq!(payload.code, "forbidden_forward");

    answer_task.abort();
    offer_peer.close().await.expect("offer peer should close");
    answer_peer.close().await.expect("answer peer should close");
}
