# UI-03 业务流转视图迁移（第三批）

基线分支 `codex/d6-20-factory`（d10f246），实施分支 `codex/d6-20-factory-ui`。本批承接 [UI-02 统一页头与内容起点](UI-02-unified-page-head.md) 定下的外壳页头契约，把「业务流转」分组的三个视图及其受控转派子组件迁到 `fams-*` 组件层，脚本行为与接口调用逐行保持不变。

## 范围

| 文件 | 迁移要点 |
|---|---|
| `views/RequisitionView.vue` | 主操作 `新建申请` 移入 `#actions` 插槽；列表、需求表单、详情、库存四块统一为 `fams-panel fams-stack`，明细用 `fams-field-grid` + `fieldset.fams-card`，交接事件用 `fams-timeline`，数量用 `fams-facts` |
| `views/LoanView.vue` | 借用记录、延期审批与历史、退还与退库三块面板化；退还列表由卡片流改为 `fams-table`，行内动作统一为 `查看 / 审批 / 验收`；逾期行用 `fams-status data-tone="warning"` |
| `views/TransferView.vue` | 拆为新建调拨、申请与执行、本人待接收、人员责任交接四块；明细动作按钮改用 `fams-button data-kind="link"`，接收前借用延期收进 `details.fams-card` |
| `components/RequisitionReassignment.vue`、`components/AssetReturnReassignment.vue`、`components/TransferReassignment.vue` | 三个转派组件此前逐字重复，本批按同一模板收敛：`fams-card fams-stack` + `fams-panel-head` + `fams-notice` + `fams-field-grid` + `fams-selection` + `fams-panel-actions`，文案与校验顺序未改 |
| `router.ts` | 为 `/loans`、`/transfers` 补 `uiDesc`，使三路由页头与 UI-02 契约一致 |

## 组件层新增

`styles/components/panel.css` 补两条规则，来源是本批密集面板暴露的缺口，不是逐页手调：

- `.fams-stack`：纵向 flex + `gap: var(--fams-space-16)`，并把子项 `margin-block` 归零。`.fams-panel`/`.fams-card` 本身不含内部间距，`base.css` 又清零了 `p, dl` 的外边距，多区块面板此前只能靠页面各自加 margin。
- `.fams-stack > .fams-button { align-self: flex-start }`：纵向 flex 默认拉伸子项，直接放在面板里的按钮会被拉成整行（样张中的 `添加调拨明细`）。
- `.fams-card > summary, .fams-panel > summary { cursor: pointer }`：折叠区标题的可点提示，标题字号仍沿用 `.fams-panel-title`。

字段模式按场景分两类，避免为 `v-for` 明细造唯一 id：

- 静态表单（资产台账建卡）用显式 `label for` + `input id`。
- 密集表单与循环明细用 `label.fams-field` 包裹 `span.fams-field-label` + 控件，走隐式关联。

## compat 回收

`styles/compat.css` 删除本批已无使用者的段落：`.page-heading` 系列、`.req-actions`/`.req-demand-form`、兜底选择器列表里的 `.fams-main .req-demand`，以及页头隐藏块中的 `.fams-main .page-heading > div:first-child`（全仓已无匹配类名）。文件当前 418 行，构建产物 CSS 从 UI-02 记录的 45.25 kB 降到 44.29 kB（gzip 7.12 kB）。其余段落（盘点、报修、导入、异常核查等未迁视图的旧类名与原生标签兜底）继续保留，按批次回收。

## 复核中修正的两处观感缺陷

- `LoanView` 首个面板的副标题与路由 `uiDesc` 逐字重复，页头已承担该说明，删除面板级重复文案，保持「外壳拥有页头描述」的单一来源。
- 上述 `.fams-stack` 按钮拉伸问题。

## 验证

- `npx vue-tsc --noEmit`、`npx eslint . --max-warnings 0`：均无告警。
- `npx vitest run`：45 个测试文件 / 172 条用例全部通过。视图测试仍绑定原生标签与按钮文案，本批只把 `TransferView.test.ts` 的接收动作选择器从明细标题改为 `核对并接收`。
- `npx vite build`：成功，CSS 44.29 kB（gzip 7.12 kB）。
- 量测（headless Chrome + CDP，绝对文档坐标，`gapChromeToTitle` = 顶栏/标签条底边到页头 `h1` 顶边）：

| 视口 | 模式 | chromeBottom | headTop | h1Top | gapChromeToTitle | visibleH1 |
|---|---|---|---|---|---|---|
| 1440×900 | sidebar | 64 | 80 | 106 | 42 | 1 |
| 735×872 | tabbar | 113 | 124 | 150 | 37 | 1 |
| 390×844 | tabbar | 113 | 124 | 150 | 37 | 1 |

  `/assets`、`/requisitions`、`/loans`、`/transfers` 四路由在三种视口下数值完全一致，页级 `h1` 恒为 1 个。
- 静态样张：1440×900 与 390×844 下三路由首屏截图，确认面板、表格、分页、折叠区与转派卡片渲染，以及窄屏定高标签条随页面滚动。

## 已知限制

- 样张来自本地 dev server + stub API，未登录到真实数据，面板主体呈现为权限提示与空态；真实数据下的表格密度、长文本换行与转派清单条数仍需后续复核。
- 三个转派组件仍各自持有一份结构相同的模板，只做到样式口径统一；是否再抽公共组件留待后续批次判断，本批不引入新抽象。
- 未启动真实后端与 compose 链路，不构成端到端验收；`element-plus` 依赖仍留在 `package.json`。

## 下一批

`views/` 下仍依赖 compat 兜底的主要分组：盘点（`InventoryTaskView` 及其面板）、报修与返厂（`RepairView` 等）、导入与期初（`AssetImportView`、`AssetImportReviewView`、`AssetInitializationView`、`InboundView`）、异常与代确认（`ProxyConfirmationView`、`ItemTargetChangeView`、`TransferTargetChangeView`、`BusinessResultNoticeView` 等）。建议按盘点单组推进，先确认 `fams-stack` 与表格族在长清单下的表现。该批已完成，见 [UI-04 盘点视图与面板群迁移](UI-04-inventory-views.md)。
