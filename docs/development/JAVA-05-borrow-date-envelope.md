# JAVA-05 · BorrowRequestDatePort.eligible 信封类型化

> 用户指定的后端结构批（JAVA-01～04 之后的跨域信封收口），不进入 D6-01～D6-27 台账、不占用其状态。
> 日期：2026-11-21 · 分支：main · 基线：`2676458`（JAVA-04 提交）
> 状态：主代理审计通过，三批合并门禁通过，已收口提交。首轮门禁唯一 SpotBugs 发现（COUNT 可空拆箱）已按 LoanService 先例 requireNonNull 修复。

## 1. 背景与红线

JAVA-02 §6.1 把 `BorrowRequestDatePort`（business 包跨域接口）登记为「保留 Map 遗留」：
当时只在 loan 侧边界把行 Map 转换为 `LoanViews.BorrowRequestChoice`，port 信封不动。
本批把该信封收口为类型化 record，复刻 JAVA-01/02 标准：

1. **范围**：仅 `eligible(...)` 信封。`outstanding()/snapshot()` 返回内部行 Map 供
   loan propose/decide 业务判定（`request_status`/`version`/`expected_return_date` 读取），
   属内部行 Map 红线，保留；`extend()` 早已类型化（基本类型/LocalDate/Instant），不动。
2. **wire 等价**：eligible 页与行形状逐字段等价。改前链路核实：
   `RequisitionBorrowDatePort.eligible` 返回 `Map.of("items", 原始 JDBC 行, "total", Long,
   "page", int, "pageSize", int)`，`LoanService.eligibleRequests` 经
   `LoanViews.borrowRequestChoice` 投影——id→toString 字符串、version→Number.longValue、
   **expected_return_date→value.toString() 日期字符串**（loan clean 的 DATE 语义，
   java.sql.Date/LocalDate 双驱动形态同 toString 为 yyyy-MM-dd）、request_status→String 直转。
   本批把该投影原样移入 requisition 适配器，loan 侧直接消费类型化返回，wire 零变化。
3. **契约零改动**：record 命名镜像 `contracts/fams-core-v1.yaml` 的
   `BorrowRequestChoice`/`BorrowRequestChoicePage`（items/total/page/pageSize 四键全必需）。
   `contracts/`、`packages/api-types`、`apps/web`、`apps/miniprogram` 零改动。
4. **幂等**：eligible 是读路径，无幂等存储链（核实 LoanService 仅 propose/decide 写
   fams_idempotency_record，BorrowRequestChoice 不参与 Reply），无跨版本回放需求。
5. **并行边界**：attachment/loss/scrap/inventory/repair/asset 包一个文件未碰
   （另一代理并行改 AttachmentEvidencePort 簇）；工作树用户未提交的小程序改动与
   AGENTS.md 未触碰。

## 2. 改动清单

主代码 5 改（+38/−42 含测试迁移），测试 1 改 1 增：

| 文件 | 变化 |
|---|---|
| `business/BorrowRequestDatePort.java` | 接口内新增嵌套 record `BorrowRequestChoice(String id, long version, String expectedReturnDate, String requestStatus)` 与 `BorrowRequestChoicePage(List<BorrowRequestChoice> items, long total, int page, int pageSize)`（命名镜像契约；对照 `ReturnLossOpeningPort` 嵌套 record 先例）；`eligible` 返回 `BorrowRequestChoicePage`；页 record 紧凑构造器 `List.copyOf` 防御拷贝（消 EI_EXPOSE_REP，JAVA-01/02 先例）；`outstanding()/snapshot()/extend()` 一字未动 |
| `requisition/RequisitionBorrowDatePort.java` | `eligible` 实现同步：SQL、scope 拼装、分页参数一字未动；行投影移入包私有静态 `choice(Map)`（冻结原 `LoanViews.borrowRequestChoice` 语义：id/日期 null 安全 toString、version Number.longValue、status String 直转），`total` Long 自动拆箱（改前 `Map.of` 对 null 同样 NPE，失败语义不变） |
| `loan/LoanService.java` | `eligibleRequests` 删除 JAVA-02 边界转换（含 `@SuppressWarnings("unchecked")`），直接返回 `requests.eligible(...)`；`actors.revalidateLocked` 调用保留；删除随之失效的 `java.util.List` import |
| `loan/LoanController.java` | `/borrow-requests` 端点返回 `BorrowRequestDatePort.BorrowRequestChoicePage`；请求参数与校验一字未动 |
| `loan/LoanViews.java` | 删除 `BorrowRequestChoice` record 与 `borrowRequestChoice` 映射（已迁移至 port 侧）；其余 17 个 record 与映射未动 |
| `loan/LoanWireShapeTest.java` | `borrowRequestChoiceMatchesLegacyCleanAtThePortBoundary` 用例迁出至新测试，断言语义不改；其余 11 条用例未动 |
| `requisition/BorrowDateWireShapeTest.java`（新增） | 2 条 wire 等价用例，见 §3 |

出口 → record 对照：

| 出口 | 改前 | 改后 |
|---|---|---|
| BorrowRequestDatePort.eligible | `Map<String, Object>` | `BorrowRequestChoicePage` |
| LoanService.eligibleRequests | `LoanViews.Page<LoanViews.BorrowRequestChoice>`（边界转换） | `BorrowRequestChoicePage`（直传） |
| LoanController `/borrow-requests` | 同上 | 同上（wire 形状不变） |

## 3. 判据：wire 等价测试

`BorrowDateWireShapeTest` 把改前 `LoanService.clean()` 投影**逐字冻结为 oracle**
（legacyClean，与 JAVA-02 LoanWireShapeTest 同一冻结文本），比较口径沿用 JAVA-01/02：
各自序列化 JSON 文本再解析比树（归一整型宽度，键序不参与；键存在性、显式 null、
值形状全部参与）。

- `borrowRequestChoiceMatchesLegacyCleanAtThePortBoundary`：原 LoanWireShapeTest 用例
  原样迁移——LocalDate/java.sql.Date 双驱动形态行投影等价；页装配与改前
  `Map.of("items",…,"total",1L,"page",1,"pageSize",20)` 经 legacyClean 投影后比树等价。
- `nullDateKeepsExplicitNullAndEmptyPageKeepsAllKeys`（新增判据）：expected_return_date
  为 null 时显式 null 键保留（默认包含语义）；空 items 页四键齐全。

单元 oracle 边界沿用 JAVA-01 教训：只证明「行→视图」序列化等价；真实 HTTP 运行时用例
（HandoverHttpTest 等含 `/loans/borrow-requests` 链路）由主代理门禁实跑。本批无跨边界
快照比较语义、无幂等存储链。

## 4. 门禁证据

产品门禁完整 Java 序列（与 JAVA-05/06/07 合并树单次实跑，2026-11-21，JAVA-03/04 先例）：
**83 步全部 exit=0**，surefire 聚合 **778 项测试 0 失败 0 错误**，`verify_java_reports` 通过
（Checkstyle 0、SpotBugs 0），覆盖率 **LINE 27219/29236（93.10%）、BRANCH 9882/13045（75.75%）**。
主代理合并树复跑单元判据 70/70。证据：[JAVA-05-06-07-java-gate.txt](evidence/JAVA-05-06-07-java-gate.txt)。

实施代理已跑（非产品门禁）：

- 定向单测：`JAVA_HOME=graalvm-jdk-21.0.7 ./mvnw -pl backend -Dtest='LoanWireShapeTest,BorrowDateWireShapeTest,TransferWireShapeTest' -DfailIfNoTests=false test`
  → **Tests run: 30, Failures: 0, Errors: 0**（BorrowDateWireShapeTest 2/2、
  LoanWireShapeTest 11/11、TransferWireShapeTest 17/17）。
- 全仓编译：`./mvnw -pl backend -q test-compile` → exit=0。
- Checkstyle（项目规则集）：`./mvnw -pl backend -Pquality checkstyle:check@check-java-conventions`
  → exit=0（首轮抓出新测试文件缺末尾换行——JAVA-02 同款坑，已修复复跑）。

## 5. 影响分析（GitNexus）

编辑前按 AGENTS 要求执行 `impact -d upstream`（Node 24 前缀 + --repo）：

- `BorrowRequestDatePort`：LOW，3 直接依赖（LoanService import/构造器、
  RequisitionBorrowDatePort implements），0 受影响执行流。
- `RequisitionBorrowDatePort`：LOW，0 上游（接口级消费经 DI 绑定，下界声明已如实记录；
  接口 impact 已覆盖该消费点）。
- `eligibleRequests`：两候选均 LOW（LoanService 方法 1 直接依赖＝LoanController；
  LoanController 方法 0 上游）。
- `LoanViews`：LOW，0 上游；`borrowRequestChoice`：LOW，2 影响
  （LoanService.eligibleRequests + LoanWireShapeTest），均在 loan 包内。
- 无 HIGH/CRITICAL。

`detect-changes`：21 文件 30 符号，受影响执行流 0，聚合 risk low——符号清单大头为
工作树中用户未提交的小程序改动与 AGENTS.md（不属本批，未触碰；CLI 列表显示截断于
15 条，如实登记）。本批后端改动仅 §2 所列 7 文件，git status 核对无排除包文件。

## 6. 偏差与遗留

1. **排除清单（保留 Map/不动，按规格）**：
   - `outstanding()/snapshot()`：内部行 Map 红线，供 loan propose/decide 业务判定，未动。
   - `extend()`：早已类型化，未动。
   - 审计 payload、幂等指纹 digest 输入、loan/requisition 内部行 Map：一律未动。
   - attachment/loss/scrap/inventory/repair/asset 包：零触碰（并行代理边界）。
   - `apps/web`、`apps/miniprogram`、`contracts/`、`packages/api-types`：零改动。
2. **投影归属**：行→record 映射放在唯一实现 `RequisitionBorrowDatePort`（包私有静态
   `choice`），business 接口只含纯 record（对照 ReturnLossOpeningPort 先例）；
   DATE→toString 的 loan clean 语义随映射迁移并在 record javadoc 与测试中冻结。
3. **total 拆箱**：改前 `Map.of` 遇 null total 即 NPE；改后 record 构造自动拆箱同样
   NPE，失败语义不变（COUNT(*) 实际不返回 null）。
4. **通用信封接口进度**：JAVA-02 §7 登记的三个跨域信封中 `BorrowRequestDatePort`
   本批收口；`ProxyReceiptPort`/`TargetChangePort` 已于 JAVA-04 类型化。
5. 工作树中用户未提交的小程序改动（14 文件）与 AGENTS.md 改动不属本批，未触碰。
6. **裁决点：无。** 未遇到形状不可判定、HIGH/CRITICAL impact、需触排除包或疑似改前缺陷。

## 7. 下一步

主代理在固定候选统一重跑产品门禁完整 Java 序列（含 `/loans/borrow-requests` HTTP
运行时用例）并回填 §4；本批契约零改动，契约门禁无需变更。