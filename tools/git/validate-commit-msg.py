#!/usr/bin/env python3
"""Validate a FAMS commit message with commitlint and local metadata rules."""

from __future__ import annotations

import re
import subprocess
import sys
from collections import Counter
from pathlib import Path


REQUIRED_TRAILERS = (
    "Agent-Task",
    "Agent-Model",
    "Agent-Decision",
    "Agent-Limitation",
)
TRAILER_PATTERN = re.compile(r"^(Agent-(?:Task|Model|Decision|Limitation)):(.*)$")
PLACEHOLDER_PATTERN = re.compile(r"<[^>\n]+>|\$\{[^}\n]+\}")


def fail(message: str) -> int:
    print(f"commit message rejected: {message}", file=sys.stderr)
    return 1


def run_commitlint(message_path: Path, repository_root: Path) -> int:
    """Delegate all title grammar checks to the pinned commitlint binary."""

    binary = repository_root / "tools" / "git" / "node_modules" / ".bin" / "commitlint"
    if not binary.is_file():
        print(
            "commit message rejected: pinned commitlint is not installed; "
            "run `rtk npm ci --prefix tools/git --ignore-scripts` first",
            file=sys.stderr,
        )
        return 2
    config = repository_root / "tools" / "git" / "commitlint.config.cjs"
    result = subprocess.run(
        [str(binary), "--config", str(config), "--edit", str(message_path)],
        cwd=repository_root,
        check=False,
    )
    return result.returncode


def validate_metadata(message: str) -> int:
    """Check body and trailers without duplicating commitlint's title grammar."""

    lines = [line.rstrip("\r") for line in message.splitlines() if not line.startswith("#")]
    while lines and not lines[-1].strip():
        lines.pop()
    if not lines:
        return fail("message is empty")

    all_labels = [
        match.group(1)
        for line in lines
        if (match := TRAILER_PATTERN.fullmatch(line))
    ]
    if Counter(all_labels) != Counter(REQUIRED_TRAILERS):
        return fail("each Agent trailer must appear exactly once")

    trailer_lines = lines[-len(REQUIRED_TRAILERS) :]
    labels: list[str] = []
    for line in trailer_lines:
        match = TRAILER_PATTERN.fullmatch(line)
        if not match:
            return fail("the final four lines must be Agent trailers in the required order")
        label, value = match.groups()
        if re.search(r"\bAgent-(?:Task|Model|Decision|Limitation):", value):
            return fail("each Agent trailer must occupy its own line")
        if not value.strip():
            return fail(f"{label} must have a non-empty value")
        if PLACEHOLDER_PATTERN.search(value):
            return fail(f"{label} must not contain an unresolved placeholder")
        labels.append(label)

    if labels != list(REQUIRED_TRAILERS):
        return fail("Agent trailers are missing, duplicated, or out of order")
    if len(lines) < len(REQUIRED_TRAILERS) + 3:
        return fail("a non-empty body is required between the title and trailers")

    first_trailer = len(lines) - len(REQUIRED_TRAILERS)
    if first_trailer == 0 or not lines[first_trailer - 1].strip():
        body_lines = lines[1 : max(first_trailer - 1, 1)]
    else:
        return fail("one blank line is required before the Agent trailers")
    if not body_lines or not any(line.strip() for line in body_lines):
        return fail("a non-empty body describing background and main changes is required")
    if len(lines) < 2 or lines[1].strip():
        return fail("one blank line is required after the title")
    return 0


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(f"usage: {argv[0]} <commit-message-file>", file=sys.stderr)
        return 2
    message_path = Path(argv[1]).resolve()
    try:
        message = message_path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as error:
        return fail(f"cannot read message file: {error}")

    metadata_status = validate_metadata(message)
    if metadata_status:
        return metadata_status
    return run_commitlint(message_path, Path(__file__).resolve().parents[2])


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
