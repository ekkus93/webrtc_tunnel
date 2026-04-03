# RUST_WEBRTC_CODE_REVIEW_TODO.md

This TODO is intended for GitHub Copilot implementation work.

Goal:
- fix the correctness and security gaps found during review
- bring the implementation closer to the intended secure v1 design
- reduce config/spec drift

Priority legend:
- **P0** = fix before trusting this in production
- **P1** = important correctness/security cleanup
- **P2** = quality, UX, completeness

---

# P0 — Blockers

## 1. Fix idle answer replay protection

### Problem
The idle answer loop recreates `ReplayCache` per message instead of keeping one persistent cache across idle message processing.

### Files
- `crates/p2p-daemon/src/lib.rs`

### Tasks
- [ ] Move replay cache allocation out of the idle answer message loop.
- [ ] Create one long-lived replay cache for the idle answer daemon.
- [ ] Reuse that replay cache for every inbound idle-loop signaling payload.
- [ ] Verify replay cache size comes from `config.security.replay_cache_size`.
- [ ] Add a test covering replay of the same offer during idle state.
- [ ] Add a test covering replay of the same ack-required message during idle state.

### Acceptance criteria
- Replaying the same MQTT payload while the answer daemon is idle is rejected.
- The replay decision survives across multiple loop iterations.

---

## 2. Run the answer-side tunnel bridge in its own task

### Problem
The answer daemon currently blocks its own session loop by awaiting `bridge.run_answer(connector)` inline.

### Files
- `crates/p2p-daemon/src/lib.rs`
- possibly `crates/p2p-tunnel/src/bridge.rs`

### Tasks
- [ ] Mirror the offer-side bridge execution model on the answer side.
- [ ] Spawn the answer bridge in a Tokio task.
- [ ] Track the task via a join handle.
- [ ] Continue processing signaling, ICE candidates, and ICE state while the answer bridge is active.
- [ ] Handle bridge task completion cleanly.
- [ ] Abort the bridge task on session teardown or fatal ICE failure.
- [ ] Add a regression test or integration-style harness for active tunnel + concurrent signaling processing.

### Acceptance criteria
- While the answer bridge is running, the daemon still processes MQTT signaling and ICE updates.
- The session loop does not deadlock when the tunnel is active.

---

## 3. Fix offer-side reconnect for live active tunnels

### Problem
Reconnect currently depends on `pending_stream.is_some()`, which is no longer true once the bridge is active.

### Files
- `crates/p2p-daemon/src/lib.rs`
- possibly `crates/p2p-tunnel/src/offer.rs`
- possibly `crates/p2p-tunnel/src/bridge.rs`

### Tasks
- [ ] Audit current reconnect assumptions on the offer side.
- [ ] Introduce explicit session/bridge state that distinguishes:
  - [ ] local client accepted, not yet bridged
  - [ ] bridge active
  - [ ] reconnect in progress
  - [ ] bridge closed
- [ ] Decide and implement the intended v1 behavior when a live bridge drops:
  - [ ] either fail the local client immediately and end the session, or
  - [ ] hold and reconnect for a short window if that behavior is truly implemented
- [ ] Remove reconnect gating based solely on `pending_stream.is_some()`.
- [ ] Ensure reconnect logic is triggered for the normal active-session ICE failure path.
- [ ] Add tests for a live tunnel entering ICE failed/disconnected state.

### Acceptance criteria
- A live tunnel drop enters the reconnect path or cleanly fails according to explicit policy.
- Reconnect behavior is not limited to pre-bridge setup state.

---

## 4. Implement configured MQTT TLS behavior or remove unsupported TLS config

### Problem
Broker TLS config is declared but mostly ignored; the transport uses default TLS config only.

### Files
- `crates/p2p-signaling/src/transport.rs`
- `crates/p2p-core/src/config.rs`

### Tasks
- [ ] Decide whether v1 fully supports custom TLS config.
- [ ] If yes, implement:
  - [ ] `ca_file`
  - [ ] `client_cert_file`
  - [ ] `client_key_file`
  - [ ] `server_name`
  - [ ] `insecure_skip_verify` behavior
- [ ] If full support is not practical now, remove or disable misleading config fields and fail clearly on unsupported settings.
- [ ] Implement `connect_timeout_secs` if the MQTT client library supports it cleanly.
- [ ] Audit whether `session_expiry_secs` is actually supported; implement or remove.
- [ ] Add tests or smoke-level validation for TLS config parsing and startup validation.

### Acceptance criteria
- The transport behavior matches the TLS config surface.
- Unsupported TLS settings are not silently ignored.

---

# P1 — Important correctness and protocol cleanup

## 5. Enforce expected session during active answer decode

### Problem
The active answer loop decodes with `expected_session = None` and then checks session mismatch later.

### Files
- `crates/p2p-daemon/src/lib.rs`
- `crates/p2p-signaling/src/transport.rs` or signaling decode helpers if needed

### Tasks
- [ ] Pass `Some(session.session_id)` to active answer-session decode.
- [ ] Ensure stale/foreign-session messages are rejected during decode.
- [ ] Confirm ACK behavior does not run before session validation.
- [ ] Add tests for stale-session and foreign-session payloads during an active answer session.

### Acceptance criteria
- Active answer sessions reject foreign-session messages before ACK or processing.

---

## 6. Move ACK behavior behind policy/authorization checks where appropriate

### Problem
The idle answer daemon ACKs ack-required messages before final `allow_remote_peers` checks.

### Files
- `crates/p2p-daemon/src/lib.rs`

### Tasks
- [ ] Audit all ACK call sites.
- [ ] For `Offer`, ensure policy checks occur before ACK.
- [ ] Decide whether other message types should also defer ACK until sender/policy checks are complete.
- [ ] Keep ACK behavior protocol-consistent across idle and active loops.
- [ ] Add tests verifying unauthorized but authorized-key-listed peers do not receive misleading ACKs.

### Acceptance criteria
- Policy-rejected offers do not receive a success-looking ACK.

---

## 7. Make `max_attempts = 0` mean unlimited retries

### Problem
Current code converts `0` to `3`.

### Files
- `crates/p2p-daemon/src/lib.rs`

### Tasks
- [ ] Replace current reconnect attempt loop with logic that supports true unlimited retries when `max_attempts == 0`.
- [ ] Preserve bounded retry behavior for nonzero values.
- [ ] Update any related tests or add new ones.

### Acceptance criteria
- `max_attempts = 0` behaves as documented.

---

## 8. Resolve the data-channel-label config mismatch

### Problem
The data channel is created using a config label, then validated against a hardcoded constant.

### Files
- `crates/p2p-webrtc/src/lib.rs`
- `crates/p2p-core/src/config.rs`

### Tasks
- [ ] Decide whether data channel label is truly configurable in v1.
- [ ] If configurable:
  - [ ] remove hardcoded validation against a different constant
  - [ ] use config consistently on both create and receive paths
- [ ] If not configurable:
  - [ ] remove the config field
  - [ ] hardcode one label everywhere
- [ ] Add tests for expected label behavior.

### Acceptance criteria
- There is exactly one source of truth for the data channel label.

---

## 9. Harden config validation to match secure-default design

### Problem
The config validator checks some basics but does not enforce several stronger fail-closed expectations.

### Files
- `crates/p2p-core/src/config.rs`
- `crates/p2p-crypto/src/identity.rs`
- possibly daemon startup code

### Tasks
- [ ] Audit all `security.*` config knobs.
- [ ] Enforce or remove any knob that the implementation does not truly support.
- [ ] Add validation for insecure path conditions where feasible.
- [ ] Validate required file presence and consistency for TLS if TLS config remains supported.
- [ ] Ensure startup fails clearly on unsupported or insecure settings.

### Acceptance criteria
- Security-related config is either enforced or rejected, not silently ignored.

---

## 10. Audit signaling/session behavior around ACK ordering

### Problem
Some session validation and ACK ordering paths are weaker than intended.

### Files
- `crates/p2p-daemon/src/lib.rs`
- `crates/p2p-signaling/src/ack.rs`
- `crates/p2p-signaling/src/messages.rs`

### Tasks
- [ ] Audit message types that require ACK.
- [ ] Confirm ACK is sent only after the message is considered valid for this peer/session.
- [ ] Confirm duplicates are handled cleanly.
- [ ] Add tests for:
  - [ ] duplicate offer
  - [ ] stale session message
  - [ ] wrong sender peer
  - [ ] unauthorized peer

### Acceptance criteria
- ACK behavior is protocol-consistent and not misleading.

---

# P2 — UX, completeness, and cleanup

## 11. Add overwrite protection to `p2pctl keygen`

### Problem
`keygen` overwrites `identity` and `identity.pub` without confirmation.

### Files
- `bins/p2pctl/src/main.rs`

### Tasks
- [ ] Refuse to overwrite existing `identity` or `identity.pub` by default.
- [ ] Add `--force` to allow explicit overwrite.
- [ ] Print a clear warning or success message indicating whether files were newly created or replaced.
- [ ] Add tests for default refusal and `--force` behavior.

### Acceptance criteria
- Existing identities are not overwritten accidentally.

---

## 12. Make broker password optional

### Problem
`password_file` is always read, which blocks anonymous or certificate-only broker setups.

### Files
- `crates/p2p-signaling/src/transport.rs`
- `crates/p2p-core/src/config.rs`

### Tasks
- [ ] Make password loading conditional.
- [ ] Allow username-only, certificate-only, or anonymous broker auth modes where explicitly configured.
- [ ] Add validation rules so invalid combinations fail clearly.
- [ ] Add tests for password/no-password startup behavior.

### Acceptance criteria
- Broker auth modes are explicit and do not require a password file when not needed.

---

## 13. Remove or implement dead config fields

### Problem
Some config knobs appear unused or unimplemented.

### Files
- `crates/p2p-core/src/config.rs`
- relevant implementation crates

### Tasks
- [ ] Audit each config field for real runtime use.
- [ ] For each unused field, choose one:
  - [ ] implement it
  - [ ] remove it
  - [ ] mark it unsupported and fail if set
- [ ] Candidate fields to audit first:
  - [ ] `webrtc.max_message_size`
  - [ ] `logging.log_rotation`
  - [ ] `health.status_socket`
  - [ ] reconnect hold-local-client fields
- [ ] Update README/docs to match the real config surface.

### Acceptance criteria
- Config no longer promises behavior that does not exist.

---

## 14. Improve failure-path and reconnect tests

### Problem
The current tests are a good start, but the risky areas are state-machine/failure paths.

### Files
- tests across `p2p-daemon`, `p2p-signaling`, `p2p-webrtc`, `p2p-tunnel`

### Tasks
- [ ] Add tests for replay rejection across daemon idle loop iterations.
- [ ] Add tests for active answer session handling while tunnel bridge is running.
- [ ] Add tests for reconnect after ICE failure during an active tunnel.
- [ ] Add tests for unauthorized peer offers.
- [ ] Add tests for stale-session messages.
- [ ] Add tests for duplicate ACK-required signaling messages.

### Acceptance criteria
- The highest-risk control-flow and protocol paths are covered by regression tests.

---

## 15. Tighten logging around security-sensitive paths

### Problem
The code already has decent structure, but sensitive paths should be extra cautious.

### Files
- `crates/p2p-daemon/src/logging.rs`
- signaling and crypto call sites

### Tasks
- [ ] Audit logs for potential leakage of decrypted payloads or sensitive metadata.
- [ ] Ensure broker credentials and key material are never logged.
- [ ] Keep SDP/candidate redaction behavior consistent.
- [ ] Add structured logs for reject reasons without leaking plaintext contents.

### Acceptance criteria
- Logs remain useful for debugging without violating the secure design intent.

---

# Suggested implementation order

## Phase 1 — Security/correctness blockers
- [ ] Fix idle replay cache lifetime
- [ ] Spawn answer bridge in a task
- [ ] Fix live-session reconnect logic
- [ ] Resolve MQTT TLS config drift

## Phase 2 — Protocol cleanup
- [ ] Enforce expected session on answer decode
- [ ] Fix ACK ordering vs authorization/session validation
- [ ] Fix unlimited reconnect semantics
- [ ] Resolve data-channel-label mismatch

## Phase 3 — Config/UX cleanup
- [ ] Harden config validation
- [ ] Add `p2pctl keygen --force`
- [ ] Make password file optional
- [ ] Remove or implement dead config knobs

## Phase 4 — Regression coverage
- [ ] Add tests for all critical failure/replay/reconnect paths

---

# Definition of done

The review follow-up is complete when:
- replay protection persists across idle answer daemon message handling
- answer-side active tunneling does not block signaling/ICE processing
- reconnect behavior is explicit and works for live active sessions
- MQTT TLS config either works as configured or unsupported settings are rejected clearly
- active session validation is stricter and ACK behavior is cleaner
- config surface matches real implementation
- the main failure and replay paths have regression tests
