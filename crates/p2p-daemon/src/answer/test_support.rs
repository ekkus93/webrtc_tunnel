//! Test-only observation hooks and rendezvous barriers for the answer daemon, split
//! out of `answer::mod` so the production entry points/run loop stay readable.
//! Everything using `mpsc` here is `test`/`debug_assertions`-gated, so a plain
//! release build (e.g. the Android NDK release build) must not require it either.

#[cfg(any(test, debug_assertions))]
use tokio::sync::mpsc;

/// Bundles the answer daemon's test-only observation/injection hooks so
/// `run_answer_daemon_inner` stays under Clippy's argument-count lint as test seams
/// accumulate (mirrors `OfferDaemonTestHooks` on the offer side).
#[cfg(any(test, debug_assertions))]
#[derive(Default)]
pub(crate) struct AnswerDaemonTestHooks {
    /// Fires once, right after a payload is confirmed present but before the
    /// post-payload shutdown admission check, so a test can deterministically
    /// force `shutdown.request_shutdown()` to land in that exact window instead
    /// of racing real time against the scheduler (P0-006).
    pub(crate) payload_admission_barrier: Option<PayloadAdmissionBarrier>,
    /// Armed onto the next admitted session's spawned task, letting a test make a
    /// *real* `run_answer_session_task` panic on command so the full
    /// panic -> `JoinError` -> registry cleanup -> drain -> terminal-status chain
    /// can be proven end-to-end instead of fabricating an `AnswerTaskCompletion`
    /// directly (P0-009).
    pub(crate) session_panic_trigger: Option<AnswerSessionPanicArm>,
}

/// A repeatable rendezvous: the daemon loop calls
/// [`PayloadAdmissionBarrier::enter_and_wait_for_release`] every time a payload is
/// ready (mirroring the production gate, which checks shutdown for every payload,
/// not just offers), blocking until the test observes entry (via
/// [`PayloadAdmissionBarrierEntered::wait`]) and explicitly releases it (via
/// [`PayloadAdmissionBarrierRelease::release`]). It must fire more than once per test:
/// an incoming offer is preceded by an unrelated Hello payload, so the test needs to
/// let that first payload through untouched and only force the race on the second.
/// A broken channel on either side is a test-harness bug, not something to continue
/// past silently — see P1-004.
#[cfg(any(test, debug_assertions))]
pub struct PayloadAdmissionBarrier {
    entered_tx: mpsc::Sender<()>,
    release_rx: mpsc::Receiver<()>,
}

#[cfg(any(test, debug_assertions))]
impl PayloadAdmissionBarrier {
    pub fn new() -> (Self, PayloadAdmissionBarrierEntered, PayloadAdmissionBarrierRelease) {
        let (entered_tx, entered_rx) = mpsc::channel(1);
        let (release_tx, release_rx) = mpsc::channel(1);
        (
            Self { entered_tx, release_rx },
            PayloadAdmissionBarrierEntered { entered_rx },
            PayloadAdmissionBarrierRelease { release_tx },
        )
    }

    pub(crate) async fn enter_and_wait_for_release(&mut self) {
        self.entered_tx
            .send(())
            .await
            .expect("payload admission barrier observer must remain alive");
        self.release_rx
            .recv()
            .await
            .expect("payload admission barrier release sender must remain alive");
    }
}

#[cfg(any(test, debug_assertions))]
pub struct PayloadAdmissionBarrierEntered {
    entered_rx: mpsc::Receiver<()>,
}

#[cfg(any(test, debug_assertions))]
impl PayloadAdmissionBarrierEntered {
    pub async fn wait(&mut self) {
        self.entered_rx
            .recv()
            .await
            .expect("payload admission barrier must not be dropped before entering");
    }
}

#[cfg(any(test, debug_assertions))]
pub struct PayloadAdmissionBarrierRelease {
    release_tx: mpsc::Sender<()>,
}

#[cfg(any(test, debug_assertions))]
impl PayloadAdmissionBarrierRelease {
    pub async fn release(&self) {
        self.release_tx
            .send(())
            .await
            .expect("payload admission barrier observer must remain alive");
    }
}

/// Test-held half of the P0-009 real-panic proof: call [`Self::fire`] to make the
/// session that armed the matching [`AnswerSessionPanicArm`] panic inside its own
/// real, spawned `run_answer_session_task`, at the next opportunity in its select
/// loop. A broken channel is a test-harness bug, not something to continue past
/// silently — see P1-004.
#[cfg(any(test, debug_assertions))]
pub struct AnswerSessionPanicTrigger {
    fire_tx: tokio::sync::oneshot::Sender<()>,
}

#[cfg(any(test, debug_assertions))]
impl AnswerSessionPanicTrigger {
    pub fn new() -> (Self, AnswerSessionPanicArm) {
        let (fire_tx, fire_rx) = tokio::sync::oneshot::channel();
        (Self { fire_tx }, AnswerSessionPanicArm { fire_rx })
    }

    pub fn fire(self) {
        self.fire_tx.send(()).expect("answer session panic arm must remain alive");
    }
}

/// Daemon-held half of the P0-009 real-panic proof; see [`AnswerSessionPanicTrigger`].
///
/// Unlike its trigger counterpart, this is NOT `#[cfg]`-gated: `tokio::select!`
/// does not support per-branch `cfg` attributes, so the session task's select loop
/// carries the branch that reads this unconditionally, disabled via
/// `Option::is_some()` the same way the existing `bridge_result` branch is — dead
/// weight in release builds, not a functional difference. Only the trigger side
/// (`AnswerSessionPanicTrigger`, constructed solely by tests) needs to be gated to
/// avoid an unreachable-in-release dead-code warning.
pub struct AnswerSessionPanicArm {
    pub(crate) fire_rx: tokio::sync::oneshot::Receiver<()>,
}
