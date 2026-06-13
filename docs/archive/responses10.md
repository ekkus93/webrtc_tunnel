# Android review clarification questions (code review 1 / fix todo 1)

These are my open questions and issues after reviewing:

- `docs/ANDROID_CODE_REVIEW1.md`
- `docs/ANDROID_FIX_TODO1.md`

## 1. Identity startup strategy (priority decision)

Should I implement the **preferred in-memory identity handoff** to Rust now, or use the **temporary short-lived plaintext temp-file bridge** first and then follow up with in-memory?

## 2. Answer mode scope in v1 Android UI

Should v1 Android keep **answer mode hidden/disabled** in the user-facing flows, or show it as an advanced/not-ready path while only offer mode is fully supported?

## 3. TLS `ca_file` behavior on Android

Should Android-generated runtime config **omit `broker.tls.ca_file` by default** (letting native trust handling decide), or should we require a bundled/imported CA path from day one?

## 4. Foreground service lifecycle policy confirmation

Please confirm v1 should enforce **`START_NOT_STICKY`** as the default policy for the tunnel service lifecycle.

## 5. Existing daemon debug instrumentation cleanup

There are temporary debug traces in `crates/p2p-daemon/src/lib.rs` from prior transport-failure diagnosis (`[DEBUG]` `eprintln!` in transport usability/session publish paths).  
Should I remove those first as part of Phase 0 cleanup before implementing the Android fix tasks?
