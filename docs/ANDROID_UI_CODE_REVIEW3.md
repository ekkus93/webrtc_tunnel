# ANDROID_UI_CODE_REVIEW3.md

# Android WebRTC Tunnel UI Code Review 3 — Final UI Cleanup Review

## 1. Review scope

This review covers the remaining Android UI/UX issues after the second UI polish hardening pass.

The current Android app is close to the target design. This review is intentionally narrow. It does **not** propose a new architecture, protocol change, Rust rewrite, or Android project restructuring.

This review focuses on:

1. correcting the remaining metered/cellular "Allow Temporarily" semantics,
2. removing duplicate Review-step controls,
3. finishing scrollability / large-font safety,
4. tightening the Logs action layout,
5. making the final test/validation status honest,
6. documenting remaining mockup differences and E2E status.

## 2. Current status summary

The Android UI is much improved and now broadly matches the desired Material-style mockup:

- light card-based visual style,
- navy app bars,
- Material 3 components,
- Home / Forwards / Logs / Settings bottom tabs,
- secondary flows with back navigation,
- real Setup Wizard,
- Home forwards summary uses configured forwards,
- private identity export warning path mostly fixed,
- metered/cellular warning path mostly fixed,
- Setup Wizard `canAdvance` moved into ViewModel state,
- Forward Details exists,
- Import/Export uses Android document picker flows,
- Settings is more complete,
- redaction behavior is preserved.

However, a few issues remain before the UI polish pass should be called done.

## 3. High-priority remaining issues

## P0 — "Allow Temporarily" is not temporary

### Problem

The Home screen action labeled:

```text
Allow Temporarily
```

appears to persist:

```kotlin
allowMetered = true
```

to DataStore.

That means a user action that sounds temporary may permanently enable cellular/metered tunnel usage.

### Why this matters

Cellular/metered tunnel usage can consume large amounts of data. The app's UX must not mislead the user about whether they are enabling metered use temporarily or permanently.

### Required fix

Choose one of the following:

#### Option A — Implement true temporary allowance

Preferred.

The Home action should allow metered use only for the current tunnel run/session or a specific duration.

Examples:

```text
Allow this session
Allow for 15 minutes
Allow until tunnel stops
```

Temporary allowance must not persist `allowMetered = true` in DataStore.

Store temporary allowance in one of:

- ForegroundService in-memory state,
- repository/runtime session state,
- a DataStore field with expiration timestamp, if duration-based.

#### Option B — Rename the action if it is permanent

If the implementation persists `allowMetered = true`, then the button must not say "Allow Temporarily."

Use:

```text
Allow Metered Data
```

or route the user to Settings.

Recommended v1 choice: **Option A** with "Allow This Session."

## P1 — Duplicate Review-step Save/Start controls

### Problem

The Setup Wizard Review step may display Save/Start actions in both:

1. the wizard shell bottom action row, and
2. the `ReviewStepContent` itself.

This creates duplicate controls and confusing behavior.

### Required fix

Use one clear completion model.

Recommended:

```text
Bottom row:
  Back | Save | Start Tunnel
```

The Review content should show summary cards only, not duplicate Save/Start buttons.

Action semantics:

```text
Save:
  saves configuration and returns Home or remains on Review with saved confirmation

Start Tunnel:
  saves configuration
  validates identity/config/network
  starts ForegroundService
  navigates Home
```

Labels must exactly match behavior.

## P1 — Home and Forwards still need better scrollability

### Problem

Most long screens now scroll, but Home and Forwards may still use fixed `ScreenSurface` patterns.

Home can exceed screen height with:

- status card,
- network card,
- forwards summary,
- error card,
- action row,
- notification permission messages,
- large system font.

Forwards can also become cramped with many forwards and large text.

### Required fix

Make Home and Forwards phone-safe:

- use `LazyColumn` or vertical scroll,
- preserve clear spacing,
- keep primary action buttons reachable,
- test with small viewport and large system font.

Home should remain dashboard-like but must not clip.

## P1 — Logs actions can still be crowded

### Problem

The Logs screen was improved from one overcrowded row to multiple rows, but there may still be too many full-width text buttons in a single horizontal row.

### Required fix

Use one of:

- `FlowRow`,
- overflow menu,
- two-button primary row plus secondary overflow,
- stacked full-width actions.

Recommended:

```text
Row:
  Pause Logs | Clear Logs

Row:
  Copy Logs | Export

Overflow or separate:
  Share Diagnostics
```

Avoid three long labels in one phone-width row.

## P1 — Test coverage is still overstated

### Problem

The TODO may mark UI tests complete for warning flows, Home configured forwards, composition correctness, and wizard behavior, but the code review did not find complete evidence for all of these.

### Required fix

Add or verify tests for the most important remaining risks:

- Home "Allow This Session" does not persist permanent metered allowance,
- private identity export warning must appear before SAF export,
- Settings metered toggle warning,
- Wizard metered toggle warning,
- Home configured forwards in stopped/connected/paused states,
- Setup Wizard `canAdvance` comes from ViewModel state,
- no repository/native validation on recomposition,
- Review step has only one Save/Start control set.

If a test is not practical, document the manual test performed.

## P1 — Manual Android↔desktop E2E remains not run

### Problem

The real product acceptance test is:

```text
desktop p2p-answer
Android p2p-offer
Android browser -> http://127.0.0.1:<port>
remote service responds
```

If this is still not run, the project should not claim full product acceptance.

### Required fix

Run and document the E2E test, or mark it explicitly as not run.

This is not necessarily a UI-blocker, but it is a merge-readiness blocker.

## 4. Lower-priority polish issues

### P2 — Advanced Settings is only partially collapsed

The advanced controls may be hidden behind a switch, but the Advanced section itself remains visible.

Acceptable for v1, but better UX is an expandable/collapsed card:

```text
Advanced  >
```

Tap expands to show debug/raw-path controls.

### P2 — EditForwardDialog title may be wrong for Add vs Edit

If the same dialog is used for add/edit, pass an explicit `isNew` flag.

Use:

```text
Add Forward
```

for new forwards and:

```text
Edit Forward
```

for existing forwards.

### P2 — Content descriptions can be more contextual

Replace generic descriptions:

```text
Copy URL
Open browser
Delete
```

with contextual descriptions:

```text
Copy llama local URL
Open llama local URL in browser
Delete forward llama
```

### P2 — Mockup differences should be documented

Some differences from `android_screens.png` are acceptable, but they should be documented:

- exact stepper styling differs from mockup,
- some metric fields may be absent because runtime does not expose them,
- Settings may be denser than mockup,
- Logs actions may use overflow rather than visible row.

## 5. What should not change in this pass

Do not change:

- MQTT signaling wire format,
- tunnel frame format,
- desktop Rust protocol semantics,
- STUN/TURN policy,
- VPN/TUN scope,
- Android Keystore identity-at-rest design,
- network policy safety design,
- log/diagnostic redaction design,
- offer-side `forward_id` model.

This is a UI cleanup pass.

## 6. Recommended fix order

1. Fix Home "Allow Temporarily" semantics.
2. Remove duplicate Review-step Save/Start controls.
3. Make Home and Forwards scrollable/large-font safe.
4. Tighten Logs action layout.
5. Add/verify targeted UI/ViewModel tests.
6. Document manual mockup differences.
7. Run Android/Rust validation.
8. Run or explicitly defer Android↔desktop browser E2E.

## 7. Bottom line

The Android UI is close. The remaining issues are small but important.

The top issue is semantic correctness: a temporary-looking cellular/metered allowance must not permanently enable metered use. The second issue is finishing the polish so the wizard and long screens behave well on real phones.

After these fixes, the Android UI polish pass should be ready to sign off, pending actual E2E compatibility testing.
