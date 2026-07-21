//! Offer-role daemon: binds local listeners, dials the configured remote peer,
//! runs a single multiplexed peer session at a time, and transparently attempts
//! ICE-restart reconnects before returning to the waiting-for-local-client steady
//! state. Startup/security failures are fatal; transport turbulence is recoverable.
//!
//! The module is split across a few files: this one holds only the public entry
//! points (`run_offer_daemon` and its test-hook variants), [`runtime`] holds the
//! core run loop, [`accept`] holds the accept-loop workers, and [`test_support`]
//! holds test-only observation hooks/barriers.

use p2p_core::AppConfig;
use p2p_crypto::{AuthorizedKeys, IdentityFile};
use p2p_signaling::MqttSignalingTransport;
#[cfg(any(test, debug_assertions))]
use tokio::sync::mpsc;

use crate::DaemonError;
use crate::ShutdownToken;
use crate::status::*;
use crate::types::*;

mod accept;
mod cooldown;
mod runtime;
mod session;
mod test_support;
#[cfg(test)]
mod tests;

use runtime::run_offer_daemon_inner;

#[cfg(any(test, debug_assertions))]
use test_support::OfferDaemonTestHooks;
#[cfg(any(test, debug_assertions))]
pub use test_support::{
    OfferAcceptWorkerTestHandle, OfferLoopTopBarrier, OfferLoopTopBarrierEntered,
    OfferLoopTopBarrierRelease, OfferSessionTestEvent, OfferSessionTestHandle,
};

// `OfferAcceptTaskExit` is reached unconditionally via `super::OfferAcceptTaskExit`
// from the `session` submodule (a real session, not just a test seam, observes
// accept-worker exits — see `OfferSessionIo::worker_exits`).
pub(crate) use accept::OfferAcceptTaskExit;

// Everything else here exists only for this module's own `tests` submodule
// (`use super::*`) and the crate-root `offer::*` re-export glob (gated
// `#[cfg(test)]` in `lib.rs`) used by unit tests elsewhere in the crate. Kept at
// the same visibility they had before this module was split across files.
#[cfg(test)]
pub(crate) use accept::{
    OfferAcceptMonitor, bind_offer_listeners, spawn_offer_accept_loop,
    stop_and_join_offer_accept_runtime,
};
#[cfg(test)]
pub(crate) use runtime::merge_offer_run_and_cleanup_results;

// Session helpers the daemon unit tests reach through `super::` (via the crate-root
// cfg(test) re-export glob).
#[cfg(test)]
pub(crate) use session::{
    attempt_offer_reconnect, handle_offer_session_message,
    maybe_ack_duplicate_active_session_message, process_offer_session_payload,
};

pub async fn run_offer_daemon(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
) -> Result<(), DaemonError> {
    run_offer_daemon_with_shutdown(config, local_identity, authorized_keys, ShutdownToken::new())
        .await
}

pub async fn run_offer_daemon_with_shutdown(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    shutdown: ShutdownToken,
) -> Result<(), DaemonError> {
    let transport = MqttSignalingTransport::connect(&config)?;
    run_offer_daemon_with_transport_and_shutdown(
        config,
        local_identity,
        authorized_keys,
        transport,
        shutdown,
    )
    .await
}

pub async fn run_offer_daemon_with_transport<T: DaemonSignalingTransport>(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    transport: T,
) -> Result<(), DaemonError> {
    run_offer_daemon_with_transport_and_shutdown(
        config,
        local_identity,
        authorized_keys,
        transport,
        ShutdownToken::new(),
    )
    .await
}

pub async fn run_offer_daemon_with_transport_and_shutdown<T: DaemonSignalingTransport>(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    transport: T,
    shutdown: ShutdownToken,
) -> Result<(), DaemonError> {
    #[cfg(any(test, debug_assertions))]
    {
        run_offer_daemon_with_transport_and_test_hook_and_shutdown(
            config,
            local_identity,
            authorized_keys,
            transport,
            None,
            shutdown,
        )
        .await
    }

    #[cfg(not(any(test, debug_assertions)))]
    {
        let mut transport = transport;
        run_offer_daemon_inner(
            config,
            local_identity,
            authorized_keys,
            &mut transport,
            None,
            None,
            shutdown,
        )
        .await
    }
}

/// Offer daemon entry point that streams live `DaemonStatus` to `status_sink` in
/// addition to the usual status-file behavior. Used by the Android runtime so the
/// UI reflects real daemon/connection state. Behaves identically to
/// [`run_offer_daemon`] otherwise.
pub async fn run_offer_daemon_with_status(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    status_sink: tokio::sync::watch::Sender<DaemonStatus>,
) -> Result<(), DaemonError> {
    run_offer_daemon_with_status_and_shutdown(
        config,
        local_identity,
        authorized_keys,
        status_sink,
        ShutdownToken::new(),
    )
    .await
}

pub async fn run_offer_daemon_with_status_and_shutdown(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    status_sink: tokio::sync::watch::Sender<DaemonStatus>,
    shutdown: ShutdownToken,
) -> Result<(), DaemonError> {
    let mut transport = MqttSignalingTransport::connect(&config)?;
    run_offer_daemon_inner(
        config,
        local_identity,
        authorized_keys,
        &mut transport,
        None,
        Some(status_sink),
        shutdown,
    )
    .await
}

#[cfg(any(test, debug_assertions))]
pub async fn run_offer_daemon_with_transport_and_test_hook<T: DaemonSignalingTransport>(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    transport: T,
    session_hook: Option<mpsc::UnboundedSender<OfferSessionTestHandle>>,
) -> Result<(), DaemonError> {
    run_offer_daemon_with_transport_and_test_hook_and_shutdown(
        config,
        local_identity,
        authorized_keys,
        transport,
        session_hook,
        ShutdownToken::new(),
    )
    .await
}

/// Combines the session-hook test seam with shutdown cancellation, so lifecycle
/// tests can deterministically observe in-progress session/reconnect state (via
/// `session_hook`) and then trigger shutdown at that observed moment, rather than
/// racing real-time sleeps against the two-node harness.
#[cfg(any(test, debug_assertions))]
pub async fn run_offer_daemon_with_transport_and_test_hook_and_shutdown<
    T: DaemonSignalingTransport,
>(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    mut transport: T,
    session_hook: Option<mpsc::UnboundedSender<OfferSessionTestHandle>>,
    shutdown: ShutdownToken,
) -> Result<(), DaemonError> {
    run_offer_daemon_inner(
        config,
        local_identity,
        authorized_keys,
        &mut transport,
        Some(OfferDaemonTestHooks {
            session_hook,
            worker_fault_hook: None,
            loop_top_barrier: None,
            status_audit: None,
            recovery_barrier: None,
        }),
        None,
        shutdown,
    )
    .await
}

/// Combines a test transport with the [`OfferLoopTopBarrier`] test hook and the
/// live `DaemonStatus` sink, so a test can deterministically force
/// `shutdown.request_shutdown()` to land in the exact window between an ordinary
/// session outcome returning the run loop to its top and the next steady-state
/// write, while observing every status transition to prove none escapes
/// (P0-005/P0-010).
#[cfg(any(test, debug_assertions))]
pub async fn run_offer_daemon_with_loop_top_barrier_and_shutdown<T: DaemonSignalingTransport>(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    mut transport: T,
    loop_top_barrier: OfferLoopTopBarrier,
    status_sink: tokio::sync::watch::Sender<DaemonStatus>,
    shutdown: ShutdownToken,
) -> Result<(), DaemonError> {
    run_offer_daemon_inner(
        config,
        local_identity,
        authorized_keys,
        &mut transport,
        Some(OfferDaemonTestHooks {
            session_hook: None,
            worker_fault_hook: None,
            loop_top_barrier: Some(loop_top_barrier),
            status_audit: None,
            recovery_barrier: None,
        }),
        Some(status_sink),
        shutdown,
    )
    .await
}

/// Combines the [`OfferLoopTopBarrier`] test hook with a [`StatusAuditLog`], so a
/// test can force `shutdown.request_shutdown()` into the exact race window (as
/// with [`run_offer_daemon_with_loop_top_barrier_and_shutdown`]) while recording
/// every status write attempt without `watch`-channel coalescing — the only
/// trustworthy way to prove no illegal intermediate state was ever emitted after
/// the shutdown boundary (P0-002).
#[cfg(any(test, debug_assertions))]
pub async fn run_offer_daemon_with_loop_top_barrier_and_status_audit_and_shutdown<
    T: DaemonSignalingTransport,
>(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    mut transport: T,
    loop_top_barrier: OfferLoopTopBarrier,
    status_audit: StatusAuditLog,
    shutdown: ShutdownToken,
) -> Result<(), DaemonError> {
    run_offer_daemon_inner(
        config,
        local_identity,
        authorized_keys,
        &mut transport,
        Some(OfferDaemonTestHooks {
            session_hook: None,
            worker_fault_hook: None,
            loop_top_barrier: Some(loop_top_barrier),
            status_audit: Some(status_audit),
            recovery_barrier: None,
        }),
        None,
        shutdown,
    )
    .await
}

/// Combines a [`StatusAuditLog`] with a barrier immediately before
/// `recover_daemon_after_session` on an ordinary session outcome, so a test can
/// force `shutdown.request_shutdown()` into a window the local loop-top shutdown
/// check does *not* re-guard — isolating the central `runtime_status_allowed`
/// token-aware gate as the only defense against a stale ordinary status write
/// (P0-005). Contrast with [`run_offer_daemon_with_loop_top_barrier_and_status_audit_and_shutdown`],
/// whose barrier sits at a point the local loop-top check *does* also protect.
#[cfg(any(test, debug_assertions))]
pub async fn run_offer_daemon_with_recovery_barrier_and_status_audit_and_shutdown<
    T: DaemonSignalingTransport,
>(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    mut transport: T,
    recovery_barrier: OfferLoopTopBarrier,
    status_audit: StatusAuditLog,
    shutdown: ShutdownToken,
) -> Result<(), DaemonError> {
    run_offer_daemon_inner(
        config,
        local_identity,
        authorized_keys,
        &mut transport,
        Some(OfferDaemonTestHooks {
            session_hook: None,
            worker_fault_hook: None,
            loop_top_barrier: None,
            status_audit: Some(status_audit),
            recovery_barrier: Some(recovery_barrier),
        }),
        None,
        shutdown,
    )
    .await
}

/// Combines a test transport with the live `DaemonStatus` sink (see
/// [`run_offer_daemon_with_status_and_shutdown`]), so a lifecycle test can observe
/// every status transition — not just periodic file-poll samples — against an
/// in-memory transport instead of a real broker connection (P0-010).
#[cfg(any(test, debug_assertions))]
pub async fn run_offer_daemon_with_transport_and_status_and_shutdown<
    T: DaemonSignalingTransport,
>(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    mut transport: T,
    status_sink: tokio::sync::watch::Sender<DaemonStatus>,
    shutdown: ShutdownToken,
) -> Result<(), DaemonError> {
    run_offer_daemon_inner(
        config,
        local_identity,
        authorized_keys,
        &mut transport,
        None,
        Some(status_sink),
        shutdown,
    )
    .await
}

/// Like [`run_offer_daemon_with_transport_and_test_hook_and_shutdown`], but also
/// hands back the accept-worker `AbortHandle`s (via `worker_fault_hook`) once the
/// accept runtime has started, so a lifecycle test can deterministically force one
/// worker to fail — during idle waiting or mid-session — and observe that the
/// daemon treats it as fatal (see P0-003/P0-016).
#[cfg(any(test, debug_assertions))]
pub async fn run_offer_daemon_with_worker_fault_hook_and_shutdown<T: DaemonSignalingTransport>(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    mut transport: T,
    worker_fault_hook: mpsc::UnboundedSender<Vec<OfferAcceptWorkerTestHandle>>,
    shutdown: ShutdownToken,
) -> Result<(), DaemonError> {
    run_offer_daemon_inner(
        config,
        local_identity,
        authorized_keys,
        &mut transport,
        Some(OfferDaemonTestHooks {
            session_hook: None,
            worker_fault_hook: Some(worker_fault_hook),
            loop_top_barrier: None,
            status_audit: None,
            recovery_barrier: None,
        }),
        None,
        shutdown,
    )
    .await
}
