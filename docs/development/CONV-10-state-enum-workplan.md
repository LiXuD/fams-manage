# CONV-10 · A5 状态枚举工作单（未开工，先纠正工作面口径）

> 日期：2026-11-25 · 基线 `a017913` · 状态：**规划**，未改任何源码
> 押后原因：A5 触及 DB 列值与线格式，属"改变行为"类，按 CONV-09 §3 政策须等 V87 修复、
> 运行时门禁恢复后一次验收。本文件先把工作面量准，避免开工时重复犯错。

## 1. 原提法"661 处状态字面量"是错的，必须废弃

结构化聚合（按 SQL 比较式与 `IN` 列表的**列名**归并，加 Java `.equals` 面）实测：

- **候选状态列 = 58 个**（取值 ≥2 种），跨 **17 个域**。
- Java `.equals("XXX")` 面共 **669 次、162 个不同字面量**，但这个桶是**混杂的**：
  里面同时有契约错误码（`ASSET_IMPORT_ROLLBACK_LOCKED`、`ASSET_NOT_FOUND`、`AUTH_FORBIDDEN`、
  `TARGET_CHANGE_FORBIDDEN`、`REPORT_EXPORT_LIMIT`）、角色码（`WAREHOUSE_OPERATOR`）、
  业务类型码、HTTP 方法名（`GET`）、JSON 键名（`HEAD`、`OPTIONS`、`CONDITION`）与真正的状态值。
  把它们一起"类型化为状态枚举"会造出一个毫无意义的巨型枚举。

所以 A5 的正确单位不是"字面量出现次数"，而是**状态机**。

## 2. 一个更关键的口径错误：不能按列名聚合

按列名聚合会把**不同表的同名列**并进同一个状态机。实测 `status` 一列只聚出 7 个取值
（`ACTIVE`/`AVAILABLE`/`CONSUMED`/`DISABLED`/`ENABLED`/`LOCKED`/`SUPERSEDED`），
但这 7 个值分属**多张表各自的**状态机——`fams_system_account.status`（ENABLED/DISABLED/LOCKED）、
`fams_business_parameter_version.status`、`fams_asset_import_rollback.status`
（PENDING/APPROVED/REJECTED/QUEUED/COMPLETED/FAILED，见契约 `AssetImportRollback`）等互不相干。
按列名建枚举会**跨表串味**，比不建更糟。

**正确单位 = (表, 列) 二元组**。开工前必须先做一步结构化提取：对每条 SQL 语句解析其
`FROM`/`UPDATE`/`INSERT INTO`/`JOIN` 的表名，再把该语句内的列比较归到 (表, 列)。
这一步不能用 grep，必须走语句级解析（见 CONV-03R §7 的方法论结论）。

## 3. 已量出的高价值候选（按出现次数，供开工时排序）

| 列名 | 取值数 | 出现次数 | 跨域 |
|---|---|---|---|
| `business_type` | 21 | 216 | asset, assetimport, attachment, inbound, inventory… |
| `status`（需按表拆分） | 7 | 178 | asset, assetimport, auth, inbound, loan, loss, masterdata… |
| `lifecycle_status` | 8 | 68 | asset, inbound, loan, reminder, report, requisition |
| `todo_status` | 2 | 51 | asset, inbound, inventory, loan, loss, reminder, report |
| `card_stage` | 4 | 38 | asset, inbound, loan, masterdata, report, requisition |
| `request_status` | 8 | 36 | asset, reminder, repair, report, requisition, scrap |
| `stage` | 9 | 31 | reminder, report |
| `action` | 22 | 30 | loan, reminder, repair, report, requisition, scrap |
| `todo_type` | 14 | 29 | inventory, loan, loss, reminder, repair, report, requisition |
| `reminder_type` | 6 | 29 | reminder, report |
| `exception_status` | 3 | 27 | asset, loan, loss, reminder, report, requisition |
| `role_code` | 4 | 23 | asset, assetimport, inbound, loan, reminder, repair |
| `relation_status` | 4 | 22 | asset, loan, reminder, report, transfer |
| `receipt_status` | 5 | 22 | attachment, inbound, masterdata, reminder |
| `report_status` | 5 | 21 | asset, loss, reminder, repair, report |
| `task_status` | 7 | 20 | inventory, reminder, report |
| `job_status` | 7 | 19 | attachment, job, reminder |
| `disposition_status` | 4 | 19 | reminder, report, scrap |
| `round_status` | 5 | 18 | reminder, repair, scrap |
| `custody_type` | 3 | 17 | asset, inbound, repair, report |

`business_type` 的 21 个取值已与契约 `WechatSubscriptionGrantRequest.businessType`
（`REQUISITION,LOAN,REPAIR,INVENTORY_TASK,REQUISITION_ALLOCATION,TRANSFER_ITEM,INBOUND`）
部分重叠但不相等，开工时必须以 `contracts/fams-core-v1.yaml` 的 80 个 `enum:` 块为准做交集核对，
不能只从 Java 侧反推。

注意 `NOT` 也被聚出 12 个取值——那是 SQL `NOT IN (...)` 被正则误当作列名，属工具伪命中，
开工时的解析器必须排除。

## 4. 三条不得漂移的实施约束（沿用系列裁决）

1. **枚举化止步于线格式边界**：`*Views`/`*Input` record 与 JDBC 读写边界一律保持字符串，
   枚举只替换内部比较与集合常量。违反即撞 JAVA 系列"响应 JSON 逐字节 wire 等价"硬红线。
2. **DB 列值与 JSON 值必须逐字节不变**：枚举 `name()` 必须与现有字面量完全一致，
   且不得依赖 `ordinal()`；序列化路径若经 record 组件则仍写字符串。
3. 命名遵循 `springboot` 技能：枚举类名带 `Enum` 后缀、成员全大写下划线。
   注意全仓现有 20 个 enum **都不带** `Enum` 后缀（`AccountStatus`、`Role`、`DataScope`、
   `SecurityEventType`、`AttachmentFormat` 等），新增枚举若带后缀会与存量风格分裂——
   这一条需用户裁决：**新增一律带 `Enum` 后缀**（合规但与存量不一致），
   还是**沿用存量无后缀风格**（一致但不合规），或**一并重命名存量 20 个**（合规且一致，
   但触及 `Role`/`DataScope` 等被广泛引用的类型）。

## 5. 建议的开工顺序

先做单一域内闭合、且契约已有 enum 定义的列（`lifecycle_status`、`card_stage`、
`request_status`、`receipt_status`），每域一批、跑该域运行时用例；
`business_type`（21 值、216 次、跨 8 域）与 `action`（22 值）放最后，因为它们跨域最广、
一旦值集判定错误影响面最大。
