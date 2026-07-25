use std::net::SocketAddr;
use std::time::Duration;

use p2p_core::ForwardOfferConfig;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::time::timeout;

use crate::TunnelError;

/// Bound on how long [`close_tcp_stream_gracefully`] waits for the peer to stop sending
/// before giving up and closing anyway. Long enough to catch an already-sent, bounded
/// request sitting in the kernel receive buffer; short enough that abandoning a
/// connection (shutdown, cooldown, a session that never reached the bridge, or a
/// multiplex stream still awaiting its OPEN-ack when the whole session tears down)
/// still completes promptly.
const GRACEFUL_CLOSE_DRAIN_TIMEOUT: Duration = Duration::from_millis(50);

/// Closes a local TCP connection that is being abandoned before its data was ever
/// fully relayed, instead of letting a bare drop discard it. An ordinary drop here
/// leaves any bytes the peer already wrote sitting unread in the kernel receive
/// buffer; Linux responds to that by sending a TCP RST instead of a clean FIN on
/// close, which surfaces to the peer as a confusing `ConnectionReset` even though
/// nothing on the wire was actually corrupted — the tunnel genuinely just never came
/// up (or stopped accepting) in time. Draining whatever is already buffered, bounded
/// by a short grace period since a well-behaved peer may still be mid-write or may
/// send nothing further, avoids that in the common case. Shared by [`OfferClient`]
/// (a client never handed to the bridge) and the multiplex offer/answer loops (a
/// stream still opening when the whole session tears down).
pub(crate) async fn close_tcp_stream_gracefully(mut stream: TcpStream) {
    let mut discard = [0_u8; 1024];
    loop {
        match timeout(GRACEFUL_CLOSE_DRAIN_TIMEOUT, stream.read(&mut discard)).await {
            Ok(Ok(read)) if read > 0 => continue,
            _ => break,
        }
    }
    let _ = stream.shutdown().await;
}

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
        Ok(OfferClient::new(self.forward_id.clone(), stream))
    }
}

pub struct OfferClient {
    forward_id: String,
    stream: Option<TcpStream>,
}

impl OfferClient {
    pub fn new(forward_id: impl Into<String>, stream: TcpStream) -> Self {
        Self { forward_id: forward_id.into(), stream: Some(stream) }
    }

    pub fn forward_id(&self) -> &str {
        &self.forward_id
    }

    pub fn take_stream(&mut self) -> Result<TcpStream, TunnelError> {
        self.stream.take().ok_or_else(|| {
            TunnelError::InvalidFrame("offer client stream already taken".to_owned())
        })
    }

    /// Closes a client connection that is being abandoned before it was ever handed to
    /// the multiplex bridge (the tunnel failed to establish, the daemon is shutting
    /// down, or a probe-failure cooldown is rejecting new clients). See
    /// [`close_tcp_stream_gracefully`] for why this matters more than a bare drop.
    pub async fn close_gracefully(mut self) {
        let Some(stream) = self.stream.take() else { return };
        close_tcp_stream_gracefully(stream).await;
    }
}

#[cfg(test)]
mod tests {
    use p2p_core::ForwardOfferConfig;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
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

    /// Reproduces the real bug this fix closes: a client that already wrote request
    /// bytes the server never read must see a clean close (EOF) when the server
    /// abandons the connection, not a `ConnectionReset` — the whole point of
    /// `close_gracefully` is draining that unread data first so the kernel sends a
    /// normal FIN instead of an RST on close.
    #[tokio::test]
    async fn close_gracefully_leaves_client_with_clean_eof_not_connection_reset() {
        let listener = OfferListener::bind("ssh", &offer_config()).await.expect("listener");
        let addr = listener.local_addr().expect("local addr");

        let mut client = TcpStream::connect(addr).await.expect("connect");
        let accepted = listener.accept_client().await.expect("accept");

        // Client sends its request and does NOT read a response — exactly the
        // real-world shape (offer session tears down before the bridge ever starts
        // reading from this client).
        client.write_all(b"h001").await.expect("client write");

        // Give the bytes a moment to actually land in the server-side kernel receive
        // buffer before the server abandons the connection, matching the real race.
        tokio::time::sleep(std::time::Duration::from_millis(10)).await;

        accepted.close_gracefully().await;

        let mut buf = [0_u8; 4];
        let result = client.read(&mut buf).await;
        match result {
            Ok(0) => {}
            Ok(read) => panic!("expected clean EOF, got {read} unexpected bytes"),
            Err(error) => panic!("expected clean EOF, got a read error instead: {error}"),
        }
    }
}
