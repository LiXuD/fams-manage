# JAVA-04 · 跨域信封接口类型化（ProxyReceiptPort / TargetChangePort）

> 用户指定的后端结构批（JAVA-01 transfer 打样、JAVA-02 loan 之后的跨域信封批），不进入 D6-01～D6-27 台账、不占用其状态。
> 日期：2026-11-21 · 分支：main · 基线：`8624d83`（JAVA-02 提交）
> 状态：主代理审计与裁决点复核通过，与 JAVA-03 合并跑产品门禁完整 Java 序列通过，已收口提交。

## 1. 背景与红线

JAVA-01 §6.1 登记的遗留：`proxy/ProxyReceiptPort`（snapshot/execute）与
`targetchange/TargetChangePort`（context）由 transfer/requisition/repair 三域共同实现，
属多态信封，当时只在 transfer 适配器边界 `convertValue` 转 Map。本批把接口本身类型化，
三域同批适配，删除 transfer 侧 convertValue 边界层。红线完全复刻 JAVA-01/02：

1. **比较语义逐一对应改前代码，不统一简化**（JAVA-01 §3 回归教训，最高优先级）：
   - transfer `proxyReceipt`：改前「序列化文本再解析」比树。改后两侧虽都是 record，
     仍**保留文本往返**（expected 来自存储/HTTP JSON，repair 侧 target/loan 原始 Map
     分量含 Integer 宽度值），并补 record→HTTP JSON→record 全链回归用例。
   - requisition `proxyReceipt`：改前文本往返，原样保留。
   - repair `proxyReceipt`：改前文本往返，原样保留（target/loan 是 asset/loan 包产出的
     原始 Map，JDBC Long 与 HTTP Integer 宽度差异必须归一）。
   - 编排方 `same()`（create 对 HTTP Draft.snapshot 原始 Map、lockedCurrent 对存储
     JsonNode）、workflow `propose`（对 Proposal.snapshot 原始 HTTP Map）、`decide`
     （对存储 source_snapshot_json JsonNode）：任一侧是原始 Map/JsonNode，文本往返原样保留。
2. 投影助手逐字冻结为 oracle：workflow `clean()`、编排方 `wire()`、requisition
   handover 行的 `clean()`（**只转 LocalDateTime、Timestamp 原样透传**，与 workflow
   clean() 的双分支不同，不可混用）。
3. 条件键 NON_NULL（Change.targetDisplay 仅非空才出现）；显式 null 默认包含
   （JsonConfiguration ALWAYS；repair approvedTargetId/loan、Confirmation 可空列）。
4. 幂等存储旧形状 JSON 可回放：密封 ProxySnapshot 用 EXISTING_PROPERTY businessType
   （JAVA-02 Followup 先例），零新增键；补旧存储反序列化等价用例（三域 snapshot、
   Confirmation、Change 三条存储链）。
5. JDBC 原始类型不臆断：repair version/ownership_version/asset_version 核实 V43 均
   BIGINT NOT NULL 后用 long；Confirmation 与 Handover 的 DATETIME 分量保持 Object
   原样透传（LocalDateTime/Timestamp 双驱动形态，改前 wire()/clean() 即透传）。
6. 含 List/Map 分量 record 紧凑构造器防御拷贝（Map 用 unmodifiableMap+LinkedHashMap
   保序且容忍 null 值，对照 TargetChangeInput.Proposal 先例；List 用 List.copyOf）。
7. 契约 `contracts/fams-core-v1.yaml` 与 `packages/api-types` 零改动：形状逐字段等价
   （ProxyConfirmationSnapshot oneOf+discriminator businessType 约 4170 行、三域
   snapshot schema、ItemTargetChange/BusinessTargetChangeRecord snake_case 键均已核对）。

## 2. 改动清单

主代码 18 改 2 增，测试 1 改 2 增（in-scope 合计 +211/−310，不含新增四文件）：

| 文件 | 变化 |
|---|---|
| `proxy/ProxyViews.java`（新增） | 密封 `ProxySnapshot`（`RequisitionSnapshot`/`TransferSnapshot`/`RepairSnapshot`，`@JsonTypeInfo(NAME, EXISTING_PROPERTY, "businessType", visible=true)`+`@JsonSubTypes`）；密封 `ReceiptResult`（三域执行结果）；`Reply`（id/version）+ `Confirmation`（fams_proxy_confirmation 31 列 camelCase 投影）+ `ConfirmationPage`；`confirmation()` 工厂逐字冻结编排方 wire() |
| `proxy/ProxyReceiptPort.java` | snapshot 返回 `ProxyViews.ProxySnapshot`；execute（含 evidence 重载）expected 参数与返回类型化；ExecutionEvidence 不动 |
| `targetchange/TargetChangeViews.java`（新增） | **非密封** `Context` 接口（businessType/version/assetCode/target，见 §6.1）；`Reply`+`Change`（clean() 的 snake_case 键形，`@JsonProperty` 保持原键，targetDisplay NON_NULL）+`Page`；`RequisitionContext`/`RequisitionTarget`/`Handover`、`RepairContext`；`change()`/`handover()` 工厂逐字冻结两套 clean() |
| `targetchange/TargetChangePort.java` | context 返回 `TargetChangeViews.Context`；其余方法不动 |
| `targetchange/TargetChangeWorkflow.java` | context/list/propose/decide 出口类型化；clean() 投影移入 Views（display 计算仍留 workflow）；replay/finish 泛型化（`Class<T>`、`Reply.id()/version()`）；propose/decide 比较语义原样（文本往返）；`snapshot.get("version"/"assetCode"/"target")` 改类型化访问器 |
| `targetchange/TargetChangeController.java` | 四端点返回类型化视图 |
| `transfer/TransferViews.java` | `ProxySnapshot`/`ProxyReceiptResult` 移入 ProxyViews（密封 permits 要求同包）；`TargetChangeContext` implements `TargetChangeViews.Context`（target() 协变返回 TargetWire），形状一字未动 |
| `transfer/TransferService.java` | proxySnapshot/proxyReceipt 改用 ProxyViews 类型；expected 参数类型化；比较保留文本往返；targetChangeContext 返回类型不变 |
| `transfer/TransferProxyReceiptPort.java` | **删除 convertValue 边界层**与 ObjectMapper 依赖，直接返回/接收类型化形状 |
| `transfer/TransferTargetChangePort.java` | context 删除 convertValue，直接返回记录 |
| `requisition/RequisitionHandoverService.java` | proxySnapshot/proxyReceipt/targetChangeContext 三方法类型化（仅这三方法）；handover 行改 `TargetChangeViews.handover(json,row)`；其余业务逻辑与 clean() 本体不动 |
| `requisition/RequisitionProxyReceiptPort.java`、`requisition/RequisitionTargetChangePort.java` | 签名适配 |
| `requisition/RequisitionProxyConfirmationService.java` | context/list/get/create/submit/decision/execute 出口类型化；删除 wire()（移入 ProxyViews）；execute 的 business_snapshot_json 反序列化为密封 ProxySnapshot（删 `@SuppressWarnings`）；replay/finish 泛型化；`snapshot.get(...)`/`result.get(...)` 改类型化访问器；same() 文本往返不动；审计 payload/notice content_json 的 detail Map 结构不动（内嵌 snapshot 记录序列化等价） |
| `requisition/RequisitionProxyConfirmationController.java` | 七端点返回类型化视图；Draft/Decision 请求体 record 与校验一字未动 |
| `requisition/ItemTargetChangeService.java`、`requisition/ItemTargetChangeController.java` | 兼容端点出口类型化 |
| `repair/RepairService.java` | **仅** targetChangeContext/proxySnapshot/proxyReceipt 三方法类型化（diff hunk 核实无其他业务逻辑改动）；loanWire()/wire()/latestPhysicalTime() 等助手不动（loanWire 仍服务未改的 reassignmentSnapshot） |
| `repair/RepairProxyReceiptPort.java`、`repair/RepairTargetChangePort.java` | 签名适配；异常翻译语义不动 |
| `transfer/TransferWireShapeTest.java` | 仅 ProxySnapshot 构造引用改 ProxyViews.TransferSnapshot（1 行），判据不变 |
| `proxy/ProxyWireShapeTest.java`（新增） | 9 条判据，见 §3 |
| `targetchange/TargetChangeWireShapeTest.java`（新增） | 9 条判据，见 §3 |

`ProxyConfirmationAttachmentPort` 无需改动：只消费 lockHeader/currentProcessor（签名未变），
impact LOW 0，编译通过核实。

## 3. 判据：wire 等价测试

`ProxyWireShapeTest`（9/9）：三域 snapshot 与改前 Map 装配比树（repair 含 loan 有无、
approvedTargetId 显式 null 两态）；三域 ReceiptResult 与改前 Map.of 比树；
**比较全链回归**（三域 record→HTTP JSON→record 经密封接口反序列化、record→HTTP→原始
Map（Integer 宽度）、存储 JSON 回放，文本往返比较全部等价）；**旧存储兼容**（改前
Map 投影生成的 business_snapshot_json 经 discriminator 反序列化为正确子类型并等价再
序列化，零新增键）；Confirmation 与冻结 wire() oracle 比树（DRAFT 全 null 条件列 +
EXECUTED 全字段、Timestamp/LocalDateTime 双形态）；ConfirmationPage 装配；幂等回放
（新记录往返 + 改前 wire() Map 存储 JSON 回放，id/version 资源身份核实）。

`TargetChangeWireShapeTest`（9/9）：Change 与冻结 workflow clean() oracle 比树
（PENDING 无 display、APPROVED 带 display、Timestamp/LocalDateTime 双形态、可空
approver/decided_at）；Page 装配；RequisitionContext 与改前 LinkedHashMap 装配比树
（含 target 子记录、handover clean() 投影）及 source_snapshot_json 存储等价；
**handover Timestamp 原样透传**判据（requisition clean() 无 Timestamp 分支，与 workflow
clean() 区分）；RepairContext 与改前装配比树（approvedTargetId/loanVersion 有无两态）；
TransferContext 经 Context 接口分派且与 JAVA-01 convertValue 边界 wire 中性；
**比较全链回归**（propose 对原始 HTTP Map、decide 对存储 JsonNode、target 子比较，
文本往返全部等价）；Change 幂等回放（新记录往返 + 改前 clean() snake_case 存储 JSON
回放 + 无 display 条件键缺失保持）。

比较口径与 JAVA-01/02 相同：各自序列化 JSON 文本再解析比树（整型宽度归一、键序不参与；
键存在性、显式 null、值形状全部参与）。单元 oracle 边界沿用 JAVA-01 教训：只证明
「行/装配→视图」序列化等价与比较链等价；真实 HTTP 运行时用例（HandoverHttpTest 的
proxy/target-change 链等）由主代理门禁实跑。

## 4. 门禁证据

实施代理已跑（非产品门禁）：

- 定向单测：`JAVA_HOME=graalvm-jdk-21.0.7 ./mvnw -pl backend -Dtest='ProxyWireShapeTest,TargetChangeWireShapeTest,TransferWireShapeTest,LoanWireShapeTest' -DfailIfNoTests=false test`
  → **Tests run: 47, Failures: 0, Errors: 0**（Proxy 9、TargetChange 9、Transfer 17、Loan 12）。
- 全仓编译：`./mvnw -pl backend -q test-compile` → exit=0。
- Checkstyle 项目口径：`./mvnw -pl backend -Pquality checkstyle:check@check-java-conventions`
  → exit=0（首轮抓出四个新文件缺末尾换行 + ProxyViews 一个无用导入，修复复跑，同 JAVA-02 先例）。

产品门禁完整 Java 序列（与 JAVA-03 合并树单次实跑，2026-11-21）：**83 步全部 exit=0**，
surefire 聚合 **768 项测试 0 失败 0 错误**，`verify_java_reports` 通过（Checkstyle 0、
SpotBugs 0），覆盖率 **LINE 27163/29185（93.07%）、BRANCH 9863/13029（75.70%）**。
主代理合并树复跑单元判据 60/60。证据：[JAVA-03-04-java-gate.txt](evidence/JAVA-03-04-java-gate.txt)。
本批契约零改动。SpotBugs 风险点已按先例预防：含 List/Map 分量 record 全部紧凑构造器防御拷贝。

**主代理裁决点复核（2026-11-21）**：
- 裁决点①两处 targetChangeContext HIGH：`git diff` 文件集证实上游闭包全部落在本批纳入集
  （三域适配器 + 域内 applyChangedTarget），无排除包外溢；HIGH 来自执行流权重。接受。
- 裁决点②RepairService 类级 HIGH：7 个 diff hunk 逐一核实仅限
  targetChangeContext/proxySnapshot/proxyReceipt 三信封方法，其余业务逻辑未动。接受。
- 裁决点③索引损坏与 clean 重建：登记属实（AGENTS.md 索引计数自动更新可见）。接受。
- 比较语义独立核实：编排方 same()、RepairService 两处、TransferService.proxyReceipt
  均保留改前文本往返；TransferWireShapeTest 仅改 record 构造引用（断言一字未动）。
- Context 非密封协变设计（TransferViews.TargetChangeContext implements Context）：
  避免 transfer↔targetchange 模块环，ArchUnit 守护通过，接受。

## 5. 影响分析（GitNexus）

编辑前按 AGENTS 执行 `impact -d upstream`（Node 24 前缀 + --repo）。**索引故障与修复
（如实登记）**：初次 impact 时索引陈旧（缺 JAVA-01 的 TransferViews），`analyze` 重建后
RepairService/RequisitionHandoverService 的方法条目仍损坏（uid/filePath 为空、Java 方法
挂上 TS 调用者、虚高 CRITICAL 计数）；`clean` 全量重建后索引恢复正常，方法级 impact
可信。以下为 clean 重建后的权威结果：

- `ProxyReceiptPort`：MEDIUM 8（3 实现 + 编排方 + 附件 port；DI 动态分派边界已注明，
  实际消费点已用 grep 逐一核实）。`TargetChangePort`：MEDIUM 6。
- 九个信封方法：transfer proxySnapshot LOW 3 / proxyReceipt LOW 1；requisition
  proxySnapshot LOW 3 / proxyReceipt LOW 1 / targetChangeContext LOW 9；repair
  proxySnapshot LOW 3 / proxyReceipt LOW 1。
- **`TransferService.targetChangeContext` HIGH 10、`RepairService.targetChangeContext`
  HIGH 9（裁决点，见 §6.2）**：上游闭包逐一核实为 applyChangedTarget（域内）、
  域适配器 context/apply、workflow propose/decide、ItemTargetChangeService/Controller、
  TargetChangeController——**全部在本批规格明令纳入的改动集内**，无任何排除包符号。
- `RepairService` 类级 HIGH 15（scrap/loss 消费的是本批未改的其他方法；本批仅改三个
  信封方法，diff hunk 核实方法级边界）。
- 其余（workflow、四个控制器、编排方、六个适配器、TransferViews、附件 port）：LOW。
- `detect-changes`：39 文件 131 符号（含并行代理的 scrap 改动、用户未提交小程序改动与
  AGENTS.md，均不属本批），受影响执行流 0，聚合 risk low。

## 6. 偏差与遗留

1. **Context 接口非密封（形状裁决，规格允许「密封或按域 record」）**：transfer context
   record 留在 TransferViews（JAVA-01 已交付形状），implements targetchange 的 Context
   接口（target() 协变返回 TargetWire）。若把三域 context record 集中进 targetchange 包，
   需 import TransferViews.TargetWire/SourceView/PhysicalView，制造 transfer↔targetchange
   模块环（ArchUnit TOP_LEVEL_MODULES_ARE_ACYCLIC 禁止）。且 context 从不被反序列化回
   类型化形状（workflow 只做树比较与序列化存储），无需 Jackson 多态，非密封接口零风险。
   ProxySnapshot 则必须密封+EXISTING_PROPERTY（编排方 execute 要从存储 JSON 回放）。
2. **裁决点：两处 targetChangeContext HIGH impact**。按规格「HIGH+ 停下登记」要求登记：
   两处 HIGH 的完整上游闭包（10/9 符号）经逐一核实是本批规格明令纳入的改动集子集
   （workflow propose/decide、三域适配器、两个控制器、ItemTargetChangeService），无排除
   包、无计划外符号；HIGH 标签来自关键执行流权重而非爆炸半径外溢。规格本身强制类型化
   这些消费点，无替代实施路径可避开该标签。据此在登记后继续实施，未扩大爆炸半径；
   请主代理/用户复核此裁决。RepairService 类级 HIGH 同理登记：本批方法级编辑不触及
   scrap/loss 消费的任何符号。
3. **排除清单（一个文件未碰，按规格）**：`scrap/`、`loss/`、`assetimport/`、`loan/`、
   `inventory/`、`asset/` 包（工作树中 scrap 改动属并行代理）；内部行 Map、审计 payload、
   幂等指纹 digest 输入（ProxyReceiptPayloads/fingerprint/hash 全未动）；
   `BorrowRequestDatePort`；`apps/web`、`apps/miniprogram`、`contracts/`、
   `packages/api-types`；用户未提交的小程序改动与 AGENTS.md。
4. **原始 Map/Object 分量保留（深度边界，非遗漏）**：RepairSnapshot.target/loan、
   RepairContext.target 是 asset/loan 包与存储 JSON 产出的原始 Map（排除包不可类型化）；
   deliveredAt/lastPhysicalTime 保持 repair wire() 的 Object 透传；Confirmation 六个
   DATETIME 分量与 Handover 两个时间分量保持 Object（改前即 JDBC 原值透传，不臆断驱动
   形态，对照 JAVA-01 OpenLoanRef.expectedReturnDate 先例）。
5. **requisition clean() 投影存在两份**：service 本体仍服务 receiptDetail 等未改出口，
   TargetChangeViews.handover() 是 targetChangeContext 专用的逐字冻结副本（规格限定只改
   三方法，不能删 service clean()）。两份算法由 oracle 判据锁定一致。
6. 旧幂等记录回放经 record 再序列化键序归一为声明序（旧 Map.of/LinkedHashMap 键序本非
   契约），语义等价，24 小时窗口后自然消失（同 JAVA-01 §6.5/JAVA-02 §6.7）。
7. repair version/ownershipVersion/assetVersion 由 JDBC 原值 Long 改 long 分量：V43 核实
   BIGINT NOT NULL，文本往返归一宽度，wire 等价（同 JAVA-01 totalQuantity 先例）。
8. 工作树中并行代理的 scrap/loss 改动不属本批，未混入、未触碰。

## 7. 下一步

通用信封接口类型化收口后，JAVA-01 §6.1/JAVA-02 §7 登记的跨域遗留仅剩
`LoanRepairPort`/`LoanReturnRepairPort`（repair/scrap/inventory 深耦合，需与 scrap 批协调）
与 `BorrowRequestDatePort`（business 包）。建议主代理门禁实跑后按小域继续；
本批两处 HIGH 裁决点请优先复核。
