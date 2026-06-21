# Android UI/UX Improvements (Round 3) — TODO

Source: full UI/UX review of the Android app (Compose screens, setup wizard, foreground
service / notification, and viewmodels). Findings below are the consolidated, de-duplicated
result of that review. Line references are accurate at time of writing but may drift; the
component/function names are the stable anchors.

Priority scale:

- `P0`: correctness bug or silent data loss / misleading state. Fix first.
- `P1`: high-value UX gap that affects every-session usability.
- `P2`: polish / maintainability / accessibility / localization.
- `P3`: optional refinement.

Standing constraints (from `CLAUDE.md`): work on `master`; no AI attribution trailers;
**fix lint, never suppress**; Android must pass `ktlintCheck` + `detekt` + `lintDebug` with
zero findings; Rust must pass `cargo fmt --check` + `cargo clippy`. Run
`cd android && ./gradlew testDebugUnitTest` and the relevant Rust tests per change.

Cross-cutting themes (each expanded into tasks below):

1. Dynamic lists lack stable `key`s → recomposition correctness/perf.
2. No user feedback after actions (save/copy/import/export/start/stop/toggles).
3. Destructive / config-replacing actions lack confirmation.
4. Expert jargon leaks onto primary surfaces (Home, notification).
5. Validation is on-submit, not inline / per-field.
6. User-facing strings are hardcoded (blocks i18n, scatters copy).
7. Accessibility gaps (content descriptions, error semantics, contrast, dark mode).

---

## P0 tasks

### P0-001 — Add stable `key`s to all dynamic lists

Files:

- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/LogsScreen.kt` (`LogList`, ~line 211)
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/ForwardsScreen.kt` (`items(forwards)`, ~line 81)
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/HomeCards.kt` (`configuredForwards.forEach`)

Problem:

`LazyColumn` `items()` and the Home `forEach` provide no `key`, so Compose tracks rows by
position. On the logs screen (auto-refresh every 2s) this forces avoidable recomposition of
unchanged rows; on the forwards list, add/delete/reorder can attach per-row state (and click
targets) to the wrong row.

Tasks:

- [ ] `LogsScreen.kt`: `items(visibleLogs, key = { it.unixMs })` — confirm `unixMs` is unique
      enough; if duplicate timestamps are possible, use a composite key (`unixMs` + index or
      a stable id if the log model has one).
- [ ] `ForwardsScreen.kt`: `items(forwards, key = { it.id })`.
- [ ] `HomeCards.kt`: convert the forwards `forEach` to a keyed loop (or `LazyColumn` with
      `key = { it.id }`); ensure the surrounding scroll container is correct (don't nest an
      unbounded `LazyColumn` inside a scrolling `Column`).
- [ ] Re-check any other `items(...)`/`forEach` over domain lists in `ui/` for missing keys.

Acceptance criteria:

- Every list render of a domain collection supplies a stable `key`.
- Add/delete/reorder of forwards visibly preserves correct per-row identity.

---

### P0-002 — Confirm before importing/overwriting configuration

Files:

- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/ImportExportScreen.kt`
  (`openTextDocumentLauncher` → `vm.importFromUri(uri, ImportKind.Config)`, ~line 130-141)
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/ImportExportViewModel.kt`

Problem:

Selecting a file for **Import config** immediately overwrites the working config with no
"replace current configuration?" confirmation. Export is guarded by a warning dialog; import
is not — an asymmetric and lossy gap (wrong file or double-tap silently replaces a working
setup). Same applies to **Import identity** and the advanced path-based import.

Tasks:

- [ ] Add a confirmation dialog shown after a file is picked but before `importFromUri`
      applies it, stating that the current configuration/identity will be replaced.
- [ ] Cover all three import kinds (Config, PrivateIdentity, PublicIdentity) and the advanced
      path-based import (`ImportExportViewModel.importConfig`, etc.).
- [ ] Consider showing a one-line summary of what was selected (file name) in the dialog.
- [ ] Confirm before overwriting an existing file on advanced path-based **export**
      (`exportConfig` writes via `File.writeText` with no overwrite check).

Acceptance criteria:

- No import path mutates on-device config/identity without an explicit confirm step.
- Advanced path export does not silently overwrite an existing file.

---

### P0-003 — Confirm before stopping a running tunnel

Files:

- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/HomeScreen.kt` (`HomeActionRow`, Stop callback)

Problem:

"Stop Tunnel" stops immediately with no confirmation and no undo. A stray tap kills a
long-running tunnel.

Tasks:

- [ ] Add an `AlertDialog` confirmation for Stop when the tunnel is in a running/active state
      (Connected/Listening/Serving/Connecting/Reconnecting).
- [ ] Do **not** confirm for idempotent/no-op stops (already Stopped/Error) — only guard the
      destructive case.
- [ ] Keep the notification Stop action immediate (system surface), or mirror the same guard
      if feasible; document the choice.

Acceptance criteria:

- Stopping an active tunnel from the app requires confirmation; stopping from a non-active
  state does not prompt.

---

### P0-004 — Gate the Forwards wizard step on "at least one enabled forward"

Files:

- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupViewModel.kt`
  (`canAdvance`, Forwards branch, ~line 202-205)
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupStepValidation.kt`
  (`validateForwardsStep`, ~line 61-67)

Problem:

`canAdvance` validates the forward list but does not require at least one **enabled** forward,
while save-time validation does. The user can advance to Review and only then get bounced back
with "enable at least one forward" — a finish-line failure.

Tasks:

- [ ] In `canAdvance` for the Forwards step, add the same `forwards.any { it.enabled }` check
      used at save time (reuse `validateForwardsStep` rather than duplicating the rule).
- [ ] Ensure the Next button reflects the gated state (disabled) with a clear reason/hint.
- [ ] Add/extend a `SetupViewModelTest` case: 0 forwards and all-disabled forwards both block
      advancing from the Forwards step.

Acceptance criteria:

- The Forwards step cannot be advanced unless at least one forward is enabled and valid.
- Save-time validation no longer fires for a condition the step itself should have blocked.

---

### P0-005 — Fix notification action icon/label mismatch

Files:

- `android/app/src/main/java/com/phillipchin/webrtctunnel/notification/NotificationController.kt`
  (`addAction(android.R.drawable.ic_media_pause, "Stop", action)`, ~line 101)

Problem:

The Stop action stops the service but shows a **pause** glyph — icon/label dissonance that
makes users hesitate or assume nothing happened.

Tasks:

- [ ] Use a stop glyph (e.g. a bundled stop vector drawable) for the "Stop" action, or
      relabel to match the icon. Prefer a real stop icon since the action terminates the
      service. Avoid relying on `android.R.drawable` for a consistent stop glyph across OEMs;
      add a small vector asset if needed.
- [ ] Verify the icon renders acceptably on Android 8–14 notification styles.

Acceptance criteria:

- The notification action's icon and label both communicate "stop".

---

### P0-006 — Surface silent async failures (save/import/export)

Files:

- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/ForwardsScreen.kt`
  (dialog dismissed immediately after `vm.saveForward(it)`, ~line 92-94)
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/ForwardsViewModel.kt` (`saveForward`)
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/ImportExportViewModel.kt`

Problem:

`saveForward` is async and sets a viewmodel `message`, but the editor dialog closes
immediately, so a failed save surfaces (if at all) on a screen the user may have left. Import
results clear on the next state change. Failures can be effectively invisible.

Tasks:

- [ ] Either keep the editor dialog open until the save completes (observe `isBusy`/result and
      close only on success, showing the error inline on failure), **or** route the result to
      the app-wide Snackbar from P1-001.
- [ ] Ensure import/export results are delivered through a durable channel (Snackbar), not a
      `message` that a recomposition wipes.

Acceptance criteria:

- A failed forward save is visibly reported and does not look like success.
- Import/export success and failure are both reliably surfaced.

> Note: P0-006 and P1-001 overlap. If P1-001 (Snackbar host) is done first, P0-006 reduces to
> wiring these specific results into it. Sequence accordingly.

---

## P1 tasks

### P1-001 — App-wide action feedback (Snackbar host)

Files:

- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/App.kt` (Scaffold / nav host)
- All viewmodels that expose a transient `message`/result
  (`ForwardsViewModel`, `ImportExportViewModel`, `LogsViewModel`, `SettingsViewModel`,
  `NetworkPolicyViewModel`, `HomeViewModel`)

Problem:

Start/Stop, copy-to-clipboard, import/export, forward save, and policy toggles fire-and-forget
with no confirmation. Users can't tell whether a tap registered. Per-screen `message` fields
are inconsistent and easily missed.

Tasks:

- [ ] Add a single `SnackbarHost` to the top-level `Scaffold` in `App.kt`.
- [ ] Provide a shared mechanism (e.g. a `SharedFlow<UiMessage>` collected at the App level,
      or a small `SnackbarController`) that viewmodels emit into.
- [ ] Migrate existing one-off `message`/`resultMessage` displays to emit through it:
      forward save/delete, config import/export, identity import/export, log copy/export,
      config validation result, network-policy toggle saved.
- [ ] Use distinct styling/duration for success vs error where the host supports it.
- [ ] Keep redaction: never put secrets/SDP/candidates into Snackbar text.

Acceptance criteria:

- Every user-initiated mutating action produces a visible success/failure confirmation.
- No action result depends on the user already looking at a specific screen.

---

### P1-002 — Per-field, inline validation in the forward editor

Files:

- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/ForwardEditor.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/ForwardsViewModel.kt`
  (`validateForwardDraft`)

Problem:

Validation surfaces as a single error line at the bottom on Save; `OutlinedTextField`s never
set `isError`/`supportingText`, so the user can't tell which field is wrong. Duplicate-port
conflicts are only discovered on Save.

Tasks:

- [ ] Extend draft validation to return per-field results (which field failed + message),
      not just a single string.
- [ ] Wire `isError = true` and `supportingText` on the offending `OutlinedTextField`(s).
- [ ] Show a live "port already in use by <name>" hint as the user edits the local port
      (mirror the existing free-port suggestion logic used when adding).
- [ ] Keep the aggregate error line as a fallback for cross-field errors.

Acceptance criteria:

- The specific invalid field is visually marked with an actionable message before Save.
- Duplicate-port conflicts are indicated inline, not only on Save.

---

### P1-003 — Inline/numeric validation for the broker port (and similar fields)

Files:

- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/WizardSteps.kt` (broker port, ~line 142-145)
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupStepValidation.kt` (~line 36)

Problem:

The broker port field coerces invalid input to `0` (`toIntOrNull() ?: 0`) and only complains on
Next. The user sees "0" with no immediate signal it's invalid.

Tasks:

- [ ] Use numeric `keyboardOptions` and reject/visually flag out-of-range input as typed.
- [ ] Set `isError`/`supportingText` on the field for empty/out-of-range values.
- [ ] Avoid silently substituting `0`; preserve raw text and validate it.

Acceptance criteria:

- An invalid broker port is flagged at the field before the user taps Next.

---

### P1-004 — Plain-language pass on Home + notification copy

Files:

- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/HomeScreen.kt` (`mapStatusUi`)
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/HomeCards.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/notification/NotificationController.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/TunnelForegroundService.kt` (status body text)

Problem:

Expert terms ("Listening", "Serving", "metered/cellular blocked", "Answer mode") appear on the
two least technical surfaces (Home, persistent notification) without explanation.

Tasks:

- [ ] Pair jargon with a plain clause, e.g. "Running — waiting for a peer to connect" for
      Listening/Serving; "Paused — on cellular/metered data" for `PausedMeteredBlocked`.
- [ ] For `PausedMeteredBlocked`, make the recovery path obvious (point at "Allow This
      Session"); consider a notification action for it (see P3).
- [ ] Keep wording consistent between Home and the notification for the same state.
- [ ] Do this in conjunction with P2-001 (string externalization) so copy lives in one place.

Acceptance criteria:

- A non-expert can read Home/notification state and know what's happening and what to do.

---

### P1-005 — Richer destructive-action confirmations (delete forward, reset config, setup cancel)

Files:

- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/ForwardsScreen.kt` (`DeleteForwardDialog`, ~line 265-277)
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/SettingsScreen.kt` (`ResetConfigDialog`, ~line 280-297)
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/FlowScreens.kt` (wizard Cancel, ~line 160)
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupViewModel.kt` (reset, ~line 139-142)

Problem:

Delete confirms by name only (two similarly-named forwards are easy to confuse). Reset config
is visually muted despite wiping broker/peer/forwards/prefs. Wizard Cancel wipes all input with
no confirm.

Tasks:

- [ ] Delete forward: show full config in the dialog (id + local/target + ports), not just name.
- [ ] Reset config: state irreversibility prominently (bold "This cannot be undone"); match the
      stronger warning style already used by the metered-warning dialog.
- [ ] Wizard Cancel: confirm before discarding entered setup input.

Acceptance criteria:

- Each destructive action shows enough context to avoid mistaken targets and is clearly marked
  irreversible where it is.

---

### P1-006 — Clarify the identity / remote-identity steps

Files:

- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/WizardSteps.kt`
  (identity import paths ~line 54-100; remote public identity ~line 195-221)

Problem:

Identity has three overlapping entry points (file picker / generate / advanced raw path) with
thin labels; the remote public-identity field gives no format hint until Validate is tapped.
This is the step a non-expert is most likely to fumble.

Tasks:

- [ ] Disambiguate labels (e.g. "Import from device (file picker)" vs advanced "Enter file
      path"); collapse advanced by default.
- [ ] Add `supportingText` describing the expected remote public-identity format
      (plaintext/TOML) and where it comes from.
- [ ] Add a password-visibility toggle to the broker password field (`WizardSteps.kt` ~line
      157-163) so users can verify typed secrets.
- [ ] In Review, badge/highlight remote identity when it shows "will be validated at save"
      (not yet pre-validated) so save-time surprises are pre-empted.

Acceptance criteria:

- A first-time user can tell which identity action to use and what to paste, without trial.

---

### P1-007 — Loading states on first paint

Files:

- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/HomeScreen.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/ForwardsScreen.kt`

Problem:

Status/forwards are collected with no loading affordance; cards can flash blank before the
first emission.

Tasks:

- [ ] Show a lightweight placeholder/spinner (or skeleton) until the first status/forwards
      emission arrives.
- [ ] Distinguish "loading" from genuine "empty" states.

Acceptance criteria:

- No transient blank cards on launch; empty and loading are visually distinct.

---

## P2 tasks

### P2-001 — Externalize user-facing strings to `strings.xml`

Files:

- `android/app/src/main/res/values/strings.xml` (currently only `app_name`)
- All `ui/` screens, `NotificationController.kt`, `TunnelForegroundService.kt` status text

Problem:

Virtually all visible copy is hardcoded in Kotlin — blocks localization and scatters wording.

Tasks:

- [ ] Move notification titles/bodies, status titles/descriptions, button labels, dialog text,
      and content descriptions into `strings.xml` (use string resources / `stringResource`).
- [ ] Use parameterized strings for dynamic values (peer id, uptime, counts).
- [ ] Establish naming conventions (e.g. `home_status_connected_title`).
- [ ] Coordinate with P1-004 so the plain-language wording is what gets externalized.

Acceptance criteria:

- No user-facing literal strings remain inline in Kotlin UI/service code.
- App builds and `lintDebug` is clean (watch for `HardcodedText`/missing-translation lints —
  fix, don't suppress).

---

### P2-002 — Accessibility sweep

Files:

- `ui/` screens broadly; `Components.kt`, `ForwardsScreen.kt`, `LogsScreen.kt`, `HomeCards.kt`

Problem:

Missing/generic content descriptions on icon-only buttons; no error semantics on fields;
status conveyed partly by color; touch targets not all verified ≥48dp.

Tasks:

- [ ] Add meaningful `contentDescription` to all icon-only buttons (e.g. copy-URL icon),
      and make status icons describe the actual state ("Tunnel connected", not "Tunnel status").
- [ ] Ensure form-field error state is exposed via `isError`/`supportingText` (ties to P1-002/003).
- [ ] Verify all tappable rows/icons meet the 48dp minimum target.
- [ ] Add `semantics`/headings where related text should be grouped for TalkBack.
- [ ] Verify status is never color-only (logs already include an uppercase level label — keep
      that pattern for any color-coded chips).

Acceptance criteria:

- TalkBack can describe every interactive control and the current tunnel state.
- No interactive target below 48dp.

---

### P2-003 — Dark theme support

Files:

- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/theme/Theme.kt`

Problem:

Only `lightColorScheme()` is defined; dark-mode users get a light app. Several raw hex colors
are scattered instead of theme tokens.

Tasks:

- [ ] Add a `darkColorScheme()` and select via `isSystemInDarkTheme()`.
- [ ] Replace scattered raw `Color(0xFF…)` usages (secondary text gray, status chip colors,
      log-level colors) with named theme tokens / a small palette object.
- [ ] Re-check contrast in both themes; darken the warning amber (`#F59E0B`) toward `#D97706`
      for WCAG AA on light surfaces.

Acceptance criteria:

- App renders correctly in light and dark; color values come from tokens, not inline literals.

---

### P2-004 — Logs screen polish

Files:

- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/LogsScreen.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/LogsViewModel.kt`

Problem:

No auto-scroll to newest on refresh; copy/export give no confirmation; filter resets on
restart; empty state is generic.

Tasks:

- [ ] Use `rememberLazyListState()` and scroll to the latest entry on refresh when not paused
      (and when the user is already at/near the bottom — don't yank them while scrolled up).
- [ ] Confirm copy/export via the P1-001 Snackbar.
- [ ] Persist the selected filter across restarts (datastore/prefs).
- [ ] Differentiate empty states ("no logs yet" vs "filter matched nothing" vs "debug hidden").

Acceptance criteria:

- New logs are visible without manual scrolling (unless the user scrolled up); copy/export
  confirm; filter survives restart.

---

### P2-005 — Import/Export screen polish

Files:

- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/ImportExportScreen.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/ImportExportViewModel.kt`

Problem:

No progress indicator during operations (buttons just disable); result message auto-clears;
file formats/consequences for each action aren't explained; advanced path state lingers.

Tasks:

- [ ] Show a progress indicator while `isBusy`.
- [ ] Deliver results via Snackbar (P1-001) instead of a transient `Text`.
- [ ] Add short help text per action (config = TOML; private identity = replaces device key;
      public identity = shareable peer key).
- [ ] Clear advanced path fields after a successful operation.
- [ ] Cache the public identity in memory instead of re-reading disk on every share/copy.

Acceptance criteria:

- Operations show progress and a durable result; each action's format/effect is explained.

---

### P2-006 — Settings screen clarity

Files:

- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/SettingsScreen.kt`

Problem:

"Edit custom topic prefix" / "Configure non-localhost bind" buttons route to the full setup
wizard but read like inline editors; "Answer mode: not available on Android" is unexplained;
validation message can show a stale "valid" while re-validating; identity load error has no
retry.

Tasks:

- [ ] Relabel wizard-routing buttons to make the destination obvious (e.g. "Re-run setup to
      change topic prefix") or provide focused inline editors.
- [ ] Add a one-line explanation (or help affordance) for "Answer mode" platform limitation.
- [ ] Clear/replace the validation message when re-validation starts; show a spinner.
- [ ] Add a Retry action when `publicIdentityLoadError` is shown.

Acceptance criteria:

- Button labels match their actual behavior; transient validation state isn't misleading;
  identity load errors are recoverable.

---

### P2-007 — Network policy screen clarity and feedback

Files:

- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/NetworkPolicyScreen.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/NetworkPolicyViewModel.kt`

Problem:

Status lines are terse/technical ("Metered", "Tunnel blocked", raw reason); toggles save with
no feedback; on cancelling the metered-warning the switch can visually flip then settle.

Tasks:

- [ ] Humanize status copy ("High data usage (metered)", "Paused because: …") with color.
- [ ] Confirm toggle saves via Snackbar (P1-001).
- [ ] Keep the metered switch in its prior state until the warning is confirmed (don't flip
      then revert).

Acceptance criteria:

- A user can understand why the tunnel is allowed/blocked and sees their toggle persisted.

---

### P2-008 — Error card dismissal / no-network guidance on Home

Files:

- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/HomeCards.kt` (`ErrorResolutionCard`)
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/HomeScreen.kt` (`mapStatusUi` NoNetwork)

Problem:

The error card has no manual dismiss; the NoNetwork description ("Connect to Wi-Fi…") doesn't
clarify whether there is genuinely no connectivity, and forwards still appear ready.

Tasks:

- [ ] Add a dismiss affordance to the error card (or auto-clear semantics that are obvious).
- [ ] Make NoNetwork copy explain the actual condition and what the user can do.

Acceptance criteria:

- Users can clear a stale error and understand a no-network state.

---

## P3 tasks

### P3-001 — Notification convenience actions

- [ ] Add a "Retry" action on Error and an "Allow this session" action on
      `PausedMeteredBlocked`, so common recoveries don't require opening the app.
- [ ] Consider Pause/Resume actions if pause semantics are wanted at the notification level.

Files: `NotificationController.kt`, `TunnelForegroundService.kt`.

### P3-002 — Preserve nav state across bottom-tab switches

- [ ] Re-evaluate the `saveState`/`restoreState` decision in `App.kt` so scroll position and
      filters survive tab switches (the current code deliberately omits it — document why if
      kept).

### P3-003 — Type-safe status/title mapping

- [ ] Replace string-equality checks that drive icon/title selection (`HomeScreen.kt`) with
      `ServiceState`-keyed logic so copy changes can't silently break icon selection.

### P3-004 — Connecting progress detail

- [ ] Optionally show elapsed time / attempt count during Connecting/Reconnecting on Home and
      in the notification so a slow connect doesn't read as "stuck".

---

## Suggested sequencing

1. **P0 batch** (P0-001…P0-005) — small, mechanical, high-confidence; keep gates green.
2. **P1-001 (Snackbar host)** early — P0-006 and several P2 items depend on it.
3. Remaining P1 (validation, copy, confirmations, identity clarity, loading).
4. **P2-001 (strings) before/with P1-004** so plain-language copy is externalized once.
5. P2 polish, then P3 as optional.

## Done-state checklist (per task)

- [ ] `cd android && ./gradlew ktlintCheck detekt lintDebug` — zero findings (no suppressions).
- [ ] `cd android && ./gradlew testDebugUnitTest` — green; add/adjust tests for new logic
      (esp. P0-004 step gating, P1-002/003 validation).
- [ ] Manual pass on device for any state/notification-visible change.
- [ ] No secrets/SDP/candidates introduced into new user-facing text (redaction preserved).
