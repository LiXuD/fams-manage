# JAVA-17c · inventory 域「差异认定/复核/照片」HTTP 出口类型化

> 用户指定的后端结构批（HTTP 出口波次 · inventory 17c 子批），不进入 D6-01～D6-27 台账、不占用其状态。
> 日期：2026-11-28 · 分支：main · 基线：主树当前状态（JAVA-15 inbound 与 16a/16b/16c repair 已交付未提交，
> 状态：主代理审计通过，波次六合并门禁通过（见文末证据节），已收口提交。
> 状态：实施完成，等待主代理合并门禁。

## 1. 背景与红线

完全复刻 JAVA-01/11a/14b1/15/16a 标准：HTTP 响应类型化；内部行 Map（`queryForList` 行、
`task()`/`observation()`/`candidate()`/`review()` 中间态、审计 `detail_json`、幂等
`payload_sha256` 指纹输入、conflict `fields` LinkedHashMap、照片 `detail_json` Map.of）保留 Map；
契约 `contracts/fams-core-v1.yaml`、OpenAPI 生成类、`packages/api-types`、前端零改动；
SQL 语句、参数顺序、`FOR UPDATE`/`FOR SHARE` 锁顺序、异常码、HTTP 状态码、端点路径、请求体
`InventoryFindingInput`/`InventoryReviewInput` record 一字未动。

SQL 零改动用脚本核证：三个 service 的 SQL 字面量序列（含 `FOR UPDATE`/`FOR SHARE`/`ORDER BY`/
`LIMIT` 片段）与 HEAD 逐条比对完全相同 —— `InventoryFindingService` 49 条、
`InventoryReviewService` 31 条、`InventoryPhotoService` 16 条，`identical=True`；diff 中出现的
SQL 行只是把语句从 `Map.of(...)` 实参嵌套里提为独立局部变量（换行重排，文本逐字相同）。
读序同样保持：finding detail 的 subject→history→TIME subject→recollection→history COUNT，
review detail 的 task→item 分页→contexts→每项 candidate(FOR SHARE)→review(FOR UPDATE)→
normalProblem COUNT→countItems→reviewedCount，recollectionTargets 的页查询先于 COUNT。

本批只触碰 8 个既有文件（`InventoryFindingController`/`InventoryFindingService`/
`InventoryReviewController`/`InventoryReviewService`/`InventoryPhotoController`/
`InventoryPhotoService`/`InventoryRepairPhotoController` + 归属读服务
`InventoryPhotoReadService`**零 diff**）+ 新增 `FindingViews`、`InventoryFindingWireShapeTest`
与本记录。禁触面 `Selection/Difference/Readiness`（17a）、`Seal/Synchronization/Observation`（17b）、
`Inventory 主/Late/LateDamage`（17d）、全部 Port（含 `InventoryAttachmentPort`、
`InventoryInboundPort`、`AssetInventoryReviewPort`、`AssetInventoryReadPort`、
`InventoryRepairPort`）、`repair/`、`inbound/`、`attachment/`、`contracts/`、`apps/`、
`DESIGN-HANDOFF.md`、`AGENTS.md` 全部零 diff；同树并行的 17a/17b/17d 改动不属本批 diff。

inventory 与先例的关键差异（改前代码逐字冻结为 oracle 核对）：

1. **本域存在两种行投影，不得互套**（读码核证，判据 §3-2/§3-8 双向冻结）：
   - `InventoryFindingService.wire(row)` 是**扁平**的：只解析 `facts_json`/`receipt_json` 两列，
     只对 `id`/`*_id` 的 Number 值 `.toString()`，Timestamp/LocalDateTime→ISO instant 串；
     嵌套 JSON 树原样入线。
   - `InventoryReviewService.wire(value)` 是**递归**的：任何 `*_json` 字符串子值都解析后继续投影，
     Number 键除 `id`/`*_id` 外还含 camel `*Id`，Map/List 逐层递归。
   同一 `fams_inventory_observation` 行因此在线上不同：`facts_json.itemId` 在发现侧是**数值 21**、
   在复核侧是**串 "21"**（`actual.locationId` 同理）。故 `FindingViews.ObservationRow` 一个 record
   配 `observation()`/`reviewObservation()` 两个工厂，两条 legacy 口径各判各的。
2. **投影口径**：`SELECT *` 列序取迁移脚本（V31/V32/V33/V34/V37/V42）定义，snake_case 键全部
   `@JsonProperty` 冻结（JAVA-11a/16a 先例）；`id`/`*_id` 数值列串化，`delivered_by` 类不带 `_id`
   后缀的数值列不存在于本域，`assetId`/`inboundReceiptId`/`counterObservationId` 是 SQL
   `CAST(... AS CHAR)` 别名（到达即串），`owner_version`/`link_kind` 来自 LEFT JOIN 可为显式 null；
   `recheck_generation`/`before_version`/`reviewed_asset_version` 等保持数值；DATETIME 双驱动形态
   （Timestamp/LocalDateTime）归一 ISO 串。EXISTS→数值不出现在本批出口。
3. **条件键甄别**：本批全部条件键来自 `Map.of` 的**两套键形**而非 put 守卫——发现目标
   FORMAL_INBOUND（7 键）与 DUPLICATE_ASSET（5 键）联合，以及 normal-acceptance 行
   ACCEPTED（3 键）与 CONFLICT（4 键）联合；两两互斥的额外键用 `@JsonInclude(NON_NULL)` 复刻
   （全局 `JsonConfiguration` inclusion=ALWAYS，其余显式 null 照写）。
   另有两处 **`{}` 空对象态**（非「全 null 行」）：finding detail 的 `subject`（无 FINDING 主体行时
   旧码 put `Map.of()`）与 review/accept 的 `review`/`current`（无复核行时旧码 put
   `Map.of("version", 0)`），逐一见 §6-3。
4. **幂等回放按泛型 `Class<T>` 蓝本**：`fams_inventory_review_receipt.response_json` 永久保留，
   三处旧 `json.readValue(response, new TypeReference<Map<String, Object>> {})` 改为
   `replay(prior, Class<T>)`（服务内私有泛型方法，查询/`FOR UPDATE`/`payload_sha256` 比较留在原流程）：
   - `FindingViews.DecisionReceipt`（5 键）：operation `FINDING:{observationId}`；
   - `FindingViews.RecollectionReceipt`（8 键）：operation `FINDING_TIME:{observationId}`；
   - `FindingViews.NormalBatchReceipt`（6 键，含 items 行联合）：operation `ACCEPT_NORMAL`。
   照片 upload 幂等走 `fams_inventory_photo_receipt` 现查（非回执 JSON 存储），其旧形状由判据
   §3-11 冻结两键等价。
5. **附件/照片面 Port 签名零改动**：`InventoryPhotoReadService.list/grant/read` 仍返回
   `List<AttachmentEvidencePort.Metadata>`/`AttachmentGrantSigner.Grant`/
   `AttachmentReadService.Content`；类型化只发生在控制器的 Map 包壳处（`Map.of("items", …)`→
   `PhotoList`、`Map.of("url", …, "expiresAtEpochSecond", …)`→`DownloadGrant`）。
   `content` 两出口为字节流出口，方法体零改动（含 `X-Content-Type-Options`、
   `Content-Disposition`、`Cache-Control`、`contentLength`）。本域无照片 delete HTTP 出口
   （回收只有 `InventoryUploadRetention` 调度器与 `InventoryAttachmentPort.discard` 内部面）。

## 2. 改动清单与出口映射

主代码 7 改 1 增，测试 1 增，本记录 1 增：

| 文件 | 变化 |
|---|---|
| `inventory/FindingViews.java`（新增） | 23 个响应 record + 2 个密封联合（`SubjectProjection`/`ReviewState`）+ 行映射工厂；`wire(ObjectMapper, Object)` 递归口径自 `InventoryReviewService` 逐字移入（快照树与复核行共用单源）；`wiredJson/wiredTree/reviewNode` 产 JsonNode；含 List 分量的紧凑构造器 `List.copyOf` 防御拷贝；条件键全量 `@JsonInclude(NON_NULL)` |
| `inventory/InventoryFindingService.java` | 5 个出口类型化（targets/detail/recollectionTargets/decide/recollect）；删除私有扁平 `wire(Map)`（投影语义移入 FindingViews 并被 typed 工厂逐列复刻）；新增 `<T> T replay(Map, Class<T>)`；`historyTotal` 补 `Objects.requireNonNull`（COUNT 口径）；`reject` 的 `processedAt` 改取 `FindingViews.subject(...).reviewedAt()`（同文本、同 NPE 语义）；SQL/锁/异常零改动 |
| `inventory/InventoryReviewService.java` | 4 个出口类型化（detail/observations/acceptNormal 及 findings 端点复用 observations）；删除私有递归 `wire(Object)`（移入 FindingViews）；`conflict()` 改产 `NormalBatchItem`；`outcomes` 由 `List<Map>` 改 `List<NormalBatchItem>`；新增 `<T> T replay(Map, Class<T>)`；SQL/锁/异常零改动 |
| `inventory/InventoryPhotoService.java` | `upload` 返回 `FindingViews.PhotoReceipt`；两条返回分支（精确重放与首次登记）与 `result.objectId()` 删除判定逐行保持，时序未动 |
| `inventory/InventoryPhotoController.java` | list→`PhotoList`、grant→`DownloadGrant`、upload→`PhotoReceipt`；URL 拼接、`/api/v1/mini` 前缀判定、`content` 字节流出口、`Idempotency-Key`/`If-Match-Version`/`X-FAMS-Device-Id` 头与校验一字未动 |
| `inventory/InventoryRepairPhotoController.java` | grant→`DownloadGrant`、list→`RepairPhotoList`（`AttachmentEvidencePort.Metadata` 原样入列，createdAt 裸值直通）；`content` 出口未动 |
| `inventory/InventoryFindingController.java` | 5 个端点返回类型化视图；路径、校验注解、请求体未动 |
| `inventory/InventoryReviewController.java` | 4 个端点返回类型化视图；`findings` 端点仍复用 `observations(...)` 调用（itemId=null 分支未动） |
| `inventory/InventoryPhotoReadService.java` | **零 diff**：`Photo` record 与 `photo(Metadata)` 静态投影保留原位（禁改面 `attachment/AttachmentEvidenceWireShapeTest` 直接引用该方法引用，见 §6-2） |
| `test/inventory/InventoryFindingWireShapeTest.java`（新增） | 21 条 wire 等价用例，见 §3 |

出口 → 类型对照（14 个 Map 出口全部归零，2 个字节流出口不动）：

| 旧 Map 出口 | 新 record |
|---|---|
| GET …/finding-targets：`items` + 7 键 \| 5 键 \| 空 | `FindingTargets`（行 `Target`，3 个 NON_NULL 条件键） |
| GET …/findings/{observation}：7 键（timeSubject/recollections/taskVersion/observation/subject/history/historyTotal） | `FindingDetail`（`SubjectRow`/`DecisionRecollectionRow`/`ObservationRow`/`DecisionHistoryRow` + `SubjectProjection` 空态） |
| GET …/recollection-targets：`items/total/page/pageSize`（items=扁平 observation 行） | `ObservationPage` |
| POST …/decisions body 与 `response_json`：5 键 | `DecisionReceipt`（新写文本与旧 LinkedHashMap 序逐字节同） |
| POST …/time-decisions body 与 `response_json`：8 键 | `RecollectionReceipt` |
| GET …/inventory-reviews/tasks/{task}：`task/items/total/page/pageSize/reviewedCount`，items=7 键行 | `ReviewTaskDetail`（`TaskRow`/`ReviewTaskItem`，行含 `ItemRow`/`ObservationRow`/`ReviewState`/JsonNode current） |
| GET …/items/{item}/observations 与 …/findings：`items/total/page/pageSize`（items=递归 observation 行） | `ObservationPage` |
| POST …/normal-acceptance body 与 `response_json`：6 键 + 行 3\|4 键联合 | `NormalBatchReceipt`/`NormalBatchItem`（`review`/`code`/`current` 三条件键） |
| POST …/photos（upload）：`objectId/photoId` | `PhotoReceipt` |
| GET …/photos：`items` + 7 键（createdAt 双驱动转换） | `PhotoList`（分量沿用 `InventoryPhotoReadService.Photo`） |
| GET /inventory-repair-evidence/…/photos：`items` + Metadata 原样 | `RepairPhotoList`（`List<AttachmentEvidencePort.Metadata>`，createdAt 裸值） |
| POST …/download-grant（两控制器）：`url/expiresAtEpochSecond` | `DownloadGrant` |
| GET …/content（两控制器）：`ResponseEntity<byte[]>` + 4 头 | 未动（红线：字节流出口） |
| 非出口（未动）：`InventoryPhotoReadService.list/grant/read`、`InventoryRepairPhotoReadService.list/grant/read`、`InventoryAttachmentPort`、`InventoryInboundPort.find/context`、`AssetInventoryReviewPort.contexts/matchesVersion`、`AssetInventoryReadPort.identify/currentSnapshot` | 已类型化 Port/内部面，签名零改动 |

## 3. 判据：wire 等价测试

`InventoryFindingWireShapeTest`（21 用例）把改前 `InventoryFindingService.wire`、
`InventoryReviewService.wire`、finding-target/finding-detail/recollection-page 装配、review detail
item 与 document、acceptNormal 响应与两种行形、decide/recollect 结果、upload 两键回执、
`InventoryPhotoController.list` 的逐键拷贝 + createdAt 改写、两控制器 grant 两键逐一复制为
legacy oracle（verbatim），比对走 JSON 文本往返树（键序无关；键存在性、显式 null、值形状严格）：

1. **finding 侧行投影**：observation 行（24 列）双驱动形态 ≡ 扁平 `legacyFindingWire`；
   `id`/`task_id` 串化、`client_event_id`/`device_id` CHAR 直通、`recheck_generation` 的 Integer
   宽度变体、`binding_id`/`calibrated_*` 显式 null、`facts_json`/`receipt_json` 解析成树且
   **内层数字不变串**；subject 行（13 列）已复核/未复核双态、history 行（18 键，含 CAST 别名与
   LEFT JOIN null 态：`assetId`/`link_kind` 文本态↔null、`owner_version` 数值态↔显式 null）、
   recollection 行（16 键，`counterObservationId` 串 + `counter_observed_at` ISO）；
2. **两种口径的分歧判据**（本批核心红线）：同一 observation 行
   `finding ≠ review` —— `facts_json.itemId` 数值 21 vs 串 "21"、`facts_json.actual.locationId`
   复核侧串化，非 JSON 列两侧相同，且递归侧 ≡ 独立 `legacyReviewWire` 拷贝；
3. **detail 全装配双态**：有 subject（7 键齐备、`subject` 13 列含显式 null、taskVersion=6、
   historyTotal=1）vs 无 subject（`subject` 为**零键对象** `hasSize 0`，空数组两段）；
4. **条件键双态**：finding-target 两形 7↔5 键（`assetName`/`inboundReceiptId`/`receiptNumber`/
   `status` 缺失断言 + `assertEquals(7/5, size)`）、空 items 信封；
5. **review 侧行投影**：task 行（21 列）双驱动 + `range_json` 的**嵌套 `*_json` 串再解析**
   （`nested_json.warehouseId` → "9" 串、`locationIds` 复数键保持数值数组）、item 行（9 列）、
   item_review 行（13 列）NOT_OBSERVED/ACCEPTED 双态；
6. **review detail 富态/瘦态**：`current`（快照树递归口径）、`movementEvents`（跨域
   `AssetInventoryReviewPort.Movement` 分量，`occurredAt` Instant 直通文本）、`candidate` null、
   `review` 的 `{version:0}` 单键态 vs 全行态、`blockedReason` 恒在显式 null、document 6 键；
7. **联合两路同源**：`reviewState`（GET 侧密封 record 联合）与 `reviewNode`（存储侧 JsonNode）
   序列化逐字节相同，且 ≡ `legacyReviewWire(row)`；null 态固定 `{"version":0}`；
8. **acceptNormal 行联合**：ACCEPTED 3 键（无 `code`/`current`）vs CONFLICT 4 键（无 `review`）
   vs `current={"version":0}` 三态同文档，信封 6 键；
9. **幂等旧形状回放**（`response_json` 跨版本必回放）：decide 旧树 → `readValue(DecisionReceipt)`
   → 树等价 **且新写文本与旧 LinkedHashMap 序逐字节相同**；recollect 8 键旧树回放 + 二次回放稳定；
   acceptNormal 混合行旧树回放 + 二次回放稳定；
10. **照片双驱动形态判据**：observation 照片列表在 Timestamp / LocalDateTime / 已是 ISO 串
    三态下 ≡ 旧逐键拷贝（7 键），前两态归一同一 instant 文本、第三态直通不被改写；Integer 宽度
    变体等价；repair 证据列表 `assertSame(createdAt, metadata.createdAt())` 且 ≡ 裸行图
    （**不**归一，防顺手转串）；
11. **upload 两键回执**、**grant 两键**（两种 URL 前缀形态）、**List 分量防御拷贝**
    （构造后清空源列表，视图仍持 1 项——SpotBugs EI_EXPOSE_REP 预清口径）。

**单元 oracle 边界（JAVA-01/15/16a 沿用）**：只证明「行→视图→存储/回放」序列化等价。真实 HTTP
运行时链（`InventoryReviewHttpRuntimeCase`/`InventoryFindingHttpRuntimeCase`/
`InventoryPhotoHttpRuntimeCase`/`InventoryRepairHttpRuntimeCase` 一类 `@EnabledIfSystemProperty`
容器门禁用例）需要容器 MySQL，本批环境无容器、且任务红线禁跑 `scripts/verify-d6-03a.py`，
登记为门禁边界，由主代理合并门禁实跑。

## 4. 实施代理自测（非产品门禁）

- 定向判据：`JAVA_HOME=graalvm-jdk-21.0.7 ./mvnw -pl backend -o -Dtest='InventoryFindingWireShapeTest' -DfailIfNoTests=false test`
  → **Tests run: 21, Failures: 0, Errors: 0**（首轮以独立 JUnit Platform 启动器复现同 21 项同结果，
  用于绕开同树并行红）。
- 全量结构回归：`-Dtest='*WireShapeTest,TransferCalendarTest'` → **33 套件 Tests run: 340,
  Failures: 0, Errors: 0, Skipped: 0**（本批新增 21，其余 319 项零退化；收口前复跑一次仍为
  340/0/0）。
- 并行红记录（三轮，全部非本批文件，按 35 秒重试策略自愈）：① 首轮同口径跑出 340 中 5 红，
  5 项全落在 17d 在途文件 `InventoryMainWireShapeTest`；② 两次整树 `test-compile` 失败源自
  17a `InventorySelectionWireShapeTest`、17d `InventoryMainWireShapeTest` 的在途编译错误
  （`JsonNode.convertValue` 等）；③ 一轮 `target/classes` 被同树并发 mvn 写坏（生成的
  `com.fams.product.contract.model.*` 与 `AssetLossPort` 等主类瞬时缺失），复跑即恢复。
- 旁证：`-Dtest='InventoryFindingWireShapeTest,AttachmentEvidenceWireShapeTest'` → 30/30 绿
  （JAVA-06 冻结的照片投影判据未退化，§6-2 的原位保留成立）。
- 全仓编译：`./mvnw -pl backend -o test-compile` → **BUILD SUCCESS**（含 17a/17b/17d 与
  JAVA-15/16a/b/c 在途文件同树共编译）。
- Checkstyle：`./mvnw -pl backend -o -Pquality checkstyle:check@check-java-conventions` →
  **0 violations / BUILD SUCCESS**（新文件末尾换行、无未用 import）。
- 未跑：SpotBugs/JaCoCo/真实 HTTP 运行时用例与全量门禁——按任务红线留给主代理合并门禁；
  EI_EXPOSE_REP 风险按先例在构造侧消解（9 个 List 分量全部 `List.copyOf`；Map 分量零新增；
  Object 分量仅承载不可变 JDBC 标量；JsonNode 分量为 Jackson 树类型，`LossViews`/
  `TransferViews`/`RepairViews` 既有先例）。
- SQL 字面量序列脚本核证见 §1（49/31/16 条 identical=True）。

## 5. 影响分析（GitNexus）

编辑前执行 `node .gitnexus/run.cjs impact <target> -d upstream --repo fams-manage`
（Node 24 `/Users/lixd/.nvm/versions/node/v24.18.0/bin` 前缀）：

- 类级上游全部 **LOW**：`InventoryFindingService`/`InventoryReviewService`/
  `InventoryPhotoService`/`InventoryPhotoReadService`/`InventoryRepairPhotoReadService`
  各 direct=1（唯一上游=本批控制器），四个控制器各 direct=0。索引 epistemic=exact、
  `processes_affected=0`、`modules_affected=0`——**无越批 HIGH/CRITICAL**。
- 真实跨包消费者只有测试面：`grep` 核得 `attachment/AttachmentEvidenceWireShapeTest` 引用
  `InventoryPhotoReadService::photo`（禁改面）与 `requisition/SmallPortsWireShapeTest` 的注释提及
  `InventoryFindingService` 读 `id().toString()`（不改端口面，判据已含该取值）。故 §1-5 与 §6-2
  把 `Photo`/`photo` 原地保留。
- 伪边登记：`gitnexus` 索引不含本批新符号（`FindingViews`）属正常；同名 `wire/detail/list/grant/
  read/upload/replay` 在多域各自独立，方法级 impact 未使用（改动的私有件由编译器封闭在类内，
  公开件上游=同包控制器），改后以 `grep` 逐一核实在册消费面，未见跨批真实上游。
- `detect-changes` 未单独执行：整工作树当前含 17a/17b/17d 与 repair/inbound 在途改动（25 个
  inventory 文件 M + 4 个新增 Views 文件），非本批 diff 口径；本批 diff 以 §2 的 7+3 文件为准，
  由主代理合并门禁在固定快照上跑 `detect_changes`。

## 6. 偏差与遗留

1. **裁决点：`InventoryRepairPhotoController` 的归属 service = `InventoryRepairPhotoReadService`，
   不触 17d**。任务书要求「读码确定归属，若装配在 17d 的 `InventoryService` 则停下登记裁决」——
   读码结论：该控制器的 grant/list/content 装配全部落在
   `InventoryRepairPhotoReadService`（`InventoryRepairPort.evidence` + 本域
   `fams_inventory_observation`/`fams_inventory_upload_scope` JOIN），与 `InventoryService`
   零调用关系，故按「及其归属 service」纳入本批。本批只改控制器包壳（`Map.of`→record），
   该 read service **未改一行**（其 list 已返回类型化 `List<AttachmentEvidencePort.Metadata>`）；
   `InventoryRepairPort`（禁改，16a 冻结面）签名零改动。
2. **裁决点：`InventoryPhotoReadService.Photo`/`photo(Metadata)` 原地保留**。禁改面
   `backend/src/test/java/com/fams/attachment/AttachmentEvidenceWireShapeTest.java`（JAVA-06 冻结）
   以方法引用 `InventoryPhotoReadService::photo` 作判据；把该投影移入 `FindingViews` 会破坏
   禁改测试文件。故本批只新增 `PhotoList` 信封包裹既有 `Photo` 分量，照片 item 的 7 键投影
   （含 createdAt 双驱动改写）继续由原位 record 承载，其等价性同时由本批判据 §3-10 与
   JAVA-06 判据双向冻结。
3. **裁决点：`{}` 空对象态的两种建模**。
   - finding detail 的 `subject`（GET 面，永不入库）用密封联合
     `SubjectProjection permits SubjectRow, NoSubject`，`NoSubject()` 零分量 record 序列化为
     `{}`（先用 Jackson 3.1.5 探针确认默认不触发 `FAIL_ON_EMPTY_BEANS`、无 `@JsonTypeInfo` 的
     接口分量按运行期类型序列化且不加判别键）。
   - acceptNormal 的 `review`/`current` 与 review detail 的 `review` 需要**反向可反序列化**
     （旧 `response_json` 回放），密封联合在无类型信息标注下无法反序列化，且
     `InitialReview(long version)` 与 `ItemReviewRow.version` 属性名重叠会破坏
     `Id.DEDUCTION`；故存储侧分量取 `JsonNode`（`FindingViews.reviewNode`），GET 侧仍取
     类型化 `ReviewState` 联合，两路径同源、字节一致由判据 §3-7 锁死。
     任意资产快照树 `current` 同样取 `JsonNode`（`RepairViews.RepairSummary.source`、
     `InboundViews` 的 JSON 分量先例）。
4. **口径登记：`taskVersion`/`historyTotal` 由 JDBC 原值 `Object` 改为 `long`**
   （`number(task,"version")` / `Objects.requireNonNull(queryForObject COUNT)`）——
   BIGINT 到达即 Long，JSON 文本同值（JAVA-11a/16a §6-5 先例）；`ObservationRow` 的
   `assignment_version`/`recheck_generation` 等 NOT NULL 数值列取 `long`，可空
   `snapshot_asset_version`/`owner_version` 取 `Long` 保留显式 null。
5. **口径登记：`FindingViews.time()` 对非 Timestamp/LocalDateTime 形态取 `toString()`**
   （旧扁平/递归 wire 为原样直通）。DATETIME(3) 列经 JDBC 只出这两种形态；`Instant` 形态
   `toString()` 与 mapper 文本一致，其他形态在可达成状态集上不可达——登记不修正，
   与 16a `timeValue` 同口径。`text()` 对 `*_id` 列统一 `value.toString()`（Number 走旧串化分支、
   CHAR 列恒等）已由判据 §3-1 双形态覆盖。
6. **新写幂等文本的键序**：`decide` 旧装配是 `LinkedHashMap`，record 声明序 = 旧插入序，
   故**新写 `response_json`/审计 `detail_json` 与旧文本逐字节同序**（判据 §3-9 断言）；
   `recollect` 与 `acceptNormal` 旧为 `Map.of`（跨 JVM 随机序、Map 树无契约），新写归一为
   声明序，旧存储行回放等价由判据 §3-9 覆盖。
7. **排除清单（保留 Map/不动，按规格）**：内部行 Map（`task()`/`observation()`/`candidate()`/
   `review()`/`orderedRows`/`contexts()` 返回态、`prior` 回执行、`conflictFields` 与
   `reject` 的 `fields` LinkedHashMap、finding `history`/`subject` 查询行）、审计
   `detail_json`（`audit(...)` 仍收 `Object`，照片上传的 `Map.of` 审计体未动）、
   幂等 `hash`/`fingerprint` 输入（`json.writeValueAsString(input)` 仍序列化请求 record）、
   `Instant`/`Timestamp now()` 写入参数、请求体 `InventoryFindingInput`/`InventoryReviewInput`、
   `InventoryReviewLinks`/`InventoryReviewConflict`/三个 ErrorHandler、全部 Port——一律零 diff。
   契约/OpenAPI/前端零改动（无 API 形状变化，无接口文档改动）。
8. **跨批重复登记**：`FindingViews.ObservationPage`（items/total/page/pageSize）与
   `SealViews.Page`、17a/17d 自建分页信封同形异构；observation/task/item/item_review 行投影
   在 17b/17c/17d 各自 Views 内重复定义（外层类不同、无编译冲突）。合并门禁后若要收口，
   建议单列一个 inventory 共享行批，先把三种 `wire` 口径（本域扁平、本域递归、17b 封账侧）
   做一次对照核证，再决定归一——**不要**在未核对 §3-2 分歧判据前合并两个 observation 工厂。
9. **观察项（非缺陷、未改）**：`InventoryReviewController.findings` 与 `observations` 共用一个
   service 出口（itemId=null），两端口径同一 `ObservationPage`，旧 `Map.of` 亦同形；
   `acceptNormal` 为取回执 `review` 对同一 item 二次调用 `review(item)`（旧代码即如此，
   FOR UPDATE 已持锁），本批未顺手改；`InventoryPhotoService.upload` 的重放分支读
   `fams_inventory_photo_receipt.object_id`（CHAR(36)）后 `.toString()`，与旧 `Map.of` 装载裸值
   在同一列上文本一致（判据 §3-11）。
10. AOCI 认知维护：本会话工具面未暴露 aoci_* MCP 服务，无法调用 `aoci_maintain`；受管理对象
    已达最终稳定态，登记该收尾步骤未执行，交由持有 AOCI 工具的会话对齐。

## 7. 下一步

- 主代理合并门禁：全量 Java 质量序列（含 SpotBugs/JaCoCo）+ 真实 HTTP 运行时用例
  （inventory 复核/认定/照片/返修证据链，含 `Idempotency-Key` 重放与 `If-Match-Version`
  冲突链）在固定快照上实跑；SpotBugs 关注 `FindingViews` 的 List 拷贝位（已按先例预清）与
  `reviewNode`/`wiredJson` 的 JsonNode 分量。
- 回放窗口验证：`fams_inventory_review_receipt` 为永久保留（无 24h 过期），生产旧行必须由
  判据 §3-9 的三类形状回放；若门禁实跑发现历史行含第三种键形（如早期迁移前的 recollect 缺
  `originalTimeConfidence`），record 分量缺省即显式 null，需与 §6-3 一并复核后再定裁决。
- 17a/17b/17d 交付后：`FindingViews` 与 `SelectionViews`/`SealViews`/`InventoryViews` 的
  `Page`/行投影重复按 §6-8 收口；`reviewNode` 与 `reviewState` 的双路若出现新消费者，
  优先扩 `ReviewState` 联合而非再开 Map。

## 主代理产品门禁（波次六合并实跑）

主代理在波次六合并树固定快照副本（rsync 排除并行 WIP；快照前漂移校验证明非 inventory 已提交面
零改动；四路 views 文件内容核实入照）实跑完整 Java 序列（nohup 脱离态）：首轮即 **JAVA01_VERIFY_PASS**，
83 步 exit=0，Checkstyle/SpotBugs 0，覆盖率 LINE 93.45%、BRANCH 75.29%；日志内 74 个 surefire
阶段汇总 401 项 0 失败 0 错误。四个新套件另在门禁副本内直接复跑 **80/80 绿**（Selection 19 +
Seal 20 + Finding 21 + Main 20）。主树合并判据：33 个 WireShape 类 340/340。
详见[波次证据](evidence/JAVA-17-java-gate.txt)。
