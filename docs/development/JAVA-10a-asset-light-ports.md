# JAVA-10a · asset 域五个轻量跨域 Port 的 Map public 面类型化

> 用户指定的后端结构批（JAVA-01 打样、JAVA-02～07、JAVA-08/09 之后的 asset 轻量 Port 收口），
> 不进入 D6-01～D6-27 台账、不占用其状态。JAVA-08 §6.1/JAVA-09 §6.1 登记的 asset 包候选批前半。
> 日期：2026-11-21 · 分支：main · 基线：`b82e46a`（JAVA-09 提交）
> 状态：主代理审计通过，产品门禁完整 Java 序列通过（见 §4），已收口提交。

## 1. 背景与红线

asset 包五件轻量 Port 的 public 面仍返回/接收 `Map<String, Object>`。本批复刻 JAVA-01/08 标准
（`AssetTransferPort.Snapshot/Custody/After` 为核心样板：record 归 Port 拥有、`@JsonProperty`
保持被序列化存储的键形），红线：

1. **存储形状最高红线**：两处 Port 返回值被 `writeValueAsString` 原样写进 DB JSON 列——
   `AssetHandoverPort.stock` 的 8 键 snake_case 行进 `fams_requisition_handover.formal_custody_snapshot_json`
   （RequisitionHandoverService.deliver，V21 DDL NOT NULL 列）；`AssetFactoryRepairPort.snapshot`
   的 6 键 camelCase 投影进 `fams_repair_factory_round.original_identity_json`（RepairFactoryService
   dispatch 创建 round 时写入，并被 requireOriginal 跨调用回放比对）。两 record 以 `@JsonProperty`
   逐键冻结原键形（含显式 null 键：model/serialNumber），判据用「writeValueAsString(新) ≡
   writeValueAsString(旧行投影)」证明。
2. **嵌入未类型化 HTTP 的 wire 一字不动**：`AssetFactoryRepairPort.snapshot` 另被
   `result.put("asset", …)` 嵌入 repair 未类型化 Map HTTP 响应（RepairFactoryService detail 装配，
   ~1568 行）；`Replaced.id/version` 的**值**被嵌入 repair `replacement()` 的 Map HTTP 响应与幂等
   存档 result（`result.put("replacementAssetId", …)`）。record 序列化对象形不变，登记该域 HTTP
   批再收口。`AssetFactoryReplacementPort.trace` 的返回是
   `GET /api/v1/assets/{id}/factory-replacements` 的**响应体本体**（单端点、信封仅 `{"items":[…]}`），
   按 JAVA-09 `ExecutorChoice` 先例直接类型化（分量名镜像 wire camelCase 键），等价由判据证明。
3. **SQL/锁/异常码零改动**：五个 Port 与全部调用方的 SQL 文本、`FOR UPDATE` 锁序、
   `ASSET_HANDOVER_CHANGED`/`REPAIR_FACTORY_ORIGINAL_CHANGED`/`RETURN_LOCK_CHANGED` 等异常码一字未动。
   调用方只改消费方式，无 record→Map 回退。
4. **禁回退**：全批无任何 record 转回 Map 的适配层。
5. 契约 `contracts/fams-core-v1.yaml`、`packages/api-types`、`apps/web`、`apps/miniprogram` 零改动。

## 2. 每方法处置与改动清单

主代码 7 改 0 增，测试 1 增（+134/−59，不含新增测试文件）：

| 文件 | 变化 |
|---|---|
| `asset/AssetHandoverPort.java` | `stock→Stock` record（8 分量 @JsonProperty snake_case，显式 null 保持；Number 安全转换）+ 包私有 `fromRow` 行投影；SQL 与校验一字未动；删失效 LinkedHashMap 导入 |
| `asset/AssetReturnPort.java` | `adoptLossRecovery→Adopted(long id, long version)` 窄身份 record——改前返回的整行 Map 经 grep 证实**零读者、零序列化、零嵌入**（唯一调用方 AssetReturnService.proposeLossRecovery 丢弃返回值），按「少列即少契约」只留被锁校验的身份两列；SQL/UPDATE lock 语句不动 |
| `asset/AssetFactoryReplacementPort.java` | `create→Replaced(String id, long version)`（改前 3 键 Map 中 `assetCode` 无读者，删除即少契约）；`trace→Trace(List<TraceItem>)` + `TraceItem` 9 分量镜像 camelCase wire 键，`List.copyOf` 防御拷贝（JAVA-01 先例），包私有 `TraceItem.fromRow`；`createOwned` 同步签名 |
| `asset/AssetFactoryRepairPort.java` | `snapshot→Identity` record（6 分量 @JsonProperty camelCase，存储键形冻结）+ 包私有 `of`；`requireOriginal` 入参 `Map current → Identity current`（逐字比较语义不变：assetId/assetCode/serialNumber + null 分支）；内部 recordPartsObservation/move 改访问器 |
| `asset/AssetFactoryReplacementController.java` | `/factory-replacements` 端点返回 `Trace`（wire 形状不变，JAVA-09 InventoryController 先例）；删失效 Map 导入 |
| `requisition/RequisitionHandoverService.java` | 仅 2 处 `(String) asset.get("asset_code")` → `asset.assetCode()`（proxySnapshot/targetChangeContext 嵌入投影）；deliver 的 `writeValueAsString(stock)`、warehouseAction/receipt 的丢弃调用零改动 |
| `repair/RepairFactoryService.java` | 仅 replacement() 3 处消费改访问器（`created.id()`/`created.version()`）；`result.put("asset", assets.snapshot(…))` 嵌入点与 round dispatch 存储点无需改码（record 直序列化等价）；**RepairFactoryGuard 零改动**（var 类型推断，snapshot→requireOriginal 全链类型自洽） |

出口 → record 对照：

| 出口 | 改前 | 改后 |
|---|---|---|
| AssetHandoverPort.stock | `Map` 8 键 snake | `Stock`（@JsonProperty snake，存储形不变） |
| AssetReturnPort.adoptLossRecovery | `Map` 整行（无人读） | `Adopted(id, version)` |
| AssetFactoryReplacementPort.create | `Map.of(id, version, assetCode)` | `Replaced(id, version)` |
| AssetFactoryReplacementPort.trace | `Map.of("items", List<Map>)` | `Trace(List<TraceItem>)`（HTTP 体等价） |
| AssetFactoryRepairPort.snapshot | `Map` 6 键 camel | `Identity`（@JsonProperty camel，存储形不变） |
| AssetFactoryRepairPort.requireOriginal | 入参 `Map` | 入参 `Identity` |

`asset/AssetInitialPublicationPort`：grep 核实 public 面（`create→Created`、`assertReversible`、
`cancel`）**无 Map 签名**；catalog 计 3 的 Map 全为内部件（`values` 装配 HashMap、`mapper.lock/tag`
行 Map、事件 `Map.of` 审计 payload），按规格保留——本批零改动。

调用方清单以实际 grep 为准（全仓 main+test）：

| 消费文件 | 消费内容 | 处置 |
|---|---|---|
| `requisition/RequisitionHandoverService` | stock 5 处（deliver 存储序列化 ×1、丢弃 ×2、asset_code 读取 ×2） | 2 处访问器 |
| `loan/AssetReturnService` | adoptLossRecovery ×1（丢弃返回值） | 零改动 |
| `repair/RepairFactoryService` | snapshot ×5（dispatch 存储、detail 嵌入 HTTP、requireOriginal 前置 ×3）、requireOriginal ×3、replacements.create ×1 | create 3 处访问器 |
| `repair/RepairFactoryGuard` | snapshot ×1 → requireOriginal（dispatchedIdentity） | 零改动 |
| `asset/AssetFactoryReplacementController` | trace ×1（HTTP 体） | 返回类型同步 |
| `asset/AssetFactoryRepairPort` 内部 | move/recordPartsObservation 调 snapshot+requireOriginal+serialNumber | 本文件访问器 |
| 测试 | grep 证实无直接调用这五个 Port 的既有测试（HTTP 运行时用例经端点覆盖） | 仅新增判据 |

## 3. 判据：AssetLightPortsWireShapeTest（7/7）

`asset/AssetLightPortsWireShapeTest` 把改前形状逐字冻结为参照 oracle（原投影算法逐字复刻为
`legacyStockProjection/legacyIdentityProjection/legacyTraceItem`）：

1. **Stock ≡ formal_custody_snapshot_json 改前行投影**：null model 显式键保持、键集恰为 8 键、
   version 数值形态 + Integer/Long 整型宽度变体等价。
2. **stock 消费面**：proxy/target-change 的 assetCode 嵌入值等价。
3. **Adopted 身份保真**：id/version 与锁定行一致（如实登记：改前整行 Map 零读者，无存储/嵌入 wire）。
4. **Replaced 嵌入值**：`replacementAssetId` 字符串与 version 数值在改前 Map 取值链下逐值相等。
5. **Trace ≡ 改前 HTTP 体**：单/双行、null original_serial 显式键、`{"items":[…]}` 信封等价。
6. **Identity ≡ original_identity_json 存储形**：6 键 camelCase、显式 null serialNumber 保持、
   assetId 字符串/version 数值形态。
7. **requireOriginal 跨版本回放**：新 record 对**改前形状存储串**（oracle 生成）不抛错、
   null-serial 两态判定不变、串漂移仍 409（覆盖幂等窗口内旧 round 记录）。

比较口径沿用 JAVA-01/08/09：各自序列化 JSON 文本再解析比树（整型宽度归一、键序不参与；键存在性、
显式 null、值形状参与）。单元 oracle 边界如实登记：真实 HTTP 运行时链（`/assets/{id}/factory-replacements`、
deliver 存储列、factory dispatch/receive 全链）由主代理门禁实跑（RepairFactoryHttpRuntimeCase 已存在该端点断言）。

## 4. 门禁证据（实施代理本地，非产品门禁）

- 定向六类（graalvm-jdk-21.0.7）：
  `-Dtest='AssetLightPortsWireShapeTest,TransferWireShapeTest,LoanWireShapeTest,LoanDeepWireShapeTest,ScrapWireShapeTest,SmallPortsWireShapeTest'`
  → **Tests run: 59, Failures: 0, Errors: 0**（AssetLight 7、Transfer 17、Loan 11、LoanDeep 5、
  Scrap 12、SmallPorts 7）。
- 全仓编译：`./mvnw -pl backend -q test-compile` → exit=0。
- Checkstyle 项目口径：`-Pquality checkstyle:check@check-java-conventions` → **0 violations**；
  触及文件末尾换行逐一核验。
- 未跑完整产品门禁、未提交（按规格）。

**主代理产品门禁（2026-11-21 单次实跑）**：`verify_java_quality` 完整序列
83 步全部 exit=0，surefire 聚合 **797 项测试 0 失败 0 错误**，Checkstyle/SpotBugs 0，
覆盖率 **LINE 27276/29298（93.10%）、BRANCH 9878/13041（75.75%）**。
主代理复跑单元判据六类 59/59。证据：[JAVA-10a-java-gate.txt](evidence/JAVA-10a-java-gate.txt)。

## 5. 影响分析（GitNexus，Node 24 前缀 + --repo，编辑前实跑）

- `stock`（方法级，`-f` 消歧）：**HIGH 22**——直接 6：AssetHandoverPort 内部 assign +
  RequisitionHandoverService 5 调用点；depth-2/3 闭包 = RequisitionHandoverController、
  RequisitionProxyReceiptPort、RequisitionTargetChangePort、TargetChangeWorkflow，全部只消费
  本批**签名未变**的宿主方法（改动限于方法体与两个访问器替换）。实际编辑闭包全在纳入集内，
  按规格登记后继续。
- `AssetHandoverPort`（类级）HIGH 29：其余文件均只引用未改动的 `Failure` 类型（异常翻译链）。
- `adoptLossRecovery`：LOW 2（AssetReturnService、LossReturnPort 注入链）。
- `AssetFactoryReplacementPort`：LOW 1（controller）；`create`/`trace` 方法级 LOW 1。
  RepairFactoryService 以全限定名注入，索引未建边，已用 grep 补全实际调用点（唯一）。
- `snapshot`/`requireOriginal`（方法级）：索引报 **CRITICAL 36/27**，为跨域同名方法伪边——
  源码 grep 证实 `AssetFactoryRepairPort` 全仓 import 者仅 RepairFactoryService/RepairFactoryGuard
  两文件（LossService/ScrapService/RepairService/LossController/RepairFactoryController 不引用本 Port，
  JAVA-08 §5 同名伪边先例）；真实闭包全在纳入集内，登记后继续。
- `detect-changes`：22 文件 59 符号、risk medium——含并行无关项（用户未提交的小程序改动 14 文件、
  AGENTS.md 自动更新，均不属本批）。本批 in-scope diff 经 `git diff --stat` 核实仅 §2 表列 7 改 +
  1 新增测试 + 本记录。

## 6. 偏差与遗留

1. **排除清单（按规格零触碰）**：`AssetLossPort`、`AssetRepairPort`、`AssetScrapPort`（JAVA-10b
   重 Port）；`RepairInitialPort`/`ScrapInitialPort`/`LossInitialPort`/`LossScrapSourcePort`/
   `LossRepairPort`/`InventoryLateEvidencePort`/`FactoryReplacementInboundPort`（initial 系）；
   审计 payload、指纹 digest 输入、port 私有内部行 Map（AssetMapper lock/tag 行、事件 detail
   LinkedHashMap、`values` 装配 HashMap）；`apps/web`、`apps/miniprogram`、`contracts/`、
   `packages/api-types`；工作树用户未提交的小程序改动与 AGENTS.md。
2. **adoptLossRecovery 返回值改前即被丢弃**：整行 `SELECT *` Map 无任何读者。裁决为窄身份
   `Adopted(id, version)`（少列即少契约），不复制 30 列整行 record。若后续出现真实读者再扩列。
3. **Replaced 删除 assetCode 分量**：改前 Map 的第三键无消费者（grep main+test 全链），
   按窄 record 规则不入契约。
4. **repair HTTP 批再收口（登记遗留）**：`result.put("asset", Identity)`、
   `replacementAssetId` 值嵌入的 repair 侧 Map HTTP 信封与幂等存档仍 Map（本批 wire 一字未动）；
   `FactoryReplacementInboundPort`（JAVA-10b）与本 record 的衔接参数为基本类型，无需同批。
5. **trace 端点即时类型化而非冻结**：该 Port 返回即端点响应体本体、无未类型化宿主信封，
   按 JAVA-09 `/inventory/executors` 先例收口；`TraceItem.sourceId` 假定 CHAR(36)→String
   （JDBC 驱动事实，DDL V55 核实）。
6. **stock 存储列为写侧等价**：`formal_custody_snapshot_json` 全仓 main 无读取方（V21 起仅写入，
   预留给审计/回放）；判据证明写侧新旧序列化树相等，存量行不受影响。

## 7. 下一步

主代理在本批候选上实跑产品门禁完整 Java 序列（重点：deliver/proxyReceipt/target-change 的
requisition HTTP 链、factory dispatch→receive/parts/replacement 的 repair HTTP 链、
`/assets/{id}/factory-replacements` 与 loss-recovery 运行时用例）并回填 §4 证据；
JAVA-10b（重 Port 与 initial 系）另行开批。契约零改动，契约门禁无需变更。
