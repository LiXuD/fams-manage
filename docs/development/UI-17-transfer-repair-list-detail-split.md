# UI-17 调拨与报修各拆为列表 / 建单 / 详情三页

实施分支 `codex/d6-20-factory-ui`，紧接 UI-16（同一批未提交改动之上）。改前 `TransferView.vue` 一屏 354 行、`<script setup>` 占 155 行：`selected`（调拨单）由页内 `open(row.id)` 选中，同一屏同时挂着**申请与执行列表、本人待接收列表、人员责任交接清单、建单表单（`draftItems`/`chooseAsset`/`addItem`）、整单动作、逐项实物交接表单与每项的动作按钮**，`mutate()` 由提交、批准/驳回、领取、取消、交出、收回、接收与拒绝共用。`RepairView.vue` 291 行、脚本 150 行，是本域唯一已读 `route.params.id`（`/repairs/:id?` 可选参数）且带 generation 守卫的视图：列表 + 建单 + 详情 + 五个面板（返厂/实物/返还/目标变更/转派/协作）全在一屏，`prepare('submit')` 与 `prepare('approve'|…)` 共用一个确认块。按 IA D0.2，这两屏都是列表工作区与单对象概要+详情混装，且比 UI-13～UI-16 更重——调拨域还额外承载了「待接收」与「人员交接」两个独立工作区。

沿用用户裁定的**方案 A「按对象拆」**：调拨单是一个对象（建单、整单动作、逐项实物交接都挂在它上面），「本人待接收」是接收人视角的另一张工作表，不是某张调拨单的详情，因此留在列表页；报修单是一个对象（决定、领取、原地维修、返厂、返还、转派、协作全部挂在它上面），损坏归还接续维修是建单入口，留在建单页。弹窗口径不变：本批不新增也不删除任何 `<dialog>`/`confirm`。侧栏条目不动——`调拨与人员交接`、`报修与维修` 各一条，新页由 `isCurrent()` 前缀匹配归入同一条目，实测无一串台。

## 范围

| 文件 | 行数（script 块 / 总行） | 变化 |
|---|---|---|
| `views/TransferView.vue` | 155 / 354 → 76 / 175 | 只留列表工作区三块：申请与执行（`/transfers` 分页 + 行操作）、本人待接收（`/transfers/receipts/pending` + `openReceipt/receive` 的 grant 领取与接收/拒绝）、人员责任交接清单（`<details>` 折叠 + `loadPersonnelChoices/personnel`）。行操作 `查看 / 执行` 由 `<button @click="open(row.id)">` 改为 `<router-link :to="/transfers/{id}">`（文案逐字不变）；`refresh/mutate/retry` 与幂等口 `pending ??= { path, version, key, body }` 保留（接收动作仍需要）。删除建单表单、整单动作、逐项实物交接与 `catalog("warehouse"/"employee")` 等只服务它们的读取。`#actions` 放 `新建调拨` 主操作。 |
| `views/TransferDraftView.vue` | 新增 87 / 146 | 建单：`catalog`、`load`（`/transfers/eligible-assets`）、`chooseAsset`、`chooseOrganization`、`addItem`、`create`、`custodyName/organization/place` 逐字搬自 HEAD，两条校验句 `请添加调拨明细并填写原因。`、`请核对有效目标部门、责任人和位置；公用责任人须为目标部门资产员。` 不变；`POST /transfers`（version 0）成功后 `push("/transfers/{id}?saved=1")`，`draftItems` 随之清空。 |
| `views/TransferDetailView.vue` | 新增 75 / 162 | `defineProps<{ id: string }>()`；单张调拨单的全部动作：`open`、`decide`、`physical`、`mutate`、`retry`、`refresh`、`loadPeople`（人员名解析）与 `admin/operator` 两个角色判定逐字搬入；`申请 / 决定原因` textarea、`实物编码 / 当前二维码`、`实际去向`、`目标现场实际签收人`、`现场收妥时间`、`现场交接 / 收回依据`、`已实际收回并核对实物` 七项实物表单只在 `operator && processorAccountId === 本人 && PROCESSING/WAITING_HANDOVER` 下渲染，与 HEAD 条件一致；`TransferReassignment` 仍只在 `admin && PROCESSING/WAITING_HANDOVER` 挂载。`onMounted` 顺序「非法守卫 → 目录（organization/location）→ `open()` → 消费 `?saved=1` 并 `replaceState` 清查询」，守卫错误句复用域内既有 `没有当前记录或操作的权限。` |
| `views/RepairView.vue` | 150 / 291 → 30 / 51 | 只留报修进度时间线列表：`load()` 单次 `/?page=&pageSize=20`、`paginate(±1)`、身份失效 `reset/failure` 与 epoch 守卫保留；每行由「按钮 + 页内 `open(row)`」改为 `<router-link :to="/repairs/{id}">`，文案仍是 `{{ assetCode }} · {{ fault }} · {{ repairStates[status] }}`。删除建单表单、可报修资产目录、详情、六个面板挂载与全部 mutation 逻辑。`#actions` = `刷新并核对当前结果` + `新建报修`。 |
| `views/RepairDraftView.vue` | 新增 57 / 97 | 建单：`loadAssets`（`/eligible` 分页）、`prepare`（HEAD 的 `prepare('submit')` 去掉 action 形参，校验句 `请选择资产并填写实际故障和发生时间。` 不变）、`confirm`、`perform` 与两步确认块逐字搬入；`RepairReturnSourcePanel`（损坏归还接续维修）随建单入口留在此页，其 `@created` 由 HEAD 的页内选中改为 `push("/repairs/{id}?saved=1")`。 |
| `views/RepairDetailView.vue` | 新增 125 / 231 | 单张报修单：`identity` 加入 `route.params.id`、`generation/live/current()` 与 `watch(identity, …, { immediate: true, flush: "sync" })`、`onBeforeUnmount` 逐字沿用 HEAD；`load()` 只取 `/​{id}`（不再与列表并发拉取），`prepare(action)`/`confirm`/`perform`/`clearPhoto`/`readPhoto`/`fileChosen` 与 `approvalMethod/reason/observedCode/evidence/confirmedPlace/neverLeft` 全部搬入；六个面板的挂载条件与 `@changed="load"`、`@invalidated="reset"`、`@busy-change` 接线不变。`#actions` = `刷新并核对当前结果` + `返回报修列表`。 |
| `loan/api.ts` | 34 → 38（+4） | 把原先内联在调拨视图里的状态中文抽成导出常量 `transferLabels`（13 项：`DRAFT/PENDING_APPROVAL/REJECTED/WAITING_WAREHOUSE_CLAIM/PROCESSING/WAITING_HANDOVER/COMPLETED/PARTIALLY_COMPLETED/CANCELLED/READY/DELIVERED/REFUSED/RECOVERY_PENDING`），供调拨列表、详情与建单共用；措辞逐字照搬，无新增状态。 |
| `router.ts` | +9 −1 | 6 条 import + 4 条新路由：`/transfers/new`、`/transfers/:id`、`/repairs/new`、`/repairs/:id`，均 `uiGroup: "业务流转"`；字面量 `new` 排在 `:id` 之前。报修原路由是 `path: "/repairs/:id?"`（可选参数、单组件），拆为「`/repairs` 列表 + `/repairs/:id` 详情」两条；`/repairs/:id` **不加 `props: true`**，详情页仍按 HEAD 方式读 `route.params.id`（见「测试口径变化」）。`/transfers`、`/repairs` 两条列表路由与 `uiTitle/uiDesc` 逐字不动，三条新页的 `uiTitle/uiDesc` 是本批唯一的导航级新文字。 |
| 三处深链 | 各 1 行 | `/transfers?id=` 页内选中查询在全仓有 **3 个生产者**，随拆页一并改指详情页：`ProxyConfirmationView.vue:25` 的 `businessLink()`、`BusinessResultReviewView.vue:48` 的「查看业务单据」、`TransferTargetChangeView.vue:144` 的「查看原调拨单」。三处链接文案（`查看业务单据`、`查看原调拨单`）逐字不变；`REPAIR`/`REQUISITION` 分支原本已是 `/repairs/{id}` 形态，本批只是把三元式统一。 |

本批未改任何样式文件。

## 不变量比对

`scripts/check_script_untouched.sh` 不适用：拆页必然移动脚本。改用四项集合差集（脚本与输出 `/tmp/fams-ui17-audit.mjs` / `.txt`），以 HEAD 的 `TransferView.vue` + `RepairView.vue` 两文件为基线，与工作区 6 个新文件并集比对，证明「逻辑逐条照搬、只搬不写」。

- **控制签名差集**（HEAD 70 种 / NOW 69 种）：HEAD-only 5 条全部落在两类预期改名上——① 行内选中 `@click="open(row.id)"`、`@click="open(row)"` 两个 `<button>` 整体改为 `router-link`（文案不变）；② 列表翻页与刷新的 `:disabled` 由 `locked`/`locked || page === 1` 改为 `busy`/`busy || page === 1`（列表页不再有「详情动作进行中」这一层锁）。NOW-only 4 条即上述两项的替代形态加上 `prepare('submit')` → `prepare()`。计数变化 6 条全部是「原先一屏共用、现在每页各一份」：`刷新 1→3`、`重试上次操作 1→3`、`申请 / 决定原因 textarea 1→3`、`确认保存本次办理 1→2`、`返回修改 1→2`、`v-if=pending 重试 1→2`。
- **可见文案差**：HEAD 去重 179 段 → 并集 182 段，总段数 189 → 212。**HEAD-only 恰 0 段，即没有任何一句用户可见文本在拆页中丢失**；NOW-only 3 段全是跨页导航的 `新建报修` ×1、`返回报修列表` ×2、`返回调拨列表` ×2（`新建调拨` 本已存在于 HEAD）。其余计数变化：`{{ error }}` 2→6、`{{ notice }}` 2→4、`刷新` 1→3、`刷新并核对当前结果` 1→2、`重试上次操作` 1→3、`重试原报修操作` 1→2、`确认保存本次办理` 1→2、`返回修改` 1→2、`申请 / 决定原因` 1→3、`新建调拨` 1→2，以及报修确认句 `请核对本次资产、实际依据及处理范围。…` 1→2——全部是拆页后各页自带的框架句，无一句改写。
- **按钮/链接文案多重集**：HEAD 去重 40 → 并集 42，总份数 40 → 52。HEAD-only 3 条中 `button:查看 / 执行` 在 NOW 侧以 `router-link:` 同名出现（文案逐字不变）；`button:上一页报修`/`button:下一页报修` 的「丢失」是审计脚本按文件抽取时的正则捕获产物——单文件复测确认两枚按钮在建单页与详情页各自存在且文案一致（`RepairDraftView.test.ts`、`RepairView.test.ts` 亦断言其文案）。NOW-only 5 条 = `查看 / 执行` 的 link 形态 + `新建调拨`/`新建报修`/`返回调拨列表 ×2`/`返回报修列表 ×2` 的 link 形态。`提交调拨/取消申请/批准调拨/驳回调拨/领取整单调拨/记录交出，等待目标人接收/未交出取消/开始实物收回/确认实物收回后取消/核对并接收/确认调拨接收/反馈实物不符/加载原责任人员/查询未交接责任/添加调拨明细/移除此明细/保存调拨草稿/批准内部无费用处理/驳回无效报修/取消尚未批准的报修/领取维修处理/核对受理原地维修/确认已恢复并原地关闭/查找可报修资产/核对提交报修` 的文案与数量与 HEAD 一致。
- **符号差集**：HEAD 两文件 44 个函数 → 六文件并集逐一列出（`/tmp/fams-ui17-audit.txt` 末段）。HEAD-only 恰好 2 个函数：两个域的 `fn:open`——页内取详情被路由取代，调拨侧改名为详情页的 `fn:open`（仍在，只是归属换了文件），报修侧的 `fn:open(row)` 变成列表页的 `router-link` + 详情页的 `fn:load`。NOW-only 为 `fn:load`（列表/详情各自）、`fn:send` 未出现、以及拆页带来的管道变量（`props`、`router`、`detail`）。**未新增任何业务动作函数**。口径注意：符号 tokenizer 只识别每行首个声明，本仓库习惯把多个 `const` 写在同一行，因此两侧集合都低估，结论只用于函数级核对。

## compat 回收

`compat.css` 已在 UI-07 整体删除，本批无回收项；`Grep 'class="asset-|class="panel|<style scoped' apps/web/src/**/*.vue` 仍为 0 命中。

## 测试口径变化

HEAD 的 `RepairView.test.ts`（40 行 / 8 用例）是本域最关键的安全口径，**逐条照搬、不放宽**，只是随逻辑搬到新页；`TransferView.test.ts`（49 行 / 1 用例）未改动，其断言（列表读取 + 状态中文）在拆页后仍成立。搬移中的三处必要改写：

1. **textarea 索引 `[1]` → `[0]`**：HEAD 一屏里 `findAll("textarea")[0]` 是建单表单的「实际故障」、`[1]` 才是「本次决定原因」；详情页不再渲染建单表单，决定原因成为 `[0]`。8 个用例中 5 个受影响，均只改选择器，断言的提交体（`{ result: "APPROVE", allowedMethod: "INTERNAL_NO_COST", reason: "…" }`）与 `If-Match-Version` 逐字不变。
2. **删一条跨页断言**：HEAD「返厂确认与响应未知锁兄弟动作」用例里 `expect(button(wrapper,"查找可报修资产").attributes("disabled")).toBeDefined()` 断的是「建单按钮在详情动作期间被锁」，拆页后两者不再同屏，该断言删除；同用例的 `刷新并核对当前结果`、`确认保存本次返厂办理`、`fieldset.disabled` 与重试体比对全部保留。
3. **`RepairDetailView` 保留 `useRoute` 而非 `props`**：HEAD 8 个用例都靠 `vi.mock("vue-router", () => ({ useRoute: () => … }))` 注入单号，若详情改为 `defineProps` 就得重写这 8 个 mock 的注入方式。为把改动限制在「选择器索引」这一类，报修详情继续读 `route.params.id`，路由记录也就不加 `props: true`；调拨详情则按 UI-16 先例用 `props: true`（其 HEAD 测试没有路由 mock）。
4. **面板 `:blocked` 组成**：`RepairFactoryPanel` 的 `:blocked` 在 HEAD 含 `sourceLocked`（建单面板的锁），拆页后该锁不在详情页，故这一项被移除。首版误把它自己的 `factoryLocked` 加进 `:blocked`，导致面板自锁、`vitest` 报「expected '' to be undefined」——按 HEAD 的「各面板的 `:blocked` 恰不含自身锁」口径改正。

新增/重写测试：`RepairDetailView.test.ts`（43 行 / 10 用例 = 上述 8 条 + 非法单号不向服务端发出 + 建单跳转来的 `?saved=1` 只播报一次且 `window.location.search === ""`；EMPLOYEE 用例追加「除本条详情外不向服务端发任何请求」）、`RepairDraftView.test.ts`（58 行 / 5 用例：挂载零读取 + `返回报修列表` 链接 + 管理员可见损坏归还接续、缺发生时间的校验句与零写请求、完整提交的请求体与 `If-Match-Version: 0` 与 `push("/repairs/77?saved=1")`、响应丢失后重试体逐字节相同）、`RepairView.test.ts` 重写为 33 行 / 4 用例（只发一次列表请求、行链接顺序、非管理员按钮恰为 `["刷新并核对当前结果","上一页报修","下一页报修"]` 且正文不含建单控件、翻页与刷新只读当前页、身份替换清空）。调拨侧 `TransferDraftView.test.ts`（94 行 / 4 用例）、`TransferDetailView.test.ts`（111 行 / 6 用例）。UI-17 六文件合计 **30 用例**（HEAD 两文件合计 9 用例）。

## 验证

| 门禁 | 结果 |
|---|---|
| `npx vue-tsc --noEmit` | 0 错误 |
| `npx eslint . --max-warnings 0` | No issues found（`.vue` 仍不在根 `eslint.config.mjs` 的 `sourceFiles` 覆盖内，属既有盲区） |
| `npx vitest run`（apps/web 下） | Test Files 61 passed / Tests 242 passed（UI-16 为 57 / 221；本批 +4 文件 +21 用例） |
| `npx vite build` | CSS `31.42 kB / gzip 5.65 kB`，与 UI-12 起逐字节相同（本批未碰样式）；JS `641.12 kB / gzip 170.79 kB`（UI-16 为 `632.58 / 169.48`，+8.5 kB 为 4 条新路由与拆出的模板） |

CDP 量测（`/tmp/fams-stub-api.mjs` 扩出**有状态**的调拨 stub：3 张调拨单（草稿 / 库管处理中 / 待库管领取）、`POST /transfers` 建单、submit/decision/claim、逐项 deliver/recovery/recover、待接收 grant 与 receive、`catalog` 补 `warehouse` 与 `personnel`（含已停用人员）、`personnel-handover`；备份 `/tmp/fams-stub-api.ui16.bak.mjs`，splice 脚本 `/tmp/fams-ui17-splice.mjs`，探针 `/tmp/fams-ui17-probe.mjs`，均为临时脚本非产品代码）。**39 个口径量测 + 39 张整页样张**，`MISS` 0 次：

| 口径 | 结果 |
|---|---|
| 页头契约 | 39 次全部 `scrollY=0`；1440 档 `chromeBottom=64 headTop=80 h1Top=106 gapChromeToTitle=42`，735 与 390 档 `headTop=124 h1Top=150 gapChromeToTitle=86`，与 UI-10 起基线一致；`overlap=0` 全绿 |
| 侧栏高亮 | `/transfers*` 的 20 个口径 `navCurrent=["调拨与人员交接"]`，`/repairs*` 的 19 个口径 `navCurrent=["报修与维修"]`，无一串台 |
| 页头动作区 | 调拨列表 `新建调拨@1408`、调拨三页 `返回调拨列表@1408`；报修列表 `刷新并核对当前结果@1162 + 新建报修@1408`、报修详情 `刷新并核对当前结果@1141 + 返回报修列表@1408`；390 档折到第二行左对齐（`@98`/`@126`），与 UI-16 记录同类 |
| 溢出 | `docScrollW == innerW`（1440/735/390 全等，无整页横向滚动）；`overflowRight` 仅 `transfers-list/390`=31 一个口径 >0，且 `overflowNotTable` 全为 0，即右溢元素全在 `.fams-table-scroll` 内，属既有设计 |
| 面板拆分 | 1440 档调拨列表 `request-title@192 h=359` + `receipt-title@575 h=193` + `人员责任交接清单@792 h=94`（展开后 h=448）；调拨详情按状态 `h=840`（草稿）→ `656`（待领取）→ `1180`（领取后，含转派卡与实物栅格）→ `1772`（3 明细）；建单页 `h=530`（未选资产）→ `568`（已选）→ `742`（含 1 条明细卡）。报修列表 `报修进度@192 h=310`；建单页 `提交报修@450 h=528` → 确认态 `@602 h=700`；详情按状态 待决定 `h=1852 cards=8` → 已完成 `h=1242 cards=6` → 库管处理中 `h=9548 cards=21`。首屏不再同时渲染「列表 + 建单 + 详情 + 六个面板」 |
| 窄屏 | 390 档调拨列表 `472 + 233 + 78`、详情 `h=1188`（草稿）/ `2458`（3 明细处理中）、建单 `h=964`；报修列表 `h=416`、建单 `h=640`、详情 `h=11776`。735 档对应 `416 + 249 + 94`、`840 / 1998`、`890`、`332`、`618`、`10332` |
| 端到端（stub 真实点击） | 调拨列表 `查看 / 执行` → `/transfers/5201`；填原因 → `提交调拨` → `操作已保存。` + `待管理员确认` + 出现 `批准调拨/驳回调拨` → 批准 → `待库管领取`；`/transfers/5203` `领取整单调拨` → `库管处理中` 并长出转派卡 + 七项实物表单；`/transfers/5202` 填实物五项 + 勾选 → `开始实物收回`（`待目标人接收 → 待实物收回`）→ `确认实物收回后取消`（→ `已取消`）；列表页 `核对并接收` → 填编码 + 勾选 → `确认调拨接收` → `本人待接收 0 项` + 空态句；`加载原责任人员` → 选 `周敏（已停用）` → `查询未交接责任` → `周敏 · 已停用；未交接前保留原责任。` + 责任清单；建单页选资产 → 目标位置 → `添加调拨明细` → `保存调拨草稿` → 落点 `/transfers/5299` 且 `?saved=1` 被清成 `/transfers/5299`；报修建单 `查找可报修资产` → 选资产 → 填故障/影响/时间 → `核对提交报修` → 确认块 → `确认保存本次办理` → 落点 `/repairs/18` 播报「本次结果已保存…」 |
| 非法 id | `/transfers/..%2Fother` `panels=[] buttons=[]`，正文只有 `没有当前记录或操作的权限。`，零服务端请求；`/repairs/..%2Fother` 同样零请求、正文清空，但**没有**提示句——HEAD 的报修域从未有过该措辞，按「不新增用户可见文本」约束不补，见「已知限制」 |

样张 39 张：1440 档 25 张 `/tmp/fams-ui17-{transfers-list,transfer-draft-detail,transfer-pending-approval,transfer-waiting-claim,transfer-claim,transfer-claimed,transfer-processing,transfer-recovery-started,transfer-recovered,transfer-receipt-form,transfer-receipt-done,transfers-personnel,transfer-draft-new-empty,transfer-draft-new-picked,transfer-draft-item,transfer-created-saved,repairs-list,repair-draft,repair-draft-confirm,repair-created-saved,repair-detail,repair-detail-pending,repair-detail-completed,transfer-detail-malformed,repair-detail-malformed}-1440.png`；390 与 735 档各 7 张 `/tmp/fams-ui17-{transfers-list,transfer-draft-detail,transfer-processing,transfer-draft-new,repairs-list,repair-draft,repair-detail}-{390,735}.png`。量测原始输出 `/tmp/fams-ui17-probe.log`，审计输出 `/tmp/fams-ui17-audit.txt`。

复核中修掉的问题：① `RepairDetailView.vue` 首版漏声明 `reason`，`vue-tsc` 报错，按 HEAD 与 `approvalMethod` 同行补齐；② `neverLeft` 被写成 `ref("")` 又赋布尔值，恢复 HEAD 的 `ref(false)` 与两处 `= false` 复位；③ 首版给详情页发明了空态句 `没有当前记录或操作的权限。` 之外的 `没有当前记录或操作已失效。`，按约束删除；④ `RepairDraftView` 的 `RepairReturnSourcePanel :blocked` 误引用不存在的 `factoryLocked`，改为 `busy || !!pending || !!confirmation`；⑤ 新测试里 `state.session` 注成 `Record<string, any>` 破坏成员访问并触发 `no-explicit-any`，改回 HEAD 的推断形状 + `beforeEach` 里 `reactive({...})` 重建、用例内就地改 `user.id` / `user.roles`；⑥ VTU 的 `global.mocks.$route` 覆盖不了 `useRoute` mock，改为 hoisted 可变 `state.route`；⑦ 「`?saved=1` 只播报一次」用例首版断言「只发 1 次请求」失败——管理员身份下返厂面板也会读 `/factory`，收窄为只数精确的 `/api/v1/repairs/9`；⑧ 样张首跑 `POST /api/v1/repairs` 被 stub 的列表分支吞掉（返回整张列表、无 `id`），建单跳转落到 `/repairs/undefined`，且 `/repairs/18/collaborations` 未实现导致协作面板 404 → `@invalidated` → 详情整屏空白：补 stub 的 POST 分支与任意单号协作板空数据后复跑，落点变 `待管理员确认` 详情正常。产品代码在这两处均与 HEAD 一致（`result.repairId ?? result.id` 逐字沿用），属临时 stub 覆盖不足，不是实现缺陷。

## 已知限制

- 验证口径是**单测 + 构建 + stub 静态样张**，不是真实后端端到端验收；`/api/v1/repairs/*` 与 `/api/v1/transfers/*` 全部来自临时 stub（`/tmp/fams-stub-api.mjs`，非产品代码），真实状态机、权限矩阵与并发冲突未测。
- 样张里「新建报修」提交后的落点复用 stub 既有的 18 号记录（POST 固定返回 `repairId: "18"`），只用于目视「建单 → 详情」跳转后的布局，不代表真实新建记录的内容。
- eslint 对 `.vue` 无覆盖面（根配置 `sourceFiles` 只含 `*.{ts,tsx}`），本批 6 个 `.vue` 的模板未受 lint 检查，属既有盲区，未临时改配置。
- 报修详情对非法单号只清空正文、不给提示句（受「不新增用户可见文本」约束，与 HEAD 一致）；调拨详情有提示句是因为 HEAD 本就存在。两域口径因此不同，留待文案批次统一。
- 735 档 `新建调拨` 页的 `返回调拨列表` 会因 `uiDesc` 略长折到页头第二行左对齐（`@126`），同视口下 `调拨详情` 页仍在右侧（`@719`）——这是 `fams-page-head` 既有的 flex 换行行为（UI-16 已记录同类），非本批引入；`overlap=0`、无整页右溢。
- 调拨详情的 `申请 / 决定原因` textarea 打开时为空，与 HEAD 一致（HEAD 也从不回填 `selected.reason`），本批未改此行为。
- 拆页后「本人待接收」仍留在调拨列表页，与「申请与执行」两张表同屏；若后续要把接收做成独立工作区，需要新的路由与导航条目，本批未做。

## 下一批

UI-18：资产台账 `AssetLedgerView.vue`（329 行）拆为 `/assets`（列表）、`/assets/new`（建单）、`/assets/:id`（详情）。该域深链生产者需先盘点（`?asset=`、`/assets/{id}` 两类），并复核 `AppChrome.isCurrent("/assets")` 与 `/asset-returns` 前缀不冲突（UI-16 已实测过一侧）。
