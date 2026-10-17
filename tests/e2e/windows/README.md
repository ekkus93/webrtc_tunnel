# Windows E2E harnesses

## `mqtts-loopback.ps1` — real `mqtts://` signaling on Windows (P1-001)

A manual, single-machine proof that a Windows offer↔answer pair completes signaling
over a real MQTT-over-TLS broker and passes data:

```
client -> p2p-offer listener -> WebRTC -> p2p-answer -> echo target -> back
```

The script generates a throwaway CA and broker cert (openssl), starts a native
mosquitto with a TLS listener on a free `127.0.0.1` port, creates two identities with
`p2pctl keygen` (each against its own throwaway `%USERPROFILE%`) and cross-authorizes
them with `p2pctl add-authorized-key`, writes offer/answer configs from
`docs/examples/*-config.toml` with one forward to a local echo server, validates them
with `p2pctl check-config`, starts both daemons, round-trips a payload through the
tunnel, and tears everything down. It exits `0` on success.

Everything binds to loopback, so Windows Firewall should not prompt.

### Prerequisites

- mosquitto: the upstream Windows installer from
  <https://mosquitto.org/files/binary/win64/> (there is no Chocolatey package). It
  installs to `C:\Program Files\mosquitto`, which the script finds without a PATH
  change; `/S` installs it silently. Only the executable is used; the
  installer's `mosquitto` Windows service on port 1883 is not needed and can stay
  stopped.
- openssl: on `PATH`, or the one bundled with Git for Windows
  (`C:\Program Files\Git\mingw64\bin\openssl.exe`), which the script also checks.
- Built debug binaries. The script runs `target\debug\*.exe` directly rather than via
  `cargo run`, because it changes `USERPROFILE` for `p2pctl`, which would break a
  rustup-proxied cargo.

### Run

From the repo root:

```powershell
cargo build -p p2p-offer -p p2p-answer -p p2pctl
powershell -NoProfile -ExecutionPolicy Bypass -File tests\e2e\windows\mqtts-loopback.ps1
```

Options: `-MosquittoBin <path>` (or `P2P_MOSQUITTO_BIN`), `-OpenSslBin <path>`,
`-TargetDir <dir>` (e.g. `target\release`), `-TimeoutSeconds <n>` (default 60), and
`-KeepWorkDir` to keep the certs, configs and logs after a passing run. A failing run
always keeps its work directory and prints its path along with the tail of each
process's stderr.

### Relationship to the automated test

`crates/p2p-daemon/tests/native_broker_tunnel.rs` (P2-003) covers the same broker path
in-process — it calls `run_offer_daemon` / `run_answer_daemon` directly and generates
its certs with rcgen — and runs in CI's `test-windows` job. This script is the
real-process complement: the shipped executables, `p2pctl` key management, and TOML
configs with Windows paths, which the in-process test does not exercise.
