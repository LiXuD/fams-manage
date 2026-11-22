# JAVA-14b1 · requisition 域「核心 + 交接」HTTP 出口类型化

> 用户指定的后端结构批（HTTP 出口波次 · requisition 最重子批），不进入 D6-01～D6-27 台账、不占用其状态。
> 日期：2026-11-26 · 分支：main · 基线：`2fe2880`（JAVA-11c 提交）
> 状态：主代理审计通过，波次三合并门禁通过（见文末证据节），已收口提交。

## 1. 背景与红线

完全复刻 JAVA-01/11a 标准：HTTP 响应类型化；内部行 Map、审计 payload、幂等指纹输入保留 Map；
契约 `contracts/fams-core-v1.yaml`、OpenAPI 生成类、`packages/api-types`、前端零改动；SQL 语句、
锁顺序、异常码、HTTP 状态码、响应头、端点路径一字未动。本批只触碰
`RequisitionController/RequisitionService/RequisitionHandoverController/RequisitionHandoverService`
四个既有文件 + 新增 `RequisitionViews` 与 `RequisitionWireShapeTest`。同包
BusinessResultNotice*/Reassignment/Unavailability/ProxyConfirmation*/MiniProxy*/BorrowDatePort/
ItemTargetChangeInput/ProxyReceiptPayloads 与 masterdata、contracts、apps 零触碰。

requisition 与先例的关键差异（改前代码逐字冻结为 oracle 核对）：

1. **两条不同的 clean() 投影**：`RequisitionService.clean` 处理 Timestamp/LocalDateTime→ISO
   instant、java.sql.Date→yyyy-MM-dd、`id`/`*Id` 数值→toString、`*Json`→解析树；
   `RequisitionHandoverService.clean` **只转 LocalDateTime**，Timestamp 原样透传。故
   `Summary/Demand/…` 等核心链的时间分量是 String，而 `PendingReceiptItem.deliveredAt/
   receiptDeadlineAt` 与 `ReceiptHandover` 两个时间分量保持 **Object 直通**（OpenLoanRef 先例，
   DATETIME 驱动形状不定不规范化）。
2. **detail 装配 = 18 列头 + 9 张子表全列**（demands/handovers/handoverEvents/decisions/
   allocations(+lock_version)/unfulfillable/ownership(+active_guard)/changes/followups）。
   全部键恒在（全局 JsonConfiguration inclusion=ALWAYS，显式 null 照写），本批核心链无新增
   条件键；条件键冻结样例复用 JAVA-09 已类型化的 `RequisitionMasterDataPort.CatalogChoice.
   organizationId`（employee 有键、其余 kind 无键），在 CatalogPage 判据中双态甄别。
   `Ownership.activeGuard`（TINYINT 生成列）保留 Object 直通。
3. **14 个幂等操作全部落 Detail**：requisition:create/revise/submit/change/cancel/decide/claim/
   allocate/unfulfillable/dispute/followup 与 handover:deliver/cancel/recovery/recover 存储的
   response_body_json 都是同一个 detail 树（get 出口形状）；handover:receive/refuse 单独存
   ReceiptResult 四键形状。replay/finish 按 TransferService 先例泛型化
   （`Class<T>` + `sealed Reply { long version(); }`，finish 的 resource_version 由
   `result.get("version")` 改 `result.version()`，值同源）。
4. **MySQL/驱动口径**：数值列统一 long（BIGINT/INT 文本归一）；JSON 列经 `readTree(raw.toString())`
   与旧 `readValue(raw, Object.class)` 序列化同树；字符串化 id 逐值复刻 toString 语义；
   `grant.expiresAt` 保留改前「重新计算 `clock.instant().plusSeconds(300)`」的表达式（与落库
   expires_at 可能相差毫秒，属既有行为，不修正）。

## 2. 改动清单与出口映射

主代码 4 改 1 增，测试 1 增，本记录 1 增：

| 文件 | 变化 |
|---|---|
| `requisition/RequisitionViews.java`（新增） | 17 个响应 record + 行映射工厂（summary/detail/demand/handover/handoverEvent/decision/allocation/unfulfillable/ownership/changeProposal/followup/inventoryAsset/pendingReceiptItem/receiptHandover）；`Reply` 密封接口（Detail/ReceiptResult 暴露 `version()`）；含 List 分量 record 紧凑构造器 `List.copyOf` 防御拷贝 |
| `requisition/RequisitionService.java` | 15 个出口类型化；新增 `getDetail(Actor,long)`（typed）；`get(Actor,long)` 保留 `Map<String,Object>` 签名，实现改为 `json.convertValue(getDetail(...))`（单源装配，形状由判据冻结）；删除 Map 版 `detail(id)`；`replay`/`finish` 改产/读 Detail；`clean`/`cleanRows` 保留为内部 DB 快照 payload（assetSnapshot、approved_scope_json、requirement_snapshot_json、followup source_reason_snapshot_json）与 get 兼容链服务；SQL 与查询顺序逐条未动 |
| `requisition/RequisitionHandoverService.java` | 6 个出口类型化；删除 `clean()`（全部投影移入 RequisitionViews）；`replay`/`finish` 泛型化（`Class<T extends Reply>`）；`requisitions.get` 三处调用点全部改 `getDetail`（deliver/warehouseAction 响应与 replay 纯校验）；其余内部行 Map（header/item/demand/one/targetReferenceSnapshot/legacyTargetSnapshot）与 ReferenceSnapshot、幂等指纹 Map 未动 |
| `requisition/RequisitionController.java` | 15 个端点返回类型化视图（get 端点改调 `service.getDetail`）；请求体 record、校验、路径、`Idempotency-Key`/`If-Match-Version` 头一字未动 |
| `requisition/RequisitionHandoverController.java` | 8 个端点返回类型化视图；`CacheControl.noStore()` 响应头保留 |
| `requisition/RequisitionWireShapeTest.java`（新增） | 15 条 wire 等价用例，见 §3 |

出口 → 类型对照：

| 旧 Map 键形（legacy 装配） | 新 record |
|---|---|
| catalog：`items/page/pageSize/employeeId/organizationId/canApplyShared`（Map.of） | `CatalogPage`（items 复用 masterdata `CatalogChoice`，organizationId 条件键不重造） |
| list：`items/total/page/pageSize`，items=clean(fams_requisition 18 列) | `Page<Summary>` |
| get 与全部幂等操作 body：clean 头 18 键 + `demands/handovers/handoverEvents/decisions/allocations/unfulfillable/ownership/changes/followups` 九列表 | `Detail implements Reply`（头 18 分量 + 9 个 List 分量；`Reply.version()` 承接 finish 的 resource_version） |
| inventory：`items/total/page/pageSize`，items=clean(7 列 asset) | `Page<InventoryAsset>` |
| handover pending：`items/total/page/pageSize`，items=handoverClean(a.id,a.request_id,r.request_no,a.asset_snapshot_json,a.version,h.delivered_at,h.receipt_deadline_at,h.destination_text) | `Page<PendingReceiptItem>`（deliveredAt/receiptDeadlineAt Object 直通） |
| grant：`grant/expiresAt/requestVersion/allocationVersion`（Map.of） | `Grant` |
| receipt：`allocationId/requestId/result/version`（LinkedHashMap） | `ReceiptResult implements Reply` |
| receiptDetail：`requestId/requestNo/requestType/expectedReturnDate/requestVersion/allocationId/allocationStatus/allocationVersion/asset/handover` | `ReceiptDetail`（handover 子对象 `ReceiptHandover`，时间 Object 直通） |
| 11 个幂等写操作原 `replay/finish` 的 `Map<String,Object>` | `replay(..., Class<T>)`/`finish(..., T extends Reply)` 泛型链 |

## 3. 判据：wire 等价测试

`RequisitionWireShapeTest` 把改前 `RequisitionService.clean`、`RequisitionHandoverService.clean`
与 `detail()` Map 装配、list/inventory/pending/grant/receipt/receiptDetail/catalog 的 Map 装配
逐字段冻结为 oracle，构造 JDBC 类型行（Long/Integer/String/Timestamp/LocalDateTime/
java.sql.Date/LocalDate/JSON 串/active_guard Integer）比对：

- 头行：Timestamp 主形态 + LocalDateTime/双 null 变体 + DATE 的 sql.Date/LocalDate 双形态；
- 9 张子表行逐一 ≡ legacyClean（含 allocation 的 approved_target_json/前替换分支、
  ownership 的 archived/activeGuard null 分支、demand 的 SHARED 分支、handoverEvent 的
  LocalDateTime 变体、inventory 的 brand/model null 变体）；
- Detail 全装配 ≡ legacy detail()（18+9 键全树）；
- **显式 null 键甄别**：`sourceRequestId`、`demands[0].preferredBrand`、
  `ownership[0].archivedAt` 断言键存在且为 null；
- **条件键双态甄别**：CatalogPage items 中 employee 行含 `organizationId`、category 行无该键
  （`has/assertFalse` 直接断言）；
- handover pending：LocalDateTime 转换形态 + **Timestamp 原样透传形态**（断言 Object 分量持有
  同一实例，防「顺手规范化成 String」退化）；
- **幂等存储形状旧 JSON 回放**（fams_idempotency_record.response_body_json 永久保留，跨版本
  必测）：
  1. requisition:* 与 handover deliver/warehouseAction 共用的 legacy detail 树 → `readValue(Detail)`
     → 再序列化比树；
  2. handover receive/refuse 的 legacy ReceiptResult 树 → `readValue(ReceiptResult)` → 等价；
  3. typed Detail/ReceiptResult 自身 序列化→反序列化→再序列化 恒等；
  4. `get` 兼容包装 `convertValue(Detail→Map)` ≡ legacy detail 树（ReassignmentService 消费面）。

**单元 oracle 边界（JAVA-01 教训沿用）**：只证明「行→视图→存储」序列化等价与回放等价。真实
HTTP 运行时用例（`auth/RequisitionHttpTest`、`auth/HandoverHttpTest`、
`auth/RequisitionUpgradeRuntimeCase`、`auth/RequisitionChangeUpgradeRuntimeCase`）需要容器
MySQL/Testcontainers，本批环境跑不动，按计划由主代理合并门禁实跑，此处登记为边界。

## 4. 实施代理自测（非产品门禁）

- 定向判据：`JAVA_HOME=graalvm-jdk-21.0.7 ./mvnw -pl backend -Dtest='RequisitionWireShapeTest' -DfailIfNoTests=false test`
  → **Tests run: 15, Failures: 0, Errors: 0**。
- 邻域纯单元回归（com/fams/requisition 下全部既有一元测 + 蓝本）：
  `-Dtest='SmallPortsWireShapeTest,BorrowDateWireShapeTest,ProxyReceiptPayloadsTest,ProxyConfirmationWireShapeTest,TransferWireShapeTest'`
  → **Tests run: 38, Failures: 0, Errors: 0**。
- 全仓编译：`./mvnw -pl backend -q test-compile` → exit=0。
- Checkstyle：`./mvnw -pl backend -Pquality checkstyle:check@check-java-conventions` →
  **exit=0（BUILD SUCCESS）**，新文件末尾换行合规。
- 未跑：SpotBugs/JaCoCo/真实 HTTP 运行时用例与 `scripts/verify-d6-03a.py` 全量门禁——按任务
  红线留给主代理合并门禁；EI_EXPOSE_REP 风险已按先例在构造侧消解（List 分量拷贝、Object 分量
  仅承载不可变 JDBC 标量）。

## 5. 影响分析（GitNexus）

编辑前执行 `impact -d upstream --repo fams-manage`（Node 24 前缀）：

- `RequisitionService`/`RequisitionHandoverService`/`RequisitionController`/
  `RequisitionHandoverController`（类级）：全部 **LOW**（上游 0～3，均在 requisition 包内：
  RequisitionReassignmentService、RequisitionTargetChangePort、RequisitionProxyReceiptPort、两控制器）。
- 方法级 `get`（HIGH 61）、`detail`/`clean`/`replay`/`finish`（MEDIUM 22～60）全部为**同名伪边**：
  索引把 loss/asset/repair 等域各自独立的 `get/detail/clean/replay/finish` 聚进同一候选名
  （`impact get` 首查即返回 20 个同名候选、目录含 Repair/Loss）。grep 核实真实消费面：
  `requisitions.get` 仅 5 处（HandoverService 3 + ReassignmentService 2，全部本批或已登记）；
  `detail/clean/replay/finish` 在两个服务内均为 private（编译器封闭）；
  `RequisitionHandoverService` 的包外使用者（Proxy/TargetChange Port）只消费早已类型化的
  proxySnapshot/proxyReceipt/targetChangeContext/validateChangedTarget/applyChangedTarget，
  未触本批出口。
- `detect-changes`（工作树 vs HEAD，含并行批改动）：15 文件 86 符号、risk low、0 执行流——
  本批 4+2 文件，其余为并行 masterdata/notice 代理的 WIP，不属于本批 diff。
- 索引不含并行批新符号属正常；`RequisitionViews` 为本批新符号，索引尚未收录。

## 6. 偏差与遗留

1. **裁决点：RequisitionService.get 形状与 ReassignmentService 兼容**。
   `RequisitionReassignmentService`（禁改文件）在 `declareUnavailable`/`reassign` 两处
   `var result = requisitions.get(actor, id);` 后要求 result 可直接赋给 `Map<String, Object>`
   返回并 `json.writeValueAsString` 落幂等表——record 组件访问与 Map 赋值不可两全。最小方案：
   `get` 保留 `Map<String,Object>` 签名，实现改为对 `getDetail` 的 `convertValue`（单一装配源，
   永不漂移），其落库回执与 Detail 回执逐键等值（判据 §3-4 冻结）。**ReassignmentService 的
   inspect/reassign/declareUnavailable 出口类型化留待 14b 后续子批**，届时删除本兼容包装。
   **收口注记：JAVA-14c 已删除该包装并切换全部调用点（grep `requisitions.get(` 全仓归零）；
   本批测试第 78 行过期注释亦由主代理在收口时归位。**
2. **排除清单（保留 Map/不动，按规格）**：内部行 Map（header/item/demand/demands/one/
   `refreshed` 中间态）、`assetSnapshot` 与 `cleanRows`/`clean` 的 DB 存储快照 payload
   （approved_scope_json、requirement_snapshot_json、source_reason_snapshot_json）、审计
   detail_json、幂等指纹 `digest/fingerprint` 输入、`targetReferenceSnapshot`/
   `legacyTargetSnapshot`/`ReferenceSnapshot`（JAVA-09 已冻结的存储形状）——一律未动。
3. SQL 文本、参数顺序、FOR UPDATE 锁顺序、`one()` 语义、异常码、状态码、路径、`grant` 的
   noStore 头零改动；`finish` 落库的 http_status/`resource_type='REQUISITION'` 未动。
4. **replay 校验调用的类型化**：handover `replay()` 中 `requisitions.get(actor, request)`
   纯校验调用（结果丢弃）改为 `getDetail`——DB 读序列（revalidate→requireVisible→detail 九查）
   与改前完全一致，仅返回值换型；该处「只校验可读性却装配整棵 detail」是本批前既有行为，
   未在结构批顺手优化，留作观察项。
5. 旧幂等回执经 record 再序列化键序归一为声明序（旧 Map.of 键序本就随机、Map 树无契约），
   语义等价；跨版本回放由判据 §3 覆盖。
6. `RequisitionHandoverService.clean` 与 `RequisitionService.clean` 两条投影在测试中分别冻结
   （legacyClean/legacyHandoverClean），避免「统一顺手转 ISO」造成 handover pending 时间形状
   回归——这是本批最大的退化风险点，已用 Object 直通 + 实例同一断言锁死。
7. 任务书所述「requisitions.get 三处纯校验调用」实为 **1 处纯校验（replay）+ 2 处响应装配
   （deliver/warehouseAction）**，与代码逐行核对后按实际执行；ReassignmentService 2 处消费
   与任务书一致。
8. 契约/OpenAPI/前端：零改动（无 API 形状变化，无接口文档改动）。

## 7. 下一步

- 主代理合并门禁：全量 Java 质量序列 + 真实 HTTP 运行时用例
  （RequisitionHttpTest/HandoverHttpTest/两条 Upgrade 用例）实跑。
- 14b 后续子批：RequisitionReassignmentService/Unavailability/ProxyConfirmation 残留出口
  （若 14b2/14b3 未覆盖）与 `get` 兼容包装删除；requisition 域内部投影链进一步 record 化
  （可选，非出口判据）。

## 主代理产品门禁（波次三合并实跑）

主代理在波次三合并树固定快照副本（rsync 排除并行会话 WIP `com/fams/local`；快照内容经核实含本波
三个新 Views 文件与五个新 WireShape 套件）实跑 `verify-d6-03a.py` 完整 Java 序列：**JAVA01_VERIFY_PASS**，
83 步 exit=0，Checkstyle/SpotBugs 0，覆盖率 LINE 93.22%、BRANCH 75.61%；日志内 74 个 surefire 阶段
汇总 401 项 0 失败 0 错误。门禁自身报告的 tests=555 与波次二同数，为消除口径歧义，五个新套件已在
该副本内直接复跑 **45/45 绿**。主树合并判据：25 个 WireShape 套件 202/202。
详见[波次证据](evidence/JAVA-14-java-gate.txt)。
