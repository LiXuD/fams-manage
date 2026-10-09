#!/usr/bin/env python3
"""Reuse the owned three-role stack for borrowing/return browser verification.

The inherited four in-stock assets support normal requests. One explicitly
synthetic overdue initial loan supports extension/history/return checks; this
seed does not prove the import publication path (the HTTP runtime suite does).
"""

import importlib.util
from pathlib import Path


def main() -> int:
    path = Path(__file__).with_name("run-d6-12-browser-runtime.py")
    spec = importlib.util.spec_from_file_location("d6_12_runtime", path)
    runtime = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(runtime)
    checked = runtime.checked

    def loan_checked(args: list[str], **kwargs: object):
        sql = kwargs.get("input")
        if isinstance(sql, str) and sql.startswith("INSERT INTO fams_organization"):
            sql += (
                "INSERT INTO fams_asset (id,asset_code,name,category_id,model,card_stage,custody_type,"
                "lifecycle_status,exception_status,using_organization_id,responsible_employee_id,"
                "current_location_id,created_business_date,parameter_version_id,creator_account_id,"
                "creator_username_snapshot,created_at,updated_at,created_by,updated_by) VALUES "
                "(20,'LOAN-INITIAL-20','Initial borrowed desk',1,'Standard','ACTIVE','PERSONAL',"
                "'BORROWED','NORMAL',1,3,1,'2020-01-01',1,1,'warehouse',NOW(3),NOW(3),1,1);"
                "INSERT INTO fams_asset_import_batch (id,actor_account_id,upload_key,source_sha256,"
                "source_bytes,source_xlsx,batch_status,created_at,updated_at) VALUES "
                "('aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',1,'d6-13-browser-fixture',REPEAT('0',64),"
                "1,X'00','PUBLISHED',NOW(3),NOW(3));"
                "INSERT INTO fams_loan (asset_id,borrower_employee_id,using_organization_id,source_type,"
                "initial_batch_id,initial_sheet_row,borrowed_date,original_return_date,expected_return_date,"
                "parameter_snapshot_json,created_at,updated_at) VALUES "
                "(20,3,1,'INITIAL_IMPORT','aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',2,'2020-01-01',"
                "'2020-02-01','2020-02-01','{\"timezone\":\"Asia/Shanghai\"}',NOW(3),NOW(3));"
            )
            kwargs["input"] = sql
        elif isinstance(sql, str) and sql.startswith("SELECT request_no"):
            kwargs["input"] = sql + (
                "SELECT id,asset_id,source_type,original_return_date,expected_return_date,relation_status,version FROM fams_loan ORDER BY id;"
                "SELECT id,extension_status,original_due_date,requested_due_date,overdue_from,overdue_through FROM fams_loan_extension ORDER BY id;"
                "SELECT id,asset_id,return_status,inspection_result,version FROM fams_asset_return ORDER BY id;"
                "SELECT action,COUNT(*) FROM fams_audit_event WHERE resource_type IN ('LOAN','LOAN_EXTENSION','ASSET_RETURN') GROUP BY action;"
                "SELECT asset_code,lifecycle_status,custody_type,using_organization_id,responsible_employee_id,storage_warehouse_id FROM fams_asset ORDER BY id;"
                "SELECT business_type,business_id FROM fams_asset_business_lock WHERE released_at IS NULL;"
            )
        return checked(args, **kwargs)

    runtime.checked = loan_checked
    return runtime.main()


if __name__ == "__main__":
    raise SystemExit(main())
