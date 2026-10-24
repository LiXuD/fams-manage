#!/usr/bin/env python3
"""Run the D6-03A local Compose and quality gate."""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass
from http import HTTPStatus
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import time
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
import xml.etree.ElementTree as ElementTree


ROOT = Path(__file__).resolve().parents[1]
MYSQL_IMAGE = (
    "mysql:8.4.11@sha256:"
    "3466ba4a4828aa8d46fb7c3bc16b67b781c98413cf4ea0fac6feaa6e881faa26"
)
MYSQL_DIGEST = (
    "sha256:3466ba4a4828aa8d46fb7c3bc16b67b781c98413cf4ea0fac6feaa6e881faa26"
)
JAVA_VERSION = "21.0.7"
NODE_VERSION = "v24.18.0"
NPM_VERSION = "11.16.0"
MAVEN_VERSION = "3.9.16"
PYTHON_MINOR_VERSION = "3.12"
PY_YAML_VERSION = "6.0.2"
MINIMUM_JAVA_TESTS = 27
MINIMUM_LINE_COVERAGE = 0.75
MINIMUM_BRANCH_COVERAGE = 0.75
BASE_COMMIT = "5676bd9823e27ef65988568acd940a7532ab82b7"
D6_01A_REVIEWED_COMMIT = "338d49da5d734edb64729373f92ca92d7487bcf6"
GENERATED_TARGETS = (
    ROOT / "backend/target",
    ROOT / "packages/api-types/target",
)
OBSERVABILITY_PROFILE = (
    ROOT / "backend/src/main/resources/application-local-observability.properties"
)
JACOCO_REPORT = ROOT / "backend/target/site/jacoco/jacoco.xml"
CHECKSTYLE_REPORT = ROOT / "backend/target/checkstyle-result.xml"
SPOTBUGS_REPORT = ROOT / "backend/target/spotbugsXml.xml"
SUREFIRE_REPORTS = ROOT / "backend/target/surefire-reports"
REQUIRED_TEST_REPORTS = {
    "TEST-com.fams.architecture.FamsArchitectureRulesTest.xml",
    "TEST-com.fams.architecture.FamsArchitectureTest.xml",
    "TEST-com.fams.auth.AuthHttpTest.xml",
    "TEST-com.fams.auth.MiniSessionHttpTest.xml",
    "TEST-com.fams.auth.MiniBindingHttpTest.xml",
    "TEST-com.fams.auth.MiniSessionUpgradeRuntimeCase.xml",
    "TEST-com.fams.auth.RequisitionUpgradeRuntimeCase.xml",
    "TEST-com.fams.auth.ReassignmentUpgradeRuntimeCase.xml",
    "TEST-com.fams.auth.RequisitionHttpTest.xml",
    "TEST-com.fams.auth.HandoverHttpTest.xml",
    "TEST-com.fams.auth.HandoverUpgradeRuntimeCase.xml",
    "TEST-com.fams.auth.ProxyConfirmationUpgradeRuntimeCase.xml",
    "TEST-com.fams.auth.InventoryUpgradeRuntimeCase.xml",
    "TEST-com.fams.auth.InventoryTaskHttpRuntimeCase.xml",
    "TEST-com.fams.auth.InventoryObservationUpgradeRuntimeCase.xml",
    "TEST-com.fams.auth.InventoryObservationHttpRuntimeCase.xml",
    "TEST-com.fams.auth.InventoryReviewUpgradeRuntimeCase.xml",
    "TEST-com.fams.auth.InventoryDifferenceUpgradeRuntimeCase.xml",
    "TEST-com.fams.auth.InventorySelectionUpgradeRuntimeCase.xml",
    "TEST-com.fams.auth.InventoryEvidenceUpgradeRuntimeCase.xml",
    "TEST-com.fams.auth.InventoryFindingUpgradeRuntimeCase.xml",
    "TEST-com.fams.auth.InventorySynchronizationUpgradeRuntimeCase.xml",
    "TEST-com.fams.auth.InventorySynchronizationHttpRuntimeCase.xml",
    "TEST-com.fams.auth.InventoryReadinessUpgradeRuntimeCase.xml",
    "TEST-com.fams.auth.InventoryReadinessHttpRuntimeCase.xml",
    "TEST-com.fams.auth.InventorySealUpgradeRuntimeCase.xml",
    "TEST-com.fams.auth.InventorySealHttpRuntimeCase.xml",
    "TEST-com.fams.auth.InventoryLateUpgradeRuntimeCase.xml",
    "TEST-com.fams.auth.InventoryRecollectionUpgradeRuntimeCase.xml",
    "TEST-com.fams.auth.RepairUpgradeRuntimeCase.xml",
    "TEST-com.fams.auth.RepairHttpRuntimeCase.xml",
    "TEST-com.fams.auth.RepairCollaborationUpgradeRuntimeCase.xml",
    "TEST-com.fams.auth.RepairCollaborationHttpRuntimeCase.xml",
    "TEST-com.fams.auth.RepairPhysicalUpgradeRuntimeCase.xml",
    "TEST-com.fams.auth.RepairPhysicalHttpRuntimeCase.xml",
    "TEST-com.fams.auth.RepairReturnUpgradeRuntimeCase.xml",
    "TEST-com.fams.auth.RepairReturnTargetUpgradeRuntimeCase.xml",
    "TEST-com.fams.auth.RepairReturnHttpRuntimeCase.xml",
    "TEST-com.fams.auth.RepairReassignmentHttpRuntimeCase.xml",
    "TEST-com.fams.auth.RepairProxyUpgradeRuntimeCase.xml",
    "TEST-com.fams.auth.RepairProxyHttpRuntimeCase.xml",
    "TEST-com.fams.auth.RepairReturnSourceUpgradeRuntimeCase.xml",
    "TEST-com.fams.auth.RepairReturnSourceHttpRuntimeCase.xml",
    "TEST-com.fams.auth.InventoryLateHttpRuntimeCase.xml",
    "TEST-com.fams.auth.InventoryReviewHttpRuntimeCase.xml",
    "TEST-com.fams.inventory.InventoryObservationTimeTest.xml",
    "TEST-com.fams.auth.LoanUpgradeRuntimeCase.xml",
    "TEST-com.fams.auth.RequisitionChangeUpgradeRuntimeCase.xml",
    "TEST-com.fams.auth.AuthSessionRestartTest.xml",
    "TEST-com.fams.contract.ContractProbeHttpTest.xml",
    "TEST-com.fams.observability.LocalObservabilityHttpTest.xml",
    "TEST-com.fams.runtime.RuntimeCompatibilityHttpTest.xml",
    "TEST-com.fams.runtime.RuntimeCompatibilityTestDatabaseTest.xml",
    "TEST-com.fams.masterdata.IdentityMasterDataHttpTest.xml",
    "TEST-com.fams.asset.AssetHttpTest.xml",
    "TEST-com.fams.attachment.AttachmentRegistryTest.xml",
    "TEST-com.fams.attachment.AttachmentHttpTest.xml",
    "TEST-com.fams.attachment.AttachmentCleanupTest.xml",
}


class VerificationFailure(RuntimeError):
    """A blocking D6-03A assertion failed."""


@dataclass(frozen=True)
class RuntimeContext:
    project: str
    database: str
    username: str
    password: str
    root_password: str
    mysql_port: int
    business_port: int
    management_port: int


@dataclass
class AppProcess:
    process: subprocess.Popen[str]
    log_path: Path
    log_handle: Any


BLOCKING_STEPS: list[tuple[str, int, str]] = []
RUNTIME_SECRETS: list[str] = []
CURRENT_HEAD_SECRET_PATTERNS = (
    (
        "private-key",
        re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----"),
    ),
    (
        "aws-access-key",
        re.compile(r"(?i)(?<![A-Za-z0-9_])(?:AKIA|ASIA)[0-9A-Z]{16}(?![0-9A-Z])"),
    ),
    (
        "github-classic-token",
        re.compile(r"(?<![A-Za-z0-9_])ghp_[A-Za-z0-9]{20,}(?![A-Za-z0-9])"),
    ),
    (
        "github-fine-grained-token",
        re.compile(
            r"(?<![A-Za-z0-9_])github_pat_[A-Za-z0-9_]{20,}(?![A-Za-z0-9_])"
        ),
    ),
    (
        "openai-token",
        re.compile(r"(?<![A-Za-z0-9_])sk-[A-Za-z0-9_-]{20,}(?![A-Za-z0-9_-])"),
    ),
    (
        "slack-token",
        re.compile(r"(?<![A-Za-z0-9_])xox[baprs]-[A-Za-z0-9_-]{20,}(?![A-Za-z0-9_-])"),
    ),
)



def java_test_output_violations(output: str) -> list[str]:
    """Reject Java test runs that rely on runtime agent attachment."""

    violations: list[str] = []
    if "Mockito is currently self-attaching" in output:
        violations.append("mockito-self-attachment-detected")
    if "A Java agent has been loaded dynamically" in output:
        violations.append("dynamic-java-agent-loading-detected")
    return violations


def java_test_summary_violations(output: str, *, minimum_tests: int) -> list[str]:
    """Require the aggregate Maven test summary to be complete and unskipped."""

    summaries = re.findall(
        r"Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)",
        output,
    )
    if not summaries:
        return ["java-test-summary-missing"]
    tests, failures, errors, skipped = (int(value) for value in summaries[-1])
    if tests < minimum_tests or failures or errors or skipped:
        return ["java-tests-not-fully-executed"]
    return []


def current_head_secret_violations(
    entries: Iterable[tuple[str, str]],
) -> list[str]:
    """Return precise token/private-key findings without flagging examples by name."""

    violations: list[str] = []
    for relative, content in entries:
        for label, pattern in CURRENT_HEAD_SECRET_PATTERNS:
            if pattern.search(content):
                violations.append(f"{relative}:{label}")
    return violations


def retained_python_toolchain_violations(
    python_version: str,
    pyyaml_version: str,
) -> list[str]:
    """Require the Python minor and PyYAML version reviewed by D6-02C."""

    violations: list[str] = []
    if not python_version.startswith(PYTHON_MINOR_VERSION + "."):
        violations.append("retained-python-version-drift")
    if pyyaml_version != PY_YAML_VERSION:
        violations.append("retained-pyyaml-version-drift")
    return violations


def environment_with_executable_directory(
    environment: dict[str, str],
    executable: str,
) -> dict[str, str]:
    """Prepend one resolved tool directory without mutating the caller's mapping."""

    result = environment.copy()
    directory = str(Path(executable).parent)
    current_path = result.get("PATH", "")
    result["PATH"] = directory + (os.pathsep + current_path if current_path else "")
    return result


def create_python_shim_environment(
    environment: dict[str, str],
    executable: str,
    shim_directory: Path,
) -> dict[str, str]:
    """Expose only Python aliases so nested gates retain the selected Node runtime."""

    shim_directory.mkdir(parents=True, exist_ok=True)
    for name in ("python3", "python3.12"):
        shim = shim_directory / name
        if shim.exists() or shim.is_symlink():
            raise VerificationFailure(f"Python shim already exists: {name}")
        shim.symlink_to(executable)
    return environment_with_executable_directory(
        environment,
        str(shim_directory / "python3.12"),
    )


def typescript_lint_output_violations(output: str) -> list[str]:
    """Reject actual ESLint warning summaries without matching CLI option names."""

    warning_counts = [
        int(count)
        for count in re.findall(r"\b(\d+)\s+warnings?\b", output, re.IGNORECASE)
    ]
    if any(count > 0 for count in warning_counts):
        return ["typescript-lint-warnings-reported"]
    return []


def typescript_negative_fixture_violations(
    returncode: int,
    output: str,
) -> list[str]:
    """Require the temporary TypeScript fixture to fail for both intended rules."""

    if returncode == 0:
        return ["typescript-negative-fixture-accepted"]
    required_rule_ids = (
        "@typescript-eslint/no-unused-vars",
        "@typescript-eslint/no-explicit-any",
    )
    if not all(rule_id in output for rule_id in required_rule_ids):
        return ["typescript-negative-fixture-not-policy-driven"]
    return []


def compose_policy_violations(compose: dict[str, Any]) -> list[str]:
    """Return actionable violations for the project-local Compose model."""

    services = compose.get("services")
    mysql = services.get("mysql") if isinstance(services, dict) else None
    if not isinstance(mysql, dict):
        return ["mysql-service-missing"]

    violations: list[str] = []
    if mysql.get("image") != MYSQL_IMAGE:
        violations.append("mysql-image-not-pinned")

    ports = mysql.get("ports")
    if not isinstance(ports, list) or not ports or not all(
        isinstance(port, str)
        and port.startswith("127.0.0.1:")
        and port.endswith(":3306")
        for port in ports
    ):
        violations.append("mysql-port-not-loopback")

    healthcheck = mysql.get("healthcheck")
    if not isinstance(healthcheck, dict) or not healthcheck.get("test"):
        violations.append("mysql-healthcheck-missing")

    service_volumes = mysql.get("volumes")
    declared_volumes = compose.get("volumes")
    named_volume = "mysql-data:/var/lib/mysql"
    has_named_volume = isinstance(service_volumes, list) and any(
        volume == named_volume
        or (
            isinstance(volume, dict)
            and volume.get("type") == "volume"
            and volume.get("source") == "mysql-data"
            and volume.get("target") == "/var/lib/mysql"
        )
        for volume in service_volumes
    )
    if (
        not has_named_volume
        or not isinstance(declared_volumes, dict)
        or "mysql-data" not in declared_volumes
    ):
        violations.append("mysql-named-volume-missing")

    environment = mysql.get("environment")
    if not isinstance(environment, dict):
        environment = {}
    required_variables = {
        "MYSQL_DATABASE": "${FAMS_MYSQL_DATABASE:?required}",
        "MYSQL_USER": "${FAMS_MYSQL_USER:?required}",
        "MYSQL_PASSWORD": "${FAMS_MYSQL_PASSWORD:?required}",
        "MYSQL_ROOT_PASSWORD": "${FAMS_MYSQL_ROOT_PASSWORD:?required}",
    }
    for key, value in required_variables.items():
        if environment.get(key) != value:
            violations.append(f"{key.lower().replace('_', '-')}-not-required-variable")

    return violations


def coverage_policy_violations(
    report_xml: str,
    *,
    minimum_line_ratio: float,
    minimum_branch_ratio: float,
) -> list[str]:
    """Return JaCoCo line and branch threshold violations."""

    report = ElementTree.fromstring(report_xml)
    violations: list[str] = []
    thresholds = (
        ("LINE", minimum_line_ratio, "line"),
        ("BRANCH", minimum_branch_ratio, "branch"),
    )
    for counter_type, minimum, label in thresholds:
        counter = report.find(f"./counter[@type='{counter_type}']")
        if counter is None:
            violations.append(f"{label}-coverage-missing")
            continue
        missed = int(counter.attrib["missed"])
        covered = int(counter.attrib["covered"])
        total = missed + covered
        if total <= 0:
            violations.append(f"{label}-coverage-missing")
        elif covered / total < minimum:
            violations.append(f"{label}-coverage-below-threshold")
    return violations


def observability_policy_violations(
    *,
    business_metrics_status: int,
    management_health_status: int,
    management_health_body: str,
    management_metrics_status: int,
    management_metrics_body: str,
) -> list[str]:
    """Return violations for the restricted local management surface."""

    violations: list[str] = []
    if business_metrics_status != 404:
        violations.append("metrics-exposed-on-business-port")

    try:
        health = json.loads(management_health_body)
    except json.JSONDecodeError:
        health = None
    if (
        management_health_status != 200
        or not isinstance(health, dict)
        or health.get("status") != "UP"
    ):
        violations.append("management-health-not-up")

    if (
        management_metrics_status != 200
        or "jvm_memory_used_bytes" not in management_metrics_body
    ):
        violations.append("management-metrics-missing")
    return violations


def observability_profile_violations(properties: dict[str, str]) -> list[str]:
    """Require the local management profile to remain narrow and loopback-only."""

    violations: list[str] = []
    if properties.get("management.server.address") != "127.0.0.1":
        violations.append("management-address-not-loopback")
    if properties.get("management.server.port") != "${FAMS_MANAGEMENT_PORT:39091}":
        violations.append("management-port-not-dedicated-variable")
    exposed = {
        item.strip()
        for item in properties.get(
            "management.endpoints.web.exposure.include",
            "",
        ).split(",")
        if item.strip()
    }
    if exposed != {"health", "prometheus"}:
        violations.append("management-endpoints-overexposed")
    if properties.get("management.endpoint.health.show-details") != "never":
        violations.append("management-health-details-exposed")
    if properties.get("spring.jmx.enabled") != "false":
        violations.append("jmx-management-enabled")
    return violations


def all_blocking_steps_pass(steps: Iterable[tuple[str, int, str]]) -> bool:
    """Return whether the supplied blocking steps completed successfully."""

    observed = tuple(steps)
    return bool(observed) and all(
        returncode == 0 and status == "EXECUTED"
        for _, returncode, status in observed
    )


def redact(value: str) -> str:
    """Remove task credentials, machine paths, and token-shaped values."""

    for secret in RUNTIME_SECRETS:
        if secret:
            value = value.replace(secret, "<redacted>")
    value = value.replace(str(ROOT), "<workspace>")
    value = value.replace(str(Path.home()), "<home>")
    value = re.sub(
        r"(?<![A-Za-z0-9_])sk-[A-Za-z0-9_-]{20,}(?![A-Za-z0-9_-])",
        "<redacted-token>",
        value,
    )
    return value


def output_excerpt(output: str, *, limit: int = 10) -> str:
    lines = [redact(line.strip()) for line in output.splitlines() if line.strip()]
    return "\n".join(lines[-limit:])


def run_rtk(
    args: list[str],
    *,
    cwd: Path = ROOT,
    env: dict[str, str] | None = None,
    timeout: float = 600,
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["rtk", *args],
        cwd=cwd,
        env=env or os.environ.copy(),
        capture_output=True,
        text=True,
        check=False,
        timeout=timeout,
    )


def require_command(
    label: str,
    args: list[str],
    *,
    cwd: Path = ROOT,
    env: dict[str, str] | None = None,
    timeout: float = 600,
    show: bool = True,
) -> str:
    result = run_rtk(args, cwd=cwd, env=env, timeout=timeout)
    output = result.stdout + result.stderr
    BLOCKING_STEPS.append((label, result.returncode, "EXECUTED"))
    print(f"[{label}] exit={result.returncode}")
    if show:
        excerpt = output_excerpt(output)
        if excerpt:
            for line in excerpt.splitlines():
                print(f"  {line}")
    if result.returncode != 0:
        raise VerificationFailure(f"{label} failed with exit {result.returncode}")
    return output


def record_policy_pass(label: str, detail: str) -> None:
    BLOCKING_STEPS.append((label, 0, "EXECUTED"))
    print(f"[{label}] {detail}")


def choose_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("127.0.0.1", 0))
        return int(listener.getsockname()[1])


def read_properties(path: Path) -> dict[str, str]:
    properties: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator:
            raise VerificationFailure(f"invalid properties line in {path.name}")
        properties[key.strip()] = value.strip()
    return properties


def clear_generated_outputs() -> None:
    for target in GENERATED_TARGETS:
        if target.is_symlink():
            target.unlink()
        elif target.is_dir():
            shutil.rmtree(target)
        elif target.exists():
            target.unlink()


def assert_clean_start() -> None:
    existing = [target.relative_to(ROOT).as_posix() for target in GENERATED_TARGETS if target.exists()]
    if existing:
        raise VerificationFailure(
            "generated targets must be absent before verification: " + ", ".join(existing)
        )
    status = require_command("clean worktree preflight", ["git", "status", "--porcelain"])
    if status.strip():
        raise VerificationFailure("D6-03A verification requires a clean worktree")
    tracked = require_command(
        "generated output tracking preflight",
        ["git", "ls-files", "backend/target", "packages/api-types/target"],
    )
    if tracked.strip():
        raise VerificationFailure("generator-owned target output must remain untracked")


def verify_tool_versions() -> None:
    java_output = require_command("Java version", ["java", "-version"], show=False)
    java_match = re.search(
        r'(?im)^\s*(?:openjdk|java)\s+version\s+"([0-9]+(?:\.[0-9]+){1,2})"',
        java_output,
    )
    if not java_match or java_match.group(1) != JAVA_VERSION:
        raise VerificationFailure(f"Java must be exactly {JAVA_VERSION}")
    node_output = require_command("Node version", ["node", "--version"], show=False).strip()
    if node_output != NODE_VERSION:
        raise VerificationFailure(f"Node must be exactly {NODE_VERSION}")
    npm_output = require_command("npm version", ["npm", "--version"], show=False).strip()
    if npm_output != NPM_VERSION:
        raise VerificationFailure(f"npm must be exactly {NPM_VERSION}")
    maven_output = require_command("Maven version", ["./mvnw", "--version"], show=False)
    if f"Apache Maven {MAVEN_VERSION}" not in maven_output or JAVA_VERSION not in maven_output:
        raise VerificationFailure("Maven or its Java runtime drifted from the frozen combination")
    python_output = require_command(
        "retained Python toolchain",
        [
            "python3.12",
            "-c",
            "import sys,yaml; "
            "print(f'{sys.version_info.major}.{sys.version_info.minor}."
            "{sys.version_info.micro}|{yaml.__version__}')",
        ],
        show=False,
    ).strip()
    python_version, separator, pyyaml_version = python_output.partition("|")
    if not separator:
        raise VerificationFailure("retained Python toolchain metadata was not parseable")
    python_violations = retained_python_toolchain_violations(
        python_version,
        pyyaml_version,
    )
    if python_violations:
        raise VerificationFailure(
            "retained Python toolchain violations: " + ", ".join(python_violations)
        )
    docker_output = require_command(
        "Docker engine",
        [
            "docker",
            "version",
            "--format",
            "{{.Client.Version}}|{{.Server.Version}}|{{.Server.Os}}|{{.Server.Arch}}",
        ],
        show=False,
    ).strip()
    docker_parts = docker_output.split("|")
    if len(docker_parts) != 4 or docker_parts[2] != "linux" or docker_parts[3] not in {
        "arm64",
        "amd64",
    }:
        raise VerificationFailure(f"unsupported Docker engine metadata: {docker_output}")
    compose_output = require_command(
        "Docker Compose version",
        ["docker", "compose", "version"],
        show=False,
    ).strip()
    if not re.fullmatch(r"Docker Compose version v\d+\.\d+\.\d+", compose_output):
        raise VerificationFailure("Docker Compose did not report a semantic version")
    record_policy_pass(
        "frozen toolchain",
        f"Java={JAVA_VERSION}, Node={NODE_VERSION}, npm={NPM_VERSION}, "
        f"Maven={MAVEN_VERSION}, Python={python_version}, PyYAML={pyyaml_version}, "
        f"{redact(compose_output)}",
    )


def verify_static_configuration() -> None:
    profile_violations = observability_profile_violations(
        read_properties(OBSERVABILITY_PROFILE)
    )
    if profile_violations:
        raise VerificationFailure(
            "observability profile violations: " + ", ".join(profile_violations)
        )

    package = json.loads((ROOT / "package.json").read_text(encoding="utf-8"))
    expected_dev_dependencies = {
        "@eslint/js": "10.0.1",
        "eslint": "10.10.0",
        "typescript-eslint": "8.70.0",
    }
    actual_dev_dependencies = package.get("devDependencies")
    if not isinstance(actual_dev_dependencies, dict) or any(
        actual_dev_dependencies.get(name) != version
        for name, version in expected_dev_dependencies.items()
    ):
        raise VerificationFailure("root TypeScript quality versions drifted")
    if package.get("workspaces") != ["apps/*", "packages/*"]:
        raise VerificationFailure("future client workspaces are not automatically included")
    scripts = package.get("scripts")
    if not isinstance(scripts, dict) or not {"lint", "typecheck"}.issubset(scripts):
        raise VerificationFailure("root TypeScript quality scripts drifted")

    pom = (ROOT / "backend/pom.xml").read_text(encoding="utf-8")
    required_pom_markers = (
        "<archunit.version>1.4.2</archunit.version>",
        "<checkstyle.version>14.1.0</checkstyle.version>",
        "<maven-checkstyle-plugin.version>3.6.0</maven-checkstyle-plugin.version>",
        "<maven-dependency-plugin.version>3.11.0</maven-dependency-plugin.version>",
        "<maven-enforcer-plugin.version>3.6.3</maven-enforcer-plugin.version>",
        "<maven-surefire-plugin.version>3.5.6</maven-surefire-plugin.version>",
        "<spotbugs-maven-plugin.version>4.10.4.1</spotbugs-maven-plugin.version>",
        "<jacoco-maven-plugin.version>0.8.15</jacoco-maven-plugin.version>",
        "<minimum>0.75</minimum>",
    )
    missing = [marker for marker in required_pom_markers if marker not in pom]
    if missing or pom.count("<minimum>0.75</minimum>") < 2:
        raise VerificationFailure("Java quality versions or measured thresholds drifted")
    record_policy_pass(
        "static quality policy",
        "tool versions, future TypeScript paths, 75% line/branch floors, and local metrics profile",
    )


def verify_current_head_secrets() -> None:
    tracked_output = require_command(
        "current tracked file list",
        ["git", "ls-files", "-z"],
        show=False,
    )
    entries: list[tuple[str, str]] = []
    for relative in (item for item in tracked_output.split("\0") if item):
        path = ROOT / relative
        try:
            if path.is_symlink():
                content = os.readlink(path)
            else:
                content = path.read_text(encoding="utf-8", errors="ignore")
        except OSError as exc:
            raise VerificationFailure(
                f"unable to inspect tracked file for secrets: {relative}"
            ) from exc
        entries.append((relative, content))
    violations = current_head_secret_violations(entries)
    if violations:
        raise VerificationFailure(
            "current HEAD secret findings: " + ", ".join(violations)
        )
    record_policy_pass(
        "current HEAD secret scan",
        f"{len(entries)} tracked files; token and private-key findings=0",
    )


def verify_self_tests() -> None:
    require_command(
        "D6-03A verifier unit tests",
        [
            sys.executable,
            "-m",
            "unittest",
            "scripts.test_verify_d6_03a",
        ],
        timeout=300,
    )


def install_locked_dependencies() -> None:
    require_command(
        "locked root dependencies",
        ["npm", "ci", "--ignore-scripts"],
        timeout=300,
    )
    require_command(
        "locked commit tooling",
        ["npm", "ci", "--prefix", "tools/git", "--ignore-scripts"],
        timeout=300,
    )


def verify_d6_01a_in_clean_clone() -> None:
    with tempfile.TemporaryDirectory(prefix="fams-d6-03a-baseline-") as temporary:
        clone = Path(temporary) / "fams"
        require_command(
            "D6-01A local clone",
            ["git", "clone", "--local", "--no-hardlinks", str(ROOT), str(clone)],
            timeout=300,
        )
        require_command(
            "D6-01A reviewed checkout",
            ["git", "checkout", "-B", "main", D6_01A_REVIEWED_COMMIT],
            cwd=clone,
        )
        require_command(
            "D6-01A clone remote removal",
            ["git", "remote", "remove", "origin"],
            cwd=clone,
        )
        require_command(
            "D6-01A clone hook configuration",
            ["git", "config", "core.hooksPath", ".githooks"],
            cwd=clone,
        )
        for relative in (".playwright-cli", "output", ".superpowers"):
            fixture = clone / relative / "d6-03a-ignore-fixture"
            fixture.parent.mkdir(parents=True, exist_ok=True)
            fixture.write_text("task-owned ignore fixture\n", encoding="utf-8")
        require_command(
            "D6-01A clone tooling",
            ["npm", "ci", "--prefix", "tools/git", "--ignore-scripts"],
            cwd=clone,
            timeout=300,
        )
        require_command(
            "D6-01A retained gate",
            ["python3", "scripts/verify-d6-01a.py"],
            cwd=clone,
            timeout=600,
        )


def verify_new_commit_messages() -> None:
    commits_output = require_command(
        "D6-03A commit range",
        ["git", "rev-list", "--reverse", f"{BASE_COMMIT}..HEAD"],
        show=False,
    )
    commits = [line for line in commits_output.splitlines() if line.strip()]
    if not commits:
        raise VerificationFailure("D6-03A commit range is empty")
    with tempfile.TemporaryDirectory(prefix="fams-d6-03a-commits-") as temporary:
        message_file = Path(temporary) / "commit-message.txt"
        for commit in commits:
            message = require_command(
                f"commit body {commit[:8]}",
                ["git", "show", "-s", "--format=%B", commit],
                show=False,
            )
            message_file.write_text(message, encoding="utf-8")
            require_command(
                f"commit format {commit[:8]}",
                [".githooks/commit-msg", str(message_file)],
            )
    record_policy_pass(
        "D6-03A commit messages",
        f"validated {len(commits)} commits from the approved baseline",
    )


def verify_retained_gates() -> None:
    verify_d6_01a_in_clean_clone()
    clear_generated_outputs()
    python_executable = shutil.which("python3.12")
    if python_executable is None:
        raise VerificationFailure("Python 3.12 is unavailable for the retained D6-02C gate")
    with tempfile.TemporaryDirectory(prefix="fams-d6-03a-python-") as temporary:
        environment = create_python_shim_environment(
            os.environ.copy(),
            python_executable,
            Path(temporary),
        )
        require_command(
            "D6-02C retained complete gate",
            ["python3.12", "scripts/verify-d6-02c.py"],
            env=environment,
            timeout=2400,
        )
    remaining = [target for target in GENERATED_TARGETS if target.exists()]
    if remaining:
        raise VerificationFailure("D6-02C retained gate did not clean generated output")
    record_policy_pass(
        "retained local gates",
        "D6-01A and D6-02A/B/C executed successfully on the reviewed content",
    )


def create_runtime_context() -> RuntimeContext:
    suffix = secrets.token_hex(8)
    ports: set[int] = set()
    while len(ports) < 3:
        ports.add(choose_port())
    mysql_port, business_port, management_port = sorted(ports)
    context = RuntimeContext(
        project=f"fams-d6-03a-{suffix}",
        database=f"fams-d6-02b-db-{suffix}",
        username="d6_02b_runtime",
        password=secrets.token_urlsafe(32),
        root_password=secrets.token_urlsafe(32),
        mysql_port=mysql_port,
        business_port=business_port,
        management_port=management_port,
    )
    RUNTIME_SECRETS.extend((context.password, context.root_password))
    return context


def compose_environment(context: RuntimeContext) -> dict[str, str]:
    environment = os.environ.copy()
    environment.update(
        {
            "FAMS_MYSQL_DATABASE": context.database,
            "FAMS_MYSQL_USER": context.username,
            "FAMS_MYSQL_PASSWORD": context.password,
            "FAMS_MYSQL_ROOT_PASSWORD": context.root_password,
            "FAMS_MYSQL_PORT": str(context.mysql_port),
        }
    )
    return environment


def compose_command(context: RuntimeContext, *args: str) -> list[str]:
    return ["docker", "compose", "-p", context.project, *args]


def verify_mysql_image() -> None:
    output = require_command(
        "pinned MySQL image metadata",
        [
            "docker",
            "image",
            "inspect",
            MYSQL_IMAGE,
            "--format",
            "{{json .RepoDigests}}|{{.Os}}|{{.Architecture}}",
        ],
        show=False,
    ).strip()
    digest_json, separator, platform = output.partition("|")
    if not separator:
        raise VerificationFailure("MySQL image metadata was not parseable")
    os_name, separator, architecture = platform.partition("|")
    if not separator or os_name != "linux" or architecture not in {"arm64", "amd64"}:
        raise VerificationFailure("MySQL image platform does not match a supported Linux engine")
    try:
        repo_digests = json.loads(digest_json)
    except json.JSONDecodeError as exc:
        raise VerificationFailure("MySQL RepoDigests metadata was invalid") from exc
    if not isinstance(repo_digests, list) or not any(
        isinstance(item, str) and item.endswith("@" + MYSQL_DIGEST)
        for item in repo_digests
    ):
        raise VerificationFailure("the local MySQL image does not match the frozen digest")


def start_compose(context: RuntimeContext) -> None:
    environment = compose_environment(context)
    model_output = require_command(
        "Compose policy model",
        compose_command(
            context,
            "config",
            "--format",
            "json",
            "--no-interpolate",
            "--no-normalize",
        ),
        env=environment,
        show=False,
    )
    try:
        model = json.loads(model_output)
    except json.JSONDecodeError as exc:
        raise VerificationFailure("Compose policy model was not valid JSON") from exc
    violations = compose_policy_violations(model)
    if violations:
        raise VerificationFailure("Compose policy violations: " + ", ".join(violations))
    record_policy_pass(
        "Compose static policy",
        "pinned MySQL, loopback port, required task variables, healthcheck, named volume",
    )

    require_command(
        "Compose first startup",
        compose_command(context, "up", "-d", "--wait"),
        env=environment,
        timeout=180,
    )
    require_command(
        "Compose repeated startup",
        compose_command(context, "up", "-d", "--wait"),
        env=environment,
        timeout=180,
    )
    provision_auth_database(context)
    verify_mysql_image()


def cleanup_compose(context: RuntimeContext) -> None:
    environment = compose_environment(context)
    result = run_rtk(
        compose_command(context, "down", "--volumes", "--remove-orphans"),
        env=environment,
        timeout=180,
    )
    output = result.stdout + result.stderr
    print(f"[Compose cleanup] exit={result.returncode}")
    if result.returncode != 0:
        excerpt = output_excerpt(output)
        if excerpt:
            print(excerpt)
        raise VerificationFailure("task-owned Compose cleanup failed")
    filters = (
        ("containers", ["docker", "ps", "-aq", "--filter", f"label=com.docker.compose.project={context.project}"]),
        ("volumes", ["docker", "volume", "ls", "-q", "--filter", f"label=com.docker.compose.project={context.project}"]),
        ("networks", ["docker", "network", "ls", "-q", "--filter", f"label=com.docker.compose.project={context.project}"]),
    )
    for label, command in filters:
        remaining = run_rtk(command, timeout=60)
        if remaining.returncode != 0 or remaining.stdout.strip():
            raise VerificationFailure(f"task-owned Compose {label} were not fully cleaned")
    record_policy_pass("Compose cleanup", "task container, network, and named volume removed")


def jdbc_url(context: RuntimeContext) -> str:
    return (
        f"jdbc:mysql://127.0.0.1:{context.mysql_port}/{context.database}"
        "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    )


def auth_database(context: RuntimeContext) -> str:
    match = re.fullmatch(r"fams-d6-02b-db-([0-9a-f]{16})", context.database)
    if match is None:
        raise VerificationFailure("runtime database name cannot derive an authentication schema")
    return f"fams-d6-04-db-{match.group(1)}"


def auth_jdbc_url(context: RuntimeContext) -> str:
    return (
        f"jdbc:mysql://127.0.0.1:{context.mysql_port}/{auth_database(context)}"
        "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    )


def asset_database(context: RuntimeContext) -> str:
    return auth_database(context).replace("d6-04", "d6-06")


def asset_jdbc_url(context: RuntimeContext) -> str:
    return (
        f"jdbc:mysql://127.0.0.1:{context.mysql_port}/{asset_database(context)}"
        "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    )


def inbound_database(context: RuntimeContext) -> str:
    return auth_database(context).replace("d6-04", "d6-07")


def inbound_jdbc_url(context: RuntimeContext) -> str:
    return (
        f"jdbc:mysql://127.0.0.1:{context.mysql_port}/{inbound_database(context)}"
        "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    )


def provision_auth_database(context: RuntimeContext) -> None:
    database = auth_database(context)
    asset_schema = asset_database(context)
    inbound_schema = inbound_database(context)
    attachment_schema = auth_database(context).replace("d6-04", "d6-09")
    job_schema = auth_database(context).replace("d6-04", "d6-08")
    sql = (
        f"CREATE DATABASE IF NOT EXISTS `{database}` "
        "CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; "
        f"CREATE DATABASE IF NOT EXISTS `{asset_schema}` "
        "CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; "
        f"CREATE DATABASE IF NOT EXISTS `{inbound_schema}` "
        "CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; "
        f"CREATE DATABASE IF NOT EXISTS `{attachment_schema}` "
        "CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; "
        f"CREATE DATABASE IF NOT EXISTS `{job_schema}` "
        "CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; "
        "SET GLOBAL log_bin_trust_function_creators=1; "
        f"CREATE USER IF NOT EXISTS 'd6_04_runtime'@'%' IDENTIFIED BY '{context.password}'; "
        f"ALTER USER 'd6_04_runtime'@'%' IDENTIFIED BY '{context.password}'; "
        f"GRANT ALL PRIVILEGES ON `{database}`.* TO 'd6_04_runtime'@'%';"
        f"GRANT ALL PRIVILEGES ON `{asset_schema}`.* TO 'd6_04_runtime'@'%';"
        f"GRANT ALL PRIVILEGES ON `{inbound_schema}`.* TO 'd6_04_runtime'@'%';"
        f"GRANT ALL PRIVILEGES ON `{attachment_schema}`.* TO 'd6_04_runtime'@'%';"
        f"GRANT ALL PRIVILEGES ON `{job_schema}`.* TO 'd6_04_runtime'@'%';"
    )
    require_command(
        "D6-04 authentication database provisioning",
        compose_command(
            context,
            "exec",
            "-T",
            "-e",
            f"MYSQL_PWD={context.root_password}",
            "mysql",
            "mysql",
            "-uroot",
            "--execute",
            sql,
        ),
        env=compose_environment(context),
        show=False,
    )


def runtime_environment(context: RuntimeContext) -> dict[str, str]:
    environment = os.environ.copy()
    environment.update(
        {
            "FAMS_TEST_NODE_EXECUTABLE": require_command(
                "resolved Node runtime", ["proxy", "node", "-p", "process.execPath"],
                show=False,
            ).strip(),
            "D6_02B_TEST_DATASOURCE_URL": jdbc_url(context),
            "D6_02B_TEST_DATASOURCE_USERNAME": context.username,
            "D6_02B_TEST_DATASOURCE_PASSWORD": context.password,
            "D6_04_TEST_DATASOURCE_URL": auth_jdbc_url(context),
            "D6_04_TEST_DATASOURCE_USERNAME": "d6_04_runtime",
            "D6_04_TEST_DATASOURCE_PASSWORD": context.password,
            "D6_05_TEST_DATASOURCE_URL": auth_jdbc_url(context),
            "D6_05_TEST_DATASOURCE_USERNAME": "d6_04_runtime",
            "D6_05_TEST_DATASOURCE_PASSWORD": context.password,
            "D6_06_TEST_DATASOURCE_URL": asset_jdbc_url(context),
            "D6_06_TEST_DATASOURCE_USERNAME": "d6_04_runtime",
            "D6_06_TEST_DATASOURCE_PASSWORD": context.password,
            "D6_07_TEST_DATASOURCE_URL": inbound_jdbc_url(context),
            "D6_07_TEST_DATASOURCE_USERNAME": "d6_04_runtime",
            "D6_07_TEST_DATASOURCE_PASSWORD": context.password,
            "D6_09_TEST_DATASOURCE_URL": auth_jdbc_url(context).replace("d6-04", "d6-09"),
            "D6_09_TEST_DATASOURCE_USERNAME": "d6_04_runtime",
            "D6_09_TEST_DATASOURCE_PASSWORD": context.password,
            "D6_08_TEST_DATASOURCE_URL": auth_jdbc_url(context).replace("d6-04", "d6-08"),
            "D6_08_TEST_DATASOURCE_USERNAME": "d6_04_runtime",
            "D6_08_TEST_DATASOURCE_PASSWORD": context.password,
        }
    )
    return environment


def parse_counter_ratio(report: ElementTree.Element, counter_type: str) -> tuple[int, int, float]:
    counter = report.find(f"./counter[@type='{counter_type}']")
    if counter is None:
        raise VerificationFailure(f"JaCoCo {counter_type} counter is missing")
    missed = int(counter.attrib["missed"])
    covered = int(counter.attrib["covered"])
    total = missed + covered
    if total <= 0:
        raise VerificationFailure(f"JaCoCo {counter_type} denominator is empty")
    return covered, missed, covered / total


def verify_java_reports() -> None:
    required = (CHECKSTYLE_REPORT, SPOTBUGS_REPORT, JACOCO_REPORT)
    missing = [path.relative_to(ROOT).as_posix() for path in required if not path.is_file()]
    if missing:
        raise VerificationFailure("missing Java quality reports: " + ", ".join(missing))

    checkstyle = ElementTree.parse(CHECKSTYLE_REPORT).getroot()
    if checkstyle.findall(".//error"):
        raise VerificationFailure("Checkstyle report contains violations")
    spotbugs = ElementTree.parse(SPOTBUGS_REPORT).getroot()
    if spotbugs.findall(".//BugInstance") or spotbugs.findall(".//Error"):
        raise VerificationFailure("SpotBugs report contains findings or analysis errors")

    report_files = {
        path.name: path for path in SUREFIRE_REPORTS.glob("TEST-*.xml")
    }
    missing_reports = sorted(REQUIRED_TEST_REPORTS - set(report_files))
    if missing_reports:
        raise VerificationFailure("missing required test reports: " + ", ".join(missing_reports))
    total_tests = 0
    for name in sorted(REQUIRED_TEST_REPORTS):
        suite = ElementTree.parse(report_files[name]).getroot()
        tests = int(suite.attrib.get("tests", "-1"))
        failures = int(suite.attrib.get("failures", "-1"))
        errors = int(suite.attrib.get("errors", "-1"))
        skipped = int(suite.attrib.get("skipped", "-1"))
        if tests <= 0 or failures or errors or skipped:
            raise VerificationFailure(f"required Java suite was not clean and zero-skipped: {name}")
        total_tests += tests
    if total_tests < MINIMUM_JAVA_TESTS:
        raise VerificationFailure("required Java suites executed fewer tests than the frozen baseline")

    report_xml = JACOCO_REPORT.read_text(encoding="utf-8")
    coverage_violations = coverage_policy_violations(
        report_xml,
        minimum_line_ratio=MINIMUM_LINE_COVERAGE,
        minimum_branch_ratio=MINIMUM_BRANCH_COVERAGE,
    )
    if coverage_violations:
        raise VerificationFailure("coverage violations: " + ", ".join(coverage_violations))
    report = ElementTree.fromstring(report_xml)
    line_covered, line_missed, line_ratio = parse_counter_ratio(report, "LINE")
    branch_covered, branch_missed, branch_ratio = parse_counter_ratio(report, "BRANCH")
    packages = {item.attrib["name"] for item in report.findall("./package")}
    generated_packages = {
        "com/fams/contract/api",
        "com/fams/contract/invoker",
        "com/fams/contract/model",
    }
    if packages & generated_packages:
        raise VerificationFailure("generator-owned Java packages leaked into the coverage denominator")
    record_policy_pass(
        "Java quality reports",
        f"tests={total_tests}, Checkstyle=0, SpotBugs=0, "
        f"lines={line_covered}/{line_covered + line_missed} ({line_ratio:.2%}), "
        f"branches={branch_covered}/{branch_covered + branch_missed} ({branch_ratio:.2%})",
    )


def verify_java_quality(context: RuntimeContext) -> None:
    try:
        environment = runtime_environment(context)
        require_command("Java clean", ["./mvnw", "-pl", "backend", "clean"],
                        env=environment, timeout=300)
        for label, flag, selected in (
            ("mini session V16 upgrade", "-Dd6.10.upgrade.runtime=true",
             "MiniSessionUpgradeRuntimeCase"),
            ("requisition V18 upgrade and constraints", "-Dd6.11.upgrade.runtime=true",
             "RequisitionUpgradeRuntimeCase"),
            ("requisition V19 change upgrade", "-Dd6.11.change.upgrade.runtime=true",
             "RequisitionChangeUpgradeRuntimeCase"),
            ("requisition V20 handover upgrade", "-Dd6.12.upgrade.runtime=true",
             "HandoverUpgradeRuntimeCase"),
            ("loan V21 borrowing and return upgrade", "-Dd6.13.upgrade.runtime=true",
             "LoanUpgradeRuntimeCase"),
            ("transfer V25 upgrade and repeat", "-Dd6.14.upgrade.runtime=true",
             "TransferUpgradeRuntimeCase"),
            ("transfer V27 ownership upgrade and repeat", "-Dd6.15.upgrade.runtime=true",
             "ReassignmentUpgradeRuntimeCase"),
            ("return V28 ownership upgrade and repeat", "-Dd6.15.return.upgrade.runtime=true",
             "ReturnReassignmentUpgradeRuntimeCase"),
            ("proxy confirmation V29 upgrade and repeat", "-Dd6.15.proxy.upgrade.runtime=true",
             "ProxyConfirmationUpgradeRuntimeCase"),
            ("inventory V31 populated upgrade and repeat", "-Dd6.16.inventory.upgrade.runtime=true",
             "InventoryUpgradeRuntimeCase"),
            ("inventory V32 populated upgrade and repeat", "-Dd6.17.inventory.upgrade.runtime=true",
             "InventoryObservationUpgradeRuntimeCase"),
            ("inventory V33 populated review upgrade and repeat", "-Dd6.18.inventory.upgrade.runtime=true",
             "InventoryReviewUpgradeRuntimeCase"),
            ("inventory V34 populated difference upgrade and repeat",
             "-Dd6.18.inventory.difference.upgrade.runtime=true", "InventoryDifferenceUpgradeRuntimeCase"),
            ("D6-18 selection filled upgrade runtime",
             "-Dd6.18.inventory.selection.upgrade.runtime=true", "InventorySelectionUpgradeRuntimeCase"),
            ("D6-18 evidence disposition filled upgrade runtime",
             "-Dd6.18.inventory.evidence.upgrade.runtime=true", "InventoryEvidenceUpgradeRuntimeCase"),
            ("inventory V37 populated finding links upgrade and repeat",
             "-Dd6.18.inventory.finding.upgrade.runtime=true", "InventoryFindingUpgradeRuntimeCase"),
            ("inventory V38 populated synchronization upgrade and repeat",
             "-Dd6.18.inventory.sync.upgrade.runtime=true", "InventorySynchronizationUpgradeRuntimeCase"),
            ("inventory V39 populated unobserved reasons upgrade and repeat",
             "-Dd6.18.inventory.readiness.upgrade.runtime=true", "InventoryReadinessUpgradeRuntimeCase"),
            ("inventory V40 populated sealed reports upgrade and repeat",
             "-Dd6.18.inventory.seal.upgrade.runtime=true", "InventorySealUpgradeRuntimeCase"),
            ("inventory V41 populated terminal supplement upgrade and repeat",
             "-Dd6.18.inventory.late.upgrade.runtime=true", "InventoryLateUpgradeRuntimeCase"),
            ("inventory recollection upgrade", "-Dd6.18.inventory.recollection.upgrade.runtime=true", "InventoryRecollectionUpgradeRuntimeCase"),
            ("repair V43 populated upgrade and repeat", "-Dd6.19.repair.upgrade.runtime=true", "RepairUpgradeRuntimeCase"),
            ("repair V44 populated collaboration upgrade", "-Dd6.19.repair.collaboration.upgrade.runtime=true", "RepairCollaborationUpgradeRuntimeCase"),
            ("repair V45 populated physical custody upgrade", "-Dd6.19.repair.physical.upgrade.runtime=true", "RepairPhysicalUpgradeRuntimeCase"),
            ("repair V46 populated actual return upgrade", "-Dd6.19.repair.return.upgrade.runtime=true", "RepairReturnUpgradeRuntimeCase"),
            ("repair V47 populated approved target expansion", "-Dd6.19.repair.return.target.upgrade.runtime=true", "RepairReturnTargetUpgradeRuntimeCase"),
            ("repair V48 populated proxy evidence expansion", "-Dd6.19.repair.proxy.upgrade.runtime=true", "RepairProxyUpgradeRuntimeCase"),
            ("repair V49 populated damaged return source expansion", "-Dd6.19.repair.return.source.upgrade.runtime=true", "RepairReturnSourceUpgradeRuntimeCase"),
            ("actual damaged return source adoption and native HTTP", "-Dd6.19.repair.return.source.runtime=true", "RepairReturnSourceHttpRuntimeCase"),
            ("actual repair approved proxy evidence receipt and native HTTP", "-Dd6.19.repair.proxy.runtime=true", "RepairProxyHttpRuntimeCase"),
            ("actual repair controlled reassignment and native HTTP", "-Dd6.19.repair.reassignment.runtime=true", "RepairReassignmentHttpRuntimeCase"),
            ("actual repair original receiver handover and continued borrowing HTTP", "-Dd6.19.repair.return.runtime=true", "RepairReturnHttpRuntimeCase"),
            ("actual repair physical acceptance and atomic inbound HTTP", "-Dd6.19.repair.physical.runtime=true", "RepairPhysicalHttpRuntimeCase"),
            ("invitation scoped technical opinions and private evidence HTTP", "-Dd6.19.repair.collaboration.runtime=true", "RepairCollaborationHttpRuntimeCase"),
            ("actual repair approval ownership custody rollback and photos HTTP", "-Dd6.19.repair.runtime=true", "RepairHttpRuntimeCase"),
            ("inventory synchronization declaration and native HTTP",
             "-Dd6.18.inventory.sync.runtime=true", "InventorySynchronizationHttpRuntimeCase"),
            ("inventory explicit unobserved reasons and waiting review HTTP",
             "-Dd6.18.inventory.readiness.runtime=true", "InventoryReadinessHttpRuntimeCase"),
            ("inventory immutable seal dates and native HTTP",
             "-Dd6.18.inventory.seal.runtime=true", "InventorySealHttpRuntimeCase"),
            ("inventory terminal supplemental decisions and native HTTP",
             "-Dd6.18.inventory.late.runtime=true", "InventoryLateHttpRuntimeCase"),
            ("inventory task snapshot and assignment HTTP", "-Dd6.16.inventory.runtime=true",
             "InventoryTaskHttpRuntimeCase"),
            ("inventory collection offline receipts and photos HTTP", "-Dd6.17.inventory.runtime=true",
             "InventoryObservationHttpRuntimeCase"),
            ("inventory explicit review and concurrent administration HTTP",
             "-Dd6.18.inventory.runtime=true", "InventoryReviewHttpRuntimeCase"),
            ("durable job coverage", "-Dd6.08.job.runtime=true",
             "DurableJobRuntimeCase#mysqlSubmissionClaimFencingBackoffAndBusinessRollback"),
            ("asset import batch coverage", "-Dd6.08.import.runtime=true",
             "AssetImportBatchRuntimeCase"),
            ("asset import HTTP coverage", "-Dd6.08.import.http.runtime=true",
             "AssetImportHttpRuntimeCase#warehouseUploadPreviewAndErrorRowsUseRealSessionAndMysql"),
        ):
            require_command(label, ["./mvnw", "-pl", "backend", "-Pquality", flag,
                                    f"-Dtest={selected}", "test"],
                            env=environment, timeout=420)
        output = require_command(
            "Java blocking quality",
            [
                "./mvnw",
                "-pl",
                "backend",
                "-Pquality",
                "-Dd6.02b.runtime=true",
                "-Dd6.04.runtime=true",
                "-Dd6.05.identity.runtime=true",
                "-Dd6.06.asset.runtime=true",
                "-Dd6.07.inbound.runtime=true",
                "-Dd6.11.requisition.runtime=true",
                "-Dd6.12.handover.runtime=true",
                "-Dd6.15.proxy.runtime=true",
                "-Dd6.09.attachment.runtime=true",
                # Real Clamd has its own owned-daemon gate; do not count it as a skipped pass here.
                "-Dtest=*Test,*Tests,Test*,*TestCase,!AttachmentHttpTest#realClamdAllowsCleanDownloadAndQuarantinesEicarAndDaemonOutage",
                "verify",
            ],
            env=environment,
            timeout=900,
        )
    except VerificationFailure:
        if JACOCO_REPORT.is_file():
            report = ElementTree.parse(JACOCO_REPORT).getroot()
            for counter_type in ("LINE", "BRANCH"):
                covered, missed, ratio = parse_counter_ratio(report, counter_type)
                print(f"[Java coverage diagnostic] {counter_type}: "
                      f"{covered}/{covered + missed} ({ratio:.2%})")
            missing_branches = []
            for java_class in report.findall("./package/class"):
                branch = java_class.find("./counter[@type='BRANCH']")
                if branch is not None:
                    missing_branches.append((int(branch.attrib["missed"]),
                                             java_class.attrib["name"]))
            for missed, name in sorted(missing_branches, reverse=True)[:12]:
                print(f"[Java branch diagnostic] {name}: missed={missed}")
        raise
    violations = java_test_output_violations(output)
    violations.extend(
        java_test_summary_violations(output, minimum_tests=MINIMUM_JAVA_TESTS)
    )
    if violations:
        raise VerificationFailure("Java test output violations: " + ", ".join(violations))
    verify_java_reports()


def verify_typescript_quality() -> None:
    lint_output = require_command("TypeScript lint", ["npm", "run", "lint"], timeout=300)
    lint_violations = typescript_lint_output_violations(lint_output)
    if lint_violations:
        raise VerificationFailure(
            "TypeScript lint violations: " + ", ".join(lint_violations)
        )
    require_command("TypeScript compile", ["npm", "run", "typecheck"], timeout=300)
    require_command("Web component tests", ["npm", "run", "test:web"], timeout=300)
    require_command("Web production build", ["npm", "run", "build:web"], timeout=300)
    require_command("Mini session transport tests", ["npm", "run", "test:mini"], timeout=300)
    require_command("Native mini package", ["npm", "run", "build", "--workspace", "@fams/miniprogram"], timeout=300)

    fixture = (
        ROOT
        / "packages/api-types/src"
        / f"d6-03a-negative-{secrets.token_hex(8)}.ts"
    )
    if fixture.exists():
        raise VerificationFailure("temporary TypeScript fixture path already exists")
    try:
        fixture.write_text(
            'const intentionallyUnused: any = "D6-03A negative fixture";\n',
            encoding="utf-8",
        )
        rejected = run_rtk(["npm", "run", "lint"], timeout=300)
        output = rejected.stdout + rejected.stderr
        violations = typescript_negative_fixture_violations(
            rejected.returncode,
            output,
        )
        if violations:
            raise VerificationFailure(
                "TypeScript negative fixture violations: " + ", ".join(violations)
            )
        record_policy_pass(
            "TypeScript negative fixture",
            "no-unused-vars and no-explicit-any rejected the temporary source",
        )
    finally:
        fixture.unlink(missing_ok=True)
    require_command("TypeScript post-fixture lint", ["npm", "run", "lint"], timeout=300)


def verify_dependency_integrity() -> None:
    npm_tree = require_command("npm dependency tree", ["npm", "ls", "--depth=0"])
    markers = (
        "@eslint/js@10.0.1",
        "eslint@10.10.0",
        "typescript-eslint@8.70.0",
        "typescript@5.9.2",
    )
    if any(marker not in npm_tree for marker in markers):
        raise VerificationFailure("resolved TypeScript quality dependencies drifted")
    require_command(
        "npm high-severity audit",
        ["npm", "audit", "--audit-level=high"],
        timeout=300,
    )
    dependency_tree = require_command(
        "Maven runtime dependency tree",
        ["./mvnw", "-pl", "backend", "dependency:tree", "-Dscope=runtime"],
        timeout=300,
    )
    runtime_markers = (
        "spring-boot-starter-actuator:jar:4.1.1",
        "micrometer-registry-prometheus:jar:1.17.1",
        "mysql-connector-j:jar:9.7.0",
    )
    if any(marker not in dependency_tree for marker in runtime_markers):
        raise VerificationFailure("resolved Java runtime dependencies drifted")
    record_policy_pass(
        "dependency integrity",
        "locked npm tree, high-severity audit, Enforcer, and runtime dependency markers passed",
    )


def find_application_jar() -> Path:
    jars = sorted(
        path
        for path in (ROOT / "backend/target").glob("*.jar")
        if not path.name.endswith(".original")
    )
    if len(jars) != 1:
        raise VerificationFailure(
            "expected one executable backend jar, found "
            + ", ".join(path.name for path in jars)
        )
    return jars[0]


def application_command(context: RuntimeContext, application_jar: Path) -> list[str]:
    """Build the raw RTK command used to retain complete startup evidence."""

    return [
        "rtk",
        "proxy",
        "java",
        "-jar",
        str(application_jar),
        "--server.address=127.0.0.1",
        f"--server.port={context.business_port}",
        f"--management.server.port={context.management_port}",
    ]


def start_application(context: RuntimeContext) -> AppProcess:
    environment = os.environ.copy()
    environment.update(
        {
            "SPRING_PROFILES_ACTIVE": "runtime-compatibility,local-observability",
            "SPRING_DATASOURCE_URL": jdbc_url(context),
            "SPRING_DATASOURCE_USERNAME": context.username,
            "SPRING_DATASOURCE_PASSWORD": context.password,
        }
    )
    descriptor, log_name = tempfile.mkstemp(prefix="fams-d6-03a-app-", suffix=".log")
    os.close(descriptor)
    log_path = Path(log_name)
    log_handle = log_path.open("w", encoding="utf-8")
    try:
        process = subprocess.Popen(
            application_command(context, find_application_jar()),
            cwd=ROOT,
            env=environment,
            stdout=log_handle,
            stderr=subprocess.STDOUT,
            text=True,
        )
    except BaseException:
        log_handle.close()
        log_path.unlink(missing_ok=True)
        raise
    return AppProcess(process=process, log_path=log_path, log_handle=log_handle)


def read_app_log(application: AppProcess) -> str:
    application.log_handle.flush()
    return redact(
        application.log_path.read_text(encoding="utf-8", errors="replace")
    )


def stop_application(application: AppProcess | None) -> str:
    if application is None:
        return ""
    if application.process.poll() is None:
        application.process.terminate()
        try:
            application.process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            application.process.kill()
            application.process.wait(timeout=10)
    application.log_handle.close()
    try:
        content = redact(
            application.log_path.read_text(encoding="utf-8", errors="replace")
        )
    finally:
        application.log_path.unlink(missing_ok=True)
    return content


def http_request(
    port: int,
    path: str,
    *,
    method: str = "GET",
    body: dict[str, object] | None = None,
    accept: str = "application/json",
    timeout: float = 8,
) -> tuple[int, str]:
    payload: bytes | None = None
    headers = {"Accept": accept}
    if body is not None:
        payload = json.dumps(body, separators=(",", ":")).encode("utf-8")
        headers["Content-Type"] = "application/json"
    request = Request(
        f"http://127.0.0.1:{port}{path}",
        data=payload,
        headers=headers,
        method=method,
    )
    try:
        with urlopen(request, timeout=timeout) as response:
            return response.status, response.read().decode("utf-8")
    except HTTPError as error:
        return error.code, error.read().decode("utf-8")


def wait_for_management_health(
    application: AppProcess,
    context: RuntimeContext,
    *,
    timeout: float = 60,
) -> None:
    deadline = time.monotonic() + timeout
    last_status = 0
    last_body = ""
    while time.monotonic() < deadline:
        if application.process.poll() is not None:
            excerpt = output_excerpt(read_app_log(application))
            raise VerificationFailure(
                "application exited before management health was UP: " + excerpt
            )
        try:
            last_status, last_body = http_request(
                context.management_port,
                "/actuator/health",
            )
            parsed = json.loads(last_body)
            if (
                last_status == HTTPStatus.OK
                and isinstance(parsed, dict)
                and parsed.get("status") == "UP"
            ):
                return
        except (URLError, TimeoutError, ConnectionError, json.JSONDecodeError):
            pass
        time.sleep(0.5)
    raise VerificationFailure(
        "management health did not become UP "
        f"(last status={last_status}, body={redact(last_body)[:200]})"
    )


def verify_loopback_listener(context: RuntimeContext) -> None:
    output = require_command(
        "management listener",
        [
            "lsof",
            "-nP",
            f"-iTCP:{context.management_port}",
            "-sTCP:LISTEN",
        ],
    )
    expected = f"127.0.0.1:{context.management_port}"
    if expected not in output or f"*:{context.management_port}" in output:
        raise VerificationFailure("management listener is not restricted to IPv4 loopback")


def verify_observability_http(context: RuntimeContext) -> None:
    business_status, _ = http_request(
        context.business_port,
        "/actuator/prometheus",
        accept="text/plain",
    )
    health_status, health_body = http_request(
        context.management_port,
        "/actuator/health",
    )
    metrics_status, metrics_body = http_request(
        context.management_port,
        "/actuator/prometheus",
        accept="text/plain",
    )
    violations = observability_policy_violations(
        business_metrics_status=business_status,
        management_health_status=health_status,
        management_health_body=health_body,
        management_metrics_status=metrics_status,
        management_metrics_body=metrics_body,
    )
    if violations:
        raise VerificationFailure(
            "runtime observability violations: " + ", ".join(violations)
        )
    verify_loopback_listener(context)
    record_policy_pass(
        "restricted metrics HTTP",
        "business=404, management health=UP, Prometheus JVM metrics present, loopback listener",
    )


def write_runtime_probe(context: RuntimeContext, key: str) -> None:
    status, body = http_request(
        context.business_port,
        "/runtime-compatibility/probes",
        method="POST",
        body={"probeKey": key, "probeValue": "d6-03a-persisted"},
    )
    try:
        parsed = json.loads(body)
    except json.JSONDecodeError as exc:
        raise VerificationFailure("runtime write response was not JSON") from exc
    if (
        status != HTTPStatus.CREATED
        or not isinstance(parsed, dict)
        or parsed.get("probeKey") != key
        or parsed.get("probeValue") != "d6-03a-persisted"
    ):
        raise VerificationFailure("runtime probe write failed")


def read_runtime_probe(context: RuntimeContext, key: str) -> None:
    status, body = http_request(
        context.business_port,
        f"/runtime-compatibility/probes/{key}",
    )
    try:
        parsed = json.loads(body)
    except json.JSONDecodeError as exc:
        raise VerificationFailure("runtime read response was not JSON") from exc
    if (
        status != HTTPStatus.OK
        or not isinstance(parsed, dict)
        or parsed.get("probeKey") != key
        or parsed.get("probeValue") != "d6-03a-persisted"
    ):
        raise VerificationFailure("runtime probe did not survive restart")


def verify_runtime_recovery(
    context: RuntimeContext,
) -> AppProcess:
    probe_key = f"d6-03a-recovery-{secrets.token_hex(8)}"
    application = start_application(context)
    try:
        wait_for_management_health(application, context)
        verify_observability_http(context)
        write_runtime_probe(context, probe_key)
        first_log = stop_application(application)
        application = None
        if "Started ContractProbeApplication" not in first_log:
            raise VerificationFailure("first application start was not observed in its log")

        application = start_application(context)
        wait_for_management_health(application, context)
        read_runtime_probe(context, probe_key)
        record_policy_pass(
            "application restart recovery",
            "task probe remained readable after a clean application restart",
        )

        require_command(
            "database restart",
            compose_command(context, "restart", "mysql"),
            env=compose_environment(context),
            timeout=180,
        )
        require_command(
            "database restart health",
            compose_command(context, "up", "-d", "--wait"),
            env=compose_environment(context),
            timeout=180,
        )
        wait_for_management_health(application, context, timeout=90)
        read_runtime_probe(context, probe_key)
        verify_observability_http(context)
        record_policy_pass(
            "database restart recovery",
            "same-volume data and application health recovered after MySQL restart",
        )
        return application
    except BaseException:
        stop_application(application)
        raise


def verify_final_worktree() -> None:
    clear_generated_outputs()
    require_command("git diff check", ["git", "diff", "--check"])
    status = require_command("clean worktree postflight", ["git", "status", "--porcelain"])
    if status.strip():
        raise VerificationFailure("verification left tracked or untracked workspace changes")


def main() -> None:
    context: RuntimeContext | None = None
    application: AppProcess | None = None
    failure: BaseException | None = None
    try:
        assert_clean_start()
        verify_self_tests()
        verify_tool_versions()
        verify_static_configuration()
        verify_current_head_secrets()
        install_locked_dependencies()
        verify_new_commit_messages()
        verify_retained_gates()

        context = create_runtime_context()
        start_compose(context)
        verify_java_quality(context)
        verify_typescript_quality()
        verify_dependency_integrity()
        application = verify_runtime_recovery(context)
        stop_application(application)
        application = None
    except BaseException as exc:
        failure = exc
    finally:
        if application is not None:
            try:
                stop_application(application)
            except BaseException as cleanup_error:
                failure = failure or cleanup_error
        if context is not None:
            try:
                cleanup_compose(context)
            except BaseException as cleanup_error:
                failure = failure or cleanup_error
        try:
            verify_final_worktree()
        except BaseException as cleanup_error:
            failure = failure or cleanup_error

    if failure is not None:
        raise failure
    if not all_blocking_steps_pass(BLOCKING_STEPS):
        raise VerificationFailure("one or more blocking steps did not execute successfully")
    print(
        "D6-03A verification passed "
        f"({len(BLOCKING_STEPS)} blocking observations; local development only)."
    )


if __name__ == "__main__":
    try:
        main()
    except (VerificationFailure, subprocess.TimeoutExpired, OSError) as error:
        print(f"D6-03A verification failed: {redact(str(error))}", file=sys.stderr)
        raise SystemExit(1)
