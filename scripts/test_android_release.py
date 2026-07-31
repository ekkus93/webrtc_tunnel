#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import stat
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

import android_release as release


class AndroidVersionTest(unittest.TestCase):
    def test_reads_single_version_source(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "version.properties"
            path.write_text("versionCode=5\nversionName=0.3.2\n", encoding="utf-8")
            version = release.load_android_version(path)
            self.assertEqual(version.code, 5)
            self.assertEqual(version.name, "0.3.2")

    def test_rejects_duplicate_property(self) -> None:
        with self.assertRaises(release.ReleaseContractError):
            release.parse_properties_text("versionCode=5\nversionCode=6\nversionName=0.3.2\n", ("versionCode", "versionName"))

    def test_rejects_invalid_semver(self) -> None:
        with self.assertRaises(release.ReleaseContractError):
            release.AndroidVersion.parse(5, "0.3")

    def test_tag_must_match_version_name(self) -> None:
        version = release.AndroidVersion.parse(5, "0.3.2")
        release.validate_tag("v0.3.2", version)
        with self.assertRaises(release.ReleaseContractError):
            release.validate_tag("v0.3.3", version)

    def test_version_code_and_name_must_increase(self) -> None:
        previous = release.AndroidVersion.parse(5, "0.3.2")
        release.validate_version_progression(release.AndroidVersion.parse(6, "0.3.3"), [previous])
        with self.assertRaises(release.ReleaseContractError):
            release.validate_version_progression(release.AndroidVersion.parse(5, "0.3.3"), [previous])
        with self.assertRaises(release.ReleaseContractError):
            release.validate_version_progression(release.AndroidVersion.parse(6, "0.3.1"), [previous])

    def test_semver_prerelease_ordering(self) -> None:
        alpha = release.AndroidVersion.parse(6, "0.4.0-alpha.1")
        beta = release.AndroidVersion.parse(7, "0.4.0-beta.1")
        final = release.AndroidVersion.parse(8, "0.4.0")
        self.assertLess(alpha.compare_name(beta), 0)
        self.assertLess(beta.compare_name(final), 0)

    def test_git_history_reads_prior_version_and_enforces_monotonicity(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "android").mkdir()
            subprocess.run(("git", "init", "-q"), cwd=root, check=True)
            subprocess.run(("git", "config", "user.name", "Release Test"), cwd=root, check=True)
            subprocess.run(("git", "config", "user.email", "release-test@example.invalid"), cwd=root, check=True)
            version_path = root / "android/version.properties"
            version_path.write_text("versionCode=5\nversionName=0.3.2\n", encoding="utf-8")
            subprocess.run(("git", "add", "."), cwd=root, check=True)
            subprocess.run(("git", "commit", "-qm", "old"), cwd=root, check=True)
            subprocess.run(("git", "tag", "v0.3.2"), cwd=root, check=True)
            version_path.write_text("versionCode=6\nversionName=0.3.3\n", encoding="utf-8")
            subprocess.run(("git", "commit", "-qam", "new"), cwd=root, check=True)
            sha = subprocess.check_output(("git", "rev-parse", "HEAD"), cwd=root, text=True).strip()
            previous = release.validate_git_version_history(
                root, "v0.3.3", release.AndroidVersion.parse(6, "0.3.3"), sha
            )
            self.assertEqual(previous, ["v0.3.2"])


class CertificateAndSigningTest(unittest.TestCase):
    def test_normalizes_certificate_fingerprint(self) -> None:
        raw = ":".join(["AA"] * 32)
        self.assertEqual(release.normalize_fingerprint(raw), "aa" * 32)

    def test_unprovisioned_production_pin_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "certificate.properties"
            path.write_text("sha256=UNPROVISIONED\n", encoding="utf-8")
            with self.assertRaises(release.ReleaseContractError):
                release.load_certificate_pin(path)
            self.assertEqual(
                release.load_certificate_pin(path, allow_unprovisioned=True),
                release.PRODUCTION_PIN_PLACEHOLDER,
            )

    def test_signing_environment_requires_every_input_and_private_file(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            keystore = Path(directory) / "release.p12"
            keystore.write_bytes(b"not-a-real-keystore")
            keystore.chmod(0o600)
            env = {
                "ANDROID_RELEASE_KEYSTORE_PATH": str(keystore),
                "ANDROID_RELEASE_STORE_PASSWORD": "store",
                "ANDROID_RELEASE_KEY_ALIAS": "release",
                "ANDROID_RELEASE_KEY_PASSWORD": "key",
            }
            self.assertEqual(release.require_signing_environment(env)["ANDROID_RELEASE_KEY_ALIAS"], "release")
            for name in release.REQUIRED_SIGNING_ENV:
                broken = dict(env)
                broken[name] = ""
                with self.subTest(name=name), self.assertRaises(release.ReleaseContractError):
                    release.require_signing_environment(broken)

    @unittest.skipUnless(os.name == "posix", "POSIX permission contract")
    def test_group_readable_keystore_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            keystore = Path(directory) / "release.p12"
            keystore.write_bytes(b"x")
            keystore.chmod(stat.S_IRUSR | stat.S_IWUSR | stat.S_IRGRP)
            env = {
                "ANDROID_RELEASE_KEYSTORE_PATH": str(keystore),
                "ANDROID_RELEASE_STORE_PASSWORD": "store",
                "ANDROID_RELEASE_KEY_ALIAS": "release",
                "ANDROID_RELEASE_KEY_PASSWORD": "key",
            }
            with self.assertRaises(release.ReleaseContractError):
                release.require_signing_environment(env)

    def test_environment_requires_reviewers_no_admin_bypass_and_ref_policy(self) -> None:
        valid = {
            "protection_rules": [{"type": "required_reviewers", "reviewers": [{"type": "User"}]}],
            "can_admins_bypass": False,
            "deployment_branch_policy": {"protected_branches": False, "custom_branch_policies": True},
        }
        tag_policy = {"total_count": 1, "branch_policies": [{"name": "v*"}]}
        release.verify_environment_protection(valid, tag_policy)
        for mutation, policies in (
            ({**valid, "protection_rules": []}, tag_policy),
            ({**valid, "can_admins_bypass": True}, tag_policy),
            ({**valid, "deployment_branch_policy": None}, tag_policy),
            (valid, {"total_count": 1, "branch_policies": [{"name": "master"}]}),
        ):
            with self.assertRaises(release.ReleaseContractError):
                release.verify_environment_protection(mutation, policies)


class ArtifactVerificationTest(unittest.TestCase):
    GOOD_BADGING = "package: name='com.phillipchin.webrtctunnel' versionCode='5' versionName='0.3.2' platformBuildVersionName='15'\n"

    def test_apksigner_and_keytool_fingerprints_are_single_and_equal_shape(self) -> None:
        digest = ":".join(["AB"] * 32)
        self.assertEqual(
            release.parse_apksigner_fingerprint(f"Signer #1 certificate SHA-256 digest: {digest}\n"),
            "ab" * 32,
        )
        self.assertEqual(release.parse_keytool_fingerprint(f"SHA256: {digest}\n"), "ab" * 32)
        with self.assertRaises(release.ReleaseContractError):
            release.parse_apksigner_fingerprint("")

    def test_debuggable_and_test_only_artifacts_are_rejected_by_metadata(self) -> None:
        good = release.parse_aapt_badging(self.GOOD_BADGING)
        self.assertFalse(good.debuggable)
        self.assertFalse(good.test_only)
        self.assertTrue(release.parse_aapt_badging(self.GOOD_BADGING + "application-debuggable\n").debuggable)
        self.assertTrue(release.parse_aapt_badging(self.GOOD_BADGING + "application-testOnly\n").test_only)

    def test_required_abi_members_are_enforced(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            archive_path = Path(directory) / "app.apk"
            with zipfile.ZipFile(archive_path, "w") as archive:
                for name in release.REQUIRED_APK_MEMBERS:
                    archive.writestr(name, b"so")
            release.require_archive_members(archive_path, release.REQUIRED_APK_MEMBERS)
            with zipfile.ZipFile(archive_path, "w") as archive:
                archive.writestr(release.REQUIRED_APK_MEMBERS[0], b"so")
            with self.assertRaises(release.ReleaseContractError):
                release.require_archive_members(archive_path, release.REQUIRED_APK_MEMBERS)

    def test_wrong_fingerprint_unsigned_and_debug_artifacts_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / "app.apk"
            aab = root / "app.aab"
            for path, members in ((apk, release.REQUIRED_APK_MEMBERS), (aab, release.REQUIRED_AAB_MEMBERS)):
                with zipfile.ZipFile(path, "w") as archive:
                    for name in members:
                        archive.writestr(name, b"so")
            version = release.AndroidVersion.parse(5, "0.3.2")
            digest = "aa" * 32
            outputs = {
                "apksigner": f"Signer #1 certificate SHA-256 digest: {digest}\n",
                "jarsigner": "jar verified.\n",
                "keytool": f"SHA256: {digest}\n",
                "aapt": self.GOOD_BADGING,
            }

            def fake_run(command: tuple[str, ...], *, cwd: Path | None = None) -> str:
                del cwd
                return outputs[Path(command[0]).name]

            with mock.patch.object(release, "_run", side_effect=fake_run):
                report = release.verify_artifacts(
                    apk=apk,
                    aab=aab,
                    expected_fingerprint=digest,
                    version=version,
                    expected_application_id="com.phillipchin.webrtctunnel",
                    apksigner="apksigner",
                    aapt="aapt",
                    jarsigner="jarsigner",
                    keytool="keytool",
                )
                self.assertEqual(report["certificate_sha256"], digest)

                outputs["apksigner"] = ""
                with self.assertRaises(release.ReleaseContractError):
                    release.verify_artifacts(
                        apk=apk,
                        aab=aab,
                        expected_fingerprint=digest,
                        version=version,
                        expected_application_id="com.phillipchin.webrtctunnel",
                        apksigner="apksigner",
                        aapt="aapt",
                        jarsigner="jarsigner",
                        keytool="keytool",
                    )
                outputs["apksigner"] = f"Signer #1 certificate SHA-256 digest: {digest}\n"
                outputs["aapt"] = self.GOOD_BADGING + "application-debuggable\n"
                with self.assertRaises(release.ReleaseContractError):
                    release.verify_artifacts(
                        apk=apk,
                        aab=aab,
                        expected_fingerprint=digest,
                        version=version,
                        expected_application_id="com.phillipchin.webrtctunnel",
                        apksigner="apksigner",
                        aapt="aapt",
                        jarsigner="jarsigner",
                        keytool="keytool",
                    )


class StagingTest(unittest.TestCase):
    def test_staging_and_checksum_verification(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            inputs = {}
            for name in ("app.apk", "app.aab", "gradle.txt", "cargo.json"):
                path = root / name
                path.write_text(name, encoding="utf-8")
                inputs[name] = path
            report = root / "verification.json"
            report.write_text(json.dumps({"certificate_sha256": "aa" * 32}), encoding="utf-8")
            output = root / "release"
            staged = release.stage_artifacts(
                apk=inputs["app.apk"],
                aab=inputs["app.aab"],
                verification_report=report,
                gradle_inventory=inputs["gradle.txt"],
                cargo_inventory=inputs["cargo.json"],
                output_dir=output,
                label="v0.3.2",
                repository="ekkus93/webrtc_tunnel",
                source_sha="1" * 40,
                workflow_run_url="https://example.invalid/run",
                distribution="github_release",
            )
            self.assertEqual(len(staged), 6)
            release.verify_checksums(output)
            (output / "webrtc-tunnel-android-v0.3.2.apk").write_text("tampered", encoding="utf-8")
            with self.assertRaises(release.ReleaseContractError):
                release.verify_checksums(output)


class RepositoryContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.repo_root = Path(__file__).resolve().parents[1]

    def test_workflow_is_fail_closed_and_separates_dry_run_from_production(self) -> None:
        workflow = (self.repo_root / ".github/workflows/android-release.yml").read_text(encoding="utf-8")
        required_fragments = (
            "environment: android-production-release",
            "ANDROID_RELEASE_KEYSTORE_BASE64",
            "actions/attest@v4",
            "softprops/action-gh-release@v3",
            "verify-environment",
            "android_bundle_release.py verify-chain",
            "assert-unsigned-aab",
            "app-release-signed.aab",
            "BUNDLETOOL_JAR",
            "Run negative release contract tests",
            "github.event.workflow_run.conclusion",
            "ci/android-release-artifacts",
            "ci/android-release-dry-run",
        )
        for fragment in required_fragments:
            with self.subTest(fragment=fragment):
                self.assertIn(fragment, workflow)
        self.assertNotIn("continue-on-error: true", workflow)
        self.assertNotIn("|| true", workflow)
        self.assertNotIn("android_release.py verify-artifacts", workflow)

    def test_gradle_production_signing_never_uses_debug_config(self) -> None:
        gradle = (self.repo_root / "android/app/build.gradle.kts").read_text(encoding="utf-8")
        for fragment in (
            'gradleProperty("productionRelease")',
            'requiredReleaseEnvironment("ANDROID_RELEASE_KEYSTORE_PATH")',
            'create("productionRelease")',
            'validateProductionReleaseSigning',
            'getByName("productionRelease")',
        ):
            self.assertIn(fragment, gradle)
        self.assertNotIn('signingConfigs.getByName("debug")', gradle)

    def test_private_keystore_extensions_are_ignored(self) -> None:
        gitignore = (self.repo_root / ".gitignore").read_text(encoding="utf-8")
        for extension in ("*.jks", "*.keystore", "*.p12", "*.pfx"):
            self.assertIn(extension, gitignore)


if __name__ == "__main__":
    unittest.main()
