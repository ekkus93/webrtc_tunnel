# Android WebRTC Tunnel Final Patch TODO

## 1. Goal

Apply the final small patch to the Android app before final E2E validation.

This TODO is deliberately narrow. Do not redesign the app. Fix the remaining concrete issues from the latest review.

## 2. Rules

- [ ] Keep Android work on the Android feature branch unless the user explicitly says otherwise.
- [ ] Do not merge to `master` until validation passes.
- [ ] Do not change MQTT signaling wire format.
- [ ] Do not change tunnel frame format.
- [ ] Do not change desktop Rust protocol semantics.
- [ ] Do not add TURN.
- [ ] Do not add VPN/TUN mode.
- [ ] Do not add arbitrary Android remote host/port selection.
- [ ] Do not weaken encrypted identity-at-rest behavior.
- [ ] Do not weaken network policy behavior.
- [ ] Do not weaken log/diagnostic redaction.
- [ ] Do not check off E2E compatibility unless the real Android↔desktop test is run and documented.

---

# Phase 0 — Preserve E2E honesty

## 0.1 Audit current validation docs

Inspect:

```text
docs/ANDROID_VALIDATION.md
ANDROID_FINAL_HARDENING_TODO.md
ANDROID_FINAL_HARDENING_TODO(2).md, if present
docs/memory.md
```

Check all references to:

- [ ] Android offer connects to desktop Rust answer.
- [ ] Android browser reaches remote service via `127.0.0.1:<port>`.
- [ ] manual E2E validation complete.
- [ ] E2E validation documented with exact steps/results.

## 0.2 Correct state

If manual E2E is still not run:

- [ ] keep Android offer ↔ desktop answer unchecked;
- [ ] keep Android browser localhost validation unchecked;
- [ ] keep manual E2E validation unchecked;
- [x] document `NOT RUN`;
- [x] include exact reason;
- [x] include future run steps.

If manual E2E is run:

- [ ] document exact desktop command;
- [ ] document desktop config summary;
- [ ] document Android device/emulator and API level;
- [ ] document Android app build;
- [ ] document broker summary with secrets redacted;
- [ ] document configured forward;
- [ ] document Android browser URL;
- [ ] document response result;
- [ ] document redacted Android/desktop logs;
- [ ] mark E2E items complete only after this evidence exists.

## 0.3 Acceptance

- [x] No E2E item is checked unless real E2E evidence exists.
- [x] Validation docs clearly distinguish PASS, FAIL, and NOT RUN.

---

# Phase 1 — Apply workspace lint discipline to `p2p-mobile`

## 1.1 Audit lint configuration

Inspect:

```text
Cargo.toml
crates/p2p-mobile/Cargo.toml
```

Document:

- [x] root workspace lint policy;
- [x] current mobile crate lint policy;
- [x] whether workspace Clippy lints apply to `p2p-mobile`;
- [x] why `unsafe_code` is allowed.

## 1.2 Implement lint policy

Preferred:

```toml
[lints]
workspace = true
```

If Cargo configuration does not allow the preferred form together with a required `unsafe_code` exception:

- [x] preserve workspace Clippy lint behavior by the narrowest equivalent means;
- [x] document why the exception is needed;
- [x] keep the exception limited to Rust `unsafe_code`;
- [x] do not suppress `unwrap_used`, `todo`, `dbg_macro`, or warning-level Clippy checks broadly.

## 1.3 Fix lint fallout

Run:

```bash
cargo clippy --workspace --all-targets --all-features -- -D warnings
```

Tasks:

- [x] fix any new warnings/errors;
- [x] do not hide warnings with broad `allow`;
- [x] add safety comments for unsafe blocks/functions if lint policy requires it;
- [x] keep FFI-specific exceptions narrow and documented.

## 1.4 Acceptance

- [x] `p2p-mobile` is not silently outside workspace lint discipline.
- [x] `unsafe_code` exception is documented and narrow.
- [x] Clippy passes with `-D warnings`.

---

# Phase 2 — Wrap FFI destroy in panic boundary

## 2.1 Audit destroy path

Inspect:

```text
p2ptunnel_destroy_runtime
AndroidTunnelController::stop
RustTunnelBridge.dispose()
```

Document:

- [x] null handle behavior;
- [x] Kotlin double-dispose behavior;
- [x] raw double-destroy limitations;
- [x] whether `stop()` can panic;
- [x] whether controller drop can panic;
- [x] where panic/error can be recorded.

## 2.2 Implement panic-safe destroy

Required:

- [x] `p2ptunnel_destroy_runtime()` uses `catch_unwind` or existing panic-boundary helper;
- [x] null handle remains safe;
- [x] stop/drop panic cannot cross FFI;
- [x] panic is logged/stored where feasible;
- [x] Kotlin `dispose()` remains double-call safe;
- [x] calls after `dispose()` fail locally with clear error.

## 2.3 Tests

Add or update tests:

- [x] null destroy is safe;
- [x] Kotlin double dispose is safe;
- [x] calls after dispose return clear error;
- [x] stop before dispose is safe;
- [x] panic boundary is covered or explicitly documented if hard to trigger;
- [x] invalid native paths do not unwind across FFI.

## 2.4 Acceptance

- [x] FFI destroy path is panic-safe.
- [x] No panic can unwind across destroy FFI boundary.

---

# Phase 3 — Fix STOP-during-START lifecycle race

## 3.1 Audit lifecycle flow

Inspect:

```text
TunnelForegroundService.onStartCommand(...)
TunnelForegroundService.startOffer(...)
TunnelForegroundService.doStartOffer(...)
TunnelForegroundService.stopServiceWork(...)
TunnelForegroundService.pause(...)
network callback pause/resume paths
```

Document:

- [x] where START captures state;
- [x] where STOP cancels startup;
- [x] where native `repository.start()` can still be in flight;
- [x] where Running state is published;
- [x] whether STOP can arrive while native START is in progress.

## 3.2 Implement deterministic lifecycle protection

Choose one:

### Option A — generation token

- [x] increment generation on every START;
- [x] increment generation on every STOP;
- [x] increment generation on every PAUSE/network-block;
- [x] START captures generation;
- [x] after native start returns, START checks generation is still current;
- [x] if stale, call `repository.stop()` and do not publish Running;
- [x] STOP wins over older START.

### Option B — serialized actor/state machine

- [ ] all START/STOP/PAUSE events go through one serialized lifecycle queue;
- [ ] no overlapping native start/stop transitions;
- [ ] desired state controls final published state;
- [ ] STOP wins over pending START.

## 3.3 Requirements

- [x] duplicate START does not run duplicate native starts;
- [x] STOP during pending START is safe;
- [x] STOP during native START cannot leave stale Running state;
- [x] network policy pause during START is safe;
- [x] start-stop-start still works;
- [x] notification state matches repository state.

## 3.4 Tests

Add tests:

- [x] duplicate START is serialized;
- [x] STOP before native START returns prevents Running state;
- [x] STOP during artificial delayed native START stops runtime after stale success;
- [x] network pause during pending START prevents Running state;
- [x] start-stop-start succeeds;
- [x] repository status is not stale after cancelled START.

## 3.5 Acceptance

- [x] STOP wins over in-flight START.
- [x] Stale START cannot publish Running after STOP/PAUSE.
- [x] Lifecycle behavior is deterministic.

---

# Phase 4 — Remove or async-fix stale `startAnswer()` path

## 4.1 Audit answer path

Inspect:

```text
ACTION_START_ANSWER handling
TunnelForegroundService.startAnswer()
Setup Wizard mode selection
any Answer-mode UI entry points
```

Document:

- [x] whether answer mode is supported on Android;
- [x] whether any UI can trigger answer mode;
- [x] whether `startAnswer()` performs native start synchronously;
- [x] whether answer mode is covered by tests.

## 4.2 Choose final behavior

Choose one.

### Option A — remove/dead-code eliminate

- [ ] remove unused `startAnswer()` method;
- [ ] ensure `ACTION_START_ANSWER` returns clear disabled error;
- [ ] update tests.

### Option B — keep disabled safely

- [x] keep answer mode explicitly disabled;
- [x] attempted answer start returns redacted actionable error;
- [x] no native startup occurs;
- [x] no synchronous blocking path remains.

### Option C — make async-safe

- [ ] implement answer path through same lifecycle-safe async flow as offer;
- [ ] add tests equivalent to offer lifecycle tests.

## 4.3 Acceptance

- [x] No stale synchronous answer native start path remains.
- [x] Android v1 answer-mode behavior is explicit and tested.

---

# Phase 5 — Clean config import temp file on all paths

## 5.1 Audit config import

Inspect:

```text
ImportExportViewModel.importConfigContent(...)
ConfigRepository.writeConfigAtomically(...)
ConfigRepository.validate...
```

Find temp files such as:

```text
config-import-candidate.toml
```

## 5.2 Implement cleanup

Required:

- [x] temp file deleted on successful validation/import;
- [x] temp file deleted on validation failure;
- [x] temp file deleted on thrown exception;
- [x] active config unchanged on validation failure;
- [x] active config unchanged on exception.

Use `try/finally` or equivalent.

## 5.3 Tests

Add tests:

- [x] valid import deletes temp file;
- [x] invalid import deletes temp file;
- [x] thrown validation error deletes temp file;
- [x] invalid import does not replace active config;
- [x] exception path does not replace active config.

## 5.4 Acceptance

- [x] Config import leaves no stale temp file.
- [x] Invalid import remains transactional.

---

# Phase 6 — Replace `Thread.sleep()` in tests

## 6.1 Audit tests

Search for:

```text
Thread.sleep
delay(...)
runBlocking
```

in Android unit/instrumentation tests.

Pay special attention to:

```text
ForwardsViewModelTest
service lifecycle tests
network policy tests
```

## 6.2 Replace fixed sleeps

Use one or more:

- [x] `runTest`;
- [x] test dispatcher;
- [x] `advanceUntilIdle`;
- [x] deterministic fake bridge callback;
- [x] polling helper with timeout and clear failure;
- [x] `CompletableDeferred`;
- [x] fake socket/server lifecycle synchronization.

## 6.3 Tests

- [x] no `Thread.sleep()` remains in unit tests unless narrowly justified;
- [x] Test Local Port success/failure tests remain reliable;
- [x] lifecycle race tests are deterministic;
- [x] tests do not become timing-flaky.

## 6.4 Acceptance

- [x] Tests avoid fixed sleeps where deterministic synchronization is possible.
- [x] Test Local Port tests are stable.

---

# Phase 7 — Clean up Setup Wizard remote public identity UX

## 7.1 Audit current wizard layout

Inspect:

```text
SetupScreen
SetupViewModel
SetupConfigInput
```

Document where these are shown:

- [x] local private identity import/generation;
- [x] local public identity;
- [x] local peer ID;
- [x] remote peer ID;
- [x] remote public identity.

## 7.2 Fix labeling/placement

Preferred:

- [x] local identity generation/import remains on Identity step;
- [x] local public identity remains on Identity step;
- [x] remote peer ID moves to Remote Peer step;
- [x] remote public identity moves to Remote Peer step;
- [x] Remote Peer step validates peer ID/public identity match;
- [x] Review step clearly separates Local Identity and Remote Peer.

Acceptable:

- [x] if not moving fields, clearly label remote identity section as Remote Peer Identity;
- [x] avoid implying remote public identity belongs to local identity;
- [x] keep mismatch validation.

## 7.3 Tests

Add/update tests:

- [x] local identity values shown in Identity step;
- [x] remote public identity values shown/labeled in Remote Peer step or clearly separated;
- [x] remote peer mismatch still rejected;
- [x] review summary clearly separates local and remote peers.

## 7.4 Acceptance

- [x] Wizard does not confuse local identity with remote peer identity.
- [x] Existing local/remote peer consistency validation remains intact.

---

# Phase 8 — Keep Test Local Port honest

## 8.1 Verify implementation

Inspect:

```text
ForwardsViewModel.testLocalPort(...)
ForwardsScreen
ForwardsViewModelTest
```

Confirm:

- [x] probe runs off UI thread;
- [x] probe targets the configured local host/port;
- [x] success/failure result is shown;
- [x] failure is actionable;
- [x] tests are deterministic after Phase 6.

## 8.2 Acceptance

- [x] Test Local Port is implemented and tested, or checklist says deferred.
- [x] There is no false claim about copy/open/test support.

---

# Phase 9 — Validation

## 9.1 Rust validation

Run:

```bash
cargo fmt --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets
```

Tasks:

- [x] `cargo fmt --check` passes;
- [x] Clippy passes with `-D warnings`;
- [x] Rust tests pass;
- [x] no broad lint suppression added.

## 9.2 Android native build

Run:

```bash
cargo ndk \
  -t arm64-v8a \
  -t x86_64 \
  -o android/app/src/main/jniLibs \
  build -p p2p-mobile --release
```

Tasks:

- [x] native build passes;
- [x] `arm64-v8a` output exists;
- [x] `x86_64` output exists.

## 9.3 Android build/tests

Run:

```bash
cd android
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Tasks:

- [x] `assembleDebug` passes;
- [x] unit tests pass;
- [x] APK contains native libraries.

## 9.4 Connected tests

If emulator/device available:

```bash
cd android
./gradlew connectedDebugAndroidTest
```

Tasks:

- [x] connected tests pass;
- [x] if not run, document exact reason.

## 9.5 APK native library check

Run:

```bash
unzip -l android/app/build/outputs/apk/debug/app-debug.apk | grep libp2p_mobile.so
```

Expected:

- [x] `lib/arm64-v8a/libp2p_mobile.so`;
- [x] `lib/x86_64/libp2p_mobile.so`.

## 9.6 Manual E2E

Run if environment is available:

- [ ] start desktop answer;
- [ ] configure Android offer from UI;
- [ ] start Android tunnel;
- [ ] open Android browser at `http://127.0.0.1:<port>`;
- [ ] confirm remote service response;
- [ ] collect redacted logs;
- [ ] document PASS/FAIL.

If not available:

- [x] document `NOT RUN`;
- [x] leave E2E acceptance unchecked.

## 9.7 Documentation

Update:

```text
docs/ANDROID_VALIDATION.md
```

Include:

- [x] date;
- [x] commit hash;
- [x] environment;
- [x] command results;
- [x] E2E result or NOT RUN reason;
- [x] unresolved failures.

## 9.8 Acceptance

- [x] Validation docs are current.
- [x] PASS/FAIL/NOT RUN are clearly distinguished.
- [x] No unavailable validation is marked as passing.

---

# Phase 10 — Final acceptance checklist

## 10.1 Required for this final patch

- [x] `p2p-mobile` inherits workspace Clippy discipline or has narrow documented equivalent.
- [x] FFI destroy path is panic-safe.
- [x] STOP during START cannot publish stale Running.
- [x] stale synchronous answer path removed, disabled safely, or made async-safe.
- [x] config import temp files are cleaned on success/failure/exception.
- [x] fixed sleeps removed from tests where practical.
- [x] Setup Wizard remote public identity UX is clear.
- [x] Test Local Port implementation/checklist is honest.
- [x] validation docs are updated.

## 10.2 Required before compatibility acceptance

- [ ] Android offer connects to desktop Rust answer.
- [ ] Android browser reaches remote service via `127.0.0.1:<port>`.
- [ ] E2E result is documented with exact steps/results.

## 10.3 Required before merge

- [ ] `cargo fmt --check` passes.
- [ ] `cargo clippy --workspace --all-targets --all-features -- -D warnings` passes.
- [ ] `cargo test --workspace --all-targets` passes.
- [ ] `cargo ndk ... build -p p2p-mobile --release` passes.
- [ ] `./gradlew assembleDebug` passes.
- [ ] `./gradlew testDebugUnitTest` passes.
- [ ] connected tests pass if available, or NOT RUN is documented.
