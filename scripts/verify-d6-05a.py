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
        command = " ".join(args)
        for secret in SECRETS:
            tail = tail.replace(secret, "<redacted>")
            command = command.replace(secret, "<redacted>")
        raise VerificationFailure(f"{command} failed ({completed.returncode}):\n{tail}")
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


def verify_v3_to_v5_sql_upgrade(database: str, password: str) -> None:
    """Apply the immutable V1-V3 files before V4/V5 in a second task-owned schema."""
    if not CONTAINER:
        raise VerificationFailure("task-owned MySQL container is missing")
    mysql = ["docker", "exec", "-i", CONTAINER, "mysql", "-h127.0.0.1",
             "-ud6_05_runtime", f"-p{password}"]
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        ready = run(mysql + ["-D", database, "-N", "-e", "SELECT 1"], check=False)
        if ready.returncode == 0 and ready.stdout.strip() == "1":
            break
        time.sleep(1)
    else:
        raise VerificationFailure("task-owned MySQL did not accept authenticated upgrade queries")
    migration_dir = ROOT / "backend/src/main/resources/db/migration/d6_04"
    migrations = (
        "V1__create_authentication.sql",
        "V2__create_catalog_master_data.sql",
        "V3__create_numbering_and_business_parameters.sql",
        "V4__create_asset_fact.sql",
        "V5__create_inbound_targets.sql",
    )
    for migration in migrations:
        sql = (migration_dir / migration).read_text(encoding="utf-8")
        completed = subprocess.run(
            ["rtk", *mysql, database], input=sql,
            cwd=ROOT, capture_output=True, text=True, timeout=60, check=False,
        )
        if completed.returncode:
            output = (completed.stdout + completed.stderr).replace(password, "<redacted>")
            raise VerificationFailure(f"V3-to-V5 SQL upgrade failed at {migration}: {output[-1000:]}")
        if migration == "V3__create_numbering_and_business_parameters.sql":
            run(mysql + ["-D", database, "-e",
                         "INSERT INTO fams_warehouse "
                         "(code, code_normalized, name, status, version, created_at, updated_at) "
                         "VALUES ('EXISTING', 'EXISTING', 'Existing warehouse', 'ACTIVE', 0, "
                         "'2026-09-23 01:00:00', '2026-09-23 01:00:00')"])
    tables = run(mysql + ["-N", "-D", database, "-e",
                          "SELECT COUNT(*) FROM information_schema.tables "
                          "WHERE table_schema = DATABASE() AND table_name IN "
                          "('fams_asset','fams_inbound_receipt','fams_inbound_receipt_item')"])
    if tables.stdout.strip() != "3":
        raise VerificationFailure("V3-to-V5 SQL upgrade did not create all three domain tables")
    preserved = run(mysql + ["-N", "-D", database, "-e",
                             "SELECT COUNT(*) FROM fams_warehouse WHERE code = 'EXISTING'"])
    if preserved.stdout.strip() != "1":
        raise VerificationFailure("V3-to-V5 SQL upgrade did not preserve the existing warehouse")


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
        if counts != (24, 0, 0, 0):
            raise VerificationFailure(f"runtime suite is incomplete: {counts}")
        cleanup()
        upgrade_database, upgrade_password, _ = start_mysql()
        verify_v3_to_v5_sql_upgrade(upgrade_database, upgrade_password)
        print("D6-05 PASSED: 24 real MySQL integration cases; V1-V3 SQL to V5 preserved existing data.")
        return 0
    except (OSError, subprocess.TimeoutExpired, VerificationFailure) as exception:
        print(f"D6-05A FAILED: {exception}", file=sys.stderr)
        return 1
    finally:
        cleanup()


if __name__ == "__main__":
    raise SystemExit(main())
