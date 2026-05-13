use p2p_core::ForwardAnswerConfig;
use tokio::net::TcpStream;

use crate::TunnelError;

#[derive(Clone, Debug)]
pub struct AnswerTargetConnector {
    config: ForwardAnswerConfig,
}

impl AnswerTargetConnector {
    pub fn new(config: &ForwardAnswerConfig) -> Self {
        Self { config: config.clone() }
    }

    pub async fn connect_target(&self) -> Result<TcpStream, TunnelError> {
        TcpStream::connect((self.config.target_host.as_str(), self.config.target_port))
            .await
            .map_err(|error| TunnelError::TargetConnectFailed(error.to_string()))
    }
}
