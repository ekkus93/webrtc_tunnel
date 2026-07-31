# Android Release Signing Policy

## Scope and distribution decision

The WebRTC Tunnel Android application is distributed directly through GitHub Releases. A production release publishes:

- one installable, production-signed APK;
- one production-signed AAB retained for provenance and possible future manual Google Play submission;
- SHA-256 checksums, exact-source metadata, and dependency inventories.

Google Play publication is **not automated** by this policy. Enrolling in Google Play App Signing or introducing a distinct upload key requires a separate reviewed change. The GitHub APK signing identity remains the update identity for direct-download users.

## Ownership and authorization

- The repository owner, `ekkus93` (Phillip Chin), is the signing-key custodian and is accountable for backup, recovery, and revocation decisions.
- A production Android release is authorized only by an exact `v<SemVer>` tag whose CI workflow completed successfully on the same commit.
- The publication job must use the `android-production-release` GitHub Environment.
- That environment must require at least one reviewer, disable administrator bypass, and restrict deployment refs to the intended release-tag policy.
- Pull requests, forks, ordinary branch pushes, and dry-run jobs cannot access production signing secrets.

## Key and certificate profile

Generate the production key on a trusted offline workstation, not on a GitHub-hosted runner:

- container: PKCS#12;
- key algorithm: RSA, 4096 bits;
- signature algorithm: SHA256withRSA;
- validity: at least 10,950 days (30 years);
- alias: a stable non-secret alias such as `webrtc-tunnel-android-release`;
- suggested subject: `CN=WebRTC Tunnel Android Release,OU=Release Engineering,O=WebRTC Tunnel Project,C=US`.

The certificate SHA-256 fingerprint is public trust metadata. After independent verification, commit it to `android/release-certificate.properties`. Production release jobs fail closed while that file contains `UNPROVISIONED`.

The private key and passwords must never be committed, attached to an issue, copied into build logs, or stored in repository variables.

## Protected environment secrets

The `android-production-release` environment contains exactly these production secrets:

- `ANDROID_RELEASE_KEYSTORE_BASE64`: base64 encoding of the PKCS#12 bytes;
- `ANDROID_RELEASE_STORE_PASSWORD`;
- `ANDROID_RELEASE_KEY_ALIAS`;
- `ANDROID_RELEASE_KEY_PASSWORD`.

The workflow decodes the key into `$RUNNER_TEMP` under `umask 077`, verifies mode `0600`, uses a no-daemon Gradle process, and deletes the temporary file in an `always()` cleanup step. Passwords are supplied through the environment, never as checked-in Gradle properties.

## Backup and recovery

Maintain two independently encrypted offline backups in separate physical locations. Each backup must contain:

- the exact PKCS#12 bytes;
- alias and password recovery material;
- the committed certificate fingerprint;
- creation date and key profile;
- restoration instructions.

At least annually, restore one backup on an isolated machine, verify its fingerprint against the repository pin, sign a non-production fixture, and record the result outside the repository. A developer-local keystore that is not covered by this process is not a production key.

## Rotation, compromise, and revocation

Direct-download APK update compatibility normally requires the same signing identity. Replacing the key without a supported signing lineage can prevent installed clients from updating. Android versions below API 28 also limit practical key-rotation compatibility, while this app supports API 26.

If compromise is suspected:

1. stop and cancel all release jobs;
2. remove environment secrets and reject pending deployments;
3. mark affected GitHub Releases as compromised and remove downloadable Android assets;
4. determine the last trustworthy tag, workflow run, checksums, and attestation;
5. decide whether a compatible signing lineage exists; otherwise publish under a new application ID and clearly communicate that it is a separate install;
6. never silently replace assets under an existing tag.

Routine rotation requires a separately reviewed migration plan, device-version compatibility analysis, and negative update tests before changing the pinned fingerprint.

## Publication and retention

- Verified workflow artifacts are retained for 90 days.
- GitHub Release assets are immutable by policy: the workflow refuses to replace an existing Android asset name.
- The workflow downloads every published asset and compares it byte-for-byte with the verified staging directory.
- GitHub artifact attestations bind the release files to the workflow, environment, repository, and exact commit.
- A bad release is yanked by removing Android assets and marking the release notes; it is never repaired in place. A corrected release uses a new version and tag.

## Reproducibility statement

The pipeline does not claim bit-for-bit reproducible Android packages. ZIP entry metadata, Android packaging tools, and signing metadata may vary between builds. The authoritative identity of a release is therefore the exact tag/SHA, pinned signing fingerprint, SHA-256 checksum set, retained workflow artifact, and GitHub provenance attestation.
