use p2p_core::{Kid, MsgId, PROTOCOL_MAGIC, PROTOCOL_SUITE, PROTOCOL_VERSION, ProtocolError};

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct EnvelopeFlags {
    pub ack_required: bool,
    pub response: bool,
}

impl EnvelopeFlags {
    pub fn to_byte(self) -> u8 {
        let mut value = 0_u8;
        if self.ack_required {
            value |= 1 << 0;
        }
        if self.response {
            value |= 1 << 1;
        }
        value
    }

    pub fn from_byte(byte: u8) -> Result<Self, ProtocolError> {
        if byte & !0b11 != 0 {
            return Err(ProtocolError::InvalidEnvelope(
                "reserved flag bits must be zero".to_owned(),
            ));
        }

        Ok(Self { ack_required: byte & 1 != 0, response: byte & 0b10 != 0 })
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct OuterEnvelope {
    pub flags: EnvelopeFlags,
    pub sender_kid: Kid,
    pub recipient_kid: Kid,
    pub msg_id: MsgId,
    pub eph_x25519_pub: [u8; 32],
    pub aead_nonce: [u8; 24],
    pub ciphertext: Vec<u8>,
    pub signature: [u8; 64],
}

impl OuterEnvelope {
    pub fn encode(&self) -> Result<Vec<u8>, ProtocolError> {
        let ciphertext_len = u32::try_from(self.ciphertext.len()).map_err(|_| {
            ProtocolError::InvalidEnvelope("ciphertext exceeds u32 length".to_owned())
        })?;
        let mut bytes = Vec::with_capacity(147 + self.ciphertext.len() + 64);
        bytes.extend_from_slice(&PROTOCOL_MAGIC);
        bytes.push(PROTOCOL_VERSION);
        bytes.push(PROTOCOL_SUITE);
        bytes.push(self.flags.to_byte());
        bytes.extend_from_slice(self.sender_kid.as_bytes());
        bytes.extend_from_slice(self.recipient_kid.as_bytes());
        bytes.extend_from_slice(self.msg_id.as_bytes());
        bytes.extend_from_slice(&self.eph_x25519_pub);
        bytes.extend_from_slice(&self.aead_nonce);
        bytes.extend_from_slice(&ciphertext_len.to_be_bytes());
        bytes.extend_from_slice(&self.ciphertext);
        bytes.extend_from_slice(&self.signature);
        Ok(bytes)
    }

    pub fn decode(bytes: &[u8]) -> Result<Self, ProtocolError> {
        const HEADER_LEN: usize = 147;
        const SIGNATURE_LEN: usize = 64;
        if bytes.len() < HEADER_LEN + SIGNATURE_LEN {
            return Err(ProtocolError::InvalidEnvelope(
                "envelope is shorter than the minimum size".to_owned(),
            ));
        }
        if bytes[0..4] != PROTOCOL_MAGIC {
            return Err(ProtocolError::InvalidEnvelope("invalid magic".to_owned()));
        }
        if bytes[4] != PROTOCOL_VERSION {
            return Err(ProtocolError::InvalidEnvelope("unsupported version".to_owned()));
        }
        if bytes[5] != PROTOCOL_SUITE {
            return Err(ProtocolError::InvalidEnvelope("unsupported suite".to_owned()));
        }

        let flags = EnvelopeFlags::from_byte(bytes[6])?;
        let sender_kid = Kid::new(bytes[7..39].try_into().map_err(|_| {
            ProtocolError::InvalidEnvelope("sender_kid must be 32 bytes".to_owned())
        })?);
        let recipient_kid = Kid::new(bytes[39..71].try_into().map_err(|_| {
            ProtocolError::InvalidEnvelope("recipient_kid must be 32 bytes".to_owned())
        })?);
        let msg_id =
            MsgId::new(bytes[71..87].try_into().map_err(|_| {
                ProtocolError::InvalidEnvelope("msg_id must be 16 bytes".to_owned())
            })?);
        let eph_x25519_pub = bytes[87..119].try_into().map_err(|_| {
            ProtocolError::InvalidEnvelope("ephemeral public key must be 32 bytes".to_owned())
        })?;
        let aead_nonce = bytes[119..143]
            .try_into()
            .map_err(|_| ProtocolError::InvalidEnvelope("nonce must be 24 bytes".to_owned()))?;
        let ciphertext_len = u32::from_be_bytes(bytes[143..147].try_into().map_err(|_| {
            ProtocolError::InvalidEnvelope("ciphertext length field must be 4 bytes".to_owned())
        })?) as usize;
        let expected_len = HEADER_LEN + ciphertext_len + SIGNATURE_LEN;
        if bytes.len() != expected_len {
            return Err(ProtocolError::InvalidEnvelope(format!(
                "envelope length mismatch: expected {expected_len}, got {}",
                bytes.len()
            )));
        }
        let ciphertext = bytes[147..147 + ciphertext_len].to_vec();
        let signature = bytes[147 + ciphertext_len..]
            .try_into()
            .map_err(|_| ProtocolError::InvalidEnvelope("signature must be 64 bytes".to_owned()))?;

        Ok(Self {
            flags,
            sender_kid,
            recipient_kid,
            msg_id,
            eph_x25519_pub,
            aead_nonce,
            ciphertext,
            signature,
        })
    }

    pub fn aad_bytes(&self) -> Result<Vec<u8>, ProtocolError> {
        let ciphertext_len = u32::try_from(self.ciphertext.len()).map_err(|_| {
            ProtocolError::InvalidEnvelope("ciphertext exceeds u32 length".to_owned())
        })?;
        let mut bytes = Vec::with_capacity(147);
        bytes.extend_from_slice(&PROTOCOL_MAGIC);
        bytes.push(PROTOCOL_VERSION);
        bytes.push(PROTOCOL_SUITE);
        bytes.push(self.flags.to_byte());
        bytes.extend_from_slice(self.sender_kid.as_bytes());
        bytes.extend_from_slice(self.recipient_kid.as_bytes());
        bytes.extend_from_slice(self.msg_id.as_bytes());
        bytes.extend_from_slice(&self.eph_x25519_pub);
        bytes.extend_from_slice(&self.aead_nonce);
        bytes.extend_from_slice(&ciphertext_len.to_be_bytes());
        Ok(bytes)
    }

    pub fn signed_bytes(&self) -> Result<Vec<u8>, ProtocolError> {
        let mut bytes = self.aad_bytes()?;
        bytes.extend_from_slice(&self.ciphertext);
        Ok(bytes)
    }
}
