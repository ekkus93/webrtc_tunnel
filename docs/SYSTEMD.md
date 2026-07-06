# Running as a Linux `systemd` service

`p2p-offer` and `p2p-answer` are ordinary foreground processes — `systemd` is an
optional supervisor, not a requirement. This guide covers installing them as
native Linux services. Manual execution (`p2p-offer run --config ...`) and
Docker both remain fully supported without `systemd`; see the main
[README](../README.md#running-offer-and-answer) and
[Docker/container lifecycle](../README.md#dockercontainer-lifecycle) sections.

## 1. Build and install binaries

```bash
cargo build --release -p p2p-offer -p p2p-answer -p p2pctl
sudo install -m 0755 target/release/p2p-offer /usr/local/bin/p2p-offer
sudo install -m 0755 target/release/p2p-answer /usr/local/bin/p2p-answer
sudo install -m 0755 target/release/p2pctl /usr/local/bin/p2pctl
```

## 2. Create the service account

```bash
sudo useradd --system --home /nonexistent --shell /usr/sbin/nologin p2ptunnel
```

Distro package managers may offer their own idiomatic way to create a system
account; the important properties are: no login shell, no home directory
dependency, and not root.

## 3. Create config directories

```bash
sudo install -d -m 0750 -o root -g p2ptunnel /etc/p2ptunnel/offer
sudo install -d -m 0750 -o root -g p2ptunnel /etc/p2ptunnel/answer
```

Place `config.toml`, `identity`, `authorized_keys`, and (if used)
`mqtt_password` under each role's directory. Use absolute paths in these
configs — do not rely on `~/` expansion or a particular `HOME` for a system
service.

Example `[paths]` block:

```toml
[paths]
identity = "/etc/p2ptunnel/offer/identity"
authorized_keys = "/etc/p2ptunnel/offer/authorized_keys"
state_dir = "/var/lib/p2ptunnel-offer"
log_dir = "/var/log/p2ptunnel-offer"
```

(swap `offer` for `answer` in the answer-side config).

### Identity file permissions

The private `identity` file must satisfy the daemon's own
`refuse_world_readable_identity` check and be readable by the `p2ptunnel`
service user — for example `chown root:p2ptunnel` with mode `0640`.

## 4. Prefer journald over file logging

```toml
[logging]
level = "info"
format = "json"
file_logging = false
stdout_logging = true
log_file = "/var/log/p2ptunnel-offer/p2ptunnel.log"
redact_secrets = true
redact_sdp = true
redact_candidates = true
log_rotation = "none"
```

`log_file` is still required by the config schema but is never opened when
`file_logging = false` — `journald` collects stdout/stderr instead, avoiding an
unbounded application-managed log file.

## 5. Install and enable the units

The unit files live in the repository at
[`packaging/systemd/p2p-offer.service`](../packaging/systemd/p2p-offer.service)
and
[`packaging/systemd/p2p-answer.service`](../packaging/systemd/p2p-answer.service).

```bash
sudo install -m 0644 packaging/systemd/p2p-offer.service /etc/systemd/system/
sudo install -m 0644 packaging/systemd/p2p-answer.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now p2p-offer.service
sudo systemctl enable --now p2p-answer.service
```

Steps 2–5 above (service account, config directories, unit install, and
`daemon-reload`) can also be run via
[`scripts/install-systemd-services.sh`](../scripts/install-systemd-services.sh):

```bash
sudo scripts/install-systemd-services.sh          # installs only; does not enable/start
sudo scripts/install-systemd-services.sh --enable # also runs enable --now for both units
```

It never overwrites an existing config directory or its contents, and refuses
to run as non-root or on a non-Linux/non-systemd host.

Each unit runs `p2pctl check-config` as `ExecStartPre`, so a broken config
fails the service start immediately rather than starting a daemon that will
immediately error.

## 6. Stop, restart, and inspect

```bash
sudo systemctl stop p2p-offer.service
sudo systemctl restart p2p-offer.service
sudo systemctl status p2p-offer.service
```

`systemctl stop` sends `SIGTERM`; the daemon drains its active session (if
any) and listeners, writes a final `closed` status, and exits 0 within the
unit's `TimeoutStopSec=30s`. A normal stop does not trigger `Restart=on-failure`
— only a nonzero exit does.

## 7. Logs and troubleshooting

```bash
journalctl -u p2p-offer.service
journalctl -u p2p-offer.service -f
journalctl -u p2p-offer.service -b
/usr/local/bin/p2pctl check-config --config /etc/p2ptunnel/offer/config.toml
```

## Hardening caveat

The baseline units enable a conservative sandboxing profile
(`ProtectSystem=strict`, `NoNewPrivileges`, etc.). Do not add further
restrictions such as `PrivateNetwork=true`, a restrictive
`RestrictAddressFamilies=`, or `IPAddressDeny=any` without testing that
interface discovery, DNS, MQTT TLS, STUN, ICE host candidates, and the local
offer listeners still work under them.

## Running multiple instances (templated units)

If one host needs more than one offer or answer daemon (e.g. tunnels to
several different remote peers), use the templated units instead of the
plain ones:
[`packaging/systemd/p2p-offer@.service`](../packaging/systemd/p2p-offer@.service)
and
[`packaging/systemd/p2p-answer@.service`](../packaging/systemd/p2p-answer@.service).

Each instance is named after the `%i` specifier and reads its own config from
a per-instance subdirectory:

```text
/etc/p2ptunnel/offer/<instance>/config.toml
/etc/p2ptunnel/answer/<instance>/config.toml
```

with per-instance state/log directories (`/var/lib/p2ptunnel-offer-<instance>`,
`/var/log/p2ptunnel-offer-<instance>`) so instances never collide.

```bash
sudo install -m 0644 packaging/systemd/p2p-offer@.service /etc/systemd/system/
sudo install -m 0644 packaging/systemd/p2p-answer@.service /etc/systemd/system/
sudo systemctl daemon-reload

sudo install -d -m 0750 -o root -g p2ptunnel /etc/p2ptunnel/offer/home
# ... populate /etc/p2ptunnel/offer/home/{config.toml,identity,authorized_keys} ...

sudo systemctl enable --now p2p-offer@home.service
sudo systemctl status p2p-offer@home.service
journalctl -u p2p-offer@home.service -f
```

Each instance is an independent unit (`p2p-offer@home.service`,
`p2p-offer@office.service`, ...) with its own `enable`/`start`/`stop`/`status`
lifecycle; stopping one does not affect the others. The install helper script
(`scripts/install-systemd-services.sh`) only installs the non-templated
`p2p-offer.service`/`p2p-answer.service` pair today — for multiple instances,
install the `@.service` templates and per-instance directories manually as
shown above.

## `Type=notify` readiness: not shipped

`p2p-offer`/`p2p-answer` never touch `systemd` — no dependency, no linking, no
`sd_notify` calls at runtime — and the only supported unit type is
`Type=simple`, which `systemd` considers "started" as soon as the process
exists. An earlier optional `sd_notify`/`Type=notify` integration was removed:
it sent `READY=1` right after startup but before the daemon future was ever
polled, so it could report ready before MQTT subscription, peer authorization,
or listener binding actually completed — false readiness. `Type=notify` is
intentionally not shipped until the daemon core exposes a genuine
supervisor-neutral readiness event tied to an actual runtime milestone (MQTT
subscribed, listeners bound, accept workers started).
