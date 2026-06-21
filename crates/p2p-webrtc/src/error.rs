//! The crate's error type.

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
