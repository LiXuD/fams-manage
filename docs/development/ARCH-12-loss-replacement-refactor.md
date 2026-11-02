# ARCH-12 报失与换机新增前端按 ARCH 标准与 fams-* 词表收口（架构批第 12 批）

基线：`39be3bb`（把 `codex/d6-21-loss` 报失功能线合入架构线，并把新增前端移植到 `features/` 垂直切片）。判据沿用本分支已建立的四条，不新立标准：

- `vue-best-practices` + ARCH-08：模板不得计算，派生一律归 `computed` 或行显示模型；判据 `scripts/audit-web-template-derivations.py`。
- ARCH-09：primitive 状态用 `shallowRef`、迟到抑制用 `useRequestLiveness`、原件预览用 `useObjectUrlPreview`；判据 `scripts/audit-web-state-ownership.py`。
- ARCH-10：确认式幂等提交用 `useConfirmedSubmit`，幂等键只归提交底座；判据同上（D 类）。
- ARCH-03/07 + UI-01～21：域内 HTTP 走 `api/http` 底座，视图/面板内不得裸 `fetch`；组件级原始 CSS 归位 `fams-*` 令牌词表。

## 量级

| 文件 | 改前 | 改后 | 主要变化 |
| --- | --- | --- | --- |
| `features/loss/api.ts` | 43 行 | 51 行 | 草稿/确认两型分离；`lossRequest` 改走 `requestJson` 底座 |
| `features/loss/views/LossView.vue` | 173 行 | 328 行 | 三底座采纳 + 9 处模板派生归位 + 词表迁移（自带 scoped CSS 739 字节归零） |
| `features/loss/panels/LossRecoveryPanel.vue` | 53 行 | 241 行 | 最长单行 2496 字符拆为三族核实构造器 + 10 处派生归位 + 词表（403 字节归零） |
| `features/loss/panels/LossProgressPanel.vue` | 36 行 | 60 行 | 幂等键上交、primitive 归位、词表（350 字节归零） |
| `features/repair/panels/RepairFactoryReplacementPanel.vue` | 157 行 | 228 行 | 同上，另收口本批最后一个视图内裸 `fetch`（321 字节 CSS 归零） |
| `components/AssetFactoryReplacementTrace.vue` | 28 行 | 43 行 | 裸 `fetch` → `features/asset/api.listFactoryReplacements`，`generation/live` → 底座 |

diff 合计 11 文件 +946 / −283（含 4 个实测底座脚本）。

## 1. 幂等键归位：面板只起草，视图才持有键

报失线原本三处各自 `crypto.randomUUID()`（视图两处 + 两个面板各一处），面板把键塞进 `LossIntent` 交给父级，等于把「重试不换键」的不变量分散到子组件。现按 ARCH-10 的既有形状分离：

```ts
export interface LossIntent { path: string; version: number; body?: unknown; file?: File; summary?: string }
export interface LossSubmission extends LossIntent { key: string }
```

面板 `emit("prepare", Object.freeze({...}))` 只给草稿；视图侧 `useConfirmedSubmit<Omit<LossSubmission, "key">>` 的 `prepare/confirm/settle/fail` 负责建键、草稿→在途的原子移交与确定性失败清键。本域确定性判据 `cause instanceof LossApiError && cause.status < 500` 逐字保留在 `options.failure`，未改成别的域的形状。

全仓 `crypto.randomUUID()` 在报失线从 4 处降到 1 处：`viewPhoto` 换取下载授权（`POST .../download-grant`）是一次性读取、不参与二次确认，就地取键并留了一行说明为什么它不占用底座槽位。

`LossView.prepareProgress` 复核后删掉了 `readOwner` 这个自造读守卫：`detail` 只在 `liveness.isCurrent(turn)` 为真时写入，面板又按 `identity + 单号 + 版本` 重新挂载，因此「视图已有 detail」本身就蕴含「当前身份已读到过」，保留它只会多一个和底座并行的第二事实源。

## 2. 迟到抑制、预览与分页读取

- `let generation = 0, live = true, readOwner` + `current(epoch, owner)` → `useRequestLiveness(() => [identity.value])`，五个异步出口（列表+详情、可报失资产、写请求、原件授权、原件下载）统一 `begin()/isCurrent()`。
- `preview/previewName/download/clearPreview` 四个槽与 `URL.createObjectURL/revokeObjectURL` 簿记 → `useObjectUrlPreview`；blob 下载段 → `api/http.requestBlob`（同一 `fetch→状态码→blob` 机械序列）。
- `LossRecoveryPanel.loadLocations` 是唯一无守卫的读取（原样把分页结果 `push` 进列表）：补 `useRequestLiveness(() => [report.id, report.version, locked])`，锁上或换单后的迟到响应不再落进位置表。位置记录 `locations` 由分页 `push` 追加，因此保持 `ref` 深跟踪，实际选中位置用现算函数而不是 `computed` 缓存，避免「缓存值不随 push 失效」。

## 3. 模板派生归位：19 → 0

判据在报失线命中 19 处（`LossView` 9、`LossRecoveryPanel` 10）。归位形状：

- 行显示模型：`reportRows`（补 `stateText`）、`attemptRows`（补 `statusText/processorText/destinationText/conditionText/custodianText/identityText` 与三个互斥的执行按钮可见性）。找回申请列表原来在模板里做 `.find()/.some()` 与四段三元，现由 `attemptRows` 一次算完，模板只读具名字段。
- 判据型 computed：`showDamagedSection`、`showReturnSection`、`showRecoveryPanel`、`showProgressPanel`、`showSearchingNote`、`showConfirmedLostNote`、`locationsLoaded`。
- 绑定型 computed：`assetPrevDisabled`/`assetNextDisabled`/`recordsPrevDisabled`/`recordsNextDisabled`（页大小收成 `PAGE_SIZE` 常量，不再在模板里 `page * 20 >= total`）、`recordsTitle`、`preparedSummary`/`pendingSummary`、`recoveryPanelKey`/`progressPanelKey`。

`LossRecoveryPanel.prepare` 原来是把「能力位复核 + 三族核实 + 路径拼装 + 文案」压在一条 2496 字符语句里的单函数，现拆为 `returnFacts/damagedFacts/intactFacts` 三个核实构造器、`recoveryBody` 一个乐观版本装配、`pathOf`/`ATTEMPT_PATH`/`REPORT_PATH`/`*_CAPABILITY` 四张表和一个 `kindMatches` 互斥判据；校验顺序、错误句、请求体字段与确认文案逐字保留（`LossRecoveryPanel.test.ts`、`LossReturnRecovery.test.ts` 未改任何断言仍绿）。

## 4. fams-* 词表与组件级 CSS 回收

四个文件自带 scoped CSS 共 1813 字节（含 `#64748b`/`#cbd5e1`/`#b91c1c`/`#2563eb` 等裸色值）全部删除，改用 `fams-panel/fams-stack/fams-card/fams-field/fams-control/fams-selection/fams-file/fams-button(data-kind|data-size)/fams-table/fams-pagination/fams-status/fams-facts/fams-timeline/fams-notice/fams-media-preview`。

构建侧的直接结果：per-view CSS 产物从两份（`LossView-*.css` 1.93 kB / gzip 0.52、`RepairDetailView-*.css` 0.34 kB）归零，主样式表 `index-CvDOmE8w.css` 31.42 kB / gzip 5.65 **文件名与字节均未变化**——即本批没有往令牌层塞新规则，只是把组件自带样式换成既有词表。

两处刻意的可见文本变化（不属于文案来源改动，属口径统一）：

1. `LossView` 删掉自带的 `<h1>报失与查找</h1>` + 描述段——路由 `meta.uiTitle/uiDesc` 已给出同名标题与描述，页头由 `AppChrome` 单点渲染，此前同一屏出现两遍。
2. 分页读数统一为全站口径「第 N 页，共 M 项 / 第 N 页」（原视图为 `第1页，共0项` 无空格）。

## 5. 换机面板与换机追溯组件

`RepairFactoryReplacementPanel`（157 行，含 7 处模板派生与自造在途计数）与 `AssetFactoryReplacementTrace`（28 行，裸 `fetch`）同样收口：底座三件套 + 行显示模型（`replacementRows`/`referenceRows`/`dispositionOptions`/`originalsRows`）+ 词表迁移。

`fetchRefs` 原来在面板里裸 `fetch` 四类参考资料的分页接口，现走资产域底座 `activeReferences(path, page, pageSize)`（该函数新增两个可选参数，缺省值保持既有调用方口径）。为不改动用户可见失败文案，面板把 `AssetApiError` 在边界处换成本域 `RepairApiError(status, code)` 后再交给 `factoryProblem`，失效判定与文案与前完全一致。

至此 `apps/web/src` 全部 `.vue` 文件内裸 `fetch(` 命中数为 **0**（ARCH-07 建立的口径在合入报失线后重新归零）。

`blocked` prop 在本面板保留：父级 `RepairFactoryPanel` 持有「轮次已寄出/已收回」与目录过期这些面板外部事实，与 ARCH-10 记录保留的 3 处同一口径，不是漏改。

## 6. 门禁与真实浏览器实测

四道门禁（Node 24.18.0）：根 `npm run lint` 退出 0；`vue-tsc --noEmit` 0 错误；`vitest run` **79 文件 / 349 用例全绿，未修改任何既有断言**；`vite build` 通过，主 CSS 逐字节未变，`LossView` chunk 33.47 → 42.14 kB（gzip 10.60 → 11.87）、`RepairDetailView` chunk 127.46 → 129.98 kB（gzip 33.20 → 33.36）、入口 `index` 55.33 → 55.26 kB（gzip 21.08 → 21.05）、`AppChrome` 73.89 / 29.17 未变。两个判据脚本：`apps/web/src` 全量 `files_with_derivation=0 sites=0`、`hard_violations=0`（E 类 `lifecycle_shape_review=13`，与 ARCH-09/10 记录的同一份保留清单，本批未增减）。

真实浏览器：一次性栈（MySQL 8.4.11 固定 digest + `fams-product` jar + clamav 1.4.6 + 两份 vite dev），基线 worktree `39be3bb`（改前）与当前工作树（改后）并排跑同一后端、同一库状态、同一份 **43 条路由 × 3 角色 = 129 个渲染单元**，比对最终 URL、DOM 结构签名、掩码后文本指纹、控件总数/禁用数、可点文案集合、翻页探针与 console/异常/HTTP≥400 事件集合：

```
compared=129 {"SAME":117,"STATUS_DIFF":0,"STRUCT_DIFF":12,"TEXT_DIFF":12,
              "CONTROL_DIFF":0,"LABEL_DIFF":0,"ISSUE_DIFF":0,"PROBE_DIFF":0,"MISSING":0}
```

117 个非报失单元逐字节一致（含新挂上换机追溯的 `/assets`、换机面板所在的 `/repairs/1`、`/inbound/1`、`/asset-returns/1`）；12 个差异单元全部是 `/loss-reports` 与其三张单据详情，差异只在结构签名与文本（词表迁移与页头去重的预期结果），控件数、禁用数、按钮/字段文案集合与运行时事件集合完全一致。改后侧 129 单元 `routes_with_issues=27`，与改前侧同一份 27 条（全部是越权路由的 403/404 与占位 id 的 404 噪声，两侧逐条同集）。

报失线数据由产品自己的 API 造出（`scripts/seed-arch-ui-docs.py --only-loss`）：三张报失单分别停在待管理员批准（含一张经 clamd 扫描 CLEAN 的 multipart 情况图片）、库管核实中、已确认遗失＋一张已批准并重新认领的在办找回申请——最后一张同屏渲染「找回核实与实际去向」与「遗失持续跟进」两个重写面板。栈侧新增 `--attachments`（配 `fams.attachments.quarantine-root` 并起 clamd），补掉了 ARCH-11 记录的「附件接口 500」遗留项。

## 边界与遗留

- 与 ARCH-08～10 同口径：不改接口契约、权限、事务、并发与用户可见文案来源；本批可见变化仅第 4 节列出的页头去重与分页空格口径两处。
- 换机面板与换机追溯组件未进浏览器实测：前者需要一条已寄出/已收回的返厂轮次且当前身份为该单处理库管，后者需要在台账里选中一张有换机记录的卡，一次性栈没有这类单据；两者的行为由 `RepairFactoryReplacementPanel.test.ts`（4 例）与 `AssetFactoryReplacementTrace.test.ts`（3 例）覆盖，判据脚本对这两个文件命中 0。
- 找回申请执行三族（原机恢复 / 转维修 / 关联归还退库）在浏览器里只渲染到「已批准并认领、待执行」这一步，实际执行会改写资产与维修/退库单据，只读普查不做。
- 2026-11-03 经用户授权，本分支（ARCH-01～ARCH-12 全部提交，tip `cbe84a6`）推送为 origin 新分支 `codex/d6-21-loss-ui`；原远端分支 `codex/d6-20-factory-ui` 停在 `e0f404c`，未合并 `main`、未建 PR。推送只改变分发状态：除本地四道门禁与本批真实浏览器实测外，远端 CI、第七阶段 QA、真实外部联调、独立复核与生产验收仍未执行；不改变 D6-19/D6-20/D6-21 各任务的开放状态。功能开发的下一步仍是现役计划的 D6-20C1 PC 缺陷修复与重验、C2 整机新卡与换机入库、D 及 D6-21～27。

## 复现

```bash
./mvnw -B -DskipTests package                                   # Java 21，报失控制器需在 jar 内
python3 scripts/run-arch-ui-stack.py --web-port 5173 &          # MySQL + fams-product + clamd + vite dev(改后)
python3 scripts/seed-arch-ui-docs.py                            # 9 类单据
python3 scripts/seed-arch-ui-docs.py --only-loss                # 三张报失单（含 multipart 原件与找回申请链）
python3 scripts/arch-ui-routes.py                               # router.ts → 43 条路由（含 loss 三条详情）
git worktree add /tmp/fams-base-39be3bb 39be3bb
ln -sfn <repo>/node_modules /tmp/fams-base-39be3bb/node_modules
ln -sfn <repo>/apps/web/node_modules /tmp/fams-base-39be3bb/apps/web/node_modules
(cd /tmp/fams-base-39be3bb/apps/web && npx vite --port 5174 --strictPort --host 127.0.0.1)
PW=$(python3 -c "import json;print(json.load(open('/tmp/fams-arch-stack.json'))['password'])")
node scripts/arch-ui-cdp-sweep.mjs --mode sweep --base http://127.0.0.1:5173 --routes /tmp/arch-routes.json --password "$PW" --out /tmp/sweep-head-all.json --port 9431 --settle 700
node scripts/arch-ui-cdp-sweep.mjs --mode sweep --base http://127.0.0.1:5174 --routes /tmp/arch-routes.json --password "$PW" --out /tmp/sweep-base-all.json --port 9441 --settle 700
node scripts/arch-ui-cdp-sweep.mjs --mode diff --left /tmp/sweep-base-all.json --right /tmp/sweep-head-all.json
# 人工核对观感时加 --shotDir <目录> --viewport 1440x900（或 390x844）
```

本批给实测底座加了三项：`--attachments`（含 clamd 容器与扫描根）、`--only-loss`（只重跑报失线并保留既有单据 id）、`--filter/--shotDir/--viewport` 与比对侧的 `LABEL_DIFF`（按钮/链接文案与字段类型集合是否变化），使「词表迁移只改形状不改可点集合」成为可判定的口径而不是自述。
