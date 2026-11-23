# JAVA-17b · inventory 域「封账/同步/观测」HTTP 出口类型化

> 用户指定的后端结构批（HTTP 出口波次 · inventory 17b 子批），不进入 D6-01～D6-27 台账、不占用其状态。
> 日期：2026-11-28 · 分支：main · 基线：主树当前状态（JAVA-15 inbound 与 16a/16b/16c repair 已交付未提交，
> 状态：主代理审计通过，波次六合并门禁通过（见文末证据节），已收口提交。
> 状态：实施完成（含续作核账：main/测试已全量复核，§4 maven 正式口径全绿，唯一缺口 §6-4 已补完），
> 等待主代理合并门禁。

## 1. 背景与红线

完全复刻 JAVA-01/11a/14b1/15/16a 标准：HTTP 响应类型化；内部行 Map（queryForList 行、封账快照
packet/header/evidencePacket、审计 `detail_json`、观测 `facts_json` payload、幂等 `payload_sha256`
指纹输入）保留 Map；契约 `contracts/fams-core-v1.yaml`、OpenAPI 生成类、`packages/api-types`、
前端零改动；SQL 语句、参数顺序、`FOR UPDATE`/`FOR SHARE` 锁顺序（含观测写入与封账范围锁的交叉
读序逐点保持）、异常码、HTTP 状态码、端点路径、请求体 record 一字未动。

本批只触碰 6 个既有文件（`InventorySealController`/`InventorySealService`/
`InventorySynchronizationController`/`InventorySynchronizationService`/
`InventoryObservationController`/`InventoryObservationService`）+ 新增 `SealViews`、
`InventorySealWireShapeTest` 与本记录。`InventoryObservationTime.java` **零 diff**：其 `Result`
只承载时间判定内部态（decision/observedAt/persistedAt），从未成为 HTTP 出口，出口侧的时间串
（`receivedAt`/`declaredAt`/`serverTime`）在旧代码里就是 Java 侧 `Instant.toString()`，无显式驱动
转换需要照抄。`InventorySealEvidence.java` **零 diff**（17a 并行适配中）：本批只消费其不变的
public 签名 `validate(Map<String,Object> task, Map<String,Object> item, Map<String,Object> review,
AssetInventoryReviewPort.Context)` 与 `subjects(Map, Map)`，入参继续传内部行 Map。
禁触面 `Selection/Difference/Readiness`（17a）、`Finding/Review/Photo/RepairPhoto`（17c）、
`Inventory 主/Late/LateDamage`（17d）、全部 Port（含 `InventoryLateEvidencePort`）、
`Inventory*ConflictHandler`、`InventoryReviewLinks`、`repair/`、`inbound/`、`contracts/`、
`apps/`、`DESIGN-HANDOFF.md`、`AGENTS.md` 本批零 diff（`git diff` 路径集仅上述 6+2+1）。
同树并行的 17a/17c/17d 改动不属本批 diff。

红线机械核证：对 6 个改过文件做「HEAD 字符串字面量多重集 vs 工作区」比对（脚本按行取
`"…"` 字面量、剔除注释行、多重集相减），结果 **零新增字面量**；删除侧共 101 处出现 / 59 个唯一值，
其中 57 个唯一值是移入 `SealViews` 的 Map 键名与列名字面量（逐一在 `SealViews.java` 内复现核对），
2 个是改后不可达的守卫消息（§6-3）。所有 SQL 文本、异常码、审计动作码字面量逐字保持；
`git diff -U0` 中命中 SQL 关键字的行只有三处 `replay` 查询随块搬移产生的缩进差，
观测/同步服务的 `"""` 文本块 SQL 全部落在上下文行未动。

本批与先例的关键差异（改前代码逐字冻结为 oracle 核对）：

1. **投影口径 = 冻结 `InventorySealService.wire()`**：`id`/`*_id` 列逐值 `.toString()`
   （`timezone_id` 是 VARCHAR 但列名命中 `*_id` 规则，旧码同样走 toString，typed 侧保持 String）；
   `*_json` 文本列经 `readValue(text, Object.class)` 解析为树（typed 侧沿 14b1 口径
   `readTree(raw.toString())`，序列化同树）；`Timestamp`/`LocalDateTime` → ISO instant 串；
   其余 JDBC 值原样透传。`wire()` 本体保留在服务内——封账存储面（`header`、`frozenItems` packet、
   `evidencePacket`、`dateChange`、`unobservedHistory`、`missingLosses`）继续消费它，
   本批不扩大类型化范围到不可变报告的字节快照。
2. **行投影 record 保持 SQL snake_case 键**（`@JsonProperty`，JAVA-11a/16a 先例）并按
   `SELECT *` 列序声明分量：`TaskRow` 21 列（fams_inventory_task）、`SealedReportRow` 15 列
   （fams_inventory_sealed_report，无 id 列、主键 task_id）、`SnapshotRow` 2 列。
3. **指纹输入字节等价（本批特有判据）**：`sync_digest = SHA-256(writeValueAsString(roster))`，
   而 roster 元素旧码是 LinkedHashMap（participant 序 `accountId,name,current,reason,devices`；
   device 序 `deviceId,bindingId,current,reason[,declarationId,declaredAt,unresolvedCount]`）。
   `SyncParticipant`/`DeviceRow` 分量声明序逐一等于该 put 序，判据用**逐字节文本相等**（不是树
   相等）冻结，因为客户端把 preflight 拿到的 `synchronizationDigest` 原样回填到后续 seal 请求，
   跨请求边界比较的是文本摘要；同一文本还逐行写入 `fams_inventory_sealed_sync.snapshot_json`
   并参与 `report_sha256`。观测出口同理：`ObservationReceipt` 六键声明序 ≡ 旧 put 序，其文本
   同时是 `fams_inventory_observation.receipt_json`（回放源）与两处审计 `detail_json`。
4. **条件键甄别**（`@JsonInclude(NON_NULL)` 复刻 put 守卫）：preflight 双分支互斥键
   （sealed 分支只 put `task/sealed/report`，open 分支只 put 其余 7 键）；`Blocker.itemId`/
   `observationId`（旧 `Map.of` 三形态）；`DeviceRow` 三个申报键（仅当该设备有 declaration 行）；
   `Identification` 的 8 个非 kind 键（五个分支各 put 自己那组）。显式 null 面（全局
   JsonConfiguration inclusion=ALWAYS 照写）：`DeviceRow.bindingId`（旧码恒 put，未绑定设备写
   null）、`SealedReportRow.missing_sync_reason`、`TaskRow` 的
   `parameter_version_id/publisher_account_id/copied_from_task_id/published_at/started_at/ended_at/
   end_reason`。
5. **幂等回放按泛型 `Class<T>` 蓝本**，三处旧存储 JSON 形状全部有回放判据：
   `fams_inventory_review_receipt`（operation='SEAL'，`SealReceipt`）、
   `fams_inventory_sync_receipt`（`SyncDeclarationReceipt`）、
   `fams_inventory_observation.receipt_json`（观测无独立回执表，`ObservationReceipt`）。
   三处原内联「查旧回执 → 比 payload_sha256 → 命中即返回」块改为各服务私有
   `<T> T replay(..., Class<T> type)`，查询文本、身份/负载三重校验次序与冲突副作用逐行保持
   （seal 的 `conflict(...)` 审计写序、declare/submit 的 409 码不变）。
6. **无 Map 兼容包装**：与 14b1/15/16a 的永久 convertValue 包装不同，本批 11 个出口在禁改面上
   **没有** Map 消费者——grep 核实三个服务的上游只有各自控制器与 `SealService.evaluate →
   SynchronizationService.status` 同组边（后者随本批一并 typed），port 面（`AssetInventorySealPort`、
   `AssetInventoryReviewPort`、`InventoryLossPort`、`InventorySynchronizationIdentityPort`、
   `InventoryIdentityPort`、`InventoryLateEvidencePort`、`AttachmentEvidencePort`）签名与返回
   零改动且不被这些出口反喂 Map。故不留任何 `convertValue` 镜像包装。

## 2. 改动清单与出口映射

主代码 6 改 1 增，测试 1 增，本记录 1 增：

| 文件 | 变化 |
|---|---|
| `inventory/SealViews.java`（新增） | 15 个响应 record（Page/TaskRow/Blocker/Preflight/SealReceipt/SealedReportRow/SnapshotRow/DeviceRow/SyncParticipant/SynchronizationStatus/OwnDeviceState/SyncDeclarationReceipt/CalibrationResult/Identification/ObservationReceipt）+ 行映射工厂（taskRow/sealedReportRow/snapshotRow/device）；`timeValue` 冻结 wire() DATETIME 分支；含 List 分量紧凑构造器 `List.copyOf` 防御拷贝；分量序即指纹文本序（类 javadoc 登记） |
| `inventory/InventorySealService.java` | 4 个出口类型化；新增私有泛型 `replay(..., Class<T>)`（旧内联回执块搬移，SQL 文本与判定序不变）；`Evaluation` 的 synchronization/blockers 分量改 typed；`blockerCount`/`missing`/`accountId` 取值点随投影改写；删除只服务出口的 `cast()`；**`wire()`/`evidencePacket()`/`dateChange()`/`conflict()`/`audit()`/`task()`/`number()`/`instant()`/`hash()` 全部保留**（存储与冲突面）；SQL、`FOR UPDATE`（task）与 `FOR SHARE`（item/review/observation/sealed_report/范围锁 `dates.lock`）次序零改动 |
| `inventory/InventorySynchronizationService.java` | 3 个出口类型化；`device()` 返回 `DeviceRow`（reason 判定链与其内部 4 次读序不变，申报三键守卫移入 `SealViews.device`）；新增私有泛型 `replay(..., Class<T>)`；`declare` 的 `response` 由 LinkedHashMap 改 record，三处 `writeValueAsString(response)` 落库文本字节不变 |
| `inventory/InventoryObservationService.java` | 4 个出口类型化（calibrate/identify/identifyBody/submit）；`identify` 五分支 `Map.of` 改 9 分量 record 构造；新增私有泛型 `replay(..., Class<T>)`（回执源为 `fams_inventory_observation.receipt_json`）；`receipt.counted()` 取代 `Boolean.TRUE.equals(receipt.get("counted"))`；`identify` 数值分量复用文件内既有 `nullable()` 归一 Long（§6-4）；观测写入链的 `FOR UPDATE`（task/item）与 late 证据 `late.append(Task.fromRow(task), observation)` 调用序零改动 |
| `inventory/InventorySealController.java` | 4 个端点返回类型化视图；`seal` 的 CannotAcquireLockException 一次重试内联循环未动（`return` 仍在 `for (;;) { try { ... } }` 内）；请求体 `InventorySealInput`、`Idempotency-Key`/`If-Match-Version` 头、`@Validated` 校验、路径零改动 |
| `inventory/InventorySynchronizationController.java` | 3 个查询/申报端点类型化；PC 端 `denyPcDeclaration` 的 403 空响应出口未动（无返回体） |
| `inventory/InventoryObservationController.java` | 4 个端点类型化（identify 与 identifyBody 共用 `Identification`）；内嵌请求体 `Lookup` record、头校验、路径未动 |
| `inventory/InventorySealWireShapeTest.java`（新增） | 20 条 wire 等价用例，见 §3 |

出口 → 类型对照（11 个出口）：

| 旧 Map 出口 | 新 record |
|---|---|
| GET /inventory-sealing/tasks/{task}（与 /mini 双路径）：sealed 三键 或 open 九键 | `Preflight`（内嵌 `TaskRow` 21 列 snake 投影 + `SealedReportRow` + `Blocker` + `SyncParticipant` 名单；7 个分支互斥条件键） |
| POST …/seal 与 SEAL 幂等回执 body：`taskId/version/status/reportSha256/acceptedCount/notObservedCount/missingSynchronizationCount`（Map.of 七键） | `SealReceipt` |
| GET …/report：`cast(wire(fams_inventory_sealed_report 行))` | `SealedReportRow`（15 列 snake，`snapshot_json` 解析树） |
| GET …/report/{section}：`items/total/page/pageSize`，items=`wire(snapshot 行)` | `Page<SnapshotRow>` |
| GET /inventory-synchronization/tasks/{task}：`items/total/page/pageSize/taskVersion/declarationOnly`（Map.of） | `SynchronizationStatus`（items=`SyncParticipant`，同时是 SealService 指纹输入） |
| GET /mini/…/tasks/{task}/devices/{device}：LinkedHashMap 八键 | `OwnDeviceState`（内嵌 `DeviceRow`） |
| POST /mini/…/declarations 与回执 body：LinkedHashMap 六键 | `SyncDeclarationReceipt` |
| POST /inventory-observations/calibrations：`Map.of` 三键 | `CalibrationResult` |
| GET + POST /inventory-observations/tasks/{id}/identification：五分支 `Map.of` | `Identification`（kind + 8 条件键，`snapshot` 为 JsonNode） |
| POST /inventory-observations/tasks/{id}/events 与 `receipt_json` 回放 body：LinkedHashMap 六键 | `ObservationReceipt` |
| 非出口（保留 Map/内部态）：`task()`/items/reviews/observations/unobservedHistory/selectionHistory 行 Map、`wire()`/`evidencePacket()`/`dateChange()` 封账快照装配、`header`/`frozenTask`、`conflict()` fields 与 `InventoryReviewConflict`、`audit()` detail 入参、观测 `facts_json`（`writeValueAsString(input)`）、`payload`/`report_sha256`/`sync_digest` 指纹、`InventoryObservationTime.Result`、`InventorySealEvidence.validate/subjects` 行 Map 入参 | 不动（17a 拥有的 `InventorySealEvidence` 签名冻结） |

## 3. 判据：wire 等价测试

`InventorySealWireShapeTest` 把改前 `InventorySealService.wire()/cast()`、preflight 双分支、
seal 回执、report/reportRows 装配、blocker 三形态，`InventorySynchronizationService.device()/
own()/status()/declare()` 装配与 `InventoryObservationService.calibrate()/identify()` 五分支、
`submit()` 回执装配逐字段复制为 legacy oracle（构造 JDBC 类型行：Long/Integer/String/Timestamp/
LocalDateTime/JSON 串/CAST CHAR 串/可空列 null），JSON 文本往返树比较：

1. `TaskRow` ≡ legacyWire：Timestamp 主形态 + LocalDateTime 变体 + INT 宽度变体 + lean 全 null
   态；显式 null 键断言（publisher_account_id/ended_at/end_reason）、`timezone_id` 字符串化断言、
   `range_json` 两键树断言；
2. `Preflight` open 态 ≡ 旧 Map.of 九键（含 blockerCount/missingSynchronizationCount 零值仍在）
   与 sealed 态 ≡ 旧三键，7 个分支互斥条件键缺失逐一 assertFalse；
3. `Blocker` 三形态（仅 code / +itemId / +observationId）键数与键存在性双断言；
4. `SealReceipt` ≡ 旧 Map.of 七键（含零计数态）；
5. `SealedReportRow` ≡ `legacyCast(legacyWire(row))`：task_id/actor_account_id 字符串化、
   task_version 数值、`snapshot_json` 为对象、`missing_sync_reason` 显式 null、15 键总数；
6. `Page<SnapshotRow>` ≡ 旧 Map.of（items 解析树 + `snapshot_sha256` 串）；
7. `DeviceRow` 两态：申报态七键齐备 vs 无申报态四键 + `bindingId` 显式 null + 三申报键缺失；
8. `SynchronizationStatus` ≡ 旧 Map.of（两 participant，其一 devices 空数组）；
9. `OwnDeviceState` ≡ 旧 LinkedHashMap 八键 + `observationWatermark` 仍为字符串断言；
10. `SyncDeclarationReceipt` 既树等价又**逐字节等价**（旧 LinkedHashMap 序）；
11. roster 指纹：整表与逐行 `writeValueAsString` **字节等价**（sync_digest /
    `fams_inventory_sealed_sync.snapshot_json` 输入面）；
12. `CalibrationResult` ≡ 旧 Map.of 三键；
13. `Identification` 五分支全-tree ≡ 旧 Map.of，并断言各分支键数（2/2/3/2/6）与异分支键缺失；
14. `ObservationReceipt` DIFFERENCE_REVIEW 与 EXPIRED 两态 **字节等价**；
15. 幂等旧存储回放（跨版本必测）：SEAL 七键旧文本（Map.of 随机序）→ `readValue(SealReceipt)` →
    等树再序列化；sync_receipt 六键旧文本 → 回放后**字节**不变；observation receipt_json
    ACCEPTED/CONFLICT_REVIEW 两态 → 字节不变 + `counted()/differences()` 取值断言；
    roster 旧文本 → `List<SyncParticipant>` 回放 → 字节不变（digest 输入可复现）；
16. typed 自身 序列化→反序列化→再序列化 恒等（TaskRow/SealedReportRow/SnapshotRow/DeviceRow 两态/
    Preflight/SyncParticipant/ObservationReceipt）。

**单元 oracle 边界（JAVA-01/16a 教训沿用）**：只证明「行→视图→存储/回放文本」等价，不证明真实
HTTP 运行时链。`auth/InventorySealHttpRuntimeCase`、`InventorySynchronizationHttpRuntimeCase`、
`InventoryObservationHttpRuntimeCase`、`InventoryLateHttpRuntimeCase`、
`InventoryReviewHttpRuntimeCase`、`InventoryReadinessHttpRuntimeCase` 等是
`@EnabledIfSystemProperty` 容器 MySQL 门禁用例，本批环境无容器且任务红线禁跑
`scripts/verify-d6-03a.py`，登记为主代理合并门禁实跑，不算本批失败。

## 4. 实施代理自测（非产品门禁）

口径统一为 `rtk env JAVA_HOME=<graalvm-jdk-21.0.7> ./mvnw -pl backend -o … test`（离线，仓库根执行）。

续作核账会话（2026-11-28；下列数字为最终态，定向判据共复跑三轮：去重前 / `boxed()` 去重后 /
全部文档定稿后收尾，三轮结果一致）：

- 定向判据：`-Dtest='InventorySealWireShapeTest' -DfailIfNoTests=false test` →
  **Tests run: 20, Failures: 0, Errors: 0, Skipped: 0**（BUILD SUCCESS，0.273s）。
- 回归口径：`-Dtest='*WireShapeTest,TransferCalendarTest'` →
  **Tests run: 340, Failures: 0, Errors: 0, Skipped: 0**（BUILD SUCCESS）。覆盖 33 个测试类
  （32 个 `*WireShapeTest` + `TransferCalendarTest`），
  含同树三路并行批的在途判据 `InventorySelectionWireShapeTest` 19、`InventoryFindingWireShapeTest` 21、
  `InventoryMainWireShapeTest` 20 与 repair 16a/16b/16c、inbound 15、`TransferCalendarTest` 1；
  本轮**无并行中间态阻塞**，无需旁路。
- 全仓编译：`./mvnw -pl backend -q test-compile` → **EXIT=0，零错误输出**（前后两轮，
  他批在途文件亦处于可编译状态）。
- Checkstyle：`./mvnw -pl backend -Pquality checkstyle:check@check-java-conventions` →
  **You have 0 Checkstyle violations.**（BUILD SUCCESS）；三个新文件末尾换行实测为 `\n`。
- SpotBugs 实跑（续作会话补，超出前序登记口径）：
  `-Pquality spotbugs:check@check-bytecode-defects`（effort=Max、threshold=Medium、
  `config/spotbugs/exclude.xml`）→ **BugInstance size is 0，BUILD SUCCESS**，整模块（含本批
  `SealViews` 与三路并行批在途 main 类）零缺陷，本批无遗留红。下面三查留作人工核证记录：
  1. **拆包**——`SealViews.num/id/time` 只用于已核 NOT NULL 列或先判 null 的路径；
     `Preflight.blockerCount/missingSynchronizationCount`、`Identification` 三个版本分量、
     `DeviceRow.unresolvedCount` 之外的 Object 分量全部装箱承载，无新增隐式拆包点；
     `blockers().getFirst().code()`、`result.items()/total()` 无拆包。
  2. **COUNT**——`reportRows` 的 `SELECT COUNT(*) … FOR SHARE` 与 `status()` 的
     `SELECT COUNT(*)` 两处 `queryForObject(Long.class)` 保持 HEAD 原样的
     `Objects.requireNonNull(…)` 包裹（HEAD 即无 message，本批不额外加参以免偏离逐字冻结口径）；
     `watermark()` 的 `COALESCE(MAX(id),0)` 与 `declare()` 的 `LAST_INSERT_ID()` 同步保持。
  3. **可变内嵌**——5 个含 List 分量的 record（`Page.items`、`Preflight.blockers`/
     `synchronization`、`SyncParticipant.devices`、`SynchronizationStatus.items`、
     `ObservationReceipt.differences`）全部紧凑构造器 `List.copyOf`；无 Map/array 分量；
     Object 类分量只用 `JsonNode`/`String`/`Long`/`Integer`/`Boolean`；`SealViews` 静态工厂
     只借用注入的 `ObjectMapper` 参数不存字段，故无新增 EI_EXPOSE_REP/REP2 面。
- 前序会话遗留登记（保留）：首轮实施期间三轮全树 `test-compile` 曾被 17d `InventoryLateService`
  （`InventoryViews` 适配中）、17d `InventoryMainWireShapeTest`、17a `InventorySelectionWireShapeTest`
  的在途编译错误阻塞，当时按任务书以 `javac` 定向编译 + 反射 runner 旁路先跑通本批判据（20/20）；
  续作会话上述 maven 正式口径已复跑转绿，旁路数字被正式数字取代。本批文件在三次阻塞中均非报错方。
- 未跑：JaCoCo 覆盖率门禁、真实 HTTP 运行时用例（`@EnabledIfSystemProperty` 容器 MySQL 门禁，
  `*HttpRuntimeCase`/`*Process` 命名亦不被 surefire 默认包含）、`scripts/verify-d6-03a.py`
  ——按任务红线留给主代理合并门禁。

## 5. 影响分析（GitNexus）

编辑前执行 `impact -d upstream --repo fams-manage`（Node 24 前缀 `~/.nvm/versions/node/v24.18.0/bin`）：

- 类级：`InventorySealController` LOW（direct 0）、`InventorySealService` LOW（direct 1）、
  `InventorySynchronizationController` LOW（0）、`InventorySynchronizationService` LOW（2）、
  `InventoryObservationController` LOW（0）、`InventoryObservationService` LOW（1）——
  全部 epistemic=exact，无 MEDIUM/HIGH/CRITICAL，无需预警。
- 方法级（`-f` 文件消歧）：`preflight` 1、`seal` 1、`report` 3、`reportRows` 1、`evaluate` 2、
  `own` 1、`declare` 1、`status` 2、`device` 2、`calibrate` 1、`identifyAndRecord` 1、`submit` 1
  全 **LOW**；depth-1 明细逐条核为「本批控制器 + 同文件私有件 + SealService.evaluate →
  SynchronizationService.status」，全部在册（`report` 的三个上游是
  `InventorySealController.report`、`InventorySealService.preflight`、
  `InventorySealService.reportRows`）。
- `identify` 报 ambiguous（同文件 `identify#3` 公有 3 参与 `identify#4` 私有 4 参两个候选，
  max 4 impacted、maxRisk LOW）——非跨域伪边，逐一按 LOW 处理。
- 跨包消费者：grep 核实 `InventorySealService|InventorySynchronizationService|
  InventoryObservationService` 在 `inventory` 包外**零引用**（含测试目录），故类型化返回不破任何
  编译面；`InventorySealEvidence` 的两个 public 方法签名未被触碰，17a 的并行适配与本批无交集。
- 索引不含本批新符号（`SealViews`）属正常。`detect_changes` 留主代理合并门禁（同树四路并行，
  工作树含他批 diff，整树口径不可归因）。
- 续作核账会话未获得 GitNexus MCP 工具面（本会话 MCP 目录无 gitnexus server），故上述 impact 结论
  **沿用前序代理实测**并原样登记，不重复声称已跑。本会话唯一 main 改动落在
  `InventoryObservationService.identify` 的私有投影取数（`boxed()` → 既有 `nullable()`），
  属 §5 方法级 `identify`（ambiguous，max 4 impacted / LOW）在册范围内，无新符号、无跨文件签名变化。

## 6. 偏差与遗留

1. **裁决点：不留 convertValue 包装**。16a/15 因禁改 port 以 Map 消费 `get/submit` 而保留永久
   Map 镜像；本批 11 个出口无任何禁改 Map 消费者（§1-6），若后续批（17a/17c/17d 或 D6 QA）出现
   需要，应在消费侧切 typed，不回滚本批出口。
2. **归属裁决：`InventorySynchronizationService` 与 `InventoryObservationService` 归 17b**。
   任务书把 17b 定义为「封账/同步/观测」三 controller + 三 service；`SealService.evaluate` 直连
   `SynchronizationService.status` 组内边随批 typed。`InventoryObservationTime`（判定 helper）与
   `InventoryObservationInput`（请求体）不属出口装配，零 diff。
3. **两条不可达运行时守卫随投影删除**：`evaluate` 内 `rows instanceof List<?>` 失败即
   `IllegalStateException("Inventory synchronization result invalid")`——typed `items()` 之后该
   分支不可表达；`cast()` 的 `IllegalStateException("Inventory seal map expected")` 同理（`cast`
   本身失去唯一调用者后删除）。`"Inventory synchronization page incomplete"` 守卫（total 与已收
   条数不一致的真实状态）**保留原样、原位置**。异常码/状态码面无任何变化。
4. **数值分量取 long/Long 归一（14b1/15 先例）**：`TaskRow.expected_count` 等旧码原样透传 JDBC
   `Long`，typed 取 `long`；`Identification.assignmentVersion/recheckGeneration/assetVersion` 取
   `Long`（旧码 `Map.of` 直传 BIGINT），实现上**复用文件内既有 `nullable(Map,String)` helper**——
   续作核账发现首版另加了与其方法体逐字相同的 `boxed()`，已删除该重复实现并在调用点留一行注释，
   行为零变化（判据 §3-13 复跑全绿）；`SyncDeclarationReceipt.taskVersion`、
   `SealReceipt.acceptedCount/notObservedCount` 同理。JSON 文本往返归一为同一数字节点，INT 宽度
   变体由判据 §3-1 冻结。`Identification.itemId` 保持字符串（旧 SQL 已 `CAST(id AS CHAR)`）。
5. **null 语义微差（登记不修正，均为 NOT NULL 列不可达）**：`SealViews.device` 在
   `declared_at` 为 null 时输出缺失键，旧码此处必 NPE——列 `declared_at DATETIME(3) NOT NULL`；
   `Identification` 三个版本分量、`TaskRow.taskStatus` 与 `Identification.assetCode`/`name` 在
   null 时分别输出显式 null 或缺失键，旧码 `Map.of` 必 NPE——相关列均
   `BIGINT NOT NULL`/`VARCHAR NOT NULL`（`fams_inventory_task`、`fams_inventory_sync_declaration`
   建表逐列核对）。可达成状态集上无差异。
6. **`*_json` 列解析口径**：typed 侧沿 14b1 用 `readTree(raw.toString())`，旧 `wire()` 用
   `field.endsWith("_json") && item instanceof String` 守卫后 `readValue(text, Object.class)`。
   驱动返回 String 时两者同树（判据 §3-1/§3-5 树等价）；若某驱动形态返回非 String 的 JSON 包装，
   旧 wire() 会把裸对象塞进快照而 typed 侧仍解析其 `toString()`——本域 `TaskRow` 的
   `range_json`、`SealedReportRow` 的 `snapshot_json`（LONGTEXT，判据已证 String 形态）与该差异
   相关，与 14b1/16a 同口径承担，未额外造 Object 直通分量。
7. **旧幂等/审计 JSON 文本键序**：`SealReceipt`、`SynchronizationStatus`、`Preflight`、
   `CalibrationResult`、`Identification` 旧码用 `Map.of`（跨 JVM 无序、树无契约），record 化后
   归一为声明序——回放按属性名匹配，不受影响；`SyncDeclarationReceipt`/`ObservationReceipt`/
   roster 三处旧码是 LinkedHashMap，声明序与之**逐字节相同**（判据 §3-10/11/14/15 锁死），
   因此参与摘要与快照比对的文本零漂移。
8. **排除清单（保留 Map/不动，按规格）**：封账存储面（`header` LinkedHashMap、`frozenItems`
   packet、`evidencePacket` 七关系 Map.of 表、`unobservedHistory`/`selectionHistory`/
   `missingLosses` 行投影、`dateChange`、`frozenTask`）、`wire()` 本体、`conflict()` 的
   `fields` LinkedHashMap 与 `InventoryReviewConflict`/`InventorySealConflictHandler` 错误面、
   `audit()` 双表插入与 `detail_json` 入参、观测 `facts_json`（请求体原样）、
   `sha/hash` 指纹输入、`InventoryObservationTime.Result`、`InventoryLateEvidencePort.Task`、
   全部 `db.queryForList` 行 Map——一律未动。契约/OpenAPI/前端零改动（无 API 形状变化）。
9. **观察项（非缺陷、未改）**：`reportRows` 的 `table`/`order` 由 section 字符串拼接进 SQL 是
   改前既有形态（三个分支均为白名单常量），本批未扩大；`preflight` 的 sealed 分支再次调用
   `report()`（重复 `admin()` revalidate + task FOR UPDATE + report FOR SHARE）是改前读序，
   保持不变。
10. AOCI 认知维护：本会话工具面未暴露 aoci_* MCP 服务，无法调用 `aoci_maintain`；受管理对象已达
    本批最终稳定态，登记该收尾步骤未执行，交由持有 AOCI 工具的会话对齐。
11. **续作核账记录（本次会话，逐项已核并非缺陷，列出以免重复劳动）**：
    1. 唯一实质缺口 = §6-4 的 `boxed()` 重复 helper，已删除并复用 `nullable()`；其余 main 改动经
       `git diff HEAD` 逐段核对为完整、无半截出口。
    2. 列集核证：`TaskRow` 21 分量 / `SealedReportRow` 15 分量逐一等于
       `V31__create_inventory_task_snapshots.sql` 与 `V40__create_inventory_sealed_reports.sql` 的
       `SELECT *` 列序（`fams_inventory_sealed_report` 无独立 id 列，主键即 `task_id`）；
       `grep 'ALTER TABLE fams_inventory_task|…sealed_report|…fams_inventory_item'` 全库零命中，
       故不存在漏投影的后加列。
    3. `node()` vs 旧 `wire()` 递归：`range_json` 由 `InventoryViews.inventoryRangeJson` 写成
       camelCase `*Id` 键（`usingOrganizationId`/`categoryId`…），不命中旧 `wire()` 的
       `id`/`*_id` 规则，JSON 文本内也不可能出现 Timestamp，故旧递归对本域 `*_json` 列是幂等的，
       typed 侧不再递归等价。
    4. `CalibrationResult.calibrationId` 与 `Identification.itemId` 旧值本就是 Java `String`
       （`UUID.randomUUID().toString()` / SQL 侧 `CAST(id AS CHAR)`），typed String 不是新字符串化。
    5. 全局 Jackson 口径核到 `contract/probe/JsonConfiguration`：`withValueInclusion(ALWAYS)` +
       Textual 对 Integer/Float/Boolean 输入 CoercionAction.Fail；record 因此必须逐分量显式
       NON_NULL，且本批回放涉及的每个分量在旧存储文本里都是同名同类型（无数字→字符串隐式转换路径）。
    6. 出口面复扫：三服务 `public` 方法返回类型已 11/11 typed，包外对三服务零引用，
       三控制器除 `denyPcDeclaration`（403 空体）外无 Map 返回，`InventoryObservationController`
       内嵌 `Lookup` 请求体未动。
    7. 真实消费面预检：`apps/miniprogram/test/runtime-inventory-sealing-http.mjs` 的
       `nativeSealedOriginalReplyRecovered` 走页面状态/解析树断言（`assert.deepEqual` 只用于两次
       *请求*），不锁回放响应文本的键序，故 `Map.of` 旧随机序文本 → typed 声明序重放不破该门禁；
       `InventorySealHttpRuntimeCase` 断言用到的键名集合
       （acceptedCount/blockerCount/missingSynchronizationCount/notObservedCount/
       synchronizationDigest/taskVersion/observationWatermark/devices/current/reason/itemId/kind/
       snapshot/calibrationId/counted/outcome/eventId/device/items/total/version/status）
       全部在本批 record 的键集内。

## 7. 下一步

- 主代理合并门禁：JaCoCo 覆盖率序列 + 真实 HTTP 运行时用例
  （`InventorySealHttpRuntimeCase`、`InventorySynchronizationHttpRuntimeCase`、
  `InventoryObservationHttpRuntimeCase` 及 17a/17c/17d 消费面）实跑；SpotBugs 已由本批续作会话
  实跑为零缺陷（§4），合并门禁按全树口径复跑确认即可，`SealViews` List 分量拷贝位无需再关注。
- 与 17a/17c/17d 合并顺序无关：本批与他批零文件交集，但 `InventorySealEvidence`（17a）若改
  public 签名，本批 `evaluate()` 的两处调用点需同步——当前判据不含该面，合并门禁需一并核证。
- 后续波次：inventory 域剩余 Map 出口在 17a（选择/差异/就绪）、17c（发现/复核/照片）、
  17d（主任务/迟到/迟到损坏）各自回收；`SealViews` 与他们的 Views 文件并存不冲突（本批只放
  本路 record）。

## 主代理产品门禁（波次六合并实跑）

主代理在波次六合并树固定快照副本（rsync 排除并行 WIP；快照前漂移校验证明非 inventory 已提交面
零改动；四路 views 文件内容核实入照）实跑完整 Java 序列（nohup 脱离态）：首轮即 **JAVA01_VERIFY_PASS**，
83 步 exit=0，Checkstyle/SpotBugs 0，覆盖率 LINE 93.45%、BRANCH 75.29%；日志内 74 个 surefire
阶段汇总 401 项 0 失败 0 错误。四个新套件另在门禁副本内直接复跑 **80/80 绿**（Selection 19 +
Seal 20 + Finding 21 + Main 20）。主树合并判据：33 个 WireShape 类 340/340。
详见[波次证据](evidence/JAVA-17-java-gate.txt)。
