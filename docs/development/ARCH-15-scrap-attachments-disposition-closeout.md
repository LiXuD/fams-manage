# ARCH-15 报废附件保护、实际处置结案与超期清单前端按 ARCH 标准收口（架构批第 15 批）

基线：`0052735`（把 `codex/d6-21-loss` 的 D6-22A「受保护附件—实际处置结案—按业务日期的超期清单」三段
增量合入架构线，冲突全部按 `features/<域>/{api,views,panels}` 形状落地；改造提交 `2529c3b`）。判据沿用
本分支已建立的口径，不新立标准：

- `vue-best-practices` + ARCH-08：模板不得计算，派生一律归 `computed` 或行显示模型；判据
  `scripts/audit-web-template-derivations.py`。
- ARCH-09：primitive 状态用 `shallowRef`，整份替换的集合保持 `ref`，迟到抑制归 `useRequestLiveness`；
  判据 `scripts/audit-web-state-ownership.py`。
- ARCH-10：确认式幂等提交用 `useConfirmedSubmit`，幂等键只归提交底座；判据同上（D 类）。
- ARCH-03/07 + ARCH-09 底座：域内 HTTP 走 `api/http` 的 `requestJson`/`requestBlob`，`.vue` 内不得裸
  `fetch`；object URL 簿记归 `useObjectUrlPreview`；组件级原始 CSS 归位 `fams-*` 令牌词表。

## 量级

| 文件 | 合并基线 | 本批改后 | 主要变化 |
| --- | --- | --- | --- |
| `features/scrap/api.ts` | 86 行 | 113 行 | 两段裸 `fetch` → `requestJson`/`requestBlob`；`ScrapIntent` 补 `file` 并拆出 `ScrapSubmission`；新增 `ScrapListResult`、`SCRAP_ATTACHMENT_MIME`、`scrapAttachmentSizeLimit` |
| `features/scrap/views/ScrapView.vue` | 337 行 | 462 行 | 三底座采纳 + 13 处模板派生归位 + 30 处 primitive 归 `shallowRef` + 词表迁移（含裸色值 `#d9e0e8` 的 392 字节 scoped CSS 归零） |
| `features/scrap/views/ScrapView.test.ts` | 231 行 | 231 行 | 本批一字未改（合并批按重命名自动合并，含功能线新增 4 条用例） |

合并提交 `0052735` 全仓 25 文件 +2,862/−296，其中 `apps/web` 3 文件 +445/−207；本批改造提交 2 文件
+372/−220。

## 1. 三段新形状的功能事实全部取自功能线

候选 `6134e96` 相对上一轮接续基线 `38d95fc` 是 7 个提交（`3524539`/`6b5cde8` 附件保护与双端页面、
`ec351d5` 双端受保护附件接入、`547b67f` 重启证据、`135087f` 带核实证据的实际处置结案、`9d48e97` 按
业务日期的超期待处置清单、`6134e96` 超期实测记录）。后端、契约、小程序与 D6-22 证据文件直接取功能线，
本批只动 `apps/web`。三段新形状是：

1. 附件段：`GET /attachment-capabilities` 给 `canRead/canUploadRequest/canUploadDisposition/
   requestVersion/dispositionVersion` 五位；两类列表 `attachments/{request|disposition}`；上传走
   multipart + `If-Match-Version` + 幂等键；原件读取走 `download-grant` → 授权地址 → 无缓存读回。
2. 实际处置结案段：`actions.dispose` 门禁的「登记实际处置」fieldset，冻结 `requestVersion`、
   `ownershipVersion`、`disposition.version` 三个独立版本，凭证只能取本次已核对到的处置附件 object id。
3. 超期清单段：列表作用域在 `?page` 与 `/dispositions/overdue?page` 之间切换，读 `canListOverdue
   Dispositions` 决定是否给开关、读 `businessDate` 与 `total` 装配「业务日期 …，已超期 N 单」。

## 2. 域内 HTTP：两段裸 fetch 收进底座

`scrapRequest` 原来是视图内自持的 `fetch → ok? → json()`，含 CSRF 头、幂等键头、可选
`If-Match-Version`、multipart 分支与不合形的错误码直读；现与其余 12 个域同形，机械序列交给
`api/http.requestJson`，本域只保留 `PREFIX`、`ScrapApiError` 与 `Profile`（兜底码仍是
`SCRAP_REQUEST_FAILED`，缺令牌仍是 `401 AUTH_SESSION_INVALID`）。带文件时改传 `form`，不带文件时传
`json`，与 `features/loss/api.ts` 同形。

`scrapOriginal` 的 grant→content 序列拆成两段：grant 走 `requestJson`（POST，底座自动带 CSRF 与幂等键，
不发 `If-Match-Version`，与功能线一致），content 走 `requestBlob`（同源会话、`cache: no-store`）。
UUID 身份校验、授权地址前缀与令牌合形校验、媒体类型/归属/体积比对属域内政策，留在域函数；活性判断
仍由视图传入回调，底座不接管。

两处口径差异如实记录，均不影响用户可见文案：

- 授权地址前缀比对改用 `apiUrl(path, PREFIX)` 而不是手拼 `/api/v1/scrap-requests`，比较结果与功能线
  逐字相同（本批改前一处真实回归：先用域内相对路径比对导致 `startsWith` 恒假、预览不出现，由
  `ScrapView.test.ts` 的「discards a late attachment response…」抓到）。
- grant 与 content 的失败码由功能线的固定串（`SCRAP_ATTACHMENT_GRANT_FAILED` /
  `SCRAP_ATTACHMENT_READ_FAILED`）改为底座口径：content 仍固定 `SCRAP_ATTACHMENT_READ_FAILED`，
  grant 走 `parseFailure` 的服务端合形码或本域兜底码。视图只按 `status` 分支（401 复位、其余落同一句
  文案），因此该差异对用户不可见。

上传的 multipart 刻意不套 `fileForm`：`fileForm` 会显式带上 `filename`，UA 因此新建一个 `File` 实例，
而报废上传的「重试复用同一份原件」要求同一次 FormData 里仍是调用方那一个 `File`（功能线用例逐字断言
`toBe(file)`）。不传 `filename` 时默认名即 `file.name`，线上字节与 `Content-Disposition` 完全相同。

## 3. 迟到抑制与状态形状

功能线在视图里自持 `let generation = 0, live = true` 与 `current(epoch, owner)`，整体替换为
`useRequestLiveness(() => [identity.value])` 一个实例：`load`、`loadAssets`、`catalog`、
`loadAttachments`、`openAttachment` 五条读取在功能线里本就共用同一个自持计数与同一个互斥位 `busy`，
换成底座是等价替换而不是行为改变（与 ARCH-13 的「三组互不相干槽位不能共用一代序号」情形不同）。
`loadAttachments(row, turn)` 沿用调用方已取的令牌，不新起计数。

30 处 primitive 由 `ref` 归 `shallowRef`（新增 `overdueOnly/canListOverdue/businessDate/recipient`），
`rows/detail/assets/places/people/capabilities/attachments/dispositionEvidence` 八个整份替换或集合值
保持深跟踪 `ref`（后两者与 `LossRecoveryPanel` 的 `locations` 同形；`dispositionEvidence` 由复选框
`v-model` 增删，必须深跟踪）。预览簿记交 `useObjectUrlPreview`：`url/name/mime` 三个只读 getter 取代
功能线的 `preview` 对象 ref，`close()` 统一负责 abort 与 `revokeObjectURL`，卸载收尾由底座承担。

`ScrapIntent` 的 `file` 字段留在意图形状里，但幂等键仍由 `useConfirmedSubmit` 持有：上传与结案都走
同一份草稿/在途两槽，`pickAttachment` 只 `draft(...)`，确认后才 `send()`，重试不换键、4xx 清键的
判据与既有五段动作共用同一条 `cause instanceof ScrapApiError && 400 <= status < 500`。

## 4. 模板派生归位：13 → 0

判据在合并基线命中 1 文件/13 处，全部在 `ScrapView.vue`；本批把这 13 处归位到 1 个行显示字段与 10 个新
`computed`（`completionText`、`requestsTitle`、`overdueToggleLabel`、`overdueSummaryText`、
`showOverdueEmptyNote`、`attachmentSections`、`dispositionFiles`、`showDispositionEmptyNote`、
`dispositionDisabled`、`previewImageShown`），另把 `dispositionText` 的映射补全：

- 行显示模型：`requestRows` 在既有 `stateText` 之外补 `dueText`（原模板做 `计划处置 {{ row.disposition
  ?.due_date }}` 的装配）。
- 判据/绑定型 computed：`dispositionDisabled`（原 `locked || stale || !capabilities?.canRead`）、
  `previewImageShown`（原 `preview.mime !== 'application/pdf'`）、`showDispositionEmptyNote`
  （原 `attachments.disposition.length === 0`）。
- 文本与开关型 computed：`requestsTitle`、`overdueToggleLabel`、`overdueSummaryText`、
  `showOverdueEmptyNote`、`completionText`（原模板四段装配）、`dispositionText` 补
  `DISPOSED → 已处置` 映射（功能线模板里的三元链）。
- 两类附件的段落差异（标题、上传位、列表来源）原先靠模板里按 `kind` 做三元与 `===` 判断，现收成
  `attachmentSections` 一处装配（`kind/label/uploadLabel/canUpload/items` 五个字段），模板只迭代具名
  段落，`v-for` 与 `v-if` 都不再含判据。

一处刻意的可见形状变化（与 ARCH-12/14 同口径，不是文案来源改动）：原件预览段由功能线的
`<section aria-label="报废附件原件"> + <img class="scrap-preview"> + <a> + <button>` 改为挂
`FamsMediaPreview`（与其余 11 个域的原件预览同形），文案「下载原件 …」与「关闭报废原件」逐字保留。
合并基线自带的 `<h1>报废与处置</h1>` 与外层 `<section class="scrap-page">` 延续 ARCH-14 的处置：
页头由 `AppChrome` 按路由 `meta.uiGroup/uiTitle` 单点渲染，`uiDesc` 仍不给（功能线没写过这段描述）。

## 5. 门禁与真实浏览器实测

四道门禁（Node 24.18.0）：根 `npm run lint --max-warnings=0` 退出 0；`vue-tsc --noEmit` 0 错误；
`vitest run` **81 文件 / 361 用例全绿**（合并基线同为 81/361，即功能线新增的 4 条报废用例在改造前后
都通过且断言一字未改；上一批 ARCH-14 为 81/357）；`vite build` 通过。主样式表 `index-CvDOmE8w.css`
31.42 kB / gzip 5.65 kB **文件名与字节均未变化**（本批没有往令牌层加规则），`dist/assets` 的 CSS 产物
从两份收成一份——合并基线自带的 `ScrapView-qftm0Zzb.css` 392 字节 / gzip 214 随 scoped 块删除；
`ScrapView` chunk 20.76 → 25.39 kB（gzip 7.23 → 8.05），`LossView` 53.24 / 13.79、`AppChrome`
73.95 / 29.19、`RepairDetailView` 132.63 / 34.29 字节不变，入口 `index` 55.57 → 55.54 kB
（gzip 同为 21.13/21.14），全量 JS gzip 266,140（合并基线）→ **266,907** 字节，即本批改造净增 767 字节。

两条判据脚本 `apps/web/src` 全量：`files_with_derivation=0 sites=0`、`hard_violations=0`
（E 类 `lifecycle_shape_review=13`，与 ARCH-09/10/12/13/14 同一份保留清单，本批未增减）。合并基线的
同一对读数是 1 文件/13 处派生与 7 项硬违规（6 项 A 类 primitive `ref` + 1 项 D 类自造在途计数），
即 §3/§4 的输入。`.vue` 内裸 `fetch(` 命中 0、报废域裸色值命中 0、`ScrapView.vue` 的 `<style>` 块为 0。

真实浏览器：一次性栈（MySQL 8.4.11 + `fams-product` jar——本批用 Java 21 现场重建，jar 内已含
`/attachment-capabilities`、`/attachments/{kind}`、`/download-grant`、`/disposition/complete` 与
`/dispositions/overdue` 五个新端点 + clamav 1.4.6 + 两份 vite dev），合并基线 worktree `0052735`（改前）
与当前工作树（改后）并排跑同一后端、同一库状态、同一份 **48 条路由 × 3 角色 = 144 个渲染单元**，比对
最终 URL、DOM 结构签名、掩码后文本指纹、控件总数/禁用数、可点文案集合、翻页探针与
console/异常/HTTP≥400 事件集合：

```
compared=144 {"SAME":129,"STATUS_DIFF":0,"STRUCT_DIFF":15,"TEXT_DIFF":15,
              "CONTROL_DIFF":0,"LABEL_DIFF":0,"ISSUE_DIFF":0,"PROBE_DIFF":0,"MISSING":0}
```

15 个差异单元恰好是 5 条报废路由 × 3 角色，差异只在结构签名与文本（词表迁移与页头去重的预期结果，
每单元字符数差 4），控件总数/禁用数、按钮与字段文案集合、翻页探针与运行时事件集合逐条一致；其余 129
个单元逐字节同屏。两侧文本总长 58,972 → 58,914 字符；`routes_with_issues` 两侧同为 27 且事件集合逐条
同集（全部是越权路由的 403/404 与占位 id 的 404 噪声）。

三段新形状在浏览器里**确实渲染出来**的部分（两侧同集，逐单元核对）：

- 附件能力位段：15 个报废单元全部出现「查看报废附件需要单独授权。」，说明 `attachment-capabilities`
  读取与 `canRead` 分支在两侧同形；`warehouse /scrap-requests/3` 另含「申请附件」「处置凭证」
  「选择处置凭证」与文件控件（`canUploadDisposition` 为真）。
- 实际处置结案段：`warehouse /scrap-requests/3` 出现「登记实际处置」「批准的处置方式 …」
  「处置原机编码/序列号」「实际处置时间」「接收或处置对象」「加载更多实际经办人」「实际经办人」
  「请先上传处置凭证。」「核对实际处置结案」，控件总数两侧同为 47（禁用 5）；`/scrap-requests/2`
  两侧同为 50，说明 `actions.dispose` 门禁与目录按钮形状未变。
- 超期开关：`admin`/`warehouse` 的全部 10 个报废单元出现「查看超期待处置」按钮（
  `canListOverdueDispositions` 为真），`employee` 侧不出现——开关本身的权限分支已被两侧同形覆盖。

1440×900 整页截图（`warehouse /scrap-requests/3`）已人工核对观感：改后页头单点渲染「业务流转 /
报废与处置」，详情段依次为事实行、建议处置行、报废附件卡（申请附件 / 处置凭证 + 选择文件）、处置进度
行「待实际处置 · 计划日期 2026-11-16」、登记实际处置卡（含禁用的四个输入与两个按钮）；改前同屏出现两遍
「报废与处置」标题且字段控件为未收口的原生样式，其余内容逐段对应。

## 边界与遗留

- 与 ARCH-08～10/12/13/14 同口径：不改接口契约、权限、事务、并发与用户可见文案来源；本批可见变化只有
  §4 列出的页头去重、预览段改挂 `FamsMediaPreview` 与词表空白口径。
- 实测仍只覆盖读路径与渲染形状。以下三处新形状**没有**在浏览器里渲染出来：
  1. 超期清单的**结果页**（业务日期读数、超期行与「计划处置 …」列）——`overdueOnly` 是视图内状态，
     只有点「查看超期待处置」才切换，而普查的探针只点分页按钮；开关按钮本身已按上一条覆盖。
  2. 附件**读取与预览**分支（`canRead` 为真时的两类列表、原件 `<img>`/下载链接与授权地址校验链）——
     一次性栈的三个账号都没有 `SCRAP_ATTACHMENT` 字段权限，`capabilities.canRead` 恒假；把该权限造出来
     需要走授权管理侧的 `fams_account_authorization` 授予链，本批没有接进 `seed-arch-ui-docs.py`。
  3. 上传与结案的**写路径**（multipart 提交、`/disposition/complete`）——普查不执行写操作，五个新按钮
     「选择处置凭证/核对实际处置结案」等的点击链路只在功能线自己的用例与后端证据里走通。
  这三处由功能线新增、本批一字未改并通过的四条用例覆盖（`ScrapView.test.ts` 的
  「retries the original multipart file and request version after a committed response is lost」、
  「discards a late attachment response after identity changes and revokes an opened original」、
  「closes actual disposition with its independent version and selected clean original」、
  「opens an overdue disposition from the scoped server list and returns to all requests」），
  判据脚本对 `features/scrap/*` 两个文件命中 0。建议把「字段权限授予 + 附件上传 + 结案 + 超期切换」
  做成造单与探针底座的下一段扩展，与下一次触及报废写路径的批次一起实测。
- ARCH-14 记下的两处返厂实测缺口（`RepairFactoryPanel` 的「返厂原机无法收回」段、`LossRecoveryPanel`
  的「返厂原机找回续修」段，以及找回转调拨三条路由）本批未触及、仍未实测：本轮没有新建返厂范围申请→
  批准→建轮次→寄出这条链，两侧同库同状态，因此不影响本批 129/15 的结论。
- 造单侧本轮唯一失败仍是 `inbound accept A` 报 `409 INBOUND_ASSET_STATE_CONFLICT`（与 ARCH-14 同），
  属造单数据形状问题，与报废域无关；入库详情路由仍按同一状态被渲染与比对。
- 量测卫生（本轮踩到，写死在复现里）：`scripts/arch-ui-routes.py` 自己写 `--out` 文件并只把摘要打到
  stdout，**不能**用 `> /tmp/arch-routes.json` 重定向——那样摘要会盖掉 JSON 前若干字节，后续 sweep 会
  在 `JSON.parse` 处立刻失败，而旧的同名报告文件仍在原地，容易被误当成本轮读数。本批第一轮就因此拿到
  一份上一批遗留的 `sweep-*-all.json`，已改名为 `*.stale20261105` 并用全新文件名
  `sweep15-{head,base}-all.json` 重测。
- 计数口径更正：改造提交 `2529c3b` 的提交说明把新 computed 写成「8 个」，其后逐字列出的名字实为 10 个
  新 computed 加 1 个既有 computed 的映射补全；以本记录的 §4 清单为准，提交说明不回改。
- 小程序侧的报废页面（`apps/miniprogram/src/scrap-pages.ts`、`native/pages/scrap-requests/*`、
  `session.ts` 与 `runtime-scrap-http.mjs`）与后端、契约、spotbugs 例外直接取自功能线候选 `6134e96`，
  本批未改造、未独立复核；小程序侧相对 `api-types` 导入仍是 ARCH-07 的遗留。
- 本批两个提交（`0052735` 合并、`2529c3b` 改造）与本记录批只推送当前 UI 工作分支，未合并 `main`、
  未建 PR。推送只改变分发状态：除本地四道门禁与本批真实浏览器实测外，远端 CI、第七阶段 QA、
  真实外部联调、独立复核与生产验收仍未执行；不改变 D6-21/D6-22 各任务的开放状态。

## 复现

```bash
./mvnw -B -DskipTests package                                   # Java 21，五个报废新端点需在 jar 内
python3 scripts/run-arch-ui-stack.py --web-port 5173 --attachments &   # MySQL + fams-product + clamd + vite dev(改后)
python3 scripts/seed-arch-ui-docs.py                            # 10 类单据（含报废四张与报失三张）
python3 scripts/arch-ui-routes.py --out /tmp/arch-routes-15.json   # 只走 --out，不要重定向 stdout
git worktree add --detach /tmp/fams-base-0052735 0052735
ln -sfn <repo>/node_modules /tmp/fams-base-0052735/node_modules
ln -sfn <repo>/apps/web/node_modules /tmp/fams-base-0052735/apps/web/node_modules
(cd /tmp/fams-base-0052735/apps/web && npx vite --port 5174 --strictPort --host 127.0.0.1)
PW=$(python3 -c "import json;print(json.load(open('/tmp/fams-arch-stack.json'))['password'])")
node scripts/arch-ui-cdp-sweep.mjs --mode sweep --base http://127.0.0.1:5173 --routes /tmp/arch-routes-15.json --password "$PW" --out /tmp/sweep15-head-all.json --port 9431 --settle 700
node scripts/arch-ui-cdp-sweep.mjs --mode sweep --base http://127.0.0.1:5174 --routes /tmp/arch-routes-15.json --password "$PW" --out /tmp/sweep15-base-all.json --port 9441 --settle 700
node scripts/arch-ui-cdp-sweep.mjs --mode diff --left /tmp/sweep15-base-all.json --right /tmp/sweep15-head-all.json
# 人工核对观感：--filter scrap-requests/3 --roles warehouse --label head|base --shotDir <目录> --viewport 1440x900
```

沿用 ARCH-13/14 记下的两条执行前提：`node scripts/arch-ui-cdp-sweep.mjs` 必须用 Node 24 前缀（Node 16 的
`node:util` 无 `parseArgs`）；`--attachments` 需要本机 docker 可用。本轮 `rtk git fetch` 与
`rtk git push` 均正常。截图参数是 `--label`（决定 PNG 文件名前缀），不存在 `--shotPrefix`。
