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
