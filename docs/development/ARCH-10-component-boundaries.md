# ARCH-10 组件边界与分解复查（架构批第 10 批，三刀落地）

基线：`ARCH-09` `c86b448`。判据来自 `vue-best-practices` 的组件边界要求：**props down / events up，中间层不得只做转发**；typed provide/inject 用 `InjectionKey`；抽抽象要有多份真实重复，且**不得为了消除转发而合并职责不同的东西**；文件长度只是复查信号，不是抽取阈值。

## 三处提交与量级

| 提交 | 内容 | diff |
| --- | --- | --- |
| `543221b` | 页内互斥登记表 + 目录装配纯函数 | 23 文件 +316 / −141 |
| `4b4009b` | `useConfirmedSubmit` 底座 + 维修域七面板采纳 | 9 文件 +284 / −66 |
| `46bb20a` | 同一底座推到盘点、调拨/领用/退库、异常/借用/入库三域 | 19 文件 +316 / −228 |

## 1. 页内互斥登记表（断开面板↔父级的连线转发）

维修详情页原本 7 个面板各自 `emit("busyChange")`、父级聚合成一个 `blocked` 布尔再逐个 prop 传回——**每个面板都要知道其他每个面板**，是 O(N²) 的连线，也是 ARCH-08 期间「模板里 `blocked || busy`」这类派生的源头。

改为 `features/repair/busyRegistry.ts`：

```ts
export type BusyId = "collaboration" | "factory" | "host" | "physical"
  | "reassignment" | "return" | "returnSource" | "targetChange";
```

- `createBusyRegistry()` 持位图；`provideBusyRegistry()` 返回实例；`useBusy(id)` 拿只写自己那一位的 `BusyClaim`，缺 provider 直接抛（不是静默降级为「不互斥」）。
- `onScopeDispose` 释放自己这一位；`occupied(id)` 供页头那种**不对称**门禁（「除我之外有人在忙就锁页头」）。
- 结果：7 个面板的 `blocked` prop 与 `busyChange` emit 全部删除，7 个面板测试同步改（`RepairCollaborationPanel.test.ts` 等）；新增 `busyRegistry.test.ts` 5 条：两个消费者互见且不含自己、第三方卸载后释放、缺 provider 抛、位只写自己、`occupied` 的不对称口径。
- **保留** `blocked` prop 的 3 处（逐条核过理由，不是没改完）：`RepairFactoryPartsPanel.vue`（父级持有「目录已过期」这一外部事实，不是页内互斥）、`RepairInventoryPhoto.vue`、`InventoryLateDamagePanel.vue`（后两者的禁用来自只读态与父级单据状态，域外输入）。

## 2. 目录装配纯函数（`features/transfer/references.ts`）

- `loadCatalogPages(request, basePath, kind, organizationId?)`：`page=1 / pageSize=200` 的翻页扇出，请求客户端注入而不是自造（5 个视图同形抄写）。
- `resolveReference(choices, id)`、`buildTransferTarget({…})`：把「目录项 → 目标快照 + 引用版本」的组装从视图里断开，成为可单测的纯函数。
- **刻意不抽** `useCatalogChoices`：5 处的不变量各不相同（是否带 organizationId、是否要求唯一命中、失败是否阻断建单）。合并只会造出一个带 5 个开关的抽象，把差异藏进参数里。

## 3. 确认式幂等提交底座（`composables/useConfirmedSubmit.ts`）

不变式：**`Idempotency-Key` 在「起草意图」时生成一次，重试必须复用同一把键**；确定性失败（4xx）清键；身份换了或组件卸载使键失效。此前 28 处各写一遍这套簿记。

```ts
export interface Submission<T> { key: string; payload: T }
```

- 动作：`prepare / confirm / settle / abandon / fail / cancel / reset`。
- `prepared` 与 `pending` 暴露成**两个 `ComputedRef`**，而不是一个联合状态：Collaboration 的读门禁、6 个模板的 `v-if="pending" / v-else-if="prepared"`、Factory 的 `watch([pending, confirmation])` 都需要区分这两相——合成一相就会把「已备未确认」误判成「无意图」。
- **不吸收** `busy`、`locked`、URL 回跳与域内错误文案：这些是各域政策，吸收会把差异压成参数。
- 采纳 **26** 个视图/面板（另有底座自身与其 8 条用例）。
- **未采纳 2 处并写明理由**：`InventoryTaskDetailView.vue`（它的提交流程同时驱动预览摘要与冻结快照两段状态，键只是其中一环）、`InboundDetailView.vue`（多动作共用在途意图校验：`pending.path !== path` 的拦截语义与底座的单槽模型不同）。

## 验证

| 口径 | 结果 |
| --- | --- |
| `audit-web-state-ownership.py` / `audit-web-template-derivations.py` | `hard_violations=0`（E 类 13 为清单）/ `sites=0` |
| 新增用例 | `busyRegistry.test.ts` 5 条、`useConfirmedSubmit.test.ts` 8 条（含重试复用同键、4xx 清键、身份变化失效、卸载失效、两相区分） |
| `npm run lint` / `vue-tsc --noEmit` | exit 0 / 0 错误 |
| `vitest run` | 70 文件 / 308 用例全绿 |
| `vite build` | CSS 31.42 kB / gzip 5.65 kB **逐字节未变**；`index` 54.48→54.89 kB（gzip 20.81→20.94）、`AppChrome` 73.47→73.83 kB（gzip 29.02→29.15）——三个底座的体积代价 |
| 真实浏览器 | ARCH-11 的 117/117 一致，含控件总数/禁用数与按钮文案集逐路由相同（`busyRegistry` 的落点正在这一项上） |

## 复查过但决定不抽的 4 个候选

`RepairRoundPhysicalForm`、`RequisitionInventoryPicker`、`ImportRowBrowser`、`ProxyIntakeForm`。四者的重复只体现在**行数**上，不变量、字段来源与失败口径都不同；按技能口径（不为消除长度或转发而合并职责不同的代码）留为现状，本批只记录判断。文件长度信号在维修详情页与入库详情页仍然偏高，但它们的高长度来自状态机分支而非可抽的公共部分。

## 已知限制

1. `busyRegistry` 的类型并集（`BusyId`）与面板新增是**人工同步**的：漏登记只会让该面板不参与互斥，判据脚本咬不住。目前靠 `useBusy` 必须传 id 的写法 + 5 条用例约束。
2. `useConfirmedSubmit` 的单槽模型不覆盖「同一视图同时持有两个不同 path 的在途意图」；这类视图（入库详情）保留自造簿记，属有意未收口。
3. 真实幂等落库与重试往返由后端链路与 D6 系列批次证明，本批只在浏览器里验到可用性与文案（ARCH-11 限制 1）。
4. 本地验证，不替代远端 CI、正式 QA 与生产验收。

## 下一批

ARCH-11 已完成（真实浏览器逐路由渲染普查，117/117 一致）。ARCH-08～11 六批的本地收口至此结束；继续做的两个候选见 ARCH-11「下一批」：写入交互的可对比口径，以及 loan/异常/导入三域的行级渲染补齐。
