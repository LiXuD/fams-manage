# JAVA-14b2 · BusinessResultNotice HTTP 出口类型化

> 用户指定的后端结构批（HTTP 出口波次三 · notice 子批），不进入 D6-01～D6-27 台账、不占用其状态。
> 日期：2026-11-22 · 分支：main · 基线：`2fe2880`（JAVA-11c 提交）
> 状态：主代理审计通过，波次三合并门禁通过（见文末证据节），已收口提交。

## 1. 背景与红线

完全复刻 JAVA-01/11a 标准：`requisition` 域 BusinessResultNotice 四个 HTTP 出口
（list / review-requests / read / objection）从 `Map<String,Object>` 收敛为具名 record；
内部 SQL 行 Map、审计 payload（`detail_json` 的 `Map.of` 装配）与幂等指纹 digest 输入保留 Map；
契约 `contracts/fams-core-v1.yaml` 与 `packages/api-types`、`apps/web`、`apps/miniprogram` 零改动。
SQL、锁顺序（read/objection 的 FOR UPDATE、幂等记录先删过期再 `FOR UPDATE` 查询）、事务边界、
异常码、路径与请求体校验逐字未动。本子批甄别要点（改前代码逐字冻结为 oracle 核对）：

1. **时间串口径**：list 的 `createdAt` 与 review-requests 的 `createdAt` 在改前就是
   `row.get(...).toString()` 原值透传（**不是** transfer/loss 域的 ISO instant 归一），
   `NoticeViews` 冻结同一表达式，Timestamp/LocalDateTime 双驱动形态都按同一 toString 输出。
2. **EXISTS→布尔**：list 的 `reviewRequested` 冻结 `"1".equals(String.valueOf(...)) ||
   Boolean.TRUE.equals(...)` 双形态谓词（驱动可能回 Long 或 Boolean）；`read` 为 `read_at != null`。
3. **id 字符串化**：id/businessId/itemId/executionId/noticeId 均在改前 `.toString()`，
   record 分量即 String；`businessType` 为 VARCHAR 直通 `(String)` 读取。
4. **content 原样 JSON**：list 的 `content` 是 `json.readTree(content_json)` 的 JsonNode 直通
   （含 `"null"` → NullNode 显式键，须在全局 `JsonConfiguration` 的 `Include.ALWAYS` mapper 下
   两侧对称，测试 mapper 已镜像该配置，同 JAVA-11c/12a 判据）。
5. **无条件键**：四出口全部 `Map.of` 装配（拒绝 null 值），每个键恒在且非空，故本批
   **没有** `@JsonInclude(NON_NULL)` 条件键；review-requests 的 `reason` 冻结
   `path("reason").asText()` 缺失回退空串语义。
6. **objection 幂等存储链**：`fams_idempotency_record.response_body_json` 存的就是该出口结果
   四键 JSON；replay 由 `readValue(..., Map.class)` 改 `readValue(..., ObjectionReceipt.class)`，
   旧存储 JSON 可反序列化进 record 并等价再序列化（测试覆盖跨版本回放）。
   read 虽带 Idempotency-Key 头但改前就不落幂等记录，无存储链。

## 2. 改动清单

主代码 2 改 1 增，测试 1 增：

| 文件 | 变化 |
|---|---|
| `requisition/NoticeViews.java`（新增） | 6 个响应 record（NoticeItem/NoticePage/ReviewRequestItem/ReviewRequestPage/ReadReceipt/ObjectionReceipt）+ 两个行映射工厂（冻结改前 lambda 表达式）；List 分量的 Page 紧凑构造器 `List.copyOf` 防御拷贝 |
| `requisition/BusinessResultNoticeService.java` | 4 个出口类型化；items 装配 lambda 换 `NoticeViews::noticeItem`/`::reviewRequestItem`；objection replay 改类型化 `readValue`（删除 `@SuppressWarnings("unchecked")`）；审计 detail_json 与幂等 payload 的 `Map.of`/`writeValueAsString` 未动 |
| `requisition/BusinessResultNoticeController.java` | 4 个端点返回类型化视图；请求体 record `Objection`、校验注解、路径与头零改动 |
| `requisition/BusinessResultNoticeWireShapeTest.java`（新增） | 7 条 wire 等价用例，见 §3 |

出口 → record 对照：

| 出口 | record |
|---|---|
| list | `NoticePage`（items: `NoticeItem` 8 键 + page/pageSize/total/unread） |
| reviewRequests | `ReviewRequestPage`（items: `ReviewRequestItem` 7 键 + total/page/pageSize） |
| read | `ReadReceipt`（id 字符串化 + read=true） |
| objection | `ObjectionReceipt`（noticeId/status/businessType/businessId，同为幂等存储形状） |

## 3. 判据：wire 等价测试

`BusinessResultNoticeWireShapeTest` 把改前 `Map.of` 装配逐字段冻结为 oracle
（`legacyWire`/`legacyListPage`/`legacyReviewPage`/`legacyObjectionWire`），构造 JDBC 类型行
（Long/String/Timestamp/LocalDateTime/JSON 串）比对，文本往返比树（整型宽度归一、
`Map.of` 键序本就非契约；键存在性与值形状参与）：

- noticeItem：已读+EXISTS=1L / 未读+0L / `review_requested` Boolean 变体 / `content_json="null"`
  显式 null 键四态；
- noticePage：有行页 + **空列表页** 与改前 Map.of 装配比树；
- reviewRequestItem：reason 有值 + **detail_json 无 reason 键回退空串** + occurred_at 双驱动形态；
- reviewRequestPage：有行/空页两态；
- readReceipt / objectionReceipt：与改前 Map 装配比树；
- 幂等回放：改前形状（oracle 生成）存储 JSON → `ObjectionReceipt` 反序列化 → 再序列化比树；
  新 record 自产存储 JSON 往返等价。

**单元 oracle 边界（JAVA-01 教训沿用）**：只证明「行→视图」序列化等价；真实 HTTP 运行时用例
由主代理门禁实跑。

## 4. 门禁证据

（留空，主代理补全量序列。）

实施代理已跑（非产品门禁）：

- 定向单测：`JAVA_HOME=graalvm-jdk-21.0.7 ./mvnw -pl backend -Dtest='BusinessResultNoticeWireShapeTest' -DfailIfNoTests=false test`
  → **Tests run: 7, Failures: 0, Errors: 0**。
- 全仓编译：`./mvnw -pl backend -q test-compile` → exit=0（期间两次失败均为并行代理
  同树并发 clean/编译的 target/ 竞态伪红，与本批文件无关，间隔重试后全绿）。
- Checkstyle（项目规则集）：`./mvnw -pl backend -Pquality checkstyle:check@check-java-conventions` → exit=0。

## 5. 影响分析（GitNexus）

编辑前按 AGENTS 要求执行 `impact -d upstream`（Node 24 前缀 + --repo，索引 fams-manage）：

- `BusinessResultNoticeService` 整体与 `reviewRequests`/`objection` 方法：全部 **LOW**，
  上游仅配对 `BusinessResultNoticeController`（包私有类，编译器封闭）。
- 方法名 `list`/`read` 的查询命中 apps/*、asset、masterdata 等文件为**同名伪边**：grep 全仓
  核实 `BusinessResultNotice` 标识符只出现在本批 Controller/Service 两个文件，真实上游闭包
  不出本域，无越批 HIGH/CRITICAL。

## 6. 偏差与遗留

1. **排除清单（保留 Map/不动，按规格）**：内部行 Map（`db.queryForList` 结果与
   `rows.getFirst()` 的 notice 行）、审计 `detail_json` 的 `Map.of` 装配与
   `writeValueAsString`、幂等 `payload_sha256` 摘要输入、`RequisitionActorContext`、
   `RequisitionFailure`——一律未动；同包 `RequisitionService`/`RequisitionViews` 等属并行
   代理，零触碰；`contracts/`、`packages/api-types`、`apps/*` 零改动。
2. **businessType 读取**：改前 `Map.of` 直通 JDBC Object 值，类型化后按 `(String)` 读取
   （VARCHAR 列 JDBC 恒回 String，序列化文本相同）；与 JAVA-11a/11b 同口径。
3. 旧存储收据经 record 再序列化键序归一为声明序（旧 `Map.of` 键序本就跨 JVM 随机），语义等价；
   `fams_idempotency_record` 24h 过期窗内重放仅影响响应键序，不涉比较逻辑。
4. **裁决点：无。** 未遇到形状不可判定、真实 HIGH/CRITICAL 上游越批或需改排除接口。

## 7. 下一步

主代理对波次三（14a/14b1/14b2/14b3 并行子批）合并门禁后收口；后续按 14c → 15 inbound →
16 repair 系 → 17 inventory 系推进。

## 主代理产品门禁（波次三合并实跑）

主代理在波次三合并树固定快照副本（rsync 排除并行会话 WIP `com/fams/local`；快照内容经核实含本波
三个新 Views 文件与五个新 WireShape 套件）实跑 `verify-d6-03a.py` 完整 Java 序列：**JAVA01_VERIFY_PASS**，
83 步 exit=0，Checkstyle/SpotBugs 0，覆盖率 LINE 93.22%、BRANCH 75.61%；日志内 74 个 surefire 阶段
汇总 401 项 0 失败 0 错误。门禁自身报告的 tests=555 与波次二同数，为消除口径歧义，五个新套件已在
该副本内直接复跑 **45/45 绿**。主树合并判据：25 个 WireShape 套件 202/202。
详见[波次证据](evidence/JAVA-14-java-gate.txt)。
