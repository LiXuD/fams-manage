#!/usr/bin/env python3
"""Run the D6-03A local Compose and quality gate."""

from __future__ import annotations

from collections.abc import Iterable
import json
from typing import Any
import xml.etree.ElementTree as ElementTree


MYSQL_IMAGE = (
    "mysql:8.4.11@sha256:"
    "3466ba4a4828aa8d46fb7c3bc16b67b781c98413cf4ea0fac6feaa6e881faa26"
)


def java_test_output_violations(output: str) -> list[str]:
    """Reject Java test runs that rely on runtime agent attachment."""

    violations: list[str] = []
    if "Mockito is currently self-attaching" in output:
        violations.append("mockito-self-attachment-detected")
    if "A Java agent has been loaded dynamically" in output:
        violations.append("dynamic-java-agent-loading-detected")
    return violations


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
    if (
        not isinstance(service_volumes, list)
        or named_volume not in service_volumes
        or not isinstance(declared_volumes, dict)
        or "mysql-data" not in declared_volumes
    ):
        violations.append("mysql-named-volume-missing")

    environment = mysql.get("environment")
    if not isinstance(environment, dict):
        environment = {}
    required_variables = {
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


def all_blocking_steps_pass(steps: Iterable[tuple[str, int, str]]) -> bool:
    """Return whether the supplied blocking steps completed successfully."""

    observed = tuple(steps)
    return bool(observed) and all(
        returncode == 0 and status == "EXECUTED"
        for _, returncode, status in observed
    )
