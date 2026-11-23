# CONV-04 · 门禁从空转为默认强制，并锁住 CONV-01/03

> 系列第四批。基线 `900a62c`（CONV-03）。日期：2026-11-24 · 分支：main
> 状态：已实施并通过正反双向验证。

## 1. 为什么这批优先于剩余整改项

审查发现的根因不是"程序员写错"，而是**规范没有任何强制力**：checkstyle 只有 10 条机械规则，
且整段挂在 `quality` profile 里——不带 `-Pquality` 的日常构建根本不跑它。任何偏离都不会变红，
于是长期累积。若先把 A5/A6/A8 修完再补门禁，中间窗口内新写的代码仍会重复同类偏离。

本批只做**当前已成立、且能锁住已交付成果**的规则；违规面尚未清零的规则（Service 直注 Mapper、
行长、Javadoc）留到对应整改批之后再启用，避免把门禁降级成"带豁免清单的半开状态"。

## 2. 改动

- `backend/pom.xml`：`maven-checkstyle-plugin` 的 `check-java-conventions` execution 从
  `quality` profile 整体移入主 `<build><plugins>`，phase 仍是 `validate`，
  `failOnViolation=true`。此后**不带任何 profile 的 `./mvnw -pl backend test` 就会跑 checkstyle**。
- `architecture/FamsArchitectureRules.java` 增两条规则并接入 `FamsArchitectureTest`：
  - `SERVICES_DO_NOT_DECLARE_CLASS_LEVEL_TRANSACTIONS` —— 锁 CONV-03；
  - `APPLICATION_ENTRY_POINT_IS_IN_THE_ROOT_PACKAGE` —— `@SpringBootApplication` 类必须位于
    `com.fams`，锁 CONV-01。测试类自身的嵌套启动配置被既有
    `ImportOption.DoNotIncludeTests` 排除，不会误报。

## 3. 双向验证（正例 + 反向对照）

正例（当前 main）：`./mvnw -pl backend -Dtest=FamsArchitectureTest test`
→ checkstyle `0 violations`、`Tests run: 5, Failures: 0`、BUILD SUCCESS。

**反向对照**（关键）：把两条新规则文件与根包 `FamsApplication` 拷入 `6431f06` 的
`git archive` 副本 `/tmp/fams-base`（即 CONV-01/03 之前的代码）后运行：
→ `Tests run: 5, Failures: 2`，其中类级事务规则报
**`was violated (37 times)`**，逐条列出 `com.fams.inventory.*Service` 等类。

这一步同时证明两件事：规则不是空转（能真的变红），且 **ArchUnit 独立数出的 37 处类级事务
Service 与主代理机械覆盖率审计的 37 完全吻合**——CONV-03 的工作面口径得到第二个正交工具的确认。

## 4. 本批不启用的规则及原因

| 规则 | 为何暂不启用 |
|---|---|
| Service/Controller 不得直注 `*Mapper` | 当前 28 处违规，须等 CONV-05（B2 Repository 封装）清零 |
| `LineLength(120)` | 565 行违规，须等格式重排批 |
| `MethodLength(80)` / `ParameterNumber(5)` | 6 处 / 43 处违规 |
| `JavadocType` / `JavadocMethod` | 289 文件无类注释、`@param` 全仓 0 次 |
| `@Transactional` 必须含 `rollbackFor` | ArchUnit 可取注解参数，但"写方法"无法静态判定；只读与锁定读合法省略。改为代码评审清单项，不进机器门禁 |

## 5. 门禁证据

`-Dtest=FamsArchitectureTest test`：checkstyle 0 violations + 5/5 ArchUnit 通过。
全量单元测试与运行时门禁结果记于 CONV-05 收口档（本批改动只涉 pom 生命周期与测试类，
不触碰产品代码路径）。
