use std::time::{SystemTime, UNIX_EPOCH};

use p2p_core::{MessageType, MsgId, PeerId, ProtocolError, SessionId};
use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct InnerMessage {
    pub version: u8,
    pub message_type: MessageType,
    pub session_id: SessionId,
    pub sender_peer_id: PeerId,
    pub recipient_peer_id: PeerId,
    pub timestamp_ms: u64,
    pub body: MessageBody,
}

impl InnerMessage {
    pub fn encode(&self) -> Result<Vec<u8>, serde_cbor::Error> {
        let raw = RawInnerMessage {
            v: self.version,
            t: self.message_type as u8,
            sid: self.session_id.into_bytes(),
            sp: self.sender_peer_id.as_str().to_owned(),
            rp: self.recipient_peer_id.as_str().to_owned(),
            ts: self.timestamp_ms,
            body: self.body.to_value()?,
        };
        serde_cbor::to_vec(&raw)
    }

    pub fn decode(bytes: &[u8]) -> Result<Self, ProtocolError> {
        let raw: RawInnerMessage = serde_cbor::from_slice(bytes)
            .map_err(|error| ProtocolError::InvalidMessage(error.to_string()))?;
        let message_type = message_type_from_u8(raw.t)?;
        let sender_peer_id = raw.sp.parse()?;
        let recipient_peer_id = raw.rp.parse()?;
        let body = MessageBody::from_value(message_type, raw.body)?;
        Ok(Self {
            version: raw.v,
            message_type,
            session_id: SessionId::new(raw.sid),
            sender_peer_id,
            recipient_peer_id,
            timestamp_ms: raw.ts,
            body,
        })
    }
}

#[derive(Clone, Debug)]
pub struct InnerMessageBuilder {
    session_id: SessionId,
    sender_peer_id: PeerId,
    recipient_peer_id: PeerId,
}

impl InnerMessageBuilder {
    pub fn new(session_id: SessionId, sender_peer_id: PeerId, recipient_peer_id: PeerId) -> Self {
        Self { session_id, sender_peer_id, recipient_peer_id }
    }

    pub fn build(self, body: MessageBody) -> InnerMessage {
        InnerMessage {
            version: 1,
            message_type: body.message_type(),
            session_id: self.session_id,
            sender_peer_id: self.sender_peer_id,
            recipient_peer_id: self.recipient_peer_id,
            timestamp_ms: current_time_ms(),
            body,
        }
    }

    pub fn ack(self, ack_msg_id: MsgId) -> InnerMessage {
        self.build(MessageBody::Ack(AckBody { ack_msg_id: ack_msg_id.into_bytes() }))
    }
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct HelloBody {
    pub role: String,
    pub caps: Vec<String>,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct OfferBody {
    pub sdp: String,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AnswerBody {
    pub sdp: String,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct IceCandidateBody {
    pub candidate: Option<String>,
    pub sdp_mid: Option<String>,
    pub sdp_mline_index: Option<u16>,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AckBody {
    pub ack_msg_id: [u8; 16],
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PingBody {
    pub seq: u64,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct CloseBody {
    pub reason_code: String,
    pub message: Option<String>,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ErrorBody {
    pub code: String,
    pub message: String,
    pub fatal: bool,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RenegotiateRequestBody {
    pub reason: String,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize, Default)]
#[serde(deny_unknown_fields)]
pub struct EndOfCandidatesBody {}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum MessageBody {
    Hello(HelloBody),
    Offer(OfferBody),
    Answer(AnswerBody),
    IceCandidate(IceCandidateBody),
    Ack(AckBody),
    Ping(PingBody),
    Pong(PingBody),
    Close(CloseBody),
    Error(ErrorBody),
    IceRestartRequest,
    RenegotiateRequest(RenegotiateRequestBody),
    EndOfCandidates(EndOfCandidatesBody),
}

impl MessageBody {
    pub fn message_type(&self) -> MessageType {
        match self {
            Self::Hello(_) => MessageType::Hello,
            Self::Offer(_) => MessageType::Offer,
            Self::Answer(_) => MessageType::Answer,
            Self::IceCandidate(_) => MessageType::IceCandidate,
            Self::Ack(_) => MessageType::Ack,
            Self::Ping(_) => MessageType::Ping,
            Self::Pong(_) => MessageType::Pong,
            Self::Close(_) => MessageType::Close,
            Self::Error(_) => MessageType::Error,
            Self::IceRestartRequest => MessageType::IceRestartRequest,
            Self::RenegotiateRequest(_) => MessageType::RenegotiateRequest,
            Self::EndOfCandidates(_) => MessageType::EndOfCandidates,
        }
    }

    fn to_value(&self) -> Result<serde_cbor::Value, serde_cbor::Error> {
        match self {
            Self::Hello(body) => serde_cbor::value::to_value(body),
            Self::Offer(body) => serde_cbor::value::to_value(body),
            Self::Answer(body) => serde_cbor::value::to_value(body),
            Self::IceCandidate(body) => serde_cbor::value::to_value(body),
            Self::Ack(body) => serde_cbor::value::to_value(body),
            Self::Ping(body) | Self::Pong(body) => serde_cbor::value::to_value(body),
            Self::Close(body) => serde_cbor::value::to_value(body),
            Self::Error(body) => serde_cbor::value::to_value(body),
            Self::IceRestartRequest => serde_cbor::value::to_value(EndOfCandidatesBody {}),
            Self::RenegotiateRequest(body) => serde_cbor::value::to_value(body),
            Self::EndOfCandidates(body) => serde_cbor::value::to_value(body),
        }
    }

    fn from_value(
        message_type: MessageType,
        value: serde_cbor::Value,
    ) -> Result<Self, ProtocolError> {
        match message_type {
            MessageType::Hello => {
                Ok(Self::Hello(serde_cbor::value::from_value(value).map_err(as_protocol)?))
            }
            MessageType::Offer => {
                Ok(Self::Offer(serde_cbor::value::from_value(value).map_err(as_protocol)?))
            }
            MessageType::Answer => {
                Ok(Self::Answer(serde_cbor::value::from_value(value).map_err(as_protocol)?))
            }
            MessageType::IceCandidate => {
                Ok(Self::IceCandidate(serde_cbor::value::from_value(value).map_err(as_protocol)?))
            }
            MessageType::Ack => {
                Ok(Self::Ack(serde_cbor::value::from_value(value).map_err(as_protocol)?))
            }
            MessageType::Ping => {
                Ok(Self::Ping(serde_cbor::value::from_value(value).map_err(as_protocol)?))
            }
            MessageType::Pong => {
                Ok(Self::Pong(serde_cbor::value::from_value(value).map_err(as_protocol)?))
            }
            MessageType::Close => {
                Ok(Self::Close(serde_cbor::value::from_value(value).map_err(as_protocol)?))
            }
            MessageType::Error => {
                Ok(Self::Error(serde_cbor::value::from_value(value).map_err(as_protocol)?))
            }
            MessageType::IceRestartRequest => {
                let _: EndOfCandidatesBody =
                    serde_cbor::value::from_value(value).map_err(as_protocol)?;
                Ok(Self::IceRestartRequest)
            }
            MessageType::RenegotiateRequest => Ok(Self::RenegotiateRequest(
                serde_cbor::value::from_value(value).map_err(as_protocol)?,
            )),
            MessageType::EndOfCandidates => Ok(Self::EndOfCandidates(
                serde_cbor::value::from_value(value).map_err(as_protocol)?,
            )),
        }
    }
}

#[derive(Serialize, Deserialize)]
struct RawInnerMessage {
    v: u8,
    t: u8,
    sid: [u8; 16],
    sp: String,
    rp: String,
    ts: u64,
    body: serde_cbor::Value,
}

fn current_time_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("system time is before unix epoch")
        .as_millis() as u64
}

fn as_protocol(error: serde_cbor::Error) -> ProtocolError {
    ProtocolError::InvalidMessage(error.to_string())
}

fn message_type_from_u8(value: u8) -> Result<MessageType, ProtocolError> {
    match value {
        1 => Ok(MessageType::Hello),
        2 => Ok(MessageType::Offer),
        3 => Ok(MessageType::Answer),
        4 => Ok(MessageType::IceCandidate),
        5 => Ok(MessageType::Ack),
        6 => Ok(MessageType::Ping),
        7 => Ok(MessageType::Pong),
        8 => Ok(MessageType::Close),
        9 => Ok(MessageType::Error),
        10 => Ok(MessageType::IceRestartRequest),
        11 => Ok(MessageType::RenegotiateRequest),
        12 => Ok(MessageType::EndOfCandidates),
        _ => Err(ProtocolError::InvalidMessage(format!("unknown message type {value}"))),
    }
}
