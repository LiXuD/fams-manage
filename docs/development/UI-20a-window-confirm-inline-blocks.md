# UI-20a 盘点与目标变更 11 处 `window.confirm` 收口为页内两步确认块（第 20 批 · 上）

基线分支 `codex/d6-20-factory-ui`（`ca54dce`，即 UI-19 提交），实施分支同。承接 [UI-19 资产域枚举文案与页内两步确认](UI-19-asset-enum-copy-and-inline-confirm.md)。

本批是**交互批**，不是模板迁移批：UI-19 收口了资产域的 5 处 `window.prompt`，本批收口盘点域与目标变更域的 11 处 `window.confirm`（6 个文件），收口后 `apps/web/src` 生产代码内 `window.confirm` = **0**（仅剩 2 个越界测试文件里的 3 条 `expect(window.confirm).not.toHaveBeenCalled()` 断言，它们对应的面板本来就没有对话框）。UI-20 的下半批（残余枚举串中文化）见「下一批」。

11 处对话框按语义拆成 **17 个确认块**（同一函数服务多种动作时逐种拆开，例如任务详情的 `action('start'|'cancel'|'terminate'|'delete')` 一个对话框对应四个动作）。CSS 层零改动：构建 CSS 仍 `31.42 kB / gzip 5.65 kB`，与 UI-12 以来逐字节一致。

## 范围

| 文件 | 行数 | 变化 |
|---|---|---|
| `views/InventoryTaskDetailView.vue` | 199 → 234 | 删 4 处 `window.confirm`（`publish` / `copyRange` / `action` / `assign` 各 1），改为 `Confirm = publish\|copy\|start\|cancel\|terminate\|delete\|assign` 七态 + 单个确认块；`openConfirm` 复刻原对话框前的全部前置校验，`runConfirm` 只负责提交 |
| `views/InventoryReviewPanel.vue` | 171 → 190 | 删 1 处，改为 `confirmOpen` + `confirmCount`；采信项数在**打开确认块时**定格（`items.length` 不再在提交瞬间重算），幂等键 `crypto.randomUUID()` 在打开时铸造一次 |
| `views/InventorySelectionPanel.vue` | 128 → 145 | 删 1 处，改为 `stage` + `confirmOpen`；载荷在打开时快照进 `stage`，确认后才提升为 `pending` |
| `views/InventoryEvidenceDecisionPanel.vue` | 156 → 172 | 删 1 处（该处按 `acceptance` 分支生成 4 种不同提示），改为 `confirmKind = decision\|acceptance` + `confirmHint` ref；`closeConfirm()` 同时清 `stage`，避免残留已铸造的幂等键 |
| `views/ItemTargetChangeView.vue` | 145 → 180 | 删 2 处（提请 / 决定），改为 `Confirm = propose\|APPROVED\|REJECTED`；提请块与决定块分处 `<form>` 与决定 `fieldset`，各自独立 `v-if` |
| `views/TransferTargetChangeView.vue` | 162 → 197 | 同上（调拨口径）。因 `captureTarget()` 既校验又构造快照，在 `openConfirm`（作为门）与 `runConfirm`（构造载荷）各调一次 |
| 6 个同名 `.test.ts` | +15 / −0 | 见「测试口径变化」 |

## 文案清单（逐字，供复核）

主按钮文案 = 该确认块 `role="group"` 的 `aria-label`，次按钮统一为仓库既有的**返回修改**。触发按钮文案逐字未动。提示语全部由**原 `window.confirm` 消息逐字**或「确认 + 原动词」构成，未自造新句；下表「动态」表示含插值。

| 位置 | 主按钮 | 提示语 |
|---|---|---|
| 任务详情 · 发布 | 确认发布并冻结集合 | `发布并冻结 {n} 项应盘集合？后续资产流转不会改写快照。`（动态，原消息逐字） |
| 任务详情 · 复制范围 | 确认复制范围创建新草稿 | 复制此范围创建新草稿？原任务和快照保留。（原消息逐字） |
| 任务详情 · 手动开始 | 确认开始盘点 | 确认开始盘点？（原消息逐字） |
| 任务详情 · 取消 | 确认取消无结果任务 | 确认取消无结果任务？（原消息逐字） |
| 任务详情 · 终止 | 确认终止并保留证据 | 确认终止并保留证据？（原消息逐字） |
| 任务详情 · 删除草稿 | 确认删除草稿 | 确认删除草稿？（原消息逐字） |
| 任务详情 · 分配 | 确认分配所选资产 | 确认分配所选资产？原执行人的历史授权和证据保留。（原消息逐字） |
| 复核面板 · 正常采信 | 确认采信所选正常观察 | `采信所选 {n} 项正常观察？存在差异或冲突的项目不会自动采信。`（动态，原消息逐字） |
| 选择面板 · 待采信选择 | 确认保存待采信选择 | 保存这条证据及核实依据作为待采信选择？独立差异仍需处理，不会增加已采信数量。（原消息逐字） |
| 处置面板 · 共同处置 | 确认共同提交证据处置 | 三分支原消息逐字：`共同核实所选证据的 {n} 个异常维度？位置、责任和状况等仍须独立闭环。` / `以明确反证拒绝证据 {id}，并对所列 {n} 个维度共同记录决定？已有正式关联不得覆盖。` / `将证据 {id} 保留为补充线索？位置、责任、状况和期间流转不会因此关闭。` |
| 处置面板 · 最终采信 | 确认最终采信所选证据 | 最终采信所选证据？服务端将重新核对全部本轮证据、独立差异、当前流转和原始离线期限。（原消息逐字） |
| 领用目标变更 · 提请 | 确认提交目标变更 | 提交目标变更供管理员确认？批准前仍以原目标为准。（原消息逐字） |
| 领用目标变更 · 批准 | 确认批准目标变更 | 确认批准此目标变更？责任仍在实际接收后转移。（原消息逐字） |
| 领用目标变更 · 驳回 | 确认驳回目标变更 | 确认驳回此目标变更？（原消息逐字） |
| 调拨目标变更 · 提请 | 确认提交调拨目标变更 | 提请调拨目标变更？已交出实物和原责任保持，批准后新目标仍需按原业务实际接收。（原消息逐字） |
| 调拨目标变更 · 批准 | 确认批准调拨目标变更 | 批准本次新目标？这不会完成接收或解除实物锁。（原消息逐字） |
| 调拨目标变更 · 驳回 | 确认驳回调拨目标变更 | 驳回此目标变更？（原消息逐字） |

除上表外无新增用户可见文字；被删除的只有 `window.confirm(...)` 调用本身。

## 脚本口径变化

本批与 UI-01～UI-19 的模板迁移批**口径不同**：`<script setup>` 按设计必须改动（对话框只能在那里收口），因此 `fams-ui-batch-migration` 的 `audit.mjs script-hash` 对本批 6 个文件必然 MISMATCH，本批不把它当作门禁，改为逐条人工核对：

- 原对话框前的**全部**前置条件搬进 `openConfirm`（如 `!preview?.count`、`!selectedItems.length || !executor`、`reason.trim().length < 3`、`evidence.trim().length < 10`、`review_status !== "SELECTED"`），条件不满足时不弹块，与原行为一致；
- `runConfirm` / `runAccept` / `runSave` 只允许 `busy` 自锁守卫（不能守 `locked`，否则打开块时写入的 `pending` 会把确认按钮永久锁死）；
- 幂等键 `crypto.randomUUID()` 每块只铸造一次（打开时），失败后关闭块走「重试原操作」重放，与原 `window.confirm` 只在确认后才发请求的语义等价；
- 三个面板的 `clear()` / `closeConfirm()` 一并重置 `stage` 与块开关，避免残留已铸造但未使用的键。

`audit.mjs buttons`（只比静态按钮文案）在本批 6 个文件上仅报「返回修改」为新增静态文案，与原按钮文案序列完全兼容；17 个主按钮文案走 `{{ confirmLabels[...] }}` 插值，不在该审计的比对范围内，已改为逐行读 diff 核对（见上表）。`audit.mjs bindings` 的「丢失」项全部是本次设计内的处理器改写（`action('cancel')` → `openConfirm('cancel')`、`publish` → `openConfirm('publish')`、`accept`/`save`/`send(true|false)`/`decide(...)`/`propose` 同理），无 `v-if` / `v-for` / `v-model` 丢失。

## 测试口径变化

6 个测试文件共 **+15 行 / −0 行**，全部是在原驱动序列中间插入一步「点主按钮 → 断言提示语 → 点确认按钮」，**未修改、未删除任何既有断言**：

- `InventoryTaskDetailView.test.ts` +3、`InventoryReviewPanel.test.ts` +3、`InventoryEvidenceDecisionPanel.test.ts` +3：追加确认块点击与提示语断言；
- `InventorySelectionPanel.test.ts` +2、`ItemTargetChangeView.test.ts` +2、`TransferTargetChangeView.test.ts` +2：同上。

三个面板的测试里原有的 `vi.spyOn(window, "confirm")` 桩件保留未删（它现在只用于证明「没有被调用」，属无害冗余）。

## 验证

四道门禁（`apps/web`，本轮最后一次源码改动后复跑）：

- `rtk npx vue-tsc --noEmit` → exit 0
- `rtk npx eslint . --max-warnings 0`（仓库根）→ `ESLint: No issues found`；`.vue` 不在根 `eslint.config.mjs` 的 `sourceFiles`（只覆盖 `*.{ts,tsx}`）内，属既有盲区，非本批引入
- `rtk npx vitest run` → `Test Files 64 passed (64)` / `Tests 263 passed (263)`（与 UI-19 同数，未增删用例）
- `rtk npx vite build` → 构建成功；CSS `31.42 kB / gzip 5.65 kB`（逐字节同 UI-19），JS `651.69 kB / gzip 173.50 kB`（UI-19 为 `645.96 / 172.37`，增量来自 17 个确认块与字典常量内联）

静态样张（dev server 5173 + `/tmp/fams-stub-api.mjs` 8080，**非真实后端端到端**）：`/tmp/fams-ui20-probe.mjs` 用原生 CDP 逐个打开 17 个确认块并截图，`Page.javascriptDialogOpening` 全程 **`[]`**（运行期确实没有再出现任何浏览器对话框）。

- 页头契约：1440×900 全部 17 帧 `chromeBottom=64 headTop=80 h1Top=106 gapChromeToTitle=42 visibleH1=1 legacy=0 overlap=0 overflowNotTable=0`；390×844 两帧 `headTop=124 h1Top=150 gapChromeToTitle=86`，其余同上。`legacy` 按 classList 逐词判定，避免 `fams-panel-title` 被前缀正则误命中。
- 确认块契约：17 帧 `gapTriggerToGroup=16`（块顶到上一兄弟底边）；1440 下按钮高 40、宽 90–188，390 下两按钮均为高 44、宽 232 / 258（满宽堆叠，符合 UI-10c 触控密度口径）。
- 截图 19 张：`/tmp/fams-ui20-{publish,copy,delete,start,cancel,terminate,assign,review-accept,selection-save,evidence-decision,final-acceptance,item-propose,item-approve,item-reject,transfer-propose,transfer-approve,transfer-reject}-1440.png` + `/tmp/fams-ui20-{review-accept,item-approve}-390.png`。
- 目视复核：确认块渲染为左侧强调条 + 提示语 + 主/次按钮，1440 横排、390 满宽堆叠；`采信所选 1 项正常观察`、`以明确反证拒绝证据 4401…` 等动态插值在样张中按桩件数据正确取数。
- 为让样张能走到每个块，`/tmp/fams-stub-api.mjs`（非产品代码）补了 `…/observations`、`…/selection`、`…/evidence-subjects`、`…/evidence-dispositions` 四个端点与 `invObservations` / `reviewSelection` fixture，并把 `reviewItem(invItems[0], { canAcceptNormal: true })` 打开。

## 已知限制

1. 验证口径是单测 + 构建 + 静态样张，**不是**真实后端端到端验收；确认块后的实际提交只在单元测试里以 fetch 桩验证，未在浏览器里对真实服务验证。
2. 样张里「未盘原因与待复核 / 封存检查与报告 / 执行人同步声明」三块渲染为红色错误提示条，是 stub 未提供对应端点的兜底文案，属数据缺口，不是本批缺陷；这三块本批未改动。
3. 「手动开始」块需要任务处于 `READY`，样张里是先真实点了一次「确认发布并冻结集合」让桩件把草稿推进为 `READY` 才截到的；桩件状态是进程内的，重跑样张前需重启 `/tmp/fams-stub-api.mjs`。
4. `audit.mjs script-hash` 对本批不适用（见「脚本口径变化」），因此本批的「脚本零改动」证明强度低于模板迁移批，改由逐条人工核对 + 263 条用例兜底。
5. 本会话 GitNexus MCP 未挂载，AGENTS.md 要求的 `impact()` / `detect_changes()` 未能执行，改动影响面以人工阅读 diff 与测试结果为准。
6. 工作树含未跟踪的 `.qoder/`（本地 repowiki 资产，约 3.1 MB），不纳入提交；本批 12 个改动文件尚未提交，也未推送（`43a89f5` / `3a431a2` / `ca54dce` 三个前序提交同样仍在本地）。

## 下一批

**UI-20b 残余枚举串中文化**（同批口径：只改文案与字典，不动 CSS）。已核完权威值来源（`contracts/fams-core-v1.yaml` + `backend/src/main/resources/db/migration/d6_04/` 的 CHECK 约束），待改点位：

- 入库：`receipt_status`（7 值，复用 `requisitionLabels` 口径）、`decision.result`、`item.executionStatus`
- 借用提醒 `reminderType`（5 值）、导入 `review.status`（3 值）与 `rollback.status`（6 值）、返厂 `inspectionResult`（3 值）
- 维修状态复用既有 `repairStates`；附件 `scanStatus` 复用 `AttachmentPanel` 的 `scanText`
- 代确认 `collectionMethod` / `evidenceKind`（V29）、迟到损坏 `recheckResult`（复用 `InventoryLatePanel` 的 `results` 口径）、调拨台账行内 `lifecycleStatus` 复用 `lifecycleLabels`
- `InventoryTaskDraftView` 异常选项 遗失 → **已遗失**（PRD 口径），并把 `exceptionLabels` 提升到 `asset/api.ts`
- 明确**不做**：`treatmentResult`、`row.method`、`d.purpose` 等 `VARCHAR(2000)` 自由文本列（非枚举，字典化会吞掉真实数据）
