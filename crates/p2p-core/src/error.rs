use thiserror::Error;

#[derive(Debug, Error)]
pub enum ConfigError {
    #[error("missing required field: {0}")]
    MissingField(&'static str),
    #[error("invalid config: {0}")]
    InvalidConfig(String),
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
    #[error("toml error: {0}")]
    Toml(#[from] toml::de::Error),
}

#[derive(Debug, Error)]
pub enum ProtocolError {
    #[error("invalid peer_id: {0}")]
    InvalidPeerId(String),
    #[error("invalid message: {0}")]
    InvalidMessage(String),
    #[error("invalid envelope: {0}")]
    InvalidEnvelope(String),
    #[error("protocol violation: {0}")]
    Violation(String),
}

#[derive(Debug, Error)]
pub enum AppError {
    #[error(transparent)]
    Config(#[from] ConfigError),
    #[error(transparent)]
    Protocol(#[from] ProtocolError),
    #[error("crypto error: {0}")]
    Crypto(String),
    #[error("runtime error: {0}")]
    Runtime(String),
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
}
