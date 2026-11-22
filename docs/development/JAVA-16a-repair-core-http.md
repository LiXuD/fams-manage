# JAVA-16a · repair 域「核心」HTTP 出口类型化

> 用户指定的后端结构批（HTTP 出口波次 · repair 最重子批），不进入 D6-01～D6-27 台账、不占用其状态。
> 日期：2026-11-22 · 分支：main · 基线：`dd43a5c` + JAVA-15 在途 inbound 批（本批零触碰其文件）
> 状态：主代理审计通过，波次五合并门禁通过（含首轮 SpotBugs NP 红项修复复跑，见文末证据节），已收口提交。

## 1. 背景与红线

完全复刻 JAVA-01/11a/14b1 标准：HTTP 响应类型化；内部行 Map、审计 payload、幂等指纹输入保留
Map；契约 `contracts/fams-core-v1.yaml`、OpenAPI 生成类、`packages/api-types`、前端零改动；
SQL 语句、参数顺序、FOR UPDATE 锁顺序、异常码、HTTP 状态码、端点路径、请求体 RepairInput
一字未动（diff 中 SQL 行仅换行重排，逐条比对文本相同）。本批只触碰
`RepairController/RepairService/RepairPhotoController/RepairPhotoService/RepairReassignmentController`
五个既有文件 + 新增 `RepairViews` 与 `RepairCoreWireShapeTest`。禁改面
RepairFactoryService/RepairFactory*（16b）、RepairCollaboration*（16c）、AssetRepairPort、
RepairInitialPort、RepairProxyReceiptPort、RepairTargetChangePort、InventoryRepairPort、
LossRepairPort、inbound/（JAVA-15 在途）、attachment/、contracts/、apps/ 全部零 diff
（`git diff --stat` 仅上述 5+2 文件）。红线 `RepairFactoryService.ScrapEvidence` 与
RepairService↔factory 互调签名（matchesFactoryScrapEvidence/requireFactoryScrapEvidenceObjects/
scrapSource/factoryScrapSource 等）逐字未动。

repair 与先例的关键差异（改前代码逐字冻结为 oracle 核对）：

1. **投影口径 = 冻结 `RepairService.view()` + `wire()`**：`id`/`*_id` 值逐列 `.toString()`；
   `*_json` 字符串列（含 `detail_json` LONGTEXT）经 `readValue(text, Object.class)` 解析为树
   ——与 loss 11a 的 `detail_json` 原样字符串口径不同，逐域冻结不得互套；
   Timestamp/LocalDateTime→ISO instant 串；其余原样透传（`delivered_by/received_by/approved_by`
   不以 `_id` 结尾，保持数值；生成列 `active_repair_id` 以 `_id` 结尾，字符串化）。
   `wire()` 本体保留在服务内（decide 的 basis、scrapSource context、reject fields、
   physicalTime 解析等内部链继续消费）。
2. **detail 装配 = view 头 16 键 + 19 个附加键**，其中条件键三个：`factorySummary`
   （仅当 RepairFactoryGuard.summary 非 null）、`latestLossReportId`（仅当存在
   REPAIR_LOSS_HANDED_OFF 事件）、`actions.manageFactory`（仅 admin 或仓管处理人）——
   全部 @JsonInclude(NON_NULL) 复刻；其余键恒在（全局 JsonConfiguration inclusion=ALWAYS，
   显式 null 照写）。子表段（decisions/history/physicalHandling/inbound/returnHandovers/
   approvedReturnTargets/returnRecollections）保留 SQL snake_case 键（@JsonProperty 冻结，
   JAVA-11a LossEvent 先例）。DB 查询语句与顺序逐条未动（含 `outstandingReturn` 的
   FOR UPDATE 位置与 `factory.summary` 内的锁查询顺序）。
3. **幂等回放按泛型 `Class<T>` 蓝本（loss 11a 先例，无 Reply 接口——repair 回执表
   `fams_repair_receipt` 无 resource_version 列，finish 不需要从结果取版本）**。存储形状四类，
   全部有旧 JSON 回放判据：
   - `RepairSummary`（view 形状）：SUBMIT、LOSS_RECOVERY:\*、DECIDE、CANCEL、CLAIM、ACCEPT、
     COMPLETE、ACCEPT_AWAY、INBOUND、RETURN_DELIVER、RETURN_RECEIVE、RETURN_REFUSE、
     RETURN_RECOLLECT、RETURN_SOURCE 共 14 个 operation 键；
   - `RepairDetail`（get 全树）：REASSIGN；
   - `RepairViews.PhotoReceipt`（三键）：PHOTO；
   - `RepairViews.UnavailabilityReceipt`（三键）：DECLARE_UNAVAILABLE。
4. **RepairFactoryGuard.summary 仍返回 Map（16b 表面）**：本批只冻结其被消费形状
   （FactorySummary/FactoryScope/FactoryRound 三段 record + 旧 `wire()` 后处理等价）；16b
   类型化该出口时应直接复用 RepairViews.FactorySummary 或登记迁移。
5. **跨域分量零改动复用**：`AssetRepairPort.EligiblePage/PhysicalPage/ReturnTarget/Snapshot`、
   `LoanViews.Page/RepairFollowupSource/Followup` 多态、`ProxyViews.RepairSnapshot`（10b 冻结，
   其 `loan` 分量声明为 `Map<String,Object>`，故服务内 `loanWire` 保留 Map 产出；
   reassignment 快照侧另以 `RepairViews.LoanRef` 类型化，两形键值判据冻结等价）、
   `TargetChangeViews.RepairContext`——文件全部零 diff，判据 §3 增加形状不变断言。

## 2. 改动清单与出口映射

主代码 5 改 1 增，测试 1 增，本记录 1 增：

| 文件 | 变化 |
|---|---|
| `repair/RepairViews.java`（新增） | 22 个响应 record（Page/RepairSummary/RepairDetail/InventoryEvidenceRef/7 类行投影/ReportActions/FactorySummary 三件/ReturnSourceContext/ReturnSourceDetail/LoanRef/Operator/ReassignmentSnapshot/ReassignmentView/UnavailabilityReceipt/PhotoReceipt/PhotoGrant）+ 行映射工厂；List 分量紧凑构造器 `List.copyOf` 防御拷贝；`timeValue` 冻结 wire() DATETIME 分支 |
| `repair/RepairService.java` | 17 个方法出口类型化；`replay` 改 `<T> T replay(..., Class<T>)`，`remember` 产 `RepairSummary`，`saveReceipt` 结果参数改 `Object`；新增 `getDetail`/`submitSummary`；删除 `view()`/`returnSourceWire()`/Map 版装配（移入 RepairViews，内部存储/审计点改消费同一 record 单源装配）；`reassignmentSnapshot` 改产 `ReassignmentSnapshot` record（审计 detail 与 reassign 快照比对 `readTree(writeValueAsString(snapshot)).equals(input.snapshot())` 语义不变）；**保留 4 个 convertValue Map 镜像包装** `get`/`submit`/`submitLossRecovery`/`submitClosedLossRecovery`（禁改文件 InventoryRepairPort/LossRepairPort 以 Map 消费，见 §6-1）；`wire()` 保留供内部链使用；SQL/锁/异常零改动 |
| `repair/RepairController.java` | 15 个端点返回类型化视图（get 端点改调 `getDetail`、submit 端点改调 `submitSummary`）；`retry` 泛型化 `<T> T retry(Supplier<T>)`（CannotAcquireLockException 一次重试结构保持）；已类型化出口 eligible/returnSources/physicalCatalog/returnTarget 未动；请求体、校验、路径、`Idempotency-Key`/`If-Match-Version` 头一字未动 |
| `repair/RepairReassignmentController.java` | 3 个端点类型化（inspect→ReassignmentView、declareUnavailable→UnavailabilityReceipt、reassign→RepairDetail，内联重试循环未动）；请求体 record Input/Unavailability 未动 |
| `repair/RepairPhotoController.java` | upload→`PhotoReceipt`、grant→`PhotoGrant`（Map.of 装配换 record，键集相同）；content 二进制出口与响应头未动 |
| `repair/RepairPhotoService.java` | upload 返回 `PhotoReceipt`；registerPhoto 与冲突重放的原混合 Map 信封改为私有 `UploadOutcome` record（receipt 或 conflictCode+conflictFields），删除-再抛出的时序逐行保持（冲突路径先 storage.delete 后 throw；重放 objectId 不匹配同样删除） |
| `repair/RepairCoreWireShapeTest.java`（新增） | 18 条 wire 等价用例，见 §3 |

出口 → 类型对照（RepairController 15 + Reassignment 3 + Photo 2）：

| 旧 Map 出口 | 新 record |
|---|---|
| list：`items/total/page/pageSize`，items=view(头行) | `Page<RepairSummary>` |
| get 与 REASSIGN body：view 16 键 + 19 附加键（3 条件键） | `RepairDetail` |
| submit/decide/cancel/claim/accept-in-place/complete-in-place/accept-away/close-inbound/return-delivery/return-receipt/return-refusal/return-recollection/submitReturned：remember→view(头行) | `RepairSummary`（14 个幂等 operation 共用） |
| registerPhoto：`repairId/objectId/version` | `PhotoReceipt` |
| declareUnavailable：`id/version/recorded` | `UnavailabilityReceipt` |
| reassignment inspect：`snapshot/operators(+unavailableReason?)` | `ReassignmentView`（内嵌 `ReassignmentSnapshot`，factorySummary 条件键） |
| return-sources/{id}：returnSourceWire 11 键 + `asset` | `ReturnSourceDetail`（存储面单源复用 `ReturnSourceContext`） |
| eligible / return-sources / physical-catalog/{kind} / return-target | `AssetRepairPort.EligiblePage` / `LoanViews.Page<RepairFollowupSource>` / `AssetRepairPort.PhysicalPage` / `AssetRepairPort.ReturnTarget`（已类型化，复用未动） |
| photos/{object}/download-grant：`url/expiresAtEpochSecond` | `PhotoGrant` |
| 非出口（port 面，未动）：proxySnapshot/proxyReceipt/targetChangeContext/scrapSource/lossSource/handoff*/resume*/restore*/closeFrom*/factoryLossScrap/canRestoreFromScrap | JAVA-04/10b 已冻结类型面与既有 record，签名零改动 |

## 3. 判据：wire 等价测试

`RepairCoreWireShapeTest` 把改前 `RepairService.view()`、`wire()`、`returnSourceWire()`、
`loanWire()`、get() Map 装配、reassignment/reassignmentSnapshot 装配、actions LinkedHashMap、
RepairFactoryGuard.summary 消费形状逐字段冻结为 oracle，构造 JDBC 类型行（Long/Integer/
String/Timestamp/LocalDateTime/LocalDate/JSON 串/生成列 null/数值列 Integer 宽度变体）比对：

- 头行：Timestamp 主形态 + LocalDateTime 变体 + 可空列双 null；**显式 null 键甄别**
  （processorAccountId/acceptedAt/allowedMethod 键在且为 null）；
- 7 张子表行逐一 ≡ legacyWire：decision（snapshot_json 解析）、event（**detail_json 解析**，
  与 loss 域字符串口径区隔）、physicalHandling、inbound、returnHandover（生成列
  active_repair_id 字符串化/RECEIVED 态 null、delivered_by/received_by 保数值、
  round_number Integer 宽度变体）、approvedTarget（JSON 列 target_json 解析）、recollection；
- detail 全装配双态：含 factorySummary+latestLossReportId+manageFactory 三条件键齐备态 vs
  三者全缺态（has/assertFalse 直接断言），lockVersion/returnOrigin/loanExpectedReturnDate
  显式 null 断言；
- actions 矩阵双态（19 键 vs 18 键）；factorySummary 芯片四态（scope/round 有无）；
- returnSource：GET 出口 ≡ legacy 装配，且 source_context_json 存储文本 ≡ legacy wire 文本；
- reassignment：inspect 出口双态 + snapshot 各分量（lastPhysicalTime 持 Instant 直通、
  loan LoanRef ≡ legacyLoanWire、outstandingReturn ≡ legacyWire 单行）；
- **幂等存储形状旧 JSON 回放**（fams_repair_receipt.response_json 永久保留，跨版本必测）：
  1. legacyView 树 → `readValue(RepairSummary)` → 等价再序列化；
  2. legacy get 全树（富态 + lean 态）→ `readValue(RepairDetail)` → 等价；
  3. PHOTO 三键、DECLARE_UNAVAILABLE 三键旧树回放；
  4. typed Summary/Detail/两回执 自身 序列化→反序列化→再序列化 恒等；
  5. `get`/`submit` Map 镜像 convertValue ≡ legacyView（冻结禁改 port 消费面）；
- 跨域分量零改动形状冻结：RepairFollowupSource snake 键、Followup 多态经既有 businessType
  判别回放（无 @type 新键、子类保真）、ReturnTarget 11 camel 键、PhysicalPage per-kind 键集。

**单元 oracle 边界（JAVA-01 教训沿用）**：只证明「行→视图→存储/回放」序列化等价。真实 HTTP
运行时用例（`auth/RepairHttpRuntimeCase`、`RepairPhysicalHttpRuntimeCase`、
`RepairReturnHttpRuntimeCase`、`RepairReassignmentHttpRuntimeCase`、
`RepairProxyHttpRuntimeCase`、`InventoryRepairHttpRuntimeCase`、
`RepairPhysicalUpgradeRuntimeCase`、`RepairFactoryUpgradeRuntimeCase` 等）需要容器 MySQL，
本批环境无容器、且任务红线禁跑 `scripts/verify-d6-03a.py`，登记为门禁边界，由主代理合并
门禁实跑。

## 4. 实施代理自测（非产品门禁）

- 定向判据：`JAVA_HOME=graalvm-jdk-21.0.7 ./mvnw -pl backend -Dtest='RepairCoreWireShapeTest' test`
  → **Tests run: 18, Failures: 0, Errors: 0**。
- 全量结构回归（同任务书口径）：`-Dtest='*WireShapeTest,TransferCalendarTest'`
  → **Tests run: 236, Failures: 0, Errors: 0**（派发时基线 218 + 本批新增 18，零退化；
  27 个套件类全部执行，含 JAVA-15 在途 InboundWireShapeTest 与本域
  InitialPortsWireShapeTest/AssetRepairPortWireShapeTest/LoanDeepWireShapeTest/
  ProxyWireShapeTest/TargetChangeWireShapeTest）。
- 全仓编译：`./mvnw -pl backend -q test-compile` → exit=0（含在途 inbound 批文件，同树共编译通过）。
- Checkstyle：`./mvnw -pl backend -Pquality checkstyle:check@check-java-conventions` →
  **BUILD SUCCESS**，新文件末尾换行合规。
- 未跑：SpotBugs/JaCoCo/真实 HTTP 运行时用例与全量门禁——按任务红线留给主代理合并门禁；
  EI_EXPOSE_REP 风险已按先例在构造侧消解（List 分量拷贝、Map 分量无新增、Object 分量仅承载
  不可变 JDBC 标量/Instant/LocalDate）。
- 同树并发 mvn 竞态：JAVA-15 已交付在途（文件不再变动），本批三轮 mvn 均一次通过，未触发
  35 秒重试条款。

## 5. 影响分析（GitNexus）

编辑前执行 `impact -d upstream --repo fams-manage`（Node 24 前缀）：

- `RepairController`/`RepairPhotoService`/`RepairPhotoController`/
  `RepairReassignmentController`（类级）：**LOW**（direct 0～1，包内）。
- `RepairService`（类级）：**HIGH（direct 15）**——全部 15 个上游文件逐一核实：
  repair 包内 8 个（两控制器 + Photo 两件 + InventoryRepairPort/LossRepairPort/
  RepairCollaboration/ProxyReceipt/TargetChange Port）、包外 4 个（loss/LossService、
  scrap/Scrap×3）。包外消费者只用嵌套 record 与 port 方法（ScrapSource/FactoryLossScrap/
  lossSource/handoff*/resume* 等），签名零改动；禁改文件对 Map 版 get/submit/
  submitLossRecovery 的赋值需求由 convertValue 镜像包装满足（§6-1）。**该 HIGH 为消费面广但
  全部兼容的真实风险面，非阻断项**；无越批 HIGH/CRITICAL。
- 方法级 `get`：20 个同名候选（max CRITICAL 110）——索引同名伪边（loss/asset/loan 等域各自
  独立的 get），grep 核实 RepairService.get 真实消费面仅 InventoryRepairPort×2、
  RepairCollaborationService×2 与控制器（本批文件），全部在册。
- `view/wire/replay/remember`：repair 包内 private（编译器封闭），无真实跨域边。
- `detect-changes`（工作树 vs HEAD）：12 文件 105 符号、risk critical——**整树口径**，混入
  JAVA-15 在途 inbound 8 文件与 AGENTS.md 并行改动；本批 diff 仅 5+2 文件，属上列核实面。
- 索引不含本批新符号（RepairViews）属正常。

## 6. 偏差与遗留

1. **裁决点：Map 兼容包装永久位（与 14b1→14c 先例的差异）**。`InventoryRepairPort.link(row)`
   （禁改）把 `repairs.get/submit` 返回值直接赋给 `Map<String,Object>` 形参，
   `LossRepairPort.Created.fromView(Map)`（禁改）同理消费 submitLossRecovery/
   submitClosedLossRecovery。故 `get`/`submit`/`submitLossRecovery`/`submitClosedLossRecovery`
   保留 `Map<String,Object>` 签名、实现为对类型化出口的 `json.convertValue(..., WIRE_MAP)`
   （单一装配源，永不漂移）。requisition 先例中包装由后续批删除；repair 无后续批登记消费方，
   包装按**终态兼容面**保留，判据 §3-5 冻结其等价性。
2. **归属裁决：RepairReassignmentController 属 16a「核心」**。repair 域无独立
   ReassignmentService——inspect/declareUnavailable/reassign 三出口装配全部位于
   RepairService.reassignment/declareUnavailable/reassign 与其私有件（reassignmentSnapshot/
   reassignable/unavailabilityReason/canonicalReassignment），随核心一并类型化；
   `RepairCollaboration*`（16c）与 `RepairFactory*`（16b）的 service 归属与本批文件零交叉，
   未发生需裁决的交叉装配。
3. **FactorySummary 消费 16b 表面 Map**：RepairFactoryGuard.summary 未动，其被 core 出口
   消费的键形（scope/round/actualDispatchCount/followupOpen 与内部 camel 键、已字符串化 id、
   已 toString 的 expectedReturnDate）由 RepairViews.factorySummary 映射冻结；16b 若改形，
   判据 §3 芯片用例即红，属预期防退化耦合，已在类 javadoc 登记。
4. **排除清单（保留 Map/不动，按规格）**：内部行 Map（header/outstandingReturn/approvedTarget/
   returnHandover 行、basis/`context` 快照装配、reject fields LinkedHashMap）、审计与幂等存储
   payload（`frozen` snapshot、audit detail、loanWire Map——ProxyViews.RepairSnapshot.loan
   分量的既有类型要求）、`hash` 指纹输入、`canonicalReassignment` 指纹树、
   `requireRepairSource` 校验链、`targetChangeContext` 的 target 内联 Map（其出口
   TargetChangeViews.RepairContext 早已类型化）——一律未动。
   `describeReturnTarget/targetDisplay` 信封本批出口未引用（10b 裁决面），零扩大。
5. **口径甄别记录**：detail/history 各行的 `delivered_by/received_by/approved_by/round_number/
   before_*/location_version` 等数值列保持数值（旧 wire 只字符串化 `id`/`*_id`）；
   `lockVersion`（get 与 reassignment snapshot）由 JDBC 原值 Object 改 `((Number)).longValue()`
   装箱 Long/BIGINT 文本同值（JAVA-11a 口径）；`lastPhysicalTime` 持
   `RepairFactoryGuard.latestPhysicalTime` 的 Instant 旧直通（wire 不识别 Instant，序列化文本由
   全局 mapper 决定，两侧同 mapper）；`loanExpectedReturnDate` 保持 LocalDate 分量（旧码同为
   LocalDate 对象直通，非 toString）；`occurredAt/claimedAt/...` 经 wire DATETIME 分支为 ISO
   String（timeValue 复刻 Timestamp/LocalDateTime 双驱动形态，其余形态沿 14b1/transfer 先例
   不规范化）。
6. **actions 表达式复用**：`changeReturnTargetAction = deliverReturnAction` 取代两处逐字相同
   的布尔表达式——同值纯表达式复用，无短路/求值顺序差异；`requestLoss = requestScrap`（翻转后）
   保持旧 `Boolean.TRUE.equals(actions.get("requestScrap"))` 语义。
7. **旧幂等回执经 record 再序列化键序归一为声明序**（旧 Map.of/Map.ofEntries 键序本就跨 JVM
   随机、Map 树无契约），语义等价；跨版本回放由判据 §3 覆盖。
   source_context_json **新写**行仍与旧文本逐字节同序（ReturnSourceContext 声明序 = 旧
   LinkedHashMap 序）。
8. **RepairPhotoService 冲突信封**：旧代码把 `conflictFields` 经 `k.toString()/v.toString()`
   重拷贝（字段本就是 `Map<String,String>`，toString 对非 null 值恒等）；新代码
   `LinkedHashMap.putAll` 直拷——仅在 null 值输入时新旧有别（旧 NPE、新保留 null），
   现网 fields 值全部为 `.toString()` 产物不可能为 null，登记为不可达路径偏差。
9. 契约/OpenAPI/前端：零改动（无 API 形状变化，无接口文档改动）。
10. AOCI 认知维护：本会话工具面未暴露 aoci_* MCP 服务，无法调用 `aoci_maintain`；受管理对象
    已达最终稳定态，登记该收尾步骤未执行，交由持有 AOCI 工具的会话对齐。

## 7. 下一步

- 主代理合并门禁：全量 Java 质量序列 + 真实 HTTP 运行时用例
  （Repair*/InventoryRepair/Loss/Handover 消费面 + Upgrade 链）实跑；SpotBugs 关注
  RepairViews List 分量拷贝位（已按先例预清）。
- 16b（repair factory 面）：RepairFactoryGuard.summary 类型化时回收
  `RepairViews.FactorySummary` 消费耦合；16c（collaboration 面）：其 `repairs.get` 消费为
  纯可见性校验（结果丢弃），类型化时可直接切 `getDetail` 并观察 §6-1 包装是否需要迁移。

## 主代理产品门禁（波次五合并实跑）

主代理在波次五合并树固定快照副本（rsync 排除并行 WIP；快照含三路全部产物与主代理两处收口修正：
RepairService.get javadoc 归位、16b parts COUNT 的 requireNonNull）实跑完整 Java 序列：
**JAVA01_VERIFY_PASS**，83 步 exit=0，Checkstyle/SpotBugs 0，覆盖率 LINE 93.36%、BRANCH 75.60%；
日志内 74 个 surefire 阶段汇总 401 项 0 失败 0 错误。首轮门禁红于 16b parts 的
NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE（COUNT 直进 long 拆箱），按 JAVA-09 先例
`Objects.requireNonNull(…, "PARTS_COUNT_MISSING")` 修复后复跑绿。三个新套件另在门禁副本内
直接复跑 42/42 绿。主树合并判据：29 个 WireShape 类 260/260。
详见[波次证据](evidence/JAVA-16-java-gate.txt)。
