# WebRTC Tunnel — Android Signed Release Artifacts TODO

**Target branch:** `master`  
**Implementation baseline:** `5b0d98186251ef2610c176c771f4388346d56805`  
**Status:** repository pipeline implemented; production key/environment provisioning and first exact tagged release remain externally gated  
**Purpose:** publish only trustworthy, production-signed Android APK/AAB files with exact-source, signature, checksum, provenance, and negative-path evidence.

## Non-negotiable rules

- [x] No unsigned or debug-signed artifact can reach a GitHub Release.
- [x] Missing signing material, wrong aliases/passwords, mismatched fingerprints, stale versions, missing ABIs, or skipped required CI fail closed.
- [x] Production private-key material is never generated on a hosted runner, committed, logged, or exposed to pull requests/forks.
- [x] Dry-run signing is explicitly non-production and cannot publish.
- [x] Existing release assets are never silently replaced.

## P0 — Release signing policy

- [x] Assigned signing-key custody to repository owner `ekkus93` (Phillip Chin).
- [x] Chose direct GitHub Release distribution for the APK; the AAB is signed and published for provenance/future manual Play use, with no automated Play submission.
- [x] Defined offline PKCS#12 generation, two-location encrypted backup, annual restore testing, recovery, rotation, compromise, and revocation procedures.
- [x] Defined an RSA-4096/SHA256withRSA certificate profile with at least 30-year validity and a stable project subject.
- [x] Defined the public fingerprint pin in `android/release-certificate.properties`.
- [x] Restricted production authorization to exact SemVer tags after successful same-SHA CI and protected-environment approval.
- [x] Required `android-production-release`, reviewers, no administrator bypass, and custom release-tag deployment policy; the workflow verifies the environment API response.
- [x] Defined four environment-only secrets; repository/fork/PR jobs cannot read them.
- [x] Added `.gitignore` protection for common private-keystore file extensions.

### External provisioning

- [ ] Generate the real production PKCS#12 key offline.
- [ ] Create and test two encrypted offline backups.
- [ ] Replace `UNPROVISIONED` with the independently verified certificate SHA-256 fingerprint.
- [ ] Configure `android-production-release` with required reviewers, administrator bypass disabled, and only the approved `v*` custom tag policy.
- [ ] Add the four production environment secrets.

## P0 — Gradle release configuration

- [x] Added explicit `productionRelease` signing populated only from environment inputs and an explicit Gradle property.
- [x] Production configuration fails immediately when any required signing input is absent.
- [x] Debug signing remains isolated; no release path selects the debug certificate.
- [x] Added `validateProductionReleaseSigning` for PKCS#12 validity, permissions, alias/key entry, passwords, and pinned fingerprint.
- [x] Bound validation to `preReleaseBuild` for both APK and AAB production builds.
- [x] Temporary key files are created under `umask 077`, checked for group/other access, and deleted in `always()` cleanup.
- [x] Passwords are environment inputs, not command-line Gradle properties or log output.
- [x] Production `assembleRelease` and `bundleRelease` use the explicit production signing config only.
- [x] JNI library packaging remains mandatory under `-PskipRustBuild=true`.

## P1 — Version and tag integrity

- [x] Added `android/version.properties` as the single Android version source.
- [x] Gradle reads both `versionCode` and `versionName` from that file.
- [x] Production tags must exactly equal `v<versionName>`.
- [x] Version name is SemVer validated.
- [x] Production release checks every reachable prior release tag and rejects non-increasing version names or version codes.
- [x] Exact source SHA, repository, workflow run, release label, package metadata, certificate fingerprint, and reproducibility statement are recorded in release metadata.
- [x] Documented that packages are not claimed bit-for-bit reproducible; checksums and attestations bind the exact build.

## P1 — Tag-only CI job

- [x] Added `.github/workflows/android-release.yml`.
- [x] Production starts only after the exact tag-triggered `CI` workflow completes successfully.
- [x] The existing tag CI forces Android unit/lint, Docker E2E, Android emulator real-data-path E2E, and full signoff.
- [x] Production builds Rust JNI libraries for `arm64-v8a` and `x86_64`.
- [x] Production runs signed `assembleRelease` and `bundleRelease`.
- [x] Assets use deterministic tag/type names.
- [x] SHA-256 checksums and Gradle/Cargo dependency inventories are generated.
- [x] Verified workflow artifacts are uploaded for 90-day retention before GitHub Release publication.
- [x] Only files from the verified staging directory are attached to the existing GitHub Release.
- [x] The workflow refuses to replace any existing Android asset name.
- [x] Tag-only Android publication remains outside ordinary commit-level `[full-signoff]` closure.

## P1 — Signature and package verification

- [x] APK verification uses `apksigner verify --verbose --print-certs --min-sdk-version 26 -Werr`.
- [x] APK certificate SHA-256 must match the committed production pin.
- [x] AAB verification uses strict `jarsigner` verification plus `keytool -printcert -jarfile` fingerprint extraction.
- [x] Application ID, version code, and version name must match the release contract.
- [x] Required `arm64-v8a` and `x86_64` JNI libraries must exist in APK and AAB.
- [x] Debuggable and test-only APKs are rejected.
- [x] The signed release APK is installed, launched, process-checked, and version-checked on an API 35 emulator.
- [x] Upload cannot run before all signature/package/smoke checks pass.
- [x] Verification logs expose only public fingerprint/metadata, not private signing inputs.

## P2 — Provenance and publication controls

- [x] Added GitHub build provenance attestations using `actions/attest@v4` with least-required OIDC/attestation permissions.
- [x] Set dry-run artifact retention to three days and production workflow artifact retention to 90 days.
- [x] Production uses a protected environment approval gate and verifies its configuration.
- [x] Defined yank/rollback and compromised-key procedures.
- [x] Documented direct-download update compatibility and API 26–27 key-rotation limitations.
- [x] Published assets are downloaded and compared byte-for-byte with verified staging files.
- [x] Exact tagged commit receives `ci/android-release-artifacts=success` only after publication verification.

## P2 — Dry-run and negative validation

- [x] Master changes to the release pipeline trigger a full signed dry run.
- [x] Manual dry runs are restricted to an exact current `master` SHA.
- [x] Dry run creates an ephemeral, two-day, clearly non-production certificate.
- [x] Dry run builds and verifies both APK and AAB, creates inventories/checksums, runs emulator smoke, and uploads only a three-day workflow artifact.
- [x] Dry run has no GitHub Release publication step and no production environment/secrets.
- [x] Unit tests cover missing/invalid signatures, wrong fingerprint, debug/test-only artifacts, missing ABIs, checksum tampering, tag mismatch, and version regression.
- [x] Gradle negative tests cover missing password, missing/invalid keystore, wrong alias, and fingerprint mismatch.
- [x] Negative-test logs are checked to ensure signing passwords are not printed.

## Repository implementation validation

- [x] Local Python release contract suite passed (20 tests).
- [x] Python bytecode compilation passed.
- [x] Negative shell script passed syntax validation.
- [x] Workflow YAML parsed successfully.
- [ ] Exact implementation commit passes ordinary main CI.
- [ ] Exact implementation commit passes `ci/android-release-dry-run` with signed APK/AAB verification and emulator smoke evidence.

## Final production release acceptance

- [ ] All external provisioning tasks above are complete.
- [ ] One exact tagged commit passes the full required main matrix.
- [ ] The protected production job is approved and runs with the pinned key.
- [ ] APK and AAB are production-signed, verified, checksummed, and traceable to that exact tag/SHA.
- [ ] GitHub Release assets match the verified workflow artifacts byte-for-byte.
- [ ] `ci/android-release-artifacts` is successful on the exact tagged commit.
- [ ] Android artifacts are listed as generally available only after this evidence exists.

## Files added or changed

- `.github/workflows/android-release.yml`
- `.gitignore`
- `android/app/build.gradle.kts`
- `android/version.properties`
- `android/release-certificate.properties`
- `scripts/android_release.py`
- `scripts/test_android_release.py`
- `scripts/test_android_release_negative.sh`
- `docs/ANDROID_RELEASE_SIGNING_POLICY.md`
- `docs/ANDROID_RELEASE_RUNBOOK.md`
- `docs/review-source/WEBRTC_TUNNEL_ANDROID_SIGNED_RELEASE_ARTIFACTS_IMPLEMENTATION_REPORT.md`
- this ledger
