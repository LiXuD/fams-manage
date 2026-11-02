#!/usr/bin/env python3
"""给 ARCH 浏览器实测的真实栈造业务单据：只走产品自己的 HTTP API，不手写库内快照。

用法：python3 scripts/seed-arch-ui-docs.py [--info /tmp/fams-arch-stack.json] [--out /tmp/fams-arch-ids.json]

每个流程独立执行；失败只记录状态码与服务端 code，不中断其余流程，
结尾打印「已建行 / 未建行」清单，供实测报告如实说明覆盖范围。
"""

from __future__ import annotations

import argparse
import json
import subprocess
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

BASE = "http://127.0.0.1:8080/api/v1"


class Session:
    """手工带会话 Cookie：后端把 FAMS_SESSION 标了 Secure，Python 的 CookieJar 在 http 下会丢弃它。

    Chrome 把 127.0.0.1 视为可信来源，所以浏览器端登录不受影响；这里只是复刻它做的的事。
    """

    def __init__(self, username: str, password: str) -> None:
        self.username = username
        self.csrf: str | None = None
        self.cookie: str | None = None
        status, body = self.call("POST", "/auth/login",
                                 {"username": username, "password": password})
        if status != 200:
            raise RuntimeError(f"login {username} failed: {status} {body}")
        self.csrf = json.loads(body)["csrfToken"]

    def call(self, method: str, path: str, payload: object = None,
             version: int | None = None) -> tuple[int, str]:
        headers = {"Content-Type": "application/json"}
        if self.csrf:
            headers["X-CSRF-Token"] = self.csrf
        if self.cookie:
            headers["Cookie"] = self.cookie
        if method != "GET":
            headers["Idempotency-Key"] = str(uuid.uuid4())
        if version is not None:
            headers["If-Match-Version"] = str(version)
        request = urllib.request.Request(
            BASE + path, data=None if payload is None else json.dumps(payload).encode(),
            method=method, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                issued = response.headers.get("Set-Cookie")
                if issued:
                    self.cookie = issued.split(";")[0]
                return response.status, response.read().decode()
        except urllib.error.HTTPError as error:
            issued = error.headers.get("Set-Cookie")
            if issued:
                self.cookie = issued.split(";")[0]
            return error.code, error.read().decode()


def now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--info", type=Path, default=Path("/tmp/fams-arch-stack.json"))
    parser.add_argument("--out", type=Path, default=Path("/tmp/fams-arch-ids.json"))
    args = parser.parse_args()
    password = json.loads(args.info.read_text())["password"]

    admin = Session("admin", password)
    warehouse = Session("warehouse", password)
    employee = Session("employee", password)
    created: dict[str, str] = {}
    failures: list[str] = []

    def step(label: str, session: Session, method: str, path: str,
             payload: object = None, version: int | None = None,
             record: str | None = None) -> dict | None:
        status, body = session.call(method, path, payload, version)
        try:
            data = json.loads(body) if body.strip().startswith(("{", "[")) else None
        except json.JSONDecodeError:
            data = None
        if status >= 300:
            code = (data or {}).get("code") if isinstance(data, dict) else None
            message = (data or {}).get("message") if isinstance(data, dict) else None
            failures.append(f"{label} {method} {path} -> {status} {code or ''} {str(message or body)[:120]}")
            return None
        print(f"ok   {label} {method} {path} -> {status}", flush=True)
        if record and isinstance(data, dict):
            created[record] = str(data.get("id", ""))
        return data if isinstance(data, dict) else None

    def version_of(data: dict | None) -> int:
        return int((data or {}).get("version", 0))

    # 入库单：草稿 → 提交 → 管理员批准 → 库管领取 → 逐项验收（覆盖入库列表/详情四种状态）
    receipt_a = step("inbound draft A", warehouse, "POST", "/inbound-receipts", {
        "businessDate": "2026-11-02",
        "items": [{"asset": {"id": "10", "version": 0},
                   "targetWarehouse": {"id": "1", "version": 0},
                   "targetLocation": {"id": "1", "version": 0}}]},
        record="inboundA")
    if receipt_a:
        receipt_id = str(receipt_a["id"])
        submitted = step("inbound submit A", warehouse, "POST",
                         f"/inbound-receipts/{receipt_id}/submit", None,
                         version_of(receipt_a))
        decided = step("inbound approve A", admin, "POST", f"/inbound-receipts/{receipt_id}/decision",
                       {"decision": "APPROVED", "reason": "Delivery verified"},
                       version_of(submitted))
        claimed = step("inbound claim A", warehouse, "POST", f"/inbound-receipts/{receipt_id}/claim",
                       None, version_of(decided))
        item_id = ((submitted or receipt_a).get("items") or [{}])[0].get("id")
        if claimed and item_id:
            current = step("inbound reload A", warehouse, "GET", f"/inbound-receipts/{receipt_id}")
            line = next((row for row in (current or {}).get("items", [])
                         if str(row.get("id")) == str(item_id)), {})
            step("inbound accept A", warehouse, "POST",
                 f"/inbound-receipts/{receipt_id}/items/{item_id}/accept",
                 {"assetVersion": ((line.get("asset") or {}).get("version", 0)),
                  "lockVersion": (line.get("businessLock") or {}).get("lockVersion", 0),
                  "observedAssetCode": (line.get("assetSnapshot") or {}).get("assetCode", "FA-DRAFT")},
                 version_of(claimed))
        created["inboundA"] = receipt_id

    receipt_b = step("inbound draft B (waiting decision)", warehouse, "POST", "/inbound-receipts", {
        "businessDate": "2026-11-02",
        "items": [{"asset": {"id": "11", "version": 0},
                   "targetWarehouse": {"id": "1", "version": 0},
                   "targetLocation": {"id": "2", "version": 0}}]}, record="inboundB")
    if receipt_b:
        step("inbound submit B", warehouse, "POST",
             f"/inbound-receipts/{receipt_b['id']}/submit", None, version_of(receipt_b))
        created["inboundB"] = str(receipt_b["id"])

    # 维修：本人责任资产报修（PENDING_ADMIN_DECISION）
    repair = step("repair draft", employee, "POST", "/repairs", {
        "assetId": "14", "assetVersion": 0, "fault": "Screen flickers under load",
        "impact": "HIGH", "occurredAt": (datetime.now(timezone.utc) - timedelta(hours=2))
        .replace(microsecond=0).isoformat().replace("+00:00", "Z")}, record="repair")
    if repair:
        step("repair second (shared asset)", admin, "POST", "/repairs", {
            "assetId": "15", "assetVersion": 0, "fault": "Feed roller jammed",
            "impact": "MEDIUM", "occurredAt": now()}, record="repair2")

    # 领用与借用：草稿 + 已批准（覆盖两种状态与两种 requestType）
    demand = {
        "category": {"id": "1", "version": 0},
        "specification": "Standard desk, 1400mm", "purpose": "New joiner workstation",
        "quantity": 1, "expectedDate": "2026-11-10", "custodyType": "PERSONAL",
        "organization": {"id": "2", "version": 0}, "employee": {"id": "3", "version": 0},
        "location": {"id": "2", "version": 0},
    }
    requisition = step("requisition draft", employee, "POST", "/requisitions", {
        "requestType": "REQUISITION", "expectedReturnDate": None, "demands": [demand]},
        record="requisition")
    if requisition:
        submitted = step("requisition submit", employee, "POST",
                         f"/requisitions/{requisition['id']}/submit", None, version_of(requisition))
        step("requisition decision", admin, "POST", f"/requisitions/{requisition['id']}/decision",
             {"result": "APPROVED", "comment": "Approved for the new joiner"},
             version_of(submitted) or 1)
    step("borrow draft", employee, "POST", "/requisitions", {
        "requestType": "BORROW", "expectedReturnDate": "2026-12-01",
        "demands": [dict(demand, quantity=2, purpose="Two-week field trial")]},
        record="borrow")

    # 盘点任务草稿
    step("inventory task draft", admin, "POST", "/inventory-tasks", {
        "name": "Q4 full stocktake", "range": {"owningOrganizationId": 1},
        "startsAt": now(), "dueAt": (datetime.now(timezone.utc) + timedelta(days=7))
        .replace(microsecond=0).isoformat().replace("+00:00", "Z")}, record="inventoryTask")

    # 调拨草稿：在库资产只能转向 STORAGE，且目标位置须是同一仓库的 BIN（服务端 validateTarget 规则）
    step("transfer draft", admin, "POST", "/transfers", {
        "reason": "Relocate stock desk to the north bin",
        "items": [{"assetId": "13", "assetVersion": 0,
                   "target": {"owningOrganizationId": "1", "custodyType": "STORAGE",
                              "usingOrganizationId": None, "employeeId": None, "warehouseId": "1",
                              "locationId": "3",
                              "referenceVersions": {"location": 0, "owningOrganization": 0,
                                                    "warehouse": 0}}}]},
        record="transfer")

    # 退库提请：借用中的实物由责任人提请归还
    step("asset return propose", employee, "POST", "/asset-returns", {
        "assetId": "20", "assetVersion": 0, "reason": "Desk handed back to the store"},
        record="assetReturn")

    args.out.write_text(json.dumps(created, indent=2, ensure_ascii=False))
    print(f"\ncreated={json.dumps(created, ensure_ascii=False)}", flush=True)
    if failures:
        print(f"failures={len(failures)}")
        for line in failures:
            print("  " + line)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
