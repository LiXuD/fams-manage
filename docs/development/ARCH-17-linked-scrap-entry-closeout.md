# ARCH-17 报失/维修/返厂转关联报废前端按 ARCH 标准收口（架构批第 17 批）

基线：`54ce8ae`（把 `codex/d6-21-loss` 的 D6-22「确认遗失/已受理维修/返厂换机 → 关联报废」三段双端
入口增量合入架构线）。判据沿用本分支已建立的口径，不新立标准：

- `vue-best-practices` + ARCH-08：模板不得计算，派生一律归 `computed` 或行显示模型；判据
  `scripts/audit-web-template-derivations.py`。
- ARCH-09：primitive 状态用 `shallowRef`，整份替换的集合保持 `ref`，迟到抑制归 `useRequestLiveness`；
  判据 `scripts/audit-web-state-ownership.py`。
- ARCH-10：确认式幂等提交用 `useConfirmedSubmit`，幂等键只归提交底座；判据同上（D 类）。
- ARCH-03/07 + ARCH-09 底座：域内 HTTP 走 `api/http` 的 `requestJson`/`requestBlob`，`.vue` 内不得裸
  `fetch`；object URL 簿记归 `useObjectUrlPreview`；组件级原始 CSS 归位 `fams-*` 令牌词表。

## 量级

| 文件 | 合并基线 `c3e63d3` | 本批改后 | 主要变化 |
| --- | --- | --- | --- |
| `features/loan/api.ts` | 5 行 | 5 行 | `LOST_TERMINATED: "遗失终止"` 补进 `loanLabels` 域内映射（功能线把它写在视图内的 labels 里） |
| `features/loss/api.ts` | 1 处 | 1 处 | `LossRecoveryAsset.lifecycle?` 字段（git 自动并入） |
| `features/repair/api.ts` | 1 处 | 1 处 | `Repair.actions.requestScrap?`（git 自动并入） |
| `features/scrap/api.ts` | 113 行 | 116 行 | `ScrapRequest` 补 `sourceType`/`sourceBusinessId`/`factoryDisposition`、`actions.executeLoss`/`executeFactory`，`disposition.due_date` 与 `completion.recipient`/`operatorName` 转可选 |
| `features/loss/views/LossView.vue` | 331 行 | 409 行 | 关联报废入口 + 报废在途生命周期三种读法，全部走 `useConfirmedSubmit`/`useRequestLiveness` 底座 |
| `features/repair/views/RepairDetailView.vue` | 246 行 | 297 行 | 关联报废入口、三个报废移交事件名、确认段摘要行 |
| `features/repair/panels/RepairFactoryReplacementPanel.vue` | 228 行 | 284 行 | 旧机关联报废入口挂到既有面板，草稿/在途两槽仍由面板的 `useConfirmedSubmit` 持有 |
| `features/scrap/views/ScrapView.vue` | 462 行 | 530 行 | 待遗失核销读法、`executeLoss`/`executeFactory` 两段动作、只读批准范围、厂家凭证回链 |
| 四个测试文件 | 功能线新增 5 条用例 | 同 5 条 | 断言一字未改，只把报修详情用例的宿主组件与 `router-link` 桩选择器对齐 |


合并提交 `54ce8ae` 全仓 48 文件 +2,522/−184，其中 `apps/web` 12 文件 +447/−48（含 5 条功能线新用例）；
本批没有将移植与收口拆成两个提交，两段工作在同一提交内完成（见 §6 偏差）。

## 1. 三段新形状的功能事实全部取自功能线

候选 `codex/d6-21-loss` 相对上一轮接续基线 `842a611` 是 7 个提交（`b3b8167` 借用报失遗失核销、
`048507d` 非借用报失转关联报废、`39001bf` 非借用实测证据、`ee87bb0` 从报失页打开关联报废、
`425d069` 已接受报修转关联报废、`d9c260d` 保护已记录的厂家销毁、`87edd27` 按厂家凭证执行旧机报废）。
后端、契约、迁移 `V60__allow_lost_loan_termination.sql`、小程序与 D6-22 证据文件直接取功能线，
本批只动 `apps/web`。三段新形状是：

1. 报失侧：`POST /scrap-requests/from-loss/{reportId}`，体为 `assetVersion`（取 `recoveryAsset.version`）
   + `reason` + `observedCondition` + 固定 `proposedDisposition: "遗失核销"`，`If-Match-Version` 是报失单
   自身版本；确认遗失后按 `recoveryAsset.lifecycle` 分三种读法（在途 / 已报废 / 仍在跟进）。
2. 报修侧：`POST /scrap-requests/from-repair/{repairId}`，体额外冻结 `ownershipVersion`/`lockVersion`；
   维修历史新增 `REPAIR_SCRAP_HANDED_OFF`/`REPAIR_SCRAP_RETURNED`/`REPAIR_CLOSED_BY_SCRAP` 三个事件名。
3. 报废侧：`actions.executeLoss` → `/execute-loss`（≥10 字核销依据）、`actions.executeFactory` →
   `/execute-factory`（凭证记载的实际处置时间）、`sourceType === "LOSS"` 且 `PROCESSING` 读作
   「待遗失核销」、批准范围对报失/厂家来源改为只读文本、`disposition_status = LOSS_WRITTEN_OFF`
   读作「遗失核销」且不再有「计划日期」。

返厂换机侧 `POST /scrap-requests/from-factory/{repairId}/rounds/{roundId}/replacements/{replacementId}`
的体是 `{roundVersion, scrap:{…}}`，去向码原样转写为 `proposedDisposition`，前端不改写。

## 2. 冲突一律按 features 形状落地

`git merge --no-ff origin/codex/d6-21-loss` 产生 8 处冲突：6 个「修改/删除」（功能线在旧路径
`apps/web/src/views/*.vue` 与 `apps/web/src/scrap/api.ts` 上改，本分支已把它们移进
`features/<域>/{api,views,panels}`）、1 个内容冲突（`features/repair/views/RepairDetailView.test.ts`
对功能线 `views/RepairView.test.ts`）与 `DESIGN-HANDOFF.md`。落地方式：

- 旧路径 6 个文件删除，功能线的改动逐段移植进 `features/` 对应文件；`loss/api.ts`、`repair/api.ts`
  两处纯字段扩展由 git 自动并入。
- 报修详情段挂到 ARCH 拆分出的宿主 `RepairDetailView.vue`（功能线当时仍写在合并版 `RepairView.vue`
  里），列表侧 `RepairView.vue` 零改动；返厂换机段挂到宿主面板 `RepairFactoryReplacementPanel.vue`。
- 报修详情用例随宿主改名，`router-link` 断言选择器由功能线的 `a[href=…]` 改为本分支挂载桩的
  `a[data-destination=…]`；报失用例保持 `a[href=…]`（该测试的 vue-router 桩就渲染 `href`）。
- `DESIGN-HANDOFF.md` 取本分支版本，登记留到本记录批，避免把功能线的交接文案带进架构分支入口。

## 3. 底座与状态形状

三个视图的关联报废提交都不再自造在途计数：

- `LossView.vue` 把 `useConfirmedSubmit` 的载荷泛型从 `Omit<LossSubmission, "key">` 扩到
  `PageIntent`（报失意图 ∪ `{path, version, body, summary}` 的关联报废意图），幂等键、草稿/在途两槽
  与「4xx 清键」判据同一条；`send()` 按路径前缀 `/from-loss/` 分流到 `scrapRequest`，成功回执只落
  `scrapReceiptId`，失败仍由 `reportFailure` 先把 `ScrapApiError` 归一成 `LossApiError` 再判复位。
- `RepairDetailView.vue` 沿用本视图既有的 `pending`/`confirmation` 两槽（ARCH-10 未把它换成底座），
  关联报废意图直接复用 `RepairMutation` 自带的 `key` 字段，因此「重试不换键」与既有五段动作同源；
  `perform()` 按 `repairSourceId` 判别分流。
- `RepairFactoryReplacementPanel.vue` 把面板底座的泛型换成 `Mutation`（常规换机突变 ∪ 带 `scrap: true`
  标记的报废突变），`prepareScrap` 只 `draft(...)`，`perform` 命中 `scrap` 标记时交给 `scrapRequest`，
  成功回执写 `scrapReceipt`；`failure` 在入口把 `ScrapApiError` 归一成 `RepairApiError`，失效复位与
  stale 判定沿用面板既有口径。
- `ScrapView.vue` 的 `executeLoss`/`executeFactory` 与既有五段动作共用同一条 `cause instanceof
  ScrapApiError && 400 <= status < 500` 判据；`occurred`/`evidence` 两个输入位复用既有 `shallowRef`，
  没有新增对象/数组 `ref`。

## 4. 模板派生归位与词表

按功能线 diff 的新增行统计，本批引入的模板派生共 4 文件/13 处（报失 6、报修 2、换机面板 1、报废 4），
另有 3 行共 10 个 primitive `ref`；判据在本批最终态为 `sites=0`/`hard_violations=0`，即全部归位：

- 报失：`showScrappedNote`/`showPendingScrapNote`/`showConfirmedLostNote`/`showReviewLine`/
  `pendingScrapNoteText`/`confirmedLostNoteText`/`reviewLineText`/`showLinkedScrapForm`/
  `showScrapReceipt`/`scrapReceiptHref` 十个 computed，另有 `lifecycleAfterScrap` 与 `isLinkedScrap`
  两个模块级纯函数（与 ARCH-13 的「同一判据只写一处」口径一致）。
- 报修详情：`confirmationSummaryText`/`showScrapReceipt`/`scrapReceiptHref` 三个 computed。
- 换机面板：`scrapReplacementRows`（资格与 `prepareScrap` 的复核同源）/`showScrapForm`/
  `showScrapReceipt`/`scrapReceiptHref` 四个 computed。
- 报废：`rowStateText`/`approvedScopeMethod` 两个模块级函数 + `approvedScopeText`/
  `showApprovedScopeNote`/`showApprovedScopeField`/`showFactoryProofLink`/`factoryProofHref` 五个
  computed；`dispositionText` 与 `completionText` 的装配补全 `LOSS_WRITTEN_OFF` 与可空字段，
  原模板里的四段三元链归零。
- 文案来源逐字保留功能线：`"请填写关联报废原因与确认遗失后的实际情况。"`、
  `"关联报废申请已提交。"`、`"查看关联报废申请"`、`"原借用责任继续保留"`、
  `"资产已进入待报废，…继续保留。"`、`"已确认遗失，本次查找单结案，原保管责任继续保留，…"`、
  `"维修关联报废原因"`/`"报废实物现状"`/`"建议报废处置方式"`/`"核对维修关联报废"`/
  `"查看维修关联报废"`、`"旧机报废原因"`/`"旧机已核实情况"`/`"核对旧机关联报废"`/
  `"查看关联报废单"`、`"待遗失核销"`/`"遗失核销"`/`"厂家收旧"`/`"厂家销毁"`/
  `"前往原维修核对厂家凭证"`/`"按厂家凭证执行报废"`/`"执行遗失核销"`/`"核对执行遗失核销"`。
  本批把散在模板里的同一批字符串收成模块级常量，字面未改。
- 词表迁移：新增段落一律用 `fams-card`/`fams-field`/`fams-control`/`fams-button`/`fams-panel-actions`，
  未新增任何 `<style>` 块与裸色值；`.vue` 内裸 `fetch(` 命中仍为 0。

## 5. 门禁与真实浏览器实测

四道门禁（Node 24.18.0）：仓库根 `npm run lint`（`--max-warnings=0`）退出 0；`vue-tsc --noEmit` 0 错误；
`vitest run` **81 文件 / 366 用例全绿**（合并前架构分支同为 81/366，即功能线新增的 5 条用例在改造前后都
通过且断言一字未改；上一批 ARCH-15/16 为 81/361）；`vite build` 通过。主样式表仍是
`index-CvDOmE8w.css` 31,420 字节 / gzip 5,689 字节，**文件名与字节均未变化**（本批没有往令牌层加规则），
`dist/assets` 仍只有这一份 CSS 产物。全量 JS gzip 266,907（ARCH-15/16 读数）→ **267,748** 字节，
即本批净增 841 字节；分块：`ScrapView` 25.39 → 26.99 kB（gzip 8.05 → 8.09）、`LossView`
53.24 → 56.10 kB（gzip 13.79 → 14.57）、`RepairDetailView` 132.63 → 137.91 kB（gzip 34.29 → 35.70）、
入口 `index` 55.54 → 55.58 kB（gzip 21.14 → 21.16）、`AppChrome` 73.95 / 29.19 不变。

两条判据脚本 `apps/web/src` 全量：`files_with_derivation=0 sites=0`、`hard_violations=0`
（E 类 `lifecycle_shape_review=13`，与 ARCH-09/10/12/13/14/15 同一份保留清单，本批未增减）。

真实浏览器：一次性栈（MySQL 8.4.11 + 本批现场重建的 `fams-product` jar，jar 内含
`from-loss`/`from-repair`/`from-factory`/`execute-loss`/`execute-factory` 五个新端点 + clamav 1.4.6 +
两份 vite dev），改前 worktree `c3e63d3`（合并前的架构分支 tip）与当前工作树（改后）并排跑同一后端、
同一库状态、同一份 **56 条路由 × 3 角色 = 168 个渲染单元**：

```
compared=168 {"SAME":128,"STATUS_DIFF":0,"STRUCT_DIFF":7,"TEXT_DIFF":40,
              "CONTROL_DIFF":7,"LABEL_DIFF":28,"ISSUE_DIFF":0,"PROBE_DIFF":0,"MISSING":0}
```

40 个差异单元恰好是本批触及的 12 条路由：8 条报废路由（列表 + 7 张详情）× 3 角色 = 24，
4 条报失详情（`/loss-reports/3` 与新增的 `4/5/6`）× 3 角色 = 12，2 条报修详情
（`/repairs/4` 三角色 + `/repairs/3` 仅库管）= 4；其余 128 个单元逐字节同屏。
`routes_with_issues` 两侧同为 27 且事件集合逐条同集（越权路由的 403/404 与占位 id 的 404 噪声），
翻页探针 `PROBE_DIFF=0`。

控件读数：只有 2 个单元的控件总数比改前少 1（`admin`/`warehouse` 的 `/scrap-requests/5`，
46→45、47→46），原因是报失来源单的「批准的处置范围」按功能线新形状从输入框改为只读文本，
属功能线设计而非本批遗漏；其余差异单元的控件只增不减。

三段新形状在浏览器里**确实渲染出来**的部分（改后侧逐单元核对）：

- 报失入口：`/loss-reports/6`（已确认遗失、尚未转报废）三角色都出现「从确认遗失发起关联报废」
  段与「关联报废原因」「确认遗失后的实际情况」两个输入和「核对关联报废申请」按钮；
  `/loss-reports/4`、`/loss-reports/5`（已转报废）改读「原保管责任继续保留」/
  「资产已进入待报废…」两行，不再提示下次复核。
- 报修入口：`/repairs/3`（已受理原地维修、尚未转报废）在 `warehouse` 侧出现「从维修发起关联报废」
  段与三个输入；`/repairs/4` 已转报废，两侧文本差只来自履历里的「已移交关联报废」事件名。
- 报废执行：`/scrap-requests/6`（报失来源、已批准已认领）在 `warehouse` 侧出现「执行遗失核销」段、
  「核销复核依据」与「核对执行遗失核销」按钮；列表与详情的状态读法在三角色 24 个报废单元上全部
  从「待实物核验与执行」改为「待遗失核销」；`/scrap-requests/5`（报失来源、待管理员确认）
  的批准范围改为只读「批准的处置范围 遗失核销」。

1440×900 整页截图（`warehouse` 的 `/loss-reports/6`、`/repairs/3`、`/scrap-requests/6`）已人工核对
观感：页头仍由 `AppChrome` 单点渲染，新增段落的卡片、字段与主按钮间距与既有 `fams-*` 段落一致，
没有出现原生未收口控件。

## 6. 偏差与遗留

- **本批把「机械移植」与「按 ARCH 标准收口」合进了同一个提交 `54ce8ae`**，没有 ARCH-12～15 那样的
  独立改造提交。因此实测的改前参照只能取合并前的架构分支 tip `c3e63d3`，读数表达的是
  「新增形状确实渲染出来 + 未触及的 128 个单元零漂移」，而不是「收口前后逐屏等价」。
  收口部分的等价性由功能线自带、本批一字未改的 5 条用例（断言 URL、请求体、`If-Match-Version`、
  重试不换键与回执链接）与既有 361 条用例保证。下一次触及新功能线时仍按「合并批 + 改造批」两段落地。
- 三处新形状**没有**在浏览器里渲染出来：
  1. 「按厂家凭证执行报废」段与「前往原维修核对厂家凭证」回链——需要 `factoryDisposition` 来源的
     报废单，即完整返厂范围申请→批准→建轮次→寄出→收回→登记换机这条链，造单底座仍未接进来
     （与 ARCH-14/15 记下的返厂实测缺口同源，本批仍未闭合）。
  2. 「查看关联报废申请」/「查看维修关联报废」两个回执链接——只在关联报废提交成功后出现，
     普查不执行写操作。
  3. 遗失核销与厂家凭证执行的**写路径**（`/execute-loss`、`/execute-factory`）——同上，
     只由功能线后端 `LossHttpRuntimeCase`/`RepairHttpRuntimeCase` 与前端用例覆盖。
- 造单底座本轮扩展（属实测底座，不是产品代码）：`run-arch-ui-stack.py` 的在用资产卡从 8 张扩到 12 张
  （新形状要额外占用 5 张：两张转报废、一张入口态、两张维修线），`seed-arch-ui-docs.py` 新增
  「已受理原地维修→转关联报废」「确认遗失→转关联报废（待批准 / 已批准已认领）」与两张入口态单据，
  `arch-ui-routes.py` 为它们补了 8 条详情路由（48 → 56 条）。本轮唯一造单失败仍是
  `inbound accept A` 报 `409 INBOUND_ASSET_STATE_CONFLICT`（与 ARCH-14/15 同），属造单数据形状问题，
  与本批无关；入库详情路由仍按同一状态被渲染与比对。
- 小程序侧的报失/维修/报废页面（`apps/miniprogram/src/{loss,repair,repair-replacement,scrap}-pages.ts`、
  `native/pages/*` 与两份 `runtime-*-scrap-http.mjs`）与后端、契约、迁移直接取自功能线候选 `87edd27`，
  本批未改造、未独立复核；小程序侧相对 `api-types` 导入仍是 ARCH-07 的遗留。
- 本批提交与记录批只推送当前 UI 工作分支，未合并 `main`、未建 PR。推送只改变分发状态：除本地四道
  门禁与本批真实浏览器实测外，远端 CI、第七阶段 QA、真实外部联调、独立复核与生产验收仍未执行；
  不改变 D6-21/D6-22 各任务的开放状态。

## 复现

```bash
./mvnw -B -DskipTests package                                   # Java 21+，五个关联报废新端点需在 jar 内
python3 scripts/run-arch-ui-stack.py --web-port 5173 --attachments &   # MySQL + fams-product + clamd + vite dev(改后)
python3 scripts/seed-arch-ui-docs.py                            # 10 类单据 + 关联报废四张与两张入口态
python3 scripts/arch-ui-routes.py --out /tmp/arch-routes-17.json   # 只走 --out，不要重定向 stdout
git worktree add --detach /tmp/fams-base-c3e63d3 c3e63d3
ln -sfn <repo>/node_modules /tmp/fams-base-c3e63d3/node_modules
ln -sfn <repo>/apps/web/node_modules /tmp/fams-base-c3e63d3/apps/web/node_modules
(cd /tmp/fams-base-c3e63d3/apps/web && npx vite --port 5174 --strictPort --host 127.0.0.1)
PW=$(python3 -c "import json;print(json.load(open('/tmp/fams-arch-stack.json'))['password'])")
node scripts/arch-ui-cdp-sweep.mjs --mode sweep --base http://127.0.0.1:5174 --routes /tmp/arch-routes-17.json --password "$PW" --out /tmp/sweep17b-base-all.json --port 9442 --settle 700
node scripts/arch-ui-cdp-sweep.mjs --mode sweep --base http://127.0.0.1:5173 --routes /tmp/arch-routes-17.json --password "$PW" --out /tmp/sweep17c-head-all.json --port 9471 --settle 700   # 最终树（含 10 个 primitive 归 shallowRef 的收尾）
node scripts/arch-ui-cdp-sweep.mjs --mode diff --left /tmp/sweep17b-base-all.json --right /tmp/sweep17c-head-all.json
# 人工核对观感：--mode sweep 带 --filter <路由> --roles warehouse --viewport 1440x900 --shotDir <目录> --label head|base
```

沿用 ARCH-13/14/15 记下的三条执行前提：`node scripts/arch-ui-cdp-sweep.mjs` 必须用 Node 24 前缀
（Node 16 的 `node:util` 无 `parseArgs`）；`--attachments` 需要本机 docker 可用；截图只能通过
`--mode sweep` 带 `--shotDir` 产出（不存在 `--mode shots`），且不带 `--out` 时会在写报告处抛
`ERR_INVALID_ARG_TYPE`，PNG 已先落盘、可忽略该报错。`arch-ui-routes.py` 仍只走 `--out`，
不要重定向 stdout。
