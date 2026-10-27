# UI-05 导入与期初视图迁移（第五批）

基线分支 `codex/d6-20-factory`（d10f246），实施分支 `codex/d6-20-factory-ui`。承接 [UI-04 盘点视图与面板群迁移](UI-04-inventory-views.md)，本批迁完「资产批量导入 / 期初导入核验 / 期初导入窗口 / 入库管理」四条路由的 4 个视图与 2 个组件，并回收了 compat 里整块 `asset-*` 旧类名。

## 范围

| 文件 | 迁移要点 |
|---|---|
| `views/AssetInitializationView.vue`（78 → 97 行） | 本批样板。`#actions` 放「刷新状态」；错误与成功提示统一为 `fams-notice` + `<strong>`；有窗口时渲染 `fams-facts`（窗口编号 / 仓库操作员 / 截止时间）与「关闭窗口」，无窗口时渲染 `fams-field-grid` 三字段 + `fams-form-actions` 主按钮 |
| `views/AssetImportView.vue`（273 → 296 行） | 上传 / 字段映射 / 预检结果三块编号面板；`input[type=file]` 挂 `.fams-file`（该类此前无使用者）；14 个映射下拉进 `fams-field-grid`；预检行表补身份列、`fams-status` 状态列与空态行；分页统一为 `fams-pagination`；撤销原因与申请 / 执行按钮收进 `fams-field-row` |
| `views/AssetImportReviewView.vue`（148 → 187 行） | 待核验导入、核验批次详情、待核验整批撤销三张面板；三张表统一身份 / 数值 / 操作列口径，`<pre>` 原始 JSON 单元格保持不动；批准与驳回按钮包进 `fams-inline` |
| `views/InboundView.vue`（187 → 206 行） | 自带页头与面包屑删除（旧页头无主按钮，故不占 `#actions`）；建立入库单表单进 `fams-field-grid`；入库单列表与详情两张表的 `status` / `executionStatus` 改为 `fams-status`；草稿、提交、取消、批准、驳回、认领等动作按钮的 `v-if` 条件逐字保留 |
| `components/InboundReassignment.vue`（65 → 77 行） | `fieldset` 转 `fams-card fams-stack`，说明句转 `fams-panel-subtitle`，接任库管与两段 textarea 进 `fams-field-grid`，确认按钮进 `fams-panel-actions` |
| `components/AttachmentPanel.vue`（148 → 150 行，删除 `<style scoped>`） | 卡头 + 副标题 + 链接式「刷新附件」；上传表单 `fams-stack`，文件选择挂 `.fams-file`；附件表身份列（文件名 + 版本 / 纠错原因）、数值列（大小）、操作列（下载 / 追加纠错版本）；`scanStatus` 转 `fams-status` 并带 `data-tone` |

6 个文件的 `<script setup>` 段落经 sha 比对逐字节未变；绑定集合差异只包含新增的 class / role / aria 属性；按钮文案序列与迁移前完全一致。

## compat 回收

`styles/compat.css` 408 → 223 行，构建 CSS 从 42.02 kB（gzip 6.77 kB）降到 35.71 kB（gzip 6.08 kB），是至今单批回收最多的一次。删除的段落：

- 整块 `asset-*`：`.asset-filters`、`.asset-form`、`.asset-actions`、`.asset-heading`、`.asset-panel`、`.asset-table*`、`.asset-facts`、`.asset-pagination`、`.asset-tag-preview`、`.asset-notice`、`.attachment-notice`，以及隐藏旧页头选择器里的 `.fams-main .asset-heading > div:first-child`。
- 早已无使用者的外壳与权限页段落：`.app-chrome`、`.topbar`、`.brand`、`.brand-mark`、`.account-label`、`.workspace*`、`.sidebar`、`.nav-item*`、`.nav-note`、`.workspace-content`、`.permission-summary*`、`.permission-list`、`.form-links`、`ul[class]` 之外的表单辅助段。
- 本批后归零的按钮类：`.text-button`、`.secondary-button`（`PasswordChangeView` 只用 `.primary-button`）。

仍留的段落及其使用者：`auth-*` / `.breadcrumb` / `.form-error` / `.primary-button`（`PasswordChangeView`）、`.fams-main .panel`（`TransferTargetChangeView`）、`.factory-panel`（`RepairFactoryPanel`）、`.technical-evidence`（`RepairTechnicalEvidence`），以及未迁移视图依赖的原生控件与表格兜底。

## 测试口径变化

无。本批 4 个视图与 2 个组件的测试文件一行未改，172 条用例全部原样通过——断言仍按按钮文案、`input` / `textarea` 顺序与 `aria-label` 命中，说明迁移只换外壳类名、没有动可访问名与控件顺序。

## 验证

- `npx vue-tsc --noEmit`、`npx eslint . --max-warnings 0`：无告警。
- `npx vitest run`：45 个测试文件 / 172 条用例全部通过。
- `npx vite build`：成功，CSS 35.71 kB（gzip 6.08 kB）。
- 布局量测（口径同 UI-03/UI-04）：`/asset-imports`、`/asset-import-reviews`、`/asset-initialization`、`/inbound` 四条路由在 1440×900 下 `chromeBottom` 64、`h1Top` 106、`gapChromeToTitle` 42px；735×872 与 390×844 切到定高标签条模式，`gapChromeToTitle` 37px；页级 `h1` 恒为 1，旧类名命中数恒为 0。
- 数据样张：临时 stub API（`/tmp/fams-stub-api.mjs`，非产品代码）补上导入批次、映射列、错误 / 正确行、待核验与待撤销、期初窗口、入库单、附件与转派清单桩数据后，在 1440×900 与 390×844 取 8 张整页截图（预检通过态、已发布态、核验页、期初窗口、入库页、加载转派清单后的入库页、核验与入库移动态），逐张目视复核面板间距、表格空态与状态胶囊。
- 样张复核中修掉的两处子代理产物：核验页两个孤立的「决定原因」字段被 `fams-field-grid` 收拢（否则会拉满面板宽度）；入库页与附件表里裸枚举文本改挂 `fams-status`。

## 已知限制

- `.auth-form label { flex: 1 1 200px }` 是从已删除的横向 `asset-filters` 布局继承下来的遗留声明，很可能把 `PasswordChangeView` 的标签高度撑大。本批未动它，避免顺带改一个未迁移页面；留到该页迁移时一并删除。
- 待入库资产的多选框保留原有的 `size="8"`，在只有少量候选资产时会呈现一个较高的空框。这是产品行为，不属于本批视觉迁移范围，未改。
- 「整批撤销」那一行把状态说明 `<p>` 与两个按钮放在同一个 `fams-field-row` 内，说明文字与按钮首行基线对齐；真实数据下若撤销原因较长会换行，需要目视复核。
- 根 `eslint.config.mjs` 的 `sourceFiles` 只覆盖 `*.{ts,tsx}`，`.vue` 文件被判定为 "File ignored"，因此 `eslint .` 通过并不代表组件模板经过 lint。这是既有缺口，本批未扩大范围去修。
- 期初窗口「无窗口」分支、导入页「上传中」与「撤销执行中」等瞬时状态只由单测覆盖，样张未取到。
- 未启动真实后端与 compose 链路，不构成端到端验收。

## 下一批

仍依赖 compat 兜底的分组剩两类：报修与返厂（`RepairView` 及 `Repair*Panel`、`Repair*Evidence`、`RepairInventoryPhoto`，回收 `.factory-panel`、`.technical-evidence`），异常与代确认（`ProxyConfirmationView`、`ItemTargetChangeView`、`TransferTargetChangeView`、`BusinessResultNoticeView`、`BusinessResultReviewView`、`PasswordChangeView`，回收 `.panel`、`auth-*`、`.breadcrumb`、`.form-error`、`.primary-button`）。`PasswordChangeView` 迁完后 compat 只剩原生控件兜底，可整体删除该文件。

> 已按计划完成，见 [UI-06 报修与返厂视图迁移](UI-06-repair-views.md)。
