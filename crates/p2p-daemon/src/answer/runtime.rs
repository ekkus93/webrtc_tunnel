//! The answer daemon's core run loop: startup (config/peer validation, status
//! init), the steady-state select loop (shutdown, session-task completions, idle
//! signaling payloads, session events), and completion/drain handling shared by
//! every fatal exit path.

use std::collections::HashMap;
use std::sync::Arc;

use futures_util::StreamExt;
use p2p_core::{AppConfig, PeerId, SessionId};
use p2p_crypto::{AuthorizedKeys, IdentityFile};
use p2p_signaling::SignalCodec;
use tokio::sync::mpsc;

use crate::DaemonError;
use crate::ShutdownToken;
use crate::config::*;
use crate::signaling::*;
use crate::status::*;
use crate::types::*;

use super::payload::{
    AnswerDeps, AnswerSessionRegistry, handle_answer_daemon_payload, handle_answer_session_event,
};

#[cfg(any(test, debug_assertions))]
use super::test_support::AnswerDaemonTestHooks;
// Only named explicitly in the release (neither test nor debug_assertions) branch
// below — gated to match, so a plain release build doesn't need `test_support`'s
// other, cfg-gated items to exist just to resolve this one unconditional name.
#[cfg(not(any(test, debug_assertions)))]
use super::test_support::AnswerSessionPanicArm;

pub(super) async fn run_answer_daemon_inner<T: DaemonSignalingTransport>(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    mut transport: T,
    #[cfg(any(test, debug_assertions))] test_hooks: Option<AnswerDaemonTestHooks>,
    #[cfg(not(any(test, debug_assertions)))] _test_hooks: Option<()>,
    mut shutdown: ShutdownToken,
) -> Result<(), DaemonError> {
    #[cfg(any(test, debug_assertions))]
    let AnswerDaemonTestHooks { mut payload_admission_barrier, session_panic_trigger } =
        test_hooks.unwrap_or_default();
    // `session_panic_trigger` is threaded through `AnswerSessionRegistry` and
    // `run_answer_session_task` unconditionally (tokio::select! does not support
    // per-branch cfg attributes, so that branch is always compiled), so the
    // binding providing it must always exist too, not just under test/debug_assertions.
    #[cfg(any(test, debug_assertions))]
    let mut session_panic_trigger = session_panic_trigger;
    #[cfg(not(any(test, debug_assertions)))]
    let mut session_panic_trigger: Option<AnswerSessionPanicArm> = None;
    validate_config_authorized_peers(&config, &authorized_keys)?;
    let config = Arc::new(config);
    let local_identity = Arc::new(local_identity);
    let authorized_keys = Arc::new(authorized_keys);
    let codec = SignalCodec::new(
        &local_identity,
        &authorized_keys,
        config.security.max_clock_skew_secs,
        config.security.max_message_age_secs,
    );
    transport.subscribe_own_topic().await?;
    let status = StatusWriter::new(&config);
    let mut runtime = DaemonRuntimeState::new_connected_with_shutdown(shutdown.clone());
    let mut ctx = RuntimeContext { config: &config, status: &status, runtime: &mut runtime };
    let (event_tx, mut event_rx) = mpsc::channel(128);
    let mut sessions_by_id: HashMap<SessionId, AnswerSessionHandle> = HashMap::new();
    let mut session_by_peer: HashMap<PeerId, SessionId> = HashMap::new();
    let mut session_completions: AnswerSessionCompletions =
        futures_util::stream::FuturesUnordered::new();
    let mut next_generation = 1_u64;

    // Startup is only truthfully complete once the broker is subscribed and the
    // required remote peers are authorized (both already validated above); only
    // past this point may ordinary status writes report Serving.
    ctx.runtime.phase = DaemonRuntimePhase::Running;
    write_answer_registry_status(&ctx, &sessions_by_id).await;

    let mut replay_cache = p2p_signaling::ReplayCache::new(config.security.replay_cache_size);
    let mut shutting_down = false;
    let mut primary_error: Option<DaemonError> = None;

    loop {
        if shutting_down && sessions_by_id.is_empty() {
            break;
        }

        tokio::select! {
            biased;

            _ = shutdown.cancelled(), if !shutting_down => {
                tracing::info!(
                    active_session_count = sessions_by_id.len(),
                    "answer daemon shutdown requested; draining active sessions"
                );
                shutting_down = true;
                begin_answer_drain(&mut ctx, &shutdown, &mut primary_error, None);
            }

            completion = session_completions.next(), if !session_completions.is_empty() => {
                let completion = completion.expect("guarded by is_empty");
                handle_answer_task_completion(
                    &mut ctx,
                    &mut sessions_by_id,
                    &mut session_by_peer,
                    completion,
                    &shutdown,
                    &mut primary_error,
                )
                .await;
                if !shutting_down && shutdown.is_shutdown_requested() {
                    shutting_down = true;
                }
            }

            payload = poll_idle_signal_payload(&mut ctx, &mut transport), if !shutting_down => {
                let Some(payload) = payload else {
                    continue;
                };

                #[cfg(any(test, debug_assertions))]
                if let Some(barrier) = payload_admission_barrier.as_mut() {
                    barrier.enter_and_wait_for_release().await;
                }

                if shutdown.is_shutdown_requested() {
                    shutting_down = true;
                    begin_answer_drain(&mut ctx, &shutdown, &mut primary_error, None);
                    continue;
                }

                handle_answer_daemon_payload(
                    &AnswerDeps {
                        config: &config,
                        local_identity: &local_identity,
                        authorized_keys: &authorized_keys,
                        event_tx: &event_tx,
                        shutdown: &shutdown,
                    },
                    &codec,
                    &mut transport,
                    &mut ctx,
                    &mut AnswerSessionRegistry {
                        replay_cache: &mut replay_cache,
                        sessions_by_id: &mut sessions_by_id,
                        session_by_peer: &mut session_by_peer,
                        session_completions: &mut session_completions,
                        next_generation: &mut next_generation,
                        session_panic_trigger: &mut session_panic_trigger,
                    },
                    payload,
                )
                .await;
            }

            event = event_rx.recv() => {
                let Some(event) = event else {
                    // Fatal, but must not bypass drain: enter Draining, request
                    // shutdown, and keep consuming task completions below until the
                    // registry empties, so in-flight sessions still unwind cleanly.
                    tracing::error!("answer session event channel closed unexpectedly");
                    shutting_down = true;
                    begin_answer_drain(
                        &mut ctx,
                        &shutdown,
                        &mut primary_error,
                        Some(DaemonError::Logging(
                            "answer session event channel closed".to_owned(),
                        )),
                    );
                    continue;
                };
                handle_answer_session_event(
                    &mut ctx,
                    &codec,
                    &mut transport,
                    &mut sessions_by_id,
                    &mut session_by_peer,
                    event,
                )
                .await;
            }
        }
    }

    ctx.runtime.phase = DaemonRuntimePhase::Closed;
    let closed_result = write_answer_closed_status(&mut ctx).await;

    match primary_error {
        Some(error) => {
            if let Err(close_error) = closed_result {
                tracing::error!(reason = %close_error, "answer terminal status also failed");
            }
            Err(error)
        }
        None => closed_result,
    }
}

/// Enters `Draining`, requests cooperative shutdown, and records `error` as the
/// primary daemon failure — unless a primary error is already set, in which case
/// `error` is logged as an additional (secondary) failure during drain. Used by
/// every fatal answer-daemon path (event-channel closure, session task panic) so
/// none of them can bypass drain/finalization by returning directly.
pub(crate) fn begin_answer_drain(
    ctx: &mut RuntimeContext<'_>,
    shutdown: &ShutdownToken,
    primary_error: &mut Option<DaemonError>,
    error: Option<DaemonError>,
) {
    ctx.runtime.phase = DaemonRuntimePhase::Draining;
    if primary_error.is_none() {
        *primary_error = error;
    } else if let Some(error) = error {
        tracing::error!(reason = %error, "additional answer daemon failure during drain");
    }
    shutdown.request_shutdown();
}

/// Locates a session's current registry key by its stable identity (generation +
/// remote peer), not by session id — a same-peer pending-session replacement can
/// change the map key out from under an in-flight task.
fn find_session_id_by_generation_and_peer(
    sessions: &HashMap<SessionId, AnswerSessionHandle>,
    generation: SessionGeneration,
    remote_peer_id: &PeerId,
) -> Option<SessionId> {
    sessions.iter().find_map(|(session_id, handle)| {
        (handle.generation == generation && &handle.remote_peer_id == remote_peer_id)
            .then_some(*session_id)
    })
}

/// Resolves a completed task's current registry key: `candidate_session_id` (the
/// task's own final/initial session id) is tried first as a fast path, but is only
/// trusted if the registry entry at that key still has the *same* generation and
/// remote peer — otherwise a same-peer replacement raced ahead of this completion
/// (its `Replaced` event may still be queued, unprocessed) and the entry now lives
/// under a different key. Falling back to the stable generation+peer search finds
/// it regardless of which key it currently sits under.
fn resolve_completion_registry_session_id(
    sessions: &HashMap<SessionId, AnswerSessionHandle>,
    candidate_session_id: SessionId,
    generation: SessionGeneration,
    remote_peer_id: &PeerId,
) -> Option<SessionId> {
    if sessions.get(&candidate_session_id).is_some_and(|handle| {
        handle.generation == generation && &handle.remote_peer_id == remote_peer_id
    }) {
        return Some(candidate_session_id);
    }

    find_session_id_by_generation_and_peer(sessions, generation, remote_peer_id)
}

/// Handles one independently-observed answer session task completion (normal end,
/// remote/ICE failure, or — critically — a panic/join failure that the task never
/// self-reported). A panic can no longer strand the registry entry: this removes
/// it by stable identity and, on panic, enters drain via [`begin_answer_drain`]
/// instead of leaving the daemon silently unaware that a session vanished.
pub(crate) async fn handle_answer_task_completion(
    ctx: &mut RuntimeContext<'_>,
    sessions_by_id: &mut HashMap<SessionId, AnswerSessionHandle>,
    session_by_peer: &mut HashMap<PeerId, SessionId>,
    completion: AnswerTaskCompletion,
    shutdown: &ShutdownToken,
    primary_error: &mut Option<DaemonError>,
) {
    let AnswerTaskCompletion { initial_session_id, generation, remote_peer_id, outcome } =
        completion;

    // Both arms use the same stable-identity resolution: the task's own reported
    // session id (final on success, initial on join failure) is only a fast-path
    // hint, never trusted blindly, because a same-peer replacement can change the
    // registry key out from under an in-flight task before its completion is
    // observed here (see `resolve_completion_registry_session_id`).
    let candidate_session_id = match &outcome {
        Ok(result) => result.final_session_id,
        Err(_) => initial_session_id,
    };
    let lookup_session_id = resolve_completion_registry_session_id(
        sessions_by_id,
        candidate_session_id,
        generation,
        &remote_peer_id,
    )
    .unwrap_or(candidate_session_id);

    let matched = sessions_by_id.get(&lookup_session_id).is_some_and(|handle| {
        handle.generation == generation && handle.remote_peer_id == remote_peer_id
    });

    if matched {
        sessions_by_id.remove(&lookup_session_id);
        session_by_peer.remove(&remote_peer_id);
    } else {
        tracing::warn!(
            session_id = %lookup_session_id,
            generation = generation.0,
            remote_peer_id = %remote_peer_id,
            "ignoring stale or already-removed answer session completion"
        );
    }

    match outcome {
        Ok(result) => {
            if matched {
                recover_daemon_after_session(ctx, result.result).await;
            }
        }
        Err(join_reason) => {
            tracing::error!(
                session_id = %lookup_session_id,
                remote_peer_id = %remote_peer_id,
                reason = %join_reason,
                "answer session task panicked or was aborted unexpectedly; entering drain",
            );
            begin_answer_drain(
                ctx,
                shutdown,
                primary_error,
                Some(DaemonError::Logging(format!("answer session task panicked: {join_reason}"))),
            );
        }
    }

    write_answer_registry_status(ctx, sessions_by_id).await;
}
