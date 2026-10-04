#!/usr/bin/env python3
"""Fail-closed unit tests for the D6-03A local quality verifier."""

from __future__ import annotations

import importlib.util
import copy
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


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

    def test_verifier_unit_suite_is_a_blocking_step(self) -> None:
        verifier = load_verifier()
        with patch.object(verifier, "require_command", return_value="") as command:
            verifier.verify_self_tests()

        command.assert_called_once_with(
            "D6-03A verifier unit tests",
            [
                sys.executable,
                "-m",
                "unittest",
                "scripts.test_verify_d6_03a",
            ],
            timeout=300,
        )

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

    def test_requires_an_unskipped_successful_aggregate_test_summary(self) -> None:
        verifier = load_verifier()

        accepted = verifier.java_test_summary_violations(
            "Tests run: 23, Failures: 0, Errors: 0, Skipped: 0",
            minimum_tests=23,
        )
        skipped = verifier.java_test_summary_violations(
            "Tests run: 23, Failures: 0, Errors: 0, Skipped: 3",
            minimum_tests=23,
        )
        missing = verifier.java_test_summary_violations(
            "BUILD SUCCESS",
            minimum_tests=23,
        )

        self.assertEqual([], accepted)
        self.assertEqual(["java-tests-not-fully-executed"], skipped)
        self.assertEqual(["java-test-summary-missing"], missing)


class CurrentHeadSecretPolicyTest(unittest.TestCase):

    def test_detects_private_keys_and_supported_real_token_shapes(self) -> None:
        verifier = load_verifier()
        entries = (
            ("private.txt", "-----BEGIN " + "PRIVATE KEY-----"),
            ("classic.txt", "token=ghp_" + "A" * 36),
            ("fine-grained.txt", "token=github_pat_" + "A1_" * 12),
            ("openai.txt", "token=sk-" + "a1_" * 12),
            ("aws.txt", "token=AKIA" + "A1" * 8),
            ("slack.txt", "token=xoxb-" + "a1" * 12),
        )

        result = verifier.current_head_secret_violations(entries)

        self.assertEqual(
            {
                "private.txt:private-key",
                "classic.txt:github-classic-token",
                "fine-grained.txt:github-fine-grained-token",
                "openai.txt:openai-token",
                "aws.txt:aws-access-key",
                "slack.txt:slack-token",
            },
            set(result),
        )

    def test_ignores_placeholders_password_examples_and_pattern_source(self) -> None:
        verifier = load_verifier()
        entries = (
            ("compose.yaml", "MYSQL_PASSWORD=${FAMS_MYSQL_PASSWORD:?required}"),
            ("wrong-password.json", '{"scenario":"wrong password"}'),
            ("placeholder.txt", "github_pat_<placeholder> sk-<redacted>"),
            ("regex.py", r"ghp_[A-Za-z0-9]{20,}"),
        )

        self.assertEqual([], verifier.current_head_secret_violations(entries))


class RetainedPythonToolchainTest(unittest.TestCase):

    def test_accepts_python_3_12_with_the_frozen_pyyaml(self) -> None:
        verifier = load_verifier()

        self.assertEqual(
            [],
            verifier.retained_python_toolchain_violations("3.12.4", "6.0.2"),
        )

    def test_rejects_a_different_python_minor_or_pyyaml_version(self) -> None:
        verifier = load_verifier()

        self.assertEqual(
            ["retained-python-version-drift"],
            verifier.retained_python_toolchain_violations("3.13.1", "6.0.2"),
        )
        self.assertEqual(
            ["retained-pyyaml-version-drift"],
            verifier.retained_python_toolchain_violations("3.12.4", "6.0.3"),
        )

    def test_places_the_reviewed_python_directory_first_for_nested_gates(self) -> None:
        verifier = load_verifier()
        original = {"PATH": "/opt/homebrew/bin:/usr/bin", "KEEP": "value"}

        result = verifier.environment_with_executable_directory(
            original,
            "/usr/local/bin/python3.12",
        )

        self.assertEqual(
            "/usr/local/bin:/opt/homebrew/bin:/usr/bin",
            result["PATH"],
        )
        self.assertEqual("value", result["KEEP"])
        self.assertEqual("/opt/homebrew/bin:/usr/bin", original["PATH"])

    def test_isolates_python_shims_without_promoting_other_tool_binaries(self) -> None:
        verifier = load_verifier()
        original = {
            "PATH": "/nvm/node-v24/bin:/usr/local/bin:/usr/bin",
            "KEEP": "value",
        }
        with tempfile.TemporaryDirectory() as directory:
            shim_directory = Path(directory)

            result = verifier.create_python_shim_environment(
                original,
                sys.executable,
                shim_directory,
            )

            self.assertEqual(
                f"{shim_directory}:/nvm/node-v24/bin:/usr/local/bin:/usr/bin",
                result["PATH"],
            )
            self.assertEqual(
                Path(sys.executable),
                (shim_directory / "python3").readlink(),
            )
            self.assertEqual(
                Path(sys.executable),
                (shim_directory / "python3.12").readlink(),
            )
        self.assertEqual("/nvm/node-v24/bin:/usr/local/bin:/usr/bin", original["PATH"])


class TypeScriptNegativeFixtureTest(unittest.TestCase):

    def test_lint_output_ignores_the_max_warnings_flag_and_accepts_zero(self) -> None:
        verifier = load_verifier()
        output = "eslint src --max-warnings=0\n0 problems (0 errors, 0 warnings)\n"

        self.assertEqual([], verifier.typescript_lint_output_violations(output))

    def test_lint_output_rejects_a_reported_warning_count(self) -> None:
        verifier = load_verifier()
        output = "1 problem (0 errors, 1 warning)\n"

        self.assertEqual(
            ["typescript-lint-warnings-reported"],
            verifier.typescript_lint_output_violations(output),
        )

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

    def test_accepts_the_named_volume_shape_normalized_by_compose(self) -> None:
        verifier = load_verifier()
        compose = self.valid_compose()
        compose["services"]["mysql"]["volumes"] = [
            {
                "source": "mysql-data",
                "target": "/var/lib/mysql",
                "type": "volume",
            }
        ]

        self.assertEqual([], verifier.compose_policy_violations(compose))

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


class AuthenticationRuntimeIntegrationTest(unittest.TestCase):

    @staticmethod
    def context(verifier):
        return verifier.RuntimeContext(
            project="fams-d6-03a-0123456789abcdef",
            database="fams-d6-02b-db-0123456789abcdef",
            username="d6_02b_runtime",
            password="runtime-password-with-at-least-32-characters",
            root_password="root-password-with-at-least-32-characters",
            mysql_port=3307,
            business_port=8080,
            management_port=9091,
        )

    def test_quality_runtime_environment_includes_isolated_authentication_database(self) -> None:
        verifier = load_verifier()
        context = self.context(verifier)

        environment = verifier.runtime_environment(context)

        self.assertEqual(
            "jdbc:mysql://127.0.0.1:3307/fams-d6-04-db-0123456789abcdef"
            "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
            environment["D6_04_TEST_DATASOURCE_URL"],
        )
        self.assertEqual("d6_04_runtime", environment["D6_04_TEST_DATASOURCE_USERNAME"])
        self.assertEqual(context.password, environment["D6_04_TEST_DATASOURCE_PASSWORD"])

    def test_provisions_authentication_schema_and_least_privilege_account(self) -> None:
        verifier = load_verifier()
        context = self.context(verifier)

        with patch.object(verifier, "require_command", return_value="") as command:
            verifier.provision_auth_database(context)

        args = command.call_args.args[1]
        self.assertIn("fams-d6-04-db-0123456789abcdef", args[-1])
        self.assertIn("d6_04_runtime", args[-1])
        self.assertIn("GRANT ALL PRIVILEGES", args[-1])
        self.assertNotIn(context.root_password, args[-1])

    def test_quality_command_enables_authentication_runtime_suite(self) -> None:
        verifier = load_verifier()
        context = self.context(verifier)
        output = "Tests run: 50, Failures: 0, Errors: 0, Skipped: 0"

        with patch.object(verifier, "require_command", return_value=output) as command:
            with patch.object(verifier, "verify_java_reports"):
                verifier.verify_java_quality(context)

        self.assertIn("-Dd6.04.runtime=true", command.call_args.args[1])

    def test_required_report_floor_counts_the_new_runtime_suites(self) -> None:
        verifier = load_verifier()

        self.assertEqual(27, verifier.MINIMUM_JAVA_TESTS)
        self.assertIn(
            "TEST-com.fams.auth.AuthHttpTest.xml",
            verifier.REQUIRED_TEST_REPORTS,
        )
        self.assertIn(
            "TEST-com.fams.auth.AuthSessionRestartTest.xml",
            verifier.REQUIRED_TEST_REPORTS,
        )


class ObservabilityPolicyTest(unittest.TestCase):

    def test_runtime_metrics_requests_use_the_prometheus_text_media_type(self) -> None:
        verifier = load_verifier()
        context = verifier.RuntimeContext(
            project="test",
            database="test",
            username="test",
            password="test",
            root_password="test",
            mysql_port=3306,
            business_port=8080,
            management_port=9091,
        )
        responses = (
            (404, ""),
            (200, '{"status":"UP"}'),
            (200, "jvm_memory_used_bytes 1.0\n"),
        )
        with patch.object(verifier, "http_request", side_effect=responses) as request:
            with patch.object(verifier, "verify_loopback_listener"):
                verifier.verify_observability_http(context)

        self.assertEqual("text/plain", request.call_args_list[0].kwargs["accept"])
        self.assertNotIn("accept", request.call_args_list[1].kwargs)
        self.assertEqual("text/plain", request.call_args_list[2].kwargs["accept"])

    def test_application_process_uses_raw_rtk_proxy_for_startup_evidence(self) -> None:
        verifier = load_verifier()
        context = verifier.RuntimeContext(
            project="test",
            database="test",
            username="test",
            password="test",
            root_password="test",
            mysql_port=3306,
            business_port=8080,
            management_port=9091,
        )

        command = verifier.application_command(context, Path("backend.jar"))

        self.assertEqual(["rtk", "proxy", "java"], command[:3])
        self.assertIn("--server.address=127.0.0.1", command)
        self.assertIn("--server.port=8080", command)
        self.assertIn("--management.server.port=9091", command)

    def test_accepts_only_a_loopback_two_endpoint_management_profile(self) -> None:
        verifier = load_verifier()
        properties = {
            "management.server.address": "127.0.0.1",
            "management.server.port": "${FAMS_MANAGEMENT_PORT:39091}",
            "management.endpoints.web.exposure.include": "health,prometheus",
            "management.endpoint.health.show-details": "never",
            "spring.jmx.enabled": "false",
        }

        self.assertEqual(
            [],
            verifier.observability_profile_violations(properties),
        )

    def test_rejects_public_or_overexposed_management_configuration(self) -> None:
        verifier = load_verifier()
        properties = {
            "management.server.address": "0.0.0.0",
            "management.server.port": "8080",
            "management.endpoints.web.exposure.include": "*",
            "management.endpoint.health.show-details": "always",
            "spring.jmx.enabled": "true",
        }

        self.assertEqual(
            {
                "management-address-not-loopback",
                "management-port-not-dedicated-variable",
                "management-endpoints-overexposed",
                "management-health-details-exposed",
                "jmx-management-enabled",
            },
            set(verifier.observability_profile_violations(properties)),
        )

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
