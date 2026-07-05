# End-to-end tests

Two tiers of E2E coverage beyond the in-process `crates/p2p-daemon/tests/two_node_daemon.rs`
(which uses an in-memory signaling transport).

## Phase A — desktop ↔ desktop over a real TLS broker (automated, CI-friendly)

A self-contained Rust integration test — no docker-compose, just `cargo test`:

```
cargo test -p p2p-daemon --test real_broker_tunnel
```

What it does (`crates/p2p-daemon/tests/real_broker_tunnel.rs`):

- generates a throwaway CA + server cert at runtime (`rcgen`),
- starts **one** `eclipse-mosquitto:2` container with a TLS listener (`docker run`,
  removed automatically via an RAII guard),
- runs the real `run_offer_daemon` / `run_answer_daemon` over `mqtts://localhost:<port>`
  (trusting the CA via `broker.tls.ca_file`), with an in-test echo target,
- asserts application data round-trips: client → offer listener → WebRTC → answer →
  target → back.

Requires Docker. If Docker is absent the test logs a skip and passes, so plain
`cargo test` stays green everywhere. CI only needs Docker (preinstalled on
`ubuntu-latest`):

```yaml
- run: cargo test -p p2p-daemon --test real_broker_tunnel
```

### Phase A (docker-compose variant) — multi-container playground

A heavier, multi-container version of the same scenario, for hands-on/local use:

```
tests/e2e/docker/run.sh
```

It runs the offer and answer as **separate containers** (plus a real mosquitto TLS
broker, an nginx target, and a curl-based tester) wired on one compose bridge:

```
tester -> offer (local listener) -> WebRTC (over the bridge) -> answer -> target (nginx) -> back
```

`run.sh` generates a throwaway CA + broker cert, two peer identities + cross
`authorized_keys`, and the two daemon configs into `tests/e2e/docker/generated/`
(gitignored) at runtime, brings the stack up, and asserts the tester pulls the
target's unique marker through the tunnel, then tears everything down.

Notes:
- offer↔answer connect **directly over the compose bridge** (ICE host candidates);
  no STUN/TURN needed (unlike the emulator's NAT in Phase B).
- Host-built release binaries (`target/release/p2p-{offer,answer}`) are mounted into
  `ubuntu:24.04` (matching host glibc), so there's no slow in-Docker workspace build.
  Built automatically if missing.
- Requires `docker` + compose v2 and `openssl`. The tester runs in the offer's
  network namespace so it reaches the offer's `127.0.0.1:8080` listener.
- This is equivalent in coverage to the `cargo test` above; that test is the
  CI-friendly path, this is the multi-service local playground.

### Docker stop lifecycle (local/manual)

```
tests/e2e/docker/stop_lifecycle.sh
```

Proves the container `exec` launch pattern actually receives `docker stop`'s
`SIGTERM` and shuts down gracefully rather than hanging until the forced-kill
timeout. Brings up its own broker/target/offer/answer containers (independent
of `compose.yaml`/`run.sh`, on a throwaway Docker network, so it cannot affect
the tunnel E2E above), waits for both daemons to reach steady state, then runs
`docker stop -t 10` on both and asserts: the stop returns well under the 10s
grace period (not via forced kill), both containers exit `0`, both logs show
the shutdown-request/drain messages, and the mounted status file reports
`closed` for each. Cleans up its containers/network on exit.

## Phase B (smoke) — Android emulator against a real broker (local/manual)

```
tests/e2e/android_smoke.sh
```

Drives the real Android app on a running emulator/device through a from-scratch
setup wizard against a real MQTT broker, then asserts the offer tunnel reaches a live
**Listening** state (broker-connected, forward listening; no peer in this smoke) and
that **Stop** reverts it.

It proves the Android `.so` / JNI / Kotlin / foreground-service stack connects to a
real broker over TLS and binds its local forward listener on-device.

Prerequisites:
- a running emulator/device (`adb get-state` = `device`),
- Android SDK (set `ADB=...` if not at `~/Android/Sdk/platform-tools/adb`),
- `cargo build -p p2pctl` (auto-built if missing),
- internet access to the broker.

Defaults to the public broker `broker.emqx.io:8883` (the app trusts public roots via
webpki-roots, so no local CA provisioning is needed). Override with
`BROKER_HOST` / `BROKER_PORT`. Set `REBUILD=0` to skip the APK rebuild.

This is a **smoke** test (local/manual, not a CI gate): UI automation is inherently
emulator/AVD-sensitive, and it needs a booted emulator + internet.

## Phase B (full data path) — Android emulator offer → dockerized answer (local/manual)

```
tests/e2e/android_tunnel_e2e.sh
```

Drives the app through the same wizard (shared automation in
`lib/android_wizard.sh`), then pushes **real bytes through the tunnel**:

```
host curl --(adb forward)--> emulator 127.0.0.1:8080 (offer listener)
  --WebRTC data channel--> dockerized p2p-answer --> target (127.0.0.1:<port>) --> back
```

It runs `p2p-answer` in a container (`ubuntu:24.04`, `--network host`, mounting the
host-built release binary and the system CA bundle) that authorizes the app's own
generated identity, then asserts a unique marker is pulled all the way through. A host
`python -m http.server` on a free port is the answer's forward target.

Prerequisites: the smoke prerequisites plus `docker`, `curl`, `python3`, and the host
CA bundle at `/etc/ssl/certs/ca-certificates.crt`.

Env knobs:

- `ANSWER_NET=host|bridge` — answer networking (default `host`). `bridge` puts the answer
  behind Docker NAT (closer to a Dockerized answer-office); the forward target is reached
  via the `docker0` gateway.
- `ANDROID_ICE_MODE=auto|native|vnet|vnet_mux` — force the app's `android_ice_mode` via the
  `debug.p2p.android_ice_mode` system property (read by the **debug** build at config-render
  time). Device-agnostic: works on emulators *and* physical devices, and (unlike patching
  app-private config) survives the SELinux restriction on `run-as` **writes**. The script
  sets the prop **before** the wizard, then verifies the generated config picked it up.
  Modes: `auto` (default) uses the native engine when interface enumeration works (desktop),
  else the **UDP-mux vnet** fallback (Android 11+) — this is what fixes the offer→remote
  data-plane black-hole, so no override is needed in normal use. `vnet` forces the plain
  (non-mux) vnet fallback whose host-candidate socket is pinned to the interface IP (the
  black-holing path — diagnostic only). `vnet_mux` forces the mux path explicitly even where
  enumeration works. `native` never injects a fallback; on an **emulator**/Android 11+ it
  fails (no candidates gathered → ~30s first-open timeout), but on a **physical device** with
  a reachable host-networked answer it typically **passes** via a STUN srflx candidate — so
  the matrix tolerates either outcome for that row. Empty leaves the app default (`auto`).
- `BLACK_HOLE=1` — run the answer with `P2P_TUNNEL_DEBUG_DROP_PING=1` so it opens the data
  channel but never replies to the tunnel `Ping`. The offer's data-plane probe then times
  out; the test asserts **fast failure with no byte delivery** (and that the answer logged a
  dropped PING) instead of marker delivery. This is the only e2e path that exercises the
  probe-failure teardown end-to-end, since no local network shape actually breaks the data
  plane.

On the healthy path the script also asserts the answer logged `received tunnel PING; sending
PONG`, proving the post-DCEP data-plane probe round-tripped before bridging.

### Matrix runner (`android_tunnel_matrix.sh`)

```
tests/e2e/android_tunnel_matrix.sh
```

Runs `android_tunnel_e2e.sh` across `auto|vnet` × `host|bridge`, the `native × host`
diagnostic row (**tolerant** — either outcome is accepted, since `native` passes on a
physical device but fails on an emulator; set `EXPECT_NATIVE_ICE_PASS=1` to require a pass),
and the `BLACK_HOLE` probe-failure row, then prints a summary. The APK is built once (first
row) and reused (`REBUILD=0`) thereafter; the release answer is always rebuilt incrementally
so a schema change can't leave a stale binary rejecting the config.

Validated on a physical Samsung A54 (2026-06-15): all delivery rows pass with the data-plane
probe round-trip verified, and the black-hole row confirms fail-fast teardown end-to-end.

### Why this works now (it used to be blocked)

This path was previously deferred because the emulator gathered **no host ICE
candidate**: webrtc-rs enumerates interfaces via `getifaddrs`, which is **restricted
on Android 11+ (API 30+)**, so the emulator only offered a server-reflexive candidate
that an external answer could not reach. `p2p-webrtc`'s `build_setting_engine` now
detects that failure and injects a real-socket interface (the OS-discovered LAN IP)
via the WebRTC `SettingEngine`, so a host candidate is gathered. The emulator then
initiates ICE outbound through qemu's NAT to the host-networked answer (whose address
is reachable), and the response returns via the NAT mapping — so a valid pair forms
and the data channel opens. No TURN is involved.

The answer uses `--network host` so it advertises a reachable address; a bridge-only
container address is not reliably reachable from the emulator. This is still a
**local/manual** tier (UI-automation- and emulator-sensitive), not a CI gate. The
on-device WebRTC behaviour (host-candidate gathering + loopback handshake) is also
guarded headlessly by `WebRtcProbeInstrumentationTest`.

The wizard automation (`lib/android_wizard.sh`) works on **physical devices**, not just
emulators — UI elements are located via uiautomator (screen-size independent), the
Next button is found by scrolling when long step content pushes it off-screen, the
pre-filled broker-host field is cleared before typing, and the Remote Peer step does not
wait for a non-existent validation banner. Target a specific phone with
`ANDROID_SERIAL=<serial>` when more than one device is attached.

## Phase B (debug) — persistent both-sides rig (`android_tunnel_debug.sh`)

```
tests/e2e/android_tunnel_debug.sh            # bring the rig up (host-net answer, debug logs)
tests/e2e/android_tunnel_debug.sh --clean    # tear it down
```

Same wizard + dockerized-answer setup as the e2e test, but it **does not tear down** and
runs the answer at **DEBUG** with `stdout_logging`, so you can root-cause a stalled data
path with full both-sides visibility — answer-side frame logs via `docker logs`, plus a
host packet capture (the answer is reachable on the host). It leaves the offer Listening,
the answer container up, a host `http.server` target, and an `adb forward
127.0.0.1:18080 -> device:8080`; then drive `curl -s http://127.0.0.1:18080/marker.txt`.

Env knobs: `ANDROID_SERIAL=<serial>` (pick the phone), `ANSWER_NET=host|bridge`
(`bridge` puts the answer behind Docker NAT — closer to a Dockerized answer-office),
`ANSWER_LEVEL=debug|info`, `BROKER_HOST`/`BROKER_PORT`, `REBUILD=0`.

**What it can and cannot reproduce:** an Android offer to a *local* answer (same-LAN, or
even the phone on cellular -> a home answer) connects directly or via cone-NAT
hole-punching and **succeeds in every mode** (host, bridge, cellular) — so this rig is
great for proving the tunnel/mux/answer stack works and for fast iteration, but it does
**not** reproduce the Android-vs-remote-answer data-plane stall (that needs the real
remote answer behind its NAT/firewall). See the `memory.md` investigation notes for the
full failure matrix and the leading hypothesis (vnet-fallback socket behaviour exposed by
a symmetric/address-dependent NAT).
