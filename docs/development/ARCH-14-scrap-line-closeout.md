# ARCH-14 独立报废域新增前端按 ARCH 标准收口，并同步返厂报失与找回续修增量（架构批第 14 批）

基线：`349ab8c`（把 `codex/d6-21-loss` 的 D6-22A「独立报废」与 D6-21B2b5a「返厂原机无法收回转报失/
找回续修」合入架构线，冲突全部按 `features/<域>/{api,views,panels}` 形状落地）。判据沿用本分支已建立的
四条，不新立标准：

- `vue-best-practices` + ARCH-08：模板不得计算，派生一律归 `computed` 或行显示模型；判据
  `scripts/audit-web-template-derivations.py`。
- ARCH-09：primitive 状态用 `shallowRef`，迟到抑制按槽位归属 `useRequestLiveness`；判据
  `scripts/audit-web-state-ownership.py`。
- ARCH-10：确认式幂等提交用 `useConfirmedSubmit`，幂等键只归提交底座；判据同上（D 类）。
- ARCH-03/07 + UI-01～21：域内 HTTP 走 `api/http` 的 `requestJson`，`.vue` 内不得裸 `fetch`；
  组件级原始 CSS 归位 `fams-*` 令牌词表。

## 量级

| 文件 | 合并基线 | 本批改后 | 主要变化 |
| --- | --- | --- | --- |
| `features/scrap/api.ts` | 44 行 | 49 行 | 裸 `fetch` → `requestJson` 底座；`ScrapIntent` 拆草稿/带键两型；新增 `scrapRequestState` |
| `features/scrap/views/ScrapView.vue` | 192 行 | 274 行 | 三底座采纳 + 7 处模板派生归位 + 24 处 primitive 归 `shallowRef` + 词表迁移（275 字节含裸色值的 scoped CSS 归零） |
| `features/scrap/views/ScrapView.test.ts` | 93 行 | 93 行 | 只改导入说明符（`@/scrap/api` → `@/features/scrap/api`），两条用例一字未改 |

合并提交 `349ab8c` 全仓 53 文件 +4,257/−106，其中 `apps/web` 12 文件 +501/−23；本批改造提交 2 文件
+205/−118。

## 1. 报废域落成第 13 个垂直切片

功能线把新页面写在 ARCH-06 之前的扁平路径上（`apps/web/src/views/ScrapView.vue` 与
`src/scrap/api.ts`），git 的重命名检测把它们建议到 `features/repair/panels/`——报废不是维修的子域，
本批按 `features/scrap/{api.ts,views/}` 落地，`router.ts` 的懒加载工厂与 `AppChrome` 侧栏条目同源接上。

`scrapRequest` 原来是视图内自持的一段 `fetch → ok? → json()`，含 CSRF 头、幂等键头、可选
`If-Match-Version` 与不合形的错误码直读。现与其余 12 个域同形：域内只保留 `PREFIX`、
`ScrapApiError` 与 `Profile`（兜底码仍是 `SCRAP_REQUEST_FAILED`，缺令牌仍是
`401 AUTH_SESSION_INVALID`），机械序列交给 `api/http.requestJson`。唯一口径差异是底座会先用
`^[A-Z][A-Z0-9_]{0,63}$` 校验服务端 `code`，不合形时落回兜底码——本视图的失败文案只按 `status`
分支（401/403/404 复位、409 换句），该差异对用户可见文案无影响。

幂等键按 ARCH-10 上交：`ScrapIntent` 只留 `path/version/body/summary`，新增
`ScrapSubmission extends ScrapIntent { key }`，建键与「重试不换键」由视图的
`useConfirmedSubmit` 持有，本域确定性判据 `cause instanceof ScrapApiError && 400 <= status < 500`
逐字保留在 `options.failure`。

## 2. 迟到抑制与状态形状

`let generation = 0, live = true` + `current(epoch, owner)` 整体替换为
`useRequestLiveness(() => [identity.value])`。这里可以共用一个实例：`load`、`loadAssets`、`catalog`
三条读取在功能线里本来就共用同一个自持计数与同一个互斥位 `busy`（后发的读取会作废前一次的写回），
换成底座是等价替换而不是行为改变——与 ARCH-13 里「三组互不相干的槽位不能共用一代序号」的情形不同。

24 处 primitive（`page/total/assetPage/assetTotal/selectedAsset/reason/condition/proposed/
decisionReason/approved/observed/serial/occurred/physical/evidence/due/location/custodian/
locationPage/locationTotal/peoplePage/peopleTotal/busy/stale/error/notice`）由 `ref` 归 `shallowRef`；
`rows/detail/assets/places/people` 五个集合保持深跟踪 `ref`——它们整份替换（后两者按分页 `push`
追加），与 `LossRecoveryPanel` 的 `locations` 同形。E 类 `lifecycle_shape_review` 仍是 13 份保留清单，
本批未增减。

## 3. 模板派生归位：7 → 0

判据在合并基线命中 1 文件/7 处，全部在 `ScrapView.vue`：

- 行显示模型：`requestRows` 补 `stateText`（原模板做 `scrapStates[row.status] || row.status`）。
- 判据/绑定型 computed：`refreshDisabled`、`assetPrevDisabled`、`assetNextDisabled`、
  `recordsPrevDisabled`、`recordsNextDisabled`、`locationLoadDisabled`、`peopleLoadDisabled`
  （页大小收成 `PAGE_SIZE = 20` 与 `CATALOG_PAGE_SIZE = 100` 两个常量，模板不再算 `page * 20 >= total`）。
- 文本装配型 computed：`detailTitle`、`detailFactsText`、`detailPlanText`、`detailStateText`、
  `approvedDispositionText`、`dispositionText`（原模板内做 `disposition_status === 'PENDING_DISPOSITION'`
  三元）、`preparedSummary`。

两处刻意的可见形状变化（与 ARCH-12 同口径，不是文案来源改动）：

1. 视图自带的 `<h1>报废与处置</h1>` 与外层 `<section aria-label="报废与处置" class="scrap-page">`
   删除，页头由 `AppChrome` 按路由 `meta.uiGroup/uiTitle` 单点渲染——合并基线上同一屏出现两遍。
2. 路由 meta 只给 `uiGroup: "业务流转"` 与 `uiTitle: "报废与处置"`（两者都沿用功能线自己的导航与
   标题文案），**不给 `uiDesc`**：功能线没写过这段描述，本批不新造产品文案。`AppChrome` 的页头
   渲染条件是 `uiGroup && uiTitle`，`uiDesc` 缺省时该行不渲染，其余 44 条路由的 `uiDesc` 一字未动。

## 4. 随合并落地的返厂报失与找回续修增量

这两段是功能线的事实，本批只做目录归位与底座对齐，不改语义：

- `features/repair/panels/RepairFactoryPanel.vue` 新增 `reportLoss` 动作：确认式提交仍走本面板底座，
  只在 `perform()` 里按路径前缀 `/factory/` 分流到报失域 `lossRequest`，成功文案与「已建立待审批报失」
  分支、失败时把 `LossApiError` 在边界处换成同状态的 `RepairApiError` 后交给既有 `fail/failure`
  判据——与 ARCH-12 对换机面板 `fetchRefs` 的处置同一口径。新增的「返厂原机无法收回」段与
  「查看报失单 N 并上传情况图片」链接按词表落地，`linkedLossPath` 归 computed；
  收回 fieldset 的禁用条件补 `data.lockVersion == null` 并配同一条核对提示，四段判据
  （`reportLossSectionShown`/`noFactoryLockHint`/`receiveFieldsetDisabled`/`linkedLossPath`）
  全部归 computed，模板不留内联判据链。
- `features/loss/panels/LossRecoveryPanel.vue` 补 `recoverFactory/requestFactoryRecovery/executeFactory`
  三族：能力位表、`attemptPaths` 的 `execute-to-factory`、`draftOf` 的返厂分支与 `factoryFacts()`
  核实构造器（校验顺序、错误句「请核对找回原机、实际位置、时间及至少10字证据。」与请求体字段逐字
  取自功能线）；`matchesAttempt` 增加 `FACTORY_REPAIR` 互斥，`attemptRows` 增加 `factored` 并把它
  同时用于「本次申请去向」文案与两个执行按钮的排除项；「已交回原返厂维修」与「已关联正式维修」
  两段提示按 `report.source.factorySource` 归 `showFactoryReturnNote`/`showRepairNote`。
  新段的「加载找回实物位置」按钮复用既有 `loadLocations` 与 `locations` 槽位，因此沿用同一份
  `loadingLocations` 重入守卫，不新起迟到抑制实例。
- `features/loss/views/LossView.vue` 的 `ATTEMPT_ACTIONS` 与 `capabilityFor` 补三条动作复核。
- `features/loss/api.ts` 的 `LossReport.source` 补 `factorySource?: { repairId; roundId }`。

## 5. 造单与实测底座的扩展

- `scripts/run-arch-ui-stack.py` 的主子资产补 8 张同形状的在用卡（`FA-USE60…67`，责任人 3）。
  上一轮 ARCH-13 记的「`/loss-reports/eligible` 只剩 1 行、报失线只造出 `lossPending`」在本轮复现并
  扩大到报废线：`/scrap-requests/eligible` 直接返回 0 行。补卡后报失线造满三张、报废线造满四张。
- `scripts/seed-arch-ui-docs.py` 新增报废段（标签含 `scrap`）与 `--only-scrap` 开关，`step()` 的跳过
  条件改为按 `loss`/`scrap` 两个标签分别放行；四张单据分别停在待管理员确认、已认领待实物执行、
  已执行（带处置行）与已驳回，位置与保管人的 `id/version` 全部取自产品自己的
  `/scrap-requests/{id}/execution-catalog/{kind}`。踩到一条真实判据：`Execution.occurredAt` 必须
  不早于单据 `updated_at`、不晚于服务端 `now+2s` 且已截到毫秒，而 `iso()` 助手把微秒直接抹零会把它
  推到 `updated_at` 之前，首次造单因此报 `400 SCRAP_PHYSICAL_EVIDENCE_INVALID`；改为截秒后再 +1s。
- `scripts/arch-ui-routes.py` 的 `DOMAIN_BY_ROUTE` 补 `scrap-requests → scrap`、`EXTRA_DETAIL` 补
  `scrapProcessing/scrapExecuted/scrapRejected`、`PROBED` 补 `scrap-requests`。路由清单 44 → 48 条：
  新增 5 条报废路由，同时因本轮没有重建 ARCH-13 的找回转调拨链（那是留在 `/tmp` 的一次性驱动脚本，
  未入库），`lossTransfer/lossRecoveredTransfer/lossCreatedTransfer` 三条退出本轮清单。

## 6. 门禁与真实浏览器实测

四道门禁（Node 24.18.0）：根 `npm run lint --max-warnings=0` 退出 0；`vue-tsc --noEmit` 0 错误；
`vitest run` **81 文件 / 357 用例全绿**（合并基线同为 81/357；上一批 ARCH-13 为 80/353，本轮 +1 文件
+4 用例 = 报废视图 2 条 + 功能线报失找回/返厂报失各 1 条，未修改任何既有断言）；`vite build` 通过。
主样式表 `index-CvDOmE8w.css` 31.42 kB / gzip 5.65 kB **文件名与字节均未变化**（本批没有往令牌层加
规则），`dist/assets` 的 CSS 产物从两份归零到只剩这一份——合并基线自带的 `ScrapView-*.css`
0.31 kB / gzip 0.19 kB 随 scoped 块删除；`ScrapView` chunk 12.40 → 15.97 kB（gzip 4.74 → 5.28），
`LossView` 53.24 / 13.79 与 `AppChrome` 73.95 / 29.19 字节不变，入口 `index` 55.57 → 55.54 kB
（gzip 同为 21.14）、`RepairDetailView` 132.64 → 132.63 kB（gzip 同为 34.29），全量 JS gzip
255,824（ARCH-13）→ 259,338（合并基线）→ **259,865** 字节，即本批改造只增加 527 字节。

两条判据脚本 `apps/web/src` 全量：`files_with_derivation=0 sites=0`、`hard_violations=0`
（E 类 `lifecycle_shape_review=13`，与 ARCH-09/10/12/13 同一份保留清单，本批未增减）。合并基线的
同一对读数是 1 文件/7 处派生与 5 项硬违规（4 项 A 类 primitive `ref` + 1 项 D 类自造在途计数），
即 §2/§3 的输入。`.vue` 内裸 `fetch(` 命中 0、裸色值命中 0。

真实浏览器：一次性栈（MySQL 8.4.11 + `fams-product` jar（Java 21 现场重建，含 `/api/v1/scrap-requests`
与 `/loss-reports/factory/{repairId}/rounds/{roundId}` 端点）+ clamav 1.4.6 + 两份 vite dev），
合并基线 worktree `349ab8c`（改前）与当前工作树（改后）并排跑同一后端、同一库状态、同一份
**48 条路由 × 3 角色 = 144 个渲染单元**，比对最终 URL、DOM 结构签名、掩码后文本指纹、控件总数/禁用数、
可点文案集合、翻页探针与 console/异常/HTTP≥400 事件集合：

```
compared=144 {"SAME":129,"STATUS_DIFF":0,"STRUCT_DIFF":15,"TEXT_DIFF":15,
              "CONTROL_DIFF":0,"LABEL_DIFF":0,"ISSUE_DIFF":0,"PROBE_DIFF":0,"MISSING":0}
```

15 个差异单元恰好是 5 条报废路由 × 3 角色，差异只在结构签名与文本（词表迁移与页头去重的预期结果），
控件总数/禁用数、按钮与字段文案集合、翻页探针与运行时事件集合逐条一致；其余 129 个单元（含
`/loss-reports/1..3` 与 `/repairs/1`）逐字节同屏，说明 §4 的返厂/找回增量在既有数据形状下没有改变
任何渲染。两侧文本总长 58,552 → 58,494 字符，差值 58 字符全部来自报废页头 `<h1>` 去重与分页按钮
之间的空白；`routes_with_issues` 两侧同为 27 且事件集合逐条同集（全部是越权路由的 403/404 与占位
id 的 404 噪声）。1440×900 整页截图已核对观感：`warehouse /scrap-requests/2` 同屏渲染「提交报废申请」
「申请进度（四行状态读数：已驳回并恢复 / 已执行报废 / 待实物核验与执行 / 待管理员确认）」与
「核验原机并执行报废」段（批准范围、原机编码/序列号、核验时间、加载更多实际位置、待处置位置、
加载更多处置保管人、处置保管人、计划处置日期、30 自然日默认说明、核验依据、亲见勾选、核对执行报废），
`warehouse /scrap-requests/3` 渲染「处置进度 待实际处置 · 计划日期 …」，与既有词表同构。

## 边界与遗留

- 与 ARCH-08～10/12/13 同口径：不改接口契约、权限、事务、并发与用户可见文案来源；本批可见变化只有
  §3 列出的页头去重与空白口径两处。
- 本轮实测仍只覆盖读路径与渲染形状。以下三处新形状**没有**在浏览器里渲染出来，因为一次性栈造不出
  它们需要的数据：`RepairFactoryPanel` 的「返厂原机无法收回」段与「查看报失单」链接（需要一张已寄出
  且持返厂维修锁的轮次，本轮没有把返厂范围申请→批准→建轮次→寄出这条链跑通）、
  `LossRecoveryPanel` 的「返厂原机找回续修」段（需要 `actions.recoverFactory/requestFactoryRecovery`
  或一张 `destination=FACTORY_REPAIR` 的在办找回申请，同样依赖上一条链）、
  以及 ARCH-13 的找回转调拨三条路由（其造单驱动脚本留在 `/tmp`、未入库，本轮未重建）。
  前两者的行为由功能线新增并一字未改通过的两条用例覆盖（`LossRecoveryPanel.test.ts` 的
  「executes an approved factory recovery only through the existing repair destination」与
  `RepairFactoryPanel.test.ts` 的「factory loss keeps the original guarded request after a lost reply」），
  判据脚本对这三个文件命中 0。建议把这条返厂链补进 `seed-arch-ui-docs.py` 后与下一次触及返厂写路径的
  批次一起实测。
- 实测只读普查不执行写操作：`核对提交报废申请`、`批准报废范围`、`驳回报废申请`、`认领报废处理`、
  `核对执行报废` 五个按钮的点击链路只在造单脚本里经产品 API 走通（四条链全部走通，含实物执行与
  处置行生成），普查本身不提交。
- `inbound accept A` 在本轮造单里报 `409 INBOUND_ASSET_STATE_CONFLICT`（入库明细验收），属造单数据
  形状问题，与报废/报失两域无关；入库详情路由仍按上一轮同样的状态被渲染与比对。
- 小程序侧的报废页面（`apps/miniprogram/src/scrap-pages.ts`、`native/pages/scrap-requests/*`）与
  后端、契约变更直接取自功能线候选 `38d95fc`，本批未改造、未独立复核；小程序侧相对 `api-types`
  导入仍是 ARCH-07 的遗留。
- 本批两个提交（`349ab8c` 合并、`b0fc6eb` 改造）与本记录批只推送当前 UI 工作分支，未合并 `main`、
  未建 PR。推送只改变分发状态：除本地四道门禁与本批真实浏览器实测外，远端 CI、第七阶段 QA、
  真实外部联调、独立复核与生产验收仍未执行；不改变 D6-21/D6-22 各任务的开放状态。

## 复现

```bash
./mvnw -B -DskipTests package                                   # Java 21，报废与返厂报失端点需在 jar 内
python3 scripts/run-arch-ui-stack.py --web-port 5173 --attachments &   # MySQL + fams-product + clamd + vite dev(改后)
python3 scripts/seed-arch-ui-docs.py                            # 10 类单据（含报废四张与报失三张）
python3 scripts/seed-arch-ui-docs.py --only-scrap              # 只重跑报废线并保留既有单据 id
python3 scripts/arch-ui-routes.py                               # router.ts + ids → 48 条路由
git worktree add --detach /tmp/fams-base-349ab8c 349ab8c
ln -sfn <repo>/node_modules /tmp/fams-base-349ab8c/node_modules
ln -sfn <repo>/apps/web/node_modules /tmp/fams-base-349ab8c/apps/web/node_modules
(cd /tmp/fams-base-349ab8c/apps/web && npx vite --port 5174 --strictPort --host 127.0.0.1)
PW=$(python3 -c "import json;print(json.load(open('/tmp/fams-arch-stack.json'))['password'])")
node scripts/arch-ui-cdp-sweep.mjs --mode sweep --base http://127.0.0.1:5173 --routes /tmp/arch-routes.json --password "$PW" --out /tmp/sweep-head-all.json --port 9431 --settle 700
node scripts/arch-ui-cdp-sweep.mjs --mode sweep --base http://127.0.0.1:5174 --routes /tmp/arch-routes.json --password "$PW" --out /tmp/sweep-base-all.json --port 9441 --settle 700
node scripts/arch-ui-cdp-sweep.mjs --mode diff --left /tmp/sweep-base-all.json --right /tmp/sweep-head-all.json
# 人工核对观感时加 --filter scrap --shotDir <目录> --viewport 1440x900
```

沿用 ARCH-13 记下的两条执行前提：`node scripts/arch-ui-cdp-sweep.mjs` 必须用 Node 24 前缀（Node 16 的
`node:util` 无 `parseArgs`）；`--attachments` 需要本机 docker 可用。本轮 `rtk git fetch` 与
`rtk git push` 均正常，未再出现 ARCH-13 记的沙箱无网络情形。
