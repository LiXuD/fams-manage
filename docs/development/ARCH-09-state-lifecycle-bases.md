# ARCH-09 状态所有权与异步生命周期收口（架构批第 9 批）

基线：`ARCH-08` `fb526da`。判据来自 `vue-best-practices` 的三块：reactivity「Use `shallowRef` for primitive state」、composables「请求生命周期与清理归位」、reactivity「Clean up async effects for watchers」。

## 落判据

新增 `scripts/audit-web-state-ownership.py`（100 行）。A/B/C/D 为硬违规（退出码 1），E 只作清单：

| 规则 | 内容 | `fb526da` | 本批后 |
| --- | --- | --- | --- |
| A | primitive 状态用 `ref("")` / `ref(false)` / `ref(0)` | 10 | **0** |
| B | DOM/子组件引用自持 `ref<HTMLXxx \| null>(null)` 而不用 `useTemplateRef` | 2 | **0** |
| C | `watch(...)` 回调里有 `await` 却无 `onCleanup` / 迟到抑制 | 3 | **0** |
| D | 自造读取守卫（`let generation/epoch/live/turn = 0` 或读形异步函数裸写 `.value`）不用底座 | 45 | **0** |
| E | 视图自持 `busy + error + refresh` 三件套（**清单，不判失败**） | 13 | 13 |
| 合计硬违规 | | **60** | **0** |

C 与 D 的边界经一次收窄：最初把 `submit/decide` 这类一次性写流程也计入违规，但技能口径只要求**读取路径**可取消——写回结果由域内版本核对兜住，读取迟抑制由底座统一给。收窄后的判据只咬两类：watcher 内 `await`，以及 `load*/refresh/open/read*/inspect/list/search/preview*/catalog/paginate/choose*/fetchState` 这类读形函数。

## 更正先前判断（两处，都影响改动量）

1. 我一度按「primitive 一律 `shallowRef`」统计出 **119 处**待改。重读 `reactivity.md` 后确认口径是反的：**整体替换的值应当用 `ref()`**（`ref()` 用于「常被整体换新」的对象）。因此本批不把对象/数组改成 `shallowRef`，A 规则只收 primitive，真实残留 **10** 处。若不纠正，会为了对齐一条被误读的规则改坏 100 多个文件的语义。
2. `FamsMediaPreview.vue` 的 dialog 引用改用 `useTemplateRef` 时发现：`onMounted` 里的 `element.showModal` 与 jsdom 兜底分支必须留在组件里（测试环境无 `showModal`），底座只接管引用与生命周期顺序，不接管 UA 行为差异。

## 抽出的两个底座

**`composables/useRequestLiveness.ts`** —— `{ begin(): LivenessToken, isCurrent(token), invalidate() }`。私有 `sequence` 与 `live` 标志；身份回调在 `begin()` 时捕获、检查时再取值，因此「同一实例重复请求」与「身份换了（切单据/卸载）」被区分对待；`onBeforeUnmount` 置 `live = false` 并 `invalidate()`。采纳 **49** 个视图/面板（`grep -rl useRequestLiveness --include='*.vue'` = 49）。

**`composables/useObjectUrlPreview.ts`** —— `url / name / mime` 暴露为 **readonly getter** 而不是 ref：`<script setup>` 的模板不会对嵌套 ref 自动解包，返回嵌套 ref 会让模板拿到对象本身。动作 `show(blob, filename?)`、`close()`、`beginDownload(): AbortSignal`；私有 `controller` 与 `objectUrl`，卸载时 revoke + abort。采纳 **5** 处，替换掉四处各写一遍的 `createObjectURL / revokeObjectURL / abort` 簿记。

## 分页底座的封装收口

`usePagedResourceList` 的 `page` 改为 `Readonly<Ref<number>>`，并补一个动作：

```ts
function restart(): Promise<void> { page.value = 1; return load.refresh(); }
```

三个视图原本在模板里直接 `page++` / `page = 1; load()`，封装一收紧就断了。vitest 抓到断言失败（`expected [2,'',''] received [1,...]`），处理方式是把调用方接到 `pages(±1) / restart()` 上，而不是回退封装；随后清掉因此未使用的 `load` 别名（TS6133）。

## 验证

| 口径 | 结果 |
| --- | --- |
| `audit-web-state-ownership.py` | `hard_violations=0 lifecycle_shape_review=13` |
| 新增用例 | `useRequestLiveness.test.ts` 5 条（同实例重入、身份切换、卸载失效、token 顺序、invalidate 后旧 token 全部不 current）、`useObjectUrlPreview.test.ts` 5 条（含 revoke 与 abort 成对） |
| `npm run lint` / `vue-tsc --noEmit` | exit 0 / 0 错误 |
| `vitest run` | 70 文件 / 308 用例全绿 |
| `vite build` | CSS 31.42 kB / gzip 5.65（与 ARCH-07 逐字节同）；`index` 54.89 / gzip 20.94；`AppChrome` 73.83 / gzip 29.15 |
| diff | 6e20bff 57 文件 +955/−558；c86b448 7 文件 +192/−56 |
| 真实浏览器 | ARCH-11 的 117/117 一致 |

## 保留不合规（E 类 13 处，逐文件判断后保留）

`AssetDraftView`、`AssetReturnDetailView`、`AssetReturnDraftView`、`BusinessResultNoticeView`、`BusinessResultReviewView`、`InventoryReviewPanel`、`InventoryTaskDetailView`、`InventoryTaskDraftView`、`LoanDetailView`、`LoanView`、`RequisitionDetailView`、`RequisitionDraftView`、`TransferTargetChangeView`。

保留理由：它们的 `busy + error` 服务于**一次性写流程**而非读取列表，形状与 `useResourceLoad` 不同（无 `data`、无 refresh 语义、错误文案属域内政策）。E 规则刻意只作清单，是为了让「形状是否真的相同」这件事留在人工判断里，而不是被脚本逼成形式相同的两套实现。

## 已知限制

1. D 规则用函数名前缀识别「读形」，命名不合前缀约定的读取函数会漏；本批先按现有 49 个视图的命名收口，新写的读取路径若命名随意，判据咬不住。
2. `useRequestLiveness` 只管迟到抑制，不管请求去重与缓存；同一视图并发同类请求仍会各发一次。
3. 单测 + 构建 + 判据 + 本地真实浏览器，不替代远端 CI、正式 QA 与生产验收。

## 下一批

ARCH-10：组件边界——把维修域 7 个面板的 `blocked` props + `busyChange` emits（O(N²) 父子连线）换成 typed provide/inject 的页内互斥登记表，把调拨的目录装配与目标构造抽成纯函数，再把 28 处各写一遍的「确认式幂等提交」簿记抽成 `useConfirmedSubmit`。
