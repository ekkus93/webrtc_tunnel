//! Signaling transport.
//!
//! This module is a thin coordinator: it wires the transport submodules together
//! and re-exports the public API. The crypto encode/decode layer lives in
//! [`codec`], the broker networking in [`mqtt`]. [`SignalingSession`] bundles the
//! per-session replay cache and ack tracker.

mod codec;
mod mqtt;

use crate::ack::AckTracker;
use crate::replay::ReplayCache;

pub use codec::{DecodedSignal, SignalCodec};
pub use mqtt::{MqttSignalingTransport, signal_topic};

#[derive(Debug)]
pub struct SignalingSession {
    pub replay_cache: ReplayCache,
    pub ack_tracker: AckTracker,
}

impl SignalingSession {
    pub fn new(replay_cache_size: usize) -> Self {
        Self {
            replay_cache: ReplayCache::new(replay_cache_size),
            ack_tracker: AckTracker::default(),
        }
    }
}

// Crate-internal symbols surfaced at the transport root so the unit-test module
// reaches them via `super::` without depending on each submodule's path. Glob
// re-exports keep this maintenance-free and do not warn when an item is unused.
#[cfg(test)]
pub(crate) use self::mqtt::*;
#[cfg(test)]
pub(crate) use crate::{EnvelopeFlags, InnerMessageBuilder, OuterEnvelope, ReplayStatus};

#[cfg(test)]
mod tests;
