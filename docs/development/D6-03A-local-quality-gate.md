# D6-03A 本地统一质量门禁证据

## 1. 结论与边界

D6-03A 当前为 `IN_REVIEW`。实现候选在 macOS ARM64 主机、Docker Linux ARM64 引擎和真实
MySQL 8.4.11 上完成首轮端到端全绿：统一入口执行 77 项阻断观察并退出 0，随后又把验证器自身
28 个单元测试纳入同一阻断入口。送审 HEAD 必须再次运行完整入口；只有用户本人完成非实施者
规格/质量复核，且控制器对 reviewed HEAD 重跑通过后，才能写 `DEV_VERIFIED`。

本任务只证明本地开发环境、探针运行路径和质量规则可执行，不证明产品业务、真实业务权限与事务、
PC/小程序、远端 CI、QA、备份恢复或生产可用。

## 2. 固定工具与输入

| 范围 | 固定/实测值 | 阻断依据 |
|---|---|---|
| Java / Maven | Oracle GraalVM 21.0.7；Maven distribution 3.9.16 | 统一入口严格解析；Enforcer 要求 Java `[21,22)`、Maven `[3.9.16,3.10)` |
| Java 测试 | Surefire 3.5.6；Mockito 由 Maven 解析后作为显式 `-javaagent` | 拒绝 Mockito 自附加和动态 agent 警告；要求 23 个指定测试、0 failure/error/skipped |
| Java 静态/架构 | Checkstyle 14.1.0 / plugin 3.6.0；SpotBugs plugin 4.10.4.1；ArchUnit 1.4.2 | 任一报告 finding、分析错误或架构断言失败即退出 1 |
| Java 覆盖率 | JaCoCo 0.8.15 | 行、分支均以 75% 为初始阻断阈值 |
| TypeScript | Node 24.18.0、npm 11.16.0、TypeScript 5.9.2 | 锁文件安装、工作区 typecheck 和解析依赖树 |
| TypeScript 静态 | ESLint 10.10.0、`@eslint/js` 10.0.1、typescript-eslint 8.70.0 | `--max-warnings=0`；错误或实际 warning 数大于 0 即失败 |
| 契约与历史门禁 | OpenAPI Generator 7.25.0；Python 3.12 / PyYAML 6.0.2 | 完整复跑 D6-01A 与 D6-02A/B/C；生成、编译、真实运行和清理不得跳过 |
| 数据库 | Docker Official MySQL 8.4.11，摘要 `sha256:3466ba4a4828aa8d46fb7c3bc16b67b781c98413cf4ea0fac6feaa6e881faa26` | 镜像摘要、Linux 架构、健康检查、真实迁移与读写 |
| 可观测性 | Spring Boot Actuator 4.1.1、Micrometer Prometheus 1.17.1 | 真实 HTTP、独立管理端口和 `lsof` 回环监听 |

Python 通过任务临时 shim 暴露 `python3` / `python3.12`，只影响 D6-02C 子进程；它不把 Python
安装目录整体提前到 `PATH`，因此不会把锁定的 Node 24 意外替换成本机旧 Node。

## 3. 规则、范围与排除

### 3.1 Java

- Checkstyle 检查主代码和测试代码，阻断 tab、缺少文件末换行、星号导入、空语句、缺 braces、
  单行多语句、文件名/外部类型不一致、未使用导入和小写 `l`。
- SpotBugs 使用 `Max` effort、`Medium` threshold。只排除由权威 OpenAPI 生成且由确定性门禁另行
  验证的 `com.fams.contract.api|invoker|model`；另对
  `RuntimeCompatibilityService` 构造注入的包内 Mapper 保留一个精确 `EI_EXPOSE_REP2` 排除。
- ArchUnit 阻断顶层模块循环、跨模块直接访问 Mapper、Controller 类或方法持有事务边界。
- 对三类架构违规均有故意失败夹具，测试要求规则抛出 `AssertionError`；生产类再独立执行同一规则。
- Surefire 使用显式 Mockito agent。允许 JVM 关于 bootstrap classpath sharing 的 CDS 提示；
  Mockito self-attach 和动态加载 agent 提示是阻断项。

### 3.2 TypeScript

- 当前检查 `packages/api-types/src/**/*.ts`；未来 `apps/web/src/**/*.{ts,tsx}` 和
  `apps/miniprogram/**/*.ts` 创建后会自动纳入，目录不存在时不冒充客户端已完成。
- `node_modules` 与 generator-owned `target` 不做 ESLint 源码检查；后者由 OpenAPI 双次生成零差异、
  隔离编译和未跟踪检查负责。
- 临时负例同时违反 `@typescript-eslint/no-unused-vars` 与
  `@typescript-eslint/no-explicit-any`；必须由这两个规则拒绝，随后删除并再次 lint。

### 3.3 覆盖率

真实 MySQL 兼容性测试启用后，当前有效手写 Java 源码为：

- 行覆盖：77 / 101，`76.24%`；
- 分支覆盖：6 / 8，`75.00%`；
- 初始阻断阈值：行 `75%`、分支 `75%`。

OpenAPI 生成的 API、invoker、model 包不进入分母，因为它们由契约生成与编译门禁负责。没有排除
任何手写 Controller、Service、Mapper 或其他 main 源码；测试源码本就不计入生产覆盖率分母。未启用真实运行套件的质量命令曾
在行 `25%`、分支 `0%` 处被 JaCoCo 正确拒绝，证明阈值不是只生成报告而不阻断。

覆盖率不能替代后续业务权限、状态、事务、并发、幂等和恢复用例；产品代码增加后分母自动增长，
低于阈值即失败，阈值调整必须有新的实测与独立审查。

## 4. Compose、指标与恢复

`compose.yaml` 只创建一个 MySQL 服务：固定摘要、UTC、`utf8mb4_0900_ai_ci`、健康检查、命名卷，
数据库名/用户/密码均要求运行时变量。随机任务项目名与宿主端口避免碰撞，MySQL 只发布到
`127.0.0.1`。统一入口验证首次 `up --wait`、重复 `up --wait` 和镜像元数据。

`local-observability` profile 使用独立随机管理端口并固定：

- `management.server.address=127.0.0.1`；
- 只暴露 `health,prometheus`；
- health 不返回 components/details；JMX 关闭；
- 业务端口访问 `/actuator/prometheus` 为 404；
- 管理端口 health 为 UP，Prometheus 文本包含 JVM 指标；
- `lsof` 证明监听地址为 IPv4 loopback，不接受 `*:<port>`。

恢复检查先通过真实 HTTP 写入任务探针，干净停止并重启应用后读取同一行；随后重启 MySQL、等待健康，
由仍运行的应用再次读取同一行并复验指标边界。该结果只证明同卷本地开发恢复，不是备份/RPO/RTO
恢复演练。

无论成功或失败，finally 路径都停止任务应用，执行精确 Compose project
`down --volumes --remove-orphans`，并按 project label 确认容器、网络、卷均为空；只清理
`backend/target` 与 `packages/api-types/target`，最后要求工作树干净。

## 5. 统一入口、失败等级与实测结果

统一入口：

```text
rtk python3 scripts/verify-d6-03a.py
```

调用者需先将计划固定的 Java 21.0.7、Node 24.18.0 放在 `PATH`。验证器会再严格解析，不接受机器
默认 Java 25 或 Node 16。阻断项包括：前后工作树、验证器单元测试、固定版本、提交格式、当前树
token/private-key 扫描、锁文件、D6-01A/D6-02A/B/C、Compose/镜像、Java 测试与报告、架构负例、
行/分支覆盖率、TypeScript lint/typecheck/负例、依赖收敛与高危 npm audit、真实 HTTP 指标、应用和
数据库重启、资源清理。失败、超时、跳过或未执行均不能汇总为成功。

首次完整闭环全绿发生在实现候选 `a6b4a56`：

```text
D6-01A verification PASSED
D6-02C verification passed（内含 D6-02A/B 保留门禁）
Java tests=23, failures=0, errors=0, skipped=0
Checkstyle=0, SpotBugs=0
lines=77/101 (76.24%), branches=6/8 (75.00%)
TypeScript lint/typecheck=0；临时双规则负例被拒绝
npm audit --audit-level=high：0 vulnerabilities
business metrics=404；management health=UP；JVM metrics present；loopback listener
application restart recovery=PASS；database restart recovery=PASS
Compose task container/network/volume removed；generated targets removed；worktree clean
D6-03A verification passed (77 blocking observations; local development only)
```

`c72db1c` 又把当前 28 个验证器单元测试纳入统一入口。送审 HEAD 的最终复跑结果以计划 §六执行记录
和本次用户复核对象为准。

以下非阻断提示仍如实保留：`git-raw-commits@4.0.0` 是上游弃用提示；Java 测试有 CDS sharing 提示。
二者均没有被写成“无 warning”，且不影响当前规则执行。高危依赖审计、SpotBugs 和 Checkstyle
仍分别要求零发现。

## 6. 修复轮次与防回归

统一入口在形成完整全绿前真实发现并修复：

1. D6-01A 的旧敏感名称规则会把后续合法的 password 拒绝示例与 Compose 占位配置判为失败；
   现改为在 `338d49d` reviewed commit 复跑冻结门禁，并对当前 HEAD 另做精确 token/private-key 扫描。
2. D6-02C 顶层和嵌套 Python 命令最初未稳定解析 PyYAML 6.0.2；临时 Python-only shim 固定解释器，
   同时保留 Node 24 优先级。
3. ESLint 命令行自身的 `--max-warnings=0` 曾被全局字符串匹配误判为 warning；现只解析实际计数摘要。
4. Prometheus 请求最初使用 JSON `Accept`；现按 text media type 请求，健康仍用 JSON。
5. RTK 默认过滤曾隐藏应用启动 marker；应用改由 `rtk proxy java` 原样记录，保留启动日志断言。

上述解析、失败传播、版本、Compose、覆盖率、TypeScript、指标和运行命令均有验证器单元测试。

## 7. 用户本人复核模板

请对会话中固定的完整 SHA 回复：

```text
复核对象：<完整 SHA>
范围符合：PASS / NEEDS_FIX / QUESTION
质量规则与负例：PASS / NEEDS_FIX / QUESTION
Compose、指标与恢复：PASS / NEEDS_FIX / QUESTION
总评：PASS / NEEDS_FIX
问题清单：无，或逐条写明文件、规则、风险和期望结果
复核边界：本结论仅覆盖 D6-03A 本地开发门禁，不代表远端 CI、产品、QA 或生产验收
```
