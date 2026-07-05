//! Small pure decision predicates for reconnect and session handling. Kept
//! separate so the policy is easy to read and unit-test in isolation.

use p2p_core::AppConfig;

use crate::types::{ActiveSession, BridgeSessionState};
pub(crate) fn should_attempt_offer_reconnect(
    config: &AppConfig,
    pending_stream_present: bool,
    bridge_state: BridgeSessionState,
) -> bool {
    config.reconnect.enable_auto_reconnect
        && pending_stream_present
        && matches!(bridge_state, BridgeSessionState::Pending | BridgeSessionState::Reconnecting)
}

pub(crate) fn should_ack_idle_offer(peer_allowed: bool, requires_ack: bool) -> bool {
    peer_allowed && requires_ack
}

pub(crate) fn should_continue_reconnect_attempt(max_attempts: u32, attempt: u32) -> bool {
    max_attempts == 0 || attempt < max_attempts
}

pub(crate) fn can_attempt_same_session_ice_restart(session: &ActiveSession) -> bool {
    session.data_channel.as_ref().is_some_and(|channel| channel.is_open())
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use p2p_core::{DEFAULT_ICE_CHECKING_TIMEOUT_MS, SessionId, WebRtcConfig};
    use p2p_crypto::{AuthorizedKey, generate_identity};

    use super::can_attempt_same_session_ice_restart;
    use crate::WebRtcPeer;
    use crate::types::ActiveSession;

    fn test_webrtc_config() -> WebRtcConfig {
        WebRtcConfig {
            stun_urls: Vec::new(),
            enable_trickle_ice: false,
            enable_ice_restart: true,
            android_ice_mode: Default::default(),
            advertised_local_ipv4: None,
            ice_checking_timeout_ms: DEFAULT_ICE_CHECKING_TIMEOUT_MS,
        }
    }

    fn sample_remote_authorized() -> AuthorizedKey {
        let identity = generate_identity("remote-peer").expect("identity");
        AuthorizedKey {
            peer_id: identity.public_identity.peer_id.clone(),
            public_identity: identity.public_identity,
        }
    }

    fn session_with_data_channel(
        data_channel: Option<p2p_webrtc::DataChannelHandle>,
        peer: WebRtcPeer,
    ) -> ActiveSession {
        let mut session =
            ActiveSession::new(SessionId::random(), sample_remote_authorized(), peer, 32);
        session.data_channel = data_channel;
        session
    }

    #[tokio::test]
    async fn no_data_channel_cannot_attempt_restart() {
        let config = test_webrtc_config();
        let peer = WebRtcPeer::new(&config).await.expect("peer builds");
        let session = session_with_data_channel(None, peer);

        assert!(!can_attempt_same_session_ice_restart(&session));
    }

    #[tokio::test]
    async fn unopened_data_channel_cannot_attempt_restart() {
        let config = test_webrtc_config();
        let peer = WebRtcPeer::new(&config).await.expect("peer builds");
        let channel = peer.create_data_channel().await.expect("data channel builds");
        let session = session_with_data_channel(Some(channel), peer);

        assert!(!can_attempt_same_session_ice_restart(&session));
    }

    #[tokio::test]
    async fn open_data_channel_can_attempt_restart() {
        let config = test_webrtc_config();
        let offer_peer = WebRtcPeer::new(&config).await.expect("offer peer builds");
        let answer_peer = WebRtcPeer::new(&config).await.expect("answer peer builds");
        let offer_channel =
            offer_peer.create_data_channel().await.expect("offer data channel builds");
        let offer_sdp = offer_peer.create_offer().await.expect("offer sdp");
        answer_peer.apply_remote_offer(&offer_sdp).await.expect("answer accepts offer");
        let answer_sdp = answer_peer.create_answer().await.expect("answer sdp");
        offer_peer.apply_remote_answer(&answer_sdp).await.expect("offer accepts answer");
        offer_channel
            .wait_for_open(Duration::from_secs(10))
            .await
            .expect("offer data channel should open");

        let session = session_with_data_channel(Some(offer_channel), offer_peer);

        assert!(can_attempt_same_session_ice_restart(&session));
    }
}
