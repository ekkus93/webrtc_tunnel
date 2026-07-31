# Android Release Runbook

## One-time production provisioning

1. Generate the PKCS#12 key offline according to `ANDROID_RELEASE_SIGNING_POLICY.md`.
2. Make two encrypted offline backups and test one restoration.
3. Obtain the certificate SHA-256 fingerprint twice using independent commands/tools.
4. Replace `UNPROVISIONED` in `android/release-certificate.properties` with the normalized 64-hex fingerprint and submit that change through the normal CI/signoff process.
5. In repository settings, create the `android-production-release` environment.
6. Configure at least one required reviewer, disable administrator bypass, and restrict deployment refs to intended `v*` release tags.
7. Add the four environment secrets documented in the signing policy. Do not add them as repository-wide secrets.
8. Run the non-production dry-run workflow. Dry-run artifacts are signed by an ephemeral certificate, retained for three days, and never attached to a GitHub Release.

The production workflow verifies the environment API response at runtime. Missing reviewers, administrator bypass, unrestricted refs, missing secrets, an unprovisioned fingerprint, or a mismatched key all stop publication.

## Preparing a release

1. Update `android/version.properties`:
   - `versionName` must be valid SemVer;
   - `versionCode` must be greater than every reachable prior release version code.
2. Keep Android-facing documentation consistent with the new version.
3. Complete normal code review and exact-SHA commit closure.
4. Create and push an exact tag `v<versionName>` on the intended commit.
5. Do not manually create or replace Android release assets.

## Automated tag flow

The tag starts the ordinary `CI` workflow. Tag path detection forces the full matrix, including Android unit/lint checks, Docker E2E, Android emulator data-plane E2E, and full signoff.

Only after that exact tag CI run completes successfully does `Android signed release` enter the protected environment. After approval it:

1. validates tag/version monotonicity and the exact source SHA;
2. verifies environment protection rules;
3. decodes and validates the production PKCS#12 key;
4. builds Rust JNI libraries for `arm64-v8a` and `x86_64`;
5. builds `assembleRelease` and `bundleRelease` with explicit production signing;
6. verifies APK and AAB signatures and the pinned certificate;
7. verifies application ID, version metadata, non-debuggable/non-test-only state, and required ABIs;
8. installs and launches the release APK on an API 35 emulator;
9. creates Gradle and Cargo dependency inventories, exact-source metadata, and SHA-256 checksums;
10. uploads the verified workflow artifact, generates provenance attestations, and attaches the same files to the existing GitHub Release;
11. downloads every Android release asset and compares it byte-for-byte with the verified staging files;
12. publishes `ci/android-release-artifacts=success` on the exact tagged commit.

No failed or cancelled production job is automatically retried. Correct the underlying defect, increment the version, and create a new tag.

## Consumer verification

For a downloaded APK:

```bash
apksigner verify --verbose --print-certs --min-sdk-version 26 -Werr webrtc-tunnel-android-vX.Y.Z.apk
sha256sum -c webrtc-tunnel-android-vX.Y.Z-SHA256SUMS
```

Compare the displayed SHA-256 certificate digest with `android/release-certificate.properties`. GitHub provenance can additionally be checked with the GitHub CLI attestation verification command against `ekkus93/webrtc_tunnel`.

## Failed or bad release

- Before publication: reject/cancel the environment deployment and inspect the failed exact-SHA status.
- After publication: remove the Android assets, mark the release notes as yanked, preserve incident evidence, and publish a corrected higher version/tag.
- Never overwrite an APK/AAB/checksum under an existing tag.
- On suspected key compromise, follow the revocation procedure in the signing policy before any further build.


## Explicit AAB signing and bundle-derived APK verification

The APK and AAB use separate, fail-closed paths:

1. Gradle builds the directly distributable APK with the protected signing identity.
2. Gradle builds an unsigned AAB without `productionRelease`.
3. CI rejects any pre-existing `.SF`/`.RSA`/`.DSA`/`.EC` signature metadata.
4. `jarsigner` signs a distinct `app-release-signed.aab` using mode-0600 password files.
5. Strict `jarsigner` verification and `keytool -printcert` prove one signer and the pinned SHA-256 certificate.
6. Pinned bundletool 1.18.3 (`a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29`) validates the AAB and produces a universal APK.
7. `apksigner`, `aapt`, ABI inspection, and emulator smoke tests validate both the direct APK and the APK generated from the signed AAB.

The unsigned AAB intermediate is never staged, uploaded, attested, or published. Password files, the temporary keystore, and generated secret directory are removed under `if: always()`.
