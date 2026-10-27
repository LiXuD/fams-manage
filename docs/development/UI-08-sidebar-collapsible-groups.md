# UI-08 侧栏一级分组改为可折叠

基线分支 `codex/d6-20-factory`（d10f246），实施分支 `codex/d6-20-factory-ui`。承接 [UI-07](UI-07-exception-views.md) 之后的用户复核反馈：侧栏一级分组（总览/资产/业务流转/异常与核查）与二级条目看起来同为可点菜单，且一级分组没有对应页面、点击无任何反馈。经确认选择「可折叠分组」口径。本批只动外壳与导航组件族，未触碰任何业务视图。

## 范围

| 文件 | 变化 |
|---|---|
| `components/AppChrome.vue` | 侧栏从 26 行手写 `router-link` 改为数据驱动：新增 `NAV_GROUPS`（分组 + 条目 + 权限/角色口径，与 `router.ts` 的 `meta.uiGroup` 同源）、`visible()` 过滤、`navGroups` 计算属性（可见条目为空的分组整组不渲染）、`collapsed` 集合 + `localStorage` 持久化（键 `fams.navCollapsed`）、`toggleGroup()`、`watch(activeGroup)` 自动展开当前分组。模板改为「分组标题按钮（含 `aria-expanded` / `aria-controls` / 旋转箭头）+ 条目容器」两层结构。 |
| `styles/components/navigation.css` | 新增 `.fams-navigation-toggle`、`.fams-nav-chevron`、`.fams-navigation-items` / `-items-inner`；侧栏分组间距 4 → 12px；折叠用 `grid-template-rows: 1fr → 0fr` 过渡。 |
| `styles/layout.css` | 900px 标签条模式改为 `group: display: contents` + 隐藏标题 + 条目内层 `display: contents`，条目拉平进同一条横向流（行为与迁移前一致）。 |
| `styles/tokens.css` | 新增动效令牌 `--fams-motion-collapse: 200ms`（原只有反馈 120ms 与加载 800ms 两档）。 |

外壳脚本改动属 UI-01/UI-02 已单列的允许例外（`AppChrome.vue` 是基础设施文件），不涉及任何业务逻辑：权限与角色判定口径逐条照搬原模板的 `v-if` 表达式，条目文案与顺序完全保持。

## 交互契约

- 一级分组是可折叠按钮：箭头展开时指向下（`rotate(45deg)`）、收起时指向右，条目容器高度与透明度按 `--fams-motion-collapse` 过渡；条目按钮本身沿用按钮族既有的 background/border/color 120ms 过渡。
- 当前页所在分组不可收起（标题按钮 `disabled`，并用专门的 `:disabled` 规则保持可读弱化态而非灰底禁用样式）；整页加载与客户端跳转进入被收起的分组时都会自动展开并写回存储（`watch(activeGroup, …, { immediate: true })`）。
- 折叠状态跨刷新保留；`localStorage` 不可用（私密浏览）时降级为会话内有效。
- 层级区分：分组标题 12px/20px/600、`--fams-text-muted`、高 32px、左内边距 8px；二级条目 14px/22px/500、`--fams-text-secondary`、高 40px、缩进到 20px。

## 验证

- `npx vue-tsc --noEmit`、`npx eslint . --max-warnings 0`：无告警。
- `npx vitest run`：45 个测试文件 / 172 条用例全部通过；`AppChrome.test.ts` 一行未改（权限过滤、页头、`#actions` 三组断言仍成立）。
- `npx vite build`：成功，CSS 29.04 kB（gzip 5.30 kB），较 UI-07 收尾后的 27.71 kB（gzip 5.08 kB）增 1.33 kB，来自新增的分组标题 / 箭头 / 条目容器规则。
- CDP 交互量测（1440×900，`/assets`）：初始 4 个分组全展开、导航高 824px；点击「业务流转」收起后轨道 `216px → 0px`、条目容器高 `216 → 0`、导航高 `824 → 608`、`aria-expanded=false`、箭头 `rotate(0)`；点击当前分组「资产」标题（`disabled`）状态不变；刷新后收起状态保留（`localStorage` 为 `["业务流转"]`）；再次点击恢复 `216px` 与 824px。
- 进入被收起分组的自动展开：在 `/assets` 收起「业务流转」后，客户端跳转与整页加载 `/repairs` 两种路径均得到「业务流转=展开且禁用」，存储同步为 `[]`（`immediate` 修正前的行为是 `false(禁用)`，即当前分组既展不开也点不动）。
- 层级量测：分组标题 `12px/20px w600 rgb(51,68,93)` 高 32px 左 8px；二级条目 `14px/22px w500 rgb(83,101,125)` 高 40px 左 20px（字号、字重、色阶、高度、缩进五项全部区分）。
- 390×844 标签条：分组标题可见数 0、条目可见数 15、首条目宽 68px、横向可滚，与改造前一致。
- 样张：`fams-nav-expanded.png`（全展开侧栏）、`fams-nav-collapsed.png`（收起业务流转与异常与核查）、`fams-nav-tabbar.png`（390 标签条）逐张目视复核。
- 复核中修掉的四处问题：① 折叠后高度不收缩——先是 flex 子项自动最小尺寸，再是「多条目落在隐式行上、`0fr` 只压得住第一行」，最终改为外层单轨道 + 内层单一容器；② 分组标题字号/高度/颜色被 `.fams-navigation[data-mode="sidebar"] .fams-button`（特异 0,2,1）顶掉，一级二级仍然同款——标题规则改为同特异并写在条目规则之后；③ `aria-expanded` 绑 `String(...)` 触发 `TS2322`，改绑布尔；④ `watch(activeGroup)` 缺 `immediate`，整页加载到被收起的分组时当前分组展不开又禁用——补 `immediate` 并写回存储。

## 已知限制

- 折叠只影响侧栏；900px 以下标签条没有折叠概念，条目全部平铺。
- 收起状态的持久化按分组名存字符串数组，分组改名会丢失既有状态（可接受，无迁移成本）。
- 当前分组标题按钮以 `disabled` 表达「不可收起」，读屏会播报「已禁用」；未额外加 `title` 提示原因，避免新增用户可见文案。
- 减弱动效偏好下过渡被 `base.css` 的全局规则压到 0.01ms，折叠表现为瞬时切换，未单独验证视觉。
- 根 `eslint.config.mjs` 的 `sourceFiles` 只覆盖 `*.{ts,tsx}`，`.vue` 模板不经 lint（既有缺口）。
- 验证口径仍为「单测 + 构建 + 静态样张」，未启动真实后端。

## 下一批

> 远端 D6-20C1 同步与原机部件面板迁移见 [UI-09](UI-09-remote-sync-and-parts-panel.md)。

无既定批次。可选后续（需另行确认）：把 `.vue` 纳入 eslint 覆盖；为分组折叠补一条 `AppChrome.test.ts` 用例（当前折叠行为只由样张与量测覆盖，单测未断言）。
