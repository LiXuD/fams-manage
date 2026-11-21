# JAVA-08 · loan 深耦合 Port（LoanRepairPort / LoanReturnRepairPort）类型化

> 用户指定的后端结构批（JAVA-01 transfer 打样、JAVA-02 loan、JAVA-03 scrap、JAVA-04 信封、JAVA-05/06/07 之后），
> 不进入 D6-01～D6-27 台账、不占用其状态。JAVA-02 §6.1/JAVA-04 §7/JAVA-06 §7 登记的跨域遗留收口批。
> 日期：2026-11-21 · 分支：main · 基线：`e1eef65`（JAVA-07 提交）
> 状态：主代理审计通过，与 JAVA-09 合并门禁通过，已收口提交。

## 1. 背景与红线

两 Port 是 JAVA-02 明令排除的 MEDIUM 跨域深耦合遗留（repair/scrap 15/5 影响）。实施前逐字读码
得出关键事实：**两 Port 的 public 签名中仅剩 1 个 Map 出口**——`LoanReturnRepairPort.list`；
`LoanRepairPort` 五方法与 `pending/adopt/progress` 早已类型化（`Context` record + 基本类型）。
本批按规格把它类型化并冻结全部存储/wire 形状，红线复刻 JAVA-01～06：

1. **存储形状最高红线**：`LoanRepairPort.Context` 被调用方逐字序列化进
   `fams_repair_request.loan_snapshot_json`（RepairService.submit 568/607 行、RepairInitialPort 72 行）
   与 scrap `repairEvidence.borrowing`（ScrapInitialPort 61/234 行），并被 RepairService.inPlace/originalLoan
   与 ScrapReviewService.repairBorrowing `readValue(..., Context.class)` 回放比对。本批对该 record
   **一字未动**（camelCase 键形与 LocalDate 形态即现役存储形状），只在判据中冻结。
2. `list` 的 items 是改前 `wire()` 投影：**snake_case 原始列键、`id`/`*_id` 非空值 toString、
   version 保持数字、11 键恒在（显式 null 保留）**。类型化 record 用 `@JsonProperty` 逐键冻结
   （AssetTransferPort.Snapshot 先例）；信封恰为 `items/total/page/pageSize`（旧 `Map.of`），
   复用 `LoanViews.Page`（键形一致，JAVA-02 先例）。
3. 比较口径同 JAVA-01～06：各自序列化 JSON 文本再解析比树（整型宽度归一、键序不参与；
   键存在性、显式 null、值形状全部参与）。
4. JDBC 原始类型核实 V24/V49：`fams_return_followup` 十一列中 `version` BIGINT NOT NULL → `long`；
   其余 id 列经 wire 本就是字符串分量；status/reason VARCHAR → String；无 DATE/DATETIME 列被选出。
5. **不改任何 SQL**；两 Port 方法语义、锁顺序（`FOR UPDATE` 子查询）、异常码
   （REPAIR_LOAN_CHANGED / REPAIR_BORROWING_EXPIRED_INBOUND_REQUIRED / REPAIR_RETURN_SOURCE_CHANGED）
   一字未动。调用方业务逻辑与存储不动，只改消费方式；无 record→Map 回退。
6. 契约 `contracts/fams-core-v1.yaml`、`packages/api-types`、`apps/web`、`apps/miniprogram` 零改动。

## 2. Map 签名盘点与改动清单

主代码 4 改 0 增，测试 1 增（+47/−22，不含新增测试文件）：

| 文件 | 变化 |
|---|---|
| `loan/LoanReturnRepairPort.java` | `list` 返回 `LoanViews.Page<LoanViews.RepairFollowupSource>`；删除私有 `wire()`（逐字移入 LoanViews.repairFollowupSource 具名映射）与失效的 LinkedHashMap 导入。**pending/adopt/progress 签名原样**（早已类型化）；`linked()`/`number()`/`nullable()` 内部行 Map 不动（深度边界） |
| `loan/LoanViews.java` | 新增 `RepairFollowupSource` record（11 分量，@JsonProperty 冻结 snake_case 键形）+ 包内 `repairFollowupSource(row)` 工厂（逐字复刻 wire() 规则）；补 JsonProperty 导入。其余 record/工厂一字未动 |
| `loan/LoanRepairPort.java` | **零改动**（盘点结论：无 Map 签名；open/requireUnchanged/requireIdentity/requireContinuable/closeForRepairInbound 均已是 Context/基本类型；open() 内 db.queryForList 行 Map 属内部行 Map） |
| `repair/RepairService.java` | 仅 `returnSources` 返回类型改 `LoanViews.Page<LoanViews.RepairFollowupSource>` + 1 行 import（3 行 diff，hunk 核实无其他改动） |
| `repair/RepairController.java` | 仅 `/return-sources` GET 端点返回类型同步 + 1 行 import |
| `loan/LoanDeepWireShapeTest.java`（新增） | 5 条判据，见 §3 |

调用方清单以实际 grep 为准（规格候选名部分与现役代码不符，逐项核实）：

| 消费文件 | 消费内容 | 处置 |
|---|---|---|
| `repair/RepairService` | 唯一 `list` 消费（returnSources）；`pending/adopt/progress`；`loans.open/requireIdentity/requireUnchanged/requireContinuable/closeForRepairInbound`；`readValue(loan_snapshot_json, Context)`；`loanWire/returnSourceWire`（Context→repair 自有投影，服务 source_context_json 存储与 reassignmentSnapshot——调用方内部形状，非本 Port 契约，不动） | 仅 returnSources 签名 |
| `repair/RepairController` | returnSources 端点 | 返回类型同步 |
| `repair/RepairInitialPort` | `loans.open` → `writeValueAsString` 入 loan_snapshot_json | 零改动（已类型化，存储形状冻结于判据） |
| `scrap/ScrapInitialPort` | `loans.open` 入 proof（repairEvidence.borrowing 序列化存储）与 loanId/loanVersion 提取 | 零改动 |
| `scrap/ScrapReviewService` | `readValue(borrowing, Context.class)` 回放 | 零改动（回放形状冻结于判据） |
| `repair/RepairProxyReceiptPort`、`repair/InventoryRepairPort`（注：在 repair 包，规格写作 inventory）、`repair/RepairErrorHandler`、`loss/LossErrorHandler`、`scrap/ScrapController` | 仅 `LoanRepairPort.Failure` 捕获/翻译 | 零改动 |
| `scrap/ScrapService`、`repair/RepairFactoryService`、`inventory/InventoryDifferenceService`、`inventory/InventoryLateDamageService`、`inventory/InventoryRepairPhotoReadService`、`loss/LossService` | grep 核实**不引用**这两 Port（其 repairs.progress 等经其他 Port） | 无需改动（规格候选名与实际不符，如实登记） |

## 3. 判据：LoanDeepWireShapeTest（5/5）

`loan/LoanDeepWireShapeTest` 把改前形状逐字冻结为参照 oracle：

1. **RepairFollowupSource ≡ 冻结 wire()**：PENDING（loan_id 在、linked/last/repair_status 显式
   null）、ACCEPTED、CLOSED 三态行 + Integer/Long 整型宽度变体行，与改前 `wire()` 输出比树。
2. **11 键显式 null 全保持**：与手写字面 JSON 树精确相等——键集恰为 SELECT 的 11 列
   （不多不少）、id 列是字符串、version 是数字、null 键恒在。
3. **Page 信封 ≡ 改前 `Map.of("items",…,"total",…,"page",…,"pageSize",…)` 装配**。
4. **Context 存储形状冻结**：`writeValueAsString(new Context(…))` 与期望树
   `{"id":8,"version":0,"borrowerEmployeeId":3,"organizationId":1,"expectedReturnDate":"2026-11-10"}`
   相等；再冻结 `{"borrowing":…}` proof 嵌套形态（ScrapInitialPort 路径）。
5. **Context 回放等值**：存储串 readValue→record 与原值 `equals`（RepairService.inPlace 的
   requireUnchanged 与 ScrapReviewService 的 Objects.equals 判定依赖此语义），且
   序列化→反序列化→再序列化树相等；含 JsonNode.toString 二次入口（ScrapReviewService 形态）。

单元 oracle 边界沿用 JAVA-01 教训：只证明「行/装配→视图」与「record→存储 JSON」序列化等价；
`GET /api/v1/repairs/return-sources` 真实 HTTP 运行时链由主代理门禁实跑。

## 4. 门禁证据

实施代理已跑（非产品门禁）：

- 定向单测：`JAVA_HOME=graalvm-jdk-21.0.7 ./mvnw -pl backend
  -Dtest='LoanDeepWireShapeTest,LoanWireShapeTest,TransferWireShapeTest,ScrapWireShapeTest'
  -DfailIfNoTests=false test` → **Tests run: 45, Failures: 0, Errors: 0**
  （LoanDeep 5、LoanWire 11、Transfer 17、Scrap 12）。共享工作树本批期间未被 JAVA-09 在途
  改动阻断，无需隔离副本。
- 全仓编译：`./mvnw -pl backend -q test-compile` → exit=0。
- Checkstyle 项目口径：`./mvnw -pl backend -Pquality checkstyle:check@check-java-conventions`
  → exit=0（含新文件末尾换行）。

产品门禁完整 Java 序列（与 JAVA-09 合并树单次实跑，2026-11-21）：
**83 步全部 exit=0**，surefire 聚合 **790 项测试 0 失败 0 错误**，
`verify_java_reports` 通过（Checkstyle 0、SpotBugs 0），覆盖率
**LINE 27260/29283（93.09%）、BRANCH 9873/13039（75.72%）**。
主代理合并树复跑单元判据 82/82。证据：
[JAVA-08-09-java-gate.txt](evidence/JAVA-08-09-java-gate.txt)。

## 5. 影响分析（GitNexus）与裁决点

编辑前按 AGENTS 执行 `impact -d upstream`（Node 24 前缀 + `--repo`）：

- `LoanReturnRepairPort.list`（方法，-f 消歧）：**LOW 2**——闭包 = RepairService.returnSources →
  RepairController.returnSources，全部本批纳入。
- `LoanReturnRepairPort`（类）：LOW 5——直接 = RepairService 注入 2 条；depth-2 三条 scrap
  IMPORTS 为 LoanRepairPort 同名文件伪边（源码 grep：scrap 文件不导入 LoanReturnRepairPort）。
- `LoanRepairPort`（类）：MEDIUM 15（与 JAVA-02 登记同数）——本批**零编辑**，仅作盘点依据。
- `returnSources`（两处）：LOW 1 / LOW 0。
- 无 HIGH/CRITICAL。`detect-changes`：19 文件 30 符号、0 执行流、risk low——含并行代理与用户
  未提交的小程序改动、AGENTS.md 自动更新（均不属本批）。本批 in-scope diff 经 `git diff --stat`
  核实仅 §2 表列 4 改 + 1 新增测试。

**裁决点：无。** 未遇到形状不可判定、HIGH/CRITICAL 外溢、与 JAVA-09 并行代理的文件冲突
（RepairService/RepairController 不在其纳入集，diff hunk 核实无交叉）。

## 6. 偏差与遗留

1. **排除清单（一个文件未碰，按规格）**：`LossRepairPort`、`RepairInitialPort`、
   `ScrapInitialPort`、`LossInitialPort`、`LossScrapSourcePort`、`InventoryLateEvidencePort`、
   `FactoryReplacementInboundPort`（JAVA-10 批候选）；asset 包一切（`AssetRepairPort` 等）；
   `masterdata/RequisitionMasterDataPort`、`auth/InventoryExecutorPort`、
   `asset/AssetInventoryReadPort` 及 requisition 两 Service、inventory
   Finding/Inventory/Observation 三服务（JAVA-09 并行代理）；内部行 Map（`linked()`/`open()` 的
   queryForList 行、审计 payload、幂等指纹 digest 输入）；`apps/web`、`apps/miniprogram`、
   `contracts/`、`packages/api-types`；工作树用户未提交的小程序改动与 AGENTS.md。
2. **两 Port 实际 Map 面比规格预估小**：规格候选的 10 个调用方文件中 6 个经 grep 证实不引用
   本批 Port；`InventoryRepairPort` 实际位于 `repair` 包。如实按实际 grep 执行。
3. **Context 不改键形**：`LoanRepairPort.Context` 存储形状是 camelCase（非 snake_case）——
   这是改前既有事实（写入方与回放方同用 record 直序列化），本批判据冻结之，不"顺手"改形。
4. `RepairService.loanWire/returnSourceWire`（Context→repair 自有 Map 投影，服务
   `source_context_json` 存储与 reassignmentSnapshot 组装）与 `ProxyViews.RepairSnapshot.loan`
   原始 Map 分量：属调用方内部/未改信封形状（JAVA-04 §6.4 登记的深度边界），保持原样；
   其收敛在信封后续批一并裁决。
5. `LoanReturnRepairPort.Context`（pending 返回）从不被序列化（adopt/progress 只在内存消费），
   无存储形状需求；`pending` 内 `SELECT f.*` 行 Map 属内部行 Map。
6. 前端消费 `return-sources` 的键形零变化（snake_case items 原样），契约 additionalProperties
   放行路径不变，无需动 `packages/api-types`。

## 7. 下一步

JAVA-02/04/06 登记的跨域 Port 遗留就此收口（loan↔repair/scrap/inventory 深耦合 Port 已全部
类型化或无 Map 面）。建议主代理：在本批 + JAVA-09 合并候选上实跑产品门禁完整 Java 序列
（补 §4 留空），并把 `LossRepairPort`/`RepairInitialPort`/`ScrapInitialPort`/`LossInitialPort`/
`LossScrapSourcePort`/`InventoryLateEvidencePort`/`FactoryReplacementInboundPort` 与 asset 包
`AssetRepairPort` 等收进 JAVA-10 批。
