# responses16 — review of ANDROID_STATUS_IO_HARDENING_FOLLOWUP (spec + TODO)

Review of `ANDROID_STATUS_IO_HARDENING_FOLLOWUP_SPEC.md` and
`ANDROID_STATUS_IO_HARDENING_FOLLOWUP_TODO.md`, cross-checked against the current
`android-app` tree. No code written yet — these are the questions/issues to resolve
before implementation.

Context: the prior `ANDROID_LIFECYCLE_STATUS_HARDENING` pass (P1–P9) is already
landed and pushed. Most of this follow-up targets **residual gaps from that pass**,
which the review correctly caught. Nothing here needs a suppression; everything is
cleanly fixable.

---

## Verification — all 7 items are real (confirmed in code)

| Item | Confirmed |
|---|---|
| **P1** `Listening` not treated active everywhere | `TunnelForegroundService.kt:272` (duplicate-start guard) and `:92` (network-policy pause) check only `Connected\|\|Serving`. Since the prior pass made offer-idle map to `Listening`, Start-while-listening starts again and a policy block while listening does **not** pause. Regression introduced by the earlier P3. |
| **P2** Rust duplicate-start corrupts state | `crates/p2p-mobile/src/runtime.rs:260` → `if inner.state.active { return Err(record_start_error(...)) }`, and `record_start_error` sets `state = Error` and `active = false`. A duplicate start flips a *running* runtime to Error. |
| **P3** remaining main-thread work | `SetupIdentityController`, `SetupForwardsController`, `SetupStepValidation` (native validation via `goNext`), `LogsViewModel.refresh/exportDiagnostics`, and `SettingsViewModel.statusJson/redactedConfigOrEmpty/diagnosticsShareIntent` still run synchronously on the main thread. These were explicitly deferred in the earlier P2. |
| **P4** busy state not exception-safe | `ForwardsViewModel.saveForward/deleteForward` set `_isBusy=true … =false` with **no `try/finally`** — a thrown exception leaves it stuck true. `isBusy` is also not yet wired to disable any UI buttons. |
| **P5** persistence atomicity + corrupt-file | The prior P7 `saveForwards` does `if (!temp.renameTo(dest)) dest.writeText(...)` — the **direct-write fallback the spec forbids**. `upsertForward`/`deleteForward` use `loadForwards()` (silent `emptyList()` on corrupt), so mutating after a corrupt read **erases** the user's forwards. |
| **P6** generic notification titles | `buildStatusNotification` title `when` is `Paused`/`Error` else → `"WebRTC Tunnel running"`, so `Stopped`/`Listening`/`Connected`/`Serving`/`Starting` all read "running" — and the Stopped case contradicts its own "Tunnel stopped" body. |
| **P7** dead Settings state | `showMeteredWarningDialog` is read but **only ever assigned `false`** (never `true`) → `MeteredWarningDialog` is unreachable dead code, left over from the moved allowMetered control. (detekt doesn't flag it because it is technically read/written.) |

Nothing in the spec is wrong, and no item requires a lint/detekt/ktlint/clippy
suppression.

---

## Design questions (need answers before implementing)

### Q1 — P3: making the wizard "Next" asynchronous

`SetupStepValidation.validateStep` is called synchronously from
`SetupViewModel.goNext()` and performs native `validatePublicIdentity` on the Peer
and Review steps. Moving it off-main turns `goNext()` async: tap Next → brief
busy/disabled → validate on IO → then advance the step.

**Question:** Is an async "Next" with a transient busy/disabled state acceptable?
(It's the correct fix; just confirming the wizard may briefly show progress on Next
rather than advancing synchronously.)

### Q2 — P3: how to async-ify the "build-and-immediately-use" share/copy helpers

`diagnosticsShareIntent(): Intent`, `statusJson(): String`,
`redactedConfigOrEmpty(): String`, and `publicIdentityForShare(): String` currently
return values the UI hands straight to the share sheet / clipboard, while reading
files / calling native synchronously on the main thread.

Proposed approach: the ViewModel exposes a `suspend` that produces the payload on
the IO dispatcher; the composable calls it from `rememberCoroutineScope().launch { … }`
and then fires the share/clipboard with the result.

**Question:** Use the suspend-returns-payload form (preferred — simpler, testable),
or a one-shot event `Flow`/`Channel` the screen collects? Either keeps the file/native
work off-main; confirming which shape you want.

### Q3 — P5: move forward mutation onto the in-memory repository

To satisfy "mutations must not treat corrupt persisted forwards as empty and
overwrite," the cleanest design is to have **`ForwardsRepository.upsert/delete`
operate on its in-memory `StateFlow` list** (the source of truth) and atomically
persist, instead of `ForwardsConfigStore` re-reading possibly-corrupt disk. The store
would keep `loadForwardsResult` / `saveForwards` / `validateForwards`; the
upsert/delete *logic* moves into the repository.

**Question:** OK to relocate the mutation logic into `ForwardsRepository` (making
`ForwardsConfigStore` pure load/save/validate)? This also means a corrupt disk file
can never erase the live list during a mutation, since mutations work from in-memory.

---

## Smaller notes (not blocking)

- **P1 helper consolidation:** `TunnelRepository` already has an `UPTIME_STATES` set
  identical to the proposed `isTunnelActiveOrStarting()`. I'll replace it with the new
  canonical helper rather than keep two parallel lists. Status polling's `ACTIVE_STATES`
  in `TunnelForegroundService` already includes `Listening`; the gaps are only the
  duplicate-start and policy-pause guards.
- **P1 ↔ P2 are complementary, not redundant:** P1 is the Android-layer guard
  (Start-while-listening → "already running", no native call); P2 is Rust-layer state
  integrity if a duplicate ever reaches it. Both are wanted, as the spec lists.
- **P5 `Files.move`:** minSdk is 26, so `java.nio.file.Files` is available. Plan:
  `ATOMIC_MOVE` + `REPLACE_EXISTING`, with a non-atomic `REPLACE_EXISTING`-only
  fallback (no direct write), temp file in the same directory, cleanup on failure.
- **P6 has two text sources:** titles in `NotificationController.buildStatusNotification`
  (the fix target) and bodies in `TunnelForegroundService.StatusReporter.publishStatus`
  (already per-state). I'll make titles explicit and confirm bodies don't contradict.
- **Suggested sequencing:** P1 + P2 together (Listening-active + Rust duplicate-start),
  then P5 (persistence), then P3 + P4 (main-thread + busy/UX, which overlap heavily),
  then P6, then P7 — each a separate reviewable, suppression-clean commit, with the
  full Android + Rust gate at the end.
- **Baseline:** all linters/tests are currently green (Rust fmt/clippy `-D warnings`/
  test, Android `check` incl. type-resolution detekt, instrumentation 13/13, and the
  on-device smoke test), so this follow-up starts from a clean state.
