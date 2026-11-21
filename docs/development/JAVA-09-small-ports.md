# JAVA-09 · 三个小跨域 Port Map→record 类型化（RequisitionMasterDataPort / InventoryExecutorPort / AssetInventoryReadPort）

> 用户指定的后端结构批（JAVA-01～07 之后的小跨域 Port 收口），不进入 D6-01～D6-27 台账、不占用其状态。
> 日期：2026-11-21 · 分支：main · 基线：`e1eef65`（JAVA-07 提交）
> 状态：主代理审计通过（null 语义适配点核实），与 JAVA-08 合并门禁通过，已收口提交。

## 1. 背景与红线

三个小跨域 Port 的返回仍是 `Map<String, Object>` 行/行列表。本批按 JAVA-01/05 标准把
`current`、`choices`、`identify` 收口为 Port 拥有的 record：

1. **存储键形红线（最高）**：`RequisitionMasterDataPort.current` 返回行被原样写进两处存储 JSON——
   `fams_requisition_demand.target_snapshot_json`（RequisitionService resolve→insertDemands /
   decideChange，snake_case、数值 id 原样）与 `fams_requisition_allocation.approved_target_json`
   的 `referenceSnapshot`（RequisitionHandoverService.targetReferenceSnapshot，**id 与 *_id 被
   toString 字符串化**）。两条链分别以 `@JsonProperty` snake_case record（`MasterReference`）与
   字符串化投影 record（`RequisitionHandoverService.ReferenceSnapshot`）冻结原键形；
   `organization_id` 键仅 employee 行存在（其余 kind SQL 不选该列），record 以分量级
   `@JsonInclude(NON_NULL)` 复刻条件键（JAVA-01 lockVersion 先例）。employee.organization_id
   为 DDL NOT NULL（V1__create_authentication.sql:45），null 与缺席两态不会混淆。
2. **嵌入 HTTP 响应红线**：`InventoryExecutorPort.choices` 的行列表就是
   `GET /inventory(-mini)/executors` 的响应体本体（SQL 别名已是 camelCase、CAST AS CHAR 字符串）；
   `ExecutorChoice` 分量名镜像别名，序列化逐字段等价。`AssetInventoryReadPort.identify` 的行
   从不整体入 wire——消费方按自取键名取值，响应一字未动。requisition catalog 的 items 经改前
   `clean()` 投影（id 字符串化、organizationId camelCase 条件键），该投影冻结进 Port 的
   `CatalogChoice.fromRow`，wire 等价由测试证明。
3. **JDBC 原值透传**：`Identification.tagVersion/issuedAt` 为 `Object` 分量，SCAN 行原值直接绑定进
   tag-recognition INSERT…SELECT（驱动形态不做转换，JAVA-01 日期双形态教训）；测试以 assertSame
   冻结对象同一性。
4. **SQL/锁/异常码零改动**：三个 Port 与全部消费者的 SQL 文本、FOR UPDATE/FOR SHARE、
   `REQUISITION_REFERENCE_CHANGED`/`HANDOVER_TARGET_CHANGED` 等异常码一字未动。
   current() 缺行语义保持：改前返回空 Map、消费方 isEmpty 判定抛同码；改后返回 null、
   null 判定抛同码（`targetReferenceSnapshot` 处 validateChangedTarget→targets 已先行保证行存在，
   与原不可达分支等价，requireNonNull 仅固化该前置）。
5. **禁 record→Map 回退**：全批无任何 record 转回 Map 的适配层。
6. **契约与前端零改动**：`contracts/`、`packages/api-types`、`apps/*` 未触碰（wire 形状不变）。
7. **指纹/幂等核实**：RequisitionService 的 `digest()` 输入为请求体 record，不含 Port 行；
   HandoverService 对 `referenceSnapshot` 的回读走 `json.readTree` 树复制（line 931），
   键序归一不参与比较语义。存储 JSON 本批只做「写侧等价」。

## 2. 改动清单

主代码 8 改，测试 1 增（+188/−59，不含新增）：

| 文件 | 变化 |
|---|---|
| `masterdata/RequisitionMasterDataPort.java` | 新增嵌套 record `MasterReference`（@JsonProperty snake + NON_NULL organization_id，`fromRow` 投影，id/version long、organizationId Long）与 `CatalogChoice`（冻结 clean() wire 形：String id/version long/NON_NULL String organizationId）；`current→MasterReference`（缺行 null）、`choices→List<CatalogChoice>`；SQL 一字未动 |
| `auth/InventoryExecutorPort.java` | 新增 `ExecutorChoice(String id, String name, String organizationId)` + `fromRow`；`choices→List<ExecutorChoice>`；`eligible` 与 SQL 未动 |
| `asset/AssetInventoryReadPort.java` | 新增 `Identification(String id, String assetCode, String name, Object tagVersion, Object issuedAt)` + `fromRow`（CODE 行 tag 分量为 null）；`identify→List<Identification>`；`currentSnapshot`（规格范围外）未动 |
| `requisition/RequisitionService.java` | catalog 删除 `.map(this::clean)`（投影入 Port）；followup/reference/resolve/revalidateTargets 改读 record 访问器；insertDemands/decideChange 的 category 强转改 `MasterReference`、`text(category,"name")→category.name()`；两处 target_snapshot_json 组装点直接嵌 record（存储形不变）；随之删除两个失效 `@SuppressWarnings("unchecked")` |
| `requisition/RequisitionHandoverService.java` | `targetReferenceSnapshot` 改装配 `ReferenceSnapshot.of(...)`（包私有 record，字符串化 id/organization_id、数值 version，声明序=id,name,status,version,organization_id 冻结改前 LinkedHashMap 键形）；`targets()` 改读访问器 |
| `inventory/InventoryService.java` | `executorChoices` 返回 `List<ExecutorChoice>`（直传） |
| `inventory/InventoryController.java` | `/executors` 端点返回类型同改（wire 形状不变，JAVA-05 LoanController 先例） |
| `inventory/InventoryFindingService.java` | `rows.getFirst().get("id").toString()→.id()`（1 行） |
| `inventory/InventoryObservationService.java` | `asset.get(...)` 六处改访问器；TASK_OUTSIDE 响应 Map 键与值不变；INSERT…SELECT 绑定 `asset.id()/tagVersion()/issuedAt()` 原值 |
| `requisition/SmallPortsWireShapeTest.java`（新增） | 7 条 wire/存储等价判据，见 §3 |

出口 → record 对照：

| 出口 | 改前 | 改后 |
|---|---|---|
| RequisitionMasterDataPort.current | `Map`（空 Map 表缺失） | `MasterReference`（null 表缺失） |
| RequisitionMasterDataPort.choices | `List<Map>` | `List<CatalogChoice>` |
| InventoryExecutorPort.choices | `List<Map>` | `List<ExecutorChoice>` |
| AssetInventoryReadPort.identify | `List<Map>` | `List<Identification>` |

## 3. 判据：wire/存储等价测试

`SmallPortsWireShapeTest`（com.fams.requisition，贴近含存储红线 consumers；跨 Port 用
public `fromRow` 工厂 + 同包 `ReferenceSnapshot`）把改前形状逐字冻结为 oracle：

1. `catalogChoiceMatchesLegacyRequisitionClean`：RequisitionService.clean 全算法冻结为
   `legacyClean`，category/employee 行投影等价 + catalog items 信封等价。
2. `catalogChoiceKeepsOrganizationIdOnlyOnEmployeeRows`：employee 含 organizationId、
   organization 行该键缺席（NON_NULL 复刻条件键）。
3. `masterReferenceKeepsPersistedTargetSnapshotShape`：raw JDBC 行 map 序列化（改前存储）vs
   `MasterReference` 序列化等价；target_snapshot_json 三键组装等价；断言存储仍为数值 id snake 形。
4. `handoverReferenceSnapshotKeepsStringifiedStoredShape`：改前 LinkedHashMap 拷贝+
   `replaceAll` 字符串化算法冻结为 oracle vs `ReferenceSnapshot` 等价；version 保持数值、
   id/organization_id 为字符串。
5. `executorChoicesMatchRawHttpShape`：HTTP body 即行列表；改前行 map/列表 vs  record/列表等价；
   null name 显式键保留。
6. `identificationKeepsRawJdbcValuesForPassThroughColumns`：tagVersion/issuedAt **assertSame**
   原 JDBC 对象；TASK_OUTSIDE 响应组装等价。
7. `codeIdentificationCarriesNoTagColumnsAndConsumersOnlyReadIdentity`：CODE 分支无 tag 列 →
   null 分量；finding 消费只读 id 值等价。

比较口径沿用 JAVA-01/05：各自序列化 JSON 文本再解析比树（整型宽度归一、键序不参与；
键存在性、显式 null、值形状参与）。单元 oracle 边界如实登记：真实 HTTP 运行时用例
（InventoryHttp*/HandoverHttp* 的 catalog、/executors、identify 链路）由主代理合并门禁实跑。

## 4. 门禁证据（实施代理本地，非产品门禁）

- 定向单测（graalvm-jdk-21.0.7）：
  `-Dtest='SmallPortsWireShapeTest,LoanWireShapeTest,TransferWireShapeTest'` →
  **Tests run: 35, Failures: 0, Errors: 0**（SmallPorts 7/7、Loan 11/11、Transfer 17/17）。
- 全仓编译：`./mvnw -pl backend -q test-compile` → exit=0（含 JAVA-08 并行代理当时在途的
  loan/repair 改动，未触发隔离副本预案）。
- Checkstyle 项目口径：`-Pquality checkstyle:check@check-java-conventions` → **0 violations**。
- 未跑完整产品门禁、未提交（按规格）。

**主代理合并门禁（与 JAVA-08 合并树单次实跑，2026-11-21）**：
83 步全部 exit=0，surefire 聚合 **790 项测试 0 失败 0 错误**，
Checkstyle/SpotBugs 0，覆盖率 **LINE 93.09%、BRANCH 75.72%**；
合并树复跑单元判据 82/82。证据：
[JAVA-08-09-java-gate.txt](evidence/JAVA-08-09-java-gate.txt)。

## 5. 影响分析（GitNexus）

编辑前 `impact -d upstream`（Node 24 前缀 + --repo）：

- `RequisitionMasterDataPort`：LOW，4 直接依赖（requisition 两服务 import/构造器/访问）。
- `InventoryExecutorPort`：LOW，2 直接依赖（InventoryService import/构造器）。
- `AssetInventoryReadPort`：LOW，4 直接依赖（inventory 两服务 import/构造器）。
- 方法级：`targetReferenceSnapshot`/`executorChoices`/`insertDemands`/`currentSnapshot` LOW；
  `catalog`/`targets`/`reference`/`identify` 返回 UNKNOWN（索引同名歧义），以类级 LOW 闭包 +
  全量 grep 调用点枚举（§2 所列文件即全集）覆盖。无 HIGH/CRITICAL。
- 消费点落位核查：grep 确认三个 Port 的全部调用点均在允许文件内，
  **无一落在排除包（loan/repair/scrap/loss）或排除文件（InventoryDifferenceService、
  InventoryLateDamageService、InventoryRepairPort、InventoryRepairPhotoReadService）**。

## 6. 偏差与遗留

1. **排除清单（按规格零触碰）**：`repair/`、`scrap/`、`loss/`、`loan/` 包与四个 inventory
   排除文件（JAVA-08 并行代理边界）；`AssetRepairPort`/`AssetLossPort`/`AssetScrapPort` 等
   asset 其余方法；审计/指纹/内部行 Map（如 `InventoryService.referenceChoices` 的 inventory
   自有 references 端口、`AssetInventoryReadPort.currentSnapshot`）；工作树中用户未提交的
   小程序改动与 JAVA-08 在途 loan/repair 改动未混入。
2. **inventory HTTP Map 响应批再收口（登记遗留）**：本批只类型化了进入该域的 Port 行；
   `InventoryController` 其余端点（list/detail/review 等）的 Map 信封、catalog identify 的
   `Map.of` 响应装配，留待 inventory HTTP 批统一收口（本批 wire 一字未动）。
3. **current() 缺行表示**：空 Map → null。改前 `Map.of()` 与 `isEmpty()` 判空语义、
   异常码逐一保持；`targetReferenceSnapshot` 的改前「空 map 存 {}」分支经
   validateChangedTarget→targets 前置证明不可达，requireNonNull 固化该不变式。
4. **fromRow 工厂 public**：跨包判据测试（单测试类规格）要求三个 Port 的行投影可寻址；
   工厂仅接受内部行 Map、不出 HTTP 面，属端口内部投影的可测性暴露，与 JAVA-05 包私有
   `choice(Map)` 语义等效。
5. **JDBC 行值假定**：`fromRow` 沿用改前隐式假定（id/version 为 Number、name/status 为 String、
   executor 三列已 CAST CHAR 为 String）；CAST 列的驱动形态本就由 SQL 固定。
6. **裁决点：无。** 未遇到消费点落排除文件、形状不可判定或 HIGH/CRITICAL impact。

## 7. 下一步

主代理在 JAVA-08 收口后的固定候选上合并重跑产品门禁完整 Java 序列（含 catalog、
`/inventory/executors`、identify、handover target-change 与 requisition change 的 HTTP 运行时用例），
并回填 §4 证据；契约零改动，契约门禁无需变更。
