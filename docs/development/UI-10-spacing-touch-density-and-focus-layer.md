# UI-10 间距单源化、窄屏触控密度与确认块焦点层

实施分支 `codex/d6-20-factory-ui`，基线为 UI-09 之后的本地提交链（`e58f1a6` 起，工作区 5 个文件未提交）。本批不再迁移视图，而是按 UI/UX Pro Max 的审查口径（4/8 间距节奏、≥44pt 触控区、遮罩与确认层、z-index 分层）对已迁移的 14 条路由做一轮可量测的打磨，并把结论落成 CSS 契约。业务脚本零改动，测试一行未改。

## 审查证据（改前）

- **顶层块间距有两个来源**：`.fams-main` 的 `gap: 16` 与 `.fams-panel` 自带的 `margin-top: 24` 叠加，`/repairs/19` 实测 `[16,40,40,40]`——40px 比 D0.4 的区块间距 20—24 大一档，且首个面板要靠 `:first-of-type { margin-top: 0 }` 特例压回去。
- **窄屏触控密度不达标**：390 视口 14 条路由共 402 个可见按钮高度 < 44（PC 档 40/32 直接沿用到手机宽度），侧栏转标签条后条目仍为 48 定高容器里的 40。
- **嵌套卡片内边距逐层 24**：≤900 时四层嵌套把内容压到 224px（UI-09 已知限制）。
- **确认块与内容同权重**：16 处 `role="group"` 的「确认/核对」块与普通卡片视觉一致，「即将写入」没有从页面流里分出来。
- **弹窗层零使用者**：`styles/components/dialog.css` 的 `.fams-dialog` / `::backdrop` / `.fams-toast` 在全仓 `.vue` 里命中 0，5 处图片与凭证预览仍是页内 `<figure>` 展开。该项留给 UI-11。

## 范围

| 文件 | 变化 |
|---|---|
| `styles/layout.css`（281 → 283 行） | `.fams-main` 的 `gap` 16 → 24，删除 `.fams-main > .fams-panel:first-of-type { margin-top: 0 }` 特例；`.fams-shell` 与 `.fams-sidebar` 在 `100vh` 之后补 `100dvh`（移动端地址栏收起时不再多出一屏空白）；≤900 标签条里给 `.fams-sidebar .fams-navigation[data-mode="sidebar"] .fams-button` 补 `min-height: var(--fams-size-44)`；文件头注释同步为「区块间距 24」。 |
| `styles/components/panel.css`（208 → 242 行） | 引入每容器内边距单源 `--fams-pad`：`.fams-panel`/`.fams-card` 外层 24 → 嵌套 16 → ≤900 嵌套 12 → ≤560 收一档到 16；`.fams-panel` 删掉自带 `margin-top`；`.fams-panel-divider` 的负外边距改读 `--fams-pad`，嵌套卡里的分隔线仍能贴边；新增 `details[open] > summary { margin-bottom: 16 }`；`.fams-card[role="group"]` 加 3px 主色左描边 + `--fams-bg-subtle` 底，≤560 时其 `.fams-panel-actions` 纵向堆叠并拉满。 |
| `styles/components/button.css`（121 → 131 行） | ≤560 整档抬到 `min-height: var(--fams-size-44)`，含 `data-size="sm"` 与承担行内动作的 `data-kind="link"`；PC 密度保持 40/32 不动。 |
| `styles/base.css`（116 → 117 行） | `body` 在 `min-height: 100vh` 后补 `100dvh`。 |
| `views/RepairView.vue:267` | 盘点证据的 `v-for` 容器补 `class="fams-stack"`：原来说明 `<p>` 与「读取盘点原图」按钮 0px 相贴。`<script>` 块逐字节不变。 |

## 不变量比对

- `check_script_untouched.sh apps/web/src/views/RepairView.vue` → `SAME 2eb95e52`。本批唯一改动的 `.vue` 只有这一个 class 属性。
- 按钮文案：未新增、未删除、未改写任何用户可见文字；`RepairView.vue` 的 `{{ }}` 插值与 `:key` 表达式原样保留。
- 模板绑定差集：`RepairView.vue` 新增属性只有 `class`；其余 4 个文件是纯 CSS，不含绑定。
- 未改测试文件；未新增类名词表条目（`--fams-pad` 是自定义属性，不是选择器）。

## 验证

- `npx vue-tsc --noEmit` 退出 0；`npx eslint . --max-warnings 0` 无告警；`npx vitest run` 46 个测试文件 / 180 条用例全部通过；`npx vite build` 通过。
- CSS 体积 29.81 kB（gzip 5.41 kB），较 UI-09 的 29.20 kB（gzip 5.32 kB）增 610 B / gzip 增 90 B，来自 `--fams-pad` 三档断点、确认块焦点层与 ≤560 触控档。
- **14 条路由 × 1440/390 复扫（改后）**：
  - 顶层块间距全部 `[24]`～`[24,24,24,24,24]`，无 16/40 混档；面板 `marginTop` 全为 `0px`。
  - 面板内边距 1440 = 24px、390 = 16px；嵌套卡 1440 = 16px、390 = 12px。
  - 390 可见按钮 `<44` 归零：每路由剩 4 个高度 0 的 `.fams-navigation-toggle`，实测 `display: none`（≤900 标签条模式隐藏一级分组折叠按钮），非可见元素；可见档为 44，`/repairs` 与 `/repairs/19` 另有 54/62/76 的换行增高按钮。侧栏标签条条目 `nav=[0,44]`（0 同样是隐藏项）。
  - 390 文档宽 14 条路由全为 390，无横向溢出；表格超宽部分在 `.fams-table-scroll`（`overflow: auto`）内横滚。
  - 零间距回归探针（跳过 `details:not([open])` 后）14 条路由 × 2 视口 `tight=[]`。
  - 确认块契约：`3px / rgb(240,243,248) / 24px`（1440）与 `… / 16px`（390）；390 两个动作 `stacked: true`、主按钮实宽 288。
- **样张**（dev 5173 + 临时 stub `/tmp/fams-stub-api.mjs`，8080，非产品代码）：`/tmp/fams-ui10-repairs19-1440.png`（间距与嵌套档）、`/tmp/fams-ui10-confirm-1440.png`（焦点层：3px 主色左描边 + 浅底 + 主次动作）、`/tmp/fams-ui10-details-1440.png`（`/loans` 展开「接收前借用延期」，摘要→正文 16px）、`/tmp/fams-ui10-repairs19-390.png`（整页窄屏密度）、`/tmp/fams-ui10-confirm-390.png`（确认块动作纵向堆叠）。
- **复核中修掉的问题**：① `details` 展开后摘要与正文 0px 相贴 → `details[open] > summary` 补 16px；② `RepairView` 盘点证据说明与读取按钮 0px 相贴 → 容器补 `fams-stack`；③ 首版 `details` 样张裁剪用视口坐标，`Page.captureScreenshot` 的 `clip` 实为文档坐标，取到的是页头 → 改用 `rect + scrollY` 重取。

## 已知限制

- 确认块焦点层与 390 动作堆叠用**同构合成节点**量 CSS 契约（真实确认块需多步交互才渲染），CSS 值即最终渲染值，但没有真业务流程截图。
- 首轮零间距探针在 `/loans` 报 2 个命中，深查为收起 `<details>` 的子元素仍保留矩形（Chrome 用 `content-visibility: hidden` 跳过渲染），属探针假阳性，非视觉缺陷；探针已按 `details:not([open])` 排除。
- ≤560 把 `data-kind="link"` 的行内按钮一并抬进 44 档，表格行高随之变高——这是窄屏触控密度的代价，PC 档未动。
- `.fams-dialog` / `.fams-toast` 仍零使用者，预览仍是页内 `<figure>`；本批按用户选定口径未动，留 UI-11。
- 动效方向与破坏性语义（enter/exit 缓动、`data-kind` 危险色档）本批按用户选择**未纳入**。
- 根 `eslint.config.mjs` 的 `sourceFiles` 只覆盖 `*.{ts,tsx}`，`.vue` 模板不经 lint（既有缺口）。
- 验证口径仍为「单测 + 构建 + 静态样张」，未接真实后端；本批全部改动未提交。

## 下一批

UI-11 预览弹窗：新增 `components/FamsMediaPreview.vue`（原生 `<dialog>` + `showModal`，jsdom 无 `showModal` 故保留 `open` 属性出口，`@cancel.prevent` → emit close 以便父级 revoke blob URL），替换 5 处图片/凭证预览（`RepairView.vue:220`、`RepairInventoryPhoto.vue:58`、`InventoryPhotoEvidence.vue:64`、`RepairTechnicalEvidence.vue:27`、`RepairFactoryPanel.vue:340`）。父级 `<script setup>` 只允许多一行 import，按钮文案与 `img` 存在性必须保持，测试不改；若断言失败即停下报告。
