# ARCH-03 共享 HTTP 底座与域迁移（架构批第 3 批）

基线：`ARCH-02` `a2f812a`。本文既是迁移规格，也是本批记录：§一、§二 是底座的长期契约（后续批次与新增域 api 必须遵守），§四～§七 是本次实际落地与证据。

## 一、为什么只抽机制、不抽政策

`apps/web` 原有 12 个域 api 各写一份 fetch 包装，重复的是机制（取 CSRF 令牌、拼 `X-CSRF-Token`/`Idempotency-Key`/`If-Match-Version`、`credentials:"include"`、坏 JSON 不掩盖状态码、`fieldErrors` 只留字符串值），**不重复的是政策**（错误类形状、状态码到中文文案的映射、兜底文案本身）。UI-19/20/21 三批刚把用户可见文案收口过，本批不得顺手改任何文案。所以 `src/api/http.ts` 提供两层：

- 原语（供 FormData/二进制类域直接组合）：`apiUrl`、`sessionCsrfToken`、`newIdempotencyKey`、`mutationHeaders`、`fileForm`、`parseFailure`、`readJson`、`Page<T>`。
- 门面 `requestJson<T, E>(path, profile, init)`：把上述原语串成一次 JSON 请求，域只提供 `Profile<E>`。

`Profile<E>` 三件：`codeFallback`（域兜底码，如 `LOAN_REQUEST_FAILED`）、`failure(failure) => E`、`csrfMissing() => E`。
`RequestInit`：`method`（默认 GET）、`prefix`（`/api/v1` 之后的域路径）、`json`、`form`、`key`、`version`、`noStore`。

## 二、迁移硬约束（逐条可验证）

1. **导出符号集合不变**：函数名、参数位置与类型、返回类型一律不改。视图与测试的 import 不得需要修改。
2. **请求头政策不变**：
   - GET：`headers` 为空对象，不带 CSRF/幂等键/`If-Match-Version`。
   - 写操作：`X-CSRF-Token` 必有；`Idempotency-Key` 原本用调用方 `mutation.key` 的**必须继续用原键**（这是「丢失应答后用同一键重试」不变量，`RepairFactoryPanel.test.ts` 等直接断言）；原本用 `crypto.randomUUID()` 的继续新建。
   - `If-Match-Version`：仅当版本有定义时才发（asset/inbound 是可选参数；loan/requisition/review/repair 是必填）。
   - `Content-Type: application/json`：仅当 JSON 体存在时；`FormData` 一律不设置。
   - `cache: "no-store"`：仅原本就带的域（`inventory-review/photos.ts`、`attachment`）保留，**不得**扩散到 GET 列表域，那会改掉 HTTP 缓存语义。
3. **错误类与文案不变**：`AssetApiError`/`LoanApiError`/`RequisitionApiError`/`ReviewApiError`/`RepairApiError` 的构造参数、`super()` 取什么、状态码→中文文案映射函数全部原样留在域内。
4. **`Failure` 三字段的用法**：`code` 恒非空（服务端合规码或域兜底码）；`serverCode` 仅在服务端给出合规码时非空，专供 inbound/asset 这类「无 message 就显示错误码」的既有口径；`message` 为服务端非空 message 或 null。
   收紧说明：`serverCode` 经 `/^[A-Z][A-Z0-9_]{0,63}$/` 合形过滤（原 asset/inbound/repair/review 不过滤）。全仓对 `error.code` 的比较值均为大写蛇形，已实测确认，故此收紧不改变任何现有分支走向，只阻止不合形的服务端串漏进中文 UI。
5. **不改视图**（除第 5 项专职任务：5 处裸 `fetch()` 预签名下载改走原语）。
6. **不碰** `src/api/http.ts`、已迁移的 `inbound|loan|repair/api.ts`、`auth/api.ts`（登录/登出无会话态，走自己的 `requestJson`，是**有意的例外**，不得为凑统一而并入）、任何配置与 CSS。
7. 每域迁移后必须跑：`rtk env PATH="/Users/lixd/.nvm/versions/node/v24.18.0/bin:$PATH" npx vue-tsc --noEmit` 与该域相关 `npx vitest run <pattern>`；**不得修改测试断言来让它通过**——若断言失败，说明请求头或错误形状变了，改回实现。

## 三、已完成的样板（照此办理）

| 域 | 变体 | 迁移后 |
| --- | --- | --- |
| `inbound/api.ts` | 位置参数 `(path, method, body?, version?)`，错误为裸 `Error`，幂等键每次新建 | `profile` + `request()` 一行委托 |
| `loan/api.ts` | `mutation?` 对象、POST-only、原键复用、`code` 合形过滤、`LoanApiError(status, code)` | 同上，`json: mutation.body` |
| `repair/api.ts` | 带 `file` 的 FormData 分支 + `fieldErrors` | `form: fileForm(mutation.file)` 与 `json` 二选一 |

## 四、范围（实际落地）

新增 `apps/web/src/api/http.ts`（131 行）与 `apps/web/src/api/http.test.ts`（12 用例）。底座导出：`Page<T>`、`apiUrl`、`sessionCsrfToken`、`newIdempotencyKey`、`mutationHeaders`、`fileForm`、`parseFailure`、`readJson`、`requestBlob`、`requestJson<T,E>`。

| 域 | 迁移方式 | 说明 |
| --- | --- | --- |
| `inbound`、`loan`、`repair/api`、`asset/api`、`requisition/api`、`proxy/api`、`repair/factory-api`、`repair/collaboration`、`inventory-review/photos`（键生成） | `requestJson` + 各自 `Profile<E>` | 共 8 域的 CSRF/幂等/版本/错误解析全部交到底座 |
| `inventory-review/api` | 原语组合 | 本域写操作即使无请求体也声明 `Content-Type: application/json`（`photos.ts` 的 download-grant 是真用例，被 `photos.test.ts` 断言），与门面的「有体才声明」不同，故不强行套门面 |
| `assetimport/api` | 原语组合 | ①错误类是 `AuthApiError(status, code, messages[code], requestId)`，`Failure` 不承载 `requestId`；②后端只在 `POST /asset-import-batches` 声明 `@RequestHeader("Idempotency-Key")`（`AssetImportController.java:53`），其余 5 类写操作历史上不发幂等键，而门面对非 GET 必发 |
| `attachment/api` | 原语组合 | 同上两条：下载 grant 只带 `X-CSRF-Token`；presigned 内容读取需要在 fetch 与状态码检查之间穿插 `isCurrent()` 活性门，套门面会改变检查次序 |
| `auth/api` | **不动** | 登录/改密/登出发生在会话建立前，令牌由响应体自带，与本底座的语义不同，属有意例外 |

视图层收口：`RepairFactoryPanel`、`RepairTechnicalEvidence` 的裸 fetch 改为 `factoryEvidenceContent` / `collaborationEvidenceContent`，`RepairDetailView`、`RepairInventoryPhoto` 改为 `repairPhotoContent` / `repairEvidencePhotoContent`；`requestBlob` 明确「不重新拼装 URL，只请求调用方已校验过的地址」，保留原有的「校验什么就请求什么」安全次序。`assetimport` 里 3 份逐字重复的「blob→createObjectURL→a.download→revoke」也归一为 `downloadBlob(path, filename)`。

## 五、验证

| 口径 | 结果 |
| --- | --- |
| fetch 调用点 | **40 处 / 17 文件 → 15 处 / 6 文件**；`views/*.vue` 内裸 fetch **0 处**（改前 5 处） |
| 行数 | 14 个业务文件 + 2 个新文件；域文件净 −56 行（189 增 / 245 删，不含 http.ts 与其测试） |
| `vitest run` | **65 文件 / 276 用例全绿**（改前 64/264；净 +1 文件 +12 用例即底座契约测试，原 264 条断言一字未改） |
| 请求头不变量 | `RepairFactoryPanel.test`、`Inventory*Panel.test` 等对 `X-CSRF-Token`/`If-Match-Version` 与「丢失应答后同键同体重试」的直接断言全部通过；`attachment/api.test.ts` 的 `toEqual` 精确头集合通过 |
| `vue-tsc --noEmit` | 0 错误；`npm run lint`（含 `.vue`，`--max-warnings=0`）exit 0 |
| `vite build` | CSS `31.42/gzip 5.65 kB` 不变；首屏 `index 54.24/gzip 20.66 kB`（+0.09 kB 原始 / +0.02 kB gzip，底座并入入口块）、`AppChrome 73.45/29.01 kB` 不变 |
| 浏览器实测（新底座真链路） | dev server + 页面内 fetch 捕获桩，不接后端：GET `/inbound` 列表实际发出头集合为**空**、两行入库单渲染成功；POST `/inbound-receipts/r1/claim` 实际发出 `Idempotency-Key,If-Match-Version,X-CSRF-Token` 三键且幂等键为新 UUID；把 `session.csrfToken` 置空后再发同一写操作，抛域文案「会话已失效，请重新登录」且**网络请求数为 0** |

## 六、已知限制

1. **未做到「全站只有一个 fetch 调用点」**：`assetimport`、`attachment` 仍在 9 处自持 fetch（原因如上表，两处政策门面表达不了）。要彻底闭合需给 `Failure` 增 `requestId`、并把幂等键从「非 GET 即发」改为「调用方给了才发」——那是一次会波及 8 个已迁域的策略改动，留作独立批次（暂定 ARCH-03b），不在本批夹带。
2. **错误码合形收紧是本批唯一的行为面变化**：`serverCode` 经 `/^[A-Z][A-Z0-9_]{0,63}$/` 过滤，原先 asset/inbound/repair/review/factory/collaboration 不过滤。已实测全仓对 `error.code` 的比较值均为大写蛇形，故不改变任何现有分支走向；差异仅在服务端返回不合形 code 且无 message 的畸形响应下——原来把畸形串直接交给用户，现在回落域兜底。
3. `readJson` 对 204 返回 `undefined`（原实现会在 `response.json()` 上抛）。已确认无端点以 204 应答、亦无测试覆盖 204。
4. `requisitionRequest` 原先按「有无 mutation」决定写头，底座按「method 是否 GET」决定；`GET + mutation` 与「非 GET 且无 mutation」两种组合在改前后不同，但全仓 18 处调用点均不属于这两种（已核 `ItemTargetChangeView` 与 `loadRequisitionCatalogs`）。
5. 验证仍是单测 + 构建 + 桩化浏览器实测，未接真实后端与真实短信；不替代远端 CI、正式 QA 与生产验收。GitNexus MCP 本会话未连接，影响面以全仓引用普查 + vue-tsc 代替 `impact`。

## 七、下一批

ARCH-04：`useResourceList` / `useAsyncAction` 两个聚焦 composable（含迟到响应取消与 loading/error 归位），先迁移 6 个简单列表视图，不碰盘点/返厂带 lost-committed-reply 不变量的面板。

