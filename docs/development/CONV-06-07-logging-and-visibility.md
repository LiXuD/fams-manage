# CONV-06 + CONV-07 · 日志补齐 与 场景一可见性整改（合并批）

> 日期：2026-11-24 · 基线 `0090a69` · 分支：main
> **本文件记录两批合并提交的原因与证据**：CONV-06 日志批（L-A1/L-A2/L-B/L-C）往 Service/Port
> 文件里新增 `LOG` 字段与日志语句，CONV-07（CONV-03R 的 R1）又把同一批 Service 文件的入口方法
> 提为 `public`。**同一文件被两批同时改动，无法按文件切分为两笔提交**，故合批，
> 沿用 JAVA 系列"共享 Views 文件强制一次合并提交"的先例，并在提交信息中分述。

## 1. CONV-06 日志批

| 组 | 范围 | 产出 |
|---|---|---|
| L-A1 | inventory 6 + requisition 4 + inbound 2 + loss 1 共 13 个 Advice | ERROR=9 / WARN=19，45 个 handler 全覆盖 |
| L-A2 | asset/auth/masterdata/repair/loan/scrap/transfer×2/assetimport/targetchange/runtime/contract 共 12 个 Advice | ERROR=9 / WARN=46，47 个 handler 全覆盖 |
| L-B | inventory/inbound/loss/transfer 的 27 个 Service+Port | INFO=65 / DEBUG=64 |
| L-C | requisition/loan/scrap 的 25 个 Service+Port | INFO=62 / DEBUG=70 |

主代理已裁决并落地的收口：**4xx 的 `LOG.warn` 一律去掉末位 Throwable**（17 文件 22 处减参，
`LOG.warn` 语句数 65→65 未变，`LOG.error` 零改动）。理由是
`DataIntegrityViolationException` / `CannotAcquireLockException` 的 `getMessage()` 含完整 SQL 文本，
WARN 带栈会把 SQL 连字面量写进日志；"必须带 Throwable 保留栈"只适用于 5xx。

未做并登记为残留：7 处 advice 无 `HttpServletRequest` 参数记不了请求路径、若干 503 无栈
（补之需给 `@ExceptionHandler` 加参数，属签名变更，本系列不做）；requestId 大部分域不记
（那些 helper 在属性缺失时现算随机 UUID，日志再取会与响应体 id 不一致，记了反而误导）。
L-D / L-E 两组未发。

## 2. CONV-07 = CONV-03R 的 R1：场景一整改

把 **138 个"带 `@Transactional` 但非 public"的 Service 入口方法提升为 `public`**，
使注解从"从未被 Spring 读取"变为真正生效。逐域：

| 域 | 处数 | 涉及类 |
|---|---|---|
| transfer | 7 | TransferService |
| asset | 12 | AssetService 9、AssetPreferenceService 3 |
| inbound | 11 | InboundService 8、InboundReassignmentService 3 |
| loan+auth+reminder+loss | 33 | AssetReturnService 12、LoanService 6、AccountAdminService 6、AssetReturnReassignmentService 3、AuthApplicationService 3、WechatSubscriptionService 2、LossService 1 |
| masterdata | 31 | 11 个 Service |
| requisition | 44 | RequisitionService 15、RequisitionHandoverService 11、RequisitionProxyConfirmationService 7、BusinessResultNoticeService 4、ItemTargetChangeService 4、RequisitionReassignmentService 3 |

手法是脚本化定点插入 `public ` 修饰符，每处先断言"该行含目标方法名且原本无任何可见性修饰符"，
**零 SKIP**（138/138 落地，无一处名称漂移），未触碰方法体、注解参数、返回类型。

## 3. 新增硬门禁

`TRANSACTIONAL_METHODS_ARE_PUBLIC` ——
`methods().that().areAnnotatedWith(Transactional.class).should().bePublic()`，
理由文案直接写明 Spring 在回退类级注解之前就因非 public 返回 null 事务属性。

**反向对照**：把该规则文件拷入 CONV-04 期的 rsync 副本 `/tmp/conv-gate`（仍是整改前状态）运行，
得 `was violated (138 times)`。**扫描器 138 = ArchUnit 138**，第三个正交计数一致，
既证明规则能真的变红，也证明工作面口径没有偏差。

## 4. 门禁证据

- 主树 `-Dtest=FamsArchitectureTest test`：`Tests run: 7, Failures: 0`（新增第 7 条即上述规则）。
- 全量 `./mvnw -pl backend test`（默认生命周期已含 checkstyle）：
  `0 Checkstyle violations`、`Tests run: 651, Failures: 0, Errors: 0, Skipped: 167`、BUILD SUCCESS。
- 场景一复扫：`非 public 方法带 @Transactional = 0`。

## 5. 本批未覆盖 / 必须继续的项

1. **R2 未做**：37 个 Port 类把 `@Transactional(propagation = MANDATORY)` 挂在**类级**而方法全是
   包私有（`asset/AssetRepairPort.java:29`、`loan/LoanReceiptPort.java:19`、
   `loss/LossInitialPort.java:25` 等），"必须由调用方开启事务"这道防护**至今仍未生效**。
   提 public 后 MANDATORY 会开始真实拦截，届时暴露的 `IllegalTransactionStateException`
   是真实调用方缺口，需逐个修调用方而不是回退。
2. **R1b 未做**：190 个"非 public 且完全无注解、但体内含 SQL/Port 调用"的方法（`s1_entry_nonpublic_no_tx`）
   仍未处理，其中含 `TransferService` 的 `action/decide/physical/receipt/reassign/declareUnavailable`
   等写入口——它们连注解都没有，提 public 也不会有事务。
3. **运行时验证缺口（本批最大不确定性）**：651 单元测试全绿**不能**证明 R1 的行为正确性，
   因为这 138 条写链是**首次**真正进入事务，而并发/幂等/原子性用例全部在那 167 个被 skip 的
   docker 运行时用例里。且 main 上 V87 预存红仍会拦下整条运行时门禁。
   `AuthApplicationService.authenticate` 带 `noRollbackFor = AuthOperationException.class`、
   `AssetService.create` 的"号段预留→落库→幂等记录"六段写，是运行时最该优先看的两处。
