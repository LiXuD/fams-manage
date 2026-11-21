# JAVA-06 · AttachmentEvidencePort.list 信封类型化（五域消费点）

> 用户指定的后端结构批（JAVA-01 transfer 打样、JAVA-02 loan、JAVA-03 scrap、JAVA-04 跨域信封之后），不进入 D6-01～D6-27 台账、不占用其状态。
> 日期：2026-11-21 · 分支：main · 基线：`2676458`（JAVA-04 提交）
> 状态：主代理审计与裁决点复核通过，三批合并门禁通过，已收口提交。与 JAVA-05 并行实施互不触碰；JAVA-07 在本批后段级合并 ScrapViews。

## 1. 背景与红线

`attachment/AttachmentEvidencePort.list(type, scope)` 是附件域唯一的跨域读信封，改前返回
`List<Map<String,Object>>`（七个 SQL 别名键），由 loss/scrap/inventory/repair 四域 15 个文件
注入消费。本批把 list 返回类型化为 port 内 `Metadata` record 并同步全部消费点，红线复刻
JAVA-01～04：

1. **createdAt 原值透传（最高红线，JAVA-01 §3 JDBC 教训）**：`Metadata.createdAt` 为
   `Object`，port 不做任何转换（DATETIME(3) 驱动形态 Timestamp/LocalDateTime）。改前各域
   投影分两类，逐一对应保留：
   - **透传类**：scrap 附件列表（原 ScrapViews.attachmentMetadata 即透传）、inventory
     报修证据照片列表（控制器原样包 `Map.of("items", rows)`）——改后直接序列化 record，
     createdAt 仍是 JDBC 原值。
   - **转换类**：loss/repair/factory 的通用 `wire()` 与 inventory 照片控制器的
     createdAt 重写（Timestamp→`toInstant().toString()`、LocalDateTime→UTC instant
     字符串）——改后由具名映射逐字复刻，其他键不受影响。
2. **不得 record→Map 回退**：消费点原来把 list 行塞进通用 `wire(Object)` 助手的，一律改
   为 record→该域投影的具名映射（嵌套 record + 静态工厂）；通用 wire() 本体保留，继续
   服务未改的内部行 Map 出口。
3. **比较口径**：序列化 JSON 文本再解析比树（整型宽度归一、键序不参与；键存在性、显式
   null、值形状全部参与）。sizeBytes/businessVersion 由 JDBC 原值 Long 改 long 分量：
   V8 核实 `size_bytes`/`business_version` 均 BIGINT NOT NULL（七列全 NOT NULL），文本
   往返归一宽度，wire 等价（JAVA-01 totalQuantity 先例）。
4. **防御拷贝**：Metadata 与各域投影 record 均无 List/Map 分量，无需紧凑构造器拷贝
   （规格明示；Object createdAt 透传对照 JAVA-04 Confirmation/Handover 先例）。
5. 契约 `contracts/fams-core-v1.yaml` 与 `packages/api-types` 零改动：形状逐字段等价。

## 2. 改动清单

主代码 11 改，测试 1 改 1 增（in-scope 合计 +227/−79，不含新增测试文件）：

| 文件 | 变化 |
|---|---|
| `attachment/AttachmentEvidencePort.java` | `list` 返回 `List<Metadata>`；新增 `Metadata` record（七分量镜像 SQL 别名，createdAt Object 透传）与 `public static fromRow(Map)` 唯一生产行映射（供 list 与跨域 wire oracle 共用）。`metadata()`（private 内部行 Map）、grant/read/clean/discard 一字未动 |
| `scrap/ScrapViews.java` | 删除 `AttachmentMetadata` record 与 `attachmentMetadata()` 边界转换（−24 行）；其余投影不动 |
| `scrap/ScrapAttachmentService.java` | `list` 直接返回 `List<AttachmentEvidencePort.Metadata>`（原逐行边界转换删除，形状不变） |
| `scrap/ScrapAttachmentController.java` | list 端点返回类型同步（仅签名） |
| `loss/LossService.java` | 仅 get()/decide() 两处 `wire(photos.list(...))` → `photoProjection(...)`；新增嵌套 `Photo` record（七键）+ 静态工厂 + `photoTime()`（逐字复刻 wire() 时间分支）。decide 的 approval_snapshot_json 冻结形状等价（oracle 覆盖）；470 行 isEmpty 检查不改；通用 wire() 本体不动 |
| `repair/RepairService.java` | 仅 get() 一处 photos 行 → `photoProjection(...)`；新增嵌套 `Photo` + 工厂 + `photoTime()`（wire() 的 id/_json 规则对照片键均不命中，只有 createdAt 重写）。loanWire()/wire()/信封三方法等其余助手不动 |
| `repair/RepairFactoryService.java` | 仅 round() 一处 files 行 → `fileProjection(...)`；新增嵌套 `EvidenceFile` + 工厂 + `fileTime()`（复刻 wire() 的 Timestamp/LocalDateTime/Instant→instant() 分支；_json/Date/BigDecimal 分支对照片键不命中） |
| `repair/RepairCollaborationService.java` | `photoProjection(List<Map>)` 改 `photoProjection(List<Metadata>)`，返回嵌套 `Photo`（**仅五键** objectId/filename/contentType/sizeBytes/scanStatus，businessVersion/createdAt 从不进该 wire，逐字保持）；get() 两处调用点文本不变 |
| `inventory/InventoryPhotoReadService.java` | `list` 返回 `List<Metadata>`（透传）；新增嵌套 `Photo` + `public static photo(Metadata)`，逐字复刻原控制器的 createdAt 重写投影；删除不再使用的 Map 导入 |
| `inventory/InventoryPhotoController.java` | list 端点的 LinkedHashMap 拷贝+createdAt 转换 lambda → `InventoryPhotoReadService::photo` 具名映射（算法逐字迁移，形状不变） |
| `inventory/InventoryRepairPhotoReadService.java` | `list` 返回 `List<Metadata>`，过滤条件 `row.get("objectId").toString()` → `row.objectId()`；控制器原样透传不改（createdAt 保持 JDBC 原值） |
| `scrap/ScrapWireShapeTest.java` | attachmentViewsMatchLegacyProjection 用例两处 `ScrapViews.attachmentMetadata(row)` → `AttachmentEvidencePort.fromRow(row)`（断言与行数据一字未动），补 import |
| `attachment/AttachmentEvidenceWireShapeTest.java`（新增） | 9 条判据，见 §3 |

**15 文件消费清单核实结果（grep 逐一核对）**：实际调用 `list` 的只有上表 7 个服务
（scrap/loss/repair×3/inventory×2）+ ScrapViews 边界转换。其余 7 个注入文件
（LossPhotoService、RepairPhotoService、RepairCollaborationPhotoService、
RepairFactoryEvidenceService、ScrapReviewService、InventoryObservationService、
InventoryUploadRetention）只消费 grant/read/clean/discard（签名未改），**零改动**，
编译通过核实。ScrapReviewService 853 行的 `attachmentEvidence` 来自
fams_scrap_event.detail_json 行 Map，与本信封无关，未动。

## 3. 判据：wire 等价测试

`AttachmentEvidenceWireShapeTest`（9/9，com.fams.attachment）把改前「list 行→各域投影」
算法逐字冻结为参照 oracle：`legacyLossWire`（LossService.wire 全文）、`legacyRepairWire`
（RepairService.wire 全文含 _json 分支）、`legacyFactoryWire`+`legacyFactoryInstant`
（RepairFactoryService.wire+instant() 全文）、`legacyCollaborationProjection`（五键循环）、
`legacyInventoryPhotoItem`（控制器 LinkedHashMap 转换）、`legacyScrapMetadata`（被删除的
ScrapViews.attachmentMetadata）。用例：

1. port 行映射镜像别名行（Timestamp/LocalDateTime 双形态 + Integer 宽度变体），
   `assertSame` 断言 createdAt 原值透传；
2. scrap 附件列表与改前边界投影等价（双形态）；
3. loss photos 与冻结 wire() 等价（双形态；同一断言覆盖 decide 的
   approval_snapshot_json 存储冻结形状）；
4. repair photos 与冻结 wire() 等价（双形态）；
5. factory files 与冻结 wire() 等价（Timestamp/LocalDateTime/**Instant** 三形态，
   覆盖 factory 独有的 Instant 分支）；
6. collaboration 五键投影等价，且断言 typed JSON 无 businessVersion/createdAt 键、恰 5 键；
7. inventory 照片项与冻结控制器投影等价（Timestamp/LocalDateTime/已是字符串三形态，
   含 `Map.of("items", …)` 信封）；
8. inventory 报修证据列表原样透传（`Map.of("items", rows)` vs record 列表，双形态，
   createdAt 不转换）；
9. 转换类投影把两种驱动形态归一为同一 instant 文本（loss/repair/factory/inventory 照片
   四链），透传类不做跨形态归一断言（保留驱动差异正是红线）。

比较口径与 JAVA-01～04 相同：各自序列化 JSON 文本再解析比树。单元 oracle 边界沿用
JAVA-01 教训：只证明「行/装配→视图」序列化等价；真实 HTTP 运行时用例（附件上传/照片
读取链）由主代理门禁实跑。

## 4. 门禁证据

实施代理已跑（非产品门禁）：

- 定向单测：`JAVA_HOME=graalvm-jdk-21.0.7 ./mvnw -pl backend -Dtest='AttachmentEvidenceWireShapeTest,ScrapWireShapeTest,TransferWireShapeTest,LoanWireShapeTest,ProxyWireShapeTest,TargetChangeWireShapeTest' -DfailIfNoTests=false test`
  → **Tests run: 67, Failures: 0, Errors: 0**（AttachmentEvidence 9、Scrap 12、Transfer 17、
  Loan 11、Proxy 9、TargetChange 9；Loan 计数变化属并行 JAVA-05 工作树改动，非本批）。
- 全仓编译：`./mvnw -pl backend -q test-compile` → exit=0。
- Checkstyle 项目口径：`./mvnw -pl backend -Pquality checkstyle:check@check-java-conventions`
  → exit=0，0 违规（含新文件末尾换行）。

产品门禁完整 Java 序列（与 JAVA-05/06/07 合并树单次实跑，2026-11-21，JAVA-03/04 先例）：
**83 步全部 exit=0**，surefire 聚合 **778 项测试 0 失败 0 错误**，`verify_java_reports` 通过
（Checkstyle 0、SpotBugs 0），覆盖率 **LINE 27219/29236（93.10%）、BRANCH 9882/13045（75.75%）**。
主代理合并树复跑单元判据 70/70。证据：[JAVA-05-06-07-java-gate.txt](evidence/JAVA-05-06-07-java-gate.txt)。

**主代理裁决点复核（2026-11-21）**：①port 类级 HIGH 23 闭包全在纳入域，接受；
②LossService.get CRITICAL 110 实际 hunk 仅 get/frozen 两处 photos 调用行 + 静态投影工厂，
均在 loss 域内，接受；③RepairService.get CRITICAL 131 经 diff 核实仅一行 photos 投影 +
静态工厂，validateChangedReturnTarget 未动，索引伪边判定可信（JAVA-04 同族工具缺陷），接受；
④collaboration.get HIGH 37 闭包全 repair 包，接受。createdAt 原值透传（assertSame 判据）、
repair/loss 转换类投影逐字复刻 wire() 时间分支、collaboration 五键保持，独立核实通过。

## 5. 影响分析（GitNexus）与裁决点

编辑前按 AGENTS 执行 `impact -d upstream`（Node 24 前缀 + --repo）。

**索引故障与修复（如实登记）**：首轮 impact 中 LossService.get/RepairService.get/
RepairCollaborationService.get 出现 JAVA-04 §5 同族损坏特征（大量类内互调边方向反转，
如 factoryProcessor/view/minimal 等被标为 get 的调用方）。`clean -f` + `analyze` 全量
重建（24,028 节点/58,404 边）后该族伪边仍在，方法级计数不可全信；以下结论均以
**源码逐一核实 + grep 消费清单**为准，impact 标签如实登记。

- `AttachmentEvidencePort.list`（方法）：LOW 7，闭包 = 3 个 list 服务 + 3 个控制器
  （全在纳入域）；depth-3 的 masterdata/LocationMapper 是同名 list 方法的 HAS_METHOD
  伪关联，grep 证实无引用。loss/repair 经 ObjectProvider 动态分派未被静态跟踪，已用
  grep 逐一核实（§2）。
- `AttachmentEvidencePort`（类）：**HIGH 23（裁决点①）**。21 直接 = 14 个注入文件
  IMPORTS/ACCESSES + ScrapService.java/ScrapInitialPort.java 两条 IMPORTS 伪边
  （两文件源码无任何 attachment 引用，grep 证实）；depth-2 即该两文件。**闭包全部落在
  loss/scrap/repair/inventory 纳入域，无排除包符号**，登记后继续。
- `ScrapViews.attachmentMetadata` LOW 2、`ScrapAttachmentService.list` LOW 1、
  `InventoryPhotoReadService.list` LOW 1、`InventoryRepairPhotoReadService.list` LOW 1、
  `InventoryPhotoController.list` LOW 0、`LossService.decide` LOW 1、
  `RepairFactoryService.round` LOW 1：闭包均为纳入域内控制器，无外溢。
- `LossService.get`：**CRITICAL 110（裁决点②）**。闭包包分布 loss 110 + inventory 1
  （InventoryReadinessService.reportMissing，纳入域），**无排除包**；CRITICAL 来自 29 条
  执行流权重与反转伪边。本批编辑仅 get/decide 各一行 photos 投影 + 新增嵌套 record/
  工厂，get 签名与其余行为未动，登记后继续。
- `RepairService.get`：**CRITICAL 131（裁决点③，方法级外溢登记）**。depth-3 出现排除包
  targetchange/TargetChangeWorkflow.propose/decide。`trace` 显示声称路径
  propose→RepairTargetChangePort.validateTarget→RepairService.validateChangedReturnTarget→get；
  **源码核实该末跳为伪边**：validateChangedReturnTarget（1783–1818 行）只调用
  header/number/assets.approvedReturnTarget/approvedTarget/json，绝不调用 get；get
  （275–488 行）也绝不调用 targetChangeContext/proxySnapshot/proxyReceipt。真实上游仅
  RepairController.get。clean 重建后伪边仍在（工具缺陷，非代码事实）。本批编辑仅 get 内
  一行 photos 投影 + 新增嵌套 record/工厂，diff hunk 核实无其他业务逻辑改动，不可能
  触及 targetchange 执行流。规格强制类型化该消费点，无替代路径可避开该标签；据此登记
  后继续，**请主代理/用户优先复核此裁决**。
- `RepairCollaborationService.get`：**HIGH 37（裁决点④）**；`photoProjection` MEDIUM 31。
  闭包 100% repair 包（含本批不改的控制器/照片服务），无排除包；编辑仅 photoProjection
  本体类型化，get 两处调用点文本不变，登记后继续。
- `detect-changes`：33 文件 67 符号、81 执行流、聚合 critical——**含并行 JAVA-05 代理
  改动（business/loan/requisition 6 文件）、用户未提交小程序改动与 AGENTS.md，均不属
  本批**；critical 标签主要来自上述 get 伪边挂接的执行流权重。本批 in-scope 文件集
  （§2 的 11 主代码 + 2 测试）经 git diff 逐一核实无计划外 hunk。

## 6. 偏差与遗留

1. **排除清单（一个文件未碰，按规格）**：`AttachmentEvidencePort.metadata()`/grant/read/
   clean/discard；各域内部行 Map、审计 payload、幂等指纹 digest；请求体 record；
   `business/BorrowRequestDatePort`、`requisition/RequisitionBorrowDatePort`、`loan/` 包
   （并行 JAVA-05，工作树中其改动未混入、未触碰）；`asset/`、`inbound/`、`assetimport/`、
   `masterdata/`、`auth/`、`targetchange/`、`proxy/`、`transfer/` 包（grep 未发现额外
   list 消费点落在这些包；requisition/ProxyConfirmationAttachmentController 的
   reads.list 是另一读服务，不经本信封）；`apps/web`、`apps/miniprogram`、`contracts/`、
   `packages/api-types`；用户未提交的小程序改动与 AGENTS.md。
2. **零改动注入文件（7 个）**：只消费 grant/read/clean/discard 的注入文件无需同步（§2），
   不是遗漏。
3. **投影 record 为服务内嵌套 public record + public static 工厂**：loss/repair 域没有
   现成 Views 类，本批只类型化一个照片投影，按最小改动原则不新建域级 Views 文件；
   public 可见性是为让跨域 oracle 测试（com.fams.attachment）直接调用生产映射
   （JAVA-03 测试调用生产工厂的同款判据强度）。ArchUnit 只导入 production classes，
   测试跨域 import 不构成模块环；主代码新增依赖方向与改前完全一致（各域→attachment）。
4. **通用 wire() 助手保留**：loss/repair/factory 的 wire() 仍服务大量未改的内部行 Map
   出口（深度边界，JAVA-01 §6.2 先例）；照片投影的时间分支在具名映射中逐字复刻，两份
   算法由 oracle 判据锁定一致（JAVA-04 §6.5 双份 clean() 先例）。
5. **旧幂等/存储形状回放**：loss decide 的 approval_snapshot_json 冻结投影经 record 再
   序列化键序归一为声明序（旧 LinkedHashMap 键序本非契约），语义等价；本批未改任何
   反序列化入口（frozen 快照只被读为 JsonNode/Map），无跨版本回放风险。
6. **裁决点③的索引伪边**：clean 全量重建未能消除大类方法级反转 CALLS 边（JAVA-04 时
   clean 可修复，本批不可），已如实登记为工具缺陷；后续批次的方法级 impact 结论建议
   一律以源码核实兜底。
7. 工作树中并行 JAVA-05 代理的改动（含 LoanWireShapeTest 计数变化）不属本批，未混入。

## 7. 下一步

附件读信封收口后，JAVA-01 §6.1/JAVA-04 §7 登记的跨域遗留仅剩
`LoanRepairPort`/`LoanReturnRepairPort`（repair/scrap/inventory 深耦合）与 JAVA-05 正在
处理的借期信封。建议主代理：优先复核裁决点③（RepairService.get 的 targetchange 伪边
外溢），在固定候选上实跑产品门禁完整 Java 序列（含附件上传/照片读取真实 HTTP 链），
并关注大类方法级 impact 的工具缺陷是否需要上报 GitNexus。