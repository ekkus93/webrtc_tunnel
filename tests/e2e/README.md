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

## Phase B (smoke) — Android emulator against a real broker (local/manual)

```
tests/e2e/android_smoke.sh
```

Drives the real Android app on a running emulator/device through a from-scratch
setup wizard against a real MQTT broker, then asserts the offer tunnel reaches
**Connected** with its forward **Listening**, and that **Stop** reverts it.

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

### Why no full Android data-path E2E yet

The full path (Android offer → WebRTC → Linux answer → target, with real bytes) is
**blocked**: the emulator is behind qemu user-mode NAT, and `p2p-webrtc`'s
`build_rtc_configuration` rejects `turn:` URLs ("TURN URLs are not supported in v1")
and sets no ICE relay credentials, so the emulator and an external answer cannot
reliably establish a peer connection. Enabling it requires either:

- adding TURN support to the code (config + `RTCIceServer` credentials + a coturn
  relay) — a real product change to security-relevant networking, to be specced and
  signed off separately, or
- bridged/TAP emulator networking so direct ICE works.

Until then, the data path is covered by Phase A (desktop↔desktop over a real broker)
and the headless `bind_offer_listeners_soft_fails_individual_forward` /
`snapshot_status_overlays_daemon_status_when_active` tests, while this smoke test
covers the Android-on-device connect/listen/stop lifecycle. See
`docs/archive/DOCKER_TESTS1_TODO.md` (Phase B, B2) for details.
