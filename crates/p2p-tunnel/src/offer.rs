use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use p2p_core::TunnelOfferConfig;
use tokio::net::{TcpListener, TcpStream};

use crate::TunnelError;

pub struct OfferListener {
    listener: TcpListener,
    active_client: Arc<AtomicBool>,
}

impl OfferListener {
    pub async fn bind(config: &TunnelOfferConfig) -> Result<Self, TunnelError> {
        let listener = TcpListener::bind((config.listen_host.as_str(), config.listen_port)).await?;
        Ok(Self { listener, active_client: Arc::new(AtomicBool::new(false)) })
    }

    pub fn local_addr(&self) -> Result<SocketAddr, TunnelError> {
        Ok(self.listener.local_addr()?)
    }

    pub fn is_busy(&self) -> bool {
        self.active_client.load(Ordering::SeqCst)
    }

    pub async fn accept_client(&self) -> Result<OfferClient, TunnelError> {
        loop {
            let (stream, address) = self.listener.accept().await?;
            if self
                .active_client
                .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
                .is_ok()
            {
                return Ok(OfferClient {
                    stream: Some(stream),
                    active_client: Arc::clone(&self.active_client),
                });
            }

            tracing::warn!("rejecting extra client from {address} because tunnel is busy");
            drop(stream);
        }
    }
}

pub struct OfferClient {
    stream: Option<TcpStream>,
    active_client: Arc<AtomicBool>,
}

impl OfferClient {
    pub fn take_stream(&mut self) -> Result<TcpStream, TunnelError> {
        self.stream.take().ok_or_else(|| {
            TunnelError::InvalidFrame("offer client stream already taken".to_owned())
        })
    }
}

impl Drop for OfferClient {
    fn drop(&mut self) {
        self.active_client.store(false, Ordering::SeqCst);
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;
    use std::time::Duration;

    use p2p_core::TunnelOfferConfig;
    use tokio::net::TcpStream;
    use tokio::time::timeout;

    use super::OfferListener;

    fn offer_config() -> TunnelOfferConfig {
        TunnelOfferConfig {
            listen_host: "127.0.0.1".to_owned(),
            listen_port: 0,
            remote_peer_id: "answer-office".parse().expect("peer id"),
        }
    }

    #[tokio::test]
    async fn active_client_flag_tracks_full_offer_client_lifetime() {
        let listener = OfferListener::bind(&offer_config()).await.expect("listener");
        let addr = listener.local_addr().expect("local addr");
        let _client_side = TcpStream::connect(addr).await.expect("connect");
        let mut accepted = listener.accept_client().await.expect("accept");
        assert!(listener.is_busy());

        let _stream = accepted.take_stream().expect("take stream");
        assert!(listener.is_busy());

        drop(accepted);
        assert!(!listener.is_busy());
    }

    #[tokio::test]
    async fn busy_listener_rejects_extra_clients_until_session_releases() {
        let listener = Arc::new(OfferListener::bind(&offer_config()).await.expect("listener"));
        let addr = listener.local_addr().expect("local addr");

        let _first_client = TcpStream::connect(addr).await.expect("first connect");
        let first_session = listener.accept_client().await.expect("first accept");
        assert!(listener.is_busy());

        let listener_for_task = Arc::clone(&listener);
        let mut pending_accept =
            tokio::spawn(async move { listener_for_task.accept_client().await });

        let _second_client = TcpStream::connect(addr).await.expect("second connect");
        assert!(timeout(Duration::from_millis(100), &mut pending_accept).await.is_err());

        drop(first_session);
        let _third_client = TcpStream::connect(addr).await.expect("third connect");
        let next_session = timeout(Duration::from_secs(1), pending_accept)
            .await
            .expect("listener should accept after release")
            .expect("accept task join")
            .expect("accept after release");

        assert!(listener.is_busy());
        drop(next_session);
        assert!(!listener.is_busy());
    }
}
