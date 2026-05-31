# replies10.md

# Responses to Android Review Clarification Questions

These are the decisions to hand back to GitHub Copilot for the Android `webrtc_tunnel` hardening pass based on `ANDROID_CODE_REVIEW1.md` and `ANDROID_FIX_TODO1.md`.

---

## 1. Identity startup strategy

Implement the **preferred in-memory identity handoff now**.

Do **not** start with the plaintext temp-file bridge unless the in-memory path proves impossible after a real implementation attempt.

The Android design goal is:

```text
identity.enc stays encrypted at rest
Android Keystore decrypts identity in memory
Kotlin passes decrypted identity bytes/string to p2p-mobile
p2p-mobile parses identity from memory
no plaintext private identity file is written to disk
```

Recommended API direction:

```rust
MobileStartConfig {
    config_toml_path: PathBuf,
    identity_bytes: Vec<u8>,
    authorized_keys_bytes: Option<Vec<u8>>,
}
```

or an FFI equivalent such as:

```rust
p2ptunnel_start_offer_with_identity(
    handle,
    config_path,
    identity_ptr,
    identity_len,
)
```

The existing daemon startup path can be refactored so the mobile wrapper can supply an already-loaded identity instead of requiring:

```rust
IdentityFile::from_file(config.paths.identity)
```

Only use the temp-file bridge if the in-memory path blocks progress. If that fallback is used, it must be explicitly marked temporary, app-private, short-lived, deleted after load, and covered by tests.

---

## 2. Answer mode scope in v1 Android UI

Keep **answer mode hidden or disabled** in v1 user-facing flows.

Do **not** show it as a clickable “advanced/not-ready” path that leads to a partial setup. That will confuse users and create false expectations.

Recommended UI behavior:

```text
Choose Mode:
  Offer / Client        enabled, default
  Answer / Server       disabled, "Planned / Advanced"
```

If you want to leave a hint for future support, show non-interactive text:

```text
Answer mode is planned for a future Android release.
```

The v1 acceptance target is:

```text
Android p2p-offer -> desktop Rust p2p-answer
Android browser -> 127.0.0.1:<port>
```

Answer mode can come later after offer mode is reliable.

---

## 3. TLS `ca_file` behavior on Android

Android-generated runtime config should **omit `broker.tls.ca_file` by default**.

Do **not** generate this on Android:

```toml
ca_file = "/etc/ssl/certs/ca-certificates.crt"
```

That is a desktop/Linux assumption and should not appear in Android default config.

Preferred v1 policy:

```text
broker.tls.enabled = true
broker.tls.ca_file omitted by default
```

Then let the Rust TLS stack use its default root handling if available.

If the current Rust config requires `ca_file`, change the config model so `ca_file` is optional. If Android TLS validation cannot work without a CA bundle, then add one of these explicitly:

1. bundled CA bundle packaged with the app,
2. user-imported CA file,
3. documented temporary limitation.

But default Android config should not contain fake Linux CA paths.

---

## 4. Foreground service lifecycle policy

For v1, enforce:

```kotlin
START_NOT_STICKY
```

This tunnel is user-controlled and safety-sensitive. It should not silently restart after Android kills it without re-checking:

```text
network policy
metered/cellular permission
config validity
identity availability
notification permission
```

If a future version adds explicit “auto-resume after reboot/process death,” that should be a separate feature with clear user consent.

For v1:

```kotlin
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent == null) {
        stopSelf(startId)
        return START_NOT_STICKY
    }

    // Handle START_OFFER / STOP / PAUSE / RESUME...
    return START_NOT_STICKY
}
```

Also make sure every valid start path calls `startForeground()` promptly before long Rust startup work.

---

## 5. Existing daemon debug instrumentation cleanup

Remove the temporary `[DEBUG] eprintln!` traces from:

```text
crates/p2p-daemon/src/lib.rs
```

Do this as part of Phase 0 cleanup, before or alongside the Android fixes.

Rules:

```text
No raw eprintln! debug traces in daemon runtime code.
Use tracing with structured fields if logging is still needed.
Do not log SDP, ICE candidates, decrypted payloads, private keys, MQTT credentials, or forwarded data.
Keep useful transport/session diagnostics behind normal tracing levels.
```

This cleanup is especially important now because Android diagnostics/export flows must be redacted and user-safe. Raw `eprintln!` output bypasses the logging/redaction model.

---

## Final frozen decisions

1. **Identity:** implement in-memory identity handoff now; temp file only as a documented fallback.
2. **Answer mode:** keep hidden/disabled in v1; offer mode is the only fully supported Android workflow.
3. **TLS CA:** omit `broker.tls.ca_file` by default on Android; never use Linux CA paths.
4. **Service lifecycle:** use `START_NOT_STICKY` for v1.
5. **Debug traces:** remove temporary daemon `eprintln!` debug instrumentation.
