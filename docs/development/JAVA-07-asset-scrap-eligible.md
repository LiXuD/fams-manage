# JAVA-07 · AssetScrapPort.eligible 信封 Map→record 类型化

> 用户指定的后端结构批（JAVA-01～06 之后的 asset 域跨域 Port 收口），不进入 D6-01～D6-27 台账、不占用其状态。
> 日期：2026-11-21 · 实施分支：`worktree-agent-general-purpose-5fd78263`（隔离 worktree）· 基线：`2676458`（JAVA-04 提交）
> 状态：主代理审计通过，隔离 worktree 段级合并回主树，三批合并门禁通过，已收口提交。

## 1. 背景与红线

JAVA-03 把 scrap 读侧类型化时，`AssetScrapPort.eligible` 按当时规格保留 Map 信封，
在 scrap 侧以 `ScrapViews.eligiblePage` 边界转换为 `EligiblePage`（JAVA-03 §6.1 登记为
后续跨域批候选）。本批把该信封收口到 asset 域：record 定义在 `AssetScrapPort` 内
（对照 `AssetTransferPort` 的 Target/Snapshot/Custody/After 先例，asset 域拥有自己的
跨域形状），scrap 侧删除边界转换，直接透传 port 类型化页。

红线复刻 JAVA-01～06：

1. wire 形状逐字段等价；契约 `contracts/fams-core-v1.yaml`、`packages/api-types`、
   `apps/web`、`apps/miniprogram` 零改动。
2. 内部行 Map、审计 payload、幂等指纹不动；`AssetScrapPort` 其余方法
   （standaloneSource/lossSource/freeze/restore/execute/reregisterToStock 等与 scrap
   存储深耦合）一字未动。
3. 比较口径：各自序列化 JSON 文本再解析比树（归一整型宽度，键序不参与；键存在性、
   显式 null、值形状全部参与）；含 List 分量 record 紧凑构造器 `List.copyOf`。
4. JDBC 原始类型不臆断：eligible SQL 仅 `SELECT a.id,a.asset_code,a.name,a.version`
   四列，按 V4__create_asset_fact.sql 核实 `id BIGINT`、`asset_code VARCHAR(128) NOT NULL`、
   `name VARCHAR(256) NOT NULL`、`version BIGINT NOT NULL`。

## 2. 形状设计

改前投影（冻结为 oracle）：行 `Map.of("id", row.get("id").toString(), "assetCode",
row.get("asset_code"), "name", row.get("name"), "version", row.get("version"))`——
id 字符串化，assetCode/name/version 为 JDBC 原值直透（VARCHAR→String、BIGINT→Long）；
页 `Map.of("items", items, "total", Long, "page", int, "pageSize", int)`，total 来自
`queryForObject(COUNT(*), Long.class)` + requireNonNull。改前不经 clean/view 二次投影，
port 行装配即最终 wire。

| record（AssetScrapPort 内嵌） | 分量 | 依据 |
|---|---|---|
| `EligibleAsset` | `String id, String assetCode, String name, long version` | id 保持 toString；VARCHAR NOT NULL→String 有 schema 依据；BIGINT NOT NULL→long，文本往返归一宽度（JAVA-01 totalQuantity 同口径），Number 防御转换与 JAVA-03 边界转换逐字一致 |
| `EligiblePage` | `List<EligibleAsset> items, long total, int page, int pageSize` | 键形与改前 Map.of 完全相同（camelCase，HTTP wire 专用，不落存储，无需 @JsonProperty snake_case）；items 紧凑构造器 `List.copyOf` |

无 NEW_NULL 条件键、无显式 null 键：改前四键/四列总是 put 且非空，record 默认包含即等价。

## 3. 改动清单

主代码 4 改（+21/−30），测试 1 改（+25/−6）：

| 文件 | 变化 |
|---|---|
| `asset/AssetScrapPort.java` | 类首新增 `EligibleAsset`/`EligiblePage` record（AssetTransferPort 先例位）；`eligible` 返回 `EligiblePage`，SQL/where/scope/args/排序/分页逐字未动，仅行装配与信封改 record；其余方法未动 |
| `scrap/ScrapReadService.java` | `eligible` 返回 `AssetScrapPort.EligiblePage`，直接透传 port 页，删除 `ScrapViews.eligiblePage` 消费（2 行） |
| `scrap/ScrapController.java` | `eligible` 端点返回类型同步 + import；请求参数与校验一字未动 |
| `scrap/ScrapViews.java` | 删除 `EligibleAsset`/`EligiblePage`/`eligiblePage` 三段；类 javadoc 的 ScrapEligiblePage 契约镜像说明改为指向 AssetScrapPort。**diff 仅落在 eligible 相关段落**，AttachmentMetadata 等其余段落零改动（主树并行批领地） |
| `scrap/ScrapWireShapeTest.java` | `pagesMatchLegacyAssembly` 的 eligible 段迁移：改前 port 装配逐字冻结为 `legacyEligibleEnvelope` oracle，类型化侧用 `AssetScrapPort.EligiblePage`；新增空页断言（items 键在场、空数组）；`eligibleAsset(row)` 测试助手逐字镜像 port 行映射（JAVA-01 `snapshot(row)` 先例）；断言语义不改 |

## 4. 判据与单元 oracle 边界

- `ScrapWireShapeTest` 12/12 全绿（含迁移后的 eligible 页装配与新增空页用例）。
- 单元 oracle 只证明「行→record→JSON」与冻结改前装配的序列化等价。port 的行映射是
  eligible 方法内联 lambda（无独立工厂，保持 AssetTransferPort 先例的最小表面），测试
  助手逐字镜像该映射；真实 HTTP 运行时用例（ScrapSubmitHttpRuntimeCase 等消费
  /eligible 的链路）由主代理门禁实跑，沿用 JAVA-01/03 教训登记。
- 本批无跨边界快照直比路径、无存储 JSON 写入（eligible 页不落库、不进幂等回放），
  无 JAVA-01 式文本往返回归风险点。

## 5. 影响分析（GitNexus）

编辑前按 AGENTS 要求执行 `impact -d upstream`（Node 24 前缀 + --repo 主树）：

- `AssetScrapPort.eligible`：LOW，1 直接调用方（ScrapReadService.eligible），0 执行流。
- `AssetScrapPort`（类）：MEDIUM，8 直接依赖，全部 scrap 包（ScrapService/
  ScrapReviewService/ScrapReadService 及其构造器），0 执行流；本批仅改 eligible 签名，
  其余消费点引用未动方法。
- `ScrapReadService.eligible`：LOW，1（ScrapController.eligible）；`ScrapController.eligible`：LOW，0 上游。
- `ScrapViews.eligiblePage`：LOW，1（ScrapReadService.eligible）；`ScrapViews`/`ScrapReadService`/`ScrapController` 类级均 LOW。
- 无 HIGH/CRITICAL；影响 confined 于 scrap→asset eligible 链。全仓 grep 核实
  `ScrapViews.Eligible*` 无其他消费点（transfer 域同名 `TransferViews.EligibleAsset`
  为独立形状，未触碰）。未提交，detect-changes 由主代理合并后统一执行。

## 6. 验证证据（实施代理，非产品门禁）

环境：JDK 21.0.7（GraalVM），隔离 worktree（基线 2676458 + 本批 5 文件，无并行改动混入）。

- 定向单测：`JAVA_HOME=graalvm-jdk-21.0.7 ./mvnw -pl backend -Dtest='ScrapWireShapeTest' -DfailIfNoTests=false test`
  → **Tests run: 12, Failures: 0, Errors: 0**，BUILD SUCCESS。
- 全仓编译：`./mvnw -pl backend -q test-compile` → **exit=0**。
- Checkstyle 项目口径：`./mvnw -pl backend -Pquality checkstyle:check@check-java-conventions`
  → **0 violations**，BUILD SUCCESS（含末尾换行检查）。

## 7. 门禁证据

产品门禁完整 Java 序列（与 JAVA-05/06/07 合并树单次实跑，2026-11-21，JAVA-03/04 先例）：
**83 步全部 exit=0**，surefire 聚合 **778 项测试 0 失败 0 错误**，`verify_java_reports` 通过
（Checkstyle 0、SpotBugs 0），覆盖率 **LINE 27219/29236（93.10%）、BRANCH 9882/13045（75.75%）**。
主代理合并树复跑单元判据 70/70。证据：[JAVA-05-06-07-java-gate.txt](evidence/JAVA-05-06-07-java-gate.txt)。

## 8. 偏差与裁决点

1. **基线快进（实施决策，非形状裁决）**：worktree 创建提交为 `8696b3c`，早于 main 上
   JAVA-01～04 提交，`ScrapViews.java`/`ScrapWireShapeTest.java` 与规格指定的样板在该
   基线不存在。分支无本地改动，`git merge --ff-only main` 快进至 `2676458`（JAVA-04）
   后实施；未丢弃任何工作，主树未提交改动不受影响。
2. **record 归属**：按规格定义在 AssetScrapPort 内（asset 域拥有），ScrapViews 三段删除；
   与 JAVA-03 的 scrap 侧边界转换先例互斥，属规格指定的收口方向，无歧义。
3. **version 分量 long**：改前直透 JDBC Long，record 用 long + Number 防御转换，文本
   往返归一宽度，wire 等价；BIGINT NOT NULL 有 schema 依据，非臆断。
4. **排除清单守住**：AssetScrapPort 其余方法、内部行 Map、审计、指纹、apps/web、
   apps/miniprogram、contracts/、packages/api-types、其他包一律零改动；ScrapViews
   diff 经逐 hunk 核对仅落在 eligible 段（类 javadoc eligible 句、两 record、工厂）。
5. AOCI 维护与 detect-changes 收尾由主代理合并后统一执行（本会话无 AOCI 工具入口，
   且规格禁止提交）。
6. **裁决点：无。** 未遇到形状不可判定、HIGH/CRITICAL impact、必须触及排除段或疑似改前缺陷。

## 9. 下一步

主代理合并本批（ScrapViews 仅 eligible 段，预期与 AttachmentMetadata 并行批无冲突），
在固定候选统一跑产品门禁完整 Java 序列与 /eligible 运行时链路，补 §7 证据后按 §四.4
提交。`AttachmentEvidencePort.list` 信封（inventory 共用）的类型化仍为后续跨域批候选。
