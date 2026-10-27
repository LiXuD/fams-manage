# UI-06 报修与返厂视图迁移（第六批）

基线分支 `codex/d6-20-factory`（d10f246），实施分支 `codex/d6-20-factory-ui`。承接 [UI-05 导入与期初视图迁移](UI-05-import-views.md)，本批迁完「报修与维修」「我协助的报修」两条路由及其 8 个面板组件，回收 compat 里的 `.factory-panel` 与 `.technical-evidence` 两段。

## 范围

| 文件 | 迁移要点 |
|---|---|
| `views/RepairView.vue`（188 → 288 行） | 本批样板。`#actions` 放「刷新并核对当前结果」；错误与成功提示转 `fams-notice` + `<strong>`；提交报修 / 报修进度 / 当前报修详情三块 `fams-panel fams-stack`；两处裸 `<ul>`（报修进度行、故障照片行）转 `fams-timeline`；两组分页转 `fams-pagination` + `fams-pagination-page`；决定、原地受理、照片上传三块 `fieldset` 转 `fams-card fams-stack`；照片预览 `<figure>` 挂 `fams-card` |
| `views/RepairPhysicalPanel.vue`（54 → 107 行） | 卡头 + 副标题 + 「重试」进 `fams-panel-actions`；实物去向说明与收回 / 入库记录合并为 `fams-timeline`；地点目录分页转 `fams-pagination`；确认块转 `div.fams-card.fams-stack[role=group]` |
| `views/RepairFactoryPanel.vue`（231 → 360 行） | 本批最大面板。`section.factory-panel` → `fams-card fams-stack`；范围与轮次、新建轮次、凭证追加、报价与费用、实物寄出 / 收回五块 `fieldset` 全部转 `fams-card fams-stack`；4 处裸 `<ul>` 与 `<p v-for>` 记录转 `fams-timeline`；3 处分页转 `fams-pagination`；状态文本转 `fams-status`；原件预览 `<figure>` 挂 `fams-card` |
| `views/RepairReturnPanel.vue`（84 → 125 行） | 交出 / 接收 / 拒收 / 收回四类记录转 `fams-timeline`；按实物去向选择的四按钮进 `fams-inline`；收回位置目录分页转 `fams-pagination`；确认块 `role="group"` |
| `views/RepairReturnSourcePanel.vue`（72 → 105 行） | 损坏归还后续列表转 `fams-timeline`；分页转 `fams-pagination`，页码 `<span>` 按词表移入 `.fams-pagination-actions` 两按钮之间 |
| `views/RepairReassignmentPanel.vue`（62 → 81 行） | 「重试」按钮移入 `fams-panel-actions`（仅顺序变化）；接任库管与两段依据进 `fams-field-grid`；确认块转 `fams-card fams-stack[role=group]` |
| `views/RepairTargetChangePanel.vue`（132 → 184 行） | 已批准目标与变更申请列表转 `fams-timeline`；目标目录三按钮进 `fams-inline`、分页转 `fams-pagination`；单个 `fams-selection` 从多余的 `fams-selection-group` 中解包；管理员决定区转 `fams-card fams-stack` |
| `views/RepairCollaborationPanel.vue`（59 → 102 行） | 邀请、意见与证据三块列表转 `fams-timeline`；邀请表单进 `fams-field-grid`；分页与确认块按词表收拢 |
| `views/RepairAssistanceView.vue`（43 → 99 行） | 自带 `<h1>` 与引导句删除（`router.ts` 的 `meta.uiTitle/uiDesc` 文字与原页面一致）；「刷新受邀维修」进 `#actions`；受邀列表与既有意见转 `fams-timeline`；意见表单进 `fams-field-grid` |
| `views/RepairInventoryPhoto.vue`（55 → 60 行） | 原 `<style scoped>` 按钮外壳改为 `div.fams-inline` + `fams-button`，读取与失效按钮文案逐字保留 |
| `views/RepairTechnicalEvidence.vue`（24 → 29 行） | 同上，删除 `<style scoped>`，两个按钮进 `fams-inline` |

11 个文件的 `<script setup>` 段落经 sha256 比对逐字节未变（`RepairView` `2eb95e52`、`RepairPhysicalPanel` `4e676f31`、`RepairFactoryPanel` `d655c6b8`、`RepairReturnPanel` `edf14d98`、`RepairReturnSourcePanel` `ab81b3b9`、`RepairReassignmentPanel` `60529279`、`RepairTargetChangePanel` `ccb64df9`、`RepairCollaborationPanel` `bf2e5b92`、`RepairAssistanceView` `a790731d`、`RepairInventoryPhoto` `19dc9339`、`RepairTechnicalEvidence` `953553ce`）；模板绑定集合差异只包含新增的 class / role / `type="button"` / aria 属性；按钮文案序列与迁移前一致（仅两处「重试」按钮因进 `fams-panel-actions` 而换位）。

## compat 回收

`styles/compat.css` 223 → 214 行。删除的段落：

- `.fams-main .technical-evidence` 整段（`RepairTechnicalEvidence` 迁移后归零）。
- 外壳选择器里的 `.fams-main .factory-panel`（与 `.fams-main .panel` 合并书写的那一项）；`.fams-main .panel` 与 `.fams-main .panel h2` 保留，仍只有 `TransferTargetChangeView` 一个使用者。
- 原生 `fieldset` 兜底收窄为 `fieldset:not(.fams-card):not(.fams-panel)`、`legend:not(.fams-panel-title)`、`label:not(.fams-field):not(.fams-selection):not(.fams-file)`、`textarea:not(.fams-control)`，避免已迁移面板内的 `fams-card` fieldset 被 compat 再叠一层边框和外边距。

仍留的段落及其使用者：`auth-stage` / `auth-form` / `auth-intro` / `.breadcrumb` / `.form-error` / `.primary-button`（`PasswordChangeView`）、`.form-error`（`TransferTargetChangeView`、`BusinessResultNoticeView`、`BusinessResultReviewView`、`ProxyConfirmationView`）、`.fams-main .panel`（`TransferTargetChangeView`），以及原生控件兜底。`auth-card` / `auth-note` 两段在本批前已归零。

## 组件层补齐

两处按同一令牌补齐的基线缺口（不新增视觉语言）：

- `styles/base.css` 增加 `img { max-width: 100% }`。本批删除的 scoped 样式里有按视图写的图片上限（返厂原件预览、故障照片预览），组件层此前没有等价声明。
- `styles/components/progress.css` 增加 `.fams-timeline li > .fams-button { align-self: flex-start }`。`.fams-timeline li` 是纵向 flex，会把条目内的按钮拉成整行并居中；口径与 `panel.css` 里既有的 `.fams-stack > .fams-button` 兜底一致。样张复核前报修进度行按钮占满 1124px，补齐后按内容宽度 788px（0.70）。

## 测试口径变化

- `views/RepairView.test.ts` 与 `views/RepairAssistanceView.test.ts` 里的 AppChrome 桩件补渲染 `<slot name="actions" />`。这两个视图把主按钮放进 `#actions`，桩件只渲染默认插槽时按钮不存在；改的是桩件保真度，断言口径、按钮文案与用例数量均未变（`RepairAssistanceView` 4/4、两个文件合计 12/12 通过）。
- 除此之外测试文件一行未改。

## 验证

- `npx vue-tsc --noEmit`、`npx eslint . --max-warnings 0`：无告警。
- `npx vitest run`：45 个测试文件 / 172 条用例全部通过。
- `npx vite build`：成功，CSS 33.34 kB（gzip 5.73 kB），较本批开始前 35.71 kB（gzip 6.08 kB）再降 2.37 kB。
- 脚本不变证明：`check_script_untouched.sh` 对全部改动 `.vue` 输出 SAME，本批 11 个文件全 SAME；`grep -c "<style"` 在 11 个文件中为 0；旧类名（`factory-panel`、`technical-evidence`、裸 `panel`）在报修与协助两条路由的 DOM 里命中数为 0。
- 布局量测（口径同 UI-03～UI-05）：`/repairs/19`、`/repair-assistance` 在 1440×900 下 `chromeBottom` 64、`h1Top` 106、`gapChromeToTitle` 42px；735×872 与 390×844 切到定高标签条模式，`gapChromeToTitle` 37px；页级 `h1` 恒为 1。
- 数据样张：临时 stub API（`/tmp/fams-stub-api.mjs`，非产品代码）补上报修台账三行、实物接修与验收入库、返厂范围与轮次、本轮报价 / 最终费用与原件凭证、受控转派、返还目标变更与目录、损坏归还后续、受邀协助看板与既有意见桩数据后，取 11 张整页截图（报修列表、损坏归还后续、报修详情、转派展开、目标变更展开、目标目录展开、返厂收回展开、协助列表、协助详情、详情移动态、协助移动态）与 12 张按 `aria-label` 面板的裁剪样张，逐块目视复核。
- 报修详情页在补齐 `repair.actions` 桩数据后渲染 64 个按钮、32 个字段、12 条时间轴，页面高 10602px（1440 宽）/ 13280px（390 宽）。
- 样张复核中修掉的三处问题：时间轴条目按钮被拉满整行（补 `.fams-timeline li > .fams-button`）；`RepairView` 故障照片预览 `<figure>` 漏挂 `fams-card`，与返厂原件预览不一致（已补）；compat 原生 `fieldset` 兜底仍给已迁移面板叠加边框与外边距（选择器收窄）。

## 已知限制

- 「读取厂家凭证 / 读取故障照片」按钮在时间轴条目里位于文件名下一行（`li` 为纵向 flex），横向并排的原版排布未还原；这是词表统一口径，移动端可读性更好。
- 部分分页只有 `.fams-pagination-actions` 而没有 summary 文本（返厂收回地点、返厂历史），因为原文本就没有「共 N 条」，按不变量不新增文字，视觉上会呈现右侧孤立的按钮组。
- 返厂面板的「本轮报价」与「本轮最终费用」两块在同一 `fams-card` 内纵向堆叠，1440 宽下所选轮次面板高约 2179px；真实数据多轮时高度会继续增长，未做折叠。
- `RepairAssistanceView` 的 `#actions` 按钮依赖测试桩件渲染，桩件不渲染具名插槽时按钮不可见——后续批次若再有视图启用 `#actions`，同名桩件需同样补齐。
- 根 `eslint.config.mjs` 的 `sourceFiles` 只覆盖 `*.{ts,tsx}`，`.vue` 判定为 "File ignored"，`eslint .` 通过不代表组件模板经过 lint。既有缺口，本批未扩大范围。
- 瞬时态（凭证上传中、报价已确认待收回、PDF 原件预览、目录加载中）只由单测覆盖，样张未取到；未启动真实后端与 compose 链路，不构成端到端验收。

## 下一批

仍依赖 compat 兜底的只剩异常与代确认分组：`ProxyConfirmationView`、`ItemTargetChangeView`、`TransferTargetChangeView`、`BusinessResultNoticeView`、`BusinessResultReviewView`、`PasswordChangeView`，回收 `.fams-main .panel`、`auth-*`、`.breadcrumb`、`.form-error`、`.primary-button`，并删掉最后两个 `<style scoped>`（`TransferTargetChangeView`、`ItemTargetChangeView`）。`PasswordChangeView` 迁完后 compat 只剩无人使用的原生兜底，可整体删除该文件并从 `index.css` 移除引用。

> 已按计划完成，见 [UI-07 异常与代确认视图迁移 + compat 整体移除](UI-07-exception-views.md)。
