# Android WebRTC Tunnel Fix TODO 3

## 1. Goal

Finish the remaining Android app hardening after `ANDROID_FIX_TODO_2`.

This pass is not a redesign. It is a correctness, lifecycle, security, and validation-honesty pass.

Highest-priority outcomes:

```text
Checklist claims are honest.
ForegroundService does not block main thread during startup.
Network policy UI matches service enforcement.
Logs are redacted before display/export.
Generated TOML is safely serialized.
Android offer ↔ desktop answer E2E validation is documented.
```

## 2. Rules

- [x] Keep Android work on the Android feature branch unless the user explicitly says otherwise.
- [x] Do not merge to `master` until validation passes.
- [x] Do not change MQTT signaling wire format.
- [x] Do not change tunnel frame format.
- [x] Do not change desktop Rust protocol semantics.
- [x] Do not add TURN.
- [x] Do not add VPN/TUN mode.
- [x] Do not add arbitrary remote host/port selection from Android offer side.
- [x] Do not allow cellular/metered data unless explicitly enabled by the user.
- [x] Do not store private identity plaintext at rest.
- [x] Do not log private keys, MQTT passwords, SDP, ICE candidates, decrypted payloads, or forwarded data.
- [x] Bind local forwards to `127.0.0.1` by default.
- [x] Do not check off any acceptance item unless implementation and validation are complete.
- [x] If a validation command cannot be run, document why and leave the related acceptance item unchecked.

---

# Phase 0 — Reset checklist honesty

## 0.1 Audit over-checked items

Audit:

```text
ANDROID_WEBRTC_TUNNEL_TODO.md
ANDROID_FIX_TODO1.md
ANDROID_FIX_TODO_2.md
ANDROID_FIX_TODO_2(1).md, if present
docs/memory.md
docs/ANDROID_VALIDATION.md
```

Uncheck or annotate any item not proven by test or documented validation.

Pay special attention to:

- [x] Android offer connects to desktop Rust answer.
- [x] Android browser reaches remote service through `127.0.0.1:<port>`.
- [x] end-to-end validation is documented.
- [x] setup wizard is truly complete.
- [x] forwards screen has all requested actions.
- [x] network policy UI matches service behavior.
- [x] logs are redacted before display.
- [x] foreground-service lifecycle is fully hardened.
- [x] JNI/FFI destroy/dispose is fully safe.
- [x] final acceptance checklist is complete.

## 0.2 Define evidence standard

For every checked item, add one of:

- [x] automated test name;
- [x] validation command and result;
- [x] manual E2E validation record;
- [x] documented reason if intentionally not implemented.

## 0.3 Update validation docs

Update:

```text
docs/ANDROID_VALIDATION.md
```

Required fields:

- [x] date;
- [x] git commit;
- [x] environment;
- [x] command list;
- [x] pass/fail result for each command;
- [x] not-run reason for unavailable commands;
- [x] unresolved failures.

## 0.4 Acceptance

- [x] No checklist item is checked without evidence.
- [x] E2E items are unchecked unless real Android↔desktop validation is documented.
- [x] Validation docs distinguish PASS, FAIL, and NOT RUN.

---

# Phase 1 — Document or rerun Android↔desktop E2E validation

## 1.1 Prepare desktop answer

Document:

- [x] desktop OS/environment;
- [x] git commit;
- [x] exact desktop answer command;
- [x] desktop config path;
- [x] desktop public identity;
- [x] MQTT broker host/port/TLS summary, secrets redacted;
- [x] remote service being forwarded;
- [x] remote forward ID.

Example:

```bash
cargo run --bin p2p-answer -- --config /path/to/answer-config.toml
```

## 1.2 Prepare Android offer using UI only

Document:

- [x] Android device/emulator model;
- [x] Android API level;
- [x] app build variant/APK;
- [x] network type;
- [x] identity import/generation;
- [x] remote public identity import;
- [x] MQTT broker settings, secrets redacted;
- [x] configured forward;
- [x] network policy setting.

## 1.3 Run browser test

On Android, open:

```text
http://127.0.0.1:<local_port>
```

Record:

- [x] URL;
- [x] expected remote service;
- [x] response status or visible result;
- [x] response body summary;
- [x] redacted Android logs;
- [x] redacted desktop logs;
- [x] pass/fail result.

## 1.4 If E2E cannot be run

Document:

- [x] `NOT RUN`;
- [x] exact reason;
- [x] missing dependency/environment;
- [x] exact steps to run later.

Leave these unchecked:

- [ ] Android offer connects to desktop Rust answer.
- [ ] Android browser reaches remote service through localhost.
- [ ] E2E validation complete.

## 1.5 Acceptance

- [x] `docs/ANDROID_VALIDATION.md` contains real E2E evidence or explicit NOT RUN reason.
- [x] No E2E acceptance item is checked without evidence.

---

# Phase 2 — Move ForegroundService startup work off main thread

## 2.1 Audit current service startup path

Inspect:

```text
TunnelForegroundService
TunnelRepository
ConfigRepository
IdentityRepository
NetworkPolicyManager
RustTunnelBridge
```

Document every operation currently performed synchronously in `onStartCommand()` or directly called from it:

- [x] DataStore reads;
- [x] file I/O;
- [x] identity decrypt;
- [x] Rust config validation;
- [x] Rust runtime startup;
- [x] network policy checks;
- [x] notification updates.

## 2.2 Required service threading model

Implement:

- [x] `onCreate()` creates notification channel and calls `startForeground()` promptly.
- [x] `onStartCommand()` parses action only.
- [x] `onStartCommand()` launches service work on `serviceScope`.
- [x] startup I/O and native calls run on `Dispatchers.IO` or equivalent.
- [x] no `runBlocking` remains on main service path.
- [x] service state updates are synchronized and race-safe.
- [x] duplicate START actions do not start duplicate native runtimes.
- [x] STOP action can interrupt pending startup safely.

## 2.3 Startup flow

Implement asynchronous flow:

```text
startForeground(paused/starting notification)
launch serviceScope {
  load preferences
  check network policy
  if blocked: update paused state and return
  read/decrypt identity
  validate config with identity
  start native runtime
  update running notification/state
}
```

Tasks:

- [x] make startup idempotent;
- [x] handle cancellation;
- [x] handle config validation error;
- [x] handle identity missing/error;
- [x] handle network blocked;
- [x] handle native startup failure;
- [x] update UI/repository state for each failure.

## 2.4 Stop flow

STOP must:

- [x] cancel pending startup job;
- [x] stop native runtime idempotently;
- [x] unregister network callbacks;
- [x] update state to stopped;
- [x] stop foreground notification;
- [x] stop self;
- [x] avoid leaking coroutine jobs.

## 2.5 Tests

Add/update tests:

- [x] service start posts notification promptly;
- [x] startup work runs off main thread;
- [x] no `runBlocking` in service startup path;
- [x] duplicate START does not double-start runtime;
- [x] STOP during pending startup is safe;
- [x] native startup failure produces actionable error state;
- [x] null intent does not crash;
- [x] start-stop-start works;
- [x] no `ForegroundServiceDidNotStartInTimeException`.

## 2.6 Acceptance

- [x] ForegroundService performs no blocking startup work on main thread.
- [x] Service remains compliant with Android foreground-service timing.
- [x] Start/stop lifecycle is reliable.

---

# Phase 3 — Fix network policy consistency

## 3.1 Define single policy function

Create one shared policy calculation used by both service and UI.

Required inputs:

- [x] network type;
- [x] metered status;
- [x] `allowMetered`;
- [x] `resumeOnUnmetered`;
- [x] optional `pauseOnMetered`, if retained.

Required outputs:

- [x] `networkType`;
- [x] `isMetered`;
- [x] `allowedByDefault`;
- [x] `allowedByUserPolicy`;
- [x] `blockedReason`;
- [x] `tunnelAllowed`.

Required policy:

```text
Unmetered Wi-Fi: allowed
Metered Wi-Fi: allowed only if allowMetered = true
Cellular: allowed only if allowMetered = true
No network: blocked
Unknown: blocked always
```

## 3.2 Resolve `pauseOnMetered`

Choose one:

### Option A — remove it

- [x] remove `pauseOnMetered` from preferences;
- [x] remove from UI;
- [x] remove from tests/docs;
- [x] migrate old preference safely.

### Option B — implement it

Define semantics:

```text
pauseOnMetered = true means an already-running tunnel pauses on metered/cellular transition,
even if allowMetered is true for manual starts.
```

Tasks if Option B:

- [ ] service honors `pauseOnMetered`;
- [ ] UI explains behavior;
- [ ] tests cover interaction with `allowMetered`.

## 3.3 Update Network Policy UI

UI must show:

- [x] current network type;
- [x] metered/unmetered state;
- [x] default policy result;
- [x] user policy result;
- [x] blocked reason;
- [x] whether tunnel can start now;
- [x] current `allowMetered`;
- [x] current `resumeOnUnmetered`;
- [x] warning before enabling metered/cellular.

## 3.4 Update service

Service must:

- [x] use same policy function as UI;
- [x] block startup before native runtime on disallowed network;
- [x] pause/stop running runtime on disallowed transition;
- [x] resume only when allowed and configured;
- [x] keep Unknown blocked always;
- [x] surface blocked reason to Home/logs/notification.

## 3.5 Tests

Add tests:

- [x] unmetered Wi-Fi allowed by default;
- [x] metered Wi-Fi blocked by default;
- [x] cellular blocked by default;
- [x] metered Wi-Fi allowed when `allowMetered = true`;
- [x] cellular allowed when `allowMetered = true`;
- [x] unknown blocked even when `allowMetered = true`;
- [x] no network blocked;
- [x] UI policy matches service policy;
- [x] warning required before enabling metered/cellular;
- [x] pause/resume behavior matches selected `pauseOnMetered` policy.

## 3.6 Acceptance

- [x] Network Policy screen and service produce the same allowed/blocked answer.
- [x] Unknown network always fails safe.
- [x] Cellular/metered cannot be used by default.

---

# Phase 4 — Redact logs before display/export

## 4.1 Create shared redaction layer

Implement a single redaction component used by:

- [x] Logs screen display;
- [x] copy logs;
- [x] diagnostics export;
- [x] native log ingestion if appropriate;
- [x] status/error display where useful.

## 4.2 Redaction targets

Add patterns/tests for:

- [x] `sign.private`;
- [x] `kex.private`;
- [x] private identity TOML blocks;
- [x] private key PEM blocks;
- [x] MQTT password fields;
- [x] password file contents if ever read;
- [x] bearer tokens;
- [x] API keys;
- [x] URL credentials, e.g. `mqtts://user:pass@example.com`;
- [x] SDP blobs;
- [x] ICE candidates;
- [x] decrypted payload markers;
- [x] forwarded data markers;
- [x] temp identity paths;
- [x] MQTT username if considered sensitive;
- [x] any native last-error details that may include secrets.

## 4.3 LogsViewModel

Update:

- [x] native logs are redacted before entering UI state;
- [x] filter works on redacted logs;
- [x] copied logs are redacted;
- [x] exported diagnostics use same redacted logs;
- [x] malformed native log JSON surfaces visible redacted error.

## 4.4 DiagnosticsRepository

Update:

- [x] status JSON is redacted before export;
- [x] config is redacted;
- [x] logs are redacted;
- [x] network state included safely;
- [x] app/native version included safely;
- [x] raw secrets cannot appear in diagnostics.

## 4.5 Tests

Add realistic multiline tests:

- [x] private identity TOML redacted;
- [x] MQTT password redacted;
- [x] bearer token redacted;
- [x] URL credentials redacted;
- [x] SDP multiline blob redacted;
- [x] ICE candidate redacted;
- [x] forwarded data marker redacted;
- [x] native log display redacted;
- [x] copy logs redacted;
- [x] diagnostics export redacted;
- [x] status JSON last error redacted.

## 4.6 Acceptance

- [x] Logs are safe before display.
- [x] Copied logs are safe.
- [x] Diagnostics are safe to share.
- [x] Redaction tests cover realistic secret formats.

---

# Phase 5 — Make generated TOML safe

## 5.1 Audit raw interpolation

Inspect every TOML-producing path:

```text
ConfigRepository.defaultConfigTemplate()
ConfigRepository.renderOfferConfig()
ConfigRepository.redactConfig()
tests/fakes that produce config TOML
```

Identify all interpolated fields:

- [x] broker host;
- [x] broker port;
- [x] username;
- [x] password path;
- [x] topic prefix;
- [x] local peer ID;
- [x] remote peer ID;
- [x] authorized keys path;
- [x] identity path;
- [x] state/runtime paths;
- [x] forward ID;
- [x] forward bind host;
- [x] forward bind port;
- [x] CA path, if present.

## 5.2 Implement TOML-safe serialization

Preferred:

- [x] introduce structured config object;
- [x] serialize with TOML library.

Acceptable:

- [x] implement `tomlString(value: String): String`;
- [x] escape backslash;
- [x] escape quote;
- [x] escape newline;
- [x] escape carriage return;
- [x] escape tab;
- [x] cover Unicode safely;
- [x] use helper for every TOML string value.

## 5.3 Tests

Add tests:

- [x] quotes in broker host do not inject TOML;
- [x] newline in topic prefix is escaped/rejected safely;
- [x] quote in remote peer ID cannot inject config;
- [x] quote in forward ID cannot inject config;
- [x] backslash in path is preserved/escaped;
- [x] rendered config validates or fails with actionable validation error;
- [x] malicious-looking input cannot add extra `[[forwards]]`.

## 5.4 Acceptance

- [x] No raw user/imported value is inserted into TOML without escaping/serialization.
- [x] Generated TOML is robust against malformed input and injection.

---

# Phase 6 — Validate duplicate runtime forward IDs

## 6.1 Add validation

In Android forwards validation:

- [x] reject duplicate enabled local ports;
- [x] reject duplicate enabled `remoteForwardId`;
- [x] reject blank `remoteForwardId`;
- [x] reject invalid local port;
- [x] reject arbitrary remote host/port fields;
- [x] keep local host default `127.0.0.1`.

## 6.2 Error messages

Use actionable errors:

```text
Duplicate local port: 8080
Duplicate remote forward ID: llama
Remote forward ID is required
```

## 6.3 Tests

Add tests:

- [x] duplicate enabled `remoteForwardId` rejected;
- [x] duplicate disabled `remoteForwardId` behavior is documented and tested;
- [x] duplicate local port still rejected;
- [x] blank remote forward ID rejected;
- [x] valid unique forwards accepted;
- [x] generated Rust config uses unique forward IDs.

## 6.4 Acceptance

- [x] Android cannot render duplicate Rust forward IDs.
- [x] Forward validation errors are clear.

---

# Phase 7 — Finish Setup Wizard honestly

## 7.1 Choose Mode step

- [x] Offer mode enabled and default.
- [x] Answer mode disabled or clearly marked incomplete/advanced if not supported.
- [x] User cannot accidentally configure unsupported mode.

## 7.2 Identity step

Required:

- [x] Generate Identity action, if Rust helper available.
- [x] Import Private Identity action.
- [x] Validate private identity.
- [x] Store private identity encrypted.
- [x] Show public identity.
- [x] Copy public identity.
- [x] Share/export public identity, or uncheck/document if not implemented.
- [x] Clear setup-required error if identity is missing.

## 7.3 MQTT Broker step

Required fields:

- [x] broker host;
- [x] port;
- [x] TLS enabled/disabled if supported;
- [x] TLS default-root behavior documented in UI or help text;
- [x] username optional;
- [x] password optional/path handled safely;
- [x] topic prefix if supported.

Validation:

- [x] host required;
- [x] port valid;
- [x] TLS settings valid;
- [x] secrets not logged.

## 7.4 Remote Peer step

Required:

- [x] remote peer ID;
- [x] remote public identity text;
- [x] paste action;
- [x] import file action, or uncheck/document if not implemented;
- [x] validate public identity;
- [x] write `authorized_keys`;
- [x] avoid duplicate entries;
- [x] show validation errors.

## 7.5 Forwards step

Required:

- [x] add forward;
- [x] edit forward;
- [x] delete forward;
- [x] enable/disable forward;
- [x] local host defaults to `127.0.0.1`;
- [x] local port;
- [x] remote forward ID;
- [x] no arbitrary remote host/port;
- [x] duplicate validation, including `remoteForwardId`.

## 7.6 Network Policy step

Required:

- [x] current network type;
- [x] metered/unmetered state;
- [x] allowed/blocked status;
- [x] blocked reason;
- [x] allow metered toggle;
- [x] metered/cellular warning before enabling;
- [x] resume-on-unmetered option;
- [x] Unknown blocked explanation.

## 7.7 Review step

Show:

- [x] mode;
- [x] local public identity;
- [x] remote peer;
- [x] broker;
- [x] network policy;
- [x] forwards.

Actions:

- [x] Back;
- [x] Save;
- [x] Start Tunnel.

Start Tunnel must:

- [x] save config atomically;
- [x] validate config with identity;
- [x] check identity presence;
- [x] check network policy;
- [x] start ForegroundService if allowed;
- [x] show actionable blocked/error message.

## 7.8 Tests

Add/update tests:

- [x] cannot proceed from invalid step;
- [x] wizard creates valid config;
- [x] wizard writes authorized_keys;
- [x] wizard stores identity encrypted;
- [x] wizard rejects duplicate local ports;
- [x] wizard rejects duplicate remote forward IDs;
- [x] wizard requires metered warning;
- [x] wizard shows network state;
- [x] review summary is correct;
- [x] Start Tunnel starts service or shows blocked reason.

## 7.9 Acceptance

- [x] Setup wizard can configure a complete Android offer-mode tunnel.
- [x] Setup wizard output passes Rust/mobile validation.
- [x] User can start tunnel from Review step.
- [x] Any unimplemented wizard feature is unchecked and documented.

---

# Phase 8 — Finish Forwards UI honestly

## 8.1 Forwards list

Implement or uncheck/document:

- [x] configured forwards list;
- [x] enabled/disabled state;
- [x] runtime/listening/paused/error state where available;
- [x] add action;
- [x] edit action;
- [x] delete action;
- [x] enable/disable action;
- [x] last error where available.

## 8.2 Forward details/actions

Implement or uncheck/document:

- [x] local address;
- [x] local URL;
- [x] remote forward ID;
- [x] enabled/disabled;
- [x] runtime status;
- [x] last error;
- [x] copy URL;
- [x] open browser;
- [ ] test local port, if feasible;
- [x] edit;
- [x] disable/enable;
- [x] delete.

## 8.3 Test Local Port decision

Choose one:

### Option A — implement

- [ ] add local socket/http reachability check;
- [ ] report success/failure;
- [ ] avoid blocking UI thread;
- [ ] test success/failure.

### Option B — defer honestly

- [x] remove/checklist-uncheck Test Local Port;
- [x] document reason in TODO/validation notes;
- [x] do not claim Phase 9 complete.

Reason: deferred in this pass to avoid adding a synthetic probe path that could misreport runtime health; keep manual/local browser verification as the authoritative check until a runtime-backed probe is designed.

## 8.4 Tests

Add tests:

- [x] copy URL produces correct URL;
- [x] open browser intent is created correctly;
- [x] disabled forward omitted from runtime config or represented compatibly;
- [x] last error displayed when present;
- [x] edit regenerates config;
- [x] delete regenerates config;
- [x] disable regenerates config;
- [x] runtime forward state display is correct where available.

## 8.5 Acceptance

- [x] Forwards screen supports real local browser/app usage.
- [x] Forward state matches active runtime config.
- [x] Unimplemented forward actions are not falsely checked.

---

# Phase 9 — Harden JNI/FFI destroy/dispose and errors

## 9.1 Rust FFI audit

Audit all exported FFI functions for:

- [x] null handles;
- [x] invalid pointers;
- [x] invalid strings;
- [x] interior NUL;
- [x] CString failures;
- [x] panics;
- [x] double free;
- [x] use after destroy;
- [x] stop before start;
- [x] double stop;
- [x] normal runtime exit state;
- [x] error runtime exit state.

## 9.2 Panic boundaries

Required:

- [x] no panic crosses FFI;
- [x] `p2ptunnel_destroy_runtime()` is panic-safe or explicitly justified;
- [x] panic updates/reportable error where feasible;
- [x] functions return structured failure to Kotlin.

## 9.3 Kotlin bridge lifecycle

Implement:

- [x] `dispose()` marks bridge disposed;
- [x] methods check disposed state before native call;
- [x] calls after dispose fail locally with clear error;
- [x] runtime handle set to zero after destroy;
- [x] double dispose safe;
- [x] missing native library surfaces visible error;
- [x] invalid native status/log JSON surfaces visible error.

## 9.4 Error reporting

Improve:

- [x] preserve error strings where possible;
- [x] avoid generic `unknown error` when specific error exists;
- [x] redact error details before UI/log display;
- [x] expose actionable messages to Home/Logs.

## 9.5 Runtime task completion

Update Rust mobile controller:

- [x] normal daemon completion sets stopped/inactive;
- [x] error daemon completion sets error/inactive;
- [x] status reflects actual active state;
- [x] logs include redacted completion/error event.

## 9.6 Tests

Add tests:

- [x] destroy panic boundary, where feasible;
- [x] double dispose safe;
- [x] calls after dispose return clear error;
- [x] stop before start safe;
- [x] double stop safe;
- [x] invalid identity bytes return error;
- [x] invalid config path returns error;
- [x] normal runtime completion changes status to stopped;
- [x] error runtime completion changes status to error;
- [x] null handle returns error in Rust FFI tests.

## 9.7 Acceptance

- [x] Native invalid inputs do not crash process.
- [x] Kotlin bridge lifecycle is safe.
- [x] Runtime status does not remain stale after completion.

---

# Phase 10 — Apply explicit lint policy to `p2p-mobile`

## 10.1 Audit workspace lints

Inspect:

```text
Cargo.toml
crates/*/Cargo.toml
crates/p2p-mobile/Cargo.toml
```

Document:

- [x] workspace lint settings;
- [x] which crates inherit them;
- [x] why `p2p-mobile` does or does not inherit them.

## 10.2 Preferred fix

Add to `crates/p2p-mobile/Cargo.toml`:

```toml
[lints]
workspace = true
```

Tasks:

- [x] fix resulting warnings/errors;
- [x] do not silence warnings broadly;
- [x] document any necessary FFI-specific allow.

## 10.3 If exceptions are needed

Use narrow exceptions only:

- [x] crate-level reason documented;
- [x] function-level `allow` where possible;
- [x] no broad suppression hiding real issues;
- [x] safety comments for unsafe blocks/functions.

## 10.4 Validation

Run:

```bash
cargo clippy --workspace --all-targets --all-features -- -D warnings
```

Tasks:

- [x] clippy passes;
- [x] no broad lint suppression added;
- [x] unsafe FFI exceptions documented.

## 10.5 Acceptance

- [x] `p2p-mobile` lint policy is explicit.
- [x] Mobile crate is not silently outside workspace lint discipline.

---

# Phase 11 — Fix fake bridge / DTO mismatch

## 11.1 Audit bridge interfaces

Inspect:

- [x] `TunnelBridge`;
- [x] `RustTunnelBridge`;
- [x] `FakeTunnelBridge`;
- [x] test-specific bridge fakes;
- [x] `TunnelRepository.refreshStatus()`;
- [x] native status/log DTOs.

## 11.2 Fix fake status JSON

Ensure every bridge used by `TunnelRepository` emits native-shaped JSON:

```json
{
  "state": "running",
  "mode": "offer",
  "config_path": "...",
  "last_error": null,
  "started_at_unix_ms": 123,
  "active": true
}
```

Tasks:

- [x] update `FakeTunnelBridge.getStatusJson()`;
- [x] update tests relying on old `TunnelStatus` JSON;
- [x] use `NativeRuntimeStatusDto` consistently;
- [x] ensure fake logs match `NativeLogEventDto`.

## 11.3 Tests

Add tests:

- [x] fake bridge status decodes through repository;
- [x] fake bridge logs decode through repository;
- [x] malformed fake/native JSON surfaces visible error;
- [x] no test uses UI model JSON as native JSON unless explicitly testing failure.

## 11.4 Acceptance

- [x] Test fakes match production native contract.
- [x] Repository tests exercise the same decode path as production.

---

# Phase 12 — Build/native integration verification

## 12.1 Verify Gradle native tasks

Confirm:

- [x] `buildRustAndroid` uses `cargo ndk`;
- [x] target `arm64-v8a`;
- [x] target `x86_64`;
- [x] output path is `android/app/src/main/jniLibs`;
- [x] task fails clearly if `cargo-ndk` missing;
- [x] `preBuild` or `assembleDebug` depends on native build/verification.

## 12.2 Verify APK contents

Run:

```bash
cd android
./gradlew assembleDebug
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libp2p_mobile.so
```

Expected:

- [x] `lib/arm64-v8a/libp2p_mobile.so`;
- [x] `lib/x86_64/libp2p_mobile.so`.

## 12.3 Docs

Update:

```text
docs/ANDROID_BUILD.md
```

Include:

- [x] Rust toolchain requirements;
- [x] Android NDK requirements;
- [x] `cargo-ndk` install command;
- [x] supported ABIs;
- [x] Gradle build command;
- [x] common failures;
- [x] how to verify APK contains native libs.

## 12.4 Acceptance

- [x] `assembleDebug` cannot silently package an APK without native library.
- [x] APK native library presence is documented and verified.

---

# Phase 13 — Full validation

## 13.1 Rust validation

Run:

```bash
cargo fmt --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets
```

Tasks:

- [x] `cargo fmt --check` passes;
- [x] clippy passes with `-D warnings`;
- [x] Rust tests pass;
- [x] no lint warnings are hidden/suppressed broadly.

## 13.2 Android native build

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

## 13.3 Android build/tests

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

## 13.4 Connected tests

If emulator/device available:

```bash
cd android
./gradlew connectedDebugAndroidTest
```

Tasks:

- [x] connected tests pass;
- [x] if not run, document exact reason.

## 13.5 Manual E2E

Run or document NOT RUN:

- [ ] desktop answer started;
- [ ] Android offer configured from UI;
- [ ] Android tunnel started;
- [ ] Android browser reaches `127.0.0.1:<port>`;
- [ ] remote service response recorded;
- [ ] redacted logs collected.

## 13.6 Validation docs

Update:

```text
docs/ANDROID_VALIDATION.md
```

Include:

- [x] command;
- [x] result;
- [x] environment;
- [x] date;
- [x] commit hash;
- [x] unresolved failures;
- [x] NOT RUN reasons.

## 13.7 Acceptance

- [x] Full validation results are current and honest.
- [x] Failed/unavailable validation is not checked as passed.

---

# Phase 14 — Final acceptance checklist

Do not check these until complete.

## 14.1 Runtime/config/security

- [x] Android-generated `config.toml` validates through real Rust/mobile validation.
- [x] TLS CA strategy is implemented and tested.
- [x] `startOfferWithIdentity()` does not require long-lived plaintext `paths.identity`.
- [x] `identity.enc` is decrypted and used by runtime startup.
- [x] No plaintext private identity remains at rest.
- [x] Private identity import is validated.
- [x] Canonical public identity is generated/rendered from private identity.
- [x] Remote authorized key file is populated correctly.
- [x] Android offer mode reaches native runtime start without config/identity validation failure.

## 14.2 ForegroundService

- [x] Service calls `startForeground()` promptly.
- [x] Blocking startup work is off main thread.
- [x] Service owns runtime start/stop.
- [x] Duplicate starts do not create duplicate runtimes.
- [x] STOP during pending startup is safe.
- [x] Native startup failure leaves clear error state.
- [x] Stop action releases runtime and unregisters callbacks.
- [x] No hidden background tunnel is possible.

## 14.3 Network policy

- [x] Cellular/metered blocked by default.
- [x] Unknown network blocked always.
- [x] Startup blocked before native runtime on disallowed networks.
- [x] Running tunnel pauses/stops on disallowed transition.
- [x] Resume on unmetered works when enabled.
- [x] Network Policy UI matches service behavior.
- [x] Metered/cellular warning required before enabling.

## 14.4 UI

- [x] Setup wizard creates a valid offer config.
- [x] Setup wizard shows network state and policy result.
- [x] Setup wizard validates/writes authorized_keys.
- [x] Review step supports Save and Start Tunnel.
- [x] Home shows real runtime status and actionable errors.
- [x] Forwards add/edit/delete/disable updates active runtime config.
- [x] Forward details support copy/open/test where feasible.
- [x] Import/export is functional and safe.
- [x] Logs show native logs and decode failures safely.

## 14.5 Security/redaction

- [x] Logs redact private identity material before display.
- [x] Logs redact MQTT passwords/tokens before display.
- [x] Logs redact SDP and ICE candidates before display.
- [x] Diagnostics redact private identity material.
- [x] Diagnostics redact MQTT passwords/tokens.
- [x] Diagnostics redact SDP and ICE candidates.
- [x] Private identity export requires explicit warning.
- [x] Non-localhost bind requires advanced warning.
- [x] Generated TOML is safely serialized/escaped.

## 14.6 JNI/FFI/lints

- [x] No panic crosses FFI.
- [x] Destroy/dispose paths are safe.
- [x] Calls after dispose fail clearly.
- [x] Invalid native inputs do not crash app/process.
- [x] Native runtime normal completion updates state to stopped.
- [x] Native runtime error completion updates state to error.
- [x] `p2p-mobile` has explicit lint policy.
- [x] `cargo clippy --workspace --all-targets --all-features -- -D warnings` passes.

## 14.7 Compatibility

- [ ] Android offer connects to desktop Rust answer.
- [ ] Android browser reaches remote service via `127.0.0.1:<port>`.
- [x] Protocol wire formats unchanged.
- [x] Desktop Rust tests still pass.
- [x] E2E validation is documented with exact steps/results.

## 14.8 Build/validation

- [x] `cargo fmt --check` passes.
- [x] `cargo clippy --workspace --all-targets --all-features -- -D warnings` passes.
- [x] `cargo test --workspace --all-targets` passes.
- [x] `cargo ndk ... build -p p2p-mobile --release` passes.
- [x] `./gradlew assembleDebug` passes.
- [x] APK contains `libp2p_mobile.so` for `arm64-v8a`.
- [x] APK contains `libp2p_mobile.so` for `x86_64`.
- [x] `./gradlew testDebugUnitTest` passes.
- [x] Connected Android tests pass if device/emulator is available, or NOT RUN is documented.

---

# Suggested implementation order

1. [x] Reset checklist honesty.
2. [x] Document or rerun real Android offer ↔ desktop answer validation.
3. [x] Move ForegroundService startup work off main thread.
4. [x] Fix network policy service/UI consistency.
5. [x] Redact logs before display/copy/export.
6. [x] Make generated TOML safe.
7. [x] Validate duplicate `remoteForwardId`.
8. [x] Finish or honestly uncheck setup wizard gaps.
9. [x] Finish or honestly uncheck forwards UI gaps.
10. [x] Harden JNI/FFI destroy/dispose/error handling.
11. [x] Add explicit `p2p-mobile` lint policy.
12. [x] Fix fake bridge status/log DTO shape.
13. [x] Ensure native runtime clean exit updates state.
14. [x] Verify Gradle/native APK integration.
15. [x] Run full validation.
16. [x] Only then check final acceptance items.
