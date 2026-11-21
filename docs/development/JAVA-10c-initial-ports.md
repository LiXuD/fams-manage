# JAVA-10c · initial 系与剩余跨域 Port 的 Map 面类型化（Port 批收官）

> 用户指定的后端结构批（JAVA-01 打样、02～07、08/09/10a/10b 之后的 Port Map 面收口批），
> 不进入 D6-01～D6-27 台账、不占用其状态。范围 = JAVA-10a §6.1/JAVA-10b §6.7 排除的
> 七件 initial 系与剩余跨域 Port。
> 日期：2026-11-22 · 分支：main · 基线：`73844e4`（JAVA-10b 提交）。
> 状态：主代理审计通过（零改动声明 grep 复核属实），收官门禁通过，已收口提交。

## 1. 背景与红线

复刻 JAVA-01/08/09/10a/10b 标准（`AssetLossPort.StoredSource/Snapshot`、
`AssetTransferPort.Snapshot` 键形冻结样板），红线不变：

1. **存储序列化键形最高红线**：本批三个类型化出口牵动三处存储/嵌入文档，全部由判据证明
   新旧序列化树等价：
   - `LossRepairPort.create/createAfterConfirmation` 的返回被 LossService 读取后嵌入
     `fams_loss_report.recovery_snapshot_json` 与 `fams_loss_recovery.execution_snapshot_json`
     的 `repairId` 键、审计 payload 与 loss HTTP detail 的 `repairId` 键——改前只读 view 的
     `"id"` 一个键（grep main+test 全链，835/848/1170 三处），值本就是 `RepairService.view`
     字符串化的 String，窄 record `Created(String id)` 逐字保持。
   - `InventoryLateEvidencePort.append` 的 task 行 Map 只被读 `task_status/id/version` 三列；
     其值进入存储文档 `fams_inventory_late_case.snapshot_json` 的 `taskId/terminalStatus/`
     `taskVersion` 键（并被 snapshot_sha256 哈希）与多条 FOR SHARE/INSERT SQL 绑定——
     窄入参 record `Task(long id, String taskStatus, long version)` 保持逐值相同
     （id/version 为 V31 DDL BIGINT→Long 事实，整型宽度变体由判据证明等值）。
   - `FactoryReplacementInboundPort.create` 的返回只有 `"id"` 有读者：绑定进
     `fams_repair_factory_replacement.inbound_receipt_id` INSERT 参数与幂等存档/HTTP 的
     `inboundReceiptId` 键——窄 record `Receipt(String id)` 保持同一 String 值原样绑定。
2. **嵌入未类型化 HTTP 的 wire 一字不动**：`FactoryReplacementInboundPort.context` 的返回整体
   嵌入 repair 未类型化 replacementView 响应的 `inbound` 键（RepairFactoryService:947）。record
   `Context(String id, long version, String status, String receiptNumber)` 分量名镜像改前
   `Map.of` 四键（JAVA-10b RecoveryView 先例），直序列化对象形不变；RepairFactoryHttpRuntimeCase
   对 `inbound.status/id` 与 `inbound` 为 null 的断言由该 wire 冻结覆盖（登记：repair/inbound
   HTTP 批再统一收口）。
3. **SQL/锁/异常码/审计/指纹零改动**：三个 Port 与全部调用方 SQL 文本、FOR UPDATE/FOR SHARE
   锁序、`SCRAP_LOSS_SOURCE_*`/`INBOUND_*`/`REPAIR_*` 异常码与失败翻译一字未动；
   `fams_inventory_late_case` 的 INSERT 列序与参数值、幂等 `save/saveReceipt` 存档链原样。
4. **禁 record→Map 回退**：全批无任何 record 转回 Map 的适配层；投影方向只有 行/文档 Map→record
   （fromRow/fromView 工厂，JAVA-09 §6.4 先例）。
5. **JDBC 原值不臆断**：本批无 DATETIME 列进入 record；`Created/Receipt` 的 id 值沿用改前
   view/receipt 文档已字符串化的 String 原值（null 安全 toString），`Context.version` 走
   `((Number)…).longValue()`（改前 Map.of 对 null 即 NPE 的恒非空键）。
6. 契约 `contracts/`、`packages/api-types`、`apps/web`、`apps/miniprogram` 零改动；工作树中
   其它会话在途文件（AGENTS.md、apps/miniprogram 两配置文件）未触碰。

## 2. 逐 Port 处置表

| Port | public Map 面 | 处置 |
|---|---|---|
| `scrap/ScrapInitialPort` | 无（openRepair/open/openLoss/openFactoryDisposition/assertReversible/cancel 全 record/基元入参） | grep 核实零改动；内部 proof/source LinkedHashMap 属存储文档与事件装配（10b §6.5 边界原样） |
| `repair/RepairInitialPort` | 无（open/assertReversible/cancel） | 同上；`source()/event(facts)` 为私有行/审计 Map，保留 |
| `loss/LossInitialPort` | 无（open→Confirmed record、cancel） | 同上；assetimport 链（AssetInitialPublicationPort/AssetImportPublicationService）经 business 接口注入，接口面无 Map（§6 残留清单核证） |
| `loss/LossScrapSourcePort` | 无 public 方法 Map（requireSubmissionEvidence/requireExecutionEvidence→ConfirmedLoss、closeFollowUp→void） | 零改动；包级静态 `canSubmit/confirmedScrapSource` 是 loss 同包共享的行判定 helper（LossService:242-243 复用），属内部行 Map 面保留；scrap 侧消费全走已类型化方法（grep 证实） |
| `repair/LossRepairPort` | `create/createAfterConfirmation→Map` | →`Created(String id)`（窄身份；全链唯一读者是 id），`Created.fromView` 投影；LossService 三处消费改 `.id()` |
| `inventory/InventoryLateEvidencePort` | `append(Map task,…)` 入参 | →`Task(long id, String taskStatus, long version)` 入参 + `Task.fromRow` 投影；两消费者（InventoryLateService.prepare / InventoryObservationService.submit 终态分支）调用点投影装配，其余行用法不动 |
| `inbound/FactoryReplacementInboundPort` | `create→Map`、`context→Map` | create→`Receipt(String id)`（窄身份）；context→`Context`（四键嵌入 repair HTTP，wire 冻结）；RepairFactoryService 两处消费改 `.id()`，replacementView 的 `inbound` 嵌入零改动（record 直序列化） |

方法调用时序、异常翻译（Failure 包装）、`RepairErrorHandler/LossErrorHandler` 处理链一字未动。

## 3. 改动清单

主代码 7 改，测试 1 增（git diff --numstat：7 Java 文件 +85/−29，另测试新增 209 行）：

| 文件 | 变化 |
|---|---|
| `repair/LossRepairPort.java` | 新增 `Created` record + `fromView` 投影；两 public 出口类型化 |
| `loss/LossService.java` | 835/848/1170 三处 `repair.get("id")→repair.id()`（存储/审计/响应键与值不变） |
| `inventory/InventoryLateEvidencePort.java` | 新增 `Task` record + `fromRow`；`append` 入参类型化；guard/SQL 绑定/packet 三键改访问器（逐值等价）；私有 wire()/packet 装配其余原样 |
| `inventory/InventoryLateService.java` | prepare 循环一处调用改 `Task.fromRow(task)` |
| `inventory/InventoryObservationService.java` | 终态分支一处调用改 `Task.fromRow(task)` |
| `inbound/FactoryReplacementInboundPort.java` | 新增 `Receipt/Context` record + `fromView` 投影；两 public 出口类型化；createOwned 内部 receipt 行 Map 保留 |
| `repair/RepairFactoryService.java` | recordReplacement 两处 `inbound.get("id")→inbound.id()`；replacementView 嵌入零改动 |
| `repair/InitialPortsWireShapeTest.java`（新增） | 5 条 wire/存储等价判据，见 §4 |

## 4. 判据：InitialPortsWireShapeTest（5 条）

改前形状逐字冻结为 oracle（`legacyRepairView` 复刻 RepairService.view 的 id 字符串化投影、
`legacyReceiptDocument` 复刻 InboundService.receipt 的 id/version/status/receiptNumber 键形、
task packet 三键复刻 append 的旧装配）：

1. `createdKeepsTheViewStringifiedIdAndLossEmbeddings`：Created.id ≡ 改前 `view.get("id")` 值；
   recovery_snapshot_json（Map.of 两态）、repairId 响应键、execution_snapshot_json 三装配点
   序列化树等值。
2. `createdReplaysStoredPreRefactorResponseJson`：改前形状的幂等存档 response_json 回放 →
   fromView 取同串；Integer 宽度变体等值。
3. `taskKeepsPacketSqlAndGuardValuesOfTheRawRow`：Task.fromRow 后 SQL 绑定值 equals 原行值、
   snapshot_json 的 taskId/terminalStatus/taskVersion 三键树等值、Integer version 变体等值、
   终态 guard 输入逐值保真。
4. `receiptKeepsTheStoredIdBindingAndHttpEmbedding`：Receipt.id ≡ 改前 `receipt.get("id")`
   String；INSERT 绑定与 inboundReceiptId 嵌入装配等值。
5. `contextKeepsTheEmbeddedInboundWireShape`：Context ≡ 改前 `Map.of` 四键；replacementView
   嵌套嵌入等值、`inbound` 为 null 的隐藏态保持显式 null 键、Integer version 变体等值。

比较口径沿用 JAVA-01/08/09：序列化 JSON 文本再解析比树（整型宽度归一、键序不参与；键存在性、
显式 null、值形状参与）。单元 oracle 边界如实登记：真实 HTTP/DB 运行时链（loss 恢复两流程存储列
回放、repair recordReplacement→replacements GET 的 inbound 嵌入、inventory late prepare/submit
的 late_case 快照列）由主代理门禁实跑。

## 5. 门禁证据（实施代理本地，非产品门禁）

- 每 Port 完成后定向（graalvm-jdk-21.0.7）
  `-Dtest='InitialPortsWireShapeTest,TransferWireShapeTest,LoanWireShapeTest,ScrapWireShapeTest,AssetLossPortWireShapeTest'`：
  LossRepairPort 后 **49/49**、InventoryLateEvidencePort 后 **50/50**、
  FactoryReplacementInboundPort 后 **52/52** 全绿。
- 收官安全网：全部 14 个 WireShapeTest 类 **110/110 全绿**
  （含 AssetRepair/AssetScrap/AssetLight/Proxy/TargetChange 等 repair/scrap 邻接复跑）。
- 全仓编译：`./mvnw -pl backend -q test-compile` → exit=0。
- Checkstyle 项目口径：`-Pquality checkstyle:check@check-java-conventions` → **0 violations**；
  8 个触及 Java 文件末尾换行逐一核验通过。
- 未跑完整产品门禁、未提交（按规格）。工作树中未跟踪的 `com/fams/local` 本地替身启动器未参与
  本批定向测试（-Dtest 白名单），未观测到 bean 冲突干扰。
- GitNexus（编辑前实跑，Node 24 前缀 + --repo）：类级 impact 全部 LOW 且与 grep 调用点集合一致
  ——LossRepairPort LOW 1（LossService 构造器）、InventoryLateEvidencePort LOW 2
  （Late/Observation 两服务）、FactoryReplacementInboundPort LOW 1（RepairFactoryService，
  RepairErrorHandler 的 Failure 引用为签名未动的类型使用）；方法级 `create`(repair) LOW 0、
  `create/context`(inbound) LOW 0/1、`append` LOW 4（同名伪边为 inventory prepare/submit 自身，
  grep 核实真实调用点仅 §3 所列两处）。`append` 首次 `-f` 定位报 not found（索引歧义），
  以类级 LOW + grep 全覆盖登记。
- `detect-changes`（收官）：8 文件 34 符号、risk high——含并行会话的 AGENTS.md 在途 hunk
  2 符号（非本批）；Java 侧受影响执行流集中在 loss 恢复两链与 factory replacement 记录链，
  恰为本批消费点，`create/context/status` 跨域同名伪边聚合按 JAVA-08/10a/10b 先例登记。

## 6. Port 批收官证据：全仓 `*Port.java` public string-keyed Map 残留清单

JAVA-10c 后，全仓 65 个 `*Port.java` 的 **public 出口（返回值/入参）string-keyed `Map` 签名**
仅剩以下已裁决/合理项，未类型化的 Map 返回出口归零：

1. `business/BorrowRequestDatePort.outstanding/snapshot`（+实现 `requisition/RequisitionBorrowDatePort`
   同名）——JAVA-05 §6 裁决：内部行 Map 供 loan propose/decide 业务判定，非 wire。
2. `asset/AssetRepairPort.describeReturnTarget`（`Map<String,String>`）——JAVA-10b §6.1 裁决：
   跨域 `TargetChangePort.targetDisplay` 信封接口 Map 面，类型化必然产生 record→Map 回退。
3. `targetchange/TargetChangePort.targetDisplay`（default）与
   `repair/RepairTargetChangePort.targetDisplay`（实现）——同 2 的信封接口本体，
   JAVA-04 §6 登记，留 targetchange 信封批收口。
4. `reminder/WechatSubscriptionPort.Delivery.templateValues`（`Map<String,String>` 分量）——
   键集随微信模板 id 动态，域数据本体是映射而非固定形状结构；已有 `Map.copyOf` 防御拷贝。
5. 行/文档→record 投影工厂（`AssetInventoryReadPort/InventoryExecutorPort/RequisitionMasterDataPort×2/`
   `AttachmentEvidencePort` 的 `fromRow`、`AssetFactoryRepairPort.Identity.of`、
   `RequisitionBorrowDatePort.choice` 及本批新增 `Created/Receipt/Context.fromView`、
   `Task.fromRow`）——端口内部投影的可测性暴露（JAVA-09 §6.4 先例），非对外 wire 面。
6. `asset/AssetLossPort.StoredSource` 的条件键分量（`returnSource/factorySource` 等）——
   JAVA-10b 冻结的存储信封透传分量（嵌入未类型化交接文档），随 loss HTTP 批收口。
7. `Map<Long, record>` 身份查表返回（`AssetInventoryReviewPort.contexts`、
   `InventoryLossPort.progress`、`InventorySynchronizationIdentityPort.states`、
   `business/ReturnLossOpeningPort.latest`）——值类型全部已类型化，键为 ID 查表，不构成未类型化面。
8. 各 Port 私有方法/局部 `LinkedHashMap` 装配（`ScrapInitialPort.proof/source`、
   `RepairInitialPort.origin/identity/event(facts)`、`LossInitialPort.schedule`、
   `InventoryLateEvidencePort.packet/wire`、`AssetInitialPublicationPort.values` 等）——
   协议保留的内部行 Map/存储文档/事件 payload。

## 7. 偏差与遗留

1. **裁决点：`LossRepairPort`/`FactoryReplacementInboundPort.create` 收敛为窄身份 record**
   （Created/Receipt 各仅 id 一个分量）：grep main+test 证实整份 view/receipt 文档除 id 外
   零读者、零序列化、零嵌入（adoptLossRecovery/Replaced 先例）。RepairService/InboundService
   自身的 Map 出口属各自域 HTTP 批（§7.4 排除项），本批不顺手。
2. **Created.fromView/Context.fromView 的 null 分叉登记**：改前 Map 组装在 view/receipt 恒含
   非空 id/version（PK 与 DDL NOT NULL），该路径不可达；fromView 对 id 取 null 安全 toString、
   对 version 保持 Number 拆箱即 NPE 的 fail-fast 口径（改前 Map.of 对 null 同样抛错）。
   replacementView 的 `inbound:null` 隐藏态语义未动。
3. **`InventoryLateEvidencePort.Task` 不承载整行**：append 只读三列（其余观察行/封存行在 Port 内
   自查询）；消费者持有的完整 task 行 Map 在调用点之外用途不变（wire(task) 等 HTTP 组装未动）。
4. **排除清单（按规格零触碰）**：各域 HTTP 出口 Map（inventory/inbound/requisition/repair/loss/
   asset/masterdata/auth/report/reminder 的 controller/service 响应）——独立 HTTP 批体量；
   `repair/inbound` HTTP 批需收口本批登记的 `inbound` 嵌入与 `inboundReceiptId/repairId` 宿主信封；
   各 Port 私有行 Map、审计 payload、指纹 digest 输入链；`apps/web`、`apps/miniprogram`、
   `contracts/`、`packages/api-types`；工作树其它会话在途文件（AGENTS.md、
   `apps/miniprogram/project*.config.json`、未跟踪 `com/fams/local`）。
5. GitNexus MCP 服务在本会话不可直连，impact/detect-changes 按规格用 CLI（Node 24 前缀 + --repo）
   实跑，结果见 §5。

## 8. 下一步

主代理在本批候选上实跑产品门禁完整 Java 序列（重点：loss 恢复转报修两流程的
recovery/execution_snapshot_json 存储列与审计回放、repair factory recordReplacement→replacements
GET 的 inbound 嵌入与 inbound_receipt_id 列、inventory late prepare/submit 的 late_case
snapshot_json/snapshot_sha256 与 LATE_DAMAGE 分支、幂等 24h 窗口新旧存档混放回放），并回填 §5。
契约零改动，契约门禁无需变更。Port Map 面至此收官（§6）；后续为各域 HTTP 出口批（JAVA-11～17 规划）。

## 8. 主代理门禁证据（2026-11-22，Port 批收官）

排除并行 WIP（`com/fams/local` 双 bean 冲突源，属其它会话，未动）的树副本上完整
`verify_java_quality`：83 步全部 exit=0，surefire 聚合 **819 项测试 0 失败 0 错误**，
Checkstyle/SpotBugs 0，覆盖率 **LINE 27376/29405（93.10%）、BRANCH 9902/13081（75.70%）**；
主树 15 个 WireShape 套件复跑 **111/111**。证据：
[JAVA-10c-java-gate.txt](evidence/JAVA-10c-java-gate.txt)。

**Port 批收官结论**：全仓 `*Port.java` 的 public 字符串键 Map 出口已归零，仅剩 §6 登记的
已裁决残留（借期内部行、信封显示面、微信动态模板值、投影工厂入参、存储信封分量、
查表 Map 与私有行 Map）。后续类型化工作转入各域 HTTP 出口波次（11a loss ∥ 11b reminder ∥
11c attachment 起步）。
