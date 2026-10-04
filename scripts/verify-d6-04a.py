#!/usr/bin/env python3
"""Run the D6-04A authentication gate against task-owned MySQL 8.4."""

from __future__ import annotations

import os
import secrets
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
IMAGE = "mysql:8.4.11@sha256:3466ba4a4828aa8d46fb7c3bc16b67b781c98413cf4ea0fac6feaa6e881faa26"
RESOURCE_PREFIX = "fams-d6-04a-"
JAVA_VERSION = "21.0.7"
CONTAINER: str | None = None
SECRET_VALUES: list[str] = []


class VerificationFailure(RuntimeError):
    """The D6-04A gate did not produce the required evidence."""


def run_rtk(
    args: list[str],
    *,
    env: dict[str, str] | None = None,
    timeout: float = 300,
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    completed = subprocess.run(
        ["rtk", *args],
        cwd=ROOT,
        env=env or os.environ.copy(),
        capture_output=True,
        text=True,
        timeout=timeout,
        check=False,
    )
    if check and completed.returncode != 0:
        command = " ".join(args)
        tail = "\n".join((completed.stdout + completed.stderr).splitlines()[-25:])
        for secret in SECRET_VALUES:
            command = command.replace(secret, "<redacted>")
            tail = tail.replace(secret, "<redacted>")
        raise VerificationFailure(f"rtk {command} failed ({completed.returncode}):\n{tail}")
    return completed


def java21_environment() -> dict[str, str]:
    java_home = run_rtk(["/usr/libexec/java_home", "-v", "21"], timeout=30).stdout.strip()
    if not java_home:
        raise VerificationFailure("Java 21 home was not resolved")
    environment = os.environ.copy()
    environment["JAVA_HOME"] = java_home
    environment["PATH"] = f"{java_home}/bin:{environment.get('PATH', '')}"
    version = run_rtk(["java", "-version"], env=environment, timeout=30)
    banner = version.stdout + version.stderr
    if f'"{JAVA_VERSION}"' not in banner:
        raise VerificationFailure(f"Java version is not the frozen {JAVA_VERSION}")
    return environment


def start_mysql() -> tuple[str, str, str]:
    global CONTAINER
    suffix = secrets.token_hex(8)
    CONTAINER = f"{RESOURCE_PREFIX}mysql-{suffix}"
    database = f"fams-d6-04-db-{suffix}"
    root_password = secrets.token_hex(32)
    user_password = secrets.token_hex(32)
    SECRET_VALUES.extend((root_password, user_password))
    run_rtk([
        "docker",
        "run",
        "-d",
        "--name",
        CONTAINER,
        "-e",
        f"MYSQL_ROOT_PASSWORD={root_password}",
        "-e",
        f"MYSQL_DATABASE={database}",
        "-e",
        "MYSQL_USER=d6_04_runtime",
        "-e",
        f"MYSQL_PASSWORD={user_password}",
        "-p",
        "127.0.0.1::3306",
        IMAGE,
    ])
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        ping = run_rtk(
            ["docker", "exec", CONTAINER, "mysqladmin", "ping", "-uroot", f"-p{root_password}", "--silent"],
            timeout=10,
            check=False,
        )
        if ping.returncode == 0:
            port_output = run_rtk(["docker", "port", CONTAINER, "3306/tcp"], timeout=30).stdout.strip()
            host_port = port_output.rsplit(":", 1)[-1]
            return database, user_password, host_port
        time.sleep(1)
    raise VerificationFailure("Task-owned MySQL did not become ready")


def assert_suite(report_name: str, expected_tests: int) -> None:
    report = ROOT / "backend" / "target" / "surefire-reports" / report_name
    if not report.is_file():
        raise VerificationFailure(f"Missing runtime report: {report_name}")
    suite = ET.parse(report).getroot()
    counts = tuple(int(suite.attrib.get(name, "-1")) for name in ("tests", "failures", "errors", "skipped"))
    if counts != (expected_tests, 0, 0, 0):
        raise VerificationFailure(f"Runtime suite was not clean and zero-skipped: {report_name}={counts}")


def cleanup() -> None:
    if CONTAINER:
        run_rtk(["docker", "rm", "-f", CONTAINER], timeout=30, check=False)
        remaining = run_rtk(
            ["docker", "ps", "-a", "--filter", f"name=^{CONTAINER}$", "--format", "{{.Names}}"],
            timeout=30,
            check=False,
        ).stdout.strip()
        if remaining:
            raise VerificationFailure(f"Task-owned Docker container was not removed: {CONTAINER}")


def main() -> int:
    try:
        environment = java21_environment()
        database, user_password, host_port = start_mysql()
        environment.update({
            "D6_04_TEST_DATASOURCE_URL": (
                f"jdbc:mysql://127.0.0.1:{host_port}/{database}"
                "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
            ),
            "D6_04_TEST_DATASOURCE_USERNAME": "d6_04_runtime",
            "D6_04_TEST_DATASOURCE_PASSWORD": user_password,
        })
        run_rtk([
            "./mvnw",
            "-pl",
            "backend",
            "-Dd6.04.runtime=true",
            "-Dtest=AuthHttpTest,AuthSessionRestartTest",
            "test",
        ], env=environment, timeout=360)
        assert_suite("TEST-com.fams.auth.AuthHttpTest.xml", 7)
        assert_suite("TEST-com.fams.auth.AuthSessionRestartTest.xml", 1)
        run_rtk(["./mvnw", "-pl", "backend", "test"], env=environment, timeout=300)
        print(
            "D6-04A PASSED: Java 21.0.7; MySQL 8.4 migration; "
            "7 real HTTP tests; 1 restart test; normal Java regression suite."
        )
        return 0
    except (OSError, subprocess.TimeoutExpired, VerificationFailure) as exception:
        print(f"D6-04A FAILED: {exception}", file=sys.stderr)
        return 1
    finally:
        cleanup()


if __name__ == "__main__":
    raise SystemExit(main())
