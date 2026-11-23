# CONV-05b · 收尾 6 个 Service 并启用全局 Mapper 边界规则

> CONV-05 的续批，使 ArchUnit 规则得以**全局**生效而不是只罩 masterdata。
> 基线 `4a63991` · 日期：2026-11-24 · 分支：main

## 1. 改动

同包 Repository 范式（与 CONV-05 一致：不搬目录、不改可见性、纯透传原包私有类型）：

| Repository | 包裹 Mapper | 透传方法 | 使用者 |
|---|---|---|---|
| `asset/AssetRepository` | AssetMapper | 49 | AssetService 49 处、AssetPreferenceService 14 处、AssetLedgerExportService 3 处、AssetLookupService 1 处 |
| `auth/AccountAdminRepository` | AccountAdminMapper | 29 | AccountAdminService 37 处 |
| `runtime/compatibility/RuntimeProbeRepository` | RuntimeProbeMapper | 2 | RuntimeCompatibilityService 4 处 |

同域 `*Port`（asset 10 个）与 `auth/MyBatisAuthAccountStore` 继续直注 Mapper——**Port 在本项目里
已是该域数据访问的拥有者，属规范里 Repository 的等价物**，登记为等价而非违规，本系列不改造。

`AssetMapper` 的 `insertInitial`/`cancelInitial`/`deleteTag` 只被 Initial/Port 面使用，未透传；
`AccountAdminMapper.findActiveAuthorizations` 在 Service 侧无调用者，也未透传。透传集合与 Service
实际调用集合做过集合差校验，无多余、无遗漏。

## 2. 规格被代理纠正的一处（重要）

我给 CONV-05b 的规格要求 Repository 统一挂 `@Profile("fams-product")`（照 masterdata 范式）。
对 `runtime/compatibility/` **这是错的**：`RuntimeProbeMapper`、`RuntimeCompatibilityService` 与
该域的 `@MapperScan` 只在 `runtime-compatibility` profile 下生效，照搬 `fams-product` 会让探针
上下文缺 bean、启动失败。代理改为 `@Profile("runtime-compatibility")`，与既有
`RuntimeCompatibilityConfiguration` 一致。教训：**跨域套同一范式前必须先确认该域的 profile 边界**。

其余登记事项：`AssetService` 有 8 条预存超 120 字符的异常 message 行，属 CONV-09 重排范围；
字段名变长导致 5 行需要纯换行（无 token 语义变化）。

## 3. 全局规则启用

`architecture/FamsArchitectureRules.java` 增
`SERVICES_DO_NOT_REACH_MYBATIS_MAPPERS` —— `noClasses().that().haveSimpleNameEndingWith("Service")
.should().dependOnClassesThat().areAnnotatedWith(Mapper.class)`。

设计过程中先写错一版：用 `onlyDependOnClassesThat(resideInAnyPackage("java..", "org.springframework..", …))`
做白名单。那会误禁 Service 对 `io.micrometer`、生成契约包等大量合法依赖，制造数百条伪违规，
已废弃并改为精确的"不得依赖被 `@Mapper` 注解的类"。

## 4. 门禁证据

`-Dtest=FamsArchitectureTest test`：`0 Checkstyle violations`、`Tests run: 6, Failures: 0`
（新增第 6 条即全局 Mapper 边界规则，当前为真）。
`-Dtest='Asset*Test,Account*Test,Runtime*Test'`：BUILD SUCCESS，其中
`AssetListReferenceQueryTest` 1/1 通过——该测试是本批唯一的测试构造点同步
（`new AssetService(mapper, …)` → `new AssetService(new AssetRepository(mapper), …)`），
mock 与全部 verify/断言字符串未改，透传语义保证 verify 仍打在 mapper mock 上。

完整 650+ 测试门禁与运行时门禁待 CONV-06 日志批合流后统一实跑（当时有 4 个日志代理在途，
本轮只跑了定向套件）。
