# ARCH-21 组织账号主数据与消息增量前端收口（架构批第 21 批）

基线：合并提交 `1a0147d`（第一父 `31122d0` = ARCH-20 记录批，第二父 `05216ed` =
`codex/d6-03b-ci` 功能线 tip）、基线修正提交 `92f51f1`、收口提交 `c9d92fc`。
判据沿用本分支既有口径，不新立标准：

- `vue-best-practices` + ARCH-08：模板不得计算，派生一律归 `computed` 或行显示模型；判据
  `scripts/audit-web-template-derivations.py`。
- ARCH-09：primitive 状态用 `shallowRef`，整份替换的集合保持 `ref`，迟到抑制归
  `useRequestLiveness`；判据 `scripts/audit-web-state-ownership.py`。
- ARCH-10：确认式幂等提交用 `useConfirmedSubmit`，幂等键只归提交底座；中间层不得只做转发。
- ARCH-03/07 + ARCH-09 底座：域内 HTTP 走 `api/http` 的 `requestJson`/`requestBlob`，`.vue` 内
  不得裸 `fetch`；新增段落一律取 `fams-*` 词表，不新增 `<style>` 块与裸色值。

## 量级

| 口径 | 数值 |
| --- | --- |
| 候选范围 | `6e32585`（ARCH-20 接续基线）之后 134 个提交，tip `05216ed` |
| 功能线 `apps/web` 增量 | 53 文件 +3,110/−84 |
| 本批合并提交全仓 | 210 文件 +11,860/−479 |
| 本批收口提交 `apps/web` | 28 文件 +1,506/−928（含 2 个实测底座脚本） |

`apps/web` 落地清单：新增 8 个路由视图（`features/auth/views/AccountAdminView.vue`、
`EmployeePhoneCorrectionView.vue`、`features/masterdata/views/BusinessParameterView.vue`、
`CatalogAdminView.vue`、`IdentityAdminView.vue`、`IdentityImportView.vue`、
`NumberingRuleView.vue`、`features/asset/views/AssetLookupView.vue`）与 9 个面板
（`AccountAuthorizationPanel`、`AssetTagPlacement`、`ExceptionFollowup`、`InboundUnavailability`、
`TagCoverageReport`、`ExceptionDiscoveryReport`、`InventoryTraceabilityReport`、
`InventoryExecutorBreakdown`、`CoreBusinessSamplingReport`）及其 8 个用例文件；新建
`features/masterdata/api.ts` 与 `features/auth/api.ts`；移植 22 个既有文件的增量
（4 个转派面板、台账、详情、借用/退还、入库、领用、调拨、报修转派、报废、消息中心、
配置异常、工作台、运营报表与 5 个既有报表面板、`asset/scrap` 两域 api、router、AppChrome）。

## 1. 功能事实全部取自功能线

候选自 `6e32585` 起交付 P24/P25 页面与跨域提醒增量（组织与人员维护、五类资料目录、
业务参数与版本预览、编号规则、身份 CSV 导入、员工手机号纠正、账号与角色管理、数据范围
与敏感字段授权、消息下钻与异常跟进、无法继续处理登记与转派联动、盘点执行人分解、标签
贴标确认、保管信息完整率、导入错误可定位率、台账筛选与授权导出、资产编码查询等）。
后端契约、权限与文案来源逐字取自候选，本批零改动。

## 2. 冲突一律按 features 形状落地

`rtk git merge --no-ff origin/codex/d6-03b-ci` 产生 48 处冲突，落地方式：

- 22 个「修改/删除」：功能线在旧扁平路径（`src/views/…`、`src/components/…`、`src/asset/api.ts`
  等）上改，本分支已移进 `features/<域>/{api,views,panels}`——旧路径删除，增量逐段移植进
  对应域文件；其中 4 个转派面板的 `unavailableReason` 展示位归口到
  `useReassignmentFlow` 的 `ReassignmentSnapshot` 类型，一处类型扩展覆盖四个域。
- 25 个「文件位置」冲突：git 按改名检测把新文件建议落到 `features/repair/panels/`（与报修
  无关），按业务域改落 auth/masterdata/asset/report/reminder/inbound 的 `views|panels`；
  报表面板挂进宿主 `OperationsReports`，贴标面板挂进 `AssetDetailView`，CSV 导入面板挂进
  `InboundDetailView`，跟进面板挂进 `MessageCenterView` 行卡，不并列新页。
- `router.ts` 与 `AppChrome.vue`：取本分支懒加载与 `NAV_GROUPS` 形状，补 8 条懒路由
  （含 `uiGroup/uiTitle`）与「系统与主数据」侧栏分组 6 条；功能线的平铺 import 形状不带入。
- `DESIGN-HANDOFF.md`：取本分支版本，登记留到本记录批（沿用 ARCH-12～20 口径）。

## 3. 底座与状态形状

- 16 个新组件的自持 `let generation/live` 全部归 `useRequestLiveness`；写意图全部归
  `useConfirmedSubmit`（重试复用原键）；primitive 状态归 `shallowRef`，整份替换的集合保持
  `ref` 语义。
- 域内 HTTP：`features/masterdata/api.ts` 的 `masterDataRequest` 从裸 `fetch` 改为
  `requestJson` + 本域 `Profile`（`codeFallback: "MASTER_DATA_REQUEST_FAILED"`、
  `csrfMissing → 401 AUTH_SESSION_INVALID`，POST/PATCH 由 `mutation.method` 决定），中文文案
  逐字保留。`AccountAuthorizationPanel` 的授权写 Profile 落新建 `features/auth/api.ts` 的
  `authorizationRequest`。`ExceptionFollowup`/`AssetLookupView` 走 `api/business` 内核
  （文案映射不同的两句保留为面板内模块常量函数）。5 个报表面板走既有 `reportRequest`。
- 底座新增一个机制：`requestJson` 的 `RequestInit` 增 `text`/`textContentType` 原文请求体
  通道，`identityImportCsv` 以 `text/csv;charset=utf-8` 原文提交，端点、Content-Type、
  请求体字节与幂等键语义同原实现逐字等价（multipart 改道会改请求体字节，弃用）。
- STABLE_CODE 长度差异：masterdata 原实现错误码正则 `{0,79}`，底座为 `{0,63}`。全后端错误码
  字面量实测最长 50 字符（masterdata 域内最长 43），两正则对全部现实样本判定一致，
  fallback 行为等价，未私改底座。
- 视图内裸 `fetch(` 命中 0；本批未新增 `<style>` 块与令牌，主样式表文件名与字节不变。

## 4. 模板派生归位与词表

- 判据读数：改前（合并基线）`files_with_derivation=23 sites=87`、`hard_violations=109`；
  改后 `files_with_derivation=0 sites=0`、`hard_violations=0`。
- E 类 `lifecycle_shape_review` 29→41：新增 12 条全部是本批新域视图/面板与同族报表面板的
  「busy+error 三件套」形状，与 ARCH-09～20 保留清单同族，只列清单不判失败。
- 8 个新视图的根 `<h1>` 删除，标题经路由 meta 由 `AppChrome` 单点渲染（文案逐字相同）；
  权限门槛提示 `<p role="alert">` 保留并取 `fams-notice data-state` 词表。
- 词表：全部新组件与移植段取 `fams-*`（panel/card/field/field-grid/control/button+data-kind/
  notice/table+scroll/pagination/timeline/inline/facts/status/selection/form-actions），
  未发明类名；`AssetReturnDetailView` 与 `RequisitionDetailView` 的「登记无法继续」按钮、
  `TransferDetailView` 的无法继续段、`InboundDetailView` 的登记面板均按宿主既有词表形状挂载。
- 测试口径变化（逐条）：`AccountAdminView.test.ts` 等 3 个 auth 用例不改文案全绿；
  `AccountAuthorizationPanel.test.ts` 去掉已删除的 `blocked` prop（1 行）；
  `AssetLedgerView.test.ts` 3 处 `listAssets` 调用断言补第 4 参筛选对象与新增导出按钮位；
  `AssetDetailView.test.ts` 3 处按钮文案序列在「打印标签」后补「确认实物贴标」；
  `MessageCenterView.test.ts` 取功能线自带口径（`aria-label="业务消息"` 区块），视图侧
  以 `canSeeInbox` 门控落地；`router.test.ts` 分组表加「系统与主数据」、懒路由计数 38→46。

## 5. 门禁与真实浏览器实测

四道门禁（Node 24.18.0；`rtk` 默认 Node 16 会让 eslint/vitest 失败）：仓库根
`npm run lint`（`--max-warnings=0`）退出 0；`vue-tsc --noEmit` 退出 0；`vitest run`
**90 文件 / 404 用例全绿**——较上一批 82/378 净增 8 个用例文件与 26 条用例（功能线自带的
auth 3 文件 9 条、masterdata 5 文件 16 条 + 台账/详情/路由口径改写）；`vite build` 退出 0。

体积（同一命令口径：`dist/assets` 下全部 `.js` 逐个 `gzip -c` 求和）：

| 树 | 主样式表 | 全量 JS gzip |
| --- | --- | --- |
| 合并基线 `92f51f1` | `index-CvDOmE8w.css` 31,420 / gzip 5,689 | 331,762 |
| 收口后 `c9d92fc` | 同名同字节，未变 | 336,221 |

即本批收口净增 4,459 字节 gzip，全部来自 16 个新组件的词表与底座改写；主样式表逐字节不变。

真实浏览器：一次性栈（MySQL 8.4.11 固定 digest + 本批现场 `./mvnw -DskipTests package`
重建的后端 jar + clamav 1.4.6 + 两份 vite dev），改前 worktree `92f51f1`（5174）与收口后
工作树（5173）并排跑同一后端、同一库状态、同一份 **65 条路由 × 4 角色 = 260 个渲染单元**。
角色从 3 扩到 4：实测底座为本批新增 `sysadmin`（SYSTEM_ADMIN）账号，否则 8 条新路由与
「系统与主数据」侧栏分组在任何既有角色下都渲染不出来；同时底座为 admin 的 ASSET_ADMIN
授权补 `BULK_EXPORT` 字段权限行，使台账授权导出按钮进入渲染面；`arch-ui-routes.py` 的
翻页探针补 6 条新列表路由。造单沿用既有清单，唯一失败仍是 `inbound accept A` 的
`409 INBOUND_ASSET_STATE_CONFLICT`（与 ARCH-14～20 同源，属造单数据形状，与本批无关）。

```
全站口径（含侧栏与页头）：
compared=260 {"SAME":221,"STATUS_DIFF":0,"STRUCT_DIFF":39,"TEXT_DIFF":37,"CONTROL_DIFF":0,
              "LABEL_DIFF":0,"ISSUE_DIFF":0,"PROBE_DIFF":0,"MISSING":0}
内容区口径（--scope main.fams-main）：
compared=260 {"SAME":221,"STATUS_DIFF":0,"STRUCT_DIFF":39,"TEXT_DIFF":38,"CONTROL_DIFF":0,
              "LABEL_DIFF":0,"ISSUE_DIFF":0,"PROBE_DIFF":0,"MISSING":0}
```

39 个结构差异单元的归因（控件总数/禁用数、按钮文案集合、标签、事件与翻页探针在全部
260 单元上零差异）：

- 32 个 = 8 条新路由 × 4 角色：本批新渲染面的「改前扁平形状 → 改后词表形状」差异；
  其中 2 个单元连掩码文本都相同（空态文案一致）。
- 5 个 = admin/warehouse 的 `/`、`/login`、`/password/change`：后两者已登录访问时重渲
  工作台，差异全部来自工作台内 `WorkbenchTodos` 转派筛选表单的词表形状（文本差异只由
  部分掩码的 `数据时间` 微秒戳解释，逐词 diff 已核）。
- 1 个 = warehouse `/inbound/1`：「无法继续处理」登记面板的词表形状，文本逐字相同。
- 1 个 = warehouse `/assets/1`：贴标确认面板形状（该单元含于上面 32 之外，实际归入
  `/assets/1` 路由行）。

`finalUrl` 260 单元全同，两侧 `routes_with_issues` 均为 74，事件集合逐条同集。
1440×900 整页截图 8 张（sysadmin 的账号与角色/组织与人员/编号规则/资料目录/组织人员导入、
warehouse 的入库详情/资产详情、employee 的资产查询），其中账号与角色页与入库详情页
人工核对观感：chrome 页头 + 侧栏「系统与主数据」分组 + `fams-table` 列表 + 分页摘要 +
`fams-card` 表单区，没有出现未收口的原生控件。

## 6. 偏差与遗留

- **合并与收口分两提交但缺「移植后 vs 收口后」的独立绿基线**：合并产物本身有 9 条用例
  失败（台账/详情断言口径与消息中心 aria 口径），由基线修正提交 `92f51f1` 转绿后，
  实测改前参照取 `92f51f1` 而非裸合并提交 `1a0147d`；`1a0147d` 的 vite 运行时可用，
  但其测试口径未绿，不作为门禁参照。
- **ExceptionFollowup 写请求新增 `If-Match-Version: 0` 头**：改造前该 POST 不带版本头；
  三件套底座要求 version 必填，取 0 与消息已读登记同族。已核 `ReminderFollowupController`
  不读版本头，服务端行为不变；如真实联验出现差异按联验结论回改。
- **AssetLookupView 的 GET 不再带 `cache: "no-store"`**：`businessRequest` 内核 GET 口径
  如此，与 `/messages` 等既有读取一致，仅 HTTP 缓存语义微差。
- **CSV 上传通道是本批对 `api/http` 的唯一机制扩展**（`text`/`textContentType`），
  其余底座文件零改动；`features/auth/api.ts` 为本批新建的域内底座文件。
- **写路径未在浏览器执行**：普查只读渲染；8 条新路由的创建/维护/导入/授权/纠正/跟进、
  台账导出与翻页探针之外的点击动作都没有在浏览器里点过；与 ARCH-15～20 同源缺口，
  本批未闭合。新页面行为由功能线自带 25 条用例 + 本批口径改写覆盖。
- **报表面板的结果渲染仍靠点击读取**：TagCoverage 等 5 个面板在普查中只渲染初始态，
  功能线未交付配套用例（沿用 ARCH-20 遗留）。
- **小程序与后端不在本批范围**：`apps/miniprogram` 与 `backend` 的候选增量直接取自
  功能线合并，未改造、未独立复核。
- **候选 `codex/d6-21-loss` tip `5965ed8` 未合入**：该提交自述「保存根工作树历史在途代码」，
  含 `contracts/fams-core-v1.yaml` 大改与 DESIGN-HANDOFF 覆盖，属在途归档而非完成功能线，
  合并取舍需人工裁决，本轮按红线停下登记，不猜并。
- **提交类型偏差**：合并提交标题按 commitlint 取 `chore(web)`；`.githooks/commit-msg`
  仍未安装（`core.hooksPath` 未设置），为手工校验，不表示提交门禁已生效。
- 本批三个提交只推送当前 UI 工作分支，未合并 `main`、未建 PR。推送只改变分发状态：
  除本地四道门禁与本批真实浏览器实测外，远端 CI、第七阶段 QA、真实外部联调、独立复核与
  生产验收仍未执行；不改变 D6-24/D6-25/D6-26 各任务的开放状态。

## 复现

```bash
./mvnw -B -DskipTests package                                    # Java 21，jar 需含 masterdata/account-admin/messages follow-up 映射
python3 scripts/run-arch-ui-stack.py --web-port 5173 --attachments &   # MySQL(含 sysadmin 账号与 BULK_EXPORT 授权行) + 后端 + clamd + vite dev(改后)
python3 scripts/seed-arch-ui-docs.py                             # 既有单据集；唯一失败仍是 inbound accept A 409
python3 scripts/arch-ui-routes.py --out /tmp/arch-routes-21.json   # 65 条路由；只走 --out
git worktree add --detach /tmp/fams-base-92f51f1 92f51f1        # 改前参照（合并基线修正批）
ln -sfn <repo>/node_modules          /tmp/fams-base-92f51f1/node_modules
ln -sfn <repo>/apps/web/node_modules /tmp/fams-base-92f51f1/apps/web/node_modules
(cd /tmp/fams-base-92f51f1/apps/web && PATH="$HOME/.nvm/versions/node/v24.18.0/bin:$PATH" npx vite --port 5174 --strictPort --host 127.0.0.1)
# 先 curl 预热 worktree dev server（首次请求触发依赖预优化并重启）
PW=$(python3 -c "import json;print(json.load(open('/tmp/fams-arch-stack.json'))['password'])")
for T in "5174 9442 base" "5173 9471 head"; do set -- $T
  node scripts/arch-ui-cdp-sweep.mjs --mode sweep --base http://127.0.0.1:$1 --routes /tmp/arch-routes-21.json \
    --password "$PW" --roles admin,warehouse,employee,sysadmin --out /tmp/sweep21-$3.json --port $2 --settle 700
done
node scripts/arch-ui-cdp-sweep.mjs --mode diff --left /tmp/sweep21-base.json --right /tmp/sweep21-head.json
# 内容区口径：以上两条 sweep 各加 --scope main.fams-main，out 换 *-content.json，再 diff
# 截图：--mode sweep 单值 --filter <路由> --roles <角色> --viewport 1440x900 --shotDir <目录> --label head --out <json>
```

沿用 ARCH-13～20 的执行前提：sweep 必须 Node 24（Node 16 的 `node:util` 无 `parseArgs`）；
`--attachments` 需本机 docker；截图只能经 `--mode sweep --shotDir` 且必须带 `--out`
（`--filter` 为单值子串，多路由需多次调用）。
