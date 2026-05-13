use std::net::SocketAddr;

use p2p_core::ForwardOfferConfig;
use tokio::net::{TcpListener, TcpStream};

use crate::TunnelError;

pub struct OfferListener {
    forward_id: String,
    listener: TcpListener,
}

impl OfferListener {
    pub async fn bind(
        forward_id: impl Into<String>,
        config: &ForwardOfferConfig,
    ) -> Result<Self, TunnelError> {
        let listener = TcpListener::bind((config.listen_host.as_str(), config.listen_port)).await?;
        Ok(Self { forward_id: forward_id.into(), listener })
    }

    pub fn forward_id(&self) -> &str {
        &self.forward_id
    }

    pub fn local_addr(&self) -> Result<SocketAddr, TunnelError> {
        Ok(self.listener.local_addr()?)
    }

    pub async fn accept_client(&self) -> Result<OfferClient, TunnelError> {
        let (stream, address) = self.listener.accept().await?;
        tracing::debug!(
            forward_id = %self.forward_id,
            client_addr = %address,
            "accepted local forward client"
        );
        Ok(OfferClient { forward_id: self.forward_id.clone(), stream: Some(stream) })
    }
}

pub struct OfferClient {
    forward_id: String,
    stream: Option<TcpStream>,
}

impl OfferClient {
    pub fn forward_id(&self) -> &str {
        &self.forward_id
    }

    pub fn take_stream(&mut self) -> Result<TcpStream, TunnelError> {
        self.stream.take().ok_or_else(|| {
            TunnelError::InvalidFrame("offer client stream already taken".to_owned())
        })
    }
}

#[cfg(test)]
mod tests {
    use p2p_core::ForwardOfferConfig;
    use tokio::net::TcpStream;

    use super::OfferListener;

    fn offer_config() -> ForwardOfferConfig {
        ForwardOfferConfig { listen_host: "127.0.0.1".to_owned(), listen_port: 0 }
    }

    #[tokio::test]
    async fn listener_accepts_clients_with_forward_id() {
        let listener = OfferListener::bind("ssh", &offer_config()).await.expect("listener");
        let addr = listener.local_addr().expect("local addr");
        let _client_side = TcpStream::connect(addr).await.expect("connect");
        let accepted = listener.accept_client().await.expect("accept");
        assert_eq!(accepted.forward_id(), "ssh");
    }

    #[tokio::test]
    async fn listener_accepts_multiple_clients_without_busy_rejection() {
        let listener = OfferListener::bind("web-ui", &offer_config()).await.expect("listener");
        let addr = listener.local_addr().expect("local addr");

        let _first_client = TcpStream::connect(addr).await.expect("first connect");
        let first = listener.accept_client().await.expect("first accept");
        let _second_client = TcpStream::connect(addr).await.expect("second connect");
        let second = listener.accept_client().await.expect("second accept");

        assert_eq!(first.forward_id(), "web-ui");
        assert_eq!(second.forward_id(), "web-ui");
    }
}
