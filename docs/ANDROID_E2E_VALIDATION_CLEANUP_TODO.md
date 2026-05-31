# Android WebRTC Tunnel E2E Validation Cleanup TODO

## 1. Goal

Apply the final documentation/test-quality cleanup before running real Android offer ↔ desktop answer E2E validation.

This is not an implementation redesign. The Android app code is already very close. This TODO only covers:

1. Add a fresh validation entry for the current tiny-final-patch commit or working tree.
2. Optionally strengthen the STOP-before-native-start lifecycle test with a deterministic pre-native-start gate.
3. Run or continue to honestly defer real Android offer ↔ desktop answer E2E validation.

Do not reopen broad Android architecture work.

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

# Phase 1 — Add fresh validation entry for current patch

## 1.1 Inspect current validation docs

Open:

```text
docs/ANDROID_VALIDATION.md
```

Check whether the latest entry corresponds to the current tiny-final-patch commit or working tree.

Look for stale wording such as:

```text
base before tiny final patch commit
```

or any commit hash that predates the latest lifecycle/setup/lint changes.

## 1.2 Add current validation entry

Add a new top-level entry to `docs/ANDROID_VALIDATION.md`.

Required fields:

- [x] date/time;
- [x] git commit hash, if committed;
- [x] working tree state, if not committed;
- [x] host OS/environment;
- [x] Rust toolchain version;
- [x] Android Gradle/Gradle wrapper version;
- [x] Android SDK/NDK version;
- [x] device/emulator availability;
- [x] summary of this validation run.

If the working tree is dirty, explicitly write:

```text
Commit: <hash>
Working tree: dirty / contains tiny final patch changes
```

or:

```text
Commit: not yet committed
Working tree: tiny final patch changes present
```

## 1.3 Record automated validation commands

Run and record exact command + result:

```bash
cargo fmt --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets
```

Also run and record:

```bash
cargo ndk \
  -t arm64-v8a \
  -t x86_64 \
  -o android/app/src/main/jniLibs \
  build -p p2p-mobile --release
```

Then:

```bash
cd android
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

APK native library check:

```bash
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libp2p_mobile.so
```

Expected entries:

```text
lib/arm64-v8a/libp2p_mobile.so
lib/x86_64/libp2p_mobile.so
```

## 1.4 Record connected test status

If a device/emulator is available:

```bash
cd android
./gradlew connectedDebugAndroidTest
```

If unavailable, document:

```text
connectedDebugAndroidTest: NOT RUN
Reason: <exact reason>
```

## 1.5 Acceptance

- [x] `docs/ANDROID_VALIDATION.md` has a fresh entry for the current tiny-final-patch code.
- [x] The entry clearly distinguishes `PASS`, `FAIL`, and `NOT RUN`.
- [x] No stale pre-patch validation entry is presented as current.
- [x] Unavailable validation is not marked as passing.

---

# Phase 2 — Strengthen STOP-before-native-start lifecycle test

This phase is optional but recommended.

## 2.1 Inspect current tests

Inspect service lifecycle tests, especially any test equivalent to:

```text
stopBeforeNativeStartSkipsNativeStartCall
stopDuringNativeStartStillStopsStaleRuntime
staleStartNeverPublishesRunningAfterStop
```

Confirm whether the test deterministically pauses the service immediately before native startup, or whether it relies on scheduling timing.

## 2.2 Add deterministic pre-native-start gate

If the existing test is scheduling-sensitive, add a deterministic fake/test hook.

Acceptable approaches:

### Option A — fake bridge gate

Use a fake bridge/repository that exposes a `CompletableDeferred` or latch before native start:

```kotlin
val beforeStart = CompletableDeferred<Unit>()
val allowStart = CompletableDeferred<Unit>()

fakeRepository.onBeforeStart = {
    beforeStart.complete(Unit)
    allowStart.await()
}
```

Test flow:

```kotlin
start service
await beforeStart
send STOP
release allowStart
assert native start was skipped or stale start was stopped
assert Running was not published
```

### Option B — service test hook

If the service already supports test injection, add a test-only gate immediately before `repository.start(...)`.

The hook must not affect production behavior.

## 2.3 Required test cases

Add or strengthen tests:

- [x] STOP before native start prevents `repository.start(...)` from being called.
- [x] PAUSE/network-block before native start prevents `repository.start(...)` from being called.
- [x] STOP during native start stops stale successful runtime.
- [x] stale START never publishes Running after STOP.
- [x] start-stop-start still works.

## 2.4 Acceptance

- [x] STOP-before-native-start behavior is tested deterministically.
- [x] No lifecycle race test depends on arbitrary `Thread.sleep()`.
- [x] Tests fail reliably if the pre-native-start generation check is removed.

---

# Phase 3 — Manual Android offer ↔ desktop answer E2E

This phase is required before final compatibility acceptance.

## 3.1 If environment is available, run E2E

Prepare desktop answer:

- [ ] start desktop Rust answer;
- [ ] record exact command;
- [ ] record desktop config path;
- [ ] record desktop public identity;
- [ ] record MQTT broker summary with secrets redacted;
- [ ] record remote service and forward ID.

Configure Android offer from the Android UI only:

- [ ] import/generate Android identity;
- [ ] import desktop answer public identity;
- [ ] configure MQTT broker;
- [ ] configure local forward, for example:
  ```text
  127.0.0.1:8080 -> llama
  ```
- [ ] keep cellular/metered blocked unless intentionally testing metered mode;
- [ ] start tunnel from app UI.

Browser validation:

- [ ] open Android browser;
- [ ] navigate to:
  ```text
  http://127.0.0.1:<local_port>
  ```
- [ ] confirm remote service responds;
- [ ] record response status/body summary;
- [ ] collect redacted Android logs;
- [ ] collect redacted desktop logs.

## 3.2 If E2E cannot be run

Document in `docs/ANDROID_VALIDATION.md`:

```text
Manual Android↔desktop E2E: NOT RUN
Reason: <exact reason>
Future run steps: <exact steps>
```

Leave these unchecked:

- [ ] Android offer connects to desktop Rust answer.
- [ ] Android browser reaches remote service via `127.0.0.1:<port>`.
- [ ] E2E result is documented with exact steps/results.

## 3.3 Acceptance

- [x] E2E is either run and documented as PASS/FAIL, or explicitly documented as NOT RUN.
- [x] E2E compatibility items remain unchecked unless the real test is run.
- [x] Automated tests are not treated as a substitute for manual Android browser ↔ desktop answer validation.

---

# Phase 4 — Final checklist

## 4.1 Documentation cleanup acceptance

- [x] Fresh validation entry added for current tiny-final-patch code.
- [x] PASS/FAIL/NOT RUN are clearly distinguished.
- [x] Stale pre-patch validation is not presented as current.
- [x] Connected test status is documented.
- [x] Manual E2E status is documented.

## 4.2 Optional test-quality acceptance

- [x] STOP-before-native-start test is deterministic.
- [x] No arbitrary sleep is needed for lifecycle race tests.
- [x] Test fails if pre-native-start generation check is removed.

## 4.3 Compatibility acceptance

Do not check unless real manual E2E is run:

- [ ] Android offer connects to desktop Rust answer.
- [ ] Android browser reaches remote service via `127.0.0.1:<port>`.
- [ ] E2E result is documented with exact steps/results.
