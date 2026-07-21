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
    #[error("system clock is unavailable: {0}")]
    Clock(std::time::SystemTimeError),
    #[error("offer accept worker for forward '{forward_id}' exited unexpectedly: {reason}")]
    OfferAcceptWorkerFailed { forward_id: String, reason: String },
    #[error("offer accept monitor for forward '{forward_id}' failed: {reason}")]
    OfferAcceptMonitorJoinFailed { forward_id: String, reason: String },
}

/// True for daemon runtime infrastructure failures (an accept-worker or its monitor
/// dying unexpectedly) as opposed to ordinary session outcomes (remote close, probe
/// failure, ICE failure, etc.). An infrastructure failure must never be fed into
/// ordinary session cooldown/recovery just because it happened while a session was
/// active — see `is_offer_infrastructure_failure`'s callers in `offer::mod`.
pub(crate) fn is_offer_infrastructure_failure(error: &DaemonError) -> bool {
    matches!(
        error,
        DaemonError::OfferAcceptWorkerFailed { .. }
            | DaemonError::OfferAcceptMonitorJoinFailed { .. }
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn offer_accept_worker_failed_is_infrastructure_failure() {
        assert!(is_offer_infrastructure_failure(&DaemonError::OfferAcceptWorkerFailed {
            forward_id: "a".to_owned(),
            reason: "panic".to_owned(),
        }));
    }

    #[test]
    fn offer_accept_monitor_join_failed_is_infrastructure_failure() {
        assert!(is_offer_infrastructure_failure(&DaemonError::OfferAcceptMonitorJoinFailed {
            forward_id: "a".to_owned(),
            reason: "join error".to_owned(),
        }));
    }

    #[test]
    fn ordinary_session_error_is_not_infrastructure_failure() {
        assert!(!is_offer_infrastructure_failure(&DaemonError::IceFailed(
            IceConnectionState::Failed
        )));
        assert!(!is_offer_infrastructure_failure(&DaemonError::AckTimeout));
        assert!(!is_offer_infrastructure_failure(&DaemonError::RemoteClosed("bye".to_owned())));
    }
}
