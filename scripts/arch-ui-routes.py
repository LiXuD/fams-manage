#!/usr/bin/env python3
"""从 apps/web/src/router.ts 生成浏览器实测的路由清单。

把 :id / :id? 按域映射到 seed-arch-ui-docs.py 造出来的真实单据 id；某域没建行时回退 "1"，
让该详情路由仍然被渲染（两端回退一致，不影响基线对比）。

用法：python3 scripts/arch-ui-routes.py [--router apps/web/src/router.ts]
        [--ids /tmp/fams-arch-ids.json] [--out /tmp/arch-routes.json]
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

# 路由名 -> 建行时使用的记录键；None 表示该域本轮没有单据，回退占位 id
DOMAIN_BY_ROUTE = {
    "repair": "repair",
    "repair-detail": "repair",
    "repair-assistance": "repair",
    "inbound": "inboundA",
    "inbound-detail": "inboundA",
    "inbound-edit": "inboundA",
    "inbound-new": None,
    "requisition": "requisition",
    "requisition-detail": "requisition",
    "requisition-edit": "requisition",
    "inventory-tasks": "inventoryTask",
    "inventory-task-detail": "inventoryTask",
    "inventory-task-edit": "inventoryTask",
    "loan": "loan",
    "loan-detail": "loan",
    "asset-return-detail": "assetReturn",
    "transfer-detail": "transfer",
    "loss-reports": "loss",
    "scrap-requests": "scrap",
}

# 同一个域里要多渲染几张单据时按 ids 的键补详情路由；名字带后缀，避免和 expand 生成的重名。
EXTRA_DETAIL = {"loss-reports": {"lossPending": "loss-pending", "lossSearching": "loss-searching",
                                 "lossTransfer": "loss-transfer-direct",
                                 "lossRecoveredTransfer": "loss-transfer-attempt",
                                 "lossScrapPending": "loss-scrap-pending",
                                 "lossScrapProcessing": "loss-scrap-processing",
                                 "lossScrapEntry": "loss-scrap-entry"},
                "transfer-detail": {"lossCreatedTransfer": "transfer-from-loss-recovery"},
                "repair-detail": {"repairScrap": "repair-repair-scrap",
                                  "repairScrapEntry": "repair-repair-scrap-entry"},
                "scrap-requests": {"scrapProcessing": "scrap-processing", "scrapExecuted": "scrap-executed",
                                   "scrapRejected": "scrap-rejected", "scrapLoss": "scrap-loss-sourced",
                                   "scrapRepair": "scrap-repair-sourced",
                                   "scrapLossProcessing": "scrap-loss-processing"}}

PARAM = re.compile(r"^(\s*)(?:\{ path: \")(/[^\"]*)\"(?:, name: \"([^\"]+)\")?")

# 这些列表视图带「下一页」按钮，翻页探针加在它们身上（由 grep 下一页 的视图清单映射而来）
PROBED = {
    "assets", "asset-import", "asset-import-review", "asset-returns",
    "business-result-notices", "business-result-reviews", "inventory-task-detail",
    "inventory-tasks", "loans", "loss-reports", "proxy-confirmations", "requisition-target-changes",
    "requisitions", "repair-assistance", "repairs", "scrap-requests", "transfer-target-changes", "transfers",
}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--router", type=Path, default=Path("apps/web/src/router.ts"))
    parser.add_argument("--ids", type=Path, default=Path("/tmp/fams-arch-ids.json"))
    parser.add_argument("--out", type=Path, default=Path("/tmp/arch-routes.json"))
    args = parser.parse_args()

    ids = json.loads(args.ids.read_text()) if args.ids.is_file() else {}
    rows: list[dict[str, str]] = []
    for line in args.router.read_text().splitlines():
        match = PARAM.match(line)
        if not match:
            continue
        _, template, name = match.groups()
        name = name or template.strip("/") or "root"
        for variant in expand(template, name, ids):
            row = {"name": name, "path": variant}
            if name in PROBED:
                row["probe"] = "next-page"
            rows.append(row)
        for key, extra_name in EXTRA_DETAIL.get(name, {}).items():
            if ids.get(key):
                rows.append({"name": extra_name, "path": f"{template.replace(':id?', ':id')}".replace(":id", str(ids[key]))})

    args.out.write_text(json.dumps(rows, indent=2, ensure_ascii=False))
    missing = sorted({row["name"] for row in rows if ":id" in row["path"]})
    print(f"routes={len(rows)} out={args.out} ids={json.dumps(ids, ensure_ascii=False)}")
    if missing:
        print(f"placeholder_id_routes={len(missing)}: {', '.join(missing)}")
    return 0


def expand(template: str, name: str, ids: dict[str, str]) -> list[str]:
    if ":id?" in template:
        base = template.replace("/:id?", "")
        value = resolve(name, ids)
        return [base, f"{base}/{value}"] if value else [base]
    if ":id" in template:
        return [template.replace(":id", resolve(name, ids) or "1")]
    return [template]


def resolve(name: str, ids: dict[str, str]) -> str | None:
    key = DOMAIN_BY_ROUTE.get(name)
    return ids.get(key) if key else None


if __name__ == "__main__":
    raise SystemExit(main())
