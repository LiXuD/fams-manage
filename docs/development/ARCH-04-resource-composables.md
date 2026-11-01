# ARCH-04 资源加载 composable 与 5 个列表视图归位（架构批第 4 批）

基线：`ARCH-03` `e9f1b1e`。本批按 Vue 技能 §1.2/§2 的边界判据抽取「有状态读取职责」，不动任何模板与 CSS：构建 CSS 仍 `31.42 kB / gzip 5.65 kB`。

体检口径：57 个视图没有一个使用 composable（`src/composables` 与 `use*.ts` 改前均为 0），28 个文件各写一份 `async function load()`、25 处 `const error = ref("")` + try/catch + `finally busy = false`、18 处 `onMounted(load)`；迟到响应抑制只有 4 个文件自己写了 generation/epoch，其余靠「后到者覆盖」碰运气。

## 范围

### 一、新增两个聚焦 composable

| 文件 | 职责 | 明确不负责 |
| --- | --- | --- |
| `src/composables/useResourceLoad.ts`（62 行） | `busy`/`error`/`data` 三态与**迟到响应抑制**：每次 refresh 取递增 token，只有仍为最新且组件仍存活时才写回数据、提示与 busy；`onBeforeUnmount` 令在途结果失效并复位 busy；卸载后 refresh 直接不起新请求 | 会话身份变化后的重置（属域内政策，视图自己判断）；重试与幂等（属业务写操作，不在读取职责内）；整表清空（401/403/404 的 clear 由视图自己承担，故底座不提供无消费者的 reset） |
| `src/composables/usePagedResourceList.ts`（49 行） | 以 `page` 为唯一事实源，`rows`/`total` 用 `computed` 从最近一次成功读取派生；`goTo`/`pages` 负责越界与同页不发请求 | 筛选条件（留在调用方闭包里，改完直接 `refresh()`）；`pageSize`（各视图历史上都写死 20，不做可配） |

技能要求的「返回消费向状态与动作」据此落地：内部 `epoch`/`live` 不外泄，`data`/`busy`/`error` 以 `readonly` 返回。

### 二、5 个视图迁移（模板一字未改）

返回名对齐模板既有绑定，必要时用重命名解构，故 `<template>` 块零改动：

| 视图 | 迁移前 | 迁移后 |
| --- | --- | --- |
| `InboundView` | `receipts`/`error` 两 ref + `load()` try/catch + `onMounted` | `useResourceLoad` + `const receipts = computed(() => data.value ?? [])` |
| `AssetReturnView` | 5 ref + `refresh()` + `onMounted` | `usePagedResourceList`，`rows: returns` 重命名 |
| `RequisitionView` | 5 ref + `load()`/`refresh()` + `status` 条件 | `usePagedResourceList`，`status` 留在视图闭包 |
| `InventoryTaskView` | 5 ref + `list()`/`refresh()`/`pages(delta)` | `usePagedResourceList`，`rows: tasks`，`pages` 由底座提供 |
| `AssetLedgerView` | 5 ref + `load()` + `onMounted(load)` | `usePagedResourceList`，`refresh: load` 重命名以匹配模板里的 `load()` |

5 个视图净 −40 行；`problem` 一律传各域既有文案函数（`loanMessage`/`requisitionMessage`）或原样保留的本地兜底串，**用户可见文案零变化**。

### 三、未纳入本批的视图与原因

`RepairView`、`BusinessResultReviewView`、`LoanView`、`TransferView` 与盘点/返厂面板族的读取还需要「会话身份变了就作废在途结果」和「401/403/404 触发整表重置」两项政策。这两项是跨职责的域规则，本批不为其扩张 composable 表面（会退化成技能所警告的万能控制器）；等第二处真实消费形状确定后，再以 `isCurrent`/`onInvalidated` 钩子单独成批。

## 验证

| 口径 | 结果 |
| --- | --- |
| `vitest run` | **66 文件 / 285 用例全绿**（改前 65/276；净 +1 文件 +9 用例为底座自测，5 个视图原有断言一字未改） |
| 底座自测覆盖 | 挂载即读、失败保留上次成功数据、**并发只有最后一次有权写回（旧响应迟到 40 ms 后仍不改数据）**、卸载后在途结果与状态一律不写回、`immediate: false` 不自动读；分页的派生行/总数、`goTo`/`pages` 换页、越界与同页不发请求、失败保留原行与总数 |
| 翻页回归（原有视图测试，含真实点击） | `AssetLedgerView.test.ts:50-69` 的「下一页只改页码」与「查询回到第一页并带上条件」两条通过：断言 `listAssets` 实收 `(2,"","")`、渲染「第 2 页」，以及提交表单后实收 `(1,"000101","显示器")` |
| 浏览器实测（桩接口，不接后端） | 真实点击退库列表「下一页」：`/api/v1/asset-returns` 由 `@1` 变为追加一次 `@2`（只多一次请求），页码「第 1 页」→「第 2 页」，首行 `r1-0` → `r2-0`，汇总「共 41 条」保持 |
| `vue-tsc --noEmit` | 0 错误；`npm run lint`（含 `.vue`，`--max-warnings=0`）exit 0 |
| `vite build` | CSS 不变；两个 composable 并入共享块，`AppChrome-*.js` 73.45→73.46 kB（gzip 29.01→29.02），入口 `index` 54.24→54.35 kB（gzip 20.66→20.70），即净成本 +0.11 kB 原始 / +0.04 kB gzip |

## 已知限制

1. 只有 5 个视图迁移，其余 52 个视图仍各写读取循环；这是刻意的分批口径，不是遗漏。
2. `AssetReturnView`/`RequisitionView` 原 `refresh()` 带 `if (busy.value) return;` 重入守卫，底座改为**允许并发但以 epoch 保证最后一次写回**；两视图的刷新按钮本就 `:disabled="busy"`，故可观察行为不变，该差异在 `InventoryTaskView`/`AssetLedgerView`（原本无守卫）方向上也是同一语义。
3. 底座只有 4 个返回成员（`data`/`busy`/`error`/`refresh`）加分页的 `rows`/`total`/`page`/`goTo`/`pages`，不提供无消费者的 `reset` 之类预留接口；`InboundView` 这类「非分页列表」需要自己写一行 `computed(() => data.value ?? [])` 兜住 null，这是把「是否分页」的选择留在视图侧的代价。
4. 验证仍是单测 + 构建 + 桩化浏览器实测，未接真实后端与真实短信；不替代远端 CI、正式 QA 与生产验收。CDP 三口径页头量测本批未跑（无模板与 CSS 改动，且 `compat.css` 早在 `0321496` 已删）。

## 下一批

ARCH-05：primitive 状态由 `ref` 归位为 `shallowRef`（技能 reactivity 首条，全仓 `shallowRef` 现仍为 0），并合并 `components` 下 4 个 77–79 行几乎相同的 Reassignment 组件。
