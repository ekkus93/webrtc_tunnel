# Questions and issues from review of `ANDROID_UI_POLISH_SPEC.md` and `ANDROID_UI_POLISH_TODO.md`

1. **MQTT credentials UX shape:** Should Android support a direct password field, password-file path only, or both in the normal flow?

2. **Answer mode exposure:** Should answer mode be fully hidden for now, shown as disabled in the wizard only, or also surfaced in Advanced settings?

3. **Open URL/Open Browser behavior for non-HTTP forwards:** How should UI actions behave when a forward is not browser-openable (for example, SSH)?

4. **"Test Connection" success criteria (MQTT step):** Should success mean broker reachability only, authenticated connect only, or a stronger end-to-end signaling check?

5. **Non-localhost bind controls:** Should non-localhost bind remain hidden behind a developer-only toggle, or be exposed in Advanced by default?

6. **Settings defaults and migration policy:** What are the expected defaults for "start when app opens" and "resume on Wi-Fi return," and how should existing installs be migrated?
