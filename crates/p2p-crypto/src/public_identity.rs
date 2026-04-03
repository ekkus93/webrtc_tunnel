use base64::{Engine as _, engine::general_purpose::STANDARD};
use ed25519_dalek::VerifyingKey;
use p2p_core::PeerId;
use x25519_dalek::PublicKey as X25519PublicKey;

use crate::error::CryptoError;
use crate::util::{strip_quotes, tokenize_line};

#[derive(Clone, Debug)]
pub struct PublicIdentity {
    pub peer_id: PeerId,
    pub sign_public: VerifyingKey,
    pub kex_public: X25519PublicKey,
    pub comment: Option<String>,
}

impl PublicIdentity {
    pub fn parse(line: &str) -> Result<Self, CryptoError> {
        let tokens = tokenize_line(line)?;
        if tokens.len() < 4 {
            return Err(CryptoError::InvalidPublicIdentity(
                "expected format marker and required key fields".to_owned(),
            ));
        }

        if tokens[0] != "p2ptunnel-ed25519" {
            return Err(CryptoError::InvalidPublicIdentity(
                "unsupported public identity format".to_owned(),
            ));
        }

        let mut peer_id = None;
        let mut sign_public = None;
        let mut kex_public = None;
        let mut comment = None;

        for token in &tokens[1..] {
            let (key, value) = token.split_once('=').ok_or_else(|| {
                CryptoError::InvalidPublicIdentity(format!("invalid token '{token}'"))
            })?;
            let value = strip_quotes(value);
            match key {
                "peer_id" => peer_id = Some(value.parse().map_err(CryptoError::from_protocol)?),
                "sign_pub" => {
                    let decoded = STANDARD.decode(value)?;
                    let key_bytes: [u8; 32] = decoded.try_into().map_err(|_| {
                        CryptoError::InvalidPublicIdentity(
                            "sign_pub must decode to 32 bytes".to_owned(),
                        )
                    })?;
                    sign_public = Some(
                        VerifyingKey::from_bytes(&key_bytes).map_err(|_| CryptoError::Signature)?,
                    );
                }
                "kex_pub" => {
                    let decoded = STANDARD.decode(value)?;
                    let key_bytes: [u8; 32] = decoded.try_into().map_err(|_| {
                        CryptoError::InvalidPublicIdentity(
                            "kex_pub must decode to 32 bytes".to_owned(),
                        )
                    })?;
                    kex_public = Some(X25519PublicKey::from(key_bytes));
                }
                "comment" => comment = Some(value),
                _ => {
                    return Err(CryptoError::InvalidPublicIdentity(format!(
                        "unknown token key '{key}'"
                    )));
                }
            }
        }

        Ok(Self {
            peer_id: peer_id.ok_or_else(|| {
                CryptoError::InvalidPublicIdentity("peer_id is required".to_owned())
            })?,
            sign_public: sign_public.ok_or_else(|| {
                CryptoError::InvalidPublicIdentity("sign_pub is required".to_owned())
            })?,
            kex_public: kex_public.ok_or_else(|| {
                CryptoError::InvalidPublicIdentity("kex_pub is required".to_owned())
            })?,
            comment,
        })
    }

    pub fn render(&self) -> String {
        let sign_pub = STANDARD.encode(self.sign_public.to_bytes());
        let kex_pub = STANDARD.encode(self.kex_public.as_bytes());
        match &self.comment {
            Some(comment) => format!(
                "p2ptunnel-ed25519 peer_id={} sign_pub={} kex_pub={} comment=\"{}\"",
                self.peer_id, sign_pub, kex_pub, comment
            ),
            None => format!(
                "p2ptunnel-ed25519 peer_id={} sign_pub={} kex_pub={}",
                self.peer_id, sign_pub, kex_pub
            ),
        }
    }
}

trait ProtocolInto<T> {
    fn from_protocol(error: p2p_core::ProtocolError) -> T;
}

impl ProtocolInto<CryptoError> for CryptoError {
    fn from_protocol(error: p2p_core::ProtocolError) -> CryptoError {
        CryptoError::InvalidPublicIdentity(error.to_string())
    }
}
