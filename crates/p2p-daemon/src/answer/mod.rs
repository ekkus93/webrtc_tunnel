//! Answer-role daemon: serves multiple authorized peers concurrently. Accepts
//! inbound offers, spawns per-peer answer sessions, routes authenticated signals
//! to the owning session, and keeps local status truthful across session churn.
//!
//! The module is split across a few files: this one holds only the public entry
//! points (`run_answer_daemon` and its test-hook variants), [`runtime`] holds the
//! core run loop and completion/drain handling, [`payload`] holds incoming-payload
//! routing and session admission, and [`test_support`] holds test-only observation
//! hooks/barriers.

use p2p_core::AppConfig;
use p2p_crypto::{AuthorizedKeys, IdentityFile};
use p2p_signaling::MqttSignalingTransport;

use crate::DaemonError;
use crate::ShutdownToken;
use crate::types::*;

mod payload;
mod runtime;
mod session;
mod test_support;

use runtime::run_answer_daemon_inner;

#[cfg(any(test, debug_assertions))]
use test_support::AnswerDaemonTestHooks;
#[cfg(any(test, debug_assertions))]
pub use test_support::{
    AnswerSessionPanicTrigger, PayloadAdmissionBarrier, PayloadAdmissionBarrierEntered,
    PayloadAdmissionBarrierRelease,
};
// `AnswerSessionPanicArm` is unconditionally public (not cfg-gated, see its own doc
// comment): the daemon-held half of the P0-009 real-panic proof is threaded through
// `session`'s select loop in every build, only the test-only trigger side is gated.
pub use test_support::AnswerSessionPanicArm;

// `IncomingOffer` is reached unconditionally via `super::IncomingOffer` from the
// `session` submodule, which constructs/consumes it while starting a real session
// from a real admitted offer.
pub(crate) use payload::IncomingOffer;

// Everything else here exists only for the crate-root `answer::*` re-export glob
// (gated `#[cfg(test)]` in `lib.rs`) used by unit tests elsewhere in the crate.
// Kept at the same visibility they had before this module was split across files.
#[cfg(test)]
pub(crate) use payload::{
    AnswerDeps, AnswerSessionRegistry, handle_answer_daemon_payload, handle_answer_session_event,
    maybe_replace_pending_answer_session,
};
#[cfg(test)]
pub(crate) use runtime::handle_answer_task_completion;

// Session helpers the daemon unit tests reach through `super::` (via the crate-root
// cfg(test) re-export glob).
#[cfg(test)]
pub(crate) use session::{
    handle_answer_incoming_data_channel, handle_answer_session_message,
    process_answer_session_signal,
};

pub async fn run_answer_daemon(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
) -> Result<(), DaemonError> {
    run_answer_daemon_with_shutdown(config, local_identity, authorized_keys, ShutdownToken::new())
        .await
}

pub async fn run_answer_daemon_with_shutdown(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    shutdown: ShutdownToken,
) -> Result<(), DaemonError> {
    let transport = MqttSignalingTransport::connect(&config)?;
    run_answer_daemon_with_transport_and_shutdown(
        config,
        local_identity,
        authorized_keys,
        transport,
        shutdown,
    )
    .await
}

pub async fn run_answer_daemon_with_transport<T: DaemonSignalingTransport>(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    transport: T,
) -> Result<(), DaemonError> {
    run_answer_daemon_with_transport_and_shutdown(
        config,
        local_identity,
        authorized_keys,
        transport,
        ShutdownToken::new(),
    )
    .await
}

pub async fn run_answer_daemon_with_transport_and_shutdown<T: DaemonSignalingTransport>(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    transport: T,
    shutdown: ShutdownToken,
) -> Result<(), DaemonError> {
    run_answer_daemon_inner(
        config,
        local_identity,
        authorized_keys,
        transport,
        #[cfg(any(test, debug_assertions))]
        None,
        #[cfg(not(any(test, debug_assertions)))]
        None,
        shutdown,
    )
    .await
}

/// Like [`run_answer_daemon_with_transport_and_shutdown`], but also accepts a
/// [`PayloadAdmissionBarrier`] (see [`AnswerDaemonTestHooks`]) so a test can
/// deterministically force the post-payload shutdown-admission race (P0-006).
#[cfg(any(test, debug_assertions))]
pub async fn run_answer_daemon_with_payload_admission_barrier_and_shutdown<
    T: DaemonSignalingTransport,
>(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    transport: T,
    payload_admission_barrier: PayloadAdmissionBarrier,
    shutdown: ShutdownToken,
) -> Result<(), DaemonError> {
    run_answer_daemon_inner(
        config,
        local_identity,
        authorized_keys,
        transport,
        Some(AnswerDaemonTestHooks {
            payload_admission_barrier: Some(payload_admission_barrier),
            session_panic_trigger: None,
        }),
        shutdown,
    )
    .await
}

/// Like [`run_answer_daemon_with_transport_and_shutdown`], but also accepts an
/// [`AnswerSessionPanicArm`] that arms the *next* admitted session's real spawned
/// task to panic on command, so a test can prove the full panic -> `JoinError` ->
/// registry cleanup -> drain -> terminal-status chain end-to-end (P0-009).
#[cfg(any(test, debug_assertions))]
pub async fn run_answer_daemon_with_session_panic_trigger_and_shutdown<
    T: DaemonSignalingTransport,
>(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    transport: T,
    session_panic_trigger: AnswerSessionPanicArm,
    shutdown: ShutdownToken,
) -> Result<(), DaemonError> {
    run_answer_daemon_inner(
        config,
        local_identity,
        authorized_keys,
        transport,
        Some(AnswerDaemonTestHooks {
            payload_admission_barrier: None,
            session_panic_trigger: Some(session_panic_trigger),
        }),
        shutdown,
    )
    .await
}
