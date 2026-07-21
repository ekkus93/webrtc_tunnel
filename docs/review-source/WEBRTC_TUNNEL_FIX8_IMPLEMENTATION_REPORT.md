# WebRTC Tunnel FIX8 Implementation Report

**Status:** In progress
**Baseline HEAD (recorded before first production change):** `050cb060a82e7a63d164cb1a8a57cbdfeb15b0ac`
**Target project:** `webrtc_tunnel`
**Binding documents:**
- `docs/WEBRTC_TUNNEL_AUTHORITATIVE_STATE_ATOMIC_COMMIT_DURABLE_QUARANTINE_FIX8_SPEC.md`
- `docs/WEBRTC_TUNNEL_AUTHORITATIVE_STATE_ATOMIC_COMMIT_DURABLE_QUARANTINE_FIX8_TODO.md`
- `docs/review-source/WEBRTC_TUNNEL_FIX7_CODE_REVIEW_2026-07-21.md`
- `docs/WEBRTC_TUNNEL_AUTHORITATIVE_STATE_ATOMIC_COMMIT_DURABLE_QUARANTINE_FIX8_RESPONSES.md` (binding answers)

This report records, per task: the commit SHA, the exact negative-path tests added, commands run with results, deviations, and any `NOT RUN` items. Checkpoint summaries are appended after Stage B and Stage D (pacing answer 1(c): run straight through, checkpoint in-report, do not stop for routine approval).

---

## Environment preflight (this session)

Probed before the first production change. Unlike the FIX7 review sandbox, this session has a full toolchain.

| Probe | Result |
|---|---|
| `cargo --version` | `cargo 1.94.1 (29ea6fb6a 2026-03-24)` — AVAILABLE |
| `rustc --version` | `rustc 1.94.1 (e408947bf 2026-03-25)` — AVAILABLE |
| `./gradlew --version` | `Gradle 8.7` — AVAILABLE |
| `docker info` | OK — AVAILABLE |
| `docker compose version` | `v5.1.3` — AVAILABLE |
| `adb version` | `1.0.41` — AVAILABLE |
| `adb devices -l` | `emulator-5554 device product:sdk_gphone64_x86_64` — EMULATOR RUNNING |
| `git remote -v` | `git@github.com:ekkus93/webrtc_tunnel.git` — AVAILABLE |
| `gh auth status` | Logged in to `ekkus93` — AVAILABLE |

Consequence: cargo, gradle, Docker E2E, emulator E2E, and CI push are all executable this session. No validation category is pre-emptively `NOT RUN`. Any category that later fails to run for a specific reason will be recorded here as `NOT RUN: <reason>` and will keep final signoff explicitly incomplete for that category (never PASS-by-inspection).

---

## Setup actions (pre-P0-001)

- Moved `WEBRTC_TUNNEL_FIX7_CODE_REVIEW_2026-07-21.md` and `WEBRTC_TUNNEL_FIX8_HANDOFF_MANIFEST.md` from `docs/` to `docs/review-source/` (`git mv`) so signoff path checks (P2-002-A) pass against the canonical paths. No stale copies remain at `docs/` root (verified).
- Created `.aiworkflow/logs/fix8/` and captured the TODO's required initial inventories:
  - `initial-head.txt`, `initial-status.txt`
  - `setup-authoritative-mutation-inventory.txt` (7 hits: SetupIdentityController ×2, SetupForwardsController ×2, ForwardsViewModel ×2, ImportExportService ×1)
  - `unsafe-api-inventory.txt` (30 hits)
  - `config-preference-inventory.txt` (187 hits)
  - `quarantine-inventory.txt` (30 hits)
  - `test-timing-inventory.txt` (29 hits)
  - `rust-diagnostic-fallback-inventory.txt` (jni_bridge.rs:206 `"unix_ms":0` production; c_abi.rs:160 recent_logs failure path; log_bridge.rs:206 `unix_ms: 0` is `#[cfg(test)]` only)

---

## Task log

### Setup / relocation — commit `dcbbf65`
`git mv` FIX7 review + FIX8 manifest into `docs/review-source/`; created `.aiworkflow/logs/fix8/` inventories + `environment-preflight.txt`; added this report skeleton. Evidence logs scanned for secret-value patterns (clean — source line references only).

### P0-001-A — SetupIdentityDraft private-byte holder — commit `fae7aa9`
- Added `viewmodel/SetupIdentityDraft.kt`: non-`data` `SetupIdentityDraft` + `DraftIdentityReplacement`, `internal`. Wipes previous bytes on `replace`/`clear`; `copyForSave()` returns an independently-owned copy; `replace` requires non-empty/non-blank fields.
- Added `SetupIdentityDraftTest.kt` (5 tests): `replaceWipesPreviousPrivateBytes`, `clearWipesPrivateBytesAndDropsReplacement`, `copyForSaveReturnsIndependentCopyThatDoesNotAffectDraft`, `copyForSaveIsNullWhenEmpty`, `replaceRejectsEmptyOrBlankFields`. Confirmed the test failed to compile first (`Unresolved reference 'SetupIdentityDraft'`), then passed 5/5 after implementation.
- Focused: `testDebugUnitTest --tests '*SetupIdentityDraftTest'` → tests=5 failures=0. ktlint (main+test) + detekt (all source sets) PASS.

### Sequencing note (documented reorder, permitted by TODO §0)
Reading `SetupSaveController.validateAndCommit`/`commitSetup` showed **P0-001-C (draft-only forwards) is coupled to P0-004-D (setup `Forwards` transactional stage)**. The wizard currently persists `forwards.json` eagerly via `ForwardsRepository.upsertWithReceipt/deleteWithReceipt`; the final setup transaction renders *enabled* forwards into `config.toml` but has no `Forwards` stage to write `forwards.json`. Removing the eager write in isolation would (a) leave `forwards.json` unpersisted by setup and (b) break existing forwards tests — violating the green-commit rule. Per spec §3.2 ("Forwards is a real transactional stage; it may not be committed by SetupForwardsController before Review save"), P0-001-C and P0-004-D will land together. P0-001-B (draft-only identity generate/URI/path import) and P0-001-D (final-save draft resolution) can still proceed first, since the import-path save resolution is already draft-shaped (`fromImport`).

### P0-001-B/D/E (identity) — commit `6a390ef`
- `SetupIdentityController` (now `internal`): generate / URI import / path import all validate → canonicalize → `SetupIdentityDraft.replace(...)`; **no `storeEncryptedIdentity`**, no `canonicalPublicIdentity.orEmpty()`, no `generated.peerId ?: input.localPeerId` fallback. New `requireCanonicalIdentity()` fails closed (fixed messages) on any missing canonical field. `importPublicIdentityFromUri` also lost its `runCatching` (explicit try/catch, a P1-001-C item done early).
- `SetupSaveController` (now `internal`): `resolveSaveIdentity` checks the draft first (`copyForSave()`, `fromImport=true`); the save-owned copy is wiped by `validateAndCommit`'s existing `finally`. Successful save clears the draft; a failed save retains it for retry. **The `importIdentityPath` re-read branch is retained as a fallback in this commit** and is removed in the P0-001-D follow-up (test migration required).
- `SetupViewModel`: owns `internal val identityDraft`; wipes it on `cancel()` and `onCleared()`. Removed the private `updateState` helper (folded into `applyState`) to stay within detekt TooManyFunctions after adding `onCleared`.
- Visibility: the two controllers + their `SetupViewModel` accessor vals became `internal` (single-module app) so they can take the `internal` draft without an exposure error; `ConfigurationMutationIntegrationTest`'s direct `SetupSaveController(...)` construction updated with the new param.
- Tests: renamed the two "persists identity" tests to assert **draft-only, nothing persisted**; added `SetupWizardNoIdentityMutationTest` (9 tests): generate/URI/path "does not mutate live identity" (byte-exact file snapshots), `missingCanonicalPublicIdentityFailsWithoutFallback`, `missingGeneratedPeerIdFailsWithoutPriorPeerFallback`, `replacingDraftIdentityWipesPreviousPrivateBytes`, `setupViewModelClearWipesDraftPrivateBytesOnCancel` (holds the live byte ref, asserts zeroed), `successfulFinalSaveWipesAndClearsDraft`, `failedFinalSaveRetainsRetryableDraft`. Added an `internal` byte-observation seam (`SetupIdentityDraft.peekLivePrivateBytesForTest`, spec §8).
- Redaction note: I initially wrapped the identity-import failure messages in `SensitiveDataRedactor.redactText`, but its identity-path regex over-redacts the bare word "identity" and mangled a safe validation message. Reverted to FIX7's verbatim behavior here; proper boundary redaction is the dedicated **P1-001-C** task ("SetupIdentityController uses raw exception/native messages").
- Validation: focused `SetupWizardNoIdentityMutationTest` 9/9; `SetupViewModelTest` 36/36, `SetupSaveControllerTest` 17/17, `ConfigurationMutationIntegrationTest` 6/6, integration/authorized-keys/workspace suites green; ktlint (main+test) + detekt (all source sets) PASS; **full `testDebugUnitTest` PASS** (1m11s).

### P0-001-D follow-up (import-path re-read removal) — commit `c3f3a07`
- `SetupSaveController.resolveSaveIdentity()`: removed the `current.importIdentityPath.isNotBlank()` re-read branch entirely (was reading the file a second time at save, a TOCTOU window) — now draft-first, then stored-identity fallback only, matching the spec's required order. Dropped `resolveSaveIdentity`'s now-unused `current` parameter and the file-scoped `importPrivateIdentity()` helper + its now-unused `readPrivateIdentityFile` import (would have failed detekt `UnusedPrivateMember`/ktlint unused-import otherwise — no suppression used).
- **New finding, fixed in-scope**: removing the save-time re-read also removed its *incidental* secret-redaction side effect (`SaveError(..., redact = true)` routed through `SensitiveDataRedactor.redactText`). Without it, `SetupIdentityController`'s own `importIdentityFromPath`/`importIdentityFromUri` onFailure handlers assigned `it.message` raw to `errorMessage` — a live secret-leak regression, and also literally P0-001-B's own checklist item ("Redact native/file error messages before assigning UI state"), not merely deferred P1-001-C scope.
  - Root cause of my earlier revert (previous entry): `SensitiveDataRedactor.redactText`'s identity-path rule is *intentionally* over-broad (matches the bare word "identity" anywhere, documented/tested in `SensitiveDataRedactorTest.identityRuleAlsoMatchesThePlainWordInProse` as acceptable for diagnostic-log export) — wrong tool for a short user-facing validation message.
  - Fix: added `SensitiveDataRedactor.redactSecretValues()` — the structured-secret-field/PEM-block/bearer-auth/MQTT-userinfo passes only, refactored out of `redactText` (which now composes `redactSecretValues` + the protocol/identity-path rules, unchanged behavior, all existing `SensitiveDataRedactorTest` cases still pass). `SetupIdentityController`'s three onFailure handlers now use `redactSecretValues` — protects real secrets (`password=...`) without mangling benign prose containing "identity".
- Migrated 5 test call sites (`SetupViewModelTest` ×2, `SetupSaveAuthorizedKeysTest`, `SetupValidationWorkspaceIntegrationTest`, `SetupSaveControllerTest` ×2 incl. the `wizardReachingConfigWrite` helper) to call `identity.importIdentityFromPath()` after `setImportIdentityPath(...)` so the draft is actually populated before save (Robolectric + `Dispatchers.Main.immediate`/inline-`Unconfined` dispatchers execute the launched coroutine synchronously here, confirmed by the passing runs — no artificial await needed at these call sites).
- Updated `AllViewModelFailureRedactionTest`'s setup-identity redaction case to exercise `identity.importIdentityFromPath()` directly (the real redaction boundary now) instead of the removed save-time branch.
- Added the TODO-named test `pathFileReplacementAfterValidationCannotChangeCommittedIdentity` to `SetupWizardNoIdentityMutationTest`: imports, then replaces the file on disk *and* the bridge's canned validation result before save, and asserts the committed public identity is the original — proving no re-read.
- **Two pre-existing flaky tests discovered (NOT caused by this work — independently reproduced against the true pre-FIX8 baseline commit `050cb06` via a throwaway `git worktree`, both files/domains untouched by any FIX8 commit so far):**
  - `SetupSaveControllerTest.plaintextIdentityIsWipedOnCancellation` — fails when run in isolation (`--tests` filtered to just this method) but passes as part of its full class or the full suite. Order/shared-state dependent; reproduced failing on both `050cb06` and current HEAD when run alone.
  - `TunnelForegroundServiceInitializationRaceTest.startWhileExactlyInitializingDoesNotCallNative` — failed once during a full `./gradlew check` run (release variant), passed on immediate rerun in isolation. Consistent with a genuine race in a race-focused test, not this task's concern.
  - Neither is fixed here (out of scope for P0-001/identity work; both are exactly the class of CI-nondeterminism P1-004-C is scoped to address). Recorded for that task and for final signoff's "three consecutive full unit reruns" requirement (spec P2-002-C) — if either recurs during signoff, P1-004-C must root-cause it before signoff, per the hard rule against flaky/unchecked validation.
- Validation: focused re-run of all 9 affected test classes (SetupViewModelTest, SetupSaveControllerTest, SetupSaveAuthorizedKeysTest, SetupValidationWorkspaceIntegrationTest, ConfigurationMutationIntegrationTest, SetupIdentityDraftTest, AllViewModelFailureRedactionTest, SetupWizardNoIdentityMutationTest, SensitiveDataRedactorTest) — all green. Full `testDebugUnitTest` PASS (1m42s). Full `./gradlew check` PASS on rerun (ktlint + detekt + Android lint + debug/release unit tests, zero suppressions).

### Still open in P0-001
- **P0-001-C + remaining P0-001-E forwards tests**: land with P0-004 (draft-only forwards ↔ setup `Forwards` transactional stage), per the sequencing note above.
