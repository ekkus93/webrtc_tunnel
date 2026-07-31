#!/usr/bin/env python3
from __future__ import annotations

import os
import stat
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

from android_bundle_release import (
    ReleaseContractError,
    assert_unsigned_aab,
    extract_universal_apk,
    prepare_password_files,
    require_single_aab_signature,
    signature_metadata,
)

AAB_LIBS = (
    "base/lib/arm64-v8a/libp2p_mobile.so",
    "base/lib/x86_64/libp2p_mobile.so",
)


def write_zip(path: Path, names: tuple[str, ...]) -> None:
    with zipfile.ZipFile(path, "w") as archive:
        for name in names:
            archive.writestr(name, b"data")


class BundleSignatureMetadataTests(unittest.TestCase):
    def test_unsigned_aab_accepts_required_libraries(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "app.aab"
            write_zip(path, AAB_LIBS + ("META-INF/MANIFEST.MF",))
            assert_unsigned_aab(path)

    def test_unsigned_aab_rejects_signature_file(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "app.aab"
            write_zip(path, AAB_LIBS + ("META-INF/RELEASE.SF",))
            with self.assertRaisesRegex(ReleaseContractError, "already contains"):
                assert_unsigned_aab(path)

    def test_unsigned_aab_rejects_signature_block(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "app.aab"
            write_zip(path, AAB_LIBS + ("META-INF/RELEASE.RSA",))
            with self.assertRaisesRegex(ReleaseContractError, "already contains"):
                assert_unsigned_aab(path)

    def test_single_signature_requires_matching_pair(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "app.aab"
            write_zip(path, AAB_LIBS + ("META-INF/A.SF", "META-INF/B.RSA"))
            with self.assertRaisesRegex(ReleaseContractError, "same signer"):
                require_single_aab_signature(path)

    def test_single_signature_rejects_multiple_signers(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "app.aab"
            write_zip(
                path,
                AAB_LIBS + (
                    "META-INF/A.SF",
                    "META-INF/A.RSA",
                    "META-INF/B.SF",
                    "META-INF/B.EC",
                ),
            )
            with self.assertRaisesRegex(ReleaseContractError, "exactly one"):
                require_single_aab_signature(path)

    def test_signature_metadata_is_case_insensitive(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "app.aab"
            write_zip(path, AAB_LIBS + ("meta-inf/release.sf", "Meta-Inf/Release.Ec"))
            signature_files, blocks = signature_metadata(path)
            self.assertEqual(signature_files, ["meta-inf/release.sf"])
            self.assertEqual(blocks, ["Meta-Inf/Release.Ec"])


class PasswordFileTests(unittest.TestCase):
    def test_password_files_are_private_and_reported(self) -> None:
        if os.name != "posix":
            self.skipTest("POSIX mode assertion")
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            secret_dir = root / "secrets"
            github_env = root / "github-env"
            values = prepare_password_files(
                secret_dir,
                github_env,
                {
                    "ANDROID_RELEASE_STORE_PASSWORD": "store-secret",
                    "ANDROID_RELEASE_KEY_PASSWORD": "key-secret",
                },
            )
            self.assertEqual(stat.S_IMODE(secret_dir.stat().st_mode), 0o700)
            for path in map(Path, values.values()):
                self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
            text = github_env.read_text(encoding="utf-8")
            self.assertNotIn("store-secret", text)
            self.assertNotIn("key-secret", text)

    def test_password_file_rejects_newline(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            with self.assertRaisesRegex(ReleaseContractError, "newline"):
                prepare_password_files(
                    root / "secrets",
                    root / "github-env",
                    {
                        "ANDROID_RELEASE_STORE_PASSWORD": "bad\nvalue",
                        "ANDROID_RELEASE_KEY_PASSWORD": "key-secret",
                    },
                )

    def test_password_directory_must_be_new(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "secrets").mkdir()
            with self.assertRaisesRegex(ReleaseContractError, "already exists"):
                prepare_password_files(
                    root / "secrets",
                    root / "github-env",
                    {
                        "ANDROID_RELEASE_STORE_PASSWORD": "store-secret",
                        "ANDROID_RELEASE_KEY_PASSWORD": "key-secret",
                    },
                )


class UniversalApkExtractionTests(unittest.TestCase):
    def test_extracts_exact_root_universal_apk(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            archive = root / "set.apks"
            output = root / "universal.apk"
            with zipfile.ZipFile(archive, "w") as bundle:
                bundle.writestr("universal.apk", b"apk")
            extract_universal_apk(archive, output)
            self.assertEqual(output.read_bytes(), b"apk")

    def test_rejects_nested_universal_apk(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            archive = root / "set.apks"
            with zipfile.ZipFile(archive, "w") as bundle:
                bundle.writestr("nested/universal.apk", b"apk")
            with self.assertRaisesRegex(ReleaseContractError, "exactly one root"):
                extract_universal_apk(archive, root / "universal.apk")

    def test_rejects_existing_output(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            archive = root / "set.apks"
            output = root / "universal.apk"
            output.write_bytes(b"existing")
            with zipfile.ZipFile(archive, "w") as bundle:
                bundle.writestr("universal.apk", b"apk")
            with self.assertRaisesRegex(ReleaseContractError, "already exists"):
                extract_universal_apk(archive, output)


if __name__ == "__main__":
    unittest.main()
