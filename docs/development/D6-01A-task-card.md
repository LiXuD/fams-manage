# D6-01A 本地基线任务卡

## 任务身份与目标

- **ID：** D6-01A 本地基线
- **目标：** 将现有文档/静态原型工作区安全初始化为 `main` 本地单仓，建立文件分类、53 项开发追溯、可重复的提交消息本地反馈和恢复说明；不引入产品代码或远端能力。
- **PRD / 页面 / AC：** PRD V1.5 §19、IA D0.2 F01～F10/P01～P26/M01～M14；AC-01～AC-53 只建立责任映射，不在本任务关闭业务验收。
- **前置条件：** 已收到 D6-01A 启动指令；已读取 `AGENTS.md`、`DESIGN-HANDOFF.md`、开发计划 V1.1、PRD V1.5、IA D0.2、D0.4 交接和第五阶段覆盖清单；当前目录已有本地 Git `main`，无远端。
- **规模：** M（本地治理闭环，跨文档、Git 和可执行校验，但不含业务实现）。
- **实施负责人：** AI（本任务 worker：`gpt-5.6-luna`）。
- **复核人：** 用户负责验收；关键技术独立复核人/方式尚待用户指定，未以实施者自审代替。

## 目录、接口与边界

- **涉及目录/文件：** `.gitignore`、`.githooks/`、`tools/git/`、`scripts/`、`docs/development/`、`DESIGN-HANDOFF.md`、`docs/superpowers/plans/2026-09-12-fams-stage6-development-plan.md`；Git 本地配置只写 `.git/config`。
- **消费的现有接口/资料：** PRD §19 的 AC-01～53、IA §9 的 F/P/M 映射、第五阶段覆盖 CSV、D6 §四.5 任务/子任务表和 §四.4 提交规则。
- **新增接口/产物：** `tools/git/validate-commit-msg.py <message-file>`；`.githooks/commit-msg`；`scripts/verify-d6-01a.py`；`docs/development/traceability-matrix.csv`；文件分类清单和本任务卡。
- **事务/权限边界：** 不访问产品数据库、不创建业务事务、不修改外部服务；hook 仅提供本地提交前反馈，不能声称 CI 或远端分支保护；Git author identity 继承现有用户配置，不改全局配置。

## 正向与拒绝路径

1. 合规标题、非空正文及按序四个 Agent trailers 由 pinned `@commitlint/cli@19.8.1` 加补充校验通过；hook 允许提交。
2. 缺 scope、非法 type、空/缺正文、缺失/重复/空/乱序/同一行 trailers、未解析占位符，以及 `Merge branch 'topic'`、`fixup! ...`、`v1.2.3` 等默认忽略标题必须返回非零；hook 拒绝提交。
3. 高风险目录/日志/操作流水、敏感配置和大于等于 100 MiB 的候选保持本地不跟踪；验证入口发现已跟踪违规即非零。
4. 矩阵缺行、重复/断号、无 D6 责任任务、伪造 operation/test/evidence 状态即非零；未知值使用 `NOT_DEFINED`/`NOT_RUN`。

## 验证与证据

- **验证命令及预期：** `rtk python3 tools/git/test_commit_message_validator.py` 返回 0 且 7 个行为测试通过；`rtk scripts/verify-d6-01a.py` 返回 0，报告 `main`、无 remote、hook 路径正确、危险候选已忽略、53 行矩阵连续且均有 D6 责任、敏感/超大跟踪文件为 0；hook 正负样例分别返回 0/非零。
- **证据位置：** `.superpowers/sdd/2026-09-12-fams-stage6-development-plan/task-d6-01a-report.md`（本机任务报告，按 `.gitignore` 保留不跟踪）；脚本输出和提交 SHA 在该报告及计划 §六.3 记录；版本化基线在 `docs/development/`。
- **数据影响：** 仅新增 Git 元数据与文档/校验脚本；不写产品数据、不创建数据库、不上传外部系统。
- **回滚方法：** 在确认用户决定后，可用 `rtk git config --local --unset core.hooksPath` 取消本地 hook 配置，保留已提交文档；本地 Git 历史可按 SHA 恢复。不得删除既有原型、证据或参考文件；远端尚未配置，无远端回滚动作。

## 风险与限制

- **风险编号：** R-04（远端仓库/权限/CI 尚未准备）、R-05（后续需独立技术复核）。
- **当前限制：** D6-01B 私有 GitHub 远端、保护规则和 CI 尚未验证；commitlint 依赖需按 `tools/git/package-lock.json` 执行 `rtk npm ci --prefix tools/git --ignore-scripts` 后可用；D6-01A 不证明产品编译、接口、运行态、真实微信/短信/设备或生产验收。
- **任务状态：** `DEV_VERIFIED`（本轮修复/复核本地验证已通过；提交 `338d49d`，前序基线提交见报告）；D6-01 父任务保持未关闭，直到 D6-01B 具备实际远端证据。
