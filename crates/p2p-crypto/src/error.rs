use thiserror::Error;

#[derive(Debug, Error)]
pub enum CryptoError {
    #[error("invalid identity file: {0}")]
    InvalidIdentity(String),
    #[error("invalid public identity: {0}")]
    InvalidPublicIdentity(String),
    #[error("invalid authorized key: {0}")]
    InvalidAuthorizedKey(String),
    #[error("permission error: {0}")]
    Permission(String),
    #[error("base64 decode error: {0}")]
    Base64(#[from] base64::DecodeError),
    #[error("toml decode error: {0}")]
    Toml(#[from] toml::de::Error),
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
    #[error("signature error")]
    Signature,
    #[error("encryption error")]
    Encryption,
    #[error("decryption error")]
    Decryption,
    #[error("kdf error: {0}")]
    Kdf(String),
}
