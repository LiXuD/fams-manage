#!/usr/bin/env python3
"""Run D6-05 identity and catalog HTTP tests against task-owned MySQL 8.4."""

from __future__ import annotations

import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
IMAGE = "mysql:8.4.11@sha256:3466ba4a4828aa8d46fb7c3bc16b67b781c98413cf4ea0fac6feaa6e881faa26"
RESOURCE_PREFIX = "fams-d6-05a-"
CONTAINER: str | None = None
SECRETS: list[str] = []


class VerificationFailure(RuntimeError):
    """The D6-05A gate did not produce the required evidence."""


def run(args: list[str], *, env: dict[str, str] | None = None, check: bool = True) -> subprocess.CompletedProcess[str]:
    completed = subprocess.run(
        ["rtk", *args], cwd=ROOT, env=env or os.environ.copy(), capture_output=True,
        text=True, timeout=420, check=False,
    )
    if check and completed.returncode != 0:
        tail = "\n".join((completed.stdout + completed.stderr).splitlines()[-30:])
        for secret in SECRETS:
            tail = tail.replace(secret, "<redacted>")
        raise VerificationFailure(f"{' '.join(args)} failed ({completed.returncode}):\n{tail}")
    return completed


def java_environment() -> dict[str, str]:
    home = run(["/usr/libexec/java_home", "-v", "21"]).stdout.strip()
    environment = os.environ.copy()
    environment["JAVA_HOME"] = home
    environment["PATH"] = f"{home}/bin:{environment.get('PATH', '')}"
    if '"21.0.7"' not in (run(["java", "-version"], env=environment).stderr):
        raise VerificationFailure("Java 21.0.7 is required")
    return environment


def start_mysql() -> tuple[str, str, str]:
    global CONTAINER
    suffix = secrets.token_hex(8)
    CONTAINER = f"{RESOURCE_PREFIX}mysql-{suffix}"
    database = f"fams-d6-05-db-{suffix}"
    root_password = secrets.token_hex(32)
    user_password = secrets.token_hex(32)
    SECRETS.extend((root_password, user_password))
    run([
        "docker", "run", "-d", "--name", CONTAINER,
        "-e", f"MYSQL_ROOT_PASSWORD={root_password}", "-e", f"MYSQL_DATABASE={database}",
        "-e", "MYSQL_USER=d6_05_runtime", "-e", f"MYSQL_PASSWORD={user_password}",
        "-p", "127.0.0.1::3306", IMAGE,
    ])
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        ping = run(
            ["docker", "exec", CONTAINER, "mysqladmin", "ping", "-uroot", f"-p{root_password}", "--silent"],
            check=False,
        )
        if ping.returncode == 0:
            port = run(["docker", "port", CONTAINER, "3306/tcp"]).stdout.strip().rsplit(":", 1)[-1]
            return database, user_password, port
        time.sleep(1)
    raise VerificationFailure("task-owned MySQL did not become ready")


def cleanup() -> None:
    if not CONTAINER:
        return
    run(["docker", "rm", "-f", CONTAINER], check=False)
    remaining = run(
        ["docker", "ps", "-a", "--filter", f"name=^{CONTAINER}$", "--format", "{{.Names}}"],
        check=False,
    ).stdout.strip()
    if remaining:
        raise VerificationFailure(f"task-owned container remains: {CONTAINER}")


def main() -> int:
    try:
        environment = java_environment()
        database, password, port = start_mysql()
        environment.update({
            "D6_05_TEST_DATASOURCE_URL": (
                f"jdbc:mysql://127.0.0.1:{port}/{database}"
                "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
            ),
            "D6_05_TEST_DATASOURCE_USERNAME": "d6_05_runtime",
            "D6_05_TEST_DATASOURCE_PASSWORD": password,
        })
        run([
            "./mvnw", "-pl", "backend", "-Dd6.05.identity.runtime=true",
            "-Dtest=IdentityMasterDataHttpTest", "test",
        ], env=environment)
        report = ROOT / "backend/target/surefire-reports/TEST-com.fams.masterdata.IdentityMasterDataHttpTest.xml"
        suite = ET.parse(report).getroot()
        counts = tuple(int(suite.attrib.get(name, "-1")) for name in ("tests", "failures", "errors", "skipped"))
        if counts != (14, 0, 0, 0):
            raise VerificationFailure(f"runtime suite is incomplete: {counts}")
        print("D6-05 PASSED: 14 real MySQL HTTP cases, including identity and catalog administration.")
        return 0
    except (OSError, subprocess.TimeoutExpired, VerificationFailure) as exception:
        print(f"D6-05A FAILED: {exception}", file=sys.stderr)
        return 1
    finally:
        cleanup()


if __name__ == "__main__":
    raise SystemExit(main())
