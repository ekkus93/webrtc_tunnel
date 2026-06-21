//! WebRTC peer wrapper for the tunnel: a STUN-only `WebRtcPeer`, a reliable/ordered data
//! channel handle, and the ICE path selection (native vs `vnet`/`vnet_mux`) that works
//! around restricted interface enumeration / black-holed egress on Android.
//!
//! The implementation is split into focused modules; the crate's public API is re-exported
//! here so callers keep using `p2p_webrtc::{...}` paths.

mod data_channel;
mod error;
mod ice;
mod peer;

pub use data_channel::{DataChannelEvent, DataChannelHandle};
pub use error::WebRtcError;
pub use ice::{IceDecisionInfo, describe_ice_decision};
pub use peer::{IceCandidateSignal, IceConnectionState, WebRtcPeer, build_rtc_configuration};

#[cfg(any(test, debug_assertions))]
pub use peer::IceStateInjectorForTests;
