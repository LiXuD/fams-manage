# D6-05 task card — 基础资料

## 任务身份与目标

- **ID：** D6-05 基础资料。
- **状态：** `IN_PROGRESS`。
- **分支 / 基线：** `codex/d6-05-master-data` / `dbb1057`；任务启动提交 `f55808f`。
- **目标：** 沿用 D6-02C 已确认的契约、数据模型、权限和错误语义，交付组织人员、目录仓位、编号规则与业务参数三个小纵切片。
- **架构边界：** Java 模块化单体、MyBatis、Flyway、MySQL 8.4；各主数据使用明确表、Mapper、Service 和 API，不引入通用元数据引擎。

## 实施顺序与关闭条件

| 段 | 交付 | 必须验证 | 状态 |
|---|---|---|---|
| D6-05A | 组织、员工、标准模板导入；员工停用同步停用账号并使旧会话失效；组织停用校验启用子组织、员工和授权范围依赖 | 真实 MySQL；SYSTEM_ADMIN 正常路径；非授权角色 403；迁移、事务回滚、版本冲突和统一门禁 | `IN_PROGRESS` |
| D6-05B | 分类、品牌、厂商、仓库、位置；树循环、跨仓位置、启用父级和活动引用校验 | 真实 MySQL；允许/拒绝路径；引用停用规则；统一门禁 | `NOT_STARTED` |
| D6-05C | 编号规则、不可回收分配边界、业务参数不可变版本与生效日期冲突 | 真实 MySQL；允许/拒绝路径；并发/版本冲突；统一门禁 | `NOT_STARTED` |

父任务只有三段均完成、固定提交复跑统一门禁且关键复核满足后，才可标记 `DEV_VERIFIED`。

## 当前约束

1. API 主键继续使用十进制字符串，数据库使用 signed `BIGINT AUTO_INCREMENT`。
2. 业务写入校验会话、CSRF、`SYSTEM_ADMIN`、幂等键、版本和引用当前状态。
3. 员工停用不等待责任清理；员工、关联账号和会话失效在同一事务边界内完成，历史身份不删除。
4. 标准模板导入显式区分 `CREATE`、`UPDATE`、`DISABLE`；模板缺少某行不触发删除或停用。
5. D6-05 不实现 D6-08 的通用后台任务、期初资产导入、20,000 行大文件预检或发布回滚。

## 证据边界

本任务的本地 `DEV_VERIFIED` 只证明任务自有 MySQL 数据库、真实 HTTP/权限拒绝和本地统一门禁。远端 required-ci、第七阶段 QA、共享/生产数据库升级与生产就绪仍分别记录。

## 2026-09-23 交接检查点

### 已完成

1. 已在任务启动提交 `f55808f` 固定三段实施顺序、关闭条件和“不引入通用元数据引擎”的边界。
2. D6-05A 已形成未提交实现草稿：显式组织/员工 Controller、Service、MyBatis Mapper，管理员权限上下文，员工手机号加密/检索摘要，幂等与安全审计，以及 CSV 标准模板导入解析和验证。
3. 员工停用草稿把员工、关联账号停用和 `auth_epoch` 递增放在同一事务中；组织停用草稿校验启用子组织、员工和授权范围依赖。
4. `scripts/verify-d6-05a.py` 使用任务自有 MySQL 8.4 和真实 HTTP 执行。初始 RED 为 5/5 失败；迭代后的最近一次结果为 5 项中 4 项通过、1 项失败，测试进程退出 1，任务容器已清理。

### 未完成与当前阻塞

1. 组织存在活动依赖时应返回 `409 MASTER_DATA_DEPENDENCY_CONFLICT`，最近一次实际返回 500；原因尚未完成定位，后续不得把异常处理优先级假设写成结论。
2. 上述失败使同一用例后续的陈旧版本冲突断言尚未执行，D6-05A 仍为 `IN_PROGRESS`。
3. CSV 模板导入端点尚未补入 `contracts/fams-core-v1.yaml` 及对应示例、追溯与契约验证；在契约闭合前不得提交为已完成能力。
4. D6-03A 统一门禁尚未在当前草稿上执行；未进行独立复核、远端 CI、QA 或生产验证。
5. D6-05B、D6-05C 均未开始。当前没有 D6-05 运行容器残留。

### 工作树与未提交草稿

- 工作树：`/Users/lixd/.codex/worktrees/d6-05-master-data/fams`。
- 分支：`codex/d6-05-master-data`。
- 已修改：`backend/src/main/resources/application-fams-product.properties`。
- 未跟踪：`backend/src/main/java/com/fams/auth/MasterDataActorContext.java`、`backend/src/main/java/com/fams/masterdata/`、`backend/src/test/java/com/fams/masterdata/IdentityMasterDataHttpTest.java`、`scripts/verify-d6-05a.py`。
- 最近测试报告：`backend/target/surefire-reports/TEST-com.fams.masterdata.IdentityMasterDataHttpTest.xml`；该文件是本地生成证据，不纳入提交。

### 恢复顺序

1. 保留当前未提交草稿，先用失败用例定位依赖冲突为何映射为 500，并固定为契约规定的 409。
2. 将 CSV 模板导入端点、请求格式、正常/拒绝示例和追溯补入 D6-02C 现役契约；重新执行契约生成与验证。
3. 重跑 `rtk python3 scripts/verify-d6-05a.py`，要求全部 5 项通过、退出 0，并确认任务 MySQL/容器精确清理。
4. 运行既有 D6-03A 统一门禁；通过后按功能边界提交 D6-05A，再进入独立复核。
5. D6-05A 关闭后依次实施 D6-05B、D6-05C；三段均关闭前父任务保持 `IN_PROGRESS`。
