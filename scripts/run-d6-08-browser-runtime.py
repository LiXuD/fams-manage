#!/usr/bin/env python3
"""Run disposable D6-08 browser fixtures; type stop to clean up owned services."""

from __future__ import annotations

import os
from pathlib import Path
import secrets
import signal
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

from openpyxl import Workbook


ROOT = Path(__file__).resolve().parents[1]
IMAGE = "mysql:8.4.11@sha256:3466ba4a4828aa8d46fb7c3bc16b67b781c98413cf4ea0fac6feaa6e881faa26"
JAR = ROOT / "backend/target/contract-generation-probe-0.1.0-SNAPSHOT.jar"
PASSWORD = "correct-horse-battery-staple"


def checked(*args: str, input_text: str | None = None) -> str:
    result = subprocess.run(["rtk", *args], cwd=ROOT, input=input_text, text=True,
                            capture_output=True, check=False, timeout=120)
    if result.returncode:
        raise RuntimeError(f"{args[0]} {args[1] if len(args) > 1 else ''} failed: "
                           + "\n".join(result.stderr.splitlines()[-5:]))
    return result.stdout.strip()


def wait_for(url: str, process: subprocess.Popen[bytes]) -> None:
    for _ in range(90):
        if process.poll() is not None:
            raise RuntimeError(f"server terminated before {url} became ready")
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                if response.status == 200:
                    return
        except urllib.error.HTTPError as error:
            if error.code in (401, 403):
                return
        except (OSError, TimeoutError):
            pass
        time.sleep(1)
    raise RuntimeError(f"server not ready: {url}")


def fixture(path: Path) -> None:
    book = Workbook()
    sheet = book.active
    sheet.title = "资产"
    sheet.append(["资产编码", "名称", "分类编码", "所属组织编码", "型号", "单位", "序列号",
                  "资产状态", "仓库编码", "位置编码", "使用组织编码", "使用人编号"])
    for name in ("Desk A", "Duplicate code"):
        sheet.append(["BROWSER-A-1", name, "CAT-1", "ROOT", "M1", "piece", "", "在库",
                      "WH-1", "LOC-1", "", ""])
    book.save(path)


def main() -> None:
    if not JAR.is_file():
        raise RuntimeError("Build the backend jar with Java 21 before starting the browser fixture")
    suffix = secrets.token_hex(6)
    container = f"fams-d6-08-browser-{suffix}"
    database = f"fams_d608_{suffix}"
    root_password = secrets.token_hex(24)
    db_password = secrets.token_hex(24)
    services: list[subprocess.Popen[bytes]] = []
    started = False
    with tempfile.TemporaryDirectory(prefix="fams-d6-08-browser-") as temporary:
        directory = Path(temporary)
        backend_log = directory / "backend.log"
        web_log = directory / "web.log"
        try:
            checked("docker", "run", "-d", "--name", container,
                    "-e", "MYSQL_ROOT_PASSWORD=" + root_password,
                    "-e", "MYSQL_DATABASE=" + database,
                    "-e", "MYSQL_USER=fams_d608",
                    "-e", "MYSQL_PASSWORD=" + db_password,
                    "-p", "127.0.0.1::3306", IMAGE)
            started = True
            for _ in range(60):
                result = subprocess.run(["rtk", "docker", "exec", container, "mysqladmin", "ping",
                                         "-uroot", "-p" + root_password, "--silent"], cwd=ROOT,
                                        capture_output=True, check=False)
                if result.returncode == 0:
                    break
                time.sleep(1)
            else:
                raise RuntimeError("MySQL not ready")
            port = checked("docker", "port", container, "3306/tcp").rsplit(":", 1)[-1]
            for _ in range(60):
                try:
                    with socket.create_connection(("127.0.0.1", int(port)), timeout=2) as connection:
                        if len(connection.recv(4)) == 4:
                            break
                    time.sleep(1)
                except OSError:
                    time.sleep(1)
            else:
                raise RuntimeError("MySQL host port not ready")
            java = checked("/usr/libexec/java_home", "-v", "21")
            environment = os.environ.copy()
            environment.update({
                "JAVA_HOME": java,
                "PATH": java + "/bin:" + environment.get("PATH", ""),
                "SPRING_DATASOURCE_URL": (
                    f"jdbc:mysql://127.0.0.1:{port}/{database}"
                    "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"),
                "SPRING_DATASOURCE_USERNAME": "fams_d608",
                "SPRING_DATASOURCE_PASSWORD": db_password,
                "FAMS_SECURITY_IDEMPOTENCY_HMAC_KEY": secrets.token_hex(32),
            })
            with backend_log.open("wb") as output:
                backend = subprocess.Popen(["rtk", "java", "-jar", str(JAR),
                                            "--spring.profiles.active=fams-product",
                                            "--server.address=127.0.0.1", "--server.port=8080"],
                                           cwd=ROOT, env=environment, stdout=output,
                                           stderr=subprocess.STDOUT, start_new_session=True)
            services.append(backend)
            wait_for("http://127.0.0.1:8080/api/v1/auth/me", backend)
            digest = checked("htpasswd", "-nbBC", "12", "fixture", PASSWORD).split(":", 1)[1]
            sql = (
                "INSERT INTO fams_organization (id,code,code_normalized,name,status,created_at,updated_at) "
                "VALUES (1,'ROOT','ROOT','Root','ACTIVE',NOW(),NOW());"
                "INSERT INTO fams_asset_category (id,code,code_normalized,name,status,created_at,updated_at) "
                "VALUES (1,'CAT-1','CAT-1','Office','ACTIVE',NOW(),NOW());"
                "INSERT INTO fams_warehouse (id,code,code_normalized,name,status,created_at,updated_at) "
                "VALUES (1,'WH-1','WH-1','Main','ACTIVE',NOW(),NOW());"
                "INSERT INTO fams_location (id,code,code_normalized,name,location_type,warehouse_id,status,"
                "created_at,updated_at) VALUES (1,'LOC-1','LOC-1','Shelf','BIN',1,'ACTIVE',NOW(),NOW());"
                "INSERT INTO fams_employee (id,employee_no,employee_no_normalized,name,organization_id,status,"
                "created_at,updated_at) VALUES (1,'E1','E1','Warehouse',1,'ACTIVE',NOW(),NOW()),"
                "(2,'E2','E2','Asset admin',1,'ACTIVE',NOW(),NOW()),"
                "(3,'E3','E3','System admin',1,'ACTIVE',NOW(),NOW());"
                "INSERT INTO fams_system_account (id,employee_id,username,username_normalized,password_hash,"
                "status,initial_password_required,created_at,updated_at) VALUES "
                f"(1,1,'warehouse','WAREHOUSE','{digest}','ENABLED',FALSE,NOW(),NOW()),"
                f"(2,2,'assetadmin','ASSETADMIN','{digest}','ENABLED',FALSE,NOW(),NOW()),"
                f"(3,3,'systemadmin','SYSTEMADMIN','{digest}','ENABLED',FALSE,NOW(),NOW());"
                "INSERT INTO fams_account_role (account_id,role_code,granted_at) VALUES "
                "(1,'WAREHOUSE_OPERATOR',NOW()),(2,'ASSET_ADMIN',NOW()),(3,'SYSTEM_ADMIN',NOW());"
                "INSERT INTO fams_business_parameter_version (id,version,status,effective_at,actor_account_id,"
                "actor_username,snapshot_json,created_at) VALUES (1,1,'ACTIVE',NOW(),3,'systemadmin',"
                "'{\"timezone\":\"Asia/Shanghai\",\"workWeek\":[\"MONDAY\",\"TUESDAY\",\"WEDNESDAY\","
                "\"THURSDAY\",\"FRIDAY\"],\"holidays\":[],\"adjustedWorkdays\":[],"
                "\"adminDecisionDeadlineWorkdays\":2,\"warehouseClaimDeadlineWorkdays\":1,"
                "\"warehouseFirstExecutionDeadlineWorkdays\":2,"
                "\"handoverAcceptanceDeadlineNaturalDays\":3,\"notificationEscalationThresholds\":{}}',NOW());"
                "UPDATE fams_business_parameter_state SET current_version=1,current_version_id=1 "
                "WHERE singleton_id=1;"
            )
            checked("docker", "exec", "-i", container, "mysql", "-uroot", "-p" + root_password,
                    database, input_text=sql)
            with web_log.open("wb") as output:
                web = subprocess.Popen(["rtk", "npm", "run", "dev", "--workspace", "@fams/web"],
                                       cwd=ROOT, env=os.environ.copy(), stdout=output,
                                       stderr=subprocess.STDOUT, start_new_session=True)
            services.append(web)
            wait_for("http://127.0.0.1:5173/login", web)
            file_path = directory / "assets.xlsx"
            fixture(file_path)
            print("READY http://127.0.0.1:5173/login", flush=True)
            print(f"FILE {file_path}", flush=True)
            print("ACTORS warehouse assetadmin systemadmin; PASSWORD is browser fixture password", flush=True)
            for command in sys.stdin:
                if command.strip() == "stop":
                    break
                if command.strip() == "counts":
                    result = checked("docker", "exec", "-i", container, "mysql", "-uroot",
                                     "-p" + root_password, "--batch", "--skip-column-names", database,
                                     input_text="SELECT batch_status,COUNT(*) FROM fams_asset_import_batch "
                                                "GROUP BY batch_status; SELECT COUNT(*) FROM fams_asset_import_publication;")
                    print("COUNTS " + result.replace("\n", " | "), flush=True)
        finally:
            for process in reversed(services):
                if process.poll() is None:
                    os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=5)
            if started:
                subprocess.run(["rtk", "docker", "rm", "-f", container], cwd=ROOT,
                               capture_output=True, check=False)
            for path in (backend_log, web_log):
                if path.is_file() and any(process.returncode not in (None, 0, -15) for process in services):
                    log = path.read_text(errors="replace")
                    for secret in (root_password, db_password, digest if "digest" in locals() else ""):
                        if secret:
                            log = log.replace(secret, "<redacted>")
                    print(path.name + " tail:\n" + "\n".join(log.splitlines()[-10:]), file=sys.stderr)


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError) as error:
        print(f"D6-08 browser fixture failed: {error}", file=sys.stderr)
        raise SystemExit(1)
