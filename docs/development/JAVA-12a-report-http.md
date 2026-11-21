# JAVA-12a · report 域 HTTP 出口完成态核证与防退化判据（已完成态登记批）

> 用户指定的后端结构批（HTTP 出口波次 wave12 并行批之一），不进入 D6-01～D6-27 台账、不占用其状态。
> 日期：2026-11-22 · 分支：main · 基线：`2fe2880`（JAVA-11c 提交）
> 状态：主代理审计通过，波次二合并门禁通过（见文末证据节），已收口提交。

## 1. 背景与红线

按 /tmp/java-http-common.md 与 JAVA-01/02 先例执行 report 域 HTTP 出口完成态核证；读码核证结论与本批
规格预期一致：**该域 public 信封面在批前已全部类型化**（JAVA-08/JAVA-11c「已完成态只做残留、不造新面」
先例）。report 包 16 个控制器的每一个 JSON 端点返回类型均为命名 service record（或其 `List`）、CSV 文本
`ResponseEntity<String>` 或跨域错误信封 `ErrorResponse`，无任何 `Map`/`Object` HTTP 出口。红线遵守：
契约 `contracts/fams-core-v1.yaml`、`packages/api-types`、`apps/web`、`apps/miniprogram` 零改动；审计
payload（各 `export` 的 `fams_audit_event` JSON_OBJECT）、SQL、缓存头、锁顺序、异常码、方法时序零改动；
`masterdata/` 包（并行代理在途，工作树含其未提交改动）零触碰、仅作为 `ScrapDispositionReportService`
既有 import 只读引用；`apps/*`、AGENTS.md 等他会话在途文件未混入。本批未编辑任何既有符号，
AGENTS「编辑符号前必跑 impact」无触发面（盘点全程只读）。

## 2. 出口盘点（改前即完成态）

JSON 出口（全部为批前已存在的具名类型，行映射内联于各服务）：

| 端点 | 返回类型 |
|---|---|
| OperationsReportController `GET /operations-reports`（汇总矩阵） | `OperationsReportService.Summary(Inventory,List<Count>,List<AssetCount>,ImportErrorQuality)` |
| SealedInventoryReportController progress / list / executors / traceability | `ProgressPage` / `Page(Task)` / `ExecutorBreakdown(Executor)` / `TraceabilityPage(TraceabilityCounts,TraceabilityTask)` |
| AssetCustodyReportController distribution / list | `Distribution(Group,CustodyGroup,CustodyQuality)` / `Page(Row)` |
| AssetMovementReportController list | `Page(Row, Cursor nextBefore 嵌套对象)` |
| AssetMovementTrendController trend | `List<TrendMonth(TrendCount)>` |
| AssetTimelineReportController list | `Page(Row, Cursor nextBefore 嵌套对象)` |
| BusinessException / InboundTiming / InventoryLate / Loss / OverdueLoan / RepairOperations / ScrapDisposition 各 list | 各自 `Service.Page(Row…)`（Loss 带 `Count` 面、Repair 带 `Totals` 面、Scrap 带 `businessDate` 头） |
| ExceptionDiscoveryReportController summary | `Summary(List<Category>)` |
| TagCoverageReportController summary | `TagCoverageReportService.Summary` |
| WorkbenchTodoController list | `WorkbenchTodoService.Page(Todo,StageCount)` |
| 14+2 个 `*.csv` 端点 | `ResponseEntity<String>`（CSV 文本，非 JSON 面） |
| WorkbenchTodoController 两个 `@ExceptionHandler` | 跨域错误信封 `ErrorResponse(…,Map.of())`（红线 6，不动） |

域内剩余 Map 出现处及处置（全部为公共协议红线 5/6 保护面对象，非残留出口）：

| 位置 | 性质 | 处置 |
|---|---|---|
| `AssetMovementReportService.trend()` 内 `Map<Kind,Map<YearMonth,Long>>` 与 `Map.entry` | 内部聚合 helper，出参已折叠为 `List<TrendMonth>` | 不动（红线 5） |
| `ExceptionDiscoveryReportService` 内 `EnumMap<Kind,long[]>` | 内部计数器，出参为 `List<Category>` | 不动 |
| `InventoryLate/Loss/ScrapDisposition/ExceptionDiscovery` 的 `Filter.resultName()/stateName()` 等 | 内部 SQL 参数字符串化 helper | 不动 |
| `TagCoverageReportService.Evidence`、`SealedInventoryReportService.ExecutorBasis`、`ExceptionDiscovery….{Window,Period,Occurrence}` | 私有行 record，从不外出 | 不动 |
| `WorkbenchTodoController` 的 `Map.of()` | 跨域错误信封契约 details 形状 | 不动（红线 6） |
| 各控制器 `Filters` 请求体 record | 请求体（规格：一字不动） | 不动 |

**裁决点 1（登记，未执行）**：规格「新建 `ReportViews.java`」未创建——本域响应 record 全部以
「service-owned 嵌套 record」形态先于本批存在且被控制器、既有运行时用例广泛引用；聚合搬迁是纯代码
移动、零 wire 收益，且违背本批规格「完成态不造新面」与最小改动红线（JAVA-11c 裁决点 1 同型）。
若主代理仍要统一 Views 组织，建议另批纯搬运收口。

**裁决点 2（mapper 口径）**：判据复刻生产 HTTP mapper——`contract/probe/JsonConfiguration` 把 Jackson 3
默认 NON_NULL 改回 `ALWAYS`，故本域所有 null 分量（`nextBefore`、`organization`、`processedAt`、
`startedAt/elapsedSeconds`、`linkedTaskId`、`factoryRoundId`、`executorAccountId`、`actualCost`、
`businessId` 等）在真实 wire 上是**显式 null 键、恒在**；冻结用例按 ALWAYS 断言（AssetImport 先例）。

**裁决点 3（派生方法不上 wire）**：`ExceptionDiscoveryReportService.Category.total()` 是非组件派生
方法，经冻结键集断言核实（并冻结）Jackson 3 **不**将其序列化——wire 恰为五键；未来若形状漂移本判据
红灯，不会静默变化。

## 3. 改动清单与判据：ReportWireShapeTest（15/15）

主代码 **0 改 0 增**（已完成态，无残留面可改），测试 1 增：

| 文件 | 变化 |
|---|---|
| `backend/src/test/java/com/fams/report/ReportWireShapeTest.java`（新增） | 15 条 wire 形状冻结判据 |

无「改前投影助手」可逐字冻结（出口早已类型化），采用 JAVA-08/11c 的**字面冻结树**法：行→record 抽取
表达式（`getString` 字符串 ID、`Timestamp.toInstant`、`getObject(.., LocalDate.class)`、`BigDecimal`
定点小数、`Duration.toSeconds` 可空秒差、`State/Result/Kind/ExecutorSource` 枚举名、`getBoolean`
traceable）逐字复制现役服务行映射入测，序列化后与手写冻结 JSON 树比树（文本往返归一整型宽度、键序
不参与、键集精确断言）。覆盖点：

1. custody：`Group` COALESCE 组 id「0」字符串 + LEFT JOIN null 显式键 + `Distribution` 四面 + `Page` 游标/null；
2. movement：`Row` + **嵌套 Cursor nextBefore 对象**（与字符串游标域区分）+ null 分支 + `TrendMonth/TrendCount` 枚举名；
3. timeline：`Row`（`factoryRoundId:null`、`summary:""`）+ 嵌套 Cursor Page；
4. business-exceptions：`processedAt` 未处理/已处理双分支；
5. exception-discovery：`Summary/Category` 全键集 + 裁决点 3 派生方法不上 wire；
6. inbound-timing：`startedAt/elapsedSeconds` 双 null 分支与 CLAIM 分支 Long 秒差；
7. inventory-late：`result` 枚举 + `linkedTaskId/Status` 显式 null；
8. loss：`SEARCHING` 双 null 与 `RECOVERED` 双时间分支 + `counts` 面 Page；
9. operations 汇总矩阵：`calculatedAt` 为 String（`clock.instant().toString()`），与其余域 Instant 语义区分；
10. overdue-loans：Instant `calculatedAt` + `LocalDate` 双日期 + long 天数；
11. repair-operations：`BigDecimal actualCost` **保 scale 冻结**（`1234.560`）与显式 null 分支 + `Totals` 面；
12. scrap-dispositions：`businessDate` 头 + `dueDate/RecordedAt` null 分支；
13. sealed-inventory：`Task`/`Progress`/`Executor`（NULL 执行人显式 null 键）/`ExecutorBreakdown`/`TraceabilityPage`（boolean `traceable`）；
14. tag-coverage + workbench：聚合计数键集 + `businessId:null` 池级分支；
15. 全部 34 个响应 record 序列化→反序列化→再序列化树等价（存储/回放跨版本安全；本域无 replay/finish
    幂等链，公共协议第 4 条对本域适用面即本条 round-trip）。

实施代理自测（非产品门禁）：

- 定向单测 `./mvnw -pl backend -Dtest='ReportWireShapeTest,TransferWireShapeTest,LoanWireShapeTest' … test`
  → **Tests run: 43, Failures: 0**（Report 15、Transfer 17、Loan 11），当次全仓 main+test 编译通过；
- `./mvnw -pl backend -q test-compile` exit=0；
- Checkstyle 项目口径 `-Pquality checkstyle:check@check-java-conventions` exit=0（新文件末尾换行满足）。

## 4. 门禁证据

主代理在波次二合并树固定副本（rsync 排除并行会话 WIP 与后续波次三在途文件）实跑
`verify-d6-03a.py` 完整 Java 序列：**JAVA01_VERIFY_PASS**，83 步 exit=0，Checkstyle/SpotBugs 0，
覆盖率 LINE 93.14%、BRANCH 75.66%；日志内 74 个 surefire 阶段汇总 401 项 0 失败 0 错误
（本次日志仅存阶段汇总行，聚合口径与既往波的逐类计数不同，判据以 VERIFY_PASS+全步 exit=0 为准）。
首轮曾红于 12b 的 `MasterdataViews` 内嵌可变生成类（SpotBugs EI_EXPOSE_REP×4），已以不可变镜像
record 修复后复跑转绿，与本批 report 面无关。合并树单元判据：20 个 WireShape 套件 157/157。
详见[波次证据](evidence/JAVA-12-java-gate.txt)。

## 5. 影响分析（GitNexus）与零改动确认

本批**未编辑任何既有符号**（仅新增测试文件与本记录），impact 无触发面；盘点过程只读。`git status`
核实本批产物仅：`backend/src/test/java/com/fams/report/ReportWireShapeTest.java`（新增）+ 本记录。
排除确认：`masterdata/`（并行代理在途，含其未跟踪 MasterdataViews/MasterdataWireShapeTest 改动）零触碰；
`apps/*`、`contracts/`、`packages/api-types`、AGENTS.md、`backend/src/test/java/com/fams/local/` 零改动；
report 主代码 31 个文件与基线 `2fe2880` 逐字节一致。

## 6. 偏差与遗留

1. 规格预估「14 专题 + 汇总矩阵 + CSV + workbench」实际共 16 控制器、32 个 JSON/CSV 端点，全部批前
   已类型化——按 JAVA-08/11c 先例登记已完成态，零残留出口。
2. CSV 出口为纯文本面（含 BOM 差异、审计写入），本批不冻结其逐字节内容（属 SQL/导出行为面），
   仅登记其类型完成态。
3. 遗留候选（非本批）：统一 Views 组织（裁决点 1）或契约 schema 收紧时另批裁决。

## 7. 下一步

report 域无需后继子批；wave12 其余域（并行批）收敛后由主代理在合并树跑产品门禁。
