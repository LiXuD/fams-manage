# FAMS 项目阶段与交接入口

> 唯一现役进度入口 · 更新于 2026-09-15
> 当前结论：第 1～5 步已完成；第 6 阶段已进入进行中，Git 已在本地 `main` 初始化；D6-01A、D6-02A、D6-02B、D6-02C、D6-03A 当前为 `DEV_VERIFIED`，固定候选组合、用户本人复核及最终本地门禁均已关闭。D6-01B 与 D6-03B 远端能力尚未完成；D6-04 已在隔离分支启动，尚未取得产品运行通过结论。

## 1. 八步开发流程与当前进度

| 步骤 | 阶段 | 英文 | 阶段目标 | 当前状态 | 依据 / 下一步 |
|---:|---|---|---|---|---|
| 1 | 用户研究与立项 | Discovery | 市场与用户洞察，验证问题是否存在 | `COMPLETED` | 项目决策已确认 |
| 2 | 需求定义与产品规划 | Define | 锁定目标用户、核心场景与 MVP 范围 | `COMPLETED` | [PRD V1.5](docs/superpowers/specs/2026-09-03-fixed-asset-product-design.md) |
| 3 | 信息架构与交互设计 | Design | 流程、页面结构与交互原型 | `COMPLETED` | [IA D0.2](docs/superpowers/specs/2026-09-06-fixed-asset-ia-interaction-design.md) |
| 4 | 视觉设计与规范 | UI | 视觉风格、组件库与设计系统 | `COMPLETED` | [D0.4 设计交接](docs/superpowers/designs/2026-09-06-fams-ui/HANDOFF-D0.4.md) |
| 5 | 原型验证与可用性测试 | Validate | 核对设计稿是否覆盖功能设计文档 | `COMPLETED` | [轻量功能覆盖验证](docs/superpowers/specs/2026-09-11-fams-prototype-validation-usability-test.md)通过 |
| 6 | 技术开发与实现 | Develop | 前后端编码、接口联调 | `IN_PROGRESS` | [开发计划 V1.1](docs/superpowers/plans/2026-09-12-fams-stage6-development-plan.md)；D6-02A/B/C、D6-03A 已 `DEV_VERIFIED`；D6-04 已启动；D6-01B/03B 远端能力待外部权限 |
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
3. 本轮修订已获用户确认；D6-01A、D6-02A、D6-02B、D6-02C、D6-03A 状态为 `DEV_VERIFIED`。D6-02C 已完成首批 OpenAPI、数据/权限/错误设计、追溯、任务级复核与用户本人关键技术复核；签署记录提交上的最终门禁退出 0。D6-03A 已完成 Compose、Java/TypeScript 质量规则、覆盖率校准、受限指标和应用/数据库恢复验证；用户本人对固定送审 SHA 的范围、质量规则/负例、Compose/指标/恢复及总评全部给出 PASS，问题清单为“无”，签署记录提交上的统一入口以 84 项阻断观察退出 0。详见 [D6-03A 门禁证据](docs/development/D6-03A-local-quality-gate.md)。尚无产品业务实现，下一本地任务为 D6-04；D6-01B 与 D6-03B 仍等待 GitHub 私有仓库与权限。以 [IA D0.2](docs/superpowers/specs/2026-09-06-fixed-asset-ia-interaction-design.md) 的 `F01～F10`、`P01～P26`、`M01～M14` 为开发范围，以第5步[覆盖清单](docs/superpowers/specs/2026-09-11-fams-usability-test-matrix.csv)检查遗漏。
4. 每次任务开始、受阻、送审、验证和验收时同步维护计划台账及执行记录；本文只维护项目阶段和下一步，不复制第二套任务状态。D6-02B 的版本冻结结论与 D6-02C 的关键设计复核均已关闭；后续关键任务仍须按计划独立复核。
5. 只有 PRD 变更或开发发现设计缺口时，才重新打开第5步对应条目；无需恢复已取消的大规模用户测试方案。
6. 本次已完成 D6-01A 本地治理、D6-02A 契约生成验证、D6-02B 真实运行兼容性验证、D6-02C 首批真实设计包复核和 D6-03A 本地统一检查。D6-04 已建立任务卡并开始真实产品纵切片；当前尚无 D6-04 运行通过、远端仓库或 CI 结论。契约、兼容性探针、设计包和质量配置不等于产品能力，正式 QA 和上线分别记录。

## 4. 文档责任

| 内容 | 现役入口 |
|---|---|
| 项目阶段和下一步 | 本文 |
| 一期功能范围 | [PRD V1.5](docs/superpowers/specs/2026-09-03-fixed-asset-product-design.md) |
| 流程和页面映射 | [IA D0.2](docs/superpowers/specs/2026-09-06-fixed-asset-ia-interaction-design.md) |
| D0.4 设计证据 | [D0.4 设计交接](docs/superpowers/designs/2026-09-06-fams-ui/HANDOFF-D0.4.md) |
| 第5步验证结果 | [轻量功能覆盖验证](docs/superpowers/specs/2026-09-11-fams-prototype-validation-usability-test.md) |
| 第6步计划、Git/CI 规范、任务进度与执行记录 | [开发计划 V1.1](docs/superpowers/plans/2026-09-12-fams-stage6-development-plan.md) |

`FIXED_ASSET_SYSTEM_DESIGN.md` 是早期系统设计参考，不能替代 PRD V1.5。旧评审、D0.3验证和D0.4执行台账只作为历史证据。

## 5. 当前事实面

| 事实面 | 状态 | 说明 |
|---|---|---|
| 设计文档 | `changed-and-verified` | 一期功能覆盖验证已完成并同步 |
| 开发计划 | `in-progress` | V1.1 修订已确认；D6-01A、D6-02A、D6-02B、D6-02C、D6-03A 已 `DEV_VERIFIED`；D6-04 已启动；D6-01B/03B 远端能力尚未完成 |
| 规则 | `changed-and-verified` | AGENTS 已指向当前阶段 |
| 产品代码 | `in-progress` | D6-04 已在隔离分支启动；当前只完成任务拆分，产品实现与验证仍在进行中 |
| 产品运行态 | `probe-only` | D6-02B 仅验证本地真实 MySQL 技术闭环；D6-04 尚无真实登录或客户端运行通过证据 |
| 记忆 | `out-of-scope` | 本次未修改机器生成记忆 |
| 工作区 | `verified-current` | D6-04 位于 `feat/D6-04-pc-login-permissions` 隔离工作树并标记 `IN_PROGRESS`；D6-03A 保持 `DEV_VERIFIED`，D6-01B/03B 待外部权限 |
