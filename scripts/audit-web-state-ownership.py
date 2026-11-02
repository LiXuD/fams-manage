#!/usr/bin/env python3
"""ARCH-09 状态所有权与异步生命周期审计：把 vue-best-practices 的硬要求变成可执行判据。

判定五类：
  A primitive 状态必须用 shallowRef（技能 reactivity 的第一条 task item）。
  B DOM 与子组件引用必须用 useTemplateRef（技能 sfc，Vue 3.5+）。
  C watcher 里的异步副作用必须可取消或做迟到抑制（技能 reactivity「Clean up async
    effects for watchers」）：watch 回调中出现 await 却没有 onCleanup / isCurrent 守卫判违规。
  D 迟到抑制不得各页自造（技能 composables「Extract a Stateful Responsibility」与
    「Keep ... cleanup with its owning UI or composable」）：组件里出现
    let generation/epoch/live/turn 这类自持在途计数，或存在 await 之后写 X.value
    却完全没有 useRequestLiveness / isCurrent 守卫，判违规。
  E 读取生命周期清单：自持 busy+error 三件套却不用 useResourceLoad / usePagedResourceList
    时列出，供人工判断形状是否真的相同。本项目多数视图把读取与写流程共用同一个互斥位，
    形状不同就不必硬套，因此 E 只作清单不判失败。

例外：composables、Pinia store 与非 SFC 的 .ts 模块不受 C/D 约束——它们的生命周期由应用拥有，
不随组件卸载。

用法：python3 scripts/audit-web-state-ownership.py [扫描目录]
退出码 0 表示 A～D 全部清零。
"""
import pathlib
import re
import sys

PRIMITIVE_REF = re.compile(
    r"const \w+(?::\s*(?:shallow)?Ref<[^>]*>|:\s*(?:string|number|boolean)[!?|][^=]*)?\s*=\s*ref\("
    r"(?:\"[^\"]*\"|''|\"\"|0\b|1\b|-1\b|true\b|false\b|null\b)")
DOM_REF = re.compile(r"const \w+\s*=\s*ref<\s*(?:HTML\w+|InstanceType<)[^>]*>\s*\(\s*null\s*\)")
WATCH = re.compile(r"\bwatch\s*\(")
CLEAN_GUARD = re.compile(r"onCleanup|isCurrent|useRequestLiveness")
HANDROLLED = re.compile(r"let (?:generation|epoch|live|turn)\s*=\s*(?:0|true)\b")
LIVENESS = re.compile(r"useRequestLiveness|isCurrent\(|\bcurrent\(")
WRITE = re.compile(r"\w+(?:\.\w+)*\.value\s*=[^=]")
BUSY_ERROR = re.compile(
    r"(?:const|let) (?:busy|loading|saving)\s*=\s*(?:shallow)?Ref\(false\)"
    r"[\s\S]{0,400}?(?:const|let) error\s*=\s*(?:shallow)?Ref\(\"\"\)")


def block_of(text: str, start: int) -> str:
    depth, index = 1, start
    while index < len(text) and depth:
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1
        index += 1
    return text[start:index]


def scan(path: pathlib.Path):
    text = path.read_text()
    name = str(path)
    stateless = "composables" in name or "sessionStore" in name or path.suffix == ".ts"
    findings = []
    for m in PRIMITIVE_REF.finditer(text):
        findings.append(("A primitive 应用 shallowRef", m.group(0).strip()[:80]))
    for m in DOM_REF.finditer(text):
        findings.append(("B DOM 引用应用 useTemplateRef", m.group(0).strip()[:80]))
    if not stateless:
        for m in WATCH.finditer(text):
            block = block_of(text, m.end())
            if "await" in block and not CLEAN_GUARD.search(block):
                findings.append(("C watcher 异步无 onCleanup/迟到抑制", block.strip()[:70]))
        if HANDROLLED.search(text) and "useRequestLiveness" not in text:
            findings.append(("D 自造在途计数未用 useRequestLiveness", HANDROLLED.search(text).group(0)))
        elif not LIVENESS.search(text):
            for m in re.finditer(r"\basync function (load\w*|refresh|open|read\w*|inspect|list|search|preview\w*|catalog|paginate|choose\w*|fetchState)\s*\(", text):
                body = block_of(text, m.end())
                if "await" in body and WRITE.search(body):
                    findings.append(("D 读取路径无迟到抑制", f"async function {m.group(1)}()"))
                    break
        if BUSY_ERROR.search(text) and not re.search(r"use(ResourceLoad|PagedResourceList)", text):
            findings.append(("E 自持读取生命周期未采纳 composable", "busy+error 三件套"))
    return findings


def main(root: pathlib.Path) -> int:
    hard = soft = 0
    paths = [root] if root.suffix == ".vue" else sorted(list(root.rglob("*.vue")) + list(root.rglob("*.ts")))
    for path in paths:
        if ".test." in str(path):
            continue
        findings = scan(path)
        if not findings:
            continue
        print(f"\n== {path}")
        for kind, detail in findings:
            print(f"   {kind} :: {detail}")
            if kind.startswith("E"):
                soft += 1
            else:
                hard += 1
    print(f"\nRESULT hard_violations={hard} lifecycle_shape_review={soft}")
    return 1 if hard else 0


if __name__ == "__main__":
    sys.exit(main(pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "apps/web/src")))
