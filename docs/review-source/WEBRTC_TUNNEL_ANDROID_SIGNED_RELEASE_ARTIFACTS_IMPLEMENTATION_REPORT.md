# WebRTC Tunnel Android Signed Release Artifacts — Implementation Report

## Baseline and scope

- Baseline exact-SHA closure: `5b0d98186251ef2610c176c771f4388346d56805`.
- Source TODO: `docs/WEBRTC_TUNNEL_ANDROID_SIGNED_RELEASE_ARTIFACTS_TODO.md`.
- Distribution decision: direct GitHub Release APK; signed AAB retained/published with the same release for provenance and possible future manual Play submission. No automated Google Play publication.

This implementation adds a fail-closed repository pipeline. It deliberately does **not** invent, generate on a hosted runner, or commit a production private key. Production publication remains blocked by the public `UNPROVISIONED` certificate pin until the repository owner completes the offline provisioning runbook.

## Implemented contracts

### Signing and Gradle

- `android/version.properties` is the single source for Android `versionCode` and `versionName`.
- `android/release-certificate.properties` is the public production certificate pin and starts as `UNPROVISIONED`.
- `android/app/build.gradle.kts` creates `productionRelease` signing only when `-PproductionRelease=true` is explicit.
- Four required environment inputs provide the temporary PKCS#12 path/passwords/alias.
- No production path selects the debug signing configuration.
- `validateProductionReleaseSigning` checks the file type, POSIX permissions, PKCS#12 password, private-key alias, key password, and certificate fingerprint before `preReleaseBuild`.
- Release APK/AAB packaging still requires both Rust JNI ABIs.

### Workflow trust boundary

`.github/workflows/android-release.yml` has two mutually exclusive paths:

1. master push/manual **dry run** with an ephemeral, clearly non-production certificate and three-day workflow-artifact retention;
2. production publication only after a successful exact tag-triggered `CI` workflow run, through `workflow_run`, and only inside `android-production-release`.

The production job verifies at runtime that the environment:

- has required reviewers;
- disables administrator bypass;
- uses custom deployment policies;
- contains only an approved `v*`/`v*.*.*` deployment pattern.

Production secrets are environment-scoped. Forks, pull requests, normal pushes, and dry runs do not reference them.

### Artifact verification and publication

`scripts/android_release.py` implements:

- SemVer/tag equality and monotonically increasing version checks across reachable prior release tags;
- certificate pin parsing/normalization;
- signing-input and environment-policy validation;
- APK `apksigner` verification with warning-as-error behavior;
- AAB strict JAR signature verification and certificate extraction;
- application ID/version/debuggable/test-only validation from APK metadata;
- required `arm64-v8a` and `x86_64` JNI member checks in both APK and AAB;
- deterministic asset naming, exact source/workflow metadata, dependency inventories, and SHA-256 checksum generation;
- checksum verification after publication.

The production workflow additionally:

- installs and launches the signed release APK on an API 35 emulator;
- uploads a 90-day workflow artifact before release publication;
- creates GitHub artifact attestations with `actions/attest@v4`;
- refuses to replace an existing Android release asset;
- downloads every published Android asset and compares it byte-for-byte with staging;
- publishes `ci/android-release-artifacts` on the exact tagged commit.

### Negative and regression tests

- `scripts/test_android_release.py` contains pure-stdlib unit/static contract tests for versioning, signing inputs, environment protection, certificate parsing, missing/invalid signatures, debug/test-only rejection, required ABIs, checksums, workflow fail-closed fragments, and absence of debug signing fallback.
- `scripts/test_android_release_negative.sh` exercises Gradle rejection of missing passwords, missing/invalid keystores, wrong alias, fingerprint mismatch, and tag/version mismatch without printing password values.
- The push-triggered dry-run workflow exercises the complete release APK/AAB build, verification, staging, emulator smoke test, and upload path with an ephemeral identity that can never publish to a GitHub Release.

## Documentation and operational policy

- `docs/ANDROID_RELEASE_SIGNING_POLICY.md` records key ownership, certificate profile, backup/recovery, authorization, environment secrets, retention, revocation, update compatibility, and non-reproducibility boundaries.
- `docs/ANDROID_RELEASE_RUNBOOK.md` records the one-time offline provisioning steps, environment configuration, exact tag flow, consumer verification, and yank/rollback procedure.
- `.gitignore` rejects common Android private-keystore extensions.

## Validation state

Local validation completed before publication:

- Python release contract suite: 20 tests passed.
- Python bytecode compilation passed.
- negative shell script passed `bash -n`.
- workflow YAML parsed successfully.

CI validation and the push-triggered signed dry run must be observed on the implementation commit. Production release acceptance cannot be claimed until all external provisioning tasks are complete and one real exact tag passes the production workflow.

## External provisioning gate

The following cannot be completed safely through repository source changes alone:

1. generate the production PKCS#12 identity offline;
2. make and test two encrypted offline backups;
3. commit the independently verified public certificate fingerprint;
4. configure protected environment reviewers, no-admin-bypass, and the `v*` custom tag policy;
5. store the four private environment secrets;
6. approve and execute the first production tag release.

The repository intentionally fails closed until those steps are complete.


## Explicit AAB signing correction

The first production-style build successfully generated an APK and AAB, but strict JDK verification rejected the Android Gradle Plugin-signed AAB because `JarFile` and `JarInputStream` disagreed about signature metadata ordering. The verifier remained fail-closed; staging, emulator testing, and upload did not run.

The corrected chain builds the AAB unsigned, rejects pre-existing signature metadata, signs a distinct bundle with `jarsigner`, validates exactly one signer and the pinned certificate, validates the bundle with pinned bundletool 1.18.3, generates a universal APK, and verifies/smoke-tests both distribution paths. The failed run remains evidence and is not reclassified as success.
