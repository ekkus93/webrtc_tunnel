use thiserror::Error;

#[derive(Debug, Error)]
pub enum SignalingError {
    #[error("protocol error: {0}")]
    Protocol(String),
    #[error("crypto error: {0}")]
    Crypto(#[from] p2p_crypto::CryptoError),
    #[error("core protocol error: {0}")]
    CoreProtocol(#[from] p2p_core::ProtocolError),
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
    #[error("mqtt client error: {0}")]
    Client(Box<rumqttc::ClientError>),
    #[error("mqtt connection error: {0}")]
    Connection(Box<rumqttc::ConnectionError>),
    #[error("mqtt option error: {0}")]
    Options(Box<rumqttc::OptionError>),
    #[error("cbor error: {0}")]
    Cbor(#[from] serde_cbor::Error),
}

impl From<rumqttc::ClientError> for SignalingError {
    fn from(error: rumqttc::ClientError) -> Self {
        Self::Client(Box::new(error))
    }
}

impl From<rumqttc::ConnectionError> for SignalingError {
    fn from(error: rumqttc::ConnectionError) -> Self {
        Self::Connection(Box::new(error))
    }
}

impl From<rumqttc::OptionError> for SignalingError {
    fn from(error: rumqttc::OptionError) -> Self {
        Self::Options(Box::new(error))
    }
}
