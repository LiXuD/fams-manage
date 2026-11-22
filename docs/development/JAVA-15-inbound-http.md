# JAVA-15 · inbound 域 HTTP 出口类型化

> 用户指定的后端结构批（HTTP 出口波次 · inbound 子批），不进入 D6-01～D6-27 台账、不占用其状态。
> 日期：2026-11-27 · 分支：main · 基线：`dd43a5c`（波次三收口）
> 状态：主代理审计通过，波次四合并门禁通过（见文末证据节），已收口提交。

## 1. 背景与红线

完全复刻 JAVA-01/11a/14b1/14c 标准：HTTP 响应类型化；内部行 Map（queryForList 行、
`items()`/`resolve()` 中间态、审计 `detail_json`、幂等指纹 `digest` 输入、reassign 审计
`items` 原 Map 落库）保留；契约 `contracts/fams-core-v1.yaml`、OpenAPI 生成类、
`packages/api-types`、前端零改动；SQL 语句、FOR UPDATE 锁顺序、异常码、HTTP 状态码
（create/upload/correct 201）、响应头、端点路径一字未动。本批只触碰
`InboundController/InboundService/InboundReassignmentController/InboundReassignmentService/`
`InboundAttachmentController/InboundAttachmentReadController` 六个既有文件 +
新增 `InboundViews`、`InboundWireShapeTest` 与本记录。同包 `FactoryReplacementInboundPort`、
`InventoryInboundPort`、`InboundAttachmentPort`、两个 ErrorHandler 与 repair/inventory/asset、
attachment 包零触碰。

inbound 与先例的关键差异（改前代码逐字冻结为 oracle 核对）：

1. **跨域消费面（裁决点，见 §6-1）**：`FactoryReplacementInboundPort.Receipt.fromView(Map)`/
   `Context.fromView(Map)` 是 JAVA-10c 冻结的投影工厂（Map 入参按裁决保留），其入参来自
   `InboundService.create/get` 的返回。port 文件禁改 ⇒ `create`/`get` 两方法保留
   `Map<String,Object>` 签名，实现改为对 typed `createDetail`/`getDetail` 的
   `json.convertValue`（单源装配，JAVA-14b1 §6-1 先例，但此处为**永久包装**而非过渡态：
   消费方是禁改 port，不同于 14c 可在后续批删除）。消费点登记：`createOwned` 调
   `service.create`（读 `id` 键做字符串 parse 与 `Receipt.fromView`）、`context` 调
   `service.get`（`Context.fromView` 四键投影嵌入 repair replacementView）。
   `InboundReassignmentService.reassign` 消费点已切 typed `getDetail`（本批文件）。
2. **receipt() 条件键**：旧装配是 HashMap，`factoryReplacement/submittedAt/adminDecisionDueAt/
   warehouseClaimDueAt/warehouseFirstExecutionDueAt/cancelledAt/completedAt/notes/
   cancellationReason/decision/currentProcessor` 与 item 的 `businessLock/acceptance`、
   acceptance 的 `notes`、decision 的 `comment`、currentProcessor 的 `archivedAt` 全部走
   putTime/putOptional 判空守卫（null 时整键缺失）；全局 JsonConfiguration inclusion=ALWAYS
   下逐一用 `@JsonInclude(NON_NULL)` 复刻。其余键恒在（显式 null 照写）。
3. **投影口径逐值复刻**：id 类键 `Long.toString`/`toString` 字符串化；DATETIME 经旧
   `instant()` 四分支（Timestamp/Instant/LocalDateTime/文本 parse）归一 ISO 串；DATE 经旧
   `localDate().toString()` 归一 yyyy-MM-dd（双驱动形态判据冻结）；`factoryReplacement.sourceId`
   旧代码原样 put 裸 JDBC 值 ⇒ Object 直通（OpenLoanRef 先例）；attachment list 的
   `createdAt` Object 直通 + 实例同一断言（14b3 AttachmentEvidence 先例）。
4. **幂等存储链**：`InboundService.replay` 按 Transfer/14b1 蓝本泛型化
   （`<T extends InboundViews.Reply> T replay(..., Class<T>)`，`@SuppressWarnings("unchecked")`
   随裸 `Map.class` 删除）；`record` 结果参数改 `Object`（11a saveReceipt 先例），
   `resource_version` 仍按旧 SQL 现查 `SELECT version FROM fams_inbound_receipt`——未迁移为
   `result.version()`，SQL 零改动。9 个 operation_id 存储形状共三类：Receipt（7 操作）、
   AcceptResult（accept）、UnavailabilityResult（declareInboundProcessorUnavailable）；
   sealed `Reply{ long version(); }` 由 Receipt 的 version 分量与 AcceptResult 的委派访问器
   实现（后者非 record 分量、不入线，判据断言无 `"version"` 键泄漏）。

## 2. 改动清单与出口映射

主代码 6 改 1 增，测试 1 增，本记录 1 增：

| 文件 | 变化 |
|---|---|
| `inbound/InboundViews.java`（新增） | 24 个响应 record + 密封 `Reply` + 行映射工厂（receipt/item/acceptance/scope/decision/acceptedAsset/reassignmentItem/operator/attachmentEvidence/attachmentReceipt）；条件键全量 `@JsonInclude(NON_NULL)`；含 List 分量紧凑构造器 `List.copyOf` 防御拷贝 |
| `inbound/InboundService.java` | 9 个出口类型化；新增 typed `createDetail`/`getDetail`；`create`/`get` 保留 Map 签名改为 `convertValue` 永久包装（port 消费面，§1-1）；`receipt()/item()/scope()/acceptance()/acceptedAsset()/identity()/ref()/storage()/putTime()/putOptional()/instant()` 投影移入 InboundViews（服务侧只保留查询编排，读序注释锁定）；`replay` 泛型化、`record` 参数改 Object；输入侧 `items()/resolve()/digest()/date()/localDate()/longValue()` 等行/输入 Map 未动 |
| `inbound/InboundReassignmentService.java` | `inspect`→`ReassignmentSnapshot`（operators→checks 求值序局部变量化 + 注释锁定，14c 做法；`version/ownershipVersion` 取 long 分量 = Transfer/14c 先例）；`reassign`→`Receipt`（消费点切 `inbound.getDetail`，回放分支 `readValue(Receipt.class)`）；`declareUnavailable`→`UnavailabilityResult`；两处 `@SuppressWarnings("unchecked")` 随裸 Map.class 删除；`processing()/owner()/checks()` 内部行 Map、审计 `detail_json`（含 `"items", checks` 原 Map）、幂等指纹输入未动 |
| `inbound/InboundController.java` | 9 个端点返回类型化视图（create→`createDetail`、get→`getDetail`）；请求体 Map 入参、校验、头、路径、201 未动 |
| `inbound/InboundReassignmentController.java` | 3 个端点返回类型化视图；请求体 record 与校验未动 |
| `inbound/InboundAttachmentController.java` | upload/correct 返回 `ResponseEntity<AttachmentReceipt>`（201 保留）；内联 Map.of 六键移入 `InboundViews.attachmentReceipt`（ProxyConfirmationViews.EvidenceReceipt 先例） |
| `inbound/InboundAttachmentReadController.java` | list→`AttachmentListPage`（行→`AttachmentEvidence`，14b3 同构）、grant→`DownloadGrant`（从 `AttachmentReadService.grant` Map 取两键）；content 二进制出口未动；attachment 包零触碰 |
| `inbound/InboundWireShapeTest.java`（新增） | 16 条 wire 等价用例，见 §3 |

出口 → 类型对照（16 个出口）：

| 出口 | 旧 Map 键形 | 新 record |
|---|---|---|
| GET /inbound-receipts（list） | `page/pageSize/total/items`（Map.of） | `Page<Receipt>` |
| POST …/{id}、PATCH、submit、cancel、decision、claim 与 reassign body | receipt() 20 键（10 恒在 + 10 条件） | `Receipt implements Reply` |
| POST …/items/{itemId}/accept | `receipt/item/asset`（HashMap） | `AcceptResult implements Reply` |
| GET …/reassignment（inspect） | `id/version/ownershipVersion/processorAccountId/items(8键)/operators(2键)` + 条件 `unavailableReason` | `ReassignmentSnapshot`（items=`ReassignmentItem`、operators=`Operator`） |
| POST …/reassignment/unavailable | `receiptId/recorded`（Map.of） | `UnavailabilityResult` |
| POST …/attachments 与 …/corrections（201） | `objectId/filename/contentType/sizeBytes/scanStatus/businessVersion`（Map.of） | `AttachmentReceipt` |
| GET …/attachments | `items` + 11 键别名行 | `AttachmentListPage`（`AttachmentEvidence`，createdAt Object 直通） |
| POST /attachments/{objectId}/download-grant | `url/expiresAtEpochSecond`（Map.of） | `DownloadGrant` |

## 3. 判据：wire 等价测试

`InboundWireShapeTest`（16 用例）把改前 `InboundService.receipt/item/scope/acceptance/
acceptedAsset`、`InboundReassignmentService.inspect/declareUnavailable` 与附件三出口的
Map 装配逐字段复制为 legacy oracle（含 putTime/putOptional 守卫与 camel→snake 循环），
JSON 文本往返树比较（键序无关；键存在性、显式 null、值形状严格）：

1. **Receipt 全形状**（origin/decision/scope/currentProcessor/双 item 锁与验收全命中）与
   **DRAFT 零条件键形状**：11 个条件键双态逐一断言（有值态有键、null 态整键缺失；
   `createdBy.username` 等恒在键显式存在）；
2. **驱动形状变体**：created_at 的 Timestamp/LocalDateTime/Instant/ISO 文本四形态、
   business_date 的 java.sql.Date/LocalDate 双形态、version 列 Integer 宽度变体——typed
   ≡ legacy 逐一对齐；
3. **取消/驳回态**：cancelledAt/cancellationReason/archivedAt 条件键命中 + acceptance.notes
   缺失态；**AcceptResult** ≡ 旧三键 HashMap 且断言无 `"version"` 泄漏键；**Page** ≡ 旧
   Map.of；
4. **inspect 双申报态**（unavailableReason 有/无）、checks 8 键逐行 ≡ 旧 Map（含非 pending
   `-1L` 分支夹具）、operators ≡ 旧 Map.of、Integer 宽度变体；
5. **附件三出口**：upload/correct 六键（经真实 `attachmentReceipt` 工厂与 QuarantinedAttachment
   夹具）、list 11 键双态 + createdAt **实例同一断言**（防顺手转 String）、grant 两键；
6. **幂等存储形状旧 JSON 回放**（`fams_idempotency_record.response_body_json` 24h 窗内跨版本
   必回放）：旧 oracle 生成的 Receipt 全形状与 DRAFT 形状 → `readValue(Receipt)` → 等价再
   序列化；AcceptResult 树 → `readValue(AcceptResult)`；UnavailabilityResult 两键树；typed
   自往返恒等；
7. **port 消费面等价**：`convertValue(Receipt→Map)` ≡ legacy receipt Map 全树；
   `Receipt.fromView`/`Context.fromView` 对 legacy Map 与 wire Map 两路取值一致，且旧存储
   JSON 经 `readValue(Receipt)` 回放后四键投影仍一致（reassign/declareUnavailable 幂等链
   端到端）。

**单元 oracle 边界（14b1 沿用）**：只证明「行→视图→存储/回放→包装镜像」序列化等价。真实
HTTP 运行时链（`inbound/InboundHttpTest`、`auth/RepairFactoryHttpRuntimeCase`、
`attachment/AttachmentHttpTest` 等）为 `@EnabledIfSystemProperty` 容器门禁（需 Testcontainers
MySQL），本批环境跑不动，登记为主代理合并门禁实跑，不算本批失败。

## 4. 实施代理自测（非产品门禁）

- 定向判据：`JAVA_HOME=graalvm-jdk-21.0.7 ./mvnw -pl backend -Dtest='InboundWireShapeTest' -DfailIfNoTests=false test`
  → **Tests run: 16, Failures: 0, Errors: 0**。
- 邻域回归：`-Dtest='*WireShapeTest'` 全量 → **25 套件 Tests run: 217, Failures: 0, Errors: 0**
  （既有 24 套件 201 项 + 本批 InboundWireShapeTest 16 项，全绿；口径含
  `transfer/Reassignment=17/7`、`Requisition=15`、`InitialPorts=5` 等冻结基线）。
- inbound 包既有一元测：`-Dtest='Inbound*Test'` → InboundWireShapeTest 16/16、
  InboundAttachmentPortTest 4/4 绿；InboundHttpTest 1 项 skip（容器门禁，见 §3 边界）。
- 全仓编译：`./mvnw -pl backend -q test-compile` → **exit=0**。
- Checkstyle：`./mvnw -pl backend -Pquality checkstyle:check@check-java-conventions` →
  **BUILD SUCCESS**，新文件末尾换行合规。
- 未跑：SpotBugs/JaCoCo/真实 HTTP 运行时用例与 `scripts/verify-d6-03a.py` 全量门禁——按任务
  红线留给主代理合并门禁；EI_EXPOSE_REP 风险按先例在构造侧消解（List 分量 `List.copyOf`，
  Object 分量仅承载不可变 JDBC 标量）。

## 5. 影响分析与伪边（GitNexus）

编辑前执行 `impact -d upstream --repo fams-manage`（Node 24 前缀）：

- 类级 `InboundService`（LOW 3：port 构造点 + 两本批服务/控制器）、`InboundController`
  （LOW 0）、`InboundReassignmentService`（LOW 1：本批控制器）、`InboundReassignmentController`/
  `InboundAttachmentController`/`InboundAttachmentReadController`（LOW 0）。
- 方法级（`-f` 文件消歧后）：`create/list/submit/cancel/decide/claim/accept/inspect/reassign/
  declareUnavailable` 全 **LOW 1**（唯一上游=本批控制器或 port 构造点）；`get` 报 **HIGH 49**、
  `receipt` **HIGH 49**、`update/replay/record` **MEDIUM 22～23**——逐一核为**同名伪边聚合**：
  depth-1 明细中除 `FactoryReplacementInboundPort.createOwned/context`、`InboundController.get`、
  `InboundReassignmentService.reassign`（全部为已知消费面，§1-1 登记）外，其余 20+ 条目均为
  InboundService 同文件同名 `get/update/replay/record` 候选聚合（`receipt/item/acceptance/...`
  的"上游"全部落在 InboundService 自己文件内，private 编译器封闭）。真实上游闭包不出
  inbound 包 + 禁改 port 的既有 Map 消费面，**无越批真实 HIGH/CRITICAL**。
- `detect-changes`（工作树 vs HEAD）：7 文件 42 符号、risk medium、5 执行流——命中符号全部
  落在 inbound 六改文件；受影响流程名为 `content→…` 聚合边，源于 `InboundAttachmentReadController`
  文件级变更（其 content 二进制出口方法体零改动）。工作树中 `M AGENTS.md` 与
  `apps/miniprogram/project*.json` 未跟踪文件属会话既有状态，非本批 diff。
- 索引不含本批新符号（`InboundViews` 等）属正常。

## 6. 偏差与遗留

1. **裁决点：`create`/`get` 的 Map 兼容包装为永久面**。14b1 的 `get` 包装在 14c 随消费面切换
   被删除；本批消费方是**禁改**的 `FactoryReplacementInboundPort`（fromView Map 入参已裁决
   保留），故 `InboundService.create/get` 两个包私有 Map 包装长期保留，单源装配
   `createDetail/getDetail` + `convertValue` 镜像由判据 §3-7 三方比树冻结（legacy 树 ≡
   wire Map ≡ 回放再镜像）。后续批若要消灭该包装，须先对 port 投影工厂重新裁决。
2. **`AcceptResult.version()` 是委派访问器而非 record 分量**（`receipt.version()`），仅为满足
   sealed `Reply{version()}` 蓝本约束；不入线由判据 §3-3 的无 `"version"` 键断言锁死。
   inbound 的 `record()` 落库 `resource_version` 仍走既有现查 SQL（红线：SQL 零改动），
   与 Transfer finish 的 `result.version()` 取材不同源但同值——未顺手改。
3. **NPE 语义微差（无实际形状影响）**：upload/correct 的 `filename` 与 acceptedAsset 的
   `exception_status` 在旧代码经 `Map.of` 装载，null 会 NPE；record 分量允许 null 会输出显式
   null。前者 multipart 文件名实际必非空（14b3 同口径），后者列定义 NOT NULL DEFAULT 'NORMAL'，
   两处在可达成状态集上无差异，登记不修正。
4. `inspect` 的 `version/ownershipVersion` 与 `ReassignmentItem` 四数值分量取 long（14c §6-3
   先例）：旧装配透传 JDBC 原值 Long/Integer，JSON 文本往返归一同一数字节点，Integer 宽度
   变体由判据冻结；`unavailableReason` 取 `(String)`（TEXT 列），sourceId 与 createdAt 保持
   Object 直通（§1-3）。
5. 旧幂等回执经 record 再序列化键序归一为声明序（旧 HashMap/Map.of 键序本就跨 JVM 随机、
   Map 树无契约），跨版本回放由判据 §3-6 覆盖。
6. SQL 文本、参数顺序、FOR UPDATE 锁顺序（含 accept 三键构造序、receipt() 六段读序与
   inspect operators→checks 序，均已加注释锁定）、事务边界、异常码、状态码（create/upload/
   correct 201）、路径、`Idempotency-Key`/`If-Match-Version` 头、幂等 `operation_id`/
   `http_status`/`resource_type='INBOUND'` 零改动。
7. 排除清单（保留 Map/不动，按规格）：请求体 `Map<String,Object> body` 输入面（create/update/
   cancel/decision/accept 的键解析、`items()/resolve()/object()/longValue()` 校验链）、
   `target()/eligible()/activeReference()` 的行 Map 中间态、审计 `detail_json`（含 reassign
   的 `"items", checks`）、幂等 `digest` 输入、`lines()/one()/queryForList` 行 Map、
   `InventoryInboundPort`/`FactoryReplacementInboundPort`/`InboundAttachmentPort` 全文、
   两个 ErrorHandler、`AttachmentReadService`（grant/list 的 Map 产物在控制器出口处投影）。
   契约/OpenAPI/前端零改动（无 API 形状变化，无接口文档改动）。
8. 观察项（非缺陷、未改）：`reassign` 为校验可读性装配整棵 receipt 树（与 14b1 §6-4 同型）；
   attachment `list` 的 FOR UPDATE 行锁经 `AttachmentReadService` 保持不变。

## 7. 下一步

- 主代理合并门禁：全量 Java 质量序列（含 SpotBugs/JaCoCo）+ 真实 HTTP 运行时用例
  （`InboundHttpTest`、`RepairFactoryHttpRuntimeCase` 的 inbound 段、`AttachmentHttpTest`）实跑。
- 后续波次：16 repair 系、17 inventory 系出口类型化；若未来对 port 投影工厂重新裁决，
  回收 §6-1 的永久 Map 包装。

## 主代理产品门禁（波次四合并实跑）

主代理审计：控制器 5 处 `Map<String, Object>` 全为 `@RequestBody` 入参红线面，出口归零属实；
`create/get` 永久包装确为 `convertValue(createDetail/getDetail)` 单源装配。波次四固定快照副本
（rsync 排除并行 WIP；快照早于 16a 派发，repair 面零混入）实跑完整 Java 序列：**JAVA01_VERIFY_PASS**，
83 步 exit=0，Checkstyle/SpotBugs 0，覆盖率 LINE 93.24%、BRANCH 75.60%；日志内 74 个 surefire 阶段
汇总 401 项 0 失败 0 错误。为消 `tests=555` 口径歧义，`InboundWireShapeTest` 另在该副本内直接复跑
**16/16 绿**。主树合并判据：26 个 WireShape 类（含 TransferCalendar）218/218。
详见[门禁证据](evidence/JAVA-15-java-gate.txt)。
