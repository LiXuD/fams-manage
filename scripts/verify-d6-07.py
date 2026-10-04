#!/usr/bin/env python3
"""Run the D6-07 HTTP path against an isolated MySQL container."""

from __future__ import annotations

import os
from pathlib import Path
import secrets
import subprocess
import sys
import time


ROOT = Path(__file__).resolve().parents[1]
IMAGE = "mysql:8.4.11@sha256:3466ba4a4828aa8d46fb7c3bc16b67b781c98413cf4ea0fac6feaa6e881faa26"
CONTAINER = "fams-d6-07-" + secrets.token_hex(8)
PASSWORD = secrets.token_hex(32)
ROOT_PASSWORD = secrets.token_hex(32)


def run(*args: str, env: dict[str, str] | None = None) -> str:
    result = subprocess.run(["rtk", *args], cwd=ROOT, env=env, text=True,
                            capture_output=True, timeout=420, check=False)
    if result.returncode:
        output = (result.stdout + result.stderr).replace(PASSWORD, "<redacted>")
        output = output.replace(ROOT_PASSWORD, "<redacted>")
        raise RuntimeError(f"{' '.join(args[:4])} exited {result.returncode}:\n"
                           + "\n".join(output.splitlines()[-50:]))
    return result.stdout.strip()


def main() -> None:
    java = run("/usr/libexec/java_home", "-v", "21")
    env = os.environ.copy()
    env["JAVA_HOME"] = java
    env["PATH"] = java + "/bin:" + env.get("PATH", "")
    run("docker", "run", "-d", "--name", CONTAINER,
        "-e", "MYSQL_ROOT_PASSWORD=" + ROOT_PASSWORD,
        "-e", "MYSQL_DATABASE=fams_d6_07", "-e", "MYSQL_USER=fams_d6_07",
        "-e", "MYSQL_PASSWORD=" + PASSWORD, "-p", "127.0.0.1::3306", IMAGE)
    for _ in range(60):
        ping = subprocess.run(["rtk", "docker", "exec", CONTAINER, "mysqladmin",
                               "ping", "-uroot", "-p" + ROOT_PASSWORD, "--silent"],
                              cwd=ROOT, capture_output=True, timeout=15, check=False)
        if ping.returncode == 0:
            break
        time.sleep(1)
    else:
        raise RuntimeError("MySQL did not become ready")
    port = run("docker", "port", CONTAINER, "3306/tcp").rsplit(":", 1)[-1]
    env["D6_07_TEST_DATASOURCE_URL"] = (
        f"jdbc:mysql://127.0.0.1:{port}/fams_d6_07"
        "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC")
    env["D6_07_TEST_DATASOURCE_USERNAME"] = "fams_d6_07"
    env["D6_07_TEST_DATASOURCE_PASSWORD"] = PASSWORD
    run("./mvnw", "-pl", "backend", "-Dd6.07.inbound.runtime=true",
        "-Dtest=InboundHttpTest", "test", env=env)
    print("D6-07 real MySQL HTTP path passed")


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(f"D6-07 FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)
    finally:
        subprocess.run(["rtk", "docker", "rm", "-f", CONTAINER], cwd=ROOT,
                       capture_output=True, check=False)
