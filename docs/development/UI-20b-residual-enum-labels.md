# UI-20b 残余枚举串中文化（第 20 批 · 下）

基线分支 `codex/d6-20-factory-ui`（`ca54dce`，即 UI-19 提交），承接 [UI-20a 盘点与目标变更 11 处 `window.confirm` 收口](UI-20a-window-confirm-inline-blocks.md)。

本批仍是**文案批**，不是模板迁移批：把 `apps/web` 生产代码里剩余**直接渲染后端枚举原值**的 17 个点改为按域字典取中文，另把 2 个页内局部字典提升到所属域 `api.ts` 复用。CSS 层零改动：构建 CSS `31.42 kB / gzip 5.65 kB`，与 UI-12 以来逐字节一致。UI-20（上+下）至此收官。

字典写法沿用仓库既有口径：`export const xxxLabels: Record<string, string> = {…}` 放在**拥有该枚举的域** `api.ts`，模板只写 `labels[key]`，**不加 `?? 原值` 兜底**（未知值应暴露为数据问题，不应静默显示机器码）。值全部取自 `contracts/fams-core-v1.yaml` 的 `enum` 或 `backend/src/main/resources/db/migration/d6_04/` 的 `CHECK` 约束，无自造。

## 范围

新增字典（7 个域 `api.ts`，共 +23 行）：

| 文件 | 导出 | 值来源 |
|---|---|---|
| `inbound/api.ts` | `inboundStatusLabels`(7) / `inboundDecisionLabels`(2) / `inboundItemLabels`(2) | `contracts/fams-core-v1.yaml:3356`、`:3426` |
| `loan/api.ts` | `reminderLabels`(5) / `inspectionLabels`(3) | `:3871`（`LoanReminder.reminderType`）、`V29__create_asset_returns.sql:38`（`ck_return_inspection`） |
| `assetimport/api.ts` | `importReviewLabels`(3) / `importRollbackLabels`(6) | `:1959`、`:1987` |
| `asset/api.ts` | `exceptionLabels`(3) | `:1140` |
| `attachment/api.ts` | `scanStatusLabels`(3) | `:6764` |
| `proxy/api.ts` | `proxyEvidenceLabels`(3) / `proxyCollectionLabels`(3) | `V29__create_proxy_confirmation_and_result_notice.sql:37,39` |
| `inventory-review/api.ts` | `inventoryLateResultLabels`(4) | `:3929`（`InventoryLateDecision.result`） |
| 复用既有 | `repairStates`（`repair/api.ts:42`）、`lifecycleLabels`（`asset/api.ts`） | `V43__create_repair_requests.sql:32` |

改点位（12 个 `.vue`，17 行渲染表达式，共 +30/−27；其中 `views/InventoryLatePanel.vue` 只动脚本，见「脚本口径变化」）：

| 文件:行 | 原渲染 | 改后 |
|---|---|---|
| `views/InboundView.vue:39` | `{{ value.status }}` | `{{ inboundStatusLabels[value.status] }}` |
| `views/InboundDetailView.vue:114` | `{{ selected.receiptNumber }} · {{ selected.status }}` | `· {{ inboundStatusLabels[selected.status] }}` |
| `views/InboundDetailView.vue:118` | `管理员决定：{{ selected.decision.result }}` | `{{ inboundDecisionLabels[...] }}` |
| `views/InboundDetailView.vue:150` | `{{ item.executionStatus }}` | `{{ inboundItemLabels[item.executionStatus] }}` |
| `views/LoanDetailView.vue:97` | `· {{ reminder.reminderType }}` | `{{ reminderLabels[reminder.reminderType] }}` |
| `views/AssetReturnDetailView.vue:136` | `验收结果 {{ selected.inspectionResult }}` | `{{ inspectionLabels[...] }}`（后接的自由文本 `inspectionReason` 保留） |
| `views/AssetImportView.vue:252` | `核验状态：{{ review.status }}` | `{{ importReviewLabels[review.status] }}` |
| `views/AssetImportView.vue:257` | `整批撤销：{{ rollback.status }}` | `{{ importRollbackLabels[rollback.status] }}` |
| `views/ProxyConfirmationView.vue:164` | `凭证 {{ selected.evidenceKind }} · 采集 {{ selected.collectionMethod }}` | `{{ proxyEvidenceLabels[...] }}` / `{{ proxyCollectionLabels[...] }}` |
| `views/ProxyConfirmationView.vue:169` | `<small>{{ attachment.scanStatus }}</small>` | `<small>{{ scanStatusLabels[attachment.scanStatus] }}</small>` |
| `views/InventoryDifferencePanel.vue:245` | `· {{ linkedRepair.status }}` | `· {{ repairStates[linkedRepair.status] }}` |
| `views/InventoryLateDamagePanel.vue:94` | `补充复核 {{ context.recheckResult }}` | `{{ inventoryLateResultLabels[...] }}` |
| `views/InventoryLateDamagePanel.vue:95` | `（上次核对 {{ context.damage.repair_status }}）` | `{{ repairStates[context.damage.repair_status ?? ''] }}` |
| `views/InventoryLateDamagePanel.vue:130` | `· {{ linked.status }}` | `· {{ repairStates[linked.status] }}` |
| `views/TransferView.vue:172` | `· {{ asset.lifecycleStatus }}；` | `· {{ lifecycleLabels[asset.lifecycleStatus] }}；` |
| `views/InventoryTaskDraftView.vue:90` | 3 条硬编码 `<option>` | `<option v-for="(text, value) in exceptionLabels">` |
| `components/AttachmentPanel.vue:139` | 局部 `scanText` 常量（模板未动） | 改为 `import { scanStatusLabels as scanText }` |

## 文案清单（逐字，供复核）

新增/替换的用户可见文字**只有**下列值，其余页面文字逐字未动：

- 入库单状态：`草稿` `待管理员确认` `已驳回` `待库管领取` `库管处理中` `已完成` `已取消`
- 入库决定：`已批准` `已驳回`；入库明细：`待验收` `已验收入库`
- 借用提醒：`到期前 3 日提醒` `到期前 1 日提醒` `到期日提醒` `逾期提醒` `遗失责任跟踪`
- 退还验收：`实物正常` `实物损坏` `实物缺失`
- 导入核验：`待管理员核验` `已批准发布` `已驳回核验`；整批撤销：`待管理员批准` `已批准，待执行` `已驳回` `撤销任务排队中` `已撤销` `撤销执行失败`
- 代确认凭证：`签收单` `物流签收` `授权代收`；采集方式：`扫描核对` `核对编码` `手工记录`
- 附件扫描：`可下载` `感染文件 · 已隔离` `扫描不可用 · 已隔离`
- 迟报损坏补充复核：`待补充核实` `补充线索` `关联复盘尚待实际完成` `已记录补充结论`
- 资产异常选项（盘点草稿筛选）：`正常` `查找中` `已遗失` —— 其中 **`遗失` → `已遗失`** 是本批唯一的既有文案改写（PRD V1.5 §异常状态口径），另两值原样保留

## 脚本口径变化

同 UI-20a：本批按设计必须改 `<script setup>`（字典只能在那里导入），`audit.mjs script-hash` 不适用，改由逐条人工核对。两处是「局部字典上提」，脚本净减少，模板一字未动：

- `components/AttachmentPanel.vue`：删本地 `const scanText = {…}`，改为 `import { scanStatusLabels as scanText } from "@/attachment/api"`（模板仍写 `scanText[...]`）；
- `views/InventoryLatePanel.vue`：删本地 `const results: Record<string, string> = {…}`，改为 `import { inventoryLateResultLabels as results }`（模板仍写 `results[...]`）。

两处上前提的是**逐字相同的值**，渲染结果不变，属纯去重。

`repairStates[context.damage.repair_status ?? '']` 的 `?? ''` 只为满足 `vue-tsc` 的 TS2538（`null` 不能作索引类型）：后端 `InventoryLateDamageService` 在同一 `linked` 记录上同时写 `repair_id` 与 `repair_status`，而 `router-link` 本身已由 `v-if="context.damage.repair_id"` 守卫；`{{ null }}` 在 HEAD 就渲染为空，改后仍为空。

## 测试口径变化

4 个测试文件 **+8 / −7**，未删除任何用例：

- 5 行是 `vi.mock` 桩件保真度修正（`@/inbound/api` ×2、`@/attachment/api` ×2、`@/proxy/api` ×1）：原工厂只导出被 mock 的函数，视图新导入字典后渲染即抛错，改为仓库既有的 `importOriginal` 展开写法（`LoanView` / `TransferDraftView` 测试早已如此）。这类失败的表现是 `wrapper.text()` 只剩外壳文字，不是断言口径变化。
- 2 行是**文案本身变化**导致的断言同步（`views/InboundDetailView.test.ts:24,45`）：`"RK-FIND-001 · DRAFT"` → `"RK-FIND-001 · 草稿"`，`"RK-FIND-001 · PENDING_ADMIN_DECISION"` → `"RK-FIND-001 · 待管理员确认"`。

## 验证

四道门禁（本轮最后一次源码改动后复跑；`rtk` 默认解析到 `/usr/local/bin/node` = **v16.15.1**，会让 eslint 10 报 `structuredClone is not defined`、vitest 报 `node:util` 无 `styleText` 导出，故全部命令改为 `rtk env PATH="$HOME/.nvm/versions/node/v24.18.0/bin:$PATH" npx …`）：

- `vue-tsc --noEmit` → exit 0
- `eslint . --max-warnings 0`（仓库根）→ 输出 0 字节，即无 issue；`.vue` 不在根 `eslint.config.mjs` 的 `sourceFiles`（只覆盖 `*.{ts,tsx}`）内，属既有盲区，非本批引入
- `vitest run` → `Test Files 64 passed (64)` / `Tests 263 passed (263)`（与 UI-20a 同数）
- `vite build` → 成功；CSS `31.42 kB / gzip 5.65 kB`（逐字节同 UI-19/UI-20a），JS `652.85 kB / gzip 173.94 kB`（UI-20a 为 `651.69 / 173.50`，+1.16 kB 原始体积来自 9 个字典常量）

静态样张（dev server 5173 + `/tmp/fams-stub-api.mjs` 8080，**非真实后端端到端**）：`/tmp/fams-ui20b-probe.mjs`、`/tmp/fams-ui20b-probe2.mjs` 用原生 CDP 逐路由截图并扫描 `.fams-main` 文本。

- 页头契约：1440×900 全部帧 `chromeBottom=64 headTop=80 h1Top=106 gapChromeToTitle=42 visibleH1=1 legacy=0 overlap=0 overflowNotTable=0`；390×844 帧 `headTop=124 h1Top=150 gapChromeToTitle=86`，其余同上。
- **枚举残留**：所有路由的大写串集合只剩 `JPG` `PDF` `PNG` `WEBP`（附件下载按钮的类型标签）、`XLSX` `CSV`（上传/导出提示）、`T14`（资产型号名）——**无一个后端枚举原值**；全平台 `undefined` 渲染 = `false`（防止字典漏键渲染成 `undefined`）。
- 正向文案命中（样张内实测）：`RK-2026-1021-02 · 库管处理中`、`管理员决定：已批准`、`待验收` / `已验收入库`、`可下载` / `扫描不可用 · 已隔离`、`到期前 3 日提醒` / `到期日提醒` / `遗失责任跟踪`、`验收结果 实物损坏` / `实物缺失`、`核验状态：已驳回核验。可修正后重新预检。`、`整批撤销：已批准，待执行。`、代确认四条记录分别渲染 `凭证 授权代收 · 采集 核对编码` / `凭证 签收单 · 采集 扫描核对` / `凭证 物流签收 · 采集 核对编码` / `凭证 签收单 · 采集 手工记录`、调拨人员交接 `FA-2026-000101 · ThinkPad X1（研发三区在用） · 在用；`、盘点草稿异常筛选项 `正常 / 查找中 / 已遗失`。
- 截图 25 张：`/tmp/fams-ui20b-{inbound-list,inbound-processing,inbound-draft,loan-open,loan-lost,return-damaged,return-missing,transfers,asset-imports,asset-imports-batch,asset-imports-review,proxy-confirmation,inventory-task-new}-1440.png` + `/tmp/fams-ui20b-{transfers-personnel,inventory-review-panel}-1440.png` + `/tmp/fams-ui20b-{inbound-list,inbound-processing,inbound-draft,loan-open,asset-imports,asset-imports-batch,asset-imports-review,proxy-confirmation,inventory-task-new,transfers-personnel}-390.png`；同名 `.txt` 为各帧正文快照。
- 为取到上述文案，`/tmp/fams-stub-api.mjs`（非产品代码）补了一条 `…/asset-import-batches/77881/review` → `REJECTED` 夹具（`核验状态` 段落要求批次处于 `PREVIEWED`，原桩件只有 404）。
- 盘点域三个点（`InventoryDifferencePanel:245`、`InventoryLateDamagePanel:94/95/130`）在样张里**取不到**：差异面板的 `处理方式` 需先把原始证据列入复核并生成差异行，迟报损坏面板要求任务处于 `COMPLETED/CANCELLED/TERMINATED`，桩件与 UI-20a 夹具都不满足。改用一个**临时** vitest 文件（`src/views/__scratch_ui20b.test.ts`，跑完即删，未提交、未改动任何既有测试）以真实挂载渲染验证：`3 tests passed`，断言为 `补充复核 已记录补充结论`、`（上次核对 库管处理中）`、`维修 88 · 第 6 版 · 待库管领取`、`已核对维修 88 · 第 6 版 · 库管处理中`。

## 已知限制

1. 验证口径是单测 + 构建 + 静态样张，**不是**真实后端端到端验收。
2. 上述三个盘点域点位未经浏览器样张目视复核，只有 DOM 挂载测试与类型检查覆盖（做法见「验证」最后一条）。
3. `proxyCollectionLabels` 的 `MANUAL_RECORD → 手工记录`、`SCAN_CONFIRMED → 扫描核对` 取的是**本页自身 `<select>` 选项**（`ProxyConfirmationView.vue:135-136`）的口径，与 PRD §1610 的叙述措辞以及小程序端「授权代收」表述可能有出入；本批只做 Web 侧统一，跨端文案对齐未做。
4. `views/RequisitionDetailView.vue:92,130` 仍是本地 `handoverLabels[key] ?? 原值` 写法（HEAD 既有，非本批引入）：已知键显示中文，未知键会显示机器码。同屏扫描另有 22 处 `字典[key] ?? / || 中文兜底` 与 9 个页内局部字典，均属既有状态，本批未动，列为下一批。
5. 明确**不做**的自由文本列（`VARCHAR`，非枚举，字典化会吞掉真实数据）：`treatmentResult`、`row.method`、`d.purpose`、`physicalDestination.destination`、调拨 `destination`、`end_reason`、`inspectionReason` / `manualReason` / `cannotConfirmReason` / `decisionReason` / `reason` / `evidence` 等全部 `*Reason`。
6. 本会话 GitNexus MCP 未挂载，AGENTS.md 要求的 `impact()` / `detect_changes()` 未能执行，影响面以人工读 diff + 263 条用例为准。
7. `rtk` 解析到 Node v16.15.1 与仓库工具链不兼容（见「验证」首段），本轮所有门禁与样张脚本都显式注入 nvm v24.18.0 的 PATH；这是本机环境问题，未改任何仓库配置。
8. 工作树含未跟踪 `.qoder/`（本地 repowiki 资产，约 3.1 MB），不纳入提交；UI-20a + UI-20b 的改动尚未提交，`43a89f5` / `3a431a2` / `ca54dce` 三个前序提交也未推送。

## 下一批

**UI-21 局域字典与兜底收口**（同批口径：只改文案与字典，不动 CSS）：

- 把仍带 `?? 原值` / `|| 原值` 机器码兜底的 2 处（`RequisitionDetailView` 的 `handoverLabels`）改为无兜底并按契约补全键值；
- 评估其余 22 处 `… ?? 中文兜底` / `… || 中文兜底`：保留「数据缺失时的人工提示语」，去掉「未知枚举显示机器码」的路径；
- 上提 9 个页内局部字典（`fieldLabels` / `decisionLabels` / `reasonLabels` / `businessLabels` / `methodLabels` / `handoverLabels` / `targetText` / `costText` / `confirmLabels` 中的跨页复用者），逐字迁移值，模板不动；
- 与 UI-20 合并提交或单独提交由用户决定。
