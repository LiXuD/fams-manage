# JAVA-12b · masterdata 域 HTTP 出口残留面类型化

> 用户指定的后端结构批（HTTP 出口波次 · masterdata 子批），不进入 D6-01～D6-27 台账、不占用其状态。
> 日期：2026-11-22 · 分支：main · 基线：`2fe2880`（JAVA-11c 提交）
> 状态：主代理审计通过，波次二合并门禁通过（含首轮 SpotBugs 红项修复复跑，见 §4），已收口提交。

## 1. 背景与红线

完全复刻 JAVA-01/02/11a 标准：HTTP 响应类型化；内部行 Map、审计 payload、幂等指纹 digest 输入保留
Map；契约 `contracts/fams-core-v1.yaml`、`packages/api-types`、`apps/web`、`apps/miniprogram` 零改动。

masterdata 域与先例的关键差异（改前代码逐字冻结为 oracle 核对）：

1. **残留面只有三处**：全量盘点 10 个 Controller 的全部 62 个出口后，唯一 `Map` 出口是
   `LocationController`/`BrandController`/`AssetCategoryController` 的
   `ResponseEntity<Map<String, Object>> listActive`。其余（五类目录的 list/get/create/update/
   enable/disable、业务参数、编号规则、身份导入、账号组织/员工、通知渠道、数据范围、敏感字段授权、
   操作日志）在 ARCH-21/P25 与 JAVA-09/10c 已完成态具名化，本批零改动，见 §2 盘点表。
2. **不用契约生成 POJO 承接条件键**：`LocationActiveReferencePage`/`BrandActiveReferencePage`/
   `AssetCategoryActiveReferencePage` 及其 item 类由 OpenAPI 生成（`target/generated-sources`），
   **无 `@JsonInclude`**，而全局 mapper 为 `JsonConfiguration` 的 `Include.ALWAYS`
   （`contract/probe/JsonConfiguration.java`）。直接用生成类会把旧响应里条件 put 的键变成显式
   `"x": null`，破坏 wire 等价并撞既有真实回归断言
   （`IdentityMasterDataHttpTest` 第 303、824 行断言 `defaultManufacturerId`/`warehouse`
   `isMissingNode()`）。同域已类型化的 Warehouse/Manufacturer/Organization 先例之所以可用生成类，
   是因为其 schema 四个键全 required、无条件键。故本批按 JAVA-11a 口径新建 `MasterdataViews`
   具名 record，条件键逐个 `@JsonInclude(NON_NULL)`。
3. **条件键与显式 null 甄别**：location 的 `warehouse`/`parentLocation` 仅在 id 非空时 put；对象内
   `version` 由 `MasterdataViews.ActiveReference`（`String id, Long version` 不可变镜像，键形与原
   `CatalogReference` 逐值相同——生成类可变、直接内嵌必触 SpotBugs EI_EXPOSE_REP，首轮门禁即由此
   红）承载，JOIN 版本为 null 时新旧同为显式 `"version": null`；brand 的 `defaultManufacturerId` 需 id 非空**且**
   `defaultManufacturerStatus == "ACTIVE"`；category 的 `suggestedUnit`/`numberingPrefix` 仅非空时
   put。页信封为旧 `Map.of` 的四键（`Map.of` 键序本就跨 JVM 随机，非契约）。
4. **幂等链不受影响**：本批三处出口是只读 GET 选择器，无回执存储；`replay`/`record` 链仍围绕
   `Location`/`Brand`/`AssetCategory` 契约模型与其 `response_body_json` 往返，签名与实现一字未动。
   为覆盖协议第 4 条的等价要求，测试补「改前形状 JSON 反序列化为新 record 并等价再序列化」用例。
5. **JDBC 原值口径**：`id` 由 BIGINT→`Long.toString` 字符串；`version`/`total`（`countActive()` 返
   `long`）保持 int64 数值；`type` 取 `fams_location.location_type` 原始串（不转 `LocationType`
   枚举，避免枚举不可解析风险）。无 DATE/DATETIME 列进入本批投影，故无 Timestamp/LocalDateTime
   双形态分支，也无 Object 透传残留。
6. wire 等价比较口径与 JAVA-01/02/11a 相同：各自序列化 JSON 文本再解析比树（归一整型宽度、键序不
   参与；键存在性、条件缺省、显式 null、值形状全部参与）。

## 2. 改动清单与全量出口盘点

主代码 6 改 1 增，测试 1 增：

| 文件 | 变化 |
|---|---|
| `masterdata/MasterdataViews.java`（新增） | `Page<T>` 泛型信封（items/total/page/pageSize，紧凑构造器 `List.copyOf` 防御拷贝，同 Transfer/Loan/Loss 先例）+ `LocationActiveReference`/`BrandActiveReference`/`AssetCategoryActiveReference` 三个响应 record + 三个具名行映射工厂（逐字复刻被删除的 `toActiveReference` 判定） |
| `masterdata/LocationService.java` | `listActive` 返回 `MasterdataViews.Page<MasterdataViews.LocationActiveReference>`；删除私有 `toActiveReference` 与随之失效的 `LinkedHashMap` 导入。审计 `Map.of` payload、`replay`/`record`、校验与全部 SQL 调用零改动 |
| `masterdata/BrandService.java` | 同上（Brand 形状） |
| `masterdata/AssetCategoryService.java` | 同上（AssetCategory 形状） |
| `masterdata/LocationController.java` | `listActive` 出口具名化，`ResponseEntity.ok` 包装、`@GetMapping("/active-references")`、`requireCatalogSelector()`、`@Min/@Max` 参数校验全部保持；删除失效 `java.util.Map` 导入 |
| `masterdata/BrandController.java` | 同上 |
| `masterdata/AssetCategoryController.java` | 同上 |
| `masterdata/MasterdataWireShapeTest.java`（新增） | 6 条 wire 等价用例，见 §3 |

出口 → 类型对照（仅本批改动项）：

| 出口 | 改前 | 改后 |
|---|---|---|
| `GET /api/v1/locations/active-references` | `Map<String,Object>`（`LocationService.toActiveReference` LinkedHashMap） | `MasterdataViews.Page<MasterdataViews.LocationActiveReference>`（warehouse/parentLocation 条件键） |
| `GET /api/v1/brands/active-references` | 同上 | `MasterdataViews.Page<MasterdataViews.BrandActiveReference>`（defaultManufacturerId 条件键） |
| `GET /api/v1/asset-categories/active-references` | 同上 | `MasterdataViews.Page<MasterdataViews.AssetCategoryActiveReference>`（suggestedUnit/numberingPrefix 条件键） |

完成态盘点（核实即可，本批零改动）：

| Controller | 出口数 | 出口类型 |
|---|---|---|
| AssetCategoryController | 7 | `PageAssetCategory`/`AssetCategory` + 本批选择器 |
| BrandController | 7 | `PageBrand`/`Brand` + 本批选择器 |
| LocationController | 7 | `PageLocation`/`Location` + 本批选择器 |
| ManufacturerController | 7 | `PageManufacturer`/`Manufacturer`/`ManufacturerActiveReferencePage`（已具名） |
| WarehouseController | 7 | `PageWarehouse`/`Warehouse`/`WarehouseActiveReferencePage`（已具名） |
| IdentityMasterDataController | 14 | `PageOrganization`/`Organization`/`OrganizationActiveReferencePage`/`PageEmployee`/`Employee`/`IdentityImportResult`/`String`（模板 CSV） |
| BusinessParameterController | 4 | `PageBusinessParameterVersion`/`BusinessParameterVersion` |
| NumberingRuleController | 6 | `PageNumberingRule`/`NumberingRule` |
| NotificationChannelConfigController | 2 | `NotificationChannelConfig` |
| OperationLogController | 1 | `PageOperationLog` |

跨域消费点：`FactoryReplacementCatalogPort.references` 返回 `Object`（switch 汇聚四类目录），
`RepairFactoryReplacementReferenceService` 亦返回 `Object`，因此类型替换对其透明；
`/api/v1/pc/repairs/.../replacement-references` 的响应形状随之由本批测试覆盖，未做任何域侧边界转换。

## 3. 判据：wire 等价测试

`MasterdataWireShapeTest` 把改前三个 `toActiveReference`（LinkedHashMap 逐字复制）与
`listActive` 的 `Map.of` 四键信封逐字冻结为 oracle，比对：

- **location**：warehouse+parent 双条件键、双缺省（办公室无仓库无父级）、仅 warehouse、仅 parent
  四种组合；并显式断言缺省组合下两个键为 `missing`（防「新旧都写显式 null」的同型错误）；JOIN
  回来的 `warehouse_version` 为 null 时 `warehouse` 对象仍在、内部 `version` 为显式 null；
- **brand**：默认厂商 ACTIVE/DISABLED/id 为 null/status 为 null 四态，断言 DISABLED 时
  `defaultManufacturerId` 键缺失（与真实 HTTP 回归 `isMissingNode` 同判据）；
- **category**：`suggestedUnit`/`numberingPrefix` 有/有、有/无、无/有、无/无四态；
- **页信封**：两条 items、空 items（total 0）、`total` 用 `9007199254740993`（超出 2^53 的 long）
  验证 int64 宽度不被 int 归一吞掉；并复核 record 分量读取值；
- **防御拷贝**：`Page` 紧凑构造器在可变源列表追加后仍为 1 项；
- **幂等/回放**：改前形状 JSON → 反序列化为 `Page<LocationActiveReference>`/`BrandActiveReference`/
  `AssetCategoryActiveReference` → 再序列化与原树相等，且缺省键仍缺失。

**单元 oracle 边界（JAVA-01 教训沿用）**：只证明「行→视图」序列化等价。真实 HTTP 运行时回归
（`IdentityMasterDataHttpTest` 的 brands/asset-categories/locations `active-references` 段、
`InboundHttpTest` 的 organizations 段、repair 侧 `RepairFactoryReplacement*`）由主代理门禁实跑。
本批无跨边界快照直比语义。

## 4. 门禁证据

主代理在波次二合并树固定副本（rsync 排除并行会话 WIP 与后续波次三在途文件）实跑完整 Java 序列：
**JAVA01_VERIFY_PASS**，83 步 exit=0，Checkstyle/SpotBugs 0，覆盖率 LINE 93.14%、BRANCH 75.66%；
日志内 74 个 surefire 阶段汇总 401 项 0 失败 0 错误（聚合口径见证据文件说明）。
**首轮红项即本批**：`LocationActiveReference` 内嵌可变生成类 `CatalogReference` 触发
SpotBugs EI_EXPOSE_REP/REP2 共 4 处（backend 模块 artifactId=contract-generation-probe，门禁日志
中的模块名即 backend 本体）。修复：`MasterdataViews` 新增不可变镜像 record
`ActiveReference(String id, Long version)`（全局 ALWAYS 下键形值形与原完全一致），测试访问器随动，
复跑转绿。合并树单元判据：20 个 WireShape 套件 157/157。
详见[波次证据](evidence/JAVA-12-java-gate.txt)。

实施代理已跑（非产品门禁）：

- 定向单测：`JAVA_HOME=graalvm-jdk-21.0.7 ./mvnw -pl backend
  -Dtest='MasterdataWireShapeTest,TransferWireShapeTest,LoanWireShapeTest' -DfailIfNoTests=false test`
  → **Tests run: 34, Failures: 0, Errors: 0**（Masterdata 6/6、Loan 11/11、Transfer 17/17）。
- 全仓编译：`./mvnw -pl backend -q test-compile` → exit=0。
- Checkstyle（项目规则集）：`./mvnw -pl backend -Pquality checkstyle:check@check-java-conventions`
  → **0 violations**，exit=0。
- 首轮 Masterdata 6 项中 1 项失败，为本批测试自身对 fixture 版本号的断言笔误（把行 fixture 的
  `linkedVersion=3` 写成 21），修正断言后全绿；三处出口与 oracle 的等价断言首轮即通过。

## 5. 影响分析（GitNexus）

编辑前按 AGENTS 要求执行 `impact -d upstream`（Node 24 前缀 + `--repo`，索引 fams-manage）：

- `LocationService` LOW（上游 2：`LocationController`、`FactoryReplacementCatalogPort`）；
  `AssetCategoryService` LOW（上游 2：`AssetCategoryController`、`FactoryReplacementCatalogPort`）；
  `BrandService` LOW（上游 1：`BrandController`）；
  `LocationController`/`BrandController`/`AssetCategoryController` 均 LOW（上游 0）。
- 真实上游闭包不出 masterdata 包 + 同包 `FactoryReplacementCatalogPort`，无 HIGH/CRITICAL，
  无需越批裁决。`FactoryReplacementCatalogPort` 只把结果作为 `Object` 透传，已核实零改造。
- 方法级 `impact listActive`/`impact toActiveReference` 返回 `risk=UNKNOWN、impactedCount=0`
  （同名符号未能唯一定位，非「无使用者」）；已用 grep 核实真实使用者即上述类级闭包
  （三处 Controller 出口 + `FactoryReplacementCatalogPort` switch 分支 +
  测试侧仅 `IdentityMasterDataHttpTest`/`InboundHttpTest` 经 HTTP 断言形状）。

## 6. 偏差与遗留

1. **排除清单（按规格保留/不动）**：
   - `RequisitionMasterDataPort`（JAVA-09 完成态，`MasterReference.fromRow`/`CatalogChoice.fromRow`
     仍收内部行 Map，供 requisition 消费）：签名与实现零改动。
   - `OperationLogMasker` 脱敏内部 `Map`、`MasterDataFingerprint` 指纹输入、各服务 `audit(...)` 的
     `Map.of` payload、幂等 `response_body_json` 往返、`OrganizationRow`/`*Row` 内部行：未动。
   - SQL（含三处 `listActive`/`countActive` 语句与 JOIN）、锁顺序（`*ForUpdate` 系列）、事务边界、
     异常码、方法时序、请求体 record 与校验：零改动。
   - `report/` 包：零触碰（并行 12a 在途，工作树中 `backend/src/test/java/com/fams/report/`、
     `docs/development/JAVA-12a-report-http.md` 属该代理，不在本批 diff 内）；`AGENTS.md`、
     `apps/miniprogram/project*.config.json` 为他会话在途改动，同样未触碰。
   - `contracts/`、`packages/api-types`、`apps/web`、`apps/miniprogram`：零改动。
2. **前端运行时用例一致性**：`apps/web/src/features/asset/api.ts:136` 以
   `{page,pageSize,total,items}` 消费 `.../active-references`；
   `RepairFactoryReplacementPanel.test.ts:28` mock 同四键并含 `warehouse:{id}`。本批 Page 信封与
   item 键集与之逐键一致，未改前端。
3. **缓存头如实登记**：本批三处 `listActive` 改前**没有**任何缓存头（域内 `grep` 全仓
   `CacheControl`/`CACHE_CONTROL` 命中仅在 requisition/asset/auth/loss 的导出与二进制出口，
   masterdata 包零命中，且无 `WebMvcConfigurer`/过滤器补写）。故「缓存头保持」按「本无缓存头、
   改后仍无」执行；`ResponseEntity.ok(...)` 包装、状态码与 `@Validated` 参数校验逐字保留。
4. **`type` 保持原始串**：未改用契约 `LocationType` 枚举，避免历史脏值在类型化后抛
   `InvalidFormatException`（改前 `LocationType.valueOf` 只用于 `toLocation` 的详情投影路径，
   选择器投影本就直接透传字符串）。
5. 旧 `Map.of` 信封键序跨 JVM 随机，新 record 序列化为声明序；语义等价，不涉及任何比较逻辑。
6. **裁决点：无。** 未遇到形状不可判定、真实 HIGH/CRITICAL 上游越批或需改排除接口的情况。
   唯一需要裁决性质的是「能否直接用契约生成类」，已按 §1.2 的 ALWAYS 包含冲突自行判定为不可用，
   改走 `MasterdataViews` 具名 record（与 JAVA-11a 同口径），不需要上游决定。

## 7. 下一步

主代理在合并树上跑完整 `verify_java_quality`（含 `IdentityMasterDataHttpTest` 真实 HTTP 回归）后补
§4 证据。后续波次按 12a report（并行）→ 13 auth∥requisition → 14 inbound → 15 repair 系 →
16 inventory 系推进。
