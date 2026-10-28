# UI-14 入库管理拆分为列表 / 草稿 / 详情三页

实施分支 `codex/d6-20-factory-ui`，紧接 UI-13（工作区未提交改动之上）。用户确认 UI-13 的分层口径后要求「应用到其他页面」，本批按宣布的顺序做第一个：入库 → 领用 → 借用/退还 → 调拨 → 报修 → 资产台账。入库域改前把「建单表单 + 入库单列表 + 单张入库的全部动作（提交/取消/批准/驳回/认领/逐项验收）+ 受控转派 + 业务附件」压在同一屏，属 IA D0.2 里两类页面混装：列表工作区（P03 一档）与单对象概要+分组详情（P17—P22 一档）。拆法完全复用 UI-13 样板：列表页只留表格与主操作入口，建单/修订各自成路由，单对象动作全部下沉到详情页；弹窗仍只承担确认与预览，本批不新增也不删除任何 `window.prompt`/`window.confirm`。

## 范围

| 文件 | 行数（script / template） | 变化 |
|---|---|---|
| `views/InboundView.vue` | 208（124 / 83）→ 46（20 / 25） | 只留列表工作区：`listReceipts()` 取单、状态 `fams-status`、行 `详情` 由 `@click="open(value)"` 改为 `router-link :to="/inbound/${value.id}"`；主操作 `建立入库单` 进 `#actions`（仅 `WAREHOUSE_OPERATOR`）；删除建单表单、详情面板与全部 mutation 逻辑。列表不再发任何 `getReceipt`，也不再拉 `listAssets`/`activeReferences` 目录。 |
| `views/InboundDraftView.vue` | 新增 109（68 / 40） | 新建与修订共用，`defineProps<{ id?: string }>()` 区分：无 `id` 提交 `createReceipt`，有 `id` 先 `getReceipt` 回填业务日期、资产勾选、目标仓库与位置，再提交 `updateReceipt`（沿用原对象的 version）。`selectedRef()`、`请选择资产、仓库和位置`、payload 形状（`items[].asset/targetWarehouse/targetLocation` 各带 version）、`cardStage === "DRAFT"` 过滤、位置按 `warehouse?.id` 联动、`.fams-selection-grid` 栅格（UI-12 成果）逐字照搬。非库管 `onMounted` 直接返回，不发目录请求。保存成功 `router.push("/inbound/{id}?saved=1")`。 |
| `views/InboundDetailView.vue` | 新增 112（72 / 39） | 单张入库的全部动作：`open()` 按 `props.id` 取详情，`act('submit'/'cancel'/'approve'/'reject'/'claim')`、`accept(item)`、`execute()`、`InboundReassignment`、`AttachmentPanel` 绑定原样搬入，`window.prompt` 三处文案（`取消原因`、`驳回原因`、`核对实物编码：…`）与两条回执（`入库单状态已更新`、`{资产编码} 已验收入库`）逐字不变。`execute()` 不再回读列表（列表已是另一页），`InboundReassignment` 的 `@saved` 由 `load()` 改为 `open()`。 |
| `router.ts` | 76 → 86 | 4 条：`/inbound`（原路由，`uiDesc` 不变）+ `/inbound/new`、`/inbound/:id/edit`、`/inbound/:id`，三条新页 `uiGroup: "资产"`、`props: true`，`new` 与 `:id/edit` 排在 `:id` 之前。 |
| `views/InventoryFindingPanel.vue` | 4 行改动 | 两处「查看正式入库单 / 正式入库」深链由 `/inbound?receiptId={id}` 改为 `/inbound/{id}`。全仓 `grep -rn "receiptId=" apps/web/src` 为 0 命中（`attachment/api.ts` 里的同名函数形参与 URL 无关）。 |

本批未改任何样式文件。`AppChrome.isCurrent`（UI-13 引入）已就位，拆页后侧栏高亮不需要再动基础设施。

## 不变量比对

- `scripts/check_script_untouched.sh` 不适用：本批改动的正是脚本（状态与请求按页面归属重新划分），必然 MISMATCH。改用的证明是「逻辑逐条照搬」，见下三条差集。
- 函数映射（HEAD 7 个 → 列表 1 + 草稿 3 + 详情 4，新增 0）：`load`→列表；`selectedRef`、`create`→草稿；`open`、`act`、`accept`、`execute`→详情。删除的只有 `editDraft`——它的两件事被拆开：切换本地 `editing` 状态由 `/inbound/:id/edit` 路由承担，其提示句 `修改下方草稿内容后保存；所选资产使用同一目标仓库和位置` 原句搬到草稿页的 `fams-panel-subtitle`。
- 属性名集合差（HEAD 模板 vs 三个新模板并集）：新增只有 `to`、`:to`（`router-link`），删除 0。`v-model`、`type="date"`、`required`、`:key`、`:value`、`role="group"`、`aria-label`、`:colspan`、`scope`、`data-column-kind`、`@submit.prevent`、`@click`、`:disabled`、`@saved`、`@busy`、`@uploaded`、`:receipt`、`:version`、`:id` 全部保留在对应页面。
- 可见文案差（去标签后逐行比对）：删除 0 句；新增 4 项——`建立入库单`（原面板标题的三元 `{{ editing ? '修订草稿 X' : '建立入库单' }}` 拆成路由 `uiTitle` + 草稿页条件标题，同一表达式在拆分后各出现一次，不是新措辞）、`返回入库单列表`（跨页导航所需）、`{{ \`修订草稿 ${editing.receiptNumber}\` }}`（原三元的一支）、`修改下方草稿内容后保存；所选资产使用同一目标仓库和位置`（原 `editDraft` 提示句搬移）。
- 按钮文案序列：HEAD `{保存修订/保存草稿, 退出修订, 详情, 修订草稿, 提交审批, 取消, 批准, 驳回, 认领整单, 验收入库}` → 草稿 `{保存修订/保存草稿}` + 详情 `{提交审批, 取消, 批准, 驳回, 认领整单, 验收入库}` + 链接 `{建立入库单, 详情, 修订草稿, 退出修订, 返回入库单列表}`。原 10 个动作全部保留，其中 `详情`、`修订草稿`、`退出修订` 三个由 `<button>` 改为 `<router-link>`（导航语义，不是新增动作）。

## compat 回收

`compat.css` 已在 UI-07 整体删除，本批无回收项；`grep -rn "class=\"asset-\|class=\"panel\|<style scoped>" apps/web/src/views apps/web/src/components` 仍为 0 命中。

## 测试口径变化

拆分改变了「一个视图同时承载列表与详情」这一断言前提。HEAD 的 `InboundView.test.ts`（25 行 / 2 用例）两条都建立在 `/inbound?receiptId=…` 查询参数上，本批按新结构重写与新增（未放宽任何断言口径，非法 id 不发起请求这条安全口径原样保留，只是入口从查询参数换成路由参数）：

- `views/InboundView.test.ts`（重写，25 → 42 行 / 2 用例）：列表只发 1 次 `listReceipts`、不发 `getReceipt`、不拉 `listAssets`/`activeReferences` 目录，行链接恰为 `{详情 → /inbound/9007199254740993}`；库管得到 `{建立入库单 → /inbound/new}`、管理员无主操作。
- `views/InboundDetailView.test.ts`（新增，56 行 / 4 用例）：合法 id 只被原样按字符串请求一次且页面无建单文案；`"../other"` 不向服务端发出并显示 `详情加载失败`；DRAFT 单给出 `修订草稿 → /inbound/…/edit` 与 `提交审批`，点击后 `submitReceipt(draft)` 恰好一次、状态转 `RK-FIND-001 · PENDING_ADMIN_DECISION` 并播报 `入库单状态已更新`；`?saved=1` 播报 `入库草稿 RK-FIND-001 已保存` 后地址栏 `search` 归零。
- `views/InboundDraftView.test.ts`（新增，83 行 / 3 用例）：建单提交 `createReceipt(payload(["101"]))` 并 `push("/inbound/9007199254740994?saved=1")`；空提交报 `请选择资产、仓库和位置` 且零写请求；修订按 id 回填业务日期 `2026-10-19`、勾选 `101`、仓库/位置 `W-1`/`L-1`，显示 `修订草稿 RK-FIND-001` 与副标题、`退出修订 → /inbound/9007199254740993`，保存提交 `updateReceipt(receipt, { …payload(["101","102"]), businessDate: "2026-10-19" })`。

## 验证

| 门禁 | 结果 |
|---|---|
| `npx vue-tsc --noEmit` | 0 错误 |
| `npx eslint . --max-warnings 0` | No issues found（`.vue` 仍不在 `sourceFiles` 覆盖内，属既有盲区） |
| `npx vitest run`（apps/web 下） | Test Files 50 passed / Tests 193 passed（UI-13 为 48 / 186） |
| `npx vite build` | CSS 31.42 kB / gzip 5.65 kB，与 UI-12 起逐字节相同（本批未碰样式）；JS 620.31 kB / gzip 166.63 kB（UI-13 为 618.03 / 165.96） |

CDP 量测（`/tmp/fams-stub-api.mjs` 临时补 `POST /api/v1/inbound-receipts/:id/submit` 与 `PATCH /api/v1/inbound-receipts/:id` 两个无状态 handler，非产品代码；备份 `/tmp/fams-stub-api.mjs.bak14`）：

| 口径 | 结果 |
|---|---|
| 页头契约 | 五条路由（列表/详情/新建/修订/非法 id）在 1440×900 均为 `chromeBottom=64 headTop=80 h1Top=106 gapChromeToTitle=42`；735×872 与 390×844 均为 `headTop=124 h1Top=150 gapChromeToTitle=86`，与 UI-10 起的基线一致 |
| 侧栏高亮 | 五条路由 `navCurrent=["入库管理"]` |
| 溢出 | `docScrollW == innerW`（1440/735/390 全等）；`overflowRight` 在 390 列表 19、详情 35，735 详情 11，但 `overflowNotTable` 全为 0，即全部右溢元素都在 `.fams-table-scroll` 内，属既有设计 |
| 叠行 | `overlap=0`（各视口各页） |
| 面板 | 1440 档 `.fams-panel` 高度：列表 250、草稿 466、详情（DRAFT）709、详情（转派+附件）1104@390。首屏不再同时渲染建单表单与单张详情 |
| 端到端（stub 真实点击） | 列表 `详情` → `/inbound/9001`；`提交审批` → `入库单状态已更新` + `RK-2026-1020-01 · PENDING_ADMIN_DECISION`，动作条随之换成 `取消/批准/驳回`；新建页选仓库→选位置→勾选→`保存草稿` → 落 `/inbound/9003?saved=1` 并播报 `入库草稿 … 已保存`；`/inbound/9001/edit` 回填 `2026-10-20`、勾选 `["105"]`、`W-01`/`L-301`、页头 `修订入库草稿`、面板标题 `修订草稿 RK-2026-1020-01`；`/inbound/..%2Fother` → 只有 `详情加载失败`，面板数 0、请求 0 |

样张 12 张：`/tmp/fams-ui14-{list,detail-draft,detail-submitted,new,detail-saved,edit}-1440.png` 与 `/tmp/fams-ui14-{list,new,detail}-{735,390}.png`；量测原始输出 `/tmp/fams-ui14-probe.txt`。

复核中修掉的问题：① 详情页 `InboundDetailView.vue` 初版残留一段从 HEAD 复制但未使用的 `create()`，已删除后过 vue-tsc；② 草稿保存跨路由后原页内 `notice` 会丢失，补 `?saved=1` + 渲染后立即 `window.history.replaceState` 清查询，避免刷新重复播报；③ 修订回填与勾选共用同一 `assetIds`，测试首版误按「只提交新勾选的 102」断言，按实际行为（回填 101 + 新增 102）改正断言而不是改代码；④ `find()` 未加 VTU 泛型导致 `Property 'value' does not exist`，按仓库既有写法补 `find<HTMLInputElement>` / `findAll<HTMLSelectElement>`。

## 已知限制

- stub 的 `GET /inbound-receipts/{id}` 对未知 id 回落到 `receipts[0]`，所以「保存草稿后跳转」的样张 `fams-ui14-detail-saved-1440.png` 里单号显示为 `RK-2026-1020-01` 而非新建的 `RK-STUB-003`。这是 stub 无状态造成的，产品侧 `router.push` 带的是服务端返回的真实 id（`InboundDraftView.test.ts` 第 1 用例断言）。
- stub 只补了 `submit` 与 `PATCH` 两个 handler，`claim`、`decision`、`accept`、`reassignment`、附件上传在样张里仍是只读或错误提示态；这几块的动作排版未经真实数据复核，只有单测覆盖。
- 窄屏样张里三行的资产条目，其勾选框按 `.fams-selection { align-items: center }`（UI-12 口径）垂直居中于多行文本，不是顶端对齐。本批未碰样式，留待后续密度专项统一裁量。
- 探针里 `fill-2` 报 `checked: 1` 而实际勾了两项，是同步派发 `change` 的采样假象（UI-12 已记录同类），不影响建单→跳转→播报链路结论。
- 原 `/inbound?receiptId=` 查询形式已整体去掉（应用未部署、全仓仅两处生产者且已改指新路由），未做旧链接兼容重定向。
- 验证口径仍是「单测 + 构建 + 静态样张」，未接真实后端，不替代远端 CI 与正式 QA。UI-13 与 UI-14 均未提交。

## 下一批

UI-15 领用申请与分配（`RequisitionView.vue`，400 行、23 个面板，`editing` 有 `new | revise | change` 三态）——先定三态是否共用一个草稿路由，再按本批四道门禁 + 三口径量测推进。其后依次：UI-16 借用/退还（两类对象，需先定路由形状）、UI-17 调拨与报修（报修已读 `route.params.id` 且带 generation 守卫，风险最高放最后）、UI-18 资产台账。
