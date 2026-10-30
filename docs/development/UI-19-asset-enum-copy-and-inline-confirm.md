# UI-19 资产域枚举文案与页内两步确认（第 19 批）

基线分支 `codex/d6-20-factory-ui`（`3a431a2`，即 UI-18 提交），实施分支同。承接 [UI-18 资产台账列表/建卡/详情拆页](UI-18-asset-ledger-list-detail-split.md)。

本批不是模板迁移批，而是**文案与交互批**：用户在 UI-18 复核时选定「本批照搬 prompt，另开一批改」，本批即那一批。范围由用户四项裁决锁定：① 五处 `window.prompt` 全部改成**页内两步确认块**（资产 3 + 入库 2，收口后全仓 `window.prompt` = 0）；② 生命周期/建卡阶段/实物去向字典**提升到 `asset/api.ts` 共用**；③ 建卡阶段 `ACTIVE` 中文初选**在用**（用户否决了我推荐的「激活」），复核时追问两列「在用」的语义差别，我给出契约与后端状态机证据后用户改判为**已生效**（`PENDING_INBOUND` 同步改**待验收入库**，避免与生命周期「待入库」再撞一次）；④ 五处一起做，不分批。

CSS 层本批零改动：构建 CSS 仍 `31.42 kB / gzip 5.65 kB`，与 UI-12 以来逐字节一致。

## 范围

| 文件 | 变化 |
|---|---|
| `asset/api.ts`（105 → 112 行） | 新增 `lifecycleLabels`（8 值）、`cardStageLabels`（4 值）、`destinationLabels`（2 值）三张只读字典，作为资产域中文文案唯一来源 |
| `views/AssetDetailView.vue`（137 → 179 行） | 删除 3 处 `window.prompt`，改为 `pending: "notes"\|"copy"\|"cancel"` 状态机 + 一个 `fams-card fams-stack` 确认块；副标题与实物去向改用字典 |
| `views/InboundDetailView.vue`（112 → 159 行） | 删除 2 处 `window.prompt`，拆为 `decision: "cancel"\|"reject"` 与 `accepting: InboundItem\|null` 两个确认块；`act()` 收窄为 `submit\|approve\|claim` |
| `views/AssetLedgerView.vue`（96 → 96 行） | 生命周期列与建卡阶段列的 `{{ asset.lifecycle }}` / `{{ asset.cardStage }}` 改为字典查表，删掉原始枚举串 |
| `views/InventoryTaskDraftView.vue`（103 → 103 行） | 删本地 7 键 `lifecycleLabels`，改 `import { lifecycleLabels } from "@/asset/api"`；生命周期筛选因此**补上缺失的「待入库」**（7 → 8 值） |
| `views/AssetDetailView.test.ts`（161 → 184 行） | 见「测试口径变化」 |
| `views/InboundDetailView.test.ts`（56 → 91 行） | 追加 2 条确认块用例 |

## 文案清单（逐字，供复核）

字典值全部来自 PRD V1.5 §8.1/§8.2 与 `contracts/fams-core-v1.yaml`、`V4__create_asset_fact.sql` 的 CHECK 值，无自造词：

- `lifecycleLabels`：待入库 / 在库 / 在用 / 借出 / 维修中 / 返厂维修 / 待报废 / 已报废
- `cardStageLabels`：草稿 / 待验收入库（`PENDING_INBOUND`） / **已生效**（`ACTIVE`） / 已取消
- `destinationLabels`：已交出待接收（`DELIVERED_WAITING_RECEIPT`） / 待收回取消（`RECOVERY_PENDING_CANCEL`）

确认块内新增的用户可见文字，全部由**原 prompt 标题**与**页内既有动词**拼装，未发明新词：

| 位置 | 字段标签（原 prompt 标题逐字） | 主按钮（= `role="group"` 的 `aria-label`） | 次按钮 |
|---|---|---|---|
| 资产详情 · 修正备注 | 备注修正 | 确认修正备注 | 返回修改 |
| 资产详情 · 复制建卡 | 复制建卡数量（1–100） | 确认复制建卡 | 返回修改 |
| 资产详情 · 取消草稿 | 取消原因 | 确认取消草稿 | 返回修改 |
| 入库详情 · 取消整单 | 取消原因 | 确认取消 | 返回修改 |
| 入库详情 · 驳回 | 驳回原因 | 确认驳回 | 返回修改 |
| 入库详情 · 验收入库 | `核对实物编码：{资产编码}` | 确认验收入库 | 返回修改 |

「返回修改」复用仓库既有 18 处两步确认块的次按钮文案。触发按钮文案（复制建卡 / 修正备注 / 取消草稿 / 取消 / 驳回 / 验收入库）逐字未动。

## 交互口径

- 确认块用 `<div v-if="pending" class="fams-card fams-stack" role="group" :aria-label="…">`，与 UI-10d 之后仓库既有的 18 处两步确认块同构；焦点层由 `fams-card` 的既有 CSS 提供，**全仓无程序化焦点管理**，本批也不新增。
- 打开确认块时 `locked = busy || 有块打开`，四个动作按钮与行内「验收入库」按钮 `:disabled="locked"`，防止同时开两个块。
- 校验保留在原位置：复制建卡仍要求 `Number.isInteger && 1–100`，取消/驳回/验收仍要求非空（`trim()`），**校验失败时零请求**且确认块不关闭。
- 提交失败时确认块与输入内容保留（`pending`/`decision`/`accepting` 只在成功路径置 `null`），比原 prompt 关掉即丢内容更可用。
- 确认块的守卫用 `busy.value` 而不是 `locked.value`——块打开时 `locked` 恒真，用 `locked` 会自锁（首版即因此 6 条用例 0 次调用，已修正并在单测里固定住）。

## 脚本口径变化（本批不适用「脚本逐字节不变」）

UI-19 改的正是 `<script setup>` 的交互逻辑，`audit.mjs script-hash` 如实报 4 个 DIFF，逐文件归因：

- `AssetDetailView.vue`：`+computed`、`+type Action`、`+pending/notes/quantity/reason/locked/fieldLabels/confirmLabels`、`correctNotes/copyCurrent/cancelCurrent` 去掉 prompt 改收参数、`+openAction/confirm`。
- `InboundDetailView.vue`：`+computed`、`+type Decision`、`+decision/reason/accepting/observed/locked/decisionLabels/reasonLabels`、`act()` 收窄、`accept()` 拆成 `openAccept/confirmAccept`、新增 `openDecision/confirmDecision`。
- `AssetLedgerView.vue`：仅 import 增加两个字典（3 行模板改查表）。
- `InventoryTaskDraftView.vue`：仅 import 增加 `lifecycleLabels`，删本地同名字典。

`audit.mjs buttons`：4 个文件**零文案丢失**；新增仅「返回修改」「确认验收入库」。注意该审计看不到 `{{ confirmLabels[pending] }}`、`{{ decisionLabels[decision] }}` 这类动态文案（正则只读模板字面量），这部分由单测的 `labels()` 多重集断言覆盖，已在下节列出。

## 测试口径变化

- `AssetDetailView.test.ts`：删除全部 `window.prompt` 桩件（原本 `vi.stubGlobal`），改为点触发按钮 → 在 `div[role="group"] input` 填值 → 点确认按钮；新增「空备注仍是有效修正」（原 prompt 返回 `""` 会提交，块版同样提交，口径保持）与文案多重集断言 `["复制建卡","修正备注","打印标签","确认修正备注","返回修改"]`；断言副标题 `FA-2026-000101 · 已生效 · 版本 7`（改判后从 `在用` 同步）、`实物去向：四楼机房 · 已交出待接收；…`、`not.toContain("ACTIVE")`；测试桩件的 `exception`/`status` 修正为契约合法值 `NORMAL` / `DELIVERED_WAITING_RECEIPT`。11 条用例。
- `InboundDetailView.test.ts`：追加 2 条——「取消先在页内确认块收原因，缺依据时零请求」断言 `cancelReceipt` 恰好一次且参数 `(draft, "重复单")`；「验收入库先在页内确认块核对实物编码」断言字段标签文字 `核对实物编码：FA-2026-000101` 与 `acceptItem(receipt, item, 3, "FA-2026-000101")`。
- 其余测试文件一行未改。

## 验证

- `npx vue-tsc --noEmit`：0 错误。
- `npx eslint . --max-warnings 0`（仓库根）：No issues found。`.vue` 不在 `sourceFiles` 覆盖面内，属既有盲区。
- `npx vitest run`：**64 个测试文件 / 263 条用例**全部通过（UI-17 基线 61/242）。
- `npx vite build`：成功，CSS `31.42 kB`（gzip `5.65 kB`）与 UI-12 起持平；JS `645.95 kB`（gzip `172.35 kB`）。
- 原生弹窗清零：`grep -rn "window\.\(prompt\|alert\)" apps/web/src/{views,components}` → 无命中；`window.confirm` 仍有 6 文件 11 处（见已知限制）。
- CDP 量测（`/tmp/fams-ui19-probe.mjs`，输出 `/tmp/fams-ui19-probe.txt`）：23 份口径报告；`/assets`、`/assets/101`、`/assets/105`、`/assets/102`、`/assets/108`、`/inbound/9001`、`/inbound/9002`、`/inbound/9004`、`/inventory-tasks/new` 在 1440×900 下 `chromeBottom=64 / headTop=80 / h1Top=106 / gapChromeToTitle=42`，735×872 与 390×844 下 `headTop=124 / h1Top=150 / gapChromeToTitle=86`；`visibleH1` 恒 1、`legacy` 恒 0、`overlap` 恒 0、`overflowNotTable` 恒 0。
- 弹窗侦听（`Page.javascriptDialogOpening` 只做计数，不应答）：`dialogs(应为空): []`——五处原 prompt 流程全程零原生对话框。
- 确认块量测：输入框 `inputH=44`（三档视口一致，满足 44px 触控档）；块宽 `w=1136@1440` / `653@735` / `324@390`；块高 `h=180`（@1440/735）与 `220`（@390，标签换行）；`focusWithin:false`（见已知限制）。
- 数据样张：22 张整页 PNG（`/tmp/fams-ui19-*.png`），覆盖台账列表、三种确认块开合、备注更新、复制非法值、取消空原因、返回修改、借出实物去向、待入库阶段、入库三种状态动作，以及 390/735 窄屏。逐张目视复核确认块渲染（左侧强调条、三个动作按钮置灰、全宽 44px 输入、主/次按钮并排）。这 22 张摄于改判之前，其中台账列表与资产详情的中文列显示的是旧文案（在用/待入库），改判后以 UI-19b 的 5 张为准。
- 建卡阶段改词后的复测（`/tmp/fams-ui19b-probe.mjs`，输出 + 5 张 `/tmp/fams-ui19b-*.png`）：`/assets` 首行两列现为「在用 | 已生效」，`/assets/108` 副标题 `FA-2026-000208 · 待验收入库 · 版本 2`，`/assets/105` 为「已取消」，1440 与 390 两档 `gapChromeToTitle` 仍 42 / 86，`overlap` 与 `overflowNotTable` 恒 0，`dialogs` 恒 `[]`；改后重跑四道门禁：vue-tsc 0 错误、eslint 无告警、vitest 64/263、CSS 仍 `31.42 kB`（gzip `5.65 kB`），JS 因多一个字从 `645.95` 到 `645.96 kB`（gzip `172.37`）。复测中另发现 stub 的 `POST /assets/{id}/copy` 处理器仍返回非法值（`lifecycle: "DRAFT"`、`exception: "NONE"`），导致台账复制行的生命周期列查不到字典而渲染为空——已改回契约值 `WAITING_INBOUND` / `NORMAL` 并重启 stub 重取全部 5 张；这是桩件缺陷（产品代码未动），但它暴露了一个真实口径：**字典无 `?? 原值` 兜底，遇到超出契约的值会显示空白而不是原始码**，生产侧由 `ck_asset_lifecycle` 等 CHECK 约束保证不会发生。
- 复测脚本的一次假读数（不是产品缺陷）：UI-19b 临时探针里的 `legacy` 计数用手写正则 `(^|[\s-])(asset-|panel-title|breadcrumb|form-error)`，连字符不敏感，会把 `fams-panel-title` 误判成旧类，报 `legacy=1/2`；改用本 Skill `audit.mjs legacy` 复核，`asset- / panel-title / breadcrumb / form-error` 全部「无使用者」，真实值仍是 0。教训同 UI-01：旧类归属判定只能用 `audit.mjs`，不要手写临时正则。
- 临时 stub API（`/tmp/fams-stub-api.mjs`，非产品代码）修正：资产 fixture 的 13 处 `lifecycle: "DRAFT"`、非法 `exception`、非法 `status: "DELIVERED"` 全部换成契约值（102 → `BORROWED` + `DELIVERED_WAITING_RECEIPT`，103 → `IN_STOCK`，104 → `UNDER_REPAIR`，106 → `SCRAPPED` + `cardStage: ACTIVE`，108 → `WAITING_INBOUND` + `PENDING_INBOUND`，131 → `cardStage: CANCELLED`）；新增 `9004`（`PENDING_ADMIN_DECISION`）与 `cancel`/`decision`/`claim`/`items/{id}/accept` 四个有状态端点，中文列与确认块才能拿真实数据目视复核。

## 已知限制

1. **「在用/在用」与「待入库/待入库」列撞词——本批复核中已消歧**：`ACTIVE` 改「已生效」、`PENDING_INBOUND` 改「待验收入库」后，台账两列不再同词。语义根据：`cardStage` 是「卡片是否已正式入账」的准入门槛（`ACTIVE` 只在入库验收时由 `backend/src/main/java/com/fams/inbound/InboundService.java:327` 与 `lifecycle_status='IN_STOCK'` 同时写入，之后调拨/报修/归还/领用/盘点快照的 SQL 全部硬性带 `card_stage='ACTIVE'`），`lifecycle` 是实物此刻在哪一站；两者是一对多，只有已生效的卡才可能进入「在用」。此项**已解决**，不再是遗留。
2. **确认块不移动焦点**：三档视口 `focusWithin:false`。与仓库既有 18 处确认块一致（全仓无程序化焦点管理），键盘用户需自行 Tab；焦点层样式（UI-10d）仍在块内生效。
3. **「遗失」与 PRD「已遗失」不一致**：`views/InventoryTaskDraftView.vue:90` 的异常状态 `<option value="LOST">遗失</option>` 是散落的字面量，本批未动（不在五处 prompt 与三张字典的批准范围内）。`exceptionLabels` 因此**暂未提升**——当前仅此一个使用者，先建字典无消费者。
4. 仍有原始枚举串直接渲染（本批批准范围外）：`InboundDetailView.vue:113` 的 `selected.status`、`:117` 的 `selected.decision.result`、`:149` 的 `item.executionStatus`；`InboundView.vue:39`；`LoanDetailView.vue:97` 的 `reminder.reminderType`；`AssetImportView.vue:251/256` 的 `review.status`/`rollback.status`；`AssetReturnDetailView.vue:136` 的 `inspectionResult`；`InventoryDifferencePanel.vue:244` 与 `InventoryLateDamagePanel.vue:129` 的 `linkedRepair.status`；`RepairFactoryPanel.vue:269` 的 `treatmentResult`。
5. **`window.confirm` 未处理**：6 文件 11 处（`InventoryTaskDetailView.vue` 4、`ItemTargetChangeView.vue` 2、`TransferTargetChangeView.vue` 2、`InventoryEvidenceDecisionPanel.vue`/`InventoryReviewPanel.vue`/`InventorySelectionPanel.vue` 各 1）。本批批准口径是 prompt，confirm 的文案较长且含数字插值，改造工作量与风险都更大，留 UI-20。
6. `audit.mjs buttons` 读不到动态按钮文案（`{{ confirmLabels[pending] }}`），该部分只有单测覆盖，非脚本证明。
7. 根 `eslint.config.mjs` 的 `sourceFiles` 只覆盖 `*.{ts,tsx}`，`.vue` 恒判 "File ignored"；`eslint .` 通过不代表模板经过 lint（既有缺口）。
8. 未启动真实后端与 compose 链路；验证口径是单测 + 构建 + stub 静态样张，**不构成端到端验收**。
9. 本批改动**未 commit**，等待用户复核。`.qoder/`（本地 repowiki 工具产物，约 3.1 MB）保持未跟踪、不入库。

## 下一批（UI-20 候选）

1. `window.confirm` 11 处 → 页内两步确认块（沿用本批 `fams-card fams-stack` + `locked` + `busy` 守卫口径），收口后原生对话框全仓为 0。
2. 限制 4 列出的原始枚举串补字典：入库单状态、行执行状态、审批结果、提醒类型、导入核验/撤销状态、验收结果、返厂处理结果。
3. `exceptionLabels`（NORMAL/SEARCHING/LOST）随第 2 处使用者出现时提升，并把 `InventoryTaskDraftView.vue:90` 的「遗失」对齐 PRD「已遗失」。
4. ~~「在用/在用」列撞词需用户定词后处理。~~ 已在本批复核中解决（`ACTIVE` → 已生效，`PENDING_INBOUND` → 待验收入库）。
5. 老规矩：四道门禁 + 三口径量测 + 整页样张 + 本记录同结构。
