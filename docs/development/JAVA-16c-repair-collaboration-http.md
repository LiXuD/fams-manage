# JAVA-16c · repair 域「协同处理」HTTP 出口类型化

> 用户指定的后端结构批（HTTP 出口波次 · repair 协同子批），不进入 D6-01～D6-27 台账、不占用其状态。
> 日期：2026-11-22 · 分支：main · 基线：`699724a` + JAVA-16a 在途未提交批（RepairController/RepairService/RepairPhoto*/RepairReassignmentController/RepairViews 22 record 为 16a 树内基线，本批只追加不修改）+ JAVA-16b 并行在途（RepairFactory*，本批零触碰其文件）。
> 状态：主代理审计通过，波次五合并门禁通过（含首轮 SpotBugs NP 红项修复复跑，见文末证据节），已收口提交。

## 1. 背景与红线

完全复刻 JAVA-16a 标准：HTTP 响应类型化；内部行 Map（header/Pair/reject fields）、审计 detail、
幂等指纹输入（`hash(operation, version, input|Map.of)`）保留 Map；契约、OpenAPI 生成类、前端零
改动；SQL 语句与参数顺序、FOR UPDATE/FOR SHARE 锁顺序、异常码、HTTP 状态码、端点路径、请求体
`RepairCollaborationInput`/`RepairInput.Reason` 一字未动（diff 中 SQL 行仅随装配换行，逐条比对
文本相同）。本批只触碰
`RepairCollaborationController/RepairCollaborationService/RepairCollaborationPhotoController/
RepairCollaborationPhotoService` 四个既有文件 + `RepairViews.java` **追加区** + 新增
`RepairCollaborationWireShapeTest` 与本记录。禁改面 RepairFactory*（16b 并行禁地，实施期间未
出现在工作树 diff）、RepairController/RepairService/RepairPhoto*/RepairReassignment*（16a 基线
零 diff）、全部 Port/RepairErrorHandler、attachment/、contracts/、apps/ 零改动。

协同面与核心面（16a）的关键差异（改前代码逐字冻结为 oracle 核对）：

1. **投影口径 = 冻结协同域私有 `RepairCollaborationService.wire()`**：与 RepairService.wire 同
   规则（`id`/`*_id` 逐值 `.toString()`；`*_json` 字符串列 `readValue(text, Object.class)` 解析
   为树——`attachment_ids_json` 在此口径解析；Timestamp/LocalDateTime→ISO instant 串；其余原样
   透传）。协同私有 wire 在出口全部类型化后无剩余使用者，**整体删除**，其 DATETIME 分支由
   16a 已冻结的 `RepairViews.timeValue` 单源承接（判据复刻两驱动形态）。
2. **协同面无条件键**：逐字段甄别后确认改前所有出口（board/technicians/mine/get 与四类回执）
   的每个键恒在——`ended_at/end_reason` 等可空列在 ACTIVE 态输出显式 null（全局
   JsonConfiguration inclusion=ALWAYS），因此新增 record 全部默认包含、零 `@JsonInclude(NON_NULL)`；
   判据对「两态键集相同」直接断言。子表行（invitations/opinions）保留 SQL snake_case 键
   （@JsonProperty 冻结，JAVA-11a/16a 先例）。
3. **幂等回放按 16a 泛型 `Class<T>` 蓝本**（协同自有私有 `replay/remember`，与 RepairService
   的互不共享；回执表 `fams_repair_receipt` 无 resource_version，finish 无需取版本）。写幂等表
   的操作以读码为准共 4 个：TECH_INVITE、TECH_REVOKE、TECH_OPINION、TECH_ATTACHMENT（照片上传
   经 registerAttachment 实写）。存储形状两类三种：
   - `CollaborationRequestReceipt`（repairId/invitationId/requestVersion 三键）：TECH_INVITE、
     TECH_REVOKE 共用；
   - `CollaborationOpinionReceipt`（+opinionId/invitationVersion 四键）：TECH_OPINION；
   - `CollaborationAttachmentReceipt`（repairId/invitationId/objectId/invitationVersion 四键）：
     TECH_ATTACHMENT。
   旧 Map.of 存储 JSON 的回放等价与 typed 自回放恒等均有判据。
4. **与 16a 交叉面（只引用不改）**：`RepairViews.Page<T>` 直接复用于 technicians 与 mine 两出口
   （键集 items/total/page/pageSize 相同）；`RepairViews.PhotoGrant` 复用于协同 download-grant
   出口（键集 url/expiresAtEpochSecond 相同，URL 前缀不同由控制器拼装）；`repairs.get(actor,…)`
   两处消费（staffBoard、staffAttachmentScope）结果本就丢弃，按 16a §7 预案改调类型化
   `repairs.getDetail`——校验链（revalidate→header FOR UPDATE→visible）逐字不变，仅省去
   convertValue Map 镜像开销。**RepairService.get 的 Map 镜像包装继续保留**（其 javadoc 提到
   RepairCollaboration 消费方，本批后真实剩余消费方仅 InventoryRepairPort/LossRepairPort；
   RepairService 为 16a 在途禁改文件，该句注释滞后登记于此，主代理合并时可顺手更正）。
5. **与 16b 交叉面**：零。协同出口不消费 RepairFactoryGuard.summary，也不 import 任何
   Factory* 符号；RepairViews 追加区命名统一 Collaboration 前缀，与 16b 的 Factory 前缀追加区
   按前缀互斥。既有 `RepairCollaborationService.Photo` 五键投影与 `photoProjection` 为 JAVA-06
   已冻结类型面（AttachmentEvidenceWireShapeTest 在册），本批原样复用零改动。

## 2. 改动清单与出口映射

主代码 4 改 1 追加，测试 1 增，本记录 1 增：

| 文件 | 变化 |
|---|---|
| `repair/RepairViews.java`（追加区） | 新增 10 个协同 record（CollaborationBoard/CollaborationInvitationRow/CollaborationOpinionRow/CollaborationTechnician/CollaborationAsset/CollaborationBrief/CollaborationInvitationDetail/CollaborationRequestReceipt/CollaborationOpinionReceipt/CollaborationAttachmentReceipt）+ 3 个行映射工厂；List 分量紧凑构造器 `List.copyOf` 防御拷贝；16a 已建 22 record 与既有工厂零 diff |
| `repair/RepairCollaborationService.java` | 7 个出口方法类型化；私有 `replay` 改 `<T> T replay(..., Class<T>)`、`remember` 改 `<T> T remember(..., T result)`；`minimal/minimalJoined` 产 `CollaborationBrief`、`opinions` 产 `List<CollaborationOpinionRow>`；删除无使用者私有 `wire()`；两处 `repairs.get` 改 `repairs.getDetail`（结果丢弃语义不变）；SQL/锁顺序/审计/指纹/异常零改动 |
| `repair/RepairCollaborationController.java` | 7 个端点返回类型化视图；`retry` 泛型化 `<T> T retry(Supplier<T>)`（CannotAcquireLockException 一次重试结构保持）；请求体、校验、路径、`Idempotency-Key`/`If-Match-Version` 头一字未动 |
| `repair/RepairCollaborationPhotoController.java` | upload→`CollaborationAttachmentReceipt`、grant→`RepairViews.PhotoGrant`（Map.of 装配换 record，键集相同）；content 二进制出口与响应头未动 |
| `repair/RepairCollaborationPhotoService.java` | upload 返回 `CollaborationAttachmentReceipt`；registerAttachment 返回值与冲突混合 Map 信封改为私有 `UploadOutcome` record（复刻 16a RepairPhotoService 先例），删除-再抛出的时序逐行保持（冲突/重放 objectId 不匹配先 storage.delete 后 throw RepairConflict） |
| `repair/RepairCollaborationWireShapeTest.java`（新增） | 10 条 wire 等价用例，见 §3 |

出口 → 类型对照（Collaboration 7 + Photo 2）：

| 旧 Map 出口 | 新 record |
|---|---|
| GET /repairs/{r}/collaborations：`repairId/requestVersion/canInvite/invitations/invitationTotal/opinions/opinionTotal` | `CollaborationBoard`（内嵌 `CollaborationInvitationRow`、`CollaborationOpinionRow` snake 键行） |
| GET /repairs/{r}/eligible-technicians：`items/total/page/pageSize` | `RepairViews.Page<CollaborationTechnician>`（复用 16a Page） |
| POST invite 与 revoke（含 TECH_INVITE/TECH_REVOKE 幂等回执） | `CollaborationRequestReceipt` |
| GET /repair-collaborations（mine）：`items/total/page/pageSize` | `RepairViews.Page<CollaborationBrief>` |
| GET /repair-collaborations/{i}：brief 9 键 + `opinions/opinionTotal/photosAvailable/faultPhotos/attachments` | `CollaborationInvitationDetail`（faultPhotos/attachments 分量为 JAVA-06 既有 `RepairCollaborationService.Photo`） |
| POST opinion（含 TECH_OPINION 回执） | `CollaborationOpinionReceipt` |
| POST /{i}/attachments（含 TECH_ATTACHMENT 回执） | `CollaborationAttachmentReceipt` |
| POST /{i}/{kind}/{object}/download-grant：`url/expiresAtEpochSecond` | `RepairViews.PhotoGrant`（复用 16a） |
| GET content 二进制 | `ResponseEntity<byte[]>` 出口，非 JSON，未动 |
| 非出口（内部面，未动）：uploader/photoScope/staffAttachmentScope/opinionAttachmentScope 的授权返回值（long/void）、Pair/header 行 Map、audit detail、hash 指纹输入 | 保留 Map/标量原样 |

## 3. 判据：wire 等价测试

`RepairCollaborationWireShapeTest` 把改前 `RepairCollaborationService.wire()`、`staffBoard()`
LinkedHashMap 装配、`minimal()` Map.of 装配、`get()` 装配、technicians/mine 的 Map.of 分页、
`remember()` 存储的 Map.of 回执与 photo grant Map.of 逐字冻结为 legacy oracle，构造 JDBC 类型行
（Long/Integer/String/Timestamp/LocalDateTime/JSON 数组串）比对：

- 邀请行：Timestamp 主形态 + LocalDateTime 变体 + ACTIVE 态 `ended_at/end_reason` **显式 null
  键在位断言**（无条件键甄别）+ `id/*_id` 字符串化、`version` 数值断言；
- 意见行：`attachment_ids_json` 解析为树（与 repair 核心 detail_json 同口径、区别于域外字符串
  口径）+ Timestamp/LocalDateTime 双驱动 + INT id 宽度变体；
- board 全装配 ≡ legacy（10 邀请键计数、repairId 文本、两态键集相同断言）；
- technicians/mine 分页 ≡ legacy Map.of（复用 16a Page 形状回归）；
- brief ≡ legacyMinimal：Timestamp/LocalDateTime/Integer-version 三变体；
- get 出口双态：port 可用（photosAvailable=true、两段 Photo 列表）与 port 缺席
  （false + 空数组键仍在位断言）；
- 回执四型 ≡ legacy Map.of（含 grant 复用件）；
- **幂等存储形状旧 JSON 回放**（跨版本必测）：TECH_INVITE/REVOKE 三键、TECH_OPINION 四键、
  TECH_ATTACHMENT 四键旧 Map.of 树 → `readValue(record)` → 等价再序列化；
- typed 自身 序列化→反序列化→再序列化 恒等（含 Board/Detail 嵌套态）。

**单元 oracle 边界（JAVA-01/16a 教训沿用）**：只证明「行→视图→存储/回放」序列化等价。真实
HTTP 运行时用例 `auth/RepairCollaborationHttpRuntimeCase`、`RepairCollaborationUpgradeRuntimeCase`
需要容器 MySQL，本批环境无容器、且任务红线禁跑 `scripts/verify-d6-03a.py`，登记为门禁边界，
由主代理合并门禁实跑。

## 4. 实施代理自测（非产品门禁）

- 定向判据：`JAVA_HOME=graalvm-jdk-21.0.7 ./mvnw -pl backend -Dtest='RepairCollaborationWireShapeTest' test`
  → **Tests run: 10, Failures: 0, Errors: 0**。
- 回归（同任务书口径）：`-Dtest='RepairCoreWireShapeTest,*WireShapeTest,TransferCalendarTest'`
  → **Tests run: 246, Failures: 0, Errors: 0**（= 派发时基线 236（含 16a 的 18 项，全绿零退化）
  + 本批新增 10，套件零退化）。
- 全仓编译：`./mvnw -pl backend -q test-compile` → exit=0（含 16a 在途文件同树共编译）。
- Checkstyle：`./mvnw -pl backend -Pquality checkstyle:check@check-java-conventions` →
  **BUILD SUCCESS**，新文件末尾换行合规。
- 未跑：SpotBugs/JaCoCo/真实 HTTP 运行时用例与全量门禁——按任务红线留给主代理合并门禁；
  EI_EXPOSE_REP 风险已按先例在构造侧消解（List 分量 `List.copyOf`；JsonNode 分量不可变语义；
  无新增 Map 分量）。
- 同树并发 mvn 竞态：本批四轮 mvn 均一次通过，未遇到指向 RepairFactory* 的中间态编译红，
  35 秒重试条款未触发；全程未读写任何 Factory 文件。

## 5. 影响分析（GitNexus）

编辑前执行 `impact -d upstream --repo fams-manage`（Node 24 前缀）：

- `RepairCollaborationService`（类级）：**LOW**（direct 2：本批两消费控制器，包内）。
- `RepairCollaborationController`/`RepairCollaborationPhotoController`：**LOW**（direct 0）。
- `RepairCollaborationPhotoService`：**LOW**（direct 1 = 其控制器）。
- 方法级 `staffBoard/technicians/invite/mine/opinion/registerAttachment`：全部 **LOW**
  （direct ≤2，链尾仅本批文件与 AttachmentWriter 泛型 write）；`registerAttachment` 的
  depth-2「Attachment 模块」命中经 grep 核实为 `writer.write` 泛型回调静态噪声，
  AttachmentWriter 文件零 diff。
- 同名伪边登记：`technicians/invite/revoke/mine/opinion/upload/replay/remember` 返回 ambiguous
  候选列表——非 repair 包候选（miniprogram ts `revoke`、`LossService.replay/remember`、
  `ScrapReviewService.remember`、apps/web `upload` 等）与协同面无调用边；协同私有 `replay/
  remember` 编译器封闭。RepairCollaborationService 的 `get` 与 RepairService.get 同名不同类，
  grep 核实外部无 `collaboration.get(` 之包外消费（唯一面是其控制器）。
- `RepairService.get/getDetail`（消费方改动、符号本体零 diff）：16a 已核 HIGH 消费面在册；本批
  只把两个丢弃结果的调用点从 Map 镜像切到类型化方法，无签名变化。
- 索引不含本批新符号（Collaboration* record）属正常；RepairViews 为 16a 未提交新文件，索引未
  覆盖，追加区按「只新增符号」原则不触碰既有成员。

## 6. 偏差与遗留

1. **数值口径归一（JAVA-11a/16a 先例）**：协同 brief 的 `version`（i.version BIGINT，旧码
   JDBC 原值 Object 直通）与 invitations/opinions 行的 `version`、board 的 `requestVersion`
   统一为 `long`——装箱 Long/BIGINT 文本同值，INT 宽度变体亦同值，判据含宽度变体用例。
   这是旧 wire「非 _id 列原样透传」到 record 化的唯一受控归一点。
2. **`repairs.get`→`repairs.getDetail` 切换**：staffBoard 与 staffAttachmentScope 两处丢弃结果
   的可见性校验改走类型化出口，行为等价（get 本就是 getDetail 的 convertValue 镜像）；
   RepairService.get 的 javadoc 提及 RepairCollaboration 消费方已滞后，因 16a 文件禁改登记于
   §1-4，主代理合并时可更正注释。
3. **RepairPhotoService 冲突信封先例复刻**：upload 的 `conflictFields` 旧码经
   `k.toString()/v.toString()` 重拷贝（`RepairConflict.fields` 本就是 `Map<String,String>`，
   toString 对非 null 值恒等）；新码 `LinkedHashMap.putAll` 直拷——仅 null 值输入时新旧有别
   （旧 NPE、新保留 null），现网 fields 值全部为 `.toString()` 产物不可能为 null，与 16a §6-8
   同判：不可达路径偏差。
4. **旧幂等回执经 record 再序列化键序归一为声明序**（旧 Map.of 键序本就跨 JVM 随机、Map 树无
   契约），语义等价；跨版本回放由判据 §3 覆盖。新写行的存储 JSON 即 record 声明序，同版本内
   恒等。
5. **排除清单（保留 Map/不动，按规格）**：内部行 Map（`header`/`Pair` 的 repair+invitation 行、
   `minimal` 合成的 joined 行 Map、`reject` fields Map.of）、审计 `fams_repair_event/fams_audit_event`
   detail_json 装配（Map.of 原样）、`hash` 指纹输入（含 registerAttachment 的
   filename/sha256/mime/size Map.of）、`fams_repair_receipt.repair_id` 写入值（`n(row,"id")`/
   `repair` 原样）、请求体 `RepairCollaborationInput`/`RepairInput.Reason`、JAVA-06 已冻结的
   `Photo`/`photoProjection`——一律未动。
6. **条件键甄别结论**：协同面不存在「仅某状态 put」的键（对比 16a detail 的三条件键），
   新增 record 零 `@JsonInclude(NON_NULL)`；判据以两态键集相同断言冻结该结论。
7. **契约/OpenAPI/前端**：零改动（无 API 形状变化）。
8. **16b 并行协调**：RepairViews 追加区与 16b 的 Factory 前缀追加互斥；合并时若两批并行落盘
   同一文件，以「各自追加块无交叠」为前提按行合并即可。本批实施期间工作树未出现 Factory* 改动。
9. AOCI 认知维护：本会话工具面未暴露 aoci_* MCP 服务，无法调用 `aoci_maintain`；受管理对象
   已达最终稳定态，登记该收尾步骤未执行，交由持有 AOCI 工具的会话对齐。

## 7. 下一步

- 主代理合并门禁：与 16a/16b 合并后跑全量 Java 质量序列 + 真实 HTTP 运行时用例
  （`RepairCollaborationHttpRuntimeCase`、`RepairCollaborationUpgradeRuntimeCase` 及
  Upgrade 链）实跑；SpotBugs 关注新增 List 分量拷贝位（已按先例预清）。
- 16a §6-1 的 Map 镜像包装消费方核对：本批切换后 `RepairService.get` 的包外消费仅剩
  InventoryRepairPort（×2）与 LossRepairPort 链，后续如有 repair 域收尾批可据 §1-4 判断包装去留。

## 主代理产品门禁（波次五合并实跑）

主代理在波次五合并树固定快照副本（rsync 排除并行 WIP；快照含三路全部产物与主代理两处收口修正：
RepairService.get javadoc 归位、16b parts COUNT 的 requireNonNull）实跑完整 Java 序列：
**JAVA01_VERIFY_PASS**，83 步 exit=0，Checkstyle/SpotBugs 0，覆盖率 LINE 93.36%、BRANCH 75.60%；
日志内 74 个 surefire 阶段汇总 401 项 0 失败 0 错误。首轮门禁红于 16b parts 的
NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE（COUNT 直进 long 拆箱），按 JAVA-09 先例
`Objects.requireNonNull(…, "PARTS_COUNT_MISSING")` 修复后复跑绿。三个新套件另在门禁副本内
直接复跑 42/42 绿。主树合并判据：29 个 WireShape 类 260/260。
详见[波次证据](evidence/JAVA-16-java-gate.txt)。
