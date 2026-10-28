# UI-15 领用与借用申请拆分为列表 / 草稿 / 详情三页

实施分支 `codex/d6-20-factory-ui`，紧接 UI-14（同一批未提交改动之上）。改前 `RequisitionView.vue` 一屏 399 行、4 个顶层 `fams-panel`（列表 + 建单表单 + 单张详情 + 可用库存选择），并且一个本地 `editing` 状态同时承担 `new | revise | change` 三态；`mutate()` 的 13 处调用、`open()`、`refresh()`、`retry()` 与列表 `load()` 全挤在一个脚本块里。按 IA D0.2 仍是列表工作区与单对象概要+分组详情两类页面混装。

本批的分层口径由用户裁定为「就按两个页面的方案继续做」：`new` 与 `revise` 共用一条草稿路由（同一表单、同一提交口），`change`（提请批准范围变更）**不进新路由**，留在详情页内作为动作展开面板——它是挂在某张具体单据上的追加提案，不是独立对象，拆成第三个页面反而要再传一次单号与版本。弹窗口径不变：本批不新增也不删除任何 `window.prompt`/`window.confirm`。三态共用的表单抽成子组件 `RequisitionDemandForm.vue`（本域第一次需要抽表单子组件：入库/盘点的表单只有单态，直接留在草稿页内）。

## 范围

| 文件 | 行数（script 块 / 总行） | 变化 |
|---|---|---|
| `views/RequisitionView.vue` | 137 / 399 → 21 / 72 | 只留列表工作区：`load()` 取单、`refresh()`、状态 `fams-status`、`?page=&pageSize=20&status=` 查询串逐字不变；行 `查看申请` 与主操作 `新建申请` 由 `<button>` 改为 `<router-link>`（文案不变），`新建申请` 进 `#actions`；删除建单表单、详情面板、库存选择、`RequisitionReassignment` 挂载与全部 mutation 逻辑。列表不再拉 4 类目录（`loadRequisitionCatalogs` 只在需要填需求的页面调用）。 |
| `views/RequisitionDemandForm.vue` | 新增 51 / 113 | 三态共用表单：`defineProps<{ mode: "new" \| "revise" \| "change"; catalogs; canShared; selfEmployeeId; selfOrganizationId; demands; requestType?; expectedReturnDate?; disabled }>()`，`@submit` 抛 `{ demands, requestType, expectedReturnDate: requestType === 'BORROW' ? expectedReturnDate : null }`。`newDemand()`、`setReference()`、11 个字段、`fieldset … 需求 N`、`增加需求`、`移除此需求`、`{{ mode === 'change' ? '提请变更确认' : '保存草稿' }}` 与提示句 `偏好不代表库存承诺。已履行和待交接数量不会被变更回退。` 全部原样搬入；原来内联的 `busy \|\| retryable` 改由父页传 `disabled`。 |
| `views/RequisitionDraftView.vue` | 新增 55 / 71 | `new`/`revise` 共用，`defineProps<{ id?: string }>()` 区分：无 `id` 提交 `POST ""`，有 `id` 先取单回填需求行、申请类型、期望归还日再提交 `PATCH "/{id}"`（沿用原对象 version）。`send()` 保留幂等口 `pending ??= { path, method, key: crypto.randomUUID(), version, body }` + `retryable` + `重试上次操作`。保存成功 `router.push("/requisitions/{id}?saved=1")`。非法 id 守卫 `if (props.id && !/^[1-9][0-9]{0,18}$/.test(props.id))` 在拉目录**之前**返回，错误句用域内既有措辞 `没有此申请或操作的当前权限。` |
| `views/RequisitionDetailView.vue` | 92 / 262 | 单张申请的全部动作：`open()`、`refresh()`、`mutate()`（13 处调用点原样搬入）、`retry()`、`findInventory()`、`handover()`、`change()`/`submitChange()`、`RequisitionReassignment` 绑定、角色判定 `creator/admin/canExecute/owner/warehouse` 逐字保留。`修改草稿` 由 `@click="edit('revise')"` 改为 `router-link :to="/requisitions/{id}/edit"`；`提请范围变更` 改为面板内 `changeVisible` 开关。`onMounted` 消费 `?saved=1` 播报 `操作已保存。` 后立即 `window.history.replaceState` 清查询。 |
| `requisition/api.ts` | 35 → 53（+18） | 抽 `requisitionLabels`（10 个状态中文，原内联在视图里）供列表与详情共用；新增 `loadRequisitionCatalogs()`，按 `category/organization/employee/location` 四类 `pageSize=200` 翻页取目录并回传 `canShared/employeeId/organizationId`（原草稿页内联逻辑，抽出来避免草稿页与详情页各写一份）。 |
| `router.ts` | +6 | 3 条 import + 3 条路由：`/requisitions/new`（新建申请）、`/requisitions/:id/edit`（修改草稿）、`/requisitions/:id`（领用申请详情），均 `uiGroup: "业务流转"`、`props: true`，字面量 `new` 与 `:id/edit` 排在 `:id` 之前。`/requisitions` 原路由与 `uiTitle/uiDesc` 不动。 |
| 4 个深链生产者 | 各 1–2 行 | `BusinessResultNoticeView.vue`、`BusinessResultReviewView.vue`、`ItemTargetChangeView.vue`、`ProxyConfirmationView.vue` 的领用跳转由 `{ path: '/requisitions', query: { id } }` 改为 `/requisitions/{id}`。改后 `grep -rn "path: '/requisitions'" apps/web/src` 为 0 命中。 |

本批未改任何样式文件。`AppChrome.isCurrent`（UI-13 引入）已就位，拆页后侧栏高亮不再动基础设施。

## 不变量比对

- `scripts/check_script_untouched.sh` 不适用：本批改动的正是脚本归属划分（状态与请求按页面重新分配），必然 MISMATCH。改用的是下面四项差集，证明「逻辑逐条照搬、只搬不写」。
- **控制签名差集**（HEAD 模板 49 条 vs 三页并集 47 条）：HEAD-only 9 条、NOW-only 7 条，全部落在四类预期改名上——① 表单子组件的 `:disabled="busy || retryable"` 改为父页传入的 `:disabled="disabled"`（4 条）；② `editing !== 'change'` → `mode !== 'change'`（3 条）；③ `@click="edit('change')"` → `@click="change"`（同 `v-if` 条件逐字不变）；④ 列表页 `:disabled="busy || retryable"` → `:disabled="busy"`（列表已无 pending 写请求）与 `@click="selectRequest(row.id)"` 一行整体消失（改为 `router-link`）。
- **可见文案差**（去标签逐行比对）：HEAD 124 段 → NOW 126 段。HEAD-only 3 段全是 `{{ editing … }}` 三元表达式本体（`新建数量申请/修改草稿/提请批准范围变更` 与 `提请变更确认/保存草稿`），NOW 侧同名表达式只把 `editing` 换成 `mode`；真正新增的用户可见文本只有一句 `返回申请列表`（跨页导航所需，出现在草稿页与详情页各一次）。删除 0 句。
- **按钮/链接文案多重集**：HEAD 27 → NOW 31。`新建申请`、`查看申请`、`修改草稿` 三个由 `<button>` 变 `<router-link>`，文案逐字不变、不是新增动作；`刷新结果` 与 `重试上次操作` 各由 1 份变 2 份（列表页与详情页各一份，原先两态在同一屏共用一个按钮）；新增 `返回申请列表` ×2。其余 22 个动作按钮（`提交管理员确认`、`取消申请`、`批准当前版本`、`驳回当前版本`、`领取整单处理权`、`提请范围变更`、`选择可用库存`、`记录无法满足`、`查看最新后续 / 失败后再申请`、`通知管理员核查`、`更换未交出资产`、`记录实物交出`、`取消未交出分配`、`登记待收回取消`、`确认收回验收并取消`、`分配此资产`、`上一页库存`、`下一页库存`、`增加需求`、`移除此需求`、`保存草稿`、`提请变更确认`）文案与数量全部一致。
- **符号差集**：HEAD 38 个 → 三文件并集 67 个。HEAD-only 恰好 5 个：`fn:edit`（拆为详情页 `fn:change` + 路由本身）、`fn:save`（拆为草稿页 `fn:send` 与详情页 `fn:submitChange`）、`fn:selectRequest`（→ 详情页 `fn:open`）、`var:route`、`var:path`（不再从查询参数读单号）。NOW-only 全是拆分后的管道（`props`、`emit`、`router`、`selected`、`changeVisible`、`catalogs`、`loaded`、`version`、`demands`、`expectedReturnDate`、`session`）与 `api.ts` 既有形参，无新增业务动作。
- `mutate(` 调用点：HEAD 13 → 详情页 13，路径串与 payload 形状逐字不变。

## compat 回收

`compat.css` 已在 UI-07 整体删除，本批无回收项；`grep -rn "class=\"asset-\|class=\"panel\|<style scoped>" apps/web/src/views apps/web/src/components` 仍为 0 命中。

## 测试口径变化

HEAD 的领用域只有一个测试文件 `components/RequisitionReassignment.test.ts`（受控转派子组件，本批未动其接口与实现），三个视图级测试都是本批新写的，因此不存在「为迁移放宽既有断言」的问题：安全口径（非法单号不向服务端发出）按 UI-13/UI-14 的同一标准写。

- `views/RequisitionView.test.ts`（新增 38 行 / 2 用例）：列表只发一次 `?page=1&pageSize=20`、不调 `loadRequisitionCatalogs`、链接恰为 `{查看申请 → /requisitions/9007199254740993, 新建申请 → /requisitions/new}`；状态筛选与翻页把条件带回服务端（`&status=DRAFT`、`page=2`）并保留 `共 25 条`。
- `views/RequisitionDraftView.test.ts`（新增 68 行 / 3 用例）：新建提交 `POST ""`、version 0、payload 含 `expectedReturnDate: null` 与目录快照 `{id, version}`，并 `push("/requisitions/…?saved=1")`；修订按 id 回填后提交 `PATCH "/{id}"`、version 5；非法单号既不拉目录也不请求单据、表单不渲染、显示 `没有此申请或操作的当前权限。`
- `views/RequisitionDetailView.test.ts`（新增 86 行 / 5 用例）：合法 id 原样按字符串请求一次且页面不出现建单文案；非法 id 零请求；`提交管理员确认` 走同一条写请求口（`objectContaining({ path, version: 5 })`）后状态转 `待管理员确认` 并播报 `操作已保存。`；`提请范围变更` 在详情页内展开表单（首行 input 值回填为 `十四寸`）、提交到 `/changes` 且面板随后关闭；`?saved=1` 只播报一次且 `window.location.search` 归零。

## 验证

| 门禁 | 结果 |
|---|---|
| `npx vue-tsc --noEmit` | 0 错误 |
| `npx eslint . --max-warnings 0` | No issues found（`.vue` 仍不在 `sourceFiles` 覆盖内，属既有盲区） |
| `npx vitest run`（apps/web 下） | Test Files 53 passed / Tests 203 passed（UI-14 为 50 / 193；本批 +3 文件 +10 用例） |
| `npx vite build` | CSS 31.42 kB / gzip 5.65 kB，与 UI-12 起逐字节相同（本批未碰样式）；JS 624.49 kB / gzip 167.58 kB（UI-14 为 620.31 / 166.63） |

CDP 量测（`/tmp/fams-stub-api.mjs` 扩成**有状态**的领用 stub：列表/取单/建单/PATCH/submit/decision/claim/changes/inventory/allocate 各 handler，让样张呈现真实状态流转而非错误提示；备份 `/tmp/fams-stub-api.mjs.bak15`，均为临时脚本非产品代码）。探针在 1440×900 走完 14 个口径、在 735×872 与 390×844 各走 3 个口径，共 20 次量测：

| 口径 | 结果 |
|---|---|
| 页头契约 | 20 次全部 `scrollY=0`；1440 档 `chromeBottom=64 headTop=80 h1Top=106 gapChromeToTitle=42`，735 与 390 档 `headTop=124 h1Top=150 gapChromeToTitle=86`，与 UI-10 起基线一致 |
| 侧栏高亮 | 20 次全部 `navCurrent=["领用申请与分配"]`；`headActions` 列表页为 `新建申请@1408`、其余各页为 `返回申请列表@1408`；`overlap=0` |
| 溢出 | `docScrollW == innerW`（1440/735/390 全等）；`overflowRight` 仅 390 列表为 31，且 `overflowNotTable` 全为 0，即右溢元素全在 `.fams-table-scroll` 内，属既有设计 |
| 面板拆分 | 1440 档 `.fams-panel` 高度：列表 458、新建 697、修订 1246、详情（草稿）630 → 详情（待库管领取）1140 → 详情（库管处理中）2385、可用库存面板 +324（@2601）、变更面板 1134。首屏不再同时渲染列表 + 建单表单 + 详情 |
| 端到端（stub 真实点击） | 列表 `查看申请` → `/requisitions/5101`；`提交管理员确认` → `操作已保存。` + `LY-2026-1101-01 · 待管理员确认`，动作条随之换成 `批准当前版本/驳回当前版本`；`批准` → `待库管领取` 并出现 `领取整单处理权`；新建页填两行需求 → `保存草稿` 落 `/requisitions/5199?saved=1` 并播报，随后 `修改草稿` → `/requisitions/5199/edit` 回填两行、保存后回详情页；`选择可用库存` → `可用库存 · 24 件` 面板 + `分配此资产` → 新增一条 `A-12` 分配卡（`操作已保存。`）；`提请范围变更` → 面板 `提请批准范围变更@272 h=1134`、`links` 只剩 `返回申请列表`（详情动作条随面板收起） |
| 非法 id | `/requisitions/..%2Fother` 与 `/requisitions/..%2Fother/edit` 均 `panels=[] cards=0 rows=0 buttons=[]`，正文只有 `没有此申请或操作的当前权限。`，零服务端请求 |

样张 18 张：`/tmp/fams-ui15-{list,new,detail-draft,detail-submitted,detail-approved,detail-saved,edit,detail-revised,detail-processing,detail-inventory,detail-assigned,detail-change-form}-1440.png`、`/tmp/fams-ui15-{list,new,detail}-{390,735}.png`；量测原始输出 `/tmp/fams-ui15-probe.txt`；三项差集脚本与输出 `/tmp/fams-ui15-{diff,labels,symbols}.mjs` / `.txt`。

复核中修掉的问题：① 新写测试里 mock 形参 `path`/`method` 未使用，`noUnusedParameters` 报 4 处 TS6133，按仓库既有写法加下划线前缀（改测试不改实现）；② 探针首跑 `保存草稿` 不跳转——新增的第二需求行为空，HTML5 `required` 拦截提交，属产品正确行为，改为把两行都填满后复跑；③ 探针首跑 `chromeBottom=134 / gapChromeToTitle=-28` 假象——固定顶栏在页面被 `.click()` 滚动后按 `r.top + scrollY` 量测，改为量测前 `window.scrollTo(0, 0)` 并回报 `scrollY`；④ 草稿保存的 `notice` 跨路由会丢，沿用 UI-14 的 `?saved=1` + 渲染后立即 `replaceState`；⑤ 无状态 stub 会让保存后跳详情读到 `receipts[0]` 那类回落，本批把 stub 改为按 id 存取的 Map，`LY-STUB-0001 · 草稿` 即新建单本身。

## 已知限制

- stub 有状态且会被探针点击改写，所以 390/735 的列表样张显示 5 行（1440 段探针先新建了一条 `5199`），而 1440 列表样张是干净的 4 行。产品侧行数由服务端分页决定，与拆分无关。
- 探针在 `提请范围变更` 点击后同帧读取 DOM，`panels=["提请批准范围变更@272 h=1134"]` 是面板展开后的量测；提交 `提请变更确认` 后的关闭态只由 `RequisitionDetailView.test.ts` 第 4 用例覆盖，未取样张。
- `fams-facts` 六格（申请/待分配/待交接/已完成/无法满足/已取消）在 1440 档把最后一格换行、在 390 档整列竖排，是 UI-10 起的既有 CSS 行为；本批未碰样式，标记结构逐字搬移。
- 列表页 `状态筛选` 的 `select` 在 `.fams-field-row` 内被拉满整行——HEAD 同一结构原样搬移（`git show HEAD` 可核对），非本批引入，留待窄屏密度专项统一裁量。
- 草稿页面板标题 `新建数量申请 / 修改草稿` 与路由 `uiTitle` `新建申请 / 修改草稿` 在修订态会同屏重复一次「修改草稿」，措辞均来自 HEAD，未改写。
- 原 `/requisitions?id=` 查询深链已全仓改指 `/requisitions/{id}`，未做旧链接兼容重定向（应用未部署）；`/transfers?id=` 仍剩 `BusinessResultReviewView.vue`、`ProxyConfirmationView.vue` 两处，留到 UI-17 一并处理。
- 验证口径仍是「单测 + 构建 + 静态样张」，未接真实后端，不替代远端 CI 与正式 QA。UI-13、UI-14、UI-15 均未提交。

## 下一批

UI-16 借用、延期与退还（`LoanView.vue` 299 行）——该域同时有「借用记录」与「退还单」两类对象、两套列表，先定路由形状（`/loans` + `/loans/:id` 是否要再分 `/returns`），再按本批四道门禁 + 三口径量测推进。其后：UI-17 调拨（`TransferView.vue` 354 行，含上面两处 `/transfers?id=` 深链改指）与报修（`RepairView.vue` 291 行，已读 `route.params.id` 且带 generation 守卫，风险最高放最后）、UI-18 资产台账（`AssetLedgerView.vue` 329 行）。
