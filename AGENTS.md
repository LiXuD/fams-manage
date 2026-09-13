# AGENTS.md

## 项目入口

- 先读 `DESIGN-HANDOFF.md`；它是项目阶段、当前进度和下一步的唯一现役入口。
- 当前是文档/静态原型、D6-02A 契约探针与 D6-02B 运行兼容性探针工作区；Git 已在本地 `main` 初始化且尚无远端；仍没有 FAMS 客户端、小程序或产品业务后端。
- 第5步已按用户确认的轻量口径完成：核对D0.4是否覆盖PRD V1.5全部一期功能。第6步“技术开发与实现”已进入进行中，但产品业务实现尚未开始；D6-01A、D6-02A 当前为 `DEV_VERIFIED`；D6-02B 独立任务级复核首轮发现 4 个阻断问题，修复与真实 MySQL 回归已完成，当前仍为 `IN_REVIEW`，复核关闭前不得写为 `DEV_VERIFIED`。
- 第6步 [开发计划 V1.1](docs/superpowers/plans/2026-09-12-fams-stage6-development-plan.md)修订已确认并保存。D6-01B 远端与 CI 尚未完成；D6-02B 固定候选组合已在本地验证 MySQL/Flyway/MyBatis/Session/事务/双重重启及 Java/共享 TypeScript 构建，下一实际动作是修复轮独立复核及 reviewed HEAD 全量复跑，通过后才进入 D6-02C；D6-02/G0 仍在进行中。计划范围内的权限、事务、迁移、恢复关键独立技术复核仍须在 D6-02C 关闭前完成，不将兼容性探针视为产品实现完成。
- 开发任务以计划 §四.5 的 D6-01～D6-27 及其必需子任务台账为唯一进度明细；每次任务开始、受阻、送审、验证及验收时同步更新状态、证据和 §六 执行记录，交接入口只维护阶段与下一步。必需子任务受阻或独立复核未满足时，不得将父任务记为 DEV_VERIFIED。
- Git 提交必须遵守计划 §四.4：`<type>(<scope>): <summary>`，空行后写背景/动机/主要变化正文，再空行写各占一行的 `Agent-Task`、`Agent-Model`、`Agent-Decision`、`Agent-Limitation`。字段均必填；模型必须真实，无法核实写“无法核实”；无局限写“无”。本地 hook、CI 和最终合并提交均按计划校验，尚未配置不得声称已生效。
- 不恢复15～22人招募或R0/R1/R2测试方案；流程体验优化留到上线后的二期、三期。第6步执行契约、权限、并发、恢复检查及开发性能冒烟；完整接口、性能、安全专项与正式验收属于第7步QA。
- 需求以 `docs/superpowers/specs/2026-09-03-fixed-asset-product-design.md` 的 PRD V1.5 为准；页面流程以 IA D0.2 为准；视觉基线为 D0.4。
- 所有 shell 命令使用 `rtk` 前缀。历史计划和评审只作证据，不从其中恢复已完成任务。

## 核心原则

1. **Think Before Coding** — 不确定就问，不要假设
2. **Simplicity First** — 最小代码解决问题，不造轮子
3. **Surgical Changes** — 只改必须改的，匹配已有风格
4. **Goal-Driven** — 定义可验证的成功标准
