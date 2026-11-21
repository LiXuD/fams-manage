# JAVA-11a · loss 域 HTTP 出口类型化

> 用户指定的后端结构批（HTTP 出口波次一 · loss 子批），不进入 D6-01～D6-27 台账、不占用其状态。
> 日期：2026-11-22 · 分支：main · 基线：`d703d6e`（JAVA-10c 提交）
> 状态：主代理审计通过，波次一合并门禁通过（见文末证据节），已收口提交。

## 1. 背景与红线

完全复刻 JAVA-01/02 标准：HTTP 响应类型化；内部行 Map、审计 payload、幂等指纹 digest 输入
保留 Map；契约 `contracts/fams-core-v1.yaml` 与 `packages/api-types`、`apps/web`、
`apps/miniprogram` 零改动。loss 域与先例的关键差异（改前代码逐字冻结为 oracle 核对）：

1. **投影助手语义**：`LossService.view()` 用 `Map.ofEntries` 声明 15 列（**迭代顺序随机**，
   旧响应键序本就非契约）；`id`/`*_id` 走 `value.toString()` 字符串化，其余过 `wire()`：
   Timestamp/LocalDateTime→ISO instant 串、**java.sql.Date→toLocalDate().toString()**
   （next_review_date 双驱动形态防御，同 transfer 教训）、其余原样透传。`wire()` 的 Map/List
   递归分支仅 get() 的 history（fams_loss_event 行）实际使用——列类型全部标量，冻结进
   `LossEvent` 后整方法删除。
2. **条件键与显式 null 键甄别**：`repairId`/`returnId` 在 view() 中**总是 put**（快照缺键时
   显式 null，保持默认包含；全局 mapper 为 JsonConfiguration 的 `Include.ALWAYS`）；
   `transferId` 仅当 recovery 快照存在该节点才 put（NON_NULL 复刻）。detail 的 `lockVersion`
   （可空 Long）、`confirmation`、`recoveryAsset`、`approval`/`execution` 均总是 put 可显式
   null。history 行保留 SQL snake_case 键（`@JsonProperty` 冻结，JAVA-02
   RepairFollowupSource 先例），`detail_json` 是 LONGTEXT 原样 JSON **字符串**不解析。
3. **receipt 存储链独立**：loss 幂等表 `fams_loss_receipt` 的 report_id 走独立列（不像
   transfer finish 需从结果取 `id()/version()`），故本批不引入 Reply 密封接口，replay 直接
   `Class<T>` 泛型化；存储的 response_json 有**三种形状**：view 形状（remember）、
   view+recoveryId/recoveryVersion/recoveryStatus（rememberRecovery）、
   view 覆盖 repairId（recoverToRepair）与 {id,objectId,version}（registerPhoto），
   四类均有旧存储 JSON 回放判据。
4. **closed/非 closed 双形状方法**：recoverToReturn/recoverToTransfer/recoverToRetainedRepair
   按 closed 分别产出 LossReport（remember）或 LossReportWithRecovery（rememberRecovery），
   私有核心以 `Class<T> type` + `type.cast` 收口；公开方法按端点拆为固定返回类型
   （recover-to-* → LossReport；recoveries/*/execute-to-* → LossReportWithRecovery），
   控制器调用点逐一改名，端点路径、请求体与校验零改动。
5. wire 等价比较口径与 JAVA-01/02 相同：各自序列化 JSON 文本再解析比树（归一整型宽度、
   键序不参与；键存在性、显式 null、值形状全部参与）。

## 2. 改动清单

主代码 4 改 1 增，测试 1 增：

| 文件 | 变化 |
|---|---|
| `loss/LossViews.java`（新增） | 10 个响应 record（Page/LossReport/LossReportWithRecovery/LossDetail/ReportActions/LossEvent/LossRecovery/RecoveryActions/PhotoReceipt/PhotoGrant）+ 行映射工厂；含 List 分量的 Page/LossDetail 紧凑构造器 `List.copyOf` 防御拷贝 |
| `loss/LossService.java` | 31 个出口类型化；删除 `view()`/`wire()`（移入 LossViews 具名映射）；`recoveryView` 改产出 `LossRecovery`（actions 四布尔计算原样留在服务侧）；`replay` 泛型化（`Class<T>`）、`remember`/`rememberRecovery`/`registerPhoto` 产出类型化、`saveReceipt` 结果参数改 `Object`（写列仍 `json.writeValueAsString`）；get() 装配改 LossDetail（DB 查询语句与顺序逐条未动；lockVersion/historyTotal/recoveriesTotal 按计划口径补 `Objects.requireNonNull`，COUNT 语义不变）；`decide` 的 approval 快照 `frozen` 仍为 LinkedHashMap（审计/存储 payload，仅 report 值换类型化投影） |
| `loss/LossController.java` | 33 个端点返回类型化视图（eligible/recovery-locations 出口 JAVA-10a 已类型化，未动）；`retry` 辅助泛型化 `<T> T retry(Supplier<T>)`；请求体 record 与校验一字未动 |
| `loss/LossPhotoController.java` | upload 返回 `PhotoReceipt`、grant 返回 `PhotoGrant`（Map.of 装配换 record，键集相同）；content 二进制出口未动 |
| `loss/LossPhotoService.java` | upload 返回 `PhotoReceipt`，`result.get("objectId")` → `result.objectId()`（AttachmentWriter.write 本就泛型，attachment 包零触碰） |
| `loss/LossWireShapeTest.java`（新增） | 7 条 wire 等价用例，见 §3 |

出口 → record 对照：

| 出口 | record |
|---|---|
| LossService.list / followUps | `Page<LossReport>` |
| LossService.get | `LossDetail`（LossReport 全字段 + lockVersion/photosAvailable/photos/history/historyTotal/confirmation/recoveryAsset/recoveries/recoveriesTotal/actions(ReportActions 14 布尔)） |
| submit / submitFactory / submitInternalRepair / decide / cancel / claim / search / confirmLost / followUp / recoverSearching / recoverToRepair / recoverToReturn / recoverToTransfer / recoverToFactory / recoverToInternalRepair | `LossReport`（recoverToRepair 经 `withRepairId` 覆盖 repairId 分量） |
| requestRecovery / requestRepairRecovery / requestReturnRecovery / requestTransferRecovery / requestFactoryRecovery / requestInternalRepairRecovery / decideRecovery / cancelRecovery / claimRecovery / executeRecovery / executeRecoveryToRepair / executeRecoveryToReturn / executeRecoveryToTransfer / executeRecoveryToFactory / executeRecoveryToInternalRepair | `LossReportWithRecovery` |
| LossService.get.recoveries 行 | `LossRecovery`（+密封无关的 `RecoveryActions` 4 布尔） |
| LossService.get.history 行 | `LossEvent`（snake_case @JsonProperty 冻结） |
| LossPhotoController.upload / LossService.registerPhoto | `PhotoReceipt` |
| LossPhotoController.grant | `PhotoGrant` |
| LossService.eligible / recoveryLocations | `AssetLossPort.EligiblePage` / `RecoveryLocationPage`（JAVA-10a 已类型化，复用未动） |
| LossService.Photo / photoProjection | 已类型化面直接复用（LossDetail 引用 `LossService.Photo`） |

## 3. 判据：wire 等价测试

`LossWireShapeTest` 把改前 `LossService.view()`、`wire()` 与 `recoveryView()`（含 actions
计算）逐字冻结为 oracle，并冻结 get()/rememberRecovery()/recoverToRepair()/registerPhoto()
的 Map 装配，构造 JDBC 类型行（Long/Integer/String/Timestamp/LocalDateTime/LocalDate/
java.sql.Date/JSON 串）比对：

- report 行：基础态 + Timestamp/LocalDateTime 双形态 + next_review_date java.sql.Date/LocalDate
  双形态 + recovery 快照 repairId / returnId / transferId 三类键（显式 null 恒在 vs 条件键）
  + recoverToRepair 覆盖形状；
- event 行：wire() 全投影 + occurred_at LocalDateTime 变体；
- recovery 行：PENDING+ADMIN（decide/cancel 真）、VERIFYING+OPERATOR（execute 真）、
  COMPLETED+已决（approval/execution JsonNode 非空）三角色态；
- detail 装配：view 全键 + lockVersion null/有值、photosAvailable/photos、history、
  confirmation 显式 null、recoveryAsset null/RecoveryView、recoveries、14 键 actions；
- 页装配：items/total/page/pageSize 与改前 Map.of 装配比树；receipt：view+3 恢复键、
  PhotoReceipt、PhotoGrant 与旧 Map 装配比树；
- 幂等：LossReport（含/不含 transferId 两形）、LossReportWithRecovery、PhotoReceipt 序列化→
  反序列化→再序列化比树；**改前形状存储 JSON（oracle 生成）反序列化为新 record 并等价再
  序列化**——覆盖 remember/rememberRecovery/recoverToRepair/registerPhoto 四条存储链跨
  版本回放（fams_loss_receipt 无过期窗，收据永久保留，判据更重要）。

**单元 oracle 边界（JAVA-01 教训沿用）**：只证明「行→视图」序列化等价；真实 HTTP 运行时
用例（LossHttpRuntimeCase 3549 行、RepairFactoryHttpRuntimeCase、
InventoryReadinessHttpRuntimeCase、HandoverHttpTest 的 loss 段）由主代理门禁实跑。本批无
跨边界快照直比语义（recovery/execution 快照仍由 Map.of+writeValueAsString 生成，未动）。

## 4. 门禁证据

（留空，主代理补全量序列。）

实施代理已跑（非产品门禁）：

- 定向单测：`JAVA_HOME=graalvm-jdk-21.0.7 ./mvnw -pl backend -Dtest='LossWireShapeTest,TransferWireShapeTest,LoanWireShapeTest' -DfailIfNoTests=false test`
  → **Tests run: 35, Failures: 0, Errors: 0**（Loss 7/7、Loan 11/11、Transfer 17/17）。
- 邻域回归：`-Dtest='LossWireShapeTest,AssetLossPortWireShapeTest,AttachmentEvidenceWireShapeTest,InitialPortsWireShapeTest,Loss*Test'`
  → **Tests run: 28, Failures: 0, Errors: 0**。
- 全仓编译：`./mvnw -pl backend -q test-compile` → exit=0。
- Checkstyle（项目规则集）：`./mvnw -pl backend -Pquality checkstyle:check@check-java-conventions` → exit=0。

## 5. 影响分析（GitNexus）

编辑前按 AGENTS 要求执行 `impact -d upstream`（Node 24 前缀 + --repo，索引 fams-manage）：

- `LossService`/`LossController`/`LossPhotoService`/`LossPhotoController`：全部 **LOW**，
  上游仅 loss 包内（含 InventoryLossPort、ReturnLossOpening、LossErrorHandler）。
- 方法级 `view`/`replay`/`remember`/`rememberRecovery`/`recoveryView`/`saveReceipt`/
  `registerPhoto` 报出的 HIGH/CRITICAL/MEDIUM 全部为**同名伪边**：grep 核实这些方法名在
  repair/scrap/inventory/asset 等域各自独立存在；LossService 内 view/replay/remember/
  recoveryView/saveReceipt 均为 private（编译器封闭），`rememberRecovery` 无 loss 外使用者，
  `registerPhoto` 唯一跨类使用者为同批的 LossPhotoService，`requestRecovery` 的 scrap 命中为
  ScrapReviewService 同名方法。真实上游闭包不出 loss 包，无越批 HIGH/CRITICAL。

## 6. 偏差与遗留

1. **排除清单（保留 Map/不动，按规格）**：
   - 内部行 Map（`header()/recoveryHeader()/recoveryParent()` 的行 Map、`recoveryParent` 返回
     值）、审计 payload（`audit(...)` 的 detail 对象与 `Map.of` 装配）、幂等指纹
     `LossPayloads.fingerprint` 输入、`decide` 冻结的 approval_snapshot_json 外层
     LinkedHashMap、recovery/execution 快照 Map.of——一律未动（approval 快照内 report 值换为
     类型化投影，序列化键值集与形状相同，键序本就随机非契约）。
   - SQL、锁顺序（含 rememberRecovery 中 header→recoveryHeader 的 FOR UPDATE 顺序）、事务
     边界、异常码、方法时序零改动。
   - `reminder/`、`assetimport/`、`attachment/` 包：零触碰（并行 11b/12/11c 在途；工作树中
     reminder 两文件的改动属并行代理，不在本批 diff 内）。
   - `AttachmentEvidencePort.Metadata`、`LossService.photoProjection`、
     `AssetLossPort.EligiblePage/RecoveryLocationPage/RecoveryView/StoredSource`：
     已类型化面直接复用。
   - `contracts/`、`packages/api-types`、`apps/web`、`apps/miniprogram`：零改动。
2. **公开方法拆分**：recoverToFactory/recoverToInternalRepair 原以 `closed` 布尔复用同一公开
   签名返回 Map；类型化后按端点拆为 `recoverToFactory(input)`→LossReport 与
   `executeRecoveryToFactory(input)`→LossReportWithRecovery（recoverToReturn/
   executeRecoveryToReturn、recoverToTransfer/executeRecoveryToTransfer 原有成对公开方法，
   语义不变）。HTTP 路径、请求头、状态码与响应形状零变化。
3. **JDBC 原值口径**：`lockVersion` 由行 Map 原值改 `((Number)).longValue()`（BIGINT→Long，
   文本同值）；history 的 `detail_json` 以 `(String)` 读取（LONGTEXT 驱动返回 String，全仓
   既有先例），`version/assetVersion/ownershipVersion/recoveryVersion/beforeVersion/afterVersion`
   均 BIGINT→long（旧 Map 透传 Long，文本归一）；`occurred_at/last_seen_at` 双驱动形态经
   time() 统一 ISO 串；`next_review_date` 双形态经 toString 统一 yyyy-MM-dd。
4. **NullNode 语义保持**：repairId/returnId/transferId 沿用 `tree.get(key)` + `asText()`，
   快照显式 `"repairId": null` 时新旧同样产出字符串 "null"（当前写侧 Map.of 不可能生成该
   形状，仅保真不修正）。
5. 旧收据经 record 再序列化键序归一为声明序（旧 Map.ofEntries/Map.of 键序本就跨 JVM 随机），
   语义等价；fams_loss_receipt 永久存储仅影响重放响应键序，不涉比较逻辑。
6. `submitInventory`（InventoryLossPort 消费）、`submitReturn`（ReturnLossOpening 消费）
   返回 String/long 身份值，非 HTTP 出口，未动。
7. 观察项（非缺陷、未改）：`LossController.eligible/recoveryLocations` 出口早已类型化，
   本批保持；`registerPhoto` 的 replay 对 PHOTO 操作重放的是三键 PhotoReceipt，与视图形状
   操作互不混淆（operation 主键隔离）。
8. **裁决点：无。** 未遇到形状不可判定、真实 HIGH/CRITICAL 上游越批或需改排除接口。

## 7. 下一步

波次一其余子批（11b reminder、11c attachment）由并行代理交付后主代理合并门禁；后续波次按
12 assetimport∥proxy 残留 → 13 report∥masterdata → 14 auth∥requisition → 15 inbound →
16 repair 系 → 17 inventory 系推进。

## 主代理合并门禁（2026-11-22，波次一三批合并树单次实跑）

排除并行 WIP 副本上完整 `verify_java_quality`：83 步全部 exit=0，surefire 聚合
**844 项测试 0 失败 0 错误**（含 LossHttpRuntimeCase、Messages/Duty 运行时链的真实
HTTP 回归），Checkstyle/SpotBugs 0，覆盖率 **LINE 27445/29468（93.13%）、
BRANCH 9889/13063（75.70%）**；主树 18 个 WireShape 套件复跑 136/136。证据：
[JAVA-11-java-gate.txt](evidence/JAVA-11-java-gate.txt)。
