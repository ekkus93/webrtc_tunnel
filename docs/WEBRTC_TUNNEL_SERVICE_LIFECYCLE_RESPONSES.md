# Responses — WebRTC Tunnel Service Lifecycle Spec/TODO Review

Covers: `docs/WEBRTC_TUNNEL_SERVICE_LIFECYCLE_SPEC.md` and `docs/WEBRTC_TUNNEL_SERVICE_LIFECYCLE_TODO.md`

Fill in the `A:` line under each question, then share this file back so implementation can begin.

---

### 1

Q: Is a shutdown-aware variant of the test/session-hook API (`run_offer_daemon_with_transport_and_test_hook`) in scope for P0? The spec (§6.3) hedges with "may... if external integration tests need both [the session hook and cancellation]," but P0-018 (active offer session shutdown test) and P0-019 (shutdown-during-reconnect test) are exactly the kind of tests that existing test infrastructure normally drives via `OfferSessionTestHandle`/`session_hook` for fine-grained state control. Can P0-018/019 be fully implemented using only the two-node harness (real sockets, real timing) without needing the `session_hook`, or will a shutdown-aware test-hook variant be required to reliably force a reconnect/backoff window in test time?

A:

---

### 2

Q: For P0-013/spec §9.6 — the spec's literal instruction is that every configured offer listener gets `listen_state = stopped, last_error = null` after shutdown, which means a listener that was already in an `Error` state (e.g., it never successfully bound) would have its `last_error` discarded and be overwritten to `Stopped`. Should offer forwards that were already in an `Error` state (never successfully bound) be overwritten to `Stopped`/`last_error = null` on shutdown as currently specified, or should they retain their error info through shutdown?

A:

---

### 3

Q: Do you want this implemented and committed incrementally following the TODO's 17-step recommended sequence (separate commits per stage: token → shutdown-aware APIs → answer session propagation → answer drain state machine → offer accept-task ownership → offer session cancellation → offer reconnect cancellation → final status helpers → process signal adapters → wire binaries → lifecycle tests → systemd units → launchd plists → docs → Android P1 migration → packaging polish), or as one large pass? Given the size (27 P0 tasks spanning daemon core, both binaries, packaging, and docs), incremental commits would be the default unless you say otherwise.

A:

---

### 4

Q: Should the new `scripts/check-systemd-units.sh` / `scripts/check-launchd-plists.sh` helper scripts (P1-003/P1-004) be wired into `.github/workflows/ci.yml` as part of this work, or left as manual/local-only helpers for now? Note: CI already runs `cargo test --workspace --all-targets` on both `ubuntu-latest` and `macos-latest`, so the real-signal integration tests (P0-020) and plist/unit structural Rust tests will get genuine automatic coverage on both platforms regardless of this answer — this question is specifically about the two standalone shell scripts.

A:

---

*Once filled in, share this file back (or paste the answers) to begin implementation.*
