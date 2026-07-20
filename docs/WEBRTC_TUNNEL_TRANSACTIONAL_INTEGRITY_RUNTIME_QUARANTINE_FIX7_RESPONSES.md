# FIX7 Spec/TODO Review — Responses

Covers the `/spec-todo` review of:
- `docs/WEBRTC_TUNNEL_TRANSACTIONAL_INTEGRITY_RUNTIME_QUARANTINE_FIX7_SPEC.md`
- `docs/WEBRTC_TUNNEL_TRANSACTIONAL_INTEGRITY_RUNTIME_QUARANTINE_FIX7_TODO.md`

Fill in each `A:` line, then share this file back so implementation can begin.

---

## 1. Misplaced review-source files

Q: The spec (line 6) and TODO (line 7) both cite `docs/review-source/WEBRTC_TUNNEL_FIX6_CODE_REVIEW_2026-07-20.md` as the primary review source, and the spec's §2 claims it "is included in the handoff bundle at the exact repository path... not intentionally omitted." This is false as committed: `docs/review-source/` doesn't exist. The actual file landed at `docs/WEBRTC_TUNNEL_FIX6_CODE_REVIEW_2026-07-20(1).md` (wrong directory, stray "(1)" suffix), and the handoff manifest itself is similarly misplaced at `docs/WEBRTC_TUNNEL_FIX7_HANDOFF_MANIFEST.md` instead of `docs/review-source/WEBRTC_TUNNEL_FIX7_HANDOFF_MANIFEST.md`. Should I fix this myself (move/rename both files into `docs/review-source/` with their documented clean names) before implementation starts, or update the spec/TODO's citations to match where the files actually are?

A:

---

## 2. `enterNativeRuntimeQuarantine` — spec vs TODO disagree

Q: The spec (§6.9) and TODO (P0-007-A) give different bodies for the same function. The spec's version only calls `reporter.publishError(...)`; the TODO's version also calls `repository.setLocalError(...)` to update durable repository state before reporting. Per the spec's own precedence rule the spec should win on conflicts, but the TODO's fuller version looks like the one that actually satisfies FIX7-INV-007 ("a durable visible error must explain that a verified stop is required"). Should the durable `repository.setLocalError(...)` call be included, even though the spec's own snippet omits it?

A:

---

## 3. `requireRuntimeStartAllowed()` — new `requireReady()` method or adapt existing gate?

Q: The spec/TODO's target code for `requireRuntimeStartAllowed()` calls `appInitializationCoordinator.requireReady()`, but `AppInitializationCoordinator` (`data/AppInitialization.kt`) currently exposes only `start()`, `initialize()`, and `state` — no `requireReady()`. The real existing gate (`TunnelForegroundService.kt:527`, already named `requireRuntimeStartAllowed`) checks `state.value` some other way today. Should I add a new `requireReady()` method to `AppInitializationCoordinator` (folding in the existing inline `state` check), or is the snippet illustrative only, meaning the quarantine check should be spliced into the current `requireRuntimeStartAllowed` body without adding that new coordinator method?

A:

---

## 4. Offer-shutdown precedence — is the "unrequested, error-free exit" branch safe to fold into `Ok`?

Q: The spec (§6.11) and TODO (P0-008-B) give different-shaped precedence pseudocode for the offer cooperative-shutdown fix. The TODO's version has a `(None, Ok(()), false)` arm — no primary error, cleanup succeeded, but shutdown was **never requested** — that resolves to `Ok(())`. That's the case where the offer's worker loop exited on its own, unrequested, with no captured error, and it gets treated the same as a genuine cooperative-shutdown-while-Listening. Is an unrequested, error-free worker exit actually provably unreachable given the daemon's current loop structure (so this branch is dead/defensive-only), or should that specific combination be treated as its own distinct error/status rather than folded into success?

A:

---

## 5. Should `crates/p2p-signaling/src/messages.rs:251` be added to P0-010's scope?

Q: P0-010's file list names `crates/p2p-signaling/src/transport/codec.rs` and `crates/p2p-signaling/src/error.rs`, but there is a second, identically-shaped pre-epoch panic site the task doesn't list: `crates/p2p-signaling/src/messages.rs:251` (`current_time_ms()`, `.expect("system time is before unix epoch")`) — confirmed present in the current tree. Should `messages.rs` be added to P0-010's scope alongside `codec.rs`, and is `error.rs`'s inclusion in the file list accurate (does it actually contain a clock call) or a copy-paste artifact?

A:

---

## 6. Execution pacing — straight through, or checkpointed?

Q: This TODO is roughly 1.5–2x the size of FIX6 (10 P0 tasks + 5 P1 + 3 P2, each with 5–13 named exact tests — 150+ new/renamed test cases total), and FIX6 itself took many sessions across about two weeks to complete. Do you want FIX7 executed straight through in the stated Stage A–F order as one continuous effort, or checkpointed (e.g., pause for your review/signoff after Stage B or Stage C) before continuing?

A:

---

## 7. P0-004 and P0-005 duplicate near-identical transaction scaffolding — intended, or should it be shared?

Q: P0-004 (setup persistence transaction) and P0-005 (reset) each independently reimplement nearly identical "snapshot → mutate stages → roll back under `NonCancellable` → report incomplete" control flow, with no shared abstraction between `SetupPersistenceCoordinator` and `TransactionalReset`. This is consistent with the spec's §4.2 "no general refactoring unrelated to a named requirement," but is the duplication intentional (keep them separate, minimal-diff), or is a shared "staged transaction runner" implicitly expected even though it isn't spec'd?

A:
