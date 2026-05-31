# Replies to Copilot questions

Here are the answers for Copilot. The six questions are all implementation-scope decisions for the UI polish pass.

## 1. MQTT credentials UX shape

Use **both**, but keep the normal Android UX simple:

**Normal flow:**

- Broker host
- Port
- TLS toggle
- Username
- Password field

**Advanced flow:**

- Password file path
- CA/client certificate fields, if supported
- Custom topic prefix

For Android, a normal user should not have to manage a password file path. The app should store the MQTT password in Android app-private storage, ideally encrypted using Android Keystore or the same secure preferences mechanism used elsewhere. If the Rust config currently only supports `password_file`, then the Android app can write the password to an app-private file internally, but the UI should still present it as a password field.

UI labels:

```text
Username, optional
Password, optional
```

Advanced label:

```text
Password file path, optional — Advanced
```

Do **not** show password-file path as the primary normal-flow field.

## 2. Answer mode exposure

For now, show answer mode **disabled in the wizard only**, and optionally mention it in Advanced settings as “not available yet.”

Recommended behavior:

```text
Use this phone as a client (Offer side)
Enabled / selected by default

Use this phone as a server (Answer side) — Advanced
Disabled
Subtitle: Not available in Android v1.
```

Do not fully hide it, because the original product model includes offer and answer modes, and showing it disabled helps explain the architecture. But do not make it startable from Settings or the wizard until the Android answer-mode runtime is actually supported and tested.

In Advanced settings, it can appear as a disabled row:

```text
Answer mode
Not available in Android v1
```

No native answer startup should occur.

## 3. Open URL / Open Browser behavior for non-HTTP forwards

Only show **Open Browser** when the forward is browser-openable.

Use simple protocol inference:

```text
HTTP/browser-openable:
  local port 80, 8080, 8000, 3000, 5000, 5173, 7860, 11434,
  or forward name/id contains http/web/api/llama/ollama

Not browser-openable:
  ssh, tcp, postgres, redis, mqtt, raw custom services
```

Better implementation: add an optional `urlScheme` or `openBehavior` field later. For this UI polish pass, keep it simple.

For HTTP-like forwards:

```text
Open Browser
Copy URL
Test Local Port
```

For SSH/raw TCP forwards:

```text
Copy Address
Test Local Port
```

For SSH, show helper text:

```text
Use this with an SSH client:
127.0.0.1:2223
```

Do not open a browser for SSH or raw TCP forwards.

## 4. “Test Connection” success criteria for MQTT step

For the Setup Wizard MQTT step, **Test Connection should mean authenticated broker connection**, not full tunnel E2E.

Success criteria:

```text
DNS/host resolves
TCP connection succeeds
TLS handshake succeeds if TLS is enabled
MQTT authentication succeeds
Client can connect and disconnect cleanly
```

It should **not** require:

- remote peer online;
- WebRTC session established;
- signaling round-trip;
- forward availability.

Label it clearly:

```text
Test Broker Connection
```

Success message:

```text
Broker connection succeeded.
```

Failure examples:

```text
Could not resolve broker host.
TLS handshake failed.
Authentication failed.
Connection timed out.
```

A stronger signaling test can be added later as a separate action:

```text
Test Signaling with Remote Peer
```

Do not overload the broker test with E2E semantics.

## 5. Non-localhost bind controls

Keep non-localhost bind **hidden behind Advanced**, not exposed by default.

Default and normal UI:

```text
Local host: 127.0.0.1
```

The normal user should not see or edit bind host unless they enable Advanced options.

Advanced behavior:

```text
Allow non-localhost local binds
```

If enabled and the user chooses anything other than `127.0.0.1`, show a blocking warning:

```text
Local Network Exposure Warning

Binding to this address may allow other devices on your network to connect to this forwarded port.

Use 127.0.0.1 unless you specifically need network exposure.

[Cancel]
[I understand]
```

Do not expose `0.0.0.0` in the normal setup path.

## 6. Settings defaults and migration policy

Use these defaults:

```kotlin
startTunnelWhenAppOpens = false
allowMetered = false
resumeOnUnmetered = true
showMeteredWarning = true
debugLogsEnabled = false
advancedSettingsEnabled = false
```

Do **not** reintroduce `pauseOnMetered` unless you want to implement distinct semantics. The current simpler model is better:

```text
allowMetered = false means cellular/metered blocks startup and pauses/stops running tunnel.
resumeOnUnmetered = true means tunnel can resume when Wi-Fi/unmetered returns.
```

Migration policy:

- Existing installs should keep their current `allowMetered` value.
- Existing installs should keep their current `resumeOnUnmetered` value if present.
- If `startTunnelWhenAppOpens` is missing, default to `false`.
- If `showMeteredWarning` is missing, default to `true`.
- If an old `pauseOnMetered` preference exists, ignore it or migrate it into the current model:
  - if `pauseOnMetered = true`, keep `allowMetered = false`;
  - if `pauseOnMetered = false`, do **not** automatically enable `allowMetered`; leave `allowMetered = false` unless the user explicitly opted into metered use.

Do not migrate any old setting in a way that silently enables cellular/metered tunnels.
