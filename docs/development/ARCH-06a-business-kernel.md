# ARCH-06a 拆出跨域写请求内核（features 切片第 1 步）

基线：`ARCH-07` `d5b1522`。用户已选定 ARCH-06 口径为**完整垂直切片（含拆内核）+ 域内再分 `views/`、`panels/`**。本步先解决切片的前置阻塞：跨域寄生模块。模板与 CSS 一字未改，构建 CSS 仍 `31.42 kB / gzip 5.65 kB`。

## 为什么必须先拆

`loan/api.ts` 名义上是借用域的 api，实际被 **6 个业务域的 49 个文件**引用，且被引用的主力不是借用逻辑而是通用件：`loanRequest` 37 个使用者、`loanMessage` 30 个、`LoanMutation` 25 个、`LoanApiError` 11 个——它们是「POST + 调用方持有的 `Idempotency-Key` + `If-Match-Version`」这套乐观并发写请求的通用实现，借用、调拨、领用、退还退库、入库、盘点、维修、代确认全都在用。不把它独立出来，`features/<domain>/api` 的垂直切片就只能让 6 个 feature 反向 import `features/loan/api`，切片名不副实。

## 范围

### 一、新增 `src/api/business.ts`（内核）

导出 `BusinessMutation`、`BusinessApiError`、`businessRequest`、`businessMessage`，实现与请求头政策**逐字沿用原 loan 内核**（仍走 ARCH-03 的 `requestJson`，`method: "POST"`、调用方原键、版本必发）。

符号改名对照：`loanRequest→businessRequest`、`loanMessage→businessMessage`、`LoanApiError→BusinessApiError`、`LoanMutation→BusinessMutation`。

唯一的行为面变化：`codeFallback` 由 `LOAN_REQUEST_FAILED` 改为 `BUSINESS_REQUEST_FAILED`。已实测该串在全仓仅出现在这一处定义，无任何比较分支；`businessMessage` 只按 `status` 出中文文案、从不使用 code，故用户可见文案零变化。

### 二、`src/loan/api.ts` 瘦身为纯字典模块

保留四份中文字典（`loanLabels`、`transferLabels`、`reminderLabels`、`inspectionLabels`）与其来源口径，删除全部请求实现。**本步不动这些字典的归属**：`transferLabels` 实际属调拨域、`inspectionLabels` 实际属退还退库域，它们的归位与 06b 的 feature 目录一起发生，避免同一份字典被移动两次。

### 三、49 个使用者的引用改写（一次性 codemod，脚本不入库）

- 四个符号做词边界全量替换（改后全仓旧名残留 0 处）。
- `import { … } from "@/loan/api"` 按说明符拆分：内核说明符换到 `@/api/business`，字典说明符留在 `@/loan/api`；两类同时存在时拆成两行导入。`import type { … }` 与多行花括号写法一并覆盖。
- `vi.mock("@/loan/api", …)` 工厂只桩内核件（`loanRequest`/`loanMessage`/`LoanApiError`）的行整体换路径，含 `importOriginal<typeof import("@/loan/api")>()` 里的模块串；纯字典 mock 保持原路径。改后仍有 7 个文件引用 `@/loan/api`，全部是字典使用者，与预期一致。

## 验证

| 口径 | 结果 |
| --- | --- |
| `vitest run` | **66 文件 / 285 用例全绿，未改任何断言**。这一条同时是 mock 路径正确性的证明：若某处 `vi.mock` 路径没跟着换，被测代码就会打到真实内核模块而当场失败 |
| `vue-tsc --noEmit` | 0 错误（模块解析与拆分后的导入组合全部成立） |
| `npm run lint` | 含 `.vue`、`--max-warnings=0`，exit 0 |
| `vite build` | CSS 不变；入口 `index` 54.40→54.44 kB（gzip 20.77→20.78），共享块 `AppChrome` 73.47 kB 不变 |
| 改动面 | 49 个跟踪文件 +203/−223（净 −20 行：内核从 loan 模块独立后，借用域模块只剩字典） |
| 旧符号残留 | `loanRequest`/`loanMessage`/`LoanApiError`/`LoanMutation` 全仓 **0** 处 |

## 已知限制

1. 四份字典的域归属尚未纠正（`transferLabels`、`inspectionLabels` 仍暂居 `src/loan/api.ts`），在 06b 随 feature 目录归位；因此本步结束后「借用域模块里含有非借用字典」这一状态仍存在，只是请求内核已解耦。
2. `src/api/business.ts` 与 `src/api/http.ts` 同层，是「按关注点分层」的内核位置；若 06b 之后确认所有域内 api 都进 `features/`，这两个文件将是唯一的 `src/api/` 常驻成员，需在 06b 记录里说明该例外。
3. `proxy/api.ts` 仍复用内核错误类（原注释「不在本批修正跨域错误类复用」现指向 `BusinessApiError`，语义不变），其自身 `codeFallback` 保持 `ATTACHMENT_REQUEST_FAILED` 未动。
4. 验证为单测 + 构建，未接真实后端；不替代远端 CI、正式 QA 与生产验收。

## 下一步（06b）

按选定口径移动目录：`src/features/<domain>/{views,panels}/`（api 归 `src/features/<domain>/api.ts`），同步改 router 的 35 条懒加载路径、66 个测试文件路径、12 处同域相对兄弟导入（跨 views/panels 子目录后需改为显式路径），并把 `transferLabels`/`inspectionLabels` 等字典归入所属域模块。
