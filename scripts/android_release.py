#!/usr/bin/env python3
"""Fail-closed Android release contract helpers.

This module intentionally uses only the Python standard library so the same
validation runs in ordinary GitHub-hosted runners, dry runs, and production
release jobs without an extra package installation step.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Mapping, Sequence

SEMVER_RE = re.compile(
    r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
    r"(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?"
    r"(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$"
)
TAG_RE = re.compile(r"^v[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$")
FINGERPRINT_RE = re.compile(r"^[0-9a-f]{64}$")
APK_CERT_RE = re.compile(r"Signer #\d+ certificate SHA-256 digest:\s*([0-9A-Fa-f: ]+)")
KEYTOOL_CERT_RE = re.compile(r"SHA256:\s*([0-9A-Fa-f: ]+)")
BADGING_PACKAGE_RE = re.compile(
    r"^package:\s+name='([^']+)'\s+versionCode='([^']+)'\s+versionName='([^']+)'",
    re.MULTILINE,
)
PRODUCTION_PIN_PLACEHOLDER = "UNPROVISIONED"
REQUIRED_SIGNING_ENV = (
    "ANDROID_RELEASE_KEYSTORE_PATH",
    "ANDROID_RELEASE_STORE_PASSWORD",
    "ANDROID_RELEASE_KEY_ALIAS",
    "ANDROID_RELEASE_KEY_PASSWORD",
)
REQUIRED_APK_MEMBERS = (
    "lib/arm64-v8a/libp2p_mobile.so",
    "lib/x86_64/libp2p_mobile.so",
)
REQUIRED_AAB_MEMBERS = (
    "base/lib/arm64-v8a/libp2p_mobile.so",
    "base/lib/x86_64/libp2p_mobile.so",
)


class ReleaseContractError(RuntimeError):
    """A release contract was violated."""


@dataclass(frozen=True)
class AndroidVersion:
    code: int
    name: str
    major: int
    minor: int
    patch: int
    prerelease: tuple[str, ...]

    @classmethod
    def parse(cls, code: str | int, name: str) -> "AndroidVersion":
        try:
            parsed_code = int(code)
        except (TypeError, ValueError) as exc:
            raise ReleaseContractError("versionCode must be a positive integer") from exc
        if parsed_code <= 0:
            raise ReleaseContractError("versionCode must be a positive integer")

        match = SEMVER_RE.fullmatch(name)
        if not match:
            raise ReleaseContractError("versionName must be valid SemVer")
        prerelease = tuple((match.group(4) or "").split(".")) if match.group(4) else ()
        for item in prerelease:
            if item.isdigit() and len(item) > 1 and item.startswith("0"):
                raise ReleaseContractError("numeric SemVer prerelease identifiers cannot have leading zeroes")
        return cls(
            code=parsed_code,
            name=name,
            major=int(match.group(1)),
            minor=int(match.group(2)),
            patch=int(match.group(3)),
            prerelease=prerelease,
        )

    def compare_name(self, other: "AndroidVersion") -> int:
        left_core = (self.major, self.minor, self.patch)
        right_core = (other.major, other.minor, other.patch)
        if left_core != right_core:
            return 1 if left_core > right_core else -1
        if not self.prerelease and not other.prerelease:
            return 0
        if not self.prerelease:
            return 1
        if not other.prerelease:
            return -1
        for left, right in zip(self.prerelease, other.prerelease):
            if left == right:
                continue
            left_numeric = left.isdigit()
            right_numeric = right.isdigit()
            if left_numeric and right_numeric:
                return 1 if int(left) > int(right) else -1
            if left_numeric != right_numeric:
                return -1 if left_numeric else 1
            return 1 if left > right else -1
        if len(self.prerelease) == len(other.prerelease):
            return 0
        return 1 if len(self.prerelease) > len(other.prerelease) else -1


def parse_properties_text(text: str, required: Iterable[str]) -> dict[str, str]:
    values: dict[str, str] = {}
    for line_number, raw_line in enumerate(text.splitlines(), start=1):
        line = raw_line.strip()
        if not line or line.startswith("#") or line.startswith("!"):
            continue
        if "=" not in line:
            raise ReleaseContractError(f"invalid properties line {line_number}")
        key, value = (part.strip() for part in line.split("=", 1))
        if not key or not value:
            raise ReleaseContractError(f"empty properties key/value on line {line_number}")
        if key in values:
            raise ReleaseContractError(f"duplicate properties key: {key}")
        values[key] = value
    missing = [key for key in required if key not in values]
    if missing:
        raise ReleaseContractError(f"missing properties keys: {', '.join(missing)}")
    return values


def load_android_version(path: Path) -> AndroidVersion:
    values = parse_properties_text(path.read_text(encoding="utf-8"), ("versionCode", "versionName"))
    return AndroidVersion.parse(values["versionCode"], values["versionName"])


def load_certificate_pin(path: Path, *, allow_unprovisioned: bool = False) -> str:
    values = parse_properties_text(path.read_text(encoding="utf-8"), ("sha256",))
    value = values["sha256"].strip()
    if value == PRODUCTION_PIN_PLACEHOLDER:
        if allow_unprovisioned:
            return value
        raise ReleaseContractError("production certificate fingerprint is not provisioned")
    return normalize_fingerprint(value)


def normalize_fingerprint(value: str) -> str:
    normalized = re.sub(r"[:\s]", "", value).lower()
    if not FINGERPRINT_RE.fullmatch(normalized):
        raise ReleaseContractError("certificate fingerprint must contain exactly 64 hexadecimal digits")
    return normalized


def validate_tag(tag: str, version: AndroidVersion) -> None:
    if not TAG_RE.fullmatch(tag):
        raise ReleaseContractError("release tag must use v<SemVer>")
    expected = f"v{version.name}"
    if tag != expected:
        raise ReleaseContractError(f"release tag must equal {expected}")


def validate_version_progression(current: AndroidVersion, previous: Sequence[AndroidVersion]) -> None:
    for older in previous:
        if current.code <= older.code:
            raise ReleaseContractError(
                f"versionCode {current.code} must be greater than prior release versionCode {older.code}"
            )
        if current.compare_name(older) <= 0:
            raise ReleaseContractError(
                f"versionName {current.name} must be greater than prior release versionName {older.name}"
            )


def _run(command: Sequence[str], *, cwd: Path | None = None) -> str:
    completed = subprocess.run(
        command,
        cwd=cwd,
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    if completed.returncode != 0:
        output = completed.stdout[-4000:]
        raise ReleaseContractError(f"release verification command failed:\n{output}")
    return completed.stdout


def _git_show(repo_root: Path, spec: str) -> str | None:
    completed = subprocess.run(
        ("git", "show", spec),
        cwd=repo_root,
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
    )
    return completed.stdout if completed.returncode == 0 else None


def _version_from_gradle_text(text: str) -> AndroidVersion:
    code_match = re.search(r"\bversionCode\s*=\s*([0-9]+)", text)
    name_match = re.search(r"\bversionName\s*=\s*\"([^\"]+)\"", text)
    if not code_match or not name_match:
        raise ReleaseContractError("could not read historical Android version")
    return AndroidVersion.parse(code_match.group(1), name_match.group(1))


def load_historical_version(repo_root: Path, tag: str) -> AndroidVersion:
    properties = _git_show(repo_root, f"{tag}:android/version.properties")
    if properties is not None:
        values = parse_properties_text(properties, ("versionCode", "versionName"))
        return AndroidVersion.parse(values["versionCode"], values["versionName"])
    gradle = _git_show(repo_root, f"{tag}:android/app/build.gradle.kts")
    if gradle is None:
        raise ReleaseContractError(f"historical tag {tag} has no readable Android version source")
    return _version_from_gradle_text(gradle)


def validate_git_version_history(repo_root: Path, tag: str, version: AndroidVersion, sha: str) -> list[str]:
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ReleaseContractError("source SHA must be a full lowercase commit SHA")
    _run(("git", "cat-file", "-e", f"{sha}^{{commit}}"), cwd=repo_root)
    tags = _run(("git", "tag", "--merged", sha, "--list", "v*"), cwd=repo_root).splitlines()
    previous_tags: list[str] = []
    previous_versions: list[AndroidVersion] = []
    for candidate in sorted(set(tags)):
        if candidate == tag or not TAG_RE.fullmatch(candidate):
            continue
        candidate_version = load_historical_version(repo_root, candidate)
        if candidate_version.compare_name(version) >= 0:
            raise ReleaseContractError(f"reachable release tag {candidate} is not older than {tag}")
        previous_tags.append(candidate)
        previous_versions.append(candidate_version)
    validate_version_progression(version, previous_versions)
    return previous_tags


def require_signing_environment(env: Mapping[str, str]) -> dict[str, str]:
    values: dict[str, str] = {}
    for name in REQUIRED_SIGNING_ENV:
        value = env.get(name, "")
        if not value:
            raise ReleaseContractError(f"required signing input is missing: {name}")
        values[name] = value

    keystore = Path(values["ANDROID_RELEASE_KEYSTORE_PATH"])
    if not keystore.is_file():
        raise ReleaseContractError("production keystore is missing or not a regular file")
    if os.name == "posix":
        mode = stat.S_IMODE(keystore.stat().st_mode)
        if mode & 0o077:
            raise ReleaseContractError("production keystore permissions must not grant group/other access")
    return values


def verify_environment_protection(
    payload: Mapping[str, object], branch_policies: Mapping[str, object] | None = None
) -> None:
    rules = payload.get("protection_rules")
    if not isinstance(rules, list):
        raise ReleaseContractError("production environment has no protection rules")
    reviewer_rules = [rule for rule in rules if isinstance(rule, dict) and rule.get("type") == "required_reviewers"]
    if not reviewer_rules:
        raise ReleaseContractError("production environment must require reviewer approval")
    reviewers = reviewer_rules[0].get("reviewers")
    if not isinstance(reviewers, list) or not reviewers:
        raise ReleaseContractError("production environment reviewer list is empty")
    if payload.get("can_admins_bypass") is True:
        raise ReleaseContractError("production environment must disable administrator bypass")
    branch_policy = payload.get("deployment_branch_policy")
    if not isinstance(branch_policy, dict):
        raise ReleaseContractError("production environment must restrict deployment refs")
    if branch_policy.get("protected_branches"):
        raise ReleaseContractError("production environment must use explicit custom release-tag policies")
    if not branch_policy.get("custom_branch_policies"):
        raise ReleaseContractError("production environment deployment policy is not restrictive")
    if branch_policies is None:
        raise ReleaseContractError("production environment custom policy list is missing")
    policies = branch_policies.get("branch_policies")
    if not isinstance(policies, list) or not policies:
        raise ReleaseContractError("production environment has no custom release-tag policy")
    names = {policy.get("name") for policy in policies if isinstance(policy, dict)}
    if names != {"v*"} and names != {"v*.*.*"}:
        raise ReleaseContractError("production environment must contain only the approved v* tag policy")


def parse_apksigner_fingerprint(output: str) -> str:
    fingerprints = [normalize_fingerprint(match) for match in APK_CERT_RE.findall(output)]
    unique = sorted(set(fingerprints))
    if len(unique) != 1:
        raise ReleaseContractError("APK must have exactly one signing certificate")
    return unique[0]


def parse_keytool_fingerprint(output: str) -> str:
    fingerprints = [normalize_fingerprint(match) for match in KEYTOOL_CERT_RE.findall(output)]
    unique = sorted(set(fingerprints))
    if len(unique) != 1:
        raise ReleaseContractError("AAB must expose exactly one signing certificate")
    return unique[0]


@dataclass(frozen=True)
class PackageMetadata:
    application_id: str
    version_code: int
    version_name: str
    debuggable: bool
    test_only: bool


def parse_aapt_badging(output: str) -> PackageMetadata:
    match = BADGING_PACKAGE_RE.search(output)
    if not match:
        raise ReleaseContractError("APK package metadata is missing")
    try:
        version_code = int(match.group(2))
    except ValueError as exc:
        raise ReleaseContractError("APK versionCode is not numeric") from exc
    lowered = output.lower()
    return PackageMetadata(
        application_id=match.group(1),
        version_code=version_code,
        version_name=match.group(3),
        debuggable="application-debuggable" in lowered or "debuggable='true'" in lowered,
        test_only="application-testonly" in lowered or "testonly='true'" in lowered,
    )


def require_archive_members(path: Path, required: Sequence[str]) -> None:
    try:
        with zipfile.ZipFile(path) as archive:
            names = set(archive.namelist())
    except (OSError, zipfile.BadZipFile) as exc:
        raise ReleaseContractError(f"artifact is not a readable ZIP archive: {path.name}") from exc
    missing = [name for name in required if name not in names]
    if missing:
        raise ReleaseContractError(f"artifact {path.name} is missing required native libraries")


def verify_artifacts(
    *,
    apk: Path,
    aab: Path,
    expected_fingerprint: str,
    version: AndroidVersion,
    expected_application_id: str,
    apksigner: str,
    aapt: str,
    jarsigner: str,
    keytool: str,
) -> dict[str, object]:
    expected = normalize_fingerprint(expected_fingerprint)
    apk_output = _run((apksigner, "verify", "--verbose", "--print-certs", "--min-sdk-version", "26", "-Werr", str(apk)))
    apk_fingerprint = parse_apksigner_fingerprint(apk_output)
    if apk_fingerprint != expected:
        raise ReleaseContractError("APK signing certificate does not match the pinned production certificate")

    _run((jarsigner, "-verify", "-strict", "-verbose", "-certs", str(aab)))
    aab_cert_output = _run((keytool, "-printcert", "-jarfile", str(aab)))
    aab_fingerprint = parse_keytool_fingerprint(aab_cert_output)
    if aab_fingerprint != expected:
        raise ReleaseContractError("AAB signing certificate does not match the pinned production certificate")

    metadata = parse_aapt_badging(_run((aapt, "dump", "badging", str(apk))))
    if metadata.application_id != expected_application_id:
        raise ReleaseContractError("APK application ID does not match the release contract")
    if metadata.version_code != version.code or metadata.version_name != version.name:
        raise ReleaseContractError("APK version metadata does not match android/version.properties")
    if metadata.debuggable:
        raise ReleaseContractError("production APK must not be debuggable")
    if metadata.test_only:
        raise ReleaseContractError("production APK must not be test-only")

    require_archive_members(apk, REQUIRED_APK_MEMBERS)
    require_archive_members(aab, REQUIRED_AAB_MEMBERS)
    return {
        "schema_version": 1,
        "application_id": metadata.application_id,
        "version_code": metadata.version_code,
        "version_name": metadata.version_name,
        "certificate_sha256": expected,
        "apk_signer_count": 1,
        "aab_signer_count": 1,
        "required_abis": ["arm64-v8a", "x86_64"],
        "debuggable": False,
        "test_only": False,
    }


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def stage_artifacts(
    *,
    apk: Path,
    aab: Path,
    verification_report: Path,
    gradle_inventory: Path,
    cargo_inventory: Path,
    output_dir: Path,
    label: str,
    repository: str,
    source_sha: str,
    workflow_run_url: str,
    distribution: str,
) -> list[Path]:
    if not re.fullmatch(r"[0-9a-f]{40}", source_sha):
        raise ReleaseContractError("source SHA must be a full lowercase commit SHA")
    if not re.fullmatch(r"[0-9A-Za-z._+-]+", label):
        raise ReleaseContractError("release label contains unsafe characters")
    report = json.loads(verification_report.read_text(encoding="utf-8"))
    output_dir.mkdir(parents=True, exist_ok=False)

    staged_apk = output_dir / f"webrtc-tunnel-android-{label}.apk"
    staged_aab = output_dir / f"webrtc-tunnel-android-{label}.aab"
    staged_gradle = output_dir / f"webrtc-tunnel-android-{label}-gradle-dependencies.txt"
    staged_cargo = output_dir / f"webrtc-tunnel-android-{label}-cargo-metadata.json"
    shutil.copyfile(apk, staged_apk)
    shutil.copyfile(aab, staged_aab)
    shutil.copyfile(gradle_inventory, staged_gradle)
    shutil.copyfile(cargo_inventory, staged_cargo)

    metadata = {
        "schema_version": 1,
        "repository": repository,
        "source_sha": source_sha,
        "release_label": label,
        "distribution": distribution,
        "workflow_run_url": workflow_run_url,
        "verification": report,
        "reproducibility": {
            "bit_for_bit_reproducible": False,
            "reason": "Android packaging and signing may include nondeterministic ZIP/signature metadata; checksums and provenance identify this exact build.",
        },
    }
    metadata_path = output_dir / f"webrtc-tunnel-android-{label}-metadata.json"
    metadata_path.write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    staged = [staged_apk, staged_aab, staged_gradle, staged_cargo, metadata_path]
    checksums = output_dir / f"webrtc-tunnel-android-{label}-SHA256SUMS"
    checksums.write_text(
        "".join(f"{sha256_file(path)}  {path.name}\n" for path in sorted(staged, key=lambda item: item.name)),
        encoding="utf-8",
    )
    return staged + [checksums]


def verify_checksums(directory: Path) -> None:
    checksum_candidates = sorted(directory.glob("webrtc-tunnel-android-*-SHA256SUMS"))
    if len(checksum_candidates) != 1:
        raise ReleaseContractError("exactly one Android SHA256SUMS file is required")
    checksum_path = checksum_candidates[0]
    seen: set[str] = set()
    for line_number, line in enumerate(checksum_path.read_text(encoding="utf-8").splitlines(), start=1):
        match = re.fullmatch(r"([0-9a-f]{64})  ([0-9A-Za-z._+-]+)", line)
        if not match:
            raise ReleaseContractError(f"invalid SHA256SUMS line {line_number}")
        expected, name = match.groups()
        if name in seen or name == checksum_path.name:
            raise ReleaseContractError("duplicate or recursive checksum entry")
        seen.add(name)
        path = directory / name
        if not path.is_file() or path.is_symlink():
            raise ReleaseContractError(f"checksummed release file is missing or unsafe: {name}")
        if sha256_file(path) != expected:
            raise ReleaseContractError(f"release asset checksum mismatch: {name}")
    if not seen:
        raise ReleaseContractError("SHA256SUMS contains no entries")


def write_json(path: Path, payload: Mapping[str, object]) -> None:
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def command_version(args: argparse.Namespace) -> None:
    version = load_android_version(args.properties)
    payload = {"version_code": version.code, "version_name": version.name, "tag": f"v{version.name}"}
    if args.github_output:
        with args.github_output.open("a", encoding="utf-8") as stream:
            for key, value in payload.items():
                stream.write(f"{key}={value}\n")
    print(json.dumps(payload, sort_keys=True))


def command_certificate(args: argparse.Namespace) -> None:
    print(load_certificate_pin(args.properties, allow_unprovisioned=args.allow_unprovisioned))


def command_validate_tag(args: argparse.Namespace) -> None:
    version = load_android_version(args.properties)
    validate_tag(args.tag, version)
    previous = validate_git_version_history(args.repo_root, args.tag, version, args.sha)
    print(json.dumps({"tag": args.tag, "source_sha": args.sha, "previous_tags": previous}, sort_keys=True))


def command_validate_signing(args: argparse.Namespace) -> None:
    require_signing_environment(os.environ)
    print("signing environment contract passed")


def command_verify_environment(args: argparse.Namespace) -> None:
    payload = json.loads(args.input.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ReleaseContractError("environment API response must be an object")
    branch_payload = json.loads(args.branch_policies.read_text(encoding="utf-8"))
    if not isinstance(branch_payload, dict):
        raise ReleaseContractError("branch policy API response must be an object")
    verify_environment_protection(payload, branch_payload)
    print("production environment protection contract passed")


def command_verify_artifacts(args: argparse.Namespace) -> None:
    version = load_android_version(args.properties)
    report = verify_artifacts(
        apk=args.apk,
        aab=args.aab,
        expected_fingerprint=args.fingerprint,
        version=version,
        expected_application_id=args.application_id,
        apksigner=args.apksigner,
        aapt=args.aapt,
        jarsigner=args.jarsigner,
        keytool=args.keytool,
    )
    write_json(args.report, report)
    print(json.dumps(report, sort_keys=True))


def command_stage(args: argparse.Namespace) -> None:
    staged = stage_artifacts(
        apk=args.apk,
        aab=args.aab,
        verification_report=args.verification_report,
        gradle_inventory=args.gradle_inventory,
        cargo_inventory=args.cargo_inventory,
        output_dir=args.output_dir,
        label=args.label,
        repository=args.repository,
        source_sha=args.source_sha,
        workflow_run_url=args.workflow_run_url,
        distribution=args.distribution,
    )
    print("\n".join(str(path) for path in staged))


def command_verify_checksums(args: argparse.Namespace) -> None:
    verify_checksums(args.directory)
    print("release checksums verified")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    version_parser = subparsers.add_parser("version")
    version_parser.add_argument("--properties", type=Path, required=True)
    version_parser.add_argument("--github-output", type=Path)
    version_parser.set_defaults(function=command_version)

    certificate_parser = subparsers.add_parser("certificate")
    certificate_parser.add_argument("--properties", type=Path, required=True)
    certificate_parser.add_argument("--allow-unprovisioned", action="store_true")
    certificate_parser.set_defaults(function=command_certificate)

    tag_parser = subparsers.add_parser("validate-tag")
    tag_parser.add_argument("--properties", type=Path, required=True)
    tag_parser.add_argument("--repo-root", type=Path, required=True)
    tag_parser.add_argument("--tag", required=True)
    tag_parser.add_argument("--sha", required=True)
    tag_parser.set_defaults(function=command_validate_tag)

    signing_parser = subparsers.add_parser("validate-signing-env")
    signing_parser.set_defaults(function=command_validate_signing)

    environment_parser = subparsers.add_parser("verify-environment")
    environment_parser.add_argument("--input", type=Path, required=True)
    environment_parser.add_argument("--branch-policies", type=Path, required=True)
    environment_parser.set_defaults(function=command_verify_environment)

    stage_parser = subparsers.add_parser("stage")
    stage_parser.add_argument("--apk", type=Path, required=True)
    stage_parser.add_argument("--aab", type=Path, required=True)
    stage_parser.add_argument("--verification-report", type=Path, required=True)
    stage_parser.add_argument("--gradle-inventory", type=Path, required=True)
    stage_parser.add_argument("--cargo-inventory", type=Path, required=True)
    stage_parser.add_argument("--output-dir", type=Path, required=True)
    stage_parser.add_argument("--label", required=True)
    stage_parser.add_argument("--repository", required=True)
    stage_parser.add_argument("--source-sha", required=True)
    stage_parser.add_argument("--workflow-run-url", required=True)
    stage_parser.add_argument("--distribution", choices=("dry_run", "github_release"), required=True)
    stage_parser.set_defaults(function=command_stage)

    checksum_parser = subparsers.add_parser("verify-checksums")
    checksum_parser.add_argument("--directory", type=Path, required=True)
    checksum_parser.set_defaults(function=command_verify_checksums)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        args.function(args)
    except (ReleaseContractError, OSError, json.JSONDecodeError) as exc:
        print(f"android release contract failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
