# CONV-03R · 按 `@Transactional` 三类失效场景修订的执行方案

> 本文件**修订并取代** `CONV-03-transaction-boundaries.md` 的 §2/§3/§4 判据与 §8 开放风险。
> 依据：阿里手册 02-(三)5【强制】+ P0 红线"事务异常必须回滚 / 禁 catch 后吞异常继续执行"，
> 以及 Spring `AbstractFallbackTransactionAttributeSource.computeTransactionAttribute` 的实际字节码。
> 日期：2026-11-24 · 基线：`ae18743`（CONV-03 已提交为 `900a62c`，本修订不改那笔提交的事实）

## 1. 框架规则以字节码定论（不再依赖记忆）

`spring-tx-6.2.15.jar` 中 `computeTransactionAttribute` 的前四条指令：

```
1: invokevirtual allowPublicMethodsOnly()Z
4: ifeq 19                    // 返回 false 才跳过守卫
8: Method.getModifiers()I
11: invokestatic Modifier.isPublic(I)Z
14: ifne 19
17: aconst_null
18: areturn                  // 非 public → 直接返回 null
```

即 **`publicAnnotationsOnly()` 为真且方法非 public 时立即返回 null**，该守卫发生在"回退到类级注解"
之前，因此方法级与类级注解对非 public 方法**同样无效**。本仓未发现 AspectJ / `AdviceMode.ASPECTJ` /
`@EnableTransactionManagement` 显式配置（grep `backend/pom.xml`、`src/main/java`、
`src/main/resources` 全部零命中）→ 走 Spring Boot 默认代理模式，`publicAnnotationsOnly` 默认为真，
守卫**在本项目是激活状态**。

## 2. 三类场景实测结果（扫描器 `/tmp/tx-scan2.py`，结果 `/tmp/tx-scan-precise.json`）

### 场景一 —— 非 public 方法带 `@Transactional`：**138 处，命中即注解静默失效**

按域分布：`requisition` 44、`masterdata` 31、`loan` 21、`asset` 12、`inbound` 11、`auth` 9、
`transfer` 7、`reminder` 2、`loss` 1。

典型且最严重的一例：`asset/AssetService` **全类没有任何 public 方法**，
`AssetService.java:304 Asset create(...)` 是包私有并带 `@Transactional`，方法体内
`masterData.reserveAssetCodes` → `assetRepository.insert` → `markUsed` → `issueTag` →
`event` → `record` 六段写，`AssetController` 直接调用它。按 §1 的字节码，**这条链上的
`@Transactional` 从未生效**，六段写各自 autocommit；号段预留与资产落库之间没有原子性，
幂等记录与业务写也不在同一事务。同型问题覆盖
`auth/AccountAdminService.createAccount/changeRole/replaceAuthorization`、
`auth/AuthApplicationService.authenticate`（还带 `noRollbackFor`，同样无效）、
`inbound/InboundService.submit/decide/claim/accept`、
`loan/AssetReturnService.propose/decide/claim` 等。

**衍生出更严重的一类**：37 个 Port 类把 `@Transactional(propagation = MANDATORY)` 挂在**类级**
（`asset/AssetRepairPort.java:29`、`loan/LoanReceiptPort.java:19`、
`loss/LossInitialPort.java:25`、`requisition/RequisitionBorrowDatePort.java:20` 等，
类本身是 `public class`，但**方法全是包私有**）。按 §1，这些 MANDATORY 属性对包私有方法**不生效**
——"必须由调用方开启事务"这条防护从未真正运行过。另有 36 处 MANDATORY 挂在 public 方法上
（如 `repair/RepairFactoryService.java:74 approveInitially`），那部分是有效的。

**结论**：`readOnly` 只标了 1 处、以及 CONV-03 类级→方法级改造后 650 测试全绿，都不是"改造成功"的
证据，而是**这些注解本来就未被 Spring 读取**的直接印证。

### 场景二 —— 类内自调用：**属性不一致的真实隐患 0 处**

22 处自调用（`RepairFactoryService.java:75/80 approveInitially`、
`LossService.java:886 requestRecovery`、`InventoryObservationService.java:91 identify`、
`RepairService.java:276/1025/1822/1887/2254`、`SealedInventoryReportService.java:138 executors` 等）
逐一核对调用方与被调方的事务属性后，**没有一例属性不一致**：外层入口要么同属性、要么本就在同一
事务上下文内，自调用只是让被调方法的注解成为装饰。故本场景**不需要改造代码**，但需要一条门禁防新增。

### 场景三 —— 事务方法内 catch 吞异常：**0 处，P0 红线干净**

精确口径（只统计"该方法确实带 `@Transactional` 或类级带"的）**s3_swallow = 0**：fams 的 Service/Port
错误路径统一 `throw` 契约异常（`XxxFailure.invalid()` / `XxxOperationException`），没有 catch 后吞掉
继续提交的写法。另有 17 处 catch 吞异常位于**非事务方法**（如
`attachment/ClamdScanner.java:58` 病毒扫描降级、`inventory/InventorySchedule.java:26` 调度循环、
`configurationAnomaly` parseId），属正常的边界降级/容错，不是 02-(三)5 的违反。
CONV-02 保留的那 8 处 `CannotAcquireLockException` 重试环也在非事务面，不冲突。

## 3. §8 开放风险的裁决：**既存缺陷，且不是回归**

上一轮我留下的矛盾——"包私有入口若真无事务，MANDATORY Port 早该抛
`IllegalTransactionStateException`"——现在自洽了：

> 那些 MANDATORY Port 的方法**本身也是包私有**，所以 MANDATORY 从未被施加（见 §2 场景一衍生段），
> 自然不会抛。运行时用例历史上为绿与"事务未生效"完全一致，不构成反证。

因此 CONV-03 删除类级注解**没有改变任何运行行为**（对非 public 方法，类级注解本来就无效），
不需要回滚 `900a62c`。同时 CONV-03 原记 §3 的"224/224 无缺口"必须长期限定为
"**public 入口**已覆盖"，`reminder/WechatSubscriptionService` 与
`inbound/InboundReassignmentService` 的"语义空转"登记也据此从"待证实"升为**已证实**。

## 4. 修订后的执行方案

这批不再是"补注解"，而是**把 138 个写入口真正接入事务**，风险等级高于 CONV 系列任何一批：
一旦注解真正生效，之前依赖 autocommit 侥幸成立的代码路径可能首次暴露问题。

| 步 | 内容 | 判据 |
|---|---|---|
| R0 | 加**只读**诊断门禁：ArchUnit 规则「`@Transactional` 标注的方法必须是 public」「MANDATORY 方法必须是 public」，先以 `lenient`/报告模式产出清单，不阻断构建 | 当前应报 138 + 37 |
| R1 | 逐域把入口方法提为 `public`，**一次一个域**，每域跑全量单元测试 + 该域运行时用例 | 顺序建议：`transfer`(7) → `asset`(12) → `inbound`(11) → `loan`(21) → `auth`(9) → `reminder`(2) → `loss`(1) → `masterdata`(31) → `requisition`(44) |
| R2 | Port 的类级 MANDATORY：确认方法提 public 后 MANDATORY 开始真正拦截，逐个检查调用方是否已在 public 事务入口内 | 出现 `IllegalTransactionStateException` 即为调用方缺事务，属真缺陷要修调用方 |
| R3 | 全绿后把 R0 的两条规则改为阻断，并加 `readOnly` 复核（此时锁定读才需要按 §CONV-03 的 R-lock 规则重新判定） | 违规归零 |
| R4 | 场景二/场景三只上门禁：ArchUnit「不得自调用本类带 `@Transactional` 的方法」+ PMD/SpotBugs「事务方法内 catch 不得吞异常」 | 当前 0 违规，可直接阻断 |

**必须先取得你裁决的一点**：R1 会让 138 条写链**首次**进入事务，这是行为变更而非风格修正——
可能出现新的失败、死锁窗口变化与性能变化。可选：
① 按 R0→R1 逐域推进（我推荐，从 `transfer` 打样）；
② 只做 R0 + R4 门禁与文档，R1 作为独立立项排期，不在 CONV 系列内动可见性；
③ 先只修 6 个"多段写 + 幂等记录"最高风险入口（`AssetService.create/copy`、
`AccountAdminService.createAccount/replaceAuthorization`、`InboundService.submit`、
`AuthApplicationService.authenticate`）验证行为影响，再决定全域。

## 5. 对本系列既有结论的影响（需同步修正的记录）

- CONV-03 §1「`readOnly` 全批只能标 1 处」的理由**部分失效**：真正原因不只是
  "MySQL 只读事务遇 FOR UPDATE 报 1792"，还因为这些方法根本不在事务中。两者都成立，但前者
  不是唯一约束。
- CONV-03 §3「224/224」加限定语，见 §3。
- CONV-06 日志批里 `LoanReminderService.refresh` 被 L-C 判为"CONV-03 分类缺口"——按本文件，
  它更可能是"注解本就无效"而非"漏标"，需在 CONV-06 收口时一并复核。
