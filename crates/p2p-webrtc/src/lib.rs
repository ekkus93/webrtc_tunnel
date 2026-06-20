use std::net::{IpAddr, Ipv4Addr, UdpSocket};
use std::sync::Arc;
use std::time::Duration;

use bytes::Bytes;
use ipnet::IpNet;
use tokio::sync::{Mutex, mpsc};
use webrtc::api::APIBuilder;
use webrtc::api::media_engine::MediaEngine;
use webrtc::api::setting_engine::SettingEngine;
use webrtc::data_channel::RTCDataChannel;
use webrtc::data_channel::data_channel_init::RTCDataChannelInit;
use webrtc::data_channel::data_channel_message::DataChannelMessage;
use webrtc::data_channel::data_channel_state::RTCDataChannelState;
use webrtc::ice::udp_mux::{UDPMuxDefault, UDPMuxParams};
use webrtc::ice::udp_network::UDPNetwork;
use webrtc::ice_transport::ice_candidate::RTCIceCandidateInit;
use webrtc::ice_transport::ice_connection_state::RTCIceConnectionState;
use webrtc::ice_transport::ice_server::RTCIceServer;
use webrtc::peer_connection::RTCPeerConnection;
use webrtc::peer_connection::configuration::RTCConfiguration;
use webrtc::peer_connection::offer_answer_options::RTCOfferOptions;
use webrtc::peer_connection::sdp::session_description::RTCSessionDescription;
use webrtc_util::ifaces;
use webrtc_util::vnet::interface::Interface;
use webrtc_util::vnet::net::Net;

use p2p_core::{
    AndroidIceMode, DATA_CHANNEL_LABEL, DATA_CHANNEL_ORDERED, DATA_CHANNEL_RELIABLE, WebRtcConfig,
};

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

    pub fn is_open(&self) -> bool {
        self.inner.ready_state() == RTCDataChannelState::Open
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

/// The resolved ICE candidate-gathering path, decided from the configured
/// [`AndroidIceMode`] and whether OS interface enumeration works.
///
/// Kept as a pure value so the decision can be unit-tested without touching real
/// network interfaces (`decide_ice_path`).
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum IcePath {
    /// Use the native/default `SettingEngine`; never call `set_vnet`.
    Native,
    /// Force the `Net::Ifs` vnet fallback. `required` means a missing fallback IPv4 is a
    /// hard error (explicit `vnet`/`vnet_mux` mode) rather than a best-effort warning
    /// (`auto`). `mux` additionally routes all ICE traffic through a single `0.0.0.0`-bound
    /// UDP socket (webrtc UDP mux) instead of a socket pinned to the interface IP.
    Vnet { required: bool, mux: bool },
}

/// Pure decision: which ICE path to use given the mode and enumeration result.
///
/// `android_ice_mode` is honored on **all** platforms — the name is historical. The vnet
/// fallback is selected at runtime by interface-enumeration success, not by
/// `#[cfg(target_os = "android")]`, so desktop integration tests can force `native`/`vnet`
/// too. There is no silent cross-mode fallback: `native` never engages vnet and `vnet`
/// never silently downgrades to native (a missing fallback IPv4 is a hard error).
const fn decide_ice_path(mode: AndroidIceMode, enumeration_works: bool) -> IcePath {
    match mode {
        AndroidIceMode::Native => IcePath::Native,
        AndroidIceMode::Vnet => IcePath::Vnet { required: true, mux: false },
        AndroidIceMode::VnetMux => IcePath::Vnet { required: true, mux: true },
        AndroidIceMode::Auto => {
            if enumeration_works {
                IcePath::Native
            } else {
                IcePath::Vnet { required: false, mux: false }
            }
        }
    }
}

/// A short, stable reason string for the decision log.
const fn ice_decision_reason(mode: AndroidIceMode, enumeration_works: bool) -> &'static str {
    match mode {
        AndroidIceMode::Native => "mode_native",
        AndroidIceMode::Vnet => "mode_vnet",
        AndroidIceMode::VnetMux => "mode_vnet_mux",
        AndroidIceMode::Auto if enumeration_works => "interface_enumeration_ok",
        AndroidIceMode::Auto => "interface_enumeration_failed",
    }
}

/// Build the WebRTC `SettingEngine`, honoring [`WebRtcConfig::android_ice_mode`].
///
/// `auto` (default) preserves the historical behavior: use the native/default engine when
/// OS interface enumeration works (desktop), else inject a real-socket `Net::Ifs` fallback
/// carrying the primary local IPv4 — needed on Android 11+ (API 30+) where
/// `getifaddrs`/NETLINK enumeration is restricted, so webrtc-rs otherwise gathers no host
/// candidate. `native` always uses the default engine (never `set_vnet`) and fails loudly
/// through the normal connect path if no candidate is gathered. `vnet` always forces the
/// fallback and returns an error if a fallback local IPv4 cannot be determined. Every call
/// logs the requested mode and the selected path + reason; there is no silent fallback.
fn build_setting_engine(config: &WebRtcConfig) -> Result<SettingEngine, WebRtcError> {
    let mut engine = SettingEngine::default();
    let mode = config.android_ice_mode;
    let enumeration_works = os_interface_enumeration_works();
    let reason = ice_decision_reason(mode, enumeration_works);

    match decide_ice_path(mode, enumeration_works) {
        IcePath::Native => {
            tracing::info!(
                target: "ice",
                ?mode,
                selected_path = "native",
                set_vnet = false,
                enumeration_works,
                reason,
                "ICE setting engine decision",
            );
        }
        IcePath::Vnet { required, mux } => match fallback_net() {
            Some(net) => {
                engine.set_vnet(Some(Arc::new(net)));
                if mux {
                    // Route ICE I/O through a single 0.0.0.0-bound socket while still
                    // advertising the injected interface IP as the host candidate.
                    engine.set_udp_network(zero_bound_udp_mux()?);
                }
                tracing::info!(
                    target: "ice",
                    ?mode,
                    selected_path = if mux { "vnet_mux" } else { "vnet" },
                    set_vnet = true,
                    udp_mux = mux,
                    enumeration_works,
                    reason,
                    "ICE setting engine decision",
                );
            }
            None if required => {
                return Err(WebRtcError::InvalidConfig(
                    "android_ice_mode = \"vnet\"/\"vnet_mux\" was requested but no fallback local \
                     IPv4 could be determined; refusing to silently fall back to the native engine"
                        .to_owned(),
                ));
            }
            None => {
                tracing::warn!(
                    target: "ice",
                    ?mode,
                    selected_path = "native",
                    set_vnet = false,
                    enumeration_works,
                    reason,
                    "auto mode wanted the vnet fallback but no fallback local IPv4 was found; \
                     continuing with the native engine (ICE may gather no host candidate)",
                );
            }
        },
    }
    Ok(engine)
}

/// Whether webrtc-rs's own interface enumeration yields at least one usable
/// (non-loopback IPv4) host address. `getifaddrs` returning an error (Android) or an
/// empty / loopback-only list both count as "not working".
fn os_interface_enumeration_works() -> bool {
    match ifaces::ifaces() {
        Ok(list) => list.iter().any(
            |iface| matches!(iface.addr, Some(addr) if addr.is_ipv4() && !addr.ip().is_loopback()),
        ),
        Err(_) => false,
    }
}

/// A real-socket `Net` whose single interface carries the host's primary local IPv4.
fn fallback_net() -> Option<Net> {
    let ip = primary_local_ipv4()?;
    // The prefix length is irrelevant to candidate gathering (which only reads the
    // address); /24 is a reasonable placeholder for a LAN.
    let ipnet = IpNet::new(IpAddr::V4(ip), 24).ok()?;
    let interface = Interface::new("p2p-fallback".to_owned(), vec![ipnet]);
    Some(Net::Ifs(vec![interface]))
}

/// A webrtc UDP-mux network backed by a single real socket bound to `0.0.0.0:0`.
///
/// Used by `vnet_mux`: ICE still advertises the injected interface IP as the host candidate
/// (via `set_vnet`), but all traffic flows over this unbound socket. Binding `0.0.0.0`
/// (rather than the specific interface IP, as the plain `vnet` path does) lets the OS apply
/// its normal per-destination routing — on Android the `netd` fwmark for the default
/// network — instead of pinning egress to one source address, which is the suspected cause
/// of the offer→answer data-plane black-hole.
///
/// Must be called from within a Tokio runtime (it is, via `WebRtcPeer::new`): the socket is
/// bound with `std` then adopted with `from_std`, which registers it with the current
/// reactor. Muxed mode gathers no server-reflexive candidate (webrtc skips srflx for mux).
fn zero_bound_udp_mux() -> Result<UDPNetwork, WebRtcError> {
    let std_socket = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).map_err(|error| {
        WebRtcError::InvalidConfig(format!("failed to bind 0.0.0.0 UDP mux socket: {error}"))
    })?;
    std_socket.set_nonblocking(true).map_err(|error| {
        WebRtcError::InvalidConfig(format!("failed to set UDP mux socket non-blocking: {error}"))
    })?;
    let tokio_socket = tokio::net::UdpSocket::from_std(std_socket).map_err(|error| {
        WebRtcError::InvalidConfig(format!("failed to adopt UDP mux socket into tokio: {error}"))
    })?;
    let mux = UDPMuxDefault::new(UDPMuxParams::new(tokio_socket));
    Ok(UDPNetwork::Muxed(mux))
}

/// The OS-chosen source IPv4 for outbound traffic, discovered without interface
/// enumeration by "connecting" a UDP socket to a public address (no packets are sent)
/// and reading the bound local address.
fn primary_local_ipv4() -> Option<Ipv4Addr> {
    let socket = UdpSocket::bind("0.0.0.0:0").ok()?;
    socket.connect("8.8.8.8:80").ok()?;
    match socket.local_addr().ok()?.ip() {
        IpAddr::V4(addr) if !addr.is_loopback() && !addr.is_unspecified() => Some(addr),
        _ => None,
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
    use std::time::Duration;

    use super::{
        IceConnectionState, IcePath, WebRtcError, WebRtcPeer, build_rtc_configuration,
        build_setting_engine, decide_ice_path, expected_data_channel_label, zero_bound_udp_mux,
    };
    use p2p_core::AndroidIceMode;
    use p2p_core::DATA_CHANNEL_LABEL;
    use p2p_core::WebRtcConfig;
    use tokio::time::timeout;

    fn sample_config() -> WebRtcConfig {
        WebRtcConfig {
            stun_urls: vec!["stun:stun.l.google.com:19302".to_owned()],
            enable_trickle_ice: true,
            enable_ice_restart: true,
            android_ice_mode: Default::default(),
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

    #[test]
    fn ice_path_decision_covers_all_modes() {
        // auto follows enumeration; vnet fallback when auto can't enumerate is best-effort.
        assert_eq!(decide_ice_path(AndroidIceMode::Auto, true), IcePath::Native);
        assert_eq!(
            decide_ice_path(AndroidIceMode::Auto, false),
            IcePath::Vnet { required: false, mux: false }
        );
        // native is always native, regardless of enumeration; never engages vnet.
        assert_eq!(decide_ice_path(AndroidIceMode::Native, true), IcePath::Native);
        assert_eq!(decide_ice_path(AndroidIceMode::Native, false), IcePath::Native);
        // vnet always forces the fallback and treats a missing IPv4 as a hard error.
        assert_eq!(
            decide_ice_path(AndroidIceMode::Vnet, true),
            IcePath::Vnet { required: true, mux: false }
        );
        assert_eq!(
            decide_ice_path(AndroidIceMode::Vnet, false),
            IcePath::Vnet { required: true, mux: false }
        );
        // vnet_mux is vnet with the UDP mux engaged; also a hard error on missing IPv4.
        assert_eq!(
            decide_ice_path(AndroidIceMode::VnetMux, true),
            IcePath::Vnet { required: true, mux: true }
        );
        assert_eq!(
            decide_ice_path(AndroidIceMode::VnetMux, false),
            IcePath::Vnet { required: true, mux: true }
        );
    }

    #[test]
    fn native_mode_builds_engine_without_fallback() {
        // native must never fail on the decision itself (it never requires a fallback IPv4),
        // independent of the host's actual interfaces.
        let mut config = sample_config();
        config.android_ice_mode = AndroidIceMode::Native;
        assert!(build_setting_engine(&config).is_ok());
    }

    #[test]
    fn auto_mode_builds_engine() {
        let mut config = sample_config();
        config.android_ice_mode = AndroidIceMode::Auto;
        assert!(build_setting_engine(&config).is_ok());
    }

    #[tokio::test]
    async fn zero_bound_udp_mux_binds_real_socket() {
        // Must run inside a Tokio runtime (from_std registers with the reactor).
        assert!(zero_bound_udp_mux().is_ok(), "0.0.0.0 UDP mux should bind");
    }

    #[tokio::test]
    async fn vnet_mux_mode_builds_engine_when_fallback_ipv4_exists() {
        // vnet_mux forces the fallback path and engages the UDP mux. It builds when a
        // non-loopback IPv4 is available (CI host); otherwise it fails loudly with the
        // missing-fallback error — never silently, and never a panic.
        let mut config = sample_config();
        config.android_ice_mode = AndroidIceMode::VnetMux;
        match build_setting_engine(&config) {
            Ok(_) => {}
            Err(WebRtcError::InvalidConfig(message)) => {
                assert!(message.contains("no fallback local"), "unexpected error: {message}");
            }
            Err(other) => panic!("unexpected error variant: {other}"),
        }
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
