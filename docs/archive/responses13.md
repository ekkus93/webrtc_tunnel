# Questions and issues from review of `ANDROID_UI_CODE_REVIEW3.md` and `ANDROID_UI_FIX_TODO3.md`

1. **Temporary metered scope:** For "Allow This Session," should the allowance survive a pause/resume cycle, or be cleared immediately on pause/stop only?

2. **Temporary allowance source of truth:** Should this state live in `TunnelForegroundService` only (preferred for v1), or also be mirrored into repository/runtime state for UI observability?

3. **Review step Save behavior:** After **Save** on the Review step, should the app stay on Review with confirmation, or navigate Home?

4. **Logs layout pattern:** Should the final logs action layout use an overflow menu, or keep all actions visible with a stacked/flow layout?

5. **Manual validation document target:** For manual UI checks and E2E status, should updates be appended to an existing doc, or should we use `docs/ANDROID_VALIDATION.md` for this pass?

6. **Android↔desktop E2E expectation in this pass:** Should I attempt to run it now if environment permits, or mark it as not run unless you provide a dedicated setup window?
