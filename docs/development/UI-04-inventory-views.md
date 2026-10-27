# UI-04 盘点视图与面板群迁移（第四批）

基线分支 `codex/d6-20-factory`（d10f246），实施分支 `codex/d6-20-factory-ui`。承接 [UI-03 业务流转视图迁移](UI-03-business-flow-views.md)，本批迁完「盘点任务」路由下的 1 个视图与 11 个面板组件，是至今最大的一批，也是第一次用并行子代理批量迁移模板。

## 范围

| 文件 | 迁移要点 |
|---|---|
| `views/InventoryTaskView.vue` | 草稿表单、任务列表、任务详情三块面板化；任务列表由「一排整行按钮」改为 `fams-table`，行内动作统一为 `打开`；应盘项表格补 `fams-selection` 选择列与空态行；详情内的管理动作收进 `fieldset.fams-card.fams-stack` |
| `views/InventoryReviewPanel.vue` | 复核板表、采信表单、原始证据卡与照片证据分层；分页统一为 `fams-pagination` |
| `views/InventorySelectionPanel.vue`、`views/InventoryEvidenceDecisionPanel.vue`、`views/InventoryDifferencePanel.vue` | 三张处理卡的 `fieldset` 转 `fams-card fams-stack`，字段进 `fams-field-grid`，历史与结论转 `fams-timeline`，差异表加身份列与空态行 |
| `views/InventoryPhotoEvidence.vue` | 旧 `photo-evidence` 类转 `fams-card fams-stack`，引用列表转 `fams-timeline`，预览 `figure` 收进卡内 |
| `views/InventoryReadinessPanel.vue`、`views/InventorySealPanel.vue`、`views/InventoryLatePanel.vue`、`views/InventoryLateDamagePanel.vue`、`views/InventorySynchronizationPanel.vue`、`views/InventoryFindingPanel.vue` | 生命周期六张面板统一 head/notice/table/timeline 口径；`finding-panel` 旧类名退出使用 |

12 个文件的 `<script setup>` 段落经 sha 比对逐字节未变，`<style scoped>` 全部删除。

## 组件层新增

`styles/components/panel.css` 本批补两条：

- `.fams-stack > fieldset:not(.fams-card)`：原生 `fieldset` 只用于整组禁用，在面板内不再重复画边框与内边距；带 `.fams-card` 的分组卡保持卡片外观。
- `.fams-card .fams-card { padding: var(--fams-space-16) }`：盘点面板出现卡中卡（复核卡 → 原始证据卡 → 照片卡），逐层 24px 内边距会把正文挤成窄列，嵌套一层收紧到 16px。

## compat 回收

`styles/compat.css` 删除本批后全仓无使用者的段落：`.finding-panel`（两处选择器列表项）、`.photo-evidence`、`.table-scroll` 整段。文件 418 → 408 行，构建 CSS 从 44.29 kB 降到 42.02 kB（gzip 6.77 kB）。`.panel`、`.factory-panel`、`.technical-evidence`、`.form-error`、`.asset-notice` 仍分别被 `TransferTargetChangeView`、`InboundReassignment`、`RepairFactoryPanel`、`RepairTechnicalEvidence` 与导入类视图使用，留到对应批次。

## 测试口径变化

`views/InventoryTaskView.test.ts` 只改一处选择器：任务列表改成表格后，进入详情的动作按钮从 `固定范围 · 草稿` 变为 `打开`。任务名与状态仍由表格的身份列和 `fams-status` 呈现，`待开始`、`预计 2 项` 等断言未动。其余 11 个面板测试的选择器（按钮文案、`input`/`textarea` 顺序、`aria-label`、`role="group"`）全部保持原样通过。

## 验证

- `npx vue-tsc --noEmit`、`npx eslint . --max-warnings 0`：无告警。
- `npx vitest run`：45 个测试文件 / 172 条用例全部通过。
- `npx vite build`：成功，CSS 42.02 kB（gzip 6.77 kB）。
- 布局量测（口径同 UI-03）：`/inventory-tasks` 与 `/assets`、`/requisitions`、`/loans`、`/transfers` 一致，1440 下 `gapChromeToTitle` 42px，735 与 390 下 37px，页级 `h1` 恒为 1。
- 数据样张：本地视觉样张用的临时 stub API（`/tmp/fams-stub-api.mjs`，非产品代码）补上盘点任务、应盘项、目录、执行人与复核板桩数据后，在 1440×900 与 390×844 打开任务详情整页截图，确认草稿表单、任务表、任务操作卡、复核板表、应盘项表与分页的真实排版。

## 已知限制

- 样张里就绪度、封存、同步声明、任务外线索四张面板的接口未在 stub 中提供，因此它们呈现的是「加载失败 + 提示条」形态；这四张面板的数据排版只有单测与代码口径保证，未经真实数据目视复核。
- 复核板表格四列信息密度高，长资产名会显著拉高行；本批未加列宽或截断策略，等真实数据样张复核后再决定是否补 `.u-truncate`。
- 卡中卡收紧只解决内边距叠加，嵌套层级本身（视图面板 → 复核卡 → 证据卡 → 照片卡）仍偏深，是否扁平化留到真实数据复核后判断。
- 未启动真实后端与 compose 链路，不构成端到端验收。

## 下一批

仍依赖 compat 兜底的主要分组：报修与返厂（`RepairView` 及 `Repair*Panel`、`Repair*Evidence`、`RepairInventoryPhoto`）、导入与期初（`AssetImportView`、`AssetImportReviewView`、`AssetInitializationView`、`InboundView`）、异常与代确认（`ProxyConfirmationView`、`ItemTargetChangeView`、`TransferTargetChangeView`、`BusinessResultNoticeView`、`BusinessResultReviewView`）。建议下一批做导入与期初，收尾时即可回收 `.asset-notice` 与导入类旧类名。

> 已按计划完成，见 [UI-05 导入与期初视图迁移](UI-05-import-views.md)。
