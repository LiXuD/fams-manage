#!/usr/bin/env python3
"""Regression tests for the D6-02B runtime verifier's fail-closed parsers."""

from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("verify-d6-02b.py")
SPEC = importlib.util.spec_from_file_location("verify_d6_02b", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Cannot load verifier from {MODULE_PATH}")
VERIFIER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = VERIFIER
SPEC.loader.exec_module(VERIFIER)


class VersionEvidenceTest(unittest.TestCase):

    def test_accepts_the_exact_java_patch_from_oracle_or_openjdk_output(self) -> None:
        VERIFIER.assert_java_version_output('java version "21.0.7" 2025-04-15 LTS')
        VERIFIER.assert_java_version_output('openjdk version "21.0.7" 2025-04-15 LTS')

    def test_rejects_a_different_java_patch(self) -> None:
        with self.assertRaises(VERIFIER.VerificationFailure):
            VERIFIER.assert_java_version_output('openjdk version "21.0.8" 2025-07-15 LTS')


class DockerImageEvidenceTest(unittest.TestCase):

    def test_accepts_the_pinned_multiarch_digest_for_the_engine_architecture(self) -> None:
        platform = VERIFIER.assert_docker_image_metadata(
            "29.7.2|29.7.2|linux|amd64",
            f'["mysql@{VERIFIER.IMAGE_DIGEST}"]|linux|amd64',
        )

        self.assertEqual(("linux", "amd64"), platform)

    def test_rejects_an_image_for_a_different_architecture(self) -> None:
        with self.assertRaises(VERIFIER.VerificationFailure):
            VERIFIER.assert_docker_image_metadata(
                "29.7.2|29.7.2|linux|arm64",
                f'["mysql@{VERIFIER.IMAGE_DIGEST}"]|linux|amd64',
            )

    def test_rejects_an_unpinned_digest(self) -> None:
        with self.assertRaises(VERIFIER.VerificationFailure):
            VERIFIER.assert_docker_image_metadata(
                "29.7.2|29.7.2|linux|arm64",
                '["mysql@sha256:deadbeef"]|linux|arm64',
            )


class RuntimeTestEvidenceTest(unittest.TestCase):

    def test_rejects_skipped_runtime_tests_even_when_another_suite_passed(self) -> None:
        output = "\n".join(
            (
                "Tests run: 9, Failures: 0, Errors: 0, Skipped: 0",
                "Tests run: 3, Failures: 0, Errors: 0, Skipped: 3",
                "Tests run: 12, Failures: 0, Errors: 0, Skipped: 3",
            )
        )
        with self.assertRaises(VERIFIER.VerificationFailure):
            self._assert_runtime_execution(output, tests="3", skipped="3")

    def test_accepts_exactly_three_executed_runtime_tests_and_a_clean_total(self) -> None:
        output = "\n".join(
            (
                "Tests run: 9, Failures: 0, Errors: 0, Skipped: 0",
                "Tests run: 3, Failures: 0, Errors: 0, Skipped: 0",
                "Tests run: 12, Failures: 0, Errors: 0, Skipped: 0",
            )
        )
        self._assert_runtime_execution(output, tests="3", skipped="0")

    def _assert_runtime_execution(self, output: str, *, tests: str, skipped: str) -> None:
        with tempfile.TemporaryDirectory(prefix="d6-02b-verifier-test-") as temporary:
            report = Path(temporary) / "TEST-com.fams.runtime.RuntimeCompatibilityHttpTest.xml"
            report.write_text(
                '<testsuite name="com.fams.runtime.RuntimeCompatibilityHttpTest" '
                f'tests="{tests}" failures="0" errors="0" skipped="{skipped}"/>',
                encoding="utf-8",
            )
            VERIFIER.assert_runtime_test_execution(output, report)


class ReportEvidenceTest(unittest.TestCase):

    def test_report_preserves_runtime_proofs_and_the_observed_red_boundary(self) -> None:
        original_report = VERIFIER.REPORT
        original_proofs = list(VERIFIER.PROOFS)
        original_observations = list(VERIFIER.OBSERVATIONS)
        try:
            with tempfile.TemporaryDirectory(prefix="d6-02b-report-test-") as temporary:
                report = Path(temporary) / "report.md"
                VERIFIER.REPORT = report
                VERIFIER.PROOFS.clear()
                VERIFIER.OBSERVATIONS.clear()
                VERIFIER.record_proof("cleanup", "exact resources removed")

                VERIFIER.write_report("IN_REVIEW")

                content = report.read_text(encoding="utf-8")
                self.assertIn("Tests run: 3, Failures: 3", content)
                self.assertIn("expected routes returned HTTP 404", content)
                self.assertIn("cleanup: exact resources removed", content)
        finally:
            VERIFIER.REPORT = original_report
            VERIFIER.PROOFS[:] = original_proofs
            VERIFIER.OBSERVATIONS[:] = original_observations


if __name__ == "__main__":
    unittest.main()
