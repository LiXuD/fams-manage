# D6-05B 目录仓位与引用拒绝独立复核单

> 状态：`IN_REVIEW`；复核人：用户本人；尚无 B 的复核结论。
> 实现候选：`0828dc8c2b6ff8561faac31d5d9e02cce976e4c2`。
> 现役计划：V1.2；D6-06A/07A 真实基础表按用户授权提前实施，D6-06/07 完整业务服务尚未交付。

## 复核范围

以 [D6-05 任务卡](D6-05-task-card.md)、[PRD V1.5](../superpowers/specs/2026-09-03-fixed-asset-product-design.md)、[OpenAPI 契约](../../contracts/fams-core-v1.yaml) 和 [D6-02C 数据设计](D6-02C-domain-data-design.md) 为准，核对分类、品牌、厂商、仓库和位置五组接口，以及仓库/位置对真实资产和入库目标的停用拒绝。V4/V5 表结构一并复核，但本单不把 D6-06/07 服务或父任务视为完成。

只读入口：

```sh
rtk git status --short --branch
rtk git rev-parse HEAD
rtk git diff --stat 97aa405..0828dc8
rtk git diff 97aa405..0828dc8 -- backend/src/main/resources/db/migration/d6_04 backend/src/main/java/com/fams/masterdata backend/src/test/java/com/fams/masterdata scripts/verify-d6-05a.py
```

## 必审领域

每域给 `PASS`、`NEEDS_FIX` 或 `QUESTION`；未实际审阅的领域保持 `QUESTION`。

| 领域 | 核对重点 | 结论 |
|---|---|---|
| 需求与契约 | 五组目录仓位接口、版本化引用、分类/位置树、跨仓限制、状态与错误码 | `QUESTION` |
| 权限与拒绝 | 管理员写入、非授权 403、CSRF/幂等、旧版本、停用后编码不复用、停用引用 409 | `QUESTION` |
| 引用与并发 | 仓库当前/计划资产、未结入库目标；位置当前/计划资产、未结目标及子位置；锁定读取与后续写入锁协议 | `QUESTION` |
| 迁移与恢复 | V4/V5 对照 D6-02C 字段/外键/约束，V1～V3 不变，既有 V3 Flyway 历史和数据升级保留，终态单据放行 | `QUESTION` |
| 质量与证据 | 任务库、固定提交统一门禁、容器清理、未验证边界及送审 SHA | `QUESTION` |

## 已有证据与限制

- 实现候选 `0828dc8` 的 `scripts/verify-d6-05a.py` 退出 0：24 项任务自有 MySQL 8.4 集成用例，含 HTTP 管理接口、越权拒绝、仓库/位置对计划及正式资产和未结入库目标的拒绝、终态放行；另一任务容器的 1 项专项用例先由 Flyway 迁至 V3、插入既有仓库，再由 Flyway 迁至 V5，校验历史、三张新表及原数据保留。仍未对共享或生产数据库执行升级。
- 同一干净 HEAD `0828dc8` 的 `scripts/verify-d6-03a.py` 退出 0：199 项阻断观察，涵盖契约生成、Java/TypeScript、依赖、应用与 MySQL 重启恢复、Compose 精确清理及前后干净工作树。`148d55a` 曾因专项升级类在常规质量中被计为跳过而失败，`0828dc8` 隔离专项测试发现后通过；不将该失败运行记作通过。
- 当前不存在 D6-06 建卡或 D6-07 入库业务写入服务。测试用真实表夹具检验已存在引用的停用行为；未来服务必须在同一短事务先锁定仓库/位置并重验状态，跨服务竞争仍须在各自任务中验证。V5 只建立单头与目标明细，业务锁、决定、认领和验收仍属 D6-07 后续实现。
- 远端 required-ci、共享/生产库升级、正式 QA 和生产就绪均未验证；本次复核只决定本地开发状态。

## 回复格式

```text
复核对象：<实际 rtk git rev-parse HEAD 完整 SHA>
需求与契约：PASS / NEEDS_FIX / QUESTION
权限与拒绝：PASS / NEEDS_FIX / QUESTION
引用与并发：PASS / NEEDS_FIX / QUESTION
迁移与恢复：PASS / NEEDS_FIX / QUESTION
质量与证据：PASS / NEEDS_FIX / QUESTION
总评：PASS / NEEDS_FIX / QUESTION
问题清单：无，或逐条写明文件/接口、影响和期望修复
实际审阅证据：阅读的文件/差异、运行的命令与结果
复核边界：D6-05B 及 D6-06A/07A 基础表的本地实现
```

收到结论后逐项核对与修复；若通过，将签署记录提交并在最终固定提交重跑任务库和统一门禁，然后再判断 B 及 D6-05 父任务是否达到本地 `DEV_VERIFIED`。
