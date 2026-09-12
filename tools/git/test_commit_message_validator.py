#!/usr/bin/env python3
"""Behavioral tests for the repository commit-message validator."""

from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
VALIDATOR = REPO_ROOT / "tools" / "git" / "validate-commit-msg.py"


COMPLIANT = """docs(governance): establish local baseline

The baseline needs reproducible local checks before product implementation.
Add the traceability matrix and repository governance artifacts.

Agent-Task: D6-01A
Agent-Model: gpt-5.6-luna
Agent-Decision: Keep local feedback versioned and separate from remote CI.
Agent-Limitation: Remote repository and CI remain outside this task.
"""


class CommitMessageValidatorTest(unittest.TestCase):
    def run_validator(self, message: str) -> subprocess.CompletedProcess[str]:
        with tempfile.NamedTemporaryFile(
            mode="w", encoding="utf-8", suffix=".msg", delete=False
        ) as message_file:
            message_file.write(message)
            message_path = message_file.name
        try:
            return subprocess.run(
                [sys.executable, str(VALIDATOR), message_path],
                cwd=REPO_ROOT,
                capture_output=True,
                text=True,
                check=False,
            )
        finally:
            Path(message_path).unlink(missing_ok=True)

    def assert_rejected(self, message: str) -> None:
        result = self.run_validator(message)
        self.assertNotEqual(
            result.returncode,
            0,
            msg=f"message unexpectedly accepted:\n{result.stdout}\n{result.stderr}",
        )

    def test_accepts_compliant_message(self) -> None:
        result = self.run_validator(COMPLIANT)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_rejects_missing_scope(self) -> None:
        self.assert_rejected(COMPLIANT.replace("docs(governance):", "docs:"))

    def test_rejects_illegal_type(self) -> None:
        self.assert_rejected(COMPLIANT.replace("docs(governance):", "wip(governance):"))

    def test_rejects_empty_or_missing_body(self) -> None:
        self.assert_rejected(COMPLIANT.replace(
            "The baseline needs reproducible local checks before product implementation.\n"
            "Add the traceability matrix and repository governance artifacts.\n\n",
            "\n",
        ))
        self.assert_rejected(COMPLIANT.replace(
            "The baseline needs reproducible local checks before product implementation.\n"
            "Add the traceability matrix and repository governance artifacts.\n\n",
            "",
        ))

    def test_rejects_missing_duplicate_empty_out_of_order_and_same_line_trailers(self) -> None:
        missing = COMPLIANT.replace("Agent-Limitation: Remote repository and CI remain outside this task.\n", "")
        duplicate = COMPLIANT.replace(
            "Agent-Limitation: Remote repository and CI remain outside this task.\n",
            "Agent-Limitation: Remote repository and CI remain outside this task.\n"
            "Agent-Limitation: repeated\n",
        )
        duplicate_in_body = COMPLIANT.replace(
            "The baseline needs reproducible local checks before product implementation.\n",
            "Agent-Task: duplicate\n"
            "The baseline needs reproducible local checks before product implementation.\n",
        )
        empty = COMPLIANT.replace("Agent-Decision: Keep local feedback versioned and separate from remote CI.", "Agent-Decision:")
        out_of_order = COMPLIANT.replace(
            "Agent-Task: D6-01A\nAgent-Model: gpt-5.6-luna\n",
            "Agent-Model: gpt-5.6-luna\nAgent-Task: D6-01A\n",
        )
        same_line = COMPLIANT.replace(
            "Agent-Task: D6-01A\n",
            "Agent-Task: D6-01A Agent-Model: gpt-5.6-luna\n",
        )
        for invalid in (missing, duplicate, duplicate_in_body, empty, out_of_order, same_line):
            with self.subTest(message=invalid):
                self.assert_rejected(invalid)

    def test_rejects_unresolved_placeholders(self) -> None:
        self.assert_rejected(COMPLIANT.replace("D6-01A", "<task-id>"))
        self.assert_rejected(COMPLIANT.replace("gpt-5.6-luna", "<actual-model>"))


if __name__ == "__main__":
    unittest.main()
