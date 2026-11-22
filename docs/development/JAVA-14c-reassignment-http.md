# JAVA-14c · requisition 域「改派/不可声明」HTTP 出口类型化 + get 兼容包装删除

> 用户指定的后端结构批（HTTP 出口波次 · requisition 改派子批），不进入 D6-01～D6-27 台账、不占用其状态。
> 日期：2026-11-26 · 分支：main · 基线：JAVA-14b1 主树工作区（typed `getDetail` + `get` 兼容包装在位）
> 状态：主代理审计通过，波次三合并门禁通过（见文末证据节），已收口提交。

## 1. 背景与红线

完全复刻 JAVA-01/11a/14b1 标准：HTTP 响应类型化；内部行 Map、审计 payload、幂等指纹输入保留 Map；
契约 `contracts/fams-core-v1.yaml`、OpenAPI 生成类、`packages/api-types`、前端零改动；SQL 语句、
锁顺序、异常码、HTTP 状态码、响应头、端点路径一字未动。本批同时落实 14b1 批记录 §6-1 的裁决点：
删除 `RequisitionService.get` 的 `Map<String,Object>` 兼容包装，把 `requisitions.get` 的最后 2 处
消费面（`RequisitionReassignmentService.declareUnavailable/reassign`）切到 typed `getDetail`。

触碰文件仅 5 改 1 测 1 记录：`RequisitionReassignmentController` / `RequisitionReassignmentService` /
`RequisitionUnavailabilityController` / `RequisitionService`（仅删包装）/ `RequisitionViews`（仅追加）
+ 新建 `ReassignmentWireShapeTest` 与本记录。同包 BusinessResultNotice*/ProxyConfirmation*/
MiniProxyAttachment*/NoticeViews 与 auth、masterdata、contracts、apps 零触碰（工作树中它们的改动
属并行批基线，非本批 diff）。`RequisitionHandoverService` 的 3 处调用点 14b1 已改 `getDetail`，
本批零随动、响应装配类型未调。

三出口改前形状逐字段冻结（改前代码原样复制为 oracle 核对）：

1. **inspect**：`LinkedHashMap(Map.of(...))` 六键 + 条件键 `unavailableReason`（仅当已申报不可处理
   时 put）。`id` 为 `Long.toString(id)`，`version`/`ownershipVersion` 为 JDBC 原值（BIGINT→Long，
   文本往返归一为数字，Transfer `ReassignmentSnapshot` 先例同口径取 long 分量），
   `processorAccountId` 为 toString 字符串，`items` 为 `checks()` 行 Map 的 6 键投影（含
   `lockVersion` 非 pending 取 `-1L` 分支），`operators` 为 `{id:String,name}` 两键。
   条件键在全局 JsonConfiguration inclusion=ALWAYS 下用 `@JsonInclude(NON_NULL)` 复刻。
2. **declareUnavailable/reassign**：出口体就是 `requisitions.get` 的 Detail 树（14b1 兼容包装
   ≡ `convertValue(getDetail)`），本批直接返回 `RequisitionViews.Detail`；幂等回放分支把旧存储
   JSON 从 `readValue(Map/TypeReference)` 改为 `readValue(Detail.class)`，回放树与存储树逐键等值
   （判据 §3-3）。落库 `json.writeValueAsString(result)` 由 Map 序列化变 record 序列化——record
   声明序与 `convertValue` 出的 LinkedHashMap 迭代序同源，键序也不变。
3. **锁顺序**：`inspect` 旧装配在 `Map.of` 实参位求值 `checks(id)`（demand/allocation/asset/lock
   FOR UPDATE），随后才读 unavailability 审计。本批把 `checks(id)` 提为局部变量、`declared` 查询
   保持其后，DB 读序列（processing→owner→operators→checks→declared）逐条不变，并加注释锁定。
4. `processing()`/`owner()`/`checks()` 私有行 Map、reassign 比对逻辑、审计 `detail_json`
   （含 `"items", checks` 原 Map 落库）、幂等指纹 `digest` 输入一律未动。

## 2. 改动清单与出口映射

| 文件 | 变化 |
|---|---|
| `requisition/RequisitionViews.java`（追加） | 新增 `ReassignmentSnapshot`（含条件键 `unavailableReason` NON_NULL；List 分量紧凑构造器 `List.copyOf`）、`ReassignmentItem`、`Operator` 三个 record 与 `reassignmentItem`/`operator` 包内工厂；既有 record 语义零改动 |
| `requisition/RequisitionReassignmentService.java` | `inspect` 返回 `ReassignmentSnapshot`（typed 装配替换 Map.of 快照，DB 读序不变）；`declareUnavailable`/`reassign` 返回 `RequisitionViews.Detail`，两处 `requisitions.get` 改 `getDetail`，两回放分支 `readValue(..., Detail.class)`；`reassign` 的 `@SuppressWarnings("unchecked")`（原 `Map.class` 裸类型）随类型化删除；`LinkedHashMap` import 移除 |
| `requisition/RequisitionReassignmentController.java` | `inspect` 返回 `ReassignmentSnapshot`、`reassign` 返回 `Detail`；请求体 record、校验、路径、`Idempotency-Key`/`If-Match-Version` 头一字未动 |
| `requisition/RequisitionUnavailabilityController.java` | `declareUnavailable` 返回 `Detail`；双路径映射、校验未动 |
| `requisition/RequisitionService.java` | **仅**删除 `get(Actor,long)` 兼容包装（14b1 §6-1 裁决收口）；`getDetail` javadoc 同步改写；其余零改动 |
| `requisition/RequisitionHandoverService.java` | 零改动（3 处 `getDetail` 调用点与响应装配 14b1 已就位，「允许的最小随动」未触发） |
| `requisition/ReassignmentWireShapeTest.java`（新增） | 7 条 wire 等价用例，见 §3 |

出口 → 类型对照：

| 旧 Map 键形（legacy 装配） | 新 record |
|---|---|
| inspect：`id/version/ownershipVersion/processorAccountId/items/operators` + 条件 `unavailableReason` | `ReassignmentSnapshot`（items=`ReassignmentItem` 6 键，operators=`Operator` 2 键） |
| declareUnavailable 幂等/新执行 body：`get` 的 convertValue(Detail) Map | `Detail implements Reply`（同树，判据 §3-2/3/4） |
| reassign 幂等/新执行 body：同上 | `Detail implements Reply` |

## 3. 判据：wire 等价测试

`ReassignmentWireShapeTest`（7 用例全绿）把改前三出口装配逐字段复制为 legacy oracle，JSON 文本
往返树比较（key 序无关）：

1. **inspect 快照** ≡ legacy `LinkedHashMap(Map.of(...))` 六键装配（JDBC 原值 Long/Integer 行形
   态）；**条件键双态**：`unavailableReason` 申报态有键等值、未申报态整键缺失
   （`has/assertFalse` 直接断言）；`ReassignmentItem` 逐行 ≡ legacy checks Map（含 pending 取
   活锁 `lockVersion` 与非 pending `-1L` 两分支）、`Operator` ≡ legacy `Map.of("id","name")`。
2. **get→getDetail 消费面等价**：旧兼容包装 `convertValue(Detail→Map)` 序列化 ≡ record 直接
   序列化 ≡ legacyClean detail 树（三方比树，证明删除包装后 declareUnavailable/reassign 的
   响应与落库回执逐值不变）。
3. **显式 null 甄别**：Detail 树 `sourceRequestId`、`demands[0].preferredBrand`、
   `ownership[0].archivedAt` 键存在且为 null。
4. **幂等旧形状回放**（`fams_idempotency_record.response_body_json` 永久保留，跨版本必测）：
   legacy detail 树 → `readValue(Detail)` → 再序列化比树；typed Detail 自往返恒等。
   回放树 fixture 用与 14b1 同形的全 18+9 键行集。

**单元 oracle 边界（14b1 沿用）**：只证明「装配→视图→存储/回放」序列化等价。改派链真实 HTTP
运行时用例需容器 MySQL/Testcontainers，本批环境跑不动，按计划由主代理合并门禁实跑。

## 4. 自测（非产品门禁）

- 定向判据：`JAVA_HOME=graalvm-jdk-21.0.7 ./mvnw -pl backend -Dtest='ReassignmentWireShapeTest,RequisitionWireShapeTest' -DfailIfNoTests=false test`
  → **Tests run: 22（7+15）, Failures: 0, Errors: 0**（14b1 判据在本批删包装后仍全绿）。
- 邻域纯单元回归：`-Dtest='SmallPortsWireShapeTest,BorrowDateWireShapeTest,ProxyReceiptPayloadsTest,ProxyConfirmationWireShapeTest,TransferWireShapeTest'`
  → **Tests run: 38, Failures: 0, Errors: 0**。
- 全仓编译：`./mvnw -pl backend -q test-compile` → **exit=0**（含并行批 WIP 共存树）。
- Checkstyle：`./mvnw -pl backend -Pquality checkstyle:check@check-java-conventions` →
  **BUILD SUCCESS**，新文件末尾换行合规。
- 未跑：SpotBugs/JaCoCo/真实 HTTP 运行时用例与 `scripts/verify-d6-03a.py` 全量门禁——按任务
  红线留给主代理合并门禁；EI_EXPOSE_REP 风险按先例在构造侧消解（List 分量 `List.copyOf`，
  其余分量全为不可变标量）。

## 5. 影响分析（GitNexus）

编辑前执行 `impact -d upstream --repo fams-manage`（Node 24 前缀，`-u`/`-f` 消歧）：

- `RequisitionReassignmentService`（类级）：**LOW**，上游 2（两控制器构造点，均本批文件）。
- `inspect`/`declareUnavailable`/`reassign`（按文件消歧后）：各 **LOW 1**，唯一上游分别
  `RequisitionReassignmentController.inspect/reassign`、`RequisitionUnavailabilityController`。
- `RequisitionService.get`（uid 消歧）：索引报 **HIGH 61** 为同名伪边聚合——候选清单含
  `RequisitionController.get`（14b1 已改调 `getDetail`）、HandoverService deliver/warehouseAction/
  replay（14b1 已改 `getDetail`，索引过期边）及本域其余 `get` 同名候选。grep 全仓核实真实消费
  面 = ReassignmentService 2 处（本批改造）+ 测试注释 1 处（非代码引用），删除包装后全仓编译
  通过即封闭证明。
- 索引不含本批新符号（`ReassignmentSnapshot` 等）与并行批 WIP 新符号属正常。

## 6. 偏差与遗留

1. **14b1 §6-1 裁决点落地：`RequisitionService.get` 兼容包装已删除**。证据：主代码 grep
   `requisitions.get(` 归零、`get(Actor,long)` 方法体删除、`getDetail` javadoc 改写为单一装配源
   说明；消费面等价由判据 §3-2 三方比树冻结（legacy clean 树 ≡ convertValue Map ≡ record）。
2. `RequisitionWireShapeTest` 第 78 行注释「RequisitionService.get keeps returning a Map」在本批
   后成为过期描述（其断言用内联 `convertValue`，不引用被删方法，编译与语义均不受影响）。该文件
   不在本批允许触碰清单内，未顺手修改，留待主代理合并时一并归位。
3. `inspect` 的 `version`/`ownershipVersion` 分量取 long（Transfer `ReassignmentSnapshot` 先例）：
   改前透传 JDBC 原值 Long/Integer，JSON 文本往返归一为同一数字节点，树等值由判据 §3-1 冻结；
   不存在 Object 直通必要（DATETIME 形状不定风险不适用数值列）。
4. **落库回执键序**：新存 JSON 由 record 声明序序列化，与旧 `convertValue` Map 的迭代序同源
   （同一 Jackson 组件序），逐字节一致；历史存量回执（旧 Map.of 随机键序、14b1 前 convertValue
   产物）经 §3-4 回放判据覆盖，语义等价。
5. SQL 文本、参数顺序、FOR UPDATE 锁顺序（含 §1-3 的 inspect 读序锁定）、异常码、状态码、
   路径、幂等 `operation_id`/`http_status`/`resource_type` 零改动。
6. `declareUnavailable` 与 `reassign` 的回放谓词差异（前者不过滤 `expires_at`、后者过滤）为改前
   既有行为，原样保留未顺手统一。
7. 契约/OpenAPI/前端：零改动（无 API 形状变化）。

## 7. 下一步

- 主代理合并门禁：全量 Java 质量序列（含 SpotBugs/JaCoCo）+ 改派链真实 HTTP 运行时用例实跑。
- requisition 域出口波次收口后，剩余观察项：`inspect` 纯校验式全量 checks 装配与内部行 Map 的
  进一步 record 化（非出口判据，可选）。

## 主代理产品门禁（波次三合并实跑）

主代理在波次三合并树固定快照副本（rsync 排除并行会话 WIP `com/fams/local`；快照内容经核实含本波
三个新 Views 文件与五个新 WireShape 套件）实跑 `verify-d6-03a.py` 完整 Java 序列：**JAVA01_VERIFY_PASS**，
83 步 exit=0，Checkstyle/SpotBugs 0，覆盖率 LINE 93.22%、BRANCH 75.61%；日志内 74 个 surefire 阶段
汇总 401 项 0 失败 0 错误。门禁自身报告的 tests=555 与波次二同数，为消除口径歧义，五个新套件已在
该副本内直接复跑 **45/45 绿**。主树合并判据：25 个 WireShape 套件 202/202。
详见[波次证据](evidence/JAVA-14-java-gate.txt)。
