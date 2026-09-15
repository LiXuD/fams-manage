#!/usr/bin/env python3
"""Fail-closed unit tests for the D6-03A local quality verifier."""

from __future__ import annotations

import importlib.util
import copy
import sys
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("verify-d6-03a.py")


def load_verifier():
    if not MODULE_PATH.is_file():
        raise AssertionError(f"missing D6-03A verifier: {MODULE_PATH}")
    spec = importlib.util.spec_from_file_location("verify_d6_03a", MODULE_PATH)
    if spec is None or spec.loader is None:
        raise AssertionError(f"cannot load D6-03A verifier: {MODULE_PATH}")
    verifier = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = verifier
    spec.loader.exec_module(verifier)
    return verifier


class BlockingStepTest(unittest.TestCase):

    def test_accepts_only_when_every_blocking_step_executed_successfully(self) -> None:
        verifier = load_verifier()

        result = verifier.all_blocking_steps_pass(
            (
                ("java-quality", 0, "EXECUTED"),
                ("typescript-quality", 0, "EXECUTED"),
                ("compose-runtime", 0, "EXECUTED"),
            )
        )

        self.assertTrue(result)

    def test_rejects_failed_or_skipped_blocking_steps(self) -> None:
        verifier = load_verifier()

        failed = verifier.all_blocking_steps_pass(
            (("java-quality", 1, "EXECUTED"),)
        )
        skipped = verifier.all_blocking_steps_pass(
            (("compose-runtime", 0, "SKIPPED"),)
        )
        missing = verifier.all_blocking_steps_pass(())

        self.assertFalse(failed)
        self.assertFalse(skipped)
        self.assertFalse(missing)


class JavaTestOutputPolicyTest(unittest.TestCase):

    def test_accepts_test_output_without_dynamic_agent_loading(self) -> None:
        verifier = load_verifier()

        self.assertEqual(
            [],
            verifier.java_test_output_violations(
                "Tests run: 15, Failures: 0, Errors: 0, Skipped: 3"
            ),
        )

    def test_rejects_mockito_self_attach_and_dynamic_agent_warnings(self) -> None:
        verifier = load_verifier()

        result = verifier.java_test_output_violations(
            "\n".join(
                (
                    "Mockito is currently self-attaching to enable the inline-mock-maker.",
                    "WARNING: A Java agent has been loaded dynamically",
                )
            )
        )

        self.assertEqual(
            {
                "mockito-self-attachment-detected",
                "dynamic-java-agent-loading-detected",
            },
            set(result),
        )


class TypeScriptNegativeFixtureTest(unittest.TestCase):

    def test_accepts_a_policy_driven_lint_rejection(self) -> None:
        verifier = load_verifier()

        result = verifier.typescript_negative_fixture_violations(
            1,
            "@typescript-eslint/no-unused-vars\n"
            "@typescript-eslint/no-explicit-any\n",
        )

        self.assertEqual([], result)

    def test_rejects_an_accepted_fixture_or_an_infrastructure_failure(self) -> None:
        verifier = load_verifier()

        accepted = verifier.typescript_negative_fixture_violations(0, "")
        infrastructure_error = verifier.typescript_negative_fixture_violations(
            2,
            "ConfigError: structuredClone is not defined",
        )

        self.assertEqual(["typescript-negative-fixture-accepted"], accepted)
        self.assertEqual(
            ["typescript-negative-fixture-not-policy-driven"],
            infrastructure_error,
        )


class ComposePolicyTest(unittest.TestCase):

    @staticmethod
    def valid_compose() -> dict[str, object]:
        return {
            "services": {
                "mysql": {
                    "image": (
                        "mysql:8.4.11@sha256:"
                        "3466ba4a4828aa8d46fb7c3bc16b67b781c98413cf4ea0fac6feaa6e881faa26"
                    ),
                    "ports": ["127.0.0.1:${FAMS_MYSQL_PORT:-33306}:3306"],
                    "healthcheck": {"test": ["CMD-SHELL", "mysqladmin ping"]},
                    "volumes": ["mysql-data:/var/lib/mysql"],
                    "environment": {
                        "MYSQL_DATABASE": "${FAMS_MYSQL_DATABASE:?required}",
                        "MYSQL_USER": "${FAMS_MYSQL_USER:?required}",
                        "MYSQL_PASSWORD": "${FAMS_MYSQL_PASSWORD:?required}",
                        "MYSQL_ROOT_PASSWORD": "${FAMS_MYSQL_ROOT_PASSWORD:?required}",
                    },
                }
            },
            "volumes": {"mysql-data": None},
        }

    def test_accepts_pinned_mysql_with_loopback_port_healthcheck_and_named_volume(self) -> None:
        verifier = load_verifier()
        self.assertEqual([], verifier.compose_policy_violations(self.valid_compose()))

    def test_rejects_unpinned_image_public_port_and_missing_safety_controls(self) -> None:
        verifier = load_verifier()
        compose = copy.deepcopy(self.valid_compose())
        mysql = compose["services"]["mysql"]
        mysql["image"] = "mysql:8.4.11"
        mysql["ports"] = ["33306:3306"]
        mysql.pop("healthcheck")
        mysql["volumes"] = ["/tmp/mysql:/var/lib/mysql"]
        mysql["environment"] = {"MYSQL_PASSWORD": "committed-password"}

        self.assertEqual(
            {
                "mysql-image-not-pinned",
                "mysql-port-not-loopback",
                "mysql-healthcheck-missing",
                "mysql-named-volume-missing",
                "mysql-database-not-required-variable",
                "mysql-user-not-required-variable",
                "mysql-password-not-required-variable",
                "mysql-root-password-not-required-variable",
            },
            set(verifier.compose_policy_violations(compose)),
        )

    def test_rejects_a_mysql_service_without_a_published_loopback_port(self) -> None:
        verifier = load_verifier()
        compose = copy.deepcopy(self.valid_compose())
        compose["services"]["mysql"]["ports"] = []

        self.assertIn(
            "mysql-port-not-loopback",
            verifier.compose_policy_violations(compose),
        )


class CoveragePolicyTest(unittest.TestCase):

    REPORT = """\
<report name="backend">
  <counter type="BRANCH" missed="4" covered="6"/>
  <counter type="LINE" missed="2" covered="8"/>
</report>
"""

    def test_accepts_measured_coverage_at_or_above_both_thresholds(self) -> None:
        verifier = load_verifier()

        result = verifier.coverage_policy_violations(
            self.REPORT,
            minimum_line_ratio=0.80,
            minimum_branch_ratio=0.60,
        )

        self.assertEqual([], result)

    def test_rejects_each_coverage_dimension_below_its_threshold(self) -> None:
        verifier = load_verifier()

        result = verifier.coverage_policy_violations(
            self.REPORT,
            minimum_line_ratio=0.81,
            minimum_branch_ratio=0.61,
        )

        self.assertEqual(
            {"line-coverage-below-threshold", "branch-coverage-below-threshold"},
            set(result),
        )


class ObservabilityPolicyTest(unittest.TestCase):

    def test_accepts_management_health_and_metrics_hidden_from_business_port(self) -> None:
        verifier = load_verifier()

        result = verifier.observability_policy_violations(
            business_metrics_status=404,
            management_health_status=200,
            management_health_body='{"status":"UP"}',
            management_metrics_status=200,
            management_metrics_body=(
                "# HELP jvm_memory_used_bytes The amount of used memory\n"
                "jvm_memory_used_bytes{area=\"heap\"} 1.0\n"
            ),
        )

        self.assertEqual([], result)

    def test_rejects_public_metrics_unhealthy_management_and_empty_scrape(self) -> None:
        verifier = load_verifier()

        result = verifier.observability_policy_violations(
            business_metrics_status=200,
            management_health_status=503,
            management_health_body='{"status":"DOWN"}',
            management_metrics_status=200,
            management_metrics_body="# no application metrics\n",
        )

        self.assertEqual(
            {
                "metrics-exposed-on-business-port",
                "management-health-not-up",
                "management-metrics-missing",
            },
            set(result),
        )


if __name__ == "__main__":
    unittest.main()
