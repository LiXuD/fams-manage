# UI-21 残余英文兜底收口与逐字相同字典提升（第 21 批）

基线：`main` 已含 UI-20a `2bad591`、UI-20b `48e2892`；本批工作树改动 12 个文件（+31 / −27），全部在 `apps/web/src`。本批仍是**文案批**：不做模板迁移、不动 CSS 层，构建 CSS 仍为 `31.42 kB / gzip 5.65 kB`，与 UI-12 以来逐字节一致。

普查口径来自代码本身而非 UI-20b 的估算：生产代码里**回退到枚举原值（英文大写串）**的站点共 **11 处 / 4 个文件**，本批全部收口；另有 11 处形如 `|| '待核实'`、`|| '尚未列入复核'`、`|| '未指定位置'` 的**中文空值兜底**（6 个文件）不属泄漏，保持原样。

## 范围

### 一、字典提升（UI-21b）：只提升「2 个及以上使用者且文案逐字相同」的本地字典

| 文件 | 导出 | 值来源 |
| --- | --- | --- |
| `apps/web/src/inventory-review/api.ts:57-58` | `inventoryTaskStatusLabels`（8 键） | `V31 ck_inventory_task_status` |
| 同上 `:59-60` | `subjectStatusLabels`（4 键） | `V33 ck_inventory_subject_status` |
| `apps/web/src/requisition/api.ts:7-8` | `allocationStatusLabels`（5 键） | `V19 ck_req_alloc_status` |
| 同上 `:9-11` | `handoverEventLabels`（8 键） | `V30 ck_req_handover_event_type` |

| 使用者 | 原状 | 改后 |
| --- | --- | --- |
| `views/InventoryTaskView.vue:5`、`views/InventoryTaskDetailView.vue:12` | 各自 7 键本地 `labels`（逐字相同） | 导入 `inventoryTaskStatusLabels as labels`，删本地字典；模板表达式未改 |
| `views/InventoryDifferencePanel.vue:5`、`views/InventoryEvidenceDecisionPanel.vue:4` | 各自 4 键本地 `statuses`（逐字相同） | 导入 `subjectStatusLabels as statuses`，删本地字典 |
| `views/InventoryFindingPanel.vue:7` | 3 键本地 `states`（缺 `TRANSFERRED`） | 导入 `subjectStatusLabels as states`，键名不变故模板零改动 |
| `components/RequisitionReassignment.vue:5` | 5 键本地 `labels` | 导入 `allocationStatusLabels as labels`（5 键逐字相同） |
| `components/AssetReturnReassignment.vue:3` | 1 键本地 `labels` | 复用已有 `loanLabels as labels`（该键文案与 `loanLabels.WAREHOUSE_PROCESSING` 逐字相同） |
| `views/RequisitionDetailView.vue:7` | 9 键本地 `handoverLabels` 同时服务明细状态与事件类型 | 拆为 `allocationStatusLabels` + `handoverEventLabels` 两个域字典 |

未提升（按「≥2 使用者」规则留在本地，逐字文案不动）：`RepairDetailView.eventNames`、`InventoryReadinessPanel.kinds`、`InventoryLatePanel.kinds/methods`、`InventoryLateDamagePanel.methods/states`、`RepairReturnPanel.states`、`RepairTargetChangePanel.states`、`ProxyConfirmationView.labels/businessLabels`、`InboundDetailView.decisionLabels/reasonLabels`、`BusinessResultNoticeView.reasons`、`ItemTargetChangeView/TransferTargetChangeView` 的交互确认字典、`AssetDetailView.fieldLabels/confirmLabels`，以及 `InventoryDifferencePanel`/`InventoryEvidenceDecisionPanel`/`inventory-sealing-display.ts` 三份**文案不同**的 `dimension` 字典。

### 二、英文兜底收口（UI-21a）：11 处

| 文件:行 | 原渲染 | 改后 |
| --- | --- | --- |
| `views/RequisitionDetailView.vue:182` | `handoverLabels[k] ?? k` | `allocationStatusLabels[k]` |
| `views/RequisitionDetailView.vue:220` | `handoverLabels[k] ?? k` | `handoverEventLabels[k]` |
| `views/InventoryDifferencePanel.vue:185`（`aria-label` 内） | `dimensions[k] ?? k` | `dimensions[k]` |
| `views/InventoryDifferencePanel.vue:186` | `dimensions[k] ?? k` | `dimensions[k]` |
| `views/InventoryEvidenceDecisionPanel.vue:133` | `dimensions[k] \|\| k` | `dimensions[k]` |
| `inventory-sealing-display.ts:11` | `synchronizationReasons[v] \|\| v` | `?? "未记录"` |
| 同上 `:23`（冻结复核状态） | `states[t] \|\| t` | `enumText(states, …)` |
| 同上 `:28`（未盘原因） | `unobserved[t] \|\| t` | `enumText(unobserved, …)` |
| 同上 `:34`（原始证据结论） | `outcomes[t] \|\| t` | `enumText(outcomes, …)` |
| 同上 `:36`（现场状况） | 内联字面量 `[t] \|\| t` | 提为 `conditions` 常量 + `enumText(conditions, …)` |
| 同上 `:40`（差异维度与处理态） | 两处 `map[t] \|\| t` | `enumText(dimensions, …)`、`enumText(states, …)` |

封存打印模块的收口方式（用户选定「严格查表」）：新增 `enumText(map, value) => map[text(value)] ?? "未记录"`（`inventory-sealing-display.ts:5`）。缺项不再回吐英文原值，而是复用该模块 `text()` 既有的中文词「未记录」——不引入新文案，也不出现 `undefined` 字样。

## 文案清单（逐字，供复核）

**新增中文串（本批仅 3 条，其余全部是从本地字典原样搬移）**

| 键 | 值 | 依据 |
| --- | --- | --- |
| `handoverEventLabels.PROXY_RECEIVED` | 代确认签收 | 后端 `RequisitionHandoverService.java:485` 写入原因「异常代确认许可 …」 |
| `handoverEventLabels.TARGET_CHANGED` | 已批准目标变更 | 同文件 `:612`「批准目标变更 …」 |
| `inventoryTaskStatusLabels.DELETED` | 已删除 | `V31 ck_inventory_task_status` 允许值；UI 当前不渲染该态，属字典完整性 |

**行为纠正（非新增文案，但渲染结果变化）**

1. `InventoryFindingPanel`：`subject_status = TRANSFERRED` 的线索主体，改前 `states[...]` 未命中 → 落入 `|| '尚未列入复核'`（语义错误）；改后渲染「已转后续业务」。同文件 `:174`、`:204`、`:232` 的中文兜底表达式本身未改。
2. `RequisitionDetailView.vue:182`：明细状态字典由 9 键混合表换成 5 键 `allocationStatusLabels`，5 个 `ck_req_alloc_status` 值文案逐字相同，故可见结果不变。
3. `AssetReturnReassignment`：快照明细状态字典由 1 键扩为 `loanLabels` 11 键；后端该快照只回 `WAREHOUSE_PROCESSING`，可见结果不变。
4. `inventory-sealing-display.ts`：6 处缺项由「英文原值」改为「未记录」。当前 stub 与后端枚举全覆盖，实际不触发。

## 脚本口径变化

无。本批不属模板迁移批，未跑 `check_script_untouched.sh`；改动均为 `<script setup>` 的导入/字典常量与模板插值表达式，`v-if / v-for / v-model / :disabled / @click / :key` 表达式与按钮文案零改动（`git diff -U0` 可见模板仅 5 行插值变化）。

## 测试口径变化

1 处，属「桩件缺少新增必需导出」而非断言口径变化：

- `apps/web/src/components/AssetReturnReassignment.test.ts:5`：原 `vi.mock("@/loan/api", …)` 整体替换模块，组件改用真实 `loanLabels` 后取到 `undefined` 使 `labels[item.status]` 抛错。改为 `importOriginal` 展开真实模块、仅覆盖 `loanRequest`/`loanMessage`。断言、文案、选择器一字未动。

## 验证

- `rtk env PATH="…v24.18.0/bin:$PATH" npx vue-tsc --noEmit` → 无输出（通过）
- `npx eslint . --max-warnings 0`（仓库根）→ 无输出（通过；`.vue` 不在根 `eslint.config.mjs` 的 `sourceFiles` 覆盖内，属既有盲区）
- `npx vitest run` → `Test Files 64 passed (64)` / `Tests 263 passed (263)`（与 UI-20a/20b 同数）
- `npx vite build` → 成功；CSS `31.42 kB / gzip 5.65 kB`（逐字节同 UI-12 以来），JS `652.37 kB / gzip 174.19 kB`（UI-20b 为 `652.85 / 173.94`：字典去重使原始体积 −0.48 kB，gzip 因中文串重复度下降 +0.25 kB）
- 泄漏普查复扫：`apps/web/src` 内 `map[key] ?? key` / `map[key] || key` 形态已归零，剩余 `[0] ?? null` 命中全为 `input.files?.[0] ?? null`
- CDP 布局契约（`probe.mjs`，dev server 5173 + stub 8080）：4 条路由 × 3 断点全部 `visibleH1=1`、`legacy=0`，`gapChromeToTitle` = 42@1440、37@735/390，与 UI-13~UI-20 一致
- 枚举泄漏探针（`body.innerText` 扫 `[A-Z][A-Z0-9]*(_[A-Z0-9]+)+`）：1440 档 4 条路由各命中 1 项 `WAREHOUSE_OPERATOR`（应用外壳的角色标签，非本批范围，见已知限制）；390 档 4 条路由均 0 命中（窄屏不渲染角色标签）
- 中文标签存在性探针：`/inventory-tasks` 见「进行中」；`/inventory-tasks/9001` 见「待采信 · 位置 · 使用模式 · 责任 · 实物状况 · 旧分配 · 待处理 · 处理中 · 已接收 · 已交出 · 待实物验收」；`/requisitions/5103` 见「待交出 · 已交出待接收 · 待收回取消 · 已接收 · 已交出」
- 整页样张 8 张（1440×900 与 390×844:2，路由 `/inventory-tasks`、`/inventory-tasks/9001`、`/requisitions/5103`、`/asset-returns/53`）在 `/tmp/ui21-samples`，另有标签核查样张 3 张在 `/tmp/ui21-label-samples`。目视复核 `/requisitions/5103`：四张明细卡分别显示「· 待交出 / · 已交出待接收 / · 已接收 / · 待收回取消」，交出与收回凭据时间线首行显示「已交出 · 已交出，接收人小程序待核对。」

## 已知限制

1. 验证口径为**单测 + 构建 + 静态样张**（本地 dev server + `/tmp/fams-stub-api.mjs` 桩件），不是真实后端端到端验收。
2. 封存打印（`sealedRecordLines`）与 `synchronizationReasonText` 的缺项分支在样张中未被真实数据触发——桩件未提供冻结报告端点，故该 6 处仅经代码复核与单测覆盖，未经目视复核。
3. 应用外壳仍直接渲染角色码 `WAREHOUSE_OPERATOR`（`AppChrome`），属角色枚举而非业务枚举，未列入本批 11 处。
4. 三份 `dimension`、三份 `outcome`、两份 `condition`（不确定 / 未知）、八份 `custodyType`、两份同步原因（当前已声明 / 声明仍有效）文案互不相同，`InboundDetailView` 的 `PENDING`（待验收 vs 待验收入库）与 `transferLabels.DELIVERED`（待目标人接收 vs 已交出待接收）亦不同——统一它们会改写用户可见文案，留待 UI-22 单独裁决。
5. GitNexus MCP 本会话未挂载，`impact()` / `detect_changes()` 无法执行；改动范围以 `git diff` 与全量单测复核替代。
6. 仓库未配置 git hook 与远端 CI（`core.hooksPath` 未设置，`.git/hooks` 仅 `.sample`，无 `.github/workflows`），不声称提交校验已生效。

## 下一批

UI-22（待用户裁决文案后执行）：统一上述**文案不同**的同枚举多份字典，并把 `AppChrome` 角色码纳入字典；随后按惯例回收残余本地字典与 `|| '待核实'` 类中文兜底的口径复核。
