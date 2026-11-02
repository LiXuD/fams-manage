# ARCH-05 primitive 状态归位 shallowRef 与转派工作流抽取（架构批第 5 批）

基线：`ARCH-04` `c2c643e`。本批落技能 `reactivity.md` 的第一条 task item（primitive 一律 `shallowRef`）与「按职责抽共享不变量」。模板与 CSS 一字未改：构建 CSS 仍 `31.42 kB / gzip 5.65 kB`。

## 范围

### 一、primitive 状态归位（467 处 / 59 文件）

改前全仓生产代码 `shallowRef` 使用者为 0，而 `ref("")`、`ref(false)`、`ref(0)` 这类 primitive 声明有 **467 处 / 59 个文件**（技能要求 primitive 用 `shallowRef`，避免无意义的深代理）。

- 用一次性 codemod 完成（脚本不入库），**只匹配可证明安全的形状**：`ref(""` / `false` / `true` / `0` / `1` / `2` / `null)` 之外的对象型初值一律不动，`ref<string>`、`ref<number>`、`ref<boolean>` 显式标注的也一并转换。
- 同批修正 59 个文件的 vue import：加入 `shallowRef`（按字母序插入），`ref` 不再使用者移除——`noUnusedLocals` 与 `vue-tsc` 会立刻暴露漏改，因此「改漏」不是静默风险。
- **刻意排除 `ref(null)`**：初值为 null 的 ref 后续可能被赋对象，再就地 mutate 其字段时 `shallowRef` 会失去跟踪，属需逐点判断的一类，本批不做。
- 正确性证明是类型检查本身：primitive 初值若在某处被就地改属性或被赋对象，`vue-tsc` 必报错；实际 0 错误。
- 归位后剩余 `ref(...)` 站点 181 处，全部是对象/数组/联合 null 状态，需按「是否就地 mutate」逐点判断，留待后续批次。

### 二、四个受控转派组件的工作流抽取

四个组件（入库单、调拨、领用申请、退库单）的 `<script>` 除三处外**逐字相同**：端点集合名、明细字段映射、明细字典与展示标记。共享的是流程不变量——单据切换时作废旧在途、`generation` 抑制迟到响应、以及「丢失应答后以同一 `Idempotency-Key` 与同一请求体重试」。

新增 `src/composables/useReassignmentFlow.ts`（83 行）拥有这套时序；四个组件保留各自的明细类型、字典与模板，脚本从 46–47 行降到 15 行，四文件加底座由 313 行变 265 行（净 −48）。域侧只提供 `collection` 与 `mapItems`：

| 组件 | collection | 明细映射差异 |
| --- | --- | --- |
| `InboundReassignment` | `inbound-receipts` | `status/assetVersion/lockVersion` |
| `TransferReassignment` | `transfers` | `status: item.itemStatus`，**不带** `assetVersion` |
| `RequisitionReassignment` | `requisitions` | 同入库 |
| `AssetReturnReassignment` | `asset-returns` | 同入库 |

校验提示「请选择接任库管，填写原因及交接凭证，并核对未同步结果。」四域**逐字相同**，故随工作流收进 composable，成为单一来源。

`snapshot` 由 `ref` 改为 `shallowRef`：它只整体替换、明细在模板里只读，且这同时消掉了 `ref` 深解包带来的 `UnwrapRefSimple<TItem>` 与 `mapItems` 参数类型不兼容（本批实际撞到并由 vue-tsc 报错，改为 `shallowRef` 后类型自然对齐）。

### 三、未按原批描述执行的一处（有意）

批描述写的是「4 个 Reassignment 合并为 1 个组件（props + slot）」。执行中发现四者的明细展示是真差异：入库与领用/退库逐项显示资产编码、仓库/位置、锁版本（锁版本 <0 显示「已释放」），调拨则用 `fams-timeline` 显示去向与交出凭证。合并成单组件后仍需 4 个调用点各自传入明细渲染，等于用 slot 转发换掉 15 行脚本，按技能「合并只在边界无有用职责时进行」的判据不划算，故改为只抽工作流 composable、保留 4 个域组件。若要再进一步，可评估的形态是「共享表单体 + 明细 slot」，那是结构改动，留待单独决定。

## 验证

| 口径 | 结果 |
| --- | --- |
| primitive `ref` 残留 | **0**（改前 467 处 / 59 文件） |
| `vitest run` | **66 文件 / 285 用例全绿**，与 ARCH-04 同一集合，**未新增、未修改任何断言** |
| 转派关键不变量 | 4 个组件各自的原测试通过：表单未勾稽时 `writes` 为 0、加载后按同一路径与版本提交、丢失应答后点「原请求重试转派」产生 `writes[1] toEqual writes[0]`（同键同体）、载荷明细映射逐字不变、`saved` 事件次数不变 |
| `vue-tsc --noEmit` | 0 错误（同时作为「primitive 不会被就地 mutate / 赋对象」的机器证明） |
| `npm run lint` | 含 `.vue`、`--max-warnings=0`，exit 0 |
| `vite build` | CSS 不变；入口 `index` 54.35→54.40 kB（gzip 20.71→20.77），共享块 `AppChrome` 73.46→73.47 kB |
| 文件与行 | 60 个跟踪文件 +372/−420（净 −48 行），另有 3 个新文件（`useReassignmentFlow.ts` 83 行；ARCH-04 的两个 composable 未再改动） |

## 已知限制

1. 剩余 181 处 `ref(...)` 为对象/数组/nullable 状态，未逐点判定是否可 `shallowRef`；codemod 有意只吃 primitive 形状，不做「看起来像」的批处理。
2. `useReassignmentFlow` 未加独立测试文件——它的行为由 4 个组件的原有端到端测试逐字覆盖（含同键重试与载荷断言），且这 4 个测试在抽取前后一字未改；若后续再有第五个转派域接入，应补一份 composable 自身的边界测试。
3. 校验提示收进 composable 改变了该文案的**所在文件**（未改文案内容），若产品要求四域文案分化，需在 composable 开一个 `validateMessage` 入参——现在没有该需求，故不加。
4. 本批未跑 CDP 三口径与浏览器实测：模板与 CSS 零改动，视图测试与构建体积可比对；仍不替代远端 CI、正式 QA 与生产验收。

## 下一批

ARCH-07（提前于 ARCH-06）：把 `apps/web` 里 10+ 处 `../../../../packages/api-types/src/<domain>` 四层相对深引用换成 `@fams/api-types` 包说明符，并核对该包是否需要构建产物与 workspace 依赖声明。之后 ARCH-06 再做 `features/` 目录切片，使大面积文件移动的 diff 不与逻辑改动混批。
