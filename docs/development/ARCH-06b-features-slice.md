# ARCH-06b 目录重构为 features/ 切片（features 切片第 2 步）

基线：`ARCH-06a` `7a73178`。用户选定口径：**完整垂直切片（含拆内核）+ 域内再分 `views/`、`panels/`**。本步是纯目录与说明符重构：137 个文件被 git 识别为重命名，**未改任何模板、逻辑与文案**；构建 CSS 仍 `31.42 kB / gzip 5.65 kB`。

## 范围

### 一、目标结构与 11 个 feature

```
apps/web/src/
  api/{http.ts, business.ts, attachment.ts}     ← 跨域内核（有意常驻）
  components/{AppChrome.vue, FamsMediaPreview.vue}
  composables/{useResourceLoad, usePagedResourceList, useReassignmentFlow}
  features/<domain>/{api.ts, views/*.vue, panels/*.vue}   ← 测试与主题文件同目录
  auth/{api.ts, sessionStore.ts, types.ts}       ← 见限制 4
  router.ts, main.ts, App.vue, styles/, test/
```

feature 清单与文件数：`asset`、`asset-import`、`asset-return`、`auth`、`exception`、`inbound`、`inventory`、`loan`、`repair`、`requisition`、`transfer`。

面板归属按「是否为可复用子组件」判定（`*Panel`、`*Reassignment`、`DemandForm`、`TechnicalEvidence`、`*PhotoEvidence`、`InventoryPhoto`）；`components/` 下四个转派组件与 `AttachmentPanel` 就此从通用组件目录搬进各自业务域——`AttachmentPanel` 的唯一使用者是 `InboundDetailView`，归 `features/inbound/panels/`。

### 二、说明符重写（79 个文件）

由一次性脚本（不入库）完成，规则化而非逐文件手改：

- `@/<old-domain>/api` → `@/features/<domain>/api`；`@/views/X.vue` 与 `@/components/X.vue` → 新的 feature/views 或 feature/panels 路径；`@/attachment/api` → `@/api/attachment`。router 的 35 条懒加载工厂路径全部随之改写。
- 同域相对导入按「兄弟文件的新位置」重算：`views/` 与 `panels/` 分目录后，12 处 `./XPanel.vue` 变为 `../panels/XPanel.vue`；测试文件随主题同移，兄弟关系不变。
- 手工收尾 1 处：`api/attachment.test.ts` 的 `./api` 改为 `./attachment`（主题文件改名所致）。

### 三、兑现 06a 遗留的字典归位

`transferLabels`（实属调拨域）与 `inspectionLabels`（实属退还退库域）从 `features/loan/api.ts` **逐字**搬入新建的 `features/transfer/api.ts` 与 `features/asset-return/api.ts`，4 个使用者的导入随之改写；`features/loan/api.ts` 现只剩 `loanLabels` 与 `reminderLabels`，即「借用域模块只含借用域文案」。字典内容一字未改，UI-19/20/21 的「中文文案唯一来源」口径得以保持。

## 验证

| 口径 | 结果 |
| --- | --- |
| `vitest run` | **66 文件 / 285 用例全绿，未改任何断言**——测试与主题文件同移后兄弟关系仍成立 |
| `vue-tsc --noEmit` | 0 错误（模块图与 79 个文件的说明符全部指对；迁移过程中唯一暴露的错位是三个孤儿测试文件与 `./api`，已修） |
| `npm run lint` | 含 `.vue`、`--max-warnings=0`，exit 0（`vue/component-name-in-template-casing` 等模板规则亦通过，模板零改动） |
| `vite build` | CSS 逐字节不变；入口 `index` 54.44→54.48 kB（gzip 20.78→20.81），增量为两份新 api 模块与导入图变化；共享块 `AppChrome` 73.47 kB 不变 |
| 重命名识别 | `git add -A` 后 **137 项被识别为 R（重命名）**，历史可续接；改动正文仅 180/179 行 |
| **浏览器实测跨目录解析** | dev server + 页面内 fetch 桩打开 `/inbound/9`：`performance` 资源记录显示实际按需加载 `/src/features/inbound/views/InboundDetailView.vue`、`/src/features/inbound/panels/InboundReassignment.vue`、`/src/features/inbound/panels/AttachmentPanel.vue`、`/src/features/inbound/api.ts`、`/src/features/asset/api.ts` —— 同时证明 `views/`→`panels/` 的相对跨目录导入、跨 feature 的 api 引用、以及 `@` 别名在新层级下成立；`h1` 为「入库单详情」，桩单号 `RK-SLICE-9` 与明细行 `FA9` 渲染成功，错误提示 0 条 |

## 已知限制

1. 目录映射由名称前缀规则生成后我逐条核过，但以下几处是**判断而非事实**：`ItemTargetChangeView` 归 `requisition`（它同时服务领用与借用，路由为 `/requisition-target-changes`）；`RepairInventoryPhoto` 归 `repair`（它读取 `inventory-repair-evidence`，但 UI 是维修证据）；`AttachmentPanel` 归 `inbound`，若维修侧将来复用需上提到 shared。
2. `exception` 按路由分组「异常与核查」聚装代确认与业务结果核查两类视图，不是单一单据族；若后续要按单据族再分，属独立判断。
3. 三个常驻内核（`src/api/{http,business,attachment}.ts`）+ `src/components` 的两个通用组件 + `src/composables` 不并入 feature，这是垂直切片的有意例外，06a §2 已预告；`business.ts` 与 `attachment.ts` 何时能按域进一步拆分，取决于是否出现第二个真使用者。
4. `auth` 是唯一 api 留在域外（`src/auth/{api,sessionStore,types}.ts`）而视图在 `features/auth/views/` 的 feature——会话存储被 58 个文件引用，本批不动它，但这构成一处口径不一致，应在后续批次统一。
5. `docs/development/UI-01…21` 记录内的旧路径未回改：它们是当时状态的证据，不是现役索引；`file-classification-manifest.md` 与 `traceability-matrix.csv` 经核未按这些路径登记（0 命中），无陈旧化。
6. 本批未跑 CDP 三口径与真实后端；不替代远端 CI、正式 QA 与生产验收。
