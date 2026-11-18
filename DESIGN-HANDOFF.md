# FAMS 项目阶段与交接入口

> 2026-11-18 分支归档说明：本工作树 `codex/d6-21-loss` 正在提交此前保留的历史代码、测试与文档。下文 2026-11-13 台账为本分支快照，不代表当前集成进度。现役开发已经接续到 `.worktrees/d6-ci` / `codex/d6-03b-ci`，已推送固定 `05216ed`；当前父项为 17 项开发验证通过、7 项进行中、1 项受阻、2 项未开始。最新交接见 [05216ed 进度快照](https://github.com/LiXuD/fams-manage/blob/05216eda72d1dececeb63889836cc2532d7c830f/docs/development/2026-11-18-current-progress-handoff.md)，本分支保存范围见[根工作树归档记录](docs/development/2026-11-18-root-worktree-archive.md)。此次归档不合并或覆盖现役开发分支。

> 更新于 2026-11-13。第 1～5 步完成，第 6 步开发进行中，第 7 步正式 QA 与第 8 步上线待启动。第 6 步父任务共 27 项，本地开发验证通过 14 项、进行中 9 项、受阻 2 项、未开始 2 项。状态明细和验证证据分别见[开发计划 §四.5 和 §六](docs/superpowers/plans/2026-09-12-fams-stage6-development-plan.md)。

## 1. 当前开发基线

| 位置 | 提交 | 用途 |
|---|---|---|
| `main`（本地与 `origin/main` 跟踪引用） | `455ba00` | 主线基线 |
| 根工作区 `codex/d6-21-loss` | `08c38b2` | 有在途代码与测试改动，另有本轮同步后的文档。继续开发前先核对工作区差异 |
| `codex/d6-fourth-batch` | `ab8a699` | 第四批开发分支，位于 `.worktrees/d6-fourth-batch`，包含第一至第三批；比远端跟踪引用 `2b8a3ff` 多 1 个本地提交 |

第六阶段已在本地关闭身份权限、主数据、台账、入库与导入底座、领用、借还、调拨交接、异常转派、盘点任务与内部维修以及盘点差异与封存、返厂与换机、报失与找回等 14 个父任务。附件、微信绑定、交出接收和离线盘点分别已有内部交付，外部适配或真机仍有对应验收项。

| 交付 | 已有证据 |
|---|---|
| 第一批 | D6-24C2 固定 `1972d48` 完整产品门禁与用户复核通过。D6-02C-C2/C3 的分类和生成检查通过，`5ca95d7` 完整契约门禁受历史路径扫描阻塞 |
| 第二批 | `325bc84` 的七条实际 PC 链路通过提交后 JVM 强停重放，固定候选的产品门禁分段通过。覆盖厂家收旧/销毁、返厂遗失核销、独立处置、受控纠正、内部维修冲正和报废后找回 |
| 第三批 | `99e72d1` 已形成盘点复核封存、跨来源报失、期初三种状态承接的实际页面、原生 Page/HTTP 和恢复证据，产品门禁分段通过。八个子项已获用户复核通过。详见[第三批验证记录](docs/development/evidence/d6-third-batch-99e72d1.json) |
| 第四批 | `2b8a3ff` 已接入配置异常、停用转派、短信队列及部分超时兜底、资产管理员工作台及部分报表。第四批现有记录为分项验证，整批候选仍需联合验证；接续提交 `ab8a699` 增加报失复核和报废处置短信兜底，已通过隔离 MySQL 定向验证 |

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

## 3. 未完成任务

| 事项 | 待完成工作 |
|---|---|
| 独立复核 | D6-22A/B/C/E 和 D6-24A/B1/B2/C1 仍按各自台账补证与复核。第一至第三批本轮 12 个已送审子项已获用户复核通过 |
| D6-02C-C2/C3 契约门禁 | 处理 10 个历史文件触发的本机绝对路径检查，保留历史证据，再对固定候选跑完整 D6-02C |
| D6-22 报废 | 补齐各来源与双端的剩余验证，完成 E 的审计只读角色组织范围授权及联合复核 |
| D6-23 期初承接 | 待报废行的遗失、维修与返厂来源仍需核验，按 PRD 确认边界 |
| D6-24 通知 | 完成配置异常跨域待办及实际 PC 验证，补齐员工/角色变更、无法继续与超时转派。完成借用逾期、返厂超期等短信规则、微信订阅、指标抓取与跨 JVM 恢复，验证零外部触达时业务可办理 |
| D6-25 报表与工作台 | 对照 PRD §14 补齐剩余报表及角色工作台，核对统计分母、排除项、筛选/下钻/导出审计，补实际浏览器和规模冒烟 |
| D6-01B、D6-03B 远端协作 | 核验仓库可见性与访问控制，配置并验证 PR 提交检查、required CI 和分支保护 |
| D6-09B、D6-10B、D6-12B、D6-17C 外部联调 | 按既定安排在内部功能开发后统一验证真实短信、微信 AppID/模板/HTTPS、真机扫码接收与盘点弱网回执 |
| D6-26、D6-27 与后续阶段 | 完成 40 页、10 个流程、53 项 AC 全范围回归和缺陷闭环，固定 QA 候选并交接环境、数据、性能、安全和恢复材料，再进入第 7 步 QA、第 8 步发布 |

下一内部开发项在第四批分支接续 D6-24 和 D6-25。已送审切片按候选逐项记录复核，待开发与验收项继续更新[现役台账及执行记录](docs/superpowers/plans/2026-09-12-fams-stage6-development-plan.md)。

## 4. 文档入口

| 内容 | 现役文档 |
|---|---|
| 本地启动、工具链与开发验证 | [README](README.md)、[小程序接入](apps/miniprogram/README.md) |
| 一期需求、流程与视觉 | [PRD V1.5](docs/superpowers/specs/2026-09-03-fixed-asset-product-design.md)、[IA D0.2](docs/superpowers/specs/2026-09-06-fixed-asset-ia-interaction-design.md)、[D0.4](docs/superpowers/designs/2026-09-06-fams-ui/HANDOFF-D0.4.md) |
| 技术架构、接口约定、Git/CI、任务状态及执行记录 | [开发计划 V1.2](docs/superpowers/plans/2026-09-12-fams-stage6-development-plan.md)、[OpenAPI 契约](contracts/fams-core-v1.yaml) |
| 当前关键切片 | [D6-18 盘点](docs/development/D6-18-task-card.md)、[D6-20 返厂](docs/development/D6-20-task-card.md)、[D6-21 报失](docs/development/D6-21-task-card.md)、[D6-22 报废](docs/development/D6-22-task-card.md)、[D6-23 期初](docs/development/D6-23-task-card.md)、[D6-24 通知](docs/development/D6-24-task-card.md) |

[早期 ER 设计](FIXED_ASSET_SYSTEM_DESIGN.md)、[主线整合记录](docs/development/2026-10-04-main-integration.md)、[2026-10-30 交接](docs/development/2026-10-30-current-progress-handoff.md)和[夜间执行记录](docs/superpowers/plans/2026-11-10-overnight-p3-runbook.md)用于追溯历史决策和验证范围。
