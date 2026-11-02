# ARCH-08 模板派生全部归位 script（架构批第 8 批）

基线：`ARCH-06/07` `bdb6eb0`（features 垂直切片 + 契约类型说明符）。判据来自 `vue-best-practices` 的两条要求：**模板不做计算**（`templates` 规则：filter/find/format/ternary/comparison 一律留在 `<script setup>`），以及**状态派生用 `computed`**。

## 落判据

新增 `scripts/audit-web-template-derivations.py`（97 行）。它扫 `<template>` 里的 `{{ }}` 插值、`:attr="…"` 绑定、`v-if/v-else-if/v-show` 条件、`v-for` 源，允许下列形式，其余判违规：

| 允许 | 理由 |
| --- | --- |
| 具名状态读取（`rows`、`busy.value` 之类） | 本来就是「读」 |
| 可选链 `a?.b` | 不是派生，是空值防护 |
| 字典索引 `labels[code]` | 状态→文案的表查，`<template>` 里读它不产生逻辑 |
| `!x` 与纯布尔组合 `a && b`、`a \|\| b`（单字段兜底） | 可读性优于再造一个 computed |
| `:class` / `:style` 的对象映射 | 位置样式，Vue 官方口径允许就地写 |
| `index + 1` 序号 | 展示用序号，不是业务派生 |

违规形式：三元（`(?<![?.])\?(?![?.])`，避开可选链）、比较/算术、函数调用（含 `new Date`）、集合与字符串方法（`.filter(`、`.map(`、`.toFixed(`、`.slice(`…）。脚本可传目录，也可传单只 `.vue` 路径用于定点复核。

## 实测指标

| 口径 | `bdb6eb0` | 本批后 |
| --- | --- | --- |
| 有模板派生的文件 | 60 | **0** |
| 派生点 | 453 | **0** |
| diff | — | 61 文件，+1474 / −570 |
| `apps/web/src` SFC 总数 | 65 | 65（未新增/删除文件） |

样板是 `features/inbound/panels/AttachmentPanel.vue`：`revisionText / supersededText / sizeText / scanTone / downloadable / correctable` 六个派生一次进 `rows` computed，模板只读行对象。

## 一处真实缺陷（不是风格问题）

审计里原本有一条 `\{\s*\.\.\w+,` 用于找「展开后紧跟字段」的复制式提交处理器——**正则漏了第三个点**（`...x` 需写成 `\.\.\.`），所以两次扫描都返回空、结论无意义。改成 `\{\s*\.\.\.\w+,` 后命中 20 处，其中 `InboundDetailView.vue` 是真问题：行对象带 UI 字段（`acceptable`）被整个 `{ ...row, … }` 当 payload 提交，`acceptable: true` 泄进 `acceptItem` 请求体。修法是把源对象与展示派生分离——`itemRows` 里 `{ ...source, source, acceptable }`，模板事件改传 `item.source`；由既有测试断言 payload 形状兜住。

## 偏差与更正

1. **子代理语义漂移一处**：`InventoryReviewPanel` 的 `reviewedAtText` 被改写为 `''` 兜底，而基线是 `new Date(x!).toLocaleString()`。本批主张「不改用户可见行为」，已还原为逐字节同口径。同类还原另有 1 处（同文件）。
2. 453 个派生点里，**未**为了「少一个 computed」而合并语义不同的派生；同一文件内形状相似但不变量不同的，保留各自实现。
3. 判据是正则而非 AST：`{{ }}` 内的注释、跨行表达式靠现有格式收敛保证覆盖；漏网形式若出现，属判据盲区，不是实现盲区。

## 验证

| 口径 | 结果 |
| --- | --- |
| `audit-web-template-derivations.py` | `files_with_derivation=0 sites=0`（基线同脚本跑 `bdb6eb0` 为 60/453） |
| `npm run lint`（含 `.vue`、`--max-warnings=0`） | exit 0 |
| `vue-tsc --noEmit` | 0 错误 |
| `vitest run` | 70 文件 / 308 用例全绿 |
| `vite build` | CSS 31.42 kB / gzip 5.65 kB；入口 `index` 54.89 kB / gzip 20.94；`AppChrome` 73.83 kB / gzip 29.15 |
| 真实浏览器 | ARCH-11 的 117/117 一致 |

## 已知限制

1. 允许清单是判断，不是证明：`labels[code]`、`:class` 对象映射这类仍留在模板，技能口径里也能写成 computed，本批刻意不换，以免用一层无意义的间接换掉可读性。
2. 本批不改运行时时序：迟到响应、幂等键簿记留给 ARCH-09/10。
3. 单测 + 构建 + 判据 + 本地真实浏览器，不替代远端 CI、正式 QA 与生产验收。

## 下一批

ARCH-09：把 49 个视图各自造的「迟到响应抑制」守卫抽成 `useRequestLiveness`，并用 `audit-web-state-ownership.py` 把 primitive 状态用 `shallowRef`、DOM 引用用 `useTemplateRef`、watcher 异步可取消三条硬要求变成判据。
