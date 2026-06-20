//! On-device WebRTC self-diagnostics, exposed to the app/instrumentation tests so we
//! can verify how `p2p-webrtc` behaves on Android **without** needing a remote peer
//! or NAT traversal (the full data path is blocked on the emulator by qemu NAT).
//!
//! Three checks, all runnable on a bare device/emulator:
//! - the OS's chosen local IP for outbound traffic (reveals Wi-Fi vs cellular routing),
//! - ICE candidate gathering — does the library produce a `host` candidate with the
//!   device's LAN IP, plus a STUN `srflx` candidate? (the key question for the
//!   "Android doesn't gather its Wi-Fi candidate" hypothesis),
//! - a loopback handshake — two peers in this process completing ICE + DTLS + a data
//!   channel and echoing bytes, proving the connection machinery works on Android.
//!
//! Results are returned as JSON. This is an explicit diagnostic action (not the
//! tunnel runtime), so candidate addresses are included to make the report useful.

use std::time::{Duration, Instant};

use p2p_core::WebRtcConfig;
use p2p_webrtc::{DataChannelEvent, WebRtcPeer};
use serde::Serialize;

const STUN_URL: &str = "stun:stun.l.google.com:19302";
const PROBE_PING: &[u8] = b"probe-ping";

#[derive(Serialize, Default)]
struct WebRtcProbeReport {
    /// Source IP the OS picks for internet-bound UDP (Wi-Fi LAN IP, or cellular if
    /// that is the default route — a multi-homing tell). `None` on Android, where the
    /// hard-coded route probe is not used (see `address_source`).
    os_local_ip: Option<String>,
    /// Where `os_local_ip` came from: `desktop_udp_route_probe` on non-Android, or
    /// `unavailable_android` on Android (which sources its advertised address from the
    /// Kotlin `ConnectivityManager`/`LinkProperties` layer, not this probe).
    address_source: &'static str,
    /// What webrtc-rs's own interface enumeration (`webrtc_util::ifaces`) sees. If this
    /// is empty while `os_local_ip` is set, the library cannot gather host candidates.
    interfaces: Vec<String>,
    gather: GatherReport,
    loopback: LoopbackReport,
}

#[derive(Serialize, Default)]
struct GatherReport {
    ok: bool,
    error: Option<String>,
    host: usize,
    srflx: usize,
    relay: usize,
    other: usize,
    /// One `typ=… transport=… addr=…` summary per gathered candidate.
    candidates: Vec<String>,
}

#[derive(Serialize, Default)]
struct LoopbackReport {
    ok: bool,
    detail: String,
    elapsed_ms: u64,
}

/// Run the probe on a fresh Tokio runtime and return the JSON report. Sub-check
/// failures are captured in the report rather than bubbled, so the caller always
/// gets a readable result.
pub(crate) fn run_webrtc_probe(timeout_secs: u64) -> Result<String, String> {
    let runtime = tokio::runtime::Runtime::new().map_err(|error| error.to_string())?;
    let report = runtime.block_on(probe(timeout_secs));
    serde_json::to_string(&report).map_err(|error| error.to_string())
}

async fn probe(timeout_secs: u64) -> WebRtcProbeReport {
    let secs = timeout_secs.clamp(1, 30);
    WebRtcProbeReport {
        os_local_ip: os_local_ip(),
        address_source: ADDRESS_SOURCE,
        interfaces: raw_interfaces(),
        gather: gather_candidates(secs).await,
        loopback: loopback_handshake(secs).await,
    }
}

/// The source label for `os_local_ip` on this build target.
#[cfg(not(target_os = "android"))]
const ADDRESS_SOURCE: &str = "desktop_udp_route_probe";
#[cfg(target_os = "android")]
const ADDRESS_SOURCE: &str = "unavailable_android";

/// What webrtc-rs's own interface enumeration returns — the exact source used to
/// build host candidates (`webrtc_util::ifaces::ifaces()` → `getifaddrs`).
fn raw_interfaces() -> Vec<String> {
    match webrtc_util::ifaces::ifaces() {
        Ok(list) => list
            .iter()
            .map(|iface| format!("{} kind={:?} addr={:?}", iface.name, iface.kind, iface.addr))
            .collect(),
        Err(error) => vec![format!("ifaces() error: {error}")],
    }
}

/// The OS's chosen local source IP, found by "connecting" a UDP socket to a public
/// address (no packets are sent) and reading the bound local address. Desktop-only: the
/// hard-coded `8.8.8.8` route trick is compiled out on Android, where the advertised
/// address comes from the Kotlin `ConnectivityManager`/`LinkProperties` layer instead.
#[cfg(not(target_os = "android"))]
fn os_local_ip() -> Option<String> {
    let socket = std::net::UdpSocket::bind("0.0.0.0:0").ok()?;
    socket.connect("8.8.8.8:80").ok()?;
    socket.local_addr().ok().map(|addr| addr.ip().to_string())
}

/// Android does not use the route probe; the advertised address is injected from Kotlin.
#[cfg(target_os = "android")]
fn os_local_ip() -> Option<String> {
    None
}

async fn gather_candidates(secs: u64) -> GatherReport {
    let mut report = GatherReport::default();
    let config = WebRtcConfig {
        stun_urls: vec![STUN_URL.to_owned()],
        enable_trickle_ice: true,
        enable_ice_restart: false,
        android_ice_mode: Default::default(),
        advertised_local_ipv4: None,
    };
    let peer = match WebRtcPeer::new(&config).await {
        Ok(peer) => peer,
        Err(error) => {
            report.error = Some(format!("peer build failed: {error}"));
            return report;
        }
    };
    // A data channel adds the application m-section so ICE actually gathers, and
    // create_offer sets the local description which starts gathering.
    if let Err(error) = peer.create_data_channel().await {
        report.error = Some(format!("data channel failed: {error}"));
        let _ = peer.close().await;
        return report;
    }
    if let Err(error) = peer.create_offer().await {
        report.error = Some(format!("create offer failed: {error}"));
        let _ = peer.close().await;
        return report;
    }

    let deadline = Instant::now() + Duration::from_secs(secs);
    loop {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            break;
        }
        match tokio::time::timeout(remaining, peer.next_local_candidate()).await {
            Ok(Some(signal)) => match signal.candidate {
                Some(line) => {
                    let (typ, transport, addr) = parse_candidate(&line);
                    match typ.as_str() {
                        "host" => report.host += 1,
                        "srflx" => report.srflx += 1,
                        "relay" => report.relay += 1,
                        _ => report.other += 1,
                    }
                    report.candidates.push(format!("typ={typ} transport={transport} addr={addr}"));
                }
                None => break, // end-of-candidates
            },
            Ok(None) => break,
            Err(_) => break, // gathering timed out
        }
    }
    report.ok = report.error.is_none();
    let _ = peer.close().await;
    report
}

/// Extract `(type, transport, address:port)` from an SDP candidate line:
/// `candidate:<foundation> <component> <transport> <priority> <ip> <port> typ <type> …`.
fn parse_candidate(line: &str) -> (String, String, String) {
    let tokens: Vec<&str> = line.split_whitespace().collect();
    let transport = tokens.get(2).copied().unwrap_or("unknown").to_owned();
    let addr = match (tokens.get(4), tokens.get(5)) {
        (Some(ip), Some(port)) => format!("{ip}:{port}"),
        _ => "unknown".to_owned(),
    };
    let typ = tokens
        .iter()
        .position(|token| *token == "typ")
        .and_then(|index| tokens.get(index + 1))
        .copied()
        .unwrap_or("unknown")
        .to_owned();
    (typ, transport, addr)
}

async fn loopback_handshake(secs: u64) -> LoopbackReport {
    let started = Instant::now();
    match loopback_inner(secs).await {
        Ok(()) => LoopbackReport {
            ok: true,
            detail: "data channel opened and bytes echoed on-device".to_owned(),
            elapsed_ms: started.elapsed().as_millis() as u64,
        },
        Err(detail) => {
            LoopbackReport { ok: false, detail, elapsed_ms: started.elapsed().as_millis() as u64 }
        }
    }
}

async fn loopback_inner(secs: u64) -> Result<(), String> {
    let timeout = Duration::from_secs(secs.max(5));
    // No STUN, no trickle: candidates are gathered and bundled into the SDP, so the
    // two peers connect purely over the device's own host candidates.
    let config = WebRtcConfig {
        stun_urls: Vec::new(),
        enable_trickle_ice: false,
        enable_ice_restart: false,
        android_ice_mode: Default::default(),
        advertised_local_ipv4: None,
    };

    let offer = WebRtcPeer::new(&config).await.map_err(|error| format!("offer build: {error}"))?;
    let answer =
        WebRtcPeer::new(&config).await.map_err(|error| format!("answer build: {error}"))?;

    let offer_channel =
        offer.create_data_channel().await.map_err(|error| format!("offer channel: {error}"))?;
    let offer_sdp = offer.create_offer().await.map_err(|error| format!("create offer: {error}"))?;
    answer.apply_remote_offer(&offer_sdp).await.map_err(|error| format!("apply offer: {error}"))?;
    let answer_sdp =
        answer.create_answer().await.map_err(|error| format!("create answer: {error}"))?;
    offer
        .apply_remote_answer(&answer_sdp)
        .await
        .map_err(|error| format!("apply answer: {error}"))?;

    let answer_channel = tokio::time::timeout(timeout, answer.next_incoming_data_channel())
        .await
        .map_err(|_| "timed out waiting for incoming data channel".to_owned())?
        .ok_or_else(|| "incoming data channel stream closed".to_owned())?
        .map_err(|error| format!("incoming data channel: {error}"))?;

    offer_channel.wait_for_open(timeout).await.map_err(|error| format!("offer open: {error}"))?;
    answer_channel.wait_for_open(timeout).await.map_err(|error| format!("answer open: {error}"))?;

    offer_channel.send(PROBE_PING).await.map_err(|error| format!("send: {error}"))?;
    let received = tokio::time::timeout(timeout, async {
        loop {
            match answer_channel.next_event().await {
                Some(DataChannelEvent::Message(bytes)) => return Some(bytes),
                Some(_) => continue,
                None => return None,
            }
        }
    })
    .await
    .map_err(|_| "timed out waiting for echoed bytes".to_owned())?;

    match received {
        Some(bytes) if bytes == PROBE_PING => {}
        Some(_) => return Err("answer received unexpected bytes".to_owned()),
        None => return Err("answer channel closed before bytes arrived".to_owned()),
    }

    let _ = offer.close().await;
    let _ = answer.close().await;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_candidate_extracts_type_transport_and_address() {
        let (typ, transport, addr) =
            parse_candidate("candidate:1 1 udp 2130706431 192.168.1.5 44734 typ host");
        assert_eq!(typ, "host");
        assert_eq!(transport, "udp");
        assert_eq!(addr, "192.168.1.5:44734");
    }

    #[test]
    fn parse_candidate_tolerates_garbage() {
        let (typ, _, addr) = parse_candidate("nonsense");
        assert_eq!(typ, "unknown");
        assert_eq!(addr, "unknown");
    }

    // Proves the loopback probe logic works on the host (glibc). The same code runs
    // on Android via the instrumentation test, which is where it has diagnostic value.
    #[tokio::test]
    async fn loopback_handshake_succeeds_on_host() {
        let report = loopback_handshake(10).await;
        assert!(report.ok, "loopback failed: {}", report.detail);
    }
}
