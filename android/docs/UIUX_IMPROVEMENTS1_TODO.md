# UI/UX Improvements 1 — TODO

Derived from the UI/UX review of the Android app. Items are grouped by priority tier
(High → Medium → Low) and then by screen/component. Each item includes the relevant
file(s) and specific subtasks needed to implement it.

---

## High Priority

---

### H1 — Format log timestamps as human-readable dates ✅

**Files:** `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/screens.kt`

**Problem:** Log entries display raw Unix epoch milliseconds (e.g. `1748721234567`), which
is completely unreadable to users.

**Tasks:**
- [x] Add a private helper function `formatLogTimestamp(unixMs: Long): String` in `screens.kt`
  that formats the value as `HH:mm:ss` using `java.time.Instant` and the device local timezone
- [x] Replace `Text("${event.unixMs}", ...)` in the log entry card with
  `Text(formatLogTimestamp(event.unixMs), ...)`
- [x] Decide on format: `HH:mm:ss` for brevity (recommended for a log viewer), or
  `yyyy-MM-dd HH:mm:ss` if multi-day sessions are expected; apply consistently
- [x] Verify the formatted timestamp is still exported/copied correctly in the copy-logs
  path (the copy path formats its own string `"${it.unixMs} ${it.level} ${it.message}"` —
  keep the raw epoch there for machine-readability, only change the displayed card)
- [x] Run unit tests and lint

---

### H2 — Fix Home screen "+" button: open Add Forward dialog, not the Setup Wizard ✅

**Files:**
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/screens.kt` (`HomeScreen`)
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/App.kt`

**Problem:** The `+` `IconButton` in the Forwards card on the Home screen calls
`onOpenSetup`, which navigates the user into the full 7-step Setup Wizard. Users expecting
to add a single forward are dropped into a complex flow instead.

**Tasks:**
- [x] Add a `var showAddForwardDialog by remember { mutableStateOf(false) }` state variable
  to `HomeScreen`
- [x] Change the `+` `IconButton` `onClick` from `onOpenSetup` to
  `{ showAddForwardDialog = true }`
- [x] Add `forwardsVm: ForwardsViewModel` as a parameter to `HomeScreen` (it is needed to
  call `validateForwardDraft` and `saveForward`)
- [x] Wire the existing `EditForwardDialog` at the bottom of `HomeScreen`, passing
  `mode = ForwardEditorMode.Add`, `initial = defaultNewForward(configuredForwards)`,
  `existingForwards = configuredForwards`, `validateDraft = forwardsVm::validateForwardDraft`,
  and an `onSave` that calls `forwardsVm.saveForward(it)` then `vm.refreshForwards()`
- [x] Pass `forwardsVm` from `WebRtcTunnelApp` in `App.kt` to `HomeScreen`
- [x] Update `App.kt` `HomeScreen` composable call to include the new parameter
- [x] Run lint and tests

---

### H3 — Add an Add button to the Forwards tab ✅

**Files:**
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/screens.kt` (`ForwardsScreen`)

**Problem:** The Forwards tab shows the list of forwards but has no way to add a new one.
The only add path was via the Setup Wizard, which is non-obvious.

**Tasks:**
- [x] Add `var showAddDialog by remember { mutableStateOf(false) }` to `ForwardsScreen`
- [x] Add Add `IconButton` in the section header row
- [x] Render `EditForwardDialog` when `showAddDialog` is true, using
  `defaultNewForward(forwards)` as the initial draft, calling `vm.saveForward(it)` on save
- [x] Update empty-state message from "Add one in Setup Wizard" to "Tap + to add one"
- [x] Run lint and tests

---

### H4 — Remove hardcoded Llama server default from `resetConfiguration()` ✅

**Files:**
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/AppViewModels.kt`
  (`SettingsViewModel.resetConfiguration`)
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/screens.kt` (`SettingsScreen`)

**Problem:** `resetConfiguration()` hardcodes a `ForwardConfig` for a Llama server on port
8080. This is a development artifact that will confuse all other users.

**Tasks:**
- [x] Replace the hardcoded `saveForwards(listOf(ForwardConfig(id = "llama", ...)))` call
  with `saveForwards(emptyList())` — reset to no forwards
- [x] Add a confirmation `AlertDialog` in `SettingsScreen` before calling
  `vm.resetConfiguration()`
- [x] Change the `OutlinedButton` onClick to show the dialog first
- [x] Use `DestructiveActionButton` for the reset button with red text
- [x] Run lint and tests

---

### H5 — Pre-populate Setup Wizard from saved config on re-run ✅

**Files:**
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/AppViewModels.kt`
  (`SetupViewModel.init`)

**Problem:** Running "Setup wizard again" from Settings starts with all fields blank, even
when a valid config already exists. Users must re-enter everything from scratch.

**Tasks:**
- [x] Add `loadStoredSetupInput()` private function that calls
  `deps.configRepository.loadSetupInput()` and pre-fills `_state.value.input`
- [x] Call `loadStoredSetupInput()` in `SetupViewModel.init` before `loadStoredIdentity()`
- [x] Guard on non-empty broker/peer values so a blank stored input doesn't clobber defaults
- [x] Run lint and full test suite

---

## Medium Priority

---

### M1 — Replace the Mode step with a "Welcome / Offer only" info card ✅

**Files:**
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/FlowScreens.kt`
  (`ModeStepContent`)

**Problem:** Step 1 (Mode) shows two cards but the user cannot select anything. The step
burns a navigation tap and teaches nothing actionable.

**Tasks:**
- [x] Replace the two-card `ModeStepContent` with a single informational welcome card
  explaining the wizard steps and that only Offer mode is available on Android
- [x] Remove unused `Icons.Filled.Storage` import
- [x] Run lint

---

### M2 — Replace raw booleans in Review step with human-friendly text ✅

**Files:**
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/FlowScreens.kt`
  (`ReviewStepContent`)

**Problem:** The Review screen displays `Allow metered: false` and
`Resume on unmetered: true`, which look like debug output.

**Tasks:**
- [x] Replace `true`/`false` in allow-metered and resume-on-unmetered with "Yes"/"No"
- [x] Replace `Remote identity validated: No` with a clearer validated / will-be-validated message
- [x] Replace `Public identity imported/generated: No/Yes` with "Not yet set" / "Ready"
- [x] Run lint

---

### M3 — Move Topic Prefix to Advanced section in Broker step ✅

**Files:**
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/FlowScreens.kt`
  (`BrokerStepContent`)

**Problem:** `topicPrefix` is shown inline with broker host/port and credentials. Most
users do not need to change it and will not know what it means.

**Tasks:**
- [x] Move the `topicPrefix` `OutlinedTextField` into the `if (state.advancedExpanded)` block
- [x] Add a brief helper text explaining when to change the topic prefix
- [x] Run lint

---

### M4 — Remove non-localhost bind warning toggle from Network Policy step ✅

**Files:**
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/FlowScreens.kt`
  (`PolicyStepContent`)
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/AppViewModels.kt`

**Problem:** "Acknowledge non-localhost bind warning" appears in the Network Policy step
but non-localhost bind is not configurable anywhere in the wizard.

**Tasks:**
- [x] Remove the non-localhost bind warning `Row` and `Switch` from `PolicyStepContent`
- [x] Remove `nonLocalhostWarningAccepted` field from `SetupWizardState`
- [x] Remove `setNonLocalhostWarningAccepted` method from `SetupViewModel`
- [x] Run lint and tests

---

### M5 — Fix logs to poll periodically, not just once on unpause ✅

**Files:**
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/screens.kt` (`LogsScreen`)

**Problem:** `LaunchedEffect(paused) { if (!paused) vm.refresh() }` performs a single
refresh when the screen unpauses. Logs do not update while the screen is open.

**Tasks:**
- [x] Replace the single-shot `LaunchedEffect` with a polling loop using `delay(2_000L)`
- [x] Import `kotlinx.coroutines.delay` in `screens.kt`
- [x] Run lint

---

### M6 — Make `filteredLogs` a reactive StateFlow ✅

**Files:**
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/AppViewModels.kt`
  (`LogsViewModel`)
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/screens.kt` (`LogsScreen`)

**Problem:** `filteredLogs()` was a plain function called during composition, causing
potential stale results.

**Tasks:**
- [x] Replace `filteredLogs()` function with a `StateFlow` using `combine` + `stateIn`
- [x] Add `SharingStarted` and `stateIn` imports
- [x] Update `LogsScreen` to `collectAsStateWithLifecycle()` on the new `StateFlow`
- [x] Remove the old `filteredLogs()` function
- [x] Run lint and tests

---

### M7 — Rename "debug path import" button in Identity step ✅

**Files:**
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/FlowScreens.kt`
  (`IdentityStepContent`)

**Problem:** The toggle button label reads "Show debug path import" — the word "debug"
appears in a production UI.

**Tasks:**
- [x] Rename toggle to "Show advanced import options" / "Hide advanced import options"
- [x] Rename field label from "Private identity path (debug)" to "Private identity file path"
- [x] Add helper text explaining when to use the path field
- [x] Run lint

---

### M8 — Fix Import/Export screen public identity row layout ✅

**Files:**
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/screens.kt`
  (`ImportExportScreen`)

**Problem:** The public identity row crammed four controls into one `Row`, overflowing on
narrow phones with unclear visual grouping.

**Tasks:**
- [x] Split into two rows: import/export pair and share/copy pair
- [x] Replace bare `IconButton` with `OutlinedButton` + icon + text for share and copy
- [x] Run lint

---

### M9 — Clarify "Test Broker" only checks TCP reachability ✅

**Files:**
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/FlowScreens.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/AppViewModels.kt`
  (`SetupViewModel.testBrokerConnection`)

**Problem:** `testBrokerConnection` tests TCP socket only but the button and result said
"Broker connection succeeded", misleading users about full MQTT/TLS auth.

**Tasks:**
- [x] Rename button from "Test Broker" to "Test TCP reachability"
- [x] Update success message to clarify it's TCP-only and that full auth happens at tunnel connect
- [x] Update failure message to include host:port
- [x] Run lint

---

### M10 — Fix `ServiceState.Stopping` action button ✅

**Files:**
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/screens.kt`
  (`HomeActionRow`)

**Problem:** When the service is in the `Stopping` state, the action row showed a "Stop"
button. The service was already stopping; clicking Stop again was confusing.

**Tasks:**
- [x] Replace the `Stopping` branch button with a `CircularProgressIndicator` and
  "Stopping…" text
- [x] Run lint

---

### M11 — Fix filter chips to scroll horizontally on Logs screen ✅

**Files:**
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/screens.kt` (`LogsScreen`)

**Problem:** Five `FilterChip` components in a plain `Row` overflow on narrow phones.

**Tasks:**
- [x] Wrap the filter chips `Row` in `horizontalScroll(rememberScrollState())`
- [x] Import `horizontalScroll` and `rememberScrollState`
- [x] Run lint

---

## Low Priority

---

### L1 — Fix version string to match Rust codebase version ✅

**Files:**
- `android/app/build.gradle.kts`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/screens.kt`

**Problem:** About section hardcoded "Version 0.1" while the Rust codebase is at v0.3.

**Tasks:**
- [x] Set `versionName = "0.3.0"` and `versionCode = 3` in `build.gradle.kts`
- [x] Enable `buildConfig = true` in `buildFeatures`
- [x] Import `BuildConfig` in `screens.kt` and use `BuildConfig.VERSION_NAME`
- [x] Run lint

---

### L2 — Remove dead `ScreenSurface` composable ✅

**Files:**
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/screens.kt`

**Problem:** `ScreenSurface` was defined but never called anywhere.

**Tasks:**
- [x] Verify no callers exist (grep confirmed)
- [x] Delete the `ScreenSurface` composable
- [x] Remove unused `ColumnScope` import
- [x] Run lint and tests to confirm no compilation errors

---

### L3 — Remove no-op `Spacer(Modifier.height(0.dp))` ✅

**Files:**
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/screens.kt`
  (`ForwardDetailsScreen`)

**Problem:** A zero-height spacer between an icon and label did nothing.

**Tasks:**
- [x] Replace `Spacer(Modifier.height(0.dp))` with `Spacer(Modifier.size(4.dp))` for
  proper icon-to-text spacing
- [x] Run lint

---

### L4 — Remove "Active sessions" from Offer home view ✅

**Files:**
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/screens.kt` (`HomeScreen`)

**Problem:** `Active sessions: 0` is meaningless noise for an Offer node.

**Tasks:**
- [x] Conditionally hide "Active sessions" when `status.mode == TunnelMode.Offer`
- [x] Run lint

---

### L5 — Fix `localhostUrl()` for non-HTTP forwards ✅

**Files:**
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/AppViewModels.kt`
  (`ForwardsViewModel`)
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/screens.kt`
  (`ForwardDetailsScreen`)

**Problem:** `localhostUrl()` always returned `http://host:port`, incorrect for SSH or other
non-HTTP forwards. The detail screen labelled it "Local URL".

**Tasks:**
- [x] Remove the "Local URL" line from the details card (already shown as "Local address")
- [x] Inline the address/URL logic in `ForwardDetailsScreen`: use `http://` for
  browser-openable forwards only
- [x] Change button label to "Copy URL" for HTTP forwards, "Copy address" for others
- [x] Remove `localhostUrl()` from `ForwardsViewModel` (no longer used)
- [x] Run lint

---

### L6 — Confirmation dialog before `resetConfiguration()` ✅

*(Implemented as part of H4)*

**Tasks:**
- [x] Confirmation dialog in place (see H4)
- [x] "Reset configuration" uses `DestructiveActionButton` with red text

---

## Validation Gate ✅

- [x] Run `./gradlew --no-daemon lintDebug` — passed with no errors
- [x] Run `./gradlew --no-daemon testDebugUnitTest` — all tests passed
- [x] `./gradlew --no-daemon assembleDebug` — build successful
