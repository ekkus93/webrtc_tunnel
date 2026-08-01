# Claude Code Handoff: Android Signed-Release Real-Device Validation

**Repository:** `ekkus93/webrtc_tunnel`  
**Branch:** `master`  
**Handoff date:** 2026-07-31  
**Baseline commit at handoff:** `83d636264caa4e3af84253ad67b92006d0457bac`  
**Primary failing workflow:** `Android signed release`  
**Authoritative failed run:** `30667939378`  
**Android package:** `com.phillipchin.webrtctunnel`  
**Launcher component:** `com.phillipchin.webrtctunnel/.MainActivity`

## 1. Purpose

Pick up the remaining Android signed-release validation failure using a real Android phone.

The signed release build, signing, static artifact verification, and staging checks have already succeeded. The remaining blocker is the runtime smoke-test step that installs and launches both release APK forms:

1. the directly assembled signed release APK; and
2. the universal APK generated from the signed Android App Bundle with Bundletool.

The latest authoritative workflow run failed in:

```text
Smoke-test direct and bundle-generated release APKs
```

The available evidence does **not** yet establish the exact runtime root cause. Do not assume that this is merely an emulator problem, an app crash, an activity-resolution problem, or brittle shell parsing until a real-device reproduction supplies evidence.

Your immediate job is to reproduce both APK variants on the attached real phone, capture deterministic evidence, identify the root cause, make the narrowest correct fix, and then restore exact-SHA CI evidence.

## 2. Repository and Workflow References

Start by reading these files at the current `master` head:

```text
.github/workflows/android-release.yml
docs/review-source/WEBRTC_TUNNEL_ANDROID_SIGNED_RELEASE_ARTIFACTS_IMPLEMENTATION_REPORT.md
.github/workflows/publish-ci-status-issues.yml
```

Relevant GitHub objects:

```text
Android signed-release run:
https://github.com/ekkus93/webrtc_tunnel/actions/runs/30667939378

CI status issue:
https://github.com/ekkus93/webrtc_tunnel/issues/6
```

Before changing anything, run:

```bash
git status --short
git branch --show-current
git rev-parse HEAD
git log -10 --oneline --decorate
```

Expected branch:

```text
master
```

The baseline when this handoff was written was:

```text
83d636264caa4e3af84253ad67b92006d0457bac
```

If `master` has advanced, inspect every intervening commit and use the current workflow as authoritative. Do not reset or overwrite newer work.

## 3. Current Known State

The authoritative failed run reached the runtime smoke test only after these categories had passed:

- release build and unit-test work;
- APK signing;
- AAB signing;
- direct signed-APK verification;
- signed-AAB verification;
- Bundletool-generated APK verification; and
- release staging/inventory checks.

The artifact-upload step did not complete because the runtime smoke test failed first.

An earlier workflow failure was caused by the smoke script being interpreted by `/usr/bin/sh`, which rejected `set -o pipefail`. That shell defect was addressed by:

```text
b8ab314f77ced3fc1503cc35a9f217595f34447e
fix(android-release): run emulator smoke tests under bash
```

The later run still failed during runtime smoke validation, so do not reopen the old shell diagnosis unless current evidence shows a regression.

Other nearby commits that may be useful when reconstructing context:

```text
542f9a1cc5d4439b5ef060002ee748991360e194
Initial Android CI-status bridge mapping; contained invalid lowercase JSON booleans in embedded Python.

e585e9d28fb62a0ca7900371c8e0cb6f9b87580b
Corrected the publisher syntax.

570f2f0b260716294be0c06579026bde200aa240
fix(ci): harden CI status issue publication

83d636264caa4e3af84253ad67b92006d0457bac
Documentation/report update used to trigger the authoritative Android workflow run.
```

## 4. Non-Negotiable Constraints

Treat the following as acceptance requirements, not suggestions.

1. **Do not weaken runtime validation to install-only validation.** Both APK variants must be shown to launch successfully.
2. **Do not hide failures behind `|| true`, blanket exception handling, warning-only behavior, or unconditional success exits.**
3. **Do not add blind retries.** A retry is acceptable only for a documented transient condition, with bounded attempts, explicit diagnostics, and final failure if the condition remains unresolved.
4. **Do not silently fall back from an explicit activity launch to `monkey`, or from one APK variant to the other.** Each required path must pass independently.
5. **Do not remove or bypass signing, certificate, Bundletool, staging, or inventory checks.**
6. **Use a clean package state between the direct APK and Bundletool APK tests.** Uninstall the package and verify it is absent before installing the next variant.
7. **Keep shell execution fail-closed.** Use Bash and `set -Eeuo pipefail` where the workflow currently depends on Bash behavior.
8. **Capture diagnostics before cleanup destroys evidence.**
9. **Do not claim success based only on a manual phone test.** Manual evidence identifies the defect; final acceptance still requires a green workflow on the exact commit containing the fix.
10. **Do not create a new branch or pull request unless the user explicitly asks for one.** Work on `master` and keep commits narrowly scoped.
11. **Do not mark the broader FIX9/release-candidate work complete solely because this Android smoke test passes.** All broader exact-SHA gates must still be checked.

## 5. First Actions

### 5.1 Inspect the existing smoke-test implementation

Read the complete runtime step and all variables/functions it depends on:

```bash
sed -n '1,360p' .github/workflows/android-release.yml
```

Determine exactly:

- how the direct APK path is selected;
- how the signed AAB is selected;
- how the universal APK is generated and extracted;
- whether the existing launch uses `monkey`, `am start`, or both;
- what output is parsed;
- which package/activity/process/window assertions are made;
- when package uninstall and emulator cleanup occur; and
- whether a failed assertion currently loses `logcat` or `dumpsys` evidence.

Do not patch from this handoff alone. Patch the current source.

### 5.2 Record the physical test device

With exactly one intended Android phone connected and authorized:

```bash
adb kill-server
adb start-server
adb devices -l
```

Record at minimum:

```bash
adb -s "$ANDROID_SERIAL" shell getprop ro.product.manufacturer
adb -s "$ANDROID_SERIAL" shell getprop ro.product.model
adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.release
adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk
adb -s "$ANDROID_SERIAL" shell getprop ro.product.cpu.abilist
adb -s "$ANDROID_SERIAL" shell getprop ro.build.fingerprint
```

If more than one device is present, set and use `ANDROID_SERIAL`. Do not allow `adb` to choose a device implicitly.

Example setup:

```bash
export ANDROID_SERIAL="$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }' | head -n 1)"
test -n "$ANDROID_SERIAL"
adb -s "$ANDROID_SERIAL" get-state | grep -Fx device
```

Reject ambiguous device selection rather than testing an unintended target.

### 5.3 Reproduce the workflow build outputs

Use the workflow's exact Java, Gradle, signing, Bundletool, and output-selection commands. Do not substitute a debug build.

At the end, identify and print absolute paths and hashes for:

```text
DIRECT_SIGNED_APK
SIGNED_AAB
BUNDLETOOL_UNIVERSAL_APK
```

For every tested artifact, record:

```bash
realpath "$APK"
sha256sum "$APK"
ls -l "$APK"
```

Preserve the existing certificate/signature verification commands from the workflow before installing either APK.

## 6. Required Real-Device Test Matrix

Run the same strict sequence independently for both APK variants.

| Test case | Artifact | Required result |
|---|---|---|
| A | Direct assembled and signed release APK | Clean install, explicit launcher resolution, successful activity start, foreground/resumed activity evidence, live process, no fatal startup crash |
| B | Bundletool-generated universal APK | Same requirements after a clean uninstall/reinstall cycle |

Do not install one variant over the other. That can preserve package data, version state, splits, permissions, or process state and make the comparison invalid.

## 7. Strict Device Test Procedure

Use a helper similar to the following, adapted to the current artifact paths and Android-version output formats. Keep assertions strict; broaden parsing only when the observed platform format proves it is necessary.

```bash
#!/usr/bin/env bash
set -Eeuo pipefail

PACKAGE="com.phillipchin.webrtctunnel"
COMPONENT="com.phillipchin.webrtctunnel/.MainActivity"
SERIAL="${ANDROID_SERIAL:?ANDROID_SERIAL must identify the test phone}"
ADB=(adb -s "$SERIAL")

print_android_diagnostics() {
    local label="$1"

    echo "===== Android diagnostics: ${label} =====" >&2
    "${ADB[@]}" devices -l >&2 || true
    "${ADB[@]}" shell pm path "$PACKAGE" >&2 || true
    "${ADB[@]}" shell cmd package resolve-activity --brief \
        -a android.intent.action.MAIN \
        -c android.intent.category.LAUNCHER \
        "$PACKAGE" >&2 || true
    "${ADB[@]}" shell dumpsys package "$PACKAGE" >&2 || true
    "${ADB[@]}" shell dumpsys activity activities >&2 || true
    "${ADB[@]}" shell dumpsys window windows >&2 || true
    "${ADB[@]}" shell pidof "$PACKAGE" >&2 || true
    "${ADB[@]}" logcat -d -v threadtime >&2 || true
    echo "===== End Android diagnostics: ${label} =====" >&2
}

fail_with_diagnostics() {
    local label="$1"
    local message="$2"
    echo "ERROR: ${message}" >&2
    print_android_diagnostics "$label"
    exit 1
}

uninstall_cleanly() {
    if "${ADB[@]}" shell pm path "$PACKAGE" >/dev/null 2>&1; then
        "${ADB[@]}" uninstall "$PACKAGE"
    fi

    if "${ADB[@]}" shell pm path "$PACKAGE" >/dev/null 2>&1; then
        fail_with_diagnostics "uninstall" \
            "Package remained installed after uninstall: ${PACKAGE}"
    fi
}

test_release_apk() {
    local label="$1"
    local apk="$2"
    local resolved_activity
    local launch_output
    local pid
    local activity_dump

    test -f "$apk" || {
        echo "ERROR: APK not found for ${label}: ${apk}" >&2
        exit 1
    }

    echo "===== Testing ${label} ====="
    realpath "$apk"
    sha256sum "$apk"

    uninstall_cleanly
    "${ADB[@]}" logcat -c

    "${ADB[@]}" install --no-streaming "$apk" || \
        fail_with_diagnostics "$label" "APK installation failed"

    "${ADB[@]}" shell pm path "$PACKAGE" | grep -q '^package:' || \
        fail_with_diagnostics "$label" "Installed package path was not found"

    resolved_activity="$(
        "${ADB[@]}" shell cmd package resolve-activity --brief \
            -a android.intent.action.MAIN \
            -c android.intent.category.LAUNCHER \
            "$PACKAGE" | tr -d '\r'
    )"
    printf 'Resolved activity: %s\n' "$resolved_activity"

    case "$resolved_activity" in
        "$COMPONENT"|"com.phillipchin.webrtctunnel/com.phillipchin.webrtctunnel.MainActivity")
            ;;
        *)
            fail_with_diagnostics "$label" \
                "Unexpected launcher resolution: ${resolved_activity}"
            ;;
    esac

    "${ADB[@]}" shell am force-stop "$PACKAGE"

    if ! launch_output="$(
        "${ADB[@]}" shell am start -W -n "$COMPONENT" 2>&1
    )"; then
        printf '%s\n' "$launch_output" >&2
        fail_with_diagnostics "$label" "Explicit activity launch failed"
    fi
    printf '%s\n' "$launch_output"

    printf '%s\n' "$launch_output" | grep -Eq '^Status: ok\r?$' || \
        fail_with_diagnostics "$label" \
            "am start -W did not report Status: ok"

    pid="$("${ADB[@]}" shell pidof "$PACKAGE" | tr -d '\r' | xargs)"
    test -n "$pid" || \
        fail_with_diagnostics "$label" "Application process is not running"
    printf 'PID: %s\n' "$pid"

    activity_dump="$("${ADB[@]}" shell dumpsys activity activities | tr -d '\r')"
    printf '%s\n' "$activity_dump"

    printf '%s\n' "$activity_dump" | grep -Fq 'com.phillipchin.webrtctunnel/.MainActivity' || \
        fail_with_diagnostics "$label" \
            "MainActivity was not present in the activity state"

    # Add an Android-version-appropriate resumed/top-resumed assertion based on
    # the actual dumpsys output from this phone and the CI emulator. Do not
    # silently accept merely finding a historical/stopped activity record.

    echo "PASS: ${label}"
}

# test_release_apk "direct signed release APK" "$DIRECT_SIGNED_APK"
# test_release_apk "Bundletool universal release APK" "$BUNDLETOOL_UNIVERSAL_APK"
```

### Important note about diagnostics

The `|| true` commands above are confined to the diagnostic printer after a failure has already been detected. They must not convert the actual validation path into success. The validation function itself must still exit nonzero when a required condition fails.

## 8. Evidence to Capture for Each APK Variant

Store or paste into the implementation report:

1. Artifact absolute path.
2. Artifact SHA-256.
3. APK signer/certificate verification output.
4. `adb install` result.
5. `pm path` output.
6. launcher `resolve-activity` output.
7. complete `am start -W` output.
8. process ID from `pidof`.
9. `dumpsys activity activities` evidence showing the expected activity is resumed/top-resumed or otherwise definitively foreground.
10. relevant `dumpsys window windows` evidence.
11. `logcat` from immediately before launch through the steady-state assertion.
12. uninstall result and proof that the package was absent before the next variant.

Capture the direct and universal results separately. Do not summarize two runs with one shared statement.

## 9. Root-Cause Questions That Must Be Answered

Use the phone evidence to resolve these questions:

1. Does the direct signed APK install and launch with an explicit component?
2. Does the Bundletool universal APK install and launch with the same explicit component?
3. Does `monkey` behave differently from `am start -W -n ...`?
4. Does `am start -W` return success while the process subsequently crashes?
5. Is `MainActivity` correctly exported and resolvable as the launcher activity in the release manifest?
6. Is the expected activity actually resumed/foreground, or is another system/app surface in front?
7. Does the application enter a VPN, permission, notification, battery-optimization, or other system flow that invalidates a simplistic foreground-window assertion?
8. Is there a release-only crash, JNI load problem, missing native library, resource shrinker issue, ProGuard/R8 issue, or initialization failure?
9. Is there a direct-vs-universal artifact difference?
10. Is the GitHub emulator failure reproducible on the phone?
11. If the phone passes, which exact CI assertion or output parser is incompatible with the emulator's real output?
12. Is the workflow checking too early for a process/activity state that is eventually valid? If so, identify the deterministic state transition rather than adding an arbitrary sleep or blind retry.

## 10. Likely Fix Categories

Only choose a category after evidence supports it.

### 10.1 Application/runtime defect

Examples:

- startup crash;
- release-only JNI/native-library failure;
- invalid manifest/export configuration;
- R8/resource shrinking defect;
- required initialization or permission path fails;
- universal APK packaging omits a required asset or native library.

Fix the application or packaging defect, add a regression test where feasible, and retain the strict smoke gate.

### 10.2 CI launch defect

If both artifacts launch correctly with an explicit activity but the workflow relies on `monkey`, replace the ambiguous launch mechanism with an explicit component launch:

```bash
adb shell am force-stop com.phillipchin.webrtctunnel
adb logcat -c
adb shell am start -W \
  -n com.phillipchin.webrtctunnel/.MainActivity
```

Assert the command's exit status and `Status: ok`, then verify foreground/resumed state and PID.

Do not retain `monkey` as a silent fallback. A separate `monkey` invocation may be retained only if it tests an intentional additional property and has its own explicit assertion.

### 10.3 CI output-parser defect

If the actual state is correct but a parser expects one Android-version-specific `dumpsys` format:

- save representative real outputs as fixtures;
- write a small parser/test rather than accumulating fragile inline `grep` expressions when practical;
- accept only documented equivalent forms;
- ensure malformed/empty output still fails; and
- include tests for both accepted and rejected states.

### 10.4 Deterministic lifecycle/timing defect

Do not solve this with an unconditional long `sleep`.

Wait for an observable condition with a strict deadline, such as:

- expected activity becomes top-resumed;
- expected package acquires a PID;
- expected application state becomes available; or
- a known system permission/VPN flow is reached.

On timeout, print diagnostics and fail.

## 11. Workflow Failure-Diagnostics Requirement

Regardless of the root cause, improve the workflow so the next runtime failure is self-diagnosing.

On failure for either APK variant, emit at minimum:

```bash
adb devices -l
adb shell pm path com.phillipchin.webrtctunnel
adb shell cmd package resolve-activity --brief \
  -a android.intent.action.MAIN \
  -c android.intent.category.LAUNCHER \
  com.phillipchin.webrtctunnel
adb shell dumpsys package com.phillipchin.webrtctunnel
adb shell dumpsys activity activities
adb shell dumpsys window windows
adb shell pidof com.phillipchin.webrtctunnel
adb logcat -d -v threadtime
```

Label diagnostics with the artifact variant so direct-APK and universal-APK evidence cannot be confused.

Keep enough logcat history to include the process start and any fatal exception, native abort, linker error, ANR, security exception, or process death.

## 12. Tests to Add or Update

Add the narrowest tests that make the diagnosed defect difficult to reintroduce.

Depending on the root cause, this may include:

- shell tests for the smoke helper;
- parser fixture tests for accepted/rejected `dumpsys` output;
- Android manifest/activity resolution tests;
- release-build startup instrumentation;
- native library packaging assertions;
- direct/universal APK inventory comparisons; or
- a regression test for the specific startup crash.

Do not add tests that merely duplicate the exact implementation without exercising the failure mode.

## 13. Commit Discipline

Use small, reviewable commits. A reasonable sequence is:

1. diagnosis fixtures/tests, if needed;
2. application or workflow fix;
3. documentation/evidence update after validation.

Examples:

```text
fix(android-release): launch signed APKs explicitly

test(android-release): cover activity-state parsing

docs(android-release): record real-device and CI evidence
```

Before each commit:

```bash
git diff --check
git status --short
git diff --stat
git diff
```

Do not commit generated signing credentials, keystores, local properties, device identifiers that should remain private, raw secrets, or unrelated workspace changes.

## 14. Post-Fix CI Verification

After pushing the narrow fix to `master`, identify the workflow run tied to the exact new commit SHA.

Do not rely on a newer run for another SHA and do not infer success from a status issue alone.

For the exact fix SHA, verify:

1. signed release APK build passes;
2. signed release AAB build passes;
3. direct APK signature/certificate verification passes;
4. AAB signature/certificate verification passes;
5. Bundletool universal APK generation and verification pass;
6. direct signed APK runtime launch passes;
7. Bundletool universal APK runtime launch passes;
8. release staging/inventory validation passes;
9. release artifacts upload successfully;
10. terminal workflow/status jobs publish a successful conclusion; and
11. GitHub issue #6 reflects the same exact commit SHA rather than stale metadata.

Record:

```text
commit SHA
workflow run ID and URL
job IDs and URLs
artifact names and IDs
conclusions for every required job
CI status issue publication SHA/run ID
```

If the run fails, use the new diagnostics to make the next change evidence-driven. Do not churn the workflow with speculative patches.

## 15. Secondary CI Status Publisher Follow-Up

After the Android runtime blocker is resolved, inspect the stale-run protection in:

```text
.github/workflows/publish-ci-status-issues.yml
```

The known race is conceptually this:

```bash
latest_run_id="$(
  gh api ".../runs?branch=master&per_page=1" \
    --jq '.workflow_runs[0].id // empty'
)"

if [[ "$latest_run_id" != "$RUN_ID" ]]; then
  # skip publication
fi
```

GitHub's run-list endpoint can lag behind the workflow-run event. A newly triggered event can therefore be incorrectly skipped merely because the list endpoint temporarily reports an older different run ID.

The correct behavior should be:

1. refetch the event run by `RUN_ID`;
2. query the latest listed run for the same workflow and branch;
3. if IDs match, publish normally;
4. if IDs differ, compare canonical `created_at` values;
5. use run ID only as a deterministic tie-break when timestamps are equal;
6. skip only when the listed run is truly newer;
7. publish the event run when it is newer despite list-endpoint lag; and
8. log the lag decision clearly.

Preserve:

- branch isolation;
- workflow identity isolation;
- issue ownership markers;
- strict API/JSON failures;
- no silent fallback to unverified metadata; and
- protection against a genuinely older run overwriting a newer status.

The status issue is:

```text
#6 — CI Status: Android Signed Release — master
```

Ownership marker:

```html
<!-- maintained by publish-ci-status-issues.yml -->
```

Treat this publisher race as a separate, narrowly tested follow-up. Do not mix it into the runtime fix unless necessary to restore exact-SHA evidence.

## 16. Broader Release/FIX9 Signoff Reminder

A green Android signed-release workflow is necessary but may not be sufficient for the broader release-candidate/FIX9 signoff.

Before claiming the overall work complete, verify all required checks and evidence on the same final exact SHA, including the currently applicable versions of:

```text
ci/rc-diagnostics
ci/full-matrix
ci/release-candidate
broker-secret permission instrumentation
Android emulator real-data-path E2E
release artifacts
```

Use the repository's current TODO/report as the authority for the final required set. Do not carry forward obsolete names blindly if the documents or workflows have changed.

## 17. Required Documentation Update

After the fix is proven, update the implementation/evidence report rather than only changing code.

At minimum, document:

- real phone manufacturer/model;
- Android version and API level;
- ABI list;
- tested artifact names and SHA-256 values;
- direct APK result;
- Bundletool universal APK result;
- exact root cause;
- why the chosen patch is correct;
- tests added;
- local validation commands/results;
- final commit SHA;
- final GitHub Actions run ID/URL;
- uploaded artifact evidence;
- CI status issue exact-SHA evidence; and
- any remaining risks or unverified device classes.

Do not write `PASS`, `complete`, `release-ready`, or equivalent language for any item that lacks evidence.

## 18. Completion Criteria for This Handoff

This handoff is complete only when all of the following are true:

- [ ] Both signed APK variants have been tested independently on the real phone.
- [ ] Device and artifact metadata have been recorded.
- [ ] Full launch diagnostics have been captured for each variant.
- [ ] The exact root cause has been identified with evidence.
- [ ] The narrowest correct app/workflow fix has been implemented.
- [ ] No validation has been weakened or silently bypassed.
- [ ] Regression coverage has been added where practical.
- [ ] Local validation passes.
- [ ] Changes are committed to `master` with a narrow commit message.
- [ ] The exact fix SHA has a green `Android signed release` run.
- [ ] Both direct and Bundletool APK runtime subtests pass in CI.
- [ ] Release artifacts upload successfully.
- [ ] CI issue #6 publishes the exact successful SHA/run.
- [ ] The implementation report contains exact evidence.
- [ ] Broader FIX9/release-candidate gates are separately verified before any overall completion claim.

## 19. What to Report Back to the User

Provide a concise but evidence-rich report containing:

1. root cause;
2. whether each APK passed or failed on the physical phone;
3. whether the failure was app-specific, artifact-specific, emulator-specific, or assertion/parser-specific;
4. files changed;
5. tests added;
6. local commands and results;
7. commit SHA;
8. final CI run ID and conclusion;
9. release artifact evidence;
10. CI issue #6 exact-SHA state; and
11. any residual risks or unfinished broader signoff work.

Do not say only that the workflow is green. Explain what was proven.

## 20. Recommended Start Sequence

Use this order:

```text
1. Confirm current master and inspect intervening commits.
2. Read the workflow and implementation report completely.
3. Reproduce the signed direct APK and signed AAB/universal APK exactly.
4. Record the phone's device/API/ABI information.
5. Run the strict direct-APK sequence and preserve diagnostics.
6. Cleanly uninstall.
7. Run the strict universal-APK sequence and preserve diagnostics.
8. Compare results and identify the exact root cause.
9. Add a focused regression test/fixture when applicable.
10. Implement the narrow app or workflow fix without weakening checks.
11. Validate locally on the phone again.
12. Commit and push to master.
13. Inspect the exact-SHA GitHub Actions run through completion.
14. Repair the CI publisher race separately if exact-SHA publication is stale.
15. Update the evidence report.
16. Verify broader release/FIX9 gates before any overall completion claim.
```

The priority is not to make the workflow green by any means available. The priority is to prove that both signed release artifacts genuinely install and enter the expected running foreground state, while retaining strict and diagnosable release gates.
