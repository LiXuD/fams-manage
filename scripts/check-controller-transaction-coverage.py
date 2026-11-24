#!/usr/bin/env python3
"""Controller 直达入口的事务属性覆盖检查（CONV-08 常驻门禁）。

判据：Spring `AnnotationTransactionAttributeSource` 继承的
`AbstractFallbackTransactionAttributeSource.computeTransactionAttribute` 在任何回退到类级注解之前
就因 `!Modifier.isPublic(method)` 返回 null 事务属性。因此一个被 Controller 经代理调用、
却【既非 public 又无 @Transactional】的 Service/Port 方法，其多段写会跑在多次 autocommit 上。

用法：
    python3 scripts/check-controller-transaction-coverage.py [--root backend/src/main/java/com/fams]

退出码：0 = 无缺口；1 = 存在缺口（逐条打印 文件:行 方法名 可见性 读写）；2 = 用法错误。
已知局限（不得当作完备判据）：
1. 调用点用"Controller 字段类型名"解析，不覆盖接口方法、反射调用，以及定时任务/消息监听等非
   Controller 入口。
2. **DB 操作只统计方法体内文本可见的调用**。经同类 private 辅助方法（require/visible/one/receipt
   等）或 *Store 字段间接产生的多语句读不会被计入，因此本脚本对这类缺口是**低报**的。
   CONV-08 即由代理读码发现 8 处此类真缺口（AssetService.get/resolve/tag、
   InboundService.getDetail、AuthApplicationService.loadPermissions、
   BusinessParameterService.current、NotificationChannelConfigService.current、
   TransferService.reassignment），脚本当时判为"单语句读"。
   曾两次尝试让脚本跟随同类调用图（先递归后 BFS），均因 METHOD_DECL/CALL 正则会命中非声明行
   与常见 token 而严重过度计数（同一方法真值 1 却算出 3 与 32），故明确放弃：**调用图判定交给人
   工/代理读码，脚本只负责机械可判的部分**。任何据本脚本得出的"缺口为 0"都必须读作
   "文本可见缺口为 0"。
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from collections import defaultdict

BEAN_SUFFIX = ("Service", "Port", "Store", "Access")
FIELD = re.compile(r"^\s+private\s+(?:final\s+)?([A-Z]\w*)\s+([a-z]\w*)\s*;", re.M)
CLASS_DECL = re.compile(r"^\s{0,4}(?:public |final |abstract )*class\s")
METHOD_DECL = re.compile(
    r"^\s{4}(public |protected |private |static |final |synchronized |\s)*"
    r"([\w<>\[\],.?\s]+?)\s+([A-Za-z]\w*)\s*\("
)
WRITE = re.compile(
    r"\bdb\.(update|execute|batchUpdate)\(|\.insert\(|\.update\(|\.delete\(|saveBatch|save_or_update",
    re.I,
)
LOCK = re.compile(r"FOR\s+UPDATE|FOR\s+SHARE", re.I)
TX_ANN = re.compile(r"@Transactional\b")
FIELD_LIKE = re.compile(r"^\s{4}(?:public |protected |private |static |final )*\w[\w<>,.\[\]?\s]*\s+\w+\s*[;=]")
# 明确不算业务入口的形态：生命周期回调、工厂、访问器
# 有意的无事务入口：证据见理由文本，新增条目必须附代码级证据，不允许口头豁免。
INTENTIONAL = {
    "loss/LossPhotoService.upload": "AttachmentWriter 显式守卫：事务存在即抛 IllegalStateException，"
        "外层事务可能在返回后回滚并孤立已落盘文件",
    "scrap/ScrapAttachmentService.upload": "同上；register 为 MANDATORY，事务由 AttachmentWriter 的"
        "TransactionTemplate 供给，外层提前开事务会后移加锁窗口",
    "inventory/InventoryPhotoService.upload": "同上；且会把 fams_system_account FOR UPDATE 锁持有"
        "窗口拉长到整个文件接收/扫描期间，结尾 storage.delete 依赖注册事务已提交",
    "auth/AccountAdminService.listRoleConfigurations": "调用图零 SQL（Role 枚举 + AuthPolicy 静态"
        "集合分页），加事务只是每次请求白起一个事务并占用一条连接，无原子性可保护",
    "auth/AccountAdminService.getRoleConfiguration": "同上，纯静态权限集合查询",
    "repair/RepairPhotoService.upload": "类注释原文 Scanning occurs without a business "
        "transaction; registration rechecks current authority；经 AttachmentWriter 守卫",
    "repair/RepairCollaborationPhotoService.upload": "同上，类注释与 AttachmentWriter 守卫一致",
}

# 缺陷的真实定义：多语句之间失去原子性，或写操作缺少回滚边界。
# 单条语句的读没有可保护的原子性，加事务只是白占连接，不判为缺口。
DB_OP = re.compile(
    r"\bdb\.(queryForObject|queryForList|queryForMap|query|update|execute|batchUpdate)\(|"
    r"(?<![\w.])(\w*(?:[Rr]epository|[Mm]apper|[Pp]ort|[Ss]tore))\s*\.\s*\w+\s*\(")
CALL = re.compile(r"(?<![.\w])([a-z]\w*)\s*\(")




SKIP_ANNOTATION = ("@PostConstruct", "@PreDestroy", "@Bean", "@EventListener", "@Scheduled")


def controller_call_points(root: str) -> dict[str, set[str]]:
    """domain -> set of "<ClassName>.<method>" invoked from that domain's controllers."""
    calls: dict[str, set[str]] = defaultdict(set)
    for dirpath, _, filenames in os.walk(root):
        for name in filenames:
            if not name.endswith("Controller.java"):
                continue
            domain = os.path.relpath(dirpath, root).split(os.sep)[0]
            source = open(os.path.join(dirpath, name), encoding="utf-8").read()
            for type_name, var_name in FIELD.findall(source):
                if not type_name.endswith(BEAN_SUFFIX):
                    continue
                for m in re.finditer(
                    r"(?<![.\w])" + re.escape(var_name) + r"\s*\.\s*(\w+)\s*\(", source
                ):
                    calls[domain].add(f"{type_name}.{m.group(1)}")
    return calls


def member_annotations(lines: list[str]) -> dict[int, str]:
    """声明行号 -> 其紧邻上方归属它的注解文本。

    逐行前向扫描并维护注解缓冲，遇到声明行即把缓冲归属给它然后清空。
    这样注解不会跨方法渗漏（"上方 N 行窗口"式判定会把上一个方法的注解误算给下一个）。
    """
    owner: dict[int, str] = {}
    pending: list[str] = []
    depth = 0
    for i, line in enumerate(lines):
        stripped = line.strip()
        if depth > 0:
            pending.append(stripped)
            depth += stripped.count("(") - stripped.count(")")
            continue
        if stripped.startswith("@"):
            pending.append(stripped)
            depth = stripped.count("(") - stripped.count(")")
            continue
        if not stripped or stripped.startswith(("//", "*", "/*", "*/")):
            continue
        m = METHOD_DECL.match(line) or FIELD_LIKE.match(line)
        if m:
            owner[i] = " ".join(pending)
        pending = []
        depth = 0
    return owner


def method_body(lines: list[str], start: int) -> str:
    """按花括号配平取出方法体，避免固定行数窗口溢进下一个方法而误判读写。"""
    depth = 0
    started = False
    out = []
    for i in range(start, len(lines)):
        for ch in lines[i]:
            if ch == "{":
                depth += 1
                started = True
            elif ch == "}":
                depth -= 1
        out.append(lines[i])
        if started and depth <= 0:
            break
    return "\n".join(out)


def scan(root: str) -> list[str]:
    calls = controller_call_points(root)
    gaps: list[str] = []
    for dirpath, _, filenames in os.walk(root):
        for name in filenames:
            if not (name.endswith("Service.java") or name.endswith("Port.java")):
                continue
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, root)
            domain = rel.split(os.sep)[0]
            cls = name[: -len(".java")]
            lines = open(path, encoding="utf-8").read().split("\n")
            brace_depth = 0  # 用于识别内嵌接口/类里的抽象方法声明
            class_line = next(
                (i for i, l in enumerate(lines) if CLASS_DECL.match(l)), len(lines)
            )
            class_tx = bool(TX_ANN.search("\n".join(lines[:class_line])))
            ann_of = member_annotations(lines)
            bodies: dict[str, list[int]] = {}
            for j, ln in enumerate(lines):
                mm = METHOD_DECL.match(ln)
                if mm and not mm.group(3).isupper() and mm.group(3) != cls:
                    bodies.setdefault(mm.group(3), []).append(j)
            decls: list[tuple[str, bool, bool]] = []
            for j, ln in enumerate(lines):
                mm = METHOD_DECL.match(ln)
                if mm:
                    decls.append((
                        mm.group(3),
                        (mm.group(1) or "").strip() == "public",
                        bool(TX_ANN.search(ann_of.get(j, ""))) or class_tx,
                    ))
            for i, line in enumerate(lines):
                m = METHOD_DECL.match(line)
                if not m:
                    continue
                visibility, ret_type, method = m.group(1), m.group(2), m.group(3)
                if (visibility or "").strip() == "private":
                    continue  # private 永远不可能是代理入口，跑在外层事务里，不是缺陷
                if not re.search(r"[A-Za-z_>\]]", ret_type or ""):
                    continue
                if method.isupper() or method == cls:
                    continue
                if line.rstrip().endswith(";"):
                    continue  # 接口/抽象方法声明（无方法体），代理不读取其注解
                if f"{cls}.{method}" not in calls.get(domain, set()):
                    continue
                body = method_body(lines, i)
                writes = bool(WRITE.search(body))
                if not writes and len(DB_OP.findall(body)) < 2:
                    continue  # 方法体内可见的 DB 操作少于两条：无原子性可保护
                keys = {f"{cls}.{method}",
                        f"{rel[:-5] if rel.endswith('.java') else rel}.{method}"}
                if keys & set(INTENTIONAL):
                    continue
                own = ann_of.get(i, "")
                if any(a in own for a in SKIP_ANNOTATION):
                    continue
                annotated = bool(TX_ANN.search(own)) or class_tx
                public = (visibility or "").strip() == "public"
                if annotated and public:
                    continue
                # 同名重载：本类若已存在 public + 已标注的同名声明，其余视为中间重载委托。
                # 调用方只能按名字匹配，无法区分重载；已存在合格入口即不重复报缺口。
                if any(
                    other_name == method and other_public and other_tx
                    for other_name, other_public, other_tx in decls
                ):
                    continue
                kind = "W" if writes else ("R-lock" if LOCK.search(body) else "R")
                reason = []
                if not annotated:
                    reason.append("无事务属性")
                if not public:
                    reason.append(f"非 public[{(visibility or 'package-private').strip()}]")
                gaps.append(f"{rel}:{i + 1} {method} -> {'+'.join(reason)} ({kind})")
    return gaps


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        default="backend/src/main/java/com/fams",
        help="Java source root to scan (default: %(default)s)",
    )
    parser.add_argument(
        "--expect-zero",
        action="store_true",
        help="Fail if any gap is reported (default behaviour is to report and fail)",
    )
    args = parser.parse_args()
    if not os.path.isdir(args.root):
        print(f"root not found: {args.root}", file=sys.stderr)
        return 2
    gaps = scan(args.root)
    print(f"[Controller 事务覆盖] 缺口 = {len(gaps)}")
    for g in gaps:
        print(f"  {g}")
    if gaps:
        print("以上每条都需人工判定：同名重载会按方法名过度命中，"
              "只有确为 Controller 直达代理入口的才需补注解；有意无事务设计请加入 INTENTIONAL 并附证据。",
              file=sys.stderr)
        print(
            "结论：以上入口被 Controller 经代理直达，却不满足 Spring 的 public + @Transactional"
            " 双条件，其多段写会跑在多次 autocommit 上。",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
