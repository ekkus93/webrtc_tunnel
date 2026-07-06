# Debian/Ubuntu packaging

This is a P2/"possible future work" packaging pass: a working `.deb` for
Debian- and Ubuntu-family hosts, built by hand-assembling the package tree
and invoking `dpkg-deb` directly, rather than a full `debhelper`/`dh-cargo`
pipeline. It does not target full Debian Policy / archive-quality compliance
(see "Known limitations" below).

## Build

```bash
scripts/build-deb.sh [output-dir]   # defaults to target/debian/
```

Builds release binaries first if missing (`cargo build --release -p
p2p-offer -p p2p-answer -p p2pctl`), strips them, assembles the package tree
from the templates in `packaging/debian/`, and runs `dpkg-deb --build` via
`fakeroot`. Prints the package contents (`dpkg -c`) and, if `lintian` is
installed, an informational lint report.

## What it installs

```text
/usr/bin/p2p-offer
/usr/bin/p2p-answer
/usr/bin/p2pctl
/lib/systemd/system/p2p-offer.service
/lib/systemd/system/p2p-answer.service
/usr/share/doc/p2ptunnel/{README.md,SYSTEMD.md,copyright,changelog.gz}
```

It does **not** install a default `config.toml`, identity, or
`authorized_keys` — see [`docs/SYSTEMD.md`](SYSTEMD.md) for populating those
after install.

## Maintainer script behavior

- **postinst**: idempotently creates the `p2ptunnel` system user/group and
  `/etc/p2ptunnel/{offer,answer}`, `/var/lib/p2ptunnel-*`,
  `/var/log/p2ptunnel-*` directories if they don't already exist, then runs
  `systemctl daemon-reload` if systemd is present. Never touches an existing
  config directory's contents (so upgrades preserve your config/identity
  untouched) and never enables/starts a service on first install. On an
  **upgrade**, it `try-restart`s each of `p2p-offer.service`/
  `p2p-answer.service` that was already active, so the upgrade actually picks
  up the new binary instead of leaving the old process running until someone
  notices and restarts it by hand; `try-restart` only touches units that were
  already active, so a first install never gets auto-started here.
- **prerm**: stops `p2p-offer.service`/`p2p-answer.service` cleanly (a normal
  `systemctl stop`, which the daemons handle as graceful shutdown) — but only
  on `remove`/`deconfigure`, i.e. when the package is actually going away.
  Deliberately **not** on upgrade: stopping here would take the tunnel down
  for the whole duration of the upgrade, when postinst's try-restart above
  already brings a previously-active service back up on the new binary
  afterward.
- **postrm**: on both ordinary `remove` and `purge`, runs
  `systemctl daemon-reload` — the package's own unit files under
  `/lib/systemd/system/` are already gone by the time postrm runs (removed as
  part of the package), so without this, systemd keeps stale in-memory unit
  state around after a plain `remove`. On ordinary `remove`, config, state,
  logs, and the service account are otherwise all left in place. Only `purge`
  removes `/etc/p2ptunnel/*`, `/var/lib/p2ptunnel-*`, and
  `/var/log/p2ptunnel-*` (including any private identity files under
  `/etc/p2ptunnel/*`). The service account is deliberately **not** removed
  even on purge, to avoid a freed system UID/GID being reused later.

Verified for real via `scripts/test-debian-package.sh` (also wired into CI):
builds the real `.deb`, extracts it to confirm every packaged unit's
`ExecStart(Pre)=` path resolves to a real installed binary, then drives
postinst/prerm/postrm through fresh-install, upgrade (with a unit already
active), `prerm upgrade` (confirms nothing is stopped), `prerm remove`
(confirms active units are stopped), `postrm remove`, and `postrm purge` in a
throwaway `debian:bookworm-slim` container — asserting the exact
try-restart/stop/daemon-reload behavior described above at each step, not
just that the commands run without error.

## Known limitations

This intentionally stops short of full Debian Policy / archive-quality
packaging:

- No man pages (`lintian` flags `no-manual-page` for all three binaries).
- Maintainer scripts call `systemctl`/`adduser` directly rather than through
  `dh_installsystemd`/`dh_installsysuser` helpers (`lintian` flags
  `maintainer-script-calls-systemctl`).
- No offline/vendored build — `scripts/build-deb.sh` assumes `cargo build
  --release` can reach crates.io, unlike a real Debian archive build.
- Single-architecture (`dpkg --print-architecture` on the build host) — no
  cross-building or multi-arch packaging.

None of these block using the package for local/private deployment; they
would need addressing before this could go through actual Debian/Ubuntu
archive review.
