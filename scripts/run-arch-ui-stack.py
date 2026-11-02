#!/usr/bin/env python3
"""ARCH 浏览器实测的可弃置真实栈：隔离 MySQL + fams-product 后端 + vite dev。

用法：python3 scripts/run-arch-ui-stack.py [--web-dir apps/web] [--web-port 5173] [--seed <file.sql>]
启动成功后把容器名/端口/凭据写入 --info-file（默认 /tmp/fams-arch-stack.json），
然后阻塞直到收到 SIGTERM；退出时删除自己创建的容器，不留残余。

种子分两层：
  1) 内置的主数据 + 资产（与 D6-12 运行时脚本同一形状，账户口令为一次性随机值）；
  2) --seed 传入的额外 SQL（业务单据），通过同一个 mysql stdin 通道执行，失败即中断。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import secrets
import signal
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
IMAGE = "mysql:8.4.11@sha256:3466ba4a4828aa8d46fb7c3bc16b67b781c98413cf4ea0fac6feaa6e881faa26"
JAR = ROOT / "backend/target/contract-generation-probe-0.1.0-SNAPSHOT.jar"
JAVA_HOME = "/Users/lixd/Library/Java/JavaVirtualMachines/graalvm-jdk-21.0.7/Contents/Home"
NODE_BIN = "/Users/lixd/.nvm/versions/node/v24.18.0/bin"


def checked(args: list[str], **kwargs: object) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(args, text=True, capture_output=True, check=False, **kwargs)
    if result.returncode:
        detail = re.sub(r"[a-f0-9]{32,}|\$2[aby]\$[^\s]+", "<redacted>", result.stderr[-1800:])
        raise RuntimeError(f"{args[:3]} exited {result.returncode}: {detail}")
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


def wait_for(check, label: str, seconds: int = 120) -> None:
    until = time.monotonic() + seconds
    while time.monotonic() < until:
        if check():
            return
        time.sleep(1)
    raise RuntimeError(f"{label} not ready after {seconds}s")


MASTER_SEED = """
INSERT INTO fams_organization (id,code,code_normalized,name,status,version,created_at,updated_at) VALUES
 (1,'ROOT','ROOT','Root organization','ACTIVE',0,NOW(3),NOW(3)),
 (2,'SALES','SALES','Sales department','ACTIVE',0,NOW(3),NOW(3));
INSERT INTO fams_asset_category (id,code,code_normalized,name,extension_template_json,status,version,created_at,updated_at) VALUES
 (1,'DESK','DESK','Desk','[]','ACTIVE',0,NOW(3),NOW(3)),
 (2,'LAPTOP','LAPTOP','Laptop','[]','ACTIVE',0,NOW(3),NOW(3));
INSERT INTO fams_warehouse (id,code,code_normalized,name,status,version,created_at,updated_at) VALUES
 (1,'WH','WH','Warehouse','ACTIVE',0,NOW(3),NOW(3));
INSERT INTO fams_location (id,code,code_normalized,name,location_type,warehouse_id,status,version,created_at,updated_at) VALUES
 (1,'BIN','BIN','Bin','BIN',1,'ACTIVE',0,NOW(3),NOW(3)),
 (2,'ROOM','ROOM','Sales office','ROOM',1,'ACTIVE',0,NOW(3),NOW(3)),
 (3,'BIN-B','BIN-B','North bin','BIN',1,'ACTIVE',0,NOW(3),NOW(3));
INSERT INTO fams_employee (id,employee_no,employee_no_normalized,name,organization_id,status,version,created_at,updated_at) VALUES
 (1,'E001','E001','Warehouse operator',1,'ACTIVE',0,NOW(3),NOW(3)),
 (2,'E002','E002','Asset admin',1,'ACTIVE',0,NOW(3),NOW(3)),
 (3,'E003','E003','Employee',2,'ACTIVE',0,NOW(3),NOW(3)),
 (4,'E004','E004','Second employee',2,'DISABLED',0,NOW(3),NOW(3));
INSERT INTO fams_system_account (id,employee_id,username,username_normalized,password_hash,status,
 initial_password_required,failed_attempts,auth_epoch,authorization_version,version,created_at,updated_at) VALUES
 (1,1,'warehouse','WAREHOUSE','{hash}','ENABLED',FALSE,0,3,7,0,NOW(3),NOW(3)),
 (2,2,'admin','ADMIN','{hash}','ENABLED',FALSE,0,3,7,0,NOW(3),NOW(3)),
 (3,3,'employee','EMPLOYEE','{hash}','ENABLED',FALSE,0,3,7,0,NOW(3),NOW(3)),
 (4,4,'locked','LOCKED','{hash}','DISABLED',FALSE,0,3,7,0,NOW(3),NOW(3));
INSERT INTO fams_account_role (account_id,role_code,granted_at) VALUES
 (1,'WAREHOUSE_OPERATOR',NOW(3)),(1,'ASSET_ADMIN',NOW(3)),
 (2,'ASSET_ADMIN',NOW(3)),(2,'WAREHOUSE_OPERATOR',NOW(3)),
 (3,'EMPLOYEE',NOW(3)),(4,'EMPLOYEE',NOW(3));
INSERT INTO fams_numbering_rule (code,code_normalized,target,mode,fixed_prefix,date_part,sequence_length,status,version,created_at,updated_at) VALUES
 ('ASSET','ASSET','ASSET','ENTERPRISE','FA','NONE',8,'ACTIVE',0,NOW(3),NOW(3)),
 ('INBOUND','INBOUND','INBOUND_RECEIPT','ENTERPRISE','IN','NONE',8,'ACTIVE',0,NOW(3),NOW(3));
INSERT INTO fams_business_parameter_version (id,version,status,effective_at,actor_account_id,actor_username,snapshot_json,created_at) VALUES
 (1,1,'ACTIVE',NOW(3),2,'admin',
 '{{"timezone":"Asia/Shanghai","workWeek":["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY"],"holidays":[],"adjustedWorkdays":[],"adminDecisionDeadlineWorkdays":2,"warehouseClaimDeadlineWorkdays":1,"warehouseFirstExecutionDeadlineWorkdays":2,"handoverAcceptanceDeadlineNaturalDays":3,"notificationEscalationThresholds":{{}}}}',NOW(3));
UPDATE fams_business_parameter_state SET current_version=1,current_version_id=1 WHERE singleton_id=1;
"""

ASSET_SEED = """
INSERT INTO fams_asset (id,asset_code,name,category_id,model,unit,card_stage,custody_type,lifecycle_status,
 exception_status,storage_warehouse_id,current_location_id,planned_inbound_warehouse_id,
 planned_inbound_location_id,owning_organization_id,using_organization_id,responsible_employee_id,
 department_responsible_employee_id,created_business_date,parameter_version_id,creator_account_id,
 creator_username_snapshot,created_at,updated_at,created_by,updated_by) VALUES
 {rows};
"""

ASSET_COLUMNS = ("id", "code", "name", "category", "model", "unit", "stage", "custody", "lifecycle",
                 "exception", "warehouse", "location", "planned_warehouse", "planned_location",
                 "owner", "using_org", "responsible", "dept_responsible")

# 每行按 fams_asset 的 CHECK 约束成形：DRAFT/PENDING_INBOUND 不带保管关系；
# STORAGE 带仓库+位置且不带使用组织/责任人；PERSONAL 与 SHARED 不带仓库。
ASSET_SHAPES = [
    (10, "FA-DRAFT", "Draft desk", 1, "Standard", "台", "DRAFT", None, "WAITING_INBOUND", "NORMAL",
     None, None, None, None, 1, None, None, None),
    (11, "FA-DRAFT2", "Second draft desk", 1, "Wide 1600", "台", "DRAFT", None, "WAITING_INBOUND",
     "NORMAL", None, None, None, None, 1, None, None, None),
    (12, "FA-PENDIN", "Pending inbound desk", 1, "Standard", "台", "PENDING_INBOUND", None,
     "WAITING_INBOUND", "NORMAL", None, None, 1, 1, 1, None, None, None),
    (13, "FA-STOCK", "Desk in stock", 1, "Standard", "台", "ACTIVE", "STORAGE", "IN_STOCK", "NORMAL",
     1, 1, None, None, 1, None, None, None),
    (14, "FA-USED", "Laptop in use", 2, "Air 13", "台", "ACTIVE", "PERSONAL", "IN_USE", "NORMAL",
     None, 2, None, None, 1, 2, 3, None),
    (15, "FA-SHARE", "Shared plotter", 1, "PX-900", "台", "ACTIVE", "SHARED", "IN_USE", "NORMAL",
     None, 2, None, None, 1, 2, None, 3),
    (16, "FA-LOAN", "Loaned camera", 2, "X100", "台", "ACTIVE", "PERSONAL", "BORROWED", "NORMAL",
     None, 2, None, None, 1, 2, 3, None),
    (17, "FA-REPAIR", "Scanner under repair", 1, "S-200", "台", "ACTIVE", "STORAGE", "UNDER_REPAIR",
     "NORMAL", 1, 1, None, None, 1, None, None, None),
    (18, "FA-FACTRY", "Server at factory", 2, "R-1", "台", "ACTIVE", "STORAGE", "FACTORY_REPAIR",
     "SEARCHING", 1, 1, None, None, 1, None, None, None),
    (19, "FA-SCRAP", "Broken scanner", 1, "S-100", "台", "ACTIVE", "STORAGE", "SCRAPPED", "LOST",
     1, 1, None, None, 1, None, None, None),
    (20, "FA-RETURN", "Desk to return", 1, "Standard", "台", "ACTIVE", "PERSONAL", "IN_USE", "NORMAL",
     None, 2, None, None, 1, 2, 3, None),
]


def asset_rows() -> str:
    """Assets spread across card stage / custody / lifecycle so list rows are not uniform."""
    cells = []
    for shape in ASSET_SHAPES:
        assert len(shape) == len(ASSET_COLUMNS), shape
        row = []
        for value in shape:
            row.append("NULL" if value is None else f"'{value}'" if isinstance(value, str) else str(value))
        cells.append("(" + ",".join(row) + ",'2026-10-07',1,1,'admin',NOW(3),NOW(3),1,1)")
    # 24 张同形状的在库卡：让资产台账跨过 20 行分页边界，翻页探针才有可观察的页码变化。
    for bulk in range(30, 54):
        cells.append(f"({bulk},'FA-BULK-{bulk}','Bulk desk {bulk}',1,'Bulk','台','ACTIVE','STORAGE',"
                     "'IN_STOCK','NORMAL',1,1,NULL,NULL,1,NULL,NULL,NULL,"
                     "'2026-10-07',1,1,'admin',NOW(3),NOW(3),1,1)")
    return ",".join(cells)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--web-dir", default=str(ROOT / "apps/web"))
    parser.add_argument("--web-port", type=int, default=5173)
    parser.add_argument("--seed", type=Path, default=None)
    parser.add_argument("--info-file", type=Path, default=Path("/tmp/fams-arch-stack.json"))
    parser.add_argument("--sql", action="store_true", help="read SQL from stdin, print result, exit")
    parser.add_argument("--from-info", type=Path, default=None)
    args = parser.parse_args()

    if args.sql:
        info = json.loads((args.from_info or args.info_file).read_text())
        result = subprocess.run(
            ["rtk", "docker", "exec", "-i", info["container"], "mysql", "-uroot",
             f"-p{info['rootPassword']}", "--table", info["database"]],
            text=True, capture_output=True, input=sys.stdin.read(), check=False)
        print(result.stdout or result.stderr)
        return result.returncode

    if not JAR.is_file():
        raise RuntimeError("Build the backend jar first: ./mvnw -DskipTests package (Java 21)")
    web_dir = Path(args.web_dir).resolve()

    suffix = secrets.token_hex(6)
    container = f"fams-arch-ui-mysql-{suffix}"
    database = f"fams_arch_ui_{suffix}"
    root_password = secrets.token_hex(24)
    user_password = secrets.token_hex(24)
    password = secrets.token_hex(12)
    processes: list[subprocess.Popen[bytes]] = []
    logs: list[Path] = []
    directory = Path(f"/tmp/fams-arch-ui-{suffix}")
    directory.mkdir(parents=True, exist_ok=True)
    stop = threading.Event()

    def cleanup(code: int = 0) -> int:
        for process in reversed(processes):
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
        subprocess.run(["rtk", "docker", "rm", "-f", container], capture_output=True, check=False)
        for log in logs:
            if log.is_file():
                text = log.read_text(errors="replace")
                for secret in (root_password, user_password, password):
                    text = text.replace(secret, "<redacted>")
                print(f"== {log.name}\n" + "\n".join(text.splitlines()[-25:]), file=sys.stderr)
        return code

    signal.signal(signal.SIGTERM, lambda *_: stop.set())
    signal.signal(signal.SIGINT, lambda *_: stop.set())
    try:
        result = subprocess.run(
            ["rtk", "docker", "run", "-d", "--name", container,
             "-e", f"MYSQL_ROOT_PASSWORD={root_password}", "-e", f"MYSQL_DATABASE={database}",
             "-e", "MYSQL_USER=arch_ui", "-e", f"MYSQL_PASSWORD={user_password}",
             "-p", "127.0.0.1::3306", IMAGE], capture_output=True, text=True, check=False)
        if result.returncode:
            raise RuntimeError(f"docker run failed: {result.stderr[-500:]}")

        def mysql_ready() -> bool:
            return subprocess.run(["rtk", "docker", "exec", container, "mysqladmin", "ping", "-uroot",
                                   f"-p{root_password}", "--silent"], capture_output=True, check=False).returncode == 0

        wait_for(mysql_ready, "MySQL")
        port = checked(["rtk", "docker", "port", container, "3306/tcp"]).stdout.strip().rsplit(":", 1)[-1]

        def mysql_host_ready() -> bool:
            try:
                with socket.create_connection(("127.0.0.1", int(port)), timeout=2) as connection:
                    return len(connection.recv(4)) == 4
            except (OSError, ValueError):
                return False

        wait_for(mysql_host_ready, "MySQL host port")

        def seed(statement: str, label: str) -> None:
            """One statement per call so a rejected row (check constraint, unknown column) only costs that block."""
            failure = ""
            for piece in [part.strip() for part in statement.split(";") if part.strip()]:
                outcome = subprocess.run(["rtk", "docker", "exec", "-i", container, "mysql", "-uroot",
                                          f"-p{root_password}", database],
                                         input=piece + ";", text=True, capture_output=True, check=False)
                if outcome.returncode:
                    head = piece.split()[1] if len(piece.split()) > 1 else piece[:24]
                    detail = re.sub(r"[a-f0-9]{32}|\$2[aby]\$\S+", "<redacted>", outcome.stderr)
                    failure += f"   {head}: {detail[:180]}\n"
            print(f"seed {label}: {'PARTIAL' if failure else 'OK'}", flush=True)
            if failure:
                print(failure, end="", flush=True)

        environment = os.environ.copy()
        environment.update({
            "JAVA_HOME": JAVA_HOME,
            "PATH": f"{JAVA_HOME}/bin:{NODE_BIN}:{environment.get('PATH', '')}",
            "SPRING_DATASOURCE_URL": (f"jdbc:mysql://127.0.0.1:{port}/{database}"
                                      "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"),
            "SPRING_DATASOURCE_USERNAME": "arch_ui",
            "SPRING_DATASOURCE_PASSWORD": user_password,
            "FAMS_SECURITY_IDEMPOTENCY_HMAC_KEY": secrets.token_hex(32),
        })
        backend_log = directory / "backend.log"
        logs.append(backend_log)
        with backend_log.open("wb") as handle:
            backend = subprocess.Popen(
                ["rtk", "java", "-jar", str(JAR), "--spring.profiles.active=fams-product",
                 "--server.address=127.0.0.1", "--server.port=8080"],
                cwd=ROOT, env=environment, stdout=handle, stderr=subprocess.STDOUT, start_new_session=True)
        processes.append(backend)
        # Flyway runs at startup, so seeding only has a schema once the backend is ready.
        wait_for(lambda: ready("http://127.0.0.1:8080/api/v1/auth/me", backend), "backend", 180)

        digest = checked(["rtk", "htpasswd", "-nbBC", "12", "arch", password])
        bcrypt = digest.stdout.strip().split(":", 1)[1]
        seed(MASTER_SEED.format(hash=bcrypt), "master")
        seed(ASSET_SEED.format(rows=asset_rows()), "assets")
        if args.seed:
            seed(args.seed.read_text(), args.seed.name)

        web_log = directory / "web.log"
        logs.append(web_log)
        with web_log.open("wb") as handle:
            web = subprocess.Popen(
                ["rtk", "env", f"PATH={NODE_BIN}:{os.environ.get('PATH', '')}", "npx", "vite",
                 "--port", str(args.web_port), "--strictPort", "--host", "127.0.0.1"],
                cwd=web_dir, env=os.environ.copy(), stdout=handle, stderr=subprocess.STDOUT, start_new_session=True)
        processes.append(web)
        wait_for(lambda: ready(f"http://127.0.0.1:{args.web_port}/login", web), "web", 120)

        args.info_file.write_text(json.dumps({
            "container": container, "database": database, "mysqlPort": port,
            "rootPassword": root_password, "password": password, "logDir": str(directory),
            "webPort": args.web_port, "webDir": str(web_dir),
        }, indent=2))
        print(f"READY web=http://127.0.0.1:{args.web_port} backend=8080 mysql={port} "
              f"accounts=admin,warehouse,employee logs={directory}", flush=True)
        while not stop.wait(2):
            for label, process in (("backend", backend), ("web", web)):
                if process.poll() is not None:
                    raise RuntimeError(f"{label} exited during the sweep")
        return 0
    finally:
        cleanup()


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError) as error:
        print(f"ARCH UI stack failed: {error}", file=sys.stderr)
        raise SystemExit(1)
