# CONV-01 · 后端规范整改系列开局与启动类归位

> 用户批准的第 2 方案（A 级问题 + B2）后端 Java 规范整改系列首批。不进入 D6-01～D6-27 台账、
> 不占用其状态，沿用 JAVA/ARCH 先例作为用户指定结构批。
> 日期：2026-11-23 · 分支：main · 基线：`6431f06` · 审查依据：`java` 与 `springboot` 技能
> 状态：CONV-01 已实施、审计并过单模块门禁。

## 1. 审查结论与本批范围

静态审查（460 主源文件 + 176 测试文件）确认的分级问题与用户对四个范围问题的裁决：

| 项 | 内容 | 用户裁决 |
|---|---|---|
| A1 | 唯一 `@SpringBootApplication` 埋在 `com.fams.contract.probe` | 归位根包 |
| A2 | 67 个文件类级 `@Transactional`，`rollbackFor` 全仓仅 1 处 | 方法级拆分 |
| A3 | 460 文件仅 4 处 Logger、全仓 7 条日志语句 | 全量补（含 Port 与只读 DEBUG） |
| A4 | 41 处 Controller `catch` 分散本地、`report` 域无 Advice 且 4 个控制器重复实现 csv 失败导出 | 收敛到 Advice（见下 A4 精核） |
| A5 | 661 处状态字符串比较、47 文件裸写状态 | 全量类型化为枚举 |
| A6 | `@param` 0 次、289/460 文件无类注释 | 保持英文 + 门禁强制有注释 |
| A8 | 565 行超 120 字符、43 处参数超 5 个、6 处方法超 80 行 | 全量重排后启用硬门禁 |
| B2 | 14 个 Mapper 与 Controller/Service 同目录、28 处被 Service 直注 | 归位子包 + Repository 封装 |

### A4 精核（CONV-02 开工前逐条核对，两处修正审查口径）

- 41 处 `catch` 中 39 处属真实违规；2 处是**有意的重启重载控制流**，保留不动并登记理由：
  `AuthSessionRestartTest` 同型的 `rethrowIfSessionUnavailable` 包装（`auth/MiniAuthController.java:49`
  与 `auth/MiniBindingController.java:64/134` 中仅转换不改状态码的 2 处按实际读码结论处置）。
- 26 处 `return ResponseEntity.badRequest()/notFound()/status(403)` **不是违规**：`report` 与
  `runtime` 域的契约写作 `responses: {'400': {description: ...}}` 且无 `content`，即契约要求
  **空体** 400/403。改成抛异常会经 Advice 产出带 `ErrorResponse` 体的响应，属契约改动。
- `report` 域 21 处空 400 与 `runtime`/`reminder` 各 1 处空 404 **保留**，但 `report` 4 个导出
  控制器逐字重复的 `if (bad) return badRequest().build(); catch (Exception) { return ok(csv) }`
  抽为域内单一 helper，消除重复实现漂移风险。
- 据此 A4 的真实工作面是 **39 处 catch + 新增 `com.fams.report.ReportErrorHandler`**。


B1（补 94 个 Service 接口）、B3（引入 MyBatis-Plus）、B4（`Result<T>` 信封）、B5（DO/DTO/VO
重命名）经论证登记为**项目有意为之的豁免项**，本系列不实施。

## 2. 三项系列裁决（贯穿 CONV-01～NN，不得漂移）

1. **A5 枚举化止步于线格式边界**。对外 `<域>Views` 响应 record、`<域>Input` 入参 record 与
   JDBC 读写边界一律保持字符串，枚举只替换内部状态比较与状态集合常量。JAVA 系列确立的
   "响应 JSON 逐字节 wire 等价"是硬红线，枚举进入 record 组件即违反它。
2. **B2 覆盖 JAVA 系列的"不搬目录"裁决**，但仅覆盖 Mapper 与新建 Repository 两类文件；
   Controller/Service/Views/Input 仍留在域包原位，垂直切片与 ArchUnit 无环规则不动。
3. **A4 只允许把 Controller 的 catch 迁到已有 `@RestControllerAdvice`**，不得改错误码、
   HTTP 状态与 `ErrorResponse` 形状；`x-error-codes` 与 `x-required-error-statuses` 是契约。

## 3. A7 撤回：本批唯一的审查误判

`@RequestBody` 缺 `@Valid` 的 14 处经逐个核对为**误判**，已撤回改动并登记：

- 校验并未缺失，而在 Service 层按契约逐字节实现。`AssetImportRollbackService.java:255`
  `requireReason` 判 `null || isBlank || strip().length() > 500`；
  `AssetInitializationWindowService.java:56` 另含 `expiresAt` 须晚于当前且不超 30 天；
  `InboundService.java:257` 用 `required(body.get("decision"), 20, "INBOUND_DECISION_INVALID")`。
- 抛出的 `IllegalArgumentException` 由各域 Advice 映射为契约规定的 400 + 具名错误码
  （如 `ASSET_IMPORT_ROLLBACK_REASON_INVALID`）。加 `@Valid` 会让 Bean Validation 抢先抛
  `MethodArgumentNotValidException`，assetimport 的 Advice 无此 handler，响应体退化为 Spring
  默认形状，直接破坏 `x-required-error-statuses` 契约。
- `Map<String,Object>` / `JsonNode` 入参属 JAVA 系列登记的"Map 只读监控"保留面，非本系列范围。

## 4. 本批实际改动

| 文件 | 改动 |
|---|---|
| `backend/src/main/java/com/fams/FamsApplication.java` | 新建根包产品入口，无 `scanBasePackages` |
| `backend/src/main/java/com/fams/contract/probe/ContractProbeApplication.java` | 删除 |
| `backend/src/test/java/com/fams/auth/AuthSessionRestartTest.java` | 锚点改 `FamsApplication` |
| `backend/src/test/java/com/fams/observability/LocalObservabilityHttpTest.java` | 锚点改 `FamsApplication` |
| `backend/src/test/java/com/fams/contract/ContractProbeHttpTest.java` | 嵌套 `TestApplication` 扫描补入 `com.fams.security` |
| `scripts/verify-d6-03a.py` | 重启日志断言 `Started ContractProbeApplication` → `Started FamsApplication` |
| `backend/pom.xml` | `name`/`description` 改为反映产品后端 |

**artifactId 保留 `contract-generation-probe`**：`run-arch-ui-stack.py`、`run-d6-{04,07,08,11,12}-browser-runtime.py`
共 6 个脚本硬编码 `backend/target/contract-generation-probe-0.1.0-SNAPSHOT.jar`，另有 139 个文件
（含历史证据档与 `.idea`）引用该名。改名属独立决定，不在规范整改范围。

## 5. 过程坑账

- **入口类曾被当作"隐式扫描桥"**。`ContractProbeHttpTest` 的嵌套 `TestApplication` 只扫
  `com.fams.contract.probe`，过去旧入口类自身被扫为组件、其 `scanBasePackages = "com.fams"`
  又间接带入 `com.fams.security.NonProductSecurityConfiguration` 的 permitAll 链。删除旧入口后
  该测试以默认安全配置失败（9 处 200/400 变 401）。修复是把安全包扫描显式写进测试自身，
  不改成全仓扫描——后者会带入无数据源的产品 Bean。后续任何移除"看起来没人用"的配置类前，
  必须先确认是否被别处的 `scanBasePackages` 递归带入。
- GitNexus 的 `impact`/`detect_changes` MCP 工具在本会话不可注册（`mcp_list` 返回空），按
  AGENTS.md "尚未配置不得声称已生效"，本批以 `git diff` 文件集审计替代影响面分析。

## 6. 门禁证据

基线（HEAD `6431f06` 的 `git archive` 副本 `/tmp/fams-base`）：
`Tests run: 648, Failures: 0, Errors: 0, Skipped: 167`、`You have 0 Checkstyle violations.`、41.6s。

CONV-01 改动后（同一命令口径 `-Pquality checkstyle:check@check-java-conventions test`）：
`Tests run: 648, Failures: 0, Errors: 0, Skipped: 167`、BUILD SUCCESS，与基线逐项相同。
`ContractProbeHttpTest` 单独复跑 9/9 通过。

含 docker 的完整 `verify-d6-03a` 运行时门禁（约 83 步）留待系列收口批统一实跑，本批未跑。
