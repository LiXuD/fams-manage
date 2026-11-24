#!/usr/bin/env python3
"""CONV-09/A6 Javadoc 质量检查（不只是"有没有"，还要"是不是空话"）。

checkstyle 的 MissingJavadocType/MissingJavadocMethod 只判存在性，判不出复述方法名的空话
（`Gets the warehouse.` 配 `getWarehouse()`）。本脚本补这一层，用于 A6 批次收口验收。

判据：
  T1 缺 @param         —— 方法有参数但 Javadoc 未逐个给出 @param
  T2 缺 @return        —— 方法非 void 且无 @return
  T3 空话描述          —— 描述去掉冠词/动词后与方法名或类型名的词形重合度过高
  T4 占位/待办         —— 含 @author、@since、TODO、FIXME、XXX、lorem
  T5 描述过短          —— 首句少于 4 个词
  T6 未闭合            —— /** 没有配对 */（语法破坏，最高优先级）

用法： python3 scripts/check-javadoc-quality.py [--root backend/src/main/java/com/fams] [--domain asset]
退出码：0 = 无 T1~T6；1 = 有问题；2 = 用法错误。
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from collections import Counter, defaultdict

TYPE = re.compile(r"^\s{0,4}(?:public\s+|final\s+|abstract\s+|static\s+)*"
                  r"(?:class|interface|record|enum)\s+(\w+)")
# 只匹配声明起始；参数表用括号配平向后收集，因为本仓大量方法签名跨多行
METHOD = re.compile(r"^\s{4}(?:public\s+)(?:static\s+|final\s+|synchronized\s+)*"
                    r"([\w<>\[\],.?\s]+?)\s+(\w+)\s*\(")
JDOC_OPEN = re.compile(r"^\s*/\*\*")
TAG = re.compile(r"^\s*\*\s*@(\w+)")
STOPWORDS = {"the", "a", "an", "this", "that", "is", "are", "of", "for", "to", "in", "on",
             "gets", "get", "returns", "return", "sets", "set", "method", "class", "value",
             "values", "instance", "object", "result", "results"}


def words(text: str) -> set[str]:
    return {w.lower() for w in re.findall(r"[A-Za-z]{3,}", text)} - STOPWORDS


def camel_parts(name: str) -> set[str]:
    parts = re.findall(r"[A-Z]?[a-z]+|[A-Z]+(?=[A-Z]|$)", name)
    return {p.lower() for p in parts if len(p) >= 3} - STOPWORDS


def collect_params(lines: list[str], start: int) -> str:
    """从声明行的左括号起按括号配平收集参数表（支持跨多行签名）。"""
    depth = 0
    buf = []
    for i in range(start, min(start + 40, len(lines))):
        for ch in lines[i]:
            if ch == "(":
                depth += 1
                if depth == 1:
                    continue
            elif ch == ")":
                depth -= 1
                if depth == 0:
                    return "".join(buf)
            if depth >= 1:
                buf.append(ch)
        buf.append(",")  # 行分隔不参与，仅用于避免跨行粘连
    return "".join(buf)


def count_params(param_text: str) -> int:
    t = param_text.strip()
    if not t:
        return 0
    depth = 0
    n = 1
    for ch in t:
        if ch in "<([":
            depth += 1
        elif ch in ">)]":
            depth -= 1
        elif ch == "," and depth == 0:
            n += 1
    return n


def javadoc_blocks(lines: list[str]) -> list[tuple[int, list[str]]]:
    """返回 [(起始行号, 块内行)]，块以 /** 开始、*/ 结束。"""
    out, i = [], 0
    while i < len(lines):
        if JDOC_OPEN.match(lines[i]):
            j = i
            block = []
            while j < len(lines):
                block.append(lines[j])
                if "*/" in lines[j]:
                    break
                j += 1
            out.append((i + 1, block))
            i = j + 1
        else:
            i += 1
    return out


def block_text(block: list[str]) -> tuple[str, dict[str, list[str]], bool]:
    """(描述文本, 标签->值列表, 是否闭合)"""
    closed = any("*/" in l for l in block)
    desc, tags = [], defaultdict(list)
    for l in block:
        s = l.strip().lstrip("/*").strip()
        m = TAG.match(l)
        if m:
            tags[m.group(1)].append(s.split(None, 1)[1] if len(s.split()) > 1 else "")
        elif s and not s.startswith("@"):
            desc.append(s)
    return " ".join(desc), dict(tags), closed


def check_file(path: str, rel: str) -> list[str]:
    src = open(path, encoding="utf-8").read()
    lines = src.split("\n")
    issues: list[str] = []
    blocks = javadoc_blocks(lines)
    # 建立"声明行 -> 其上方最近的 Javadoc 块"映射
    doc_for: dict[int, tuple[int, list[str]]] = {}
    for start, block in blocks:
        k = start + len(block) - 1  # 块最后一行（0-based 索引为 start+len-2）
        for probe in range(k, min(k + 12, len(lines))):
            if lines[probe].strip().startswith("@") or not lines[probe].strip():
                continue
            doc_for[probe] = (start, block)
            break
    for start, block in blocks:
        _, _, closed = block_text(block)
        if not closed:
            issues.append(f"{rel}:{start} T6 Javadoc 未闭合")
    for i, line in enumerate(lines):
        mt = METHOD.match(line)
        tt = TYPE.match(line)
        if mt and mt.group(1).strip() in ("record", "class", "interface", "enum"):
            mt = None  # record/class 声明不是方法；组件文档由类型级 Javadoc 承载，
            #            与 checkstyle JavadocMethod 口径一致
        if mt:
            # 返回类型行里若出现 record/class 关键字或分号，说明不是方法声明
            head = line.split("(")[0]
            if ";" in head or re.search(r"\b(class|record|interface|enum)\b", head):
                mt = None
        if not (mt or tt):
            continue
        entry = doc_for.get(i)
        if entry is None:
            continue  # 存在性由 checkstyle MissingJavadoc* 负责，本脚本只管质量
        start, block = entry
        desc, tags, _ = block_text(block)
        name = (mt.group(2) if mt else tt.group(1))
        if mt:
            nparams = count_params(collect_params(lines, i))
            given = tags.get("param", [])
            if nparams and len(given) < nparams:
                issues.append(f"{rel}:{i + 1} T1 {name}() 参数 {nparams} 个但 @param 只有 {len(given)} 条")
            ret = mt.group(1).strip()
            if ret not in ("void",) and not tags.get("return"):
                issues.append(f"{rel}:{i + 1} T2 {name}() 返回 {ret[:28]} 但缺 @return")
        dw, nw = words(desc), camel_parts(name)
        if not dw:
            issues.append(f"{rel}:{start} T5 {name} 描述为空或全是停用词")
        elif len(dw) <= 3 and nw and dw <= nw:
            issues.append(f"{rel}:{start} T3 {name} 描述只复述名字：{desc[:60]!r}")
        elif nw and len(dw & nw) / max(len(dw), 1) > 0.8 and len(dw) < 6:
            issues.append(f"{rel}:{start} T3 {name} 描述与方法名词形高度重合：{desc[:60]!r}")
        first = desc.split(".")[0]
        if 0 < len(first.split()) < 4:
            issues.append(f"{rel}:{start} T5 {name} 首句过短：{first[:50]!r}")
        for bad in ("author", "since"):
            if tags.get(bad):
                issues.append(f"{rel}:{start} T4 {name} 含 @{bad} 占位标签")
        if re.search(r"\b(TODO|FIXME|XXX|lorem)\b", desc):
            issues.append(f"{rel}:{start} T4 {name} 描述含待办占位")
    return issues


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--root", default="backend/src/main/java/com/fams")
    ap.add_argument("--domain", help="只检查 com/fams/<domain>/ 下的文件")
    args = ap.parse_args()
    root = args.root if not args.domain else os.path.join(args.root, args.domain)
    if not os.path.isdir(root):
        print(f"root not found: {root}", file=sys.stderr)
        return 2
    all_issues: list[str] = []
    per_dom: Counter = Counter()
    for dp, _, fn in os.walk(root):
        for x in sorted(fn):
            if not x.endswith(".java"):
                continue
            p = os.path.join(dp, x)
            rel = os.path.relpath(p, args.root)
            found = check_file(p, rel)
            all_issues.extend(found)
            for f in found:
                per_dom[(rel.split("/")[0], f.split()[1])] += 1
    kinds: Counter = Counter(f.split()[1] for f in all_issues)
    print(f"[Javadoc 质量] 问题 = {len(all_issues)}  {dict(sorted(kinds.items()))}")
    by_dom: Counter = Counter(d for (d, _), n in per_dom.items() for _ in range(n))
    for d, n in sorted(by_dom.items(), key=lambda t: -t[1])[:12]:
        print(f"  {d:<16}{n}")
    for i in all_issues[:40]:
        print("   ", i)
    if len(all_issues) > 40:
        print(f"    ... 另有 {len(all_issues) - 40} 条")
    return 1 if all_issues else 0


if __name__ == "__main__":
    sys.exit(main())
