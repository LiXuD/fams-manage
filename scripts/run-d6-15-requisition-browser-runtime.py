#!/usr/bin/env python3
"""Owned requisition reassignment stack for real UI, persistence and restart verification."""

import importlib.util
import re
from pathlib import Path


def main() -> int:
    path = Path(__file__).with_name("run-d6-12-browser-runtime.py")
    spec = importlib.util.spec_from_file_location("d6_12_runtime", path)
    runtime = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(runtime)
    checked = runtime.checked
    owned_name = ""
    owned_volume = ""

    def transfer_checked(args: list[str], **kwargs: object):
        nonlocal owned_name, owned_volume
        if args[:3] == ["rtk", "docker", "run"]:
            # Remove this disposable container's anonymous database volume on exit.
            args = args[:3] + ["--rm"] + args[3:]
        sql = kwargs.get("input")
        if isinstance(sql, str) and sql.startswith("INSERT INTO fams_organization"):
            sql += (
                "INSERT INTO fams_location (id,code,code_normalized,name,location_type,status,version,created_at,updated_at) VALUES (2,'ROOM','ROOM','Office','ROOM','ACTIVE',0,NOW(3),NOW(3));"
                "INSERT INTO fams_employee (id,employee_no,employee_no_normalized,name,organization_id,status,created_at,updated_at) VALUES (4,'E004','E004','Next warehouse',1,'ACTIVE',NOW(3),NOW(3));"
                "INSERT INTO fams_system_account (id,employee_id,username,username_normalized,password_hash,status,initial_password_required,failed_attempts,auth_epoch,authorization_version,version,created_at,updated_at) SELECT 4,4,'target','TARGET',password_hash,'ENABLED',FALSE,0,3,7,0,NOW(3),NOW(3) FROM fams_system_account WHERE id=1;"
                "INSERT INTO fams_account_role (account_id,role_code,granted_at) VALUES (4,'WAREHOUSE_OPERATOR',NOW(3));"
            )
            kwargs["input"] = sql
        elif isinstance(sql, str) and sql.startswith("SELECT request_no"):
            kwargs["input"] = (
                "SELECT id,request_status,version FROM fams_requisition ORDER BY id;"
                "SELECT request_id,processor_account_id,ownership_version,archived_at FROM fams_requisition_ownership ORDER BY id;"
                "SELECT request_id,requested_quantity,pending_allocation_quantity,pending_handover_quantity,completed_quantity FROM fams_requisition_demand ORDER BY id;"
                "SELECT request_id,asset_id,allocation_status,version FROM fams_requisition_allocation ORDER BY id;"
                "SELECT action,COUNT(*) FROM fams_audit_event WHERE resource_type='REQUISITION' GROUP BY action;"
                "SELECT asset_code,lifecycle_status,custody_type,current_location_id FROM fams_asset ORDER BY id;"
                "SELECT business_type,business_id,lock_version FROM fams_asset_business_lock WHERE released_at IS NULL;"
            )
        result = checked(args, **kwargs)
        if args[:3] == ["rtk", "docker", "run"]:
            owned_name = args[args.index("--name") + 1]
            owned_volume = checked(["rtk", "docker", "inspect", owned_name, "--format",
                '{{range .Mounts}}{{if eq .Destination "/var/lib/mysql"}}{{.Name}}{{end}}{{end}}']).stdout.strip()
            if not re.fullmatch(r"[a-f0-9]{64}", owned_volume):
                raise RuntimeError("Owned MySQL anonymous volume identity is not provable")
        return result

    runtime.checked = transfer_checked
    try:
        return runtime.main()
    finally:
        if owned_volume:
            remaining = checked(["rtk", "docker", "ps", "-a", "--filter", f"name=^{owned_name}$",
                "--format", "{{.Names}}"])
            if remaining.stdout.strip():
                raise RuntimeError("Owned MySQL container remains; volume cleanup stopped")
            args = ["rtk", "docker", "volume", "ls", "--filter", f"name=^{owned_volume}$", "--format", "{{.Name}}"]
            if checked(args).stdout.strip():
                checked(["rtk", "docker", "volume", "rm", owned_volume])
            if checked(args).stdout.strip():
                raise RuntimeError("Owned MySQL anonymous volume remains after cleanup")
            print("Owned MySQL container and anonymous volume cleanup verified.", flush=True)


if __name__ == "__main__":
    raise SystemExit(main())
