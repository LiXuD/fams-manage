# UI-09 同步远端 D6-20C1 并迁移原机部件面板

实施分支 `codex/d6-20-factory-ui`。本批先同步远端：`origin/main` 在本地基线 `d10f246` 之后新增 4 个提交（`4b5eaf0` 文档、`7de71af` 与 `2ff8ea9` 原机部件核实的前后端与原生端、`455ba00` 交接），带来一张全新的 PC 面板 `RepairFactoryPartsPanel.vue`；随后按既定词表把它与受影响的返厂面板一起迁到 `fams-*` 组件层。业务脚本零改动，测试一行未改。

## 远端同步方式

- `merge-base` 正好等于本地 HEAD，本地 0 领先 / 4 落后，因此用 `git merge --ff-only origin/main`，不产生合并提交、不改写历史。
- 工作区有 57 个文件的未提交 UI 改动，与上游 34 个变更文件只有 `RepairFactoryPanel.vue` 重叠。做法：先做整目录字节备份（`/tmp/fams-ui-backup/web-src-before`，与被备份文件 sha256 一致），把该文件还原到 HEAD，ff 合入后再把已迁移的 `<template>` 接回上游新脚本，并补上新面板挂载点。
- 踩到的坑：`rtk git diff` 输出是压缩摘要，不能当备份补丁使用（首次生成的 571 行 patch 覆盖不了真实的 3241 增 / 1304 删）。备份一律用整目录 `cp`，恢复后按 sha256 校验。
- 上游带进 `apps/web` 的其余改动（`factory-api.ts` 事件名、`factory-types.ts` 的 `PARTS_CHANGE` 与 `FactoryPartsPage`）不含样式，原样接收。

## 范围

| 文件 | 变化 |
|---|---|
| `views/RepairFactoryPanel.vue` | 接收上游 6 处脚本改动（子面板 import、`partsBusy`、`locked`、`watch` 依赖、`reset`）与模板挂载点；保留本分支已迁移的 `fams-*` 模板（235 → 364 行）。`<RepairFactoryPartsPanel>` 挂在「核实收回 / 登记寄出」fieldset 之后、凭证 `figure` 之前，绑定表达式逐字照搬上游。 |
| `views/RepairFactoryPartsPanel.vue` | 上游新增文件，130 → 172 行：根 `section` 用 `fams-card fams-stack`，卡头 `fams-panel-head` + 标题/说明 + 右上角 `fams-panel-actions`；提示改 `fams-notice`；记录条目 `article` 用 `fams-card fams-stack`；分页改 `fams-pagination`；两个 `fieldset` 用 `fams-card fams-stack` + `legend.fams-panel-title`（保留 `:disabled` 整组禁用）；字段进 `fams-field-grid`，勾选进 `fams-selection-group`，读取按钮用 `data-kind="link" data-size="sm"`；`<style scoped>` 整段删除。 |
| `styles/base.css` | 标题 margin 重置补 `h5`、`h6`。部件记录卡用 `h5`，此前只重置了 h1–h4，`h5` 会带 UA 默认 1.67em 上下边距。 |
| `styles/components/progress.css` | `.fams-timeline li > .fams-button` 允许折行（`white-space: normal` + `overflow-wrap: anywhere` + `max-width: 100%` + 左对齐）。 |
| `styles/components/panel.css` | `.fams-inline > .fams-button` 同样允许折行。 |

## 不变量比对

- 脚本块 sha256：`RepairFactoryPanel.vue` = `427031c0`、`RepairFactoryPartsPanel.vue` = `5afcacbf`，均与 HEAD（即上游新版）逐字节一致。全仓 `check_script_untouched.sh` 只有 `AppChrome.vue`、`HomeView.vue` 两处 MISMATCH，是 UI-01/UI-02 已单列的外壳例外，本批未再动它们的脚本。
- 按钮文案序列：返厂面板 26 条、部件面板 9 条，与 HEAD 完全一致（含「核对部件变更记录 / 核对保存部件变更 / 确认保存本次部件办理 / 重试原部件办理 / 核对追加部件变更原件」等测试精确匹配的文案）。
- 模板绑定差集：新增只有 `role="group"` 与 `type="button"` 两类呈现属性；`{{ }}` 插值、`aria-label`、`maxlength`、`type`、`accept`、input/select/textarea 相对顺序全部 SAME。
- 全仓 `<style>` 命中 0；旧类名（`parts-panel`、`factory-panel`、`primary-button`、`workspace-*`、`nav-item` 等）命中 0。

## 验证

- `npx vue-tsc --noEmit` 退出 0；`npx eslint . --max-warnings 0` 无告警；`npx vitest run` 46 个测试文件 / 180 条用例全部通过（上游新增的 `RepairFactoryPartsPanel.test.ts` 8 条用例未改即通过，其中「所有 fieldset 必须 disabled」「按 label 文案定位 input/textarea」「`aria-label="确认本次部件办理"` 文本含变更前资料」三类断言是本次模板重写的硬约束）。
- `npx vite build`：CSS 29.20 kB（gzip 5.32 kB），较 UI-08 的 29.04 kB（gzip 5.30 kB）增 160 B，来自 h5/h6 重置与两条折行规则。
- 样张环境：dev server 5173 + 临时 stub 变体 `/tmp/fams-stub-api-parts.mjs`（把返厂轮次推到「原机已实际收回」、补一条 `PARTS_CHANGE` 干净原件与 `/factory/rounds/{id}/parts` 端点、两条部件核实记录 fixture）。该变体只为渲染新面板服务、非产品代码，采样后已换回原 stub 并复验 `/factory` 响应。
- 交互路径：`/repairs/19` → 核对返厂范围与轮次 → 选「第 2 轮 · 示例办公设备原厂服务站 · 原机已实际收回」→ 部件面板出现 → 核对部件变更记录。1440×2600：卡宽 1052、高 1676、字段栅格 3 列（316×3）、控件高 44、旧类命中 0；390×1600：卡宽 224、高 2344、旧类命中 0。
- 复核中发现并修掉的既有缺陷：390 视口下 `/repairs` 与 `/repairs/19` 文档宽被撑到 866（时间轴条目按钮和并排动作按钮的 `nowrap` 遇到长故障描述、UUID 文件名就不收缩）。补两条折行规则后按 18 条路由复扫：这两页 `866 → 390`，其余 16 条改前改后都保持 390（表格与标签条的宽元素各自在滚动容器内，不撑文档）。该缺陷与新面板无关，部件面板展开与否测得的 866 相同。
- 样张：`fams-parts-pc-panel.png`、`fams-parts-sm-panel.png`（部件面板裁剪）、`fams-parts-pc-full.png`、`fams-parts-sm-full.png`（整页）逐张目视复核。

## 已知限制

- 四层嵌套卡片（`fams-panel` → 返厂卡 → 所选轮次卡 → 部件卡）在 390 视口把内容压到 224px，控件可用宽度只剩约 176px；本批只修了横向溢出，未动嵌套密度，属结构性取舍（见下一批）。
- 部件面板只在轮次状态为「原机已实际收回」时渲染，样张靠临时 stub 变体触发；真实后端端到端未跑。
- `h5` 只进了 margin 重置，字号仍由 `.fams-panel-title` 决定（与 h4 同为 18px），未新增更小一档标题类，避免扩词表。
- 根 `eslint.config.mjs` 的 `sourceFiles` 只覆盖 `*.{ts,tsx}`，`.vue` 模板不经 lint（既有缺口）。
- 验证口径仍为「单测 + 构建 + 静态样张」；本批全部改动未提交。

## 下一批

无既定批次。可选后续（需另行确认）：① 窄屏嵌套密度——≤900px 时给 `.fams-card .fams-card` 降一档内边距，或让部件面板改用分隔块而非再套一张卡；② 把 `.vue` 纳入 eslint 覆盖；③ 为部件面板补一条 fams 结构断言（现只有行为断言）。
