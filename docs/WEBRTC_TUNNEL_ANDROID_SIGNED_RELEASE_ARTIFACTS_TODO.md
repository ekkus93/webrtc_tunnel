# WebRTC Tunnel — Android Signed Release Artifacts TODO

**Target branch:** `master`  
**Status:** future release feature; not part of FIX9 or its review follow-up closure  
**Purpose:** define and implement a trustworthy signed Android APK/AAB publication path before Android files are attached to tagged GitHub Releases.

The current tagged release workflow publishes Rust CLI/daemon archives only. The Android Gradle project can assemble release variants, but the repository does not yet define a release signing identity, protected credential flow, signature verification policy, or Android artifact provenance contract. Unsigned or debug-signed APK/AAB files must not be presented as production release assets.

## P0 — Release signing policy

- [ ] Decide who owns the Android release signing key.
- [ ] Decide whether distribution is through Google Play, direct GitHub APK download, or both.
- [ ] Define keystore generation, backup, recovery, rotation, and revocation procedures.
- [ ] Define the certificate subject, validity period, and fingerprint publication policy.
- [ ] Define who is authorized to trigger a signed release.
- [ ] Use a protected GitHub Environment for production release credentials and approvals.
- [ ] Store keystore bytes and passwords only in approved encrypted secrets; never commit them.
- [ ] Ensure pull-request workflows and untrusted forks cannot read signing secrets.

### Acceptance criteria

- [ ] A written signing policy exists before CI receives signing credentials.
- [ ] Loss or compromise recovery is documented.
- [ ] No production release depends on a developer-local undocumented keystore.

## P0 — Gradle release configuration

- [ ] Add an explicit release signing configuration populated from environment variables or temporary CI files.
- [ ] Fail closed when any required signing input is absent during a production artifact job.
- [ ] Keep debug signing isolated from release signing.
- [ ] Prevent release artifacts from silently falling back to the debug certificate.
- [ ] Ensure temporary keystore files are created with restrictive permissions and deleted after the build.
- [ ] Avoid printing secret values, keystore paths containing secrets, or command lines with passwords.

### Acceptance criteria

- [ ] `assembleRelease` and `bundleRelease` in the production job produce signed artifacts only.
- [ ] Missing or invalid signing inputs fail the job before upload.
- [ ] Debug-signed or unsigned output cannot reach the release upload step.

## P1 — Version and tag integrity

- [ ] Establish a single version source for `versionCode` and `versionName`.
- [ ] Require the release tag to match the Android version name under a documented convention.
- [ ] Reject duplicate/decreasing version codes.
- [ ] Record the exact source commit SHA in release metadata.
- [ ] Decide reproducibility expectations and document any nondeterministic Android build inputs.

### Acceptance criteria

- [ ] A mismatched tag/version fails visibly.
- [ ] Every Android artifact can be traced to one exact commit and tag.

## P1 — Tag-only CI job

- [ ] Add an `android-release-artifacts` job that runs only on intended release tags.
- [ ] Depend on Android unit/lint checks, Android emulator real-data-path E2E, and full matrix signoff.
- [ ] Build required Rust JNI libraries for release ABIs.
- [ ] Run `assembleRelease` and `bundleRelease` with production signing enabled.
- [ ] Stage artifacts under deterministic names containing version/tag and artifact type.
- [ ] Generate SHA-256 checksums.
- [ ] Generate an SBOM or dependency inventory appropriate for the Android app.
- [ ] Upload workflow artifacts before GitHub Release publication for inspection.
- [ ] Attach only verified signed artifacts/checksums to the GitHub Release.
- [ ] Keep this tag-only job outside ordinary commit-level `[full-signoff]` closure.

## P1 — Signature and package verification

- [ ] Verify the APK with `apksigner verify --verbose --print-certs`.
- [ ] Assert the signing certificate fingerprint matches the expected production fingerprint.
- [ ] Verify the AAB signature with the appropriate JAR/bundle verification tool.
- [ ] Inspect APK/AAB package name, version code, and version name.
- [ ] Verify required ABI libraries are present.
- [ ] Reject test-only/debuggable production artifacts unless explicitly intended and documented.
- [ ] Install the release APK on an emulator/device and run a release smoke test where feasible.

### Acceptance criteria

- [ ] Upload steps cannot run unless all signature/package checks pass.
- [ ] The verification log identifies fingerprints and metadata without exposing secrets.

## P2 — Provenance and publication controls

- [ ] Generate artifact provenance/attestation using GitHub-supported mechanisms.
- [ ] Retain build logs and checksums according to a release retention policy.
- [ ] Require manual approval before final GitHub Release publication if production signing is involved.
- [ ] Define rollback/yank procedure for a bad Android release.
- [ ] Document update compatibility implications of changing signing keys.

## P2 — Dry-run validation

- [ ] Add a safe dry-run mechanism that exercises build and verification without publishing a production release.
- [ ] Use a non-production signing identity for dry runs, clearly labeled and impossible to publish as production.
- [ ] Add negative tests for missing password, wrong keystore, wrong alias, fingerprint mismatch, and tag/version mismatch.
- [ ] Prove an unsigned or debug-signed artifact is rejected before upload.

## Final release acceptance

- [ ] All Android release tasks above are complete.
- [ ] One exact tagged commit passes the full required matrix.
- [ ] APK and AAB are built, signed, verified, checksummed, and traceable to that tag/SHA.
- [ ] GitHub Release assets match the verified workflow artifacts byte-for-byte.
- [ ] Documentation is updated to list Android artifacts only after this evidence exists.
