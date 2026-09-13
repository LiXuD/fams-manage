# D6-02B runtime-compatibility evidence

## 结论与边界

D6-02B 已完成本地实现交接，当前为 `IN_REVIEW`。固定候选组合在一台 macOS ARM64 主机和 Docker Linux ARM64 引擎中通过真实运行门禁；这证明本任务声明的兼容性路径，不证明远端 CI、跨架构执行、产品业务、登录权限、QA、部署或生产可用性。

“双端构建”在本任务中沿用 D6-02A 已实现的两个契约消费面：Java 后端编译/测试与 transport-free 共享 TypeScript 模型检查。PC Vue 与原生微信小程序尚未创建，不能将共享类型包描述为两个客户端已经构建。

## 固定输入与解析结果

| 层面 | 固定/实测值 | 证明方式 |
|---|---|---|
| Java | Oracle GraalVM 21.0.7；release 21 | `java -version` 严格解析完整补丁 |
| Spring Boot | 4.1.1 | 父 POM与 Maven 依赖树 |
| MyBatis | Starter 4.1.0、Core 3.5.19、MyBatis-Spring 4.1.0 | Maven runtime dependency tree |
| Flyway | Core 12.4.0、MySQL database module 12.4.0 | Boot 管理依赖与 runtime tree |
| Spring Session | JDBC 4.1.1 | runtime tree；真实 `SPRING_SESSION*` 表及 cookie |
| MySQL driver | Connector/J 9.7.0 | runtime tree |
| Jackson | Databind 3.1.5（`tools.jackson`） | runtime tree与 HTTP JSON |
| MySQL Server | Docker Official `8.4.11`，索引摘要 `sha256:3466ba4a4828aa8d46fb7c3bc16b67b781c98413cf4ea0fac6feaa6e881faa26` | image repo digest、Linux/ARM64 与引擎架构一致；真实容器 |
| Maven | Wrapper 3.3.4、distribution 3.9.16 | Wrapper 属性与 `./mvnw --version` |
| 契约工具 | OpenAPI Generator 7.25.0 | 保留 D6-02A 完整门禁 |
| TypeScript 工具 | Node 24.18.0、npm 11.16.0、TypeScript 5.9.2 | 严格版本、lockfile、`npm ci` 与 typecheck |

组件版本依据与 schema 来源：

- [Spring Boot 4.1 系统要求](https://docs.spring.io/spring-boot/system-requirements.html)。
- [MyBatis Spring Boot Starter 4.1.0 tagged POM](https://raw.githubusercontent.com/mybatis/spring-boot-starter/mybatis-spring-boot-4.1.0/pom.xml)，其中固定 Core 3.5.19 与 MyBatis-Spring 4.1.0。
- [Spring Session JDBC 指南](https://docs.spring.io/spring-session/reference/guides/boot-jdbc.html)及[4.1.1 MySQL schema](https://raw.githubusercontent.com/spring-projects/spring-session/4.1.1/spring-session-jdbc/src/main/resources/org/springframework/session/jdbc/schema-mysql.sql)。
- [Flyway MySQL 模块说明](https://documentation.red-gate.com/flyway/reference/database-driver-reference/mysql)。
- [Docker Official MySQL 镜像](https://hub.docker.com/_/mysql)。

官方资料用于解释候选来源；通过结论来自下面的本项目实际运行，不用文档支持列表替代兼容性证明。

## 结构、迁移与隔离

默认 `application.properties` 排除 DataSource/Flyway 自动配置，避免 D6-02A 或默认探针误连持久化。只有 `runtime-compatibility` profile 清除该排除并指定：

- 正常 Flyway 位置 `classpath:db/migration/runtime_compatibility`；
- `spring.session.jdbc.initialize-schema=never`；
- JDBC Session cookie 名 `FAMS_D6_02B_SESSION`；
- 小型 Hikari 连接池边界。

正常迁移不可变：

- `V1__create_runtime_probe.sql` 建立唯一键探针表，显式 `ENGINE=InnoDB`；
- `V2__create_spring_session_jdbc_mysql.sql` 源自 Spring Session 4.1.1 官方 MySQL schema，建立两个 InnoDB 表和官方索引/外键；
- `V999__intentionally_invalid_fixture.sql` 位于另一目录，只有额外 `invalid-migration` profile 才进入位置列表。

每次运行生成 `fams-d6-02b-` 严格前缀的唯一容器、卷和数据库，密码由 `secrets` 运行时产生，数据库与应用监听端口随机。报告对密码和机器用户路径脱敏；验证器未使用本机已有 MySQL 8.3 schema。

## RED → GREEN

在任何运行探针实现存在前，`RuntimeCompatibilityHttpTest` 的 3 个真实随机端口请求已经运行。控制器读取到的 Surefire 原始结果为：

```text
Tests run: 3, Failures: 3, Errors: 0, Skipped: 0
expected: <409> but was: <404>
expected: <201> but was: <404>
expected: <200> but was: <404>
```

这证明失败来自缺失路由。`luna-worker` 随后加入最小 profile、MyBatis mapper/service/controller、事务异常和 JDBC Session 行为。代理因额度中断未把精确 RED shell 命令写入报告；原始 Surefire 文件后来被 GREEN 重跑覆盖，因此该命令缺失是明确证据限制，不能补写为已保存。

GREEN 由统一入口在真实任务 MySQL 上强制 `-Dd6.02b.runtime=true`，并同时检查 Maven 总计与专属 XML：

```text
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
RuntimeCompatibilityHttpTest: tests=3, failures=0, errors=0, skipped=0
```

验证器解析逻辑本身先经历 RED（7 个测试因三个所需函数不存在而 error），再实现同一解析路径后 GREEN 为 7/7；增加报告证明约束后再次 RED 为 1 error、GREEN 为 8/8。

## 真实运行路径

统一入口按以下顺序执行：

1. 严格检查 Java、Node、npm、Maven Wrapper/distribution、Docker engine、镜像摘要和镜像/引擎平台一致性；错误 Java、摘要与架构夹具必须被拒绝。
2. 从 Maven runtime dependency tree 检查所有候选解析版本，并拒绝 JPA/Hibernate/ORM auto-DDL 泄漏。
3. 构建可执行 Spring Boot jar；创建唯一卷并用固定摘要启动 MySQL，`mysqladmin ping` 等待实际可用。
4. 以正常 V1/V2 加上非法 V999 启动应用；进程必须非 0，日志必须说明迁移问题。
5. 仅删除并重建该随机数据库；无 profile 启动的应用可以运行，但所有 `/runtime-compatibility/**` 请求为 404。
6. 正常 profile 启动并验证三张表均为 InnoDB、Flyway history 只有成功 V1/V2。
7. 真实 HTTP 写入和读取同一个 MyBatis 值；故意事务先写两行再抛异常，HTTP 409、两次 HTTP 404 与 SQL count 0 共同证明回滚。
8. 真实 HTTP 设置 Session proof，保存 `Set-Cookie`；同 cookie 读取成功，无 cookie 和伪 cookie 均 401，数据库存在一条 JDBC Session。
9. 停止并重启应用；Flyway history 不变，探针行和同一 cookie 的会话 proof 仍可读。
10. 停止应用，重启同名 MySQL 容器并复用同一卷，再启动应用；迁移历史、探针行与会话仍可读。
11. 运行 8 个验证器测试、启用 DB profile 的 Java 测试、默认 Java 回归、锁定 npm 安装、共享 TypeScript typecheck、完整 D6-02A 门禁、提交规则夹具、diff 与敏感/机器路径扫描。
12. 无论前面成功或失败都停止精确应用进程、删除临时日志、容器与卷；清理失败将总退出码改为 1，并再次枚举精确名称。

## 复跑命令与本地结果

可移植命令要求调用者先将固定 Java/Node 放在 `PATH`：

```text
rtk run 'python3 scripts/verify-d6-02b.py'
```

本地调用使用 Oracle GraalVM 21.0.7 与 Node 24.18.0 的机器路径，但这些绝对路径不进入版本化脚本。最新交接前完整运行退出 `0`，关键观察为：

```text
[invalid migration] startup refused the intentionally invalid V999 fixture
[invalid migration recovery] dropped and recreated only the random task database
[profile isolation] absent runtime profile cannot expose a falsely healthy probe
[fresh migration] V1/V2 applied once; probe and JDBC Session tables are InnoDB
[MyBatis write/read] committed value survived the real HTTP round trip
[transaction rollback] explicit 409 rejection and zero matching rows after partial work
[JDBC session] real cookie mutation/read works; missing and wrong cookies are rejected
[application restart] committed data, session, and unchanged migration history persisted
[database restart] same volume retained Flyway history, probe row, and JDBC session
[verifier parser tests] Ran 8 tests — OK
[real runtime HTTP tests] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
[shared TypeScript typecheck] TypeScript: No errors found
[D6-02A complete gate] exit=0
[commit-message fixtures] Ran 7 tests — OK
[tracked-file scan] no sensitive or machine-path matches
[cleanup] exact task container and volume removed; no matching resources remain
```

验证后单独枚举 `fams-d6-02b-` 容器和卷均为空，也没有剩余 `contract-generation-probe` 进程。

## 警告、未证明项与交接

- 默认 `mvn test` 为保护本地环境会跳过 3 个需真实任务数据库的 profile 专属测试；D6-02B 统一入口在同一轮运行中显式启用并证明专属套件与总计均为 0 skipped。D6-02A 回归因此会如实显示 3 skipped，不能将那一行单独当作 D6-02B 通过证据。
- Spring 测试依赖发出 Mockito/Byte Buddy 动态 agent 的未来 JDK 警告；当前 Java 21 路径通过，通用测试代理与质量规则由 D6-03A 决定。
- 本地成功使用 ARM64；验证器也接受 Docker Official 多架构摘要在 Linux AMD64 上与引擎精确匹配，但本任务没有实际 AMD64 运行证据。
- MySQL 镜像需预先以固定摘要存在；本轮预检已实际 pull。D6-03A 才负责可重复的项目级 Compose/统一环境入口。
- 任务没有业务数据、备份恢复、升级旧 schema、并发写、多版本应用兼容、认证权限、客户端或生产负载结论。
- 当前状态保持 `IN_REVIEW`。独立任务级复核通过并由控制器对 reviewed HEAD 再跑统一入口后，才允许冻结该组合、改为 `DEV_VERIFIED` 并进入 D6-02C。
