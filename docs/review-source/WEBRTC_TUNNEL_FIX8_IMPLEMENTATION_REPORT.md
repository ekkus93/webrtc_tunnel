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
