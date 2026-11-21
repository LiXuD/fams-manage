# JAVA-11c · assetimport 域 HTTP 出口类型化（已完成态登记批）

> 用户指定的后端结构批（HTTP 出口波次 wave11 并行批之一），不进入 D6-01～D6-27 台账、不占用其状态。
> 日期：2026-11-22 · 分支：main · 基线：`d703d6e`（JAVA-10c 提交）
> 状态：主代理审计通过，波次一合并门禁通过（见文末证据节），已收口提交。

## 1. 背景与红线

按 /tmp/java-http-common.md 与 JAVA-01/02 先例执行 assetimport 域 HTTP 出口 Map→record 类型化。
读码核实的结论与本批规格预期一致：**该域 public 信封面在批前已全部类型化**（JAVA-08「已完成态
只做残留、不造新面」先例）。四个控制器的每一个端点返回类型均为命名 record 或 `byte[]`
（XLSX/CSV 二进制），无任何 `Map`/`Object` HTTP 出口。红线遵守：契约
`contracts/fams-core-v1.yaml`、`packages/api-types`、`apps/web`、`apps/miniprogram` 零改动；
审计 payload、幂等/指纹输入、SQL、锁顺序、异常码、方法时序零改动；`loss/`、`reminder/`、
`attachment/` 包（并行代理在途）零改动、未触碰。

## 2. 出口盘点（改前即完成态）

| 出口 | 返回类型（批前已存在） |
|---|---|
| AssetImportController upload/get | `AssetImportBatchService.Ticket(UUID,String,long,JsonNode)` |
| preview | `Preview(UUID,long,int,int,int,List<CellError>,boolean)` |
| columns | `ColumnList(List<String>)` |
| rows / ReviewController rows | `RowPage(int,List<PreviewRow>)`，`PreviewRow(int,String,JsonNode,JsonNode,JsonNode)` |
| template / preflight-report / result | `ResponseEntity<byte[]>`（XLSX/CSV，非 JSON 面） |
| ReviewController pending | `List<AssetImportReviewService.Pending>` |
| get/request/decide | `Review(UUID,String,long,String,String,UUID)` |
| RollbackController pending | `List<AssetImportRollbackService.Pending>` |
| get/request/decide/execute | `Rollback(UUID,String,long,UUID)` |
| WindowController operators | `List<EligibleOperator(String,String)>` |
| current/open/close | `Window(String,String,String,Instant)` |

域内剩余 Map 出现处及处置（全部为公共协议红线 5/6 保护面对象，非残留出口）：

| 位置 | 性质 | 处置 |
|---|---|---|
| `AssetImportController.MappingRequest(Map<String,String> mapping)` | 请求体（规格：一字不动） | 不动 |
| `AssetImportReviewService.event(...,Map<String,Object>)`、`AssetImportRollbackService.event(...)` | 审计 payload | 不动（红线 5） |
| `AssetImportRollbackService.lockPublished` 返回 `Map<String,Object>` | **私有行 helper / 内部行 Map**（仅供 decide/queue/rollback 内部取 batch_status/source_sha256，从不外出 HTTP） | 不动（红线 5「私有行 helper：不动」；规格「剩余 Map 出口泛型化」经读码核实在此二服务内不存在 public Map 出口） |
| `AssetImportPreflight.columns` 返回 `Map<String,String>` | 内部列映射 helper；其结果经 `mapping_json` 存储后以 `Ticket.mapping` JsonNode 外出 | 不动 |
| `AssetImportWorkbook.DataRow.values`、`source_json/resolved_json/errors_json` | **Excel 动态列结构**：键为用户自定表头，边界已按已裁决透传先例以 `JsonNode` 原样外出（Identification 先例），判据冻结逐字透传 | 不类型化，登记 |
| `AssetImportErrorHandler` `ErrorResponse(...,Map.of())` | 跨域错误信封契约形状 | 不动 |

## 3. 改动清单

主代码 **0 改 0 增**（已完成态，无残留面可改），测试 1 增：

| 文件 | 变化 |
|---|---|
| `assetimport/AssetImportWireShapeTest.java`（新增） | 10 条 wire 形状冻结判据，见 §4 |

**裁决点 1（登记，未执行）**：规格「新建 `AssetImportViews.java`」未创建——域内响应 record
全部以「service-owned 嵌套 record」形态先于本批存在且被控制器/两 Review/Rollback 服务/
`AssetImportBatchRuntimeCase` 等广泛引用；把它们聚合搬入新 Views 类是纯代码搬迁、零 wire 收益，
且违背本批规格「不造新面」与最小改动红线。若主代理仍要统一 Views 组织，建议另批纯搬运收口。

**裁决点 2**：判据 mapper 复刻生产 HTTP mapper——`contract/probe/JsonConfiguration` 把
Jackson 3 的默认 NON_NULL 包含改回 `ALWAYS`，故本域所有 null 分量（`Ticket.mapping`、
`Review.decidedBy/jobId`、`PreviewRow.resolved/errors`、`Rollback.jobId`）在真实 wire 上是
**显式 null 键、恒在**，与 transfer「条件键」语义不同；冻结用例按 ALWAYS 断言显式 null。
若未来移除该 customizer，本判据会以红灯强制重新裁决形状，不会静默漂移。

## 4. 判据：AssetImportWireShapeTest（10/10）

无「改前投影助手」可逐字冻结（出口早已类型化），改用 JAVA-08 §3.2 的**字面冻结树**法：
行→record 抽取表达式（`((Number)…).longValue()`、`toString()` 字符串 ID、
`UUID.fromString` null 分支、`Timestamp.toInstant`、`LocalDateTime.toInstant(UTC)`）逐字复制现役
服务代码入测，序列化后与手写冻结 JSON 树比树（文本往返归一整型宽度、键序不参与、键集精确断言）：

1. Ticket：mapping_json 动态列键**逐字透传** + `mapping:null` 显式键 + previewVersion Integer/Long 双形态；
2. Preview：CellError 五键行 + `moreErrors` + 键序无关全键集；
3. PreviewRow：`source_json/resolved_json/errors_json` 存储树逐字透传（Unicode/含空格键名）与
   resolved/errors 显式 null 键恒在；
4. RowPage/ColumnList 信封；5. Review PENDING（decidedBy/jobId 显式 null）与 APPROVED（字符串
   ID + UUID jobId）双分支；6. Review Pending 的 requestedAt ISO instant；7. Rollback 双分支；
8. Rollback Pending 全键；9. Window（Long id→字符串、`2026-12-01T10:00:00Z`）与 EligibleOperator；
10. 全部 11 个 record 序列化→反序列化→再序列化树等价（覆盖存储/回放跨版本安全）。

幂等链说明：本域无 `replay/finish` 型幂等记录（上传重放走 `upload_key` 行复用+指纹比较，
审核/回滚走状态机与 DurableJob，见 `upload()` 与 `jobs.submit` 路径），公共协议第 4 条对本域
不适用面为「无」，第 10 条 round-trip 判据覆盖等效需求。真实 HTTP 形状由既有
`AssetImportHttpRuntimeCase`（键名断言与本冻结一致）在主代理门禁实跑。

## 5. 门禁证据

实施代理已跑（非产品门禁）：

- 定向单测：`JAVA_HOME=graalvm-jdk-21.0.7 ./mvnw -pl backend
  -Dtest='AssetImportWireShapeTest,TransferWireShapeTest,LoanWireShapeTest' -DfailIfNoTests=false test`
  → **Tests run: 38, Failures: 0, Errors: 0**（AssetImport 10、Transfer 17、Loan 11）；
  该 lifecycle 当次全仓 main+test 编译通过（此刻并行文件处于可编译快照）。
- Checkstyle 项目口径：`./mvnw -pl backend -Pquality checkstyle:check@check-java-conventions`
  → exit=0，结果含新文件、0 违规（新文件末尾换行已满足）。
- 独立全量 `./mvnw -pl backend -q test-compile`：在两次尝试中因**并行代理在途文件**失败——
  首轮 `loss/LossViews.java`（未跟踪，JAVA-11a 在途）重复构造器、次轮 `loss/LossController.java`
  类型不匹配；均在本批禁触包内、非本批产物，按公共协议第 8 条如实登记，待并行批收敛后由
  主代理在合并树复验。本批新增文件自身编译零错误（经上述定向 test 全绿证明）。

产品门禁完整 Java 序列：留待主代理在固定候选实跑（§5 此段即批记录口径的 §4 留空位）。

## 6. 影响分析（GitNexus）与零改动确认

本批**未编辑任何既有符号**（仅新增测试文件与本记录），AGENTS「编辑符号前必跑 impact」无触发面；
盘点过程只读。`git status` 核实本批产物仅：
`backend/src/test/java/com/fams/assetimport/AssetImportWireShapeTest.java`（新增）+ 本记录。
排除确认：`loss/`、`reminder/`（并行代理在途，含其未跟踪 LossViews/ReminderViews 改动）零触碰；
`attachment/` 无涉及；`apps/*`、`contracts/`、`packages/api-types`、AGENTS.md 零改动
（工作树中 AGENTS.md 与小程序未跟踪配置为用户/他会话在途内容，未混入）。

## 7. 偏差与遗留

1. 规格预估「grep 约 4 处 public 信封面」实际为域内 4 处**受保护** Map 面（请求体、审计 payload×2、
   私有行 helper），无 HTTP 出口残留——按 JAVA-08 先例登记已完成态。
2. 动态列透传面（mapping/source/resolved/errors 的 JsonNode）按已裁决先例保持原样，键集不受
   本批影响；其前端消费（`apps/web/features/asset-import`）零改动。
3. 遗留候选（非本批）：若后续统一 Views 组织（裁决点 1）或契约 schema 收紧，另批裁决。

## 8. 下一步

wave11 并行批（loss/reminder）收敛后由主代理在合并树跑产品门禁；assetimport 域无需后继子批。

## 主代理合并门禁（2026-11-22，波次一三批合并树单次实跑）

排除并行 WIP 副本上完整 `verify_java_quality`：83 步全部 exit=0，surefire 聚合
**844 项测试 0 失败 0 错误**（含 LossHttpRuntimeCase、Messages/Duty 运行时链的真实
HTTP 回归），Checkstyle/SpotBugs 0，覆盖率 **LINE 27445/29468（93.13%）、
BRANCH 9889/13063（75.70%）**；主树 18 个 WireShape 套件复跑 136/136。证据：
[JAVA-11-java-gate.txt](evidence/JAVA-11-java-gate.txt)。
