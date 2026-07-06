//! Daemon lifetime is intentionally longer than session lifetime in v0.3.
//!
//! Each daemon process stays alive and repeatedly returns to its steady state
//! (`Serving` for answer, `WaitingForLocalClient` for offer) after ordinary
//! session failures. Answer daemons can serve multiple authorized peers, while
//! each offer-side peer session may carry multiple multiplexed TCP streams.
//! Session-owned streams are cleaned up deterministically before the daemon
//! accepts follow-on work.
//! Startup and security initialization failures remain fatal, while recoverable
//! runtime transport turbulence updates local status truthfully before the
//! daemon retries and returns to service.
//!
//! This crate root is intentionally thin: it wires the daemon submodules together
//! and re-exports the public API. The role state machines live in [`offer`] and
//! [`answer`], over the shared data model in [`types`], the signaling/status helper
//! layer in [`signaling`], and the leaf helpers in [`busy`], [`config`],
//! [`messages`], and [`predicates`].

mod answer;
mod busy;
mod config;
mod error;
mod logging;
mod messages;
mod offer;
mod predicates;
mod process_signal;
mod shutdown;
mod signaling;
mod status;
mod types;

// Public API.
pub use answer::{
    run_answer_daemon, run_answer_daemon_with_shutdown, run_answer_daemon_with_transport,
    run_answer_daemon_with_transport_and_shutdown,
};
pub use config::{
    apply_answer_overrides, apply_env_overrides, apply_offer_overrides, compute_backoff_delay,
};
pub use error::DaemonError;
pub use logging::{
    candidate_log_summary, redact_candidate, redact_sdp, redact_secret, setup_logging,
};
pub use offer::{
    run_offer_daemon, run_offer_daemon_with_shutdown, run_offer_daemon_with_status,
    run_offer_daemon_with_status_and_shutdown, run_offer_daemon_with_transport,
    run_offer_daemon_with_transport_and_shutdown,
};
pub use process_signal::wait_for_process_shutdown_signal;
pub use shutdown::ShutdownToken;
pub use status::{
    DaemonStatus, ForwardListenState, ForwardRuntimeStatus, SessionStatus, StatusWriter,
};
pub use types::{ActiveSession, DaemonSignalingTransport};

// Test-only entry points, available whenever debug assertions are on (tests + dev).
#[cfg(any(test, debug_assertions))]
pub use answer::{
    PayloadAdmissionBarrier, PayloadAdmissionBarrierEntered, PayloadAdmissionBarrierRelease,
    run_answer_daemon_with_payload_admission_barrier_and_shutdown,
};
#[cfg(any(test, debug_assertions))]
pub use offer::{
    OfferSessionTestEvent, OfferSessionTestHandle, run_offer_daemon_with_transport_and_test_hook,
    run_offer_daemon_with_transport_and_test_hook_and_shutdown,
    run_offer_daemon_with_worker_fault_hook_and_shutdown,
};

// Crate-internal symbols surfaced at the root so the unit-test module reaches them
// via `super::` without depending on each submodule's path. Glob re-exports keep
// this list maintenance-free and do not warn when an item is unused.
#[cfg(test)]
pub(crate) use crate::{
    answer::*, busy::*, config::*, messages::*, offer::*, predicates::*, signaling::*, types::*,
};
// External types the unit tests reach through `super::` (formerly imported by the
// monolithic lib body).
#[cfg(test)]
pub(crate) use p2p_core::DaemonState;
#[cfg(test)]
pub(crate) use p2p_tunnel::OfferListener;
#[cfg(test)]
pub(crate) use p2p_webrtc::{IceConnectionState, WebRtcPeer};

#[cfg(test)]
mod tests;
