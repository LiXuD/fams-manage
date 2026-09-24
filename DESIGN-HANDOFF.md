# FAMS 项目阶段与交接入口

> 唯一现役进度入口 · 更新于 2026-09-24
> 当前结论：第 1～5 步已完成；第 6 阶段正在进行。D6-04、D6-05 与 D6-06A/07A 基础建表本地 `DEV_VERIFIED`；D6-06/07 父任务仍 `IN_PROGRESS`。D6-01B 与 D6-03B 继续等待远端权限。

## 1. 八步开发流程与当前进度

| 步骤 | 阶段 | 英文 | 阶段目标 | 当前状态 | 依据 / 下一步 |
|---:|---|---|---|---|---|
| 1 | 用户研究与立项 | Discovery | 市场与用户洞察，验证问题是否存在 | `COMPLETED` | 项目决策已确认 |
| 2 | 需求定义与产品规划 | Define | 锁定目标用户、核心场景与 MVP 范围 | `COMPLETED` | [PRD V1.5](docs/superpowers/specs/2026-09-03-fixed-asset-product-design.md) |
| 3 | 信息架构与交互设计 | Design | 流程、页面结构与交互原型 | `COMPLETED` | [IA D0.2](docs/superpowers/specs/2026-09-06-fixed-asset-ia-interaction-design.md) |
| 4 | 视觉设计与规范 | UI | 视觉风格、组件库与设计系统 | `COMPLETED` | [D0.4 设计交接](docs/superpowers/designs/2026-09-06-fams-ui/HANDOFF-D0.4.md) |
| 5 | 原型验证与可用性测试 | Validate | 核对设计稿是否覆盖功能设计文档 | `COMPLETED` | [轻量功能覆盖验证](docs/superpowers/specs/2026-09-11-fams-prototype-validation-usability-test.md)通过 |
| 6 | 技术开发与实现 | Develop | 前后端编码、接口联调 | `IN_PROGRESS` | [开发计划 V1.2](docs/superpowers/plans/2026-09-12-fams-stage6-development-plan.md)；D6-05 与 D6-06A/07A 基础建表本地 `DEV_VERIFIED`；下一步 D6-06 建卡/台账/标签；D6-01B/03B 待外部权限 |
| 7 | 测试与质量保障 | QA | 功能、性能、安全测试与缺陷修复 | `NOT_STARTED` | 待第 6 步形成可运行系统后执行 |
| 8 | 灰度发布与上线运营 | Launch | 灰度放量、监控与持续迭代 | `NOT_STARTED` | 尚无部署或生产端点 |

## 2. 第 5 步验证结论

本项目采用轻量验证：只核对 D0.4 设计稿是否覆盖 PRD V1.5 的一期功能，不组织大规模用户招募、访谈或多轮可用性测试。流程体验问题在系统上线后进入二期、三期持续优化。

核对结果：

- PRD 验收项：`AC-01～AC-53`，53/53 已映射到流程和设计页面。
- 业务流程：`F01～F10`，10/10 已覆盖。
- 设计页面：PC `P01～P26` 及小程序 `M01～M14`，40/40 已存在。
- D0.4 Figma：40/40 页面根节点和导出齐全，现有读回没有文字差异、越界候选或整页图片替代。
- 本地原型：40页静态审计通过，本地链接断链为0。

因此，第5步按用户确认的验收口径关闭，未发现 PRD V1.5 一期功能遗漏。详细结果见[验证文档](docs/superpowers/specs/2026-09-11-fams-prototype-validation-usability-test.md)和[覆盖清单](docs/superpowers/specs/2026-09-11-fams-usability-test-matrix.csv)。

该结论只表示设计覆盖完整，不表示功能已经开发、接口已经联调或生产系统已经验收。

## 3. 后续接手顺序

1. 先读本文和 [PRD V1.5](docs/superpowers/specs/2026-09-03-fixed-asset-product-design.md)。
2. 阅读[第六阶段开发计划](docs/superpowers/plans/2026-09-12-fams-stage6-development-plan.md)：技术架构、接口契约、Git/CI、D6-01～D6-27 台账、外部依赖及持续维护规则均在其中。Git 提交强制格式见计划 §四.4。
3. 本轮修订已获用户确认；D6-01A、D6-02A、D6-02B、D6-02C、D6-03A、D6-04A/B/C/D 及 D6-04 父任务状态为本地 `DEV_VERIFIED`。用户本人对 D6-04 五域及总评 PASS，含签署记录的 reviewed HEAD `8943dc3` 完整门禁 127 项通过；隔离 MySQL 的浏览器链路详见 [D6-04 任务卡](docs/development/D6-04-task-card.md)。D6-01B 与 D6-03B 仍等待 GitHub 私有仓库与权限。
4. 每次任务开始、受阻、送审、验证和验收时同步维护计划台账及执行记录；本文只维护项目阶段和下一步，不复制第二套任务状态。D6-02B 的版本冻结结论与 D6-02C 的关键设计复核均已关闭；后续关键任务仍须按计划独立复核。
5. 只有 PRD 变更或开发发现设计缺口时，才重新打开第5步对应条目；无需恢复已取消的大规模用户测试方案。
6. D6-04A/B/C/D 均已提交并完成本地验证；C 的 Web 14/14 测试、构建、lint/typecheck，D 的双用户角色撤销失效浏览器链路、用户本人五域及总评 PASS，与 reviewed HEAD `8943dc3` 的 127 项统一门禁共同构成父任务本地 `DEV_VERIFIED` 证据。不得据此声明远端 CI、QA、正式安全专项或生产完成。
7. D6-05 工作树为 `/Users/lixd/.codex/worktrees/d6-05-master-data/fams`，分支 `codex/d6-05-master-data`。用户本人对 [B 独立复核单](docs/development/D6-05B-independent-review.md)及先行建表回复“全部任务复核PASS”；签署记录的干净 HEAD `e15c8ce` 上，任务库 24 项真实 MySQL 与 1 项 Flyway V3→V5 历史升级、统一门禁 203 项阻断观察均退出 0。D6-05A/B/C 与父任务、D6-06A/07A 基础建表据此为本地 `DEV_VERIFIED`。下一步进入 D6-06 建卡、台账、标签和跨模块写入锁协议；D6-06/07 父任务尚无业务服务，仍 `IN_PROGRESS`。

## 4. 文档责任

| 内容 | 现役入口 |
|---|---|
| 项目阶段和下一步 | 本文 |
| 一期功能范围 | [PRD V1.5](docs/superpowers/specs/2026-09-03-fixed-asset-product-design.md) |
| 流程和页面映射 | [IA D0.2](docs/superpowers/specs/2026-09-06-fixed-asset-ia-interaction-design.md) |
| D0.4 设计证据 | [D0.4 设计交接](docs/superpowers/designs/2026-09-06-fams-ui/HANDOFF-D0.4.md) |
| 第5步验证结果 | [轻量功能覆盖验证](docs/superpowers/specs/2026-09-11-fams-prototype-validation-usability-test.md) |
| 第6步计划、Git/CI 规范、任务进度与执行记录 | [开发计划 V1.2](docs/superpowers/plans/2026-09-12-fams-stage6-development-plan.md) |

`FIXED_ASSET_SYSTEM_DESIGN.md` 是早期系统设计参考，不能替代 PRD V1.5。旧评审、D0.3验证和D0.4执行台账只作为历史证据。

## 5. 当前事实面

| 事实面 | 状态 | 说明 |
|---|---|---|
| 设计文档 | `changed-and-verified` | 一期功能覆盖验证已完成并同步 |
| 开发计划 | `in-progress` | V1.2 顺序调整已获用户授权；D6-04、D6-05 与 D6-06A/07A 本地 `DEV_VERIFIED`；D6-06/07 父任务 `IN_PROGRESS`；D6-01B/03B 远端能力尚未完成 |
| 规则 | `changed-and-verified` | AGENTS 已指向当前阶段 |
| 产品代码 | `in-progress` | D6-04 认证、账号授权和 PC 登录外壳已本地验证；D6-05A/B/C 管理接口及真实资产/入库引用拒绝已本地验证；V4/V5 仅为 D6-06/07 基础表，建卡与审批业务服务尚未实现 |
| 产品运行态 | `local-dev-partial` | D6-04 真实浏览器链路已验证；D6-05 签署后双门禁通过，HEAD `e15c8ce` 任务库 24+1 与统一门禁 203 项通过；远端 CI、QA、共享/生产库升级及生产未验证 |
| 记忆 | `out-of-scope` | 本次未修改机器生成记忆 |
| 工作区 | `handoff-recorded` | D6-05 位于 `/Users/lixd/.codex/worktrees/d6-05-master-data/fams`，分支 `codex/d6-05-master-data`；实现、验证及送审文档已提交，复核入口与证据见任务卡 |
