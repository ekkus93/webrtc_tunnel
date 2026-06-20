# Replies to Claude Code — WEBRTC_TUNNEL_HARDENING Questions & Issues

These replies address `responses3(17).md` for the WebRTC tunnel hardening spec/TODO review.

## Summary of decisions

Use the existing ICE mode schema for this pass. Do **not** rename modes right now.

The Android app should default to strict `vnet_mux`, not `auto`. The Android/Kotlin layer should generate and persist `android_ice_mode = "vnet_mux"` for normal Android configs. `auto` should be treated as an explicit best-effort/diagnostic mode, not as the Android default.

Also, P0-001 and P0-002 should be implemented together. Strict `vnet_mux` needs an Android-provided local address to advertise. Do not remove the `8.8.8.8` Android probe without adding Android `ConnectivityManager` / `LinkProperties` address injection, unless the interim state fails loudly and is not treated as complete.

The previous spec/TODO should be corrected where it assumed `auto` was `vnet -> native`. Claude Code is right that the current code appears to do the more dangerous Android case: `auto` + successful interface enumeration picks native ICE. That is exactly the path we want to stop using by default on Android.

---

## Q1 — How should `Android-default-strict` be expressed?

**Decision: choose option (b) as the main implementation path.**

The Kotlin/Android layer should pick an explicit non-`auto` mode as the Android default. Specifically, generated Android configs should use:

```toml
android_ice_mode = "vnet_mux"
```

Do **not** rely primarily on `cfg(target_os = "android")` changing the Rust core default. The Rust core is shared by desktop/CLI and Android, and silently changing default semantics by target platform can make behavior harder to reason about.

### Required implementation details

1. Update Android config templates/setup defaults so newly created Android configs use `vnet_mux`.
2. Audit any setup/reset/default path that can write `android_ice_mode`; none of them should write `auto` by default.
3. Keep Rust core defaults stable unless there is a clear explicit config input.
4. In the Android UI, hide `auto` from the normal/simple setup path. If exposed at all, label it as advanced/best-effort/diagnostic.
5. Add status fields so the user can see:
   - requested ICE mode
   - selected ICE path
   - whether native ICE was selected
   - whether fallback/best-effort behavior occurred
   - fallback/best-effort reason
   - advertised local address source

### Defense-in-depth validation

Even though Kotlin owns the Android default, the Rust/mobile side should still fail loudly in strict modes:

- If requested mode is `vnet_mux` and no Android-injected local address is available, fail startup with a specific error.
- If requested mode is `vnet_mux` and UDP mux setup fails, fail startup with a specific error.
- Do not continue as native ICE from `vnet_mux`.

### What to do with Android `auto`

Treat Android `auto` as an explicit best-effort/diagnostic mode only. If `auto` selects native ICE, that must be visible in status/diagnostics. It must not look like the normal successful Android path.

Because this app has not had an official release, we do not need to preserve old `auto` Android configs as a compatibility requirement. It is acceptable to require users/dev builds to regenerate or edit configs.

---

## Q2 — Should we keep the current 4 modes or rename them?

**Decision: choose option (a). Keep the current 4 modes for this pass:**

```text
native
vnet
vnet_mux
auto
```

Do **not** do the larger rename to `vnet_required`, `native_required`, `auto_strict`, and `auto_best_effort` in this hardening pass.

Claude Code is right that the previous suggested names dropped the critical `mux` dimension. On Android, the important working path is specifically `vnet_mux`, not plain `vnet`. A rename that hides that distinction would be a mistake.

### Required semantics for this pass

Use these semantics:

```text
vnet_mux  Explicit strict Android-safe path. Must use UDP mux and advertise the Android-provided local IPv4. No fallback to native.

vnet      Explicit non-mux vnet path. Do not use as Android default. Hide from normal Android UI or mark as experimental/diagnostic because it is not the proven Android fix.

native    Explicit native ICE path. Do not use as Android default. It may remain for desktop/diagnostics, but if selected on Android it must be obvious in status.

auto      Explicit best-effort mode. Not Android default. May select native, but must report the selected path and reason. Treat as advanced/diagnostic.
```

### Android UI/config guidance

- Normal Android setup should generate `vnet_mux`.
- Advanced mode may expose `native`, `vnet`, and `auto`, but each should have a warning.
- If Android `auto` is kept, the UI/status must make clear it is not strict and may choose the problematic native path.

### Spec/TODO correction

Update the spec/TODO language to stop saying “rename modes if needed” as the recommended path. The recommended path is now:

> Keep the current mode names, make Android default strict `vnet_mux`, and make `auto` explicit best-effort rather than default.

---

## Q3 — What is the scope/sequencing for P0-002?

**Decision: choose option (a). Implement the full Android address injection now and land it with P0-001.**

P0-001 and P0-002 are coupled. `vnet_mux` needs a real local IPv4 address to advertise as the host candidate. If Android no longer uses the `8.8.8.8` UDP-route probe, Kotlin must provide the address from Android network APIs.

### Required implementation scope

Implement Android address discovery using `ConnectivityManager` / `LinkProperties`, then pass the selected local IPv4 into Rust via JNI/native config.

The Android production path should not call the `8.8.8.8:80` helper to determine the advertised local address.

### Address-selection requirements

Kotlin should:

1. Get the active `Network` from `ConnectivityManager`.
2. Get `LinkProperties` for that active network.
3. Inspect `linkAddresses`.
4. Prefer a usable IPv4 address that is:
   - not loopback
   - not unspecified
   - not multicast
   - not link-local, unless there is no better local-only option and the UI/status reports that explicitly
5. Pass the selected IPv4 address to Rust.
6. Include enough diagnostic metadata to explain the selection:
   - address source: `android_link_properties`
   - selected address
   - active network/interface name if available
   - candidate addresses considered, if safe to expose
   - rejection reason if no usable address is found

Rust should:

1. Accept an injected advertised local IPv4/address override for `vnet_mux`.
2. Use that address for the advertised host candidate while the mux socket remains bound appropriately, e.g. `0.0.0.0` for UDP mux.
3. Fail loudly if `vnet_mux` is requested and no injected Android address is available.
4. Keep the desktop UDP-route probe only for non-Android desktop fallback, not Android production.

### Acceptable phased implementation?

A short-lived internal patch sequence is acceptable only if it is not presented as complete. For example:

1. Remove Android `8.8.8.8` usage and make strict `vnet_mux` fail loudly without an injected address.
2. Immediately add `ConnectivityManager` / `LinkProperties` injection.

But do not merge/claim completion with strict Android `vnet_mux` unable to get an address. The completed P0 state requires address injection.

### Test requirements

Add tests for:

- no active network
- active network with no IPv4 address
- active network with multiple IPv4 addresses
- address injection present and used by `vnet_mux`
- strict `vnet_mux` fails when address injection is missing
- Android production code does not call the `8.8.8.8` helper

---

## Q4 — Should P0-005 be collapsed because the core probe already exists?

**Decision: yes. Collapse P0-005 scope. Do not rebuild the probe.**

Claude Code is right: if `crates/p2p-tunnel/src/probe.rs` already performs a bidirectional `Ping -> Pong` round trip, and the offer side already waits for the matching `Pong` before starting user TCP forwarding, then the core P0 requirement is already implemented.

The remaining P0/P1 scope should be narrowed to:

1. Add/verify answer-side `ProbingDataPlane` status so answer-side UI/status does not imply full readiness too early.
2. Add a one-way-only failure test: offer-to-answer delivery succeeds, but no pong returns, and the offer must not start user TCP forwarding.
3. Tighten diagnostic wording so it only says `echo`, `round trip`, or `bidirectional` when a pong/echo was actually received and verified.
4. Keep the existing heartbeat/self-heal behavior; do not replace it.

### Updated acceptance criteria for P0-005

- Existing bidirectional session probe remains in place.
- Offer-side forwarding still does not start until the matching pong returns.
- A one-way-only data path fails the probe.
- Answer-side status distinguishes probe handling from fully ready tunnel state.
- Diagnostics wording is precise and does not overclaim.

---

## Non-blocking clarification — TURN validation

Yes, move or duplicate TURN rejection into config validation.

The construction-time guard should stay as defense-in-depth, but invalid TURN config should fail before tunnel startup, not only when WebRTC peer construction happens.

Required behavior:

- `turn:` and `turns:` URLs fail config validation.
- Error message should be explicit, preferably:

```text
TURN servers are not supported in STUN-only mode
```

- Do not silently filter TURN URLs and continue.
- Keep the existing WebRTC-construction-time rejection as a second guard.

Tests should cover both `turn:` and `turns:`.

---

## Non-blocking clarification — P1-007 status fields

Correct: treat this as adding missing fields, not building a greenfield status system.

If heartbeat state already exists, keep it. Add only the missing fields needed to make ICE/address/probe behavior observable.

Required status/diagnostic fields:

```text
requested_ice_mode
selected_ice_path
ice_fallback_occurred
ice_fallback_reason
local_address_source
advertised_local_address
candidate_counts
probe_state
last_probe_error
```

Use names that match the existing status model style if there is already a convention.

---

## Non-blocking clarification — P2-001 unknown ICE state

Yes, implement this as a small self-contained fix.

Do not map unmapped upstream ICE states to `New`.

Add something like:

```rust
Unknown
```

or:

```rust
Unspecified
```

Then ensure status/logging preserves that value. If an unknown upstream state appears, it should be visible as unknown, not disguised as normal startup.

---

## Corrections to apply to the spec/TODO before implementation

Please update the spec/TODO text in your working branch with these corrections:

1. Correct the P0-001 premise:
   - Current `auto` behavior is not “vnet then native.”
   - The dangerous Android case is `auto` choosing native when interface enumeration succeeds.

2. Keep current ICE mode names for now:
   - `native`
   - `vnet`
   - `vnet_mux`
   - `auto`

3. Make Android default strict `vnet_mux`.

4. Treat Android `auto` as advanced/best-effort/diagnostic, not default.

5. Couple P0-001 and P0-002:
   - strict `vnet_mux` requires Android-injected local address.
   - implement `ConnectivityManager` / `LinkProperties` address injection in the same P0 patch set.

6. Collapse P0-005:
   - do not rebuild the existing bidirectional probe.
   - add missing status, wording, and one-way failure test.

7. Move/duplicate TURN rejection into config validation while keeping the WebRTC construction guard.

---

## Updated priority guidance

### Still P0

- Android default must become strict `vnet_mux`.
- Android local address must come from `ConnectivityManager` / `LinkProperties`, not `8.8.8.8`.
- Strict `vnet_mux` must fail if no injected local address is available.
- JNI/native failures must preserve real errors.
- Invalid native status/log/probe output must become visible error output, not `{}` / `[]`.
- The existing probe must have a one-way failure regression test.

### P1

- `recentLogs()` must not hide failures as an empty list.
- Forwards/setup config corruption must remain visible and block unsafe mutation/reset behavior.
- Plaintext private identity buffers must be wiped in `finally` blocks.
- `FakeTunnelBridge` must move out of production `src/main`.
- Missing status fields should be added to the existing model.

### P2

- Unknown ICE state must not map to `New`.
- Missing ICE candidates should be handled explicitly, not coerced to empty strings.
- Callback send failures should be logged unless shutdown is known/expected.
- Data-channel close should distinguish normal shutdown from premature close with active streams.

---

## Final instruction for Claude Code

Proceed with the minimal-schema-churn path:

1. Keep the four current mode names.
2. Make Android-generated configs default to strict `vnet_mux`.
3. Implement Android `ConnectivityManager` / `LinkProperties` local IPv4 injection and wire it through JNI/Rust.
4. Make `vnet_mux` fail loud when address injection or mux setup fails.
5. Treat `auto` as explicit best-effort/diagnostic only, with status fields proving what it selected.
6. Collapse the probe task to missing status/wording/tests because the core ping/pong probe already exists.
7. Continue the no-silent-fallback cleanup exactly as specified for JNI/status/log/config errors.

Do not introduce compatibility shims that preserve unsafe behavior. This project has not had an official release, so correctness and debuggability matter more than preserving old broken config behavior.
