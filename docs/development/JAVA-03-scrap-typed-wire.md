# JAVA-03 · scrap 域响应与域内 Port 类型化

> 用户指定的后端结构批（JAVA-01 transfer 打样、JAVA-02 loan 之后的第三域），不进入 D6-01～D6-27 台账、不占用其状态。
> 日期：2026-11-21 · 分支：main · 基线：`8624d83`（JAVA-02 提交）
> 状态：主代理审计通过，与 JAVA-04 合并跑产品门禁完整 Java 序列通过，已收口提交。

## 1. 背景与红线

完全复刻 JAVA-01/02 标准：HTTP 响应 + 域内 Port 类型化；内部行 Map、审计 payload、幂等指纹
digest 输入保留 Map；契约 `contracts/fams-core-v1.yaml` 与 `packages/api-types` 零改动。
scrap 域与 transfer/loan 的关键差异（实施前逐字核对改前代码冻结为 oracle）：

1. **变更类出口早已类型化**：`ScrapService.Receipt`、`ScrapReviewService.Receipt`/
   `DispositionReceipt` 改前就是 record，`replay`/`remember`/`completeDisposition` 已用
   `Receipt.class`/`DispositionReceipt.class` 反序列化——属已完成态，本批一字未动。
   本批实际 Map→record 工作集中在**读侧**（ScrapReadService 五出口 + view 投影）与
   **附件侧**（capabilities/list/upload/grant 四出口）。
2. **view() 与 DB 查询交织**：改前 `ScrapReadService.view()` 在投影中途执行锁、处置、
   更正、撤销、找回五组查询。本批把纯投影移入 `ScrapViews.request(...)`（五组查询结果作
   参数传入，对照 JAVA-02 把 clean/view 移入 LoanViews 的先例），服务只留查询编排；
   角色判定仍读**原始行 Map**（`Objects.equals(actor.id(), row.get("processor_account_id"))`
   是 Long↔long 数值比较，若改读 record 的字符串分量会恒假——逐字保留）。
3. **snake_case 键与原始 JSON 文本**：disposition 段保留 SQL 列名键
   （`disposition_status`/`due_date`/`custody_snapshot_json`），history 段保留
   `actor_username`/`occurred_at`；`custody_snapshot_json` 在 wire 上是**未解析的 JSON
   字符串**（双重编码），record 分量用 String 原样透传，不得 parse。
4. **条件键与显式 null 键并存**：`factoryDisposition`/`initialFactoryDispositionAt`/
   `initialFactoryDispositionEvidence`/disposition 的 `custody_snapshot_json`（LOSS/FACTORY
   隐藏）与 `completion`（仅 DISPOSED）、Completion 的 `recipient`/`operatorName`（factory
   无）、RequestPage 的 `businessDate`（仅 overdue）、Request 的 `history`（仅 get）用
   NON_NULL 复刻；`sourceBusinessId`/`processorAccountId`/`initialSource`/`decision`/
   `execution`/`lockVersion`/`disposition`/`correction`/`reversal`/`recovery`/
   `dispositionVersion` 总是 put、可为显式 null（默认包含保持）。
5. **两处易错语义逐字保留**：① LOSS 的 execution 段被约简为仅 `writtenOffAt` 单键
   （ObjectNode 复刻 Map.of）；② `initialFactoryDispositionEvidence` 在 auditOnly
   `replaceAll(false)` **之前**用原始 decide/executeFactory 判定（虽然 auditOnly 读者
   两值必假、键必缺席，顺序仍按改前保持）；③ decision 快照移除 `attachmentEvidence` 键。
6. wire 等价比较口径与 JAVA-01/02 相同：各自序列化 JSON 文本再解析比树（归一整型宽度，
   键序不参与；键存在性、显式 null、值形状全部参与）。JDBC 原始类型不臆断：
   `occurred_at`/`createdAt`（DATETIME(3)）用 Object 分量透传驱动原值（Timestamp/
   LocalDateTime 双形态均补判据）；`initial_sheet_row`（INT）Integer→Long 文本往返归一
   （JAVA-02 同口径）；版本/锁版本列核实为 BIGINT（V6/V59/V61/V62/V63）。

## 2. 改动清单

主代码 5 改 1 增（+58/−231），测试 1 增：

| 文件 | 变化 |
|---|---|
| `scrap/ScrapViews.java`（新增） | 19 个响应 record + 行映射工厂 + `request(...)` 全投影；`Actions` 19 键布尔 record + `denied()`；含 List 分量的 4 个 record（RequestPage/Request/ChoicePage/EligiblePage）紧凑构造器 `List.copyOf` 防御拷贝 |
| `scrap/ScrapReadService.java` | 5 个出口类型化；删除 `view()` 的 Map 投影与 `correctionView()`/`reversalView()`/`recoveryView()`（移入 ScrapViews 具名映射）；`mayRequestReversal`/`mayRequestRecovery`/`auditOnly` 改包内可见供 Views 调用（逻辑一字未动）；`eligible` 在 scrap 侧把 `AssetScrapPort` 的 Map 信封边界转换为 record，port 不动 |
| `scrap/ScrapController.java` | 5 个 GET 端点返回类型化视图；POST 端点（早已类型化）、请求体 record、校验与异常处理器一字未动 |
| `scrap/ScrapAttachmentController.java` | capabilities/list/upload/grant 四出口类型化；grant 的 URL 拼装逻辑逐字保留；上传校验一字未动 |
| `scrap/ScrapAttachmentService.java` | upload/capabilities/list 类型化；`result.get("objectId")`→`result.objectId()`；list 在 scrap 侧把 `AttachmentEvidencePort` 的行 Map 边界转换为 record，port 不动 |
| `scrap/ScrapAttachmentTransactions.java` | capabilities/register 类型化；幂等回放由 `TypeReference<Map<String,Object>>` 改 `AttachmentReceipt.class`（旧存储 JSON 键形相同，可回放，判据实证）；指纹 Payload/审计 detail/内部行 Map 未动 |
| `scrap/ScrapWireShapeTest.java`（新增） | 12 条 wire 等价用例，见 §3 |

出口 → record 对照：

| 出口 | record |
|---|---|
| ScrapReadService.list | `RequestPage`（`List<Request>` + canListOverdueDispositions，businessDate NON_NULL 缺席） |
| ScrapReadService.overdueDispositions | `RequestPage`（businessDate LocalDate 在场） |
| ScrapReadService.eligible | `EligiblePage`（`EligibleAsset`；AssetScrapPort 信封边界转换） |
| ScrapReadService.get | `Request`（view 全字段 + `List<HistoryEntry>` history） |
| ScrapReadService.catalog | `ChoicePage`（`Choice`：location/bin/employee 三目录同形） |
| ScrapAttachmentController.capabilities | `AttachmentCapabilities`（dispositionVersion 显式可空） |
| ScrapAttachmentController.list | `List<AttachmentMetadata>`（AttachmentEvidencePort 行边界转换，createdAt Object 透传） |
| ScrapAttachmentController.upload / Transactions.register | `AttachmentReceipt`（幂等存储 + 回放） |
| ScrapAttachmentController.grant | `AttachmentGrant` |
| （早已类型化，未动）ScrapService.submit* | `ScrapService.Receipt` |
| （早已类型化，未动）ScrapReviewService 全部变更出口 | `ScrapReviewService.Receipt` / `DispositionReceipt` |

`Request` 子形状：`Source`/`InitialSource`/`Disposition`（snake_case `@JsonProperty`）/
`Completion`（factory 双键、物理四键，NON_NULL 复刻）/`Correction`/`Reversal`/`Recovery`/
`Actions`/`HistoryEntry`（snake_case `@JsonProperty`）。record 命名镜像契约
ScrapRequest/ScrapRequestPage/ScrapChoice(Page)/ScrapEligibleAsset(Page)/
ScrapAttachmentCapabilities/Receipt/Metadata/Grant，以代码实际 wire 为准。

## 3. 判据：wire 等价测试

`ScrapWireShapeTest` 把改前 `ScrapReadService.view()` **逐字冻结为 oracle**（五处
`db.queryForList` 调用点以传入行列表替代——恰是重构后服务传给 `ScrapViews.request` 的
同一输入），`correctionView`/`reversalView`/`recoveryView`/`mayRequestReversal`/
`mayRequestRecovery`/`auditOnly` 亦逐字冻结；`mayCancel`/`physicalSource` 本批未改，
oracle 直调生产静态方法。构造 JDBC 类型行（Long/Integer/String/Timestamp/LocalDateTime/
JSON 串）比对：

- STANDALONE 待决：admin/warehouse/creator/auditOnly 四读者 + 有锁/无锁（lockVersion
  显式 null 与 cancel 联动）；
- EXECUTED 待处置：五读者 dispose/initiateReversal 矩阵 + 撤销 PENDING/PROCESSING
  （本处理器/他处理器）链；
- DISPOSED completion：物理四键、factory 双键（custody 隐藏）、completion 快照在场但
  状态非 DISPOSED 键缺席、DISPOSED 无快照键缺席；
- LOSS 核销：execution 约简为 writtenOffAt 单键、LOSS_WRITTEN_OFF 处置（custody 隐藏、
  due_date 显式 null）、找回 initiate/decide/claim/execute/COMPLETED 全链；
- FACTORY_REPLACEMENT：initial 行 factoryDisposition/initialFactoryDispositionAt/
  evidence（处理器与 admin 两视角）、待决 admin decide 触发 evidence、非 initial 行无
  initial* 键、decision 快照 attachmentEvidence 移除；
- REPAIR PROCESSING：execute 本处理器/他处理器；
- 更正全生命周期：initiate（无更正）/decide/claim/execute（本/他处理器）/COMPLETED；
- auditOnly 全子记录在场：19 键全 false（断言键数与值）；
- initialSource（Integer sheetRow）+ history：Timestamp/LocalDateTime 双驱动形态、
  空 history 键仍在场；
- 页装配：list/overdue（businessDate）/catalog 与改前 Map.of 装配比树；eligible 冻结
  AssetScrapPort.eligible 装配经 `eligiblePage` 边界转换比树；
- 附件：capabilities（dispositionVersion 有无）、register Map.of、grant Map.of、
  metadata 行（Timestamp/LocalDateTime createdAt 双形态）；
- 幂等：AttachmentReceipt 序列化→反序列化→再序列化比树；**改前形状存储 JSON
  （Map.of 生成）反序列化为新 record 并等价再序列化**——request/disposition 两种 kind，
  覆盖 24 小时窗口跨版本回放。

单元 oracle 边界沿用 JAVA-01/02 教训：只证明「行→视图」序列化等价；真实 HTTP 运行时
用例（ScrapSubmitHttpRuntimeCase、ScrapUpgradeRuntimeCase、ScrapCorrection/Reversal/
RecoveryUpgradeRuntimeCase、AttachmentHttpTest、AttachmentIdempotentUploadTest 等）
由主代理门禁实跑。本批无跨边界快照直比路径（scrap 的 source/confirmation 比较均读存储
JSON 树，不经 view 输出），故无 JAVA-01 式文本往返回归风险点；view 输出仅 HTTP 消费
（ScrapAttachmentTransactions.reader/capabilities 调 reads.get 但丢弃返回值）。

## 4. 门禁证据

**产品门禁（与 JAVA-04 合并树单次实跑，2026-11-21）**：

- 完整 Java 质量序列：83 步全部 exit=0（`verify_java_quality`，环境补丁经 `__globals__` 注入）。
- surefire 聚合：**768 项测试 0 失败 0 错误**（JAVA-02 基线 738，净增 scrap 12 + proxy 9 +
  targetchange 9）。
- Checkstyle/SpotBugs/报告断言：`verify_java_reports` 通过，0 违规 0 发现。
- 覆盖率 LINE/BRANCH：**27163/29185（93.07%）/ 9863/13029（75.70%）**（门槛 75%）。
- 主代理合并树复跑单元判据：60/60（Scrap 12、Proxy 9、TargetChange 9、Transfer 17、
  Loan 12、Calendar 1）。
- 主代理审计：改前 `view()` 逐键核对——disposition/correction/reversal/recovery/
  initialSource/lockVersion 总是 put（含显式 null）对应 record 默认包含；
  factoryDisposition/businessDate/history/completion 条件键对应 NON_NULL；
  custody_snapshot_json LONGTEXT NOT NULL 原文透传；LOSS execution 约简与
  evidence 先于 auditOnly 判定保持；变更出口改前已是 Receipt record（代理说法核实属实）。
- 证据：[JAVA-03-04-java-gate.txt](evidence/JAVA-03-04-java-gate.txt)。

实施代理已跑（非产品门禁）：

- **隔离基线验证**：共享工作树中另一代理正在并行改 proxy/targetchange/requisition/
  repair/transfer 包，其未完成的信封接口改动使共享树 `test-compile` 间歇失败（失败文件
  仅 `repair/RepairProxyReceiptPort.java`、`repair/RepairTargetChangePort.java` 等，
  **零 scrap 错误**）。为不触碰排除包，本批在 `git archive HEAD` 的干净副本
  （/tmp/java03-verify，基线 8624d83）上仅叠加本批 7 个 scrap 文件验证：
  - 定向单测：`JAVA_HOME=graalvm-jdk-21.0.7 ./mvnw -pl backend -Dtest='ScrapWireShapeTest' -DfailIfNoTests=false test`
    → **Tests run: 12, Failures: 0, Errors: 0**，exit=0。
  - 全仓编译：`./mvnw -pl backend -q test-compile` → exit=0（HEAD 基线 + 本批，全部既有测试源码编译通过）。
  - Checkstyle（项目规则集）：`./mvnw -pl backend -Pquality checkstyle:check@check-java-conventions`
    → exit=0（首轮抓出两个新文件缺末尾换行，已修复复跑；裸 checkstyle:check 落回
    sun_checks 不是项目口径，必须带 execution id）。
- 共享工作树早期（并行代理改动落入前）`test-compile` 亦曾 exit=0；收口时共享树全量
  编译由主代理在并行批合并后统一复核。

## 5. 影响分析（GitNexus）

编辑前按 AGENTS 要求执行 `impact -d upstream`（Node 24 前缀 + --repo）：

- `ScrapReadService`：LOW，2 直接依赖（ScrapController、ScrapAttachmentTransactions）。
- `ScrapController`：LOW，0 上游。
- `ScrapAttachmentController`：LOW，0 上游。
- `ScrapAttachmentService`：LOW，1（ScrapAttachmentController）。
- `ScrapAttachmentTransactions`：LOW，1（ScrapAttachmentService）。
- 无 HIGH/CRITICAL；全部影响 confined 于 scrap 包。`AssetScrapPort.eligible` 仅
  ScrapReadService 消费（读码核实），本批不改其签名故无需 impact。

`detect-changes`：39 文件 131 符号、受影响执行流 0、聚合 risk low——含用户未提交的
小程序 14 文件、AGENTS.md 与并行代理的 proxy/targetchange/requisition/transfer 改动，
均不属本批；本批 scrap 符号 impact 全 LOW。scrap 读侧无跨域消费点（loss 域不消费 scrap
响应形状），故无 JAVA-01/02 式 loss→域执行流影响。

## 6. 偏差与遗留

1. **排除清单（保留 Map/不动，按规格）**：
   - `AssetScrapPort.eligible`（asset 包跨域 Port）：接口保留 Map；`eligible` 出口在
     scrap 侧边界转换为 `EligiblePage`（对照 JAVA-02 BorrowRequestDatePort 信封先例）。
     其余 AssetScrapPort 方法（source/freeze/restore/execute 等）是内部存储形状与资产
     生命周期操作，非 scrap HTTP 响应形状，不动。
   - `AttachmentEvidencePort.list/grant/read/clean`（attachment 包跨域 Port，inventory
     共用）：接口保留 Map/既有 record；list 出口在 scrap 侧边界转换为
     `AttachmentMetadata`。grant/read 早已返回类型化 record（Grant/Content），不动。
   - 内部行 Map（`visible()/scope()/header()/guarded()/correctionGuarded()` 等）、
     审计 payload、幂等指纹 digest 输入（ScrapService.fingerprint、
     ScrapReviewService.fingerprint、附件 Payload）：一律未动。
   - `apps/web`、`apps/miniprogram`、`contracts/`、`packages/api-types`：零改动。
   - **proxy/targetchange/requisition/repair/transfer 包：一个文件未碰**（并行代理领地）。
   - 工作树中用户未提交的小程序改动与 AGENTS.md：未触碰。
2. **LossScrapSourcePort/LossService 无需改动**：规格预期的跨域消费点读码核实为已完成
   态——`LossScrapSourcePort` 早已类型化（`ConfirmedLoss`/`FactorySource` record，
   scrap 侧 `ScrapService`/`ScrapReviewService` 以 `loss.assetId()`/`loss.lifecycle()`
   等访问器消费）；`LossService` 的 `recoveryAsset.get(...)` 读的是 asset 域 Port 行
   Map，非 scrap 响应形状，不属本批。对照 JAVA-02 §6.2 的 LoanReceiptPort 先例登记。
3. **ScrapInitialPort 无需改动**：实现 business 包 `InitialScrapPort`，方法全为 void
   或内部行 Map（`guarded()`），无 HTTP 响应形状出口；Opening/LossFacts/
   FactoryDisposition 参数早已类型化。内部行 Map 按规格保留。
4. **ScrapService/ScrapReviewService 变更出口无需改动**：Receipt/DispositionReceipt
   改前即 record，replay 已用 Class 反序列化（无 Map.class 裸类型），不属「保留 Map
   遗留」，是已完成态。本批唯一幂等泛型化工作是附件 register 的
   `TypeReference<Map>`→`AttachmentReceipt.class`。
5. **无新增写入 DB JSON 列的 record**：AttachmentReceipt 存入 fams_scrap_receipt.
   response_json 的键形与改前 Map.of 完全相同（camelCase 四键），无需 @JsonProperty
   snake_case；disposition/history 的 @JsonProperty 仅服务 HTTP wire 的 snake_case 键。
6. `initial_sheet_row`（INT）record 分量用 Long：改前原样透传 Integer，文本往返归一
   整型宽度，wire 等价（JAVA-01 totalQuantity、JAVA-02 initialSheetRow 同口径）。
7. 旧幂等记录（附件）回放经 record 再序列化键序归一为声明序（旧 Map.of 键序本随机），
   语义等价，24 小时窗口后自然消失（同 JAVA-01 §6.5、JAVA-02 §6.7）。
8. **并行代理观察项（非本批缺陷）**：共享工作树 test-compile 在并行批未完成时失败于
   repair 两个信封适配器（实现 proxy/targetchange 已改接口）；本批以隔离基线验证，
   未触碰排除包，无需裁决。主代理合并两批后统一复跑全量编译与门禁。
9. **裁决点：无。** 未遇到形状不可判定、HIGH/CRITICAL impact、必须触及排除包或疑似
   改前缺陷。

## 7. 下一步

按 JAVA-01 §7 建议继续小域批次（targetchange 已由并行代理进行中），大域
（repair/inbound/inventory）拆子批；`AssetScrapPort.eligible`/`AttachmentEvidencePort`
信封接口的类型化需 asset/attachment/inventory 跨域同批，另批裁决。产品门禁完整序列
与运行时用例实跑由主代理在固定候选统一执行。