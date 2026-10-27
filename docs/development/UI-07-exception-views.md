# UI-07 异常与代确认视图迁移 + compat 整体移除（第七批，收尾）

基线分支 `codex/d6-20-factory`（d10f246），实施分支 `codex/d6-20-factory-ui`。承接 [UI-06 报修与返厂视图迁移](UI-06-repair-views.md)。本批迁完最后 6 个仍依赖 compat 兜底的视图（异常与核查分组 5 个 + 首次登录改密），并把 `styles/compat.css` 整体删除——至此 `apps/web` 全部视图与面板都走 `fams-*` 组件层，迁移期兼容层不再存在。

## 范围

| 文件 | 迁移要点 |
|---|---|
| `views/ProxyConfirmationView.vue`（171 → 194 行） | 本批样板。自带 `<h1>` 与引导句删除（`router.ts` 的 `meta.uiTitle/uiDesc` 与原页面文字逐字一致）；「刷新当前记录」进 `#actions`；记录选择列表转 `fams-timeline`；详情转 `fams-card fams-stack`，凭证附件列表转 `fams-timeline`；审批说明转 `fams-field` + `fams-control`；分页转 `fams-pagination` |
| `views/BusinessResultNoticeView.vue`（105 → 116 行） | 「刷新通知」进 `#actions`；未读计数与引导句拆开（前者留在面板内，后者由 `meta.uiDesc` 承担）；通知条目转 `fams-card fams-stack`，展开明细用原生 `<details>`；核查说明转 `fams-field`；分页转 `fams-pagination` |
| `views/BusinessResultReviewView.vue`（50 → 60 行） | 「刷新核查列表」进 `#actions`；核查请求转 `fams-card fams-stack` + `fams-panel-head`；两条跳转链接保持原样；空态与分页按词表收拢 |
| `views/ItemTargetChangeView.vue`（121 → 145 行） | 删 `<style scoped>`；变更记录转 `fams-timeline`；决定区转 `fams-card fams-stack`；已交出地点一行拆为地点与时间两条 `<small>`；错误提示转 `fams-notice data-state="error"[role=alert]` |
| `views/TransferTargetChangeView.vue`（133 → 162 行） | 删 `<style scoped>` 与 `.panel` 卡片；目录选择转 `fams-field-grid` + `fams-inline`；分页转 `fams-pagination`；`.form-error` 转 `fams-notice` |
| `views/PasswordChangeView.vue`（49 → 55 行） | `auth-stage` / `auth-card` → `fams-auth-stage` / `fams-auth-card`（设计层既有类，见 `layout.css`）；`auth-form` 字段转 `fams-field` + `fams-control`；`.form-error` → `fams-notice`；`.primary-button` → `fams-button data-kind="primary"`；自带 `<h1 id="password-title">` 保留（该路由无 `meta.uiTitle`，外壳不渲染页头） |

6 个文件的 `<script setup>` 段经 sha256 比对逐字节未变（`ProxyConfirmationView` `542d9a31`、`BusinessResultNoticeView` `ff2680b2`、`BusinessResultReviewView` `5404fcc7`、`ItemTargetChangeView` `57842831`、`TransferTargetChangeView` `05dd2331`、`PasswordChangeView` `42d252e8`）。

模板绑定集合差异：插值集合、`role`、`maxlength`、`accept`、控件相对顺序全部 SAME；`aria` 只新增面板级 `aria-label`（`异常代确认`、`业务结果通知`、`代确认结果核查`、`领用与借用目标变更`、`调拨目标变更`，取值与 `meta.uiTitle` 同源，为词表要求的分组容器标签），无任何丢失；`type` 只新增 `button` / `submit` 显式声明。按钮文案序列一致（`BusinessResultNoticeView` 的「刷新通知」因进 `#actions` 而换位，集合不变）。

可见文字差异全部可归因：丢失的标题与引导句逐字等于 `router.ts` 的 `uiTitle`/`uiDesc`（由外壳渲染，测试桩件同步渲染后断言仍成立）；其余为整行文本按词表拆成 `<strong>` + `<small>`（凭证文件名与扫描状态、已交出地点与时间、未读计数）。未新增任何原文没有的用户可见文本。

## compat 整体删除

`styles/compat.css`（214 行）在本批视图迁移后只剩两段仍有真实使用者，先按同一令牌就地归位，再删文件与 `index.css` 的引用：

- `ul[class] { list-style: none }` → `styles/base.css`，与既有 `ul, ol { margin; padding }` 重置并列，并补 `ol[class]` 对称。
- `@media print` 段 → `styles/layout.css` 末尾。该段命中的 `.fams-topbar` / `.fams-sidebar` / `.fams-pagination` / `.fams-layout` / `.fams-panel` / `.fams-tag-preview` 全部是组件层类；选择器里的 `.breadcrumb` 已零使用者，随文件一起删除。
- 其余段落（`.primary-button`、`.form-error`、`auth-*`、`.breadcrumb`、`fieldset` 与原生控件 / 表格兜底、`.fams-main .panel`、隐藏旧页头的 `:nth-of-type` 选择器组）使用者在本批与第六批后全部归零，直接删除。

删除后样式层为 `tokens.css → base.css → components/*.css → layout.css` 共 13 个文件 / 1455 行；入口注释同步去掉「迁移兼容」。基线原 `apps/web/src/styles.css`（131 行）在本轮改造之初已被替换，仓库内不再存在任何 compat / 旧样式入口。

## 测试口径变化

- `views/ProxyConfirmationView.test.ts`、`views/BusinessResultNoticeView.test.ts`：AppChrome 桩件补渲染 `<slot name="actions" />`。视图把主按钮放进 `#actions`，桩件只渲染默认插槽时按钮不存在。改的是桩件保真度，断言、按钮文案与用例数量未变（6/6 通过）。
- `views/ItemTargetChangeView.test.ts`、`views/TransferTargetChangeView.test.ts`：AppChrome 桩件改为按 `useRoute().meta` 渲染页头，路由桩件补 `meta`。这两个视图的引导句已按不变量交给外壳，而测试断言里仍包含该句原文（`批准不会提前转移资产责任` / `原责任、已交出地点及物理锁保持`）；断言口径一字未改，只让桩件与真实外壳行为一致（各 3/3 通过）。
- 除上述 4 个桩件块外，测试文件一行未改；断言、用例数量与匹配口径均未变。

## 验证

- `npx vue-tsc --noEmit`、`npx eslint . --max-warnings 0`：无告警。
- `npx vitest run`：45 个测试文件 / 172 条用例全部通过。
- `npx vite build`：成功，CSS 27.71 kB（gzip 5.08 kB）。UI-06 结束时为 33.34 kB（gzip 5.73 kB），本批随 compat 整体删除再降 5.63 kB（gzip 0.65 kB）。
- 脚本不变证明：`check_script_untouched.sh` 对全部改动 `.vue` 输出 43 个 SAME，仅 `AppChrome.vue`、`HomeView.vue` 两个外壳 / 基础设施文件 MISMATCH（UI-01、UI-02 已单列的允许例外）。本批 6 个文件全 SAME；`grep -c "<style"` 在全部 `views/*.vue` 与 `components/*.vue` 上为 0。
- 全路由回归扫描（compat 删除后，18 条路由 × 1440×900）：每条 `visibleH1` 恒为 1、正文字体为 `-apple-system, "sy…`（令牌栈）、非 `fams-`/`u-` 旧类名命中 0、带类但不属组件层的元素 0、回落到浏览器默认衬线字体的可见文本 0。
- 布局量测（口径同 UI-03～UI-06）：`/proxy-confirmations`、`/business-result-notices`、`/business-result-reviews`、`/requisition-target-changes`、`/transfer-target-changes` 在 1440×900 下 `chromeBottom` 64、`h1Top` 106、`gapChromeToTitle` 42px；735×872 与 390×844 切到定高标签条，`gapChromeToTitle` 37px；旧类名命中全为 0。
- 数据样张（临时 stub API `/tmp/fams-stub-api.mjs`，非产品代码，补入代确认 4 条不同状态、附件与凭证、通知 2 条、核查请求 2 条、领用目标变更 2 条、调拨目标变更目录与改密会话开关后）：`proxy_list_desktop`、`proxy_detail_desktop`（41 待批准）、`proxy_approved_desktop`（42 已批准待执行）、`proxy_draft_desktop`（44 凭证草稿）、`notice_desktop`、`review_desktop`、`requisition_change_desktop`、`transfer_change_desktop`、`password_desktop`、`proxy_list_mobile`、`notice_mobile`、`transfer_change_mobile`、`password_mobile` 共 13 张整页截图，逐张目视复核。
- 改密页样张需要会话返回 `requiresInitialPasswordChange: true`，否则路由守卫按设计把 `/password/change` 重定向回工作台；验证时临时用一份只翻转该标志的 stub 副本（`/tmp/fams-stub-api-pw.mjs`）取图，取完已换回原 stub 并复核 `/api/v1/auth/me` 恢复 `false`。产品代码未为此改动。
- 样张复核未发现需要回修的问题：时间轴按钮均为内容宽度，卡片 / 面板 / 分页 / 提示与已迁移批次视觉一致。

## 已知限制

- `transfer_change_desktop` / `transfer_change_mobile` 呈现「没有当前记录或操作的权限。」——stub 的调拨目标变更上下文端点未按该业务分支返回数据，属样张数据缺口，非样式问题；该视图的表单与目录块在样张里未取到展开态，仅由单测覆盖。
- `BusinessResultReviewView` 的副标题把「核查原因」与时间用 ` · ` 连接，原因句本身以句号结尾，视觉上出现「。 · 2026/10/23 17:12:00」。这是迁移前既有的插值模板，按不变量未改写文案。
- 部分分页只有 `.fams-pagination-actions` 而无 summary（`ItemTargetChangeView` 原文即「1 / 1」），按不变量不新增「共 N 条」。
- 改密页保留自带 `<h1>`，因此该页没有外壳页头，`gapChromeToTitle` 口径不适用；其余 5 条路由页头全部由 `AppChrome` 渲染。
- 根 `eslint.config.mjs` 的 `sourceFiles` 只覆盖 `*.{ts,tsx}`，`.vue` 判定为 "File ignored"，`eslint .` 通过不代表模板经过 lint。既有缺口，本批未扩大范围。
- 全部验证口径为「单测 + 构建 + 静态样张」，未启动真实后端与 compose 链路，不构成端到端验收；真实短信、真机联调按既有安排留到功能开发完成后统一执行。
- 本轮 7 批改动均未提交，工作区保持未 commit 状态，等待用户复核。

## 下一批

无。计划内 7 个分组（业务流转 → 盘点 → 导入/期初 → 报修/返厂 → 异常与代确认）已全部迁完，`compat.css` 已整体删除，视图层不再存在旧类名与 `<style scoped>`。后续可做（未开始、需另行确认）：`.vue` 纳入 eslint 覆盖、把 `fams-*` 组件层与 D0.4 基线做一次差异复核、以及按用户安排提交本分支成果。

> 用户复核后追加一批外壳改造，见 [UI-08 侧栏一级分组改为可折叠](UI-08-sidebar-collapsible-groups.md)。
