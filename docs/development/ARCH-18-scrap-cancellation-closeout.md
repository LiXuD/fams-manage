# ARCH-18 报废受控取消前端按 ARCH 标准收口（架构批第 18 批）

基线：合并提交 `07ae15b`（父提交 `fbc54f3` = 上一批 ARCH-17 记录批，第二父 `64122d8` =
`codex/d6-21-loss` 功能线 tip）+ 改造提交 `1c13c02`。判据沿用本分支已建立的口径，不新立标准：

- `vue-best-practices` + ARCH-08：模板不得计算，派生一律归 `computed` 或行显示模型；判据
  `scripts/audit-web-template-derivations.py`。
- ARCH-09：primitive 状态用 `shallowRef`，整份替换的集合保持 `ref`，迟到抑制归 `useRequestLiveness`；
  判据 `scripts/audit-web-state-ownership.py`。
- ARCH-10：确认式幂等提交用 `useConfirmedSubmit`，幂等键只归提交底座。
- ARCH-03/07 + ARCH-09 底座：域内 HTTP 走 `api/http` 的 `requestJson`/`requestBlob`，`.vue` 内不得裸
  `fetch`；新增段落一律取 `fams-*` 词表，不新增 `<style>` 块与裸色值。

本批是 ARCH 系列里第二次把「机械移植」与「按标准收口」拆成两个提交（ARCH-12～15 为两段，
ARCH-16 零前端增量、ARCH-17 一段）。因此本批的实测多了一份真正的「收口前」参照：
`07ae15b` 的 worktree 与最终工作树并排跑同一个后端、同一份库状态。

## 量级

| 文件 | 合并基线 `fbc54f3` | 移植后 `07ae15b` | 收口后 `1c13c02` | 主要变化 |
| --- | --- | --- | --- | --- |
| `features/scrap/api.ts` | 116 行 | 116 行 | 116 行 | `ScrapRequest.actions` 补 `cancel?: boolean`（git 按 features 形状手工并入，收口批未再动） |
| `features/scrap/views/ScrapView.vue` | 530 行 | 539 行 | 544 行 | `prepare` 动作枚举补 `cancel`、cancel 分支与详情段；收口批把模板否定条件归 `computed`、错误文案归模块常量、取消段取 `fams-*` 词表 |

合并提交 `07ae15b` 全仓 20 文件 +413/−32，其中 `apps/web` 2 文件 +10/−2；改造提交 `1c13c02`
1 文件 +9/−4。本批 `apps/web` 累计 +15/−2。后端、契约、小程序与 D6-22 证据文件直接取功能线。

## 1. 取消形状的功能事实全部取自功能线

候选 `codex/d6-21-loss` 相对上一轮接续基线 `87edd27` 是 2 个提交：`3cf2c1e`（把确认遗失的返厂
原机接入双端遗失核销，`apps/web` 零增量）与 `64122d8`（开放独立报废执行前的受控取消）。
本批前端增量全部来自 `64122d8`，功能事实为：

1. 新端点 `POST /scrap-requests/{id}/cancel`，`If-Match-Version` 取报废单自身 `version`，
   `Idempotency-Key` 必填，请求体是契约新增的 `ScrapCancellation`
   `{assetVersion, lockVersion, reason}`，`reason` 限 1～1000 字。
2. 资格位 `actions.cancel` 由 `ScrapReadService` 恒发，判据是「本单仍有活动锁」且
   `ScrapReviewService.mayCancel`：待管理员确认时归原申请人或资产管理员，批准后只归资产管理员
   且状态在 `WAITING_WAREHOUSE_CLAIM`/`PROCESSING`。执行后状态按原保管关系是否可恢复分为
   `CANCELLED` 与 `RESTORE_REQUIRED` 两种结果，前端不预判。
3. 视图形状：取消段复用批准段的同一个 `decisionReason` 输入位——有决定权时该位已在
   「本次决定原因」出现，取消段只多一个按钮；无决定权时取消段自己渲染「取消原因」输入。
   文案「取消报废申请」「取消原因」「请填写取消原因。」「取消本次报废申请。系统会核对原保管
   关系，失效时保留冻结并交由管理员处理。」逐字取自功能线。

## 2. 冲突一律按 features 形状落地

`rtk git merge --no-ff origin/codex/d6-21-loss` 产生 3 处冲突：2 个「修改/删除」（功能线在旧路径
`apps/web/src/views/ScrapView.vue` 与 `apps/web/src/scrap/api.ts` 上改，本分支已把它们移进
`features/scrap/{api.ts,views/}`）与 `DESIGN-HANDOFF.md` 内容冲突。落地方式：

- 旧路径 2 个 DU 文件删除，功能线改动逐段移植进 `features/scrap/api.ts` 的 `actions` 类型与
  `features/scrap/views/ScrapView.vue` 的 `prepare` 动作枚举、cancel 分支和详情段。
- 取消段挂在批准段之后、认领段之前，与功能线的 DOM 顺序一致；不并列新页、不新增面板组件。
- `path` 与 `version` 沿用视图既有的 `capability`/`segment` 分流（`cancel` 直接落
  `/{id}/cancel`），幂等键、草稿/在途两槽与「4xx 清键」判据仍归同一条 `useConfirmedSubmit`，
  本批没有新增第二套提交底座。
- `DESIGN-HANDOFF.md` 取本分支版本，登记留到本记录批，避免把功能线的交接文案带进架构分支入口。

## 3. 底座与状态形状

- 取消意图与既有七段动作共用同一条 `prepare → draft → adopt → send` 链：`prepare('cancel')` 只
  起草 `ScrapIntent`，幂等键由底座在确认时生成，重试不换键；失败仍走同一条
  `cause instanceof ScrapApiError && 400 <= status < 500` 确定性判据。
- 本批未新增任何状态位：取消原因复用既有 `decisionReason`（`shallowRef`），没有新增对象/数组
  `ref`，`locked`/`refreshDisabled` 等既有 computed 的口径不变。
- 视图内裸 `fetch(` 命中仍为 0；本批未新增 `<style>` 块，令牌层零改动。

## 4. 模板派生归位与词表

功能线原始形状里，本批需要收口的只有 3 点（`07ae15b` 态两条判据已经全绿，说明判据脚本的命中面
不含布尔取反，收口按 ARCH-08 的口径而非仅按脚本读数执行）：

- `v-if="!detail.actions.decide"` → `computed showCancelReasonField`，并在注释里写明它与批准段
  共用 `decisionReason` 一个输入位、有决定权时不重复渲染（同一判据只写一处）。
- `error.value = "请填写取消原因。"` → 模块级常量 `CANCEL_REASON_REQUIRED_MESSAGE`，与既有
  `APPROVE_SCOPE_REQUIRED_MESSAGE`/`FACTORY_OCCURRED_REQUIRED_MESSAGE`/`LOSS_EVIDENCE_REQUIRED_MESSAGE`
  同族；确认摘要仍留在 `prepare` 的分支里，与其余七段动作同源。
- 取消段词表：段容器 `fams-card fams-stack`，输入位 `fams-field fams-field-wide` +
  `fams-field-label` + `fams-control`，按钮包进 `fams-panel-actions` 并取既有
  `fams-button data-kind="danger"`（与「驳回报废申请」同一取色，本批未新增 `data-kind` 值）。

判据最终读数：`files_with_derivation=0 sites=0`、`hard_violations=0`
（E 类 `lifecycle_shape_review=13`，与 ARCH-09/10/12/13/14/15/16/17 同一份保留清单，本批未增减）。

## 5. 门禁与真实浏览器实测

四道门禁（Node 24.18.0；`rtk` 默认 Node 16 会让 eslint/vitest 失败）：仓库根 `npm run lint`
（`--max-warnings=0`）退出 0；`vue-tsc --noEmit` 退出 0（0 错误）；`vitest run`
**81 文件 / 366 用例全绿**——与上一批 ARCH-17 的 81/366 逐数相同，因为功能线本批没有新增
`apps/web` 用例（取消语义只有后端 `ScrapSubmitHttpRuntimeCase` 新增 37 行覆盖）；`vite build` 退出 0。

体积（同一命令口径：`dist/assets` 下全部 `.js` 逐个 gzip 求和）：

| 树 | 主样式表 | 全量 JS gzip | `ScrapView` 分块 |
| --- | --- | --- | --- |
| 合并基线 `fbc54f3` | `index-CvDOmE8w.css` 31,420 / gzip 5,689 | 269,370 | 26.99 kB / gzip 8.09 |
| 移植后 `07ae15b` | 同上，文件名与字节均未变 | 269,512 | 27.59 kB / gzip 8.24 |
| 收口后 `1c13c02` | 同上，文件名与字节均未变 | 269,539 | 27.85 kB / gzip 8.27 |

即功能线的取消形状净增 142 字节 gzip，本批收口净增 27 字节 gzip，合计 +169；令牌层零改动，
`dist/assets` 仍只有这一份 CSS 产物，其余分块（`index` 55.58/21.16、`AppChrome` 73.95/29.19、
`LossView` 56.10/14.57、`RepairDetailView` 137.91/35.70）三棵树读数相同。

真实浏览器：一次性栈（MySQL 8.4.11 固定 digest + 本批现场重建的 `fams-product` jar，jar 内已含
`/{id}/cancel` 映射，`unzip -p … ScrapController.class | strings | grep -c cancel` 命中 2 +
clamav 1.4.6 + 三份 vite dev），三份报告并排跑同一后端、同一库状态、同一份 **56 条路由 ×
3 角色 = 168 个渲染单元**：

```
A 改前基线 fbc54f3 vs 收口后：
compared=168 {"SAME":163,"STATUS_DIFF":0,"STRUCT_DIFF":5,"TEXT_DIFF":5,"CONTROL_DIFF":5,
              "LABEL_DIFF":5,"ISSUE_DIFF":0,"PROBE_DIFF":0,"MISSING":0}
B 移植后 07ae15b vs 收口后：
compared=168 {"SAME":163,"STATUS_DIFF":0,"STRUCT_DIFF":5,"TEXT_DIFF":3,"CONTROL_DIFF":0,
              "LABEL_DIFF":0,"ISSUE_DIFF":0,"PROBE_DIFF":0,"MISSING":0}
```

两侧 `routes_with_issues` 同为 27 且事件集合逐条同集（越权路由的 403/404 与占位 id 的 404 噪声），
`finalUrl` 168 单元全同，翻页探针 `PROBE_DIFF=0`。

差异单元只有 5 个，全部是本批触及的报废详情：`admin`/`warehouse`/`employee` 的
`/scrap-requests/1` 与 `admin`/`warehouse` 的 `/scrap-requests/2`；`employee /scrap-requests/2`
零变化（该单对创建人已不可取消）。两个分支在浏览器里都真的渲染出来（改后侧逐单元核对）：

- 有决定权（`/scrap-requests/1` 的 `admin` 与 `warehouse`）：取消段只多出「取消报废申请」按钮，
  「取消原因」输入位不重复出现，控件 46→47。
- 无决定权（`/scrap-requests/2` 的 `admin` 42→44、`warehouse` 53→55，与 `/scrap-requests/1`
  的创建人 `employee` 33→35）：同时多出「取消原因」输入与「取消报废申请」按钮。
- A 组控件读数全部只增不减，B 组 `CONTROL_DIFF`/`LABEL_DIFF` 为 0——即收口没有增删任何控件、
  没有改任何字段类型，5 个差异只落在 DOM 形状签名与 innerText 换行上（新增的 `fams-field-label`
  与 `fams-panel-actions` 块级包装改变了文本拼接处的换行）。

1440×900 整页截图（`warehouse` 的 `/scrap-requests/2`，改前 worktree 与改后各一份）已人工核对观感：
取消段呈 `fams-card` 卡片、带字段标签的 textarea 与红色描边的 danger 按钮，与相邻的「申请附件」
「核验原机并执行报废」段落间距一致，没有出现未收口的原生控件。

实测执行事故如实登记：`07ae15b` 的 worktree dev server（5175）首份报告在第一个单元
`admin /repair-assistance` 撞上 vite 依赖预优化重启（`probe:absent`、`ISSUES:6`），该份报告的
`ISSUE_DIFF=1`/`PROBE_DIFF=1`/`CONTROL_DIFF=1` 是冷启动产物而非代码差异；预热后重跑得到
`sweep18-graft2.json`，B 组读数才是上表所示。该前提已写进「复现」。

## 6. 偏差与遗留

- **取消写路径没有在浏览器执行**：普查只读渲染，不点「取消报废申请」，因此
  `POST /scrap-requests/{id}/cancel` 的两种结果（`CANCELLED` 与 `RESTORE_REQUIRED`）、原键重试与
  409 复位只由功能线后端 `ScrapSubmitHttpRuntimeCase` 覆盖；`apps/web` 侧本批没有新增用例，
  取消段的请求体/版本头/幂等键无前端断言。这与 ARCH-17 记下的「写路径未实测」同源，本批未闭合。
- 小程序侧的取消段（`apps/miniprogram/native/pages/scrap-requests/index.wxml` 与
  `apps/miniprogram/src/scrap-pages.ts`）与后端、契约直接取自功能线候选 `64122d8`，本批未改造、
  未独立复核；小程序侧相对 `api-types` 导入仍是 ARCH-07 的遗留。
- `3cf2c1e`「确认遗失的返厂原机遗失核销」是纯后端增量，`apps/web` 零改动，本批无收口对象；
  ARCH-17 记下的三处未渲染缺口（「按厂家凭证执行报废」段与厂家凭证回链、两个关联报废回执链接、
  遗失核销与厂家凭证执行的写路径）与 ARCH-14/15 的返厂两段仍未闭合，根因不变：造单底座未接入
  完整返厂范围申请→批准→建轮次→寄出→收回→登记换机链。
- 造单与路由底座本批零改动：沿用 ARCH-17 扩好的 12 张在用资产卡与 56 条路由清单，未新增单据
  形状（取消段落在既有报废单上即可渲染）。本轮唯一造单失败仍是 `inbound accept A` 报
  `409 INBOUND_ASSET_STATE_CONFLICT`，与 ARCH-14/15/16/17 同，属造单数据形状问题，与本批无关；
  入库详情路由仍按同一状态被渲染与比对。
- 提交类型偏差：仓库固定的 commitlint（`tools/git/commitlint.config.cjs`）与本计划 §四.4 的
  type 表都不允许 `merge` 类型。本分支 ARCH-12 的合并提交 `39be3bb` 用的是合规的 `chore(web)`，
  而 ARCH-13～17 的 6 个合并提交（`1d9a776`/`a9802f7`/`349ab8c`/`0052735`/`1965b0e`/`54ce8ae`）
  用的都是 `merge(loss)`；用 `tools/git/validate-commit-msg.py` 复核这些历史提交会报 `type-enum`
  （ARCH-17 那条还报 `body-max-line-length`）。本批因此把合并提交标题定为
  `chore(web)`（回到 `39be3bb` 先例），三个提交全部通过该校验器。`.githooks/commit-msg` 仍未安装
  （`core.hooksPath` 未设置），所以这是手工校验，不表示提交门禁已生效。
- 本批记录批与代码批只推送当前 UI 工作分支，未合并 `main`、未建 PR。推送只改变分发状态：除本地
  四道门禁与本批真实浏览器实测外，远端 CI、第七阶段 QA、真实外部联调、独立复核与生产验收仍未
  执行；不改变 D6-21/D6-22 各任务的开放状态。

## 复现

```bash
./mvnw -B -DskipTests package                                   # Java 21+，/{id}/cancel 映射需在 jar 内
python3 scripts/run-arch-ui-stack.py --web-port 5173 --attachments &   # MySQL + fams-product + clamd + vite dev(改后)
python3 scripts/seed-arch-ui-docs.py                            # 10 类单据 + 关联报废四张与两张入口态（取消段落在这两张上）
python3 scripts/arch-ui-routes.py --out /tmp/arch-routes-18.json   # 只走 --out，不要重定向 stdout
git worktree add --detach /tmp/fams-base-fbc54f3 fbc54f3       # 改前参照（ARCH-17 记录批）
git worktree add --detach /tmp/fams-graft-07ae15b 07ae15b      # 收口前参照（本批合并提交）
ln -sfn <repo>/node_modules            /tmp/fams-base-fbc54f3/node_modules
ln -sfn <repo>/apps/web/node_modules   /tmp/fams-base-fbc54f3/apps/web/node_modules
ln -sfn <repo>/node_modules            /tmp/fams-graft-07ae15b/node_modules
ln -sfn <repo>/apps/web/node_modules   /tmp/fams-graft-07ae15b/apps/web/node_modules
(cd /tmp/fams-base-fbc54f3/apps/web && npx vite --port 5174 --strictPort --host 127.0.0.1)
(cd /tmp/fams-graft-07ae15b/apps/web && npx vite --port 5175 --strictPort --host 127.0.0.1)
# 关键前提：worktree dev server 首次请求会触发 vite 依赖预优化并重启，先 curl 预热再普查
PW=$(python3 -c "import json;print(json.load(open('/tmp/fams-arch-stack.json'))['password'])")
node scripts/arch-ui-cdp-sweep.mjs --mode sweep --base http://127.0.0.1:5174 --routes /tmp/arch-routes-18.json --password "$PW" --out /tmp/sweep18-base.json --port 9442 --settle 700
node scripts/arch-ui-cdp-sweep.mjs --mode sweep --base http://127.0.0.1:5175 --routes /tmp/arch-routes-18.json --password "$PW" --out /tmp/sweep18-graft2.json --port 9453 --settle 700   # 预热后的第二份
node scripts/arch-ui-cdp-sweep.mjs --mode sweep --base http://127.0.0.1:5173 --routes /tmp/arch-routes-18.json --password "$PW" --out /tmp/sweep18-head.json --port 9471 --settle 700
node scripts/arch-ui-cdp-sweep.mjs --mode diff --left /tmp/sweep18-base.json  --right /tmp/sweep18-head.json   # A 组
node scripts/arch-ui-cdp-sweep.mjs --mode diff --left /tmp/sweep18-graft2.json --right /tmp/sweep18-head.json  # B 组
# 人工核对观感：--mode sweep 带 --filter <路由> --roles warehouse --viewport 1440x900 --shotDir <目录> --label head|base
```

沿用 ARCH-13～17 记下的执行前提：`node scripts/arch-ui-cdp-sweep.mjs` 必须用 Node 24 前缀
（Node 16 的 `node:util` 无 `parseArgs`）；`--attachments` 需要本机 docker 可用；截图只能通过
`--mode sweep` 带 `--shotDir` 产出（不存在 `--mode shots`），且不带 `--out` 时会在写报告处抛
`ERR_INVALID_ARG_TYPE`，PNG 已先落盘、可忽略该报错；`arch-ui-routes.py` 仍只走 `--out`，
不要重定向 stdout。本批新增一条：worktree 的 vite dev server 必须先预热再普查，否则第一单元
是冷启动噪声。
