# CONV-05 · B2 数据访问边界（改为边界强制，不搬目录）

> 系列第五批。基线 `822cae7`（CONV-04）。日期：2026-11-24 · 分支：main
> 执行：主代理定形态，1 个代理做 masterdata（10 Service），1 个后台代理做 asset/auth/runtime（6 Service）
> 状态：masterdata 已实施并过全量门禁；收尾组进行中。

## 1. 为什么 B2 换了形态

选型时 B2 被描述为"Mapper 归位子包 + Service 直注 Mapper 经 Repository 封装"。实施前核查暴露了一个
选型时不可见的代价：

- 14 个 Mapper 全部是**包私有 interface**；它们返回的行类型（`WarehouseRow`、`NewWarehouseRow`、
  `BrandRow`、`LocationRow`、`AssetCategoryRow`、`EmployeeRow`、`OrganizationRow`、
  `IdempotencyRow`、`NumberingRuleRow`、`ManufacturerRow`、`BusinessParameterRow`、
  `NotificationChannelRow`、`NewNumberAllocationRow` 等 12+ 个）也是包私有；
  `asset/AssetMapper` 还引用包私有的 `AssetPhysicalSql`（`final class`）与 `AssetSearchSql`。
- 这些行类型被**同包 Service 直接消费**（如 `WarehouseRow` 同时出现在
  `WarehouseMapper.java`、`WarehouseService.java`、`LocationService.java`、`WarehouseRow.java`）。

因此把 Mapper 搬进 `<域>.mapper` 子包，必然要求上述 Mapper、12+ 行类型、SQL 助手全部改成 `public`
（Java 跨包只能走 public），**正好抵消项目有意为之的包私有封装**（全仓 201/460 个顶层类型是包私有，
73/91 个 Controller 包私有）。经用户裁决，B2 改为：

> **不搬目录、不改可见性；在同一包内建立 Repository 边界，使 `*Service` 不再出现 `*Mapper` 标识符，
> 并用 ArchUnit 把这条边界变成硬规则。**

这样拿到的是规范条目的**真实意图**（Service 不知道持久化技术存在），付出的目录形状代价为零，
封装面退化也为零。

## 2. masterdata 实施

新建 10 个包私有 `@Repository`（对齐 `assetimport/AssetImportCatalogRepository` 与本轮刚建立的
masterdata 范式，均带 `@Profile("fams-product")`）：

| Repository | 包裹 Mapper | 透传方法数 |
|---|---|---|
| WarehouseRepository | WarehouseMapper | 14 |
| LocationRepository | LocationMapper | 15 |
| BrandRepository | BrandMapper | 9 |
| ManufacturerRepository | ManufacturerMapper | 9 |
| BusinessParameterRepository | BusinessParameterMapper | 9 |
| NumberingRuleRepository | NumberingRuleMapper | 12 |
| AssetCategoryRepository | AssetCategoryMapper | 10 |
| IdentityMasterDataRepository | IdentityMasterDataMapper | 22 |
| NotificationChannelRepository | NotificationChannelMapper | 7 |
| NumberAllocationRepository | NumberAllocationMapper | 6 |

10 个 Service 改为依赖 Repository（合计 156 处调用点替换），业务逻辑、SQL、幂等与 CONV-03 的
方法级 `@Transactional` 一字未动。`IdentityMasterDataMapper` 被 8 个 Service 共用，故只建**一个**
`IdentityMasterDataRepository` 共用，不做一对一复制。

**测试构造点零改动**：全仓 grep 证明这 10 个 Service 没有手工 `new` 构造点，masterdata 运行时用例
全部经 Spring `@Autowired` 注入，公开方法签名未变。

## 3. 两处审查口径纠正

- `OperationLogService` **不是违规**：它的 `*Mapper` 字样只来自 Jackson
  `tools.jackson.databind.ObjectMapper`；数据访问走 `JdbcTemplate` + `com.fams.auth.OperationLogAccess`。
  masterdata 真实违规面是 10 个 Service，不是 11 个。这修正了首版报告"28 处 Service 直注 Mapper"的
  口径——该数字把 `ObjectMapper` 计入。
- 构造参数超 5 个在 masterdata 有 4 个文件（`LocationService`=6、`BrandService`=6、
  `AssetCategoryService`=6、`IdentityMasterDataService`=7，其中 7 是改造前既有）。按约束**不在本批
  重构签名**，登记给 A8 格式/签名批。

## 4. 新默认门禁第一次就拦住了代理产物

CONV-04 把 checkstyle 移入默认生命周期后，本轮首跑即报 3 处 `UnusedImports`
（`NumberAllocationRepository` 的 `java.util.List`、`NotificationChannelRepository` 的
`java.time.Instant` 与 `java.util.List`），全部位于新建 Repository。修掉后再报 1 处，最终清零。

这条同时记一个方法论错误：主代理第一次核查用 `grep -c "List"` 判断 import 是否被使用，
**import 行自身被计入命中数**，导致漏判一处。判"未使用 import"必须用编译期/静态检查器，
不能用行数计数——这正是把 checkstyle 设为默认门禁的价值。

## 5. 门禁证据

`./mvnw -pl backend test`（含 CONV-04 起默认的 checkstyle）：
`You have 0 Checkstyle violations.`、`Tests run: 650, Failures: 0, Errors: 0, Skipped: 167`、
BUILD SUCCESS。

## 6. 运行时门禁的重大发现：main 上 V87 预先为红（与本系列无关）

含 docker 的完整运行时门禁（rsync 副本 `/tmp/conv-gate`，HEAD 含 CONV-01~04）跑到第 51 步
`WeChat subscription one-shot quotas and in-app fallback V87`（`WechatSubscriptionHttpRuntimeCase`）
失败：`/mini/auth/login` 返回 500 `INTERNAL_ERROR`，期望 200。

**归因为预先存在，非本系列引入**：把同一用例在 `6431f06` 的纯净 `git archive` 副本
（不含任何 CONV 改动、无未跟踪文件）单独重跑，得**完全相同的失败**
（`/tmp/v87-fams-6431.txt`：`miniLogin:386 -> ok:299 ... expected: <200> but was: <500>`）。

线索指向 `backend/src/test/java/com/fams/local/LocalMiniServerApplication.java`——它是
`@SpringBootApplication(scanBasePackages = "com.fams")` 的本地替身启动器，内部 `LocalDoubles`
提供 `MiniWechatIdentityPort` / `MiniSmsPort` 的本地替代 Bean。该文件**现已被提交进 main**，而它在
测试源集里、会被扫描 `com.fams` 的 Spring 上下文当作组件拾取，从而产生重复
`MiniWechatIdentityPort` Bean。这与系列记忆中"该文件尚未跟踪时曾造成同一 500"的症状完全一致，
只是现在固化进了主线。

处置：属 mini 联调线的遗留，超出 CONV 规范整改范围，**本系列不擅自修改**，登记并提请
`LocalMiniServerApplication` 的责任会话处理（候选修法：给 `LocalDoubles` 加
`@Profile("local-mini")` 或从 `com.fams` 扫描面移出）。受影响的是本系列第 5、6 批的运行时门禁
置信度，故 CONV-05/CONV-06 的运行时验证需等该红修复后重跑。
