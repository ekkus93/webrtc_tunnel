use serde::{Deserialize, Serialize};

pub const PROTOCOL_MAGIC: [u8; 4] = *b"P2TS";
pub const PROTOCOL_VERSION: u8 = 1;
pub const PROTOCOL_SUITE: u8 = 1;
pub const FRAME_VERSION: u8 = 1;
pub const ACTIVE_STREAM_ID: u32 = 1;
pub const ACK_RETRY_TIMEOUT_SECS: u64 = 2;
pub const ACK_RETRY_LIMIT: u8 = 3;
pub const DATA_CHANNEL_LABEL: &str = "tunnel";
pub const DATA_CHANNEL_ORDERED: bool = true;
pub const DATA_CHANNEL_RELIABLE: bool = true;
pub const END_OF_CANDIDATES_MESSAGE_TYPE: u8 = 12;

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum DaemonState {
    Idle,
    WaitingForLocalClient,
    Negotiating,
    ConnectingDataChannel,
    TunnelOpen,
    IceRestarting,
    Renegotiating,
    Backoff,
    Closed,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum FailureCode {
    IceFailed,
    IceTimeout,
    PeerConnectionClosed,
    UnauthorizedPeer,
    DecryptFailed,
    SignatureInvalid,
    ReplayDetected,
    TargetConnectFailed,
    ProtocolError,
    Busy,
}

impl FailureCode {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::IceFailed => "ice_failed",
            Self::IceTimeout => "ice_timeout",
            Self::PeerConnectionClosed => "peer_connection_closed",
            Self::UnauthorizedPeer => "unauthorized_peer",
            Self::DecryptFailed => "decrypt_failed",
            Self::SignatureInvalid => "signature_invalid",
            Self::ReplayDetected => "replay_detected",
            Self::TargetConnectFailed => "target_connect_failed",
            Self::ProtocolError => "protocol_error",
            Self::Busy => "busy",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[repr(u8)]
pub enum MessageType {
    Hello = 1,
    Offer = 2,
    Answer = 3,
    IceCandidate = 4,
    Ack = 5,
    Ping = 6,
    Pong = 7,
    Close = 8,
    Error = 9,
    IceRestartRequest = 10,
    RenegotiateRequest = 11,
    EndOfCandidates = END_OF_CANDIDATES_MESSAGE_TYPE,
}

impl MessageType {
    pub fn requires_ack(self) -> bool {
        matches!(
            self,
            Self::Offer
                | Self::Answer
                | Self::IceCandidate
                | Self::Close
                | Self::Error
                | Self::IceRestartRequest
                | Self::RenegotiateRequest
        )
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[repr(u8)]
pub enum TunnelFrameType {
    Open = 0,
    Data = 1,
    Close = 2,
    Error = 3,
    Ping = 4,
    Pong = 5,
}
