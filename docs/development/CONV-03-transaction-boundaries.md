# CONV-03 · 事务边界方法级化与 readOnly 硬约束

> 用户批准第 2 方案（A 级 + B2）系列第三批。基线 `4ec93f1`（CONV-01）。
> 日期：2026-11-24 · 分支：main · 执行方式：3 个并行子代理按域分组（H-A/H-B/H-C）+ 2 个触顶续作代理
> 状态：37 个 Service 改造完成并经机械覆盖率审计；含 docker 的运行时并发门禁在 rsync 副本实跑中。

## 1. 规格里的一处硬错误，由子代理实做纠正

规格 §2 原本要求「读方法 → `@Transactional(readOnly = true)`」。H-A（inventory）实做时查明：

> **`readOnly = true` 不能标注在调用图包含 `FOR UPDATE` / `FOR SHARE` 锁定读的方法上**——
> MySQL 在只读事务中执行锁定读直接报错 1792。而本项目的鉴权入口
> `auth/RequisitionActorContext.java:98` 本身就是
> `SELECT fams_system_account … FOR UPDATE`，所以**几乎所有服务入口天然是锁定读**。

据此把判据改为三档：`R`（调用图证明无锁无写，才可标 `readOnly`）、`R-lock`（锁定读，只保留原
`isolation`）、`W`（含写，追加 `rollbackFor = Exception.class` 并保留原 `isolation` 与
`noRollbackFor`）。该纠正已回灌给 H-B/H-C 续作代理。

**结果：`readOnly = true` 全批增量只有 1 处** —— `reminder/DutyService.java:76 current(long)`，
其调用图（`roles` → 普通 `SELECT role_code`、`read` → `ACTIVE_ROW`、`isMember` → `ACTIVE_MEMBER`）
经逐层查证无锁无写且不接 `Actor`、不经 `RequisitionActorContext`。这不是漏做，是本项目全域锁定读
设计的硬约束。若要让 `readOnly` 面真正扩大，前提是「读写锁路径拆分」，属独立批次。

## 2. 一次真实并发回归被核账代理拦下

`loss/LossService.java` 38 个 public 方法中，首代理只覆盖 16 个。类级
`@Transactional(isolation = REPEATABLE_READ)` 已被删除，漏掉的 22 个入口——6 个锁读
（`eligible` `list` `get` `followUps` `recoveryLocations` `photoUploader`）+ 16 个写
（`cancelRecovery` `claimRecovery` `executeRecovery*` `request*Recovery` `recoverTo*` 全系）——
会脱离 RR 事务：锁随 JDBC 自动提交立即释放、幂等 replay 与写库跨事务。**这是会真实改变并发语义的
回归，单元测试无法发现**（167 个运行时/并发用例在无 docker 时是 skipped）。续作代理补齐 22 条注解。

教训写入下方 §6：类级注解下沉是**漏一个方法就等于静默改变该方法事务语义**的高危机械改造，
不能依赖代理自查，必须由主代理跑独立覆盖率审计。

## 3. 主代理机械覆盖率审计

对 37 个文件逐个枚举 4 空格缩进的 `public` 非 static、非构造方法，检查其声明前 9 行窗口内是否
存在 `@Transactional`：

```
TOTAL public instance methods = 224   annotated = 224   真实缺口 = 0
被识别为缺口的 21 处全部是构造方法（方法名 == 类名），属检测器伪阳性
```

并核对：类级 `@Transactional` 在 37 个文件中已全部移除；`jakarta.transaction.Transactional`
0 处引入；`rollbackFor` 只出现在写方法；原 `isolation` 与 `noRollbackFor` 参数逐文件保留。

## 4. 本批改动面

37 个 `*Service.java`（`inventory` 13、`requisition` 6、`reminder` 5、`repair` 4、`loan` 3、
`scrap` 3、`inbound` 1、`loss` 1、`transfer` 1）。**未触碰**任何 `*Port.java`（30 个类级
`Propagation.MANDATORY` 属有意的「强制调用方开事务」设计）、Controller、Views、Input、契约与测试。

两处登记为「改造语义空转」：`reminder/WechatSubscriptionService` 与
`inbound/InboundReassignmentService` 的类与方法均为包私有，Spring 事务默认只作用于 public，
原类级注解本就不生效。若要真正进事务需提升可见性，属签名变更，超出本批范围。

## 5. 门禁证据

- 主树 `-Pquality checkstyle:check@check-java-conventions test`：
  `Tests run: 648, Failures: 0, Errors: 0, Skipped: 167`、`0 Checkstyle violations`、BUILD SUCCESS。
- 完整运行时门禁（隔离 MySQL 8.4.11 固定 digest + ~100 用例 + JaCoCo 75% 分支门禁）在
  rsync 副本 `/tmp/conv-gate` 实跑，CONV-02 与 CONV-03 合并验证。结果补记于 §7。

## 6. 过程坑账

- **类级 `@Transactional` 下沉必须主代理独立审计，不能让代理自证**。本轮首代理在
  `LossService` 漏了 58% 的 public 入口，若直接合入即静默并发回归。判据：按「public 方法数 ==
  注解数」跑机器审计，并把构造方法排除在分母外。
- **`readOnly` 与锁定读互斥**（MySQL 1792），而本项目鉴权入口即 `FOR UPDATE`，因此
  `readOnly` 在此架构下几乎不可用。规范条目应据此记为域内豁免，而不是每轮审查重复发现。
- **150 轮上限**在 37 文件规模上是硬约束：H-B、H-C 首代理均触顶。触顶后的正确动作是先审计磁盘
  （`git status` 对比清单）再派「核账续作」代理，而不是重做；本轮 H-C 11 文件已完成、H-B 完成 9/13，
  续作代理分别只补差集与纠错。
- **`rtk env` 的 PATH 必须含 `/usr/local/bin`**：门禁的 `compose_command` 走
  `run_rtk → ["rtk","docker",…]`，rtk 需要真实 docker CLI；缺该目录时表现为
  exit=127 + `[rtk: No such file or directory (os error 2)]`，且日志 1085 字节即终止，
  易误判为"门禁静默死亡"。

## 7. 运行时门禁结果（待补）

副本 `/tmp/conv-gate` 的 `JAVA01_VERIFY_PASS` / 覆盖率行 / 失败步骤，见本文件提交时的实际结果。
