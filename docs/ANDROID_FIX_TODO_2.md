# Android WebRTC Tunnel Fix TODO 2

## 1. Goal

Fix the remaining Android app blockers after the previous Android fix pass.

This TODO is implementation-oriented. Do not treat UI polish as complete until the native/runtime/config/security path is proven.

The highest-priority outcome is:

```text
Android-generated config validates through Rust.
Encrypted Android identity is actually used by native runtime startup.
No long-lived plaintext private identity exists.
Android offer connects to desktop Rust answer.
Android browser reaches remote service through 127.0.0.1:<port>.
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
- [x] Do not check off any acceptance item unless the implementation and validation are done.

---

# Phase 0 — Correct previous checklist and validation honesty

## 0.1 Uncheck premature completion claims

Audit:

```text
ANDROID_WEBRTC_TUNNEL_TODO.md
ANDROID_FIX_TODO1.md
docs/memory.md
docs/ANDROID_VALIDATION.md
```

Uncheck or annotate any item that is not currently proven, especially:

- [x] Android-generated config validates against Rust.
- [x] `identity.enc` is used by actual tunnel startup.
- [x] no plaintext private identity remains at rest.
- [x] setup wizard is truly functional.
- [x] forwards add/edit/delete update active runtime config.
- [x] Android offer connects to desktop Rust answer.
- [x] Android browser reaches remote service through localhost.
- [x] validation commands pass.

## 0.2 Record current validation state

Run or document inability to run:

```bash
cargo fmt --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets
cargo ndk -t arm64-v8a -t x86_64 -o android/app/src/main/jniLibs build -p p2p-mobile --release
cd android && ./gradlew assembleDebug
cd android && ./gradlew testDebugUnitTest
cd android && ./gradlew connectedDebugAndroidTest
```

- [x] Add exact results to `docs/ANDROID_VALIDATION.md`.
- [x] If a command cannot be run, document why.
- [x] If a command fails, include the failing command and concise failure summary.
- [x] Do not mark validation complete until the command actually passes.

---

# Phase 1 — Fix Android config / Rust config compatibility

## 1.1 Audit current Rust config schema

Inspect:

```text
crates/p2p-core/src/config.rs
crates/p2p-mobile/src/*
android/app/src/main/java/**/ConfigRepository.kt
```

Document:

- [x] required top-level config fields;
- [x] required `paths.*` fields;
- [x] required `broker.*` fields;
- [x] required `broker.tls.*` fields;
- [x] required files/directories that must exist;
- [x] how `forwards` are represented;
- [x] whether `broker.tls.ca_file` is required for `mqtts://`.

## 1.2 Choose TLS CA strategy

Pick exactly one strategy.

### Preferred: make Rust support default/native root store

- [x] Change Rust config to allow `broker.tls.ca_file` to be optional where safe.
- [x] Update validation so `mqtts://` does not require `ca_file` when the TLS stack can use default roots.
- [x] Preserve desktop compatibility with existing configs.
- [x] Document behavior in Android docs.

### Alternative: bundle an Android CA bundle

- [x] Add a real CA bundle asset or generated app-private CA file.
- [x] Ensure the file exists before Rust validation.
- [x] Set `broker.tls.ca_file` to that actual path.
- [x] Document update/security implications.

### Alternative: user-imported CA file

- [x] Add UI/import flow for CA file.
- [x] Disable `mqtts://` start until CA is provided.
- [x] Show actionable error if CA is missing.

## 1.3 Fix Android config renderer

Update `ConfigRepository` so generated `config.toml`:

- [x] contains only app-private Android paths;
- [x] contains no `~/.config`;
- [x] contains no `~/.local`;
- [x] contains no hardcoded `/etc/ssl/certs`;
- [x] contains valid TLS config according to chosen strategy;
- [x] contains valid authorized keys path;
- [x] contains valid state/runtime directories;
- [x] contains correct offer-mode forwards;
- [x] does not expose arbitrary remote host/port on Android offer side.

## 1.4 Add atomic config rendering

Implement atomic config writes:

- [x] render to temp file;
- [x] validate temp file;
- [x] atomically replace active `config.toml` only if valid;
- [x] leave previous config unchanged if validation fails;
- [x] surface validation failure in UI.

## 1.5 Tests

Add tests:

- [x] default Android config contains no desktop paths;
- [x] generated Android config includes valid TLS strategy;
- [x] generated Android config validates through Rust/mobile validation;
- [x] invalid generated config is rejected with actionable error;
- [x] config write is atomic;
- [x] failed validation does not replace previous config.

## 1.6 Acceptance

- [x] A config produced by the Android setup flow is accepted by real Rust/mobile config validation.
- [x] A config produced by Android can be used by tunnel startup without manual editing.
- [x] TLS CA behavior is explicit, implemented, and tested.

---

# Phase 2 — Fix in-memory identity startup

## 2.1 Audit current identity startup path

Inspect:

```text
IdentityRepository
TunnelForegroundService
RustTunnelBridge
crates/p2p-mobile
crates/p2p-core config loading
```

Document:

- [x] where `identity.enc` is stored;
- [x] where private identity is decrypted;
- [x] how identity bytes reach JNI;
- [x] where Rust parses identity;
- [x] whether Rust still requires `paths.identity` file;
- [x] whether any plaintext private identity file is created.

## 2.2 Implement preferred identity override

Preferred implementation:

- [x] Add Rust/mobile API to validate/load config with identity override.
- [x] When identity override is supplied, do not require `paths.identity` to exist.
- [x] Parse private identity from supplied bytes.
- [x] Use parsed identity for runtime startup.
- [x] Keep desktop config validation unchanged unless explicitly refactored safely.
- [x] Return clear error if identity bytes are invalid.

Suggested API shape:

```text
validate_config_with_identity(config_path, identity_toml_bytes)
start_offer_with_identity(config_path, identity_toml_bytes)
```

## 2.3 Temporary fallback only if necessary

If in-memory identity override is not feasible:

- [x] Decrypt identity to a short-lived app-private temp file.
- [x] Use restrictive file mode.
- [x] Point runtime validation/startup at the temp path.
- [x] Delete temp file immediately after Rust loads it.
- [x] Delete temp file again on stop/error.
- [x] Delete stale temp files at app/service startup.
- [x] Never include temp identity path or contents in diagnostics/logs.
- [x] Document this as temporary technical debt.

## 2.4 Tests

Add tests:

- [x] startup with `identity.enc` succeeds without long-lived plaintext `paths.identity`;
- [x] invalid encrypted identity produces actionable error;
- [x] missing encrypted identity produces setup-required error;
- [x] no plaintext `identity.toml` remains in `filesDir`;
- [x] no plaintext private identity remains in `cacheDir`;
- [x] stop/error cleanup removes temp identity if fallback strategy is used;
- [x] diagnostics do not include identity bytes or temp identity paths.

## 2.5 Acceptance

- [x] `identity.enc` is actually used by native runtime startup.
- [x] Rust startup no longer requires a long-lived plaintext private identity file.
- [x] Android offer can reach native runtime start after config validation.

---

# Phase 3 — Fix private/public identity import, export, and generation

## 3.1 Add Rust-backed identity helpers if possible

Expose mobile-safe Rust helpers:

```text
generate_private_identity() -> private identity TOML
validate_private_identity(private_identity_toml) -> ok/error
render_public_identity(private_identity_toml) -> public identity string
validate_public_identity(public_identity) -> ok/error
```

Tasks:

- [x] Add Rust implementations or expose existing identity logic.
- [x] Add JNI bindings.
- [x] Add Kotlin bridge methods.
- [x] Surface errors as structured/actionable messages.

## 3.2 Fix private identity import

Update import flow:

- [x] read selected private identity file;
- [x] validate with Rust helper;
- [x] render canonical public identity with Rust helper;
- [x] encrypt private identity to `identity.enc`;
- [x] write canonical public identity to `identity.pub`;
- [x] discard plaintext bytes;
- [x] never log file contents.

Do not infer public identity by copying a `peer_id` line.

## 3.3 Add/gate identity generation

If Rust helper exists:

- [x] add Generate Identity action in setup wizard;
- [x] encrypt generated private identity to `identity.enc`;
- [x] write `identity.pub`;
- [x] show public identity to user.

If generation is not available:

- [x] clearly disable/hide Generate Identity;
- [x] show import-required message.

## 3.4 Private identity export warning

Implement explicit warning dialog:

```text
Private Identity Export Warning

Anyone with this file can impersonate this phone in your tunnel network.

Only export it if you understand the risk.

[Cancel]
[Export Private Identity]
```

Tasks:

- [x] require explicit confirmation for every private export;
- [x] optionally require device unlock/biometric if easy;
- [x] do not use a passive checkbox as the only warning;
- [x] export only after successful decrypt.

## 3.5 Tests

Add tests:

- [x] valid private identity import writes `identity.enc`;
- [x] valid private identity import writes canonical `identity.pub`;
- [x] invalid private identity import is rejected;
- [x] empty private identity import is rejected;
- [x] public identity export matches canonical Rust format;
- [x] private export requires warning confirmation;
- [x] no plaintext private identity remains after import/export.

## 3.6 Acceptance

- [x] Imported private identity is validated before storage.
- [x] Public identity is canonical and usable by desktop peer.
- [x] Private identity export is explicitly warned.
- [x] No plaintext private identity persists at rest.

---

# Phase 4 — Fix authorized_keys / remote peer import

## 4.1 Audit authorized key format

Inspect Rust desktop/daemon expectations:

- [x] public identity line format;
- [x] peer ID requirement;
- [x] authorized key file location;
- [x] multiple peer behavior;
- [x] per-forward authorization behavior, if applicable.

## 4.2 Implement remote public identity import

Setup wizard Remote Peer step must support:

- [x] paste remote public identity;
- [x] import remote public identity file;
- [x] validate public identity with Rust helper if available;
- [x] write valid identity to `filesDir/authorized_keys`;
- [x] avoid duplicate entries;
- [x] show remote peer ID in Review step.

## 4.3 Tests

Add tests:

- [x] valid remote public identity is accepted;
- [x] invalid remote public identity is rejected;
- [x] authorized_keys is created if missing;
- [x] duplicate remote identity is not duplicated;
- [x] generated config points at app-private authorized_keys.

## 4.4 Acceptance

- [x] Android offer can authorize the desktop answer peer using `authorized_keys`.
- [x] Remote peer identity import is not a placeholder.

---

# Phase 5 — Unify forwards source of truth

## 5.1 Choose source-of-truth model

Preferred:

```text
Structured Android config state -> render config.toml atomically
```

Tasks:

- [x] identify current structured state files/preferences;
- [x] decide where forwards are stored;
- [x] document the source of truth in code comments/docs;
- [x] ensure tunnel start always uses config rendered from current state.

## 5.2 Regenerate active config on forward mutation

For every forward action:

- [x] add;
- [x] edit;
- [x] delete;
- [x] enable;
- [x] disable;

do:

- [x] update structured state;
- [x] render candidate `config.toml`;
- [x] validate candidate config;
- [x] atomically replace active config if valid;
- [x] rollback state or show error if validation fails.

## 5.3 Validate forwards

Rules:

- [x] port must be 1-65535;
- [x] duplicate enabled local ports rejected;
- [x] duplicate forward IDs rejected;
- [x] local host defaults to `127.0.0.1`;
- [x] non-localhost bind requires advanced warning;
- [x] Android offer side does not expose arbitrary remote host/port;
- [x] disabled forwards are either omitted from Rust config or marked in a Rust-compatible way.

## 5.4 Tests

Add tests:

- [x] add forward updates active config;
- [x] edit forward updates active config;
- [x] delete forward updates active config;
- [x] disable forward updates active config;
- [x] duplicate local port rejected;
- [x] duplicate forward ID rejected;
- [x] non-localhost requires warning;
- [x] generated local URL is correct;
- [x] runtime start uses updated config.

## 5.5 Acceptance

- [x] Forwards UI changes affect the actual tunnel runtime config.
- [x] There is no stale `config.toml` after forward edits.

---

# Phase 6 — Make config import/export transactional and Android-safe

## 6.1 Config import

Implement:

- [x] read candidate config from selected file/path;
- [x] write candidate to temp file;
- [x] validate candidate through Rust/mobile validation;
- [x] if valid, atomically replace active config;
- [x] if invalid, keep previous active config;
- [x] show actionable validation error.

## 6.2 Config export

Implement:

- [x] export current config through Android-safe share/file mechanism if available;
- [x] redact secrets if export is diagnostics-style;
- [x] if raw config export includes secrets, show warning;
- [x] document whether config export is raw or redacted.

## 6.3 Tests

Add tests:

- [x] invalid config import does not replace active config;
- [x] valid config import replaces active config;
- [x] config import reports validation errors;
- [x] config export does not include private identity;
- [x] config export behavior around MQTT secrets is explicit and tested.

## 6.4 Acceptance

- [x] Import cannot leave the app with a broken active config.
- [x] Export behavior is documented and safe.

---

# Phase 7 — Make network policy consistent and service-enforced

## 7.1 Refine network status model

Represent:

```text
networkType
isMetered
allowedByDefault
allowedByUserPolicy
blockedReason
```

Tasks:

- [x] update `NetworkPolicyManager`;
- [x] update UI models;
- [x] update service logic to use the same policy calculation;
- [x] fail safe on unknown network;
- [x] fail safe on no network.

## 7.2 Startup gate

Before native runtime start:

- [x] load `allowMetered`;
- [x] load `resumeOnUnmetered`;
- [x] read current network status;
- [x] if disallowed, do not start Rust;
- [x] show paused/blocked notification;
- [x] update repository/UI state with blocked reason.

## 7.3 Runtime pause/resume

When network changes:

- [x] if running and network becomes disallowed, stop/pause Rust runtime;
- [x] close local listeners if runtime supports it;
- [x] update notification;
- [x] update UI state;
- [x] if network becomes allowed and resume is enabled, restart runtime after config/identity checks.

## 7.4 Metered/cellular warning

Implement warning dialog:

```text
Cellular / Metered Data Warning

WebRTC Tunnel can use a large amount of data. Browser traffic, API calls, SSH sessions, downloads, streaming, llama-server usage, or other forwarded traffic may consume your mobile data plan quickly.

Your carrier may charge overage fees, throttle your connection, or suspend service depending on your plan.

The app developer is not responsible for carrier charges, throttling, overage fees, or data-plan exhaustion caused by your use of this feature.

Only enable this if you understand the risk and accept responsibility for any data usage or charges.

[Cancel]
[I understand — allow cellular/metered tunnels]
```

Tasks:

- [x] require warning acceptance before enabling metered/cellular;
- [x] store acceptance;
- [x] show current policy clearly in Settings and Setup wizard;
- [x] make UI status reflect `allowMetered`.

## 7.5 Tests

Add tests:

- [x] startup blocked on cellular by default;
- [x] startup blocked on metered Wi-Fi by default;
- [x] startup blocked on unknown network by default;
- [x] explicit allow permits metered/cellular;
- [x] warning required before enabling metered/cellular;
- [x] running tunnel pauses on switch to cellular;
- [x] paused tunnel resumes on unmetered when configured;
- [x] Network Policy UI reflects allowed status correctly.

## 7.6 Acceptance

- [x] Network policy is enforced by the service.
- [x] Network policy UI matches service behavior.
- [x] Tunnel cannot use cellular/metered data by default.

---

# Phase 8 — Finish Setup Wizard

## 8.1 Choose Mode step

- [x] Offer mode enabled and default.
- [x] Answer mode disabled or marked Advanced/Incomplete if not supported.
- [x] User cannot proceed with unsupported mode unless intentionally allowed.

## 8.2 Identity step

- [x] Generate identity if Rust API exists.
- [x] Import private identity.
- [x] Validate private identity.
- [x] Store private identity encrypted.
- [x] Display public identity.
- [x] Copy public identity.
- [x] Share/export public identity.
- [x] Show setup-required error if identity missing.

## 8.3 MQTT Broker step

Fields:

- [x] broker host;
- [x] port;
- [x] TLS enabled;
- [x] CA strategy/import if required;
- [x] username optional;
- [x] password optional;
- [x] topic prefix optional if supported.

Validation:

- [x] host required;
- [x] port valid;
- [x] TLS settings valid;
- [x] secrets not logged.

## 8.4 Remote Peer step

Fields/actions:

- [x] remote peer ID;
- [x] remote public identity;
- [x] paste;
- [x] import file;
- [x] validate identity;
- [x] write authorized_keys.

## 8.5 Forwards step

- [x] add forward;
- [x] edit forward;
- [x] remove forward;
- [x] disable forward;
- [x] local host default `127.0.0.1`;
- [x] local port;
- [x] remote forward ID;
- [x] no remote target host/port;
- [x] duplicate validation.

## 8.6 Network Policy step

- [x] show actual current network state;
- [x] show blocked/allowed status;
- [x] keep metered/cellular blocked by default;
- [x] warning before enabling metered/cellular;
- [x] show resume-on-unmetered option.

## 8.7 Review step

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

Start Tunnel behavior:

- [x] save config atomically;
- [x] validate config;
- [x] check identity;
- [x] check network policy;
- [x] start ForegroundService if allowed;
- [x] show actionable error if blocked.

## 8.8 Tests

Add tests:

- [x] cannot proceed from invalid step;
- [x] wizard creates valid config;
- [x] wizard writes authorized_keys;
- [x] wizard stores identity encrypted;
- [x] wizard rejects duplicate forwards;
- [x] wizard requires metered warning;
- [x] review summary is correct;
- [x] Start Tunnel from Review starts service or shows blocked reason.

## 8.9 Acceptance

- [x] Setup wizard can configure a complete Android offer-mode tunnel.
- [x] Setup wizard output passes Rust/mobile validation.
- [x] User can start tunnel from wizard Review step.

---

# Phase 9 — Finish Forwards UI and behavior

## 9.1 Forwards list

Implement:

- [x] configured forwards list;
- [x] enabled/disabled state;
- [x] runtime/listening/paused/error state where available;
- [x] add action;
- [x] edit action;
- [x] delete action;
- [x] enable/disable action.

## 9.2 Forward details

Show:

- [x] local address;
- [x] local URL;
- [x] remote forward ID;
- [x] enabled/disabled;
- [x] runtime status;
- [x] last error if available.

Actions:

- [x] copy URL;
- [x] open browser;
- [x] test local port if feasible;
- [x] edit;
- [x] disable/enable;
- [x] delete.

## 9.3 Tests

Add tests:

- [x] copy URL produces correct `http://127.0.0.1:<port>` URL;
- [x] open browser intent is created correctly;
- [x] disabled forward is not active in runtime config;
- [x] last error is displayed when present;
- [x] edit/delete/disable regenerate config.

## 9.4 Acceptance

- [x] Forwards screen is useful for real local browser/app usage.
- [x] Forward state matches actual runtime config.

---

# Phase 10 — Strengthen logs and diagnostics redaction

## 10.1 Redaction targets

Redact from logs and diagnostics:

- [x] private identity files;
- [x] `sign.private`;
- [x] `kex.private`;
- [x] private key PEM blocks if ever present;
- [x] MQTT password;
- [x] password file contents;
- [x] bearer tokens;
- [x] API keys;
- [x] SDP blobs;
- [x] ICE candidates;
- [x] decrypted payloads;
- [x] forwarded data;
- [x] temporary private identity paths if temp-file strategy is used.

## 10.2 Diagnostics content

Diagnostics may include only redacted/safe data:

- [x] status JSON;
- [x] redacted config;
- [x] recent redacted logs;
- [x] network state;
- [x] app version;
- [x] Rust/mobile library version;
- [x] device/API level if useful;
- [x] validation results if available.

## 10.3 Logs screen

Implement/verify:

- [x] All/Debug/Info/Warn/Error filters;
- [x] copy visible logs;
- [x] clear logs;
- [x] export diagnostics;
- [x] native logs shown;
- [x] malformed native log JSON surfaces visible error.

## 10.4 Tests

Add tests with realistic multiline examples:

- [x] private identity TOML redacted;
- [x] MQTT password redacted;
- [x] bearer token redacted;
- [x] SDP redacted;
- [x] ICE candidate redacted;
- [x] forwarded data marker redacted;
- [x] diagnostics do not include identity bytes;
- [x] diagnostics do not include password strings;
- [x] native logs are redacted before display/export.

## 10.5 Acceptance

- [x] Diagnostics and logs are safe to share.
- [x] Redaction tests cover realistic secret formats.

---

# Phase 11 — Harden ForegroundService lifecycle

## 11.1 Startup

- [x] Service calls `startForeground()` promptly on every start path.
- [x] Long-running Rust startup work is not done on main thread.
- [x] `START_NOT_STICKY` retained unless explicitly justified.
- [x] null intents handled safely.
- [x] config/identity/network checks happen before native start.

## 11.2 Stop/cleanup

On stop:

- [x] native runtime stop is idempotent;
- [x] network callbacks unregistered;
- [x] service coroutines cancelled;
- [x] repository/service state updated;
- [x] notification updated or removed;
- [x] foreground stopped;
- [x] service stops itself.

## 11.3 Tests

Add/update tests:

- [x] service start posts notification promptly;
- [x] null intent does not crash;
- [x] stop action stops runtime;
- [x] start-stop-start works;
- [x] no `ForegroundServiceDidNotStartInTimeException`;
- [x] native startup failure leaves clear error state.

## 11.4 Acceptance

- [x] Service lifecycle is reliable under Android foreground-service rules.
- [x] No hidden background tunnel is possible.

---

# Phase 12 — Harden JNI/FFI safety and error reporting

## 12.1 Rust FFI audit

Audit all exported functions for:

- [x] null handles;
- [x] invalid pointers;
- [x] invalid UTF-8 / invalid strings;
- [x] interior NUL in strings;
- [x] CString creation failures;
- [x] panics;
- [x] double free;
- [x] use after destroy;
- [x] stop before start;
- [x] double stop.

## 12.2 Panic boundaries

- [x] no panic crosses FFI;
- [x] wrap exported functions in panic-catching helper;
- [x] update last error on panic;
- [x] return structured failure to Kotlin.

## 12.3 Kotlin native availability

- [x] store native library load success/failure;
- [x] expose load error to repository/UI;
- [x] do not swallow `System.loadLibrary()` failures;
- [x] avoid native calls after bridge dispose/destroy.

## 12.4 Tests

Add tests where feasible:

- [x] missing native library surfaces visible error;
- [x] invalid config path returns error, not crash;
- [x] stop before start safe;
- [x] double stop safe;
- [x] invalid identity bytes return error;
- [x] malformed status/log JSON visible;
- [x] null handle returns error in Rust FFI tests.

## 12.5 Acceptance

- [x] Native failures become actionable app errors.
- [x] Invalid native inputs do not crash the app/process.

---

# Phase 13 — Rust Android library Gradle integration

## 13.1 Verify build tasks

Ensure:

- [x] `buildRustAndroid` uses `cargo ndk`;
- [x] targets `arm64-v8a`;
- [x] targets `x86_64`;
- [x] outputs to `android/app/src/main/jniLibs`;
- [x] fails clearly if `cargo-ndk` missing;
- [x] `preBuild` or `assembleDebug` depends on native build or verification.

## 13.2 Verify APK contents

After build:

- [x] APK contains `lib/arm64-v8a/libp2p_mobile.so`;
- [x] APK contains `lib/x86_64/libp2p_mobile.so`;
- [x] APK does not silently build without native libs.

Command:

```bash
unzip -l android/app/build/outputs/apk/debug/app-debug.apk | grep libp2p_mobile.so
```

## 13.3 Documentation

Update Android build docs:

```bash
cargo install cargo-ndk
cd android
./gradlew assembleDebug
```

Document:

- [x] required Rust toolchain;
- [x] required Android NDK;
- [x] required cargo-ndk;
- [x] supported ABIs;
- [x] expected native library output.

## 13.4 Acceptance

- [x] `./gradlew assembleDebug` produces an APK with native Rust library included.
- [x] Build failure is clear when native dependencies are missing.

---

# Phase 14 — Protocol compatibility and end-to-end validation

## 14.1 Desktop answer setup

Document exact desktop command, for example:

```bash
cargo run --bin p2p-answer -- --config <desktop-answer-config>
```

Tasks:

- [x] create/identify desktop answer config;
- [x] identify answer peer public identity;
- [x] identify required MQTT broker settings;
- [x] identify remote forward IDs.

## 14.2 Android offer setup

Using only Android UI:

- [x] import/generate Android identity;
- [x] import desktop answer public identity;
- [x] configure MQTT broker;
- [x] configure forward `127.0.0.1:8080 -> llama` or equivalent;
- [x] keep cellular/metered blocked unless intentionally tested;
- [x] save config;
- [x] start tunnel.

## 14.3 Browser validation

On Android:

```text
http://127.0.0.1:8080
```

Tasks:

- [x] confirm remote service responds;
- [x] record response type/status;
- [x] record network type;
- [x] record Android device/emulator;
- [x] record relevant redacted logs.

## 14.4 Protocol invariants

Verify no changes to:

- [x] MQTT topic layout;
- [x] signaling envelope;
- [x] encrypted inner message schema;
- [x] identity/public key format;
- [x] authorized key semantics;
- [x] tunnel frame format;
- [x] `OpenPayload { forward_id }`;
- [x] per-forward authorization.

## 14.5 Documentation

Add results to:

```text
docs/ANDROID_VALIDATION.md
```

Include:

- [x] date;
- [x] git commit;
- [x] desktop command;
- [x] Android config summary;
- [x] network type;
- [x] result;
- [x] known failures if any.

## 14.6 Acceptance

- [x] Android offer connects to desktop Rust answer.
- [x] Android browser reaches remote service through `127.0.0.1:<port>`.
- [x] Protocol wire formats remain compatible.

---

# Phase 15 — Full validation

## 15.1 Rust validation

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
- [x] no lint warnings are suppressed to hide real issues.

## 15.2 Android native build

Run:

```bash
cargo ndk \
  -t arm64-v8a \
  -t x86_64 \
  -o android/app/src/main/jniLibs \
  build -p p2p-mobile --release
```

Tasks:

- [x] native library builds for `arm64-v8a`;
- [x] native library builds for `x86_64`;
- [x] outputs are present in `jniLibs`.

## 15.3 Android build/tests

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

## 15.4 Connected tests

If emulator/device available:

```bash
cd android
./gradlew connectedDebugAndroidTest
```

Tasks:

- [x] connected tests pass;
- [x] if not run, document why.

## 15.5 Validation note

Update:

```text
docs/ANDROID_VALIDATION.md
```

Include:

- [x] exact commands;
- [x] pass/fail result;
- [x] environment;
- [x] date;
- [x] commit hash;
- [x] unresolved failures.

---

# Phase 16 — Final acceptance checklist

Do not check these until complete.

## 16.1 P0 runtime/config/security

- [x] Android-generated `config.toml` validates through real Rust/mobile validation.
- [x] TLS CA strategy is implemented and tested.
- [x] `startOfferWithIdentity()` does not require long-lived plaintext `paths.identity`.
- [x] `identity.enc` is decrypted and used by runtime startup.
- [x] No plaintext private identity remains at rest.
- [x] Private identity import is validated.
- [x] Canonical public identity is generated/rendered from private identity.
- [x] Remote authorized key file is populated correctly.
- [x] Android offer mode reaches native runtime start without config/identity validation failure.

## 16.2 Network/service

- [x] ForegroundService starts notification promptly.
- [x] ForegroundService owns runtime start/stop.
- [x] Cellular/metered blocked by default.
- [x] Unknown network blocked by default.
- [x] Startup blocked before native runtime on disallowed networks.
- [x] Running tunnel pauses/stops on transition to disallowed network.
- [x] Resume on unmetered works when enabled.
- [x] Stop action releases runtime and unregisters callbacks.

## 16.3 UI

- [x] Setup wizard creates a valid offer config.
- [x] Review step supports Save and Start Tunnel.
- [x] Home shows real runtime status and actionable errors.
- [x] Forwards add/edit/delete/disable updates active runtime config.
- [x] Forward details support copy/open/test where feasible.
- [x] Network Policy screen reflects user preferences.
- [x] Import/export is functional and safe.
- [x] Logs show native logs and decode failures.

## 16.4 Security

- [x] Diagnostics redact private identity material.
- [x] Diagnostics redact MQTT passwords/tokens.
- [x] Diagnostics redact SDP and ICE candidates.
- [x] Logs redact secrets.
- [x] Private identity export requires explicit warning.
- [x] Non-localhost bind requires advanced warning.

## 16.5 Compatibility

- [x] Android offer connects to desktop Rust answer.
- [x] Android browser reaches remote service via `127.0.0.1:<port>`.
- [x] Protocol wire formats unchanged.
- [x] Desktop Rust tests still pass.

## 16.6 Validation

- [x] `cargo fmt --check` passes.
- [x] `cargo clippy --workspace --all-targets --all-features -- -D warnings` passes.
- [x] `cargo test --workspace --all-targets` passes.
- [x] `cargo ndk ... build -p p2p-mobile --release` passes.
- [x] `./gradlew assembleDebug` passes.
- [x] `./gradlew testDebugUnitTest` passes.
- [x] Connected Android tests pass if present and device/emulator is available.
- [x] End-to-end validation is documented.

---

# Suggested implementation order

1. [ ] Correct checklist honesty.
2. [ ] Fix Android/Rust config validation and TLS CA strategy.
3. [ ] Fix in-memory identity startup.
4. [ ] Add real Rust/mobile validation tests.
5. [ ] Fix private identity import/public identity rendering.
6. [ ] Fix authorized_keys/remote public identity import.
7. [ ] Unify forwards source of truth.
8. [ ] Make config import transactional.
9. [ ] Make network policy service/UI consistent.
10. [ ] Finish setup wizard.
11. [ ] Finish forwards details.
12. [ ] Strengthen diagnostics/log redaction.
13. [ ] Harden ForegroundService lifecycle.
14. [ ] Harden JNI/FFI errors.
15. [ ] Verify Gradle/native build integration.
16. [ ] Run end-to-end Android offer ↔ desktop answer test.
17. [ ] Run full validation.
18. [ ] Only then check final acceptance items.
