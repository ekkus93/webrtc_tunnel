//! Offer accept-loop workers: binds local listeners, spawns one accept-loop task
//! per forward plus an independent completion monitor, and owns deterministic
//! stop/join semantics so listener-port release and worker-death detection never
//! depend on a discarded `JoinHandle`.

use p2p_core::{AppConfig, ConfigError, ForwardOfferConfig, ForwardTable};
use p2p_tunnel::{OfferClient, OfferListener};
use tokio::sync::mpsc;
use tokio::time::sleep;

use crate::DaemonError;
use crate::ShutdownToken;
use crate::status::*;
use crate::types::*;

#[cfg(any(test, debug_assertions))]
use super::test_support::OfferAcceptWorkerTestHandle;

/// Owns every offer accept-loop task handle alongside the receiver they feed, so
/// shutdown can stop and join them deterministically instead of discarding the
/// `JoinHandle`s (which made listener-port release non-deterministic).
pub(crate) struct OfferAcceptRuntime {
    pub(crate) accepted_clients: mpsc::Receiver<Result<OfferClient, p2p_tunnel::TunnelError>>,
    /// Independently observed accept-worker completion, fed by `monitors` below.
    /// After successful bind/start, an unexpected exit here (panic or unexpected
    /// return while the daemon has not requested shutdown) is daemon-fatal: the
    /// worker holding a bound listener died silently, but nothing else would ever
    /// notice, leaving status falsely `Listening`/`WaitingForLocalClient`.
    pub(crate) worker_exits: mpsc::UnboundedReceiver<OfferAcceptTaskExit>,
    pub(crate) monitors: Vec<OfferAcceptMonitor>,
}

/// One accept-loop task's monitor, identified by forward id so an unexpected join
/// failure during cleanup can name which forward's worker it was.
pub(crate) struct OfferAcceptMonitor {
    pub(crate) forward_id: String,
    pub(crate) handle: tokio::task::JoinHandle<()>,
}

/// Why an offer accept-loop task returned normally (i.e. did not panic).
#[derive(Debug)]
pub(crate) enum OfferAcceptLoopExitReason {
    /// Cooperative shutdown was observed; expected during daemon teardown.
    Shutdown,
    /// The outbound client queue's receiver was dropped; not expected while the
    /// daemon owns `accepted_clients` for the runtime's lifetime.
    ClientQueueClosed,
}

/// One accept-loop task's completion, independently observed by its monitor task
/// rather than self-reported, so a panic is never silently invisible.
#[derive(Debug)]
pub(crate) struct OfferAcceptTaskExit {
    pub(crate) forward_id: String,
    pub(crate) outcome: Result<OfferAcceptLoopExitReason, String>,
}

/// Await every offer accept-loop monitor task. An unexpected `JoinError` (panic —
/// the monitor tasks themselves never return early any other way) is a cleanup
/// error, not a warning-and-succeed: silently swallowing it would mean a lost
/// listener task is never actually reported anywhere. The first such failure is
/// returned; any further ones are logged as secondary context.
pub(crate) async fn stop_and_join_offer_accept_runtime(
    monitors: Vec<OfferAcceptMonitor>,
) -> Result<(), DaemonError> {
    let mut primary_cleanup_error: Option<DaemonError> = None;

    for monitor in monitors {
        if let Err(error) = monitor.handle.await {
            let failure = DaemonError::OfferAcceptMonitorJoinFailed {
                forward_id: monitor.forward_id,
                reason: error.to_string(),
            };

            if primary_cleanup_error.is_none() {
                primary_cleanup_error = Some(failure);
            } else {
                tracing::error!(reason = %failure, "additional offer monitor cleanup failure");
            }
        }
    }

    match primary_cleanup_error {
        Some(error) => Err(error),
        None => Ok(()),
    }
}

#[cfg(test)]
pub(crate) fn spawn_offer_accept_loop(
    listener: OfferListener,
) -> mpsc::Receiver<Result<OfferClient, p2p_tunnel::TunnelError>> {
    spawn_offer_accept_loops(vec![listener], ShutdownToken::new()).0.accepted_clients
}

/// Bind a local TCP listener for each configured offer forward. Individual forwards
/// that fail to bind are recorded as `Error` (soft-fail) so one bad forward does not
/// take down the others; the per-forward outcomes are returned alongside the bound
/// listeners. It is still a daemon-level error if forwards are configured but none
/// could bind.
pub(crate) async fn bind_offer_listeners(
    config: &AppConfig,
) -> Result<(Vec<OfferListener>, Vec<ForwardRuntimeStatus>), DaemonError> {
    let table = ForwardTable::new(&config.forwards);
    let mut listeners = Vec::new();
    let mut statuses = Vec::new();
    for bind in table.offer_listeners().map_err(|error| {
        DaemonError::Config(ConfigError::InvalidConfig(format!(
            "invalid offer forward listeners: {error:?}"
        )))
    })? {
        let forward_id = bind.forward_id.to_string();
        let offer =
            ForwardOfferConfig { listen_host: bind.listen_host, listen_port: bind.listen_port };
        match OfferListener::bind(bind.forward_id, &offer).await {
            Ok(listener) => {
                tracing::info!(
                    forward_id = listener.forward_id(),
                    local_addr = %listener.local_addr()?,
                    "listening for local forward clients"
                );
                statuses.push(ForwardRuntimeStatus::listening(forward_id));
                listeners.push(listener);
            }
            Err(error) => {
                tracing::warn!(
                    forward_id = %forward_id,
                    reason = %error,
                    "failed to bind local forward listener; marking forward as error"
                );
                statuses.push(ForwardRuntimeStatus::error(forward_id, error.to_string()));
            }
        }
    }
    if !statuses.is_empty() && listeners.is_empty() {
        return Err(DaemonError::Config(ConfigError::InvalidConfig(
            "no offer forward listeners could be bound".to_owned(),
        )));
    }
    Ok((listeners, statuses))
}

/// One accept listener's loop: forwards accepted clients into `tx`, retrying past
/// recoverable listener errors, until shutdown is observed or the receiving end of
/// `tx` disappears. Returns (rather than silently exits) so its monitor task can
/// report completion independently — see [`OfferAcceptRuntime::worker_exits`].
async fn run_offer_accept_loop(
    listener: OfferListener,
    tx: mpsc::Sender<Result<OfferClient, p2p_tunnel::TunnelError>>,
    mut shutdown: ShutdownToken,
) -> OfferAcceptLoopExitReason {
    loop {
        tokio::select! {
            _ = shutdown.cancelled() => {
                tracing::debug!(
                    forward_id = listener.forward_id(),
                    "offer accept loop stopping"
                );
                return OfferAcceptLoopExitReason::Shutdown;
            }
            accepted = listener.accept_client() => {
                match accepted {
                    Ok(accepted) => match tx.try_send(Ok(accepted)) {
                        Ok(()) => {}
                        Err(mpsc::error::TrySendError::Full(Ok(dropped))) => {
                            tracing::warn!(
                                forward_id = dropped.forward_id(),
                                "offer pending client queue is full; closing local client"
                            );
                        }
                        Err(mpsc::error::TrySendError::Closed(_)) => {
                            return OfferAcceptLoopExitReason::ClientQueueClosed;
                        }
                        Err(mpsc::error::TrySendError::Full(Err(_))) => {}
                    },
                    Err(error) => {
                        tracing::warn!(reason = %error, "offer accept loop hit recoverable listener error");
                        tokio::select! {
                            _ = shutdown.cancelled() => return OfferAcceptLoopExitReason::Shutdown,
                            _ = sleep(DAEMON_RUNTIME_RETRY_DELAY) => {}
                        }
                    }
                }
            }
        }
    }
}

/// Spawns the accept-loop workers and their independent completion monitors.
/// In test/debug builds also returns each worker's [`OfferAcceptWorkerTestHandle`]
/// (its forward ID paired with an `AbortHandle` decoupled from the `JoinHandle`
/// the monitor awaits) so test hooks can deterministically force one worker to
/// fail — aborting a task and a genuine panic are indistinguishable to the
/// monitor, both surface as `Err` on `worker.await`, so this exercises the exact
/// same fatal-supervision path. Release builds have no consumer for that
/// bookkeeping, so they skip it entirely (a plain `Vec<AbortHandle>`) rather than
/// building `OfferAcceptWorkerTestHandle`s whose fields would then go unread.
#[cfg(any(test, debug_assertions))]
pub(crate) fn spawn_offer_accept_loops(
    listeners: Vec<OfferListener>,
    shutdown: ShutdownToken,
) -> (OfferAcceptRuntime, Vec<OfferAcceptWorkerTestHandle>) {
    let (tx, rx) = mpsc::channel(64);
    let (exit_tx, exit_rx) = mpsc::unbounded_channel();
    let mut monitors = Vec::with_capacity(listeners.len());
    let mut worker_test_handles = Vec::with_capacity(listeners.len());
    for listener in listeners {
        let forward_id = listener.forward_id().to_owned();
        let monitor_forward_id = forward_id.clone();
        let tx = tx.clone();
        let task_shutdown = shutdown.clone();
        let exit_tx = exit_tx.clone();
        let worker = tokio::spawn(run_offer_accept_loop(listener, tx, task_shutdown));
        worker_test_handles.push(OfferAcceptWorkerTestHandle {
            forward_id: forward_id.clone(),
            abort_handle: worker.abort_handle(),
        });
        let handle = tokio::spawn(async move {
            let outcome = match worker.await {
                Ok(reason) => Ok(reason),
                Err(error) => Err(error.to_string()),
            };
            if exit_tx
                .send(OfferAcceptTaskExit { forward_id: forward_id.clone(), outcome })
                .is_err()
            {
                tracing::error!(
                    forward_id = %forward_id,
                    "offer accept worker exit could not be delivered to supervisor",
                );
            }
        });
        monitors.push(OfferAcceptMonitor { forward_id: monitor_forward_id, handle });
    }
    drop(tx);
    drop(exit_tx);
    (
        OfferAcceptRuntime { accepted_clients: rx, worker_exits: exit_rx, monitors },
        worker_test_handles,
    )
}

#[cfg(not(any(test, debug_assertions)))]
pub(crate) fn spawn_offer_accept_loops(
    listeners: Vec<OfferListener>,
    shutdown: ShutdownToken,
) -> (OfferAcceptRuntime, Vec<tokio::task::AbortHandle>) {
    let (tx, rx) = mpsc::channel(64);
    let (exit_tx, exit_rx) = mpsc::unbounded_channel();
    let mut monitors = Vec::with_capacity(listeners.len());
    let mut abort_handles = Vec::with_capacity(listeners.len());
    for listener in listeners {
        let forward_id = listener.forward_id().to_owned();
        let monitor_forward_id = forward_id.clone();
        let tx = tx.clone();
        let task_shutdown = shutdown.clone();
        let exit_tx = exit_tx.clone();
        let worker = tokio::spawn(run_offer_accept_loop(listener, tx, task_shutdown));
        abort_handles.push(worker.abort_handle());
        let handle = tokio::spawn(async move {
            let outcome = match worker.await {
                Ok(reason) => Ok(reason),
                Err(error) => Err(error.to_string()),
            };
            if exit_tx
                .send(OfferAcceptTaskExit { forward_id: forward_id.clone(), outcome })
                .is_err()
            {
                tracing::error!(
                    forward_id = %forward_id,
                    "offer accept worker exit could not be delivered to supervisor",
                );
            }
        });
        monitors.push(OfferAcceptMonitor { forward_id: monitor_forward_id, handle });
    }
    drop(tx);
    drop(exit_tx);
    (OfferAcceptRuntime { accepted_clients: rx, worker_exits: exit_rx, monitors }, abort_handles)
}
