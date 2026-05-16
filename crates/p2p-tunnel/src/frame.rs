use bytes::{BufMut, BytesMut};
use p2p_core::{FRAME_VERSION, FailureCode, TunnelFrameType};
use serde::{Deserialize, Serialize};

use crate::TunnelError;

const HEADER_LEN: usize = 10;
const MAX_PAYLOAD_LEN: usize = 1024 * 1024;

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct OpenPayload {
    pub forward_id: String,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ErrorPayload {
    pub code: String,
    pub message: String,
}

impl ErrorPayload {
    pub fn from_failure(code: FailureCode, message: impl Into<String>) -> Self {
        Self { code: code.as_str().to_owned(), message: message.into() }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct TunnelFrame {
    pub version: u8,
    pub frame_type: TunnelFrameType,
    pub stream_id: u32,
    pub payload: Vec<u8>,
}

impl TunnelFrame {
    pub fn new(frame_type: TunnelFrameType, stream_id: u32, payload: Vec<u8>) -> Self {
        Self { version: FRAME_VERSION, frame_type, stream_id, payload }
    }

    pub fn open(stream_id: u32, payload: OpenPayload) -> Result<Self, TunnelError> {
        if payload.forward_id.is_empty() {
            return Err(TunnelError::InvalidFrame("OPEN forward_id must not be empty".to_owned()));
        }
        Ok(Self::new(TunnelFrameType::Open, stream_id, encode_json_payload(&payload)?))
    }

    pub fn open_ack(stream_id: u32) -> Self {
        Self::new(TunnelFrameType::Open, stream_id, Vec::new())
    }

    pub fn data(stream_id: u32, payload: Vec<u8>) -> Self {
        Self::new(TunnelFrameType::Data, stream_id, payload)
    }

    pub fn close(stream_id: u32) -> Self {
        Self::new(TunnelFrameType::Close, stream_id, Vec::new())
    }

    pub fn ping(payload: Vec<u8>) -> Self {
        Self::new(TunnelFrameType::Ping, 0, payload)
    }

    pub fn pong(payload: Vec<u8>) -> Self {
        Self::new(TunnelFrameType::Pong, 0, payload)
    }

    pub fn error(stream_id: u32, payload: ErrorPayload) -> Result<Self, TunnelError> {
        Ok(Self::new(TunnelFrameType::Error, stream_id, encode_json_payload(&payload)?))
    }

    pub fn open_payload(&self) -> Result<OpenPayload, TunnelError> {
        decode_json_payload(&self.payload, "OPEN")
    }

    pub fn error_payload(&self) -> Result<ErrorPayload, TunnelError> {
        decode_json_payload(&self.payload, "ERROR")
    }
}

#[derive(Debug, Default)]
pub struct TunnelFrameCodec;

impl TunnelFrameCodec {
    pub fn encode(frame: &TunnelFrame) -> Result<Vec<u8>, TunnelError> {
        validate_frame(frame)?;
        let payload_len = u32::try_from(frame.payload.len())
            .map_err(|_| TunnelError::InvalidFrame("payload exceeds u32 length".to_owned()))?;

        let mut buffer = BytesMut::with_capacity(HEADER_LEN + frame.payload.len());
        buffer.put_u8(frame.version);
        buffer.put_u8(frame.frame_type as u8);
        buffer.put_u32(frame.stream_id);
        buffer.put_u32(payload_len);
        buffer.extend_from_slice(&frame.payload);
        Ok(buffer.to_vec())
    }

    pub fn decode(encoded: &[u8]) -> Result<TunnelFrame, TunnelError> {
        if encoded.len() < HEADER_LEN {
            return Err(TunnelError::TruncatedFrame);
        }

        let version = encoded[0];
        if version != FRAME_VERSION {
            return Err(TunnelError::unsupported_version(version));
        }

        let frame_type = tunnel_frame_type_from_u8(encoded[1])
            .ok_or(TunnelError::UnknownFrameType(encoded[1]))?;
        let stream_id = u32::from_be_bytes([encoded[2], encoded[3], encoded[4], encoded[5]]);
        let payload_len =
            u32::from_be_bytes([encoded[6], encoded[7], encoded[8], encoded[9]]) as usize;
        if payload_len > MAX_PAYLOAD_LEN {
            return Err(TunnelError::InvalidFrame(format!(
                "payload length {payload_len} exceeds maximum {MAX_PAYLOAD_LEN}"
            )));
        }
        if encoded.len() != HEADER_LEN + payload_len {
            return Err(TunnelError::InvalidFrame(format!(
                "payload length mismatch: header says {payload_len}, frame has {} payload bytes",
                encoded.len().saturating_sub(HEADER_LEN)
            )));
        }

        let frame =
            TunnelFrame { version, frame_type, stream_id, payload: encoded[HEADER_LEN..].to_vec() };
        validate_frame(&frame)?;
        Ok(frame)
    }
}

fn validate_frame(frame: &TunnelFrame) -> Result<(), TunnelError> {
    if frame.version != FRAME_VERSION {
        return Err(TunnelError::unsupported_version(frame.version));
    }
    if frame.payload.len() > MAX_PAYLOAD_LEN {
        return Err(TunnelError::InvalidFrame(format!(
            "payload length {} exceeds maximum {MAX_PAYLOAD_LEN}",
            frame.payload.len()
        )));
    }

    match frame.frame_type {
        TunnelFrameType::Open
        | TunnelFrameType::Data
        | TunnelFrameType::Close
        | TunnelFrameType::Error => {
            if frame.stream_id == 0 {
                return Err(TunnelError::ReservedStreamId);
            }
        }
        TunnelFrameType::Ping | TunnelFrameType::Pong => {
            if frame.stream_id != 0 {
                return Err(TunnelError::SessionControlStreamId(frame.stream_id));
            }
        }
    }

    if frame.frame_type == TunnelFrameType::Error {
        let payload = frame.error_payload()?;
        if payload.code.is_empty() {
            return Err(TunnelError::InvalidFrame("ERROR code must not be empty".to_owned()));
        }
    }

    Ok(())
}

fn encode_json_payload<T: Serialize>(payload: &T) -> Result<Vec<u8>, TunnelError> {
    serde_json::to_vec(payload)
        .map_err(|error| TunnelError::InvalidFrame(format!("payload JSON encode failed: {error}")))
}

fn decode_json_payload<T: for<'de> Deserialize<'de>>(
    payload: &[u8],
    frame_name: &'static str,
) -> Result<T, TunnelError> {
    serde_json::from_slice(payload).map_err(|error| {
        TunnelError::InvalidFrame(format!("{frame_name} payload JSON decode failed: {error}"))
    })
}

fn tunnel_frame_type_from_u8(value: u8) -> Option<TunnelFrameType> {
    match value {
        0 => Some(TunnelFrameType::Open),
        1 => Some(TunnelFrameType::Data),
        2 => Some(TunnelFrameType::Close),
        3 => Some(TunnelFrameType::Error),
        4 => Some(TunnelFrameType::Ping),
        5 => Some(TunnelFrameType::Pong),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use p2p_core::{FRAME_VERSION, TunnelFrameType};

    use super::{ErrorPayload, OpenPayload, TunnelFrame, TunnelFrameCodec};
    use crate::TunnelError;

    #[test]
    fn frame_round_trip() {
        let frame = TunnelFrame::data(2, vec![1, 2, 3, 4]);
        let encoded = TunnelFrameCodec::encode(&frame).expect("frame should encode");
        let decoded = TunnelFrameCodec::decode(&encoded).expect("frame should decode");
        assert_eq!(decoded, frame);
    }

    #[test]
    fn reject_invalid_frame_lengths() {
        let mut encoded =
            TunnelFrameCodec::encode(&TunnelFrame::data(1, vec![1, 2, 3])).expect("encode");
        encoded.truncate(encoded.len() - 1);
        assert!(matches!(TunnelFrameCodec::decode(&encoded), Err(TunnelError::InvalidFrame(_))));
    }

    #[test]
    fn reject_reserved_stream_id_for_stream_frames() {
        let frame = TunnelFrame::data(0, vec![9]);
        assert!(matches!(TunnelFrameCodec::encode(&frame), Err(TunnelError::ReservedStreamId)));
    }

    #[test]
    fn reject_unsupported_versions() {
        let mut encoded = TunnelFrameCodec::encode(&TunnelFrame::data(1, vec![9])).expect("encode");
        encoded[0] = FRAME_VERSION + 1;
        assert!(matches!(
            TunnelFrameCodec::decode(&encoded),
            Err(TunnelError::UnsupportedVersion { .. })
        ));
    }

    #[test]
    fn reject_unknown_frame_types() {
        let mut encoded = TunnelFrameCodec::encode(&TunnelFrame::data(1, vec![9])).expect("encode");
        encoded[1] = 99;
        assert!(matches!(
            TunnelFrameCodec::decode(&encoded),
            Err(TunnelError::UnknownFrameType(99))
        ));
    }

    #[test]
    fn preserve_open_frame_structure() {
        let frame =
            TunnelFrame::open(2, OpenPayload { forward_id: "ssh".to_owned() }).expect("open frame");
        let encoded = TunnelFrameCodec::encode(&frame).expect("frame should encode");
        assert_eq!(encoded[0], FRAME_VERSION);
        assert_eq!(encoded[1], TunnelFrameType::Open as u8);
        assert_eq!(u32::from_be_bytes([encoded[2], encoded[3], encoded[4], encoded[5]]), 2);
        let decoded = TunnelFrameCodec::decode(&encoded).expect("decode");
        assert_eq!(decoded.open_payload().expect("open payload").forward_id, "ssh");
    }

    #[test]
    fn open_ack_uses_empty_payload() {
        let frame = TunnelFrame::open_ack(1);
        let encoded = TunnelFrameCodec::encode(&frame).expect("encode");
        let decoded = TunnelFrameCodec::decode(&encoded).expect("decode");
        assert!(decoded.payload.is_empty());
    }

    #[test]
    fn multiplex_spec_documents_only_empty_open_ack() {
        let spec_path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../docs/MULTIPLEXED_FORWARDING_SPEC.md");
        let spec = std::fs::read_to_string(spec_path).expect("multiplex spec should be readable");
        assert!(spec.contains("answer sends exactly `OPEN(stream_id)` with an empty payload"));
        assert!(spec.contains(
            "Malformed answer-side `OPEN` request payloads are stream-local protocol errors"
        ));
        assert!(spec.contains("The v0.2 multiplexed session is persistent"));
        assert!(spec.contains("Zero active streams alone must not close the session"));
        assert!(!spec.contains("OPEN(stream_id, { \"ok\": true })"));
        assert!(!spec.contains("OPEN(stream_id, empty_payload)` or"));
        assert!(!spec.contains("one WebRTC session per TCP stream"));
        assert!(!spec.contains("close when no streams"));
    }

    #[test]
    fn malformed_open_rejected() {
        assert!(matches!(
            TunnelFrame::open(1, OpenPayload { forward_id: String::new() }),
            Err(TunnelError::InvalidFrame(_))
        ));
    }

    #[test]
    fn codec_leaves_open_payload_validation_to_role_handlers() {
        let frame = TunnelFrame::new(TunnelFrameType::Open, 1, b"{".to_vec());
        let encoded = TunnelFrameCodec::encode(&frame).expect("frame-level encoding should pass");
        let decoded = TunnelFrameCodec::decode(&encoded).expect("frame-level decode should pass");
        assert!(matches!(decoded.open_payload(), Err(TunnelError::InvalidFrame(_))));
    }

    #[test]
    fn error_payload_round_trips() {
        let frame = TunnelFrame::error(
            3,
            ErrorPayload { code: "unknown_forward".to_owned(), message: "missing".to_owned() },
        )
        .expect("error frame");
        let encoded = TunnelFrameCodec::encode(&frame).expect("encode");
        let decoded = TunnelFrameCodec::decode(&encoded).expect("decode");
        assert_eq!(decoded.error_payload().expect("error payload").code, "unknown_forward");
    }
}
