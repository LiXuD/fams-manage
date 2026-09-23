# D6-05C 编号规则与业务参数独立复核单

> 状态：用户本人于 2026-09-24 回复“全部复核PASS”；五域及总评按此回复记 `PASS`。
> 代码候选：`beea5bd582aa06e29660c6dda3f255d619a76078`。
> 基线：`f761bb8`（D6-05B 交接后、C 实施前）。
> 回复前最近明确展示的 HEAD：`b3c6451e98261c3f8096bf0806447268910bae31`；用户未单列实际 reviewed HEAD、逐项命令或原始审阅记录，不补造。

## 复核范围

请以 [D6-05 任务卡](D6-05-task-card.md)、[PRD V1.5](../superpowers/specs/2026-09-03-fixed-asset-product-design.md) 和 [OpenAPI 契约](../../contracts/fams-core-v1.yaml) 为准，核对 C 的规则配置、内部号段分配、分类前缀约束与业务参数版本。以下结论只决定本地开发状态；第七阶段 QA、远端 CI、共享/生产升级和上线另行验证。复核时记录实际 HEAD；若代码变化，请核对新的差异和证据。

只读入口：

```sh
rtk git status --short --branch
rtk git rev-parse HEAD
rtk git diff --stat f761bb8..beea5bd
rtk git diff f761bb8..beea5bd -- backend/src/main/java/com/fams/masterdata backend/src/main/resources/db/migration/d6_04/V3__create_numbering_and_business_parameters.sql scripts/verify-d6-05a.py
```

## 必审领域

每域给 `PASS`、`NEEDS_FIX` 或 `QUESTION`；未实际审阅的领域保持 `QUESTION`。

| 领域 | 核对重点 | 主要入口 | 结论 |
|---|---|---|---|
| 需求与契约 | `ASSET` 与 `INBOUND_RECEIPT` 允许模式、日期粒度、固定/分类前缀、编号永久唯一；业务参数完整快照与即时生效 | `contracts/fams-core-v1.yaml`、`NumberingRuleService`、`BusinessParameterService` | `PASS`（用户总体回复） |
| 权限与拒绝 | 管理接口的会话、CSRF、管理员角色、幂等、旧版本、非法配置、日历/时区/期限错误及多余字段 | 两个 Controller、`IdentityMasterDataHttpTest` | `PASS`（用户总体回复） |
| 并发与永久性 | 规则与分类写入的跨实例串行化；分配事务、并发唯一、号段耗尽、已提交作废号不回收、配置变化后的号码不碰撞 | `NumberAllocationService`、两个 Mapper、V3 | `PASS`（用户总体回复） |
| 历史与审计 | 参数不可变版本、当前指针原子切换、操作者身份和审计、已生成号码/旧参数不被新配置重新解释 | `BusinessParameterService`、V3、测试 | `PASS`（用户总体回复） |
| 数据与质量 | V3 向前迁移、真实 MySQL 用例、质量门禁、固定提交统一门禁及残留资源 | 任务卡证据、`scripts/verify-d6-05a.py`、`scripts/verify-d6-03a.py` | `PASS`（用户总体回复） |

## 已有证据与边界

- 代码候选上任务库 22 项真实 MySQL 集成用例（HTTP 与内部调用）退出 0；Java `-Pquality verify` 81 项测试无失败/跳过，行 2872/3211、分支 874/1155（75.67%），Checkstyle/SpotBugs 0。
- 干净代码 HEAD `beea5bd` 的 `scripts/verify-d6-03a.py` 退出 0，共 177 项阻断观察，覆盖契约生成、Java/TypeScript、依赖、应用/MySQL 重启恢复、Compose 精确清理和干净工作树检查。
- 内部分配器尚未由下游资产建卡、批量导入或入库单服务调用；已验证预占、并发、作废和号段耗尽的任务库行为，不能据此声称端到端建卡或单据链路完成。若事务整体回滚，尚未提交的预占随事务回滚；已提交的 `VOID` 记录不会回收。
- 只验证任务自有新建 MySQL 8.4；未验证既有共享/生产库升级、远端 required-ci、正式 QA 或生产。

## 回复格式

```text
复核对象：<实际 rtk git rev-parse HEAD 完整 SHA>
需求与契约：PASS / NEEDS_FIX / QUESTION
权限与拒绝：PASS / NEEDS_FIX / QUESTION
并发与永久性：PASS / NEEDS_FIX / QUESTION
历史与审计：PASS / NEEDS_FIX / QUESTION
数据与质量：PASS / NEEDS_FIX / QUESTION
总评：PASS / NEEDS_FIX / QUESTION
问题清单：无，或逐条写明文件/接口、影响和期望修复
实际审阅证据：阅读的文件/差异、运行的命令与结果
复核边界：仅 D6-05C 本地实现与证据
```

收到结论后逐条处理问题，在最终固定提交重跑任务库与统一门禁；独立复核通过前 C 保持 `IN_REVIEW`，B 的下游引用缺口关闭前 D6-05 父任务保持 `IN_PROGRESS`。

## 用户结论与后续验证

用户本人回复“全部复核PASS”，据此记录上述五域及总评 `PASS`，无已报告问题。此回复关联最近明确展示的 HEAD `b3c6451`，不声称用户实际审阅的 SHA 或逐项审阅动作已另行提供。签署记录落库后仍须在干净固定提交重跑任务库与统一门禁；两门禁通过后才决定 C 的本地 `DEV_VERIFIED`。该结论不涵盖 B 未完成的下游引用验证，也不扩大至远端 CI、正式 QA、共享/生产升级或生产。
