# UI-18 资产台账拆为列表 / 单个建卡 / 资产详情三页

实施分支 `codex/d6-20-factory-ui`，紧接 UI-17（`43a89f5`）之上。改前 `AssetLedgerView.vue` 一屏 329 行、`<script setup>` 占 169 行，是全部视图里「一屏装三页」最典型的一个：`formOpen`（布尔）控制建卡表单显隐、`selected`（资产卡）由页内 `openCard(asset.id)` 选中，同一屏同时挂着**台账筛选 + 8 列分页表、7 字段 4 下拉的建卡表单（`selectedReference`/`submit`）、单卡详情（`fams-facts` 四项 + 标签预览 + 二维码）、以及复制建卡 / 修正备注 / 取消草稿 / 打印标签四个动作**，`custodyLabel()` 这个纯展示函数也被列表列与详情 `fams-facts` 共用而内联在视图里。按 IA D0.2，这是列表工作区与单对象概要+详情混装，且本域在 UI-03 首轮迁移时就是唯一「拆页未做、只换了外壳」的视图。

沿用用户裁定的**方案 A「按对象拆」**：资产卡是一个对象（复制、修正备注、取消、打印标签都挂在它上面），建卡是「还没有对象」的前置动作，因此各自成页。弹窗口径不变：本批不新增也不删除任何 `<dialog>`/`confirm`，三处 `window.prompt` 按用户裁定**逐字照搬**，与 `lifecycle`/`cardStage` 的英文枚举文案一起留到 UI-19。侧栏条目不动——`资产` 组下仍是 `资产台账` 一条，`/assets/new` 与 `/assets/:id` 由 `AppChrome.isCurrent()` 前缀匹配归入同一条目，26 个量测口径 `navCurrent` 恒为 `["资产台账"]`，无一串台（`/asset-returns`、`/asset-imports` 不同前缀，UI-16 已实测过一侧）。

## 范围

| 文件 | 行数（script 块 / 总行） | 变化 |
|---|---|---|
| `views/AssetLedgerView.vue` | 169 / 329 → 27 / 96 | 只留列表工作区：`rows/total/page/code/nameFilter/error/load` 与筛选 `@submit.prevent="page = 1; load()"`、8 列表、空态行 `暂无符合条件的资产` + 下一步提示、`共 N 项` 与 `:disabled="page <= 1"` / `page * 20 >= total` 分页全部逐字保留。行操作 `详情` 由 `<button @click="openCard(asset.id)">` 改为 `<router-link :to="/assets/{id}">`（文案逐字不变）；`#actions` 的 `单个建卡` 由 `@click="openForm"` 按钮改为 `router-link to="/assets/new"`，`v-if="canWrite"` 条件不变。删除 `selected/tag/qrImage/notice/busy/formOpen` 与四个动作函数——列表侧 `notice`/`busy` 在拆页后已无写入者，故一并删净。 |
| `views/AssetDraftView.vue` | 新增 63 / 127 | 建卡：`busy/error` + 七个 `ref` + 四个目录 `ref` + `selectedReference` + `submit` 逐字搬自 HEAD；HEAD 的 `openForm()` 里「清字段 + 拉四类基础资料」拆成 `load()` 并在 `onMounted(load)` 调用（页内折叠态不再需要复位）。`POST /assets` 成功后由「页内切详情」改为 `push("/assets/{created.id}?saved=1")`。面板标题 `单个建卡 · 保存草稿`、7 个字段与 4 个 `<select>` 的顺序、`maxlength="256/128/32"`、`required`、`保存草稿` 的 `:data-state="busy ? 'loading' : null"` 与校验句 `请填写资产名称` 全部不变；HEAD 表单里的 `取消` 按钮删除，由 `#actions` 的 `返回台账列表` 承担。 |
| `views/AssetDetailView.vue` | 新增 92 / 137 | `defineProps<{ id: string }>()` + `useRoute()`；单张资产卡的四个动作 `correctNotes/copyCurrent/cancelCurrent/printCurrent` 与 `selected/tag/qrImage/error/notice/busy` 逐字搬入，只删去 HEAD 每个动作尾部的 `await load()`（详情不再需要回读列表）。HEAD 的 `openCard(id)` 改名 `open()`，内部 `getAsset → getTag → QRCode.toDataURL(…, { width: 160, margin: 1 })` 三步与 `catch` 文案不变；三处 `window.prompt("备注修正"/"复制建卡数量（1–100）"/"取消原因")` 原样保留。`#actions` = `返回台账列表`，替代 HEAD 的 `关闭` 按钮。`onMounted` 顺序「非法守卫 `/^[1-9][0-9]{0,18}$/` → `open()` → 消费 `?saved=1` 并 `replaceState` 清查询」，守卫错误句复用域内既有的 `资产详情加载失败`（HEAD `openCard` 的 `catch` 兜底句，未新造文字）。 |
| `asset/api.ts` | 93 → 105（+12） | `custodyLabel()` 从视图内联函数原样提升为导出函数（含内部 `referenceId` 闭包与 `尚未入库 / 在库 · 仓库 N / 部门公用 · 责任人 N / 个人保管 · 责任人 N` 四句措辞），供列表 `正式保管` 列与详情 `当前保管` 事实项共用；先例是 UI-17 把状态中文抽成 `loan/api.ts` 的 `transferLabels`。 |
| `router.ts` | +4 | 2 条 import + 2 条新路由：`/assets/new`（`name: asset-new`）、`/assets/:id`（`name: asset-detail`），均 `props: true`、`uiGroup: "资产"`，字面量 `new` 排在 `:id` 之前。`/assets` 一条列表路由与 `uiTitle/uiDesc` 逐字不动，两条新页的 `uiTitle/uiDesc` 是本批唯一的导航级新文字。 |
| 深链生产者 | 0 处 | `Grep '/assets' apps/web/src/**.{vue,ts}` 全仓核对：HEAD 除本视图自身的页内选中外，**没有任何跨视图指向 `/assets` 的路由链接或查询**（`InventoryFindingPanel.vue:65` 的 `/assets?page=` 是 API 路径，不是路由）。因此本批不像 UI-17 那样需要改 3 处深链。 |

本批未改任何样式文件。

## 不变量比对

`scripts/check_script_untouched.sh` 不适用：拆页必然移动脚本。改用四项集合差集（脚本与输出 `/tmp/fams-ui18-audit.mjs` / `.txt`），以 HEAD 的 `AssetLedgerView.vue` 为基线，与工作区 3 个文件并集比对，证明「逻辑逐条照搬、只搬不写」。

- **控制签名差集**（HEAD 21 种 / NOW 17 种）：**NOW-only 恰 0 条，即本批没有新增、改写或参数化任何一个原生控件**——`input/select/textarea` 的 `id/class/maxlength/required/v-model` 与 `type=submit/type=button` 全部原样。HEAD-only 4 条全是「页内切屏」的四枚 `<button>` 整体被路由取代：`@click="openForm"`（`单个建卡`）、`@click="openCard(asset.id)"`（`详情`）、`@click="formOpen = false"`（`取消`）、`@click="selected = null"`（`关闭`）。计数变化 0 条——本批不像 UI-17 那样出现「一屏共用、现在每页各一份」，因为台账的 `刷新/重试` 一类共享控件本就不存在。
- **可见文案差**：HEAD 去重 57 段 → 并集 56 段，总段数 68 → 70。HEAD-only 恰 2 段 = `关闭`、`取消`（上面两枚被路由取代的按钮的文字）；NOW-only 1 段 = `返回台账列表 ×2`（建卡页与详情页各一枚，是 UI-13～UI-17 五批一致的跨页导航句）。**除这两处被导航取代的按钮文字外，无一句用户可见文本丢失或改写。** 计数变化只有 `{{ error }}` 1→3——拆页后三页各自渲染错误提示条，插值表达式本身逐字相同。
- **按钮/链接文案多重集**：HEAD 去重 12 → 并集 11，**总份数 12 → 12 不变**。HEAD-only 4 条 `button:单个建卡 / button:详情 / button:取消 / button:关闭` 中，前两枚在 NOW 侧以完全同名（`单个建卡`、`详情`）的 `router-link:` 形态存在，后两枚即被 `router-link:返回台账列表 ×2` 取代；`查询 / 上一页 / 下一页 / 保存草稿 / 复制建卡 / 修正备注 / 取消草稿 / 打印标签` 的文案与数量与 HEAD 一致。
- **符号差集**：HEAD 单文件 10 个函数 → 三文件并集 9 个函数（`/tmp/fams-ui18-audit.txt` 末段逐一列出）。差异恰好 4 项且全部可归因：`fn:custodyLabel` 移出视图（进 `asset/api.ts`，非删除）、`fn:openCard` → 详情的 `fn:open`、`fn:openForm` → 建卡页的 `fn:load`（`onMounted` 钩子取代按钮触发）、`var:formOpen` 随折叠态消失；`var:notice` 从列表侧消失但仍在详情页。NOW-only 为 `var:props`、`var:route`、`var:router` 三条拆页管道变量。**未新增任何业务动作函数。** 口径注意：符号 tokenizer 只识别每行首个声明，本仓库习惯把多个 `const` 写在同一行，两侧集合都低估，结论只用于函数级核对。
- 脚本 sha256 逐字节比对（UI-01～UI-12 那类外壳批次的核心口径）在本批不适用：HEAD `AssetLedgerView.vue` 的 `<script>` 块 `a868b34a…` 与拆页后三块必然不同，改由上面四项集合差 + 18 个新用例断言请求体来证明「只搬不写」。

## compat 回收

`compat.css` 已在 UI-07 整体删除，本批无回收项；`Grep 'class="asset-|class="panel|<style scoped' apps/web/src/**/*.vue` 仍为 0 命中。

## 测试口径变化

本域在 HEAD **没有任何测试**（`git ls-tree -r HEAD apps/web/src/views` 里 `AssetLedgerView.vue` 无同名 `.test.ts`，是全部已拆视图域中唯一零覆盖的一个），因此本批不存在「照搬既有断言」，18 个用例全部是**净增覆盖**，且没有放宽任何既有断言。`AppChrome.test.ts:68-69` 早已存在的 `/assets/:id` 侧栏前缀高亮用例本批未改且仍通过。

新写测试时有一处口径需要记录：**jsdom 不在 `<button type="submit">` 上点击时派发 `submit` 事件**，因此三个新文件统一用 `await wrapper.find("form").trigger("submit")`（仓库既有 14+ 个 `.test.ts` 同口径），不是产品行为差异。另有两处是拆页带来的选择器收窄，均不改断言内容：详情页的按钮集合按 `cardStage` 断言（`ACTIVE=["复制建卡","修正备注","打印标签"]`、`DRAFT=["复制建卡","修正备注","取消草稿"]`、`CANCELLED=["复制建卡"]`），因此 `labels` 必须是活取值 getter 而不是夹具创建时的快照；`vi.mock("@/asset/api", importOriginal => …)` 保留真实的 `custodyLabel`，只 mock 网络函数，以便列表列与详情事实项的中文仍被真正执行。

新增测试：`AssetLedgerView.test.ts`（82 行 / 4 用例：挂载只发一次 `listAssets(1,"","")` + 行链接与建卡入口链接逐字节 `[["详情","/assets/21"],["详情","/assets/22"],["单个建卡","/assets/new"]]`、`EMPLOYEE` 角色不出现建卡入口且翻页只改页码、查询回到第 1 页并带上编码与名称条件、空结果的空态句）、`AssetDraftView.test.ts`（78 行 / 4 用例：挂载只读四类目录且 `#actions` 只有 `返回台账列表`、名称留空时校验句与零写请求、完整提交的请求体 `{ name, model, unit, category:{id:"c1",version:2}, … }` 与 `push("/assets/107?saved=1")`、只填名称时的最小请求体）、`AssetDetailView.test.ts`（161 行 / 10 用例：详情 + 标签 + 二维码三次读取的入参、`update("21",7,{notes:"同意修正"})`、`copy("21",7,2)`、`cancel("21",1,"重复建卡")`、`reprint("21",7)`、`toDataURL("https://tag.example/FA-2026-000101",{width:160,margin:1})`、三种 `cardStage` 的按钮集合、`EMPLOYEE` 角色零写请求、非法单号不向服务端发出任何请求且只渲染 `资产详情加载失败`、`?saved=1` 播报一次后 `window.location.search === ""`）。

## 验证

| 门禁 | 结果 |
|---|---|
| `npx vue-tsc --noEmit` | 0 错误 |
| `npx eslint . --max-warnings 0`（仓库根） | No issues found（`.vue` 仍不在根 `eslint.config.mjs` 的 `sourceFiles` 覆盖内，属既有盲区） |
| `npx vitest run`（apps/web 下） | Test Files 64 passed / Tests 260 passed（UI-17 为 61 / 242；本批 +3 文件 +18 用例，全部通过） |
| `npx vite build` | CSS `31.42 kB / gzip 5.65 kB`，产物名 `index-CvDOmE8w.css` 与 UI-12 起逐字节相同（本批未碰样式）；JS `642.57 kB / gzip 171.51 kB`（UI-17 为 `641.12 / 170.79`，+1.45 kB 为 2 条新路由与拆出的模板） |

CDP 量测（`/tmp/fams-stub-api.mjs` 扩出**有状态**的资产 stub：`GET /assets` 列表分支改为按 `req.method` 分流（避免 POST 被列表吞掉，UI-17 踩过的同一陷阱）、`GET /assets/{id}` 按 id 取卡、`POST /assets` 真实追加 `id=107 / FA-2026-000201` 草稿、`PATCH`（备注，版本 +1）、`POST /copy`（按数量追加多张草稿）、`POST /cancel`（置 `CANCELLED` 并回版本 +1）、`GET /{id}/tag` 与 `POST /{id}/tag/reprint`；另补 `cardStage=PENDING_INBOUND` 夹具 108 以覆盖按钮抑制分支；改前备份 `/tmp/fams-stub-api.pre-ui18.mjs`，探针 `/tmp/fams-ui18-probe.mjs`，三处 `window.prompt` 由 CDP `Page.javascriptDialogOpening` + `handleJavaScriptDialog` 自动应答走通，`window.print` 在探针里替换为空函数，均为临时脚本非产品代码）。**26 个口径量测 + 25 张整页样张**，`MISS` 0 次：

| 口径 | 结果 |
|---|---|
| 页头契约 | 26 次全部 `scrollY=0`；1440 档 `chromeBottom=64 headTop=80 h1Top=106 gapChromeToTitle=42`，735 与 390 档 `headTop=124 h1Top=150 gapChromeToTitle=86`，与 UI-10 起基线一致；`overlap=0` 全绿 |
| 侧栏高亮 | 三页 26 个口径 `navCurrent` 恒为 `["资产台账"]`，`/assets/new` 与 `/assets/:id` 未新增条目也未串台 |
| 页头动作区 | 列表 `单个建卡@1408`；建卡页与详情页 `返回台账列表@1408`。390 档 `单个建卡@98`、`返回台账列表@126`（折到页头第二行左对齐，与 UI-16/UI-17 同类）；735 档列表 `@719`、建卡与详情 `@126` |
| 溢出 | `docScrollW == innerW`（1440/735/390 全等，无整页横向滚动）；`overflowRight` 只在三个台账列表口径 >0（1440 满页 41、探测后 22 行 49、735 档 162、390 档 230），且 `overflowNotTable` 全为 0，即右溢元素全在 `.fams-table-scroll` 内；建卡页与详情页三档 `overflowRight` 恒 0 |
| 面板与首屏 | 列表 `台账筛选与列表@192 h=1942`（18 行）→ 空态 `h=343` → 筛选命中 4 行 `h=504`；建卡页 `create-title@192 h=404`（390 档 `h=752`、735 档 `h=764`）；详情 `detail-title@192 h=590`，含实物去向时 `h=654`，带提示条时面板整体下移到 `@272`。首屏不再同时渲染「列表 + 建卡表单 + 详情 + 标签预览」 |
| 详情事实与标签 | `facts` 四项恒为 `分类 / 型号 / 所属组织 / 当前保管`，`当前保管` 三种中文（`个人保管 · 责任人 E-3301`、`部门公用 · 责任人 E-1102`、`在库 · 仓库 W-01` 在列表侧、`尚未入库`）与 HEAD 逐字一致；标签预览 `260×336`、二维码 `160×160` 三档均在容器内不越界 |
| 端到端（stub 真实点击） | 列表行 `详情` → `/assets/101`；`修正备注` 应答 prompt → 提示条 `备注已更新` 且副标题版本 `7 → 8`；`复制建卡` 应答 `3` → `已创建 3 张独立草稿`，回列表多出 3 行（`rows 18 → 22`）；`打印标签` → `打印任务 JOB-FA-2026-000101 已完成；标签身份保持不变` 且二维码刷新；`/assets/105` 草稿三按钮 → `取消草稿` 应答原因 → `草稿已取消，编码永久保留` + 副标题转 `CANCELLED · 版本 2` + 按钮集合收缩为 `["复制建卡"]`；`/assets/106`（CANCELLED 夹具）与 `/assets/108`（PENDING_INBOUND 夹具）同样只有 `复制建卡`，证明两个 `v-if` 抑制条件搬移后仍生效；建卡页填 7 项（含 4 个下拉）→ `保存草稿` → 落点 `/assets/107`、播报 `草稿 FA-2026-000201 已保存`、URL 已把 `?saved=1` 清成 `/assets/107` |
| 非法 id | `/assets/..%2Fother` 的 `panels=[] buttons=[] facts=[]`，正文只有 `资产详情加载失败`，零服务端请求 |

样张 25 张：1440 档 17 张 `/tmp/fams-ui18-{ledger-list,ledger-list-empty,ledger-list-filtered,draft-new-empty,draft-new-filled,detail-saved,detail-active,detail-notes-updated,detail-copied,detail-printed,detail-draft,detail-cancelled,detail-cancelled-fixture,detail-pending-inbound,detail-physical-destination,detail-malformed,draft-name-empty}-1440.png`；390 与 735 档各 4 张 `/tmp/fams-ui18-{ledger-list,draft-new,detail-active,detail-draft}-{390,735}.png`。量测原始输出 `/tmp/fams-ui18-probe.txt`，审计输出 `/tmp/fams-ui18-audit.txt`。

复核中修掉的问题：① 首版 `AssetLedgerView.vue` 拆完仍留着 `notice`/`busy` 两个 `ref`，已无写入者，按「不留死代码」删净；② 新测试沿用 `@click` 提交按钮的直觉写法导致 3+2 个用例失败（jsdom 不派发 submit），改为 `form.trigger("submit")` 后全绿，并把 `fixture(25)` 用于翻页用例（`total=2` 时 `page * 20 >= total` 本就把 `下一页` 置灰）；③ `上一页` 的 disabled 断言位置写反（应在跳到第 2 页之前），已调整；④ 详情测试的按钮集合最初在夹具创建时快照一次，取消草稿后不反映重渲染，改为活取值 getter；⑤ 测试里误把权限名 `ASSET_LEDGER_READ` 当角色填进 `roles: Role[]`，读 `auth/types.ts` 后改用 `EMPLOYEE`；⑥ 给 stub 打补丁时第一次 Edit 误删了 `GET /{id}/tag` 分支，随即补回并用 curl 复验 200；⑦ 一次断言里写了 `toContain("A · B".replace(" · ", ""))` 这种自造字符串，替换为两条独立 `toContain`。产品代码在这七处均与最终差集口径一致，未因测试失败而改动实现。

## 已知限制

- 验证口径是**单测 + 构建 + stub 静态样张**，不是真实后端端到端验收；`/api/v1/assets/*` 九个端点全部来自临时 stub（`/tmp/fams-stub-api.mjs`，非产品代码），真实状态机、权限矩阵、乐观锁冲突与二维码真机扫码未测。样张里的数据是 stub 夹具，且探针自身会改动状态（101 版本 7→8、105 变 CANCELLED、多出 4 行），复跑得到的行数与版本号会变。
- 三处 `window.prompt`（`备注修正`、`复制建卡数量（1–100）`、`取消原因`）与 `lifecycle`/`cardStage` 的英文枚举按用户裁定**本批照搬**，样张与量测里 `IN_USE / IDLE / DRAFT / ACTIVE / CANCELLED / PENDING_INBOUND / DELIVERED` 以及详情副标题 `FA-… · DRAFT · 版本 1` 全是英文，留到 UI-19 一并改。
- 1440 档 8 列表格的 `操作` 列落在面板右缘之外，需要 `.fams-table-scroll` 自身横向滚动才能点到 `详情`（`overflowRight=41`、`overflowNotTable=0`）。CSS 层自 UI-12 起产物逐字节未变、表格模板与 HEAD 完全相同，因此这是既有行为而非本批引入；若要收敛，需要单独一批处理列宽或把 action 列做成 sticky，本批未动。
- 建卡页资产名称留空点 `保存草稿` 不会出现 `请填写资产名称`——HTML5 `required` 先拦截提交，JS 守卫是后备（与 UI-15 记录同类现象），样张 `draft-name-empty-1440.png` 即该状态。
- 本域没有幂等重试口：`Idempotency-Key` 由 `asset/api.ts` 的 `request()` 每次请求内 `crypto.randomUUID()` 新生成，响应丢失后无「重试上次操作」可点，与 HEAD 一致（UI-17 的调拨/报修域有该口，两域口径因此不同）。
- eslint 对 `.vue` 无覆盖面（根配置 `sourceFiles` 只含 `*.{ts,tsx}`），本批 3 个 `.vue` 的模板未受 lint 检查，属既有盲区，未临时改配置。
- `?saved=1` 的播报句 `草稿 {assetCode} 已保存` 里的编码取自详情 `GET` 而非 `POST` 响应；两者不一致时以 `GET` 为准（HEAD 是页内直接复用 `POST` 结果，这是拆页带来的唯一取值路径变化，已在用例里按 `GET` 结果断言）。
- GitNexus MCP 本会话不可用，AGENTS.md 要求的 `detect_changes()` / `impact()` 未执行，改以四项集合差 + 全量门禁 + 全路由样张替代；`git config core.hooksPath` 仍为空，提交消息校验 hook 与远端 CI 均未生效，不声称其生效。

## 下一批

UI-19（本域交互与文案改造）：把详情页三处 `window.prompt` 改为页内字段 + 基线确认层（沿用 UI-10d 的焦点层与 UI-11 的原生 `dialog` 预览口径），并为 `lifecycle`/`cardStage`/`physicalDestination.status` 引入中文标签字典（先例：UI-17 的 `transferLabels`）。因涉及新增用户可见文字，**先出文案清单给用户过目再动手**。之后仍有 `AssetImportView`、`AssetInitializationView` 等未拆的一屏多态视图，按同一天平继续。
