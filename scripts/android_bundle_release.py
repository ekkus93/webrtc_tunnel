#!/usr/bin/env python3
"""Explicit, fail-closed Android App Bundle signing and verification helpers."""

from __future__ import annotations

import argparse
import json
import os
import re
import stat
import subprocess
import sys
import zipfile
from pathlib import Path
from typing import Mapping, Sequence

from android_release import (
    AndroidVersion,
    ReleaseContractError,
    REQUIRED_AAB_MEMBERS,
    REQUIRED_APK_MEMBERS,
    load_android_version,
    normalize_fingerprint,
    parse_aapt_badging,
    parse_apksigner_fingerprint,
    parse_keytool_fingerprint,
    require_archive_members,
    require_signing_environment,
    write_json,
)

SIGNATURE_FILE_RE = re.compile(r"^META-INF/([^/]+)\.SF$", re.IGNORECASE)
SIGNATURE_BLOCK_RE = re.compile(r"^META-INF/([^/]+)\.(RSA|DSA|EC)$", re.IGNORECASE)


def run_checked(command: Sequence[str]) -> str:
    completed = subprocess.run(
        command,
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    if completed.returncode != 0:
        raise ReleaseContractError(
            "Android release command failed:\n" + completed.stdout[-6000:]
        )
    return completed.stdout


def archive_names(path: Path) -> list[str]:
    if not path.is_file() or path.is_symlink():
        raise ReleaseContractError(f"artifact is missing or unsafe: {path.name}")
    try:
        with zipfile.ZipFile(path) as archive:
            return archive.namelist()
    except (OSError, zipfile.BadZipFile) as exc:
        raise ReleaseContractError(f"artifact is not a readable ZIP archive: {path.name}") from exc


def signature_metadata(path: Path) -> tuple[list[str], list[str]]:
    signature_files: list[str] = []
    signature_blocks: list[str] = []
    for name in archive_names(path):
        if SIGNATURE_FILE_RE.fullmatch(name):
            signature_files.append(name)
        elif SIGNATURE_BLOCK_RE.fullmatch(name):
            signature_blocks.append(name)
    return sorted(signature_files), sorted(signature_blocks)


def assert_unsigned_aab(path: Path) -> None:
    signature_files, signature_blocks = signature_metadata(path)
    if signature_files or signature_blocks:
        raise ReleaseContractError(
            "unsigned AAB input already contains JAR signature metadata"
        )
    require_archive_members(path, REQUIRED_AAB_MEMBERS)


def require_single_aab_signature(path: Path) -> None:
    signature_files, signature_blocks = signature_metadata(path)
    if len(signature_files) != 1 or len(signature_blocks) != 1:
        raise ReleaseContractError("signed AAB must contain exactly one JAR signer")
    sf_stem = SIGNATURE_FILE_RE.fullmatch(signature_files[0]).group(1).lower()
    block_stem = SIGNATURE_BLOCK_RE.fullmatch(signature_blocks[0]).group(1).lower()
    if sf_stem != block_stem:
        raise ReleaseContractError("AAB signature file and block do not identify the same signer")


def _write_secret_file(path: Path, value: str) -> None:
    if not value:
        raise ReleaseContractError(f"secret value is empty: {path.name}")
    if "\n" in value or "\r" in value:
        raise ReleaseContractError(f"secret value contains a newline: {path.name}")
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    descriptor = os.open(path, flags, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8", closefd=True) as stream:
        stream.write(value)
    os.chmod(path, 0o600)
    if stat.S_IMODE(path.stat().st_mode) != 0o600:
        raise ReleaseContractError(f"secret file permissions are not 0600: {path.name}")


def prepare_password_files(directory: Path, github_env: Path, env: Mapping[str, str]) -> dict[str, str]:
    if directory.exists():
        raise ReleaseContractError("secret-file directory already exists")
    directory.mkdir(mode=0o700, parents=False)
    os.chmod(directory, 0o700)
    if stat.S_IMODE(directory.stat().st_mode) != 0o700:
        raise ReleaseContractError("secret-file directory permissions are not 0700")

    store_path = directory / "store-password"
    key_path = directory / "key-password"
    _write_secret_file(store_path, env.get("ANDROID_RELEASE_STORE_PASSWORD", ""))
    _write_secret_file(key_path, env.get("ANDROID_RELEASE_KEY_PASSWORD", ""))

    values = {
        "ANDROID_RELEASE_STORE_PASSWORD_FILE": str(store_path),
        "ANDROID_RELEASE_KEY_PASSWORD_FILE": str(key_path),
    }
    with github_env.open("a", encoding="utf-8") as stream:
        for key, value in values.items():
            stream.write(f"{key}={value}\n")
    return values


def _require_password_file(env: Mapping[str, str], name: str) -> Path:
    value = env.get(name, "")
    if not value:
        raise ReleaseContractError(f"required password file is missing: {name}")
    path = Path(value)
    if not path.is_file() or path.is_symlink():
        raise ReleaseContractError(f"password file is missing or unsafe: {name}")
    if os.name == "posix" and stat.S_IMODE(path.stat().st_mode) != 0o600:
        raise ReleaseContractError(f"password file permissions must be 0600: {name}")
    return path


def signing_inputs(env: Mapping[str, str]) -> tuple[dict[str, str], Path, Path]:
    values = require_signing_environment(env)
    return (
        values,
        _require_password_file(env, "ANDROID_RELEASE_STORE_PASSWORD_FILE"),
        _require_password_file(env, "ANDROID_RELEASE_KEY_PASSWORD_FILE"),
    )


def strict_verify_aab(
    *,
    aab: Path,
    expected_fingerprint: str,
    jarsigner: str,
    keytool: str,
    env: Mapping[str, str],
) -> str:
    values, store_password_file, _ = signing_inputs(env)
    require_single_aab_signature(aab)
    run_checked(
        (
            jarsigner,
            "-verify",
            "-strict",
            "-verbose",
            "-certs",
            "-keystore",
            values["ANDROID_RELEASE_KEYSTORE_PATH"],
            "-storetype",
            "PKCS12",
            "-storepass:file",
            str(store_password_file),
            str(aab),
            values["ANDROID_RELEASE_KEY_ALIAS"],
        )
    )
    fingerprint = parse_keytool_fingerprint(
        run_checked((keytool, "-printcert", "-jarfile", str(aab)))
    )
    expected = normalize_fingerprint(expected_fingerprint)
    if fingerprint != expected:
        raise ReleaseContractError("AAB signing certificate does not match the pinned certificate")
    require_archive_members(aab, REQUIRED_AAB_MEMBERS)
    return fingerprint


def sign_aab(
    *,
    unsigned_aab: Path,
    signed_aab: Path,
    expected_fingerprint: str,
    jarsigner: str,
    keytool: str,
    env: Mapping[str, str],
) -> str:
    values, store_password_file, key_password_file = signing_inputs(env)
    assert_unsigned_aab(unsigned_aab)
    if signed_aab.exists() or signed_aab.is_symlink() or signed_aab == unsigned_aab:
        raise ReleaseContractError("signed AAB output must be a new, distinct file")
    signed_aab.parent.mkdir(parents=True, exist_ok=True)
    run_checked(
        (
            jarsigner,
            "-keystore",
            values["ANDROID_RELEASE_KEYSTORE_PATH"],
            "-storetype",
            "PKCS12",
            "-storepass:file",
            str(store_password_file),
            "-keypass:file",
            str(key_password_file),
            "-signedjar",
            str(signed_aab),
            str(unsigned_aab),
            values["ANDROID_RELEASE_KEY_ALIAS"],
        )
    )
    return strict_verify_aab(
        aab=signed_aab,
        expected_fingerprint=expected_fingerprint,
        jarsigner=jarsigner,
        keytool=keytool,
        env=env,
    )


def verify_apk(
    *,
    apk: Path,
    expected_fingerprint: str,
    version: AndroidVersion,
    expected_application_id: str,
    apksigner: str,
    aapt: str,
) -> dict[str, object]:
    output = run_checked(
        (
            apksigner,
            "verify",
            "--verbose",
            "--print-certs",
            "--min-sdk-version",
            "26",
            "-Werr",
            str(apk),
        )
    )
    fingerprint = parse_apksigner_fingerprint(output)
    expected = normalize_fingerprint(expected_fingerprint)
    if fingerprint != expected:
        raise ReleaseContractError("APK signing certificate does not match the pinned certificate")

    metadata = parse_aapt_badging(run_checked((aapt, "dump", "badging", str(apk))))
    if metadata.application_id != expected_application_id:
        raise ReleaseContractError("APK application ID does not match the release contract")
    if metadata.version_code != version.code or metadata.version_name != version.name:
        raise ReleaseContractError("APK version metadata does not match android/version.properties")
    if metadata.debuggable:
        raise ReleaseContractError("release APK must not be debuggable")
    if metadata.test_only:
        raise ReleaseContractError("release APK must not be test-only")
    require_archive_members(apk, REQUIRED_APK_MEMBERS)
    return {
        "certificate_sha256": fingerprint,
        "application_id": metadata.application_id,
        "version_code": metadata.version_code,
        "version_name": metadata.version_name,
        "debuggable": False,
        "test_only": False,
        "required_abis": ["arm64-v8a", "x86_64"],
    }


def extract_universal_apk(apks_archive: Path, output: Path) -> None:
    if output.exists():
        raise ReleaseContractError("generated universal APK output already exists")
    try:
        with zipfile.ZipFile(apks_archive) as archive:
            matches = [name for name in archive.namelist() if name == "universal.apk"]
            if matches != ["universal.apk"]:
                raise ReleaseContractError("APK set must contain exactly one root universal.apk")
            data = archive.read("universal.apk")
    except (OSError, zipfile.BadZipFile, KeyError) as exc:
        raise ReleaseContractError("APK set is not a readable bundletool archive") from exc
    output.parent.mkdir(parents=True, exist_ok=True)
    descriptor = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o644)
    try:
        os.write(descriptor, data)
    finally:
        os.close(descriptor)


def verify_chain(
    *,
    direct_apk: Path,
    signed_aab: Path,
    apks_archive: Path,
    generated_apk: Path,
    bundletool: Path,
    expected_fingerprint: str,
    version: AndroidVersion,
    expected_application_id: str,
    apksigner: str,
    aapt: str,
    jarsigner: str,
    keytool: str,
    java: str,
    env: Mapping[str, str],
) -> dict[str, object]:
    values, store_password_file, key_password_file = signing_inputs(env)
    direct = verify_apk(
        apk=direct_apk,
        expected_fingerprint=expected_fingerprint,
        version=version,
        expected_application_id=expected_application_id,
        apksigner=apksigner,
        aapt=aapt,
    )
    aab_fingerprint = strict_verify_aab(
        aab=signed_aab,
        expected_fingerprint=expected_fingerprint,
        jarsigner=jarsigner,
        keytool=keytool,
        env=env,
    )
    if not bundletool.is_file() or bundletool.is_symlink():
        raise ReleaseContractError("pinned bundletool JAR is missing or unsafe")
    if apks_archive.exists() or generated_apk.exists():
        raise ReleaseContractError("bundletool output paths must not already exist")

    run_checked((java, "-jar", str(bundletool), "validate", f"--bundle={signed_aab}"))
    run_checked(
        (
            java,
            "-jar",
            str(bundletool),
            "build-apks",
            f"--bundle={signed_aab}",
            f"--output={apks_archive}",
            "--mode=universal",
            f"--ks={values['ANDROID_RELEASE_KEYSTORE_PATH']}",
            f"--ks-pass=file:{store_password_file}",
            f"--ks-key-alias={values['ANDROID_RELEASE_KEY_ALIAS']}",
            f"--key-pass=file:{key_password_file}",
            "--overwrite",
        )
    )
    extract_universal_apk(apks_archive, generated_apk)
    generated = verify_apk(
        apk=generated_apk,
        expected_fingerprint=expected_fingerprint,
        version=version,
        expected_application_id=expected_application_id,
        apksigner=apksigner,
        aapt=aapt,
    )
    return {
        "schema_version": 2,
        "certificate_sha256": normalize_fingerprint(expected_fingerprint),
        "application_id": expected_application_id,
        "version_code": version.code,
        "version_name": version.name,
        "aab": {
            "certificate_sha256": aab_fingerprint,
            "strict_jar_verification": True,
            "bundletool_validation": True,
            "required_abis": ["arm64-v8a", "x86_64"],
        },
        "direct_apk": direct,
        "bundle_generated_apk": generated,
    }


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)

    password = commands.add_parser("prepare-password-files")
    password.add_argument("--directory", type=Path, required=True)
    password.add_argument("--github-env", type=Path, required=True)

    unsigned = commands.add_parser("assert-unsigned-aab")
    unsigned.add_argument("--aab", type=Path, required=True)

    sign = commands.add_parser("sign-aab")
    sign.add_argument("--unsigned-aab", type=Path, required=True)
    sign.add_argument("--signed-aab", type=Path, required=True)
    sign.add_argument("--fingerprint", required=True)
    sign.add_argument("--jarsigner", default="jarsigner")
    sign.add_argument("--keytool", default="keytool")

    verify = commands.add_parser("verify-chain")
    verify.add_argument("--properties", type=Path, required=True)
    verify.add_argument("--direct-apk", type=Path, required=True)
    verify.add_argument("--signed-aab", type=Path, required=True)
    verify.add_argument("--apks-archive", type=Path, required=True)
    verify.add_argument("--generated-apk", type=Path, required=True)
    verify.add_argument("--bundletool", type=Path, required=True)
    verify.add_argument("--fingerprint", required=True)
    verify.add_argument("--application-id", required=True)
    verify.add_argument("--apksigner", required=True)
    verify.add_argument("--aapt", required=True)
    verify.add_argument("--jarsigner", default="jarsigner")
    verify.add_argument("--keytool", default="keytool")
    verify.add_argument("--java", default="java")
    verify.add_argument("--report", type=Path, required=True)
    return parser


def main() -> int:
    args = build_parser().parse_args()
    try:
        if args.command == "prepare-password-files":
            print(json.dumps(prepare_password_files(args.directory, args.github_env, os.environ), sort_keys=True))
        elif args.command == "assert-unsigned-aab":
            assert_unsigned_aab(args.aab)
            print("unsigned AAB contract passed")
        elif args.command == "sign-aab":
            print(
                sign_aab(
                    unsigned_aab=args.unsigned_aab,
                    signed_aab=args.signed_aab,
                    expected_fingerprint=args.fingerprint,
                    jarsigner=args.jarsigner,
                    keytool=args.keytool,
                    env=os.environ,
                )
            )
        elif args.command == "verify-chain":
            report = verify_chain(
                direct_apk=args.direct_apk,
                signed_aab=args.signed_aab,
                apks_archive=args.apks_archive,
                generated_apk=args.generated_apk,
                bundletool=args.bundletool,
                expected_fingerprint=args.fingerprint,
                version=load_android_version(args.properties),
                expected_application_id=args.application_id,
                apksigner=args.apksigner,
                aapt=args.aapt,
                jarsigner=args.jarsigner,
                keytool=args.keytool,
                java=args.java,
                env=os.environ,
            )
            write_json(args.report, report)
            print(json.dumps(report, sort_keys=True))
        else:
            raise ReleaseContractError("unsupported command")
    except (OSError, ValueError, json.JSONDecodeError, ReleaseContractError) as exc:
        print(f"Android bundle release contract failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
