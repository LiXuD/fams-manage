# JAVA-18 · 后端 Map→具名类型化系列收官总结

> 用户指定的后端结构批系列（JAVA-01～17d）收官记录。不进入 D6-01～D6-27 台账、不占用其状态。
> 日期：2026-11-23 · 分支：main · 基线：`59055d3`（JAVA-17d 提交）
> 状态：系列全部批次已实施、审计、过门禁并推送。

## 1. 背景与三项裁决

2026-11-20 用户经 /senior-backend-dev 提出：后端 Java 符合开发规范、分层清晰、减少前后端
Map 传参，API 变更须同步接口文档。摸底时全仓约 1560 处 `Map<String,Object>`、162 个文件，
出站响应与跨域 Port 均无编译期契约。经 AskUserQuestion 批准三项治理裁决，全程未变：

1. **深度**：HTTP 响应 + 跨域 Port 面类型化；内部 JdbcTemplate 行 Map、审计 payload、
   幂等指纹输入**保留 Map**（指纹有跨 JVM 兼容史，永不触碰）。
2. **分层**：保持 `com.fams.<域>` 垂直切片（ArchUnit 已守护），不搬目录；响应具名类型落
   域内 `<域>Views`（Port 面用 port-owned 嵌套 record）。
3. **节奏**：仿 ARCH 先例的用户指定结构批 JAVA-01~xx，小域打样后按域滚动，每批独立
   `docs/development/JAVA-*.md` 记录。

硬红线：响应 JSON 逐字节 wire 等价（字符串化 id、显式 null 键、条件键、存储快照原形）；
`contracts/fams-core-v1.yaml` 与 `packages/api-types` 仅在 API 形状真实变化时改——**整个系列
零 API 变更、零契约改动**，故无接口文档同步项。

## 2. 批次台账（27 笔提交）

### Port 批（JAVA-01～10c，收官判据：全仓 `*Port.java` public 字符串键 Map 出口归零）

| 批 | 内容 | 提交 |
|---|---|---|
| 01 | transfer 打样：TransferViews 24 record、sealed Reply+泛型 replay/finish、wire() oracle 冻结法 | `764a235` |
| 02 | loan 域响应与域内 Port | `8624d83` |
| 03/04 | scrap 读侧；跨域 proxy/targetchange 信封（非密封 Context 避 ArchUnit 环） | `44e5c49`/`2676458` |
| 05/06/07 | 并行信封批（borrow-date、attachment-evidence、asset-scrap-eligible） | `c3718fb`/`d8b169a`/`e1eef65` |
| 08/09 | 深耦合 Port 并行轮（loan 深面；三个小跨域 Port） | `a684138`/`b82e46a` |
| 10a/10b/10c | asset 轻量五 Port；asset 重三面 22 出口（worktree 基线滞后重放）；initial 系收口 | `e4a74f3`/`73844e4`/`d703d6e` |

### HTTP 出口波次（11～17，收官判据：全仓控制器 Map 响应出口归零）

| 波 | 内容 | 提交 |
|---|---|---|
| 11 | loss 31 出口+35 端点 ∥ reminder 消息中心 ∥ assetimport 完成态冻结 | `8d685a6`/`c0a3700`/`2fe2880` |
| 12 | report 完成态冻结 ∥ masterdata 三选择器（不可变镜像封死生成类内嵌坑） | `33aea14`/`5a4bc5d` |
| 14 | auth 完成态 ∥ requisition 核心+交接+改派（14b1 建 convertValue 兼容包装、14c 删除归位）∥ notice ∥ proxy 回执 | `90aa4b1`/`3dde750`/`7bd0fea`/`dd43a5c` |
| 15 | inbound 16 出口（永久 convertValue 包装：port fromView 为红线入参） | `699724a` |
| 16 | repair 核心/厂内/协同三路（16a 先行守 ScrapEvidence 耦合，三路共文件合笔） | `09396d0` |
| 17 | inventory 四路并行（组内互斥 Views 文件、跨组边归属调整封闭） | `ace1ed2`/`99bb8bf`/`c271edb`/`59055d3` |

（编号无 13：波次规划期 inbound 暂定 13，派发时并入 15 系命名，台账以提交信息为准。）

## 3. 方法论（可复现要点）

1. **wire 等价判据**：每批把改造前出口组装 verbatim 抽取为 legacy oracle，两侧序列化 JSON
   文本往返后比树（整型宽度归一、键序无关，键存在性/显式 null/值形状严格）；32 个
   `*WireShapeTest` 文件 + TransferCalendar 共 33 类常驻防退化。
2. **包含策略甄别**：生产 `JsonConfiguration` 全局覆盖 inclusion=ALWAYS——默认组件（含 null）
   恒显式输出；仅旧代码有 put 守卫的条件键用 `@JsonInclude(NON_NULL)`。错用即翻键。
3. **JDBC 原值口径**：DATETIME/DATE 驱动形状不定用 Object 直通（除非旧代码显式转换，照抄并
   双驱动判据）；MySQL EXISTS 回 Long 非 Boolean；id `.toString()` 字符串化逐值复刻。
4. **幂等回放**：replay/finish 泛型化（sealed Reply + `Class<T>`），每域每种旧存储 JSON 形状
   逐一「反序列化→再序列化比树」，收据永久保留面跨版本必测。
5. **门禁**：`scripts/verify-d6-03a.py` 完整 Java 序列在 rsync 固定快照副本实跑（排除并行会话
   WIP；波次五起 nohup 脱离进程组防静默早退）；判据只认落盘日志
   `JAVA01_VERIFY_PASS`+逐步 exit=0，套件另在副本内直接复跑消聚合口径歧义。
6. **并行协议**：主代理写书面规格（红线+先批教训注入）→ 文件集互斥派发 → 触 150 回合上限的
   批派「核账/验证续作」代理收尾不重做 → 主代理对盘审计（零改动声明 grep 复核、DDL 列集
   对照、伪边核实）→ 共享文件批次合笔提交。
7. **GitNexus**：编辑前 impact，同名伪边（跨域 detail/list/wire/replay）一律 grep 消费面核实
   登记；共享树收口时 detect-changes 的 CRITICAL 须按本批文件集切分口径说明。

## 4. 收官态与裁决保留面

- **控制器 HTTP 响应出口：全仓 grep `Map<String,Object>` 返回值 = 0**。
- **跨域 Port public 字符串键 Map 出口：0**（JAVA-10c 起保持）。
- 具名类型面：`*Views`/port-owned 嵌套 record 合计约 373 个。
- 裁决保留 Map 面（非残留，勿再改造）：请求体 `@RequestBody` 与 Input 类；私有行 Map
  （queryForMap/queryForList 中间态）；审计 detail_json 与幂等指纹 digest 输入；
  `BorrowRequestDatePort.outstanding/snapshot` 内部行；`describeReturnTarget`/`targetDisplay`
  信封面；`WechatSubscriptionPort.templateValues` 动态模板键；`fromRow/fromView` 投影工厂入参
  （inbound 域 create/get 的 convertValue 镜像为此为**永久终态**）；`MasterdataViews.ActiveReference`
  类不可变镜像；契约错误信封动态键。
- 契约/OpenAPI/api-types/前端：系列全程零改动。

## 5. 系列坑账（沉淀给后续会话）

1. 可变契约生成类内嵌 record 必触 SpotBugs EI_EXPOSE_REP——用不可变镜像（12b 首红）。
2. COUNT `queryForObject` 直进 long 拆包触 NP——`Objects.requireNonNull`（16b 首红；17b 续作
   沿 HEAD 无 message 口径的例外已登记）。
3. 生成类缺 `@JsonInclude`，在 ALWAYS 下把条件缺失键翻成显式 null 破坏等价（12b 刻意不用）。
4. proxy 快照比较必须 JSON 文本往返，`valueToTree` 区分 IntNode/LongNode 会假红（JAVA-01 教训）。
5. 同一行数据可能存在**两种既有 wire 口径**（17c：finding 扁平 vs review 递归、itemId 数值 vs
   字符串）——分工厂冻结，未核对前禁止合并。
6. 门禁后台任务可能被静默杀死——nohup 脱离 + 一次性存活确认 + 判据行核验；通知文案一律不可
   作为绿/红依据（本系列多次出现与磁盘矛盾的注入型假通知，含虚构红项、虚构提前交付）。
7. JaCoCo BRANCH 75% 只在完整序列下可达（jacoco.exec 跨阶段追加），子集跑必假红。

## 6. 质量与证据索引

- 各批记录：`docs/development/JAVA-*.md`（30 份，含状态行、判据、影响分析、偏差与裁决点）。
- 波次门禁证据：`docs/development/evidence/JAVA-*-java-gate.txt` 全程 14 份
  （01、02、03-04、05-06-07、08-09、10a、10b、10c、11、12、14、15、16、17）。
- 各波合并判据轨迹（主代理亲测）：136→157→195→202→218→260→340 全绿；
  末轮门禁 LINE 93.45% / BRANCH 75.29%、Checkstyle/SpotBugs 0。
- 交接入口 `DESIGN-HANDOFF.md` 已按波逐段登记（2026-11-20 至 2026-11-23）。

## 7. 后续建议（未启动，需另行批准）

1. AOCI 认知维护：本系列子代理会话无 aoci 工具面，受管对象语义未随批更新，建议单批补做。
2. `apps/*` 客户端类型消费面：api-types 虽零改动，可加一条「响应键集对契约的回归断言」把
   wire 判据前移到契约层。
3. 全局 Map 出现数（含合法保留面）作为只读监控指标纳入质量报表，防回潮。
