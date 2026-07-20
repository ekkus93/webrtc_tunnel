# FIX7 Spec/TODO Review — Responses

Covers the `/spec-todo` review of:
- `docs/WEBRTC_TUNNEL_TRANSACTIONAL_INTEGRITY_RUNTIME_QUARANTINE_FIX7_SPEC.md`
- `docs/WEBRTC_TUNNEL_TRANSACTIONAL_INTEGRITY_RUNTIME_QUARANTINE_FIX7_TODO.md`

The answers below are binding FIX7 clarifications. They supersede conflicting illustrative snippets in the spec or TODO while preserving the documents' stated invariants.

---

## 1. Misplaced review-source files

Q: The spec (line 6) and TODO (line 7) both cite `docs/review-source/WEBRTC_TUNNEL_FIX6_CODE_REVIEW_2026-07-20.md` as the primary review source, and the spec's §2 claims it "is included in the handoff bundle at the exact repository path... not intentionally omitted." This is false as committed: `docs/review-source/` doesn't exist. The actual file landed at `docs/WEBRTC_TUNNEL_FIX6_CODE_REVIEW_2026-07-20(1).md` (wrong directory, stray "(1)" suffix), and the handoff manifest itself is similarly misplaced at `docs/WEBRTC_TUNNEL_FIX7_HANDOFF_MANIFEST.md` instead of `docs/review-source/WEBRTC_TUNNEL_FIX7_HANDOFF_MANIFEST.md`. Should I fix this myself (move/rename both files into `docs/review-source/` with their documented clean names) before implementation starts, or update the spec/TODO's citations to match where the files actually are?

A: **Move and rename the files into the documented `docs/review-source/` paths. Do not change the spec/TODO citations to match the accidental names.** The handoff's intended repository layout is authoritative; the `(1)` suffix and placement directly under `docs/` are copy/import artifacts.

Make this a docs-only prerequisite commit before Stage A:

```bash
mkdir -p docs/review-source

git mv \
  'docs/WEBRTC_TUNNEL_FIX6_CODE_REVIEW_2026-07-20(1).md' \
  docs/review-source/WEBRTC_TUNNEL_FIX6_CODE_REVIEW_2026-07-20.md

git mv \
  docs/WEBRTC_TUNNEL_FIX7_HANDOFF_MANIFEST.md \
  docs/review-source/WEBRTC_TUNNEL_FIX7_HANDOFF_MANIFEST.md
```

If either source file is untracked, use `mv` and then `git add` instead of `git mv`. Remove any duplicate stale copies after verifying the destination files are byte-identical. The review report and manifest contents do not need checksum changes merely because they moved; update the manifest only if file contents change. Before implementation, verify:

```bash
test -f docs/review-source/WEBRTC_TUNNEL_FIX6_CODE_REVIEW_2026-07-20.md
test -f docs/review-source/WEBRTC_TUNNEL_FIX7_HANDOFF_MANIFEST.md
rg -n 'WEBRTC_TUNNEL_FIX6_CODE_REVIEW_2026-07-20' \
  docs/WEBRTC_TUNNEL_TRANSACTIONAL_INTEGRITY_RUNTIME_QUARANTINE_FIX7_*.md \
  docs/review-source/WEBRTC_TUNNEL_FIX7_HANDOFF_MANIFEST.md
git status --short
```

Do not begin production changes while the documented review source is still missing.

---

## 2. `enterNativeRuntimeQuarantine` — spec vs TODO disagree

Q: The spec (§6.9) and TODO (P0-007-A) give different bodies for the same function. The spec's version only calls `reporter.publishError(...)`; the TODO's version also calls `repository.setLocalError(...)` to update durable repository state before reporting. Per the spec's own precedence rule the spec should win on conflicts, but the TODO's fuller version looks like the one that actually satisfies FIX7-INV-007 ("a durable visible error must explain that a verified stop is required"). Should the durable `repository.setLocalError(...)` call be included, even though the spec's own snippet omits it?

A: **Yes. The direct durable `repository.setLocalError(...)` update is required and this response corrects the omission in the specification snippet.** FIX7-INV-007 requires quarantine to remain visible even when logging, notification rendering, or the reporter throws. Therefore, all safety state and durable repository state must be committed before any fallible reporting operation.

Use the canonical durable code and a fixed message:

```kotlin
private fun enterNativeRuntimeQuarantine(
    code: String,
    message: String,
) {
    nativeStopVerified.set(false)
    nativeRuntimeUncertain.set(true)
    invalidatePendingPolicyRetry()

    repository.setLocalError(
        code = "native_runtime_quarantined",
        message =
            "Native runtime state is uncertain; " +
                "a verified explicit stop is required before restart",
        state = ServiceState.Error,
    )

    publishServiceDiagnosticSafely(
        code = code,
        message = SensitiveDataRedactor.redactText(message),
    )
}
```

There is an important implementation constraint: the current `StatusReporter.publishError(...)` also calls `repository.setLocalError(...)`. Do **not** call that method after setting the canonical quarantine error if it would overwrite `lastError` with a narrower code such as `manual_pause_stop_failed`. Split reporting so the second step only logs and updates the notification, or add an explicit reporter option that does not mutate repository state. The durable repository error remains `native_runtime_quarantined`; the operation-specific code is secondary diagnostic context.

Required tests must prove both cases:

- a throwing reporter still leaves `nativeRuntimeUncertain == true`, `nativeStopVerified == false`, pending retry cleared, and repository state set to `native_runtime_quarantined`;
- a successful reporter does not replace the canonical durable quarantine code with the operation-specific diagnostic code.

---

## 3. `requireRuntimeStartAllowed()` — new `requireReady()` method or adapt existing gate?

Q: The spec/TODO's target code for `requireRuntimeStartAllowed()` calls `appInitializationCoordinator.requireReady()`, but `AppInitializationCoordinator` (`data/AppInitialization.kt`) currently exposes only `start()`, `initialize()`, and `state` — no `requireReady()`. The real existing gate (`TunnelForegroundService.kt:527`, already named `requireRuntimeStartAllowed`) checks `state.value` some other way today. Should I add a new `requireReady()` method to `AppInitializationCoordinator` (folding in the existing inline `state` check), or is the snippet illustrative only, meaning the quarantine check should be spliced into the current `requireRuntimeStartAllowed` body without adding that new coordinator method?

A: **Adapt the existing `requireRuntimeStartAllowed()` gate; do not add `AppInitializationCoordinator.requireReady()` solely to match the illustrative snippet.** The current coordinator's `state` is already the canonical readiness source, and the service already has one shared gate used by start/resume paths. Adding another API would create unnecessary churn and risk two subtly different admission policies.

Keep the existing exception types and use one `when` body. Put quarantine first so runtime uncertainty cannot be hidden by a simultaneous initialization condition:

```kotlin
private fun requireRuntimeStartAllowed(): Result<Unit> {
    val readiness = appInitialization.state.value
    return when {
        nativeRuntimeUncertain.get() ->
            Result.failure(
                NativeRuntimeQuarantinedException(
                    "Native runtime state is uncertain; " +
                        "explicit STOP is required before restart.",
                ),
            )

        readiness is AppInitializationState.Failed ->
            Result.failure(
                AppInitializationIncompleteException(
                    readiness.code,
                    readiness.message,
                ),
            )

        readiness !is AppInitializationState.Ready ->
            Result.failure(
                AppInitializationIncompleteException(
                    "app_initialization_failed",
                    "App initialization has not completed yet.",
                ),
            )

        else -> Result.success(Unit)
    }
}
```

Every start, resume, reconnect, and pending-policy-retry path must continue to call this one gate before any native call. Add a focused test where initialization is also non-ready but quarantine is set; the quarantine result should win and no native operation may occur. A future `requireReady()` helper is acceptable only as a separate justified refactor with direct tests, not as a FIX7 requirement.

---

## 4. Offer-shutdown precedence — is the "unrequested, error-free exit" branch safe to fold into `Ok`?

Q: The spec (§6.11) and TODO (P0-008-B) give different-shaped precedence pseudocode for the offer cooperative-shutdown fix. The TODO's version has a `(None, Ok(()), false)` arm — no primary error, cleanup succeeded, but shutdown was **never requested** — that resolves to `Ok(())`. That's the case where the offer's worker loop exited on its own, unrequested, with no captured error, and it gets treated the same as a genuine cooperative-shutdown-while-Listening. Is an unrequested, error-free worker exit actually provably unreachable given the daemon's current loop structure (so this branch is dead/defensive-only), or should that specific combination be treated as its own distinct error/status rather than folded into success?

A: **Do not fold an unrequested, error-free offer-loop exit into success. Treat it as an invariant violation and return an error.** The current loop's `Ok(())` exits appear to be shutdown-driven, so `(no primary error, cleanup success, shutdown not requested)` should be unreachable today. Encoding it as `Ok(())` would nevertheless turn a future accidental early return or worker-supervisor defect into false clean shutdown.

There is also a subtle ordering issue: the current finalizer calls `shutdown.request_shutdown()` unconditionally before merging results. Therefore, checking `shutdown.is_shutdown_requested()` *after* entering the finalizer cannot distinguish a genuine requested shutdown from an unexpected clean loop exit. Capture the exit reason before the finalizer mutates the token, preferably as a typed value. If a larger loop rewrite is undesirable, capture `shutdown_requested_at_loop_exit` immediately after `run_result` returns and before `shutdown.request_shutdown()`.

The binding result precedence is:

```rust
match (
    run_result,
    cleanup_result,
    closed_result,
    shutdown_requested_at_loop_exit,
) {
    (Err(primary), _, _, _) => Err(primary),
    (Ok(()), Err(error), _, _) => Err(error),
    (Ok(()), Ok(()), Err(error), _) => Err(error),
    (Ok(()), Ok(()), Ok(()), true) => Ok(()),
    (Ok(()), Ok(()), Ok(()), false) => Err(DaemonError::Logging(
        "offer daemon exited without a shutdown request".to_owned(),
    )),
}
```

Preserve and log secondary cleanup or terminal-status failures when a primary error already exists, as the current merge helper does. Add a pure-helper test such as `unrequestedCleanOfferExitIsFailure` in addition to the real Listening-with-no-peer cooperative-shutdown test.

---

## 5. Should `crates/p2p-signaling/src/messages.rs:251` be added to P0-010's scope?

Q: P0-010's file list names `crates/p2p-signaling/src/transport/codec.rs` and `crates/p2p-signaling/src/error.rs`, but there is a second, identically-shaped pre-epoch panic site the task doesn't list: `crates/p2p-signaling/src/messages.rs:251` (`current_time_ms()`, `.expect("system time is before unix epoch")`) — confirmed present in the current tree. Should `messages.rs` be added to P0-010's scope alongside `codec.rs`, and is `error.rs`'s inclusion in the file list accurate (does it actually contain a clock call) or a copy-paste artifact?

A: **Yes. Add `crates/p2p-signaling/src/messages.rs` to P0-010 explicitly.** It contains a second production pre-epoch panic and is part of the correctness-sensitive message-construction path. Both `messages.rs` and `transport/codec.rs` must become fallible and use the shared clock seam.

`crates/p2p-signaling/src/error.rs` is not itself a clock-call inventory hit. Its inclusion is intentional only as a possible typed-error propagation site. Clarify the task's file list rather than treating it as another panic location:

```text
crates/p2p-core/src/time.rs
crates/p2p-core/src/error.rs                  # if ProtocolError gains ClockUnavailable
crates/p2p-signaling/src/messages.rs          # message-builder clock panic
crates/p2p-signaling/src/transport/codec.rs   # replay/freshness clock panic
crates/p2p-signaling/src/error.rs             # propagation/conversion if required
```

Preferred error shape: add a fixed `ProtocolError::ClockUnavailable` or an equivalently typed, non-secret-bearing core clock error, make `InnerMessageBuilder::build`/`ack` return `Result`, and let `SignalingError` carry it through the existing core-protocol conversion. If that design means `p2p-signaling/src/error.rs` needs no code change, leave it unchanged and record that it was reviewed as a propagation site. Do not invent a second redundant clock-error variant merely to force a modification to that file.

Update tests and callers for both paths. The static/source inventory must prove that neither panic remains.

---

## 6. Execution pacing — straight through, or checkpointed?

Q: This TODO is roughly 1.5–2x the size of FIX6 (10 P0 tasks + 5 P1 + 3 P2, each with 5–13 named exact tests — 150+ new/renamed test cases total), and FIX6 itself took many sessions across about two weeks to complete. Do you want FIX7 executed straight through in the stated Stage A–F order as one continuous effort, or checkpointed (e.g., pause for your review/signoff after Stage B or Stage C) before continuing?

A: **Use checkpoints. Do not execute FIX7 as one uninterrupted multi-week change set.** Each individual task must still be completed in the stated order with a green focused test/build and a scoped commit; checkpoints are review gates, not permission to leave a task half-implemented or knowingly red.

Use this pacing:

1. **Checkpoint 1 — after Stages A and B.** Stop after the operation-admission foundation, pure rendering/workspace work, setup transaction, reset cancellation rollback, and identity atomicity are green. Provide the task-to-commit map, focused test output, inventories, and any deviations. This is the most important architecture review because later work depends on these transaction boundaries.
2. **Checkpoint 2 — after Stages C and D.** Stop after runtime quarantine, cooperative offer shutdown, fail-closed network handling, and repository-wide clock correction are green. Include Rust and Android focused evidence plus the real offer shutdown test result.
3. **Final pass — Stages E and F.** After checkpoint 2 approval, complete integration truthfulness, static enforcement, full suites, emulator/device/E2E evidence, and final signoff.

Claude Code should not wait for review between ordinary subtasks inside a checkpoint unless it discovers a contradiction that changes a binding invariant. It should record unresolved issues rather than silently choosing an unsafe fallback.

---

## 7. P0-004 and P0-005 duplicate near-identical transaction scaffolding — intended, or should it be shared?

Q: P0-004 (setup persistence transaction) and P0-005 (reset) each independently reimplement nearly identical "snapshot → mutate stages → roll back under `NonCancellable` → report incomplete" control flow, with no shared abstraction between `SetupPersistenceCoordinator` and `TransactionalReset`. This is consistent with the spec's §4.2 "no general refactoring unrelated to a named requirement," but is the duplication intentional (keep them separate, minimal-diff), or is a shared "staged transaction runner" implicitly expected even though it isn't spec'd?

A: **Keep `SetupPersistenceCoordinator` and `TransactionalResetCoordinator` separate for FIX7. A generic staged-transaction runner is not required and should not be introduced in the P0 implementation.** The apparent control-flow similarity hides materially different domain rules:

- setup owns plaintext/private-identity wiping, authorized-key canonicalization, broker-secret snapshots, config-last ordering, and a request containing optional stages;
- reset owns absent-versus-present config restoration, default replacement, setup-input reset, forwards restoration, and different visible result codes;
- their snapshot types, stage types, restore APIs, and rollback-failure semantics are intentionally auditable in domain-specific code.

A callback-heavy generic runner would make cancellation, secret wiping, and exact rollback ordering harder to inspect and test. Duplicating the small `withContext(NonCancellable) { rollback... }` orchestration is preferable to hiding safety behavior behind a framework.

Narrow, non-domain helpers may be shared only when they preserve transparency—for example, a redaction helper or a tiny immutable result type already used consistently. Do not introduce a generic `TransactionStage<T>`, reflection-based stage runner, callback registry, or common snapshot container during FIX7. Any broader deduplication can be proposed as a separate post-FIX7 refactor after both coordinators have complete direct negative-path coverage.
