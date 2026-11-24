#!/usr/bin/env python3
"""Controller 直达入口的事务属性覆盖检查（CONV-08 常驻门禁）。

判据：Spring `AnnotationTransactionAttributeSource` 继承的
`AbstractFallbackTransactionAttributeSource.computeTransactionAttribute` 在任何回退到类级注解之前
就因 `!Modifier.isPublic(method)` 返回 null 事务属性。因此一个被 Controller 经代理调用、
却【既非 public 又无 @Transactional】的 Service/Port 方法，其多段写会跑在多次 autocommit 上。

用法：
    python3 scripts/check-controller-transaction-coverage.py [--root backend/src/main/java/com/fams]

退出码：0 = 无缺口；1 = 存在缺口（逐条打印 文件:行 方法名 可见性 读写）；2 = 用法错误。
口径与人工复核的差异：本脚本用"Controller 字段类型名"解析调用点，不解析接口方法/反射调用；
新增非 Controller 入口（定时任务、消息监听）需另行覆盖。
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
            class_line = next(
                (i for i, l in enumerate(lines) if CLASS_DECL.match(l)), len(lines)
            )
            class_tx = bool(TX_ANN.search("\n".join(lines[:class_line])))
            ann_of = member_annotations(lines)
            for i, line in enumerate(lines):
                m = METHOD_DECL.match(line)
                if not m:
                    continue
                visibility, ret_type, method = m.group(1), m.group(2), m.group(3)
                if not re.search(r"[A-Za-z_>\]]", ret_type or ""):
                    continue
                if method.isupper() or method == cls:
                    continue
                if f"{cls}.{method}" not in calls.get(domain, set()):
                    continue
                own = ann_of.get(i, "")
                if any(a in own for a in SKIP_ANNOTATION):
                    continue
                annotated = bool(TX_ANN.search(own)) or class_tx
                public = (visibility or "").strip() == "public"
                if annotated and public:
                    continue
                body = method_body(lines, i)
                kind = "W" if WRITE.search(body) else ("R-lock" if LOCK.search(body) else "R")
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
        print(
            "结论：以上入口被 Controller 经代理直达，却不满足 Spring 的 public + @Transactional"
            " 双条件，其多段写会跑在多次 autocommit 上。",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
