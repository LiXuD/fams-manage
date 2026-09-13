#!/usr/bin/env python3
"""Regression tests for the D6-02B runtime verifier's fail-closed parsers."""

from __future__ import annotations

import importlib.util
import subprocess
import sys
import tempfile
import unittest
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import patch


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


class CleanupEvidenceTest(unittest.TestCase):

    def test_rejects_failed_docker_cleanup_and_enumeration(self) -> None:
        original_container = VERIFIER.MYSQL_CONTAINER
        original_volume = VERIFIER.MYSQL_VOLUME
        original_proofs = list(VERIFIER.PROOFS)
        try:
            VERIFIER.MYSQL_CONTAINER = "fams-d6-02b-mysql-test"
            VERIFIER.MYSQL_VOLUME = "fams-d6-02b-volume-test"
            VERIFIER.PROOFS.clear()

            failed = subprocess.CompletedProcess(
                args=["rtk", "docker"],
                returncode=1,
                stdout="",
                stderr="Docker daemon unavailable",
            )
            with patch.object(VERIFIER, "run_rtk", return_value=failed) as runner:
                with self.assertRaises(VERIFIER.VerificationFailure):
                    VERIFIER.cleanup_resources()

            self.assertEqual(6, runner.call_count)
            self.assertFalse(any(proof.startswith("cleanup:") for proof in VERIFIER.PROOFS))
        finally:
            VERIFIER.MYSQL_CONTAINER = original_container
            VERIFIER.MYSQL_VOLUME = original_volume
            VERIFIER.PROOFS[:] = original_proofs

    def test_still_attempts_all_cleanup_steps_after_a_timeout(self) -> None:
        original_container = VERIFIER.MYSQL_CONTAINER
        original_volume = VERIFIER.MYSQL_VOLUME
        try:
            VERIFIER.MYSQL_CONTAINER = "fams-d6-02b-mysql-test"
            VERIFIER.MYSQL_VOLUME = "fams-d6-02b-volume-test"
            failed = subprocess.CompletedProcess(
                args=["rtk", "docker"],
                returncode=1,
                stdout="",
                stderr="Docker daemon unavailable",
            )
            effects = [
                subprocess.TimeoutExpired(cmd=["rtk", "docker"], timeout=60),
                failed,
                failed,
                failed,
                failed,
                failed,
            ]

            with patch.object(VERIFIER, "run_rtk", side_effect=effects) as runner:
                with self.assertRaises(VERIFIER.VerificationFailure):
                    VERIFIER.cleanup_resources()

            self.assertEqual(6, runner.call_count)
        finally:
            VERIFIER.MYSQL_CONTAINER = original_container
            VERIFIER.MYSQL_VOLUME = original_volume


class InvalidMigrationEvidenceTest(unittest.TestCase):

    def test_rejects_an_unrelated_startup_failure_that_names_the_profile(self) -> None:
        log = "\n".join(
            (
                "The following profiles are active: runtime-compatibility,invalid-migration",
                "Web server failed to start. Port 8080 was already in use.",
            )
        )

        with self.assertRaises(VERIFIER.VerificationFailure):
            VERIFIER.assert_invalid_migration_failure(log)

    def test_accepts_the_exact_flyway_fixture_and_sql_failure(self) -> None:
        log = "\n".join(
            (
                "org.flywaydb.core.api.FlywayException: Migration failed",
                "Migration V999__intentionally_invalid_fixture.sql failed",
                "SQL State  : 42000",
                "Message    : You have an error in your SQL syntax",
            )
        )

        VERIFIER.assert_invalid_migration_failure(log)

    def test_rejects_a_package_name_without_a_flyway_exception_class(self) -> None:
        log = "\n".join(
            (
                "Documentation: org.flywaydb",
                "Migration V999__intentionally_invalid_fixture.sql failed",
                "SQL State  : 42000",
            )
        )

        with self.assertRaises(VERIFIER.VerificationFailure):
            VERIFIER.assert_invalid_migration_failure(log)


class RetainedContractGateOrderTest(unittest.TestCase):

    def test_runs_the_d6_02a_gate_before_any_generating_build(self) -> None:
        order: list[str] = []

        def mark(label: str, result: object = None):
            def action(*_args: object, **_kwargs: object) -> object:
                order.append(label)
                return result

            return action

        with ExitStack() as stack:
            stack.enter_context(patch.object(VERIFIER, "validate_versions", side_effect=mark("versions")))
            stack.enter_context(
                patch.object(
                    VERIFIER,
                    "verify_retained_d6_02a_gate",
                    side_effect=mark("d6-02a"),
                )
            )
            stack.enter_context(
                patch.object(
                    VERIFIER,
                    "verify_resolved_dependencies",
                    side_effect=mark("dependencies"),
                )
            )
            stack.enter_context(
                patch.object(
                    VERIFIER,
                    "build_and_package",
                    side_effect=mark("build", Path("probe.jar")),
                )
            )
            for name in (
                "start_mysql",
                "exercise_invalid_migration",
                "exercise_absent_profile",
                "exercise_runtime",
                "run_final_checks",
                "cleanup_resources",
                "write_report",
                "append_ledger",
            ):
                stack.enter_context(patch.object(VERIFIER, name, side_effect=mark(name)))

            self.assertEqual(0, VERIFIER.main())

        self.assertEqual(1, order.count("d6-02a"))
        self.assertLess(order.index("d6-02a"), order.index("dependencies"))
        self.assertLess(order.index("d6-02a"), order.index("build"))


class SensitiveScanPatternTest(unittest.TestCase):

    def test_detects_supported_token_and_machine_path_shapes(self) -> None:
        samples = (
            "token=ghp_" + "A" * 36,
            "token=github_pat_" + "A1_" * 12,
            "token=sk-" + "a1_" * 12,
            "token=xoxb-" + "a1" * 12,
            "cache=/" + "Users/example/.m2/repository",
            "node=/" + "home/example/.nvm/versions/node",
        )

        for sample in samples:
            with self.subTest(sample=sample):
                self.assertTrue(VERIFIER.contains_sensitive_or_machine_path(sample))

    def test_ignores_documented_placeholders_and_relative_paths(self) -> None:
        samples = (
            "MYSQL_PASSWORD=<redacted>",
            "token=github_pat_<placeholder>",
            "cache=.m2/repository",
        )

        for sample in samples:
            with self.subTest(sample=sample):
                self.assertFalse(VERIFIER.contains_sensitive_or_machine_path(sample))


if __name__ == "__main__":
    unittest.main()
