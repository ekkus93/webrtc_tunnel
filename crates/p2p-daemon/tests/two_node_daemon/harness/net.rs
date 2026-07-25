//! TCP client and echo-target helpers: connecting with retry, single and
//! retry-until-success round trips, denied-stream assertions, and spawnable echo
//! targets that count accepted connections.

use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::task::JoinHandle;
use tokio::time::{sleep, timeout};

/// Bound for a single fresh session's full establishment (signaling, ICE, DTLS, data
/// channel, data-plane probe) plus the actual echo round trip. Must stay comfortably
/// above the harness's own `data_plane_probe_timeout_ms` (see `harness/config.rs`) —
/// both were widened together after CPU-contention flakiness investigation showed real
/// test failures caused by scheduling delay alone, not a genuine session/data-plane
/// problem. Used only for a single fresh attempt, not `try_client_round_trip`'s
/// retry-until-success loop (which intentionally keeps each attempt short so it can
/// re-check its own outer deadline promptly).
const FRESH_SESSION_ROUND_TRIP_TIMEOUT: Duration = Duration::from_secs(25);

pub(crate) async fn connect_with_retry(port: u16) -> TcpStream {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(10);
    loop {
        match TcpStream::connect(("127.0.0.1", port)).await {
            Ok(stream) => return stream,
            Err(error) if tokio::time::Instant::now() < deadline => {
                let _ = error;
                sleep(Duration::from_millis(50)).await;
            }
            Err(error) => panic!("offer listener did not start in time: {error}"),
        }
    }
}

pub(crate) async fn assert_client_round_trip(
    port: u16,
    request: &'static [u8; 4],
    response: &'static [u8; 4],
) {
    let mut client = connect_with_retry(port).await;
    client.write_all(request).await.expect("client write");
    let mut received = [0_u8; 4];
    timeout(FRESH_SESSION_ROUND_TRIP_TIMEOUT, client.read_exact(&mut received))
        .await
        .expect("client should receive response in time")
        .expect("client should read response");
    assert_eq!(&received, response);
    client.shutdown().await.expect("client shutdown");
}

pub(crate) async fn try_client_round_trip(
    port: u16,
    request: &[u8; 4],
    response: &[u8; 4],
) -> Result<(), String> {
    let mut client = TcpStream::connect(("127.0.0.1", port))
        .await
        .map_err(|error| format!("connect: {error}"))?;
    client.write_all(request).await.map_err(|error| format!("write: {error}"))?;
    let mut received = [0_u8; 4];
    timeout(Duration::from_secs(10), client.read_exact(&mut received))
        .await
        .map_err(|_| "read timeout".to_owned())?
        .map_err(|error| format!("read: {error}"))?;
    if received != *response {
        return Err(format!("response mismatch: got {received:?}, expected {response:?}"));
    }
    let _ = client.shutdown().await;
    Ok(())
}

pub(crate) async fn assert_client_round_trip_eventually(
    port: u16,
    request: [u8; 4],
    response: [u8; 4],
    description: &str,
) {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(20);
    loop {
        match try_client_round_trip(port, &request, &response).await {
            Ok(()) => return,
            Err(error) => {
                if tokio::time::Instant::now() >= deadline {
                    panic!("{description} did not complete in time; last error: {error}");
                }
                sleep(Duration::from_millis(100)).await;
            }
        }
    }
}

pub(crate) async fn assert_client_round_trip_owned(port: u16, request: [u8; 4], response: [u8; 4]) {
    let mut client = connect_with_retry(port).await;
    client.write_all(&request).await.expect("client write");
    let mut received = [0_u8; 4];
    timeout(FRESH_SESSION_ROUND_TRIP_TIMEOUT, client.read_exact(&mut received))
        .await
        .expect("client should receive response in time")
        .expect("client should read response");
    assert_eq!(received, response);
    client.shutdown().await.expect("client shutdown");
}

/// The offer session closes an abandoned client gracefully (drains any already-sent
/// bytes, then shuts down) rather than dropping it with unread data still buffered,
/// which the kernel would otherwise turn into a raw `ConnectionReset`. That graceful
/// close is itself a best-effort, bounded drain: under severe scheduler contention the
/// client's write can still land after the drain window closes, so `ConnectionReset`
/// remains a possible (if much rarer) outcome alongside the intended `UnexpectedEof`.
/// Both mean the same thing to the caller — the connection was abandoned without a
/// response — so tests assert on that, not one exact `ErrorKind`.
pub(crate) fn assert_client_connection_abandoned_without_response(error: &std::io::Error) {
    assert!(
        matches!(
            error.kind(),
            std::io::ErrorKind::UnexpectedEof | std::io::ErrorKind::ConnectionReset
        ),
        "expected the abandoned client's read to fail with a clean EOF or a reset, got: {error}"
    );
}

pub(crate) async fn assert_client_stream_fails(port: u16, request: &'static [u8; 4]) {
    let mut client = connect_with_retry(port).await;
    client.write_all(request).await.expect("client write");
    let mut received = [0_u8; 4];
    let result = timeout(Duration::from_secs(5), client.read_exact(&mut received)).await;
    assert!(
        !matches!(result, Ok(Ok(_))),
        "denied stream unexpectedly returned bytes: {received:?}"
    );
}

pub(crate) async fn spawn_echo_target(
    expected_connections: usize,
) -> (u16, JoinHandle<()>, Arc<AtomicUsize>) {
    let listener = TcpListener::bind(("127.0.0.1", 0)).await.expect("target listener should bind");
    let port = listener.local_addr().expect("target addr").port();
    let accepted = Arc::new(AtomicUsize::new(0));
    let accepted_for_task = Arc::clone(&accepted);
    let task = tokio::spawn(async move {
        for _ in 0..expected_connections {
            let (mut stream, _) = listener.accept().await.expect("target accept");
            let accepted_for_stream = Arc::clone(&accepted_for_task);
            tokio::spawn(async move {
                let mut request = [0_u8; 4];
                stream.read_exact(&mut request).await.expect("target read");
                stream.write_all(&request).await.expect("target write");
                stream.shutdown().await.expect("target shutdown");
                accepted_for_stream.fetch_add(1, Ordering::SeqCst);
            });
        }
    });
    (port, task, accepted)
}

pub(crate) async fn spawn_counting_echo_target() -> (u16, JoinHandle<()>, Arc<AtomicUsize>) {
    let listener = TcpListener::bind(("127.0.0.1", 0)).await.expect("target listener should bind");
    let port = listener.local_addr().expect("target addr").port();
    let accepted = Arc::new(AtomicUsize::new(0));
    let accepted_for_task = Arc::clone(&accepted);
    let task = tokio::spawn(async move {
        loop {
            let (mut stream, _) = listener.accept().await.expect("target accept");
            let accepted_for_stream = Arc::clone(&accepted_for_task);
            tokio::spawn(async move {
                let mut request = [0_u8; 4];
                if stream.read_exact(&mut request).await.is_ok() {
                    let _ = stream.write_all(&request).await;
                    let _ = stream.shutdown().await;
                    accepted_for_stream.fetch_add(1, Ordering::SeqCst);
                }
            });
        }
    });
    (port, task, accepted)
}
