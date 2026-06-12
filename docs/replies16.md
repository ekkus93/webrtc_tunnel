# replies16.md — answers for ANDROID_STATUS_IO_HARDENING_FOLLOWUP

These are my answers to the questions/issues in `responses16.md` for the Android status/IO hardening follow-up.

Overall: Claude Code's review looks correct. The seven items are real, they are all cleanly fixable, and I agree that no suppressions should be added. Please implement this follow-up as normal production fixes, with tests updated or added where behavior changes.

---

## Q1 — P3: async wizard "Next"

Yes. An async `Next` action with a short busy/disabled state is acceptable and is the right fix.

The important requirement is that the UI behavior stays deterministic:

- Tapping **Next** should not advance the wizard until validation completes successfully.
- While validation is running, disable the Next button so repeated taps cannot enqueue duplicate validations.
- Prefer disabling Back/Previous during the same short validation window too, unless the current screen already has a safe cancellation pattern.
- Keep the user on the same step if validation fails, and surface the same validation error as before.
- Do not block the main thread for native validation, file reads, or any other potentially slow work.

Implementation shape I prefer:

- Keep the UI-facing `goNext()` as a normal ViewModel method if possible.
- Inside `goNext()`, launch a single `viewModelScope` coroutine.
- Move `SetupStepValidation.validateStep(...)` work that can call native code onto the injected IO dispatcher.
- Expose a specific busy state such as `isAdvancingStep`, `isValidatingStep`, or a setup-level `isBusy` in the setup UI state.
- Use `try/finally` so the busy flag always clears, including on validation exceptions.
- Guard against duplicate invocation at the ViewModel level, not only in the Composable.

In other words, I prefer this user-facing flow:

```text
Tap Next -> set validation busy -> run validation on IO -> if valid advance step -> clear busy
```

Do not leave the old synchronous validation path in place just to preserve instant step advancement. The main-thread correctness issue is more important.

Tests I would like:

- A unit test that `goNext()` does not advance until async validation completes.
- A unit test that validation failure leaves the wizard on the current step and clears busy.
- A unit test that an exception during validation clears busy and reports an error.
- A duplicate-tap test if the ViewModel already has enough test infrastructure for it.

---

## Q2 — P3: async share/copy helpers

Use the **suspend-returns-payload** form. Do not use a one-shot `Flow`/`Channel` for these actions unless there is already an established screen-wide event system that makes that simpler.

These are user-initiated actions. The screen asks for a payload, waits for it, then performs the Android UI operation such as starting the share sheet or writing to the clipboard. A suspend function is simpler, easier to test, and avoids one-shot event replay/lifecycle edge cases.

Preferred shape:

```kotlin
suspend fun statusJson(): String
suspend fun redactedConfigOrEmpty(): String
suspend fun diagnosticsShareIntent(): Intent
suspend fun publicIdentityForShare(): String
```

or, if you want to keep Android framework types out of the ViewModel where practical:

```kotlin
suspend fun diagnosticsSharePayload(): DiagnosticsSharePayload
```

Then the Composable can do:

```kotlin
val scope = rememberCoroutineScope()

Button(
    enabled = !isBusy,
    onClick = {
        scope.launch {
            try {
                setLocalBusy(true)
                val payload = viewModel.statusJson()
                clipboardManager.setText(AnnotatedString(payload))
            } finally {
                setLocalBusy(false)
            }
        }
    }
) { ... }
```

Exact UI state wiring can follow the existing screen style, but these constraints matter:

- File reads and native calls must happen on the injected IO dispatcher.
- Clipboard writes, `startActivity`, and share sheet launch should happen from the UI coroutine on the main thread after the payload is produced.
- Handle errors visibly. A snackbar/toast/error row is fine; do not silently fail.
- Use `try/finally` for any busy state.
- Avoid `GlobalScope` or ad hoc thread creation.
- Avoid returning stale cached diagnostics unless the existing feature already intentionally uses cached data.

A `Flow`/`Channel` would be more appropriate if the ViewModel spontaneously emitted navigation or share events. That is not the case here; the user is explicitly clicking a button and the screen needs the immediate result.

---

## Q3 — P5: move forward mutation onto the in-memory repository

Yes. Move forward mutation logic into `ForwardsRepository` and make `ForwardsConfigStore` a pure load/save/validate layer.

I agree with the proposed design:

- `ForwardsConfigStore` should keep:
  - `loadForwardsResult(...)`
  - `saveForwards(...)`
  - `validateForwards(...)`
  - low-level atomic persistence mechanics
- `ForwardsRepository` should own:
  - the in-memory `StateFlow` / `MutableStateFlow` list
  - `upsertForward(...)`
  - `deleteForward(...)`
  - mutation-from-current-state semantics

The key invariant is:

> A corrupt persisted file must never be interpreted as an empty list during mutation and then written back over the user's existing forwards.

So mutation should use the repository's current in-memory state as the source of truth. Do not re-read disk inside `upsertForward` or `deleteForward`.

Suggested mutation flow:

```text
current in-memory list -> validate proposed new list -> atomic save -> update StateFlow
```

I slightly prefer saving first and publishing the new StateFlow value only after save succeeds, so the UI does not briefly show a mutation that failed to persist. If current code already publishes optimistically, that is acceptable only if failures roll back cleanly and are tested. The safer implementation is save-then-publish.

Important edge cases to cover:

- If initial load fails because the forwards file is corrupt, expose the load error and do not silently replace the file with `[]`.
- If the in-memory list was successfully loaded earlier, later mutations must use that live list even if the disk file becomes corrupt between operations.
- If save fails, keep the old in-memory list and report the error.
- If validation fails, do not save and do not mutate the StateFlow.
- Concurrent mutations should be serialized, probably with a `Mutex`, so two rapid edits cannot race and lose an update.

---

## P1 — Listening must be treated as active everywhere

Proceed with the helper consolidation.

Use one canonical helper/set for "active or starting" tunnel states and make sure it includes `Listening`. It is fine to replace the existing `TunnelRepository.UPTIME_STATES` if it duplicates the new helper.

Expected behavior:

- Start while `Listening` should be treated as already running/listening and should not call native start again.
- Network-policy pause while `Listening` should pause the tunnel if the current policy disallows continuing.
- Existing status polling behavior that already includes `Listening` should remain intact.

Please add or update tests for the duplicate-start guard and the policy-pause guard specifically, because those are the two confirmed gaps.

---

## P2 — Rust duplicate-start must not corrupt runtime state

Proceed. This is complementary to P1 and should still be fixed in Rust.

If a duplicate start reaches Rust while the runtime is already active, it should return an "already running" style error without mutating the active runtime into `Error` and without setting `active = false`.

Expected invariant:

```text
active runtime + duplicate start attempt -> error returned, existing active state preserved
```

Do not route this path through a generic `record_start_error(...)` helper if that helper changes the runtime state to `Error`. Use a separate non-mutating duplicate-start error path, or change the helper so only actual start failures mutate state.

Please add a Rust unit test for this invariant if there is suitable test coverage around `runtime.rs`.

---

## P4 — busy state must be exception-safe and wired into UI

Proceed with `try/finally` and UI disabling.

For `ForwardsViewModel.saveForward(...)` and `deleteForward(...)`:

- Set busy before work starts.
- Clear busy in `finally`.
- Surface exceptions through the existing error state/snackbar mechanism.
- Do not allow repeated clicks to enqueue duplicate saves/deletes.

Also wire `isBusy` into the actual Compose buttons/menu actions that mutate forwards. A busy flag that is only internal is not enough.

Tests I would like:

- Save success clears busy.
- Delete success clears busy.
- Save exception clears busy.
- Delete exception clears busy.
- Buttons/actions are disabled while busy, if Compose tests already exist for this screen.

---

## P5 — atomic persistence and corrupt-file behavior

Proceed with `Files.move` as proposed.

The atomic write behavior should be:

```text
write temp file in same directory -> fsync/flush as practical -> Files.move(temp, dest, ATOMIC_MOVE, REPLACE_EXISTING)
```

If `ATOMIC_MOVE` is unsupported, fall back to:

```text
Files.move(temp, dest, REPLACE_EXISTING)
```

Do not fall back to direct `dest.writeText(...)`. That is the specific behavior the spec is trying to eliminate.

Also clean up temp files on failure where practical.

For corrupt-file behavior:

- Loading a corrupt forwards file may return a structured error/result.
- It must not silently become `emptyList()` in a mutation path.
- Mutating after a corrupt load must not erase the user's existing persisted data.
- If there is no valid in-memory list because startup load failed, block mutation and surface the load error rather than writing a new empty baseline.

---

## P6 — notification titles should be explicit per state

Proceed. Make notification titles explicit for all states handled by `TunnelStatus`.

At minimum, avoid the current generic fallback where `Stopped`, `Listening`, `Connected`, `Serving`, and `Starting` all become "WebRTC Tunnel running".

Reasonable titles:

```text
Starting  -> WebRTC Tunnel starting
Listening -> WebRTC Tunnel listening
Connected -> WebRTC Tunnel connected
Serving   -> WebRTC Tunnel serving
Paused    -> WebRTC Tunnel paused
Error     -> WebRTC Tunnel error
Stopped   -> WebRTC Tunnel stopped
```

Exact wording can vary, but title and body must not contradict each other. Please check both `NotificationController.buildStatusNotification` and `TunnelForegroundService.StatusReporter.publishStatus` together.

---

## P7 — remove dead Settings metered-warning state

Proceed. Remove the dead `showMeteredWarningDialog` state and the unreachable `MeteredWarningDialog` UI if the allow-metered warning/control has already moved elsewhere.

Do not keep dead state just because detekt does not flag it. If there is still a valid metered-warning UX somewhere else, keep that active implementation, but delete the unreachable leftover path.

---

## Sequencing

The proposed sequencing is good:

1. P1 + P2 together: `Listening` active-state handling plus Rust duplicate-start state integrity.
2. P5: persistence atomicity and corrupt-file-safe mutation architecture.
3. P3 + P4: async main-thread cleanup plus busy/disabled UX, since they overlap.
4. P6: notification title/body consistency.
5. P7: dead Settings state cleanup.
6. Full Android + Rust gate.

Keep these as separate reviewable commits if practical. Do not add lint, detekt, ktlint, clippy, or test suppressions.

---

## Final acceptance criteria

I would consider this follow-up complete when:

- `Listening` is consistently treated as active/active-or-starting anywhere duplicate start, uptime, or policy pause logic needs it.
- Rust duplicate start no longer changes an already-active runtime into `Error`.
- The setup wizard's native validation runs off the main thread.
- Settings/logs/share/copy helpers no longer do file/native work on the main thread.
- All busy flags are exception-safe and wired into the UI where they matter.
- Forwards persistence has no direct-write fallback.
- Corrupt persisted forwards cannot be silently overwritten by a mutation path that interpreted corruption as an empty list.
- Notification titles are explicit and non-contradictory.
- Dead Settings metered-warning state is removed.
- Existing tests still pass, with new tests for the regression-prone paths above.
