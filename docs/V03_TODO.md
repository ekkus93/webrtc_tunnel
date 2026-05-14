# v0.3 TODO — Multiple Simultaneous Offer Sessions

This checklist tracks the work required to implement `docs/V03_SPEC.md`.

Scope summary:

- one `p2p-answer` daemon must support multiple simultaneous authorized `p2p-offer` peers,
- each peer still gets at most one active session,
- each session still uses the existing v2 multiplexed forwarding model,
- signaling/tunnel wire formats should remain unchanged for the first pass.

## 0. Guardrails and invariants

- [ ] Re-read `docs/V03_SPEC.md` before touching runtime/session code.
- [ ] Preserve current signaling wire format, tunnel frame format, and config format unless a later task proves a change is required.
- [ ] Preserve offer-side reconnect ownership.
- [ ] Preserve per-forward authorization on the answer side.
- [ ] Preserve daemon-fatal startup/security/init failure behavior.
- [ ] Preserve session-local handling for stream failures and target connect failures.
- [ ] Preserve the rule that unauthorized or disallowed peers do not receive useful plaintext diagnostics.

## 1. Baseline audit and design prep

- [ ] Audit the current single-session answer runtime in `crates/p2p-daemon`.
  - [ ] Identify the current global `active_session` slot and all places that read/write it.
  - [ ] Identify current session-state enums and state transitions.
  - [ ] Identify all code paths that assume answer-side `idle` vs `busy`.
  - [ ] Identify all teardown paths that currently clear global session state.
- [ ] Audit current signaling dispatch flow.
  - [ ] Locate all entry points for inbound `offer`, `answer`, ICE, ACK, `close`, and `error`.
  - [ ] Identify where `session_id` matching happens today.
  - [ ] Identify where same-peer replacement offers are currently allowed.
- [ ] Audit current answer-side reconnect/replacement behavior.
  - [ ] Confirm the current reconnect policy that must remain session-local in v0.3.
  - [ ] Identify any global reconnect state that would conflict with multi-session support.
- [ ] Audit status and logging assumptions.
  - [ ] Identify all places that serialize a single active session into `status.json`.
  - [ ] Identify all log events that need `session_id` / `peer_id` context added.

## 2. Define the new answer-side session model

- [ ] Introduce an explicit answer-side session registry design.
  - [ ] Define `sessions_by_id: SessionId -> SessionRuntime`.
  - [ ] Define `session_by_peer: PeerId -> SessionId`.
  - [ ] Define any additional indices needed for efficient lookup or teardown.
- [ ] Define `SessionRuntime` ownership boundaries.
  - [ ] Authenticated remote peer identity.
  - [ ] Session state enum.
  - [ ] WebRTC peer handle.
  - [ ] Data channel handle.
  - [ ] ACK tracker / retransmit state.
  - [ ] Multiplexed stream runtime handle(s).
  - [ ] Cancellation/teardown handles.
  - [ ] Per-session duplicate/busy helper state.
- [ ] Define explicit per-session states for answer runtime.
  - [ ] `Negotiating`
  - [ ] `ConnectingDataChannel`
  - [ ] `Active`
  - [ ] `Reconnecting`
  - [ ] `Closing`
  - [ ] `Closed`
- [ ] Define daemon-level aggregate service state.
  - [ ] Remove the assumption that daemon truth is only `idle` or `busy`.
  - [ ] Represent daemon service as globally serving plus per-session state.
  - [ ] Expose `active_session_count` as an aggregate.

## 3. Replace single-session global state in `p2p-daemon`

- [ ] Refactor answer daemon runtime structs to hold a multi-session registry.
- [ ] Remove direct use of a single global answer `active_session`.
- [ ] Update helpers and control flow that currently:
  - [ ] create one session,
  - [ ] mutate one session,
  - [ ] close one session,
  - [ ] assume one global session owns all answer-side runtime.
- [ ] Ensure teardown removes only the owning session entry.
  - [ ] Remove from `sessions_by_id`.
  - [ ] Remove from `session_by_peer`.
  - [ ] Cancel session-owned tasks only.
  - [ ] Leave unrelated sessions untouched.
- [ ] Ensure cleanup is idempotent when multiple teardown triggers race.

## 4. Centralized signaling dispatch with per-session routing

- [ ] Keep one MQTT transport loop for the answer daemon.
- [ ] Refactor signaling receive path to:
  - [ ] authenticate/decrypt/validate the message first,
  - [ ] determine authenticated remote peer,
  - [ ] inspect `session_id`,
  - [ ] route the message to the owning session when one exists.
- [ ] Implement new-session candidate handling.
  - [ ] If message is an `offer` for a new `session_id`, evaluate as a new session.
  - [ ] If message is not a valid new-session entry point, ignore/reject per protocol rules.
- [ ] Ensure stale or unknown-session non-offer messages cannot create session state.
- [ ] Ensure routing is stable under concurrent session activity.

## 5. Same-peer occupancy and session-admission policy

- [ ] Enforce one active session per authenticated `peer_id`.
- [ ] Define same-peer offer classification logic.
  - [ ] Duplicate/retransmit for same session -> normal dedupe path.
  - [ ] Valid replacement/reconnect for same peer/session -> allowed.
  - [ ] New unrelated session from same peer while one is active -> encrypted `busy`.
- [ ] Refactor existing busy logic so it no longer means "any session exists".
- [ ] Add a single global hard session-capacity limit for the first pass.
  - [ ] Choose where the constant lives.
  - [ ] Enforce it only for fully allowed peers.
  - [ ] Keep unauthorized/disallowed peers on the existing no-useful-response path.
- [ ] Ensure capacity checks do not break same-peer reconnect/replacement flows.

## 6. Create and start session runtimes safely

- [ ] Implement session creation for a newly admitted offer.
  - [ ] Allocate session runtime entry.
  - [ ] Bind authenticated peer ID to that session.
  - [ ] Initialize per-session ACK/retransmit state.
  - [ ] Initialize per-session multiplexed stream runtime.
  - [ ] Start WebRTC negotiation for that session only.
- [ ] Ensure session creation is atomic enough to avoid double-admission races.
- [ ] Ensure failure during early session creation cleans up partially inserted session state.
- [ ] Ensure same-peer replacement can atomically swap the old session entry with the new one when allowed.

## 7. Bind all callbacks and async work to the owning session

- [ ] Audit all answer-side WebRTC callbacks.
  - [ ] ICE state callbacks.
  - [ ] candidate callbacks.
  - [ ] data-channel arrival/open/close callbacks.
  - [ ] peer-connection state callbacks.
- [ ] Audit all session-owned task spawns.
  - [ ] bridge tasks,
  - [ ] stream tasks,
  - [ ] reconnect helpers,
  - [ ] teardown helpers.
- [ ] Ensure every callback/task captures the creating `session_id`.
- [ ] Add guards so stale callbacks for a closed/replaced session are ignored.
- [ ] Ensure one session's callback cannot clear or mutate another session's registry entry.

## 8. Keep multiplexed tunnel runtime session-local

- [ ] Verify each `SessionRuntime` owns a fully isolated multiplexed tunnel runtime.
- [ ] Confirm stream ID allocation stays per-session, not global.
- [ ] Confirm forward authorization checks use:
  - [ ] the authenticated peer for that session,
  - [ ] the requested `forward_id`,
  - [ ] that forward's `allow_remote_peers`.
- [ ] Confirm one session's stream failures remain stream-local or session-local as appropriate.
- [ ] Confirm multi-session answer runtime can host many multiplex runtimes concurrently without shared mutable cross-session tunnel state.

## 9. Reconnect and replacement isolation

- [ ] Audit current reconnect/replacement code for global state assumptions.
- [ ] Ensure offer-driven reconnect remains scoped to the owning session.
- [ ] Ensure same-peer valid replacement can replace only that peer's session.
- [ ] Ensure a reconnect attempt from peer A cannot:
  - [ ] pause peer B,
  - [ ] clear peer B's session,
  - [ ] reset daemon-wide busy state incorrectly,
  - [ ] drop peer B's active streams.
- [ ] Ensure reconnect teardown cleans only the affected session.

## 10. Failure isolation hardening

- [ ] Review all answer-side error paths and classify them as:
  - [ ] stream-local,
  - [ ] session-local,
  - [ ] daemon-fatal.
- [ ] Convert any currently global answer-session failure handling into session-local cleanup where appropriate.
- [ ] Verify each of the following is isolated to the owning session:
  - [ ] WebRTC failure,
  - [ ] ACK timeout,
  - [ ] remote `close`,
  - [ ] remote `error`,
  - [ ] reconnect failure,
  - [ ] target connect failure,
  - [ ] bridge-task failure.
- [ ] Verify daemon-fatal startup failures remain process-fatal and unchanged.

## 11. Status model redesign

- [ ] Extend local status structures for multi-session reporting.
- [ ] Keep daemon-level fields:
  - [ ] local `peer_id`,
  - [ ] role,
  - [ ] `mqtt_connected`,
  - [ ] daemon service state,
  - [ ] `active_session_count`,
  - [ ] session capacity.
- [ ] Add per-session status entries with at least:
  - [ ] `session_id`,
  - [ ] `remote_peer_id`,
  - [ ] `state`,
  - [ ] `data_channel_open`,
  - [ ] `active_stream_count`,
  - [ ] `open_forward_ids`.
- [ ] Ensure status writes remain best-effort local output only.
- [ ] Ensure status serialization tolerates concurrent session changes without partial-invalid semantics.

## 12. Logging improvements for concurrent sessions

- [ ] Audit answer-side logs and add session context consistently.
- [ ] Include `session_id` in all session-owned logs.
- [ ] Include remote `peer_id` in all peer-owned logs.
- [ ] Include `stream_id` in stream-owned logs where applicable.
- [ ] Ensure logs for global MQTT transport state remain daemon-level, not falsely session-scoped.
- [ ] Keep secret redaction and SDP/ICE redaction intact.

## 13. `p2pctl status` output updates

- [ ] Update `p2pctl status` to display multiple active sessions cleanly.
- [ ] Ensure output remains readable for:
  - [ ] zero active sessions,
  - [ ] one active session,
  - [ ] multiple active sessions.
- [ ] Ensure CLI output reflects the new status schema without breaking basic operator inspection workflows.

## 14. Config and validation review

- [ ] Confirm no config shape change is required for the first pass.
- [ ] Confirm current `authorized_keys` plus `allow_remote_peers` are sufficient for multi-session admission.
- [ ] If a global session-capacity constant is introduced, keep it internal unless a real operator need forces public config.
- [ ] If any new config is proposed later:
  - [ ] define exact semantics,
  - [ ] add strict validation,
  - [ ] update README/spec/examples,
  - [ ] reject decorative/no-op fields.

## 15. Test scaffolding and helpers

- [ ] Review current in-memory signaling transport and daemon integration harness.
- [ ] Extend test harness to support multiple concurrent answer-side sessions.
- [ ] Ensure test harness can:
  - [ ] run two offer daemons against one answer daemon,
  - [ ] observe per-session signaling traces,
  - [ ] inject per-session failures,
  - [ ] assert that unrelated sessions survive.
- [ ] Add any minimal test-only hooks needed for session-scoped fault injection.
- [ ] Avoid adding production-only complexity just for tests.

## 16. Unit tests

- [ ] Add focused unit coverage for answer-side session admission logic.
  - [ ] new session from allowed peer when none active,
  - [ ] same-peer unrelated second session rejected,
  - [ ] same-peer valid replacement allowed,
  - [ ] capacity-full path returns `busy` only for allowed peers.
- [ ] Add focused unit coverage for session routing.
  - [ ] known `session_id` routes correctly,
  - [ ] unknown non-offer session message is rejected/ignored,
  - [ ] stale session callback is ignored.
- [ ] Add unit coverage for registry cleanup.
  - [ ] session removal clears both indices,
  - [ ] one session removal leaves others intact,
  - [ ] repeated teardown is safe.
- [ ] Add unit coverage for status serialization with multiple sessions.

## 17. Integration tests

- [ ] Add an integration test where two different authorized offer peers connect concurrently to one answer daemon.
- [ ] Add an integration test where both peers open streams on different forwards concurrently.
- [ ] Add an integration test where both peers open streams on the same forward concurrently if allowed.
- [ ] Add an integration test where peer A fails and peer B remains healthy throughout.
- [ ] Add an integration test where same peer tries a second unrelated session and gets encrypted `busy`.
- [ ] Add an integration test where a valid same-peer replacement session succeeds without disturbing peer B.
- [ ] Add an integration test where unauthorized or disallowed peers still get no useful response under load.
- [ ] Add an integration test where per-forward allowlists are enforced independently across simultaneous sessions.
- [ ] Add an integration test for duplicate/replay handling with many active sessions.
- [ ] Add an integration test proving stale callbacks from a torn-down session do not mutate another active session.

## 18. Regression tests for failure isolation

- [ ] Add coverage for session-local WebRTC failure.
- [ ] Add coverage for session-local ACK timeout.
- [ ] Add coverage for session-local remote `close`.
- [ ] Add coverage for session-local remote `error`.
- [ ] Add coverage for session-local reconnect failure.
- [ ] Add coverage for session-local target connect failure while another session remains active.
- [ ] Add coverage for stream-level errors inside one session while another session remains unaffected.

## 19. Documentation updates

- [ ] Update `README.md` once the implementation lands.
  - [ ] explain one answer daemon can host multiple simultaneous offer peers,
  - [ ] explain each offer machine needs its own identity,
  - [ ] explain one active session per peer,
  - [ ] explain that multiple streams still multiplex inside each session.
- [ ] Update `docs/RUST_WEBRTC_SPECS.md` to reflect v0.3 behavior.
- [ ] Update any stale review/spec/todo docs that still say "one active peer session at a time" after implementation.
- [ ] Add/update sample configs only if operator-visible config changes are introduced.
- [ ] Document local status output changes.

## 20. Validation and release checklist

- [ ] Run targeted crate tests during development:
  - [ ] `cargo test -p p2p-daemon`
  - [ ] `cargo test -p p2p-tunnel`
  - [ ] `cargo test -p p2p-signaling`
  - [ ] `cargo test -p p2pctl`
- [ ] Run targeted clippy during development where changes land.
- [ ] Run full workspace lint before signoff:
  - [ ] `cargo clippy --workspace --all-targets --all-features -- -D warnings`
- [ ] Run full workspace tests before signoff:
  - [ ] `cargo test --workspace --all-targets`
- [ ] Verify no stale single-session assumptions remain in status, docs, or tests.
- [ ] Verify no wire-format or config-surface changes slipped in unintentionally.

## 21. Done criteria

- [ ] One `p2p-answer` daemon supports multiple simultaneous authorized offer peers.
- [ ] Each peer still uses one multiplexed session.
- [ ] Same-peer second unrelated session is explicitly rejected.
- [ ] Per-forward authorization still gates target access.
- [ ] One session failure does not kill unrelated sessions.
- [ ] Status and logs are multi-session aware.
- [ ] Existing signaling/replay/reconnect invariants still hold.
- [ ] End-to-end integration tests prove real concurrent-session behavior.
