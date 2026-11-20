# JAVA-01 · transfer 域响应与跨域 Port 类型化（打样批）

> 用户指定的后端结构批（同 ARCH 系列先例），不进入 D6-01～D6-27 台账、不占用其状态。
> 日期：2026-11-20 · 分支：main · 基线：`8696b3c`

## 1. 背景与红线

后端请求体早已是类型化 record（`TransferController.Draft` 等 + `@Valid`），但**响应与跨域
Port 传参几乎全是 `Map<String, Object>`**（全仓 162 文件约 1560 处）：字段名、可空性只存在
于字符串键，编译期零保护。本批按用户裁定的三项口径执行：

1. **深度**：HTTP 响应 + 跨域 Port 类型化；内部 `JdbcTemplate` 行 Map、审计 payload、
   幂等指纹摘要输入**保留 Map**（指纹已冻结且有跨 JVM 兼容修复历史，不得改动）。
2. **分层**：维持 `com.fams.<域>` 垂直切片（ArchUnit 已守护模块无环、跨模块 Mapper 禁入、
   Controller 无事务），不做目录搬移；响应 record 集中于域内 `TransferViews`。
3. **wire 等价**：响应 JSON 形状逐字段等价（字符串 ID、显式 null 键、条件键、存储快照
   原样 JSON）。契约 `contracts/fams-core-v1.yaml` 与 `packages/api-types` **零改动**——
   本批不改任何 API 形状，故接口文档无需变更；若后续批次改变形状，必须同步契约与类型包。

## 2. 改动清单

主代码 8 改 1 增，测试 1 增（+292/−328，不含新增两文件）：

| 文件 | 变化 |
|---|---|
| `transfer/TransferViews.java`（新增） | 24 个响应 record + 行映射工厂；`Reply` 密封接口（`Detail`/`Item`/`UnavailableReceipt`）暴露幂等资源身份；`lockVersion`/`unavailableReason` 用 `NON_NULL` 复刻条件键；含 List 分量的 5 个 record 按仓库既有先例（`Reassignment`/`Target`）在紧凑构造器 `List.copyOf` 防御拷贝，消 EI_EXPOSE_REP/REP2 |
| `transfer/TransferService.java` | 15 个出口方法返回类型化视图；删除通用 `wire()`/`wireTarget()`（移入 TransferViews 的具名映射）；`replay`/`finish` 泛型化（`Class<T>` 反序列化、`Reply.id()/version()` 取资源身份，`TRANSFER_ITEM` 判定由 `containsKey("requestId")` 改为 `instanceof Item`，语义逐一对应）；`sourceAllowed`/`receiptRequired` 改读类型化快照 |
| `transfer/TransferController.java` | 15 个端点返回类型化视图，请求体 record 与校验一字未动 |
| `asset/AssetTransferPort.java` | `snapshot→Snapshot`、`validateTarget→Custody`、`complete→After`；三个 record 用 `@JsonProperty` 保持 **snake_case**——它们被原样序列化进 `source_snapshot_json`/`after_snapshot_json` 与资产事件 detail，存储形状不得变 |
| `transfer/LossTransferPort.java` | `create` 返回 `TransferViews.Detail` |
| `transfer/InventoryTransferPort.java` | `link()` 改消费类型化 Detail，删除 instanceof/Map 取值链 |
| `transfer/TransferProxyReceiptPort.java`、`transfer/TransferTargetChangePort.java` | 服务侧已类型化；**通用信封接口暂留 Map**（见 §6），边界用 `convertValue` 转换，JSON 形状不变 |
| `loss/LossService.java` | 唯一跨域消费点 `linked.get("id")` → `linked.id()`（1 行） |
| `transfer/TransferWireShapeTest.java`（新增） | 16 条 wire 等价用例，见 §3 |

## 3. 判据：wire 等价测试

`TransferWireShapeTest` 把**改前的 `wire()` 投影算法逐字冻结为参照 oracle**，对每种行形状
（请求头、明细行、事件、合格资产、待接收、五类目录、人员交接七类行、操作员、物理段、
目标 wire、资产快照、保管变更、After）构造 JDBC 类型行（Long/String/Timestamp/
LocalDateTime/Boolean/java.sql.Date/JSON 串），比对 oracle 与类型化视图的序列化结果。

比较口径如实说明：断言为「各自序列化成 JSON 文本再解析后比树」。两点原因：
- 旧 `detail()` 的 `totalQuantity` 是 int、新 record 是 long，Jackson 树相等区分
  IntNode/LongNode，但序列化字节同为 `1`——文本往返把整型节点宽度归一，正是 wire 口径；
- 旧响应多处用 `Map.of`（键序随机），键序本就不是契约，对象键序不参与比较。
键存在性、显式 null、值形状全部参与比较。

另两条幂等兼容用例：类型化响应序列化→反序列化→再序列化逐字节一致；**改前形状的存储
JSON（oracle 生成）能反序列化为新 record 并等价再序列化**——覆盖 24 小时幂等窗口内旧记录
回放与升级用例的跨版本 replay。

**单元 oracle 的边界（如实登记）**：它只证明「行→视图」的序列化等价。首轮 HandoverHttpTest
实跑抓出两处 oracle 覆盖不到的真实回归，均已修复并补判据：

1. `proxyReceipt` 的快照比较被简化为 `valueToTree` 直比后，Jackson 树相等区分
   IntNode/LongNode：expected Map 经 HTTP 反序列化小数字是 Integer，record 侧是 long，
   同值被判「范围已变更」误报 409（7 条 proxy/target-change 用例失败）。修复：**恢复改前
   的「序列化文本再解析」比较**（文本往返归一整型宽度），并新增
   `proxySnapshotComparisonSurvivesPortConversionAndHttpRoundTrip` 用例模拟
   record→convertValue→HTTP 往返全链。
2. 目录 `employee` 的 `shared_eligible`：MySQL `EXISTS` 经 JDBC 返回 **Long 1/0**，
   旧 wire 原样透传（真实 JSON 是 `"sharedEligible":1` 而非布尔）；Boolean 强转致 500。
   修复：record 分量改 `long`，判据行改用 Long。同批把 `openLoans.expectedReturnDate`
   改为 JDBC 原值透传（DATE 列在驱动版本间有 java.sql.Date/LocalDate 两种形态，
   LoanService 亦双型防御），并补 LocalDate 变体等价用例。

教训入标准：后续批次凡涉及**跨边界比较语义**与**JDBC 原始类型**，必须以真实 HTTP 用例
为准，单元 oracle 只是第一道筛。

## 4. 门禁证据

环境：JDK 21.0.7（GraalVM，quality profile enforcer 强制 [21,22)）、隔离 MySQL 8.4.11
（冻结 digest，compose 策略校验通过）、Node v24.18.0（显式指定 `FAMS_TEST_NODE_EXECUTABLE`；
`rtk proxy node` 默认解析到 Node 16 会使原生 harness 的 `.ts` 执行失败）。驱动复用
`scripts/verify-d6-03a.py` 的 `create_runtime_context/start_compose/runtime_environment/
verify_java_quality/cleanup_compose`，**直接执行产品门禁的完整 Java 质量序列**（全部升级
与运行时用例 + 阻塞质量 verify + 报告断言），与产品门禁 Java 部分同口径。

口径说明：曾先用「9 定向用例 + 阻塞质量」子集跑，测试全绿但 JaCoCo 分支 41.39% 未达
75% 门槛——产品门禁的覆盖率由同一 target 下全部用例的 jacoco.exec 追加累积而成，子集
天然不足，缺口集中在未运行的 repair/scrap/inventory 域，非本批代码回归。故改跑全量序列。

- 全部升级/运行时用例（82 步）exit=0；阻塞质量首轮 726 项测试 0 失败，覆盖率
  LINE 93.03%、BRANCH 75.74%（门槛 75%）达标，SpotBugs 报 24 条 EI_EXPOSE_REP/REP2
  （新 record 的 List 分量），按仓库先例补紧凑构造器防御拷贝后复跑阻塞质量收口。
- 最终阻塞质量（`-Pquality … verify`）：exit=0；Checkstyle 0 违规、
  SpotBugs 0 发现、JaCoCo 门槛由 pom 与报告断言强制。
- 覆盖率：LINE 26866/28878（93.03%）、BRANCH 9845/13006（75.70%）（JaCoCo 报告实测）。
- 单元判据：`TransferWireShapeTest` 17/17、`TransferCalendarTest` 1/1。

未在本批重跑：TS 四道、依赖审计、应用重启恢复（产品门禁其余阶段）与 D6-02C 契约门禁
python 全量——按项目惯例在固定候选上统一重跑；本批契约零改动，ContractProbeHttpTest 已随
阻塞质量通过。

## 5. 影响分析（GitNexus）

编辑前按 AGENTS 要求执行 `impact`（索引重建于本工作树，fams-manage）：
- `TransferService`：MEDIUM，5 个直接依赖，全部在 transfer 包内。
- `AssetTransferPort`：MEDIUM，9 个影响；跨域部分（inventory/loss 输入）只引用未改动的
  `Target` record；`snapshot/validateTarget/complete` 仅 TransferService 调用。
- `LossTransferPort`：LOW，仅 LossService。
无 HIGH/CRITICAL。提交前 `detect-changes`：23 文件 85 符号（含用户未提交的小程序改动与
AGENTS 自动更新），受影响执行流为 loss→transfer 找回链 10 条，恰为本批跨域消费点，已由
LossHttpRuntimeCase 与两个 loss 升级用例覆盖。

## 6. 偏差与遗留

1. **通用信封接口仍 Map**：`ProxyReceiptPort`/`TargetChangePort` 由 transfer/requisition/
   repair 三域共同实现，属多态信封。本批只在 transfer 适配器边界 `convertValue`，接口本身
   类型化需三域同批，登记为后续跨域批候选（JAVA-02 已用于 loan 域）。
2. **内部行 Map 保留**：`request()/item()/items()/one()` 等仍返回行 Map——这是用户选定的
   深度边界，不是遗漏。
3. 契约 schema 本就比实际 wire 宽松（如 `Transfer` 未列 `decidedBy` 等键，
   `additionalProperties` 默认放行）；本批不改契约，schema 收紧属契约变更，另批裁决。
4. 工作树中用户未提交的小程序改动（14 文件）不属本批，未混入提交。
5. 旧幂等记录回放经 record 再序列化时键序归一为 record 声明序（旧 `Map.of` 键序本随机），
   语义等价；24 小时窗口后自然消失。

## 7. 下一步

打样标准已立：域内 `<域>Views` + 冻结 oracle 等价测试 + 定向运行时用例 + 阻塞质量。
建议后续批次按小域优先（scrap/loan/targetchange），大域（repair/inbound/inventory）
拆子批；每批同一判据，契约形状不变则文档不动。