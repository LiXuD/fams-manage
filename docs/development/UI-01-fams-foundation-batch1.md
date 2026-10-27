# UI-01 设计底座与应用外壳（第一批）

基线分支 `codex/d6-20-factory`（d10f246），实施分支 `codex/d6-20-factory-ui`。视觉语言以 [D0.4 设计稿](../superpowers/designs/2026-09-06-fams-ui/README.md) 为参考，但实现按工程化方式落在 `apps/web/src/styles/`，不搬运设计稿 CSS：设计稿只提供颜色、尺寸与状态口径，组件层由本仓库自行组织。技术路线沿用 DEVELOPMENT.md 约束的原生 DOM + CSS，不引入组件库。

## 分层

| 文件 | 职责 |
|---|---|
| `styles/tokens.css` | 唯一颜色/尺寸/层级来源，含基线 56 项令牌与应用侧补充 |
| `styles/base.css` | 重置、`:focus-visible` 焦点环、8 档排版原语、`u-*` 工具类、减弱动效 |
| `styles/components/*.css` | 10 个组件族：button、field、selection、status、notice、table、panel、navigation、progress、dialog |
| `styles/layout.css` | 外壳（topbar/sidebar/main/auth）与 900px、560px 断点 |
| `styles/compat.css` | 迁移期兼容层，把旧类名与原生标签重指向令牌；随批次删除 |
| `styles/index.css` | 唯一入口，按 tokens → base → components → layout → compat 顺序引入 |

`main.ts` 改为只引入 `./styles/index.css`，并移除从未被使用的 Element Plus 注册（依赖仍留在 `package.json`，删除依赖留待后续批次一并处理）。原 `styles.css` 已删除。

## 令牌差异

基线 56 项令牌全部按原名、原值落地，无删改。应用侧新增 19 项，均为基线未定义但实现必需的常量：

- 排版与动效：`--fams-font-stack`、`--fams-font-mono`、`--fams-tracking-tight`、`--fams-motion-feedback`、`--fams-motion-ease`
- 层级与阴影：`--fams-shadow-surface`、`--fams-shadow-dialog`、`--fams-backdrop`、`--fams-z-dropdown`、`--fams-z-dialog`、`--fams-z-toast`
- 布局：`--fams-topbar-height`、`--fams-sidebar-width`、`--fams-content-padding`、`--fams-table-min-width`、`--fams-table-column-min-width`
- 尺度补档：`--fams-radius-16`、`--fams-bg-muted`、`--fams-text-muted`

## 组件族缺口

D0.4 组件清单只出现 `fams-asset/button/control/dialog/dialog-actions/field/field-hint/inline/metric/nav-icon/nav-label/navigation/notice/progress/readonly/selection/spinner/status/steps/table/table-scroll/timeline`。以下类名由实现补齐，取值全部来自同一套令牌：

| 补齐项 | 原因 |
|---|---|
| `.fams-pagination` + `-summary/-page/-actions` | 基线只有表格，未定义分页控件；与按钮族共用 40px 高度 |
| `.fams-table-empty` | 基线未定义空态；采用 32px 上下留白与次级文字 |
| `.fams-toast-region` / `.fams-toast` | 基线只有 dialog，未定义轻提示；层叠低于对话框 |
| `.fams-panel` + `-head/-title/-subtitle/-actions`、`.fams-card`、`.fams-facts`、`.fams-metric-grid`、`.fams-form-actions`、`.fams-field-grid`、`.fams-field-row`、`.fams-tag-preview` | 页面级容器、事实列表与表单栅格在基线中只有零散示例，无独立类名 |
| `.fams-navigation-group` / `.fams-navigation-note` | 侧栏需要分组层级与占位说明；≤900px 转为横滚标签栏时隐藏 |
| `.fams-page-head` / `.fams-breadcrumb` / 外壳类（`fams-shell/topbar/brand/account/layout/sidebar/main/auth-*`） | 基线以整页样张表达，未沉淀外壳类名 |
| `data-kind="link"`、`data-size="sm"`、`data-state="error"` 等属性轴 | 基线给出 kind/size/state/tone/mode/column-kind 轴，实现按同一命名补齐缺失取值 |

## 迁移进度

本批完成：`AppChrome.vue`（外壳与分组导航，并修正工作台长期高亮的既有缺陷，改用 `aria-current`）、`LoginView.vue`、`HomeView.vue`、`AssetLedgerView.vue`。`views/` 下其余 36 个文件未改脚本与结构，其观感由 `compat.css` 兜底：旧类名（`primary-button`、`asset-panel`、`asset-table` 等）与未挂 fams 类的原生 `button/select/input/textarea/table` 都被重指向令牌；兜底选择器一律用 `:not(.fams-*)` 排除已迁移组件。每批视图迁移完成后删除对应段落，全部迁完即可整体移除该文件。已回收的段落见 [UI-03 业务流转视图迁移](UI-03-business-flow-views.md) 的「compat 回收」一节。

## 验证

- `npx vue-tsc --noEmit`、`npx eslint . --max-warnings 0`：无告警。
- `npx vitest run`：44 个测试文件 / 169 条用例全部通过；视图测试绑定的原生标签与断言未改动。
- `npx vite build`：成功，CSS 44.68 kB（gzip 7.16 kB）。
- 静态样张：以本地 stub API + dev server 在 1440×900 与 390×844 截图，覆盖登录、工作台、资产台账列表/详情/建卡表单，以及一个未迁移页面（领用与借用申请）用于确认兼容层生效。未启动真实后端与 compose 链路，因此不构成端到端验收。

## 已知限制

- 样张中的引用型字段（分类、组织、仓库）显示为 ID，是 stub 数据形状限制，非产品缺陷。
- `element-plus` 仍声明在 `apps/web/package.json` 中，本批只移除注册，未动依赖清单。
- 深色模式、移动端原生手势与打印标签的真实纸张尺寸未在本批处理；打印仅保留 `@media print` 的基础规则。
- 本批的页头与空白布局问题由 [UI-02 统一页头与内容起点](UI-02-unified-page-head.md) 接续处理，该文档的验证数字为当前口径。
