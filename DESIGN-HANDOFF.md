# FAMS 项目阶段与交接入口

> 唯一现役进度入口 · 更新于 2026-10-14
> 当前结论：第 1～5 步已完成；第 6 阶段正在进行。D6-04～D6-08（含 A/B/C/D）与 D6-09A 本地 `DEV_VERIFIED`；用户本人本轮复核全部 PASS，已以 `b007a5f` 为基准整合到 `main`。D6-14 已获用户全部复核 PASS；本轮已完成成果整合到 main，远端 origin 已提供，CI 与协作设置待验证。

## 1. 八步开发流程与当前进度

| 步骤 | 阶段 | 英文 | 阶段目标 | 当前状态 | 依据 / 下一步 |
|---:|---|---|---|---|---|
| 1 | 用户研究与立项 | Discovery | 市场与用户洞察，验证问题是否存在 | `COMPLETED` | 项目决策已确认 |
| 2 | 需求定义与产品规划 | Define | 锁定目标用户、核心场景与 MVP 范围 | `COMPLETED` | [PRD V1.5](docs/superpowers/specs/2026-09-03-fixed-asset-product-design.md) |
| 3 | 信息架构与交互设计 | Design | 流程、页面结构与交互原型 | `COMPLETED` | [IA D0.2](docs/superpowers/specs/2026-09-06-fixed-asset-ia-interaction-design.md) |
| 4 | 视觉设计与规范 | UI | 视觉风格、组件库与设计系统 | `COMPLETED` | [D0.4 设计交接](docs/superpowers/designs/2026-09-06-fams-ui/HANDOFF-D0.4.md) |
| 5 | 原型验证与可用性测试 | Validate | 核对设计稿是否覆盖功能设计文档 | `COMPLETED` | [轻量功能覆盖验证](docs/superpowers/specs/2026-09-11-fams-prototype-validation-usability-test.md)通过 |
| 6 | 技术开发与实现 | Develop | 前后端编码、接口联调 | `IN_PROGRESS` | [开发计划 V1.2](docs/superpowers/plans/2026-09-12-fams-stage6-development-plan.md)；D6-10A 及 D6-11 已获用户复核 PASS；D6-12A 候选 959fd3c 已获用户全部复核 PASS；D6-13 候选 e99bca5 已获用户全部复核 PASS；D6-14 候选 f983439 已获用户全部复核 PASS，D6-15A 调拨受控转派已获用户复核通过，D6-15B1 入库受控转派已送审，B2 领用/借用转派送审，B3 归还转派固定候选47fbab5完整门禁通过并送审，小程序调拨转派a08a8ae完整门禁通过并送审，C1领用/借用异常代确认PC/API及责任通知已实现并通过真实运行检查，补齐责任核查队列，a8b1005完整门禁退出0并送审，C2调拨PC/API及责任通知核查已实现并通过真实运行与浏览器恢复，65d3d67完整门禁退出0并送审，C3小程序办理、附件与责任核查已实现并通过真实HTTP，e54820a完整门禁退出0并送审，D1领用/借用批准目标变更修复候选6baf5c1完整门禁退出0并送审，D2调拨目标更正候选d60543e完整门禁退出0并送审，D3小程序目标更正候选abc2b3f完整门禁退出0并送审，D4联合验证候选a7ec1fe完整门禁退出0并送审，E1开发冒烟及新统一入口候选ffe7069完整门禁退出0并送审，E2综合独立复核材料已准备但结论待完成；下一内部开发项D6-16盘点任务与快照，后续范围见现役台账，真实短信/真机留到全部功能开发后；D6-01B/03B 已有远端权限，设置与 CI 待验 |
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
7. D6-05A/B/C 与父任务、D6-06/06A、D6-07/07A、D6-08A/B/C/D 和 D6-09A 均已本地 `DEV_VERIFIED`。用户本人本轮确认 D6-08 复核全部 PASS，后续开发从 `main` 统一基线继续；远端 CI、QA 与生产尚未验证。

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
| 开发计划 | `in-progress` | V1.2 顺序调整已获用户授权；D6-04～D6-08 与 D6-09A 本地 `DEV_VERIFIED`；D6-08 全部必需子任务及父任务经用户复核关闭；D6-01B/03B 远端能力尚未完成 |
| 规则 | `changed-and-verified` | AGENTS 已指向当前阶段 |
| 产品代码 | `in-progress` | D6-04～D6-08 及 D6-09A 已本地开发验证并复核；全部分支成果整合到 `main` |
| 产品运行态 | `local-dev-partial` | D6-08 候选 `6d94db8` 独有 MySQL/HTTP 与完整本地门禁通过；B/C/D 经用户本人复核全部 PASS，远端 CI、QA、共享/生产升级及生产未验证 |
| 记忆 | `out-of-scope` | 本次未修改机器生成记忆 |
| 工作区 | `handoff-recorded` | 统一开发基线为根目录 `main`；本轮整合来源 `a752157`，全部现有分支及干净工作树成果已纳入，旧工作树保留；origin 已绑定用户指定仓库，推送与远端一致性由 [本轮整合记录](docs/development/2026-10-09-main-remote-integration.md)及实际远端引用确认 |

## 2026-09-30 续接记录

用户本人对 D6-07、D6-08A、D6-09A 复核全部 PASS，三项本地 `DEV_VERIFIED`。D6-08B 的 Excel 模板/映射、预检、V14 批次、真实 HTTP、PC 入口及结果下载已实现；D6-08C 的期初窗口、核验批准、在库/在用原子发布、最终 CSV、受控整批回滚和三角色 PC 操作已实现。独有 MySQL 中撤权、`SIGKILL` 后新 JVM 重领、写入中异常整体回滚及本地浏览器链路通过。D6-08D 的一万行预检、容量边界、十万资产查询与单轮事务计时已记录，列表引用 N+1 已修复。

本分支最新功能测试提交为 `4c2e757`。其导入核验驳回/重提、回滚驳回/重请，以及预检人员范围、重复标识和非法状态定向用例通过；Java 质量 `validate` 退出 0。最近一次完整产品门禁在此前提交 `b11502f` 上退出 1：Java 行覆盖 91.18%、分支覆盖 71.72%，未达到既定 75% 分支门槛；新增用例尚未重跑完整门禁，因此 D6-08B/C/D 保持 `IN_PROGRESS`，不可写为 `DEV_VERIFIED` 或正式 QA 通过。下一步仅补关键业务分支并在候选稳定后跑一次门禁，达到门槛再送独立复核；D6-09B 与微信/短信/HTTPS 等真实外部资源另待准备。不重复历史治理与 SHA 审计。

## 2026-10-04 送审记录（历史）

代码候选 `6d94db8` 在隔离工作树以 Java 21、Node 24 和独有 MySQL 8.4.11 运行完整 `scripts/verify-product-development.py`，退出 0；Java 必需测试 59 项、Checkstyle/SpotBugs 0、行覆盖 91.99%、分支覆盖 75.03%，Web lint/typecheck、依赖审计及应用/数据库重启恢复通过。原始输出见 [D6-08 门禁记录](docs/development/evidence/D6-08-product-gate-6d94db8.txt)。D6-08B/C/D 已由 `IN_PROGRESS` 转 `IN_REVIEW`，父任务仍 `IN_PROGRESS`；下一步由非实施者按 [D6-08 任务卡](docs/development/D6-08-task-card.md)复核权限、事务、恢复及测量边界，记录逐项结论。未经复核不得写本地 `DEV_VERIFIED`。远端 CI、第七阶段 QA、共享/生产迁移和上线均未完成；本分支未合并或推送。

## 2026-10-04 复核关闭与主线整合

用户本人本轮明确“复核全部 pass”，确认送审交接 `b007a5f`、代码候选 `6d94db8` 的 D6-08B/C/D 复核通过。A/B/C/D 及 D6-08 父任务记为本地 `DEV_VERIFIED`。全部已有分支历史和工作树成果以该候选为基准整合到根目录 `main`；后续接手先读 [主线整合记录](docs/development/2026-10-04-main-integration.md)，再按现役计划推进 D6-09B 外部资源或 D6-10。第六阶段继续 `IN_PROGRESS`；远端 CI、正式 QA 和生产仍未验证。

根目录 `main` 整合候选 `3b901ff` 的完整产品门禁退出 0，指标与原送审候选一致；[原始输出](docs/development/evidence/main-product-gate-3b901ff.txt)。所有 12 个分支 HEAD 已纳入主线，11 个工作树清洁检查通过。AOCI 收尾仍受治理停点阻断，当前正式索引尚未对齐新增产品对象，详见整合记录。

## 2026-10-05 开发续接

从 main `df8cb6d` 开始 D6-10A，当前功能分支 `codex/d6-10a-session`。短信验证码、绑定/换绑/解绑、受审计号码更正及 M01/M14 原生页面已实现，固定候选 `d903199` 完整产品门禁退出 0，用户本人 2026-10-06 复核全部 PASS；下一开发项为 D6-11，真实短信/真机联调按用户安排留到全部功能开发后，外部证据由 D6-10B 承担。详细状态和证据仅在现役计划及 [D6-10 任务卡](docs/development/D6-10-task-card.md)维护。开始时已有 `.aoci/baseline.json` 和 `aoci.code.txt` 未提交变化，保留且不混入功能提交。

2026-10-07：从 D6-10A 已复核提交 `00c795c` 进入 `codex/d6-11-requisition`，先执行 D6-11A 事实与迁移。子任务与证据以开发计划和 [D6-11 任务卡](docs/development/D6-11-task-card.md)为准；下一步接 D6-11B 申请审批和 D6-11C 分配再申请。真实短信/真机仍待全部功能开发后联调。

2026-10-07：D6-11 全功能提交 `92c1662` 已完成申请审批、PC/小程序页面、独占分配、无法满足、关联再申请与范围变更。固定候选门禁及当前提交真实浏览器/硬重启链路通过，父任务及 A/B/C/D 为 `IN_REVIEW`；详见现役开发计划与 [D6-11 任务卡](docs/development/D6-11-task-card.md)。下一步统一独立复核后进入 D6-12；真实短信/真机仍按用户安排后置。

2026-10-07：用户本人确认 D6-11 全部复核 PASS，固定候选 `92c1662`、送审交接 `701da4d` 的父任务与 A/B/C/D 关闭为本地 `DEV_VERIFIED`。按计划继续 D6-12；功能与证据以现役计划及任务卡为准，真实短信/真机仍后置。

2026-10-07：D6-12A 内部交出、实际接收/拒收、未交出取消、收回验收取消与 PC/M09 页面已实现，
固定候选 `959fd3c` 完整门禁退出 0，内部功能为 `IN_REVIEW`。真实浏览器和 MySQL 持久效果、
原生处理器重试、并发、事务回滚与硬重启恢复详见 [D6-12 任务卡](docs/development/D6-12-task-card.md)。
D6-12B 真机验收仍按用户安排后置，父任务保持外部 `BLOCKED`；下一内部功能项为 D6-13。

2026-10-07：用户本人确认 D6-12A 全部复核 PASS，关闭为本地 DEV_VERIFIED；B 真实移动验收仍后置，父任务保留外部 BLOCKED。继续 D6-13 借用与归还及后续内部功能，详细状态见现役计划和 D6-13 任务卡。

2026-10-09：D6-13 业务候选 e99bca5 完整门禁退出 0，真实内部两端链路、浏览器和硬重启验证完成，父任务与 A/B/C 为 IN_REVIEW。下一步为用户按 D6-13 任务卡独立复核，通过后进入 D6-14；外部短信/微信/真机继续后置。用户批准压缩后使用当前源码与运行证据继续，避免重复完整索引加载，已固化于 AGENTS.md。

2026-10-09：用户本人确认 D6-13 全部复核通过，业务候选 e99bca5、送审材料 6931e89 的父任务及 A/B/C 本地 DEV_VERIFIED。进入 D6-14 调拨与人员交接；现役台账与任务卡维护细节，外部短信/微信/真机继续后置。

2026-10-09：D6-14 候选 f983439 完整开发门禁、真实浏览器与同库硬重启通过，父任务及 A/B/C/D 为 IN_REVIEW。下一步按 D6-14 任务卡独立复核，通过后进入 D6-15；详细证据和状态仅维护于计划与任务卡。

2026-10-09：用户本人确认 D6-14 本次复核全部通过，父任务及 A/B/C/D 本地 DEV_VERIFIED；授权将已完成成果整合到 main 并绑定 origin `https://github.com/LiXuD/fams-manage.git` 推送。下一内部开发项 D6-15；远端当前公开，CI 和协作设置按 D6-01B/03B 继续配置验收，真实外部联调保持后置。

2026-10-14：D6-16盘点任务/快照正在codex/d6-16-inventory-tasks实现与联合验证，按现役计划子台账执行，尚未完成固定候选门禁或独立复核。D6-15综合送审和父项开放状态保持。

2026-10-14 D6-16最终功能候选a57a9f6统一门禁通过，任务/快照/唯一执行人管理、两端只读及兼容修复证据已送独立复核，父项保持开放。当前下一内部开发项D6-17现场采集与离线；详细进度以计划§四.5和[D6-16复核材料](docs/development/2026-10-14-d6-16-independent-review-pack.md)为准，真实外部联调仍后置。
