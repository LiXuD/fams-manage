# JAVA-17a · inventory 域「盘点单/差异/就绪」HTTP 出口类型化

> 用户指定的后端结构批（HTTP 出口波次 · inventory 最重子批），不进入 D6-01～D6-27 台账、不占用其状态。
> 日期：2026-11-22 · 分支：main · 基线：`699724a` + 主树在途 JAVA-16a/16b/16c repair 批与 JAVA-15 inbound（本批零触碰其文件）
> 状态：主代理审计通过，波次六合并门禁通过（见文末证据节），已收口提交。

## 1. 背景与红线

完全复刻 JAVA-01/11a/14b1/15/16a 标准：HTTP 响应类型化；内部行 Map、审计 `detail_json`（盘点
快照/证据 payload）、幂等指纹 `digest`/`hash` 输入保留 Map；契约 `contracts/fams-core-v1.yaml`、
OpenAPI 生成类、`packages/api-types`、前端与小程序零改动；SQL 语句、参数顺序、FOR UPDATE 锁顺序、
异常码、HTTP 状态码、端点路径、请求体 `InventorySelectionInput`/`InventoryDifferenceInput`/
`InventoryEvidenceInput`/`InventoryReadinessInput`/`MissingLossInput` 一字未动。本批只触碰
`InventorySelectionController/Service`、`InventoryDifferenceController/Service`、
`InventoryReadinessController/Service` 六个既有文件 + 新增 `SelectionViews`、
`InventorySelectionWireShapeTest` 与本记录。**`InventorySealEvidence.java` 零 diff**（§6-9），
17b/17c/17d 归属文件（Seal/Synchronization/Observation、Finding/Review/Photo/RepairPhoto、
Inventory 主/Late/LateDamage）、全部 Port、repair/、inbound/、requisition/、contracts/、apps/、
DESIGN-HANDOFF.md、AGENTS.md 零触碰；未 commit/push，未跑 `scripts/verify-d6-03a.py`。

SQL 零改动以脚本逐条比对取证：改造前后从 `git show HEAD` 与工作树分别按调用点抽取拼接后的 SQL
文本，`InventorySelectionService` 40 条、`InventoryDifferenceService` 34 条、
`InventoryReadinessService` 24 条、`InventorySealEvidence` 8 条，共 **106 条逐条文本相同**
（diff 中仅换行/缩进重排）。

inventory 与先例的关键差异（改前代码逐字冻结为 oracle 核对）：

1. **投影口径 = 三个各自不同的私有 `wire()`，不得互套**：
   - `InventorySelectionService.wire`：解析 `*_json` 字符串列成树；**数值** `id`/`*_id`/`*Id`
     键 `.toString()`；Timestamp/LocalDateTime → ISO instant；
   - `InventoryDifferenceService.wire`：**不解析** `*_json`；只字符串化数值 `id`/`*_id`；
   - `InventoryReadinessService.wire`：解析 `*_json`；对 **任何非 null** 的 `id`/`*_id` 值
     `.toString()`。
   三者在各出口实际投影的列集上恒等，判据 §3 逐出口冻结：本批涉及的
   `fams_inventory_review_subject/decision/transfer/repair/evidence_disposition/item_review/
   unobserved_reason` 均无 `*_json` 列（`DifferenceService` 不解析也就无差异），而唯一进入
   Selection 递归的 JSON 载荷 `physical_snapshot_json` 由 `physical()` 的 `asText()` 生成（只含
   字符串/null），`range_json`（camel `*Id` 数值）只被 **Readiness** 变体投影，而该变体不认 camel
   `Id` ⇒ 递归恒等，`snapshot_json` 由 V31 视图 `CAST(... AS CHAR)` 保证全部 id 键为字符串 ⇒
   以 `JsonNode` 原样承载与旧树逐值相同。
2. **空 review 的两态形状**：`detail` 的 `review` 在无复核行时是**单键** `{"version":0}`，有行时是
   **13 键整行且含显式 null**（`observation_id`/`verified_observed_at` 可空）。单一 record 无法同时
   表达"键缺失"与"键在且为 null"，故本批用 `sealed interface ReviewChip permits ReviewState,
   NoReview`，**不加 `@JsonTypeInfo`**：Jackson 按运行时类型序列化，树中无判别键，与旧 Map 完全一致
   （判据 §3-1/3-2 双向断言键数 13 与 1）。幂等存储面的 `SelectionReply/EvidenceAcceptanceReply`
   声明为具体 `ReviewState`（命令后复核行必在），回放不依赖接口多态。
3. **幂等回放按泛型 `Class<T>` 蓝本（16a/15/Transfer 原型，无 Reply 接口）**：
   `fams_inventory_review_receipt` 无 resource_version 列，`finish/finishEvidence` 不再从结果取版本。
   旧存储 JSON 的 9 种形状全部逐一回放等价（判据 §3-9）：`SELECT_EVIDENCE`、`DECIDE_EVIDENCE`、
   `ACCEPT_SELECTED`、`PREPARE_DIFFERENCES`、`DECIDE_DIFFERENCES`（transfer 分支 5 键 / repair 分支
   7 键两形）、`UNOBSERVED:{itemId}`、`MISSING_LOSS:{reasonId}`、`WAITING_REVIEW`。
4. **条件键只有两处**：`DifferenceDecisionItem.linkedRepairId/repairStatus`（旧码仅 repair 分支的
   七键 `Map.of` put）与 `detail.review` 的整键两态（第 2 点）。其余键一律恒在，全局
   JsonConfiguration inclusion=ALWAYS 下显式 null 照写——包括 `ReviewState` 的两个可空列、
   `TaskRow` 的 6 个可空列、`ReadinessItemRow` 的 7 个 LEFT JOIN 列、`EvidenceSubjectRow` 的 6 个
   可空列与 `ReadinessItem.current`/`MissingLossRow.report`。
5. **跨域分量零改动复用**：`AssetInventoryReviewPort.Movement`（`movementEvents` 直通）、
   `InventoryLossPort.Progress`（`missingLosses[].report` 直通，其 `transferred()/canRetry()` 非
   getter 命名、不入线，两侧同类同结论）、`InventoryRepairPort.Link`/`InventoryTransferPort.Link`
   只在服务内部消费。文件全部零 diff。
6. **本批无 Map 兼容包装需求**：全仓 grep 证明三服务的全部外部消费点只有各自控制器（15 出口）与
   `InventorySealEvidence.requiredDimensions`，无任何禁改 Port 以 Map 消费本批 HTTP 出口 ⇒
   与 15 §6-1/16a §6-1 不同，**无永久 convertValue 包装登记**。

## 2. 改动清单与出口映射

主代码 6 改 1 增，测试 1 增，本记录 1 增：

| 文件 | 变化 |
|---|---|
| `inventory/SelectionViews.java`（新增，722 行） | 30 个响应 record + 1 个 `sealed interface ReviewChip` + 行映射工厂（reviewChip/reviewState/selectionEvents/decision/transferLink/repairLink/evidenceSubject/differenceSubject/evidenceDisposition/unobservedReason/taskRow/readinessItemRow）；snake_case 行投影全量 `@JsonProperty`；条件键 `@JsonInclude(NON_NULL)`；含 List 分量的 record 一律紧凑构造器 `List.copyOf` 防御拷贝；`timeValue` 冻结 wire 的 DATETIME 双驱动分支 |
| `inventory/InventorySelectionService.java` | 6 个出口类型化（detail/select/evidenceSubjects/dispositions/decideEvidence/acceptSelected）；`original` 改 `<T> T original(..., Class<T>)`；`finishEvidence` 改 `<T> T finishEvidence(..., T response)`（Transfer `finish(..., T result)` 原型），taskId/itemId/version 由调用点装配，**`acceptSelected` 的 `review(item)` 复读提到调用点以保持旧 finishEvidence 实参求值位置**；删除私有 `wire()`（移入 SelectionViews，逐字保留为测试 oracle）；COUNT 拆包处新增 `Objects.requireNonNull(..., TOTAL_REQUIRED)` 消息常量；detail 的 `taskVersion` 由裸 JDBC 原值改 `number(...)` 取 long（15 §6-4 口径）；SQL/锁/异常零改动 |
| `inventory/InventoryDifferenceService.java` | 4 个出口类型化（list/prepare/decide/repairContext）+ 私有 `decideRepair` 随 decide 同形；`replay` 改 `<T> T replay(..., Class<T>)`；`finish` 改 `<T> T finish(..., T result, boolean changed)` 并抽 `versionAfter(version, changed)` 单源版本算术（UPDATE 与回复同源）；`wire()` **保留**（`reject` 冲突 fields 与 `InventoryRepairPort.Evidence.originalObservedAt` 两个内部 payload 仍消费，逐字未动）；`requiredDimensions(Map,Map)->Set<String>` 签名与实现零改动；repairLinks 查询提为局部变量，位置仍为 `repairContext` 最后一次读 |
| `inventory/InventoryReadinessService.java` | 5 个出口类型化（detail/history/record/reportMissing/waiting）；`original` 泛型化；删除私有 `wire()`；`missingLosses[].report` 与 `current` 分别保持 `Progress` 分量与 JSON 树直通；`MissingLossInput` 请求体 record 与 `conflict/audit/save/hash/number/instant` 内部面零改动 |
| `inventory/InventorySelectionController.java` | 6 个端点返回类型化视图；请求体、校验、`Idempotency-Key`/`If-Match-Version` 头、路径、`@Profile`/`@RequestMapping` 一字未动；随出口归零删除 `java.util.Map` 导入 |
| `inventory/InventoryDifferenceController.java` | 4 个端点类型化，其余同上 |
| `inventory/InventoryReadinessController.java` | 5 个端点类型化，其余同上 |
| `inventory/InventorySealEvidence.java` | **零 diff**（§6-9） |
| `inventory/InventorySelectionWireShapeTest.java`（新增，1504 行） | 19 条 wire 等价用例，见 §3 |

出口 → 类型对照（Selection 6 + Difference 4 + Readiness 5 = 15，全部 HTTP 出口）：

| 旧 Map 出口 | 新 record |
|---|---|
| GET …/items/{i}/selection：`taskId/taskVersion/itemId/recheckGeneration/review/contextDigest/movementEvents/history/page/pageSize/total` | `SelectionDetailView`（review=`ReviewChip`，history=`List<SelectionEventRow>` 17 键） |
| POST …/items/{i}/selection 与 SELECT_EVIDENCE 回执：`taskId/itemId/version/review/contextDigest` | `SelectionReply` |
| GET …/evidence-subjects：`items/requiredDimensions`，items=13 列 + decisions/links/repairLinks | `EvidenceSubjectView`（`EvidenceSubjectRow` 16 键） |
| GET …/evidence-dispositions：`items/total/page/pageSize` | `DispositionPage`（`EvidenceDispositionRow` 15 键） |
| POST …/evidence-decisions 与 DECIDE_EVIDENCE 回执：`observationId/method/closed/taskId/itemId/version` | `EvidenceDecisionReply` |
| POST …/acceptance 与 ACCEPT_SELECTED 回执：`review/taskId/itemId/version` | `EvidenceAcceptanceReply` |
| GET …/differences：`items/page/pageSize/total`，items=13 列 + `client_event_id` + 三段 | `DifferencePage`（`DifferenceSubjectRow` 17 键） |
| POST …/differences/preparation 与 PREPARE_DIFFERENCES 回执：`created/taskId/version` | `DifferencePrepareReply` |
| POST …/differences/decisions 与 DECIDE_DIFFERENCES 回执：`items/taskId/version`，item 5 键或 7 键 | `DifferenceDecisionReply`（`DifferenceDecisionItem`，后两键 NON_NULL） |
| GET …/differences/{o}/repair-context：`taskId/taskVersion/observationId/subjectVersion/subjectStatus/assetId/currentAsset/originalObservedAt/originalFacts/repairLinks` | `DifferenceRepairContext` |
| GET /inventory-readiness/tasks/{t}：`task/items/total/page/pageSize/missingCount/notObservedCount`，items 六键 + item 17 列 | `ReadinessView`（`TaskRow` 21 键、`ReadinessItem`、`ReadinessItemRow`、`MissingLossRow`） |
| GET …/items/{i}/unobserved-history：`items/total/page/pageSize` | `UnobservedHistoryPage`（`UnobservedReasonRow` 11 键） |
| POST …/items/{i}/unobserved 与 UNOBSERVED:* 回执（7 键） | `UnobservedReply` |
| POST …/missing/{r}/loss 与 MISSING_LOSS:* 回执（5 键） | `MissingLossReply` |
| POST …/waiting-review 与 WAITING_REVIEW 回执（4 键） | `WaitingReply` |
| 非出口（未动）：`requiredDimensions`、`reject/conflict/audit/physical/digest/hash/number/instant`、冲突 `fields` LinkedHashMap、`canonical` 指纹树、`review/selectionEvent/observation/task/item` 行 Map | 内部行 Map 与 payload，按红线保留 |

## 3. 判据：wire 等价测试

`InventorySelectionWireShapeTest`（19 用例）把改前三个 `wire()` 与每一处 Map 装配逐字段复制为
legacy oracle（`legacySelectionWire`/`legacyDifferenceWire`/`legacyReadinessWire`、
`legacySelectionDetail`、`legacySelectReply`、`legacyFinishEvidence`、`legacyDifferenceFinish`、
`legacyRepairContext`、`legacyReadinessDetail`、`legacyUnobservedReply`、`legacyMissingLossReply`、
`legacyWaitingReply`、`legacySelectionSubject`），JSON 文本往返树比较（键序无关；键存在性、显式
null、值形状严格）：

1. **selection detail 双态**：有复核行（13 键，含 `observation_id`/`verified_observed_at` 显式
   null）vs 无行（`{"version":0}` 单键，`countKeys==1`）；`history` 行 17 键、id 字符串化、
   `review_version/movement_count` 保持数值、`physical_snapshot_json` 内 `locationId` 仍为字符串；
2. **驱动形状变体**：`verified_observed_at/recorded_at` 的 Timestamp 与 LocalDateTime 双形态同出
   一 ISO 文本；`movement_count/review_version` 的 Integer 宽度变体；`disposition.recorded_at`
   LocalDateTime 变体；
3. **evidence-subjects**：16 键（13+3 段）且断言**无** `client_event_id`；三段行数 14/6/5；
4. **differences 列表**：17 键且含 `client_event_id`（CHAR(36) 断言非数值）；
5. **repair-context**：10 键、`originalObservedAt` ISO、`originalFacts`/`currentAsset` 原样树、
   `repairLinks[].decision_version` 保持数值、`repair_id` 字符串化；
6. **readiness detail 两 item**：`task` 21 键（6 可空列显式 null、`range_json` 内 camel `*Id`
   整数不被字符串化）、`item` 17 键（7 个 LEFT JOIN 列 null 态齐备）、有资产上下文 ⇒ `current`
   树 + `canRecord` 假、无上下文 ⇒ `current` 显式 null、`missingLosses` 五键与 `report` 四键、
   `physicalMissingOpen`/`canSubmit` 真值表两态（Progress=REPORTED ⇒ open 真 / retry 假；
   无 Progress ⇒ `report` 显式 null 键 + canSubmit 真）；
7. **readiness history**：11 键逐列；
8. **三个变更回复 + 两个证据回复 + 两个差异回复** ≡ 旧 `Map.of`/LinkedHashMap 合并（键数 5/6/4/3/
   7/5/4 逐一断言），`DECIDE_DIFFERENCES` 的 5 键与 7 键两分支条件键双向断言（有值态有键、
   缺失态 `assertFalse(has)`，且 `linkedRequestId` 空串键恒在）；
9. **幂等存储形状旧 JSON 回放**（`fams_inventory_review_receipt.response_json` 24h 窗内跨版本必
   回放）：以上 9 种 operation 存储形状各由 legacy oracle 生成文本 → `readValue(该操作 record)` →
   等价再序列化树比较；typed 自往返恒等（含 5 键/7 键混合 items）；
10. **17b 消费面冻结**：反射断言 `InventorySealEvidence.validate(Map,Map,Map,Context)`/
    `subjects(Map,Map)`/构造器参数表与 `InventoryDifferenceService.requiredDimensions(Map,Map)->Set`
    签名一字未变，并断言三控制器**已无 Map 返回类型**；
11. **内部行 Map 不被污染**：`InventorySealEvidence` 与 `requiredDimensions` 共用的
    task/observation/review/item 行 Map 在全部 typed 工厂调用前后树形完全相同（取值逐键一致，
    并逐键读出 `task.id`、`observation.facts_json.actual.condition`、`review.review_status`、
    `item.recheck_generation`）。

**单元 oracle 边界（15/16a 沿用）**：只证明「行→视图→存储/回放」序列化等价。真实 HTTP 运行时链
（`auth/InventorySelectionUpgradeRuntimeCase`、`InventoryDifferenceUpgradeRuntimeCase`、
`InventoryEvidenceUpgradeRuntimeCase`、`InventoryReadinessUpgradeRuntimeCase`、
`InventoryRepairHttpRuntimeCase`、`InventoryObservationHttpRuntimeCase`、
`InventoryLateHttpRuntimeCase` 等）为容器门禁（`@EnabledIfSystemProperty` + Testcontainers
MySQL），本批环境无容器且任务红线禁跑 `scripts/verify-d6-03a.py`，登记为门禁边界，由主代理合并
门禁实跑（§4 的 `-Dtest='Inventory*'` 显示这些用例 147 项 skip、0 失败）。

## 4. 实施代理自测（非产品门禁）

- 定向判据：`JAVA_HOME=graalvm-jdk-21.0.7 ./mvnw -pl backend -Dtest='InventorySelectionWireShapeTest' test`
  → **Tests run: 19, Failures: 0, Errors: 0**。
- 结构回归：`-Dtest='*WireShapeTest,TransferCalendarTest'` → **Tests run: 340, Failures: 0,
  Errors: 0**（33 套件；= 派发基线 260 + 本批 19 + 同树并行的 17b `InventorySealWireShapeTest` 20
  / 17c `InventoryFindingWireShapeTest` 21 / 17d `InventoryMainWireShapeTest` 20，**零退化**）。
- inventory 域全测：`-Dtest='Inventory*'` → **Tests run: 230, Failures: 0, Errors: 0, Skipped: 147**
  （skip 全部为容器门禁的 HTTP/Upgrade 运行时用例）。
- 全仓编译：`./mvnw -pl backend -q test-compile` → **exit=0**（含 repair 16a/b/c 在途与三路并行
  inventory 文件，同树共编译通过）。
- Checkstyle：`./mvnw -pl backend -Pquality checkstyle:check@check-java-conventions` →
  **BUILD SUCCESS**（新文件末尾换行合规；无未用导入）。
- SQL 取证：§1 的 106 条语句拼接文本逐条与 HEAD 相同（脚本比对，非人工目测）。
- 并发干扰：两轮 mvn 因并行代理重建 `target/classes` 出现撕裂读取（首轮
  `SelectionViews$*.class NoSuchFileException`，次轮 `LossViews/MasterdataViews
  NoClassDefFoundError`，均指向非本批根因）；按任务条款等待空窗后重跑，第三轮一次通过 340/340。
- 未跑：SpotBugs/JaCoCo/真实 HTTP 运行时用例与 `scripts/verify-d6-03a.py` 全量门禁——按任务红线
  留给主代理合并门禁。EI_EXPOSE_REP 风险已在构造侧消解：30 个 record 的全部 List 分量
  `List.copyOf`；无新增 Map 分量；无 Object 分量（改标量/`JsonNode`/不可变 port record）；未内嵌
  任何 OpenAPI 生成可变类（`MasterdataViews.ActiveReference` 先例无需启用）。

## 5. 影响分析（GitNexus）

编辑前逐个既有符号执行 `impact -d upstream --repo fams-manage`（Node 24 前缀）：

- 类级：`InventorySelectionService` **LOW 1**（控制器构造点）、`InventoryReadinessService`
  **LOW 1**、`InventoryDifferenceService` **LOW 3**（两本批控制器 + `InventorySealEvidence`）、
  三控制器 **LOW 0**、`InventorySealEvidence` **LOW 1**（`InventorySealService`，17b）。
- 方法级（一律 `-f <file>` 消歧，消除跨域同名伪边后）：15 个出口方法全 **LOW 1**，唯一上游即本批
  对应控制器；`original/replay/finish/finishEvidence` **LOW 4～6**，depth-1 明细全部落在同文件内
  （私有方法，编译器封闭），depth-2 为本批控制器。
- **MEDIUM 两处，均非阻断**：
  1. `InventoryDifferenceService.requiredDimensions` **MEDIUM 13**（difference.prepare、
     SealEvidence.subjects/validate、selection.evidenceSubjects/decideEvidence/acceptSelected，
     传递到 SealService.evaluate/preflight/seal）——本批**签名与实现零改动**，风险面未被触碰；
  2. `InventorySelectionService.wire` **MEDIUM 10**——索引为改前快照，10 条上游全在本文件与其
     控制器（私有件），删除后由 `SelectionViews` 工厂同名替代，无跨域真实边。
- 同名伪边登记：`detail/list/decide/record/waiting/history/wire/replay/finish/original/select`
  在 loss/transfer/inbound/requisition/repair/observation 各域均有同名符号；未加 `-f` 的裸查询会
  聚合这些同名候选，本批全部结论基于 `-f` 文件消歧 + grep 消费面复核
  （`grep -rn "differences\.|selections\.|readiness\."` 命中仅 15 控制器调用点 + SealEvidence 一处）。
  真实 HIGH/CRITICAL：**无**。
- `detect-changes`（工作树 vs HEAD）：26 文件 173 符号、**risk low**、受影响流程 0——**整树口径**，
  含 16a/b/c repair 在途、AGENTS.md 与三路并行 inventory 文件；本批 diff 为 6 改 + 2 增。
- 索引不含本批新符号（`SelectionViews`）属正常。

## 6. 偏差与裁决点

1. **`ReviewChip` 多态承载两态键集**（本批唯一新增结构模式）：`detail.review` 声明为
   `sealed interface ReviewChip`，序列化按运行时类型且无判别键；`NoReview(long version)` 复刻
   `Map.of("version", 0)`。判据 §3-1/3-2 冻结两态。幂等存储面不用接口，避免跨版本反序列化需要
   类型信息。
2. **裁决点：不可达路径的键集差异（登记不修正）**。旧码在 `select`/`acceptSelected` 回复里对
   `wire(review(item))` 求值，若复核行缺失（同事务刚 INSERT/UPDATE，DB 状态机不可达）旧输出为
   `{}` 零键，typed 输出 13 键；`DifferenceService.decide` 的 `Map.of("dimension", …)` 与 repair
   分支 `linkedRepairId/repairStatus` 在 null 时旧码抛 NPE、typed 分别输出显式 null / 省略键
   （`dimension` 列 NOT NULL、后两者来自 `InventoryRepairPort.Link` 非空文本）。三处均落在可达
   状态集之外（15 §6-3 同型）。
3. **COUNT 拆包新增 requireNonNull**：`SelectionService.detail` 旧码把 `queryForObject(Long.class)`
   原值放入 Map（不拆包、不可能 NPE）；typed 出口取 `long` 需拆包，按红线补
   `Objects.requireNonNull(..., "INVENTORY_SELECTION_TOTAL_REQUIRED")`，
   `dispositions` 与 `DifferenceService.list` 的既有 `requireNonNull` 同处补消息常量
   `INVENTORY_DIFFERENCE_TOTAL_REQUIRED`。`COUNT(*)` 恒非 null，语义不变。
4. **JSON 载荷承载方式**：`readValue(text, Object.class)` 的 Map/List 中间态改 `readTree(text)` 的
   `JsonNode`（`SelectionViews.node` 与 readiness `current`、repair-context 两键同口径）——文本树
   等价，且旧递归在本批列集上恒等（§1-1 论证）。
5. **数值宽度归一**：`taskVersion/recheckGeneration/valid_raw_count/generation/review_version/
   closed/created/canSubmit` 等旧码直通裸 JDBC 值（Long/Integer），typed 取 `long`/`int`
   （15 §6-4、16a §6-5 口径），Integer/Long 宽度变体由判据 §3-2 冻结同值；`review_version` 与
   `readiness` 可空数值取 `Long` 保 null。
6. **finish/finishEvidence 由「内部合并」改「调用点装配 + 只落库」**（Transfer `<T> T finish(...,
   T result)` 原型）。为保证 DB 求值顺序不变：`acceptSelected` 的 `review(item)` FOR UPDATE 复读
   提到调用点（旧为 `finishEvidence` 实参，位于 INSERT selection_event 之后、UPDATE task 之前，
   现位置不变，已加注释锁定）；`decide/prepare` 的 `versionAfter(version, changed)` 抽为单源助手，
   UPDATE 与回复取同一表达式。`finish` 内 UPDATE→audit→receipt 顺序、`finishEvidence` 内
   UPDATE→audit→receipt 顺序、`detail` 的 review→COUNT 顺序、evidence-subjects/differences 的
   逐 subject「decision→transfer→repair」三段读序、`repairContext` 的 repairLinks 末位读，全部
   逐点保持。盘点范围锁/密封锁交叉序列在本批文件内不出现（属 Seal/Synchronization 侧，17b 面）。
7. **键序归一为 record 声明序**：旧 `Map.of`/`LinkedHashMap` 合并的 response_json 与 audit
   detail_json 文本键序本就跨 JVM 不稳定、无契约；新写文本按声明序稳定化，跨版本回放由判据 §3-9
   覆盖。`physical_snapshot_json`/`range_json` 等**存储 payload 文本本身未参与改写**（仍由
   `physical()`/`rangeJson()` 生成后原样入库）。
8. **审计 payload 归属**：`reject/conflict` 的 `Map.of("code",…,"fields",…)`、`waiting` 的
   `Map.of("reason",…,"result",…)`、`select` 的 `physical(context)` 快照与 `digest` 输入、
   `canonical` 指纹树、`DifferenceService.reject` 的 `wire(...)` 后处理一律保留 Map（红线）；只有
   「回复体同时作为 audit detail」的 `json.writeValueAsString(response)` 由 Map 换成同键值 record，
   树形不变。
9. **SealEvidence 适配面 = 0 改动**。其唯一消费点是 `differences.requiredDimensions(task,
   observation)`：入参为内部行 Map（红线保留），返回 `Set<String>` 非 HTTP 出口 ⇒ 无需适配。
   签名不变证明：`git diff` 该文件为空（§2 表）+ 判据 §3-10 反射冻结 + §3-11 行 Map 未被污染；
   其 `seal` 侧逻辑（validate/normal/physical/digest/instant/fail）逐字未动。
10. **Selection→Difference 无装配双轨**。任务预设的「两侧都在你手里、直连 typed」落空：全仓 grep
    证明两服务间只有 `requiredDimensions`（§6-9），不存在 Map 出口交叉调用，故既不引入 typed 化
    （会破坏红线「内部行 Map 保留」），也不留双 Map。
11. 契约/OpenAPI/前端/小程序：零改动（无 API 形状变化 ⇒ 无接口文档改动）。
12. AOCI 认知维护：本会话工具面未暴露 aoci_* MCP 服务，无法调用 `aoci_maintain`；受管理对象已达
    本任务最终稳定态，登记该收尾步骤未执行，交由持有 AOCI 工具的会话对齐（16a §6-10 同）。

## 7. 下一步

- 主代理合并门禁：全量 Java 质量序列（含 SpotBugs/JaCoCo）+ 真实 HTTP 运行时用例
  （InventorySelection/InventoryDifference/InventoryEvidence/InventoryReadiness/InventoryRepair/
  InventoryObservation 链与 Upgrade 链）实跑；重点复核 §6-2 三处不可达路径与 §6-3 拆包点在真实
  驱动下的形状。
- 与 17b/17c/17d 合并：本批未触碰 `InventorySealEvidence`、`FindingViews`/`SealViews`/
  `InventoryViews` 等兄弟批文件；`SelectionViews` 的 `DecisionRow/TransferLinkRow/RepairLinkRow`
  与兄弟批的同形投影存在有意重复（各批自持出口），若后续要收敛为共享面，须先裁决归属再迁移。
- 后续波次：inventory 域余下出口由 17b/17c/17d 收口；若未来对 `requiredDimensions` 的行 Map 入参
  重新裁决，可回收本批 §6-10 记录的装配双轨缺位。

## 主代理产品门禁（波次六合并实跑）

主代理在波次六合并树固定快照副本（rsync 排除并行 WIP；快照前漂移校验证明非 inventory 已提交面
零改动；四路 views 文件内容核实入照）实跑完整 Java 序列（nohup 脱离态）：首轮即 **JAVA01_VERIFY_PASS**，
83 步 exit=0，Checkstyle/SpotBugs 0，覆盖率 LINE 93.45%、BRANCH 75.29%；日志内 74 个 surefire
阶段汇总 401 项 0 失败 0 错误。四个新套件另在门禁副本内直接复跑 **80/80 绿**（Selection 19 +
Seal 20 + Finding 21 + Main 20）。主树合并判据：33 个 WireShape 类 340/340。
详见[波次证据](evidence/JAVA-17-java-gate.txt)。
