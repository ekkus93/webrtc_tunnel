# Questions and issues from review of `ANDROID_FIX_SPEC_2.md` and `ANDROID_FIX_TODO_2.md`

1. **TLS CA strategy (required decision):** Should implementation use **Option A** (optional `broker.tls.ca_file` + system/native roots), **Option B** (bundled CA file), or **Option C** (user-imported CA only)?

2. **Identity startup fallback policy:** If in-memory identity handoff cannot be used in a specific flow, should temporary plaintext file fallback be allowed, or should that be treated as blocked and escalated for review?

3. **Checklist reset policy in Phase 0:** `ANDROID_FIX_TODO_2.md` requires checklist honesty and unchecking previously claimed items. Should I strictly reset prior checks first and only re-check under this new plan, even if some currently pass?

4. **Authorized key format authority:** Should the desktop Rust parser format be treated as the single accepted format (reject all others), or should there be a migration/normalization path for older pasted variants?

5. **Network type `Unknown` behavior:** When user enables metered/cellular, should `Unknown` be considered allowed too, or remain blocked?

6. **Private export security gate:** Is an explicit warning/confirmation dialog sufficient, or must private export also require biometric or device-unlock gating?

7. **Phase 14 end-to-end validation ownership:** Should I execute and document Android↔desktop validation directly in-repo whenever environment permits, or provide runnable steps and wait for your externally collected artifacts when environment constraints block local completion?

8. **Phase-0 audit scope confirmation:** `ANDROID_FIX_TODO_2.md` references `ANDROID_WEBRTC_TUNNEL_TODO.md`. Confirm that file is intentionally in scope for the initial audit along with `ANDROID_FIX_TODO1.md`.
