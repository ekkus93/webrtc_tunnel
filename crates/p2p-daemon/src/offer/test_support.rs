//! Test-only observation hooks and rendezvous barriers for the offer daemon, split
//! out of `offer::mod` so the production entry points/run loop stay readable.
//! Nothing here is used by a non-test/non-debug-assertions production build.

// Everything in this file is only used when `test` or `debug_assertions` is set — a
// plain release build (e.g. the Android NDK release build) has neither, so these
// imports must not require any cfg-gated item to unconditionally exist.
#[cfg(any(test, debug_assertions))]
use tokio::sync::mpsc;

#[cfg(any(test, debug_assertions))]
use crate::status::StatusAuditLog;

#[cfg(any(test, debug_assertions))]
pub struct OfferSessionTestHandle {
    pub session_id: p2p_core::SessionId,
    pub ice_state_injector: p2p_webrtc::IceStateInjectorForTests,
    /// Deterministic lifecycle events (currently: reconnect/backoff transitions),
    /// so tests can observe the actual state instead of guessing it with a sleep.
    pub test_events: mpsc::UnboundedReceiver<OfferSessionTestEvent>,
}

/// Deterministic offer-session lifecycle events, observed by tests instead of a
/// timing sleep.
#[cfg(any(test, debug_assertions))]
#[derive(Clone, Debug)]
pub enum OfferSessionTestEvent {
    ReconnectBackoffStarted { session_id: p2p_core::SessionId, delay: std::time::Duration },
}

/// Identifies an accept worker's [`tokio::task::AbortHandle`] by its forward ID
/// (rather than by its position in a `Vec`), so a test with more than one
/// forward can select the worker it means to fault without depending on
/// listener/spawn order (P1-002).
#[cfg(any(test, debug_assertions))]
#[derive(Debug)]
pub struct OfferAcceptWorkerTestHandle {
    pub forward_id: String,
    pub abort_handle: tokio::task::AbortHandle,
}

/// Bundles the offer daemon's test-only observation hooks so `run_offer_daemon_inner`
/// stays under Clippy's argument-count lint as test seams accumulate.
#[cfg(any(test, debug_assertions))]
#[derive(Default)]
pub(crate) struct OfferDaemonTestHooks {
    pub(crate) session_hook: Option<mpsc::UnboundedSender<OfferSessionTestHandle>>,
    pub(crate) worker_fault_hook: Option<mpsc::UnboundedSender<Vec<OfferAcceptWorkerTestHandle>>>,
    /// Fires at the very top of the run loop, before the P0-005 shutdown gate and
    /// the ordinary steady-state write, every iteration (not just once) — an
    /// ordinary session outcome can bring the loop back to top more than once
    /// before a test gets a chance to land shutdown in the gap.
    pub(crate) loop_top_barrier: Option<OfferLoopTopBarrier>,
    /// Non-coalescing audit recorder (see [`StatusAuditLog`]) attached to the
    /// daemon's `StatusWriter` when present, so a test can prove an exact
    /// shutdown boundary instead of relying on a `watch` stream that can
    /// coalesce away an illegal intermediate write (P0-002).
    pub(crate) status_audit: Option<StatusAuditLog>,
    /// Fires immediately before `recover_daemon_after_session` on an ordinary
    /// (non-infrastructure) session outcome — a point the local loop-top shutdown
    /// check does *not* re-guard, unlike `loop_top_barrier`'s position. Lets a test
    /// isolate the central `runtime_status_allowed` token-aware gate as the *only*
    /// defense against a stale ordinary status write (P0-005).
    pub(crate) recovery_barrier: Option<OfferLoopTopBarrier>,
}

/// A repeatable rendezvous at the top of the offer run loop (see
/// [`OfferDaemonTestHooks::loop_top_barrier`]), letting a test force
/// `shutdown.request_shutdown()` to land in the exact window between an ordinary
/// session outcome bringing the loop back to its top and the next steady-state
/// write, instead of racing real scheduler timing (P0-005/P0-010). A broken
/// channel on either side is a test-harness bug, not something to continue past
/// silently — see P1-004.
#[cfg(any(test, debug_assertions))]
pub struct OfferLoopTopBarrier {
    entered_tx: mpsc::Sender<()>,
    release_rx: mpsc::Receiver<()>,
}

#[cfg(any(test, debug_assertions))]
impl OfferLoopTopBarrier {
    pub fn new() -> (Self, OfferLoopTopBarrierEntered, OfferLoopTopBarrierRelease) {
        let (entered_tx, entered_rx) = mpsc::channel(1);
        let (release_tx, release_rx) = mpsc::channel(1);
        (
            Self { entered_tx, release_rx },
            OfferLoopTopBarrierEntered { entered_rx },
            OfferLoopTopBarrierRelease { release_tx },
        )
    }

    pub(crate) async fn enter_and_wait_for_release(&mut self) {
        self.entered_tx.send(()).await.expect("offer loop-top barrier observer must remain alive");
        self.release_rx
            .recv()
            .await
            .expect("offer loop-top barrier release sender must remain alive");
    }
}

#[cfg(any(test, debug_assertions))]
pub struct OfferLoopTopBarrierEntered {
    entered_rx: mpsc::Receiver<()>,
}

#[cfg(any(test, debug_assertions))]
impl OfferLoopTopBarrierEntered {
    pub async fn wait(&mut self) {
        self.entered_rx
            .recv()
            .await
            .expect("offer loop-top barrier must not be dropped before entering");
    }
}

#[cfg(any(test, debug_assertions))]
pub struct OfferLoopTopBarrierRelease {
    release_tx: mpsc::Sender<()>,
}

#[cfg(any(test, debug_assertions))]
impl OfferLoopTopBarrierRelease {
    pub async fn release(&self) {
        self.release_tx.send(()).await.expect("offer loop-top barrier observer must remain alive");
    }
}
