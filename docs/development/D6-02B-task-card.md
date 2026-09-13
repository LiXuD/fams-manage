# D6-02B task card — 运行兼容性

## 任务身份与目标

- **ID：** D6-02B 运行兼容性。
- **目标：** 在 D6-02A 生成链路之上，以真实、固定摘要的 MySQL 8.4 容器证明 Spring Boot、MyBatis、Flyway、Spring Session JDBC、事务、应用重启、数据库重启及 Java/共享 TypeScript 构建可以共同工作。
- **状态：** `IN_REVIEW`（2026-09-13）；独立复核问题已完成两轮修复且本地完整门禁返回 0，尚待修复轮规格与质量复核，不能提前写为 `DEV_VERIFIED`。
- **分支 / 基线：** `feat/D6-02B-runtime-compatibility`；`c9069f10a5a58a7d101f66490bee3a2ed51cced0`。
- **实现提交：** `777103b`（依赖、profile 与迁移）、`11f2626`（MyBatis/事务/会话 HTTP 探针）、`a6a3eb8`（真实运行门禁）、`c6e7ed2`（门禁防假通过修复）、`97304b5`（测试数据库强隔离）、`04d93cc`（Flyway 异常类判据）。
- **规模：** M；一个运行兼容性闭环，不含业务表、业务契约、客户端应用或项目级 Compose/CI。
- **实施负责人：** `luna-worker`（`gpt-5.6-luna`）完成依赖、迁移、探针和验证器初稿；其额度中断后由控制器接管验证器防假通过测试、证据和交接文档，控制器模型无法核实。
- **复核人：** 独立任务级会话 `/root/d6_01a_task_review` 首轮给出 `Spec Compliance: FAIL`、`Task Quality: FAIL`；其额度受限后由未参与 D6-02B 实施的 `/root/d6_02a_luna_worker` 定向复核，确认 5/6 项处理、Flyway 包名误判仍开放。`04d93cc` 已补负例并修复，当前待再次复核；历史 RED 命令缺口继续如实保留。用户仍负责产品与里程碑验收；该任务复核不能替代 D6-02C 关闭前计划要求的权限、事务、迁移、恢复关键独立技术复核。

## PRD、页面与 AC 边界

- **PRD / 页面 / AC：** 不适用（N/A）。本任务不实现 `F01～F10`、`P01～P26`、`M01～M14` 或 `AC-01～AC-53` 中的任何业务行为。
- **交付含义：** 只证明候选运行组件可以形成可重复的本地数据与会话闭环；`/runtime-compatibility/**` 是显式 profile 下的技术探针，不是产品 API。
- **明确不在本任务：** D6-02C 登录/基础资料/建卡/入库真实设计包，D6-03A 通用 Compose/检查/指标，PC 与小程序脚手架，登录和权限，远端仓库、CI、QA、部署与生产可用性。

## 固定候选输入

| 输入 | 本地门禁固定值 |
|---|---|
| Java | 21.0.7（Oracle GraalVM；编译 release 21） |
| Spring Boot | 4.1.1 |
| MyBatis Starter / Core / Spring | 4.1.0 / 3.5.19 / 4.1.0 |
| Flyway | 12.4.0；另含 `flyway-mysql` |
| Spring Session JDBC | 4.1.1 |
| MySQL Connector/J | 9.7.0 |
| Jackson Databind | 3.1.5（Jackson 3 坐标） |
| MySQL 镜像 | `mysql:8.4.11@sha256:3466ba4a4828aa8d46fb7c3bc16b67b781c98413cf4ea0fac6feaa6e881faa26` |
| Maven Wrapper / Maven | 3.3.4 / 3.9.16 |
| OpenAPI Generator | 7.25.0 |
| Node / npm / TypeScript | 24.18.0 / 11.16.0 / 5.9.2 |

只有独立复核与控制器最终复跑通过后，D6-02B 才能关闭并把该组合写为 D6 阶段冻结输入。版本升级须另立任务，不能在后续业务提交中隐式漂移。

## 运行边界与接口

- **激活方式：** 仅显式启用 `runtime-compatibility` profile；默认配置排除 DataSource 与 Flyway 自动配置，不暴露兼容性路由。
- **HTTP：** `GET /runtime-compatibility/health`、探针写入/读取、故意回滚、Session proof 写入/读取。所有端点只服务本任务验证。
- **数据访问：** `RuntimeProbeMapper` 使用可审查的 MyBatis 注解 SQL；没有 JPA、Hibernate 或 ORM 自动建表。
- **事务：** 正常写入在一个 Spring 事务中提交；拒绝路径在同一事务写入两行后抛出显式异常，HTTP 返回 409，HTTP 与 SQL 均验证两行残留为 0。
- **会话：** 实际 `HttpSession` 写入 Spring Session JDBC；验证器通过真实 `Set-Cookie`/`Cookie` 往返读取。无 cookie 与伪造 cookie 均返回 401。本结果不代表产品登录、认证、授权或停用失效规则已实现。

## 迁移、恢复与数据影响

- **权威机制：** Flyway 是唯一建表机制；`spring.session.jdbc.initialize-schema=never`。正常位置只包含不可变 `V1` 探针表和 `V2` Spring Session 表。
- **会话 schema 来源：** Spring Session 4.1.1 官方 `schema-mysql.sql`，在迁移头部记录版本与原始链接。
- **失败夹具：** `invalid-migration` profile 追加独立 `V999` 非法 SQL；启动必须非 0，且日志同时包含明确 Flyway 异常类、精确 V999 文件和 SQL 失败原因，仅有包名、端口冲突或连接失败不得满足。该夹具不进入正常迁移位置。
- **恢复：** 不提供 Flyway down/undo，也不修改已执行迁移。验证器只删除并重建其随机、严格前缀的临时数据库，再由正常迁移前向恢复。
- **持久性：** 同一个任务卷上重启应用进程和 MySQL 容器；迁移历史保持 V1/V2 各一次，已提交探针值与 JDBC Session proof 均保持可读。
- **环境隔离：** 每次生成不可预测数据库密码、随机监听端口、唯一容器/卷/数据库名；专属 JUnit 在 Spring context 启动前强制校验这些输入且没有固定 URL/凭据回退。未连接或修改本机已有 MySQL schema。
- **清理：** `finally` 停止应用并删除临时日志、精确容器与精确卷；容器/卷的前置枚举、删除和后置枚举逐步检查退出码，任一步不可验证都强制总门禁非 0，同时仍尽力执行全部清理步骤。

## TDD、正向与拒绝路径

1. 先建立 3 个真实 socket 期望；缺实现时 Surefire 为 `Tests run: 3, Failures: 3`，预期 201/409/200 的路由均实际返回 404。原始报告在 GREEN 前由控制器读取；`luna-worker` 额度中断导致其精确 RED shell 调用未保留，作为证据限制明确记录。
2. 最小实现及修复后，启用 profile 并连接任务 MySQL 的测试为 `Tests run: 15, Failures: 0, Errors: 0, Skipped: 0`，其中运行兼容性套件 XML 精确为 3/0/0/0，另有 3 个测试验证数据库输入缺失或不安全时在连接前失败。
3. 防假通过验证器现有 16 个 Python 行为测试：除精确版本、摘要、架构、zero-skipped 和报告证据外，还拒绝无关启动失败或仅 Flyway 包名冒充 V999、Docker 清理/枚举失败或超时、生成前门禁顺序错误、敏感 token 与机器路径漏检。
4. 实际正向路径覆盖 Flyway 新建、MyBatis 写读、真实会话、应用重启、数据库重启、Java 与共享 TypeScript 构建。
5. 实际拒绝/恢复路径覆盖缺 profile 404、非法迁移启动失败、事务两行全部回滚、缺失/错误 cookie 401、版本/镜像夹具拒绝、失败后精确清理。

## 验证与证据

- **统一入口：** `scripts/verify-d6-02b.py`。
- **固定环境命令：** 调用者将 Java 21.0.7 与 Node 24.18.0 放在 `PATH` 后运行 `rtk run 'python3 scripts/verify-d6-02b.py'`。
- **本地结果：** 依赖树精确版本通过；完整 D6-02A 门禁在任何生成构建前通过；真实 MySQL/Flyway/MyBatis/事务/Session/双重重启通过；16 个验证器测试、15 个启用 profile 的 Java 测试、共享 TypeScript 检查、7 个提交信息夹具、差异检查和敏感/机器路径扫描均返回 0。
- **可移植证据：** [D6-02B-runtime-compatibility.md](D6-02B-runtime-compatibility.md)。
- **本机原始报告：** `.superpowers/sdd/2026-09-12-fams-stage6-development-plan/task-d6-02b-report.md`，按仓库规则忽略且凭据已脱敏。
- **交接：** 当前只到 `IN_REVIEW`；同一独立会话复核修复轮和控制器对 reviewed HEAD 的完整复跑均通过后才能改为 `DEV_VERIFIED`，然后进入 D6-02C。

## 回滚与限制

- **代码回滚：** 在未共享的短分支上按当前 D6-02B 提交逆序回退至基线 `c9069f1`；不使用数据库 down migration。
- **数据回滚：** 本任务仅有已被清理的随机临时数据，不存在产品数据回滚或备份恢复结论。
- **当前限制：** 无真实产品 schema、登录/权限、PC、小程序、Compose、CI、外部服务、QA 或生产证据；默认 Java 测试将 3 个 profile 专属测试显示为 skipped，同时仍运行 12 个非专属测试；统一 D6-02B 入口另行提供严格任务数据库输入并确认 15 个测试 0 skipped。
- **已知警告：** Spring 测试依赖报告 Mockito/Byte Buddy 动态 agent 的未来 JDK 警告；Java 21 当前测试通过，D6-03A 决定通用测试代理配置，不能把警告写成生产通过。
- **流程限制：** 实施代理额度中断使控制器接管了验证器收尾；因此必须保留独立任务复核，不以实施者自检代替。
