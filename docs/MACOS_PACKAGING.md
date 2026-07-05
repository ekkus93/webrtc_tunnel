# macOS installer package (`.pkg`)

**Scope note:** this is P2/"possible future work" packaging scaffolding,
written and committed from a Linux development environment with no access to
`pkgbuild`, `productbuild`, `plutil`, code-signing tooling, or a real macOS
host. `scripts/build-macos-pkg.sh` and the two installer scripts have been
syntax-checked (`bash -n` / `sh -n`) and reviewed, but **have not been run or
verified end-to-end on real macOS** by whoever wrote this pass. Before
relying on this, actually run it — either on a real Mac, or via the
project's `macos-latest` CI runner (see `.github/workflows/ci.yml`) — and
verify the resulting `.pkg` installs, starts the services, and uninstalls
cleanly.

This deliberately mirrors the same design already documented and verified
for [`docs/SYSTEMD.md`](SYSTEMD.md), [`docs/LAUNCHD.md`](LAUNCHD.md), and
[`docs/DEBIAN_PACKAGING.md`](DEBIAN_PACKAGING.md): the same unmodified
foreground `p2p-offer`/`p2p-answer` binaries, the same `LaunchDaemon` plists
already shipped under `packaging/launchd/`, no daemonization, no auto-start.

## Build

```bash
scripts/build-macos-pkg.sh [output-dir]   # defaults to target/macos-pkg/
```

Builds release binaries if missing, assembles a payload
(`/usr/local/bin/{p2p-offer,p2p-answer,p2pctl}` and the two `LaunchDaemon`
plists under `/Library/LaunchDaemons/`), lints the plists with `plutil` if
available, and runs `pkgbuild` to produce a single **component package**
(no `productbuild` distribution/installer-UI wrapper — that's a reasonable
next step, not included here) at `target/macos-pkg/p2ptunnel-<version>.pkg`.

The output is **unsigned**. Real distribution requires, in order:

```bash
productsign --sign "Developer ID Installer: <your name/org> (<TEAMID>)" \
  target/macos-pkg/p2ptunnel-<version>.pkg signed.pkg
xcrun notarytool submit signed.pkg --keychain-profile <profile> --wait
xcrun stapler staple signed.pkg
```

This needs an active Apple Developer Program membership and a Developer ID
Installer certificate — neither of which this scaffolding can create or
verify. Building unsigned locally is fine for testing on your own machine
(with Gatekeeper warnings); shipping to other people's Macs needs the full
sign+notarize flow above.

## What it installs

```text
/usr/local/bin/p2p-offer
/usr/local/bin/p2p-answer
/usr/local/bin/p2pctl
/Library/LaunchDaemons/com.p2ptunnel.offer.plist
/Library/LaunchDaemons/com.p2ptunnel.answer.plist
```

It does **not** install a default `config.toml`, identity, or
`authorized_keys` — see [`docs/LAUNCHD.md`](LAUNCHD.md) for populating those
after install, and does **not** load (bootstrap) the `LaunchDaemons`.

## Installer script behavior

- **preinstall**: refuses to proceed unless the `_p2ptunnel` service account
  already exists. Creating that account safely (correct, non-colliding
  UID/GID allocation via `sysadminctl`/`dscl`) is treated as an
  administrator prerequisite here, for the same reason
  `scripts/install-launchd-services.sh` treats it that way: getting this
  wrong is a real security footgun, and there is no way to verify
  auto-creation logic without a real macOS host to test it against.
- **postinstall**: idempotently creates
  `/Library/Application Support/P2PTunnel/{offer,answer}` and
  `/Library/Logs/P2PTunnel` if they don't already exist (never touches an
  existing config directory's contents), then prints next steps. Does not
  create config/identity/authorized_keys and does not bootstrap the
  `LaunchDaemons`.

## Known limitations / explicitly deferred

- **Not built, installed, or uninstalled on real macOS as part of this
  change** — see the scope note above. Treat this as reviewed-but-unverified
  scaffolding until someone runs it for real.
- No `productbuild` distribution package (installer welcome/license UI,
  multiple sub-packages) — only a single `pkgbuild` component package.
- No code signing or notarization — see the commands above; both require
  credentials this environment cannot have.
- No automated `_p2ptunnel` account creation — administrator prerequisite,
  matching `docs/LAUNCHD.md`'s existing stance.
- No uninstaller package — macOS `.pkg` has no first-class uninstall
  story; removal is manual (`launchctl bootout`, then `rm` the binaries/
  plists/directories), same as it already is for the manually-installed
  `LaunchDaemon` path in `docs/LAUNCHD.md`.
