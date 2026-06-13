# Replies to Copilot questions

## 1. TLS CA strategy

Use **Option A** as the primary implementation:

> Make `broker.tls.ca_file` optional and use the platform/system/native root store when it is omitted.

For Android, the generated config **must not** require `/etc/ssl/certs/ca-certificates.crt`. The previous TODO explicitly called out that Android must not assume that Linux path exists and that one acceptable solution is omitting `ca_file` if the Rust TLS stack can use native/root-store behavior.

Implementation guidance:

```toml
[broker.tls]
ca_file = ""
client_cert_file = ""
client_key_file = ""
insecure_skip_verify = false
```

or omit `ca_file` entirely if the Rust config model supports `Option<PathBuf>`.

Rust should treat `ca_file = ""` / missing `ca_file` as:

```text
Use default TLS root verification.
```

Do **not** use Option C as the only path. User-imported CA should be optional advanced behavior later. Do **not** bundle a CA file unless Option A is not technically possible with the current Rust TLS stack.

## 2. Identity startup fallback policy

Use this policy:

> In-memory identity handoff is mandatory for the normal Android runtime path. Temporary plaintext fallback is allowed only as a clearly documented emergency compatibility path, guarded by tests, and must not be the default.

The current goal remains: private identity encrypted at rest, decrypted at startup, and provided to `p2p-mobile` without long-lived plaintext. The previous TODO allowed a temporary compatibility strategy only if necessary, with strict deletion rules:

- under app-private cache/runtime storage,
- private permissions,
- deleted immediately after Rust loads it,
- deleted again on stop/error,
- never included in diagnostics,
- never persisted across restarts.

So Copilot should first fix Rust config loading so `startOfferWithIdentity()` can validate config without requiring a plaintext `paths.identity` file. Only use temp plaintext if that Rust-side fix is impossible within this patch.

## 3. Checklist reset policy

Yes.

> Strictly reset prior checks first. Then re-check only items proven under the new implementation and validation plan.

Even if some items currently pass, uncheck them during Phase 0 unless there is direct current evidence. The earlier checklist was overly optimistic; it marked validation and acceptance items complete even though TODO rules say not to mark items complete unless implemented and tested.

Use this rule:

```text
Unchecked = not yet revalidated under ANDROID_FIX_SPEC_2 / TODO_2.
Checked = implemented, tested, and documented with command/output or test name.
```

## 4. Authorized key format authority

Use the **desktop Rust parser format as the single source of truth**.

Do not invent a second Android-only public identity format. Android import should accept exactly what desktop Rust accepts.

A small normalization path is okay only for harmless whitespace:

```text
trim leading/trailing whitespace
ignore empty trailing newline
normalize CRLF to LF
```

But reject semantically different formats. Do not accept partial identity fragments such as only `peer_id`. The TODO requires protocol compatibility and preserving authorized key semantics.

Required behavior:

```text
Import remote public identity
→ pass through the same Rust parser/validator used by desktop
→ store canonical rendered format in filesDir/authorized_keys
```

## 5. Network type `Unknown` behavior

`Unknown` must remain blocked.

Even when the user enables metered/cellular, `Unknown` should not be treated as allowed. The prior TODO explicitly says “Unknown must fail safe.”

Policy:

```text
Unmetered Wi-Fi: allowed
Metered Wi-Fi: allowed only if allowMetered = true
Cellular: allowed only if allowMetered = true
No network: blocked
Unknown: blocked always
```

Reason: if Android cannot classify the network, the app cannot honestly enforce the “no cellular/metered unless explicitly enabled” safety rule.

## 6. Private export security gate

For this patch, an explicit warning/confirmation dialog is sufficient.

Biometric or device-unlock gating is optional, not mandatory for this pass. The previous TODO listed device unlock / biometric as optional.

Required:

```text
Private Identity Export Warning

Anyone with this file can impersonate this phone in your tunnel network.

Only export it if you understand the risk.

[Cancel]
[Export Private Identity]
```

The export action must not happen unless the user explicitly confirms this warning.

Do not hide export behind a generic checkbox only. Use a real modal confirmation flow.

## 7. Phase 14 end-to-end validation ownership

Copilot should execute and document Android↔desktop validation directly in-repo whenever the environment permits.

If the local environment blocks completion, Copilot must add runnable steps and clearly mark the E2E validation item incomplete. Do not check it off based only on theoretical correctness.

The previous TODO requires documenting exact E2E steps/results, including desktop command, Android config summary, network type, result, and errors. It also says failed or unavailable validation must remain unchecked and documented.

Correct behavior:

```text
If emulator/device + desktop answer are available:
  run the E2E test and document results.

If unavailable:
  add docs/ANDROID_VALIDATION.md with exact commands and “NOT RUN: reason”.
  leave Phase 14 E2E acceptance unchecked.
```

## 8. Phase 0 audit scope confirmation

Yes.

`ANDROID_WEBRTC_TUNNEL_TODO.md` is intentionally in scope for the initial audit, along with `ANDROID_FIX_TODO1.md`.

Reason: `ANDROID_FIX_TODO1.md` specifically instructed auditing `ANDROID_WEBRTC_TUNNEL_TODO.md` and unchecking or annotating claims that were not truly complete, especially Android offer connectivity, localhost browser forwarding, encrypted identity use, service-enforced network policy, setup wizard, import/export, and validation commands.

So Phase 0 should audit:

```text
ANDROID_WEBRTC_TUNNEL_TODO.md
ANDROID_FIX_TODO1.md
ANDROID_FIX_TODO_2.md
docs/memory.md
docs/ANDROID_VALIDATION.md, if present
```

## Extra clarification for Copilot

The priority order should be:

1. Fix Rust config compatibility with Android-generated config.
2. Fix `startOfferWithIdentity()` so it does not require plaintext `paths.identity`.
3. Add Rust-backed validation tests for Android-generated config.
4. Fix identity import/public identity canonicalization.
5. Fix forwards source-of-truth so UI changes update the actual runtime config.
6. Only then work on setup wizard polish and UI completeness.

Do not spend time checking off UI polish while the Android runtime startup path can still fail before the tunnel starts.
