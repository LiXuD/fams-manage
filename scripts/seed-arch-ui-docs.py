#!/usr/bin/env python3
"""给 ARCH 浏览器实测的真实栈造业务单据：只走产品自己的 HTTP API，不手写库内快照。

用法：python3 scripts/seed-arch-ui-docs.py [--info /tmp/fams-arch-stack.json] [--out /tmp/fams-arch-ids.json]

每个流程独立执行；失败只记录状态码与服务端 code，不中断其余流程，
结尾打印「已建行 / 未建行」清单，供实测报告如实说明覆盖范围。
"""

from __future__ import annotations

import argparse
import base64
import json
import subprocess
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

BASE = "http://127.0.0.1:8080/api/v1"
# 1x1 真实 PNG：报失批准要求至少一张扫描 CLEAN 的原件，用它能走通产品自己的上传通道。
PNG_1PX = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")


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


    def upload(self, path: str, payload: bytes, filename: str, content_type: str,
               version: int | None = None) -> tuple[int, str]:
        """走产品自己的 multipart 上传通道（报失情况图片）；JSON 版 call 发不了表单。"""
        boundary = uuid.uuid4().hex
        body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\";"
                f" filename=\"{filename}\"\r\nContent-Type: {content_type}\r\n\r\n").encode() \
            + payload + f"\r\n--{boundary}--\r\n".encode()
        headers = {"Content-Type": f"multipart/form-data; boundary={boundary}",
                   "X-CSRF-Token": self.csrf or "", "Idempotency-Key": str(uuid.uuid4())}
        if self.cookie:
            headers["Cookie"] = self.cookie
        if version is not None:
            headers["If-Match-Version"] = str(version)
        request = urllib.request.Request(BASE + path, data=body, method="POST", headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return response.status, response.read().decode()
        except urllib.error.HTTPError as error:
            return error.code, error.read().decode()


def now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--info", type=Path, default=Path("/tmp/fams-arch-stack.json"))
    parser.add_argument("--out", type=Path, default=Path("/tmp/fams-arch-ids.json"))
    parser.add_argument("--only-loss", action="store_true",
                        help="只跑报失线：其它 step 直接返回 None，沿用上一轮已造好的单据")
    parser.add_argument("--only-scrap", action="store_true",
                        help="只跑报废线：其它 step 直接返回 None，沿用上一轮已造好的单据")
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
        if (args.only_loss and "loss" not in label) or (args.only_scrap and "scrap" not in label):
            return None
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

    # 报失与找回：三张单据分别停在待批准（带原件）、库管核实中（查找表单）与
    # 已确认遗失 + 在办找回申请（找回面板与跟进面板同屏）。
    def iso(hours: int) -> str:
        return (datetime.now(timezone.utc) + timedelta(hours=hours)).replace(microsecond=0)\
            .isoformat().replace("+00:00", "Z")

    def reload(report_id: str) -> dict:
        status, body = employee.call("GET", f"/loss-reports/{report_id}")
        return json.loads(body) if status == 200 else {}

    def move(label: str, session: Session, path: str, payload: object, current: dict,
             version: int | None = None, target: str | None = None) -> dict:
        """乐观版本默认取报失单自身；找回申请单的动作要带它自己的版本。"""
        done = step(label, session, "POST", path, payload,
                    int(current.get("version", 0) if version is None else version))
        return reload(str(target or current.get("id", ""))) if done else current

    def recovery_facts(current: dict) -> dict:
        asset = current.get("recoveryAsset") or {}
        return {"foundAt": iso(-1), "observedAssetCode": (current.get("source") or {}).get("assetCode", ""),
                "observedSerialNumber": asset.get("serialNumber"), "locationId": asset.get("locationId"),
                "locationVersion": asset.get("locationVersion"), "physicalIdentityVerified": True,
                "condition": "INTACT", "stillOriginalCustodian": True,
                "evidence": "当面核对原机编码与序列号，实物仍在原登记位置由原保管人保管"}

    def report(index: int, tag: str, pool: list[dict] | None = None) -> dict:
        rows = eligible_items if pool is None else pool
        if index >= len(rows):
            failures.append(f"loss {tag}: eligible assets exhausted ({len(rows)} rows)")
            return {}
        asset = rows[index]
        head = step(f"loss submit {tag}", employee, "POST", "/loss-reports", {
            "assetId": asset["id"], "assetVersion": asset["version"], "lastSeenAt": iso(-3),
            "lastLocation": "原保管位置：三楼东侧办公室",
            "circumstances": "下班后原实物不在原保管位置，交接、维修与盘点线索均未指向实物"})
        if not head:
            return {}
        status, body = employee.upload(f"/loss-reports/{head['id']}/photos", PNG_1PX, "site-photo.png",
                                       "image/png", int(head.get("version", 0)))
        if status >= 300:
            failures.append(f"loss photo {tag} -> {status} {body[:140]}")
        return reload(str(head["id"]))

    step_cache: dict[str, dict] = {}
    eligible_items = ((step("loss eligible", employee, "GET",
                            "/loss-reports/eligible?page=1&pageSize=20") or {}).get("items")) or []

    pending = report(0, "pending")
    if pending:
        created["lossPending"] = str(pending["id"])

    verifying = report(1, "verifying")
    if verifying:
        approved = move("loss approve", admin, f"/loss-reports/{verifying['id']}/decision",
                        {"result": "APPROVE", "reason": "批准进入实物查找"}, verifying)
        claimed = move("loss claim", warehouse, f"/loss-reports/{approved.get('id', '')}/claim", None, approved)
        if claimed.get("id"):
            created["lossSearching"] = str(claimed["id"])

    tracking = report(2, "tracking")
    if tracking:
        approved = move("loss approve tracked", admin, f"/loss-reports/{tracking['id']}/decision",
                        {"result": "APPROVE", "reason": "批准进入实物查找"}, tracking)
        claimed = move("loss claim tracked", warehouse, f"/loss-reports/{approved.get('id', '')}/claim",
                       None, approved)
        searched = move("loss search", warehouse, f"/loss-reports/{claimed.get('id', '')}/search", {
            "assetVersion": claimed.get("assetVersion"), "ownershipVersion": claimed.get("ownershipVersion"),
            "lockVersion": claimed.get("lockVersion"),
            "custodianVerification": "已联系最后保管人核对完整交接过程",
            "searchedScope": "已查找原办公室抽屉、维修工位与盘点留痕",
            "searchedAt": iso(-2), "nextReviewDate": (datetime.now(timezone.utc) + timedelta(days=14))
            .date().isoformat()}, claimed)
        confirmed = move("loss confirm lost", warehouse, f"/loss-reports/{searched.get('id', '')}/confirm-lost", {
            "assetVersion": searched.get("assetVersion"), "ownershipVersion": searched.get("ownershipVersion"),
            "lockVersion": searched.get("lockVersion"),
            "custodianVerification": "已联系最后保管人核对完整交接过程",
            "searchedScope": "已查找原办公室抽屉、维修工位与盘点留痕",
            "searchedAt": iso(-2), "relatedClues": "交接单、维修工单与盘点记录均无实物线索",
            "inabilityReason": "全部登记位置与责任人线索查尽，仍未见原实物",
            "responsibilityEvidence": "保管人与两名同事共同确认原位置无实物",
            "confirmedAt": iso(-1)}, searched)
        requested = move("loss recovery request", employee,
                         f"/loss-reports/{confirmed.get('id', '')}/recovery-requests",
                         {"assetVersion": confirmed.get("assetVersion"), "facts": recovery_facts(confirmed)},
                         confirmed)
        attempts = requested.get("recoveries") or []
        if attempts:
            decided = move("loss recovery approve", admin,
                           f"/loss-reports/recoveries/{attempts[0]['id']}/decision",
                           {"result": "APPROVE", "reason": "批准新找回申请，待库管核实执行"},
                           requested, version=int(attempts[0].get("version", 0)))
            attempts = decided.get("recoveries") or attempts
            move("loss recovery claim", warehouse, f"/loss-reports/recoveries/{attempts[0]['id']}/claim",
                 None, decided, version=int(attempts[0].get("version", 0)))
        if requested.get("id"):
            created["loss"] = str(requested["id"])

    # 报废与处置：四张单据分别停在待管理员确认、已认领待实物执行（执行目录与核实段同屏）、
    # 已执行（带处置进度行）与被驳回。实物执行的位置与保管人版本一律取自产品自己的
    # execution-catalog，不手写库内快照。
    def scrap_reload(request_id: str) -> dict:
        status, body = admin.call("GET", f"/scrap-requests/{request_id}")
        return json.loads(body) if status == 200 else {}

    def scrap_submit(index: int, tag: str) -> dict:
        if index >= len(scrap_eligible):
            failures.append(f"scrap {tag}: eligible assets exhausted ({len(scrap_eligible)} rows)")
            return {}
        asset = scrap_eligible[index]
        head = step(f"scrap submit {tag}", employee, "POST", "/scrap-requests", {
            "assetId": asset["id"], "assetVersion": asset["version"],
            "reason": "原机已无法继续使用，维修报价超过重置成本",
            "observedCondition": "主板与电源均无法通电，现场核验实物仍在原登记位置",
            "proposedDisposition": "断电拆解后按电子废弃物回收"})
        return scrap_reload(str(head["id"])) if head else {}

    def scrap_approve(tag: str, current: dict) -> dict:
        approved = step(f"scrap approve {tag}", admin, "POST", f"/scrap-requests/{current.get('id')}/decision", {
            "assetVersion": current.get("assetVersion"), "lockVersion": current.get("lockVersion"),
            "result": "APPROVE", "reason": "已核对维修与使用记录，同意按建议范围报废",
            "approvedDisposition": "断电拆解后按电子废弃物回收"}, int(current.get("version", 0)))
        return scrap_reload(str(current.get("id"))) if approved else current

    def scrap_claim(tag: str, current: dict) -> dict:
        claimed = step(f"scrap claim {tag}", warehouse, "POST", f"/scrap-requests/{current.get('id')}/claim", {
            "assetVersion": current.get("assetVersion"), "lockVersion": current.get("lockVersion")},
            int(current.get("version", 0)))
        if not claimed:
            return current
        status, body = warehouse.call("GET", f"/scrap-requests/{current.get('id')}")
        return json.loads(body) if status == 200 else current

    def scrap_execute(tag: str, current: dict) -> dict:
        request_id = str(current.get("id"))
        places = ((step(f"scrap location catalog {tag}", warehouse, "GET",
                        f"/scrap-requests/{request_id}/execution-catalog/location?page=1&pageSize=100")
                    or {}).get("items")) or []
        staff = ((step(f"scrap employee catalog {tag}", warehouse, "GET",
                       f"/scrap-requests/{request_id}/execution-catalog/employee?page=1&pageSize=100")
                  or {}).get("items")) or []
        if not places or not staff:
            failures.append(f"scrap execute {tag}: execution catalog empty")
            return current
        executed = step(f"scrap execute {tag}", warehouse, "POST", f"/scrap-requests/{request_id}/execute", {
            "assetVersion": current.get("assetVersion"), "lockVersion": current.get("lockVersion"),
            "ownershipVersion": current.get("ownershipVersion"),
            "observedCode": (current.get("source") or {}).get("assetCode", ""),
            "observedSerial": (current.get("source") or {}).get("serialNumber") or None,
            "locationId": places[0]["id"], "locationVersion": places[0]["version"],
            "custodianEmployeeId": staff[0]["id"], "custodianVersion": staff[0]["version"],
            "occurredAt": (datetime.now(timezone.utc).replace(microsecond=0)
                           + timedelta(seconds=1)).isoformat().replace("+00:00", "Z"),
            "physicalIdentityVerified": True,
            "evidence": "现场核对原机编码与序列号一致，实物已断电拆解并移交处置保管人",
            "dispositionMethod": "断电拆解后按电子废弃物回收",
            "plannedDisposalDate": (datetime.now(timezone.utc) + timedelta(days=10)).strftime("%Y-%m-%d")},
            int(current.get("version", 0)))
        if not executed:
            return current
        status, body = warehouse.call("GET", f"/scrap-requests/{request_id}")
        return json.loads(body) if status == 200 else current

    def scrap_reject(tag: str, current: dict) -> dict:
        rejected = step(f"scrap reject {tag}", admin, "POST", f"/scrap-requests/{current.get('id')}/decision", {
            "assetVersion": current.get("assetVersion"), "lockVersion": current.get("lockVersion"),
            "result": "REJECT", "reason": "仍可维修，先回原责任人继续使用并观察"},
            int(current.get("version", 0)))
        return scrap_reload(str(current.get("id"))) if rejected else current

    scrap_eligible = ((step("scrap eligible", employee, "GET",
                            "/scrap-requests/eligible?page=1&pageSize=20") or {}).get("items")) or []

    scrap_pending = scrap_submit(0, "pending")
    if scrap_pending:
        created["scrap"] = str(scrap_pending["id"])

    scrap_processing = scrap_submit(1, "processing")
    if scrap_processing:
        scrap_processing = scrap_claim("processing", scrap_approve("processing", scrap_processing))
        if (scrap_processing.get("actions") or {}).get("execute"):
            created["scrapProcessing"] = str(scrap_processing["id"])

    scrap_done = scrap_submit(2, "executed")
    if scrap_done:
        scrap_done = scrap_execute("executed", scrap_claim("executed", scrap_approve("executed", scrap_done)))
        if (scrap_done.get("disposition")):
            created["scrapExecuted"] = str(scrap_done["id"])

    scrap_rejected = scrap_submit(3, "rejected")
    if scrap_rejected:
        scrap_rejected = scrap_reject("rejected", scrap_rejected)
        if (scrap_rejected.get("status") or "").startswith("REJECT"):
            created["scrapRejected"] = str(scrap_rejected["id"])

    # 关联报废来源：一条「已受理原地维修 → 转关联报废」，两条「确认遗失 → 转关联报废」
    # （一张停在待管理员确认，一张走到已批准已认领，让执行动作与报废在途生命周期同屏可见）。
    def repair_reload(repair_id: str) -> dict:
        # 关联报废的动作位只有当前处理库管读得到，用建单员工的会话读会拿到空 actions。
        status, body = warehouse.call("GET", f"/repairs/{repair_id}")
        return json.loads(body) if status == 200 else {}

    def repair_accepted(tag: str, pool: list[dict], index: int) -> dict:
        """建单 → 批准内部无费用 → 库管领取 → 原地受理；停在 requestScrap 入口可见处。"""
        if len(pool) <= index:
            failures.append(f"repair {tag}: eligible assets exhausted ({len(pool)} rows)")
            return {}
        chosen = pool[index]
        accepted = step(f"repair {tag} submit", employee, "POST", "/repairs", {
            "assetId": chosen["id"], "assetVersion": chosen["version"],
            "fault": "原机维修后仍无法恢复", "impact": "MEDIUM", "occurredAt": now()})
        if not accepted:
            return {}
        decided = step(f"repair {tag} approve", admin, "POST",
                       f"/repairs/{accepted['id']}/decision",
                       {"result": "APPROVE", "allowedMethod": "INTERNAL_NO_COST",
                        "reason": "批准内部无费用处理，实物留在原地"}, version_of(accepted))
        claimed = step(f"repair {tag} claim", warehouse, "POST",
                       f"/repairs/{accepted['id']}/claim", None, version_of(decided) or 1)
        current = repair_reload(str(accepted["id"]))
        step(f"repair {tag} accept in place", warehouse, "POST",
             f"/repairs/{accepted['id']}/accept-in-place", {
                 "assetVersion": current.get("assetVersion"),
                 "ownershipVersion": current.get("ownershipVersion"),
                 "lockVersion": current.get("lockVersion"),
                 "observedCode": (current.get("source") or {}).get("assetCode", ""),
                 "actualLocationId": (current.get("source") or {}).get("locationId"),
                 "neverLeftCustodian": True,
                 "evidence": "现场核对原机编码与实物仍在原保管人处，按原地受理核实"},
             version_of(claimed) or version_of(current))
        return repair_reload(str(accepted["id"]))

    def repair_linked_scrap(tag: str, current: dict) -> None:
        if not (current.get("actions") or {}).get("requestScrap"):
            failures.append(f"repair {tag}: requestScrap action absent after accept-in-place")
            return
        submitted = step(f"repair {tag} linked scrap", warehouse, "POST",
                         f"/scrap-requests/from-repair/{current['id']}", {
                             "assetVersion": current.get("assetVersion"),
                             "ownershipVersion": current.get("ownershipVersion"),
                             "lockVersion": current.get("lockVersion"),
                             "reason": "原机维修后仍无法恢复",
                             "observedCondition": "实物仍在原地但已无使用价值",
                             "proposedDisposition": "断电拆解后按电子废弃物回收"},
                         version_of(current))
        if submitted:
            created["repairScrap"] = str(current["id"])
            created["scrapRepair"] = str(submitted["id"])

    def loss_confirmed(tag: str, index: int, pool: list[dict]) -> dict:
        """跑到「已确认遗失且未找回」，保留 requestScrap 入口本身可见。"""
        report_row = report(index, tag, pool)
        if not report_row:
            return {}
        approved = move(f"loss {tag} approve", admin, f"/loss-reports/{report_row['id']}/decision",
                        {"result": "APPROVE", "reason": "批准进入实物查找"}, report_row)
        claimed = move(f"loss {tag} claim", warehouse, f"/loss-reports/{approved.get('id', '')}/claim",
                       None, approved)
        searched = move(f"loss {tag} search", warehouse, f"/loss-reports/{claimed.get('id', '')}/search", {
            "assetVersion": claimed.get("assetVersion"), "ownershipVersion": claimed.get("ownershipVersion"),
            "lockVersion": claimed.get("lockVersion"),
            "custodianVerification": "已联系最后保管人核对完整交接过程",
            "searchedScope": "已查找原办公室抽屉、维修工位与盘点留痕",
            "searchedAt": iso(-2), "nextReviewDate": (datetime.now(timezone.utc) + timedelta(days=14))
            .date().isoformat()}, claimed)
        return move(f"loss {tag} confirm lost", warehouse,
                    f"/loss-reports/{searched.get('id', '')}/confirm-lost", {
                        "assetVersion": searched.get("assetVersion"),
                        "ownershipVersion": searched.get("ownershipVersion"),
                        "lockVersion": searched.get("lockVersion"),
                        "custodianVerification": "已联系最后保管人核对完整交接过程",
                        "searchedScope": "已查找原办公室抽屉、维修工位与盘点留痕",
                        "searchedAt": iso(-2),
                        "relatedClues": "交接单、维修工单与盘点记录均无实物线索",
                        "inabilityReason": "全部登记位置与责任人线索查尽，仍未见原实物",
                        "responsibilityEvidence": "保管人与两名同事共同确认原位置无实物",
                        "confirmedAt": iso(-1)}, searched)

    def loss_linked_scrap(tag: str, index: int, pool: list[dict], claim_after_approve: bool) -> dict:
        confirmed = loss_confirmed(f"{tag} source", index, pool)
        if not confirmed:
            return {}
        if not (confirmed.get("actions") or {}).get("requestScrap"):
            failures.append(f"loss {tag}: requestScrap action absent after confirm-lost")
            return confirmed
        linked = step(f"loss {tag} linked scrap", warehouse, "POST",
                      f"/scrap-requests/from-loss/{confirmed['id']}", {
                          "assetVersion": (confirmed.get("recoveryAsset") or {}).get("version"),
                          "reason": "持续查找未找回，原实物无法收回",
                          "observedCondition": "线索查尽且保管人与同事共同确认无实物",
                          "proposedDisposition": "遗失核销"}, version_of(confirmed))
        if not linked:
            return confirmed
        receipt = scrap_reload(str(linked["id"])) or linked
        if not claim_after_approve:
            created["scrapLoss"] = str(receipt["id"])
            return confirmed
        approved_scrap = step(f"loss {tag} scrap approve", admin, "POST",
                              f"/scrap-requests/{receipt['id']}/decision", {
                                  "assetVersion": receipt.get("assetVersion"),
                                  "lockVersion": receipt.get("lockVersion"), "result": "APPROVE",
                                  "reason": "已核对遗失证据，批准按遗失核销处置",
                                  "approvedDisposition": "遗失核销"}, version_of(receipt))
        if not approved_scrap:
            return confirmed
        current = scrap_reload(str(receipt["id"]))
        claimed_scrap = step(f"loss {tag} scrap claim", warehouse, "POST",
                             f"/scrap-requests/{current.get('id')}/claim", {
                                 "assetVersion": current.get("assetVersion"),
                                 "lockVersion": current.get("lockVersion")}, version_of(current))
        if claimed_scrap:
            created["scrapLossProcessing"] = str(receipt["id"])
        return confirmed

    # 关联报废要在报失/报废两条线跑完之后再取一次可报失清单：先前那份快照里的
    # 前几张资产此时已被自己的单据锁住，直接按下标复用会撞 LOSS_ASSET_CHANGED。
    linked_eligible = ((step("loss linked eligible", employee, "GET",
                             "/loss-reports/eligible?page=1&pageSize=20") or {}).get("items")) or []
    pending_linked = loss_linked_scrap("linked pending", 0, linked_eligible, False)
    if pending_linked:
        created["lossScrapPending"] = str(pending_linked["id"])
    processing_linked = loss_linked_scrap("linked processing", 1, linked_eligible, True)
    if processing_linked:
        created["lossScrapProcessing"] = str(processing_linked["id"])

    # 入口态各留一张：一张「已确认遗失、尚未转报废」（报失页的关联报废表单可见），
    # 一张「已受理原地维修、尚未转报废」（报修详情页的关联报废表单可见）。
    entry_eligible = ((step("loss entry eligible", employee, "GET",
                            "/loss-reports/eligible?page=1&pageSize=20") or {}).get("items")) or []
    entry_report = loss_confirmed("scrap entry", 0, entry_eligible)
    if (entry_report.get("actions") or {}).get("requestScrap"):
        created["lossScrapEntry"] = str(entry_report["id"])
    else:
        failures.append("loss scrap entry: requestScrap action absent after confirm-lost")

    repair_pool = ((step("repair linked eligible", employee, "GET",
                         "/repairs/eligible?page=1&pageSize=20") or {}).get("items")) or []
    entered_repair = repair_accepted("scrap entry", repair_pool, 0)
    if (entered_repair.get("actions") or {}).get("requestScrap"):
        created["repairScrapEntry"] = str(entered_repair["id"])
    else:
        failures.append("repair scrap entry: requestScrap action absent after accept-in-place")
    handed_repair = repair_accepted("linked scrap", repair_pool, 1)
    if handed_repair:
        repair_linked_scrap("linked scrap", handed_repair)


    if (args.only_loss or args.only_scrap) and args.out.is_file():
        # 只跑单条线时保留上一轮记下的其它单据 id，路由清单仍要它们。
        created = {**json.loads(args.out.read_text()), **created}
    args.out.write_text(json.dumps(created, indent=2, ensure_ascii=False))
    print(f"\ncreated={json.dumps(created, ensure_ascii=False)}", flush=True)
    if failures:
        print(f"failures={len(failures)}")
        for line in failures:
            print("  " + line)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
