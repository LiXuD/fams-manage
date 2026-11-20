# JAVA-02 · loan 域响应与域内 Port 类型化

> 用户指定的后端结构批（JAVA-01 transfer 打样后的第二域），不进入 D6-01～D6-27 台账、不占用其状态。
> 日期：2026-11-21 · 分支：main · 基线：`764a235`（JAVA-01 提交）
> 状态：主代理审计通过，产品门禁完整 Java 序列通过，已收口提交。

## 1. 背景与红线

完全复刻 JAVA-01 标准：HTTP 响应 + 域内 Port 类型化；内部行 Map、审计 payload、幂等指纹
digest 输入保留 Map；契约 `contracts/fams-core-v1.yaml` 与 `packages/api-types` 零改动。
loan 域与 transfer 的关键差异（实施前逐字核对改前代码冻结为 oracle）：

1. **两套投影助手并存**：`LoanService.clean()` 跳过 `parameter_snapshot_json`/`pending_target`，
   snake→camel，LocalDateTime/Timestamp→ISO instant，**LocalDate/java.sql.Date→toString()
   日期字符串**（transfer 的 wire() 无此分支），`id`/`*_id`/`proposed_by`/`decided_by` 的
   Number→字符串。`AssetReturnService.view()` 跳过 `parameter_snapshot_json`/`active_asset_id`，
   id 规则为 `id`/`*_id`/`*_by`，**无 DATE 分支**（fams_asset_return/fams_return_followup/
   目录与资产子查询均无 DATE 列，核实于 V1/V2/V24/V28/V49 迁移）。两者不可混用。
2. **生成列上线**：`fams_loan.open_asset_id`（V22 STORED 生成列）出现在 `SELECT l.*`/`SELECT *`
   中，clean 后为 `openAssetId`（可空字符串 ID）；`fams_asset_return.active_asset_id` 被 view
   显式跳过。契约 Loan schema 未列 openAssetId，但 additionalProperties 放行，wire 必须保留。
3. **条件键与显式 null 键并存**：`lossRecovery`/`returnLossReport`/`recoveryReturnStatus`/
   `missingLossLockVersion`/`canRetryMissingLoss`/`unavailableReason` 仅非 null 才 put
   （NON_NULL 复刻）；`lockVersion`（get 详情）与 LOST_REPORT 的 `lossReport` 总是 put、
   可为显式 null（默认包含保持）。
4. wire 等价比较口径与 JAVA-01 相同：各自序列化 JSON 文本再解析比树（归一整型宽度，
   键序不参与；键存在性、显式 null、值形状全部参与）。

## 2. 改动清单

主代码 9 改 1 增（+125/−186），测试 1 增：

| 文件 | 变化 |
|---|---|
| `loan/LoanViews.java`（新增） | 18 个响应 record + 3 个密封接口（`Reply`/`ReturnCatalogChoice`/`Followup`）+ 行映射工厂；含 List 分量的 5 个 record 紧凑构造器 `List.copyOf` 防御拷贝 |
| `loan/LoanService.java` | 6 个出口类型化；删除 `clean()`/`loanView()`（移入 LoanViews 具名映射，overdue/lostLiability 计算含短路语义原样保留）；`replay`/`finish` 泛型化（`Class<T>`、`Reply.id()/version()`）；`eligibleRequests` 在 loan 侧把 `BorrowRequestDatePort` 的行 Map 边界转换为 record，port 信封不动 |
| `loan/AssetReturnService.java` | 10 个出口类型化；删除 `view()`；`followupView` 改产出密封 `Followup`（REPAIR/LOST_REPORT 分支语义逐一对应）；`replay` 泛型化，`finish` 返回 `AssetReturn`（resource_id 仍传 long id，resource_version 取 `result.version()`） |
| `loan/AssetReturnReassignmentService.java` | `inspect` 产出 `ReassignmentSnapshot`（checks() 内部 Map 不动，边界转换）；`declareUnavailable`/`reassign` 返回 `AssetReturnDetail`，幂等回放改 `Class` 反序列化（原 `Map.class` 裸类型）；删除随之失效的 `@SuppressWarnings("unchecked")` |
| `loan/LoanController.java`、`loan/AssetReturnController.java`、`loan/AssetReturnReassignmentController.java`、`loan/AssetReturnUnavailabilityController.java` | 端点返回类型化视图；请求体 record 与校验一字未动 |
| `loan/LossReturnPort.java` | `create` 返回 `LoanViews.AssetReturn`（LOW，仅 LossService 消费） |
| `loss/LossService.java` | 唯一跨域消费点 `linked.get("id")` → `linked.id()`（1 行，对照 JAVA-01 先例；写入 recovery/execution 快照的 returnId 仍为同值字符串） |
| `loan/LoanWireShapeTest.java`（新增） | 12 条 wire 等价用例，见 §3 |

出口 → record 对照：

| 出口 | record |
|---|---|
| LoanService.list | `Page<Loan>` |
| LoanService.eligibleRequests | `Page<BorrowRequestChoice>` |
| LoanService.get | `LoanDetail`（Loan 全字段 + `List<Extension>` + `List<Reminder>`） |
| LoanService.extensions | `Page<Extension>` |
| LoanService.propose / proposeBeforeReceipt / decide | `Extension`（implements Reply） |
| AssetReturnService.list | `Page<AssetReturn>` |
| AssetReturnService.catalog | `ReturnCatalogPage`（密封 `ReturnCatalogChoice`：`WarehouseChoice`/`LocationChoice`） |
| AssetReturnService.eligible | `Page<ReturnAssetChoice>` |
| AssetReturnService.get | `AssetReturnDetail`（implements Reply；`ReturnAsset asset`、显式可空 `lockVersion`、`canReportMissing`、密封 `Followup`：`RepairFollowup`/`LostReportFollowup`） |
| AssetReturnService.propose / proposeLossRecovery / resubmitLossRecovery / decide / claim / inspect / retryMissingLoss | `AssetReturn`（implements Reply） |
| AssetReturnReassignmentService.inspect | `ReassignmentSnapshot`（`ReassignmentItem`/`Operator`，NON_NULL unavailableReason） |
| AssetReturnReassignmentService.declareUnavailable / reassign | `AssetReturnDetail` |
| LossReturnPort.create | `AssetReturn` |

`Reply` 密封接口 permits `Extension`/`AssetReturn`/`AssetReturnDetail`——恰为三类会写入
幂等记录并回放的响应；`LoanDetail` 不参与幂等存储，不 implements Reply。

## 3. 判据：wire 等价测试

`LoanWireShapeTest` 把改前 `LoanService.clean()`、`LoanService.loanView()`（含 today()/date()
助手）与 `AssetReturnService.view()`（含 linkedLoss）**逐字冻结为 oracle**，构造 JDBC 类型行
（Long/Integer/String/Timestamp/LocalDateTime/LocalDate/java.sql.Date/Boolean/JSON 串）比对：

- loan 行：OPEN 逾期、RETURNED 闭户（openAssetId null）、LOST 责任、SCRAPPED、INITIAL_IMPORT
  （initial_sheet_row Integer 透传）五态 + LocalDate/java.sql.Date 双驱动形态；
- extension 行：PENDING/已决/收前（request_id 分支）+ pending_target 生成列跳过 + sql.Date 变体；
- reminder、borrow-request（port 边界）、return 行（lossRecovery 条件键、returnLossReport
  条件键、completed 全字段）、followup（REPAIR 无 loss 键；LOST_REPORT lossReport 显式 null
  与全条件键组合）、目录 warehouse/location（含 warehouse_id 显式 null）、eligible 资产、
  reassignment 快照（unavailableReason 有无）；
- 页装配：LoanPage/ExtensionPage/BorrowRequestChoicePage/AssetReturnPage/ReturnCatalogPage
  （**无 total 键**）/ReturnAssetChoicePage 与改前 Map.of 装配比树；
- 幂等：三类 Reply 序列化→反序列化→再序列化比树；**改前形状存储 JSON（oracle 生成）反序列化
  为新 record 并等价再序列化**——覆盖 loan propose/decide、return finish、reassign/unavailable
  三条存储链的 24 小时窗口跨版本回放。

**Followup 多态反序列化（本批新判据）**：`AssetReturnDetail` 被 reassign/declareUnavailable
存入幂等记录，其 `List<Followup>` 必须可回放。密封接口用
`@JsonTypeInfo(use=NAME, include=EXISTING_PROPERTY, property="businessType", visible=true)` +
`@JsonSubTypes(REPAIR/LOST_REPORT)`——businessType 本就是 wire 上的真实键且取值被 DB CHECK
约束为这两个名字，故**零新增键、零形状变化**；旧存储 JSON 回放用例实证通过。
（DEDUCTION 方案被否：RepairFollowup 字段集是 LostReportFollowup 的子集，无独有属性可推断。）

单元 oracle 边界沿用 JAVA-01 教训：只证明「行→视图」序列化等价；真实 HTTP 运行时用例
（HandoverHttpTest、LossHttpRuntimeCase、RepairReturnHttpRuntimeCase 等）由主代理门禁实跑。
本批无跨边界快照比较语义（loan 域无 proxy 快照直比路径），lossRecovery 以 JsonNode 原样透传。

## 4. 门禁证据

环境：JDK 21.0.7（GraalVM，quality enforcer 强制 [21,22)）、Node v24.18.0
（显式 `FAMS_TEST_NODE_EXECUTABLE`）、隔离 MySQL 8.4.11 冻结 digest。驱动复用
`scripts/verify-d6-03a.py` 的 `verify_java_quality` 完整序列（环境补丁经函数
`__globals__` 注入——`runpy.run_path` 返回 globals 副本，直接改返回 dict 不生效，
JAVA-01 收口时已踩坑并修复）。

- 全部升级/运行时用例 + 阻塞质量共 **83 步全部 exit=0**；surefire 聚合
  **738 项测试 0 失败 0 错误**（JAVA-01 为 726，净增本批 12 条判据）。
- `verify_java_reports` 断言通过：Checkstyle 0 违规、SpotBugs 0 发现、必需报告齐全。
- 覆盖率：**LINE 27051/29069（93.06%）、BRANCH 9823/12989（75.63%）**（门槛 75%）。
- 单元判据：`LoanWireShapeTest` 12/12、`TransferWireShapeTest` 17/17、
  `TransferCalendarTest` 1/1（主代理自跑复核）。
- 主代理审计：逐文件 diff 核对列覆盖（fams_loan/fams_loan_extension/fams_loan_reminder/
  fams_asset_return V24+V28/fams_return_followup V24+V49）、条件键与显式 null 语义、
  `linkedLoss`≡`has("lossRecovery")`、checks() 六键、`resource_id` BIGINT 列字符串
  coerce 同值（transfer 先例）、审计/指纹/内部行 Map 未动、排除项遵守。

实施代理已跑（非产品门禁）：

- 定向单测：`JAVA_HOME=graalvm-jdk-21.0.7 ./mvnw -pl backend -Dtest='LoanWireShapeTest,Loan*Test,AssetReturn*Test' -DfailIfNoTests=false test`
  → **Tests run: 12, Failures: 0, Errors: 0**（LoanWireShapeTest 12/12；仓库无其他 Loan*Test/AssetReturn*Test 类）。
- 全仓编译：`./mvnw -pl backend -q test-compile` → exit=0。
- Checkstyle（项目规则集）：`./mvnw -pl backend -Pquality checkstyle:check@check-java-conventions`
  → exit=0（首轮抓出两个新文件缺末尾换行，已修复复跑）。注意：裸 `checkstyle:check` 会落回
  sun_checks 默认规则集（全仓 2.7 万条历史违规），**不是**项目口径，必须带 execution id。

未在本批重跑：TS 四道、依赖审计、应用重启恢复与 D6-02C 契约门禁 python 全量——按项目
惯例在固定候选统一重跑。本批契约零改动，ContractProbeHttpTest 已随阻塞质量通过。
SpotBugs 风险点已按 JAVA-01 先例预防：含 List 分量 record 全部紧凑构造器
`List.copyOf`（Page/LoanDetail/AssetReturnDetail/ReturnCatalogPage/ReassignmentSnapshot）。

## 5. 影响分析（GitNexus）

编辑前按 AGENTS 要求执行 `impact -d upstream`（Node 24 前缀 + --repo）：

- `LoanService`：LOW，1 直接依赖（LoanController）。
- `AssetReturnService`：LOW，3 直接依赖（AssetReturnController、AssetReturnReassignmentService、LossReturnPort）。
- `AssetReturnReassignmentService`：LOW，2（两个控制器）。
- 四个控制器：LOW，0 上游。
- `LossReturnPort`：LOW，1（LossService）；`LossService`：LOW，4（均在 loss 包内）。
- 无 HIGH/CRITICAL。

`detect-changes`：24 文件 78 符号（含用户未提交的小程序 14 文件与 AGENTS.md，不属本批）；
受影响执行流 10 条，全部为 RecoverToReturn/ExecuteRecoveryToReturn 链——恰为本批唯一跨域
消费点 `linked.id()`，与 JAVA-01 的 loss→transfer 链同构，由 LossHttpRuntimeCase 与 loss
升级用例覆盖（待门禁实跑）。聚合 risk 标 high 来自未提交小程序改动混入统计，本批后端符号
impact 全 LOW。

## 6. 偏差与遗留

1. **排除清单（保留 Map/不动，按规格）**：
   - `LoanRepairPort`/`LoanReturnRepairPort`：MEDIUM 跨域深耦合（repair/scrap/inventory 15/5
     影响，Context 被 RepairService/ScrapReviewService 序列化进 loan_snapshot_json 等存储列），规格明令排除。
   - `BorrowRequestDatePort`（business 包跨域接口）：接口保留 Map；`eligibleRequests` 出口在
     loan 侧边界转换为 `BorrowRequestChoice`（对照 JAVA-01 信封先例）。`outstanding()/snapshot()`
     返回的内部行 Map 供 propose/decide 业务判定，属内部行 Map，不动。
   - 内部行 Map（`loan()/one()/target()/request()/processing()/checks()`）、审计 payload
     （含 reassign details 的 items）、幂等指纹 digest 输入：一律未动。
   - `apps/web`、`apps/miniprogram`、`contracts/`、`packages/api-types`：零改动。
2. **LoanReceiptPort/LoanInitialPort/LoanLossPort 无需改动**：impact 前读码核实三者签名早已
   类型化（基本类型/LocalDate/Instant/UUID/`OpenBorrowing` record），无任何 Map 返回或参数，
   不属「保留 Map 遗留」，是已完成态。`LoanReminderService` 无 HTTP 出口（内部 refresh），不动。
3. **存储快照形状**：本批无新增写入 DB JSON 列的 record（lossRecovery 快照仍由
   `json.valueToTree(source)` 生成，Source record 未动；recovery/execution 快照的 returnId
   仍为字符串），故无 @JsonProperty snake_case 需求。
4. `initial_sheet_row`（INT）record 分量用 Long：clean 原样透传 Integer，文本往返归一整型
   宽度，wire 等价（与 JAVA-01 totalQuantity int→long 同口径）。
5. `LoanService.finish` 的 resource_id 由 long id 改传 `result.id()`（String，同值），对照
   TransferService.finish 先例；resource_version 语义不变。
6. 观察项（非缺陷、未改）：`proposeLossRecovery` 的 `key` 参数改前即未使用（幂等由 LossService
   侧 remember() 承担），签名保持原样。
7. 旧幂等记录回放经 record 再序列化键序归一为声明序（旧 Map.of/LinkedHashMap 键序本非契约），
   语义等价，24 小时窗口后自然消失（同 JAVA-01 §6.5）。
8. 工作树中用户未提交的小程序改动（14 文件）与 AGENTS.md 改动不属本批，未触碰。
9. **裁决点：无。** 未遇到形状不可判定、HIGH/CRITICAL impact、需改排除接口或疑似改前缺陷。

## 7. 下一步

按 JAVA-01 §7 建议继续小域批次（scrap/targetchange），大域拆子批；
`LoanRepairPort`/`LoanReturnRepairPort` 与通用信封接口（`ProxyReceiptPort`/
`TargetChangePort`/`BorrowRequestDatePort`）的类型化需跨域同批，另批裁决。