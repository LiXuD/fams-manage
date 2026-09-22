# D6-04 PC 登录与权限独立复核单

> 状态：`AWAITING_USER_REVIEW` · 复核人：用户本人（2026-09-22 用户消息“由我本人进行复核”）
> 实现候选：`50bcf5f2289876422af34df35e30966d883c075c`
> 实现比较基线：`5f4bc8ae842dbd2ee3f2c7f6ea8a2ae3d0071513`
> 复核对象：请在开始审阅时记录含本复核单的 `rtk git rev-parse HEAD` 完整 SHA；后续改动需要重新确认审阅范围。
> 本文件只准备问题与证据，不预填用户结论，不把送审前门禁算作 reviewed HEAD 复跑。

## 1. 决策与边界

请判断 D6-04A～D 的实现是否满足 [任务卡](D6-04-task-card.md)、[开发计划 §四.5](../superpowers/plans/2026-09-12-fams-stage6-development-plan.md)、[PRD V1.5 §4.3～4.5、§5.4](../superpowers/specs/2026-09-03-fixed-asset-product-design.md) 与 [OpenAPI 契约](../../contracts/fams-core-v1.yaml) 的一期身份权限要求，尤其是 A-04-01（注销/停用后旧会话拒绝）和 A-04-02（缺失或错误 CSRF 的写操作拒绝）。

本次审阅是非实施者对规格、权限、迁移/恢复和质量的技术判断。它不等于产品验收、第七阶段 QA、正式安全专项、远端 CI 或生产可用性签署。D6-01B/D6-03B 的远端门禁仍单独开放。

代码审阅可从以下只读命令开始；不得在本工作树切换 HEAD 或改写用户数据：

```sh
rtk git status --short --branch
rtk git rev-parse HEAD
rtk git diff --stat 5f4bc8ae842dbd2ee3f2c7f6ea8a2ae3d0071513..50bcf5f2289876422af34df35e30966d883c075c
rtk git diff 5f4bc8ae842dbd2ee3f2c7f6ea8a2ae3d0071513..50bcf5f2289876422af34df35e30966d883c075c -- backend/src/main/java/com/fams/auth backend/src/main/resources/db/migration/d6_04 apps/web/src contracts/fams-core-v1.yaml
```

实现比较范围较大（约 90 个文件，含锁文件和验证脚本），建议先按下表的责任边界审阅，再按需打开完整差异。文档更新可另看 `50bcf5f..HEAD`。

## 2. 必审领域

请每域给出 `PASS`、`NEEDS_FIX` 或 `QUESTION`。`PASS` 应基于实际阅读的文件、测试或运行证据；没有检查的条目保持 `QUESTION`，不能由本单已有结论推断通过。

| 领域 | 请核对的关键问题 | 主要入口 | 用户结论 |
|---|---|---|---|
| 需求与契约 | P01、首次改密、当前身份和权限、注销、账号/角色/范围/字段授权是否遵守 PRD 与 OpenAPI；是否把系统管理员错误地扩成资产业务管理员；是否加入未批准的审批或业务范围 | [任务卡](D6-04-task-card.md)、[PRD](../superpowers/specs/2026-09-03-fixed-asset-product-design.md)、[OpenAPI](../../contracts/fams-core-v1.yaml)、`AuthController`、`AccountAdminController` | `待填写` |
| 认证与权限 | 安全 Cookie、会话轮换、首次改密、注销、停用、重置密码、角色/授权变化后的旧会话拒绝是否在服务端逐请求生效；CSRF 对写请求是否强制；匿名、越权、错误密码是否稳定拒绝且不泄密 | `AuthRequestFilter`、`AuthSessionManager`、`AuthSessionValidator`、`AuthPolicy`、`AuthApplicationService`、`AuthHttpTest` | `待填写` |
| 账号配置与并发 | 固定角色不可动态扩权；一般数据范围与敏感字段授权是否独立；版本化引用失效、乐观版本 409、幂等重放/冲突和授权代际是否正确；数据库约束与事务是否与代码一致 | `AccountAdminService`、`AccountAdminMapper`、`AuthAccountMapper`、`V1__create_authentication.sql`、`AuthHttpTest` | `待填写` |
| 迁移、恢复与审计 | 迁移是否仅作用于任务自有产品库；已有库如果执行过旧 V1，是否明确要求另建增量迁移而非重放；应用/数据库重启后 Session 与数据是否恢复；安全事件是否避免密码、完整 Cookie/CSRF；测试资源是否精确清理 | [任务卡迁移边界](D6-04-task-card.md)、`V1__create_authentication.sql`、`AuthSessionRestartTest`、`run-d6-04-browser-runtime.py`、`verify-d6-03a.py` | `待填写` |
| PC 与质量 | 浏览器仅用真实表单、Cookie 通道和服务端权限快照，无 token 注入或本地持久化绕权；改密错误只显示一次；14 项 Web 测试、构建/lint/typecheck 与完整门禁是否覆盖关键正反路径 | `apps/web/src/auth/`、`apps/web/src/router.ts`、`apps/web/src/views/`、`apps/web/src/components/`、[任务卡证据](D6-04-task-card.md) | `待填写` |

## 3. 已有证据与不得外推的边界

- 固定实现 SHA `50bcf5f` 的干净隔离检出运行 D6-03A 完整入口退出 0：121 项阻断观察；32 个验证器测试、34 条提交格式、706 个跟踪文件敏感扫描、31 项 Java 测试、行 91.70% / 分支 78.52%、Checkstyle/SpotBugs 0、保留门禁/依赖/指标/应用与数据库恢复/精确清理通过。Web 14/14 测试、构建、lint、typecheck 另行退出 0。详见[任务卡检查点](D6-04-task-card.md)。
- 真实浏览器曾经通过 Vite 同源入口、真实 MySQL 和表单登录验证首次改密、刷新、注销、Cookie 属性与缺失 CSRF 403；另一轮双用户场景验证撤销角色后旧会话读取 401。账号配置经公开 API 执行，不是尚未实现的 PC 管理界面。
- 为避免测试口令泄露，原始 Playwright 快照已删除；只保留 Git 忽略目录内无凭证工作台截图。浏览器证据是任务记录和当时运行观察，不能据此声称有可复放的完整原始网络档案。改密错误重复显示修复有失败后转绿的组件用例，但没有单独重放该视觉浏览器场景。
- 产品迁移只在新建、任务自有 MySQL 库验证。现有共享库若已应用较早 V1，不得直接运行修改后的 V1；需要单独设计增量迁移和数据核对。本轮未执行共享库升级、远端 CI、QA、真机或生产验证。

## 4. 用户回复模板

审阅完成后，请在会话中回填实际审阅对象和逐域结论；有问题时写明文件/接口、风险、期望结果。`问题清单：无` 仅在确实逐域审阅且没有问题时填写。

```text
复核对象：<审阅时 rtk git rev-parse HEAD 的完整 SHA>
需求与契约：PASS / NEEDS_FIX / QUESTION
认证与权限：PASS / NEEDS_FIX / QUESTION
账号配置与并发：PASS / NEEDS_FIX / QUESTION
迁移、恢复与审计：PASS / NEEDS_FIX / QUESTION
PC 与质量：PASS / NEEDS_FIX / QUESTION
总评：PASS / NEEDS_FIX / QUESTION
问题清单：无，或逐条写明文件/接口、风险和期望结果
实际审阅证据：阅读的文件/差异、运行的命令及结果；未审阅项请说明
复核边界：仅 D6-04 本地实现与证据，不代表远端 CI、QA 或生产验收
```

收到结论后，实施者先逐条核对问题并修复或说明不采纳依据，再请用户确认受影响领域。只有用户明确签署 `PASS`、问题关闭，且控制器对最终 reviewed HEAD 再次完整运行门禁后，才可将 D6-04D 与父任务记为 `DEV_VERIFIED`。
