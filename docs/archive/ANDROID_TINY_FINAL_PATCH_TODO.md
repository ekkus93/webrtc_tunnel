# Android WebRTC Tunnel Tiny Final Patch TODO

## 1. Goal

Apply the last tiny Android hardening patch before running real Android offer ↔ desktop answer E2E validation.

This is intentionally small. Do not redesign the app. Do not reopen broad Android architecture work.

The patch should fix only:

1. Prevent stale native start after STOP/PAUSE by adding a pre-native-start generation check.
2. Explicitly cancel `startupJob` on STOP/PAUSE paths.
3. Remove `runBlocking` from `SetupViewModel.saveAndApplyConfig()`.
4. Add a clear comment explaining why `p2p-mobile` mirrors workspace Clippy lints instead of using `[lints] workspace = true`.

Manual E2E compatibility should remain unchecked until actually run.

---

## 2. Rules

- [ ] Do not change MQTT signaling wire format.
- [ ] Do not change tunnel frame format.
- [ ] Do not change desktop Rust protocol semantics.
- [ ] Do not add TURN.
- [ ] Do not add VPN/TUN mode.
- [ ] Do not add arbitrary Android remote host/port selection.
- [ ] Do not weaken encrypted identity-at-rest behavior.
- [ ] Do not weaken network policy behavior.
- [ ] Do not weaken log/diagnostic redaction.
- [ ] Do not mark Android↔desktop E2E complete unless the real test is run and documented.

---

# Phase 1 — Add pre-native-start generation check

## 1.1 Audit current lifecycle generation logic

Inspect:

```text
TunnelForegroundService.startOffer(...)
TunnelForegroundService.doStartOffer(...)
TunnelForegroundService.stopServiceWork(...)
TunnelForegroundService.pause(...)
TunnelForegroundService.pauseForPolicy(...)
```

Confirm:

- [x] where `lifecycleGeneration` increments;
- [x] where START captures the generation;
- [x] where STOP increments generation;
- [x] where PAUSE/network-block increments generation;
- [x] where `repository.start(...)` is called;
- [x] where post-start stale-generation check is performed.

## 1.2 Add pre-start stale check

Before calling native startup:

```kotlin
repository.start(...)
```

add a generation/desired-state check.

Required behavior:

- [x] START captures generation before async startup work begins.
- [x] Immediately before native `repository.start(...)`, check that captured generation is still current.
- [x] If stale, do **not** call `repository.start(...)`.
- [x] If stale, publish no Running state.
- [x] If stale, leave repository/UI in stopped/paused/blocked state as appropriate.
- [x] Keep the existing post-start stale check after `repository.start(...)` returns.

Suggested pattern:

```kotlin
val stillCurrentBeforeNativeStart = lifecycleMutex.withLock {
    lifecycleGeneration == startGeneration
}
if (!stillCurrentBeforeNativeStart) {
    return
}

val result = repository.start(...)

val stillCurrentAfterNativeStart = lifecycleMutex.withLock {
    lifecycleGeneration == startGeneration
}
if (!stillCurrentAfterNativeStart) {
    withContext(Dispatchers.IO) { repository.stop() }
    return
}
```

## 1.3 Tests

Add or update tests:

- [x] STOP before native start prevents `repository.start(...)` from being called.
- [x] PAUSE/network-block before native start prevents `repository.start(...)` from being called.
- [x] STOP during native start still stops stale successful runtime.
- [x] stale START never publishes Running after STOP.
- [x] start-stop-start still works.

## 1.4 Acceptance

- [x] STOP/PAUSE before native start prevents native start.
- [x] STOP/PAUSE during native start prevents stale Running state.
- [x] Existing lifecycle tests still pass.

---

# Phase 2 — Explicitly cancel `startupJob` on STOP/PAUSE

## 2.1 Audit current job handling

Inspect:

```text
TunnelForegroundService.stopServiceWork(...)
TunnelForegroundService.pause(...)
TunnelForegroundService.pauseForPolicy(...)
TunnelForegroundService.onDestroy(...)
```

Find all paths that currently do:

```kotlin
startupJob = null
```

without first cancelling the active job.

## 2.2 Implement explicit cancellation

Required:

- [x] On manual STOP, call `startupJob?.cancel()` before clearing it.
- [x] On manual PAUSE, call `startupJob?.cancel()` before clearing it.
- [x] On network-policy pause/block, call `startupJob?.cancel()` before clearing it.
- [x] On service destroy, call `startupJob?.cancel()` before clearing/cancelling service scope.
- [x] Keep cancellation safe if `startupJob` is already null or completed.
- [x] Do not block the main thread waiting for cancellation.

Suggested helper:

```kotlin
private fun cancelStartupJob() {
    startupJob?.cancel()
    startupJob = null
}
```

Use it consistently.

## 2.3 Tests

Add or update tests:

- [x] STOP cancels pending startup job.
- [x] PAUSE cancels pending startup job.
- [x] network-policy pause cancels pending startup job.
- [x] duplicate STOP remains safe.
- [x] cancellation does not break start-stop-start.

## 2.4 Acceptance

- [x] No path clears `startupJob` without cancelling it first.
- [x] Pending startup work is cancelled as early as possible.

---

# Phase 3 — Remove `runBlocking` from `SetupViewModel.saveAndApplyConfig()`

## 3.1 Audit current setup save/start flow

Inspect:

```text
SetupViewModel.saveAndApplyConfig(...)
SetupViewModel.startTunnelFromReview(...)
ConfigRepository.savePreferences(...)
ConfigRepository.preferences
```

Confirm:

- [x] where config is rendered;
- [x] where config is validated;
- [x] where config is written atomically;
- [x] where preferences are saved;
- [x] where service start is triggered;
- [x] whether service start waits for preference save.

## 3.2 Make save operation nonblocking

Remove:

```kotlin
runBlocking { ... }
```

from ViewModel/UI-facing setup code.

Use one of these designs.

### Preferred: suspend save function

- [x] Make the internal save/apply operation suspendable.
- [x] Perform preference read/write from coroutine context.
- [x] Start Tunnel only after save completes successfully.
- [x] Keep UI responsive.

Example shape:

```kotlin
fun saveAndApplyConfig() {
    viewModelScope.launch {
        saveAndApplyConfigInternal()
    }
}

private suspend fun saveAndApplyConfigInternal(): Result<Unit> {
    ...
}
```

For Start Tunnel:

```kotlin
fun startTunnelFromReview() {
    viewModelScope.launch {
        val saved = saveAndApplyConfigInternal()
        if (saved.isSuccess) {
            startForegroundService(...)
        }
    }
}
```

### Acceptable alternative: callback/result state

- [x] Save runs in `viewModelScope.launch`.
- [x] UI state records save success/failure.
- [x] Start Tunnel chains from successful save.
- [x] Service start cannot race ahead of preference save.

## 3.3 Preserve behavior

Ensure:

- [x] generated config still validates before write;
- [x] config write remains atomic;
- [x] preferences save completes before service start;
- [x] errors are shown in setup state;
- [x] no UI-thread blocking remains;
- [x] no preference-save race is reintroduced.

## 3.4 Tests

Add or update tests:

- [x] `saveAndApplyConfig()` does not use `runBlocking`.
- [x] Start Tunnel waits for preference save.
- [x] failed config validation prevents service start.
- [x] failed preference save prevents service start and shows error.
- [x] successful save starts service exactly once.
- [x] UI state updates after async save.

## 3.5 Acceptance

- [x] No `runBlocking` remains in `SetupViewModel` setup save/start path.
- [x] Start Tunnel still waits for preferences to persist.
- [x] Setup UI remains responsive.

---

# Phase 4 — Add `p2p-mobile` lint-policy comment

## 4.1 Audit current lint block

Inspect:

```text
crates/p2p-mobile/Cargo.toml
```

Current expected shape may include:

```toml
[lints.rust]
unsafe_code = "allow"

[lints.clippy]
all = { level = "warn", priority = -1 }
dbg_macro = "deny"
todo = "deny"
unwrap_used = "deny"
```

## 4.2 Add explanatory comment

Add a clear comment explaining why `p2p-mobile` does not simply use:

```toml
[lints]
workspace = true
```

if that is still the case.

The comment should say:

- [x] `p2p-mobile` is the JNI/FFI boundary.
- [x] Rust `unsafe_code` must be allowed narrowly for JNI/FFI exports.
- [x] The Clippy lint list intentionally mirrors the workspace policy.
- [x] The crate must not weaken `unwrap_used`, `todo`, or `dbg_macro`.
- [x] If workspace lint policy changes, this crate's mirrored Clippy policy must be updated too, unless Cargo config is refactored to inherit workspace lints directly.

Suggested comment:

```toml
# This crate is the Android JNI/FFI boundary and must allow Rust `unsafe_code`
# for exported native functions and pointer handling. Cargo does not let this
# crate inherit the workspace lint table while overriding only `unsafe_code` in
# the shape we need here, so the Clippy policy below intentionally mirrors the
# workspace policy. Keep this list in sync with `[workspace.lints.clippy]`.
```

Adjust wording if Cargo behavior differs.

## 4.3 Validation

Run:

```bash
cargo clippy --workspace --all-targets --all-features -- -D warnings
```

## 4.4 Acceptance

- [x] The lint exception is documented.
- [x] Clippy policy remains equivalent to workspace policy for `p2p-mobile`.
- [x] Clippy passes.

---

# Phase 5 — Validation

## 5.1 Rust validation

Run:

```bash
cargo fmt --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets
```

Tasks:

- [x] `cargo fmt --check` passes.
- [x] Clippy passes with `-D warnings`.
- [x] Rust tests pass.
- [x] No broad lint suppression added.

## 5.2 Android validation

Run:

```bash
cargo ndk \
  -t arm64-v8a \
  -t x86_64 \
  -o android/app/src/main/jniLibs \
  build -p p2p-mobile --release

cd android
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Tasks:

- [x] native build passes.
- [x] `assembleDebug` passes.
- [x] unit tests pass.
- [x] APK contains native libraries.

## 5.3 Connected tests

If a device/emulator is available:

```bash
cd android
./gradlew connectedDebugAndroidTest
```

Tasks:

- [x] connected tests pass; or
- [ ] NOT RUN is documented with exact reason.

## 5.4 Manual E2E

If the environment is available, run:

- [ ] desktop answer started;
- [ ] Android offer configured from UI;
- [ ] Android tunnel started;
- [ ] Android browser opens `http://127.0.0.1:<port>`;
- [ ] remote service response confirmed;
- [ ] redacted logs collected;
- [ ] result documented.

If the environment is not available:

- [x] document `NOT RUN`;
- [x] leave E2E compatibility unchecked.

## 5.5 Documentation

Update:

```text
docs/ANDROID_VALIDATION.md
```

Include:

- [x] date;
- [x] commit hash;
- [x] environment;
- [x] command results;
- [x] connected test result or NOT RUN reason;
- [x] manual E2E result or NOT RUN reason;
- [x] unresolved failures.

---

# Phase 6 — Final acceptance checklist

## 6.1 Tiny final patch acceptance

- [x] Pre-native-start generation check prevents stale native start after STOP/PAUSE.
- [x] Post-native-start generation check still prevents stale Running publication.
- [x] STOP/PAUSE paths explicitly cancel `startupJob`.
- [x] `SetupViewModel` save/start path no longer uses `runBlocking`.
- [x] Start Tunnel waits for preference save before starting service.
- [x] `p2p-mobile` lint-policy comment explains the mirrored Clippy policy and unsafe exception.
- [x] Rust validation passes.
- [x] Android validation passes.
- [x] Validation docs are updated.

## 6.2 Compatibility acceptance

Do not check these unless real manual E2E is run:

- [ ] Android offer connects to desktop Rust answer.
- [ ] Android browser reaches remote service via `127.0.0.1:<port>`.
- [ ] E2E result is documented with exact steps/results.
