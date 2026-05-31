# Android WebRTC Tunnel Final Hardening TODO

## 1. Goal

Finish the last Android hardening items before final acceptance.

This TODO is intentionally narrower than previous Android TODOs. Do not redesign the app. Fix the remaining lifecycle, security, validation, and honesty gaps.

Highest-priority outcomes:

```text
E2E compatibility claims are honest.
Home/UI shows startup failures.
Service logs/notifications are redacted.
pause/stop/onDestroy paths do not block the service main thread.
Setup Wizard cannot create identity/local-peer mismatch.
Raw config export warns before export.
p2p-mobile inherits workspace lint discipline or has narrow documented exceptions.
FFI destroy is panic-safe.
```

## 2. Rules

- [ ] Keep Android work on the Android feature branch unless the user explicitly says otherwise.
- [ ] Do not merge to `master` until validation passes.
- [ ] Do not change MQTT signaling wire format.
- [ ] Do not change tunnel frame format.
- [ ] Do not change desktop Rust protocol semantics.
- [ ] Do not add TURN.
- [ ] Do not add VPN/TUN mode.
- [ ] Do not add arbitrary Android remote host/port selection.
- [ ] Do not allow cellular/metered data unless explicitly enabled.
- [ ] Keep Unknown network blocked always.
- [ ] Do not store private identity plaintext at rest.
- [ ] Do not log private keys, MQTT passwords, SDP, ICE candidates, decrypted payloads, or forwarded data.
- [ ] Bind local forwards to `127.0.0.1` by default.
- [ ] Do not check off incomplete acceptance items.
- [ ] If a command/test/E2E run is unavailable, document `NOT RUN` and leave the relevant item unchecked.

---

# Phase 0 — Fix checklist contradictions

## 0.1 Audit E2E claims

Audit:

```text
ANDROID_FIX_TODO_3.md
ANDROID_FIX_TODO_3(1).md
docs/ANDROID_VALIDATION.md
docs/memory.md
```

Find all claims/checks related to:

- [x] Android offer connects to desktop Rust answer.
- [x] Android browser reaches remote service via `127.0.0.1:<port>`.
- [x] manual E2E validation complete.
- [x] E2E validation documented with exact steps/results.

## 0.2 Correct E2E checklist state

If manual E2E is still not run:

- [x] mark Android offer ↔ desktop answer unchecked everywhere;
- [x] mark Android browser localhost validation unchecked everywhere;
- [x] mark manual E2E validation unchecked everywhere;
- [x] preserve documentation saying `NOT RUN`;
- [x] include exact reason and future run steps.

If E2E is run:

- [ ] document exact desktop command;
- [ ] document Android device/API/build;
- [ ] document broker summary with secrets redacted;
- [ ] document configured forward;
- [ ] document browser URL and result;
- [ ] document redacted logs;
- [ ] then check the relevant items.

## 0.3 Acceptance

- [x] No E2E item is checked unless real E2E evidence exists.
- [x] `docs/ANDROID_VALIDATION.md` clearly distinguishes PASS, FAIL, and NOT RUN.

---

# Phase 1 — Surface startup failures in Home/UI

## 1.1 Audit startup failure paths

Inspect:

```text
TunnelForegroundService
TunnelRepository
HomeViewModel
HomeScreen
RustTunnelBridge
```

Failure sources to cover:

- [ ] network blocked;
- [ ] missing encrypted identity;
- [ ] identity decrypt failure;
- [ ] config validation failure;
- [ ] native start failure;
- [ ] native library unavailable;
- [ ] service startup cancelled;
- [ ] native runtime exits with error.

## 1.2 Repository/UI state update

Implement a clear failure-state path.

Required:

- [ ] startup failure updates repository/shared status, not only notification;
- [ ] Home screen shows `Error` or `Paused/Blocked` with reason;
- [ ] error reason is actionable;
- [ ] error reason is redacted;
- [ ] stale prior `Running` state is cleared after failure;
- [ ] failed startup does not leave service pretending to run.

Suggested options:

```text
TunnelRepository.setLocalError(...)
TunnelRepository.setBlocked(...)
TunnelRepository.refreshStatusAfterFailure(...)
```

## 1.3 Tests

Add tests:

- [ ] config validation failure updates Home status;
- [ ] identity missing updates Home status;
- [ ] identity decrypt failure updates Home status;
- [ ] network blocked updates Home status;
- [ ] native start failure updates Home status;
- [ ] previous running state is cleared after startup failure;
- [ ] error text is redacted before UI state.

## 1.4 Acceptance

- [ ] Home/UI surfaces startup failures clearly.
- [ ] Notifications are not the only place startup failures appear.
- [ ] Startup failure state is redacted and actionable.

---

# Phase 2 — Redact service errors before logs/notifications/UI

## 2.1 Audit service error output

Inspect every service path that writes:

- [ ] `Log.e`;
- [ ] notification text;
- [ ] repository error state;
- [ ] status message;
- [ ] diagnostics/log event.

Specifically inspect:

```text
TunnelForegroundService.publishError(...)
TunnelForegroundService.publishStatus(...)
TunnelRepository.start(...)
TunnelRepository.stop(...)
```

## 2.2 Apply shared redaction

Use the same redaction component used by logs/diagnostics.

Required:

- [ ] redact message before Android `Log.e`;
- [ ] redact message before notification;
- [ ] redact message before repository/UI state;
- [ ] redact native error details before display;
- [ ] keep enough information for actionable debugging.

## 2.3 Tests

Add tests:

- [ ] service error containing `sign.private` is redacted;
- [ ] service error containing MQTT password is redacted;
- [ ] service error containing bearer token is redacted;
- [ ] service error containing SDP/ICE is redacted;
- [ ] notification message uses redacted text;
- [ ] Home error state uses redacted text.

## 2.4 Acceptance

- [ ] No service error display path bypasses redaction.
- [ ] Android logs/notifications do not expose secrets.

---

# Phase 3 — Move pause/stop/onDestroy native calls off service main thread

## 3.1 Audit remaining synchronous paths

Inspect:

```text
TunnelForegroundService.pause()
TunnelForegroundService.stopTunnel()
TunnelForegroundService.onDestroy()
Network callback pause/stop paths
```

Find any direct calls to:

- [ ] `repository.stop()`;
- [ ] native bridge stop;
- [ ] file cleanup;
- [ ] callback unregister that can block;
- [ ] coroutine cancellation that waits/blocking.

## 3.2 Make stop/pause async-safe

Implement:

- [ ] pause action launches service coroutine;
- [ ] stop action launches service coroutine or uses a bounded nonblocking stop path;
- [ ] onDestroy does not block main thread on native stop;
- [ ] pending startup job is cancelled safely;
- [ ] native stop is idempotent;
- [ ] duplicate STOP is safe;
- [ ] STOP during START is safe;
- [ ] notification/service state is updated consistently.

If onDestroy must call native stop synchronously, document why it is guaranteed nonblocking and add a test.

## 3.3 Race protection

Prevent duplicate startup/stop races:

- [ ] protect startup with `Mutex`, `AtomicBoolean`, or single-threaded serialized service actor;
- [ ] duplicate START cannot launch duplicate validation/decrypt/native start;
- [ ] STOP during pending START cannot leave runtime running unexpectedly;
- [ ] network policy pause cannot race with manual stop/start.

## 3.4 Tests

Add tests:

- [ ] pause does not call native stop on main thread;
- [ ] stop does not call native stop on main thread;
- [ ] onDestroy does not block on native stop;
- [ ] duplicate START is serialized;
- [ ] STOP during START is safe;
- [ ] network pause during START is safe;
- [ ] start-stop-start still works.

## 3.5 Acceptance

- [ ] ForegroundService has no blocking native stop/start path on main thread.
- [ ] Start/stop/pause race behavior is deterministic.

---

# Phase 4 — Fix Setup Wizard identity/local peer mismatch risk

## 4.1 Audit wizard identity flow

Inspect:

```text
SetupViewModel
SetupScreen
IdentityRepository
ConfigRepository.renderOfferConfig(...)
```

Document:

- [ ] where local peer ID is entered;
- [ ] where identity is generated/imported;
- [ ] how public identity peer ID is extracted;
- [ ] whether local peer ID can be edited after identity generation/import;
- [ ] whether save/start validates local peer ID matches identity peer ID.

## 4.2 Choose strategy

Pick one.

### Preferred: derive local peer ID from identity

- [ ] after generate/import identity, set local peer ID from canonical identity;
- [ ] make local peer ID read-only;
- [ ] render config from derived local peer ID;
- [ ] prevent manual mismatch.

### Alternative: validate manual local peer ID

- [ ] allow manual local peer ID editing;
- [ ] parse identity peer ID;
- [ ] block Save/Start if configured local peer ID differs;
- [ ] show actionable error.

## 4.3 Remote peer consistency

Also validate:

- [ ] remote peer ID matches pasted/imported remote public identity;
- [ ] authorized_keys is written from canonical public identity;
- [ ] duplicate remote identity entries are avoided.

## 4.4 Tests

Add tests:

- [ ] generated identity sets local peer ID;
- [ ] imported identity sets local peer ID;
- [ ] local peer ID cannot diverge after identity import/generation, or mismatch is rejected;
- [ ] save blocked on local identity mismatch;
- [ ] start blocked on local identity mismatch;
- [ ] remote peer ID mismatch rejected;
- [ ] review summary shows identity-derived peer IDs.

## 4.5 Acceptance

- [ ] Wizard cannot create config whose local peer ID differs from private identity.
- [ ] Wizard cannot authorize a remote identity under a mismatched remote peer ID.

---

# Phase 5 — Implement or honestly defer wizard Network Policy controls

## 5.1 Audit current Network Policy wizard step

Inspect:

```text
SetupScreen
SetupViewModel
NetworkPolicyViewModel
NetworkPolicyManager
```

Determine whether wizard currently supports:

- [ ] current network type display;
- [ ] metered/unmetered display;
- [ ] allowed/blocked result;
- [ ] blocked reason;
- [ ] allow metered toggle;
- [ ] metered/cellular warning;
- [ ] resume-on-unmetered option;
- [ ] Unknown blocked explanation.

## 5.2 Choose implementation state

Choose one.

### Option A — implement fully

- [ ] show current network state;
- [ ] show policy result;
- [ ] allow changing `allowMetered`;
- [ ] require warning before enabling metered/cellular;
- [ ] allow changing `resumeOnUnmetered`;
- [ ] save preferences before Start Tunnel;
- [ ] ensure Start Tunnel waits for preferences save.

### Option B — defer honestly

- [ ] remove/checklist-uncheck wizard Network Policy controls;
- [ ] wizard says policy is configured in Settings;
- [ ] Review step displays current policy read-only;
- [ ] Start Tunnel still enforces service policy;
- [ ] docs/TODO mark wizard controls deferred.

## 5.3 Fix preference save race

If wizard changes preferences:

- [ ] make save operation suspend/awaitable;
- [ ] Start Tunnel waits for preference save;
- [ ] service reads the newly saved preferences;
- [ ] tests cover changed preferences before Start Tunnel.

## 5.4 Tests

Depending on chosen option:

- [ ] wizard shows current network state;
- [ ] warning required before enabling metered;
- [ ] preference save completes before service start;
- [ ] Start Tunnel uses updated policy;
- [ ] if deferred, checklist/docs clearly mark controls deferred.

## 5.5 Acceptance

- [ ] Wizard Network Policy claims match implemented behavior.
- [ ] No preference-save race exists before Start Tunnel.

---

# Phase 6 — Add pre-export warning for raw config export

## 6.1 Audit export flows

Inspect:

```text
ImportExportViewModel
SettingsScreen
SetupScreen, if any export actions exist
DiagnosticsRepository
```

Identify:

- [ ] raw config export;
- [ ] redacted diagnostics export;
- [ ] public identity export/share;
- [ ] private identity export;
- [ ] logs export/copy.

## 6.2 Raw config warning

Before raw config export, require explicit confirmation:

```text
Raw Config Export Warning

This config may include broker addresses, usernames, password file paths,
peer IDs, local paths, and other operational details.

It must never include private identity material, but it may still be sensitive.

[Cancel]
[Export Raw Config]
```

Tasks:

- [ ] export does not happen until confirmation;
- [ ] warning appears every raw export, or until explicitly accepted for current action;
- [ ] redacted diagnostics export remains separate;
- [ ] post-export message does not replace pre-export warning.

## 6.3 Tests

Add tests:

- [ ] raw config export blocked without confirmation;
- [ ] raw config export succeeds after confirmation;
- [ ] cancel leaves no exported file;
- [ ] private identity is never included in config export;
- [ ] diagnostics export remains redacted and does not require raw config warning.

## 6.4 Acceptance

- [ ] Raw config export cannot happen accidentally.
- [ ] User is warned before sensitive operational config is written/shared.

---

# Phase 7 — Replace or honestly defer raw path import/export UX

## 7.1 Audit path-based UX

Find all UI fields/actions that require raw filesystem paths:

- [ ] config import path;
- [ ] config export path;
- [ ] private identity import path;
- [ ] private identity export path;
- [ ] public identity import path;
- [ ] public identity export path;
- [ ] diagnostics export path;
- [ ] logs export path.

## 7.2 Choose implementation state

Choose one.

### Option A — implement Android-safe UX

Use:

- [ ] `ACTION_OPEN_DOCUMENT` for config/private identity/public identity import;
- [ ] `ACTION_CREATE_DOCUMENT` for config/private identity/public identity export;
- [ ] Android share sheet for public identity;
- [ ] Android share sheet for diagnostics/logs where appropriate;
- [ ] app-private temp files cleaned after sharing;
- [ ] no hardcoded `/sdcard/Download/...` export path.

### Option B — defer honestly

- [ ] label raw path import/export as developer/debug only;
- [ ] uncheck production import/export UX items;
- [ ] remove claims that import/export is Android-safe;
- [ ] keep diagnostics/export safe from secrets;
- [ ] document SAF/share as future work.

## 7.3 Tests

If implementing SAF/share:

- [ ] import content URI works;
- [ ] export content URI works;
- [ ] share public identity intent created;
- [ ] diagnostics share intent created;
- [ ] hardcoded `/sdcard/Download` is removed;
- [ ] app-private temp share files are cleaned up.

If deferring:

- [ ] docs clearly mark raw path UX as debug/deferred;
- [ ] final checklist does not claim Android-safe import/export.

## 7.4 Acceptance

- [ ] Import/export UX is Android-safe, or honestly marked deferred.
- [ ] No hardcoded public external-storage path is required for diagnostics.

---

# Phase 8 — Apply workspace lint discipline to `p2p-mobile`

## 8.1 Audit current lint policy

Inspect:

```text
Cargo.toml
crates/p2p-mobile/Cargo.toml
```

Document:

- [ ] workspace lint policy;
- [ ] current `p2p-mobile` lint policy;
- [ ] whether workspace Clippy lints apply;
- [ ] why `unsafe_code` is allowed.

## 8.2 Apply preferred policy

Preferred target:

```toml
[lints]
workspace = true
```

If Rust/Cargo cannot combine this with `unsafe_code = "allow"` in the same way, use the narrowest equivalent configuration that preserves workspace Clippy lints.

Required:

- [ ] workspace Clippy lints apply to `p2p-mobile`;
- [ ] `unsafe_code` exception is documented;
- [ ] no broad lint suppression;
- [ ] unsafe blocks/functions have safety comments where required;
- [ ] no `unwrap()` introduced in FFI paths unless justified and allowed by lint policy.

## 8.3 Fix resulting warnings

Run:

```bash
cargo clippy --workspace --all-targets --all-features -- -D warnings
```

Tasks:

- [ ] fix warnings;
- [ ] avoid hiding warnings;
- [ ] document narrow exceptions only.

## 8.4 Acceptance

- [ ] `p2p-mobile` is not silently outside workspace lint discipline.
- [ ] Clippy passes with `-D warnings`.

---

# Phase 9 — Wrap FFI destroy in panic boundary

## 9.1 Audit destroy/dispose path

Inspect:

```text
p2ptunnel_destroy_runtime
AndroidTunnelController::stop
Drop behavior, if any
RustTunnelBridge.dispose()
```

Document:

- [ ] null handle behavior;
- [ ] double dispose behavior from Kotlin;
- [ ] raw double destroy limitations;
- [ ] whether stop can panic;
- [ ] whether drop can panic.

## 9.2 Implement panic-safe destroy

Required:

- [ ] `p2ptunnel_destroy_runtime()` catches unwind;
- [ ] null handle remains safe;
- [ ] stop/drop panic does not cross FFI;
- [ ] panic is logged/stored where feasible;
- [ ] Kotlin dispose remains double-call safe;
- [ ] calls after dispose fail locally with clear error.

## 9.3 Tests

Add tests where feasible:

- [ ] null destroy safe;
- [ ] Kotlin double dispose safe;
- [ ] calls after dispose return clear error;
- [ ] stop before dispose safe;
- [ ] destroy panic boundary covered or documented;
- [ ] no panic crosses FFI in tested invalid paths.

## 9.4 Acceptance

- [ ] Destroy/dispose path is FFI-safe.
- [ ] No panic can unwind across destroy FFI boundary.

---

# Phase 10 — Test Local Port honesty

## 10.1 Decide final state

Choose one.

### Option A — implement

- [ ] add nonblocking local port probe;
- [ ] avoid UI thread blocking;
- [ ] show success/failure;
- [ ] test success;
- [ ] test failure.

### Option B — defer

- [ ] leave Test Local Port unchecked;
- [ ] update final checklist wording to exclude test action;
- [ ] document deferral reason;
- [ ] do not claim “copy/open/test” support.

## 10.2 Acceptance

- [ ] Forward details checklist matches actual implemented behavior.

---

# Phase 11 — Full validation

## 11.1 Rust validation

Run:

```bash
cargo fmt --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets
```

Tasks:

- [ ] `cargo fmt --check` passes;
- [ ] clippy passes with `-D warnings`;
- [ ] Rust tests pass;
- [ ] no broad lint suppression added.

## 11.2 Android native build

Run:

```bash
cargo ndk   -t arm64-v8a   -t x86_64   -o android/app/src/main/jniLibs   build -p p2p-mobile --release
```

Tasks:

- [ ] native build passes;
- [ ] `arm64-v8a` output exists;
- [ ] `x86_64` output exists.

## 11.3 Android build/tests

Run:

```bash
cd android
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Tasks:

- [ ] `assembleDebug` passes;
- [ ] unit tests pass;
- [ ] APK contains native libraries.

## 11.4 Connected tests

If emulator/device available:

```bash
cd android
./gradlew connectedDebugAndroidTest
```

Tasks:

- [ ] connected tests pass;
- [ ] if not run, document exact reason.

## 11.5 Manual E2E

Run or document NOT RUN:

- [ ] desktop answer started;
- [ ] Android offer configured from UI;
- [ ] Android tunnel started;
- [ ] Android browser reaches `127.0.0.1:<port>`;
- [ ] remote service response recorded;
- [ ] redacted logs collected.

## 11.6 Documentation

Update:

```text
docs/ANDROID_VALIDATION.md
```

Include:

- [ ] date;
- [ ] commit hash;
- [ ] environment;
- [ ] command results;
- [ ] E2E result or NOT RUN reason;
- [ ] unresolved failures.

## 11.7 Acceptance

- [ ] Full validation docs are current and honest.
- [ ] Failed/unavailable validation is not checked as passed.

---

# Phase 12 — Final acceptance checklist

Do not check until complete.

## 12.1 E2E / compatibility

- [ ] Android offer connects to desktop Rust answer, or remains unchecked with NOT RUN reason.
- [ ] Android browser reaches remote service via `127.0.0.1:<port>`, or remains unchecked with NOT RUN reason.
- [ ] Protocol wire formats unchanged.
- [ ] Desktop Rust tests pass.

## 12.2 Runtime/UI

- [ ] Home/UI surfaces startup failures.
- [ ] Startup failure state is actionable and redacted.
- [ ] Previous running state is cleared after failed startup.
- [ ] ForegroundService startup/pause/stop paths do not block main thread.
- [ ] Duplicate START/STOP races are controlled.

## 12.3 Security

- [ ] Service errors are redacted before logs.
- [ ] Service errors are redacted before notifications.
- [ ] Logs remain redacted before display/copy/export.
- [ ] Diagnostics remain redacted.
- [ ] Raw config export requires warning before export.
- [ ] Private identity remains encrypted at rest.
- [ ] FFI destroy path is panic-safe.

## 12.4 Setup/import/export

- [ ] Setup Wizard cannot create local peer/private identity mismatch.
- [ ] Setup Wizard cannot create remote peer/public identity mismatch.
- [ ] Wizard Network Policy behavior is implemented or honestly deferred.
- [ ] Import/export UX is Android-safe or honestly deferred.
- [ ] Test Local Port is implemented or honestly deferred.

## 12.5 Build/lints

- [ ] `p2p-mobile` inherits workspace lint discipline or has narrow documented exceptions.
- [ ] `cargo fmt --check` passes.
- [ ] `cargo clippy --workspace --all-targets --all-features -- -D warnings` passes.
- [ ] `cargo test --workspace --all-targets` passes.
- [ ] `cargo ndk ... build -p p2p-mobile --release` passes.
- [ ] `./gradlew assembleDebug` passes.
- [ ] `./gradlew testDebugUnitTest` passes.
- [ ] connected tests pass if available, or NOT RUN is documented.

---

# Suggested implementation order

1. [ ] Fix E2E checklist contradictions.
2. [ ] Surface startup failures in Home/UI.
3. [ ] Redact service errors before logs/notifications/UI.
4. [ ] Move pause/stop/onDestroy native stop paths off main thread.
5. [ ] Fix service start/stop race protection.
6. [ ] Fix Setup Wizard identity/local peer mismatch risk.
7. [ ] Implement or honestly defer wizard Network Policy controls.
8. [ ] Add raw config export pre-warning.
9. [ ] Implement or honestly defer Android SAF/share import/export UX.
10. [ ] Apply workspace lint discipline to `p2p-mobile`.
11. [ ] Wrap FFI destroy in panic boundary.
12. [ ] Keep Test Local Port honestly deferred or implement it.
13. [ ] Run full validation.
14. [ ] Run real Android↔desktop E2E or leave compatibility unchecked with NOT RUN reason.
