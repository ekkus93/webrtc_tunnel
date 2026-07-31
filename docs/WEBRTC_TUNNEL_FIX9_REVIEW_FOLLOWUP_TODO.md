# WebRTC Tunnel — FIX9 Review Follow-up TODO

**Target branch:** `master`  
**Review baseline:** FIX9 closure commit `9503b4aba0f3046446a0392522fa7eac242a9343`  
**Implementation start:** `aa66695396cba84f6f9e066fb132fd6b37df158f`  
**Final candidate:** the exact `[full-signoff]` commit containing this ledger  
**Purpose:** close the non-blocking issues found during the post-FIX9 comprehensive review without reopening core FIX9.

This file is the follow-up closure ledger. The original detailed task/subtask instructions remain in Git history at `aa66695396cba84f6f9e066fb132fd6b37df158f`.

## Non-negotiable rules

- [x] No FIX9 gate was weakened.
- [x] Required behavior is not converted to warnings.
- [x] No retry-only fix, silent skip, required-operation `|| true`, or timeout inflation was introduced.
- [x] Release artifacts are not claimed unless the workflow actually builds, verifies, and uploads them.
- [x] New diagnostics are bounded and do not capture setup secrets.

---

# P0 — Release artifact contract

## P0-001 — Decide and encode the Android release-artifact contract

- [x] Inspected `.github/workflows/ci.yml` and `android/app/build.gradle.kts`.
- [x] Confirmed the current tag workflow publishes Rust CLI/daemon archives only.
- [x] Confirmed the Android project has no production release signing/keystore/fingerprint/provenance contract.
- [x] Chose fail-closed documentation correction rather than uploading unsigned or debug-signed APK/AAB files.
- [x] Updated `docs/WEBRTC_TUNNEL_STALE_SETUP_RESULT_CONTRACT_FIX9_TODO.md` so it does not claim APK/AAB validation.
- [x] Added `docs/WEBRTC_TUNNEL_ANDROID_SIGNED_RELEASE_ARTIFACTS_TODO.md` for future properly signed Android publication.

### Decision

The current release contract is **Rust archives only**. Android APK/AAB publication remains out of scope until production signing ownership, protected credentials, signature verification, version/tag integrity, checksums/provenance, and negative release tests are implemented.

### Acceptance

- [x] No current document treats nonexistent Android packaging as success.
- [x] A normal `[full-signoff]` commit is not represented as tag-artifact validation.
- [x] Unsigned/debug-signed Android output cannot be mislabeled as a production release asset by this follow-up.

## P0-002 — Tag-only Android APK/AAB packaging

- [x] Marked **not applicable to the current release contract**.
- [x] Preserved the work as a separate signed-release TODO rather than adding an unsafe unsigned packaging shortcut.

---

# P1 — Android E2E probe evidence

## P1-001 — Require healthy-path PING/PONG evidence

- [x] Decided explicit data-plane probe evidence is a required Android emulator E2E success condition.
- [x] Kept marker delivery as an independent required condition.
- [x] Extracted `verify_probe_evidence` into `tests/e2e/lib/probe_evidence.sh`.
- [x] Updated `tests/e2e/android_tunnel_e2e.sh` to fail nonzero when the expected PING/PONG line is absent.
- [x] Delayed the final PASS message until marker delivery and probe evidence both succeed.
- [x] Kept BLACK_HOLE mode behavior intact.
- [x] Missing evidence prints only bounded probe/data-channel/error lines.
- [x] A missing answer-log file is a visible failure.

Implementation commits:

- `1bcee719fb872fa495c0a17d1938d34696b5b23d`
- `e0a8698df4d6255a4ae71226978e60d0981d17d1`

## P1-002 — Focused probe-contract fixture

- [x] Added `tests/e2e/probe_evidence_test.sh`.
- [x] A fixture containing `received tunnel PING; sending PONG` passes.
- [x] A fixture without the line fails.
- [x] An absent answer log fails.
- [x] Diagnostics are asserted.
- [x] The full E2E script and fixture use the same helper.
- [x] Added `ProbeEvidenceShellContractTest` so ordinary Gradle `check` executes the shell fixture.

Implementation commits:

- `8a9d9a5fb504541110b29b8f5e164420e4e43b22`
- `fbabe71a725c6aa98bb0fd84ffc94ffccaaa6a55`

---

# P2 — Static enforcement

## P2-001 — Inventory and classify enforcement

- [x] Reviewed `Fix9SourceContractAuditTest` and the real Android Lint `CheckResult` fixture.
- [x] Added `docs/review-source/WEBRTC_TUNNEL_FIX9_FOLLOWUP_STATIC_CONTRACT_INVENTORY.md`.
- [x] Distinguished type-aware lint, source tripwires, and runtime proof.
- [x] Listed every known authoritative result/receipt API in scope.
- [x] Documented accepted and forbidden consumption forms.
- [x] Documented that the lightweight scanner is not an AST/data-flow proof.

## P2-002 — Stronger ignored-result enforcement

- [x] Retained `@CheckResult` plus Android Lint promoted to error for ordinary bare ignored calls.
- [x] Retained `CheckResultEnforcementFixtureTest` using the real Lint detector.
- [x] Added balanced-call scanning for fake `.also { }` and `.apply { }` consumption.
- [x] Added scanning for `run { authoritativeCall(); Unit }` discard wrappers.
- [x] Added positive fixtures for legitimate result propagation/inspection.
- [x] Added negative fixtures for each fake-consumption form.
- [x] Violations identify the production file and API.
- [x] Enforcement runs in the ordinary Gradle unit/check gate.
- [x] `_` discard assignment was documented as not an applicable Kotlin production exception.

## P2-003 — Cancellation-first mutation handling

- [x] Added a finite mutation-sensitive production-file inventory.
- [x] Executable `runCatching { ... }` is rejected in those files.
- [x] Executable `catch (...: Throwable)` is rejected in those files.
- [x] Existing selected-subclass and pre-`try` `getOrThrow()` negative fixtures remain active.
- [x] The scanner strips comments and quoted values before checking forbidden executable patterns.
- [x] Added a fixture proving explanatory comments do not create false positives.

## P2-004 — Setup-operation token propagation

- [x] Audits cover identity path import.
- [x] Audits cover identity URI import and discarded private-byte wiping.
- [x] Audits cover identity generation.
- [x] Audits cover remote public identity validation/import.
- [x] Audits cover forward upsert/delete.
- [x] Audits cover validation navigation.
- [x] Audits cover final setup save.
- [x] Audits cover start-from-review freshness.
- [x] Runtime cancellation tests remain the behavioral proof; source checks remain regression tripwires.

Implementation commits:

- `b33858e621d9dc7d66f2afdf89c27666770be346`
- `f213fc98ef459a4b8fde72be7e2768bf0f253c37`

---

# P3 — Stored identity canonical-public behavior

## P3-001 — Make the compatibility fallback explicit and bounded

- [x] Inspected stored identity resolution in `SetupSaveController`.
- [x] Kept canonical public output from private validation authoritative when present.
- [x] Kept the coherently-read stored public file only as a legacy rendering fallback when canonical output is absent.
- [x] Confirmed the peer ID always comes from validating decrypted private identity.
- [x] Confirmed the stored public fallback cannot override that peer ID.
- [x] Confirmed local-peer mismatch blocks persistence before config commit.
- [x] Confirmed an existing-identity save does not silently rewrite the identity pair.
- [x] Added `SetupStoredIdentityCanonicalContractTest` for canonical precedence.
- [x] Added a negative test proving a mismatched/malicious stored public rendering cannot authorize a different private peer.
- [x] Documented the compatibility boundary in the test and implementation report.

Implementation commit:

- `4641ed0e300a8fa3055b071c66b9f8006487f849`

---

# P4 — Documentation and evidence

## P4-001 — Follow-up evidence

- [x] Added `docs/review-source/WEBRTC_TUNNEL_FIX9_REVIEW_FOLLOWUP_IMPLEMENTATION_REPORT.md`.
- [x] Recorded implementation commits and decisions.
- [x] Explicitly stated that Android release artifacts are not in the current contract.
- [x] Explicitly stated that PING/PONG evidence is required for healthy Android E2E success.
- [x] Distinguished commit-level validation from tag-only Rust release artifacts.
- [x] Did not claim tag artifact validation from a commit-only run.

## P4-002 — Exact-SHA final validation rule

The final candidate is the exact `[full-signoff]` commit containing this ledger. A parent, sibling, or rerun from another SHA is not interchangeable. This follow-up is closed only when the authoritative status records for that exact SHA show:

- `ci/rc-diagnostics`: `success`
- `ci/full-matrix`: `success`
- `ci/release-candidate`: `success`
- Rust lint/tests and Linux/macOS package/lifecycle gates: `success`
- Android full Gradle `check`: `success`
- foreground-service stop truthfulness suite: `success`
- second Android unit invocation: `success`
- Docker real-data-path/lifecycle E2E: `success`
- Android emulator real-data-path E2E: `success`, including marker delivery and explicit PING/PONG evidence
- broker-secret instrumentation: `success` under the final `[full-signoff]` policy

- [x] The closure mechanism fails closed unless all records refer to the exact final commit.
- [x] Tag-only Rust artifact jobs are expected to skip on this commit candidate.
- [x] No Android tag-artifact success is required or claimed.

The CI status issues and commit status API are the machine-readable, non-circular closure evidence. Embedding run IDs after validation would create a new SHA requiring another complete signoff, so this ledger identifies the candidate by self-reference and requires external exact-SHA status agreement.

---

## Files added or changed

- `tests/e2e/lib/probe_evidence.sh`
- `tests/e2e/probe_evidence_test.sh`
- `tests/e2e/android_tunnel_e2e.sh`
- `android/app/src/test/java/com/phillipchin/webrtctunnel/ProbeEvidenceShellContractTest.kt`
- `android/app/src/test/java/com/phillipchin/webrtctunnel/Fix9FollowupContractAuditTest.kt`
- `android/app/src/test/java/com/phillipchin/webrtctunnel/viewmodel/SetupStoredIdentityCanonicalContractTest.kt`
- `docs/WEBRTC_TUNNEL_STALE_SETUP_RESULT_CONTRACT_FIX9_TODO.md`
- `docs/WEBRTC_TUNNEL_ANDROID_SIGNED_RELEASE_ARTIFACTS_TODO.md`
- `docs/review-source/WEBRTC_TUNNEL_FIX9_FOLLOWUP_STATIC_CONTRACT_INVENTORY.md`
- `docs/review-source/WEBRTC_TUNNEL_FIX9_REVIEW_FOLLOWUP_IMPLEMENTATION_REPORT.md`
- this ledger

## Final disposition

Implementation is complete. Commit-level closure is contingent solely on the exact candidate satisfying every status requirement above; no unchecked implementation subtask remains.
