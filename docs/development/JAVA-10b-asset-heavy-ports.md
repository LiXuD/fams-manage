# JAVA-10b · asset 域三个重 Port 剩余 Map public 面类型化（AssetLossPort / AssetRepairPort / AssetScrapPort）

> 用户指定的后端结构批（JAVA-01 打样、JAVA-02～07、JAVA-08/09/10a 之后的 asset 重 Port 收口），
> 不进入 D6-01～D6-27 台账、不占用其状态。JAVA-10a §6.1 排除的三件重 Port 为本批唯一范围。
> 日期：2026-11-21 · 分支：worktree-agent-general-purpose-904eb898 · 基线：`e1eef65`（JAVA-07 提交；
> 10a 改动在主树在途，未进本基线）。
> 状态：主代理审计通过（含 worktree 基线滞后的 08 交集 hunk 重放），产品门禁完整 Java 序列通过（见 §7），已收口提交。
> 门禁过程如实登记：主树两次红项均归因并行会话在途文件（requisition harness 缺 confirm 桩、
> 未跟踪 LocalMiniServerApplication 双 bean 冲突），非本批回归；排除该 WIP 的树副本全量绿。

## 1. 背景与红线

三 Port 剩余 public 面共 22 个 `Map<String, Object>` 方法签名（13 loss + 6 repair + 3 scrap，
另 `AssetRepairPort.describeReturnTarget` 返回 `Map<String,String>` 见 §6.1 裁决）。复刻
JAVA-01/08/09/10a 标准（`AssetTransferPort.Snapshot`、`AssetHandoverPort.Stock` 键形冻结样板 +
`AssetScrapPort.EligiblePage` 信封样板），红线：

1. **存储形状最高红线**：本批 8 个 Port 出口的返回值被调用方 `writeValueAsString` 原样写进
   DB JSON 列，全部以 `@JsonProperty` 逐键冻结改前键形并由判据证明：
   - `AssetLossPort.Snapshot`（11 键 camelCase，id/*_id 字符串化、version 数值、显式 null）→
     `fams_loss_report.source_snapshot_json`（LossService.insertReport 三处 + LossInitialPort
     open，回读方为 `require*/validate*Recovery` 的 frozen.path 比对与 `reference()` 取值）；
     条件键 `returnSource/factorySource/internalRepairSource/initialSource` 由新增 Port record
     `StoredSource`（`@JsonUnwrapped` 展平 + 分量级 `NON_NULL`）保持「无该键 ↔ 有该键」两态。
   - `AssetRepairPort.Snapshot`（13 键 camelCase，较 loss 多 owningOrganizationId/locationName）→
     `fams_repair_request.source_snapshot_json`（RepairInitialPort open 经 `InitialSource` 信封
     附加恒定 initialSource 键；同文档再整体嵌入 approval_snapshot_json 的 `source` 键）。
   - `AssetRepairPort.ReturnTarget`（11 键 camelCase，id 字符串化、三个 version 数值）→
     `fams_repair_return_handover.target_snapshot_json`（deliverReturn）、
     `fams_repair_approved_return_target.target_json`（applyChangedReturnTarget），并被
     closeReturned/closeApprovedReturn/proxySnapshot 回读比对与嵌入资产事件 detail。
   - `AssetScrapPort.SourceRow`（**14 键 snake_case 且 id/version 全部保持数值原形**——改前是
     原始 JDBC 行 Map 直序列化，与 loss/repair 的字符串化快照不同）→
     `fams_scrap_request.source_snapshot_json`；条件键 `lossEvidence/repairEvidence/initialSource`
     由 `StoredSource`（@JsonUnwrapped + NON_NULL Object 分量，initialSource 兼容 Map 与
     JsonNode 两态）冻结。
2. **嵌入未类型化 HTTP 的 wire 一字不动**：`AssetLossPort.RecoveryView` 被
   `result.put("recoveryAsset", …)` 嵌入 loss 未类型化 Map detail 响应（LossService.get），
   record 分量名即改前 11 键，直序列化等价；登记 loss HTTP 批再收口。三个 eligible/目录/
   return-target/locations 出口是**单端点响应体本体**（/repairs/eligible、
   /repairs/physical-catalog/{kind}、/repairs/{id}/return-target、/loss-reports/eligible、
   /loss-reports/recovery-locations），按 JAVA-09/10a 先例直接类型化，Service+Controller
   返回类型同步，wire 形状由判据证明不变。契约、`packages/api-types`、`apps/*` 零改动。
3. **SQL/锁/异常码/审计/指纹零改动**：三 Port 与全部调用方的 SQL 文本、`FOR UPDATE/FOR SHARE`
   锁序、`LOSS_ASSET_CHANGED`/`REPAIR_RETURN_TARGET_CHANGED`/`SCRAP_ASSET_OR_LOCK_CHANGED` 等
   异常码一字未动；方法调用时序逐链保持（require/validate 拆分出的私有 Row 方法按原顺序调用）。
   资产事件 detail（`Map.of("target", target)` 等）与幂等指纹输入不含本批 record 之外的变化，
   record 直序列化对象形与改前 Map 一致。
4. **禁 record→Map 回退**：全批无任何 record 转回 Map 的适配层；存储文档的「快照 + 附加键」
   装配统一由 @JsonUnwrapped 信封 record 承担。
5. **私有内部行 Map 保留**：`AssetLossPort.snapshot/locked/lockRepairAsset/requireLinkedRecovery`、
   `AssetRepairPort.locked/available/requireActiveTarget/activeLocation/snapshot`、
   `AssetScrapPort.lockedSourceAsset/hasFrozenCustody` 等原样保留；`lossSource` 内部
   `json.valueToTree(row)` 比较链一字未动（判据证明行 Map 与 record 的树同形）。

## 2. 逐方法处置表

### AssetLossPort（13 个 Map 签名）

| 出口 | 改前 | 处置 | 键形依据 / 调用方 |
|---|---|---|---|
| eligible | `Map` items/total/page/pageSize | `EligiblePage(List<Snapshot>,long,int,int)`（List.copyOf） | GET /loss-reports/eligible 响应体本体；LossService.eligible→LossController |
| returnSubmission / initialLossSnapshot / factorySubmission / internalRepairSubmission | `Map` 11 键 camel | `Snapshot`（@JsonProperty camel，显式 null，Number→String 除 version） | 序列化进 source_snapshot_json；调用方 LossService.submitReturn/submitFactory/submitInternalRepair、LossInitialPort.open |
| recoveryView | `Map` 11 键 camel | `RecoveryView`（嵌入未类型化 HTTP，wire 冻结） | LossService.get 的 recoveryAsset 键 + eligible/locked/lifecycle/exceptionStatus/factoryScrapEligible 判定 |
| requireOriginalRecovery / validateOriginalRecovery / requireReturnRecovery / requireTransferRecovery / validateReturnRecovery / validateTransferRecovery | 原始锁定整行 `Map` | 窄 record `RecoverySource(lifecycleStatus,responsibleEmployeeId,usingOrganizationId)`（adoptLossRecovery 先例；grep 证实全链仅此三列有读者） | LossService.requestRecovery/recoverToReturn/restoreOriginal 传 loans.requireRecovery；公开链拆出私有 Row 方法保序 |
| recoveryLocations | `Map` 信封 | `RecoveryLocationPage(List<RecoveryLocation>…)` | GET /loss-reports/recovery-locations 响应体本体 |
| （新增信封） | 调用方 `snapshot.put(key,…)` 可变 Map | `StoredSource(@JsonUnwrapped Snapshot, returnSource, factorySource, internalRepairSource, initialSource)`，条件键 NON_NULL | 冻结 source_snapshot_json 的「快照+交接键」平面文档 |

### AssetRepairPort（6 个类型化 + 1 个保持，见 §6.1）

| 出口 | 改前 | 处置 | 键形依据 / 调用方 |
|---|---|---|---|
| eligible | `Map` | `EligiblePage(List<Snapshot>…)` | GET /repairs/eligible 响应体本体；RepairService/RepairController |
| beginInitialRepair | `Map` 13 键 camel | `Snapshot` + 信封 `InitialSource(@JsonUnwrapped, initialSource)` | RepairInitialPort.open 存储 source_snapshot_json 与 approval 嵌入 |
| physicalCatalog | `Map`，item 键形随 kind 变化 | `PhysicalPage(List<?>…)` + `WarehouseTarget`（3 键）/`LocationTarget`（5 键，warehouse_id 显式 null、NON_ABSENT 由类型分态） | GET /repairs/physical-catalog/{kind} 响应体本体；两 record 复刻条件键集 |
| returnTarget / approvedReturnTarget / proxyReturnTarget | `Map` 11 键 camel | `ReturnTarget`（存储+回读+审计嵌入三用途逐键冻结） | RepairService.returnTarget 端点、effectiveReturnTarget/deliverReturn（存储）、validateChanged/applyChanged（存储 target_json + 参数绑定）、proxySnapshot（ProxyViews 嵌入）、Port 内 close* 链 |
| describeReturnTarget | `Map<String,String>` | **保持**（§6.1 裁决点） | 唯一调用方 RepairTargetChangePort.targetDisplay 是 TargetChangePort 跨域信封接口的 Map 面（JAVA-04 §6 深度边界） |

`ProxyViews.RepairSnapshot.target` 分量 `Map<String,Object>→Object`（去防御拷贝、注释更新）：
live 侧直传 `ReturnTarget`，存量回放侧仍是 LinkedHashMap，双向序列化对象形一致；
`proxyReceipt` 的文本往返比较语义未动（JAVA-01 教训红线），ProxyWireShapeTest 9/9 与
TargetChangeWireShapeTest 9/9 复跑通过。

### AssetScrapPort 剩余面（3 个 Map 签名；eligible=JAVA-07 不动）

| 出口 | 改前 | 处置 | 键形依据 / 调用方 |
|---|---|---|---|
| standaloneSource / lossSource / repairSource | 原始 14 列 snake 行 `Map`（数值 id 原形） | `SourceRow`（@JsonProperty snake，id/version 全数值 long/Long，显式 null）+ 信封 `StoredSource(@JsonUnwrapped, lossEvidence, repairEvidence, initialSource: Object)` | ScrapService.submit/submitFromLoss/createFromRepair→create（source_snapshot_json 存储、lifecycle_status 读取、requireOpenBorrowing 取值）；ScrapInitialPort.openRepair/openLoss/openFactoryDisposition（initialSource 含 Map 与 JsonNode 两态） |

## 3. 改动清单

主代码 12 改（+584/−237 含测试文件），测试 3 增：

| 文件 | 变化 |
|---|---|
| `asset/AssetLossPort.java` | 新增 Snapshot/EligiblePage/StoredSource/RecoveryView/RecoverySource/RecoveryLocation(Page) record；13 个 public 出口类型化；require/validate 链拆私有 Row 方法保序；SQL/异常码零改动 |
| `asset/AssetRepairPort.java` | 新增 Snapshot/EligiblePage/InitialSource/WarehouseTarget/LocationTarget/PhysicalPage/ReturnTarget record；6 出口类型化；resolveReturnTarget/approved/proxy 组装改 record 构造；close* 改访问器 |
| `asset/AssetScrapPort.java` | 新增 SourceRow/StoredSource；3 出口类型化；eligible 与 restore/execute 面零改动 |
| `loss/LossService.java` | eligible/recoveryLocations 签名；recoveryAsset 五处读取改访问器；三处 `snapshot.put(...)` 改 StoredSource 装配；三处 requireRecovery 消费改访问器 |
| `loss/LossController.java`、`repair/RepairController.java` | 5 个直传端点返回类型同步（wire 不变） |
| `loss/LossInitialPort.java` | custody 装配改 StoredSource；INSERT 参数改 snapshot 访问器 |
| `repair/RepairService.java` | eligible/physicalCatalog/returnTarget/effectiveReturnTarget 签名；deliverReturn/validateChanged/applyChanged/proxySnapshot 消费改访问器 |
| `repair/RepairInitialPort.java` | source 改 InitialSource；openFactory 参数改 Snapshot |
| `proxy/ProxyViews.java` | RepairSnapshot.target → Object（注释同步） |
| `scrap/ScrapService.java` | create 参数改 StoredSource；三处装配点改造；responsible/using 读取改访问器 |
| `scrap/ScrapInitialPort.java` | 三处 `new LinkedHashMap<>(...)` + put 改 StoredSource 装配（其自有 SQL 与 open() 直查行零改动） |

## 4. 判据：三个 WireShapeTest（17 条）

- `AssetLossPortWireShapeTest`（7）：Snapshot ≡ 冻结 snapshot() 投影（含显式 null/整型宽度/键集恰 11）；
  StoredSource ≡ 五种存储装配（含条件键缺席态、12 键全形）；frozen original 回放逐字段
  asText 等值；eligible 信封；RecoveryView 三态 ≡ 冻结装配 + recoveryAsset 嵌入等值；
  recoveryLocations 信封；RecoverySource 身份保真（窄 record 如实登记）。
- `AssetRepairPortWireShapeTest`（6）：Snapshot ≡ 冻结 13 键投影；InitialSource 信封 ≡ 存储形 +
  条件 factoryDestination 透传 + approval `source` 嵌入；eligible 信封；physicalCatalog 两 kind
  键集（warehouse 3 键无 location 键 / location 5 键含显式 null warehouse_id）；ReturnTarget
  ≡ 冻结 resolveReturnTarget 装配 + 改前存储串回放逐字段 asLong/asText 等值；
  ProxyViews.RepairSnapshot 的 Map-target 旧装配 vs record-target 直序列化树等值 + 存量存储串
  反序列化后的文本往返比较等值（JAVA-01 proxy 回归红线）。
- `AssetScrapPortWireShapeTest`（4）：SourceRow ≡ 原始行 Map 直序列化（**数值 id**、14 键、显式
  null、整型宽度）；StoredSource 五装配（bare/loss/repair/initial-Map/initial-JsonNode、14 键
  缺席态）；`valueToTree(row) ≡ valueToTree(SourceRow)`（ORIGINAL_CUSTODY 比较面）；
  改前存储串对 record 逐字段 path 回放等值。

比较口径沿用 JAVA-01/08/09：序列化 JSON 文本再解析比树（整型宽度归一、键序不参与；键存在性、
显式 null、值形状参与）。单元 oracle 边界如实登记：真实 HTTP 运行时链（loss/repair 各端点、
deliver/return-target/physical-catalog、initial-import 存储列）由主代理门禁实跑。

## 5. 门禁证据（实施代理本地，非产品门禁）

- 定向（graalvm-jdk-21.0.7）：`-Dtest='AssetLossPortWireShapeTest(+Repair+Scrap),
  TransferWireShapeTest,LoanWireShapeTest,ScrapWireShapeTest'` 三 Port 分别跑
  **47/47、46/46、57/57 全绿**；`ProxyWireShapeTest,TargetChangeWireShapeTest` **18/18 全绿**
  （ProxyViews 触碰复核）。
- 全仓编译：`./mvnw -pl backend -q test-compile` → exit=0。
- Checkstyle 项目口径：`-Pquality checkstyle:check@check-java-conventions` → **0 violations**；
  15 个触及 Java 文件末尾换行逐一核验通过。
- 未跑完整产品门禁、未提交（按规格）。
- GitNexus `detect-changes`：12 文件 94 符号、risk critical/120 执行流——为同名方法跨域伪边
  聚合（recoveryView/eligible/returnTarget/lossSource 等在 loss/repair/scrap/transfer 多域
  重名，JAVA-08/10a 先例）；真实闭包以逐方法 `impact -f` + 全仓 grep 调用点枚举核定（下表）。

影响分析（编辑前实跑，方法级 `-f` 消歧）：
`AssetLossPort` 类 LOW 5、`AssetRepairPort` 类 MEDIUM 9、`AssetScrapPort` 类 MEDIUM 8；
eligible(loss/repair) LOW 2、physicalCatalog LOW 2、returnTarget LOW 9、proxyReturnTarget LOW 5、
standaloneSource LOW 2、lossSource LOW 3、repairSource LOW 7、recoveryLocations LOW 2、
beginInitialRepair HIGH 3（直接=RepairInitialPort.open 本批纳入；depth-2/3 仅经
`InitialRepairPort.open` void 签名触达 AssetInitialPublicationPort/AssetImportPublicationService，
其签名未变、零编辑）；`recoveryView` CRITICAL 81、`requireOriginalRecovery` CRITICAL 23 为
LossService 同名私有方法 `recoveryView`/跨域 recovery 命名伪边——grep 证实真实调用者仅
LossService（163/897-911/1197-1204/1244/1523）与 LossInitialPort，全在纳入集，登记后继续。

## 6. 偏差与遗留

1. **裁决点：`AssetRepairPort.describeReturnTarget` 保持 `Map<String,String>`**。其唯一读者
   `RepairTargetChangePort.targetDisplay` 是跨域多态接口 `TargetChangePort.targetDisplay` 的
   实现（JAVA-04 登记的信封 Map 面，消费方 TargetChangeWorkflow 直接嵌入 `targetDisplay`
   条件键）。类型化它必然在信封适配处产生 record→Map 回退或要求同批改三域信封接口——超出本批
   范围。登记为 targetchange 信封后续批收口。
2. **require/validate*Recovery 六出口收敛为窄 record**：改前返回整行 `SELECT *` Map，grep
   main+test 证实消费面仅 lifecycle_status/responsible_employee_id/using_organization_id 三列
   （loans.requireRecovery 入参），其余列零读者、零序列化——按 adoptLossRecovery 先例不入契约。
3. **RecoveryView 嵌入 loss 未类型化 HTTP detail**（recoveryAsset 键）：record 分量名即改前
   键名，直序列化一字未动；loss HTTP 批统一收口（同 10a repair HTTP 批登记）。
4. **ProxyViews.RepairSnapshot.target 分量 Map→Object**：属 JAVA-04 域文件的调用方适配，
   注释标明新旧两态（live record / 存量 raw map）；`loan` 分量与 deliveredAt 原样保留。
   主代理合并时注意与 10a 之外在途 proxy 改动的 hunk 相邻性。
5. **ScrapInitialPort/LossInitialPort/RepairInitialPort 仅做调用方适配**（10c 候选的 public
   Map 面一字未动）；`ScrapService` 内部 proof LinkedHashMap 属消费者自有装配（非 Port 面），
   原样保留并作为信封 Object 分量直传。
6. **JDBC 类型假定登记**：SourceRow/RecoveryView 的数值列按 V24 fams_asset BIGINT→Long 事实
   取 long/Long（Integer 宽度变体已由判据证明等值）；RecoveryView.locationVersion 为 LEFT JOIN
   可空列取 Long。无 DATE/DATETIME 列被类型化进 record。
7. **排除清单（按规格零触碰）**：JAVA-10c 七件 initial 系 Port；`AssetFactory*`/
   `AssetHandoverPort`/`AssetReturnPort`（10a 在途）；各 Port 私有行 Map 与事件 detail/
   指纹装配链；`apps/web`、`apps/miniprogram`、`contracts/`、`packages/api-types`。
   与 10a 纳入集**无文件重叠**；本 worktree 基线 e1eef65 不含 10a 改动，合并时预期冲突面为零
   （RepairService/RepairInitialPort/LossInitialPort/ScrapInitialPort 为 10c 共同候选文件）。

## 7. 下一步

主代理在本批候选与 10a 合并树上实跑产品门禁完整 Java 序列（重点：loss 五流程存储列回放与
recoverOriginal/Return/Transfer 链、repair deliver→receive/proxy receipt 的 target_snapshot_json
与 approval 回放、physical-catalog/return-target/eligible HTTP 运行时、scrap 四来源
source_snapshot_json 与 review/restore 回放、initial-import 全链），并回填 §5 证据。
契约零改动，契约门禁无需变更。JAVA-10c（initial 系七 Port）另行开批。

## 7. 主代理门禁证据（2026-11-22）

排除并行 WIP 的树副本上完整 `verify_java_quality`：83 步全部 exit=0，
surefire 聚合 **814 项测试 0 失败 0 错误**，Checkstyle/SpotBugs 0，
覆盖率 **LINE 27364/29388（93.11%）、BRANCH 9905/13077（75.74%）**；
主树复跑单元判据 106/106。归因与 run 史见
[JAVA-10b-java-gate.txt](evidence/JAVA-10b-java-gate.txt)。
