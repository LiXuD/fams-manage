# ARCH-20 运营报表与关联报失增量前端收口（架构批第 20 批）

基线：合并即收口提交 `49c2634`（第一父 `02aed62` = ARCH-19 记录批，第二父 `6e32585` =
`codex/d6-03b-ci` 功能线 tip）。判据沿用本分支既有口径，不新立标准（同 ARCH-19 §判据）：

- ARCH-08 模板不得计算，派生归 `computed` 或行显示模型（`scripts/audit-web-template-derivations.py`）。
- ARCH-09 primitive 用 `shallowRef`、整份替换集合保持 `ref`、迟到抑制归 `useRequestLiveness`
  （`scripts/audit-web-state-ownership.py`）。
- ARCH-10 确认式幂等提交归 `useConfirmedSubmit`，中间层不转发。
- ARCH-03/07 域内 HTTP 走 `api/http`，`.vue` 内不得裸 `fetch`；新增段落取 `fams-*` 词表。

本批是 ARCH 系列第四次把「机械移植」与「按标准收口」合进同一提交（先例 ARCH-17/19），
实测只有「合并基线 vs 收口后」一份参照，理由与后果写在 §6。

## 量级

| 口径 | 数值 |
| --- | --- |
| 候选范围 | `08c38b2`（上一轮接续基线）之后 59 个提交，tip `6e32585` |
| 候选覆盖 | `codex/d6-03b-ci` 为 `codex/d6-21c-repair-loss`（`9b1a130`）与 `codex/d6-fourth-batch`（`2b8a3ff`）的超集，三条一并合入 |
| 功能线 `apps/web` 增量 | 39 文件 +1,846/−47 |
| 本批合并提交全仓 | 176 文件 +12,863/−2,097 |
| 本批 `apps/web` 落地 | 45 文件 +2,388/−64（17 个新增 + 28 个移植/改造，含 1 个用例改写） |

`apps/web` 落地构成：新建报表域 `features/report/api.ts`（17 行只读底座）与
`features/report/panels/` 16 个面板（OperationsReports 中枢、WorkbenchTodos、
SealedInventoryTasks、InventoryProgressReport、AssetCustodyReport、AssetDistribution、
AssetMovementReport、AssetMovementTrend、AssetTimelineReport、BusinessExceptionReport、
InboundTimingReport、InventoryLateReport、LossOperationsReport、OverdueLoanReport、
RepairOperationsReport、ScrapDispositionReport）；`features/reminder/panels/
ConfigurationAnomalies.vue` 挂进 MessageCenterView；13 个旧路径 `views/` 文件的改动逐段
移植后删除 `views/`；`HomeView.vue` 接快捷入口与两面板；台账与盘点任务列表加深链；
`AppChrome.vue` 报废条目角色补 `AUDIT_READ_ONLY`。

## 1. 功能事实全部取自功能线

1. 工作台：`GET /api/v1/workbench/todos?scope&stage&before` 共享池与本人待办、阶段计数与
   游标翻页；快捷入口五条（单个建卡/批量导入/建立入库单/期初导入核验/创建盘点草稿）按
   角色与 `ASSET_LEDGER_READ` 显隐；台账深链 `?assetCode/?assetId/?action=create`。
2. 运营报表矩阵：`/operations-reports` 汇总 + 14 个专题端点（inventory、inventory-progress、
   assets、asset-distribution、movements、movement-trend、overdue-loans、loss、
   scrap-dispositions、inbound-timing、business-exceptions、inventory-late、asset-timeline、
   repair-operations），各配 `.csv` 同条件导出；保管清单支持生命周期/异常下钻
   （`AssetCustodyReport.defineExpose({ select })` 由中枢驱动）。
3. 关联报失三条入口：盘点未盘项 `POST /inventory-readiness/tasks/{t}/items/{i}/missing/{r}/loss`；
   归还缺失验收 `inspect` 请求体新增 `missingLoss` 与续办 `POST /asset-returns/{id}/missing-loss`；
   内部维修 `POST /loss-reports/internal-repairs/{id}`（`/repairs/{id}` 详情新增
   `requestLoss` 资格位与 `latestLossReportId`）。找回侧新增内部维修分支
   （`recover-to-internal-repair`、`internal-repair-recovery-requests`、
   `execute-to-internal-repair`、去向 `INTERNAL_REPAIR` 与资格位 `recoverInternalRepair`/
   `requestInternalRepairRecovery`）。
4. 配置异常清单 `GET /configuration-anomalies?page&pageSize` 挂消息中心（SYSTEM_ADMIN）；
   待办词典补 `REASSIGNMENT_REQUIRED: "需要转派"`；报废列表/详情按 `AUDIT_READ_ONLY` 单角色
   隐藏附件读取与提交段；导入模板补维修与返厂十六列。

## 2. 冲突一律按 features 形状落地

`rtk git merge --no-ff origin/codex/d6-03b-ci` 产生 31 处冲突，落地方式：

- 16 个「文件位置」冲突：git 按目录改名检测把新增面板建议落到 `features/repair/panels/`
  （与报修无关），按新业务域改落 `features/report/panels/`，域 api 落
  `features/report/api.ts`；`ConfigurationAnomalies` 改落 `features/reminder/panels/`。
- 13 个「修改/删除」：功能线在旧路径 `views/*.vue` 上改，本分支已迁进
  `features/<域>/`——旧路径删除，改动逐段移植（未盘面板盘亏转报失、退还详情缺失转报失、
  报修详情维修转报失、找回内部维修分支、台账与盘点深链、封存 taskVersion 刷新、
  报废 auditOnly、导入十六列、消息中心异常段与词典、返厂轮次状态兜底）。
- `AppChrome.vue`：取本分支 `NAV_GROUPS` 形状，只把报废条目角色扩为
  `SCRAP_ROLES = [...FIELD_ROLES, "AUDIT_READ_ONLY"]`；功能线平铺导航不带入。
- `DESIGN-HANDOFF.md`：三处冲突取本分支版本，登记留本记录批（沿用 ARCH-12～19 口径）。
- 后端、契约、`packages/api-types`、小程序与 `.github` CI 文件全部直接取自功能线候选，
  本批未改造；`api-types` 的 `returnLossReport`/`missingLossLockVersion`/
  `internalRepairSource` 等字段经目录改名检测自动折叠进 features 域文件。

## 3. 底座与状态形状

- 报表域只有读路径：`features/report/api.ts` 以 `requestJson` 组 `reportRequest`
  （`codeFallback: "REPORT_REQUEST_FAILED"`、`noStore`），各面板 catch 文案逐字取自功能线；
  视图内裸 `fetch(` 命中 0。CSV 导出保持 `<a href="/api/v1/….csv">` 完整锚点直下，
  不经域底座（与功能线一致，属下载动作而非读取）。
- 迟到抑制：WorkbenchTodos、OperationsReports、AssetCustodyReport、ConfigurationAnomalies
  的功能线自持 `generation` 归 `useRequestLiveness`；其余 13 个面板功能线本无任何抑制，
  按 ARCH-09 判据（读取路径无迟到抑制即 hard）统一补 `begin/isCurrent` 门禁，只拦写回、
  不改请求与文案。
- 确认式提交：盘亏转报失与缺失转报失并入宿主既有 `useConfirmedSubmit`/
  `prepare→confirm→perform` 一条链，幂等键只归底座；维修转报失意图在
  `RepairDetailView.PageMutation` 上扩 `lossSource` 判别位，`perform` 按
  `"lossSource" in operation` 分流交 `lossRequest`，4xx 确定性失败清键口径与报废侧同源。
- 状态形状：面板 primitive 全部 `shallowRef`；整份替换的 `result/page/rows` 保持 `ref`；
  AssetCustodyReport 的游标历史与 WorkbenchTodos 的 cursors 数组保持整槽替换/索引写入形状。

## 4. 模板派生归位与词表

- 各面板的 `items.length === 0`、`page * 20 >= total`、`history.length === 0`、
  `next === null`、`kind === 'FACTORY'`、`selection?.month.from === month.from && …`、
  `visible.counts.find(…)`、`month.from.slice(0, 7)`、`new Date(…).toLocaleString()`、
  `` `报失 ${id}` `` 一类全部归 computed 或行显示模型（`repairRows`、`lossRows`、
  `monthRows/cells`、`todoRows`、`anomalyRows`、`csvHref`、`trendKey` 等）。
- 保留在模板的只有词表允许的形态：属性路径、字典下标（含单字段 `?? ` 兜底，如
  `statuses[row.status] ?? row.status`）、纯布尔组合、`:to` 对象字面量与事件处理器内的
  赋值/调用。
- 词表：面板根 `fams-panel fams-stack`（嵌套 `fams-card fams-stack`），筛选
  `fams-field-row` + `fams-field`/`fams-control`，表格 `fams-table-scroll`/`fams-table`
  （identity/numeric/action 列与 `fams-table-empty` 语义保留原文案位置），按钮
  `fams-button data-kind`（查询类 secondary、核对类 primary、下钻 link sm），错误
  `fams-notice data-state="error"`，翻页 `fams-pagination-actions`，分布与履历列表
  `fams-timeline`。本批未新增 `data-kind` 值与任何 CSS 类。
- 全角分隔空格 U+3000（报失/报废两处计数行）移入脚本转义 `\u3000`，渲染文本逐字不变，
  以过 `no-irregular-whitespace`（功能线原文在旧路径下不在 eslint 覆盖面内）。

判据最终读数：`files_with_derivation=0 sites=0`、`hard_violations=0`
（E 类 `lifecycle_shape_review=29`：既有 13 处不变，本批 16 个新面板的自持 busy+error
形状入 E 清单——报表读取是「点查询才拉、带游标翻页」的交互形，不套
`usePagedResourceList`，按口径只列清单不判失败）。

## 5. 门禁与真实浏览器实测

四道门禁（Node 24.18.0；`rtk` 默认 Node 16 会让 eslint/vitest 失败）：仓库根
`npm run lint`（`--max-warnings=0`）退出 0；`vue-tsc --noEmit` 退出 0；`vitest run`
**82 文件 / 378 用例全绿**——较上一批 82/375 净增 3 条用例（功能线自带的封存版本刷新
1 条 + 返厂轮次状态兜底 `it.each` 2 条；退还详情 MISSING 用例按功能线新文案与
`missingLoss` 请求体改写，属功能线行为而非本批发明）；`vite build` 退出 0。

体积（同一命令口径：`dist/assets` 全部 `.js` 逐个 `gzip -c` 求和）：

| 树 | 主样式表 | 全量 JS gzip | `index` | `http` 共享块 | `RepairDetailView` | `InventoryTaskDetailView` | `MessageCenterView` | `ScrapView` |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 合并基线 `02aed62` | `index-CvDOmE8w.css` 31,420 / gzip 5,689 | 278,204 | 20,908 | 861 | 35,438 | 34,973 | 5,029 | 11,346 |
| 收口后（本批） | 同名同字节，未变 | 296,351 | 35,860 | 29,437 | 36,016 | 35,821 | 5,860 | 11,445 |

即净增 18,147 字节 gzip：`index` +14,952（16 个报表面板与 HomeView 进入口块）、
`RepairDetailView` +578（维修转报失）、`InventoryTaskDetailView` +848（封存版本刷新）、
`MessageCenterView` +831（配置异常面板）、`ScrapView` +99（审计只读）；chunk 图重排——
改前的 `AppChrome`（28,843）与 `useRequestLiveness` 独立块并入 `http` 共享块
（861→29,437），主样式表文件名与字节均未变化，令牌层零改动。

真实浏览器：一次性栈（MySQL 8.4.11 + clamav 1.4.6 + 本批现场 `./mvnw -B -DskipTests
package` 重建的后端 jar + 两份 vite dev），改前 worktree `02aed62`（5174）与改后工作树
（5173）并排跑同一后端、同一库状态、同一份 **57 条路由 × 3 角色 = 171 个渲染单元**
（本批无新路由，报表是工作台面板）。两份口径读数相同：

```
全站口径与内容区口径（--scope main.fams-main）：
compared=171 {"SAME":164,"STATUS_DIFF":0,"STRUCT_DIFF":7,"TEXT_DIFF":7,"CONTROL_DIFF":7,
              "LABEL_DIFF":7,"ISSUE_DIFF":0,"PROBE_DIFF":0,"MISSING":0}
```

7 个差异单元全部是本批新增渲染面，控件只增不减：

- `/`、`/login`、`/password/change` × admin、warehouse（6 个单元）：改前工作台内容区
  0 控件，改后 81/0——快捷入口 5 条、待办池切换与阶段计数、报表中枢与 14 个专题面板的
  查询按钮；employee 无该面，3 个单元一致。warehouse 账号角色含 ASSET_ADMIN，与后端
  可见性一致。
- `/repairs/3` × warehouse：内部维修实物缺失段渲染，33/6 → 37/6（+3 输入 +1 核对按钮，
  禁用数不变）。

`finalUrl` 171 单元全同，两侧 `routes_with_issues` 同为 27，事件集合逐条同集，
`PROBE_DIFF=0`。首轮全站口径曾出现 6 条 `VUE_ROUTER_R0100`（功能线旧路由把报修详情内嵌
在 `repairs` 上，报表与待办下钻带 `params.id` 在拆分路由下被丢弃）——把三处下钻目标映射
为 `repair-detail` 路由名后 ISSUE_DIFF 归零，目标页面与功能线内嵌详情等价，属结构映射，
登记进 §6 偏差。1440×900 整页截图（warehouse 的 `/`、`/repairs/3`、`/asset-returns/1`）
已人工核对观感：工作台呈 fams 面板矩阵与查询按钮行，报修详情「内部维修实物缺失」段与相邻
「从维修发起关联报废」段同形同距，没有出现未收口的原生控件。

## 6. 偏差与遗留

- **合并与收口同提交**（系列第四次）：没有「移植后 vs 收口后」第二份实测参照；收口前后
  由「164/171 同屏 + 7 个差异全部落在新增渲染面且控件只增不减」间接证明。理由同
  ARCH-19：报表面板在架构分支没有对应物，首落形状即目标形状；逐字照搬会在合并里留下
  已知不绿的判据（裸 fetch、自派生模板、无抑制读取路径）。
- **报修下钻路由名映射**：功能线 `repairs` 内嵌详情在本分支拆为 `repairs` 列表 +
  `repair-detail` 详情，三处下钻（WorkbenchTodos、RepairOperationsReport、
  AssetTimelineReport）目标路由名改映射，URL 形状由 `/repairs` 变为 `/repairs/{id}`，
  是结构等价而非逐字节等价。
- **报表数据面只跑了种子库形状**：普查里报表面板停在「查询」初始态（面板均为点击才读取），
  14 个专题端点的结果渲染、CSV 内容与游标翻页未在浏览器里逐一点开；配置异常面板经
  MessageCenterView 用例覆盖，报表面板没有配套用例文件（功能线未交付）。
- **写路径未在浏览器执行**：盘亏/归还/维修三条转报失、缺失续办、内部维修找回段都只有
  用例与后端证据，普查只读渲染；与 ARCH-15～19 同源缺口，本批未闭合。
- **造单底座未扩展**：沿用 ARCH-17 的单据集与 57 条路由清单；既有失败仍是
  `inbound accept A` 409 `INBOUND_ASSET_STATE_CONFLICT`，属造单数据形状问题，与本批无关。
- **未映射待办阶段按功能线原样显示码值**：工作台待办里 `EXECUTE`、`WAREHOUSE_EXECUTION`
  等阶段仍显示码值（词典兜底即码值本身），补词典属改用户可见文案来源，本批按红线不动。
- **小程序侧未改造**：本批合并带入的 `apps/miniprogram` 页面与 native wxml 增量直接取自
  功能线候选，未收口、未独立复核。
- **`.github/workflows/required-ci.yml` 随功能线入库**：远端 CI 是否生效属 D6-01B/03B 的
  另行验收，本批未验证远端流水线。
- **提交类型偏差**：合并提交标题沿用 ARCH-18/19 的合规 `chore(web)` 先例（仓库 commitlint
  不允许 `merge` 类型），`body-max-line-length=100` 逐行核过；`.githooks/commit-msg`
  仍未安装，属手工校验，不表示提交门禁已生效。
- 本批代码批与记录批只推送当前 UI 工作分支，未合并 `main`、未建 PR。推送只改变分发状态：
  除本地四道门禁与本批真实浏览器实测外，远端 CI、第七阶段 QA、真实外部联调、独立复核与
  生产验收仍未执行；不改变 D6-25 及各关联任务的开放状态。

## 复现

```bash
./mvnw -B -DskipTests package                                    # Java 21+，jar 内需含 operations-reports/workbench/configuration-anomalies/missing-loss/internal-repairs 映射
python3 scripts/run-arch-ui-stack.py --web-port 5173 --attachments &   # MySQL + fams-product + clamd + vite dev(改后)
python3 scripts/seed-arch-ui-docs.py                             # 10 类单据 + 关联报废四张；本轮唯一失败仍是 inbound accept A 409
python3 scripts/arch-ui-routes.py --out /tmp/arch-routes-20.json   # 57 条路由（本批无新路由）；只走 --out
git worktree add --detach /tmp/fams-base-02aed62 02aed62        # 改前参照（ARCH-19 记录批）
ln -sfn <repo>/node_modules          /tmp/fams-base-02aed62/node_modules
ln -sfn <repo>/apps/web/node_modules /tmp/fams-base-02aed62/apps/web/node_modules
(cd /tmp/fams-base-02aed62/apps/web && npx vite --port 5174 --strictPort --host 127.0.0.1)
# 关键前提：worktree 的 vite dev server 先 curl 预热再普查
PW=$(python3 -c "import json;print(json.load(open('/tmp/fams-arch-stack.json'))['password'])")
node scripts/arch-ui-cdp-sweep.mjs --mode sweep --base http://127.0.0.1:5174 --routes /tmp/arch-routes-20.json --password "$PW" --out /tmp/sweep20b-full.json --port 9442 --settle 700
node scripts/arch-ui-cdp-sweep.mjs --mode sweep --base http://127.0.0.1:5173 --routes /tmp/arch-routes-20.json --password "$PW" --out /tmp/sweep20h-full.json --port 9471 --settle 700
node scripts/arch-ui-cdp-sweep.mjs --mode diff --left /tmp/sweep20b-full.json --right /tmp/sweep20h-full.json    # 全站口径
# 内容区口径：以上两条 sweep 各加 --scope main.fams-main，out 换成 *-content.json，再 diff 这两份
# 人工核对观感：--routes 传只含 /、/repairs/3、/asset-returns/1 的子集 + --roles warehouse --viewport 1440x900 --shotDir <目录> --label head
```

沿用 ARCH-13～19 记下的执行前提：`node scripts/arch-ui-cdp-sweep.mjs` 必须用 Node 24 前缀
（Node 16 的 `node:util` 无 `parseArgs`，diff 子命令同样受影响）；`--attachments` 需要本机
docker 可用；截图只能通过 `--mode sweep` 带 `--shotDir` 产出；`--filter` 是子串匹配，
只截特定路由时用路由子集文件。
