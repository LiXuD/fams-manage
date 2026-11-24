# CONV-08 · Controller 直达入口事务补齐（含未收口的过度整改项）

> 基线 `953ec22` · 日期：2026-11-24~25 · 分支：main
> 三组并行代理：T-A masterdata、T-B transfer+report+repair、T-C asset/auth/inbound+四单条
> 状态：**代码已过全量单元测试与 checkstyle，但本批存在一个未收口的过度整改问题，见 §4**

## 1. 本批修掉的真实缺陷

A 类缺口 = 被 Controller 经代理直达、却不满足 Spring `public` + `@Transactional` 双条件的
Service/Port 方法。其多段写/多段读跑在多次 autocommit 上：号段预留与落库之间、分页内容与总数
之间都没有原子性或一致性快照。根因是 CONV-03 的分母只数了 `public` 方法。

三组共处置 **57 条**（T-A 26、T-B 17、T-C 14），每条都同时做了两件事：加
`@Transactional(propagation = Propagation.REQUIRED, ...)` 与提 `public`——只加注解不改
可见性等于白加（`computeTransactionAttribute` 在回退类级注解之前就因非 public 返回 null）。
`isolation` 一律抄同文件已标注兄弟方法，未发明；写档带 `rollbackFor = Exception.class`，
锁定读档明确禁 `readOnly`（MySQL 只读事务执行 `FOR UPDATE` 报 1792）。

## 2. 代理纠正了我清单的三类错误

- **读写标记双向错标**：`transfer/TransferService.pending` 我标 `W` 实为 R-lock（体内两条分页
  SELECT + `RequisitionActorContext:98` 的 `FOR UPDATE`）；`receipt` 与 `reassign` 我标 `R`
  实为 `W`（多处 `db.update`）。错因是我生成清单时用了"方法体取下方 45 行"的固定窗口，
  会溢进下一个方法的写语句。
- **有意的无事务设计被误判为缺陷**：7 个附件/照片 `upload` 入口经
  `attachment/AttachmentWriter.java:37-40` 显式守卫——事务已存在即抛
  `IllegalStateException`，注释原文 "An enclosing transaction could roll back after this
  method returned and orphan the file"；`repair/RepairPhotoService.java:21` 类注释亦逐字写明
  "Scanning occurs without a business transaction"。给它们加事务会直接把上传打成 500。
  已作为带证据的 `INTENTIONAL` 豁免进入门禁脚本。
- **假阳性**：`asset/AssetService.java` 内嵌 `@FunctionalInterface private interface
  ReferenceLookup` 的抽象方法 `get(Long id, String kind)` 被当成 Service 入口（代理不读取
  接口抽象方法上的注解）。

## 3. 门禁脚本 `scripts/check-controller-transaction-coverage.py`

判据从"grep 计数"升级为结构判定：Controller 字段类型名→变量名映射解析调用点、前向注解归属
扫描（杜绝跨方法渗漏）、花括号配平取方法体。并把缺陷定义收紧为**真实的原子性缺口**：

- `private` 方法直接排除（永不可能是代理入口）；
- 接口/抽象方法声明排除（无方法体，代理不读注解）；
- **单条语句的读排除**——没有可保护的原子性，加事务只是每次请求白起事务并占用一条连接；
- 同名重载若本类已存在 `public` + 已标注的同名声明，其余视为中间重载委托，不重复报缺口；
- 有意无事务设计走 `INTENTIONAL` 表，**必须附代码级证据文本**，不接受口头豁免。

主树现状：`缺口 = 0`、`EXIT=0`。全量单元测试 `652, Failures: 0`、`0 Checkstyle violations`。
`git diff` 分类器：± 行中非"注解行/声明行/import 行"者 **0 条**，方法体零触碰。

## 4. 未收口：反向对照与实修数不一致，判定本批存在过度整改

同一份最终规则回打 `953ec22` 之前的纯净 `git archive` 副本，只报 **缺口 = 28**
（R 21、W 7），其中 masterdata 仅 14 条；而 T-A 实际修改了 26 条。差额说明：

> 约 **29 条被本批加了事务的入口，按最终判据其实不是缺陷**（单语句读，或重载委托已由
> 合格 public 入口覆盖）。它们是"为了让我那份过时清单归零"而被加上的注解。

后果分级：给单语句读加 `readOnly = true` 是**性能与连接占用开销，不是正确性破坏**，
单元测试与 checkstyle 都拦不到；但它掩盖了真实缺口面，并使脚本的"0"失去意义。

**下一批的第一件事**（不是新工作，是本批收口）：用 `git diff` 逐条比对代理实改的 57 处与规则
判定的 28 处，把不在 28 之内的注解与 `public` 提升**回退**，再让门禁脚本独立跑出 0。
回退前不要把本批视为已验收。

## 5. 证据层缺口仍然存在（不要重复误判为已完成）

V87 预存红未修，167 个 docker 并发/幂等/原子性用例无法跑完。本批 28 条真缺口里含 **7 个写入口**
（`TransferService.action/decide/physical/grant/receipt/reassign/declareUnavailable` 等），
它们**首次**进入事务会改变锁持有窗口与回滚范围——目前只有单元测试绿作为证据，
不足以判定并发正确性。判据与交接见 `docs/development/V87-runtime-red-handoff.md`。
