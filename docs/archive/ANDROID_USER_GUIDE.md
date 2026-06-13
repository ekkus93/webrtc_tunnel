# Android user guide (v0.3 experimental)

The Android app is an **offer/client-first** control panel for the Rust tunnel runtime.

## Security model

- MQTT remains untrusted transport; signaling is encrypted/signed by Rust core logic.
- Private identity is encrypted at rest as `identity.enc`.
- The encryption key is generated and protected by Android Keystore.
- Logs and diagnostics are redacted; secrets, SDP, and ICE candidates are not exposed in normal logs.
- Rooted/compromised devices are out of scope.
- Metered/cellular usage is blocked by default and requires explicit user opt-in with warning.

## First-time setup

1. Open the app and run the setup wizard.
2. Choose mode (**Offer/Client** recommended for v0.3 mobile flow).
3. Generate or import identity.
4. Configure MQTT broker settings.
5. Configure remote peer identity and forward mappings.
6. Confirm network policy (metered off by default).
7. Save and start tunnel.

## Main screens

- **Home**: tunnel/service state, network state, start/stop controls.
- **Forwards**: configured forwards and runtime state.
- **Logs**: redacted operational logs and diagnostics export.
- **Settings**: setup rerun, config import/export, identity actions, diagnostics.

## Import/export

- Config import/export supports `config.toml`.
- Public identity can be copied/shared.
- Private identity export uses an explicit warning flow.
- Android-specific preferences are stored separately from tunnel protocol config.

## Foreground service behavior

- Tunnel runtime runs in `TunnelForegroundService`.
- Persistent notification reflects state and supports stop/open actions.
- Notification behavior honors Android runtime notification permission requirements.
