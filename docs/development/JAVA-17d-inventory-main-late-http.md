# JAVA-17d · inventory 域「盘点主档/迟交/迟交损坏」HTTP 出口类型化

> 用户指定的后端结构批（HTTP 出口波次 · inventory 17d 子批），不进入 D6-01～D6-27 台账、不占用其状态。
> 日期：2026-11-23 · 分支：main · 基线：主树当前状态（JAVA-15 inbound 与 16a/16b/16c repair 已交付，
> 状态：主代理审计通过，波次六合并门禁通过（见文末证据节），已收口提交。
> 状态：实施完成，等待主代理合并门禁。
> 续作附记（2026-11-23）：前序实施代理触回合上限终止，本验证代理收口。对 17d 六文件 diff 快审
> （SQL 文本/锁读序/异常码/状态码/路径红线、public `Map<String, Object>` 出口全仓 grep 归零、
> SpotBugs 三查逐条核证）并实跑复核：定向 `InventoryMainWireShapeTest` **20/20 绿**、
> `-Dtest='*WireShapeTest,TransferCalendarTest'` **340/340 绿（BUILD SUCCESS，含 17a/17b/17c
> 当时落盘套件零退化）**、`-pl backend test-compile` **exit=0**、
> `checkstyle:check@check-java-conventions` **0 violations**。§3 判据与 §4 自测数字与实跑一致，
> 未更正实现与记录正文；DDL 列集与六个 record 字段逐一对照全等（V31/V41/V51，无 ALTER 加列）。

## 1. 背景与红线

完全复刻 JAVA-01/11a/14b1/15/16a 标准：HTTP 响应类型化；内部行 Map（`task()`/`linkedTask()`/
`lateCase()`/`latest()`/`state()`/`source()` 的 `queryForList` 行、盘点事件证据 payload
（`fams_inventory_task_event.detail_json` 的 `Map.of(...)` 与 `captured` 摘要）、审计 `detail_json`、
幂等指纹输入 `fingerprint(version, input)`/`hash(...)`）保留 Map；契约
`contracts/fams-core-v1.yaml`、OpenAPI 生成类、`packages/api-types`、前端零改动；SQL 语句、参数顺序、
`FOR UPDATE`/`FOR SHARE` 锁顺序、异常码、HTTP 状态码、端点路径、请求体 record 一字未动。

红线机械核证：对三个 service 做「HEAD 每个 `db.*` 调用的前导拼接字符串字面量表达式 vs 工作区」比对，
**66 条 JDBC 语句文本与出现次序全等**（InventoryService 31、InventoryLateService 18、
InventoryLateDamageService 17）。唯一换行重排是损坏回执页 `COUNT(*)` 语句的拼接断点
（`...decision WHERE` + ` observation_id=?` → `...decision` + ` WHERE observation_id=?`），
拼接结果逐字符相同。

本批只触碰 6 个既有文件（`InventoryController`/`InventoryService`/`InventoryLateController`/
`InventoryLateService`/`InventoryLateDamageController`/`InventoryLateDamageService`）+ 新增
`InventoryViews`、`InventoryMainWireShapeTest` 与本记录。`InventoryViews.java` 经全仓 grep 确认派发前
无占用；17a/17b/17c 各用 `SelectionViews`/`SealViews`/`FindingViews`，四路零同名冲突，
`InventoryViews` 的使用者恰为本批 7 个文件（grep 核证），跨批零耦合。
禁触面 `Selection/Difference/Readiness/SealEvidence`（17a）、`Seal/Synchronization/Observation`（17b）、
`Finding/Review/Photo/RepairPhoto`（17c）、全部 Port（`InventoryLateEvidencePort`/
`InventoryExecutorPort`/`InventoryLossPort`/`InventoryRepairPort`/`InventoryAttachmentPort`）、
`AssetInventoryReviewPort`/`AssetMasterDataPort`/`InventoryReferencePort`、
`Inventory*ConflictHandler`、`InventorySchedule`、`InventoryExceptionTodoScheduler`、
`InventoryLatePolicy`、`InventoryRange`、`InventoryInput*`、`repair/`、`inbound/`、`contracts/`、
`apps/`、`DESIGN-HANDOFF.md`、`AGENTS.md` 本批零 diff。同树并行的 17a/17b/17c 改动不属本批 diff。

**scheduler 适配面为零**：`InventorySchedule` 只消费 `InventoryService.startDue()`（`boolean`，
本批未改签名，循环条件与 `LOG.warn` 分支逐字未动）；`InventoryExceptionTodoScheduler` 只持有
`JdbcTemplate`+`Clock`，根本不引用 `InventoryService`（grep 核证）。故任务书预留的「get 类出口签名
最小适配」两文件最终**未被触发，保持一字未动**，判据 §3-14 以反射断言锁死该契约防退化。

inventory 与先例的关键差异（改前代码逐字冻结为 oracle 核对）：

1. **同一张 `fams_inventory_task` 行在本批存在三种投影，不可互套**：
   - 主档 `InventoryService.clean`（**非递归**）：`range_json` 经 `InventoryInput.Range` 往返归一
     （存储侧数字标识符因此出图为文本，`InventoryTaskHttpRuntimeCase` 的
     `largeRangeIdentifiers…` 判据），其余 `*_json` 走 `readTree(value.toString())` 原样成树；
   - 主档写出口体 = 同一 `clean` 行投影（旧代码 `finish(..., clean(task(id,false)))`，**无
     `overdue`**），同时也是 `fams_idempotency_record.response_body_json` 的存储形状；
   - 迟交面 `InventoryLateService.wire`（**递归**）：任意深度嵌套 `id`/`*_id` 键 `.toString()`，
     任意深度 `*_json` 字符串字段先 `readValue(text, Object.class)` 展开再继续走；
     `range_json` 因此是**存储原文档**（键序、键数、数字类型都照存储），与主档口径刻意不同；
   - 迟交损坏面 `InventoryLateDamageService.wire` 与迟交面同形但**无 `*_json` 分支**，
     而它两表（`fams_inventory_late_damage`/`_damage_decision`）确无 JSON 列，故三者共用一套
     行内规则、各按来源分派；`originalEvidence` 分量另走非递归 `readTree` 直通（旧代码即如此）。
2. **条件键的两种"结构性缺失"不用 NON_NULL**：`taskView` 对非管理员 `remove` 六个键，而管理员视角下
   `publisher_account_id`/`end_reason` 在 DRAFT 态**必须仍是显式 null**（全局 inclusion=ALWAYS）；
   `view()` 的 `review` 在两态下键集不同（占位两键 vs 决策行十键），`damageFollowup` 在无损坏行时是
   `{}` 而非缺键。三者一律以**多 record + sealed interface** 复刻，`@JsonInclude(NON_NULL)` 会改变
   线上形状，故本批一处也未用（`InventoryViews` 全文零 NON_NULL）。
3. **幂等回放形状集**：主档 8 个 operation 只有 1 种存储形状；迟交面 `original()` 2 种
   （prepare/decide）；损坏面 1 种。蓝本 `Class<T>` 泛型只在多形状处采用（详见 §6-2）。
4. **零 convertValue 兼容包装**：16a 因 `InventoryRepairPort.link(row)`/`LossRepairPort.Created.fromView`
   以 Map 消费出口而保留 4 个永久包装。本批全部出口的下游消费者都在本批文件内
   （`InventoryLateService.decide` 的 `inventory.copy` 已切 typed `draft.id()`），
   `InventoryLateEvidencePort.Task.fromRow(task)` 收的是**内部行 Map**（不是出口，签名与入参形状不变），
   `InventoryRepairPort.Link` 早已类型化 ⇒ 本批**不新增任何 Map 镜像面**。

## 2. 改动清单与出口映射

主代码 6 改 1 增，测试 1 增，本记录 1 增：

| 文件 | 变化 |
|---|---|
| `inventory/InventoryViews.java`（新增） | 24 个响应 record + 2 个 sealed 接口（`InventoryTaskView`、`InventoryLateReview`、`InventoryLateDamageFollowup`）+ 行映射工厂（`inventoryTaskRow`/`inventoryItemRow`/`inventoryLateTaskHeader`/`inventoryLateEvidence`/`inventoryLateDecision`/`inventoryLateDamage`/`inventoryLateDamageDecision`/`inventoryLateCaseView`）+ 两条投影规则的单源实现（非递归 `inventoryNode` 与递归 `inventoryWire`/`inventoryWireField`）；`inventoryRangeJson`/`inventoryNormalizedRange` 冻结 Range 往返；含 List 分量的 5 个 record 全部紧凑构造器 `List.copyOf` 防御拷贝；无 Map 分量；`time` 分支三分支（Timestamp/LocalDateTime/其它 `.toString()`，§6-3） |
| `inventory/InventoryService.java` | 12 个方法出口类型化（`preview`/`referenceChoices`/`list`/`detail` + 7 个幂等写出口 + 私有 `digest`）；`replay` 返回 typed `InventoryTaskRow`（存储体经**同一投影工厂**再投影，等价旧 `clean(readValue(...))`）；`finish` 结果参数改 `InventoryTaskRow`，`resource_id`/`resource_version` 取 `result.id()`/`result.version()`；`taskView` 改派双 record，`overdue` 判定式逐字保留；**删除** `clean()`/`rangeJson()`/`identifier()`（移入 InventoryViews，写入侧 `INSERT` 参数改调 `InventoryViews.inventoryRangeJson`，文本同源的存储值不变）；`INVENTORY_PUBLISHED` 事件/审计 payload 保持旧 `Map.of("count",…,"digest",…)` 字面装配；SQL、`FOR UPDATE`（task/item/participant 逐条）、`SUM`/`COUNT` 现查、异常码零改动 |
| `inventory/InventoryController.java` | 11 个端点返回类型化视图；`executors` 端点（早已 typed `List<InventoryExecutorPort.ExecutorChoice>`）零改动；请求体、校验注解、路径、`Idempotency-Key`/`If-Match-Version` 头一字未动；删除随之无用的 `java.util.Map` 导入 |
| `inventory/InventoryLateService.java` | 4 个出口类型化；`original()` 改 `<T> T original(…, Class<T>)`（11a/15 蓝本），`receipt()` 结果参数改 `Object`；`view()` 拆出 `damageRow()`/`todoOpen()` 私有件以**保持旧读序**（latest → damage → todo，且 list 内 items 三段读仍先于 `COUNT`）；**删除** `wire()`（移入 InventoryViews）；`inventory.copy(...)` 消费点由 `draft.get("id").toString()` 改 `draft.id()`；SQL、`FOR UPDATE`（linkedTask）/`FOR SHARE`（case/decision/damage/todo）次序与 `InventoryReviewConflict` 语义零改动 |
| `inventory/InventoryLateDamageService.java` | 2 个出口类型化；旧内联 `TypeReference<Map>` 回放改 `readValue(…, InventoryLateDamageDecisionReceipt.class)`；`detail` 的 `history` 局部化以锁住「行查询 → COUNT」次序；`COUNT` 加 `Objects.requireNonNull`（§6-4）；**删除** `wire()`；`repairs.create/link/progress` 与 `InventoryRepairPort.Evidence` 装配、`fams_inventory_late_damage` 乐观锁 `UPDATE … WHERE version=?`、`changed != 1` 判定、todo 双分支、`InventoryReviewConflict` 字段链零改动 |
| `inventory/InventoryLateController.java` | 4 个端点返回类型化视图；`decide` 的 `CannotAcquireLockException` 内联一次重试循环结构原样保留 |
| `inventory/InventoryLateDamageController.java` | 2 个端点返回类型化视图；同上重试循环未动 |
| `inventory/InventoryMainWireShapeTest.java`（新增） | 20 条 wire 等价用例，见 §3 |

出口 → 类型对照（17 个类型化出口 + 1 个复用已类型化出口）：

| 旧 Map 出口 | 新 record |
|---|---|
| POST /inventory-tasks/preview；`digest()`（发布守卫同源） | `InventoryPreviewDigest(count, digest)` |
| GET /inventory-tasks | `InventoryTaskPage(items, total, page, pageSize, canManage)`，items = `InventoryTaskView` |
| GET /inventory-tasks/catalog/{kind} | `InventoryReferenceChoicePage(items, page, pageSize)`（旧信封无 `total` 键，故不复用他域 `Page`） |
| GET /inventory-tasks/{id} | `InventoryTaskDetail(task, items, total, page, pageSize, canManage)`，items = `InventoryItemRow` |
| POST create/copy/revise/publish/start/assign/`{delete\|cancel\|terminate}` 与幂等存储体 | `InventoryTaskRow`（21 键 clean 行，声明序=列序） |
| 管理员列表项 / `detail.task` | `InventoryTaskAdminView`（21 键 + `overdue`）implements `InventoryTaskView` |
| 执行人列表项 / `detail.task` | `InventoryTaskExecutorView`（15 键 + `overdue`）implements `InventoryTaskView` |
| GET /inventory-tasks/executors | `List<InventoryExecutorPort.ExecutorChoice>`（10c 已类型化，复用未动） |
| GET /inventory-late/tasks/{task} | `InventoryLateCasePage(task, items, page, pageSize, total, supplementOnly)`；task = `InventoryLateTaskHeader`，items = `InventoryLateCaseView` |
| GET /inventory-late/tasks/{task}/observations/{o} | `InventoryLateCaseDetail(task, @JsonProperty("case") lateCase, history, supplementOnly)`；history = `InventoryLateDecisionPage` |
| `view()` 的 `evidence` / `review` / `damageFollowup` | `InventoryLateCaseEvidence`（9 键）/ sealed `InventoryLateReview` = `InventoryLateReviewPlaceholder`(2 键) \| `InventoryLateDecisionRow`(10 键) / sealed `InventoryLateDamageFollowup` = `InventoryLateDamageAbsent`(`{}`) \| `InventoryLateDamageRow`(7 键) |
| POST …/prepare 与其收据体 | `InventoryLatePrepareReceipt(taskId, prepared, moreMayRemain, taskVersion, supplementOnly)` |
| POST …/decisions 与其收据体 | `InventoryLateDecisionReceipt`（10 键，`linkedTaskId` 显式 null） |
| GET …/damage-repair | `InventoryLateDamageDetail`（12 键，含 `originalEvidence` 非递归直通）；history = `InventoryLateDamageDecisionPage`(13 键行) |
| POST …/damage-repair 与其收据体 | `InventoryLateDamageDecisionReceipt`（13 键） |
| 非出口（保留 Map）：`task()`/`lateCase()`/`latest()`/`state()`/`source()`/`linkedTask()` 行、`event(...)` 与 `audit(...)` 的 `Map.of` payload、`conflict()` 的 `fieldErrors` LinkedHashMap、`fingerprint`/`hash` 指纹输入、`requireCompletedRecheck` 校验链、`InventorySealEvidence`/Port 面 | 一律未动 |

## 3. 判据：wire 等价测试

`InventoryMainWireShapeTest`（20 用例）把改前 `InventoryService.clean/rangeJson/identifier/taskView/
list/detail/digest/referenceChoices`、`InventoryLateService.wire/view/list/detail/prepare/decide` 与
`InventoryLateDamageService.wire/detail/decide` 逐字段复制为 legacy oracle，构造 JDBC 类型行
（Long/Integer/Short/String/Timestamp/LocalDateTime/Boolean/JSON 串/可空列 null）比对：

1. **主档 clean 行**：全 21 键 + 新写存储体**字节同序**断言；标识符列文本化、数值列保数值、
   `range_json` 归一为固定 8 键（缺项显式 null）；DRAFT 全 null 态；LocalDateTime 变体（ISO 串逐值）；
   `Integer`/`Short` 宽度变体。
2. **双角色 taskView**：管理员 22 键（`publisher_account_id`/`end_reason` 显式 null 必须在）vs
   执行人 16 键（六键必须不存在），逐 key `has`/`assertFalse` 直接断言；`overdue` 三态（未到期 /
   IN_PROGRESS 已过期 / 非 IN_PROGRESS 已过期）。
3. **item 行**：9 键**有序**序列断言 + `snapshot_json` 嵌套 `*_id` **不**被字符串化（非递归口径锁死）。
4. **主档信封**：`InventoryTaskPage`/`InventoryTaskDetail` ≡ 旧 `Map.of`；键数 5/6 断言；
   preview 两键与 catalog 三键（项内 3 键 + `name` 显式 null）。
5. **迟交递归 wire**：`InventoryLateTaskHeader` 保留存储原文档（2 键、数字类型）但**嵌套
   `keeper_id` 字符串化**；`InventoryLateCaseEvidence` 的 `snapshot_json` 覆盖数组内对象、
   三层嵌套 `*_id`、嵌套 `*_json` 字符串展开后再走、null 保留、camelCase 数值不动 ——
   一条用例即能抓住"把递归口径退化成 JsonNode 直通"的漂移。
6. **迟交 case view 双态**：占位 2 键（`OPEN`/`INFORMATION` 两分支）与决策行 10 键；
   `damageFollowup` 的 `{}` 与 7 键两态；`needs_review` 布尔直通。
7. **迟交信封**：`InventoryLateCasePage`(6 键)/`InventoryLateCaseDetail`(4 键，`case` 键名保留、
   无 `lateCase` 泄漏)/`InventoryLateDecisionPage`(4 键)。
8. **损坏面**：`InventoryLateDamageDetail` 12 键（顶层 LinkedHashMap 键序逐项断言，内层 history
   旧为 `Map.of` 故只比树）+ 无资产上下文态（`currentAsset` 显式 null）+ 空 history；
   `InventoryLateDamageDecisionReceipt` 13 键两态（FORMAL/REFRESH）。
9. **幂等存储旧 JSON 回放**（跨版本必测）：`response_body_json` 旧文本 →「readValue 成 Map +
   同一 `inventoryTaskRow` 工厂」→ 树等价**且再序列化字节等价**；typed 行 ≡ 原始行投影（单源装配）；
   **旧体 `range_json.categoryId` 被写成数字**的现网形状（`InventoryTaskHttpRuntimeCase` 同源手法）
   → 回放仍出文本 `"9007199254740993"`；`InventoryLatePrepareReceipt`/`InventoryLateDecisionReceipt`/
   `InventoryLateDamageDecisionReceipt` 三张旧收据体回放等价（LinkedHashMap 派附字节同序断言）；
   四类型 `序列化→反序列化→再序列化` 恒等。
10. **消费点防退化**：`InventoryService.startDue()` 反射断言仍返回 `boolean`（`InventorySchedule`
    循环条件）；`finish` 的 `resource_id`/`resource_version` 取旧 Map 读值同值断言；
    `InventoryLateService.decide` 的 `linkedTaskId` 与 `Task.fromRow` 三元组同值断言（证据 port 面未动）。

**单元 oracle 边界（JAVA-01 沿用）**：只证明「行→视图→存储/回放」序列化等价与读序局部变量化不改语义。
真实 HTTP 运行时链（`auth/InventoryTaskHttpRuntimeCase`、`InventoryLateHttpRuntimeCase`、
`InventoryLateDamageHttpRuntimeCase`、`InventoryObservationHttpRuntimeCase`、
`InventoryReviewHttpRuntimeCase`、`InventoryLateUpgradeRuntimeCase`、
`InventoryLateDamageUpgradeRuntimeCase`、`InventoryRepairHttpRuntimeCase` 等）为
`@EnabledIfSystemProperty` 容器门禁（需 Testcontainers MySQL），本批环境无容器、且任务红线禁跑
`scripts/verify-d6-03a.py`，登记为门禁边界，由主代理合并门禁实跑。

## 4. 实施代理自测（非产品门禁）

- 定向判据：`JAVA_HOME=graalvm-jdk-21.0.7 ./mvnw -pl backend -o -Dtest='InventoryMainWireShapeTest'
  -DfailIfNoTests=false test` → **Tests run: 20, Failures: 0, Errors: 0, Skipped: 0**。
- 回归口径：`-Dtest='*WireShapeTest,TransferCalendarTest'` → **33 套件 Tests run: 340, Failures: 0,
  Errors: 0, Skipped: 0**（BUILD SUCCESS；其中本批 20 项，余 320 项为既有 29 套件 + 同树并行
  17a `InventorySelectionWireShapeTest`、17b `InventorySealWireShapeTest`、17c
  `InventoryFindingWireShapeTest` 已落盘套件，**零退化**）。
- 全仓编译：`./mvnw -pl backend -o -q test-compile` → **exit=0**。
- Checkstyle：`./mvnw -pl backend -o -Pquality checkstyle:check@check-java-conventions` →
  **You have 0 Checkstyle violations. / BUILD SUCCESS**，新文件末尾换行合规。
- 红线机械核证：§1 所述 66 条 JDBC 语句文本+次序全等脚本（HEAD vs 工作区，逐 `db.*` 调用抽取前导
  拼接字面量表达式）。
- 并行代理阻塞登记：本批共遇 **6 次**同树 maven 竞态红窗（他人文件在途编译错误，或
  `target/classes` 被并发重建导致 `无法访问 com.fams.repair.RepairViews.Page`/
  `程序包 com.fams.asset.AssetRepairPort 不存在` 等虚假报错）。按任务书 35 秒重试策略处理，
  未修改任何并行批文件；本批定向判据在第 2 次尝试转绿、回归口径首试即绿。
  报错签名全部落在并行批文件或"整包不存在"，无一指认本批文件。
- 未跑：SpotBugs/JaCoCo/真实 HTTP 运行时用例与 `verify-d6-03a.py` 全量门禁——按任务红线留给主代理
  合并门禁。EI_EXPOSE_REP 风险在构造侧预清：5 个含 List 分量的 record 全部 `List.copyOf`；
  **无 Map 分量**；非 String 类分量只用 `JsonNode`（不可变）与 `Object`（仅 `needs_review` 一处，
  承载不可变 `Boolean`）。`COUNT(*)` 拆包点全部 `Objects.requireNonNull`。

## 5. 影响分析（GitNexus）

编辑前执行 `impact -d upstream --repo fams-manage`（Node 24 前缀 `~/.nvm/versions/node/v24.18.0/bin`）：

- 类级：`InventoryService` **LOW（direct 3）**、`InventoryLateService` **LOW 1**、
  `InventoryLateDamageService` **LOW 1**、`InventoryController`/`InventoryLateController`/
  `InventoryLateDamageController` **LOW 0**。`InventoryService` 的 3 个 d=1 明细即
  `InventoryController`、`InventoryLateService`、`InventorySchedule` —— 全部在册（前者本批改、
  中者本批改、后者只消费未改的 `startDue()`）。
- 方法级（`-f` 文件消歧）：`InventoryService.copy` LOW（d=1 = `InventoryController.copy` +
  `InventoryLateService.decide`，后者经 `Inventory` 模块边聚合）；`detail`/`list`/`preview` LOW 1；
  `InventoryLateService.view` LOW 4、`wire` LOW 5（d=1 全在本服务与 `InventoryLateController` 内）；
  `InventoryLateService.task` LOW 8（同名 `task` 私有件在本文件内聚合，编译器封闭）；
  `InventoryLateDamageService.detail`/`decide` 各 LOW 1（唯一上游 = 本批控制器）。
  **无越批 HIGH/CRITICAL，无真实跨域上游**。
- 伪边核实：`wire/view/task/clean/finish/digest` 等通用名的 d=1 结果逐条落回 inventory 包内
  （private 成员由编译器封闭保证），grep 复核 `InventoryLateService|InventoryLateDamageService|
  InventoryService` 的仓内引用集只含本批 6 文件 + `InventorySchedule` + 两个测试类
  （`InventoryTaskHttpRuntimeCase`/`InventoryObservationHttpRuntimeCase` 仅 `@Autowired` 与
  `startDue()`，签名不变 ⇒ 零适配，编译已核证）。
- `detect-changes`（工作树）：26 文件 173 符号、risk low、0 受影响执行流——**整树口径**，混入
  17a/17b/17c 在途 inventory 文件与 repair/inbound 已交付文件、`AGENTS.md`；本批 diff 为
  6 改 + 2 增 + 本记录，全部落在上列 LOW 面内。
- 索引不含本批新符号（`InventoryViews`）属正常。

## 6. 偏差与遗留

1. **裁决点：三种任务形状 + 两个 sealed 替代 NON_NULL 条件键**。见 §1-2：旧代码是"按角色删键"和
   "空对象 vs 整行"，与"按 null 省键"不同源。若后续要合并 admin/executor 两 record，须先接受
   DRAFT 态管理员响应缺 `publisher_account_id`/`end_reason` 的形状变化（当前判据 §3-2 明确禁止）。
   `case` 为 Java 保留字，故用 `@JsonProperty("case") lateCase`，判据断言线上键名仍是 `case`。
2. **裁决点：`replay` 泛型只在多形状处采用**。主档 8 个 operation 存储形状唯一（clean 行），
   故 `InventoryService.replay` 直接返回 `InventoryTaskRow`，并保留「`readValue` 成 Map + 同一投影
   工厂」两步——**不能**改写成 `readValue(body, InventoryTaskRow.class)`：旧代码是
   `clean(readValue(body, Map))`，其中 `range_json` 必须再过一次 Range 归一，现网确有把
   `$.range_json.categoryId` 写成数字的旧回执（`InventoryTaskHttpRuntimeCase` 用 `JSON_SET` 制造同型
   场景），直读 record 会让它出成数字而破坏回放判据（§3-9 锁死）。`InventoryLateService.original`
   两形状 ⇒ `<T> T original(…, Class<T>)`；损坏面单形状 ⇒ 直接 `readValue(…, Class)`。
3. **判据抓到的真实等价性缺口（已修）**：`InventoryViews` 初版把 DATETIME 分支写成
   Timestamp/LocalDateTime 二分 + 强制 cast，回放路径（存储体里时间已是 ISO **字符串**）抛
   `ClassCastException`，而旧 `clean` 的 else-if 链在该情形是**直通**。现固定为三分支
   （Timestamp → `toInstant()`；LocalDateTime → `toInstant(UTC)`；其它 → `value.toString()`），
   §3-1/§3-9 两用例覆盖。残余理论差：若驱动对 DATETIME 返回第三种类型（Connector/J 不会），
   旧代码会把对象交给 mapper、新代码取 `toString()`；口径同 16a §6-5「双驱动形态」，登记不修正。
4. **`Objects.requireNonNull` 新增 1 处**：损坏回执页 `COUNT(*)`（旧代码未包，直接入 Map）。
   `COUNT(*)` 无 `GROUP BY` 必返一行 ⇒ null 不可达；改动只把"理论上输出显式 null"变成 NPE，
   并为 SpotBugs NP 消警。口径同 16a/15（其余三处 COUNT 原本已带 `requireNonNull`，原样保留）。
5. **新写存储文本的键序**：`InventoryTaskRow`（声明序=列序=旧 LinkedHashMap 序）、
   `InventoryLateDecisionReceipt`、`InventoryLateDamageDecisionReceipt` 三类**新写文本逐字节同序**
   （判据 §3-9 断言）；`InventoryLatePrepareReceipt` 旧体为 `Map.of`（键序本就是哈希序、跨 JVM 无契约），
   新写归一为声明序，跨版本按树等价回放。审计 `detail_json` 中 `INVENTORY_LATE_PREPARED`、
   `INVENTORY_LATE_DECIDED`、`INVENTORY_LATE_DAMAGE_DECIDED` 三处的 payload 即响应体，
   随类型化同样归一为声明序（语义等价，键序无契约）；`INVENTORY_PUBLISHED` 事件与审计的
   `{"count":…,"digest":…}` 保持旧 `Map.of` 字面装配，文本不变。
6. **`recheckResult`/`todoOpen` 的求值位置**：旧代码写在 `result.put(...)` 实参里，本批移到 record
   构造实参（`decide`）或顶层实参（`detail`），实参仍按 Java 左到右求值 ⇒ 两条 `FOR SHARE` 查询的
   相对次序与旧完全一致；`InventoryLateService.list/detail` 另把 items/history 局部化，
   显式锁住"行查询（含每 case 三段读）先于 `COUNT`"的旧读序，代码内已加注释钉住。
7. **排除清单（保留 Map/不动，按规格）**：请求体 `InventoryInput.*`/`InventoryLateInput`/
   `InventoryLateDamageInput`/`InventoryDifferenceInput.Damage`；内部行 Map 与
   `InventoryLateEvidencePort.Task.fromRow(row)` 入参；`event(...)`/`audit(...)` 的 `Map.of`
   盘点事件证据与冲突 `fieldErrors`；`fingerprint`/`hash` 幂等指纹输入；`InventoryRange.predicate`
   SQL 构造与 `digest()` 的行游标累加；`requireCompletedRecheck`/`source()` 的快照与哈希校验链；
   `InventorySealEvidence`、全部 Port、两个 ErrorHandler/三个 ConflictHandler——一律零 diff。
   契约/OpenAPI/前端零改动（无 API 形状变化）。
8. **`InventoryLateDamageService` 与主档共享的 `fams_inventory_late_damage` 行投影**：17b 的
   `SealViews`、17c 的 `FindingViews` 若后续也投影同一表，可直接复用
   `InventoryViews.InventoryLateDamageRow`（同一递归 wire 口径），或按各自冻结口径另立 record；
   两种都不影响本批判据。
9. **跨版本回放的形状前提（已核证，登记）**：`InventoryLateDamageDecisionReceipt.repairId` 取
   `String`，因为 `InventoryRepairPort.Link.repairId` 自其唯一引入提交 `684c6dc` 起即 `String`，
   现网收据体不存在数字形态（否则全局 `Textual←Integer=Fail` 约束会使回放抛错）。
   若未来 `Link.repairId` 改数值，须同步登记旧收据回放兼容面。
10. AOCI 认知维护：本会话工具面未暴露 `aoci_*` MCP 服务，无法调用 `aoci_maintain`；受管理对象已达
    本任务最终稳定态，登记该收尾步骤未执行，交由持有 AOCI 工具的会话对齐。

## 7. 下一步

- 主代理合并门禁：全量 Java 质量序列（含 SpotBugs/JaCoCo）+ 真实 HTTP 运行时链
  （`InventoryTask/InventoryLate/InventoryLateDamage/InventoryObservation/InventoryReview/
  InventoryRepair` 及 `*UpgradeRuntimeCase` 幂等升级链）实跑；SpotBugs 关注
  `InventoryViews` 的 List 分量拷贝位与 `needs_review` 的 `Object` 分量（已按先例预清）。
- 同树合并注意：`InventoryViews`（17d）与 `SelectionViews`（17a）/`SealViews`（17b）/
  `FindingViews`（17c）为四个独立新文件，无重名 record；四路 `*WireShapeTest` 亦各自独立。
- 后续若要把主档 admin/executor 两 view 合并、或把 `InventoryLateTaskHeader` 与
  `InventoryTaskRow` 统一，须先处置 §1-1 的两种 `range_json` 口径与 §6-1 的角色键集差异。

## 主代理产品门禁（波次六合并实跑）

主代理在波次六合并树固定快照副本（rsync 排除并行 WIP；快照前漂移校验证明非 inventory 已提交面
零改动；四路 views 文件内容核实入照）实跑完整 Java 序列（nohup 脱离态）：首轮即 **JAVA01_VERIFY_PASS**，
83 步 exit=0，Checkstyle/SpotBugs 0，覆盖率 LINE 93.45%、BRANCH 75.29%；日志内 74 个 surefire
阶段汇总 401 项 0 失败 0 错误。四个新套件另在门禁副本内直接复跑 **80/80 绿**（Selection 19 +
Seal 20 + Finding 21 + Main 20）。主树合并判据：33 个 WireShape 类 340/340。
详见[波次证据](evidence/JAVA-17-java-gate.txt)。
