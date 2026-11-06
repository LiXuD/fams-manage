# ARCH-16 报失转关联报废后端增量合入与前端零增量确认（架构批第 16 批）

基线：`1965b0e`（把 `codex/d6-21-loss` 的 `842a611`「冻结已确认借用报失的关联报废」合入架构线，唯一冲突
在 `DESIGN-HANDOFF.md` 两处，按「功能事实取功能线、架构登记取本分支」合并）。本批没有改造提交：候选
相对上一轮接续基线 `6134e96` 只有一个提交，且 `apps/web` 与 `packages/api-types` 零文件差异，因此本批的
收口内容是「确认无前端增量可收口 + 用新后端 jar 重跑全量渲染烟雾测」。判据沿用本分支已建立的口径，
不新立标准：

- `vue-best-practices` + ARCH-08：模板不得计算，派生一律归 `computed` 或行显示模型；判据
  `scripts/audit-web-template-derivations.py`。
- ARCH-09：primitive 状态用 `shallowRef`，整份替换的集合保持 `ref`，迟到抑制归 `useRequestLiveness`；
  判据 `scripts/audit-web-state-ownership.py`。
- ARCH-10：确认式幂等提交用 `useConfirmedSubmit`，幂等键只归提交底座；判据同上（D 类）。
- ARCH-03/07 + ARCH-09 底座：域内 HTTP 走 `api/http` 的 `requestJson`/`requestBlob`，`.vue` 内不得裸
  `fetch`；object URL 簿记归 `useObjectUrlPreview`；组件级原始 CSS 归位 `fams-*` 令牌词表。

## 量级

| 口径 | 数值 |
| --- | --- |
| 候选提交 | `842a611`（1 个提交，相对 `6134e96`） |
| 合并提交 `1965b0e` 全仓 | 15 文件 +332/−33 |
| 其中 `apps/web` | **0 文件 0 行** |
| 其中 `packages/api-types` | 0 文件 |
| 本批改造提交 | 无（无可收口增量） |
| 冲突文件 | 仅 `DESIGN-HANDOFF.md`（2 处，均为文档登记） |

## 1. 功能事实全部取自功能线，本批只核形状不取语义

`842a611` 将「已确认且未找回的借用报失」接入独立报废线，交付物是：

1. 两个新写端点：`POST /scrap-requests/from-loss/{reportId}`（PC，需 CSRF）与
   `POST /mini/scrap-requests/from-loss/{reportId}`，请求体 `ScrapLossSubmission` 不带 `assetId`，
   资产与版本由报失侧锁定；错误码新增 `SCRAP_LOSS_SOURCE_FORBIDDEN`、`SCRAP_LOSS_SOURCE_CHANGED`、
   `LOSS_LOAN_IDENTITY_CHANGED`。
2. `ScrapRequest` 增加可选 `sourceType`（`STANDALONE`/`LOSS`/`REPAIR`/`FACTORY_REPLACEMENT`）与可空
   `sourceBusinessId`，`required` 列表未变。
3. 新增 `LossScrapSourcePort`、`AssetScrapPort`/`LoanLossPort` 扩展、`ScrapService.submitFromLoss`
   的冻结与原借用保留、`ScrapReviewService` 的驳回恢复，以及 `LossHttpRuntimeCase` 的运行时用例。

`git diff --name-only 7b41e15 1965b0e` 只命中后端、`contracts/fams-core-v1.yaml`、`config/spotbugs/exclude.xml`
与三个文档文件；对 `apps/web` 与 `packages` 执行同一比较返回空集，随后用 `diff -rq` 复核
`apps/web/src` 与改前 worktree 亦无差异。结论：本批确实没有新增或改动的 `apps/web` 文件可供收口。

## 2. 为什么不把新端点接进前端（越界判定，非遗漏）

现役入口自述本轮「已接入……继续双端入口」，即 PC 与小程序的报失转报废入口本就仍是 D6-22 功能线的未完成
项。本批不实现，三条理由：

1. 前端补 `sourceType`/`sourceBusinessId` 到 `features/scrap/api.ts` 的型别，等于让报废视图开始消费
   「来源」这一产品语义（是否显示、显示成什么文案、哪种来源允许何种操作），属用户可见文案来源与契约消费，
   踩红线。
2. 若加「转报废」按钮，需要报失视图交出 `reportId` 与当前版本，并新增 `If-Match-Version` + 幂等键 +
   CSRF 的写序列与新确认文案——这是新写路径与新交互形状，不是结构调整。
3. 本批范围由「已改动的 `apps/web` 文件」定义，空集即空操作。因此只登记遗留，等功能线交付入口后按
   ARCH-08/09/10 口径收口。

## 3. 判据与四道门禁（Node 24）

| 项 | 本批读数 | 与 `2529c3b`（ARCH-15 改造提交）对比 |
| --- | --- | --- |
| 模板派生判据 | `files_with_derivation=0 sites=0` | 同值 |
| 状态所有权判据 | `hard_violations=0 lifecycle_shape_review=13` | 同值，E 类保留清单 13 文件未增减 |
| 根 `npm run lint`（eslint `--max-warnings=0`） | 退出 0 | 同值 |
| `vue-tsc --noEmit` | 0 错误 | 同值 |
| `vitest run` | 81 文件 / 361 用例全绿，8.41s | 同值，无新增或删除用例 |
| `vite build` | 通过 | 主样式仍为 `index-CvDOmE8w.css` 31.42 kB / gzip 5.65 kB，`dist/assets` 仅此一份 CSS |

产物体积口径说明：本轮另按「逐行累加 vite 输出的 gzip 列」统计得 60 个 JS 产物合计 273,920 字节，与
ARCH-15 记录的 266,907 字节不是同一算法，不可直接相减。本批的等价性证据更强——`apps/web` 输入逐字节
相同，故 `vite build` 输出（含内容哈希文件名）逐字节相同，主 CSS 哈希名 `index-CvDOmE8w.css` 未变即为
直接印证。

## 4. 真实浏览器逐路由实测

底座与两侧：

- 后端：`JAVA_HOME=…/graalvm-jdk-21.0.7/Contents/Home ./mvnw -B -DskipTests package` → BUILD SUCCESS。
  用 `unzip -p backend/target/contract-generation-probe-0.1.0-SNAPSHOT.jar BOOT-INF/classes/com/fams/scrap/ScrapController.class`
  取回字符串确认 `from-loss/{reportId}` 映射已在 jar 内，且 `LossScrapSourcePort` 类计数为 2；否则本轮
  跑的是合并前的旧后端，实测不成立。
- 栈：`python3 scripts/run-arch-ui-stack.py --web-port 5173 --attachments` → READY（MySQL 8.4.11 按 digest
  `3466ba4a…` 引用 + fams-product 8080 + clamav 1.4.6 + 改后 vite 5173）。
- 造单：`python3 scripts/seed-arch-ui-docs.py` 建成 16 类单据（`inboundA/B`、`repair`/`repair2`、
  `requisition`、`borrow`、`inventoryTask`、`transfer`、`assetReturn`、`lossPending`/`lossSearching`/`loss`、
  `scrap`/`scrapProcessing`/`scrapExecuted`/`scrapRejected`）。唯一失败仍是 `inbound accept A` 报
  `409 INBOUND_ASSET_STATE_CONFLICT`，与 ARCH-14/15 同值，属造单数据形状，与报废域无关。
- 路由清单：`python3 scripts/arch-ui-routes.py --out /tmp/arch-routes-16.json` → 48 条。
- 改前侧：`git worktree add --detach /tmp/fams-base-7b41e15 7b41e15` + 两条 `node_modules` 软链 +
  Node 24 `npx vite --port 5174 --strictPort`。

结果（同一后端、同一库状态、同一份 48 条路由清单 × `admin`/`warehouse`/`employee` 3 角色）：

- 两侧各 `rows=144`，`routes_with_issues` 两侧同为 **27**（与 ARCH-15 读数同值，即新契约字段与新错误码
  没有让任何一条路由多出 4xx/5xx、console 或未处理异常）。
- `--mode diff`：`compared=144 {"SAME":144,"STATUS_DIFF":0,"STRUCT_DIFF":0,"TEXT_DIFF":0,"CONTROL_DIFF":0,"LABEL_DIFF":0,"ISSUE_DIFF":0,"PROBE_DIFF":0,"MISSING":0}`
  —— 144/144 逐单元一致，最终 URL、DOM 结构签名、掩码后文本指纹、控件总数/禁用数、可点文案集合、
  翻页探针与运行时事件集合全部零差异。
- 本轮 diff 的含义要说清：两侧前端源码本是同一份（§1 已证），所以它**不是**「改造前后行为等价」的证据，
  而是「新后端 jar + 新契约字段 + 同一前端」的回归烟雾测。它证明报废/报失/借用/退还/调拨全部路由在后端
  新增 `sourceType`、`sourceBusinessId` 与 `SCRAP_LOSS_SOURCE_*` 错误码后渲染与交互零变化。改造等价性
  证据仍由 ARCH-12～15 各批承担。
- 未做整页截图人工核对：本批可见形状零变化，截图与 ARCH-15 的改后屏必然同源，省去做无对比意义的动作。

## 边界与遗留

- 本批未改任何产品代码，无改造提交；除 `DESIGN-HANDOFF.md` 与本记录外零文件变化。
- **新遗留**：报失转关联报废的双端入口（PC 与小程序）仍未实现，`sourceType`/`sourceBusinessId` 在前端
  零消费；该入口属 D6-22 功能线，下一批若功能线交付前端，则按 ARCH-08/09/10 口径收口并做两侧并排实测。
- ARCH-15 的三处未在浏览器渲染的缺口本批仍未闭合：超期清单结果页（需点「查看超期待处置」，普查探针只点
  分页）、附件读取与预览（一次性栈三个账号仍无 `SCRAP_ATTACHMENT` 字段权限，`capabilities.canRead` 恒假）、
  上传与结案写路径（普查不写库）。三者仍由功能线四条用例与 `features/scrap/*` 判据零命中保护。
- ARCH-14 记下的返厂报失与找回续修两段仍未实测。
- 后端、契约、spotbugs 例外与 `LossHttpRuntimeCase` 测试直接取自功能线 `842a611`，本批未独立复核；
  小程序侧未改造，相对 `api-types` 的导入仍是 ARCH-07 遗留。
- 本轮环境事实两条：本机 `mysql:8.4.11` 的 tag 已不存在，只有 digest `3466ba4a…` 的镜像，栈脚本按
  `repo:tag@sha256:` 引用仍能命中本地镜像，不需要重新拉取；不带凭据直接 `curl POST :8080/scrap-requests/from-loss/1`
  对存在与不存在的路径一律返回 500，不能用它证明端点已注册，要证明就查 jar 内映射字符串。
- 一次性栈已按 SIGTERM 收尾（自建容器 `fams-arch-ui-mysql-6a4020919586` 与 clamd 已消失，5173/5174 端口
  已关闭，worktree 已移除）。另有一台**非本批创建**的历史容器 `fams-d6-03a-625409ba1409fdb7-mysql-1`
  仍在运行，属早前 D6-03A 轮次遗留，本批未触碰、未删除。
- 本批两个提交（合并 `1965b0e` 与本记录批）只推送当前 UI 工作分支 `codex/d6-21-loss-ui`，未合并 `main`、
  未建 PR。推送只改变分发状态：远端 CI、第七阶段 QA、真实外部联调、独立复核与生产验收仍未执行；
  不改变 D6-21/D6-22 各任务的开放状态。

## 复现

```bash
JAVA_HOME=/Users/lixd/Library/Java/JavaVirtualMachines/graalvm-jdk-21.0.7/Contents/Home \
  ./mvnw -B -DskipTests package                                   # jar 内必须含 from-loss 映射
unzip -p backend/target/contract-generation-probe-0.1.0-SNAPSHOT.jar \
  BOOT-INF/classes/com/fams/scrap/ScrapController.class | grep -a -o 'from-loss/{reportId}'
python3 scripts/run-arch-ui-stack.py --web-port 5173 --attachments &   # MySQL + fams-product + clamd + 改后 vite
python3 scripts/seed-arch-ui-docs.py                                # 16 类单据，唯一失败 inbound accept A 409（同前两批）
python3 scripts/arch-ui-routes.py --out /tmp/arch-routes-16.json      # 只走 --out，不要重定向 stdout
git worktree add --detach /tmp/fams-base-7b41e15 7b41e15
ln -sfn <repo>/node_modules /tmp/fams-base-7b41e15/node_modules
ln -sfn <repo>/apps/web/node_modules /tmp/fams-base-7b41e15/apps/web/node_modules
(cd /tmp/fams-base-7b41e15/apps/web && PATH=/Users/lixd/.nvm/versions/node/v24.18.0/bin:$PATH \
  npx vite --port 5174 --strictPort --host 127.0.0.1)
PW=$(python3 -c "import json;print(json.load(open('/tmp/fams-arch-stack.json'))['password'])")
export PATH=/Users/lixd/.nvm/versions/node/v24.18.0/bin:$PATH
node scripts/arch-ui-cdp-sweep.mjs --mode sweep --base http://127.0.0.1:5173 --routes /tmp/arch-routes-16.json --password "$PW" --out /tmp/sweep16-head-all.json --port 9431 --settle 700
node scripts/arch-ui-cdp-sweep.mjs --mode sweep --base http://127.0.0.1:5174 --routes /tmp/arch-routes-16.json --password "$PW" --out /tmp/sweep16-base-all.json --port 9441 --settle 700
node scripts/arch-ui-cdp-sweep.mjs --mode diff --left /tmp/sweep16-base-all.json --right /tmp/sweep16-head-all.json
# 预期：两侧各 rows=144 且 routes_with_issues=27；diff compared=144 {"SAME":144, 其余七类全 0}
```

两条既有执行前提继续适用：`node scripts/arch-ui-cdp-sweep.mjs` 必须用 Node 24（Node 16 的 `node:util` 无
`parseArgs`）；`--attachments` 需要本机 docker。收尾用 `pkill -TERM -f run-arch-ui-stack.py`（脚本自删除
建过的容器），worktree 若因两条自建的 `node_modules` 软链被判定为「有未跟踪文件」，先 `git status --short`
确认只有软链再 `git worktree remove --force`。
