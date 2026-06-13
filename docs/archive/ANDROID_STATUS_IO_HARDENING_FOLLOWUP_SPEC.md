# Android Status / IO Hardening Follow-up Spec

## Purpose

This spec defines the next focused hardening pass for the Android version of the WebRTC tunnel app after the `ANDROID_LIFECYCLE_STATUS_HARDENING` work.

The previous pass substantially improved the app architecture: lifecycle-scoped ViewModels, centralized dispatchers, a shared forwards repository, more truthful runtime status mapping, Rust runtime metadata cleanup, SDK-gated notification permission checks, build workflow hardening, and launcher icon polish.

This follow-up is intentionally narrower. It targets the remaining correctness gaps found in the latest Android code review:

1. `ServiceState.Listening` is not treated as an active/running state everywhere.
2. Duplicate native start attempts can incorrectly mutate a running Rust runtime into `Error`.
3. Several setup/logs/diagnostics paths still perform disk/native/ContentResolver work synchronously.
4. Busy-state handling exists but is not consistently exception-safe or reflected in the UI.
5. `forwards.json` persistence is only partially atomic and corrupt-file handling is still risky.
6. Notification titles remain too generic and can contradict the body text.
7. Settings has minor dead state left over from moved network-policy controls.

The goal is to make Android runtime behavior honest, policy-safe, main-thread-safe, and harder to corrupt through lifecycle or file-system edge cases.

---

## Scope

### In scope

- Android app under `android/app/src/main/java/com/phillipchin/webrtctunnel/`
- Android tests under `android/app/src/test/`
- Rust mobile runtime under `crates/p2p-mobile/` only for duplicate-start and runtime-state correctness
- Gradle tests/build files only if needed to support tests for this patch

### Out of scope

- Full Android answer-mode implementation
- New daemon-to-controller telemetry architecture beyond the existing status fields
- Desktop CLI changes except where shared Rust behavior is directly affected
- Major UI redesign
- New app features
- Large navigation refactor
- Changing public tunnel protocol behavior

---

## Current baseline assumptions

The codebase is expected to already have:

- AndroidX lifecycle-scoped ViewModels using `viewModel(factory = ...)`
- Cast-free `viewModelFactory { initializer { ... } }` wiring
- `AppDispatchers` in `AppDependencies`
- `ForwardsRepository` exposing `StateFlow<List<ForwardConfig>>`
- Conservative status mapping where offer-mode running-without-session maps to `Listening`
- `skipRustBuild` Gradle property support for Android-only checks
- App-owned launcher icon resources
- No lint/detekt/ktlint/clippy suppressions

This follow-up must preserve those properties.

---

## Non-negotiable implementation rules

1. **No suppressions.** Do not add `@Suppress`, `@SuppressLint`, ktlint disables, detekt ignores, clippy `allow`, lint baselines, or equivalent bypasses.
2. **No main-thread file/native work from UI actions.** UI-triggered disk IO, ContentResolver IO, encrypted identity operations, diagnostics export, config rendering, and native validation must run on injected dispatchers.
3. **No false `Connected` status.** The UI and notification layer must not claim the tunnel is connected unless an actual active session/tunnel is known.
4. **No direct-write fallback for forward persistence.** `forwards.json` must not be written directly after a failed temp-file move.
5. **No duplicate start state corruption.** Attempting to start an already-running runtime must not convert it to `Error`.
6. **Policy pause must apply while listening.** A tunnel in `Listening` is still running and must be paused if the active network becomes disallowed.
7. **Tests must be added or updated for every corrected bug.**

---

## P1 — Treat active/running service states consistently

### Problem

The hardening pass introduced `ServiceState.Listening` as the truthful state for offer mode when the daemon is running but no active session exists. However, service lifecycle logic still checks only `Connected` and `Serving` in some places.

This causes two serious bugs:

- A tunnel in `Listening` may not pause when network policy becomes blocked.
- A user can tap Start while already in `Listening`, causing a duplicate native start attempt.

### Required behavior

Define one canonical helper for active or starting tunnel states. The exact location is flexible, but it must be reused consistently.

Recommended model:

```kotlin
internal fun ServiceState.isTunnelActiveOrStarting(): Boolean =
    this == ServiceState.Starting ||
        this == ServiceState.Connecting ||
        this == ServiceState.Reconnecting ||
        this == ServiceState.Listening ||
        this == ServiceState.Serving ||
        this == ServiceState.Connected

internal fun ServiceState.isTunnelRunning(): Boolean =
    this == ServiceState.Listening ||
        this == ServiceState.Serving ||
        this == ServiceState.Connected
```

Use these helpers in:

- duplicate-start prevention
- network-policy pause decisions
- status polling active conditions
- stop/notification logic where appropriate
- any UI/service action that currently tests only `Connected` or `Serving`

### Acceptance criteria

- A running offer tunnel in `Listening` is considered active.
- Start while `Listening` does not call native start again.
- Network-policy block while `Listening` pauses the tunnel.
- Polling and notification behavior do not accidentally treat `Listening` as stopped.
- Unit tests cover duplicate-start and policy-pause behavior for `Listening`.

---

## P2 — Fix Rust duplicate-start behavior

### Problem

The Rust mobile runtime currently treats a start request while already active as an error path that can mutate runtime state to `Error` and set `active = false`, even though the original daemon task may still be running.

That is dangerous because a duplicate Android Start tap can make the UI believe the runtime failed while the native task continues.

### Required behavior

If the runtime is already active, `start()` must reject the duplicate request without corrupting the current runtime state.

Acceptable outcomes:

- Return an error such as `"runtime already running"` without changing state.
- Or return a typed/idempotent already-running result if the existing API supports it.
- But do not set `state = Error`.
- Do not set `active = false`.
- Do not clear live runtime metadata.
- Do not abort the existing task unless the user explicitly requested stop/restart.

### Acceptance criteria

- Rust test: start once, attempt duplicate start, status remains running/listening/active.
- Rust test: duplicate start reports a clear error/result.
- Android service test: Start while already `Listening` does not put the app into Error.
- Existing clean error paths still preserve meaningful error information.

---

## P3 — Finish main-thread safety

### Problem

The previous pass moved several operations to the IO dispatcher, but not all UI-triggered blocking work was migrated.

Known remaining risky areas include:

- `SetupIdentityController`
- `SetupForwardsController`
- `SetupStepValidation`
- `LogsViewModel`
- Settings diagnostics/copy/share helpers
- Any path that calls `ContentResolver.openInputStream/openOutputStream`
- Any path that reads/writes config or diagnostics files
- Any path that imports/exports encrypted identity material
- Any path that calls native validation/status/log retrieval from a UI event

### Required behavior

Move all remaining UI-triggered blocking work onto injected dispatchers.

Preferred pattern:

```kotlin
viewModelScope.launch {
    _isBusy.value = true
    try {
        val result = withContext(deps.dispatchers.io) {
            // file/native/ContentResolver work
        }
        // update state/message on ViewModel context
    } catch (error: Exception) {
        // update error state/message
    } finally {
        _isBusy.value = false
    }
}
```

For helper/controller classes that are not ViewModels, either:

- make their blocking APIs `suspend`, or
- ensure the caller invokes them inside `withContext(deps.dispatchers.io)`, and document that contract.

### Required migration targets

#### Setup identity

Operations involving the following must not run on the main thread:

- reading stored public/private identity
- importing identity from a file path
- importing identity from a content URI
- validating private/public identity through native code
- generating and storing identity
- reading public identity from a URI

#### Setup forwards

Operations involving the following must not run on the main thread:

- loading saved forwards
- refreshing forwards from disk
- upserting/deleting forwards
- validation that reads persisted forwards

Prefer migrating setup forward operations to the shared `ForwardsRepository` instead of directly using `ForwardsConfigStore`.

#### Logs and diagnostics

Operations involving the following must not run on the main thread:

- native log retrieval if it may block
- diagnostics export
- diagnostics share intent creation if it reads/writes files
- writing diagnostics to a content URI
- redacted config reads
- status JSON generation if it calls native status synchronously

### Acceptance criteria

- Source audit shows no UI event handler directly performs disk/ContentResolver/native blocking work.
- Tests use injected dispatchers.
- Long-running operations expose busy/progress state where user-visible.
- Duplicate taps are disabled or ignored safely while busy.
- No new raw `Dispatchers.IO` calls in ViewModel business logic except via `AppDispatchers` or an explicitly injected dispatcher.

---

## P4 — Make busy-state handling exception-safe and visible

### Problem

Some ViewModels now have busy state, but the state is not always reset with `finally`, and UI buttons are not consistently disabled while an operation is in progress.

### Required behavior

For operations such as save, delete, test, import, export, validate, diagnostics export/share, identity import/generate, and forward edit/delete:

- set busy before work starts
- clear busy in `finally`
- prevent duplicate taps while busy
- keep messages/errors visible near the initiating action where practical
- avoid leaving UI permanently disabled after unexpected exceptions

### Acceptance criteria

- `ForwardsViewModel.saveForward()` and `deleteForward()` cannot leave `_isBusy = true` after an exception.
- Import/export buttons are disabled or ignored while import/export is busy.
- Forward save/delete/test controls are disabled or ignored while a conflicting operation is busy.
- Settings validation/diagnostics buttons are disabled or ignored while the relevant operation is busy.
- Tests cover successful completion and thrown-exception cleanup for at least the main save/delete/import/export paths.

---

## P5 — Fix forward persistence atomicity and corrupt-file behavior

### Problem

`ForwardsConfigStore.saveForwards()` uses a temp file, but falls back to a direct destination write if `renameTo()` fails. That fallback can corrupt `forwards.json` if the app/process dies mid-write.

Additionally, `loadForwards()` can silently return an empty list on corrupt JSON, which is dangerous if mutation paths later save that empty list.

### Required behavior

Use safer file replacement:

- write to a temp file in the same directory
- flush and close the writer
- move temp file into place using `java.nio.file.Files.move`
- prefer `StandardCopyOption.ATOMIC_MOVE` + `REPLACE_EXISTING`
- if atomic move is unsupported, use non-atomic replace move as a fallback
- never direct-write the destination file after a failed move
- clean up temp files on failure

Corrupt-file handling:

- mutation paths must not treat corrupt persisted forwards as empty and overwrite the user’s config
- repository should preserve last known in-memory state if disk reload fails
- expose/log an error for corrupt JSON
- avoid `getOrElse { emptyList() }` in paths that can later save/mutate

### Acceptance criteria

- Tests cover normal save/load.
- Tests cover corrupt JSON.
- Tests cover failed load preserving in-memory state.
- Tests cover temp-file cleanup or move fallback behavior where practical.
- No mutation path silently overwrites corrupt persisted forwards with an empty list.

---

## P6 — Fix notification status wording

### Problem

Notification titles are still too generic. Some states can produce contradictory notification text, such as a title implying “running” while the body says “stopped.”

### Required behavior

Use explicit notification titles for representative states.

Suggested mapping:

| State | Notification title |
|---|---|
| `Stopped` | `WebRTC Tunnel stopped` |
| `Starting`, `Connecting`, `Reconnecting` | `WebRTC Tunnel starting` |
| `Listening` | `WebRTC Tunnel listening` |
| `Serving` | `WebRTC Tunnel serving` |
| `Connected` | `WebRTC Tunnel connected` |
| `PausedMeteredBlocked`, `NoNetwork` | `WebRTC Tunnel paused` |
| `Stopping` | `WebRTC Tunnel stopping` |
| `Error`, `ConfigInvalid` | `WebRTC Tunnel error` |

The body should provide detail, such as:

- `Waiting for connection`
- `Tunnel connected`
- `Blocked on metered network`
- `No network available`
- `Tunnel stopped`
- redacted error message

### Acceptance criteria

- Stopped notification does not say running.
- Listening notification does not say connected.
- Connected notification is only used for actual connected/session-active state.
- Tests cover stopped, starting, listening, serving, connected, paused, and error.

---

## P7 — Remove dead Settings state

### Problem

After moving network policy controls out of Settings, stale Settings state such as `showMeteredWarningDialog` may remain unused.

### Required behavior

Remove dead state and UI paths related to old duplicate network policy controls.

### Acceptance criteria

- Settings has no unused metered warning dialog state.
- Settings has no duplicate editable network policy switches.
- Settings retains read-only network policy summary and a button to open Network Policy.
- Static analysis reports no unused private declarations introduced by the cleanup.

---

## Validation requirements

### Android checks

Run:

```bash
cd android
./gradlew --no-daemon -PskipRustBuild=true lintDebug
./gradlew --no-daemon -PskipRustBuild=true testDebugUnitTest
./gradlew --no-daemon lintDebug
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon assembleDebug
./gradlew --no-daemon check
```

### Rust checks

If `crates/p2p-mobile` or shared Rust runtime code changes, run:

```bash
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test -p p2p-mobile
cargo test --workspace
```

### Manual smoke test

Run on a physical Android device when possible:

1. Install debug APK.
2. Launch app.
3. Confirm notification permission flow on Android 13+.
4. Start offer-mode tunnel.
5. Confirm initial running state is `Listening` or equivalent, not `Connected`, when there is no active session.
6. Tap Start again while listening; confirm no Error state and no duplicate native start.
7. Move to a blocked/metered network with policy blocking enabled; confirm tunnel pauses.
8. Return to allowed Wi-Fi; confirm resume behavior is correct if enabled.
9. Stop tunnel; confirm notification and Home status say stopped and uptime disappears.
10. Export diagnostics; confirm UI stays responsive and duplicate taps are prevented.
11. Import/export identity/config through URI; confirm UI stays responsive and duplicate taps are prevented.
12. Edit/delete forwards; confirm Home and Forwards remain in sync.
13. Corrupt `forwards.json` in a test/dev build if practical; confirm forwards are not silently erased.

---

## Done definition

This hardening follow-up is complete when:

- `Listening` is treated as active/running everywhere relevant.
- duplicate Start while already running does not corrupt Android or Rust runtime state.
- remaining setup/logs/diagnostics blocking paths are off the main thread.
- busy flags are exception-safe and reflected in UI controls.
- forward persistence never direct-writes the destination as a fallback.
- corrupt forward JSON cannot silently erase user configuration.
- notification titles match service states.
- dead Settings network-policy state is removed.
- all relevant Kotlin and Rust tests pass.
- no suppressions or lint bypasses are added.
