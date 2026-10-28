# UI-12 入库单「待入库资产」改为栅格复选

实施分支 `codex/d6-20-factory-ui`，紧接 UI-11（工作区未提交改动之上）。用户反馈：入库管理 → 建立入库单里的「待入库资产」框在页面变化时会挤占其他样式，而且不好选。量测确认根因是 `<select multiple size="8">` 自带 188px 定高，把 `.fams-field-grid` 的整行撑到 218px（业务日期与目标仓库各留 174px 空白，目标位置被挤到第二行），多选还依赖 ⌘/Ctrl 点选。本批把它换成 fams-* 词表内的复选栅格：整行可点、选中行有色、高度封顶后内部滚动。脚本与测试一行未改。

## 范围

| 文件 | 变化 |
|---|---|
| `views/InboundView.vue` | 只改 `<template>`：删掉 `<select multiple required size="8">`，改为 `.fams-field.fams-field-wide` 内的 `.fams-selection-grid`（`role="group" aria-label="待入库资产（可多选）"`），每个资产一条 `.fams-selection` 标签 + 原生 `input[type=checkbox] v-model="assetIds" :value="item.id"`；资产块移到栅格末尾，日期/仓库/位置回到同一行。可见文案与 `{{ item.assetCode }} · {{ item.name }}` 插值逐字保留。 |
| `styles/components/selection.css` | +48 行新增 `.fams-selection-grid`：`repeat(auto-fill, minmax(min(100%, 260px), 1fr))`、`grid-auto-rows: min-content`、`gap 4/16`、`min-height 120 / max-height 320`、`overflow-y: auto` + `overscroll-behavior: contain`、1px `--fams-border-strong` 边框与 `--fams-radius-8`；条目 `min-height 40`、`padding-inline 8`、悬停 `--fams-bg-subtle`、`:has(:checked)` 上 `--fams-action-subtle`、`span` 允许 `overflow-wrap: anywhere`；≤560 单列、条目 44px、容器封顶 `44×6`。 |

## 不变量比对

- `<script setup>` 块 sha256 与 HEAD 完全一致：`962d1e302a6bdc559f8c5a481fa3e59ab01ab4ffe58b6ddf29c9bdccd3319c1b`（`awk` 抽取脚本段两侧比对）。
- 绑定集合差：新增 `v-model="assetIds"`（复选数组口径）、`:value="item.id"`、`role="group"`、`aria-label`，删除 `multiple`/`size`/`required`；`v-for`/`:key` 表达式与 `{{ }}` 插值原样。
- 按钮文案序列不变；未新增或删除任何用户可见文本。
- 未修改测试文件。

## 验证

| 门禁 | 结果 |
|---|---|
| `npx vue-tsc --noEmit` | 0 错误 |
| `npx eslint . --max-warnings 0` | No issues found（`.vue` 仍不在 `sourceFiles` 覆盖内，属既有盲区） |
| `npx vitest run` | Test Files 46 passed / Tests 180 passed |
| `npx vite build` | CSS 31.42 kB / gzip 5.65 kB（UI-11 为 30.38 / 5.52） |

CDP 量测（12 条 DRAFT 资产，`/tmp/fams-stub-api.mjs` 临时补的 fixture，非产品代码）：

| 口径 | 改前 | 改后 |
|---|---|---|
| 1440×900 | 表单首行 218px（`select` 188px 内滚），目标位置 top=495 | 日期/仓库/位置同行等高 74px；资产块 top=351、容器 h=224（client=scroll=222）、4 列、行高 66 |
| 735×872 | 同上撑高 | 2 列、容器 h=320（client=318 / scroll=432，内部滚动） |
| 390×844 | 资产行 218px | 单列、容器 h=264（client=262 / scroll=830）、行高 44—66、叠行 0；文档宽 390 = 视口宽 |

交互：逐个点选得到 `["105","121","125"]`、再点取消归零、`Input.dispatchMouseEvent` 真实点击 label 中心同样生效；选中行底色 `rgb(234,240,255)`（`--fams-action-subtle`）；提交经 stub 返回 `入库草稿 RK-STUB-003 已保存` 且明细按所选资产渲染；`修订草稿` 从单据回填勾选（`105`）正常；不选资产提交得到页面提示「请选择资产、仓库和位置」。

复核中修掉的问题：390 档首版出现文字叠行——容器封顶后 `auto` 行轨会收缩到条目的 `min-height`（44px），三行长名称溢出到下一格；补 `grid-auto-rows: min-content` 后行轨按内容取 66px，叠行归零且封顶与滚动保持有效。

样张：`/tmp/fams-ui12-old-panel-1440.png`（改前）、`/tmp/fams-ui12-fixed-{1440,735,390}.png`、`/tmp/fams-ui12-new-checked-1440.png`、`/tmp/fams-ui12-new-submitted-1440.png`、`/tmp/fams-ui12-revision-1440.png`。

## 已知限制

- 该表单段没有单测覆盖：`InboundView.test.ts` 只测深链，且 mock 角色为 `ASSET_ADMIN`，`warehouseRole` 为假时建立入库单段整体不渲染；栅格选择行为只由静态样张与 stub 提交验证。
- 资产项去掉了原生 `required`，空选提示从浏览器气泡变为页面顶部提示条（脚本 `create()` 内既有守卫，未改动）。
- 样张用的是临时 stub 与人工扩充的 DRAFT fixture，未接真实后端；390 档仍有 19 个元素超出视口宽度（表格滚动容器内的既有现象，与本次改动无关）。

## 下一批

可选方向（未开工）：预览弹窗关闭后焦点归位到触发按钮、弹窗与折叠的动效档、破坏性动作的 `data-kind` 语义、`.fams-toast` 首个使用者或删层；以及把同类「多选靠 ⌘/Ctrl」的原生 `select multiple` 统一到 `.fams-selection-grid`。
