//! The `WebRtcPeer` connection wrapper, the ICE candidate/state types it surfaces, and the
//! `RTCConfiguration` builder (STUN-only).

use std::sync::Arc;
use std::time::Duration;

use tokio::sync::{Mutex, mpsc};
use webrtc::api::APIBuilder;
use webrtc::api::media_engine::MediaEngine;
use webrtc::data_channel::data_channel_init::RTCDataChannelInit;
use webrtc::ice_transport::ice_candidate::RTCIceCandidateInit;
use webrtc::ice_transport::ice_connection_state::RTCIceConnectionState;
use webrtc::ice_transport::ice_server::RTCIceServer;
use webrtc::peer_connection::RTCPeerConnection;
use webrtc::peer_connection::configuration::RTCConfiguration;
use webrtc::peer_connection::offer_answer_options::RTCOfferOptions;
use webrtc::peer_connection::sdp::session_description::RTCSessionDescription;

use p2p_core::{DATA_CHANNEL_LABEL, DATA_CHANNEL_ORDERED, DATA_CHANNEL_RELIABLE, WebRtcConfig};

use crate::WebRtcError;
use crate::data_channel::DataChannelHandle;
use crate::ice::build_setting_engine;

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct IceCandidateSignal {
    pub candidate: Option<String>,
    pub sdp_mid: Option<String>,
    pub sdp_mline_index: Option<u16>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum IceConnectionState {
    New,
    Checking,
    Connected,
    Completed,
    Disconnected,
    Failed,
    Closed,
    /// An upstream ICE state we do not model. Kept distinct from `New` so an unexpected
    /// state is not misread as normal startup.
    Unknown,
}

impl From<RTCIceConnectionState> for IceConnectionState {
    fn from(value: RTCIceConnectionState) -> Self {
        match value {
            RTCIceConnectionState::New => Self::New,
            RTCIceConnectionState::Checking => Self::Checking,
            RTCIceConnectionState::Connected => Self::Connected,
            RTCIceConnectionState::Completed => Self::Completed,
            RTCIceConnectionState::Disconnected => Self::Disconnected,
            RTCIceConnectionState::Failed => Self::Failed,
            RTCIceConnectionState::Closed => Self::Closed,
            other => {
                tracing::warn!(target: "ice", ?other, "unmapped upstream ICE state; reporting Unknown");
                Self::Unknown
            }
        }
    }
}

#[cfg(any(test, debug_assertions))]
#[derive(Clone)]
pub struct IceStateInjectorForTests {
    tx: mpsc::Sender<IceConnectionState>,
}

#[cfg(any(test, debug_assertions))]
impl IceStateInjectorForTests {
    pub async fn inject(&self, state: IceConnectionState) -> Result<(), WebRtcError> {
        self.tx.send(state).await.map_err(|_| {
            WebRtcError::InvalidConfig("ice state channel closed unexpectedly".to_owned())
        })
    }
}

pub struct WebRtcPeer {
    peer_connection: Arc<RTCPeerConnection>,
    local_candidate_rx: Arc<Mutex<mpsc::Receiver<IceCandidateSignal>>>,
    ice_state_rx: Arc<Mutex<mpsc::Receiver<IceConnectionState>>>,
    #[cfg(any(test, debug_assertions))]
    test_ice_state_tx: mpsc::Sender<IceConnectionState>,
    incoming_data_channel_rx: Arc<Mutex<mpsc::Receiver<Result<DataChannelHandle, WebRtcError>>>>,
    config: WebRtcConfig,
}

impl WebRtcPeer {
    pub async fn new(config: &WebRtcConfig) -> Result<Self, WebRtcError> {
        let rtc_config = build_rtc_configuration(config)?;
        let mut media_engine = MediaEngine::default();
        media_engine.register_default_codecs()?;
        let api = APIBuilder::new()
            .with_media_engine(media_engine)
            .with_setting_engine(build_setting_engine(config)?)
            .build();
        let peer_connection = Arc::new(api.new_peer_connection(rtc_config).await?);

        let (local_candidate_tx, local_candidate_rx) = mpsc::channel(64);
        let (ice_state_tx, ice_state_rx) = mpsc::channel(32);
        let (incoming_dc_tx, incoming_dc_rx) = mpsc::channel(8);
        #[cfg(any(test, debug_assertions))]
        let test_ice_state_tx = ice_state_tx.clone();

        peer_connection.on_ice_candidate(Box::new(move |candidate| {
            let local_candidate_tx = local_candidate_tx.clone();
            Box::pin(async move {
                let payload = match candidate {
                    Some(candidate) => match candidate.to_json() {
                        Ok(json) => IceCandidateSignal {
                            candidate: Some(json.candidate),
                            sdp_mid: json.sdp_mid,
                            sdp_mline_index: json.sdp_mline_index,
                        },
                        Err(error) => {
                            tracing::warn!("failed to serialize local ICE candidate: {error}");
                            return;
                        }
                    },
                    None => {
                        IceCandidateSignal { candidate: None, sdp_mid: None, sdp_mline_index: None }
                    }
                };
                let _ = local_candidate_tx.send(payload).await;
            })
        }));

        peer_connection.on_ice_connection_state_change(Box::new(move |state| {
            let ice_state_tx = ice_state_tx.clone();
            Box::pin(async move {
                // State names (new/checking/connected/failed/...) carry no address
                // and are the key signal for diagnosing whether a peer connection
                // ever establishes, so log every transition unconditionally.
                tracing::info!(target: "ice", state = %state, "ICE connection state changed");
                let _ = ice_state_tx.send(state.into()).await;
            })
        }));

        peer_connection.on_data_channel(Box::new(move |channel| {
            let incoming_dc_tx = incoming_dc_tx.clone();
            Box::pin(async move {
                if channel.label() != expected_data_channel_label() {
                    let _ = incoming_dc_tx
                        .send(Err(WebRtcError::UnexpectedDataChannel(channel.label().to_owned())))
                        .await;
                    return;
                }
                let _ = incoming_dc_tx.send(Ok(DataChannelHandle::observe(channel))).await;
            })
        }));

        Ok(Self {
            peer_connection,
            local_candidate_rx: Arc::new(Mutex::new(local_candidate_rx)),
            ice_state_rx: Arc::new(Mutex::new(ice_state_rx)),
            #[cfg(any(test, debug_assertions))]
            test_ice_state_tx,
            incoming_data_channel_rx: Arc::new(Mutex::new(incoming_dc_rx)),
            config: config.clone(),
        })
    }

    pub fn peer_connection(&self) -> Arc<RTCPeerConnection> {
        Arc::clone(&self.peer_connection)
    }

    pub async fn create_offer(&self) -> Result<String, WebRtcError> {
        self.create_offer_with_restart(false).await
    }

    pub async fn create_offer_with_restart(
        &self,
        ice_restart: bool,
    ) -> Result<String, WebRtcError> {
        let offer = self
            .peer_connection
            .create_offer(Some(RTCOfferOptions { ice_restart, ..Default::default() }))
            .await?;
        self.peer_connection.set_local_description(offer).await?;
        self.local_sdp().await
    }

    pub async fn apply_remote_offer(&self, sdp: &str) -> Result<(), WebRtcError> {
        self.peer_connection
            .set_remote_description(RTCSessionDescription::offer(sdp.to_owned())?)
            .await?;
        Ok(())
    }

    pub async fn create_answer(&self) -> Result<String, WebRtcError> {
        let answer = self.peer_connection.create_answer(None).await?;
        self.peer_connection.set_local_description(answer).await?;
        self.local_sdp().await
    }

    pub async fn apply_remote_answer(&self, sdp: &str) -> Result<(), WebRtcError> {
        self.peer_connection
            .set_remote_description(RTCSessionDescription::answer(sdp.to_owned())?)
            .await?;
        Ok(())
    }

    pub async fn add_remote_candidate(
        &self,
        candidate: IceCandidateSignal,
    ) -> Result<(), WebRtcError> {
        let IceCandidateSignal { candidate, sdp_mid, sdp_mline_index } = candidate;
        // End-of-candidates is signaled by a separate message type, so a candidate message
        // with no content here is a protocol error — never coerce it to an empty string and
        // hand it to WebRTC as if it were a real candidate.
        let candidate = candidate.ok_or_else(|| {
            WebRtcError::InvalidConfig(
                "remote ICE candidate had no candidate content (empty candidate is invalid; \
                 end-of-candidates is signaled separately)"
                    .to_owned(),
            )
        })?;
        let init =
            RTCIceCandidateInit { candidate, sdp_mid, sdp_mline_index, username_fragment: None };
        self.peer_connection.add_ice_candidate(init).await?;
        Ok(())
    }

    pub async fn next_local_candidate(&self) -> Option<IceCandidateSignal> {
        self.local_candidate_rx.lock().await.recv().await
    }

    pub async fn next_ice_state(&self) -> Option<IceConnectionState> {
        self.ice_state_rx.lock().await.recv().await
    }

    #[cfg(any(test, debug_assertions))]
    pub fn ice_state_injector_for_tests(&self) -> IceStateInjectorForTests {
        IceStateInjectorForTests { tx: self.test_ice_state_tx.clone() }
    }

    pub async fn next_incoming_data_channel(
        &self,
    ) -> Option<Result<DataChannelHandle, WebRtcError>> {
        self.incoming_data_channel_rx.lock().await.recv().await
    }

    pub async fn create_data_channel(&self) -> Result<DataChannelHandle, WebRtcError> {
        let options = RTCDataChannelInit {
            ordered: Some(DATA_CHANNEL_ORDERED),
            max_packet_life_time: None,
            max_retransmits: if DATA_CHANNEL_RELIABLE { None } else { Some(0) },
            protocol: None,
            negotiated: None,
        };
        let channel = self
            .peer_connection
            .create_data_channel(expected_data_channel_label(), Some(options))
            .await?;
        if channel.label() != expected_data_channel_label() {
            return Err(WebRtcError::UnexpectedDataChannel(channel.label().to_owned()));
        }
        Ok(DataChannelHandle::observe(channel))
    }

    pub async fn wait_for_ice_state(
        &self,
        timeout: Duration,
        target: IceConnectionState,
    ) -> Result<(), WebRtcError> {
        tokio::time::timeout(timeout, async {
            loop {
                match self.next_ice_state().await {
                    Some(state) if state == target => return Ok(()),
                    Some(IceConnectionState::Failed) => {
                        return Err(WebRtcError::InvalidConfig(
                            "ice connection failed while waiting for target state".to_owned(),
                        ));
                    }
                    Some(_) => continue,
                    None => {
                        return Err(WebRtcError::InvalidConfig(
                            "ice state channel closed unexpectedly".to_owned(),
                        ));
                    }
                }
            }
        })
        .await
        .map_err(|_| WebRtcError::Timeout)?
    }

    pub async fn close(&self) -> Result<(), WebRtcError> {
        self.peer_connection.close().await?;
        Ok(())
    }

    async fn local_sdp(&self) -> Result<String, WebRtcError> {
        if !self.config.enable_trickle_ice {
            let mut gathering_complete = self.peer_connection.gathering_complete_promise().await;
            let _ = gathering_complete.recv().await;
        }
        self.peer_connection
            .local_description()
            .await
            .map(|description| description.sdp)
            .ok_or_else(|| {
                WebRtcError::InvalidConfig(
                    "local description was not set after SDP creation".to_owned(),
                )
            })
    }
}

pub fn build_rtc_configuration(config: &WebRtcConfig) -> Result<RTCConfiguration, WebRtcError> {
    if config.stun_urls.iter().any(|url| url.starts_with("turn:") || url.starts_with("turns:")) {
        return Err(WebRtcError::InvalidConfig(
            "TURN servers are not supported in STUN-only mode".to_owned(),
        ));
    }

    Ok(RTCConfiguration {
        ice_servers: vec![RTCIceServer { urls: config.stun_urls.clone(), ..Default::default() }],
        ..Default::default()
    })
}

fn expected_data_channel_label() -> &'static str {
    DATA_CHANNEL_LABEL
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use super::{
        IceCandidateSignal, IceConnectionState, WebRtcPeer, build_rtc_configuration,
        expected_data_channel_label,
    };
    use crate::WebRtcError;
    use p2p_core::{DATA_CHANNEL_LABEL, WebRtcConfig};
    use tokio::time::timeout;

    fn sample_config() -> WebRtcConfig {
        WebRtcConfig {
            stun_urls: vec!["stun:stun.l.google.com:19302".to_owned()],
            enable_trickle_ice: true,
            enable_ice_restart: true,
            android_ice_mode: Default::default(),
            advertised_local_ipv4: None,
        }
    }

    #[test]
    fn build_configuration_from_stun_urls() {
        let config = build_rtc_configuration(&sample_config()).expect("configuration should build");
        assert_eq!(config.ice_servers.len(), 1);
        assert_eq!(config.ice_servers[0].urls[0], "stun:stun.l.google.com:19302");
    }

    #[test]
    fn turn_urls_are_rejected() {
        let mut config = sample_config();
        config.stun_urls = vec!["turn:example.com:3478".to_owned()];
        assert!(build_rtc_configuration(&config).is_err());
    }

    #[test]
    fn state_translation_covers_every_variant() {
        use webrtc::ice_transport::ice_connection_state::RTCIceConnectionState as Rtc;
        let cases = [
            (Rtc::New, IceConnectionState::New),
            (Rtc::Checking, IceConnectionState::Checking),
            (Rtc::Connected, IceConnectionState::Connected),
            (Rtc::Completed, IceConnectionState::Completed),
            (Rtc::Disconnected, IceConnectionState::Disconnected),
            (Rtc::Failed, IceConnectionState::Failed),
            (Rtc::Closed, IceConnectionState::Closed),
            // `Unspecified` (and any future unmapped variant) maps to the explicit `Unknown`,
            // never `New`, so an unexpected state is not misread as normal startup.
            (Rtc::Unspecified, IceConnectionState::Unknown),
        ];
        for (input, expected) in cases {
            assert_eq!(IceConnectionState::from(input), expected, "mapping {input:?}");
        }
    }

    #[test]
    fn data_channel_label_is_fixed_to_protocol_constant() {
        assert_eq!(expected_data_channel_label(), DATA_CHANNEL_LABEL);
    }

    #[tokio::test]
    async fn add_remote_candidate_rejects_missing_content() {
        // A candidate message with no content is a protocol error, not an empty candidate.
        let mut config = sample_config();
        config.stun_urls = Vec::new();
        let peer = WebRtcPeer::new(&config).await.expect("peer builds");
        let signal = IceCandidateSignal {
            candidate: None,
            sdp_mid: Some("0".to_owned()),
            sdp_mline_index: Some(0),
        };
        let error =
            peer.add_remote_candidate(signal).await.expect_err("missing content is rejected");
        assert!(matches!(error, WebRtcError::InvalidConfig(_)), "got {error:?}");
    }

    #[tokio::test]
    async fn incoming_data_channel_is_delivered_after_sdp_exchange() {
        let mut config = sample_config();
        config.stun_urls = Vec::new();
        config.enable_trickle_ice = false;

        let offer_peer = WebRtcPeer::new(&config).await.expect("offer peer should build");
        let answer_peer = WebRtcPeer::new(&config).await.expect("answer peer should build");

        let offer_channel =
            offer_peer.create_data_channel().await.expect("offer data channel should build");
        let offer_sdp = offer_peer.create_offer().await.expect("offer SDP should build");
        answer_peer.apply_remote_offer(&offer_sdp).await.expect("answer should apply remote offer");
        let answer_sdp = answer_peer.create_answer().await.expect("answer SDP should build");
        offer_peer
            .apply_remote_answer(&answer_sdp)
            .await
            .expect("offer should apply remote answer");

        let answer_channel =
            timeout(Duration::from_secs(10), answer_peer.next_incoming_data_channel())
                .await
                .expect("incoming data channel should arrive")
                .expect("incoming data channel stream should yield")
                .expect("incoming data channel should be accepted");

        assert_eq!(answer_channel.label(), DATA_CHANNEL_LABEL);
        assert!(answer_channel.ordered());

        offer_channel
            .wait_for_open(Duration::from_secs(10))
            .await
            .expect("offer data channel should open");
        answer_channel
            .wait_for_open(Duration::from_secs(10))
            .await
            .expect("answer data channel should open");

        assert!(offer_channel.is_open());
        assert!(answer_channel.is_open());

        offer_peer.close().await.expect("offer peer should close");
        answer_peer.close().await.expect("answer peer should close");
    }

    #[tokio::test]
    async fn injected_ice_state_is_delivered_to_observers() {
        let mut config = sample_config();
        config.stun_urls = Vec::new();
        config.enable_trickle_ice = false;

        let peer = WebRtcPeer::new(&config).await.expect("peer should build");
        peer.ice_state_injector_for_tests()
            .inject(IceConnectionState::Disconnected)
            .await
            .expect("test ice state injection should succeed");

        let observed = timeout(Duration::from_secs(1), peer.next_ice_state())
            .await
            .expect("observer should receive injected ice state in time")
            .expect("ice state stream should yield an injected value");

        assert_eq!(observed, IceConnectionState::Disconnected);

        peer.close().await.expect("peer should close");
    }
}
