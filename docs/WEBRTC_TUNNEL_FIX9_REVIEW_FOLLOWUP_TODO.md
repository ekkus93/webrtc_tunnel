# WebRTC Tunnel — FIX9 Review Follow-up TODO

**Target branch:** `master`  
**Created from review of FIX9 closure commit:** `9503b4aba0f3046446a0392522fa7eac242a9343`  
**Purpose:** Fix the non-blocking issues found during the post-FIX9 comprehensive code review without reopening the core FIX9 stale setup-operation / result-contract closure.

This TODO intentionally separates **commit-level FIX9 closure** from **release-process and test-contract hardening**. The stale setup-operation, transactional save, canonical import, result-contract, broker-secret permission, and exact-SHA full-signoff work remain complete. The items below prevent future overstatement, quiet weakening of E2E evidence, and regression-prone static enforcement.

---

## Guiding rules for this follow-up

- Do **not** weaken any FIX9 gate to make CI pass.
- Do **not** replace failures with warnings where the contract says the behavior is required.
- Do **not** add retry-only fixes, silent skips, `|| true` around required operations, or broad timeout inflation.
- Do **not** report release artifacts as validated unless the workflow actually builds and uploads those artifacts.
- Prefer fail-closed diagnostics with bounded, redacted evidence.
- Every task below must include direct tests or CI evidence before being checked off.

---

# P0 — Release artifact contract must match the workflow

## P0-001 — Decide and encode the Android release-artifact contract

**Problem:** The FIX9 ledger says the eventual release tag must validate APK/AAB packaging, but `.github/workflows/ci.yml` currently packages only Rust binaries in `release-artifacts`.

### Tasks

- [ ] Inspect the current release workflow in `.github/workflows/ci.yml`.
- [ ] Decide whether tagged releases for this project are expected to publish:
  - [ ] Rust CLI/daemon artifacts only, or
  - [ ] Rust artifacts plus Android APK/AAB artifacts.
- [ ] Update documentation so it matches the chosen release contract exactly.
- [ ] If Android APK/AAB artifacts are **not** part of the release contract yet:
  - [ ] Change the FIX9 release-tag wording so it does not claim APK/AAB validation.
  - [ ] Add a separate future TODO for Android release packaging if desired.
- [ ] If Android APK/AAB artifacts **are** part of the release contract:
  - [ ] Implement Android release packaging in CI as described in P0-002.

### Acceptance criteria

- [ ] No document claims APK/AAB release validation unless CI actually builds and uploads APK/AAB artifacts.
- [ ] No release checklist treats skipped or nonexistent Android release packaging as success.
- [ ] The release-tag follow-up section in the FIX9 ledger is accurate after this task.

---

## P0-002 — Add tag-only Android APK/AAB release packaging if Android releases are required

**Applies only if P0-001 decides Android APK/AAB artifacts are part of the release contract.**

### Tasks

- [ ] Add an Android release-artifact job to `.github/workflows/ci.yml`, or extend the existing tag-only `release-artifacts` workflow with Android packaging.
- [ ] Ensure the job runs only for tags unless an explicit manual release dry-run mode is added.
- [ ] Build the release APK and release AAB.
- [ ] Upload APK/AAB artifacts with deterministic names that include the tag name.
- [ ] If GitHub Releases are used, attach APK/AAB files to the release assets.
- [ ] Ensure the Android release-artifact job depends on:
  - [ ] Rust lint/test gates when relevant,
  - [ ] Android unit-test gate,
  - [ ] Android emulator real-data-path E2E gate,
  - [ ] full matrix signoff.
- [ ] Keep tag-only release artifact jobs out of ordinary `[full-signoff]` commit closure.

### Suggested implementation sketch

```yaml
android-release-artifacts:
  name: Android release artifacts
  if: startsWith(github.ref, 'refs/tags/')
  needs:
    - android
    - android-emulator-e2e
    - signoff
  runs-on: ubuntu-latest
  permissions:
    contents: write
  steps:
    - name: Check out repository
      uses: actions/checkout@v5

    - name: Set up JDK 17
      uses: actions/setup-java@v5
      with:
        distribution: temurin
        java-version: "17"

    - name: Install Android SDK tools
      uses: android-actions/setup-android@v4
      with:
        packages: ""

    - name: Install Android SDK components
      run: yes | sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0" "ndk;26.3.11579264"

    - name: Install Rust toolchain with Android targets
      uses: dtolnay/rust-toolchain@stable
      with:
        targets: aarch64-linux-android,x86_64-linux-android

    - name: Install cargo-ndk
      run: cargo install cargo-ndk

    - name: Build Android Rust JNI libraries
      env:
        ANDROID_NDK_HOME: ${{ env.ANDROID_SDK_ROOT }}/ndk/26.3.11579264
      run: cargo ndk -t arm64-v8a -t x86_64 -o android/app/src/main/jniLibs build -p p2p-mobile --release

    - name: Build Android release APK/AAB
      run: |
        cd android
        ./gradlew --no-daemon assembleRelease bundleRelease

    - name: Stage Android artifacts
      shell: bash
      run: |
        mkdir -p release-artifacts/android
        cp android/app/build/outputs/apk/release/*.apk release-artifacts/android/
        cp android/app/build/outputs/bundle/release/*.aab release-artifacts/android/

    - name: Upload Android release artifacts
      uses: actions/upload-artifact@v7
      with:
        name: android-webrtc-tunnel-${{ github.ref_name }}
        path: release-artifacts/android/*

    - name: Upload Android release assets
      uses: softprops/action-gh-release@v3
      with:
        files: release-artifacts/android/*
```

### Acceptance criteria

- [ ] A tag push builds APK/AAB artifacts or fails visibly.
- [ ] The release job uploads the APK/AAB files as artifacts.
- [ ] The release job attaches the APK/AAB files to the GitHub Release if release assets are enabled.
- [ ] A normal `[full-signoff]` commit still skips tag-only release packaging.
- [ ] Documentation states exactly which artifacts are produced on tag builds.

---

# P1 — Android E2E probe evidence must match the test contract

## P1-001 — Make healthy Android E2E fail if required PING/PONG evidence is missing

**Problem:** `tests/e2e/android_tunnel_e2e.sh` verifies marker delivery through the tunnel, then logs a warning if the answer log lacks the data-plane probe line. The final FIX9 run did show PING/PONG evidence, but the harness would currently pass if that evidence disappeared later.

### Tasks

- [ ] Decide whether data-plane probe evidence is a required success condition for Android emulator E2E.
- [ ] If required, change the healthy-path probe check from warning to failure.
- [ ] Keep the marker-delivery assertion as a separate required condition.
- [ ] Keep BLACK_HOLE mode behavior unchanged unless a targeted improvement is needed.
- [ ] Ensure failure output includes bounded answer logs and does not leak secrets.

### Suggested code change

In `tests/e2e/android_tunnel_e2e.sh`, change the healthy success path from warning-on-missing-probe to fail-closed behavior:

```bash
if grep -qi "received tunnel PING; sending PONG" "$ANSWER_LOG"; then
  log "verified data-plane probe: answer received PING and replied PONG"
else
  log "FAIL: marker delivered but no data-plane probe PING/PONG was seen in answer logs"
  grep -iE "ping|pong|data channel|peer connection|error" "$ANSWER_LOG" \
    | tail -30 | sed 's/^/    [answer] /' || true
  exit 1
fi
```

### Tests / validation

- [ ] Run the Android emulator real-data-path E2E in the normal healthy path.
- [ ] Confirm it still passes when marker delivery and PING/PONG evidence are both present.
- [ ] Add or run an existing negative path where probe evidence is absent or intentionally suppressed.
- [ ] Confirm the negative path fails visibly instead of passing with a warning.

### Acceptance criteria

- [ ] Android E2E success requires marker delivery.
- [ ] Android E2E success requires required probe evidence if the contract says it does.
- [ ] Missing probe evidence exits nonzero and prints bounded diagnostic lines.
- [ ] No retry-only workaround, timeout inflation, or silent skip is introduced.

---

## P1-002 — Add an explicit probe-contract test or script mode

**Problem:** The current E2E script tests BLACK_HOLE delivery failure, but there is no focused low-cost test that proves the healthy-path probe evidence check itself fails closed when the expected log line is absent.

### Tasks

- [ ] Add a focused shell test, fixture mode, or script-level unit test for the healthy-path probe-log check.
- [ ] Prefer extracting the probe verification into a small helper function that can be tested without booting an emulator.
- [ ] Keep the helper shell-only and dependency-light.

### Suggested helper shape

```bash
verify_probe_evidence() {
  local answer_log="$1"
  if grep -qi "received tunnel PING; sending PONG" "$answer_log"; then
    log "verified data-plane probe: answer received PING and replied PONG"
    return 0
  fi
  log "FAIL: marker delivered but no data-plane probe PING/PONG was seen in answer logs"
  grep -iE "ping|pong|data channel|peer connection|error" "$answer_log" \
    | tail -30 | sed 's/^/    [answer] /' || true
  return 1
}
```

### Acceptance criteria

- [ ] A fixture log with the PING/PONG line passes.
- [ ] A fixture log without the PING/PONG line fails.
- [ ] The full E2E script uses the same helper, not duplicate logic.
- [ ] The helper does not mask grep failure with a success exit.

---

# P2 — Strengthen static enforcement beyond shallow source-shape checks

## P2-001 — Inventory current static source-contract audits

**Problem:** `Fix9SourceContractAuditTest` is useful but mostly string-based. It catches common regressions, but it is not a semantic guarantee that every authoritative `Result` is consumed or that every mutation path preserves cancellation.

### Tasks

- [ ] Review `android/app/src/test/java/com/phillipchin/webrtctunnel/Fix9SourceContractAuditTest.kt`.
- [ ] List every rule currently enforced by string matching.
- [ ] Classify each rule as:
  - [ ] adequate as string matching,
  - [ ] should become regex/window-based,
  - [ ] should become AST/Detekt-based,
  - [ ] should become a runtime/unit regression test instead.
- [ ] Identify mutation/result APIs that must never be ignored.

### Acceptance criteria

- [ ] A short inventory exists in code comments or a review-source document.
- [ ] The inventory names every known authoritative `Result` API that requires consumption.
- [ ] The inventory distinguishes tripwires from proof-level enforcement.

---

## P2-002 — Add stronger ignored-Result enforcement for authoritative APIs

### Tasks

- [ ] Define the authoritative APIs whose `Result` or receipt must be consumed. Include at minimum:
  - [ ] `ConfigRepository.savePreferences(...)`
  - [ ] `ConfigRepository.prepareActiveConfigForStart(...)`
  - [ ] `ConfigRepository.writeConfigAtomically(...)`
  - [ ] `ConfigRepository.replaceConfigTransactionally(...)`
  - [ ] `ConfigRepository.saveSetupInputAtomically(...)`
  - [ ] `ConfigRepository.restoreSetupInputFileSnapshot(...)`
  - [ ] `ConfigRepository.restoreConfigSnapshot(...)`
  - [ ] `ConfigRepository.captureFilesSnapshot(...)`
  - [ ] `BrokerSecretRepository.persist(...)`
  - [ ] `BrokerSecretRepository.restore(...)`
  - [ ] `BrokerSecretRepository.captureSnapshot(...)`
  - [ ] `IdentityRepository.appendAuthorizedPublicIdentity(...)`
  - [ ] `IdentityRepository.restoreStorageSnapshot(...)`
  - [ ] `IdentityRepository.restoreIdentityPairSnapshot(...)`
  - [ ] `IdentityRepository.restoreAuthorizedKeysSnapshot(...)`
  - [ ] `ForwardsRepository` transaction/capture/restore/replace result-returning APIs.
- [ ] Add enforcement that rejects bare calls whose result is ignored.
- [ ] Reject fake consumption patterns such as:
  - [ ] `.also { }`
  - [ ] `.apply { }`
  - [ ] assignment to `_`-style unused variables if present in Kotlin code style,
  - [ ] `run { authoritativeMutation(); Unit }` where the mutation result is discarded.
- [ ] Allow explicit consumption patterns such as:
  - [ ] `.getOrThrow()` inside a cancellation-first `try`,
  - [ ] `.getOrElse { ... }`,
  - [ ] `isFailure` / `exceptionOrNull()` handling,
  - [ ] return of the `Result` to the caller,
  - [ ] conversion to visible failure state.

### Implementation options

Choose one:

- [ ] **Option A:** custom Detekt rule for ignored authoritative `Result` calls.
- [ ] **Option B:** lightweight Kotlin source scanner with function-call windows and negative fixtures.
- [ ] **Option C:** compiler/static-analysis plugin if the project already has infrastructure for it.

### Acceptance criteria

- [ ] Positive fixtures pass for legitimate consumption.
- [ ] Negative fixtures fail for ignored calls and fake `.also { }` / `.apply { }` consumption.
- [ ] The enforcement runs in ordinary Gradle `check`.
- [ ] The enforcement failure message names the file and offending API.

---

## P2-003 — Enforce cancellation-first mutation error handling

### Tasks

- [ ] Inventory production mutation paths that catch exceptions.
- [ ] Reject `runCatching` in production mutation paths unless explicitly allowlisted for non-mutating code.
- [ ] Reject `catch (Throwable)` outside approved cleanup-composition helpers.
- [ ] Reject selected-subclass-only catches around APIs that must convert all ordinary exceptions to `Result.failure`.
- [ ] Reject pre-`try` `getOrThrow()` in functions whose contract is to return `Result`.
- [ ] Keep `CancellationException` rethrow requirements explicit.

### Acceptance criteria

- [ ] `runCatching` cannot be reintroduced into authoritative mutation paths without a failing test.
- [ ] `catch (Throwable)` cannot be used to swallow fatal errors or cancellation.
- [ ] A `Result`-contract function cannot call `getOrThrow()` before entering its protecting `try` block.
- [ ] Negative fixtures cover each forbidden pattern.

---

## P2-004 — Add semantic checks for setup-operation token propagation

### Tasks

- [ ] Strengthen checks that setup controllers route async operations through `SetupOperationCoordinator.runGuarded`.
- [ ] Verify that each real controller action receives and uses `SetupOperationToken` before publishing UI state after suspend boundaries.
- [ ] Add checks for these production paths:
  - [ ] identity path import,
  - [ ] identity URI import,
  - [ ] identity generation,
  - [ ] remote public identity validation/import,
  - [ ] forward upsert,
  - [ ] forward delete,
  - [ ] validation navigation,
  - [ ] final setup save,
  - [ ] start tunnel from review.
- [ ] Add negative fixtures where a controller publishes captured stale state after a suspend call without checking freshness.

### Acceptance criteria

- [ ] A controller action that publishes after a suspend boundary without token freshness fails the audit.
- [ ] Existing legitimate code passes without adding dummy checks.
- [ ] Failure messages point to the controller/action that violated the contract.

---

# P3 — Stored identity canonical-public behavior review

## P3-001 — Decide whether stored identity resolution must require canonical public identity

**Problem:** Imported/generated private identity correctly requires canonical private identity, canonical public identity, and peer ID. Stored identity resolution currently validates decrypted private bytes and falls back to the stored public identity when the native bridge does not return canonical public identity. This may be acceptable for backward compatibility, but it is weaker than the import path.

### Tasks

- [ ] Inspect `SetupSaveController.resolveStoredIdentity(...)`.
- [ ] Determine whether native private identity validation is expected to always return canonical public identity for stored identities.
- [ ] Decide whether fallback to `material.publicIdentity` is still justified.
- [ ] If fallback is kept:
  - [ ] Document why stored identities are allowed to use the previously stored public identity.
  - [ ] Add a test proving mismatched/corrupt stored public identity cannot cause an unsafe save.
- [ ] If fallback is removed:
  - [ ] Fail closed when `canonicalPublicIdentity` is missing or blank.
  - [ ] Publish a redacted visible save failure.
  - [ ] Add migration or recovery instructions if older stored identities can lack canonical output.

### Suggested stricter behavior

```kotlin
val publicIdentity = validated.canonicalPublicIdentity
    ?.takeIf { it.isNotBlank() }
    ?: throw IllegalArgumentException("Stored private identity validation returned no canonical public identity")
```

### Acceptance criteria

- [ ] The project has an explicit decision on stored identity canonical-public behavior.
- [ ] Tests cover missing canonical public output from stored private identity validation.
- [ ] Tests cover corrupt or mismatched stored public identity if fallback remains.
- [ ] The behavior is documented and not an accidental fallback.

---

# P4 — Documentation and evidence cleanup

## P4-001 — Update review-source evidence after follow-up fixes

### Tasks

- [ ] Add a concise follow-up implementation report under `docs/review-source/` after the fixes are implemented.
- [ ] Include exact commit SHA(s), workflow run IDs, and job IDs for validation.
- [ ] Explicitly state whether Android release artifacts are in scope.
- [ ] Explicitly state whether probe PING/PONG evidence is required for Android E2E success.
- [ ] Do not claim tag artifact validation from a commit-only `[full-signoff]` run.

### Acceptance criteria

- [ ] Evidence distinguishes commit-level validation from tag-level release validation.
- [ ] Evidence does not rely on parent/sibling SHA results.
- [ ] Every closure claim names the exact SHA it applies to.

---

## P4-002 — Final validation

### Required validation before closing this follow-up

- [ ] Run full Android unit suite.
- [ ] Run full Rust/Linux/macOS matrix as applicable.
- [ ] Run Android emulator real-data-path E2E.
- [ ] Run broker-secret instrumentation if broker-secret or Android release workflow code was touched.
- [ ] Run tag-release workflow on a test tag or dry-run mechanism if release artifact packaging was changed.
- [ ] Confirm `ci/full-matrix` succeeds on the exact final commit SHA.
- [ ] Confirm no required release artifact job is skipped on a tag where it is expected to run.

### Acceptance criteria

- [ ] All required commit-level checks pass on one exact SHA.
- [ ] Any tag-only artifact claims are backed by a tag workflow run, not by a commit-only run.
- [ ] The follow-up TODO is updated to mark completed items only after evidence exists.

---

# Suggested implementation order

1. **P0-001:** resolve the release contract/documentation mismatch first.
2. **P1-001:** make probe evidence fail closed if it is a required E2E contract.
3. **P1-002:** add focused test coverage for the probe-evidence helper.
4. **P2-001 through P2-003:** strengthen static enforcement around ignored results and cancellation-first mutation handling.
5. **P2-004:** strengthen setup-token propagation audits.
6. **P3-001:** decide and test stored-identity canonical-public behavior.
7. **P4:** update evidence and run exact-SHA validation.

---

# Non-goals

- Do not reopen the core FIX9 stale setup-operation implementation unless a new bug is found in that code.
- Do not redesign the setup wizard UX.
- Do not change broker, WebRTC, or tunnel protocol behavior except where required by tests.
- Do not add broad sleeps/timeouts to Android E2E as a substitute for semantic readiness.
- Do not create a release tag merely to satisfy this TODO unless release publication is actually intended.
