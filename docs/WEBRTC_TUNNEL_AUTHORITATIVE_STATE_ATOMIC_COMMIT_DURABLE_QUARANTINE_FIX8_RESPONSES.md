# FIX8 Responses — WEBRTC_TUNNEL_AUTHORITATIVE_STATE_ATOMIC_COMMIT_DURABLE_QUARANTINE_FIX8 (SPEC + TODO)

Fill in each `A:` line, then share this file back (or paste the answers). Implementation will not begin until these are answered. Items 1 and 4 already carry decisions captured during the review; confirm or change them.

---

1. Q: Pacing. FIX8 is 16 tasks / 10 P0 blockers / ~250 checkboxes / ~130 named tests — a multi-day effort. The TODO defines Stages A–F but, unlike FIX7, gives no explicit stop points. Should I (a) checkpoint per stage — stop and report for review after each of Stage A–F; (b) checkpoint after Stage B and Stage D only (FIX7's exact 2-checkpoint cadence); or (c) run straight through with one commit per task, no stops, until all P0/P1/P2 are done or I hit a blocker? Default absent direction: (b).
   A:

2. Q: Validation environment. Signoff (P2-002-C/D/E/F) requires gradle, cargo, Docker E2E, Android emulator E2E, and green GitHub Actions CI on the exact signoff SHA. The FIX7 reviewer could run none of these (no network, no cargo/rustc, gradle wrapper download failed). Which can THIS session actually execute: `cargo` (fmt/clippy/test); Android `./gradlew`; Docker real-broker E2E; Android emulator/ADB (for setup-wizard, live metered-to-unmetered, and service-recreation-while-quarantined E2E); outbound network for `git push` / CI? Anything unavailable will be marked `NOT RUN: <reason>`, never PASS-by-inspection (per spec §10).
   A:

3. Q: Broker-secret permissions (P0-008-A). `android.system.Os.chmod`/`Os.stat` are not faithful under JVM/Robolectric unit tests. Confirm the intended design: an injectable permission enforcer/verifier — real `Os.chmod`/`Os.stat` `0600` enforcement on device/emulator, with a faked enforcer for JVM unit tests that exercises the `broker_secret_permissions_failed` path and the owner-only verification. Is that the intended approach?
   A:

4. Q: Static enforcement mechanism (P2-001). The repo CLAUDE.md policy forbids `@Suppress`/baselines/threshold-lowering. P2-001 wants permanent rules forbidding production `runCatching`, bare authoritative `File.delete()`, ignored `mkdirs`/`setReadable`/`setWritable`, setup-controller authoritative mutation calls, `unix_ms:0`, `snapshot.bytes ?: ByteArray(0)`, and config writes inside candidate/workspace scope. Should enforcement be (a) JVM test / source-scan guards that scan production sources and fail on forbidden patterns with exact allowlisted exceptions, or (b) genuine custom detekt rules compiled into the build? Review decision captured: (a) test/source-scan guards.
   A: Test/source-scan guards (captured during review — confirm or change).

5. Q: Doc-path discrepancy. The spec (line 6), TODO (P2-002-A, lines 1514–1518), and manifest reference the FIX7 review and FIX8 manifest at `docs/review-source/WEBRTC_TUNNEL_FIX7_CODE_REVIEW_2026-07-21.md` and `docs/review-source/WEBRTC_TUNNEL_FIX8_HANDOFF_MANIFEST.md`, but the pull placed both at `docs/` root. Signoff P2-002-A checks the `review-source/` paths. Resolve by (a) `git mv` both files into `docs/review-source/` to match the documents, or (b) leave them at `docs/` root and amend the spec/TODO/manifest references?
   A: Move both into docs/review-source/ (captured during review — confirm or change).

6. Q: Baseline confirmation. The reviewed baseline was `webrtc_tunnel-master_2607211131.zip` (no `.git`). Current HEAD is `6eb1085`. The FIX7 review references `OfferCoordinator.kt`/`AppDependencies.kt`, so it postdates the OfferCoordinator split, but since then `status.rs`, `p2pctl/main.rs`, and `TransactionalResetCoordinatorTest.kt` were also split (none in FIX8's material scope). Confirm FIX8 should target current HEAD `6eb1085` and treat the review's line numbers as approximate anchors (adapt to current code, do not paste over changed code) — per the manifest's own guidance.
   A:

7. Q: Nullable diagnostic timestamp — breaking schema change (SPEC §5.8 / §3.8, P0-010). Changing `AndroidLogEvent.unix_ms` from `u64` to `Option<u64>` changes the Rust→JNI/C-ABI→Kotlin serialization schema. Spec §11 explicitly authorizes dropping zero-timestamp compatibility. Confirm no out-of-tree consumer reads the old `unix_ms:0` JSON that would break, i.e. the change is safe to make unconditionally.
   A:

8. Q: Evidence artifacts to create. Implementation must author `docs/review-source/WEBRTC_TUNNEL_FIX8_IMPLEMENTATION_REPORT.md` (required Claude Code output, P2-002-A) and write inventory logs under `.aiworkflow/logs/fix8/` (does not exist yet; not gitignored). Confirm both should be created and committed as part of the work (the `.aiworkflow/logs/fix8/` initial inventories are the first concrete step of P0-001 per the TODO's "Required initial inventories").
   A:

---

Please fill in the blank `A:` lines (and confirm items 4 and 5) and share back. No spec, TODO, or source files will be modified until then.
