# CONV-09 · A8/A6 工作面实测与推进政策（门禁口径定稿）

> 基线 `2c266d7` · 日期：2026-11-25 · 分支：main
> 数据来源：**checkstyle 14.1.0 本体**（`/tmp/cs-audit2.xml` 变体配置，
> `-Dcheckstyle.failOnViolation=false` 取清单），不是 grep 估算。

## 1. 为什么必须用工具重新量

本系列已三次因自写正则报出错数（190 → 0 → 70 → 28，见 CONV-03R §6/§7）。A8/A6 直接改用
checkstyle 本体计量，得到的数字与我此前 grep 估算差距很大：

| 项 | 我此前 grep 估算 | checkstyle 实测（main） |
|---|---|---|
| 超 120 字符行 | 565 | **581** |
| 方法体超 80 行 | 6 | **53** |
| 参数超 5 个 | 43 | **355**（含构造方法）/ **313**（仅 `METHOD_DEF`） |
| 缺类注释 | 289 | **340**（`scope=public` 验收口径）/ 748（含包私有与嵌套 record） |
| 缺 public 方法注释 | 未测 | **837** |
| 魔法值 | 661（仅状态串） | **1507**（`MagicNumber` 全量） |

grep 估算低估了近一个数量级的方法长度与参数超标面，说明"抽样 + 正则"在这个规模的仓库上
不可作为工作面依据。

## 2. 一个真实的规则冲突及其裁决

`ParameterNumber(max=5)` 与 springboot 技能强制的"始终使用构造器注入"直接冲突：Spring 构造器
注入会把依赖全部写进构造参数，`IdentityMasterDataService` 改造前已是 7 参。

**裁决：`ParameterNumber` 的 `tokens` 限定为 `METHOD_DEF`，排除 `CTOR_DEF`。**
理由是构造参数个数由依赖注入决定，不是方法设计缺陷；而普通方法的参数个数才是可读性与可测性
问题。按此口径实测仍有 **313 个方法**超标（构造只贡献 42 个），说明这确实是一大片真实工作面，
不能用"构造器注入被冤枉"来豁免掉。

## 3. 推进政策：按"是否改变行为"分两类，而不是按 A 级/B 级

V87 预存红未修 → 167 个 docker 并发/幂等/原子性用例跑不完。因此：

**可立即推进（纯附加，零行为风险，不需要运行时证据）**
- `LineLength(120)` 重排 581 行：只改换行与缩进，不改 token 顺序。
- `MissingJavadocType` 748 处 / `MissingJavadocMethod` 837 处：只加注释。
  语言保持英文（系列裁决 2）。
- A3 日志剩余两组（L-D、L-E）：只加日志语句。

**必须等运行时门禁恢复（改变行为或签名）**
- `MethodLength(80)` 53 处：抽方法会改变调用时序与局部变量作用域，锁持有窗口可能随之变化。
- `ParameterNumber(METHOD_DEF)` 313 处：改签名会连带改所有调用点与 `new XxxService(...)` 测试构造点。
- A5 状态枚举 661/1507 处：触及线格式与 DB 列值，撞 JAVA 系列"响应 JSON 逐字节等价"硬红线。
- R2 的 30 个类级 `MANDATORY` Port 提 public：会**首次激活**"调用方必须已开事务"的断言，
  可能立刻暴露 `IllegalTransactionStateException`，必须有运行时证据才敢开。

这条政策的好处是：不依赖运行时证据的项可以持续清零，而所有需要并发证据的项集中在 V87
修复后一次验收，避免"改了但没人能证明改对了"的状态堆积。

## 4. 门禁启用的先后依赖（沿用 CONV-04 判据）

规则只能在**违规归零后**启用，否则构建直接红，门禁会被迫退化成带豁免清单的半开状态。顺序：

1. 重排 581 行 → 启用 `LineLength(120)`（含 `ignorePattern` 放行 package/import 与 URL）。
2. 补 748 + 837 处注释 → 启用 `MissingJavadocType(scope=public)` 与
   `MissingJavadocMethod(scope=public)`。`scope` 取 `public` 而非 `package`，因为项目有 201 个
   包私有顶层类型与大量 `*Views`/`*Input` 内嵌 record，对它们逐条要求 Javadoc 是噪声而非收益。
3. `MethodLength` 与 `ParameterNumber` 待运行时门禁恢复后再排。

## 5. 本批未做的事

- 未改任何 Java 源码（本文件是工作面测量与政策定稿）。
- 未改 `config/checkstyle/checkstyle.xml`（启用规则要等违规归零，见 §4）。
- `/tmp/cs-audit2.xml` 是审计用临时配置，不入库；入库时以 §4 的最终规则形态写进仓库配置。
- （已收敛）`MissingJavadocType` 在 `scope=public` 单独重测得 **340 处 / 123 个文件**
  （按域：repair 48、scrap 42、transfer 32、loss 28、inventory 26、loan 24、requisition 21、
  auth 20…），§1 表里的 748 是 public 与 package 两个 scope 相加的**重叠计数**，不作验收基数。
  §4 的门禁取 `scope=public`，验收基数即 **340**；`MissingJavadocMethod(scope=public)` 为 **837**。

## 6. 关键补充：`LineLength` 不能按原样启用（实测后定的门禁口径）

仓库 checkstyle 执行绑定 `includeTestSourceDirectory=true`，实测确认**测试源确实被扫描**
（执行绑定的 `checkstyle:check@check-java-conventions` 扫到 main 479 + test 176 个文件；
`checkstyle:check` 默认 cli 则只扫 main，且 `-Dcheckstyle.config.location` 会被执行里的
`<configLocation>` 覆盖——审计时必须用执行绑定的 goal 才是真实口径）。

因此启用 `LineLength(120)` 会连测试源一起判。测试源实测 **1727 行超 120 字符 / 95 个文件**，
按风险分类：

| 类别 | 行数 | 能否重排 |
|---|---|---|
| 文本块 `"""` 内 | 741 | **禁改**：内容即字符串值 |
| 含字符串字面量 | 935 | **禁改**：WireShape 与运行时用例的期望 JSON 逐字节冻结，重排即改断言 |
| 纯代码行 | 51 | 可安全重排 |

`auth` 一个域就占 1528 行，正是冻结断言最密集处。**结论：测试源不做重排。**

**门禁口径裁决**：`LineLength` 用 `SuppressionSingleFilter` 按文件正则只对
`.*/src/test/.*` 抑制该一条规则，其余 9 条规则（`UnusedImports`/`NeedBraces`/`EqualsHashCode` 等）
对测试源继续全量生效。**不得**为此把 `includeTestSourceDirectory` 改成 false——那会连带关掉
测试源的其它规则，是拿门禁强度换便利。

main 侧 581 行的处置：457 行文本块外按 §3 重排；**124 行在文本块内**（SQL 与 CSV 字面量，
JAVA 系列已冻结存储形状）用 `// CHECKSTYLE:OFF` / `// CHECKSTYLE:ON` 逐块包裹并配
`SuppressWithPlainTextCommentFilter`（该 filter 正是为 `LineLength` 这类 Checker 级检查设计），
包裹处须注明"冻结 SQL/CSV 字面量，重排即改值"。两类都不许改字符串内容。

验收基数据此改为：main 文本块外超长行归零 + 文本块内 124 行全部处于显式 OFF 区间 +
测试源 LineLength 抑制生效且其余规则仍全量扫描测试源。

## 7. 两条更正与一条红线撤销（实测后）

### 7.1 文本块归属判定错了，124 这个数字作废

§1/§6 用的判定是"从目标行向上找未配平的 `"""`"。该启发式**无法区分遇到的是开引号还是闭引号**：
向上第一个含奇数个 `"""` 的行可能正是文本块的**闭合行**，于是把块外的普通声明误判成块内。
实证：`report/AssetMovementReportService.java:181` 是 `record Row(String factId, ...)` 记录声明，
被误判为文本块内。

改为**从文件首行向下逐次翻转状态机**（每遇到一个 `"""` 翻转一次，同行两次即回到原状态）后重测：

| 口径 | 错误启发式 | 状态机（正确） |
|---|---|---|
| main 侧超 120 行·文本块内 | 124 / 121 | **38** |
| main 侧超 120 行·文本块外 | 457 | **369** |

块内 38 行分布：`asset` 11、`reminder` 8、`report` 8、`repair` 5、`scrap` 4、`loss` 2。
所以需要 `CHECKSTYLE:OFF` 包裹的面比原估小一个数量级，可重排/可拆的面比原估大。

### 7.2 撤销"禁止拆分字符串常量"红线

第一档重排代理据该红线留下 50 行（requisition+loan）与 37 行（repair+attachment+targetchange）
判为"无合法布局"。主代理裁定**红线定错**：`"SELECT a, b"` 拆成 `"SELECT a, " + "b"` 经编译期
常量折叠后**运行值逐字节不变**，不改 SQL、不改线格式与存储形状。

正确性判据沿用代理已在用的那条自查：**文件内全部字面量按出现顺序拼接的内容序列，改前改后
逐字节相等**——拆一个成两个后顺序拼接结果不变，故该判据对拆分依然有效，且能抓住任何字符增删。
切分点必须落在 SQL token 边界（空格处），不得在标识符、引号内部或转义序列中间切。
文本块内的行仍然**绝对禁改**，走 OFF 包裹。

### 7.3 重排代理的两处披露（已核，接受）

- 2 处为注释折行（`business/InitialScrapPort.java:6` 单行 Javadoc 拆两行、
  `reminder/ReassignmentReminderService.java:25` 长 `//` 注释拆两行），仅新增注释标记符、文字逐字未动。
- 6 处 SQL 拼接续行由既有"+4 深缩进"对齐到实参缩进（纯去空白），其中 2 行恰为 120 字符。
- 主代理自办的 `report/` 域 OFF 包裹曾因 `'\n'.join()` 丢掉文件末尾换行触发
  `NewlineAtEndOfFile`，已补回；后续任何脚本化改文件都必须保留末尾换行。

## 8. A6 剩余面（标签完整性）与两个工具的口径对账

`MissingJavadocType` / `MissingJavadocMethod` 只判**存在性**，判不出"有 Javadoc 但缺 `@param`/
`@return`"。用 checkstyle 的 `JavadocMethod(accessModifiers=public, allowMissing*=false)` 实测
全仓 **603 条**；用本仓 `scripts/check-javadoc-quality.py`（按**方法**计）实测
**177 个方法缺 `@param` + 54 个方法缺 `@return`**。两个数字互为佐证：177 × 平均约 3 个参数
+ 54 ≈ 585 ≈ 603，差异来自每方法缺失标签数不同。按域（检查器口径）：`asset` 76、
`inventory` 75、`loss` 34、`scrap` 14、`auth` 7，其余分散。

检查器自身也修过一处保守低报：原正则要求参数表在同一行，而本仓大量签名跨多行
（`asset/AssetFactoryRepairPort.recordPartsObservation` 十个参数逐行排列），这些方法被整体跳过，
使 asset 的 T1/T2 只报 13/9 而 checkstyle 报 143。改为**括号配平向后收集参数表 + 按尖括号与
圆括号深度数顶层逗号**后才与 checkstyle 量级吻合。

**裁决**：`JavadocMethod` 本批**不启用**。理由不是工作量，而是口径——它会把 record 紧凑构造器、
以及本仓有意的电报体注释一并判红，而"存在性 + 无空话（T3）+ 无占位（T4）+ 未闭合（T6）"
已覆盖真正有价值的部分。标签完整性列为 A6 的**剩余面**，单独排期。

## 9. 启用硬门禁的确切步骤（收口清单）

前置条件（全部满足才可执行第 3 步）：

1. 全仓 `LineLength` 违规 = 0。当前进度：`requisition`/`loan`/`masterdata`/`transfer`/`report`/
   `reminder` 已归零；`inventory`、`loss`/`auth`/`assetimport`/`inbound`、`asset`/`scrap` 三档在途。
2. 全仓文本块内超长行已全部 `CHECKSTYLE.OFF/ON` 包裹，且验收通过：
   全仓标记行号 ∉ 文本块行号集合、每块恰好一对 OFF/ON。已完成 `report`(7 对)、`reminder`(8 对)、
   `repair`(3 对)；待补 `asset`(11 行)、`scrap`(4 行)、`loss`(2 行)。
   包裹脚本 `/tmp/wrap_tb.py`（**必须**用向下翻转状态机；改文件后保留末尾换行）。
3. `MissingJavadocType(scope=public)` 与 `MissingJavadocMethod(scope=public)` 均 = 0。

执行：把 `/tmp/cs-final.xml` 的内容写入 `config/checkstyle/checkstyle.xml`（该草案已含原有 10 条规则
+ `LineLength(120)` + `SuppressWithPlainTextCommentFilter` + 只对 `.*/src/test/.*` 抑制 `LineLength`
的 `SuppressionSingleFilter` + 两条 `MissingJavadoc*`，并已验证可解析、抑制生效）。
注意草案里 `MissingJavadocType` 写了 `scope=public` 与 `scope=package` 两条，**入库时只保留
`scope=public` 那一条**（§4 裁决：对 201 个包私有顶层类型与大量 `*Views`/`*Input` 内嵌 record
逐条要求注释是噪声而非收益）。

验收：`./mvnw -pl backend test`（默认生命周期已含 checkstyle）必须 BUILD SUCCESS，
并在 `git archive HEAD` 纯净副本上复跑一次全量测试与 ArchUnit（当前 7 条规则）。
