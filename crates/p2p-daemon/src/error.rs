use p2p_webrtc::IceConnectionState;

#[derive(Debug, thiserror::Error)]
pub enum DaemonError {
    #[error("core config error: {0}")]
    Config(#[from] p2p_core::ConfigError),
    #[error("crypto error: {0}")]
    Crypto(#[from] p2p_crypto::CryptoError),
    #[error("signaling error: {0}")]
    Signaling(#[from] p2p_signaling::SignalingError),
    #[error("webrtc error: {0}")]
    WebRtc(#[from] p2p_webrtc::WebRtcError),
    #[error("tunnel error: {0}")]
    Tunnel(#[from] p2p_tunnel::TunnelError),
    #[error("i/o error: {0}")]
    Io(#[from] std::io::Error),
    #[error("missing authorized peer '{0}'")]
    MissingAuthorizedPeer(String),
    #[error("acknowledgement timed out")]
    AckTimeout,
    #[error("ice failed in state {0:?}")]
    IceFailed(IceConnectionState),
    #[error("remote peer closed session with reason '{0}'")]
    RemoteClosed(String),
    #[error("remote peer reported error '{0}': {1}")]
    RemoteError(String, String),
    #[error("expected data channel was not available")]
    MissingDataChannel,
    #[error("data channel did not open within {0:?} after session start")]
    DataChannelOpenTimeout(std::time::Duration),
    #[error("data-plane probe failed after data channel open: {0}")]
    DataPlaneProbeFailed(p2p_tunnel::TunnelError),
    #[error("logging setup error: {0}")]
    Logging(String),
    #[error("offer accept worker for forward '{forward_id}' exited unexpectedly: {reason}")]
    OfferAcceptWorkerFailed { forward_id: String, reason: String },
}
