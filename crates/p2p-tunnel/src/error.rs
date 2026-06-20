use p2p_core::{FRAME_VERSION, FailureCode, TunnelFrameType};

#[derive(Debug, thiserror::Error)]
pub enum TunnelError {
    #[error("unsupported tunnel frame version {actual}; expected {expected}")]
    UnsupportedVersion { actual: u8, expected: u8 },
    #[error("stream id 0 is reserved for session-level control")]
    ReservedStreamId,
    #[error("session-level control frame used nonzero stream id {0}")]
    SessionControlStreamId(u32),
    #[error("unknown tunnel frame type {0}")]
    UnknownFrameType(u8),
    #[error("truncated tunnel frame")]
    TruncatedFrame,
    #[error("invalid tunnel frame: {0}")]
    InvalidFrame(String),
    #[error("i/o error: {0}")]
    Io(#[from] std::io::Error),
    #[error("failed to connect target tcp service: {0}")]
    TargetConnectFailed(String),
    #[error("webrtc error: {0}")]
    WebRtc(#[from] p2p_webrtc::WebRtcError),
    #[error("offer listener is busy")]
    Busy,
    #[error("data channel closed")]
    DataChannelClosed,
    #[error("data-plane probe timed out after {0:?}")]
    DataPlaneProbeTimeout(std::time::Duration),
    #[error("data-plane probe failed: {0}")]
    DataPlaneProbeFailed(String),
    #[error("data-plane heartbeat lost: {missed} consecutive heartbeats unacknowledged")]
    DataPlaneHeartbeatLost { missed: u32 },
    #[error("data channel writer closed")]
    WriterClosed,
    #[error("stream id space exhausted")]
    StreamIdExhausted,
    #[error("stream {0} already exists")]
    StreamAlreadyExists(u32),
    #[error("stream {0} not found")]
    StreamNotFound(u32),
    #[error("unexpected tunnel frame {0:?}")]
    UnexpectedFrame(TunnelFrameType),
    #[error("remote tunnel failure: {}", code.as_str())]
    RemoteFailure { code: FailureCode, detail: Option<String> },
}

impl TunnelError {
    pub fn unsupported_version(actual: u8) -> Self {
        Self::UnsupportedVersion { actual, expected: FRAME_VERSION }
    }
}
