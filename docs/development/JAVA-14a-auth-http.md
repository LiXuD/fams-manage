# JAVA-14a · auth 域 HTTP 出口类型化核查 + 防退化冻结测试（已完成态登记批）

> 用户指定的后端结构批（HTTP 出口波次三并行批之一），不进入 D6-01～D6-27 台账、不占用其状态。
> 日期：2026-11-22 · 分支：main · 基线：`2fe2880`（JAVA-11c 提交）
> 状态：主代理审计通过，波次三合并门禁通过（见文末证据节），已收口提交。

## 1. 背景与红线

按 /tmp/java-http-common.md 与 JAVA-11c 先例执行 auth 域 HTTP 出口 Map→record 类型化核查。
读码核实的结论与本批规格预期一致：**该域 public 信封面在批前已全部类型化**（JAVA-08/11c
「已完成态只做残留、不造新面」先例），本批为完成态登记批，主代码 **0 改 0 增**，仅新增
1 个 wire 形状冻结测试防退化。红线遵守：契约 `contracts/fams-core-v1.yaml`、`packages/api-types`、
`apps/*`、`DESIGN-HANDOFF.md`、`AGENTS.md`、其他批次记录零改动；未跑全量门禁
（`scripts/d6-03a.py` 未触碰）；主树并行会话在途改动（masterdata、requisition、report 等）
零触碰；未 commit/push。

## 2. 出口盘点（改前即完成态）

auth 域 5 个控制器全部端点返回类型逐一读码核实，均为契约生成具名模型
（`com.fams.product.contract.model.*`，由 openapi 生成器从 `fams-core-v1.yaml` 产出）或
`ResponseEntity<Void>`，无任何 `Map`/`Object` HTTP 出口：

| 控制器 | 端点 → 返回类型 |
|---|---|
| AuthController（implements AuthApi） | login / changeInitialPassword → `SessionResponse`；getCurrentUser → `CurrentUser`；getMyPermissions → 契约 `PermissionSnapshot`（含嵌套 `DataScopeGrant`/`OrganizationReference`/`CatalogReference`/`SensitiveFieldPermission`）；logout → `Void` |
| AccountAdminController（implements AccountAdminApi） | CRUD/状态/角色/授权 12 端点 → `SystemAccount`、`PageSystemAccount`、`RoleConfiguration(Page)`、`SystemAccountAuthorization` |
| MiniAuthController（implements MiniAuthApi） | miniLogin → `MiniSessionGrant`；miniCurrentSession → `MiniSessionView`；miniLogout → `Void` |
| MiniBindingController（implements MiniBindingApi） | support → `MiniBindingSupport`；challenge → `MiniBindingChallenge`；verify → `MiniBindingResult`；unbind/adminMiniUnbind → `Void` |
| MiniPhoneCorrectionController（implements MiniIdentitySupportApi） | correct → `MiniPhoneCorrectionResult` |

控制器消费的 service/port 出口同样全部具名类型：`AuthApplicationService.authenticate → AuthAccount`
（record）、`loadPermissions → com.fams.auth.PermissionSnapshot`（record，嵌套 `DataScope` 枚举、
`AuthorizedDataScope`/`VersionedReferenceRow` record）；`AuthSessionManager.start → AuthSession`
（record）；`AccountAdminService` 11 个 public 方法全部直接返回契约模型（内部经
`AccountAdminMapper` 与 `AuthorizationAdminRow`/`AuthorizationScopeRow`/`EmployeeAdminRow`/
`AdminIdempotencyRow` 等具名行 record 组装，不经过行 Map）；`MiniSessionService → Grant/Current`
record；`MiniBindingService → Delivery/Result` record；`MiniPhoneCorrectionService.correct → long`；
`MiniWechatIdentityPort.exchange → Identity` record。

域内剩余 Map 出现处及处置（全部为公共协议红线保护的非 HTTP 出口面，非残留出口）：

| 位置 | 性质 | 处置 |
|---|---|---|
| `AuthErrorHandler` `Map<String,String> fieldErrors`（2 处采集 + ErrorResponse 分量） | 跨域错误信封契约形状：`fieldErrors` 为契约 `ErrorResponse`（`com.fams.product.contract.model`）定义字段，键为动态字段名 | 不动（11c 同位置先例） |
| `InventorySynchronizationIdentityPort.states` 返回 `Map<Long,State>` | 内部 port 聚合 helper（键为已具名 accountId、值为 State record），从不外出 HTTP | 不动 |
| `InventoryExecutorPort.ExecutorChoice.fromRow(Map<String,Object> row)` | **私有行 helper / 内部行 Map**（仅供 inventory 侧取执行人行），从不外出 HTTP | 不动（红线 5「私有行 helper：不动」） |

**Map 残留 HTTP 出口：0 个。** 无残留候选需登记改造。

## 3. 改动清单

主代码 **0 改 0 增**（已完成态，无残留面可改），测试 1 增：

| 文件 | 变化 |
|---|---|
| `backend/src/test/java/com/fams/auth/AuthWireShapeTest.java`（新增） | 7 条 wire 形状冻结判据，见 §4 |

**裁决点（对齐 11c 裁决点 2）**：判据 mapper 复刻生产 HTTP mapper——`contract/probe/JsonConfiguration`
把 Jackson 3 的默认 NON_NULL 包含改回 `ALWAYS`，故 auth 面所有 null 分量（mini login UNBOUND 分支的
`sessionToken/accountId/bindingId/idleExpiresAt/absoluteExpiresAt`）在真实 wire 上是**显式 null 键、
恒在**；冻结用例按 ALWAYS 断言显式 null。若未来移除该 customizer，本判据会以红灯强制重新裁决形状。

**实现说明（非偏差）**：契约生成模型为带无参/全参构造器的普通 POJO，可在单测中脱离 Spring 直接
构造；「内部快照 → 契约模型」的投影逻辑（`toCurrentUser`、`toPermissionSnapshot`、mini 组装、
`utc` helper）按控制器现役代码**逐字复制**入测冻结，与 11c 的「字面冻结树 + verbatim 抽取表达式」
方法一致。

## 4. 判据：AuthWireShapeTest（7/7）

序列化后与手写冻结 JSON 树比树（文本往返归一整型宽度、键序不参与、键集精确断言）：

1. login `SessionResponse`：csrfToken + UTC OffsetDateTime 双过期键 + `requiresInitialPasswordChange`
   false/true 双分支（login 与 changeInitialPassword 复用同一投影）+ 嵌套 user 键集；
2. `CurrentUser`：Long id→字符串、`SystemAccountStatus` 名透传（LOCKED 分支）、
   roles 按枚举名排序（ASSET_ADMIN,EMPLOYEE 输入乱序冻结有序输出）；
3. 契约 `PermissionSnapshot`：内部快照 `dataScopes × scopeGrants` 分组投影——
   SPECIFIED_ORGANIZATIONS/SPECIFIED_WAREHOUSES 各带版本化引用（`{"id":"7","version":3}`）、
   无授予的 ENTERPRISE 冻结为空数组、`fieldPermissions` 枚举值数组、`authorizationVersion`；
   嵌套键集（DataScopeGrant 三键、引用两键）；
4. mini login `MiniSessionGrant`：BOUND 全键 + **UNBOUND 全 null 显式键恒在**（ALWAYS 语义冻结）；
5. mini `MiniSessionView`：恒定 BOUND + accountId 字符串化 + bindingVersion；
6. binding `MiniBindingChallenge`（`resendAfterSeconds` 固定 60、expiresAt UTC）/
   `MiniBindingResult` / `MiniBindingSupport`（含中文文案）/ `MiniPhoneCorrectionResult`；
7. 全部 10 个契约响应模型序列化→反序列化→再序列化树等价（round-trip 跨版本安全）。

幂等链说明：auth 域幂等重放（`AdminIdempotencyRow`、`AuthIdempotencyRecord`）产出的即上表契约
模型本身（`AccountAdminService` 重放分支返回已存的 `SystemAccount` 等），无独立幂等 wire 面；
真实 HTTP 形状由既有 `AuthHttpTest`、`MiniAuthControllerTest` 等在主代理门禁实跑。

## 5. 实施代理自测（非产品门禁）

- 定向单测：`JAVA_HOME=graalvm-jdk-21.0.7 ./mvnw -pl backend -Dtest='AuthWireShapeTest'
  -DfailIfNoTests=false test` → **Tests run: 7, Failures: 0, Errors: 0**；该 lifecycle 当次
  全仓 main+test 编译通过（此刻并行文件处于可编译快照）。
- 独立 `./mvnw -pl backend -q test-compile` → exit=0。
- Checkstyle 项目口径：`./mvnw -pl backend -Pquality checkstyle:check@check-java-conventions`
  → exit=0，BUILD SUCCESS，含新文件 0 违规（新文件末尾换行已满足）。

产品门禁完整 Java 序列：留待主代理在固定候选实跑。

## 6. 影响分析

本批**未编辑任何既有符号**（只读核查 + 新增测试文件与本记录），无编辑面，AGENTS「编辑符号前
必跑 impact」无触发面，故未跑 gitnexus impact（登记为「只读核查+新增测试，无编辑面」）。
`git status` 核实本批产物仅：`backend/src/test/java/com/fams/auth/AuthWireShapeTest.java`（新增）
+ 本记录。排除确认：工作树中 masterdata/requisition/report 等未提交改动与 AGENTS.md 修改为
并行会话在途内容，本批零触碰、未混入；`apps/*`、`contracts/`、`packages/api-types` 零改动。

## 7. 偏差与遗留

1. 规格预估的 auth 域「Map 出口改造面」实际为 0——5 控制器批前已全类型化，按 JAVA-08/11c
   先例登记已完成态；本批仅冻结既有形状。
2. 无 auth Views 类新建需求：域内响应模型全部为契约生成类（比 11c 的 service-owned 嵌套
   record 收口更彻底），无搬运动机。
3. 遗留候选（非本批）：`AuthErrorHandler.fieldErrors` 动态键面属契约错误信封，维持透传；
   若契约 schema 收紧另批裁决。

## 8. 下一步

波次三并行批收敛后由主代理在合并树跑产品门禁；auth 域无需后继子批。

## 主代理产品门禁（波次三合并实跑）

主代理在波次三合并树固定快照副本（rsync 排除并行会话 WIP `com/fams/local`；快照内容经核实含本波
三个新 Views 文件与五个新 WireShape 套件）实跑 `verify-d6-03a.py` 完整 Java 序列：**JAVA01_VERIFY_PASS**，
83 步 exit=0，Checkstyle/SpotBugs 0，覆盖率 LINE 93.22%、BRANCH 75.61%；日志内 74 个 surefire 阶段
汇总 401 项 0 失败 0 错误。门禁自身报告的 tests=555 与波次二同数，为消除口径歧义，五个新套件已在
该副本内直接复跑 **45/45 绿**。主树合并判据：25 个 WireShape 套件 202/202。
详见[波次证据](evidence/JAVA-14-java-gate.txt)。
