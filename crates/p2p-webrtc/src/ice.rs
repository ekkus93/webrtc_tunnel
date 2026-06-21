//! ICE candidate-gathering path selection (native vs `vnet`/`vnet_mux`) and the WebRTC
//! `SettingEngine` it builds, plus the host-candidate address resolution that backs it.

use std::net::{IpAddr, Ipv4Addr, UdpSocket};
use std::sync::Arc;

use ipnet::IpNet;
use webrtc::api::setting_engine::SettingEngine;
use webrtc::ice::udp_mux::{UDPMuxDefault, UDPMuxParams};
use webrtc::ice::udp_network::UDPNetwork;
use webrtc_util::ifaces;
use webrtc_util::vnet::interface::Interface;
use webrtc_util::vnet::net::Net;

use p2p_core::{AndroidIceMode, WebRtcConfig};

use crate::WebRtcError;

/// The resolved ICE candidate-gathering path, decided from the configured
/// [`AndroidIceMode`] and whether OS interface enumeration works.
///
/// Kept as a pure value so the decision can be unit-tested without touching real
/// network interfaces (`decide_ice_path`).
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum IcePath {
    /// Use the native/default `SettingEngine`; never call `set_vnet`.
    Native,
    /// Force the `Net::Ifs` vnet fallback. `required` means a missing fallback IPv4 is a
    /// hard error (explicit `vnet`/`vnet_mux` mode) rather than a best-effort warning
    /// (`auto`). `mux` additionally routes all ICE traffic through a single `0.0.0.0`-bound
    /// UDP socket (webrtc UDP mux) instead of a socket pinned to the interface IP.
    Vnet { required: bool, mux: bool },
}

/// Pure decision: which ICE path to use given the mode and enumeration result.
///
/// `android_ice_mode` is honored on **all** platforms — the name is historical. The vnet
/// fallback is selected at runtime by interface-enumeration success, not by
/// `#[cfg(target_os = "android")]`, so desktop integration tests can force `native`/`vnet`
/// too. There is no silent cross-mode fallback: `native` never engages vnet and `vnet`
/// never silently downgrades to native (a missing fallback IPv4 is a hard error).
const fn decide_ice_path(mode: AndroidIceMode, enumeration_works: bool) -> IcePath {
    match mode {
        AndroidIceMode::Native => IcePath::Native,
        AndroidIceMode::Vnet => IcePath::Vnet { required: true, mux: false },
        AndroidIceMode::VnetMux => IcePath::Vnet { required: true, mux: true },
        AndroidIceMode::Auto => {
            if enumeration_works {
                IcePath::Native
            } else {
                // Enumeration only fails where the OS restricts it — in practice Android
                // 11+. There a plain vnet host-candidate socket (bound to the specific
                // interface IP) gets its egress dropped/misrouted, black-holing offer→answer
                // data; the UDP-mux path (0.0.0.0-bound socket, real IP advertised) is the
                // proven fix. So auto's fallback engages the mux by default, making it work
                // without the `vnet_mux` debug override. Best-effort (`required: false`): if
                // no fallback IPv4 is found we still continue with the native engine.
                IcePath::Vnet { required: false, mux: true }
            }
        }
    }
}

/// A short, stable reason string for the decision log.
const fn ice_decision_reason(mode: AndroidIceMode, enumeration_works: bool) -> &'static str {
    match mode {
        AndroidIceMode::Native => "mode_native",
        AndroidIceMode::Vnet => "mode_vnet",
        AndroidIceMode::VnetMux => "mode_vnet_mux",
        AndroidIceMode::Auto if enumeration_works => "interface_enumeration_ok",
        AndroidIceMode::Auto => "interface_enumeration_failed",
    }
}

/// The stable config string for an [`AndroidIceMode`].
const fn ice_mode_str(mode: AndroidIceMode) -> &'static str {
    match mode {
        AndroidIceMode::Auto => "auto",
        AndroidIceMode::Native => "native",
        AndroidIceMode::Vnet => "vnet",
        AndroidIceMode::VnetMux => "vnet_mux",
    }
}

/// A snapshot of the ICE path decision for status/diagnostics, computed the same way as
/// [`build_setting_engine`] so the UI can show which path is actually active. Carries no
/// secrets (the advertised IPv4 is a LAN host address the peer already learns via ICE).
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct IceDecisionInfo {
    /// The requested `android_ice_mode` (`auto`/`native`/`vnet`/`vnet_mux`).
    pub requested_mode: &'static str,
    /// The path actually selected (`native`/`vnet`/`vnet_mux`).
    pub selected_path: &'static str,
    /// True when `auto` engaged its best-effort fallback (enumeration failed → mux path).
    pub fallback: bool,
    /// A short, stable reason string for the decision.
    pub reason: &'static str,
    /// The local IPv4 that will be advertised as the host candidate, if any.
    pub advertised_local_ipv4: Option<String>,
}

/// Describe the ICE path decision for the given config, matching what
/// [`build_setting_engine`] would actually do on this host right now.
pub fn describe_ice_decision(config: &WebRtcConfig) -> IceDecisionInfo {
    let mode = config.android_ice_mode;
    let enumeration_works = os_interface_enumeration_works();
    let selected_path = match decide_ice_path(mode, enumeration_works) {
        IcePath::Native => "native",
        IcePath::Vnet { mux: true, .. } => "vnet_mux",
        IcePath::Vnet { mux: false, .. } => "vnet",
    };
    IceDecisionInfo {
        requested_mode: ice_mode_str(mode),
        selected_path,
        fallback: matches!(mode, AndroidIceMode::Auto) && !enumeration_works,
        reason: ice_decision_reason(mode, enumeration_works),
        advertised_local_ipv4: advertised_local_ipv4(config).map(|ip| ip.to_string()),
    }
}

/// Build the WebRTC `SettingEngine`, honoring [`WebRtcConfig::android_ice_mode`].
///
/// `auto` (default): use the native/default engine when OS interface enumeration works
/// (desktop), else inject a real-socket `Net::Ifs` fallback carrying the primary local IPv4
/// **and** route ICE through a single `0.0.0.0`-bound UDP-mux socket — needed on Android 11+
/// (API 30+) where `getifaddrs`/NETLINK enumeration is restricted (so webrtc-rs gathers no
/// host candidate) and where a socket pinned to the interface IP has its egress dropped (so
/// the mux is required to actually carry data). `native` always uses the default engine
/// (never `set_vnet`) and fails loudly
/// through the normal connect path if no candidate is gathered. `vnet` always forces the
/// fallback and returns an error if a fallback local IPv4 cannot be determined. Every call
/// logs the requested mode and the selected path + reason; there is no silent fallback.
pub(crate) fn build_setting_engine(config: &WebRtcConfig) -> Result<SettingEngine, WebRtcError> {
    let mut engine = SettingEngine::default();
    let mode = config.android_ice_mode;
    let enumeration_works = os_interface_enumeration_works();
    let reason = ice_decision_reason(mode, enumeration_works);

    match decide_ice_path(mode, enumeration_works) {
        IcePath::Native => {
            tracing::info!(
                target: "ice",
                ?mode,
                selected_path = "native",
                set_vnet = false,
                enumeration_works,
                reason,
                "ICE setting engine decision",
            );
        }
        IcePath::Vnet { required, mux } => match fallback_net(config) {
            Some(net) => {
                engine.set_vnet(Some(Arc::new(net)));
                if mux {
                    // Route ICE I/O through a single 0.0.0.0-bound socket while still
                    // advertising the injected interface IP as the host candidate.
                    engine.set_udp_network(zero_bound_udp_mux()?);
                }
                tracing::info!(
                    target: "ice",
                    ?mode,
                    selected_path = if mux { "vnet_mux" } else { "vnet" },
                    set_vnet = true,
                    udp_mux = mux,
                    enumeration_works,
                    reason,
                    "ICE setting engine decision",
                );
            }
            None if required => {
                return Err(WebRtcError::InvalidConfig(
                    "android_ice_mode = \"vnet\"/\"vnet_mux\" was requested but no fallback local \
                     IPv4 could be determined; refusing to silently fall back to the native engine"
                        .to_owned(),
                ));
            }
            None => {
                tracing::warn!(
                    target: "ice",
                    ?mode,
                    selected_path = "native",
                    set_vnet = false,
                    enumeration_works,
                    reason,
                    "auto mode wanted the vnet fallback but no fallback local IPv4 was found; \
                     continuing with the native engine (ICE may gather no host candidate)",
                );
            }
        },
    }
    Ok(engine)
}

/// Whether webrtc-rs's own interface enumeration yields at least one usable
/// (non-loopback IPv4) host address. `getifaddrs` returning an error (Android) or an
/// empty / loopback-only list both count as "not working".
fn os_interface_enumeration_works() -> bool {
    match ifaces::ifaces() {
        Ok(list) => list.iter().any(
            |iface| matches!(iface.addr, Some(addr) if addr.is_ipv4() && !addr.ip().is_loopback()),
        ),
        Err(_) => false,
    }
}

/// A real-socket `Net` whose single interface carries the advertised local IPv4.
fn fallback_net(config: &WebRtcConfig) -> Option<Net> {
    let ip = advertised_local_ipv4(config)?;
    // The prefix length is irrelevant to candidate gathering (which only reads the
    // address); /24 is a reasonable placeholder for a LAN.
    let ipnet = IpNet::new(IpAddr::V4(ip), 24).ok()?;
    let interface = Interface::new("p2p-fallback".to_owned(), vec![ipnet]);
    Some(Net::Ifs(vec![interface]))
}

/// The local IPv4 to advertise as the `vnet`/`vnet_mux` host candidate.
///
/// Prefers an explicit address injected via config (`advertised_local_ipv4`) — the Android
/// production path, where the address comes from `ConnectivityManager`/`LinkProperties`.
/// Only when none is injected does it fall back to the desktop UDP-route probe, which is
/// compiled out on Android (`desktop_route_probe_ipv4`) so production Android never relies on
/// the hard-coded `8.8.8.8` route trick. On Android with no injected address this returns
/// `None`, and `vnet`/`vnet_mux` then fail loudly rather than dropping to native ICE.
fn advertised_local_ipv4(config: &WebRtcConfig) -> Option<Ipv4Addr> {
    if let Some(raw) = config.advertised_local_ipv4.as_deref() {
        return parse_advertised_ipv4(raw);
    }
    desktop_route_probe_ipv4()
}

/// Parse and sanity-check a config-injected host-candidate address. Rejects
/// loopback/unspecified so a misconfigured value fails loud instead of advertising junk.
fn parse_advertised_ipv4(raw: &str) -> Option<Ipv4Addr> {
    match raw.parse::<Ipv4Addr>() {
        Ok(addr) if !addr.is_loopback() && !addr.is_unspecified() => Some(addr),
        _ => None,
    }
}

/// Desktop-only UDP-route probe for the source IPv4. Compiled out on Android so the
/// hard-coded `8.8.8.8` route trick is never used in Android production.
#[cfg(not(target_os = "android"))]
fn desktop_route_probe_ipv4() -> Option<Ipv4Addr> {
    primary_local_ipv4()
}

/// On Android the advertised address must be injected via config; there is no route probe.
#[cfg(target_os = "android")]
fn desktop_route_probe_ipv4() -> Option<Ipv4Addr> {
    None
}

/// A webrtc UDP-mux network backed by a single real socket bound to `0.0.0.0:0`.
///
/// Used by `vnet_mux`: ICE still advertises the injected interface IP as the host candidate
/// (via `set_vnet`), but all traffic flows over this unbound socket. Binding `0.0.0.0`
/// (rather than the specific interface IP, as the plain `vnet` path does) lets the OS apply
/// its normal per-destination routing — on Android the `netd` fwmark for the default
/// network — instead of pinning egress to one source address, which is the suspected cause
/// of the offer→answer data-plane black-hole.
///
/// Must be called from within a Tokio runtime (it is, via `WebRtcPeer::new`): the socket is
/// bound with `std` then adopted with `from_std`, which registers it with the current
/// reactor. Muxed mode gathers no server-reflexive candidate (webrtc skips srflx for mux).
fn zero_bound_udp_mux() -> Result<UDPNetwork, WebRtcError> {
    let std_socket = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).map_err(|error| {
        WebRtcError::InvalidConfig(format!("failed to bind 0.0.0.0 UDP mux socket: {error}"))
    })?;
    std_socket.set_nonblocking(true).map_err(|error| {
        WebRtcError::InvalidConfig(format!("failed to set UDP mux socket non-blocking: {error}"))
    })?;
    let tokio_socket = tokio::net::UdpSocket::from_std(std_socket).map_err(|error| {
        WebRtcError::InvalidConfig(format!("failed to adopt UDP mux socket into tokio: {error}"))
    })?;
    let mux = UDPMuxDefault::new(UDPMuxParams::new(tokio_socket));
    Ok(UDPNetwork::Muxed(mux))
}

/// The OS-chosen source IPv4 for outbound traffic, discovered without interface
/// enumeration by "connecting" a UDP socket to a public address (no packets are sent)
/// and reading the bound local address. Desktop-only — see `desktop_route_probe_ipv4`.
#[cfg(not(target_os = "android"))]
fn primary_local_ipv4() -> Option<Ipv4Addr> {
    let socket = UdpSocket::bind("0.0.0.0:0").ok()?;
    socket.connect("8.8.8.8:80").ok()?;
    match socket.local_addr().ok()?.ip() {
        IpAddr::V4(addr) if !addr.is_loopback() && !addr.is_unspecified() => Some(addr),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use std::net::Ipv4Addr;

    use super::{
        IcePath, advertised_local_ipv4, build_setting_engine, decide_ice_path,
        describe_ice_decision, fallback_net, parse_advertised_ipv4, zero_bound_udp_mux,
    };
    use crate::WebRtcError;
    use p2p_core::{AndroidIceMode, WebRtcConfig};

    fn sample_config() -> WebRtcConfig {
        WebRtcConfig {
            stun_urls: vec!["stun:stun.l.google.com:19302".to_owned()],
            enable_trickle_ice: true,
            enable_ice_restart: true,
            android_ice_mode: Default::default(),
            advertised_local_ipv4: None,
        }
    }

    #[test]
    fn ice_path_decision_covers_all_modes() {
        // auto follows enumeration; when it can't enumerate (Android) it falls back to the
        // mux path (best-effort) so the data-plane fix applies without the debug override.
        assert_eq!(decide_ice_path(AndroidIceMode::Auto, true), IcePath::Native);
        assert_eq!(
            decide_ice_path(AndroidIceMode::Auto, false),
            IcePath::Vnet { required: false, mux: true }
        );
        // native is always native, regardless of enumeration; never engages vnet.
        assert_eq!(decide_ice_path(AndroidIceMode::Native, true), IcePath::Native);
        assert_eq!(decide_ice_path(AndroidIceMode::Native, false), IcePath::Native);
        // vnet always forces the fallback and treats a missing IPv4 as a hard error.
        assert_eq!(
            decide_ice_path(AndroidIceMode::Vnet, true),
            IcePath::Vnet { required: true, mux: false }
        );
        assert_eq!(
            decide_ice_path(AndroidIceMode::Vnet, false),
            IcePath::Vnet { required: true, mux: false }
        );
        // vnet_mux is vnet with the UDP mux engaged; also a hard error on missing IPv4.
        assert_eq!(
            decide_ice_path(AndroidIceMode::VnetMux, true),
            IcePath::Vnet { required: true, mux: true }
        );
        assert_eq!(
            decide_ice_path(AndroidIceMode::VnetMux, false),
            IcePath::Vnet { required: true, mux: true }
        );
    }

    #[test]
    fn describe_ice_decision_reports_requested_selected_and_address() {
        let mut config = sample_config();
        config.android_ice_mode = AndroidIceMode::VnetMux;
        config.advertised_local_ipv4 = Some("10.1.3.11".to_owned());
        let info = describe_ice_decision(&config);
        assert_eq!(info.requested_mode, "vnet_mux");
        assert_eq!(info.selected_path, "vnet_mux");
        assert!(!info.fallback, "an explicit mode is never a best-effort fallback");
        assert_eq!(info.advertised_local_ipv4.as_deref(), Some("10.1.3.11"));

        // native is always native, with no injected address consulted as a fallback.
        config.android_ice_mode = AndroidIceMode::Native;
        let native = describe_ice_decision(&config);
        assert_eq!(native.requested_mode, "native");
        assert_eq!(native.selected_path, "native");
    }

    #[test]
    fn native_mode_builds_engine_without_fallback() {
        // native must never fail on the decision itself (it never requires a fallback IPv4),
        // independent of the host's actual interfaces.
        let mut config = sample_config();
        config.android_ice_mode = AndroidIceMode::Native;
        assert!(build_setting_engine(&config).is_ok());
    }

    #[test]
    fn auto_mode_builds_engine() {
        let mut config = sample_config();
        config.android_ice_mode = AndroidIceMode::Auto;
        assert!(build_setting_engine(&config).is_ok());
    }

    #[tokio::test]
    async fn zero_bound_udp_mux_binds_real_socket() {
        // Must run inside a Tokio runtime (from_std registers with the reactor).
        assert!(zero_bound_udp_mux().is_ok(), "0.0.0.0 UDP mux should bind");
    }

    #[tokio::test]
    async fn vnet_mux_mode_builds_engine_when_fallback_ipv4_exists() {
        // vnet_mux forces the fallback path and engages the UDP mux. It builds when a
        // non-loopback IPv4 is available (CI host); otherwise it fails loudly with the
        // missing-fallback error — never silently, and never a panic.
        let mut config = sample_config();
        config.android_ice_mode = AndroidIceMode::VnetMux;
        match build_setting_engine(&config) {
            Ok(_) => {}
            Err(WebRtcError::InvalidConfig(message)) => {
                assert!(message.contains("no fallback local"), "unexpected error: {message}");
            }
            Err(other) => panic!("unexpected error variant: {other}"),
        }
    }

    #[test]
    fn parse_advertised_ipv4_accepts_routable_and_rejects_junk() {
        assert_eq!(parse_advertised_ipv4("10.1.3.11"), Some(Ipv4Addr::new(10, 1, 3, 11)));
        assert_eq!(parse_advertised_ipv4("192.168.0.5"), Some(Ipv4Addr::new(192, 168, 0, 5)));
        // Loopback / unspecified / non-IPv4 / garbage all reject so we never advertise junk.
        assert_eq!(parse_advertised_ipv4("127.0.0.1"), None);
        assert_eq!(parse_advertised_ipv4("0.0.0.0"), None);
        assert_eq!(parse_advertised_ipv4("not-an-ip"), None);
        assert_eq!(parse_advertised_ipv4("::1"), None);
    }

    #[test]
    fn injected_address_is_preferred_over_the_route_probe() {
        // An injected address is used verbatim (this is the Android production path) without
        // ever consulting the desktop route probe.
        let mut config = sample_config();
        config.advertised_local_ipv4 = Some("10.1.3.11".to_owned());
        assert_eq!(advertised_local_ipv4(&config), Some(Ipv4Addr::new(10, 1, 3, 11)));
        // A garbage injected value resolves to None (→ vnet_mux fails loud) rather than
        // silently falling through to the route probe.
        config.advertised_local_ipv4 = Some("garbage".to_owned());
        assert_eq!(advertised_local_ipv4(&config), None);
    }

    #[tokio::test]
    async fn vnet_mux_builds_engine_with_injected_address() {
        // With an explicit injected address, vnet_mux builds deterministically — no reliance
        // on host interfaces or the route probe. This is the strict Android path.
        let mut config = sample_config();
        config.android_ice_mode = AndroidIceMode::VnetMux;
        config.advertised_local_ipv4 = Some("10.1.3.11".to_owned());
        assert!(build_setting_engine(&config).is_ok());
        assert!(fallback_net(&config).is_some());
    }

    #[tokio::test]
    async fn vnet_mux_with_garbage_injected_address_fails_loud() {
        // A garbage injected address must not silently drop to native ICE; vnet_mux is a hard
        // error when no usable advertised address can be resolved.
        let mut config = sample_config();
        config.android_ice_mode = AndroidIceMode::VnetMux;
        config.advertised_local_ipv4 = Some("999.999.0.1".to_owned());
        match build_setting_engine(&config) {
            Err(WebRtcError::InvalidConfig(message)) => {
                assert!(message.contains("no fallback local"), "unexpected error: {message}");
            }
            Ok(_) => panic!("vnet_mux must not build with an unusable injected address"),
            Err(other) => panic!("unexpected error variant: {other}"),
        }
    }
}
