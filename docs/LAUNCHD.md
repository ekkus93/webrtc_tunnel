# Running as a macOS `launchd` service

`p2p-offer` and `p2p-answer` are ordinary foreground processes — `launchd` is
an optional supervisor, not a requirement. This guide covers installing them
as native macOS system services. Manual execution (`p2p-offer run --config
...`) and Docker both remain fully supported without `launchd`; see the main
[README](../README.md#running-offer-and-answer) and
[Docker/container lifecycle](../README.md#dockercontainer-lifecycle) sections.

## System daemon, not a per-user agent

This guide installs system-wide `LaunchDaemon` definitions under
`/Library/LaunchDaemons`, which run without an interactive user login and are
suitable as an always-on service. Per-user `LaunchAgent` variants are possible
future work and are **not** equivalent — they run only in a logged-in user
session and stop at logout.

## 1. Build and install binaries

```bash
cargo build --release -p p2p-offer -p p2p-answer -p p2pctl
sudo install -m 0755 target/release/p2p-offer /usr/local/bin/p2p-offer
sudo install -m 0755 target/release/p2p-answer /usr/local/bin/p2p-answer
sudo install -m 0755 target/release/p2pctl /usr/local/bin/p2pctl
```

If your installation uses a different absolute executable prefix (e.g. an
Apple Silicon Homebrew layout), update the plist's `ProgramArguments` to
match — `launchd` does not perform a `PATH` lookup, so the path must be exact.

## 2. Service account prerequisite

The baseline plists run as a dedicated unprivileged account, `_p2ptunnel`.
Creating this account safely (correct UID/GID allocation) is currently an
administrator prerequisite; a tested repository helper for this does not yet
exist (see the P1 packaging follow-up). Do not remove `UserName` from the
plist to make it "just work" as root.

## 3. Create directories and permissions

```text
/Library/Application Support/P2PTunnel/offer/
/Library/Application Support/P2PTunnel/answer/
/Library/Logs/P2PTunnel/
```

- `config.toml` / `identity` / `authorized_keys`: owned by root/admin,
  readable by `_p2ptunnel` as required (identity files should not be
  world-readable).
- `state/` and the log directory: writable by `_p2ptunnel`.
- The plist files themselves: `root:wheel`, not group/world-writable.

## 4. Validate configuration before (re)loading

The baseline plist has no `launchd` equivalent of `systemd`'s
`ExecStartPre`, so validate explicitly before every load or config change:

```bash
/usr/local/bin/p2pctl check-config \
  --config "/Library/Application Support/P2PTunnel/offer/config.toml"
/usr/local/bin/p2pctl check-config \
  --config "/Library/Application Support/P2PTunnel/answer/config.toml"
```

Do not wrap the binaries in a `/bin/sh -c` script solely to emulate
`ExecStartPre` — that adds another PID/signal-forwarding layer and weakens the
direct foreground-process model both `systemd` and Docker rely on.

## 5. Install and validate the plists

The plist files live in the repository at
[`packaging/launchd/com.p2ptunnel.offer.plist`](../packaging/launchd/com.p2ptunnel.offer.plist)
and
[`packaging/launchd/com.p2ptunnel.answer.plist`](../packaging/launchd/com.p2ptunnel.answer.plist).

```bash
sudo install -o root -g wheel -m 0644 \
  packaging/launchd/com.p2ptunnel.offer.plist \
  /Library/LaunchDaemons/com.p2ptunnel.offer.plist
sudo install -o root -g wheel -m 0644 \
  packaging/launchd/com.p2ptunnel.answer.plist \
  /Library/LaunchDaemons/com.p2ptunnel.answer.plist

plutil -lint /Library/LaunchDaemons/com.p2ptunnel.offer.plist
plutil -lint /Library/LaunchDaemons/com.p2ptunnel.answer.plist
```

## 6. Load, inspect, restart, and stop

```bash
# Load/bootstrap
sudo launchctl bootstrap system /Library/LaunchDaemons/com.p2ptunnel.offer.plist
sudo launchctl bootstrap system /Library/LaunchDaemons/com.p2ptunnel.answer.plist

# Inspect
sudo launchctl print system/com.p2ptunnel.offer
sudo launchctl print system/com.p2ptunnel.answer

# Restart an already-loaded job
sudo launchctl kickstart -k system/com.p2ptunnel.offer
sudo launchctl kickstart -k system/com.p2ptunnel.answer

# Stop and unload intentionally
sudo launchctl bootout system/com.p2ptunnel.offer
sudo launchctl bootout system/com.p2ptunnel.answer
```

Use `bootout` for an intentional stop, not a vague `launchctl stop`: the
plist's `KeepAlive.SuccessfulExit = false` means a *stopped-but-still-loaded*
keepalive job can be eligible for relaunch, whereas `bootout` removes it from
the service domain entirely, making operator intent explicit.

`launchd` delivers `SIGTERM` on unload/shutdown, which the daemon handles
exactly the same way as `systemd`'s `SIGTERM` or a manual Ctrl-C: it drains
any active session and listeners, writes a final `closed` status, and exits 0.

## 7. Logging and troubleshooting

Keep application-level file logging off and let `launchd` redirect stdout/
stderr (enabling both would duplicate logs):

```toml
[logging]
file_logging = false
stdout_logging = true
```

```bash
tail -F /Library/Logs/P2PTunnel/offer.stdout.log
tail -F /Library/Logs/P2PTunnel/offer.stderr.log
sudo launchctl print system/com.p2ptunnel.offer
```
