# Responses — WebRTC Tunnel Service Lifecycle Spec/TODO Review

Covers: `docs/WEBRTC_TUNNEL_SERVICE_LIFECYCLE_SPEC.md` and `docs/WEBRTC_TUNNEL_SERVICE_LIFECYCLE_TODO.md`

Fill in the `A:` line under each question, then share this file back so implementation can begin.

---

### 1

Q: Is a shutdown-aware variant of the test/session-hook API (`run_offer_daemon_with_transport_and_test_hook`) in scope for P0? The spec (§6.3) hedges with "may... if external integration tests need both [the session hook and cancellation]," but P0-018 (active offer session shutdown test) and P0-019 (shutdown-during-reconnect test) are exactly the kind of tests that existing test infrastructure normally drives via `OfferSessionTestHandle`/`session_hook` for fine-grained state control. Can P0-018/019 be fully implemented using only the two-node harness (real sockets, real timing) without needing the `session_hook`, or will a shutdown-aware test-hook variant be required to reliably force a reconnect/backoff window in test time?

A: **Yes. A shutdown-aware variant of the test/session-hook API is in P0 scope and should be used for deterministic P0-018/P0-019 coverage.** Do not make those tests depend only on the two-node harness, real socket timing, sleeps, or hoping to catch a reconnect/backoff window at the right moment. The two-node harness is still valuable as supplemental end-to-end coverage, but it is not the right sole synchronization mechanism for these lifecycle tests.

Implement one generalized internal offer-daemon entry point that accepts all runtime dependencies, including the `ShutdownToken` and optional `session_hook`, then keep the existing public/test APIs as thin compatibility wrappers. If the existing integration-test location cannot access a crate-private helper, expose a narrowly named shutdown-aware test variant such as:

```rust
pub async fn run_offer_daemon_with_transport_and_test_hook_and_shutdown<T>(
    /* existing arguments */,
    shutdown: ShutdownToken,
    session_hook: Option<OfferSessionTestHook>,
) -> Result<()>
where
    T: SignalingTransport,
{
    run_offer_daemon_inner(/* ..., */ shutdown, session_hook).await
}
```

The existing `run_offer_daemon_with_transport_and_test_hook` must remain source-compatible and delegate to the generalized implementation with a fresh token that is never cancelled by that wrapper. Avoid duplicating the daemon loop or creating separate production/test lifecycle logic.

For P0-018, use the hook to deterministically observe that an offer session is active before cancelling the token. For P0-019, use the injected transport/session controls to force the reconnect/backoff condition, observe that the daemon has entered the intended window, then cancel. Any timeout should be a bounded watchdog that fails the test, not the mechanism used to create or synchronize the state.

This answer supersedes the hedged wording in spec §6.3; update the spec/TODO wording accordingly.

---

### 2

Q: For P0-013/spec §9.6 — the spec's literal instruction is that every configured offer listener gets `listen_state = stopped, last_error = null` after shutdown, which means a listener that was already in an `Error` state (e.g., it never successfully bound) would have its `last_error` discarded and be overwritten to `Stopped`. Should offer forwards that were already in an `Error` state (never successfully bound) be overwritten to `Stopped`/`last_error = null` on shutdown as currently specified, or should they retain their error info through shutdown?

A: **Retain the error information. Do not erase a meaningful pre-existing `last_error` merely because shutdown occurred.** The final operational state and the diagnostic history are separate concerns.

On completed shutdown:

- every configured offer forward must have `listen_state = Stopped`;
- a forward that was healthy and stopped normally should have `last_error = None`;
- a forward that already had a meaningful `last_error` must retain that error;
- if shutdown/cleanup itself produces a newer meaningful error, record that newer error instead.

In other words, `Stopped` answers **"is this listener running now?"**, while `last_error` answers **"what most recently went wrong?"**. A forward that never successfully bound should therefore end as `Stopped` with its bind error still present, not as `Stopped` with the diagnostic erased.

Do not leave the listener in `Error` after the daemon has fully shut down, because it is no longer an active runtime state; normalize the operational state to `Stopped` while preserving the diagnostic field. The daemon-level final state should still be `DaemonState::Closed`, with MQTT disconnected and active session count zero.

This explicitly supersedes the literal `last_error = null` instruction in spec §9.6 and P0-013. Update those documents as part of the implementation so the repository does not retain contradictory requirements.

---

### 3

Q: Do you want this implemented and committed incrementally following the TODO's 17-step recommended sequence (separate commits per stage: token → shutdown-aware APIs → answer session propagation → answer drain state machine → offer accept-task ownership → offer session cancellation → offer reconnect cancellation → final status helpers → process signal adapters → wire binaries → lifecycle tests → systemd units → launchd plists → docs → Android P1 migration → packaging polish), or as one large pass? Given the size (27 P0 tasks spanning daemon core, both binaries, packaging, and docs), incremental commits would be the default unless you say otherwise.

A: **Implement and commit incrementally. Do not do this as one large pass.** Follow the TODO's recommended sequence as the dependency/order-of-work guide, because the feature crosses shared daemon lifecycle code, both binaries, tests, Linux packaging, macOS packaging, documentation, and later Android integration.

The goal is not necessarily exactly 17 commits. Small adjacent stages may be combined when they form one coherent change, but each commit should:

1. have one clear purpose;
2. compile on its own;
3. keep existing APIs working unless that commit intentionally adds a backward-compatible wrapper;
4. include the tests that prove the behavior introduced by that commit whenever practical; and
5. leave the tree in a reviewable, non-broken state.

Recommended commit boundaries are the major lifecycle milestones: shutdown primitive/API plumbing; answer propagation and drain state machine; offer listener/session/reconnect cancellation; final status semantics; process signal adapters and binary wiring; lifecycle tests; `systemd`; `launchd`; docs; then P1/P2 work. Do not mix unrelated cleanup or opportunistic refactors into these commits.

Complete and stabilize P0 before starting the Android P1 migration or packaging polish. If implementation discovers that one planned stage must be split further to keep commits buildable and tests meaningful, split it. Reviewability and bisectability are more important than matching an exact commit count.

---

### 4

Q: Should the new `scripts/check-systemd-units.sh` / `scripts/check-launchd-plists.sh` helper scripts (P1-003/P1-004) be wired into `.github/workflows/ci.yml` as part of this work, or left as manual/local-only helpers for now? Note: CI already runs `cargo test --workspace --all-targets` on both `ubuntu-latest` and `macos-latest`, so the real-signal integration tests (P0-020) and plist/unit structural Rust tests will get genuine automatic coverage on both platforms regardless of this answer — this question is specifically about the two standalone shell scripts.

A: **Wire the helper scripts into CI when P1-003/P1-004 are implemented; do not leave them manual-only.** They should be platform-gated and become required checks for the artifacts they validate.

Use the existing matrix so that:

```yaml
- name: Validate systemd units
  if: runner.os == 'Linux'
  run: ./scripts/check-systemd-units.sh

- name: Validate launchd plists
  if: runner.os == 'macOS'
  run: ./scripts/check-launchd-plists.sh
```

The scripts should fail loudly on invalid artifacts. They may explicitly report/skip only when a validation utility is genuinely unavailable in an environment where that utility is not expected; do not silently convert validation failures into success. On the current hosted targets, prefer using the native validators available on each platform (`systemd-analyze verify` or the script's documented equivalent on Ubuntu, and `plutil -lint` on macOS).

Because P1-003/P1-004 are P1, their absence must not block completion of the P0 lifecycle feature. But once either helper is added, add its matching CI step in the same change so it does not become dead, local-only tooling. The existing Rust structural tests and real-signal tests remain required; the shell-script checks are additional packaging/deployment validation, not replacements for those tests.

---

*Once filled in, share this file back (or paste the answers) to begin implementation.*
