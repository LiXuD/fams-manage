#!/usr/bin/env python3
"""Repeatable, behavior-oriented D6-01A local baseline verification."""

from __future__ import annotations

import csv
import re
import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MATRIX = ROOT / "docs" / "development" / "traceability-matrix.csv"
HOOK = ROOT / ".githooks" / "commit-msg"
VALIDATOR_TEST = ROOT / "tools" / "git" / "test_commit_message_validator.py"
MAX_TRACKED_BYTES = 100 * 1024 * 1024
EXPECTED_HEADER = [
    "ac_id",
    "prd_acceptance_item_reference",
    "f_flow",
    "pm_pages",
    "operation_ids",
    "business_data_module",
    "responsible_d6_task_ids",
    "test_ids",
    "evidence",
    "qa_status",
    "notes_limitations",
]
EXPECTED_TRAILERS = (
    "Agent-Task",
    "Agent-Model",
    "Agent-Decision",
    "Agent-Limitation",
)
VALID_MESSAGE = """docs(governance): verify local baseline

The verification entry checks the repository baseline and its rejection paths.

Agent-Task: D6-01A
Agent-Model: gpt-5.6-luna
Agent-Decision: Keep the local hook and behavior fixtures versioned together.
Agent-Limitation: Remote CI and repository protection are outside this task.
"""
INVALID_MESSAGE = VALID_MESSAGE.replace("docs(governance):", "docs:")
SENSITIVE_NAME_PATTERN = re.compile(
    r"(^|/)(?:\.env(?:\..*)?|.*(?:secret|credential|password)(?:$|[._-].*))$|"
    r"\.(?:pem|key|p12|jks)$",
    re.IGNORECASE,
)
SENSITIVE_CONTENT_PATTERNS = (
    re.compile(rb"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    re.compile(
        rb"(?i)(?:api[_-]?key|secret|password|session[_-]?key)\s*[:=]\s*['\"][^'\"]{12,}['\"]"
    ),
    re.compile(rb"\b(?:ghp_|github_pat_|sk-|AKIA)[A-Za-z0-9_\-/]{16,}\b"),
)


def command(args: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, cwd=ROOT, capture_output=True, text=True, check=False)


def fail(errors: list[str], message: str) -> None:
    errors.append(message)
    print(f"FAIL {message}")


def check_repository(errors: list[str]) -> None:
    branch = command(["git", "symbolic-ref", "--short", "HEAD"])
    if branch.returncode != 0 or branch.stdout.strip() != "main":
        fail(errors, f"branch is {branch.stdout.strip() or '<unavailable>'}, expected main")
    else:
        print("PASS branch main")

    hooks_path = command(["git", "config", "--local", "--get", "core.hooksPath"])
    if hooks_path.returncode != 0 or hooks_path.stdout.strip() != ".githooks":
        fail(errors, f"core.hooksPath is {hooks_path.stdout.strip() or '<unset>'}, expected .githooks")
    elif not HOOK.is_file() or not HOOK.stat().st_mode & 0o111:
        fail(errors, "versioned .githooks/commit-msg is missing or not executable")
    else:
        print("PASS versioned commit-msg hook active")

    remotes = command(["git", "remote"])
    if remotes.returncode != 0:
        fail(errors, "unable to read configured remotes")
    elif remotes.stdout.strip():
        fail(errors, f"unexpected remote configured: {remotes.stdout.strip()}")
    else:
        print("PASS no remote configured (D6-01B remains open)")


def existing_sample(relative: str) -> str | None:
    path = ROOT / relative
    if path.is_file():
        return relative
    if path.is_dir():
        for child in sorted(path.rglob("*")):
            if child.is_file():
                return child.relative_to(ROOT).as_posix()
    return None


def check_ignored_artifacts(errors: list[str]) -> None:
    samples = [
        ".DS_Store",
        ".playwright-cli",
        "output",
        ".superpowers",
        "datae.ndm2",
        "docs/superpowers/designs/2026-09-06-fams-ui/figma-native/mcp-client.log",
        "docs/superpowers/designs/2026-09-06-fams-ui/figma-native/operations.jsonl",
        ".env",
    ]
    checked = 0
    for sample in samples:
        candidate = existing_sample(sample) or sample
        result = command(["git", "check-ignore", "-q", "--no-index", "--", candidate])
        if result.returncode != 0:
            fail(errors, f"high-risk candidate is not ignored: {candidate}")
        else:
            checked += 1
    if checked == len(samples):
        print(f"PASS high-risk ignore rules ({checked} candidates)")


def tracked_files() -> list[Path]:
    result = command(["git", "ls-files", "-z"])
    if result.returncode != 0:
        return []
    return [ROOT / item for item in result.stdout.split("\0") if item]


def check_tracked_constraints(errors: list[str]) -> None:
    files = tracked_files()
    if not files:
        fail(errors, "git index has no tracked files")
        return
    oversized: list[str] = []
    sensitive_names: list[str] = []
    sensitive_content: list[str] = []
    for path in files:
        relative = path.relative_to(ROOT).as_posix()
        try:
            if path.stat().st_size >= MAX_TRACKED_BYTES:
                oversized.append(relative)
            if SENSITIVE_NAME_PATTERN.search(relative):
                sensitive_names.append(relative)
            data = path.read_bytes()
        except OSError as error:
            fail(errors, f"cannot inspect tracked file {relative}: {error}")
            continue
        if b"\0" not in data:
            for pattern in SENSITIVE_CONTENT_PATTERNS:
                if pattern.search(data):
                    sensitive_content.append(relative)
                    break
    if oversized:
        fail(errors, f"tracked files at or above 100 MiB: {', '.join(oversized)}")
    else:
        print(f"PASS tracked size constraint ({len(files)} files; 0 at or above 100 MiB)")
    if sensitive_names:
        fail(errors, f"sensitive tracked names: {', '.join(sensitive_names)}")
    else:
        print("PASS tracked sensitive-name constraint (0 findings)")
    if sensitive_content:
        fail(errors, f"sensitive tracked content patterns: {', '.join(sensitive_content)}")
    else:
        print("PASS tracked sensitive-content scan (0 findings)")


def check_matrix(errors: list[str]) -> None:
    try:
        with MATRIX.open(newline="", encoding="utf-8") as stream:
            reader = csv.DictReader(stream)
            if reader.fieldnames != EXPECTED_HEADER:
                fail(errors, "traceability matrix header does not match the required columns")
                return
            rows = list(reader)
    except (OSError, UnicodeError, csv.Error) as error:
        fail(errors, f"cannot read traceability matrix: {error}")
        return

    expected_ids = [f"AC-{number:02d}" for number in range(1, 54)]
    ids = [row.get("ac_id", "") for row in rows]
    if len(rows) != 53 or ids != expected_ids:
        fail(errors, "traceability matrix must contain exactly continuous unique AC-01..AC-53 rows")
        return
    missing_owners = [
        row["ac_id"]
        for row in rows
        if not re.search(r"\bD6-\d{2}(?:[A-C])?\b", row["responsible_d6_task_ids"])
    ]
    if missing_owners:
        fail(errors, f"matrix rows without D6 ownership: {', '.join(missing_owners)}")
        return
    invalid_unknowns = [
        row["ac_id"]
        for row in rows
        if row["operation_ids"] == ""
        or row["test_ids"] == ""
        or row["evidence"] == ""
        or row["qa_status"] == ""
    ]
    if invalid_unknowns:
        fail(errors, f"matrix rows with blank future-state fields: {', '.join(invalid_unknowns)}")
        return
    print("PASS traceability matrix (53 continuous AC rows; every row has D6 ownership)")


def check_commit_fixtures(errors: list[str]) -> None:
    result = command([sys.executable, str(VALIDATOR_TEST)])
    if result.returncode != 0:
        fail(errors, "commit-message behavioral fixtures failed")
        if result.stdout:
            print(result.stdout.rstrip())
        if result.stderr:
            print(result.stderr.rstrip())
        return
    print("PASS commit-message behavioral fixtures (compliant and rejection cases)")

    with tempfile.TemporaryDirectory(prefix="fams-d6-01a-") as directory:
        message_path = Path(directory) / "commit.msg"
        message_path.write_text(VALID_MESSAGE, encoding="utf-8")
        accepted = subprocess.run([str(HOOK), str(message_path)], cwd=ROOT, check=False)
        message_path.write_text(INVALID_MESSAGE, encoding="utf-8")
        rejected = subprocess.run([str(HOOK), str(message_path)], cwd=ROOT, check=False)
    if accepted.returncode != 0 or rejected.returncode == 0:
        fail(errors, f"hook behavior mismatch (accepted={accepted.returncode}, rejected={rejected.returncode})")
    else:
        print("PASS hook behavior (valid message accepted; invalid scope rejected)")


def main() -> int:
    errors: list[str] = []
    check_repository(errors)
    check_ignored_artifacts(errors)
    check_tracked_constraints(errors)
    check_matrix(errors)
    check_commit_fixtures(errors)
    if errors:
        print(f"D6-01A verification FAILED ({len(errors)} issue(s))")
        return 1
    print("D6-01A verification PASSED (local baseline only; D6-01B/CI/product runtime not verified)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
