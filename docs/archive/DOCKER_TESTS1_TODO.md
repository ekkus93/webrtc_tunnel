# Docker + Emulator End-to-End Tests — TODO (Phase A & Phase B)

## Objective

Build end-to-end (E2E) tests that exercise the WebRTC tunnel across **real
processes and a real MQTT broker**, beyond the existing in-process integration
test (`crates/p2p-daemon/tests/two_node_daemon.rs`, which uses an in-memory
signaling transport).

- **Phase A — hermetic desktop ↔ desktop over a real TLS broker (docker-compose).**
  Two desktop daemons (offer + answer) + mosquitto(TLS) + a target HTTP service,
  asserting real data flows through the tunnel. Low risk, CI-friendly.
- **Phase B — Android emulator (offer) ↔ Linux answer (docker).** The real prize:
  validates the Android `.so` + JNI + Kotlin + foreground service + real MQTT/TLS
  in an actual tunnel. Higher risk (NAT traversal, Android CA trust).

> Build Phase A first; it de-risks and provides reusable PKI/broker/config assets
> for Phase B.

---

## Background constraints (grounded in the current code — read before designing)

- **Binaries:** `bins/p2p-offer` (`run --config <path> [--broker-url ...]`),
  `bins/p2p-answer` (same shape), `bins/p2pctl`
  (`keygen <peer_id>`, `add-authorized-key <pub>`, `fingerprint`, `check-config`).
- **Config:** TOML `format = "p2ptunnel-config-v3"` with sections `[node]`,
  `[peer]`, `[paths]`, `[broker]` (+`[broker.tls]`), `[webrtc]`, `[tunnel]`,
  `[[forwards]]` (+`forwards.offer` / `forwards.answer`), `[reconnect]`,
  `[security]`, `[logging]`, `[health]`.
- **Roles:** offer side binds a **local listener** (`forwards.offer.listen_host/port`)
  and tunnels to the answer, which dials `forwards.answer.target_host/target_port`.
  **Android only runs offer** (answer mode is disabled), so in Phase B the container
  is the answer and the emulator is the offer.
- **MQTT is TLS-only:** `security.require_mqtt_tls` is forced on and
  `broker.tls.insecure_skip_verify` is unsupported
  (`crates/p2p-core/src/config.rs`). The broker URL must be `mqtts://`.
- **TLS trust:**
  - Desktop/`build_tls_transport` uses `broker.tls.ca_file` when set; if empty it now
    falls back to **webpki-roots (Mozilla roots)** (`crates/p2p-signaling/src/transport.rs`).
  - A **local** mosquitto uses a **private CA**, which webpki-roots will NOT trust →
    peers must point `broker.tls.ca_file` at our CA cert. Desktop supports this.
  - **The Android app has no `ca_file` UI** (`ConfigRepository` never sets it). This
    is a Phase B blocker that B1 must resolve.
- **TURN is NOT supported:** `build_rtc_configuration`
  (`crates/p2p-webrtc/src/lib.rs:~374`) **errors on any `turn:`/`turns:` URL** and
  builds `RTCIceServer` with STUN URLs only (no username/credential). This is the
  central Phase B risk (B2): emulator↔container WebRTC across qemu NAT normally
  needs a relay.
- **Existing coverage to NOT duplicate:** `two_node_daemon.rs` already covers
  offer↔answer signaling + real WebRTC + reconnect/fault injection over in-memory
  transport. New value = real broker/TLS (Phase A) and the Android stack (Phase B).

---

## P0 — Decisions & prerequisites (resolve before building)

- [ ] **D1 — Goal:** hermetic CI gate vs. local/dev validation. CI → Phase A must be
      offline + reliable; Phase B likely a manual/dedicated-runner job.
- [ ] **D2 — Host requirements:** Docker + docker-compose available; for Phase B,
      `/dev/kvm` + an Android SDK/emulator on the host (already present here:
      `~/Android/Sdk`, AVD `Medium_Phone_API_36.0`).
- [ ] **D3 — Emulator location (Phase B):** run emulator **on the host** (recommended,
      fewer failure modes) and only put broker/answer/target/relay in compose; vs.
      emulator-in-Docker (needs KVM passthrough). Recommend host.
- [ ] **D4 — Phase B NAT traversal strategy (pick one; see B2):**
      (a) add TURN support to the code (coturn relay), (b) bridged/TAP emulator
      networking so direct ICE works, or (c) accept Phase B is "best effort" and
      lean on Phase A + scripted Android smoke for coverage.
- [ ] **D5 — Phase B broker:** local mosquitto + CA provisioning on Android (hermetic)
      vs. public `broker.emqx.io` (works today, not hermetic, needs internet).
- [ ] **D6 — Where test assets live:** e.g. `tests/e2e/docker/` (compose, certs gen,
      configs, Dockerfiles) and a runner script `tests/e2e/run.sh`. Confirm path.
- [ ] Record host versions (docker, compose, emulator, adb) for reproducibility.

---

## Phase A — Hermetic desktop ↔ desktop over a real TLS broker

> **✅ IMPLEMENTED (simplified, no docker-compose).** Built as a single self-contained
> Rust integration test: `crates/p2p-daemon/tests/real_broker_tunnel.rs`,
> `full_tunnel_over_real_tls_broker`. It:
> - generates a throwaway CA + server cert at runtime with `rcgen` (dev-dep),
> - starts **one** `eclipse-mosquitto:2` container with a TLS listener (`docker run`,
>   removed via an RAII `Drop` guard) — no compose, no `bash` orchestration,
> - runs the real `run_offer_daemon` / `run_answer_daemon` over `mqtts://localhost:<port>`
>   (trusting the CA via `broker.tls.ca_file`), with an in-test echo target,
> - asserts application data round-trips: client → offer listener → WebRTC → answer →
>   echo target → back.
> - **Auto-skips** (logs + passes) when Docker is absent, so plain `cargo test` stays
>   green everywhere; CI just needs Docker (preinstalled on `ubuntu-latest`).
>
> Run: `cargo test -p p2p-daemon --test real_broker_tunnel`. Verified locally:
> passes in ~2s, repeatable, leaves no containers behind.
> Dev-deps added to `crates/p2p-daemon/Cargo.toml`: `rcgen`, `tempfile`.
>
> **Also implemented — docker-compose variant** (`tests/e2e/docker/`,
> `run.sh` + `compose.yaml`): the same scenario with the offer and answer as
> **separate containers** plus a real mosquitto TLS broker, an nginx target, and a
> curl tester, wired on one compose bridge. `run.sh` generates certs/identities/
> configs at runtime into `generated/` (gitignored), brings the stack up, asserts
> the tester pulls the target's marker through the tunnel, and tears down. Verified
> locally: passes repeatably, clean teardown. offer↔answer connect direct over the
> bridge (no STUN/TURN). This realizes the A1–A10 design below as a multi-service
> local playground; the `cargo test` above remains the CI-friendly path. See
> `tests/e2e/README.md`.

### A1 — PKI / certificate generation
- [ ] Script `tests/e2e/docker/gen-certs.sh` that produces (idempotently, into a
      gitignored dir):
  - [ ] a test **CA** (key + cert), e.g. `ca.crt` / `ca.key`.
  - [ ] a **broker server cert** signed by the CA, with **SAN `DNS:broker`** (the
        compose service name) and `DNS:localhost` / `IP:127.0.0.1` for host runs.
  - [ ] correct file perms (config validation rejects world-writable secret paths;
        see `validate_non_world_writable`).
- [ ] Document why a private CA is needed (broker is internal; no public cert).
- [ ] Ensure the generated dir is in `.gitignore` (no committed secrets).

### A2 — Mosquitto broker container (TLS)
- [ ] `mosquitto.conf` with a TLS listener on 8883: `cafile`, `certfile`, `keyfile`,
      `require_certificate false` (no mTLS for Phase A), `allow_anonymous true`.
- [ ] Compose service `broker` (image `eclipse-mosquitto`), mount conf + certs,
      expose 8883 on the compose network.
- [ ] Healthcheck (e.g. `mosquitto_sub`/port check) so peers wait for readiness.

### A3 — Identities & authorized_keys
- [ ] Generate two identities with `p2pctl keygen` (e.g. `offer-peer`, `answer-peer`)
      into per-peer config dirs (use `HOME`/`--` to control output location).
- [ ] Build each peer's `authorized_keys` containing the **other** peer's public
      identity (`p2pctl add-authorized-key`), so each authorizes the other.
- [ ] Verify peer_ids and that `config.validate_identity_peer` will pass.
- [ ] Keep private identities out of git; generate at test time.

### A4 — Peer configs (TOML)
- [ ] `offer.toml`: `node.role = "offer"`, `peer.remote_peer_id = "answer-peer"`,
      `broker.url = "mqtts://broker:8883"`, `broker.tls.ca_file = <ca.crt path>`,
      a forward with `forwards.offer.listen_host="0.0.0.0"`/`listen_port=8080`
      and matching `forwards.answer` block, `webrtc.stun_urls` (see A4-note),
      `paths.*` pointing at the container's mounted dirs, `health.write_status_file`.
- [ ] `answer.toml`: `node.role = "answer"`, `peer.remote_peer_id = "offer-peer"`,
      same broker/CA, forward with `forwards.answer.target_host="target"`,
      `target_port=80`, `allow_remote_peers=["offer-peer"]`.
- [ ] **A4-note (STUN/ICE in-compose):** both peers share a docker network, so ICE
      **host candidates** on the compose network should connect directly without
      STUN/TURN. Set `stun_urls = []` (or a reachable STUN) and confirm ICE
      completes over the bridge network. (No TURN needed for Phase A.)
- [ ] Run `p2pctl check-config` against both configs as a pre-flight.

### A5 — Target service
- [ ] Compose service `target` serving deterministic content (e.g. `nginx` or
      `kennethreitz/httpbin`) on port 80, with a known response body to assert on.

### A6 — Peer images
- [ ] `Dockerfile` building `p2p-offer`, `p2p-answer`, `p2pctl` (`cargo build
      --release -p ...`) on a slim runtime base; OR a builder stage + mount the
      release binaries to keep images small. Decide and document.
- [ ] Entrypoints: `p2p-offer run --config /cfg/offer.toml` /
      `p2p-answer run --config /cfg/answer.toml`.

### A7 — docker-compose wiring
- [ ] `docker-compose.yml` (or `compose.yaml`) with services: `broker`, `target`,
      `answer`, `offer`, `tester`.
- [ ] One user-defined bridge network; service-name DNS (`broker`, `target`).
- [ ] `depends_on` + healthchecks: target ← answer ← (broker healthy) ← offer.
- [ ] Mount certs/configs read-only; set the secret-file perms expected by config
      validation.
- [ ] Surface offer's local forward (8080) to the `tester` (same network) and/or
      host port for debugging.

### A8 — Test driver & assertions
- [ ] `tester` service (or `tests/e2e/run.sh`) that, after the tunnel is up:
  - [ ] polls offer's status file / logs until offer reaches a "listening/serving"
        state and the WebRTC data channel is open (with a timeout).
  - [ ] performs `curl http://offer:8080/<known-path>` and asserts the response
        equals the `target` service's known content (proves the **data path**:
        offer local listener → WebRTC → answer → target).
  - [ ] returns non-zero on mismatch/timeout (the test's pass/fail signal).
- [ ] Add negative/robustness cases (optional, incremental):
  - [ ] broker down → offer reports error, does not hang.
  - [ ] answer restart → tunnel reconnects (exercises reconnect path).
- [ ] Capture logs from all services on failure for debugging.

### A9 — Runner & CI
- [ ] `tests/e2e/run.sh`: gen certs → `docker compose up --build --abort-on-container-exit
      --exit-code-from tester` → propagate exit code → `docker compose down -v`.
- [ ] Make it idempotent and self-cleaning (no leftover networks/volumes).
- [ ] CI job (if D1=CI): runs on a Docker-capable runner; reasonable timeout; upload
      logs as artifacts on failure.
- [ ] Document local usage in `tests/e2e/README.md`.

### A10 — Phase A acceptance
- [ ] `run.sh` exits 0: real `mqtts` broker used, real WebRTC, `curl` through the
      tunnel returns the target's content.
- [ ] Teardown leaves no containers/volumes/networks.
- [ ] No secrets committed; certs/identities generated at runtime.

---

## Phase B — Android emulator (offer) ↔ Linux answer (docker)

> **◐ PARTIALLY IMPLEMENTED — smoke tier done; full data path deferred.**
>
> **Done (smoke):** `tests/e2e/android_smoke.sh` automates a running emulator end to
> end — builds/installs the APK, `pm clear`s for a clean state, generates a remote
> peer identity (`p2pctl`), drives the full 7-step setup wizard via `adb`/uiautomator
> against a **real broker** (`broker.emqx.io:8883`, B1 Option 3 — no CA change needed
> since the app trusts public roots via webpki-roots), starts the tunnel, and asserts
> the offer reaches **Connected** with its forward **Listening**, then **Stop** →
> **Stopped**. Verified locally: passes repeatably, leaves the app stopped/clean.
> This exercises the Android `.so`/JNI/Kotlin/foreground-service stack against a real
> TLS broker on-device. It is a **local/manual** smoke test (needs a booted emulator
> + internet), not a CI gate.
>
> **Deferred (full data path):** Android offer → WebRTC → Linux answer → target with
> real bytes is **blocked by B2**: the emulator is behind qemu NAT and `p2p-webrtc`
> rejects `turn:` URLs (no ICE relay), so emulator↔external WebRTC can't reliably
> connect. Unblocking needs the TURN code change (B2 Option A — separate product
> spec/sign-off) or bridged emulator networking (B2 Option B). Recorded here rather
> than silently bolting a TURN feature onto a tests task. Meanwhile the data path is
> covered by Phase A (desktop↔desktop over a real broker) + headless daemon tests.
>
> See `tests/e2e/README.md`. The detailed task breakdown below remains the plan for
> the full tier once B2 is resolved.

### B1 — Android broker TLS trust (BLOCKER) — pick a path
- [ ] **Option 1 (hermetic, preferred long-term): add `ca_file` support to Android.**
  - [ ] Let the app set `broker.tls.ca_file` (config import via the Import/Export
        screen, or a new wizard field), and place the CA cert at a device path the
        app process can read (e.g. app `filesDir`).
  - [ ] Verify the native daemon reads it (`build_tls_transport` honors `ca_file`).
- [ ] **Option 2 (hermetic, no app change): full-config import.**
  - [ ] Generate a complete `config.toml` (ca_file + identity refs) on the host,
        `adb push` config + CA + identity into the app sandbox via `run-as`
        (debuggable build), confirm the app/daemon load them. Note: identity is
        Keystore-encrypted by the app, so this may still require wizard-generated
        identity — verify feasibility.
- [ ] **Option 3 (non-hermetic): public broker `broker.emqx.io`** (empty `ca_file` →
      webpki-roots; already verified working). Accept internet dependency.
- [ ] Decide and document the chosen path; it determines B5/B6.

### B2 — WebRTC NAT traversal (BLOCKER) — pick a path
- [ ] **Root issue:** the emulator is behind qemu user-mode NAT (10.0.2.x); inbound
      UDP to it from a container fails, and **TURN is rejected by the code**
      (`build_rtc_configuration`). Options:
- [ ] **Option A — add TURN support (code change, most robust).**
  - [ ] Extend `WebRtcConfig` with TURN servers + username/credential; build
        `RTCIceServer` with credentials; remove the "TURN not supported" guard.
  - [ ] Run `coturn` reachable by both (emulator via `10.0.2.2`/host-forward,
        container via compose network).
  - [ ] This is a real product change — spec/sign-off separately; it also benefits
        real deployments behind strict NATs.
- [ ] **Option B — bridged/TAP emulator networking (no code change).**
  - [ ] Launch the emulator with a network mode where it's a first-class host on a
        network the container shares, so ICE host/srflx candidates connect directly.
  - [ ] Validate ICE actually completes; document the exact emulator flags.
- [ ] **Option C — scope-down:** accept that full emulator↔container WebRTC is out of
      scope; rely on Phase A for tunnel-data coverage + a scripted Android **smoke**
      test (B7-smoke) that asserts Connected/Listening against a broker (no peer
      data path). Lowest effort.
- [ ] Decide; B6/B7 depend on this.

### B3 — Emulator provisioning (host)
- [ ] Script headless emulator boot (`emulator -avd <name> -no-window -no-snapshot
      -wait-for-device`), wait for `sys.boot_completed`.
- [ ] Pin/record AVD + system image; document creating the AVD if absent.
- [ ] Helper to reset app state between runs (`pm clear` / reinstall).

### B4 — App build, install, provisioning
- [ ] Build debug APK with the Rust `.so` (`./gradlew assembleDebug` →
      `buildRustAndroid` cargo-ndk). Ensure the **x86_64** ABI is built for the
      emulator.
- [ ] `adb install -r`; grant `POST_NOTIFICATIONS` via `pm grant`.
- [ ] Provision config per B1:
  - [ ] Drive the setup wizard via `adb` (proven approach: in-app **Generate
        identity**, set broker host, **Validate remote identity** with a host-
        generated `p2pctl` public identity), OR
  - [ ] import a prepared config (B1 Option 1/2).
  - [ ] Reusable helper to type into Compose fields (tap field → `input text`,
        spaces as `%s`; avoid `+`/`/` in pasted identities or set via clipboard).

### B5 — Relay / broker services (compose, per B1/B2)
- [ ] If B2=Option A: `coturn` service with static credentials; expose to host so
      the emulator reaches it via `10.0.2.2:<port>`.
- [ ] If B1=local broker: reuse Phase A `broker` + ensure the emulator can reach it
      (host port-forward; emulator dials `10.0.2.2:8883`); broker cert SAN must
      include the address the emulator uses.
- [ ] Reuse Phase A `answer` + `target`, with the answer authorizing the emulator's
      (host-generated) offer identity.

### B6 — Topology wiring
- [ ] Compose for `broker`/`answer`/`target`(/`coturn`) on the host; emulator on the
      host; map the addresses each side uses (emulator → `10.0.2.2:<ports>`;
      containers → service DNS).
- [ ] Ensure the answer's `allow_remote_peers` includes the emulator's offer peer_id
      and authorized_keys has its public identity.

### B7 — Test driver & assertions
- [ ] **Full path (if B2 solved):**
  - [ ] Start tunnel in-app (wizard Start or `am start ... ACTION_START_OFFER`).
  - [ ] Assert Home reaches **Connected** and the forward shows **Listening**
        (read native status JSON / scrape UI via uiautomator).
  - [ ] From the device, `curl`/HTTP `http://127.0.0.1:8080/<known-path>` (run an
        on-device HTTP client, e.g. via `adb shell` toybox/`UrlConnection` test
        hook) and assert it returns the `target` content → proves Android offer →
        WebRTC → answer → target.
  - [ ] Tap **Stop**; assert status `Stopped` and forward reverts to `Configured`.
- [ ] **B7-smoke (if B2=Option C):** assert Connected + `Listening` + `mqtt_connected`
      against the broker (no end-to-end data), plus Stop reverts. (This is the flow
      already driven manually; script it.)
- [ ] **Per-forward soft-fail (D1 behavior):** add a 2nd forward on a port already
      bound on the device; assert one forward `Listening`, the other `Error`
      (mirrors `bind_offer_listeners_soft_fails_individual_forward` on-device).
- [ ] Capture `adb logcat`, in-app logs, and screenshots on failure.

### B8 — Reliability
- [ ] Generous timeouts + bounded retries around ICE/boot (Android E2E is flaky).
- [ ] Detect & fail fast on known errors (e.g. TLS `UnknownIssuer`, ICE `Failed`).
- [ ] Clean teardown: stop tunnel, uninstall app, kill emulator, `compose down -v`.

### B9 — CI / runner
- [ ] If feasible, a dedicated/self-hosted runner with KVM; otherwise document as a
      **manual/local** job (`tests/e2e/android.sh`).
- [ ] Mark Phase B non-blocking for CI if flakiness is unacceptable; keep Phase A as
      the gating E2E.

### B10 — Phase B acceptance
- [ ] (Full) Android offer establishes a real tunnel to the Linux answer; HTTP
      request on the device returns the target's content.
- [ ] (Smoke) Android reaches Connected + `Listening` against a real broker; Stop
      reverts to Configured.
- [ ] Soft-fail scenario shows mixed `Listening`/`Error` per forward.
- [ ] No secrets committed; emulator/app/containers cleaned up.

---

## Cross-cutting

- [ ] **Secrets hygiene:** all CAs, server certs, identities, authorized_keys are
      generated at test time into a gitignored dir; nothing secret committed.
- [ ] **Determinism:** pin image tags (mosquitto, nginx/httpbin, coturn), AVD/system
      image, and the Rust toolchain.
- [ ] **Docs:** `tests/e2e/README.md` — prerequisites, how to run Phase A and Phase
      B locally, how to read failures, known-flaky notes.
- [ ] **Make targets / scripts:** `tests/e2e/run.sh` (Phase A),
      `tests/e2e/android.sh` (Phase B); both self-cleaning.

## Risks & open items (call out, don't bury)

- [ ] **TURN unsupported** (`p2p-webrtc`) — Phase B full data path likely needs
      either a code change (B2-A) or bridged emulator networking (B2-B). Highest
      risk; decide early.
- [ ] **Android has no `ca_file` UI** — hermetic local broker on Android needs B1
      Option 1/2 or a public broker (B1 Option 3).
- [ ] **Emulator E2E flakiness** — keep Phase B out of the blocking CI gate unless a
      reliable runner exists.
- [ ] **On-device HTTP assertion** — need a way to issue an HTTP request from within
      the device/app context to `127.0.0.1:8080`; settle the mechanism in B7.

## Definition of done

- [x] Phase A: a hermetic, self-cleaning, CI-runnable E2E proves real tunnel data
      over a real TLS broker; green. (Implemented as
      `cargo test -p p2p-daemon --test real_broker_tunnel`, not a `run.sh` — single
      mosquitto container, no compose.)
- [x] Phase B: the scripted Android **smoke** (Connected + `Listening` + Stop) runs
      repeatably (`tests/e2e/android_smoke.sh`); the **full** Android↔Linux data path
      is explicitly **deferred** with the reason recorded (B2: TURN unsupported).
- [x] All assets generated at runtime; no committed secrets; documented in
      `tests/e2e/README.md`.
- [x] Existing `two_node_daemon.rs` and unit suites remain green.
- [ ] (Future) Full Android data-path E2E + per-forward soft-fail on-device — gated
      on resolving B2 (TURN support or bridged emulator networking).
