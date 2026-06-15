# Android WebRTC Emulator Data-Plane Hardening TODO

This TODO implements `ANDROID_WEBRTC_EMULATOR_DATA_PLANE_SPEC.md`.

Scope constraints:

- Do not implement TURN.
- Do not require `answer-office`.
- Use Docker + Android emulator for development and acceptance testing.
- Do not add silent fallback paths.
- Existing configs must remain backward-compatible.

## P0 — Config schema and backward compatibility

- [ ] Add `AndroidIceMode` enum in `p2p-core`.
  - [ ] Values: `auto`, `native`, `vnet`.
  - [ ] Use `#[serde(rename_all = "lowercase")]`.
  - [ ] Derive `Clone`, `Copy`, `Debug`, `Eq`, `PartialEq`, `Serialize`, `Deserialize`.

- [ ] Add `android_ice_mode` to `WebRtcConfig`.
  - [ ] Use `#[serde(default = "default_android_ice_mode")]`.
  - [ ] Default is `AndroidIceMode::Auto`.
  - [ ] Existing TOML files without the field must still parse.

- [ ] Add `data_plane_probe_timeout_ms` to `TunnelConfig`.
  - [ ] Use `#[serde(default = "default_data_plane_probe_timeout_ms")]`.
  - [ ] Default is `5000`.
  - [ ] Existing TOML files without the field must still parse.

- [ ] Update config validation tests.
  - [ ] Old config without new fields parses.
  - [ ] New config with `android_ice_mode = "auto"` parses.
  - [ ] New config with `android_ice_mode = "native"` parses.
  - [ ] New config with `android_ice_mode = "vnet"` parses.
  - [ ] Invalid mode fails validation.
  - [ ] `data_plane_probe_timeout_ms = 0` is **rejected** (not clamped) via an explicit
        validation hook — `#[serde(default)]` only handles a missing field, not range.
  - [ ] Enforce bounds: min **100 ms**, default **5000 ms**, max ~**60000 ms**;
        out-of-range values rejected with a clear config error (no silent clamp).

- [ ] Update Android config templates in `android/app/src/main/java/com/phillipchin/webrtctunnel/data/ConfigTemplates.kt`.
  - [ ] Generated `[webrtc]` includes `android_ice_mode = "auto"`.
  - [ ] Generated `[tunnel]` includes `data_plane_probe_timeout_ms = 5000`.

- [ ] Update all test-generated TOML configs.
  - [ ] `tests/e2e/android_tunnel_e2e.sh`
  - [ ] `tests/e2e/android_tunnel_debug.sh`
  - [ ] Any Rust integration test fixture configs.

## P0 — Android ICE mode implementation

- [ ] Change `crates/p2p-webrtc/src/lib.rs` so `build_setting_engine()` receives `&WebRtcConfig`.
  - [ ] New signature should return `Result<SettingEngine, WebRtcError>`.
  - [ ] Update `WebRtcPeer::new(config)` to pass config into it.

- [ ] Implement selected-mode decision logic. **`android_ice_mode` is honored on ALL
      platforms** (historical name; the vnet fallback is already runtime-selected by
      interface enumeration, not `#[cfg(target_os = "android")]`). Do **not** ignore it off
      Android — desktop integration tests must be able to force `native`/`vnet` too. Add a
      code comment noting the cross-platform semantics.
  - [ ] `auto` uses native/default setting engine if interface enumeration works.
  - [ ] `auto` applies `Net::Ifs` fallback if interface enumeration fails.
  - [ ] `auto` logs the decision + reason (no silent fallback), e.g.
        `ice_mode=auto selected_path=native|vnet set_vnet=false|true reason=...`.
  - [ ] `native` never calls `set_vnet`; fails loudly (no fallback) if enumeration yields
        no usable candidate.
  - [ ] `vnet` forces fallback construction even if enumeration would have worked.
  - [ ] `vnet` returns a config/startup error if fallback local IPv4 cannot be found
        (never silently falls back to native).

- [ ] Add internal decision diagnostics.
  - [ ] Requested mode.
  - [ ] Selected mode.
  - [ ] OS interface enumeration success/failure.
  - [ ] Fallback IPv4 discovery success/failure.
  - [ ] Whether `set_vnet` was applied.

- [ ] Add logs with target `ice`.
  - [ ] `android ICE mode requested`
  - [ ] `android ICE mode selected`
  - [ ] `OS interface enumeration unavailable`
  - [ ] `using native/default setting engine`
  - [ ] `using Net::Ifs fallback setting engine`
  - [ ] `forced vnet mode requested but fallback local IPv4 unavailable`

- [ ] Preserve existing successful behavior for default Android emulator runs.
  - [ ] Default config value is `auto`.
  - [ ] `auto` must still allow emulator/Docker e2e to pass.

- [ ] Add focused Rust unit tests around the mode decision if practical.
  - [ ] Test pure decision helper separately from real OS interface enumeration.
  - [ ] Do not write tests that depend on the CI machine's actual network interfaces.

## P0 — Data-plane probe implementation

- [ ] Add `rand_core` dependency to `crates/p2p-tunnel/Cargo.toml`.
  - [ ] Use `rand_core = { workspace = true }` — it is **already** a workspace dep
        (used by `p2p-core/src/ids.rs`); this only wires it into `p2p-tunnel`'s manifest.
  - [ ] Generate a 16-byte nonce via `OsRng`.

- [ ] Add explicit tunnel errors in `crates/p2p-tunnel/src/error.rs`.
  - [ ] `DataPlaneProbeTimeout(Duration)`.
  - [ ] `DataPlaneProbeFailed(String)`.

- [ ] Add `probe_data_plane(...)` to `p2p-tunnel`.
  - [ ] Signature: `pub async fn probe_data_plane(data_channel: &DataChannelHandle, timeout: Duration) -> Result<(), TunnelError>`.
  - [ ] Send `TunnelFrame::ping(nonce)` directly over the data channel.
  - [ ] Wait for matching `TunnelFrame::pong(nonce)`.
  - [ ] Enforce timeout.
  - [ ] Return `DataPlaneProbeTimeout` on timeout.
  - [ ] Return `DataChannelClosed` if channel closes.
  - [ ] Reply to inbound `Ping` with `Pong` while waiting.
  - [ ] Ignore or warn on mismatched `Pong`.
  - [ ] Treat `Open`, `Data`, `Close`, or `Error` before probe completion as protocol errors unless clearly justified.
  - [ ] Do not log arbitrary payload bytes.

- [ ] Add probe logs.
  - [ ] `data-plane probe sent`.
  - [ ] `data-plane probe acknowledged`.
  - [ ] `data-plane probe timed out`.
  - [ ] `data-plane probe received mismatched pong`.
  - [ ] `data-plane probe received unexpected pre-probe frame`.

- [ ] **Verify/extend** frame tests — most of this is already enforced, not new. The codec
      already rejects stream frames on id 0 and Ping/Pong on nonzero id
      (`frame.rs:150-156`, `TunnelError::SessionControlStreamId`), and
      `reject_reserved_stream_id_for_stream_frames` already exists. Confirm coverage and add
      only the missing cases:
  - [ ] Ping frame encodes/decodes with stream id 0. (verify)
  - [ ] Pong frame encodes/decodes with stream id 0. (verify)
  - [ ] Ping/Pong with nonzero stream id is rejected. (verify — already covered)
  - [ ] Stream frame with stream id 0 is rejected. (verify — `reject_reserved_...` test)

- [ ] Add focused tests for pure probe helpers.
  - [ ] Matching nonce succeeds.
  - [ ] Mismatched nonce is not accepted.
  - [ ] Timeout path maps to `DataPlaneProbeTimeout`.

## P0 — Offer session integration

- [ ] Update `crates/p2p-daemon/src/offer/session/mod.rs`.
  - [ ] Do not start `run_multiplex_offer()` solely because `data_channel.is_open()` is true.
  - [ ] Run `p2p_tunnel::probe_data_plane()` first.
  - [ ] Use `Duration::from_millis(config.tunnel.data_plane_probe_timeout_ms)`.
  - [ ] Only set status to `TunnelOpen` after probe success.
  - [ ] Only set `BridgeSessionState::Active` after probe success.
  - [ ] Only set `bridge_ever_active = true` after probe success.
  - [ ] Only then start `run_multiplex_offer()`.

- [ ] On probe failure:
  - [ ] Log `session_id` and `remote_peer_id`.
  - [ ] Log timeout duration.
  - [ ] Return an error from the session.
  - [ ] Ensure `cleanup_active_session()` runs.
  - [ ] Ensure daemon returns to listening/waiting steady state.
  - [ ] Ensure local TCP client is dropped/closed instead of hanging.

- [ ] Preserve existing first data-channel-open timeout.
  - [ ] That timeout still protects pre-open stalls.
  - [ ] The new probe timeout protects post-open data-plane stalls.

- [ ] Make sure the probe consumes only pre-bridge events.
  - [ ] No stream `OPEN` should be sent until after probe success.
  - [ ] `run_multiplex_offer()` should not receive the probe Pong later.
  - [ ] Exactly **one consumer** of `DataChannelHandle::next_event()`: the probe owns it
        until success/failure, then hands off to `run_multiplex_offer`. Probe and first
        real `OPEN` are never in flight together.

- [ ] Run the probe **cancel-safe**, not as a bare blocking `await`.
  - [ ] Run it as a `tokio::select!` arm racing ICE `Failed`/`Disconnected`/`Closed`, so a
        mid-probe ICE disconnect aborts the probe immediately instead of waiting out the
        timeout. (Ordering "probe before OPEN" is separate from "probe must not block ICE
        handling".)
  - [ ] Add a one-line comment noting the benign probe-vs-answer-start race: the offer may
        send `Ping` before the answer starts reading, but SCTP is reliable+ordered so the
        buffered `Ping` is delivered — not a bug, do not "fix" it.

- [ ] Prevent a reconnect hot loop on a persistently-broken data plane (§8.4a).
  - [ ] Existing `enable_auto_reconnect` backoff governs reconnect/ICE-restart, NOT fresh
        per-local-client negotiation, so it does not cover this case by itself.
  - [ ] Add a **per-remote-peer probe-failure cooldown**: after a probe failure to a peer,
        suppress re-negotiation to that peer for a short window (few seconds, backoff to a
        cap) so repeated local connects fail fast against the cooldown rather than
        re-running negotiate+probe each time.
  - [ ] Log the cooldown/cadence; keep fail-fast per request.

## P1 — Answer-side logging cleanup

- [ ] Update `crates/p2p-tunnel/src/multiplex/answer.rs`.
  - [ ] When receiving `Ping`, log `answer received tunnel PING; sending PONG`.
  - [ ] Do not include payload bytes.
  - [ ] Keep existing behavior of replying with `Pong(frame.payload)`.

- [ ] Update offer-side frame handling if needed.
  - [ ] Keep normal-loop behavior for inbound `Ping` => reply `Pong`.
  - [ ] Keep ignoring unsolicited `Pong` after bridge is active unless diagnostics justify logging at trace/debug.

## P1 — E2E script support for mode and Docker networking

- [ ] Update `tests/e2e/android_tunnel_e2e.sh`.
  - [ ] Support `ANSWER_NET=host|bridge`.
  - [ ] Default remains `host` for backward compatibility.
  - [ ] Bridge mode should match the behavior already implemented in `android_tunnel_debug.sh`.
  - [ ] Configure answer target host correctly for both host and bridge modes.

- [ ] Add a way to set Android offer `android_ice_mode` from the E2E script.
  - [ ] Accept `ANDROID_ICE_MODE=auto|native|vnet`.
  - [ ] Ensure the app-generated offer config actually contains the requested value.
  - [ ] Do not implement this as a Rust desktop-only environment variable.
  - [ ] **Preferred (device-agnostic):** debug-build reads a test-only **intent extra** or
        **system property** (e.g. `debug.p2p.android_ice_mode`) and writes the mode into the
        generated config. Works on emulator **and** physical devices.
  - [ ] **Do not rely on `run-as` config patching** as the primary path: it works on the
        emulator but **fails on a physical Samsung A54** (Android 16 SELinux blocks `run-as`
        **writes**; reads work). May stay as a documented emulator-only fallback.
  - [ ] **Timing:** inject **after** the wizard completes and **before** Start — a plain
        Start does not regenerate config so the override sticks, but **re-running the wizard
        regenerates `"auto"`** and overwrites it.

- [ ] Add E2E log assertions.
  - [ ] Offer logs include `data-plane probe sent`.
  - [ ] Offer logs include `data-plane probe acknowledged`.
  - [ ] Answer logs include `received tunnel PING`.
  - [ ] Answer logs include `sending PONG` or equivalent.
  - [ ] Logs include selected Android ICE mode.

- [ ] Improve E2E failure output.
  - [ ] Print last 100 answer logs.
  - [ ] Print recent Android native logs if accessible.
  - [ ] Print selected mode and Docker network mode.
  - [ ] Print whether probe was sent and acknowledged.

## P1 — Add matrix script

- [ ] Add `tests/e2e/android_tunnel_matrix.sh`.

- [ ] Required rows:
  - [ ] `ANDROID_ICE_MODE=auto ANSWER_NET=host tests/e2e/android_tunnel_e2e.sh`
  - [ ] `ANDROID_ICE_MODE=auto ANSWER_NET=bridge tests/e2e/android_tunnel_e2e.sh`
  - [ ] `ANDROID_ICE_MODE=vnet ANSWER_NET=host tests/e2e/android_tunnel_e2e.sh`
  - [ ] `ANDROID_ICE_MODE=vnet ANSWER_NET=bridge tests/e2e/android_tunnel_e2e.sh`

- [ ] Optional diagnostic row:
  - [ ] `ANDROID_ICE_MODE=native ANSWER_NET=host tests/e2e/android_tunnel_e2e.sh`

- [ ] Native-mode row behavior:
  - [ ] By default, allow native row to be `EXPECTED_FAIL` on emulator/Android 11+.
  - [ ] It must fail loudly if it fails.
  - [ ] It must prove `set_vnet` was not used (`ice_mode=native set_vnet=false`).
  - [ ] Key `EXPECTED_FAIL` on the right path/latency: native gathers **no** candidates on
        the emulator, so the data channel never opens and it fails via the
        **first-open timeout (~30 s)**, *not* the probe timeout (~5 s).
  - [ ] If `EXPECT_NATIVE_ICE_PASS=1`, treat native row failure as a hard failure.

## P1 — Black-hole-answer E2E (probe FAILURE path)

The matrix's happy-path rows can only succeed (no local network shape breaks the A54 data
plane — see `docs/ANDROID_WEBRTC_DATA_PLANE_ISSUE.md`), so the fail-fast teardown is never
exercised end-to-end without a deliberately broken answer. Unit tests cover probe logic;
this covers whole-session behavior.

- [ ] Add a **debug-only** drop-ping mode to `p2p-answer`.
  - [ ] Flag `--debug-drop-ping` (CLI), gated by env `P2P_TUNNEL_DEBUG_DROP_PING=1`.
  - [ ] Opens the WebRTC data channel normally but **silently drops inbound `Ping`** (never
        replies `Pong`).
  - [ ] Debug/test build only; must not affect normal operation.
- [ ] Add a matrix/E2E row running the Android offer against the black-hole answer, asserting:
  - [ ] data channel opens,
  - [ ] offer sends `Ping`, receives **no** `Pong`,
  - [ ] probe times out at ~`data_plane_probe_timeout_ms` (not the ~30 s open timeout),
  - [ ] session tears down cleanly, daemon returns to listening/waiting,
  - [ ] local TCP client does **not** hang (prompt refusal/close),
  - [ ] no reconnect hot loop (cooldown holds).

- [ ] Matrix output should summarize:
  - [ ] mode,
  - [ ] answer network,
  - [ ] result,
  - [ ] probe sent yes/no,
  - [ ] probe acknowledged yes/no,
  - [ ] selected ICE path.

## P1 — Android logs / diagnostics visibility

- [ ] Ensure Android recent logs include the new native log events.
  - [ ] Requested Android ICE mode.
  - [ ] Selected ICE path.
  - [ ] Probe sent.
  - [ ] Probe acknowledged.
  - [ ] Probe timeout/failure.

- [ ] Ensure a probe failure does not leave the UI stuck in a misleading open state.
  - [ ] Status should return to waiting/listening after session cleanup.
  - [ ] Logs screen should show the probe failure reason.

- [ ] Do not add a full user-facing settings screen for `android_ice_mode` in this pass unless needed for testability.
  - [ ] Prefer keeping it as generated config/test harness plumbing for now.

## P2 — Documentation updates

- [ ] Update `docs/ANDROID_WEBRTC_DATA_PLANE_ISSUE.md` or add a follow-up note.
  - [ ] State that emulator/Docker is the current development target.
  - [ ] State that `answer-office` is down and not part of current acceptance.
  - [ ] Describe the new post-open probe.
  - [ ] Describe Android ICE modes.
  - [ ] Document that emulator tests do not prove arbitrary real-device NAT behavior.

- [ ] Update `tests/e2e/README.md`.
  - [ ] Document `ANDROID_ICE_MODE`.
  - [ ] Document `ANSWER_NET` for both e2e and debug scripts.
  - [ ] Document `android_tunnel_matrix.sh`.
  - [ ] Document expected native-mode behavior.

- [ ] Add troubleshooting notes.
  - [ ] Probe timeout means DCEP opened but application data did not round-trip.
  - [ ] Native-mode failure on emulator may be expected if Android interface enumeration is restricted.
  - [ ] Vnet-mode failure to find fallback IPv4 is fatal and should not silently continue.

## P2 — Optional MTU/read-size hardening note only

- [ ] Do not change `read_chunk_size` in this pass unless a test proves it is necessary.

- [ ] Add a short comment or future-work note that SCTP/data-channel MTU hardening remains separate from the current probe/mode work.

## Validation checklist

Run these before handing back:

- [ ] `cargo fmt --all`
- [ ] `cargo clippy --workspace --all-targets -- -D warnings`
- [ ] `cargo test --workspace`
- [ ] `cd android && ./gradlew --no-daemon testDebugUnitTest`
- [ ] `cd android && ./gradlew --no-daemon assembleDebug`
- [ ] `tests/e2e/android_tunnel_e2e.sh`
- [ ] `ANSWER_NET=bridge tests/e2e/android_tunnel_e2e.sh`
- [ ] `tests/e2e/android_tunnel_matrix.sh`

## Final acceptance checklist

- [ ] No TURN support was added.
- [ ] `answer-office` is not required by any new test.
- [ ] Existing configs remain backward-compatible.
- [ ] Android-generated configs include explicit new fields.
- [ ] `android_ice_mode=auto` preserves emulator/Docker success.
- [ ] `android_ice_mode=native` never calls `set_vnet`.
- [ ] `android_ice_mode=vnet` forces fallback or fails loudly.
- [ ] Offer sends Ping after data-channel open.
- [ ] Answer replies Pong.
- [ ] Offer requires matching Pong before stream OPEN.
- [ ] Probe timeout tears down the session.
- [ ] User/client does not hang indefinitely on post-open data-plane failure.
- [ ] E2E logs prove probe sent and acknowledged.
- [ ] Matrix script covers host and bridge Docker answer modes.
- [ ] No new unsafe silent fallback behavior was introduced.
- [ ] A black-hole-answer E2E exercises the probe-failure path end-to-end (open → no Pong →
      timeout → clean teardown → return to listening → client does not hang).
- [ ] Probe is cancel-safe (races ICE failure; single `next_event()` consumer).
- [ ] Persistently-broken data plane does not hot-loop (per-remote-peer cooldown).
- [ ] `data_plane_probe_timeout_ms = 0` / out-of-range is rejected by validation.
- [ ] `android_ice_mode` is honored on all platforms (not ignored off Android).
- [ ] Docs state this pass is a detector/guardrail + test lever, NOT the answer-office fix.
