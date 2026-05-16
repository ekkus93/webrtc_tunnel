# v0.3 TODO — Multiple Simultaneous Offer Sessions

This checklist tracks the work required to implement `docs/V03_SPEC.md`.

Historical note: the initial v0.3 implementation was followed by the hardening pass tracked in `docs/V03_FIX_TODO.md`, which corrected authenticated routing, stale event isolation, and honest status field names.

Scope summary:

- one `p2p-answer` daemon must support multiple simultaneous authorized `p2p-offer` peers,
- each peer still gets at most one active session,
- each session still uses the existing v2 multiplexed forwarding model,
- signaling/tunnel wire formats should remain unchanged for the first pass.

## 0. Guardrails and invariants

- [x] Re-read `docs/V03_SPEC.md` before touching runtime/session code.
- [x] Preserve current signaling wire format, tunnel frame format, and config format unless a later task proves a change is required.
- [x] Preserve offer-side reconnect ownership.
- [x] Preserve per-forward authorization on the answer side.
- [x] Preserve daemon-fatal startup/security/init failure behavior.
- [x] Preserve session-local handling for stream failures and target connect failures.
- [x] Preserve the rule that unauthorized or disallowed peers do not receive useful plaintext diagnostics.

## 1. Baseline audit and design prep

- [x] Audit the current single-session answer runtime in `crates/p2p-daemon`.
  - [x] Identify the current global `active_session` slot and all places that read/write it.
  - [x] Identify current session-state enums and state transitions.
  - [x] Identify all code paths that assume answer-side `idle` vs `busy`.
  - [x] Identify all teardown paths that currently clear global session state.
- [x] Audit current signaling dispatch flow.
  - [x] Locate all entry points for inbound `offer`, `answer`, ICE, ACK, `close`, and `error`.
  - [x] Identify where `session_id` matching happens today.
  - [x] Identify where same-peer replacement offers are currently allowed.
- [x] Audit current answer-side reconnect/replacement behavior.
  - [x] Confirm the current reconnect policy that must remain session-local in v0.3.
  - [x] Identify any global reconnect state that would conflict with multi-session support.
- [x] Audit status and logging assumptions.
  - [x] Identify all places that serialize a single active session into `status.json`.
  - [x] Identify all log events that need `session_id` / `peer_id` context added.

## 2. Define the new answer-side session model

- [x] Introduce an explicit answer-side session registry design.
  - [x] Define `sessions_by_id: SessionId -> SessionRuntime`.
  - [x] Define `session_by_peer: PeerId -> SessionId`.
  - [x] Define any additional indices needed for efficient lookup or teardown.
- [x] Define `SessionRuntime` ownership boundaries.
  - [x] Authenticated remote peer identity.
  - [x] Session state enum.
  - [x] WebRTC peer handle.
  - [x] Data channel handle.
  - [x] ACK tracker / retransmit state.
  - [x] Multiplexed stream runtime handle(s).
  - [x] Cancellation/teardown handles.
  - [x] Per-session duplicate/busy helper state.
- [x] Define explicit per-session states for answer runtime.
  - [x] `Negotiating`
  - [x] `ConnectingDataChannel`
  - [x] `Active`
  - [x] `Reconnecting`
  - [x] `Closing`
  - [x] `Closed`
- [x] Define daemon-level aggregate service state.
  - [x] Remove the assumption that daemon truth is only `idle` or `busy`.
  - [x] Represent daemon service as globally serving plus per-session state.
  - [x] Expose `active_session_count` as an aggregate.

## 3. Replace single-session global state in `p2p-daemon`

- [x] Refactor answer daemon runtime structs to hold a multi-session registry.
- [x] Remove direct use of a single global answer `active_session`.
- [x] Update helpers and control flow that currently:
  - [x] create one session,
  - [x] mutate one session,
  - [x] close one session,
  - [x] assume one global session owns all answer-side runtime.
- [x] Ensure teardown removes only the owning session entry.
  - [x] Remove from `sessions_by_id`.
  - [x] Remove from `session_by_peer`.
  - [x] Cancel session-owned tasks only.
  - [x] Leave unrelated sessions untouched.
- [x] Ensure cleanup is idempotent when multiple teardown triggers race.

## 4. Centralized signaling dispatch with per-session routing

- [x] Keep one MQTT transport loop for the answer daemon.
- [x] Refactor signaling receive path to:
  - [x] authenticate/decrypt/validate the message first,
  - [x] determine authenticated remote peer,
  - [x] inspect `session_id`,
  - [x] route the message to the owning session when one exists.
- [x] Implement new-session candidate handling.
  - [x] If message is an `offer` for a new `session_id`, evaluate as a new session.
  - [x] If message is not a valid new-session entry point, ignore/reject per protocol rules.
- [x] Ensure stale or unknown-session non-offer messages cannot create session state.
- [x] Ensure routing is stable under concurrent session activity.

## 5. Same-peer occupancy and session-admission policy

- [x] Enforce one active session per authenticated `peer_id`.
- [x] Define same-peer offer classification logic.
  - [x] Duplicate/retransmit for same session -> normal dedupe path.
  - [x] Valid replacement/reconnect for same peer/session -> allowed.
  - [x] New unrelated session from same peer while one is active -> encrypted `busy`.
- [x] Refactor existing busy logic so it no longer means "any session exists".
- [x] Add a single global hard session-capacity limit for the first pass.
  - [x] Choose where the constant lives.
  - [x] Enforce it only for fully allowed peers.
  - [x] Keep unauthorized/disallowed peers on the existing no-useful-response path.
- [x] Ensure capacity checks do not break same-peer reconnect/replacement flows.

## 6. Create and start session runtimes safely

- [x] Implement session creation for a newly admitted offer.
  - [x] Allocate session runtime entry.
  - [x] Bind authenticated peer ID to that session.
  - [x] Initialize per-session ACK/retransmit state.
  - [x] Initialize per-session multiplexed stream runtime.
  - [x] Start WebRTC negotiation for that session only.
- [x] Ensure session creation is atomic enough to avoid double-admission races.
- [x] Ensure failure during early session creation cleans up partially inserted session state.
- [x] Ensure same-peer replacement can atomically swap the old session entry with the new one when allowed.

## 7. Bind all callbacks and async work to the owning session

- [x] Audit all answer-side WebRTC callbacks.
  - [x] ICE state callbacks.
  - [x] candidate callbacks.
  - [x] data-channel arrival/open/close callbacks.
  - [x] peer-connection state callbacks.
- [x] Audit all session-owned task spawns.
  - [x] bridge tasks,
  - [x] stream tasks,
  - [x] reconnect helpers,
  - [x] teardown helpers.
- [x] Ensure every callback/task captures the creating `session_id`.
- [x] Add guards so stale callbacks for a closed/replaced session are ignored.
- [x] Ensure one session's callback cannot clear or mutate another session's registry entry.

## 8. Keep multiplexed tunnel runtime session-local

- [x] Verify each `SessionRuntime` owns a fully isolated multiplexed tunnel runtime.
- [x] Confirm stream ID allocation stays per-session, not global.
- [x] Confirm forward authorization checks use:
  - [x] the authenticated peer for that session,
  - [x] the requested `forward_id`,
  - [x] that forward's `allow_remote_peers`.
- [x] Confirm one session's stream failures remain stream-local or session-local as appropriate.
- [x] Confirm multi-session answer runtime can host many multiplex runtimes concurrently without shared mutable cross-session tunnel state.

## 9. Reconnect and replacement isolation

- [x] Audit current reconnect/replacement code for global state assumptions.
- [x] Ensure offer-driven reconnect remains scoped to the owning session.
- [x] Ensure same-peer valid replacement can replace only that peer's session.
- [x] Ensure a reconnect attempt from peer A cannot:
  - [x] pause peer B,
  - [x] clear peer B's session,
  - [x] reset daemon-wide busy state incorrectly,
  - [x] drop peer B's active streams.
- [x] Ensure reconnect teardown cleans only the affected session.

## 10. Failure isolation hardening

- [x] Review all answer-side error paths and classify them as:
  - [x] stream-local,
  - [x] session-local,
  - [x] daemon-fatal.
- [x] Convert any currently global answer-session failure handling into session-local cleanup where appropriate.
- [x] Verify each of the following is isolated to the owning session:
  - [x] WebRTC failure,
  - [x] ACK timeout,
  - [x] remote `close`,
  - [x] remote `error`,
  - [x] reconnect failure,
  - [x] target connect failure,
  - [x] bridge-task failure.
- [x] Verify daemon-fatal startup failures remain process-fatal and unchanged.

## 11. Status model redesign

- [x] Extend local status structures for multi-session reporting.
- [x] Keep daemon-level fields:
  - [x] local `peer_id`,
  - [x] role,
  - [x] `mqtt_connected`,
  - [x] daemon service state,
  - [x] `active_session_count`,
  - [x] session capacity.
- [x] Add per-session status entries with at least:
  - [x] `session_id`,
  - [x] `remote_peer_id`,
  - [x] `state`,
  - [x] `data_channel_open`,
  - [x] `configured_forward_ids`.
  - [x] `active_stream_count` removed until backed by real multiplex-runtime state.
- [x] Ensure status writes remain best-effort local output only.
- [x] Ensure status serialization tolerates concurrent session changes without partial-invalid semantics.

## 12. Logging improvements for concurrent sessions

- [x] Audit answer-side logs and add session context consistently.
- [x] Include `session_id` in all session-owned logs.
- [x] Include remote `peer_id` in all peer-owned logs.
- [x] Include `stream_id` in stream-owned logs where applicable.
- [x] Ensure logs for global MQTT transport state remain daemon-level, not falsely session-scoped.
- [x] Keep secret redaction and SDP/ICE redaction intact.

## 13. `p2pctl status` output updates

- [x] Update `p2pctl status` to display multiple active sessions cleanly.
- [x] Ensure output remains readable for:
  - [x] zero active sessions,
  - [x] one active session,
  - [x] multiple active sessions.
- [x] Ensure CLI output reflects the new status schema without breaking basic operator inspection workflows.

## 14. Config and validation review

- [x] Confirm no config shape change is required for the first pass.
- [x] Confirm current `authorized_keys` plus `allow_remote_peers` are sufficient for multi-session admission.
- [x] If a global session-capacity constant is introduced, keep it internal unless a real operator need forces public config.
- [x] If any new config is proposed later:
  - [x] define exact semantics,
  - [x] add strict validation,
  - [x] update README/spec/examples,
  - [x] reject decorative/no-op fields.

## 15. Test scaffolding and helpers

- [x] Review current in-memory signaling transport and daemon integration harness.
- [x] Extend test harness to support multiple concurrent answer-side sessions.
- [x] Ensure test harness can:
  - [x] run two offer daemons against one answer daemon,
  - [x] observe per-session signaling traces,
  - [x] inject per-session failures,
  - [x] assert that unrelated sessions survive.
- [x] Add any minimal test-only hooks needed for session-scoped fault injection.
- [x] Avoid adding production-only complexity just for tests.

## 16. Unit tests

- [x] Add focused unit coverage for answer-side session admission logic.
  - [x] new session from allowed peer when none active,
  - [x] same-peer unrelated second session rejected,
  - [x] same-peer valid replacement allowed,
  - [x] capacity-full path returns `busy` only for allowed peers.
- [x] Add focused unit coverage for session routing.
  - [x] known `session_id` routes correctly,
  - [x] unknown non-offer session message is rejected/ignored,
  - [x] stale session callback is ignored.
- [x] Add unit coverage for registry cleanup.
  - [x] session removal clears both indices,
  - [x] one session removal leaves others intact,
  - [x] repeated teardown is safe.
- [x] Add unit coverage for status serialization with multiple sessions.

## 17. Integration tests

- [x] Add an integration test where two different authorized offer peers connect concurrently to one answer daemon.
- [x] Add an integration test where both peers open streams on different forwards concurrently.
- [x] Add an integration test where both peers open streams on the same forward concurrently if allowed.
- [x] Add an integration test where peer A fails and peer B remains healthy throughout.
- [x] Add an integration test where same peer tries a second unrelated session and gets encrypted `busy`.
- [x] Add an integration test where a valid same-peer replacement session succeeds without disturbing peer B.
- [x] Add an integration test where unauthorized or disallowed peers still get no useful response under load.
- [x] Add an integration test where per-forward allowlists are enforced independently across simultaneous sessions.
- [x] Add an integration test for duplicate/replay handling with many active sessions.
- [x] Add an integration test proving stale callbacks from a torn-down session do not mutate another active session.

## 18. Regression tests for failure isolation

- [x] Add coverage for session-local WebRTC failure.
- [x] Add coverage for session-local ACK timeout.
- [x] Add coverage for session-local remote `close`.
- [x] Add coverage for session-local remote `error`.
- [x] Add coverage for session-local reconnect failure.
- [x] Add coverage for session-local target connect failure while another session remains active.
- [x] Add coverage for stream-level errors inside one session while another session remains unaffected.

## 19. Documentation updates

- [x] Update `README.md` once the implementation lands.
  - [x] explain one answer daemon can host multiple simultaneous offer peers,
  - [x] explain each offer machine needs its own identity,
  - [x] explain one active session per peer,
  - [x] explain that multiple streams still multiplex inside each session.
- [x] Update `docs/SPECS.md` to reflect v0.3 behavior.
- [x] Update any stale review/spec/todo docs that still say "one active peer session at a time" after implementation.
- [x] Add/update sample configs only if operator-visible config changes are introduced.
- [x] Document local status output changes.

## 20. Validation and release checklist

- [x] Run targeted crate tests during development:
  - [x] `cargo test -p p2p-daemon`
  - [x] `cargo test -p p2p-tunnel`
  - [x] `cargo test -p p2p-signaling`
  - [x] `cargo test -p p2pctl`
- [x] Run targeted clippy during development where changes land.
- [x] Run full workspace lint before signoff:
  - [x] `cargo clippy --workspace --all-targets --all-features -- -D warnings`
- [x] Run full workspace tests before signoff:
  - [x] `cargo test --workspace --all-targets`
- [x] Verify no stale single-session assumptions remain in status, docs, or tests.
- [x] Verify no wire-format or config-surface changes slipped in unintentionally.

## 21. Done criteria

- [x] One `p2p-answer` daemon supports multiple simultaneous authorized offer peers.
- [x] Each peer still uses one multiplexed session.
- [x] Same-peer second unrelated session is explicitly rejected.
- [x] Per-forward authorization still gates target access.
- [x] One session failure does not kill unrelated sessions.
- [x] Status and logs are multi-session aware.
- [x] Existing signaling/replay/reconnect invariants still hold.
- [x] End-to-end integration tests prove real concurrent-session behavior.
