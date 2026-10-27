# UI-02 统一页头与内容起点（布局重排）

承接 [UI-01](UI-01-fams-foundation-batch1.md)。用户反馈：顶栏与页面标题之间空白过大，且不同菜单占比不一致（工作台占满、资产台账留出巨大空白）。实施前先用无头 Chrome + CDP 量测，实施后按同一口径复测。

## 量测到的成因

1. 标题上方有 5 段互不协调的留白：`.fams-main` 上内边距 32、面包屑 20、面包屑与标题间距 16、旧标题自带 `margin` 8，桌面合计 76px；窄屏再叠加一段浮动的导航条 57px，合计 125px。
2. 更关键的是**来源不一致**：已迁移页面走外壳面包屑，未迁移页面各自在模板里写 `<h1>` 与说明段，标题落点逐页不同，观感即"有的页面空、有的页面满"。
3. 窄屏另有结构性缺陷：`.fams-layout` 为单列网格且 `align-content` 取默认 `stretch`，页面内容不足一屏时剩余高度被摊进两条轨道，侧栏轨道实测从 48px 涨到 189px，主区被整体下移 140px——这是"空白忽大忽小"的直接原因。

## 方案（用户选定"方案一"）

页头由外壳按路由 meta 单点渲染，视图不再自带标题；未迁移页面的旧标题在兼容层隐藏。

- `router.ts`：`RouteMeta` 增补 `uiGroup / uiTitle / uiDesc`。`uiGroup` 同时用于面包屑，取值限定为侧栏四个分组（总览、资产、业务流转、异常与核查）。`/login`、`/password/change` 故意不带这组 meta。
- `AppChrome.vue`：`pageHead` 计算属性驱动 `<header class="fams-page-head">`，含面包屑、`h1`、说明段；视图的主操作通过具名插槽 `#actions` 落到同一行右侧（`AssetLedgerView` 的"单个建卡"已改造）。
- `layout.css`：`.fams-main` 上内边距降为 `--fams-space-16`；页头条 `align-items: flex-end` 使标题基线与操作按钮对齐；`.fams-layout` 补 `align-content: start`，轨道不再吸收剩余高度。
- ≤900px：侧栏转为定高 48px 横滚标签条（`--fams-tabbar-height`），当前项用内阴影下划线标记，分组标题与占位说明隐藏。轨道不再拉伸后 sticky 已无可粘行程，故标签条随页面滚动，顶栏保持 sticky。
- `compat.css`：隐藏未迁移视图自带的 `h1` 与其后的静态说明段，选择器限定 `:nth-of-type(1)` 并用 `:not([role])` 保护动态状态行（`BusinessResultNoticeView` 的"未读 N 条"已改为 `role="status"` 并保留）。

## 契约与防回归

- `router.test.ts` 遍历路由表：除登录/改密外，每个 `requiresAuth` 路由必须有 `uiGroup`（且属于四个分组）与 `uiTitle`，防止新页面重新引入页头不一致。
- `AppChrome.test.ts` 断言 `main > .fams-page-head` 只出现一个 `h1`、文本来自 meta、无 meta 时不渲染页头、`#actions` 插槽内容落在页头条内。
- 迁移每批视图时，删除该视图自带的标题块；全部迁完即可删除 `compat.css` 的隐藏段。

## 验证

- 复测（本地 stub API + dev server，10 条路由 × 3 视口，绝对文档坐标）：
  - 1440×900：每条路由 `chromeBottom 64 / headTop 80 / h1Top 106`，顶栏到标题恒定 42px。
  - 735×872 与 390×844：每条路由 `chromeBottom 113 / headTop 124 / h1Top 150`，恒定 37px。
  - 修复前同口径窄屏 `headTop` 在 124～290 之间跳动（差值最大 166px）。
  - 抽样路由可见 `h1` 数量恒为 1，未迁移页面不再出现双标题。
- `npx vue-tsc --noEmit`、`npx eslint . --max-warnings 0`：无告警。
- `npx vitest run`：45 个测试文件 / 172 条用例通过（较 UI-01 记录新增 3 条布局契约用例）。
- `npx vite build`：成功，CSS 45.25 kB（gzip 7.27 kB）。
- 未启动真实后端与 compose 链路，量测基于 stub 数据，不构成端到端验收。

## 已知限制

- 未迁移页面的正文仍靠 `compat.css` 兜底，页头统一后其内部区块间距尚未收敛，留到各自批次处理。
- 窄屏标签条随页面滚动：切换模块需回到顶部，属本批刻意取舍。
- 量测脚本与样张在 `/tmp`（`fams-layout-probe4.mjs`、`fams-layout-shots.mjs`），未纳入仓库。
