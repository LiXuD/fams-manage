"""Focused inbound recovery assertions for the disposable task runtime."""

import json
import urllib.error
import urllib.request
import uuid
from collections.abc import Callable


class Actor:
    def __init__(self, username: str, password: str):
        self.client = urllib.request.build_opener()
        self.cookie = ""
        self.csrf = ""
        result = self.call("POST", "/auth/login", {"username": username, "password": password})
        self.csrf = result["csrfToken"]

    def call(self, method: str, path: str, body=None, version=None, key=None):
        headers = {"Content-Type": "application/json"}
        if self.cookie:
            headers["Cookie"] = self.cookie
        if self.csrf and method != "GET":
            headers.update({"X-CSRF-Token": self.csrf, "Idempotency-Key": key or str(uuid.uuid4())})
        if version is not None:
            headers["If-Match-Version"] = str(version)
        request = urllib.request.Request("http://127.0.0.1:8080/api/v1" + path,
            data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
        try:
            with self.client.open(request, timeout=30) as response:
                # Echo the real login cookie on this loopback HTTP fixture, as the Java HTTP tests do.
                cookie = response.headers.get("Set-Cookie")
                if cookie and method == "POST" and path == "/auth/login":
                    self.cookie = cookie.split(";", 1)[0]
                return json.load(response)
        except urllib.error.HTTPError as error:
            raise RuntimeError(f"Recovery request {method} {path} returned {error.code}") from None


def verify_recovery(password: str, restart: Callable[[], None], query: Callable[[str], str]):
    warehouse = Actor("warehouse", password)
    admin = Actor("admin", password)
    asset = warehouse.call("POST", "/assets", {"name": "Recovery desk", "model": "M1",
        "unit": "piece", "category": {"id": "1", "version": 0},
        "organization": {"id": "1", "version": 0}})
    receipt = warehouse.call("POST", "/inbound-receipts", {"businessDate": "2026-09-27",
        "items": [{"asset": {"id": asset["id"], "version": asset["version"]},
            "targetWarehouse": {"id": "1", "version": 0},
            "targetLocation": {"id": "1", "version": 0}}]})
    path = "/inbound-receipts/" + receipt["id"]
    receipt = warehouse.call("POST", path + "/submit", version=receipt["version"])
    receipt = admin.call("POST", path + "/decision", {"decision": "APPROVED"}, receipt["version"])
    receipt = warehouse.call("POST", path + "/claim", version=receipt["version"])
    restart()
    recovered = warehouse.call("GET", path)
    assert recovered["status"] == "WAREHOUSE_PROCESSING"
    assert recovered["version"] == receipt["version"]
    assert recovered["currentProcessor"]["processor"]["username"] == "warehouse"
    item = recovered["items"][0]
    current_asset = warehouse.call("GET", "/assets/" + asset["id"])
    acceptance = {"assetVersion": current_asset["version"],
        "lockVersion": item["businessLock"]["lockVersion"], "observedAssetCode": asset["assetCode"]}
    key = str(uuid.uuid4())
    target = path + "/items/" + item["id"] + "/accept"
    # Keep the original request for a retry after losing the response across a process restart.
    result = warehouse.call("POST", target, acceptance, recovered["version"], key)
    restart()
    replay = warehouse.call("POST", target, acceptance, recovered["version"], key)
    assert replay == result
    assert warehouse.call("GET", path)["status"] == "COMPLETED"
    stocked = warehouse.call("GET", "/assets/" + asset["id"])
    assert stocked["lifecycle"] == "IN_STOCK"
    values = query("SELECT COUNT(*) FROM fams_inbound_acceptance WHERE receipt_item_id=" + item["id"]
        + "; SELECT COUNT(*) FROM fams_asset_business_lock WHERE asset_id=" + asset["id"]
        + " AND released_at IS NULL; SELECT COUNT(*) FROM fams_todo WHERE business_type='INBOUND' AND business_id="
        + receipt["id"] + " AND closed_at IS NULL;").split()
    assert values == ["1", "0", "0"], values
    print("D6-07 recovery passed: claimed work survived hard restart; acceptance replayed once; locks/todos closed.", flush=True)
