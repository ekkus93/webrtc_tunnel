use p2p_core::{MessageType, MsgId, PeerId, ProtocolError, SessionId, unix_time_ms};
use serde::{Deserialize, Serialize};

use crate::error::SignalingError;

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

    /// FIX7 P0-010-A/P0-010-D: the wire timestamp a peer's freshness check verifies is
    /// correctness-sensitive — fallible because the clock read is, propagating a typed error
    /// rather than panicking or inventing a timestamp. A message must never be built with a
    /// fabricated `timestamp_ms`.
    pub fn build(self, body: MessageBody) -> Result<InnerMessage, SignalingError> {
        self.build_with_clock(body, unix_time_ms)
    }

    /// FIX7 P0-010-F: injectable clock seam so tests can deterministically exercise a clock
    /// failure without mutating the real system clock. `pub(crate)` — only this crate's own
    /// tests need it; [`build`] is the public API and always uses the real clock.
    pub(crate) fn build_with_clock(
        self,
        body: MessageBody,
        clock: fn() -> Result<u64, std::time::SystemTimeError>,
    ) -> Result<InnerMessage, SignalingError> {
        Ok(InnerMessage {
            version: 1,
            message_type: body.message_type(),
            session_id: self.session_id,
            sender_peer_id: self.sender_peer_id,
            recipient_peer_id: self.recipient_peer_id,
            timestamp_ms: clock().map_err(SignalingError::Clock)?,
            body,
        })
    }

    pub fn ack(self, ack_msg_id: MsgId) -> Result<InnerMessage, SignalingError> {
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

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use serde_cbor::Value;

    use super::*;

    fn peer_id(value: &str) -> PeerId {
        value.parse().expect("valid peer id")
    }

    fn sample_inner_message() -> InnerMessage {
        InnerMessage {
            version: 1,
            message_type: MessageType::Offer,
            session_id: SessionId::new([9_u8; 16]),
            sender_peer_id: peer_id("offer-home"),
            recipient_peer_id: peer_id("answer-office"),
            timestamp_ms: 42,
            body: MessageBody::Offer(OfferBody { sdp: "v=0\r\n".to_owned() }),
        }
    }

    /// A genuine `SystemTimeError`, synthesized without touching the real system clock: asking
    /// for the duration since a point strictly in the future always fails this way (FIX7
    /// P0-010-F — "do not mutate system clock in tests").
    fn synthetic_clock_error() -> std::time::SystemTimeError {
        let future = std::time::SystemTime::now() + std::time::Duration::from_secs(3600);
        std::time::SystemTime::now()
            .duration_since(future)
            .expect_err("a point strictly in the future must make duration_since fail")
    }

    fn failing_clock() -> Result<u64, std::time::SystemTimeError> {
        Err(synthetic_clock_error())
    }

    // FIX7 P0-010-G: a message must never be built with a fabricated timestamp on clock
    // failure — the typed error propagates instead.
    #[test]
    fn daemon_message_build_clock_failure_returns_error() {
        let builder = InnerMessageBuilder::new(
            SessionId::new([1_u8; 16]),
            peer_id("offer-home"),
            peer_id("answer-office"),
        );

        let result = builder.build_with_clock(
            MessageBody::Offer(OfferBody { sdp: "v=0".to_owned() }),
            failing_clock,
        );

        assert!(
            matches!(result, Err(SignalingError::Clock(_))),
            "expected a typed clock error, got {result:?}"
        );
    }

    #[test]
    fn decode_rejects_unknown_message_type() {
        let mut raw = sample_inner_message().encode().expect("encode");
        let mut decoded: RawInnerMessage = serde_cbor::from_slice(&raw).expect("decode raw");
        decoded.t = 99;
        raw = serde_cbor::to_vec(&decoded).expect("encode raw");

        let error = InnerMessage::decode(&raw).expect_err("unknown type should fail");
        assert!(
            matches!(error, ProtocolError::InvalidMessage(message) if message.contains("unknown message type"))
        );
    }

    #[test]
    fn decode_rejects_truncated_payload() {
        let mut encoded = sample_inner_message().encode().expect("encode");
        encoded.pop();

        let error = InnerMessage::decode(&encoded).expect_err("truncated cbor should fail");
        assert!(matches!(error, ProtocolError::InvalidMessage(_)));
    }

    #[test]
    fn decode_rejects_invalid_offer_body_shape() {
        let raw = RawInnerMessage {
            v: 1,
            t: MessageType::Offer as u8,
            sid: [5_u8; 16],
            sp: "offer-home".to_owned(),
            rp: "answer-office".to_owned(),
            ts: 123,
            body: Value::Map(BTreeMap::from([(
                Value::Text("unexpected".to_owned()),
                Value::Text("field".to_owned()),
            )])),
        };
        let encoded = serde_cbor::to_vec(&raw).expect("encode raw");

        let error = InnerMessage::decode(&encoded).expect_err("unknown offer field should fail");
        assert!(
            matches!(error, ProtocolError::InvalidMessage(message) if message.contains("unknown field"))
        );
    }

    #[test]
    fn inner_message_roundtrip_preserves_core_fields() {
        let original = sample_inner_message();
        let encoded = original.encode().expect("encode");
        let decoded = InnerMessage::decode(&encoded).expect("decode");

        assert_eq!(decoded.version, original.version);
        assert_eq!(decoded.message_type, original.message_type);
        assert_eq!(decoded.session_id, original.session_id);
        assert_eq!(decoded.sender_peer_id, original.sender_peer_id);
        assert_eq!(decoded.recipient_peer_id, original.recipient_peer_id);
        assert_eq!(decoded.timestamp_ms, original.timestamp_ms);
        assert_eq!(decoded.body, original.body);
    }

    #[test]
    fn decode_rejects_known_type_with_non_map_body() {
        let raw = RawInnerMessage {
            v: 1,
            t: MessageType::Offer as u8,
            sid: [7_u8; 16],
            sp: "offer-home".to_owned(),
            rp: "answer-office".to_owned(),
            ts: 123,
            body: Value::Integer(9),
        };
        let encoded = serde_cbor::to_vec(&raw).expect("encode raw");

        let error = InnerMessage::decode(&encoded).expect_err("non-map body should fail");
        assert!(matches!(error, ProtocolError::InvalidMessage(_)));
    }
}
