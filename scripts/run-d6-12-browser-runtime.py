#!/usr/bin/env python3
"""Start a disposable D6-12 browser stack with warehouse and admin actors."""

from __future__ import annotations

import os
import re
import secrets
import signal
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
        raise RuntimeError(f"{args[0]} {args[1]} exited {result.returncode}: " + re.sub(r"[a-f0-9]{32,}|\$2[aby]\$[^\s]+", "<redacted>", result.stderr[-1800:]))
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
    if checked(["rtk", "node", "--version"]).stdout.strip() != "v24.18.0":
        raise RuntimeError("Run with the frozen Node 24.18.0 on PATH")
    if not JAR.is_file():
        raise RuntimeError("Build the backend jar first with Java 21")
    suffix = secrets.token_hex(8)
    name = f"fams-d6-12-browser-mysql-{suffix}"
    database = f"fams-d6-12-db-{suffix}"
    root_password = secrets.token_hex(32)
    user_password = secrets.token_hex(32)
    processes: list[subprocess.Popen[bytes]] = []
    container_started = False
    with tempfile.TemporaryDirectory(prefix="fams-d6-12-browser-") as temporary:
        try:
            checked(["rtk", "docker", "run", "-d", "--name", name,
                     "-e", f"MYSQL_ROOT_PASSWORD={root_password}",
                     "-e", f"MYSQL_DATABASE={database}",
                     "-e", "MYSQL_USER=d6_12_runtime",
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
                "SPRING_DATASOURCE_USERNAME": "d6_12_runtime",
                "SPRING_DATASOURCE_PASSWORD": user_password,
                "FAMS_SECURITY_IDEMPOTENCY_HMAC_KEY": secrets.token_hex(32),
            })
            backend_log = open(Path(temporary) / "backend.log", "wb")
            backend = subprocess.Popen(
                ["rtk", "java", "-jar", str(JAR), "--spring.profiles.active=fams-product",
                 "--server.address=127.0.0.1", "--server.port=8080"],
                cwd=ROOT, env=environment, stdout=backend_log, stderr=subprocess.STDOUT,
                start_new_session=True)
            processes.append(backend)
            wait_for(lambda: ready("http://127.0.0.1:8080/api/v1/auth/me", backend), "backend")

            digest = checked(["rtk", "htpasswd", "-nbBC", "12", "warehouse", INITIAL_PASSWORD])
            password_hash = digest.stdout.strip().split(":", 1)[1]
            # SQL is sent via stdin; no account password or hash appears in argv/logs.
            sql = (
                "INSERT INTO fams_organization "
                "(id,code,code_normalized,name,status,version,created_at,updated_at) "
                "VALUES (1,'ROOT','ROOT','Root organization','ACTIVE',0,NOW(3),NOW(3));"
                "INSERT INTO fams_asset_category (id,code,code_normalized,name,extension_template_json,status,version,created_at,updated_at) "
                "VALUES (1,'DESK','DESK','Desk','[]','ACTIVE',0,NOW(3),NOW(3));"
                "INSERT INTO fams_warehouse (id,code,code_normalized,name,status,version,created_at,updated_at) "
                "VALUES (1,'WH','WH','Warehouse','ACTIVE',0,NOW(3),NOW(3));"
                "INSERT INTO fams_location (id,code,code_normalized,name,location_type,warehouse_id,status,version,created_at,updated_at) "
                "VALUES (1,'BIN','BIN','Bin','BIN',1,'ACTIVE',0,NOW(3),NOW(3));"
                "INSERT INTO fams_employee "
                "(id,employee_no,employee_no_normalized,name,organization_id,status,version,created_at,updated_at) "
                "VALUES (1,'E001','E001','Warehouse',1,'ACTIVE',0,NOW(3),NOW(3)),"
                "(2,'E002','E002','Admin',1,'ACTIVE',0,NOW(3),NOW(3)),(3,'E003','E003','Employee',1,'ACTIVE',0,NOW(3),NOW(3));"
                "INSERT INTO fams_system_account "
                "(id,employee_id,username,username_normalized,password_hash,status,"
                "initial_password_required,failed_attempts,auth_epoch,authorization_version,"
                "version,created_at,updated_at) VALUES "
                f"(1,1,'warehouse','WAREHOUSE','{password_hash}','ENABLED',FALSE,0,3,7,0,NOW(3),NOW(3)),"
                f"(2,2,'admin','ADMIN','{password_hash}','ENABLED',FALSE,0,3,7,0,NOW(3),NOW(3)),(3,3,'employee','EMPLOYEE','{password_hash}','ENABLED',FALSE,0,3,7,0,NOW(3),NOW(3));"
                "INSERT INTO fams_account_role (account_id,role_code,granted_at) "
                "VALUES (1,'WAREHOUSE_OPERATOR',NOW(3)),(2,'ASSET_ADMIN',NOW(3)),(3,'EMPLOYEE',NOW(3));"
                "INSERT INTO fams_numbering_rule (code,code_normalized,target,mode,fixed_prefix,date_part,sequence_length,status,version,created_at,updated_at) "
                "VALUES ('ASSET','ASSET','ASSET','ENTERPRISE','FA','NONE',8,'ACTIVE',0,NOW(3),NOW(3)),"
                "('INBOUND','INBOUND','INBOUND_RECEIPT','ENTERPRISE','IN','NONE',8,'ACTIVE',0,NOW(3),NOW(3));"
                "INSERT INTO fams_business_parameter_version (id,version,status,effective_at,actor_account_id,actor_username,snapshot_json,created_at) "
                "VALUES (1,1,'ACTIVE',NOW(3),2,'admin',"
                "'{\"timezone\":\"Asia/Shanghai\",\"workWeek\":[\"MONDAY\",\"TUESDAY\",\"WEDNESDAY\",\"THURSDAY\",\"FRIDAY\"],\"holidays\":[],\"adjustedWorkdays\":[],\"adminDecisionDeadlineWorkdays\":2,\"warehouseClaimDeadlineWorkdays\":1,\"warehouseFirstExecutionDeadlineWorkdays\":2,\"handoverAcceptanceDeadlineNaturalDays\":3,\"notificationEscalationThresholds\":{}}',NOW(3));"
                "UPDATE fams_business_parameter_state SET current_version=1,current_version_id=1 WHERE singleton_id=1;"
            )
            sql += "INSERT INTO fams_asset (id,asset_code,name,category_id,model,card_stage,custody_type,lifecycle_status,exception_status,storage_warehouse_id,current_location_id,created_business_date,parameter_version_id,creator_account_id,creator_username_snapshot,created_at,updated_at,created_by,updated_by) VALUES " + ",".join(f"({i},'RQ-ASSET-{i}','Desk {i}',1,'Standard','ACTIVE','STORAGE','IN_STOCK','NORMAL',1,1,'2026-10-07',1,1,'warehouse',NOW(3),NOW(3),1,1)" for i in range(10,14)) + ";"
            checked(["rtk", "docker", "exec", "-i", name, "mysql", "-uroot",
                     f"-p{root_password}", database], input=sql)
            web_log = open(Path(temporary) / "web.log", "wb")
            web = subprocess.Popen(
                ["rtk", "npm", "run", "dev", "--workspace", "@fams/web"],
                cwd=ROOT, env=os.environ.copy(), stdout=web_log, stderr=subprocess.STDOUT, start_new_session=True)
            processes.append(web)
            wait_for(lambda: ready("http://127.0.0.1:5173/login", web), "web")
            print("READY http://127.0.0.1:5173/login; accounts employee, warehouse and admin; test password is AuthHttpTest.INITIAL_PASSWORD", flush=True)
            def restart_backend() -> None:
                nonlocal backend
                os.killpg(backend.pid, signal.SIGKILL)
                backend.wait(timeout=10)
                processes.remove(backend)
                backend = subprocess.Popen(
                    ["rtk", "java", "-jar", str(JAR), "--spring.profiles.active=fams-product",
                     "--server.address=127.0.0.1", "--server.port=8080"],
                    cwd=ROOT, env=environment, stdout=backend_log, stderr=subprocess.STDOUT,
                    start_new_session=True)
                processes.append(backend)
                wait_for(lambda: ready("http://127.0.0.1:8080/api/v1/auth/me", backend), "backend restart")

            def query(sql: str) -> str:
                return checked(["rtk", "docker", "exec", "-i", name, "mysql", "-uroot",
                    f"-p{root_password}", "--batch", "--skip-column-names", database], input=sql).stdout

            print("Commands: recover (hard restart assertions), counts, stop.", flush=True)
            for command in sys.stdin:
                action = command.strip()
                if action == "stop":
                    break
                if action == "recover":
                    restart_backend()
                    print("RESTART READY", flush=True)
                if action == "counts":
                    print(query("SELECT request_no,request_status,version FROM fams_requisition ORDER BY id; SELECT request_id,requested_quantity,pending_allocation_quantity,pending_handover_quantity,completed_quantity,unfulfillable_quantity,cancelled_quantity FROM fams_requisition_demand ORDER BY id; SELECT COUNT(*) FROM fams_asset_business_lock WHERE business_type='REQUISITION' AND released_at IS NULL; SELECT action,COUNT(*) FROM fams_audit_event WHERE resource_type='REQUISITION' GROUP BY action; SELECT COUNT(*) FROM fams_requisition_followup; SELECT COUNT(*) FROM flyway_schema_history WHERE success=1; SELECT allocation_status,COUNT(*) FROM fams_requisition_allocation GROUP BY allocation_status; SELECT event_type,COUNT(*) FROM fams_requisition_handover_event GROUP BY event_type; SELECT asset_code,lifecycle_status,custody_type FROM fams_asset ORDER BY id; SELECT COUNT(*) FROM fams_requisition_receipt_grant WHERE consumed_at IS NULL;"), flush=True)
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
                if process.poll() is None:
                    os.killpg(process.pid, signal.SIGTERM)
                else:
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
