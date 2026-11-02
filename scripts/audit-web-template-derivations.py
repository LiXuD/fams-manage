#!/usr/bin/env python3
"""ARCH-08 模板派生审计：把「模板保持声明式」变成可执行判据。

扫描 apps/web 全部 SFC 的 <template> 区块，判定四类位置：插值 {{ }}、绑定 :x=""、
条件 v-if / v-else-if / v-show、以及 v-for 的集合来源。允许的表达式只有一类：
读取具名状态（标识符、属性路径、可选链、字典下标、存在性取反 !x、单字段 || / ?? 兜底、
纯布尔组合），以及 v-for 迭代序号的位次标注（index + 1）。
其余（三元、比较、算术、函数调用、new Date、集合/字符串方法）必须归位到 <script> 的
computed 或行显示模型。事件处理器 @click / @change 与 v-model 写入目标是动作或输入，
不属于派生，不在判定范围内。

用法：python3 scripts/audit-web-template-derivations.py [扫描目录]
退出码 0 表示模板内已无派生表达式。
"""
import pathlib
import re
import sys

ORDINAL = re.compile(r"^(?:index|i|idx|n) \+ 1$")
DATE_MATH = re.compile(r"\bnew Date\b|\bMath\.|\bNumber\.|\bJSON\.|\bencodeURIComponent\(|\bdecodeURIComponent\(")
METHOD = re.compile(r"\.(filter|find|findIndex|map|some|every|reduce|sort|slice|split|join|trim|toFixed|toLocaleString|toLocaleDateString|includes|startsWith|endsWith|replace|padStart|toUpperCase|toLowerCase|concat|flat|keys|values|entries|has|get|push|abs|max|min)\b")
CALL = re.compile(r"[A-Za-z_$][\w$]*\s*\(")
COMPARE = re.compile(r"===|!==|>=|<=|!=|==|[^!<>=-]>[^=]|[^<>=!]<(?![\w/!-])|\+(?=\s*\d)|\*(?=\s*\d)|/(?=\s*\d)")
TERNARY = re.compile(r"(?<![?.])\?(?![?.])")

INTERP = re.compile(r"\{\{(.*?)\}\}", re.S)
BIND = re.compile(r'(?:^|\s)(?::[\w.-]+|v-bind:[\w.-]+)="([^"]*)"')
COND = re.compile(r'(?:^|\s)(?:v-if|v-else-if|v-show)="([^"]*)"')
FOR = re.compile(r'(?:^|\s)v-for="\(?\s*[\w, ]+?\s+in\s+(.+?)"?\)?"')


def judge(expr: str, ordinal: bool = False) -> str | None:
    expr = expr.strip()
    if not expr:
        return None
    if ordinal and ORDINAL.match(expr):
        return None
    if expr.startswith("{") and expr.endswith("}"):
        # :class / :style 的对象映射本身是声明式的，但不许藏三元、比较或日期派生
        if TERNARY.search(expr) or DATE_MATH.search(expr) or COMPARE.search(expr):
            return "对象映射内含三元/比较/日期派生"
        return None
    if DATE_MATH.search(expr):
        return "日期或数学派生"
    if METHOD.search(expr):
        return "集合/字符串方法派生"
    if CALL.search(expr):
        return "模板内函数调用"
    if COMPARE.search(expr):
        return "比较或算术"
    if TERNARY.search(expr):
        return "三元分支"
    return None


def scan(text: str):
    match = re.search(r"<template>(.*)</template>", text, re.S)
    if not match:
        return []
    head = text[: match.start()].count("\n")
    out = []
    for offset, line in enumerate(match.group(1).split("\n"), 1):
        for expr in INTERP.findall(line):
            reason = judge(expr, ordinal=True)
            if reason:
                out.append((head + offset, "插值", reason, expr.strip()[:88]))
        for kind, pattern in (("绑定", BIND), ("条件", COND)):
            for expr in pattern.findall(line):
                reason = judge(expr)
                if reason:
                    out.append((head + offset, kind, reason, expr.strip()[:88]))
        for expr in FOR.findall(line):
            reason = judge(expr)
            if reason:
                out.append((head + offset, "v-for 来源", reason, expr.strip()[:88]))
    return out


def main(root: pathlib.Path) -> int:
    total = 0
    touched = 0
    files = [root] if root.suffix == ".vue" else sorted(root.rglob("*.vue"))
    for path in files:
        findings = scan(path.read_text())
        if not findings:
            continue
        touched += 1
        total += len(findings)
        print(f"\n== {path}")
        for line, kind, reason, expr in findings:
            print(f"   L{line} {kind}: {reason} :: {expr}")
    print(f"\nRESULT files_with_derivation={touched} sites={total}")
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main(pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "apps/web/src")))
