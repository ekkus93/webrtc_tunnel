use std::sync::Arc;
use std::time::Duration;

use bytes::Bytes;
use tokio::sync::{Mutex, mpsc};
use webrtc::api::APIBuilder;
use webrtc::api::media_engine::MediaEngine;
use webrtc::data_channel::RTCDataChannel;
use webrtc::data_channel::data_channel_init::RTCDataChannelInit;
use webrtc::data_channel::data_channel_message::DataChannelMessage;
use webrtc::ice_transport::ice_candidate::RTCIceCandidateInit;
use webrtc::ice_transport::ice_connection_state::RTCIceConnectionState;
use webrtc::ice_transport::ice_server::RTCIceServer;
use webrtc::peer_connection::RTCPeerConnection;
use webrtc::peer_connection::configuration::RTCConfiguration;
use webrtc::peer_connection::offer_answer_options::RTCOfferOptions;
use webrtc::peer_connection::sdp::session_description::RTCSessionDescription;

use p2p_core::{DATA_CHANNEL_LABEL, DATA_CHANNEL_ORDERED, DATA_CHANNEL_RELIABLE, WebRtcConfig};

#[derive(Debug, thiserror::Error)]
pub enum WebRtcError {
    #[error("invalid WebRTC config: {0}")]
    InvalidConfig(String),
    #[error("webrtc error: {0}")]
    Native(Box<webrtc::error::Error>),
    #[error("timed out waiting for WebRTC event")]
    Timeout,
    #[error("unexpected data channel label '{0}'")]
    UnexpectedDataChannel(String),
}

impl From<webrtc::error::Error> for WebRtcError {
    fn from(error: webrtc::error::Error) -> Self {
        Self::Native(Box::new(error))
    }
}

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
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum DataChannelEvent {
    Open,
    Closed,
    Message(Vec<u8>),
}

#[derive(Clone)]
pub struct DataChannelHandle {
    inner: Arc<RTCDataChannel>,
    events: Arc<Mutex<mpsc::Receiver<DataChannelEvent>>>,
}

impl DataChannelHandle {
    fn observe(inner: Arc<RTCDataChannel>) -> Self {
        let (events_tx, events_rx) = mpsc::channel(32);
        let open_tx = events_tx.clone();
        inner.on_open(Box::new(move || {
            let open_tx = open_tx.clone();
            Box::pin(async move {
                let _ = open_tx.send(DataChannelEvent::Open).await;
            })
        }));

        let close_tx = events_tx.clone();
        inner.on_close(Box::new(move || {
            let close_tx = close_tx.clone();
            Box::pin(async move {
                let _ = close_tx.send(DataChannelEvent::Closed).await;
            })
        }));

        let message_tx = events_tx.clone();
        inner.on_message(Box::new(move |message: DataChannelMessage| {
            let message_tx = message_tx.clone();
            Box::pin(async move {
                let _ = message_tx.send(DataChannelEvent::Message(message.data.to_vec())).await;
            })
        }));

        Self { inner, events: Arc::new(Mutex::new(events_rx)) }
    }

    pub fn label(&self) -> String {
        self.inner.label().to_owned()
    }

    pub fn ordered(&self) -> bool {
        self.inner.ordered()
    }

    pub async fn send(&self, payload: &[u8]) -> Result<usize, WebRtcError> {
        self.inner.send(&Bytes::copy_from_slice(payload)).await.map_err(WebRtcError::from)
    }

    pub async fn next_event(&self) -> Option<DataChannelEvent> {
        self.events.lock().await.recv().await
    }

    pub async fn wait_for_open(&self, timeout: Duration) -> Result<(), WebRtcError> {
        tokio::time::timeout(timeout, async {
            loop {
                match self.next_event().await {
                    Some(DataChannelEvent::Open) => return Ok(()),
                    Some(_) => continue,
                    None => {
                        return Err(WebRtcError::InvalidConfig(
                            "data channel closed before open".to_owned(),
                        ));
                    }
                }
            }
        })
        .await
        .map_err(|_| WebRtcError::Timeout)?
    }
}

pub struct WebRtcPeer {
    peer_connection: Arc<RTCPeerConnection>,
    local_candidate_rx: Arc<Mutex<mpsc::Receiver<IceCandidateSignal>>>,
    ice_state_rx: Arc<Mutex<mpsc::Receiver<IceConnectionState>>>,
    incoming_data_channel_rx: Arc<Mutex<mpsc::Receiver<Result<DataChannelHandle, WebRtcError>>>>,
    config: WebRtcConfig,
}

impl WebRtcPeer {
    pub async fn new(config: &WebRtcConfig) -> Result<Self, WebRtcError> {
        let rtc_config = build_rtc_configuration(config)?;
        let mut media_engine = MediaEngine::default();
        media_engine.register_default_codecs()?;
        let api = APIBuilder::new().with_media_engine(media_engine).build();
        let peer_connection = Arc::new(api.new_peer_connection(rtc_config).await?);

        let (local_candidate_tx, local_candidate_rx) = mpsc::channel(64);
        let (ice_state_tx, ice_state_rx) = mpsc::channel(32);
        let (incoming_dc_tx, incoming_dc_rx) = mpsc::channel(8);

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
        let candidate = RTCIceCandidateInit {
            candidate: candidate.candidate.unwrap_or_default(),
            sdp_mid: candidate.sdp_mid,
            sdp_mline_index: candidate.sdp_mline_index,
            username_fragment: None,
        };
        self.peer_connection.add_ice_candidate(candidate).await?;
        Ok(())
    }

    pub async fn next_local_candidate(&self) -> Option<IceCandidateSignal> {
        self.local_candidate_rx.lock().await.recv().await
    }

    pub async fn next_ice_state(&self) -> Option<IceConnectionState> {
        self.ice_state_rx.lock().await.recv().await
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
        return Err(WebRtcError::InvalidConfig("TURN URLs are not supported in v1".to_owned()));
    }

    Ok(RTCConfiguration {
        ice_servers: vec![RTCIceServer { urls: config.stun_urls.clone(), ..Default::default() }],
        ..Default::default()
    })
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
            _ => Self::New,
        }
    }
}

fn expected_data_channel_label() -> &'static str {
    DATA_CHANNEL_LABEL
}

#[cfg(test)]
mod tests {
    use super::{IceConnectionState, build_rtc_configuration, expected_data_channel_label};
    use p2p_core::DATA_CHANNEL_LABEL;
    use p2p_core::WebRtcConfig;

    fn sample_config() -> WebRtcConfig {
        WebRtcConfig {
            stun_urls: vec!["stun:stun.l.google.com:19302".to_owned()],
            enable_trickle_ice: true,
            enable_ice_restart: true,
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
    fn state_translation_maps_failed() {
        let state = IceConnectionState::from(
            webrtc::ice_transport::ice_connection_state::RTCIceConnectionState::Failed,
        );
        assert_eq!(state, IceConnectionState::Failed);
    }

    #[test]
    fn data_channel_label_is_fixed_to_protocol_constant() {
        assert_eq!(expected_data_channel_label(), DATA_CHANNEL_LABEL);
    }
}
