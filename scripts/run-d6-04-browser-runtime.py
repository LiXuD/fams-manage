#!/usr/bin/env python3
"""Start a disposable D6-04 browser stack; Enter stops only owned resources."""

from __future__ import annotations

import os
import secrets
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Callable


ROOT = Path(__file__).resolve().parents[1]
IMAGE = "mysql:8.4.11@sha256:3466ba4a4828aa8d46fb7c3bc16b67b781c98413cf4ea0fac6feaa6e881faa26"
JAR = ROOT / "backend/target/contract-generation-probe-0.1.0-SNAPSHOT.jar"
INITIAL_PASSWORD = "correct-horse-battery-staple"


def checked(args: list[str], **kwargs: object) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(args, text=True, capture_output=True, check=False, **kwargs)
    if result.returncode:
        raise RuntimeError(f"{args[0]} exited {result.returncode}")
    return result


def ready(url: str, process: subprocess.Popen[bytes] | None = None) -> bool:
    if process is not None and process.poll() is not None:
        raise RuntimeError("Owned server terminated during startup")
    try:
        with urllib.request.urlopen(url, timeout=2) as response:
            return response.status in (200, 401)
    except urllib.error.HTTPError as error:
        return error.code in (401, 403)
    except (OSError, TimeoutError):
        return False


def wait_for(check: Callable[[], bool], label: str, seconds: int = 75) -> None:
    until = time.monotonic() + seconds
    while time.monotonic() < until:
        if check():
            return
        time.sleep(1)
    raise RuntimeError(f"{label} not ready after {seconds}s")


def main() -> int:
    if not JAR.is_file():
        raise RuntimeError("Build the backend jar first with Java 21")
    suffix = secrets.token_hex(8)
    name = f"fams-d6-04-browser-mysql-{suffix}"
    database = f"fams-d6-04-db-{suffix}"
    root_password = secrets.token_hex(32)
    user_password = secrets.token_hex(32)
    processes: list[subprocess.Popen[bytes]] = []
    container_started = False
    with tempfile.TemporaryDirectory(prefix="fams-d6-04-browser-") as temporary:
        try:
            checked(["rtk", "docker", "run", "-d", "--name", name,
                     "-e", f"MYSQL_ROOT_PASSWORD={root_password}",
                     "-e", f"MYSQL_DATABASE={database}",
                     "-e", "MYSQL_USER=d6_04_runtime",
                     "-e", f"MYSQL_PASSWORD={user_password}",
                     "-p", "127.0.0.1::3306", IMAGE])
            container_started = True

            def mysql_ready() -> bool:
                probe = subprocess.run(
                    ["rtk", "docker", "exec", name, "mysqladmin", "ping", "-uroot",
                     f"-p{root_password}", "--silent"], capture_output=True, check=False)
                return probe.returncode == 0

            wait_for(mysql_ready, "MySQL")
            port = checked(["rtk", "docker", "port", name, "3306/tcp"]).stdout.strip().rsplit(":", 1)[-1]

            def mysql_host_ready() -> bool:
                try:
                    with socket.create_connection(("127.0.0.1", int(port)), timeout=2) as connection:
                        return len(connection.recv(4)) == 4
                except (OSError, ValueError):
                    return False

            wait_for(mysql_host_ready, "MySQL host port")
            java_home = checked(["rtk", "/usr/libexec/java_home", "-v", "21"]).stdout.strip()
            environment = os.environ.copy()
            environment.update({
                "JAVA_HOME": java_home,
                "PATH": f"{java_home}/bin:{environment.get('PATH', '')}",
                "SPRING_DATASOURCE_URL": (
                    f"jdbc:mysql://127.0.0.1:{port}/{database}"
                    "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"),
                "SPRING_DATASOURCE_USERNAME": "d6_04_runtime",
                "SPRING_DATASOURCE_PASSWORD": user_password,
                "FAMS_SECURITY_IDEMPOTENCY_HMAC_KEY": secrets.token_hex(32),
            })
            backend_log = open(Path(temporary) / "backend.log", "wb")
            backend = subprocess.Popen(
                ["rtk", "java", "-jar", str(JAR), "--spring.profiles.active=fams-product",
                 "--server.address=127.0.0.1", "--server.port=8080"],
                cwd=ROOT, env=environment, stdout=backend_log, stderr=subprocess.STDOUT)
            processes.append(backend)
            wait_for(lambda: ready("http://127.0.0.1:8080/api/v1/auth/me", backend), "backend")

            digest = checked(["rtk", "htpasswd", "-nbBC", "12", "alice", INITIAL_PASSWORD])
            password_hash = digest.stdout.strip().split(":", 1)[1]
            # SQL is sent via stdin; no account password or hash appears in argv/logs.
            sql = (
                "INSERT INTO fams_organization "
                "(id,code,code_normalized,name,status,version,created_at,updated_at) "
                "VALUES (1,'ROOT','ROOT','Root organization','ACTIVE',0,NOW(3),NOW(3));"
                "INSERT INTO fams_employee "
                "(id,employee_no,employee_no_normalized,name,organization_id,status,version,created_at,updated_at) "
                "VALUES (1,'E001','E001','Alice',1,'ACTIVE',0,NOW(3),NOW(3));"
                "INSERT INTO fams_system_account "
                "(id,employee_id,username,username_normalized,password_hash,status,"
                "initial_password_required,failed_attempts,auth_epoch,authorization_version,"
                "version,created_at,updated_at) VALUES "
                f"(1,1,'alice','ALICE','{password_hash}','ENABLED',TRUE,0,3,7,0,NOW(3),NOW(3));"
                "INSERT INTO fams_account_role (account_id,role_code,granted_at) "
                "VALUES (1,'SYSTEM_ADMIN',NOW(3));"
            )
            checked(["rtk", "docker", "exec", "-i", name, "mysql", "-uroot",
                     f"-p{root_password}", database], input=sql)
            web_environment = os.environ.copy()
            web_environment["PATH"] = f"/Users/lixd/.nvm/versions/node/v24.18.0/bin:{web_environment.get('PATH', '')}"
            web_log = open(Path(temporary) / "web.log", "wb")
            web = subprocess.Popen(
                ["rtk", "npm", "run", "dev", "--workspace", "@fams/web"],
                cwd=ROOT, env=web_environment, stdout=web_log, stderr=subprocess.STDOUT)
            processes.append(web)
            wait_for(lambda: ready("http://127.0.0.1:5173/login", web), "web")
            print("READY http://127.0.0.1:5173/login; account alice; test password is AuthHttpTest.INITIAL_PASSWORD", flush=True)
            print("Commands: disable (fixture account), counts (audit/session), stop.", flush=True)
            for command in sys.stdin:
                action = command.strip()
                if action == "stop":
                    break
                if action == "disable":
                    checked(["rtk", "docker", "exec", "-i", name, "mysql", "-uroot",
                             f"-p{root_password}", database], input=(
                        "UPDATE fams_system_account SET status='DISABLED', "
                        "auth_epoch=auth_epoch+1, version=version+1, updated_at=NOW(3) "
                        "WHERE id=1 AND status='ENABLED';"))
                    print("Fixture account disabled in task-owned MySQL.", flush=True)
                elif action == "counts":
                    result = checked(["rtk", "docker", "exec", "-i", name, "mysql", "-uroot",
                                      f"-p{root_password}", "--batch", "--skip-column-names", database],
                                     input=("SELECT COUNT(*) FROM fams_security_event;"
                                            "SELECT COUNT(*) FROM SPRING_SESSION;"))
                    print("security_events, jdbc_sessions: " + ", ".join(result.stdout.split()), flush=True)
            return 0
        finally:
            for label in ("backend", "web"):
                log_file = Path(temporary) / f"{label}.log"
                if log_file.is_file() and any(process.poll() is not None for process in processes):
                    log = log_file.read_text(errors="replace")
                    for secret in (root_password, user_password, INITIAL_PASSWORD, environment.get("FAMS_SECURITY_IDEMPOTENCY_HMAC_KEY", "") if "environment" in locals() else ""):
                        if secret:
                            log = log.replace(secret, "<redacted>")
                    print(f"{label} exit log:\n" + "\n".join(log.splitlines()[-15:]), file=sys.stderr)
            for process in reversed(processes):
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
            if container_started:
                removed = subprocess.run(["rtk", "docker", "rm", "-f", name],
                                         capture_output=True, check=False)
                remaining = subprocess.run(
                    ["rtk", "docker", "ps", "-a", "--filter", f"name=^{name}$",
                     "--format", "{{.Names}}"], capture_output=True, text=True, check=False)
                if removed.returncode or remaining.returncode or remaining.stdout.strip():
                    raise RuntimeError(f"Task-owned MySQL cleanup failed: {name}")


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError) as error:
        print(f"Browser runtime failed: {error}", file=sys.stderr)
        raise SystemExit(1)
