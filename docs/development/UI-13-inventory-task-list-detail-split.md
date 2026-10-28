# UI-13 盘点任务拆分为列表 / 草稿 / 详情三页

实施分支 `codex/d6-20-factory-ui`，紧接 UI-12（工作区未提交改动之上）。用户反馈：菜单里的页面全是平铺的，没有弹窗、卡片分层，例如盘点任务从上到下全在一个页面里，「保存草稿」像是该做成弹窗，草稿保存后应当以列表呈现。按 IA D0.2 与 D0.4 SPECIFICATION 复核后确定口径：盘点任务属于「列表工作区 / TableRow / Filter / Status / 分区工具栏」（P15 一档），其单对象操作属于「概要 + 分组详情 / Status / Timeline / Notice / 对象级动作」（P04、P11、P17—P22 一档）；基线里的弹窗只承担确认与预览（原生 modal dialog，默认焦点在取消、Escape 关闭并返回触发点），不承载新建表单。因此本批不做「草稿弹窗」，改为把一屏平铺的盘点任务拆成列表 → 独立详情，并把新建/修改草稿各自成页，先在盘点域把这套分层跑成样板。破坏性动作的 `window.confirm` 与预览口径保持原位。

## 范围

| 文件 | 行数（script / template） | 变化 |
|---|---|---|
| `views/InventoryTaskView.vue` | 296（130 / 166）→ 73（25 / 48） | 只留列表工作区：`/inventory-tasks?page&pageSize=20` 取任务、状态 `fams-status`、`打开` 由 `@click="choose(t.id)"` 改为 `router-link :to="/inventory-tasks/${t.id}"`；主操作 `新建盘点草稿` 进 `#actions`；删除草稿表单、详情 6 个面板与全部 mutation 逻辑。 |
| `views/InventoryTaskDraftView.vue` | 新增 103（66 / 37） | 新建与修改草稿共用一个视图，`defineProps<{ id?: string }>()` 区分：无 `id` 提交 `POST /inventory-tasks`（version 0），有 `id` 先按 `?page=1&pageSize=1` 回填名称、时间、`range_json` 再提交 `POST /inventory-tasks/:id/revision`（version 取任务当前值）。字段、校验、`请填写名称和有效的开始、截止时间。`、目录分页拉取（5 类，`pageSize=200` 续页）与 pending 重放写法逐字照搬原视图。保存成功 `router.push` 到新任务详情。 |
| `views/InventoryTaskDetailView.vue` | 新增 199（97 / 102） | 单任务全部操作：`open()` 按 `props.id` 取详情，预览/发布/复制范围/手动开始/分配/复核/取消/终止/删除与 6 个面板原样搬入；`manageable` 抽自原内联 `['READY','IN_PROGRESS','WAITING_REVIEW'].includes(...)`；`编辑草稿` 改为 `router-link` 到 `/inventory-tasks/:id/edit`；`send()` 成功且返回 id 变化（复制范围）时 `router.push` 到新任务，`DELETED` 时回列表；`watch(() => props.id)` 复位分页并重取。 |
| `router.ts` | 76 → 81 | 4 条路由：`/inventory-tasks`、`/inventory-tasks/new`、`/inventory-tasks/:id/edit`、`/inventory-tasks/:id`（`new` 与 `:id/edit` 必须排在 `:id` 之前），三条新页 `props: true` 以便无路由挂载测试；`uiTitle`/`uiDesc` 由外壳页头渲染，原页面内 `<h1>` 与分组标题不重复。 |
| `components/AppChrome.vue` | 186 → 192 | 基础设施例外：新增 `isCurrent(path)` 按路径前缀判定，给侧栏条目补 `:aria-current="… ? 'page' : undefined"`。vue-router 5.3.1 用扁平路由记录，`router-link-active` 不会命中父级条目，拆页后侧栏高亮必然失效；此改动同时激活 `styles/components/navigation.css:22`、`:40` 里此前无人写入的 `[aria-current]` 规则。 |

本批未改任何样式文件。

## 不变量比对

- `scripts/check_script_untouched.sh` 对本批不适用：这批改动的就是脚本（状态与请求按页面归属重新划分），5 个业务文件必然 MISMATCH。改用的证明是「逻辑逐条照搬」：`save()` 的校验与请求体、`send()` 的 `pending ??=` 与 4xx 清 pending、`publish()` 的 `previewDigest` 前置校验、`copyRange/start/assign/pages/previewPublishedRange` 与全部 `window.confirm` 文案均按原表达式搬移，仅把 `detail.task.id`/`editing` 之类的本地状态换成 `props.id`。
- 绑定集合差（HEAD 模板 vs 三个新模板并集，属性名排序去重）：新增只有 `:to`，删除为 0。`v-model`、`maxlength`、`:value`、`:aria-label`、`:colspan`、`@change`、`@submit.prevent` 全部保留在对应页面。
- 函数映射（HEAD 14 个 → 列表 3 + 草稿 3 + 详情 9）：`list/refresh` 留列表；`open/send/previewPublishedRange/publish/copyRange/action/assign` 原样进详情；`save/send` 进草稿。合并或改名的只有三处——`pages(delta, items)` 与 `choosePage()` 按页面各留一个 `pages(delta)`（列表翻任务页、详情翻应盘项页，原 `items` 分支参数消失）、`edit(task)` 的内联 `local` 箭头函数提为草稿的 `toLocal(value)`、`choose(id)` 与 `edit(task)` 两个「切换本地状态」函数由路由取代（删除，不保留空壳）。
- 可见文案差：删除 `新建其他任务`（原「保存后再建一个」的内联按钮，拆分后由列表页 `新建盘点草稿` 承担）和面板标题 `{{ editing ? "修改草稿" : "创建盘点草稿" }}`（由路由 `uiTitle` 的「新建盘点草稿 / 修改盘点草稿」承担）；新增 `新建盘点草稿`、`返回任务列表`、草稿页下一步提示 `范围留空表示全部。保存草稿不会冻结集合；到任务详情页预览应盘数量后再发布。`，并把管理员空态从 `先在上方创建草稿并预览应盘数量。` 改写为 `新建盘点草稿设置范围，保存后到详情页预览并发布。`（空态行补下一步提示，属词表允许的例外）。其余文本逐字一致。
- 按钮文案序列：列表 `{刷新, 上一页, 下一页, 打开}`、草稿 `{保存草稿, 重试原操作}`、详情 `{刷新, 重试原操作, 复制范围创建新草稿, 预览应盘数量, 发布并冻结集合, 手动开始, 删除草稿, 取消无结果任务, 终止并保留结果, 分配所选应盘项, 上一页, 下一页}`，并集与 HEAD 一致（差异仅为上面两个改名/删除项）。

## compat 回收

`compat.css` 已在 UI-07 整体删除，本批无回收项；`grep -rn "class=\"asset-\|class=\"panel\|<style scoped>" apps/web/src/views apps/web/src/components` 仍为 0 命中。

## 测试口径变化

拆分改变了「一个视图一个页面」这一断言前提，以下 4 个测试文件按新结构重写/新增（未放宽任何断言口径）：

- `views/InventoryTaskView.test.ts`：由 1 个「整页含草稿表单」的用例改为 2 个——列表只发 1 次请求、`新建盘点草稿` 指向 `/inventory-tasks/new`、行 `打开` 指向 `/inventory-tasks/9007199254740993`，且草稿表单不在页面上；执行人角色得到「我的任务」、无主操作、无行链接。AppChrome 桩补 `<slot name="actions" />`。
- `views/InventoryTaskDetailView.test.ts`（新增）：原「预览丢失应答后重试必须复用同一条写请求」的用例整体搬到详情页（`props: { id }` 挂载，断言 `writes[1] toEqual writes[0]`、路径 `/inventory-tasks/…/publication`、version 0、`body { expectedCount: 2, previewDigest: "a"×64 }`、发布前按钮 disabled、状态转「待开始」）；另两条覆盖复制范围后 `push("/inventory-tasks/9007199254740994")` 与执行人只见应盘项、不见任务操作。
- `views/InventoryTaskDraftView.test.ts`（新增）：新建提交 `POST /inventory-tasks` version 0 并跳到新详情；修改按 `id` 回填名称与 `selects[2].value === "7"` 后提交 `/revision` version 3。
- `components/AppChrome.test.ts`：+1 用例，`/assets/12` 时侧栏高亮「资产台账」、`/` 时高亮「工作台」，验证 `isCurrent` 前缀判定。

## 验证

| 门禁 | 结果 |
|---|---|
| `npx vue-tsc --noEmit` | 0 错误 |
| `npx eslint . --max-warnings 0` | No issues found（`.vue` 仍不在 `sourceFiles` 覆盖内，属既有盲区） |
| `npx vitest run`（apps/web 下） | Test Files 48 passed / Tests 186 passed（UI-12 为 46 / 180） |
| `npx vite build` | CSS 31.42 kB / gzip 5.65 kB，与 UI-12 记录逐字节相同（本批未碰样式）；JS 618.03 kB / gzip 165.96 kB（此前批次未记录 JS 基线，故不声称增量） |

CDP 量测（`/tmp/fams-stub-api.mjs` 临时补 `invDraft`、`/preview`、创建、`revision/publication/copy/start/cancel/terminate/delete/assignments` 与各目录按 kind 的 fixture，非产品代码）：

| 口径 | 结果 |
|---|---|
| 页头契约 | 四条路由在 1440×900 均为 `chromeBottom=64 headTop=80 h1Top=106 gapChromeToTitle=42`；735×872 与 390×844 均为 `headTop=124 h1Top=150 gapChromeToTitle=86`，与 UI-10 起的基线一致 |
| 侧栏高亮 | 四条路由 `navCurrent=["盘点任务"]`（改前该属性无人写入，恒为空） |
| 溢出 | `docScrollW == innerW`（1440/735/390）；`overflowRight` 在 390 列表 17、详情 65，735 列表 11、详情 52，但 `overflowNotTable` 全为 0，即全部右溢元素都在 `.fams-table-scroll` 内，属既有设计 |
| 叠行 | `overlap=0`（各视口各页） |
| 主内容高度 | 1440 档 `.fams-main`：列表 462px、新建草稿 648px、详情 2791px；改前同一 stub 的整页样张 3762px（`/tmp/fams-inventory-desktop.png`，UI-04 取样）。首屏实际渲染从 3762px 一屏拉完变为列表 462px |
| 端到端（stub 真实点击） | 列表 `打开` → `/inventory-tasks/9002` 详情；`预览应盘数量` → 「预计 3 项（待入库和未发布资产已排除）」；`发布并冻结集合` → 状态转「待开始」并出现手动开始/主执行人/证据复核面板；`复制范围创建新草稿` → `push` 到新 id；`/inventory-tasks/9002/edit` 回填名称与 `分类=C-IT`；`dialog events: 0`（拆分未新增/丢失任何原生弹窗） |

样张 13 张：`/tmp/fams-ui13-{list,new,edit,detail-draft,detail-preview,detail-published,detail-running}-1440.png` 与 `/tmp/fams-ui13-{list,new,detail-running}-{735,390}.png`。

复核中修掉的问题：① 拆分后侧栏高亮必然失效（扁平路由 `router-link-active` 不命中父条目），补 `isCurrent` + `aria-current`；② stub 的详情端点无状态导致「发布」点了不生效，补 `invPublished` 状态后复测通过；③ stub 目录端点对所有 kind 返回同一份数据，导致修改页「分类」回填不上，按 kind 拆 fixture 后回填正确。

## 已知限制

- 详情页的盘点范围只以摘要文本呈现（`开始 … · 截止 …`），未把 `range_json` 的 8 个 id 反解成名称；要做只读范围摘要需新增 id→name 解析，本批按「不发明数据展示」跳过。
- 样张角色固定为 `WAREHOUSE_OPERATOR` + `ASSET_ADMIN`，执行人视角只有单测覆盖（`InventoryTaskDetailView.test.ts` 第 3 用例），缺真实渲染样张；stub 需支持角色切换。
- 证据复核、未盘与待复核、封存检查与冻结报告、执行人同步声明 4 个面板在 stub 下返回 403，样张里显示为红色提示条「身份、权限或任务已失效，已隐藏原资料。」——这是 stub 缺端点，不是产品缺陷，但这几块的数据排版未经视觉复核。
- 草稿表单面板无面板标题（标题由外壳页头承担），量测里该块记为 `panels=["?…"]`。
- 验证口径仍是「单测 + 构建 + 静态样张」，未接真实后端，不替代远端 CI 与正式 QA。

## 下一批

同一「列表工作区 → 独立详情页」分层可复用到仍把「表单 + 列表 + 单对象操作」压在一屏的视图：`入库管理`（InboundView）、`领用申请与分配`、`借用、延期与退还`、`调拨与人员交接`、`报修与维修`。建议按域逐批做，每批沿用本批的四道门禁 + 三口径量测；`AppChrome.isCurrent` 已就位，拆页后侧栏高亮无需再改基础设施。
