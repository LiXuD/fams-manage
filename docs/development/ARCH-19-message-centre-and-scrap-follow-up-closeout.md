# ARCH-19 消息中心与报废三段后续流程前端收口（架构批第 19 批）

基线：合并即收口提交 `940d3c4`（第一父 `83b0777` = ARCH-18 记录批，第二父 `08c38b2` =
`codex/d6-21-loss` 功能线 tip）。判据沿用本分支既有口径，不新立标准：

- `vue-best-practices` + ARCH-08：模板不得计算，派生一律归 `computed` 或行显示模型；判据
  `scripts/audit-web-template-derivations.py`。
- ARCH-09：primitive 状态用 `shallowRef`，整份替换的集合保持 `ref`，迟到抑制归
  `useRequestLiveness`；判据 `scripts/audit-web-state-ownership.py`。
- ARCH-10：确认式幂等提交用 `useConfirmedSubmit`，幂等键只归提交底座；中间层不得只做转发。
- ARCH-03/07 + ARCH-09 底座：域内 HTTP 走 `api/http` 的 `requestJson`/`requestBlob`，`.vue` 内
  不得裸 `fetch`；新增段落一律取 `fams-*` 词表，不新增 `<style>` 块与裸色值。

本批是 ARCH 系列第三次把「机械移植」与「按标准收口」合进同一个提交（先例 ARCH-17），
因此实测只有「合并基线 vs 收口后」一份参照，没有 ARCH-12～15/18 的「移植后 vs 收口后」
第二份。理由与后果写在 §6。

## 量级

| 口径 | 数值 |
| --- | --- |
| 候选范围 | `64122d8`（上一轮接续基线）之后 11 个提交，tip `08c38b2` |
| 功能线 `apps/web` 增量 | 9 文件 +901/−9 |
| 本批合并提交全仓 | 76 文件 +7,460/−291 |
| 本批 `apps/web` 落地 | 10 文件 +1,088/−10 |

`apps/web` 十个文件：新增 `features/reminder/api.ts`（77 行）与
`features/reminder/views/MessageCenterView.vue`（248 行）及其 127 行用例；
`features/scrap/api.ts` +44（`initialSource`、三段后续流程对象、16 个资格位、三段状态词典）；
`features/scrap/views/ScrapView.vue` +345（纠正/冲正/找回三段与 `bin` 目录）与其用例 +248；
`features/asset-import/views/AssetImportView.vue` +2（期初报废四列映射）；
`components/AppChrome.vue` +1（侧栏条目）；`router.ts` +2（`/messages` 懒路由与 meta）；
`router.test.ts` 懒路由计数 37→38。

## 1. 功能事实全部取自功能线

候选相对上一轮接续基线交付 11 个提交：`69c139a` 期初待报废承接、`abf0659` 误报废冲正、
`0f871e7` 受控纠正、`eff2879` 报废后找回、`4bccb4d` 企业工作日日历、`6215539` 跨域提醒扫描、
`1d28cec` 双端站内消息中心、`9c957f6` 写操作门禁改读契约声明、`5ef6eb7` 值班提醒人配置、
`4a02a18` P3 收尾计划、`08c38b2` 未读升级值班提醒。前端事实四条：

1. 报废三段后续流程共 12 个新端点：`POST /scrap-requests/{id}/corrections` 与
   `/corrections/{id}/{decision|claim|execute}`、`/{id}/reversals` 与
   `/reversals/{id}/{decision|claim|execute}`、`/{id}/recoveries` 与
   `/recoveries/{id}/{decision|claim|execute}`（`ScrapController` 的 `@RequestMapping` 已在
   `/api/v1/scrap-requests` 前缀下，故子路径不带单据 id）。资格位由读服务按角色与单据状态发放，
   前端不预判：`initiate/decide/claim/execute` × `Correction/Reversal/Recovery`。
2. 期初承接：`initialSource {batchId, sheetRow}` 只在期初报废申请上出现，读法为
   「期初承接批次 … 第 … 行：报废事实来自上线前既有记录，批准仍需管理员另行决定」。
   处置行新增 `REVERSED` 读法「已因冲正撤销」，单据状态新增 `CORRECTED`
   「冻结保管已受控纠正恢复」。
3. 消息中心：`GET /messages?view=INBOX|EXCEPTION&page&pageSize` 一条 UNION ALL 同读待办与
   业务提醒，`POST /messages/{kind}/{id}/read` 只写个人回执；值班配置
   `GET /duty-assignments` 与 `PUT /duty-assignments/{roleCode}`（`If-Match-Version` +
   `Idempotency-Key`）。`EXCEPTION` 视图只对管理员/库管可见。
4. 侧栏与路由：功能线在 `AppChrome` 平铺导航里加了一条「消息中心与异常清单」，路由
   `/messages` 无 `:id`。

## 2. 冲突一律按 features 形状落地

`rtk git merge --no-ff origin/codex/d6-21-loss` 产生 8 处冲突，落地方式：

- 3 个「修改/删除」：功能线在旧路径 `apps/web/src/views/ScrapView.vue`、
  `views/AssetImportView.vue`、`scrap/api.ts` 上改，本分支已把它们移进
  `features/<域>/{api,views}/`——旧路径删除，改动逐段移植进对应域文件。
- 2 个「文件位置」冲突：git 按改名检测把 `views/MessageCenterView.{vue,test.ts}` 建议落到
  `features/repair/panels/`（与报修面板无关），按新业务域改落
  `features/reminder/views/`，`reminder/api.ts` 同步落 `features/reminder/api.ts`。
- `router.ts` 与 `AppChrome.vue`：取本分支的懒加载与 `NAV_GROUPS` 形状，只补
  `/messages` 一条懒路由（含 `uiGroup/uiTitle/uiDesc`）与一条侧栏条目；功能线的平铺
  `import … from "@/views/…"` 形状不带入。
- `DESIGN-HANDOFF.md`：取本分支版本，登记留到本记录批（沿用 ARCH-12～18 口径）。
- 三段后续流程按功能线 DOM 顺序挂在取消段之后、认领段之前，与 ARCH-18 的「不并列新页」
  一致；消息中心是新域新页，没有宿主视图可挂。

## 3. 底座与状态形状

- 报废三段共 12 个动作并入视图既有的唯一一条 `prepare → draft → adopt → send` 链：
  幂等键仍只由 `useConfirmedSubmit` 在起草时生成、重试不换键，确定性失败判据沿用
  `cause instanceof ScrapApiError && 400 <= status < 500`；本批没有新增第二套提交底座。
  三段共用视图已有的 `observed/serial/occurred/evidence/physical` 输入位，
  `clearFacts()` 一并复位新增的 13 个状态位（`bins`/`binPage`/`binTotal` 与三段的
  目标仓位、原因、决定原因等）。
- 迟到抑制：`catalog(kind)` 扩到 `bin` 后仍走同一条 `useRequestLiveness`；资格位门禁按
  功能线口径逐条移植（`execute`、`dispose || initiateRecovery`、
  `initiateCorrection || initiateReversal || initiateRecovery`），未放宽也未收紧。
- 消息中心：功能线自持的 `let generation = 0, dutyGeneration = 0, live = true` 全部归两条
  `useRequestLiveness`（消息侧与值班侧各自独立计数，互不作废）；已读登记与值班登记各占一条
  `useConfirmedSubmit`，值班侧确定性判据取本域 `ReminderApiError && status < 500`，
  消息侧取 `BusinessApiError || AuthApiError` 的 4xx 区间。
- 域内 HTTP：`features/reminder/api.ts` 的 `dutyRequest` 从裸 `fetch` 改为
  `requestJson` + 本域 `Profile`（`codeFallback: "DUTY_REQUEST_FAILED"`、PUT + 写三件套），
  状态码到中文文案的映射 `reminderMessage` 逐字保留；消息读写走全站既有的
  `api/business` 内核（`businessMessage` 与功能线 `loanMessage` 文案逐字相同，
  仅内部兜底码由 `LOAN_REQUEST_FAILED` 变为 `BUSINESS_REQUEST_FAILED`，不是用户可见文案）。
  跨域引用 `formatProxyTime`（`features/exception/time`）沿用本分支既有做法，
  与 `TransferView` 引用 `features/asset/api` 的 `lifecycleLabels` 同类。
- 视图内裸 `fetch(` 命中 0；本批未新增 `<style>` 块，令牌层零改动。

## 4. 模板派生归位与词表

功能线形状里必须收口的点：

- 消息中心的行标题 `{{ label(message) }} · {{ message.read ? "已读" : "未读" }}`、
  业务名与池的拼接、`登记 … 截止 …`、以及 `:to="destination(message)!"` 全部归
  `messageRows` 行显示模型；值班区块的角色名、失效标记与生效时间归 `dutyRows`；
  分页与切换按钮的 `busy || view === 'INBOX'`、`page * 20 >= total`、
  `busy || !!pendingRead` 归 `inboxDisabled`/`prevDisabled`/`readDisabled` 等 computed；
  区块 `aria-label` 的三元归 `listLabel`；空态文案归 `emptyText`。
- 报废三段的 `bins.length >= binTotal` 等三处目录禁用式归 `binLoadDisabled`（沿用既有
  `locationLoadDisabled`/`peopleLoadDisabled` 同族），进度行与批准范围摘要归
  `correctionProgressText`/`correctionScopeText`/`correctionTargetText` 等九个 computed，
  期初批次行归 `initialSourceText`。
- 五处写在 `prepare` 里的必填文案归模块常量
  `SUBFLOW_DECISION_REQUIRED_MESSAGE`/`SUBFLOW_IDENTITY_REQUIRED_MESSAGE`/
  `CORRECTION_TARGET_REQUIRED_MESSAGE`/`REVERSAL_TARGET_REQUIRED_MESSAGE`/
  `RECOVERY_FACTS_REQUIRED_MESSAGE`，与既有 `APPROVE_SCOPE_REQUIRED_MESSAGE` 同族；
  确认摘要仍留在 `prepare` 分支里，与其余动作同源。
- 词表：段容器 `fams-card fams-stack`，字段 `fams-field`/`fams-field-wide` +
  `fams-field-label` + `fams-control`，按钮归 `fams-panel-actions`/`fams-form-actions` 并取
  既有 `data-kind`（批准与核对执行为 primary、驳回为 danger、认领与加载更多为 secondary），
  消息卡片为 `fams-panel` + `fams-card` + `fams-panel-head` + `fams-pagination`，
  错误提示为 `fams-notice data-state="error"`。本批未新增 `data-kind` 值。
- 消息中心视图自带的 `<h1>消息中心</h1>` 与说明段去重：标题与静态说明改由路由 meta 经
  `AppChrome` 单点渲染，动态的「未读 N 条」留在正文 `role="status"` 一行。
  这是本批唯一的可见文案位置变化，文案本身逐字取自功能线。

判据最终读数：`files_with_derivation=0 sites=0`、`hard_violations=0`
（E 类 `lifecycle_shape_review=13`，与 ARCH-09/10/12…18 同一份保留清单，本批未增减）。

## 5. 门禁与真实浏览器实测

四道门禁（Node 24.18.0；`rtk` 默认 Node 16 会让 eslint/vitest 失败）：仓库根
`npm run lint`（`--max-warnings=0`）退出 0；`vue-tsc --noEmit` 退出 0（0 错误）；
`vitest run` **82 文件 / 375 用例全绿**——较上一批 81/366 增加 1 个用例文件与 9 条用例
（功能线自带的消息中心 5 条 + 报废三段与期初批次行 4 条）；`vite build` 退出 0。

体积（同一命令口径：`dist/assets` 下全部 `.js` 逐个 `gzip -c` 求和）：

| 树 | 主样式表 | 全量 JS gzip | `ScrapView` 分块 | `MessageCenterView` 分块 | `index` | `AppChrome` |
| --- | --- | --- | --- | --- | --- | --- |
| 合并基线 `83b0777` | `index-CvDOmE8w.css` 31,420 / gzip 5,689 | 269,539 | 27,855 / 8,150 | 无此分块 | 55,586 / 20,773 | 73,951 / 28,814 |
| 收口后 `940d3c4` | 同名同字节，未变 | 278,204 | 50,100 / 11,346 | 11,991 / 5,029 | 55,950 / 20,908 | 74,006 / 28,843 |

即本批净增 8,665 字节 gzip，其中报废三段 3,196、消息中心 5,029（新分块）、
路由与侧栏 258；主样式表文件名与字节均未变化，令牌层零改动。

真实浏览器：一次性栈（MySQL 8.4.11 固定 digest + 本批现场 `./mvnw -DskipTests package`
重建的 `fams-product` jar + clamav 1.4.6 + 两份 vite dev），改前 worktree `83b0777`（5174）
与改后工作树（5173）并排跑同一后端、同一库状态、同一份 **57 条路由 × 3 角色 = 171 个
渲染单元**。本批给实测底座加了 `--scope` 参数（默认口径不变：文本根仍是
`document.body`、控件根仍是 `document`），因此给出两份读数：

```
全站口径（含侧栏与页头）：
compared=171 {"SAME":0,"STATUS_DIFF":0,"STRUCT_DIFF":171,"TEXT_DIFF":171,"CONTROL_DIFF":171,
              "LABEL_DIFF":8,"ISSUE_DIFF":3,"PROBE_DIFF":2,"MISSING":0}
内容区口径（--scope main.fams-main）：
compared=171 {"SAME":163,"STATUS_DIFF":0,"STRUCT_DIFF":8,"TEXT_DIFF":8,"CONTROL_DIFF":8,
              "LABEL_DIFF":8,"ISSUE_DIFF":3,"PROBE_DIFF":0,"MISSING":0}
```

全站口径 171 个单元全差异只由一件事解释：侧栏新增一条「消息中心与异常清单」导航，
使每个单元的控件数 +1、文本多一个词（逐单元核对 `controls N/x -> N+1/x`）；
`PROBE_DIFF=2` 是 `/assets` 翻页探针返回值里的随机页 token，两侧都是
`clicked|第 2 页|<token>`，不是行为差异。内容区口径把这条全站级增量排除后，
163 单元逐字节同屏，8 个差异全部是本批真正新增的渲染面：

- `/messages` × 3 角色：改前无该路由（内容区 0 控件、1 条 issue），改后渲染
  admin 25/3、warehouse 41/3、employee 4/3 个控件——employee 看不到「异常清单」页签与
  值班区块，与后端可见性分级一致；`ISSUE_DIFF=3` 即这三条改前 404 噪声在改后消失。
- `/scrap-requests/3`（已执行未处置）× 3 角色：冲正登记段渲染，控件只增不减
  （admin 19→23、warehouse 27→31、employee 18→22，禁用数两侧同为 4）。
- `/scrap-requests/6`（遗失核销后）× 2 角色：找回登记段渲染
  （admin 19→28、warehouse 19→28）；employee 该单无资格位，故该单元一致。

`finalUrl` 171 单元全同，两侧 `routes_with_issues` 为 30（改前）/ 27（改后），
差值恰为改前 `/messages` 的三条 404；其余事件集合逐条同集。
1440×900 整页截图（`warehouse` 的 `/messages` 与 `/scrap-requests/6`）已人工核对观感：
消息中心呈 `fams-panel` 列表 + `fams-card` 行卡 + 分页摘要与按钮 + 底部值班区块；
报废详情的「登记报废后找回」段呈 `fams-card` 卡片、带字段标签的 datetime/select/textarea、
secondary sm 的「加载更多…」与 primary 的「核对登记报废后找回」，与相邻段落间距一致，
没有出现未收口的原生控件。

## 6. 偏差与遗留

- **合并与收口同提交**：本批没有 ARCH-12～15/18 那样的独立改造提交，因此缺少
  「移植后 vs 收口后」的第二份实测参照，收口前后只由「内容区口径 163/171 同屏 + 8 个差异
  全部落在新增渲染面」间接证明，而非由同一功能形状的两个版本直接证明。原因有二：
  消息中心在架构分支没有对应物，其首落形状就是目标形状；报废三段要挂进一个已经改用
  `useRequestLiveness`/`useConfirmedSubmit` 的宿主视图，逐字照搬功能线形状会在合并提交里
  留下已知不绿的判据（自持 `generation/live` 与 `bins.length >= binTotal` 一类派生）。
  这是 ARCH-17 记过的同一类偏差，本批如实重犯，不宣称已闭合。
- **纠正段与找回的后三段未渲染**：`RESTORE_REQUIRED`（原保管关系失效）状态在一次性栈里
  用公开 API 造不出来，`initiateCorrection/decideCorrection/claimCorrection/executeCorrection`
  四段本批未在浏览器出现；找回登记成功后本应继续渲染的「确认/认领/执行找回」三段也因
  登记请求被服务端拒绝（见下条）而未出现。三段的请求体、版本头与幂等键只有功能线自带的
  4 条 `apps/web` 用例与后端 `ScrapCorrection/Reversal/RecoveryUpgradeRuntimeCase` 覆盖。
- **造单底座本批未扩展**：沿用 ARCH-17 扩好的单据集与 57 条路由清单。本轮在栈内用真实
  HTTP 追加了两步状态迁移：`warehouse` 对报废单 6 执行 `execute-loss`（200，转
  `EXECUTED` + `LOSS_WRITTEN_OFF`，`initiateRecovery` 随之为真，找回登记段因此得以渲染）；
  随后一次 `POST /scrap-requests/6/recoveries` 返回 400
  `SCRAP_RECOVERY_FACTS_INVALID`（临时保管位置/经办人/拟恢复仓位取自该单
  `execution-catalog` 的 location/employee/bin 三个目录首页，仍被判非法），未重试成功，
  故找回的后三段留作下一批。既有失败仍是 `inbound accept A` 报
  `409 INBOUND_ASSET_STATE_CONFLICT`，与 ARCH-14～18 同，属造单数据形状问题，与本批无关。
- **写路径未在浏览器执行**：普查只读渲染，三段 12 个端点、已读登记 `POST /messages/{kind}/{id}/read`
  与值班登记 `PUT /duty-assignments/{roleCode}` 都没有在浏览器里点过；这与 ARCH-15～18
  记下的同源缺口一致，本批未闭合。
- **未映射待办阶段按功能线原样显示码值**：截图里可见「待办 · EXECUTE 未读」「待办 ·
  RECOVERY_VERIFY 未读」「待办 · WAREHOUSE_EXECUTION 未读」等，因为功能线的
  `TODOS` 词典未覆盖这些阶段且兜底就是码值本身。补词典属于改用户可见文案来源，
  本批按红线不动，登记为功能线侧待办。
- **小程序侧未改造**：`apps/miniprogram/src/messages-pages.ts`、`pages/{messages,exceptions}`
  与报废三段的 `scrap-pages.ts`/native wxml 直接取自功能线候选 `08c38b2`，本批未收口、
  未独立复核；小程序相对 `api-types` 的导入仍是 ARCH-07 的遗留。
- **实测底座变更**：`scripts/arch-ui-cdp-sweep.mjs` 新增 `--scope`（内容区口径）。不带该
  参数时文本根仍是 `document.body`、控件根仍是 `document`、结构根仍是 `#app`，
  与 ARCH-11～18 的读数口径一致；本批的全站口径读数因此与历史批次可比。
- **提交类型偏差**：合并提交标题沿用 ARCH-18 回到合规 `chore(web)` 的先例
  （仓库固定的 commitlint 不允许 `merge` 类型），`body-max-line-length=100` 逐行核过；
  `.githooks/commit-msg` 仍未安装（`core.hooksPath` 未设置），这是手工校验，
  不表示提交门禁已生效。
- 本批记录批与代码批只推送当前 UI 工作分支，未合并 `main`、未建 PR。推送只改变分发状态：
  除本地四道门禁与本批真实浏览器实测外，远端 CI、第七阶段 QA、真实外部联调、独立复核与
  生产验收仍未执行；不改变 D6-21/D6-22/D6-23/D6-24 各任务的开放状态。

## 复现

```bash
./mvnw -B -DskipTests package                                    # Java 21+，jar 内需含 corrections/reversals/recoveries/messages/duty 映射
python3 scripts/run-arch-ui-stack.py --web-port 5173 --attachments &   # MySQL + fams-product + clamd + vite dev(改后)
python3 scripts/seed-arch-ui-docs.py                             # 10 类单据 + 关联报废四张与入口态；本轮唯一失败仍是 inbound accept A 409
# 追加两步真实状态迁移（本批找回段的渲染前提；recoveries 登记以 400 结束，不重试）
#   warehouse: GET /scrap-requests/6 -> POST /scrap-requests/6/execute-loss {assetVersion,lockVersion,ownershipVersion,evidence}
#   warehouse: GET /scrap-requests/6/execution-catalog/{location,employee,bin} -> POST /scrap-requests/6/recoveries -> 400 SCRAP_RECOVERY_FACTS_INVALID
python3 scripts/arch-ui-routes.py --out /tmp/arch-routes-19.json   # 57 条路由（新增 /messages）；只走 --out，不要重定向 stdout
git worktree add --detach /tmp/fams-base-83b0777 83b0777        # 改前参照（ARCH-18 记录批）
ln -sfn <repo>/node_modules          /tmp/fams-base-83b0777/node_modules
ln -sfn <repo>/apps/web/node_modules /tmp/fams-base-83b0777/apps/web/node_modules
(cd /tmp/fams-base-83b0777/apps/web && npx vite --port 5174 --strictPort --host 127.0.0.1)
# 关键前提：worktree 的 vite dev server 首次请求会触发依赖预优化并重启，先 curl 预热再普查
PW=$(python3 -c "import json;print(json.load(open('/tmp/fams-arch-stack.json'))['password'])")
node scripts/arch-ui-cdp-sweep.mjs --mode sweep --base http://127.0.0.1:5174 --routes /tmp/arch-routes-19.json --password "$PW" --out /tmp/sweep19b-base.json --port 9442 --settle 700
node scripts/arch-ui-cdp-sweep.mjs --mode sweep --base http://127.0.0.1:5173 --routes /tmp/arch-routes-19.json --password "$PW" --out /tmp/sweep19b-head.json --port 9471 --settle 700
node scripts/arch-ui-cdp-sweep.mjs --mode diff --left /tmp/sweep19b-base.json --right /tmp/sweep19b-head.json    # 全站口径
# 内容区口径：以上两条 sweep 各加 --scope main.fams-main，out 换成 *-content.json，再 diff 这两份
# 人工核对观感：--mode sweep 带 --filter /messages --roles warehouse --viewport 1440x900 --shotDir <目录> --label head
```

沿用 ARCH-13～18 记下的执行前提：`node scripts/arch-ui-cdp-sweep.mjs` 必须用 Node 24 前缀
（Node 16 的 `node:util` 无 `parseArgs`）；`--attachments` 需要本机 docker 可用；截图只能通过
`--mode sweep` 带 `--shotDir` 产出；`arch-ui-routes.py` 仍只走 `--out`。
