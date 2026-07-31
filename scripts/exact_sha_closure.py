#!/usr/bin/env python3
"""Fail-closed exact-SHA closure manifest validation and status aggregation."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

SCHEMA_VERSION = 1
BASELINE_REQUIRED_STATUSES = (
    "ci/rc-diagnostics",
    "ci/full-matrix",
    "ci/release-candidate",
    "ci/broker-secret-instrumentation",
)
CLOSURE_ID_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{0,63}$")
SAFE_MANIFEST_RE = re.compile(r"^\.github/closure-manifests/[A-Za-z0-9._/-]+\.json$")
SAFE_DOC_RE = re.compile(r"^docs/[A-Za-z0-9._/-]+\.md$")
UNCHECKED_TASK_RE = re.compile(r"^\s*-\s+\[ \]", re.MULTILINE)
FULL_SHA_RE = re.compile(r"^[0-9a-f]{40}$")
TERMINAL_FAILURE_STATES = {"error", "failure"}


class ClosureError(RuntimeError):
    """Expected fail-closed validation or aggregation failure."""


@dataclass(frozen=True)
class ClosureManifest:
    closure_id: str
    todo_path: str
    required_documents: tuple[str, ...]
    required_statuses: tuple[str, ...]


@dataclass(frozen=True)
class StatusEvaluation:
    state: str
    latest: dict[str, dict[str, Any]]
    missing: tuple[str, ...]
    failing: tuple[str, ...]


def load_manifest(manifest_path: Path, repo_root: Path) -> ClosureManifest:
    relative_manifest = manifest_path.as_posix()
    if manifest_path.is_absolute():
        try:
            relative_manifest = manifest_path.relative_to(repo_root).as_posix()
        except ValueError as error:
            raise ClosureError("manifest must be inside the repository") from error
    if not SAFE_MANIFEST_RE.fullmatch(relative_manifest):
        raise ClosureError(f"unsafe manifest path: {relative_manifest}")

    try:
        raw = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ClosureError(f"unable to read closure manifest: {error}") from error
    if not isinstance(raw, dict):
        raise ClosureError("closure manifest root must be an object")
    if raw.get("schema_version") != SCHEMA_VERSION:
        raise ClosureError(f"schema_version must be {SCHEMA_VERSION}")

    closure_id = raw.get("closure_id")
    if not isinstance(closure_id, str) or not CLOSURE_ID_RE.fullmatch(closure_id):
        raise ClosureError("closure_id must be a lowercase safe identifier")

    todo_path = _validate_doc_path(raw.get("todo_path"), "todo_path")
    required_documents_raw = raw.get("required_documents")
    if not isinstance(required_documents_raw, list) or not required_documents_raw:
        raise ClosureError("required_documents must be a non-empty array")
    required_documents = tuple(
        _validate_doc_path(item, f"required_documents[{index}]")
        for index, item in enumerate(required_documents_raw)
    )
    if todo_path not in required_documents:
        raise ClosureError("required_documents must include todo_path")
    if len(set(required_documents)) != len(required_documents):
        raise ClosureError("required_documents must not contain duplicates")

    statuses_raw = raw.get("required_statuses")
    if not isinstance(statuses_raw, list) or not all(isinstance(item, str) for item in statuses_raw):
        raise ClosureError("required_statuses must be an array of strings")
    required_statuses = tuple(statuses_raw)
    if required_statuses != BASELINE_REQUIRED_STATUSES:
        raise ClosureError(
            "required_statuses must exactly match the repository closure baseline; "
            "a manifest cannot weaken or reorder the gate set"
        )

    for relative_path in required_documents:
        path = repo_root / relative_path
        if not path.is_file():
            raise ClosureError(f"required document is missing: {relative_path}")

    todo_text = (repo_root / todo_path).read_text(encoding="utf-8")
    unchecked = [
        f"{line_number}:{line.strip()}"
        for line_number, line in enumerate(todo_text.splitlines(), start=1)
        if UNCHECKED_TASK_RE.match(line)
    ]
    if unchecked:
        preview = "; ".join(unchecked[:8])
        raise ClosureError(f"TODO contains unchecked tasks: {preview}")

    return ClosureManifest(
        closure_id=closure_id,
        todo_path=todo_path,
        required_documents=required_documents,
        required_statuses=required_statuses,
    )


def _validate_doc_path(value: object, field: str) -> str:
    if not isinstance(value, str) or not SAFE_DOC_RE.fullmatch(value):
        raise ClosureError(f"{field} must be a safe docs/*.md repository path")
    if ".." in Path(value).parts:
        raise ClosureError(f"{field} must not contain parent traversal")
    return value


def latest_statuses(status_payload: dict[str, Any]) -> dict[str, dict[str, Any]]:
    statuses = status_payload.get("statuses")
    if not isinstance(statuses, list):
        raise ClosureError("commit status response does not contain a statuses array")
    latest: dict[str, dict[str, Any]] = {}
    # GitHub returns newest statuses first. Preserve only the first entry for each context.
    for status in statuses:
        if not isinstance(status, dict):
            continue
        context = status.get("context")
        if isinstance(context, str) and context not in latest:
            latest[context] = status
    return latest


def evaluate_statuses(
    status_payload: dict[str, Any],
    required_statuses: Iterable[str] = BASELINE_REQUIRED_STATUSES,
) -> StatusEvaluation:
    required = tuple(required_statuses)
    latest = latest_statuses(status_payload)
    missing = tuple(context for context in required if context not in latest)
    failing = tuple(
        context
        for context in required
        if context in latest and latest[context].get("state") in TERMINAL_FAILURE_STATES
    )
    if failing:
        state = "failure"
    elif missing or any(latest[context].get("state") != "success" for context in required if context in latest):
        state = "pending"
    else:
        state = "success"
    return StatusEvaluation(state=state, latest=latest, missing=missing, failing=failing)


def validate_full_sha(value: str, field: str) -> str:
    if not FULL_SHA_RE.fullmatch(value):
        raise ClosureError(f"{field} must be a full lowercase 40-character commit SHA")
    return value


def gh_json(*args: str) -> dict[str, Any]:
    command = ["gh", "api", *args]
    completed = subprocess.run(command, check=False, capture_output=True, text=True)
    if completed.returncode != 0:
        detail = completed.stderr.strip() or completed.stdout.strip() or "unknown gh api failure"
        raise ClosureError(f"{' '.join(command)} failed: {detail}")
    try:
        result = json.loads(completed.stdout)
    except json.JSONDecodeError as error:
        raise ClosureError(f"{' '.join(command)} returned invalid JSON") from error
    if not isinstance(result, dict):
        raise ClosureError(f"{' '.join(command)} returned a non-object JSON value")
    return result


def gh_patch_issue(repository: str, issue_number: int, title: str, body: str) -> None:
    payload = json.dumps({"title": title, "body": body, "state": "open"})
    command = [
        "gh",
        "api",
        "--method",
        "PATCH",
        f"repos/{repository}/issues/{issue_number}",
        "--input",
        "-",
    ]
    completed = subprocess.run(command, input=payload, check=False, capture_output=True, text=True)
    if completed.returncode != 0:
        detail = completed.stderr.strip() or completed.stdout.strip() or "unknown issue update failure"
        raise ClosureError(f"unable to update closure issue: {detail}")


def post_commit_status(
    repository: str,
    candidate_sha: str,
    context: str,
    state: str,
    description: str,
    target_url: str,
) -> None:
    payload = json.dumps(
        {
            "state": state,
            "context": context,
            "description": description[:140],
            "target_url": target_url,
        }
    )
    command = [
        "gh",
        "api",
        "--method",
        "POST",
        f"repos/{repository}/statuses/{candidate_sha}",
        "--input",
        "-",
    ]
    completed = subprocess.run(command, input=payload, check=False, capture_output=True, text=True)
    if completed.returncode != 0:
        detail = completed.stderr.strip() or completed.stdout.strip() or "unknown status publication failure"
        raise ClosureError(f"unable to publish {context}: {detail}")


def render_issue_body(
    closure_id: str,
    candidate_sha: str,
    state: str,
    evaluation: StatusEvaluation | None,
    orchestrator_url: str,
    master_head: str,
    detail: str | None = None,
) -> str:
    lines = [
        "<!-- maintained by exact-sha-closure.yml -->",
        "# Exact SHA Closure",
        "",
        f"- **Closure:** `{closure_id}`",
        f"- **State:** `{state}`",
        f"- **Candidate:** `{candidate_sha}`",
        f"- **Current master:** `{master_head}`",
        f"- **Orchestrator:** {orchestrator_url}",
    ]
    if detail:
        lines.append(f"- **Detail:** {detail}")
    lines.extend(["", "## Required commit statuses", "", "| Context | State | Run |", "|---|---|---|"])
    for context in BASELINE_REQUIRED_STATUSES:
        status = evaluation.latest.get(context) if evaluation else None
        status_state = status.get("state") if status else "missing"
        target = status.get("target_url") if status else None
        run = f"[open]({target})" if target else "—"
        lines.append(f"| `{context}` | `{status_state}` | {run} |")
    lines.extend(
        [
            "",
            "Closure succeeds only when every status above is `success` on the exact candidate SHA.",
            "No failed or cancelled workflow is retried automatically.",
        ]
    )
    return "\n".join(lines) + "\n"


def wait_for_closure(args: argparse.Namespace) -> int:
    repository = args.repository
    candidate_sha = validate_full_sha(args.candidate_sha, "candidate_sha")
    closure_id = args.closure_id
    if not CLOSURE_ID_RE.fullmatch(closure_id):
        raise ClosureError("closure_id must be a lowercase safe identifier")
    context = f"closure/{closure_id}"
    deadline = time.monotonic() + args.timeout_seconds
    last_summary: tuple[str, ...] | None = None

    while True:
        ref = gh_json(f"repos/{repository}/git/ref/heads/master")
        master_head = ref.get("object", {}).get("sha")
        if not isinstance(master_head, str):
            raise ClosureError("master ref response did not contain an object SHA")
        if master_head != candidate_sha:
            detail = f"master moved to {master_head}; expected {candidate_sha}"
            post_commit_status(repository, candidate_sha, context, "failure", detail, args.target_url)
            body = render_issue_body(
                closure_id,
                candidate_sha,
                "failure",
                None,
                args.target_url,
                master_head,
                detail,
            )
            gh_patch_issue(repository, args.issue_number, "CI Status: Exact SHA Closure", body)
            raise ClosureError(detail)

        payload = gh_json(f"repos/{repository}/commits/{candidate_sha}/status?per_page=100")
        evaluation = evaluate_statuses(payload)
        summary = tuple(
            f"{context_name}={evaluation.latest.get(context_name, {}).get('state', 'missing')}"
            for context_name in BASELINE_REQUIRED_STATUSES
        )
        if summary != last_summary:
            print("; ".join(summary), flush=True)
            last_summary = summary

        if evaluation.state == "failure":
            detail = "required status failed: " + ", ".join(evaluation.failing)
            post_commit_status(repository, candidate_sha, context, "failure", detail, args.target_url)
            body = render_issue_body(
                closure_id,
                candidate_sha,
                "failure",
                evaluation,
                args.target_url,
                master_head,
                detail,
            )
            gh_patch_issue(repository, args.issue_number, "CI Status: Exact SHA Closure", body)
            raise ClosureError(detail)

        if evaluation.state == "success":
            detail = "all required workflows passed on the exact candidate SHA"
            body = render_issue_body(
                closure_id,
                candidate_sha,
                "success",
                evaluation,
                args.target_url,
                master_head,
                detail,
            )
            gh_patch_issue(repository, args.issue_number, "CI Status: Exact SHA Closure", body)
            summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
            if summary_path:
                Path(summary_path).write_text(body, encoding="utf-8")
            # Publish success only after every required status and the authoritative issue update
            # have succeeded. A reporting failure therefore leaves closure pending, not falsely green.
            post_commit_status(repository, candidate_sha, context, "success", detail, args.target_url)
            print(detail)
            return 0

        if time.monotonic() >= deadline:
            detail = "timed out waiting for exact-SHA statuses"
            post_commit_status(repository, candidate_sha, context, "failure", detail, args.target_url)
            body = render_issue_body(
                closure_id,
                candidate_sha,
                "failure",
                evaluation,
                args.target_url,
                master_head,
                detail,
            )
            gh_patch_issue(repository, args.issue_number, "CI Status: Exact SHA Closure", body)
            raise ClosureError(detail)
        time.sleep(args.poll_seconds)


def command_validate(args: argparse.Namespace) -> int:
    repo_root = Path(args.repo_root).resolve()
    manifest_path = (repo_root / args.manifest).resolve()
    manifest = load_manifest(manifest_path, repo_root)
    print(f"closure_id={manifest.closure_id}")
    print(f"todo_path={manifest.todo_path}")
    print("required_statuses_json=" + json.dumps(manifest.required_statuses, separators=(",", ":")))
    return 0


def command_evaluate(args: argparse.Namespace) -> int:
    payload = json.loads(Path(args.statuses_json).read_text(encoding="utf-8"))
    evaluation = evaluate_statuses(payload)
    print(evaluation.state)
    return {"success": 0, "pending": 2, "failure": 1}[evaluation.state]


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)

    validate = subparsers.add_parser("validate")
    validate.add_argument("--manifest", required=True)
    validate.add_argument("--repo-root", default=".")
    validate.set_defaults(function=command_validate)

    evaluate = subparsers.add_parser("evaluate")
    evaluate.add_argument("--statuses-json", required=True)
    evaluate.set_defaults(function=command_evaluate)

    wait = subparsers.add_parser("wait")
    wait.add_argument("--repository", required=True)
    wait.add_argument("--candidate-sha", required=True)
    wait.add_argument("--closure-id", required=True)
    wait.add_argument("--target-url", required=True)
    wait.add_argument("--issue-number", type=int, required=True)
    wait.add_argument("--timeout-seconds", type=int, default=7_200)
    wait.add_argument("--poll-seconds", type=int, default=30)
    wait.set_defaults(function=wait_for_closure)
    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    try:
        return args.function(args)
    except (ClosureError, OSError, json.JSONDecodeError) as error:
        print(f"exact-sha-closure: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
