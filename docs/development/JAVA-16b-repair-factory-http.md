# JAVA-16b · repair 域「厂内维修」HTTP 出口类型化

> 用户指定的后端结构批（HTTP 出口波次 · repair 厂内面子批），不进入 D6-01～D6-27 台账、不占用其状态。
> 日期：2026-11-22 · 分支：main · 基线：`699724a` + JAVA-16a 在途交付（RepairViews 22 record，未提交）+ JAVA-15 inbound 已交付
> 状态：主代理审计通过，波次五合并门禁通过（含首轮 SpotBugs NP 红项修复复跑，见文末证据节），已收口提交。

## 1. 背景与红线

完全复刻 JAVA-16a/11a/15 标准：厂内维修 HTTP 响应类型化为具名 record（追加进 16a 的
`RepairViews.java`，16a 已建 22 record 组件名/类型/注解零改动——只尾部追加 16b 段，
未触碰 16c 并行追加的 collaboration 段）；内部行 Map、审计 detail_json、幂等指纹 digest
输入（`hash(operation, version, input)` 与 registerEvidence 的 metadata Map）保留；请求体
`RepairFactoryInput` 一字未动；契约/OpenAPI/前端零改动（无 API 形状变化）。

零改动核实：`RepairFactoryGuard.java`（含 16a 依赖的 ScrapEvidence 消费面）、
`RepairFactoryController` 路径/头/校验注解/`retry` 一次重试结构、
`RepairFactoryReplacementReferenceController`（未改，见 §6-4）、`RepairPhotoService`
（厂内照片出口与 repair 照片域零交叉，未动）、`RepairFactoryInput/Payloads/CostPolicy`、
`inbound/`、`contracts/`、`apps/`、16a 面（RepairController/RepairReassignmentController/
RepairService 的 factory 互调签名 matchesFactoryScrapEvidence/requireFactoryScrapEvidenceObjects/
scrapSource 等）全部零 diff。SQL 语句逐条比对文本相同（diff 中仅 3 条 SQL 行换行重排，
字符串拼接结果一致）；FOR UPDATE 锁顺序与各出口查询求值顺序逐点保持（含 get 的
reply 锁查询先于 pending/approved/active/summary/costSummary、round 详情先
roundView 再 evidence 再 files、parts 先 items 再 total 再 partsPhase、
replacements 先 items 再 replacementPhase 再 lock 查询）。

与 16a 的口径差异（本域改前代码逐字冻结为 oracle 核对）：

1. **投影口径 = 冻结 `RepairFactoryService.wire()/fields()/instant()`**，不是 repair core 的
   view()/wire()：factory `wire()` 无 id 字符串化分支（history/evidence 行的 `id/repair_id/
   actor_account_id/uploader_account_id` 保持 JDBC 数值、键保持 snake_case），有
   java.sql.Date→ISO date、Instant→ISO instant、BigDecimal→toPlainString 三个 core wire
   没有的分支；`fields()` 仅当值为 Number 且列名为 `id`/`*_id`/`*_by` 时 `.toString()`，
   否则过 wire。条件键甄别：scope/round 段、roundView 的 actions/evidence/files、命令回执的
   partsChangeId/replacementId/sourceId/replacementAssetId/inboundReceiptId/objectId 为
   put-守卫条件键（NON_NULL）；pendingScope/approvedScope/activeRound/summary/lockVersion/
   processorAccountId/costFact/decisionBasis 为恒 put 显式 null 键（默认包含）。
2. **幂等回放泛型 `Class<T>` 蓝本（16a/transfer 先例，repair 回执表无 resource_version，
   finish 不需要版本回取）**：`replay(...)` 改 `<T> T replay(..., Class<T>)`，
   `save` 结果参数改 `Object`。工厂 15 个写幂等表的 operation（SCOPE_REQUEST/DECIDE/WITHDRAW、
   ROUND_CREATE/CANCEL/DISPATCH/RECEIVE/QUOTE/COST、PARTS、REPLACEMENT、EVIDENCE）存储形状
   全部收敛为一个 `FactoryReply`（scope-only、scope+round、+partsChangeId、+replacement 四键、
   +objectId 五类形状），逐类旧 JSON 回放等价由判据 §3-4 覆盖。
3. **RepairFactoryGuard.summary 保持返回 Map（未触碰）**：本批按 16a §6-3/§7 的登记直接复用
   `RepairViews.FactorySummary` 消费该 Map（get 出口 summary 键 = 旧 `wire(guardMap)` 等价），
   Guard 面 Map 键形逐字未变，16a `RepairCoreWireShapeTest` 18 项零退化；本批判据另加
   「RepairDetail.factorySummary 分量类型 + NON_NULL 注解 + 芯片键集」防退化反射断言。
4. **跨域分量零改动复用**：`RepairFactoryService.EvidenceFile/fileProjection`（JAVA-06 冻结，
   AttachmentEvidenceWireShapeTest 消费面不动，作为 FactoryRoundView.files 分量）；
   `FactoryReplacementInboundPort.Context`（JAVA-10c 冻结四键 record，replacementView 的
   inbound 分量原样内嵌——该面本就是类型化 passthrough，无 Map 扩大）；
   `RepairViews.Page`（16a 建）复用于 rounds/history。

## 2. 改动清单与出口映射

主代码 6 改（RepairViews 追加、RepairFactoryService、RepairFactoryController、
RepairFactoryEvidenceController、RepairFactoryEvidenceService、
RepairFactoryReplacementReferenceService、RepairFactoryCosts），测试 1 增，本记录 1 增：

| 文件 | 变化 |
|---|---|
| `repair/RepairViews.java` | 追加 16 个工厂出口 record（FactoryScopeView/FactoryQuoteRow/FactoryCostFactRow/FactoryRoundActions/FactoryEvidenceRow/FactoryRoundView(含 withRoundDetail)/FactoryEventRow/FactoryActions/FactoryCostSummary/FactoryReply/FactoryDetail/FactoryPartRow/FactoryPartsPage/FactoryReplacementRow/FactoryReplacementsPage + 复用 FactorySummary 三件）+ 纯投影工厂 `factoryScope/factoryRound/factoryQuotes/factoryEvent(s)/factoryEvidence(s)/factoryPart/factoryReplacement/factoryReply/factoryDetail` 与 `money/dateText` helper（复刻 factory wire 的 DECIMAL/DATE 分支）；List 分量紧凑构造器防御拷贝；16a 已建 record 零触碰 |
| `repair/RepairFactoryService.java` | 17 个方法出口类型化（get/rounds/round/history→record；9 个命令与 parts/recordParts/replacements/recordReplacement→record；registerEvidence→FactoryReply）；`replay` 泛型化、`remember/save` 改 Object；新增 `lockVersion(row)`（reply 锁查询单源）；`reply/scopeView/roundView` 改产 record 并保持查询顺序；`replacementView` 改产 FactoryReplacementRow；删除仅服务出口的 `fields()`；`wire()` 保留供内部链（endScope technicalOpinions、recordMoney authorization）；`EvidenceFile/fileProjection/repair` 内部行 Map、frozen 快照、audit/conflict/detail_json 全部未动；SQL/锁/异常零改动 |
| `repair/RepairFactoryController.java` | 16 个端点返回类型化 record；`retry` 改 `<T> T retry(Supplier<T>)`（CannotAcquireLockException 一次重试结构保持）；路径/头/校验/请求体零改动 |
| `repair/RepairFactoryEvidenceController.java` | upload→`RepairViews.FactoryReply`、grant→`AttachmentGrantSigner.Grant`（原 Object passthrough 的既有类型化 record，声明收紧不改形状）；content 二进制出口与响应头未动 |
| `repair/RepairFactoryEvidenceService.java` | upload 返回 `FactoryReply`；写回调整理为私有 `UploadOutcome` record（16a RepairPhotoService 先例），删除-再抛出时序逐行保持（冲突先 storage.delete 后 throw；重放 objectId 不匹配同样删除） |
| `repair/RepairFactoryReplacementReferenceService.java` | `replacements(...).get("canRecord")` → `.canRecord()`；catalog passthrough 出口未动 |
| `repair/RepairFactoryCosts.java` | `summary` 改产 `RepairViews.FactoryCostSummary`（唯一 HTTP 消费面=get 出口 costSummary）；`basis/approved/dispatchScope/policy/total/unassessed/blocked` 全部保留 Map/内部形状（request_snapshot_json 存储与 decide 比对消费面零改动） |
| `repair/RepairFactoryWireShapeTest.java`（新增） | 14 条 wire 等价用例，见 §3 |

出口 → 类型对照：

| 旧 Map 出口 | 新 record |
|---|---|
| GET /factory：reply 头 7 键 + pendingScope/approvedScope/activeRound/summary/photosAvailable/costSummary/actions(9 键 Map.of) | `FactoryDetail`（summary 分量 = 16a `FactorySummary` 复用） |
| GET /factory/rounds：items(roundView)/total/page/pageSize | `RepairViews.Page<FactoryRoundView>` |
| GET /factory/rounds/{round}：roundView + actions(3 键)+evidence+files | `FactoryRoundView.withRoundDetail(actions, evidence, files)` |
| GET /factory/history：items(wire 原 snake 行)/total/page/pageSize | `Page<FactoryEventRow>` |
| POST scopes/scope-decision/scope-withdrawal | `FactoryReply`（scope 段，round 条件缺省） |
| POST rounds / rounds/{r}/cancel / dispatch / receive-original / quotes / cost | `FactoryReply`（scope+round 段） |
| POST rounds/{r}/parts | `FactoryReply` + partsChangeId |
| POST rounds/{r}/replacements | `FactoryReply` + replacementId/sourceId/replacementAssetId/inboundReceiptId |
| POST evidence（upload/registerEvidence） | `FactoryReply` + objectId |
| GET rounds/{r}/parts | `FactoryPartsPage`（items=FactoryPartRow） |
| GET rounds/{r}/replacements | `FactoryReplacementsPage`（items=FactoryReplacementRow，inbound=JAVA-10c Context） |
| evidence/{object}/grant | `AttachmentGrantSigner.Grant`（既有类型 passthrough） |
| evidence/{object}/content | `ResponseEntity<byte[]>`（二进制，未动） |
| replacement-references/{kind} | masterdata 四型 typed page passthrough（见 §6-4） |

## 3. 判据：wire 等价测试

`RepairFactoryWireShapeTest` 把改前 `RepairFactoryService.wire()/fields()/instant()`、
scopeView/roundView/reply/get/round 详情/history/parts/replacements/costSummary 装配逐字段
verbatim 冻结为 oracle，构造 JDBC 类型行（Long/Integer/String/Timestamp/LocalDateTime/
java.sql.Date/BigDecimal/JSON 串/nullable 列双态）JSON 文本往返树比较（key 序无关、
键存在性/显式 null/值形状严格），14 用例：

1. scopeView 双态（PENDING decision null / APPROVED 齐备 / UNLIMITED costLimit 显式 null）；
2. roundView 三态（PENDING_DISPATCH 全 dispatch/receipt 键 null+costFact null、
   DISPATCHED+quote、RECEIVED+quote×2+costFact）+ 列表项不得泄漏 detail 三键；
3. round 详情出口（actions/evidence/files 追加 + evidence snake 键数值不字符串化）；
4. history 页（detail_json 解析、id 数值、scope_id/round_id null 变体）；
5. get 详情双态（chips 齐备 vs 全 null，lockVersion 显式 null）；
6. scope 命令回执（has scope / 无 round、partsChangeId、objectId 条件键甄别）；
7. round 命令回执（lockVersion null 恒键、round.costFact 恒键、round 段无 files）；
8. parts/replacement/evidence 三种追加键回执形状；
9. parts 页 Map.of ≡ record；
10. replacements 页双态（inbound Context 有/无 → replacementAssetId 联动 null）；
11. costSummary 8 键 LinkedHashMap ≡ record；
12. **Guard/16a 耦合防退化**：guard Map ≡ `RepairViews.factorySummary`（全/空芯片双态）、
    FactorySummary/scope/round 键集冻结、旧存储芯片文本可反回放回、
    `RepairDetail.factorySummary` 分量类型与 NON_NULL 注解反射冻结；
13. **幂等存储形状旧 JSON 回放**（fams_repair_receipt.response_json 永久保留，跨版本必测）：
    scope-only、scope+round、+partsChangeId、+replacement 四键、+objectId 五类 legacy 存储树
    `readValue(FactoryReply)` 后恒等再序列化；
14. typed 各 record 自身 序列化→反序列化→再序列化 恒等。

**单元 oracle 边界（16a 教训沿用）**：只证明「行→视图→存储/回放」序列化等价。真实 HTTP
运行时用例（RepairFactoryUpgradeRuntimeCase 等）需要容器 MySQL，本批环境无容器、且任务红线
禁跑 `scripts/verify-d6-03a.py`，登记为门禁边界，由主代理合并门禁实跑。

## 4. 实施代理自测（非产品门禁）

- 定向判据：`JAVA_HOME=graalvm-jdk-21.0.7 ./mvnw -pl backend -Dtest='RepairFactoryWireShapeTest' test`
  → **Tests run: 14, Failures: 0, Errors: 0**。
- 全量结构回归：`-Dtest='RepairCoreWireShapeTest,*WireShapeTest,TransferCalendarTest'`
  → **Tests run: 260, Failures: 0, Errors: 0**（16a 交付口径 236 + 16c 并行在途
  RepairCollaborationWireShapeTest 10 + 本批 14；**16a 的 RepairCoreWireShapeTest 18 项
  逐用例零退化**，含 FactorySummary 芯片消费面）。
- 全仓编译：`./mvnw -pl backend -q test-compile` → BUILD SUCCESS（含 16a/16c 在途同树共编译）。
- Checkstyle：`./mvnw -pl backend -Pquality checkstyle:check@check-java-conventions` →
  **BUILD SUCCESS**，新文件末尾换行合规。
- 未跑：SpotBugs/JaCoCo/真实 HTTP 运行时用例与全量门禁——按任务红线留主代理合并门禁；
  EI_EXPOSE_REP 风险已按先例在构造侧消解（新增 List 分量全部紧凑构造器 `List.copyOf`；
  Map 分量零新增——inbound 分量为 JAVA-10c 不可变 record；Object 承载分量零新增，
  files 沿 EvidenceFile 既有形状）。
- 同树并发：16a/16c 在途文件（RepairViews/RepairCollaboration*）编辑期间两轮 mvn 均一次
  通过，未触发 35 秒重试条款。

## 5. 影响分析（GitNexus）

编辑前执行 `impact -d upstream --repo fams-manage`（Node 24 前缀）：

- `RepairFactoryService`（类级）：**MEDIUM（direct 6）**——LossService（只用
  lossSource/handoffToLoss/restoreFromLoss/resumeAfterLoss 与 ScrapEvidence/LossSource
  record，签名零改动）、RepairFactoryController（本批）、RepairFactoryEvidenceService
  （本批）、RepairFactoryReplacementReferenceService（本批）及两个嵌套 record 消费位
  （ScrapReviewService 用 ScrapEvidence、RepairService 用 scrapEvidence/
  replacementScrapEvidence/requireScrapEvidenceObjects/approveInitially/dispatchedLossScrapVersion
  ——全部在册零改动）。真实风险面消费全在本批文件，非阻断项。
- `RepairFactoryController`（类级）：**LOW（direct 0）**。
- 方法级 `replacements`：3 同名候选 max **LOW**（Service 面 direct 2=控制器+引用服务，均本批；
  Property 边为字段同名伪边，grep 核实）。
- 方法级 `registerEvidence`：**LOW（direct 1）**——唯一上游
  RepairFactoryEvidenceService.upload（本批），Attachment 模块位为 writer.write 回调边。
- `RepairFactoryGuard`（类级）：**LOW（direct 2：RepairFactoryService/RepairService）**——本批
  未改 Guard，`summary` 返回 Map 键形逐字未变，16a 判据全绿即耦合面未漂移证明。
- 同名伪边登记：`get/round/rounds/history/dispatch/receive/save/upload/grant` 等通用名在
  loss/asset/loan/inbound/attachment 域各有独立符号，grep 核实真实消费面全部落在本批
  repair 包内文件与已核实的禁改零改动签名。
- 索引不含本批新符号（RepairViews 16b 段）属正常。

## 6. 偏差与裁决点

1. **无新增裁决点**：出口收敛不需要触碰 `RepairFactoryGuard.java`（ScrapEvidence 面零改动，
   任务预登记的裁决点未触发）；FactoryReplacementInboundPort/`describeReturnTarget` 类信封
   零扩大。
2. **reply 头数值口径**：`version/assetVersion/ownershipVersion/lockVersion` 由 JDBC 原值
   Object 改 `long/Long`（BIGINT 文本同值，JAVA-11a/16a 口径）；`round_number`（INT）同口径
   改 long 分量。历史存储 JSON 回放由判据 §3-13 覆盖。
3. **replacements 的 canRequestScrap**：旧码为探锁存在性构造了整个 `reply(row,null,null)` Map
   （只读 lockVersion 键）；新码直接 `lockVersion(row) != null`——同一条锁查询、同一求值位置，
   省去的只是无人消费的 Map 装配。
4. **replacement-references 出口保持 `Object` 声明**：catalog 面是 masterdata 四个 typed page
   record 的运行时 union（`FactoryReplacementCatalogPort.references` 即 Object passthrough，
   禁改面外文件）；HTTP 实际载荷早已类型化、repair 域不产 Map，收紧声明需 masterdata 引入
   union 超类型，超出本批范围，登记不扩大。
5. **evidence upload 冲突信封**：旧代码把 reply Map 与 `Map.of("conflictCode","conflictFields")`
   混用同一 Map 通道；新代码用私有 `UploadOutcome` record（16a RepairPhotoService §6-8 同款
   先例），conflictFields 由逐键 `toString()` 重拷贝改 `putAll` 直拷（值本就是
   `Map<String,String>`，仅 null 值输入的不可达路径有别，同 16a 登记）。
6. **存储/审计单源保留 Map**：`frozen`（scope 决定快照、money 快照）、`metadata`（evidence
   指纹输入）、audit `detail_json`、`basis`/`costBasis`（request_snapshot_json 存储与
   decide 的 JsonNode 等值比对）、`publicDetail`（REPAIR_ 事件）、conflict fields——一律
   LinkedHashMap 原样；HTTP 出口与存储 payload 自此共享同一行输入但两套装配，判据只冻结
   出口形与既有存储形状回放的等价，**新写回执文本 = 旧装配声明序**（FactoryReply 声明序 =
   旧 LinkedHashMap put 序，extras 居尾 = 旧 put 追加位）。
7. **registerEvidence 重放路径**：旧代码把存储 Map 原样返回（含 objectId）；新代码
   `readValue(FactoryReply)`——判据 §3-13 第 5 形覆盖；`RepairFactoryEvidenceService` 的
   「重放 objectId ≠ 新上传 id 则删除字节」判定改读 record 分量，语义逐行相同。
8. **instant/time 双分支甄别**：replacementView/partsView 旧用 `instant(...).toString()`
   （四分支含 Instant.parse 兜底），roundView 用 `wire()`（Timestamp/LocalDateTime 两分支）——
   DATETIME(3) 列 JDBC 只回 Timestamp/LocalDateTime，统一走 16a `timeValue` 双分支，
   序列化文本对全部真实驱动形态一致；`expected_return_date` 走 `dateText`（sql.Date→
   LocalDate ISO），guard 芯片的 `expectedReturnDate` 保持 Guard 已 `.toString()` 的 String。
9. 契约/OpenAPI/前端：零改动（无 API 形状变化）。
10. AOCI 认知维护：本会话工具面未暴露 aoci_* MCP 服务，无法调用 `aoci_maintain`；受管理对象
    已达最终稳定态，登记该收尾步骤未执行，交由持有 AOCI 工具的会话对齐。

## 7. 下一步

- 主代理合并门禁：全量 Java 质量序列 + 真实 HTTP 运行时用例
  （RepairFactory/RepairEvidence/ReplacementReference 消费面 + RepairFactoryUpgrade 链）实跑；
  SpotBugs 关注 FactoryRoundView 三分量拷贝位（已按先例预清）。
- 17（下一结构批）：repair 域 HTTP 面至此收口；Guard.summary 若未来类型化，按 16a §7 回收
  `RepairViews.factorySummary` 的 Map 消费耦合。

## 主代理产品门禁（波次五合并实跑）

主代理在波次五合并树固定快照副本（rsync 排除并行 WIP；快照含三路全部产物与主代理两处收口修正：
RepairService.get javadoc 归位、16b parts COUNT 的 requireNonNull）实跑完整 Java 序列：
**JAVA01_VERIFY_PASS**，83 步 exit=0，Checkstyle/SpotBugs 0，覆盖率 LINE 93.36%、BRANCH 75.60%；
日志内 74 个 surefire 阶段汇总 401 项 0 失败 0 错误。首轮门禁红于 16b parts 的
NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE（COUNT 直进 long 拆箱），按 JAVA-09 先例
`Objects.requireNonNull(…, "PARTS_COUNT_MISSING")` 修复后复跑绿。三个新套件另在门禁副本内
直接复跑 42/42 绿。主树合并判据：29 个 WireShape 类 260/260。
详见[波次证据](evidence/JAVA-16-java-gate.txt)。
