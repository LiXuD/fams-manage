#!/usr/bin/env python3
"""D6-06 isolated MySQL HTTP and migration gate."""

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
CONTAINER = "fams-d6-06-" + secrets.token_hex(8)
PASSWORD = secrets.token_hex(32)
ROOT_PASSWORD = secrets.token_hex(32)


def run(*args: str, env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(["rtk", *args], cwd=ROOT, env=env, text=True,
                            capture_output=True, timeout=420, check=False)
    if result.returncode:
        output = (result.stdout + result.stderr).replace(PASSWORD, "<redacted>")
        output = output.replace(ROOT_PASSWORD, "<redacted>")
        raise RuntimeError(f"{' '.join(args[:4])} exited {result.returncode}:\n"
                           + "\n".join(output.splitlines()[-30:]))
    return result


def main() -> None:
    java = run("/usr/libexec/java_home", "-v", "21").stdout.strip()
    env = os.environ.copy()
    env["JAVA_HOME"] = java
    env["PATH"] = java + "/bin:" + env.get("PATH", "")
    run("docker", "run", "-d", "--name", CONTAINER,
        "-e", "MYSQL_ROOT_PASSWORD=" + ROOT_PASSWORD,
        "-e", "MYSQL_DATABASE=fams_d6_06",
        "-e", "MYSQL_USER=fams_d6_06",
        "-e", "MYSQL_PASSWORD=" + PASSWORD,
        "-p", "127.0.0.1::3306", IMAGE)
    for _ in range(60):
        ping = subprocess.run(["rtk", "docker", "exec", CONTAINER, "mysqladmin",
                               "ping", "-uroot", "-p" + ROOT_PASSWORD, "--silent"],
                              cwd=ROOT, capture_output=True, timeout=15, check=False)
        if ping.returncode == 0:
            break
        time.sleep(1)
    else:
        raise RuntimeError("MySQL did not become ready")
    port = run("docker", "port", CONTAINER, "3306/tcp").stdout.strip().rsplit(":", 1)[-1]
    env["D6_06_TEST_DATASOURCE_URL"] = (
        f"jdbc:mysql://127.0.0.1:{port}/fams_d6_06"
        "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC")
    env["D6_06_TEST_DATASOURCE_USERNAME"] = "fams_d6_06"
    env["D6_06_TEST_DATASOURCE_PASSWORD"] = PASSWORD
    run("./mvnw", "-pl", "backend", "-Dd6.06.asset.runtime=true",
        "-Dtest=AssetHttpTest", "test", env=env)
    report = ROOT / "backend/target/surefire-reports/TEST-com.fams.asset.AssetHttpTest.xml"
    suite = ET.parse(report).getroot()
    counts = tuple(int(suite.attrib.get(name, "-1"))
                   for name in ("tests", "failures", "errors", "skipped"))
    if counts != (1, 0, 0, 0):
        raise RuntimeError(f"HTTP suite incomplete: {counts}")
    print("D6-06 HTTP gate: 1 real MySQL scenario passed")


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(f"D6-06 FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)
    finally:
        subprocess.run(["rtk", "docker", "rm", "-f", CONTAINER], cwd=ROOT,
                       capture_output=True, check=False)
