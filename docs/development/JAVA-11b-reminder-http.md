# JAVA-11b · reminder 域 HTTP 出口 Map→record 类型化

> 用户指定的后端结构批（HTTP 出口波次第一批），不进入 D6-01～D6-27 台账、不占用其状态。
> 日期：2026-11-21 · 分支：main · 基线：`d703d6e`
> 状态：主代理审计通过，波次一合并门禁通过（见文末证据节），已收口提交。

## 1. 背景与红线

复刻 JAVA-01/02 标准：reminder 域五个控制器（`MessageController`、`DutyController`、
`ConfigurationAnomalyController`、`ReminderFollowupController`、`WechatSubscriptionController`）
的全部 HTTP 出口类型化；wire 逐字段等价；契约 `contracts/fams-core-v1.yaml`、
`packages/api-types`、`apps/web`、`apps/miniprogram` 零改动；SQL、锁、事务、异常码、方法
时序、审计 payload、幂等指纹 digest 输入、内部行 Map 零改动；`loss/`、`assetimport/`、
`attachment/` 包禁触（并行代理在途）。

读码甄别结果（实施前逐文件核对）：

1. **唯一的 Map 出口是 MessageService**：`list` 返回 `Map.of(items/total/unread/page/pageSize/view)`，
   行投影 `item()` 用 `Map.ofEntries` 恒定 13 键；`read` 返回 `Map.of(kind/id/read)`。
2. **其余四控制器早已具名**：Duty（`DutyService.DutyAssignment/DutyAccount/DutyAssignmentList`，
   含类型化 replay/record 链，`Class` 直反序列化）、异常（`ConfigurationAnomalyService.AnomalyPage/
   Anomaly/TodoReference/BusinessTodoReference`，NON_NULL 条件键）、代确认（`ReminderFollowupService
   .View/Followup`）、订阅（契约模型 `MiniSubscriptionVisibility/WechatSubscriptionGrant`）。
   本批不搬移这些既有具名类型（Surgical Changes：搬入 ReminderViews 只产生引用 churn，
   且 `DutyHttpRuntimeCase` 等既有用例直接引用 `DutyService.` 嵌套类型）。
3. **BusinessReminderService / DutyEscalationService 无 HTTP 出口**：纯调度器，写
   `fams_business_reminder` 行；其 `Consumer<Map<String,Object>>`/行 Map 读取属内部行 Map
   保留项（公共协议-5），「装配泛型化」在本域落点即 MessageService 出口类型化。
4. **消息多态零出现**：TODO 与 REMINDER 经 SQL UNION 归一为同一列集（列名取第一分支），
   `item()` 对两分支输出同一 13 键——单形状 record 即可，无跨形状联合，不需要
   EXISTING_PROPERTY 密封接口，零新增键。
5. **`WechatSubscriptionPort.templateValues` 动态模板键**：已裁决保留 `Map<String,String>`
   （批规格特别甄别项）。该 Map 只流向外部微信服务商适配器（`NotificationDeliveryWorker`
   构造 `Delivery`），不经过任何 HTTP 出口（`MiniSubscriptionVisibility` 只携带模板 ID 字符串），
   故本批原样透传、不强类型化——JAVA-09 Identification 先例在本域无出口携带点。

## 2. 改动清单

主代码 2 改 1 增，测试 1 增：

| 文件 | 变化 |
|---|---|
| `reminder/ReminderViews.java`（新增） | 3 个响应 record：`MessageItem`（13 键，与冻结 `item()` 逐一对应）、`MessagePage`（items/total/unread/page/pageSize/view，List.copyOf 防御拷贝）、`MessageReadReceipt`；具名行映射 `messageItem(row, read)` 逐字复刻改前投影（含 `instant()` 双驱动形态分支） |
| `reminder/MessageService.java` | `list` 返回 `ReminderViews.MessagePage`、`read` 返回 `ReminderViews.MessageReadReceipt`；删除 `item()`/`instant()`（移入 ReminderViews 具名映射）；DB 查询与参数装配时序零改动（items 映射→total COUNT→unread COUNT 同序）；`readIds/ids/collect` 内部行 Map 未动 |
| `reminder/MessageController.java` | 两端点返回具名类型；请求参数、校验、`Idempotency-Key` 头与异常处理器一字未动 |
| `reminder/ReminderWireShapeTest.java`（新增） | 8 条 wire 等价/回放用例，见 §3 |

出口 → record 对照（五控制器全量）：

| 出口 | record | 本批动作 |
|---|---|---|
| MessageService.list | `MessagePage`（`List<MessageItem>` + total/unread long + page/pageSize int + view） | 新类型化 |
| MessageService.read | `MessageReadReceipt(kind,id,read)` | 新类型化 |
| DutyService.current | `DutyAssignmentList` | 已具名，不动 |
| DutyService.replace（含幂等 replay） | `DutyAssignment`（replay 早已 `Class` 直反序列化） | 已具名，不动 |
| ConfigurationAnomalyService.list/detail | `AnomalyPage`/`Anomaly`（NON_NULL resolvedAt、NON_EMPTY relatedBusinessTodos） | 已具名，不动 |
| ReminderFollowupService.get/record | `View`/`Followup` | 已具名，不动 |
| WechatSubscriptionService.visibility/register | 契约模型 `MiniSubscriptionVisibility`/`WechatSubscriptionGrant` | 已具名，不动 |

## 3. 判据：wire 等价测试

`ReminderWireShapeTest` 把改前 `MessageService.item()`（含 `instant()` 助手）逐字冻结为参照
oracle，页/回执装配按改前 `Map.of` 键集重建参照，比较口径与 JAVA-01/02 相同：各自序列化
JSON 文本再解析比树（整型宽度归一、键序不参与；键存在性、显式 null、值形状全部参与）。

- TODO 行（Timestamp moment、`CAST(NULL AS DATETIME)` deadline、parent 双 null、pool 行
  assignee 有无）与 REMINDER 行（moment/deadline_at 各测 Timestamp/LocalDateTime 双驱动形态、
  parent LOAN/REQUISITION、`kind:id` 已读集命中/未命中）与 oracle 比树；
- 页装配 INBOX/EXCEPTION 两 view 值、total/unread（oracle 侧 Long、typed 侧 long——文本往返
  归一）比树；`read` 回执用超 int 范围 id 验证字符串化语义；
- 幂等链：新 record 序列化→反序列化→再序列化比树；**改前形状存储 JSON（oracle 生成）反序列化
  为新 record 并等价再序列化**；另冻结 `DutyService.DutyAssignment`（本域唯一跨版本存储回放
  链，backup 显式 null 键）的存储 JSON 往返用例，证明未触及其 24 小时窗口回放；
- MessageService 的 read 端点虽带 `Idempotency-Key` 头，但实现是 `fams_message_receipt`
  UPSERT 自然幂等，不写 `fams_idempotency_record`——本批无 replay/finish 泛型化落点，
  如实登记。

单元 oracle 边界沿用 JAVA-01 教训：真实 HTTP 形状由 `auth/MessagesHttpRuntimeCase`、
`auth/HandoverHttpTest`、`auth/InventoryObservationHttpRuntimeCase`、`inbound/InboundHttpTest`
（消息中心消费方）在门禁实跑兜底。

## 4. 门禁证据

（留空，主代理按 JAVA-02 §4 同口径实跑回填：定向三 WireShapeTest、全量 test-compile、
阻塞质量与升级/运行时用例。）

实施代理已跑（非产品门禁）：

- `rtk env JAVA_HOME=graalvm-jdk-21.0.7 ./mvnw -pl backend -Dtest='ReminderWireShapeTest,TransferWireShapeTest,LoanWireShapeTest' -DfailIfNoTests=false test`
  → **Tests run: 36, Failures: 0, Errors: 0**（Reminder 8/8、Transfer 17/17、Loan 11/11）。
- `rtk env JAVA_HOME=… ./mvnw -pl backend -q test-compile` → exit=0。
- `rtk env JAVA_HOME=… ./mvnw -pl backend -Pquality checkstyle:check@check-java-conventions`
  → **0 violations**，exit=0。

## 5. 影响分析（GitNexus）

编辑前执行 `impact -d upstream`（Node 24 前缀 + `--repo`）：

- `MessageService`：LOW，1 直接依赖（`MessageController`），在本批纳入集内；0 执行流。
- `MessageController`：LOW，0 上游（HTTP 端点即叶子）。
- 同名伪边：无（grep 核实 `MessageService` 全仓仅 controller 引用；跨包无重名符号）。
- 未编辑 Duty/Anomaly/Followup/Subscription 任何符号（已具名），无对应 impact 需求。

## 6. 偏差与遗留

1. **排除清单（保留 Map/不动，按规格）**：
   - `WechatSubscriptionPort.Delivery.templateValues`（动态模板键，已裁决保留 Map，JAVA-09
     Identification 先例；无 HTTP 出口携带点，原样透传）；
   - `MessageService` 内部行 Map（`readIds/ids/collect` 对 `db.queryForList` 行的消费）、
     `BusinessReminderService.each/record`、`DutyEscalationService.staged/targets` 行读取：
     内部行 Map，不动；
   - 审计 payload（Duty audit `Map.of("roleCode",…)`、followup/订阅 `JSON_OBJECT` detail）、
     幂等指纹 digest 输入（`DutyService.digest`）：未动；
   - `loss/`、`assetimport/`、`attachment/` 包：零触碰；契约/类型包/前端：零改动。
2. **条件键甄别**：本批新类型化出口无 `@JsonInclude(NON_NULL)` 需求——改前 `Map.of/Map.ofEntries`
   拒绝 null 值，等价语义即「全键恒在、缺值以空串承载」（`parentBusinessId/parentBusinessType/
   assigneeAccountId/deadlineAt` 的 `""` 约定原样保留），`total/unread` 恒为数字。条件键先例
   存在于未改动的 Anomaly（NON_NULL resolvedAt + NON_EMPTY relatedBusinessTodos）与
   DutyAssignment（backup 显式 null 恒含键），已在 §3 用例冻结。
3. **null 防御差异（不可达路径，如实登记）**：改前 `item()` 对 `business_type/stage/object_stage/
   pool_type` 传原值、null 时 `Map.ofEntries` 抛 NPE（500）；新投影 `(String)` 强转后 null 会序列化为
   显式 null。四列在 DB 均 VARCHAR NOT NULL（V7 fams_todo、V65 fams_business_reminder 核实），
   两条路径在合法数据下均不可达，行为差异仅存在于改前即缺陷的畸形行场景。
4. **Duty/Anomaly/Followup 响应 record 保留在 service 嵌套**（见 §1.2），不并入 ReminderViews；
   ReminderViews 是本批新增消息中心出口的具名类型集合。后续批次若统一收敛视图层可一并裁决。
5. 工作树他会话在途文件（`AGENTS.md` 修改、`apps/miniprogram/project*.json` 未跟踪）与本批
   无关，未触碰；主树无其他后端在途改动与本批文件交集。
6. **裁决点：无。** 未遇到形状不可判定、HIGH/CRITICAL impact、需改排除接口或疑似改前缺陷；
   消息多态与 templateValues 两个特别甄别项均落为「零改动」结论（§1.4/§1.5）。

## 7. 下一步

HTTP 出口波次后续批（11c～17）按规划继续其余域；本域无残留 Map 出口。

## 主代理合并门禁（2026-11-22，波次一三批合并树单次实跑）

排除并行 WIP 副本上完整 `verify_java_quality`：83 步全部 exit=0，surefire 聚合
**844 项测试 0 失败 0 错误**（含 LossHttpRuntimeCase、Messages/Duty 运行时链的真实
HTTP 回归），Checkstyle/SpotBugs 0，覆盖率 **LINE 27445/29468（93.13%）、
BRANCH 9889/13063（75.70%）**；主树 18 个 WireShape 套件复跑 136/136。证据：
[JAVA-11-java-gate.txt](evidence/JAVA-11-java-gate.txt)。
