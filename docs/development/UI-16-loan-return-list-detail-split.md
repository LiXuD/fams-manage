# UI-16 借用、延期与退还拆分为借用两页 + 退还三页

实施分支 `codex/d6-20-factory-ui`，紧接 UI-15（同一批未提交改动之上）。改前 `LoanView.vue` 一屏 299 行、`<script setup>` 占 131 行，把**两个对象域**叠在同一屏：`selected`（借用记录）与 `selectedReturn`（退还单）各有一套「列表 + 页内选中 + 详情 + 动作」，`choose(id)`/`openLoan()`/`openReturn()` 三个函数只做页内切换，`mutate()` 由延期、申请归还、退库建单、批准/驳回、领取、实物验收共用。按 IA D0.2，这是列表工作区与两类单对象概要+详情混装，而且比 UI-13/14/15 更严重——领用域只有一个对象，本域有两个。

路由形状先做了方案对比与示意图，由用户裁定为**方案 A「按对象拆」**：借用是一个对象（延期申请挂在其上，与 UI-15 的 `change` 同样留在详情页内），退还/退库是另一个对象（有自己的列表、建单口与审批+验收详情），因此是 5 个页面 4 条新路由，而不是 3 页或塞成 `/loans?tab=returns`。弹窗口径不变：本批不新增也不删除任何 `<dialog>`/`confirm`。侧栏随之加一条「退还与退库」，与「借用、延期与退还」平级——两处列表各自的服务端分页条件互不相干，合并成一条导航会让 `aria-current` 无法指向。

## 范围

| 文件 | 行数（script 块 / 总行） | 变化 |
|---|---|---|
| `views/LoanView.vue` | 131 / 299 → 48 / 113 | 只留借用列表工作区：HEAD 的 `load()` 一次 `Promise.all` 拉 **5 张表**（`/loans`、`/loans/extensions`、`/asset-returns`、`/asset-returns/eligible-assets`、`/loans/borrow-requests`）并取 5 个 total 的 `Math.max`，拆页后各页只读自己渲染的表；本页改为 `Promise.all([/loans?page=&pageSize=20, /loans/extensions?page=&pageSize=20])`，`total = Math.max(loanPage.total, extensionPage.total)`；行操作 `查看 / 延期 / 归还` 由 `<button @click="choose(row.id)">` 改为 `<router-link :to="/loans/{id}">`（文案逐字不变）；面板 ②「延期审批与历史」保留，其 `操作原因 / 验收说明` textarea + `批准延期`/`驳回延期` 与 `decide()` 整体留在此页（审批对象是延期申请，不是某条借用的详情动作）。删除借用详情面板、退还列表、退还详情、退库建单表单、可退库资产目录与全部 mutation 逻辑。列表 `#actions` 为空。 |
| `views/LoanDetailView.vue` | 新增 69 / 130 | `defineProps<{ id: string }>()`；`open()` 取 `/loans/{id}` 并回填 `assetId/assetVersion`，`loadRequests()` 取 `/loans/borrow-requests?page=1&pageSize=20` 供「接收前延期」选择；`extend(beforeReceipt)`、`proposeReturn()`、`chooseBorrowRequest()` 三段 payload 逐字搬自 HEAD。`mutate()` 保留幂等口 `pending ??= { path, version, key: crypto.randomUUID(), body }` + `retryable` + `重试上次操作`，并特殊处理退库建单成功后的跨页跳转（见下）。`onMounted` 用 `/^[1-9][0-9]{0,18}$/` 守卫非法单号，错误句复用域内既有措辞 `没有当前记录或操作的权限。` |
| `views/AssetReturnView.vue` | 新增 18 / 60 | 退还与退库列表工作区：只有一个 `refresh()`（`/asset-returns?page=&pageSize=20`）、翻页与状态 `fams-status`；行操作 `查看 / 审批 / 验收` 由 `<button @click="choose(row.id, true)">` 改为 `<router-link>`（文案不变）；`#actions` 放 `提请退库` 主操作。空态在 HEAD 原句 `当前没有退还单` 之后补一句极短下一步提示 `借用归还或在用资产退库提交后会出现在这里。` |
| `views/AssetReturnDraftView.vue` | 新增 43 / 70 | 提请退库建单：`chooseReturnAsset()` 与 `proposeReturn()` 校验句 `请选择借用或填写退库资产及原因。` 逐字搬入；面板标题 `在用资产退库 / 停用人员代办` 原是从 HEAD 的 `<details><summary>` 搬来，不再有折叠层。提交 `POST /asset-returns`（version 0）后 `push("/asset-returns/{id}?saved=1")`。 |
| `views/AssetReturnDetailView.vue` | 新增 79 / 140 | 单张退还单的全部动作：`admin/warehouse/bins/canInspect` 四个 computed、`open/refresh/mutate/retry/decide/inspect/catalog` 逐字搬入，三条校验句 `请填写决定原因。`、`请填写验收说明。`、`请核对实物编码并选择仓库、仓位。` 不变；`AssetReturnReassignment` 仍只在 `admin && returnStatus === 'WAREHOUSE_PROCESSING'` 挂载。`onMounted` 顺序为「非法守卫 → `open()` → 库管目录 → 消费 `?saved=1` 并立即 `replaceState` 清查询」。 |
| `loan/api.ts` | 34 → 38（+4） | 把原先内联在视图里的状态中文抽成导出常量 `loanLabels`（11 项，`OPEN/RETURNED/VOID/PENDING/APPROVED/REJECTED/PENDING_APPROVAL/WAITING_WAREHOUSE_CLAIM/WAREHOUSE_PROCESSING/COMPLETED/TRANSFERRED`），供借用详情与退还详情共用；措辞逐字照搬，无新增状态。 |
| `router.ts` | +8 | 4 条 import + 4 条路由：`/loans/:id`（借用详情）、`/asset-returns`（退还与退库）、`/asset-returns/new`（提请退库）、`/asset-returns/:id`（退还单详情），均 `uiGroup: "业务流转"`；字面量 `new` 排在 `:id` 之前，`:id` 路由 `props: true`。`/loans` 原路由与 `uiTitle/uiDesc` 不动。 |
| `components/AppChrome.vue` | +1 行 | 基础设施单列：`业务流转` 组内在「借用、延期与退还」之后、`调拨与人员交接` 之前插入 `{ label: "退还与退库", path: "/asset-returns", roles: FIELD_ROLES }`。`isCurrent()` 前缀高亮与 `aria-current` 是 UI-14 已就地的改动，本批未再碰。 |

深链生产者：HEAD 的 `?loan=` / `?return=` 页内选中查询在全仓**没有任何生产者**（`grep -rn "'/loans'" apps/web/src` 只有路由与本页翻页），因此不像 UI-14/UI-15 需要改指四处，只是该查询消费逻辑随拆页消失。`/transfers?id=` 两处仍留到 UI-17。本批未改任何样式文件。

## 不变量比对

- `scripts/check_script_untouched.sh` 不适用：拆页必然移动脚本。改用的是下面四项差集（脚本与输出 `/tmp/fams-ui16-{audit}.mjs` / `.txt`），证明「逻辑逐条照搬、只搬不写」。
- **控制签名差集**（HEAD 模板 24 种 / 并集 25 种）：HEAD-only 5 条、NOW-only 6 条，全部落在三类预期改名上——① 行内选中 `@click="choose(row.id)"` 与 `@click="choose(row.id, true)"` 两个 `<button>` 整体消失（改为 `router-link`，文案不变）；② `selectedReturn.*` → `selected.*`（3 条：批准/驳回/领取）；③ NOW-only 多出列表页翻页按钮形态与 `:disabled=busy @click=retry`。计数变化三条均属「原先一屏共用、现在每页各一份」：`刷新 1→4`、`重试上次操作 1→3`（另有 1 份是草稿页的 `:disabled=busy || retryable` 变体）、`textarea … v-model=reason 1→4`。
- **可见文案差**：HEAD 去重 78 段 → 并集 79 段，总段数 83 → 106。HEAD-only 4 段里 3 段是 `selectedReturn` 改名的同一句（`{{ selected.reason }}`、`退还 {{ selected.id }} · …`、`验收结果 {{ selected.inspectionResult }} · …`），第 4 段 `退还与退库` 移入路由 `uiTitle`；NOW-only 真正新增的用户可见文本只有跨页导航的 `返回借用列表` ×1 与 `返回退还列表` ×2。删除 0 句。其余计数变化：`{{ error }}` 1→5、`{{ notice }}` 1→3、`刷新` 1→4、`重试上次操作` 1→4、`操作原因 / 验收说明` 1→4、`提请退库` 1→2、`共 {{ total }} 条`/`第 {{ page }} 页`/`上一页`/`下一页` 各 1→2——全部是拆页后各页自带的框架句，无一句改写。
- **按钮/链接文案多重集**：HEAD 去重 16 → 并集 19，总份数 16 → 28。HEAD-only 恰 2 条是 `button:查看 / 延期 / 归还`、`button:查看 / 审批 / 验收`，在 NOW 侧以 `router-link:` 同名出现（文案逐字不变）；NOW-only 新增 3 条 = 上述两条的 link 形态去掉后剩下的 `返回借用列表`、`提请退库`、`返回退还列表 ×2`。`批准延期/驳回延期/提请延期/申请归还/批准退还/驳回退还/领取整单验收/提交实物验收/重试上次操作/刷新/上一页/下一页` 的文案与数量与 HEAD 一致。
- **符号差集**：HEAD 35 个 → 五文件并集逐一列出。HEAD-only 恰好 3 个函数：`fn:choose`、`fn:openLoan`、`fn:openReturn`——页内选中被路由取代，`openLoan/openReturn` 变成各自详情页的 `fn:open`；NOW-only 为 `fn:loadRequests`、`fn:open`（×2）、`fn:send` 与拆页后的管道变量（`props`、`router`、`assetId`、`requestPage`、`assetPage`、`returns`），无新增业务动作。**口径注意**：符号 tokenizer 只识别每行首个声明，本仓库习惯把多个 `const` 写在同一行，因此两侧集合都低估，结论只用于函数级核对。

## compat 回收

`compat.css` 已在 UI-07 整体删除，本批无回收项；`grep -rn "class=\"asset-\|class=\"panel\|<style scoped>" apps/web/src/views apps/web/src/components` 仍为 0 命中。

## 测试口径变化

HEAD 的 `views/LoanView.test.ts`（57 行 / 2 用例）两条断言都是本域最关键的安全口径，**逐条照搬、不放宽**，只是随逻辑搬到新页：

- 「延期响应丢失后重试沿用原日期、原因、版本与幂等键」（`expect(f.writes).toHaveLength(2); expect(f.writes[1]).toEqual(f.writes[0])` + `path: "/loans/11/extensions", version: 4, body: { expectedReturnDate: "2099-02-01", reason: "continue using" }`）→ `LoanDetailView.test.ts` 第 3 用例，逐字节同断言。
- 「申请归还提交当前资产版本而非借用版本」（`path: "/asset-returns", body: { assetId: "21", assetVersion: 7, reason: "return unused device" }`）→ `LoanDetailView.test.ts` 第 4 用例，并追加拆页带来的新断言 `expect(api.push).toHaveBeenCalledWith("/asset-returns/31?saved=1")`。

因 `LoanView.vue` 的模板与 `find('input[type="date"]')`、`button("提请延期")` 等定位不再存在，`LoanView.test.ts` 按拆页后的实际结构重写为 74 行 / 3 用例：只发两张表各一次读取、行操作恰为 `{查看 / 延期 / 归还 → /loans/11}`、文本含 `A21 · Computer`/`借用中`；管理员批准延期带原因提交到当前版本且缺原因时零写请求；非管理员按钮恰为 `["刷新","上一页","下一页"]` 且延期历史只读。

新增 4 个测试文件：`LoanDetailView.test.ts`（93 行 / 6 用例，含非法单号不向服务端发出、详情只取本条借用与其待交接申请、接收前延期取所选借用申请的版本与归还日、缺日期或原因零写请求）、`AssetReturnView.test.ts`（45 行 / 2 用例，含空态文案与 `links=["提请退库"]`）、`AssetReturnDraftView.test.ts`（56 行 / 3 用例）、`AssetReturnDetailView.test.ts`（106 行 / 6 用例：非法单号零请求、批准带原因与零写守卫、领取只提交版本不提交资料、实物验收齐备后提交资产与锁版本、`MISSING` 分支不带 `warehouseId`、`?saved=1` 只播报一次且 `window.location.search === ""`）。`AssetReturnReassignment` 子组件接口与实现本批未动，测试里以 stub 存在。

## 验证

| 门禁 | 结果 |
|---|---|
| `npx vue-tsc --noEmit` | 0 错误 |
| `npx eslint . --max-warnings 0` | No issues found（`.vue` 仍不在 `sourceFiles` 覆盖内，属既有盲区） |
| `npx vitest run`（apps/web 下） | Test Files 57 passed / Tests 221 passed（UI-15 为 53 / 203；本批 +4 文件 +18 用例；借用域 5 文件 / 20 用例单独复跑亦全绿） |
| `npx vite build` | CSS `31.42 kB / gzip 5.65 kB`，与 UI-12 起逐字节相同（本批未碰样式）；JS `632.58 kB / gzip 169.48 kB`（UI-15 为 `624.49 / 167.58`） |

CDP 量测（`/tmp/fams-stub-api.mjs` 扩出**有状态**的借用/退还 stub：两张列表、延期一次 502 注入、`/asset-returns` 建单、decision/claim/inspect、`eligible-assets` 目录，按 id 存取的 Map，让样张走真实状态流转；备份 `/tmp/fams-stub-api.pre-ui16.mjs`，splice 脚本与 fixture 为 `/tmp/fams-ui16-{fixture,handlers,splice}.mjs|js`，均为临时脚本非产品代码）。探针 `fams-ui16-probe.mjs` 27 个口径 + `fams-ui16-probe2.mjs` 补 4 个口径，共 **31 次量测**：

| 口径 | 结果 |
|---|---|
| 页头契约 | 31 次全部 `scrollY=0`；1440 档 `chromeBottom=64 headTop=80 h1Top=106 gapChromeToTitle=42`，735 与 390 档 `headTop=124 h1Top=150 gapChromeToTitle=86`，与 UI-10 起基线一致；`overlap=0` 全绿 |
| 侧栏高亮 | `/loans*` 的 10 个口径 `navCurrent=["借用、延期与退还"]`，`/asset-returns*` 的 21 个口径 `navCurrent=["退还与退库"]`，无一串台（`/asset-returns` 与 `/assets` 在 `isCurrent` 前缀匹配下不冲突，已实测） |
| 页头动作区 | 借用列表 `headActions=[]`；借用详情 `返回借用列表@1408`；退还列表 `提请退库@1408`（390 档 `@106`）；退库建单与退还详情 `返回退还列表@1408`（390 档 `@126`、735 档 `@719`） |
| 溢出 | `docScrollW == innerW`（1440/735/390 全等，无横向滚动）；`overflowRight` 仅 3 个窄屏列表口径 >0（`loans-list/390`=29、`returns-list/390`=30、`loans-list/735`=20），且 `overflowNotTable` 全为 0，即右溢元素全在 `.fams-table-scroll` 内，属既有设计 |
| 面板拆分 | 1440 档：借用列表 `借用记录@192 h=359` + `延期审批与历史@575 h=444`；借用详情单面板 `h=780`（重试态仍 780、延期成功后 826）；退还列表 `退还与退库@192 h=473`；提请退库 `h=358`；退还详情 待处理/已批准/新建 310 → 领取后 `h=636 cards=1`（转派卡 + 验收栅格）→ 验收完成 308 → 破损/缺失 346。首屏不再同时渲染「两张列表 + 两套详情 + 建单表单」 |
| 窄屏 | 390 档借用列表 `403 + 560`、735 档 `359 + 554`；借用详情 390 档 `h=1006`、735 档 `h=826`；退还列表 390/735 档 `610 / 530`；**信息密度最高的「领取后待实物验收」补测**（未动过的 52 号单）：390 档 `h=954`、735 档 `h=864`、1440 档 `h=636`，`cards=1` |
| 端到端（stub 真实点击） | 借用列表 `查看 / 延期 / 归还` → `/loans/11`；`提请延期` 首 POST 被注入 502 → `操作结果尚未确认，请重试上次操作或刷新查询。` + `重试上次操作` → 重试 → `操作已保存。`、`当前归还日 2026-11-12`、历史新增 `延期至 2026-11-12 · 已批准`；`/loans/13`（`lostLiability` 单）显示责任提示且只给接收前延期；`申请归还` → 跳 `/asset-returns/{id}?saved=1`；提请退库 → `/asset-returns/56?saved=1` 播报后查询串被清成 `/asset-returns/56`；`批准退还` → `待库管领取`；`领取整单验收` → `待实物验收` + 转派卡 + 验收栅格；空字段提交 `提交实物验收` → `请核对实物编码并选择仓库、仓位。`；补齐（仓库→仓位需分两次 evaluate，Vue 在下一 tick 才渲染 `bins`）→ `退还 53 · 验收完成` + `验收结果 NORMAL · …` |
| 非法 id | `/loans/..%2Fother` 与 `/asset-returns/..%2Fother` 均 `panels=[] buttons=[]`，正文只有 `没有当前记录或操作的权限。`，零服务端请求 |

样张 29 张：`/tmp/fams-ui16-{loans-list,loan-detail,loan-detail-retry,loan-detail-extended,loan-lost,returns-list,return-draft,return-created-saved,return-pending,return-approved,return-claim,return-inspecting,return-inspect-error,return-inspected,return-damaged,return-missing}-1440.png`（16 张），`/tmp/fams-ui16-{loans-list,loan-detail,returns-list,return-draft,return-inspecting}-{390,735}.png`（10 张），`/tmp/fams-ui16-return-inspecting-52-{1440,390,735}.png`（3 张）；量测原始输出 `/tmp/fams-ui16-probe.txt`、`/tmp/fams-ui16-probe2.txt`；门禁输出 `/tmp/ui16-{tsc,eslint,vitest,vitest2,vitest-loan,build}.txt`。

复核中修掉的问题：① 两处新测试把 `roles: ["ASSET_ADMIN"]` 直接塞进 `session.user`，`vue-tsc` 报 TS2322 `string[]` 不可赋给 `Role[]`，按仓库既有写法 `import type { Role } from "@/auth/types"` 并 `as Role[]`（改测试不改实现）；② 草稿页测试里未使用的 mock 形参 `path` 触发 TS6133，按既有习惯加下划线 `_path`；③ 首版 `LoanDetailView.test.ts` 草稿混了两种入参形态并留下一个坏掉的可选链表达式，重写为单一 `async fixture(id, lose)` 助手；④ stub fixture 因嵌套 `loanRow({...})` 括号不配平 `SyntaxError`，用括号深度脚本定位后改写为每行一个 const 并 `node --check` 通过，再从 `fams-stub-api.pre-ui16.mjs` 恢复重 splice；⑤ 探针首跑仓位 `select` 无选项（同一次 evaluate 内读 DOM，早于 Vue 的 `bins` 重渲染），拆成两次 evaluate 加 `wait(700)`；⑥ 390/735 档的「待实物验收」口径量测到的其实是已被 1440 段验收完的 COMPLETED 行（stub 有状态），补 `probe2` 用未动过的 52 号单重新领取后取样。

## 已知限制

- 「不新增用户可见文本」在本批有 3 处例外并已计入差集：`返回借用列表` ×1、`返回退还列表` ×2（跨页导航，UI-14/UI-15 同类），以及退还列表空态补的一句下一步提示。另有 4 条新路由的 `uiTitle/uiDesc` 页面描述文案为本批新写。
- HEAD `/loans` 的 `uiDesc` 仍写着「退库由库管实物验收后关闭责任」——退库现已拆到 `/asset-returns`，该句不再完全对应本页动作。按「不改写既有可见文本」的口径留给统一文案复核，本批不动。
- 拆页带来框架句重复：`操作原因 / 验收说明`、`刷新`、`重试上次操作` 各从 1 份变 4 份。原先它们在同一屏被两种对象共用（借用延期与退还审批各用一份 textarea），拆页后各页必须自有一份，属拆页固有代价而非冗余实现。
- 借用列表的 `延期审批与历史` 面板保留在列表页（HEAD 即如此，且审批动作的对象是延期申请而非借用记录）。这意味着列表页仍是两面板一屏，不像 UI-13/14 的列表那样只剩一张表；若后续要把延期审批也移进各借用详情，需先确认服务端能按借用单过滤延期申请。
- `total = Math.max(loanPage.total, extensionPage.total)` 是沿用 HEAD 的「两张表共用一套翻页」折中，只是参与 max 的表从 HEAD 的 5 张减到 2 张：真实服务端两张表的总数可能不一致，翻页时另一张表可能读不满或越界。HEAD 同一段逻辑本就一次拉 5 张表取 5 个 total 的 max，本批未改这一口径，留待后端契约专项。
- 两类目录型 `select`（借用详情的「接收前借用延期」申请选择、提请退库页的可退库资产选择）本批固定 `?page=1&pageSize=20`，不再跟随列表页码。HEAD 把共享的 `page` 一并传给了 `/loans/borrow-requests` 与 `/asset-returns/eligible-assets`，即点列表翻页会连带换掉下拉框内容——这是拆页顺带纠正的耦合，但若目录项超过 20 条，两个下拉都只拿得到第一页，属既有分页能力缺口，非本批引入。
- stub 有状态且会被探针点击改写，因此 390/735 档借用列表 `cards=3`、退还列表 `rows=6` 与 1440 首口径（`cards=2`、`rows=5`）不同；产品侧由服务端分页决定。
- 验证口径仍是「单测 + 构建 + 静态样张」，未接真实后端，不替代远端 CI 与正式 QA。UI-13、UI-14、UI-15、UI-16 均未提交。

## 下一批

UI-17 调拨（`TransferView.vue` 354 行）与报修（`RepairView.vue` 291 行，本域唯一已读 `route.params.id` 且带 generation 守卫的视图，风险最高放最后）；调拨拆页时须把 `ProxyConfirmationView.vue`、`BusinessResultReviewView.vue` 两处 `/transfers?id=` 深链一并改指详情页。其后 UI-18 资产台账（`AssetLedgerView.vue` 329 行 → `/assets`、`/assets/new`、`/assets/:id`），届时 `业务流转` 之外的分组高亮要再复核一次。
