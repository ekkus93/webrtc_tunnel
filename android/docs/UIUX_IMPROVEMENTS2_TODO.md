# UI/UX Improvements 2 — TODO

Derived from the second UI/UX review of the Android app, conducted after the first batch of
improvements (UIUX_IMPROVEMENTS1_TODO.md) was completed. Items are grouped by priority tier
(High → Medium → Low) and then by screen/component. Each item includes the relevant file(s)
and specific subtasks needed to implement it.

---

## High Priority

---

### H1 — Fix `NetworkPolicyScreen` and `PolicyStepContent` raw enum display ✅

**Files:**
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/screens.kt` (`NetworkPolicyScreen`)
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/FlowScreens.kt` (`PolicyStepContent`)

**Problem:** `Text("Current network: ${status.networkType}")` renders the raw Kotlin enum
name (`UnmeteredWifi`, `MeteredWifi`, `NoNetwork`). `mapNetworkTypeLabel()` already exists
in `screens.kt` but is `private` and therefore inaccessible from `NetworkPolicyScreen`'s
inner composable and from `FlowScreens.kt`.

**Tasks:**
- [x] Move `mapNetworkTypeLabel()` from `private` to `internal` (or extract to a shared
  location — `models.kt` or a new `ui/UiHelpers.kt`)
- [x] Replace `Text("Current network: ${status.networkType}")` in `NetworkPolicyScreen`
  with `Text("Current network: ${mapNetworkTypeLabel(status.networkType)}")`
- [x] Replace `Text("Current network: ${networkStatus.networkType}")` in `PolicyStepContent`
  with the same call
- [x] Run lint and tests

---

### H2 — Fix `ReviewStepContent` hardcoded `127.0.0.1` in forward display ✅

**Files:**
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/FlowScreens.kt`
  (`ReviewStepContent`)

**Problem:** The forwards summary in the Review step prints:
```kotlin
Text("127.0.0.1:${forward.localPort} -> ${forward.remoteForwardId}")
```
This ignores `forward.localHost`. If a user configured a non-loopback bind the review
shows incorrect information, and the config would be saved based on the actual `localHost`
value (not 127.0.0.1), causing a confusing mismatch.

**Tasks:**
- [x] Replace `"127.0.0.1:${forward.localPort}"` with `"${forward.localHost}:${forward.localPort}"`
- [x] Run lint

---

### H3 — Make uptime counter auto-refresh on Home screen ✅

**Files:**
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/screens.kt` (`HomeScreen`)
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/AppViewModels.kt`
  (`HomeViewModel`)

**Problem:** `status.uptimeSeconds?.let { Text("Uptime: ${formatUptime(it)}") }` is frozen
at the value emitted by the last `TunnelStatus` update. The displayed uptime does not tick
every second; it jumps only when some other field in `TunnelStatus` causes a new emission.
This makes the counter feel broken when the tunnel is idle and stable.

**Tasks:**
- [x] Add a `var displayedUptimeSeconds by remember { mutableStateOf(status.uptimeSeconds) }`
  local state in `HomeScreen`
- [x] Add a `LaunchedEffect(status.uptimeSeconds, status.serviceState)` that, while the
  tunnel is running (Connected / Listening / Serving), increments `displayedUptimeSeconds`
  by 1 every second with `delay(1_000L)` until cancelled
- [x] Update the uptime `Text` to use `displayedUptimeSeconds` instead of `status.uptimeSeconds`
- [x] Run lint

---

### H4 — Replace log `Column` with `LazyColumn` in `LogsScreen` ✅

**Files:**
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/screens.kt` (`LogsScreen`)

**Problem:** All log entries are rendered at once inside a plain `Column` inside
`ScrollableScreenSurface`. For sessions with hundreds of log entries this causes:
- All composables materialized upfront (high memory)
- Slow initial render and janky scrolling

**Tasks:**
- [x] Replace `ScrollableScreenSurface` in `LogsScreen` with a `Scaffold`-free `Column`
  containing:
  - A fixed top area (filter chips row, Pause/Clear/Actions row) that does NOT scroll
  - A `LazyColumn` below for the log items
- [x] Move `SectionHeader`, filter chips, and action buttons outside the scrollable area
  (they should stay pinned at top)
- [x] Move each log card into a `LazyColumn` `item { StatusCard { ... } }` block
- [x] Move the `EmptyStateCard("No logs available.")` and debug-hidden notice into
  `LazyColumn` items as well
- [x] Ensure padding with `padding` (scaffold insets) is still respected on the outer
  Column
- [x] Import `androidx.compose.foundation.lazy.LazyColumn` and `items` as needed
- [x] Run lint and tests

---

### H5 — Make Home screen forward rows navigate to Forward Details ✅

**Files:**
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/screens.kt` (`HomeScreen`)
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/App.kt`

**Problem:** The forward rows in the Home screen's "Forwards" card display name and status
but are not clickable. Users who want to see details, edit, or test a port must navigate to
the Forwards tab and click through from there. The discoverability is poor.

**Tasks:**
- [x] Add `onOpenForwardDetails: (String) -> Unit` parameter to `HomeScreen`
- [x] Wrap each forward row in the Forwards card with `Modifier.clickable { onOpenForwardDetails(forward.id) }`
- [x] Add a trailing `›` indicator to each row (matching the style in `ForwardsScreen`)
- [x] Wire the new parameter in `App.kt` where `HomeScreen` is called:
  ```kotlin
  onOpenForwardDetails = { id -> navController.navigate("forwardDetails/$id") }
  ```
- [x] Run lint

---

## Medium Priority

---

### M1 — De-duplicate network policy controls between Settings and Network Policy screens ✅

**Files:**
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/screens.kt`
  (`SettingsScreen`, `NetworkPolicyScreen`)

**Problem:** "Allow cellular / metered data" and "Resume tunnel when Wi-Fi returns" appear
as interactive `PreferenceSwitch` controls on both `SettingsScreen` and `NetworkPolicyScreen`.
This creates two authoritative-looking sources for the same setting.

**Tasks:**
- [x] Keep the two `PreferenceSwitch` controls on `NetworkPolicyScreen` (that screen is
  dedicated to network policy)
- [x] In `SettingsScreen` → "Network Policy" section, remove the `PreferenceSwitch` for
  "Allow cellular / metered data" and keep only the `OutlinedButton` that opens the
  Network Policy screen
- [x] Optionally add a read-only status line showing the current metered setting, e.g.
  `Text("Cellular: ${if (prefs.allowMetered) "Allowed" else "Blocked"}")` — so the user
  can see the state without being able to change it in two places
- [x] Run lint

---

### M2 — Consolidate identity controls to a single canonical location ✅

**Files:**
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/screens.kt` (`SettingsScreen`)

**Problem:** "Copy identity" and "Share identity" appear in `SettingsScreen → Identity`, and
the full import/export operations appear in both `SettingsScreen → Configuration` ("Import /
Export" button) and `SettingsScreen → Identity` ("Import / Export identity" button). Both
open the same `ImportExportScreen`. The duplication is confusing.

**Tasks:**
- [x] In `SettingsScreen`, merge the "Identity" section and the "Import / Export" button
  from "Configuration" into a single "Identity" section
- [x] Keep the "Copy identity" and "Share identity" shortcut buttons (quick actions that don't
  require navigating away)
- [x] Replace the duplicate "Import / Export" + "Import / Export identity" buttons with one
  consistently-named button: "Import / Export identity"
- [x] Remove the redundant `OutlinedButton(onClick = onOpenImportExport, ...)` from the
  "Configuration" section (or rename the Configuration section to "Config file" and
  keep only config file import/export there, separately from identity)
- [x] Run lint

---

### M3 — Move Diagnostics behind Advanced toggle in Settings ✅

**Files:**
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/screens.kt` (`SettingsScreen`)

**Problem:** "Copy status JSON" and "Copy redacted config" are developer/debug tools sitting
at the same visual level as user-facing settings. Most users will never use them and they
add cognitive noise.

**Tasks:**
- [x] Move the "Diagnostics" `SettingsSection` content inside the
  `if (prefs.advancedSettingsEnabled)` block in the "Advanced" section, or
- [x] Alternatively, keep "Open logs / export diagnostics" and "Share diagnostics" as
  top-level (useful for all users sending bug reports), but move "Copy status JSON" and
  "Copy redacted config" into the Advanced section
- [x] Adjust section headers accordingly
- [x] Run lint

---

### M4 — Color-code `ForwardSummaryRow` status chip by listen state ✅

**Files:**
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/components.kt`
  (`ForwardSummaryRow`)
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/screens.kt` (callers)

**Problem:** The status chip in `ForwardSummaryRow` always uses `primaryContainer` color
regardless of state. A forward in Error state looks the same as one that is Listening.
`stateColorToken()` already exists.

**Tasks:**
- [x] Add a `statusColor: Color = MaterialTheme.colorScheme.primaryContainer` parameter to
  `ForwardSummaryRow`
- [x] Change the chip `Surface` `color` to use the new parameter
- [x] In `HomeScreen`, pass `stateColorToken(mapForwardListenLabel(runtime?.listenState?.name ?: ...))`
  as `statusColor`
- [x] Ensure `stateColorToken` is accessible from `components.kt` (it is, since it's in the
  same `ui` package)
- [x] Run lint

---

### M5 — Use `mapForwardListenLabel()` consistently in `ForwardsScreen` ✅

**Files:**
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/screens.kt` (`ForwardsScreen`)

**Problem:** `ForwardsScreen` displays `runtime?.listenState?.name` (raw enum string)
while `HomeScreen` uses `mapForwardListenLabel()`. The user sees different label styles
depending on which screen they're on.

**Tasks:**
- [x] Replace the `Text(runtime?.listenState?.name ?: if (forward.enabled) "Configured" else "Disabled", ...)`
  in `ForwardsScreen` with:
  ```kotlin
  val stateLabel = mapForwardListenLabel(runtime?.listenState?.name ?: if (forward.enabled) "configured" else "disabled")
  Text(stateLabel, color = stateColorToken(stateLabel))
  ```
- [x] Make `mapForwardListenLabel()` `internal` (same change as H1) so it's accessible
- [x] Run lint

---

### M6 — Truncate/format public identity display in Settings ✅

**Files:**
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/screens.kt` (`SettingsScreen`)

**Problem:** The full Ed25519 public key blob is shown as plain `bodySmall` text in the
Identity section. On narrow phones (360dp wide) a 64-byte hex key overflows and wraps
into many lines, making the section ugly and hard to scan.

**Tasks:**
- [x] Create a private helper `fun truncateIdentity(key: String): String` that returns
  the first 16 characters + "…" + the last 8 characters if `key.length > 28`, else the
  full key
- [x] In the Identity section, display `truncateIdentity(publicIdentity ?: "")` instead of
  the raw `publicIdentity` value
- [x] Keep the full identity in the clipboard copy and share operations (do not truncate
  what is copied/shared)
- [x] Add a small `(truncated)` label or tooltip if desired
- [x] Run lint

---

### M7 — Move message feedback near the triggering button in `ForwardDetailsScreen` ✅

**Files:**
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/screens.kt`
  (`ForwardDetailsScreen`)

**Problem:** The `message` state (from `vm.message.collectAsStateWithLifecycle()`) is
displayed at the very bottom of `ForwardDetailsScreen`, below the Delete button. When the
user taps "Test Local Port", the result message appears far from the button and the user
may miss it without scrolling.

**Tasks:**
- [x] Move `message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }` to
  appear immediately after the "Test Local Port" / "Enable/Disable" row
- [x] Optionally introduce separate message state for test-port feedback vs. save/delete
  feedback if the ViewModel supports it
- [x] Run lint

---

## Low Priority

---

### L1 — Remove "Unknown network is blocked." unconditional warning in `NetworkPolicyScreen`

**Files:**
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/screens.kt`
  (`NetworkPolicyScreen`)

**Problem:** `Text("Unknown network is blocked.")` is always shown regardless of the
actual network type, even when the user is on a known Wi-Fi connection. It reads as a
persistent error even when everything is fine.

**Tasks:**
- [ ] Wrap the `Text("Unknown network is blocked.")` in an
  `if (status.networkType == NetworkType.Unknown)` condition
- [ ] Or convert it to a one-time explanatory note inside an `EmptyStateCard`-style helper
  that only appears when the network is Unknown
- [ ] Run lint

---

### L2 — Fix "Answer mode: Not available in Android v1" stale version string

**Files:**
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/screens.kt` (`SettingsScreen`)

**Problem:** The Advanced section displays `"Answer mode: Not available in Android v1"`.
The app is now version 0.3.0; `v1` is stale and looks like a mistake.

**Tasks:**
- [ ] Change to `"Answer mode: not available on Android"` (no version number, since this is
  an architectural constraint, not a versioned limitation)
- [ ] Run lint

---

### L3 — Fix `ImportExportScreen` advanced section title

**Files:**
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/screens.kt`
  (`ImportExportScreen`)

**Problem:** The toggle button reads "Show Advanced paths" but the revealed section is titled
`"Advanced (developer/debug)"`. The "(developer/debug)" qualifier was removed from the
toggle label but not the section title.

**Tasks:**
- [ ] Change `SettingsSection("Advanced (developer/debug)")` to
  `SettingsSection("Advanced (file paths)")`
- [ ] Run lint

---

### L4 — Fix `EditForwardDialog` "Remote forward_id" label

**Files:**
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/FlowScreens.kt`
  (`EditForwardDialog`)

**Problem:** The field label `Text("Remote forward_id")` uses an underscore — it looks like
a variable name from the underlying data model leaked into the UI.

**Tasks:**
- [ ] Change `label = { Text("Remote forward_id") }` to `label = { Text("Remote forward ID") }`
- [ ] Run lint

---

### L5 — Add helper text for Broker password file field

**Files:**
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/FlowScreens.kt`
  (`BrokerStepContent`)

**Problem:** The `brokerPasswordFile` advanced field has no explanation. A user entering
this section will not know whether to use this instead of or in addition to the password
field.

**Tasks:**
- [ ] Add `Text("Use this instead of the password field if your credentials are stored in a file on device.", style = MaterialTheme.typography.bodySmall)` after the password file `OutlinedTextField`
- [ ] Run lint

---

### L6 — Fix "Remote peer: -" label on Home screen

**Files:**
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/screens.kt` (`HomeScreen`)

**Problem:** `Text("Remote peer: ${status.remotePeerId ?: "-"}")` shows a bare dash when
no peer is configured, which looks like an empty data field rather than a meaningful status.

**Tasks:**
- [ ] Change `status.remotePeerId ?: "-"` to `status.remotePeerId ?: "Not configured"`
- [ ] Run lint

---

### L7 — Deduplicate `isBrowserOpenable()` calls in `ForwardDetailsScreen`

**Files:**
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/screens.kt`
  (`ForwardDetailsScreen`)

**Problem:** `isBrowserOpenable(forward)` is called three times in the same render pass
(for `copyLabel`, `copyValue`, and the conditional `if (isBrowserOpenable(forward))` block).
While the function is cheap, triple-calling it in one composition is unnecessarily redundant.

**Tasks:**
- [ ] Add `val canOpenBrowser = isBrowserOpenable(forward)` at the top of the
  `ScrollableScreenSurface` lambda (after the null guard)
- [ ] Replace all three `isBrowserOpenable(forward)` call sites with `canOpenBrowser`
- [ ] Run lint

---

### L8 — Rename "MQTT Broker" step label to "Broker"

**Files:**
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/FlowScreens.kt` (`stepLabel`)

**Problem:** `SetupStep.Broker -> "MQTT Broker"` exposes the acronym "MQTT" in the
`WizardStepper` display. Most end users do not need to know the signaling protocol is MQTT.

**Tasks:**
- [ ] Change `SetupStep.Broker -> "MQTT Broker"` to `SetupStep.Broker -> "Broker"`
- [ ] Run lint

---

## Validation Gate

- [ ] Run `./gradlew --no-daemon lintDebug` — must pass with no errors
- [ ] Run `./gradlew --no-daemon testDebugUnitTest` — all tests must pass
- [ ] Run `./gradlew --no-daemon assembleDebug` — build must succeed
