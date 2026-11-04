# ARCH-13 找回转调拨新增前端按 ARCH 标准收口，并修回目录装配的仓库位反向（架构批第 13 批）

基线：`1d9a776`（把 `codex/d6-21-loss` 的 D6-21B2b4b「完好找回后关联正式调拨」合入架构线，冲突全部按
`features/<域>/{api,views,panels}` 形状落地）。判据沿用本分支已建立的四条，不新立标准：

- `vue-best-practices` + ARCH-08：模板不得计算，派生一律归 `computed` 或行显示模型；判据
  `scripts/audit-web-template-derivations.py`。
- ARCH-09：primitive 状态用 `shallowRef`，迟到抑制按槽位归属；判据 `scripts/audit-web-state-ownership.py`。
- ARCH-10：目录扇出与目标引用装配走 `features/transfer/references.ts` 纯函数，不自造第二份。
- ARCH-03/07：请求一律经 `api/http` 或跨域写内核 `api/business`，`.vue` 内不得裸 `fetch`。

## 量级

| 文件 | 合并基线 | 本批改后 | 主要变化 |
| --- | --- | --- | --- |
| `features/loss/panels/LossRecoveryPanel.vue` | 382 行 | 370 行 | 调拨段采纳 `loadCatalogPages`/`resolveReference`/`buildTransferTarget`；10 处新 primitive 归 `shallowRef`；按钮文案三元归 computed |
| `features/transfer/views/TransferDetailView.vue` | 212 行 | 216 行 | 「重新送审找回调拨」的整串内联判据归 `canResubmitFromLossRecovery` computed |
| `features/transfer/references.ts` | 36 行 | 36 行 | 修回 `warehouseId` 三元方向（1 行） |
| `features/transfer/references.test.ts` | 新增 | 27 行 | 在库/在用两型装配与 `resolveReference` 缺目录语义三条用例 |

合并提交 `1d9a776` 全仓 25 文件 +2,699/−65，其中 `apps/web` 6 文件 +193/−13；本批改造提交 4 文件 +57/−38。

## 1. 目录扇出与目标装配复用共享纯函数

功能线在面板里又抄了一份 `page=1 / pageSize=200` 的翻页循环（`transferCatalog`）和一份
`referenceVersions` 拼装（`transferTarget`）。ARCH-10 已把这两件事抽成
`features/transfer/references.ts`，同仓已有 5 个使用者，本批把第 6 个使用者接上：

```ts
function transferCatalog(kind: string, organization?: string): Promise<TransferChoice[]> {
  return loadCatalogPages<TransferChoice>(businessRequest, "/transfers/catalog", kind, organization);
}
```

请求客户端仍显式注入跨域写内核 `businessRequest`，拼出的 URL 与功能线逐字一致（功能线用
`encodeURIComponent(organizationId)`，`loadCatalogPages` 直接拼接；该参数是纯数字主键，两种拼法对同一
输入产出同一 URL）。`transferTarget()` 改为「先按现算的候选集 `resolveReference` 出四个引用项，再交给
`buildTransferTarget`」，三段校验的先后顺序、抛错条件与中文提示逐字保留。

### 1.1 顺带发现并修回的缺陷

`buildTransferTarget` 第 35 行原本是：

```ts
warehouseId: parts.stock ? null : parts.warehouse!.id,   // 抽取时的写法
```

与同一函数第 31 行 `if (parts.stock) versions.warehouse = parts.warehouse!.version;` 的条件相反，也与
`543221b` 抽取时删除的两处原内联实现（`InventoryDifferencePanel` 的 `stock.value ? wh!.id : null`、
`TransferTargetChangeView` 的 `stock.value ? warehouse!.id : null`）相反。即 ARCH-10 抽取时把三元方向写反。
后果分两支：

- 在库（`stock=true`）路径提交 `warehouseId=null` 却仍带 `referenceVersions.warehouse`；
- 非在库路径对未选中的 `parts.warehouse` 取 `.id`，直接抛 `TypeError`。

本批接入时正是后者暴露了它（找回转调拨是在用/在库两型都要装配目标的第 6 个使用者）。按抽取前的内联语义
修回一行，并新增 `references.test.ts` 锁住两型装配——此前 353 条用例没有一条覆盖该纯函数，缺陷才一路绿到
本批。两个既有使用者的行为因此回到 ARCH-10 之前，不引入新语义。

## 2. 迟到抑制：本批刻意不给调拨读取套 useRequestLiveness

`useRequestLiveness` 的不变量是「同一时刻只有最后一次 `begin()` 有权写回」。它在本面板已服务
`loadLocations` 一组槽位。调拨段另有两组互不相干的写入槽：`loadTransferTarget` 写
组织/位置/仓库三张目录，`loadTransferPeople` 写责任人目录。若三处共用同一个 liveness 实例，
「先点加载实物实际位置、再点加载调拨目标目录」就会让前一次响应被判迟到而丢弃——用户点了两个按钮只得到
一组数据，这是行为回归而不是收口。

因此本批保留功能线自带的、与槽位一一对应的守卫：`loadTransferTarget` 靠 `loadingTarget` 重入守卫串行化，
`loadTransferPeople` 靠「发起时捕获的部门值与当前值现算比较」丢弃过期响应（与 ARCH-12 记录里
`selectedPlace()` 每次现算、不缓存同一口径）。代码里留了一行说明为什么这里不用底座。
判据脚本的 E 类（`lifecycle_shape_review`）计数未因此变化，仍是 13 份保留清单。

## 3. 模板派生与状态形状

- 新增段的按钮文案 `{{ locationPage ? '继续加载实际位置' : '加载实物实际位置' }}` 归
  `loadTransferLocationsLabel` computed（与既有 `loadLocationsLabel` 共用 `locationsLoaded`，但两段的
  用户可见措辞不同，不合并文案）。
- `TransferDetailView` 的重新送审判据（创建人 + `REJECTED`/`CANCELLED` + 事件含
  `TRANSFER_FROM_LOSS_RECOVERY`）归 computed，同 ARCH-12 对 `canResubmitLoss` 的处置。
- `transferChecked`、`sameTransferCustodian`、`transferReason`、`targetCustody`、`targetOwner`、
  `targetOrganization`、`targetPerson`、`targetLocation`、`targetWarehouse`、`loadingTarget` 共 10 处
  primitive 由 `ref` 改 `shallowRef`；四张目标目录列表保持深跟踪 `ref`——它们整份替换且与本面板既有
  `locations` 同形。
- 面板仍只 `emit("prepare", 草稿)`，不生成幂等键；本批 `.vue` 内裸 `fetch(` 命中 0、裸色值命中 0。

## 4. 造单与实测底座的扩展

一次性栈的默认造单只覆盖到「一张待批准的借用资产报失单」，`/loss-reports/eligible` 在本轮库里只剩 1 行
（14/15/20 三张在用资产分别被维修、领用与退库的活动锁挡住），`seed-arch-ui-docs.py` 因此报
`eligible assets exhausted`，报失线只造出 `lossPending`。为把新段真正渲染出来，本批用产品自己的 HTTP API
补造了找回转调拨链（脚本留在 `/tmp`，未入库）：

- 报失单 2：在库资产 → 批准 → 领取 → 核实查找（`SEARCHING`，处理库管持 `recoverOriginal`）→
  `POST /loss-reports/2/recover-to-transfer` 直接执行，产出调拨单 2；
- 报失单 3：在库资产 → 批准 → 领取 → 核实查找 → 确认遗失 → `transfer-recovery-requests` 新建
  `destination=TRANSFER` 的找回申请 → 管理员批准 → 库管认领（申请 `actions.execute=true`）；
- 调拨单 2：以 `TRANSFER_FROM_LOSS_RECOVERY` 事件建单 → 管理员驳回（`REJECTED`），使重送按钮有真实来源。

实测底座侧把 `scripts/arch-ui-routes.py` 的 `EXTRA_DETAIL` 补了三个键
（`lossTransfer`/`lossRecoveredTransfer`/`lossCreatedTransfer`），路由清单从 41 条增至 44 条，覆盖
`/loss-reports/2`、`/loss-reports/3` 与 `/transfers/2`。

## 5. 门禁与真实浏览器实测

四道门禁（Node 24.18.0）：根 `npm run lint --max-warnings=0` 退出 0；`vue-tsc --noEmit` 0 错误；
`vitest run` **80 文件 / 353 用例全绿**（合并基线为 79/350，本批新增 `references.test.ts` 1 文件 3 用例；
功能线新增的「PC recovery transfer separates observed location from the approved transfer target」用例
一字未改并通过，它同时是 §1.1 修回的直接回归保护——装配方向写错时该用例先失败）；`vite build` 通过，
主样式表 `index-CvDOmE8w.css` 31.42 kB / gzip 5.65 kB **文件名与字节均未变化**，`LossView` chunk
51.34 → 50.86 kB（gzip 13.89 → 13.77）、`TransferDetailView` 15.85 → 15.88 kB（gzip 5.45 → 5.44）、
入口 `index` 55.26 / 21.04 未变、`AppChrome` 73.89 / 29.17 未变，全量 JS gzip 255,949 → 255,824 字节。

两条判据脚本 `apps/web/src` 全量：`files_with_derivation=0 sites=0`、`hard_violations=0`
（E 类 `lifecycle_shape_review=13`，与 ARCH-09/10/12 同一份保留清单，本批未增减）。合并基线同一对脚本的
读数是 2 文件/2 处派生与 2 项硬违规，即 §3 的输入。

真实浏览器：一次性栈（MySQL 8.4.11 + `fams-product` jar（Java 21 现场重建，含本批找回转调拨端点）+
clamav 1.4.6 + 两份 vite dev），基线 worktree `1d9a776`（改前）与本批提交（改后）并排跑同一后端、同一库
状态、同一份 **44 条路由 × 3 角色 = 132 个渲染单元**，比对最终 URL、DOM 结构签名、掩码后文本指纹、
控件总数/禁用数、可点文案集合、翻页探针与 console/异常/HTTP≥400 事件集合：

```
compared=132 {"SAME":132,"STATUS_DIFF":0,"STRUCT_DIFF":0,"TEXT_DIFF":0,
              "CONTROL_DIFF":0,"LABEL_DIFF":0,"ISSUE_DIFF":0,"PROBE_DIFF":0,"MISSING":0}
```

两侧文本总长同为 49,980 字符，`routes_with_issues` 同为 30 且事件集合逐条同集（全部是越权路由的
403/404 与占位 id 的 404 噪声）。本批新增形状确实进入测量而非空跑：`warehouse /loss-reports/3` 同屏渲染
「完好找回后调拨」段（加载实物实际位置、找回实物位置、两个确认勾选、加载调拨目标目录、目标所属组织、
目标仓库、目标位置、调拨原因、核实找回并提交调拨）与申请行「本次申请去向：关联正式调拨」；
`warehouse /loss-reports/2` 渲染「已关联正式调拨 2」；`warehouse /transfers/2` 渲染「重新送审找回调拨」，
且该按钮只对该单创建人（warehouse）出现，admin/employee 两侧同样不出现。1440×900 整页截图已核对观感，
新段与既有词表同构。

## 边界与遗留

- 与 ARCH-08～10/12 同口径：不改接口契约、权限、事务、并发与用户可见文案来源。§1.1 的修回是把共享纯函数
  恢复到它抽取前的语义，不是新语义；但它确实改变了盘亏转调拨与调拨目标变更两域在库路径提交的
  `warehouseId`（由 `null` 改为所选仓库 id）与非在库路径的抛错行为，**该效果未经真实浏览器写路径实测**，
  建议与下一次涉及调拨写入的批次一起在浏览器里复核。
- 实测仍只覆盖读路径：`核实找回并提交调拨`、`找回并提交调拨`、`新建调拨找回申请`、`重新送审找回调拨`
  四个按钮的点击链路只在造单脚本里经产品 API 走通（其中 `recover-to-transfer` 与驳回走通，
  `execute-to-transfer` 与 `transfer-recovery-requests` 走通），普查本身不执行写操作。
- `新建调拨找回申请`（需一张无在办申请且持 `requestRecovery` 的已确认遗失单）本轮未渲染出来：库里唯一
  一张 LOST 单已挂着在办申请。属数据形状缺口，非代码缺口。
- `apps/miniprogram` 的找回转调拨页面（`loss-pages.ts`、`index.wxml`）与后端、契约变更直接取自功能线候选
  `326aec9`，本批未改造、未独立复核；小程序侧的 5 处相对 `api-types` 导入仍是 ARCH-07 的遗留。
- 本轮 `rtk git fetch` 因 `rtk` 沙箱无网络而失败，改用裸 `git fetch` 取得远端状态；`rtk` 对本地 git
  操作与门禁前缀仍照常使用。
- 本批三个提交（`1d9a776` 合并、`fd20656` 改造、本记录批）只推送当前 UI 工作分支，未合并 `main`、未建 PR。
  推送只改变分发状态：除本地四道门禁与本批真实浏览器实测外，远端 CI、第七阶段 QA、真实外部联调、
  独立复核与生产验收仍未执行；不改变 D6-21 各任务的开放状态——B2b4b 固定候选门禁、实际 PC 与提交 JVM
  恢复仍待功能线自己收口。

## 复现

```bash
./mvnw -B -DskipTests package                                   # Java 21，找回转调拨端点需在 jar 内
python3 scripts/run-arch-ui-stack.py --web-port 5173 --attachments &   # MySQL + fams-product + clamd + vite dev(改后)
python3 scripts/seed-arch-ui-docs.py                            # 9 类单据（本轮报失线只造出 lossPending）
python3 scripts/seed-arch-ui-docs.py --only-loss               # 本轮 0 行可报失资产，改由 §4 的驱动脚本补造
python3 scripts/arch-ui-routes.py                               # router.ts + ids → 44 条路由
git worktree add --detach /tmp/fams-base-1d9a776 1d9a776
ln -sfn <repo>/node_modules /tmp/fams-base-1d9a776/node_modules
ln -sfn <repo>/apps/web/node_modules /tmp/fams-base-1d9a776/apps/web/node_modules
(cd /tmp/fams-base-1d9a776/apps/web && npx vite --port 5174 --strictPort --host 127.0.0.1)
PW=$(python3 -c "import json;print(json.load(open('/tmp/fams-arch-stack.json'))['password'])")
node scripts/arch-ui-cdp-sweep.mjs --mode sweep --base http://127.0.0.1:5173 --routes /tmp/arch-routes.json --password "$PW" --out /tmp/sweep-head-all.json --port 9431 --settle 700
node scripts/arch-ui-cdp-sweep.mjs --mode sweep --base http://127.0.0.1:5174 --routes /tmp/arch-routes.json --password "$PW" --out /tmp/sweep-base-all.json --port 9441 --settle 700
node scripts/arch-ui-cdp-sweep.mjs --mode diff --left /tmp/sweep-base-all.json --right /tmp/sweep-head-all.json
# 人工核对观感时加 --filter transfer --shotDir <目录> --viewport 1440x900
```

注意两条本批踩过的执行前提：`node scripts/arch-ui-cdp-sweep.mjs` 必须用 Node 24 前缀（Node 16 的
`node:util` 无 `parseArgs`，直接 SyntaxError）；`run-arch-ui-stack.py` 的 `--attachments` 需要本机 docker
可用，且 `rtk` 包裹的网络命令（`git fetch`）会连不上 github，需按裸命令执行。
