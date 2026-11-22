# JAVA-14b3 · 代确认回执域 HTTP 出口类型化

> 用户指定的后端结构批（波次三 · requisition 代确认回执子批），不进入 D6-01～D6-27 台账、
> 不占用其状态。日期：2026-11-27 · 分支：main · 基线：`2fe2880`（JAVA-11c 提交）。
> 状态：主代理审计通过，波次三合并门禁通过（见文末证据节），已收口提交。

## 1. 背景与红线

完全复刻 JAVA-04/11a 标准：HTTP 出口零 Map；内部行 Map、审计/指纹 Map 保留；SQL、锁、
异常码、状态码、路径与 `contracts/fams-core-v1.yaml` 契约零改动。代确认回执域的关键现状
（改造前逐字核实）：

1. **confirmation 系出口 JAVA-04 已类型化**：`RequisitionProxyConfirmationController` 七个
   端点（context/list/get/create/submit/decision/execute）返回 `ProxyViews.ProxySnapshot /
   ConfirmationPage / Confirmation`，service 全程 `ProxyViews.confirmation(json, row)` 装配、
   泛型 `replay(Class<T>)/finish(<T extends Reply>)`。本批不重复改型，只把改造前 wire() Map
   投影在本域测试内**再冻结为域侧 oracle**（含五种状态行与 previous_confirmation_id 两态）。
   台账口径下 confirmation 侧无 Map 残留即为合法产出。
2. **本批新类型化的是附件/mini 三个出口**：`ProxyConfirmationAttachmentController` 的
   list（`Map.of("items", ...)` 包 `AttachmentReadService` 的 11 键别名行）与 upload/correct
   （`ResponseEntity<Map>` 六键 `Map.of`），`MiniProxyAttachmentReadController.grant`
   （两键 Map + mini 路径重写）。AttachmentReadService/UploadService 属 attachment 包，零触碰，
   收敛在 requisition 侧完成（AttachmentEvidencePort.Metadata + fromRow 先例口径）。
3. **Port 面零改动**：`ProxyReceiptPort`（跨域契约）签名自 JAVA-04 起无 Map
   （snapshot/execute 返回 `ProxyViews.*`，行 Map 只存在于 service 私有 `row()/lockedCurrent()`）；
   `AttachmentBusinessPort/ProxyConfirmationAttachmentPort` 无 HTTP 出口。全仓 grep 确认无
   Port 签名变更需要，登记为“未改动、无消费者影响”。
4. **条件键甄别**：本批所有出口旧装配均为“键恒在、值可显式 null”（queryForList 行 Map 与
   `Map.of` 固定键集），全局 mapper 为 JsonConfiguration `Include.ALWAYS`，record 默认逐分量
   显式输出，无需任何 `@JsonInclude(NON_NULL)`；两态判据（null/非空）逐键冻结在测试里。
   `createdAt/actualReceivedAt` 等 DATETIME 保留驱动原值 Object 透传（双驱动形态）。
5. **幂等回放红线**：`fams_idempotency_record.response_body_json` 存的是 Confirmation 旧
   wire Map 序列化文本，replay 走 `json.readValue(text, ProxyViews.Confirmation.class)`；
   payload_sha256 兼容层 `ProxyReceiptPayloads`（旧 2/3 键 `Map.of` 元组键序排列容错）保留，
   本批补新旧双序 + 负例回放判据。

## 2. 改动清单

主代码 2 改 1 增，测试 1 增：

| 文件 | 变化 |
|---|---|
| `requisition/ProxyConfirmationViews.java`（新增） | 4 个出口 record：`AttachmentListPage`（List 分量紧凑构造器 `List.copyOf` 防御拷贝）、`AttachmentEvidence`（11 别名键，`attachmentEvidence(row)` 单一生产映射）、`EvidenceReceipt`（upload/correct 六键，`receipt(result, filename, version)` 工厂）、`DownloadGrant`（mini grant 两键） |
| `requisition/ProxyConfirmationAttachmentController.java` | list 返回 `AttachmentListPage`（行经 `attachmentEvidence` 映射，SQL 别名键序即 record 声明序）；upload/correct 返回 `ResponseEntity<EvidenceReceipt>`（`Map.of` 六键装配换工厂，objectId 仍 `UUID.toString()` 字符串化、businessVersion 仍 `version+1`）；状态码 201、路径、请求头零改动 |
| `requisition/MiniProxyAttachmentReadController.java` | grant 返回 `DownloadGrant`（mini 路径重写与 `expiresAtEpochSecond` 数值语义逐字复刻）；content 二进制出口未动 |
| `requisition/ProxyConfirmationWireShapeTest.java`（新增） | 9 条 wire 等价/回放用例，见 §3 |

出口 → record 对照：

| 出口 | record |
|---|---|
| ProxyConfirmationAttachmentController.list | `ProxyConfirmationViews.AttachmentListPage`（items 行 `AttachmentEvidence` 11 键） |
| ProxyConfirmationAttachmentController.upload / correct | `ProxyConfirmationViews.EvidenceReceipt` |
| MiniProxyAttachmentReadController.grant | `ProxyConfirmationViews.DownloadGrant` |
| RequisitionProxyConfirmationController 七端点 | `ProxyViews.*`（JAVA-04 已类型化，本批域侧 oracle 复冻结） |
| MiniProxyAttachmentReadController.content | 二进制 `ResponseEntity<byte[]>`，非 JSON 出口，未动 |
| RequisitionProxyConfirmationController.Draft/Decision | 请求体入参（非出口），校验注解与 Map snapshot 输入面按红线保持 |

## 3. 判据：wire 等价测试

`ProxyConfirmationWireShapeTest` 把改造前装配逐字冻结为 legacy oracle，JSON 文本往返比树
（整型宽度归一、键序不参与；键存在性/显式 null/值形状参与）：

- confirmation 详情/列表页/decision/execute 五状态行 vs `legacyWire(row)` 投影（id/`*_id`
  字符串化、`*_json` 树化复刻）；`previousConfirmationId` null/有值两态与 31 键全集断言；
- context/快照：pre-JAVA-04 存储的 legacy RequisitionSnapshot JSON 经判别器回放再序列化等价；
- 幂等回放：DRAFT/SUBMITTED/APPROVED/REJECTED/EXECUTED 五状态旧 wire Map 存储文本
  `readValue → 再序列化` 比树（即 replay 命中路径）；`ProxyReceiptPayloads` 摘要对新规范形、
  旧 2 键/3 键 `Map.of` 逆序元组匹配、异值负例不匹配；
- 附件 list：11 键 × 双驱动 createdAt 形态 × 修正链两态（previous/correction null vs 有值、
  nextObjectId 指针）整页比树 + Integer 窄化行 + 可变入参防御拷贝后仍等价；
- upload/correct：六键 `Map.of` 装配 vs `receipt(...)` 比树 + 键数=6 断言；
- mini grant：两键 + `/api/v1/attachments/`→`/api/v1/mini/attachments/` 重写复刻比树。

**单元 oracle 边界（JAVA-01/11a 沿用）**：只证明「装配→序列化」等价；真实 HTTP 回归
（`ProxyConfirmationUpgradeRuntimeCase`、`RepairProxyHttpRuntimeCase`、`HandoverHttpTest`
代确认段）由主代理门禁实跑。

## 4. 门禁证据

实施代理已跑（非产品门禁）：

- 定向单测：`JAVA_HOME=graalvm-jdk-21.0.7 ./mvnw -pl backend -Dtest='ProxyConfirmationWireShapeTest' -DfailIfNoTests=false test`
  → **Tests run: 9, Failures: 0, Errors: 0**。
- 邻域回归：`-Dtest='ProxyConfirmationWireShapeTest,ProxyWireShapeTest,AttachmentEvidenceWireShapeTest,BorrowDateWireShapeTest,SmallPortsWireShapeTest'`
  → **Tests run: 36, Failures: 0, Errors: 0**。
- 全仓编译：`./mvnw -pl backend -q test-compile` → exit=0（中途一次红为并行 14a/14x 代理
  同树瞬时态，等 30 秒重试后绿，非本批引入）。
- Checkstyle：`./mvnw -pl backend -Pquality checkstyle:check@check-java-conventions` → exit=0。

## 5. 影响分析（GitNexus）

编辑前按 AGENTS 要求执行 `impact -d upstream`（Node 24 前缀 + --repo，索引 fams-manage）：

- `ProxyConfirmationAttachmentController`：**LOW**，direct 0、processes 0（Spring MVC 叶子
  控制器，无 Java 侧上游）。
- `MiniProxyAttachmentReadController`：**LOW**，direct 0、processes 0。
- 方法名 `list/grant/upload/correct` 在全仓多域同名（loss/inventory/scrap 各有），属同名
  伪边；类级 exact 结果 0 上游已覆盖——真实消费者只有 HTTP 客户端（apps/web 附件面板与
  apps/miniprogram 代确认页），响应键集/键名/状态码零改动，前端无需变更。
- 未编辑 `RequisitionProxyConfirmationService/Controller`（confirmation 面 JAVA-04 已收口，
  本批零改动）；`RequisitionService/RequisitionHandover*`（并行代理在途）零触碰，无中间态
  依赖。

## 6. 偏差与遗留

1. **排除清单（保留 Map/不动，按红线）**：
   - `RequisitionProxyConfirmationService` 私有行 Map（`row()/lockedCurrent()/evidence()`）、
     审计 `Map.of("snapshot",...)`/detail LinkedHashMap、`fams_business_result_notice.content_json`
     的 `json.writeValueAsString(detail)`——均非 public HTTP 出口，未动；
   - `Draft.snapshot`（请求体 `Map<String,Object>` 入参与 `same()` 树比较）为输入契约面，
     红线只约束出口，保持；
   - `ProxyReceiptPayloads` 指纹兼容层整体保留（幂等 payload 是存储指纹输入，非出口）；
   - `attachment/`、`proxy/`、`masterdata/`、`contracts/`、`apps/` 零触碰。
2. **无“撤回”端点**：任务判据文案提到“撤回”，代确认域现行控制器只有
   create/submit/decision(approved=false 即拒绝)/execute 状态机，无独立撤回端点；判据以
   五状态行 + decision 双分支覆盖，登记事实不改口径。
3. **mini grant 数值分量**：旧 Map 值是装箱 Long，record 改 `long`（`((Number)).longValue()`
   提取，来源 `AttachmentGrantSigner.Grant.expiresAtEpochSecond` 本就 long），文本恒等。
4. **upload/correct 的 filename**：旧 `Map.of` 在 originalFilename 为 null 时 NPE、record 会
   显式 null——该情形被 `uploadIdempotent` 前置校验（filename null 即 400）拦截，响应装配
   实际不可达，登记为不可达形状差异。
5. **裁决点：无。** 未遇到形状不可判定、真实 HIGH/CRITICAL 越批上游或需改 Port/排除接口的
   情形（`ProxyReceiptPort` 无 Map 签名，无需改动）。

## 7. 下一步

等待主代理合并门禁（波次三 14a/14b1/14b2/14b3 合并树单次实跑）；后续批次按 14c → 15 →
16 → 17 推进。

## 主代理产品门禁（波次三合并实跑）

主代理在波次三合并树固定快照副本（rsync 排除并行会话 WIP `com/fams/local`；快照内容经核实含本波
三个新 Views 文件与五个新 WireShape 套件）实跑 `verify-d6-03a.py` 完整 Java 序列：**JAVA01_VERIFY_PASS**，
83 步 exit=0，Checkstyle/SpotBugs 0，覆盖率 LINE 93.22%、BRANCH 75.61%；日志内 74 个 surefire 阶段
汇总 401 项 0 失败 0 错误。门禁自身报告的 tests=555 与波次二同数，为消除口径歧义，五个新套件已在
该副本内直接复跑 **45/45 绿**。主树合并判据：25 个 WireShape 套件 202/202。
详见[波次证据](evidence/JAVA-14-java-gate.txt)。
