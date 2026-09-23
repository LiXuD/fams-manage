# D6-05 task card — 基础资料

## 任务身份与目标

- **ID：** D6-05 基础资料。
- **状态：** `IN_PROGRESS`（D6-05A `IN_REVIEW`；D6-05B/C 尚未开始）。
- **分支 / 基线：** `codex/d6-05-master-data` / `dbb1057`；任务启动提交 `f55808f`。
- **目标：** 沿用 D6-02C 已确认的契约、数据模型、权限和错误语义，交付组织人员、目录仓位、编号规则与业务参数三个小纵切片。
- **架构边界：** Java 模块化单体、MyBatis、Flyway、MySQL 8.4；各主数据使用明确表、Mapper、Service 和 API，不引入通用元数据引擎。

## 实施顺序与关闭条件

| 段 | 交付 | 必须验证 | 状态 |
|---|---|---|---|
| D6-05A | 组织、员工、标准模板导入；员工停用同步停用账号并使旧会话失效；组织停用校验启用子组织、员工和授权范围依赖 | 真实 MySQL；SYSTEM_ADMIN 正常路径；非授权角色 403；迁移、事务回滚、版本冲突和统一门禁 | `IN_REVIEW` |
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

## 2026-09-23 D6-05A 送审检查点

### 已实现与验证

1. 交接草稿已收敛为功能提交：契约 `abd80ea`、组织/员工实现 `91d6421`、真实 MySQL 用例 `8cac2a0`、Checkstyle 文件名修复 `eb2139d`、门禁补齐与静态检查修复 `588bb05`。原有未提交业务草稿已提交，工作树在统一门禁结束后干净。
2. 组织与员工使用显式 Controller、Service、MyBatis Mapper；组织停用校验活动子组织、员工和授权范围；员工停用与关联账号停用、`auth_epoch` 递增处于同一事务。CSV 模板导入明确 `CREATE`、`UPDATE`、`DISABLE`，缺行不隐式删除，后续行失败整批回滚。
3. 组织依赖冲突原 500/409 问题定位为全局认证异常处理器先捕获了基础资料异常；专用处理器优先级修复后返回 `409 MASTER_DATA_DEPENDENCY_CONFLICT`。旧版本冲突断言随后执行并通过。
4. `rtk python3 scripts/verify-d6-05a.py`：任务自有 MySQL 8.4、7 项真实 HTTP 用例全通过，涵盖非管理员 403、会话失效、幂等与树循环、更新与版本冲突、导入回滚；任务容器精确清理。另有 3 项 CSV 解析单测。
5. CSV 导入及模板端点、请求和响应、允许/拒绝示例与追溯已补入 D6-02C 契约。D6-02C 验证退出 0：104 个 operation、164 个示例、82 项契约单测、Java/TypeScript 两次生成一致。
6. 固定 HEAD `60632e2` 在干净工作树以 Java 21.0.7 / Node 24.18.0 执行 `scripts/verify-d6-03a.py`，退出 0：147 项阻断观察；Java 质量报告 38 项测试，Maven 总计 66 项 Java 测试无失败/跳过，Checkstyle/SpotBugs 0，行覆盖率 1533/1723（88.97%）、分支 377/500（75.40%）；TypeScript lint/typecheck、依赖检查、应用与 MySQL 重启恢复、管理端口隔离及精确 Compose 清理均通过。此前 D6-02B 文档扫描命中的本机路径已在 `60632e2` 移除并重新验证通过。

### 仍未关闭

1. D6-05A 的权限、事务、迁移与恢复关键路径尚缺计划 §四.5 要求的用户指定独立复核人及逐项结论；因此保持 `IN_REVIEW`，不得标为 `DEV_VERIFIED`。送审范围为 `4017bfd..60632e2`，重点看组织依赖与循环、员工停用即时失效、CSV 原子回滚、手机号密钥及异常映射。独立复核需记录 reviewed HEAD、复核身份、结论及问题闭合提交。
2. D6-05B、D6-05C 未开始；父任务保持 `IN_PROGRESS`。第 7 步 QA、远端 required-ci、共享/生产升级与生产就绪均未验证，D6-01B/03B 仍受远端权限约束。
3. 本工作树不含 AOCI 索引或 MCP 配置；当前 AOCI 服务绑定的是主检出，不能把其维护结果当作本工作树对齐证明。后续应在目标工作树有对应索引/服务绑定后执行认知维护。

### 后续顺序

1. 由用户指定独立复核人或提供明确独立会话标识；处理复核问题并在最终 reviewed HEAD 重跑 D6-05A 与统一门禁。
2. D6-05A 达到 `DEV_VERIFIED` 后再进入 D6-05B 分类、品牌、厂商、仓库与位置，随后 D6-05C 编号规则和业务参数。
3. 三段均通过且固定提交统一门禁、独立复核满足后，才考虑父任务 `DEV_VERIFIED`。

## 2026-09-23 历史交接检查点

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
