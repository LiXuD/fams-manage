# D6-02A task card — 生成流水线

## 任务身份与目标

- **ID：** D6-02A 生成流水线。
- **目标：** 以一个 OpenAPI 3.0.4 代表契约证明 Java/Spring 接口与共享 TypeScript 模型可重复生成、可编译，并由最小 Spring 应用通过真实 socket HTTP 实现生成接口。
- **状态：** 实现交接为 `IN_REVIEW`（2026-09-13）；独立复核完成前不得改为 `DEV_VERIFIED`。
- **分支 / 基线：** `feat/D6-02A-contract-generation`；`8b148e3ab4117c3bee3cfb2605cfb0feeede4b22`。
- **前置条件：** D6-01A 本地基线、提交校验和文件分类已完成；本任务 brief、工作区规则、设计交接、TDD 规范及测试编写规范已读取。验证环境需提供 Java 21、Node 24.18.0、npm 11.16.0；Maven Wrapper 3.3.4 固定 Maven 3.9.16。
- **规模：** M（一个代表契约，跨 `contracts/`、`backend/`、`packages/api-types/`、验证脚本和证据文档；不含产品业务实现）。
- **实施负责人：** AI，当前 worker 使用 `gpt-5.6-luna`。
- **复核人：** 用户负责最终验收；独立技术复核人/方式由控制器安排，不能用实施者自审替代；当前仍待独立复核。

## PRD、页面和 AC 边界

- **PRD / 页面 / AC：** PRD V1.5、IA D0.2 的 `F01～F10`、`P01～P26`、`M01～M14` 与 `AC-01～AC-53` 仅作为后续业务范围的背景追溯；本任务不实现或关闭任何 PRD AC，也不新增页面或 UI。
- **业务边界：** 探针只验证契约字段、序列化、校验及错误外形；不代表资产、入库、盘点、审批或小程序业务已实现。
- **明确不在本任务：** D6-02B 运行兼容性冻结、D6-02C 首批真实设计包、真实 MySQL/Flyway/Session、远端/CI、PC/小程序应用及全部 FAMS 产品业务功能。

## 候选输入与目录边界

| 输入 | 固定候选 |
|---|---|
| Java | 21（本地验证使用 21.0.7） |
| Spring Boot | 4.1.1 |
| OpenAPI Generator | 7.25.0 |
| OpenAPI | 3.0.4 |
| Node / npm | 24.18.0 / 11.16.0 |
| TypeScript | 5.9.2，锁定于 `package-lock.json` |
| Maven Wrapper / distribution | 3.3.4 / 3.9.16 |

这些只是 D6-02A 候选组合；D6-02B 才负责最终兼容性冻结。

- **权威输入：** `contracts/representative-probe.yaml`，不得手改生成输出。
- **Java 目录：** 生成接口/模型位于 `backend/target/generated-sources/openapi/java`；手写探针应用及测试均归属 `backend/`。
- **TypeScript 目录：** 共享包归属 `packages/api-types/`；生成模型位于 `packages/api-types/target/generated-sources/openapi/typescript`，导出入口为 `packages/api-types/src/index.ts`。根 `package.json` 仅编排 workspace。
- **验证入口：** `scripts/verify-d6-02a.py`；版本化证据见 `docs/development/D6-02A-generation-pipeline.md`，本地完整报告见任务 report。

## 接口、事务、权限与数据影响

- **消费/新增接口：** 消费唯一 OpenAPI 文件及 Maven/Node 工具入口；新增仅为生成的 `ContractProbeApi`、`ContractProbe`、`ErrorResponse` 和共享 TypeScript 模型类型。没有业务 API 或外部服务接口。
- **事务与权限：** 不适用（N/A）：探针没有持久化、认证、授权、事务边界或跨用户状态；真实 HTTP 只在本地随机端口接受/拒绝 JSON。
- **数据影响：** 不适用（N/A）：不创建或修改数据库、迁移、文件业务数据、账号或外部资源；`backend/target/` 与 `packages/api-types/target/` 只是被忽略的可重建构建输出。
- **回滚方法：** 保留用户现有历史；如需回滚，将生成输出目录删除后回退本任务对应提交，或按 SHA 逐个恢复 `backend/`、`packages/api-types/`、`contracts/`、脚本和证据文档。没有数据回滚、远端回滚或迁移回滚动作。

## 正向与拒绝路径

1. 固定版本从权威契约清空生成 Java 接口/模型与纯 TypeScript 模型；连续两轮的全树 SHA-256 manifest 必须无差异。
2. 生成前若已有输出，先与本轮权威生成树比较；Java 内容手改、TypeScript 内容手改、TypeScript 陈旧额外文件均必须被拒绝；无预存树必须被接受。
3. TypeScript 使用版本化最小模板和 `generateSupportingFiles=false`，只输出模型/枚举，不输出或导出 `runtime.ts`、`*Api.ts`、Fetch/API transport；`tsconfig` 用 `lib: ["ES2022"]` 在无 DOM 库下类型检查。
4. 真实随机端口 HTTP 正向路径保持大整数 ID、日期、UTC `Z`、枚举、零值、保留小数尾零、数组顺序及 nullable 字段语义；拒绝缺失必填、null quantity、数字 ID、非法枚举和非法日期/日期时间，并返回 `code`、`message`、`requestId`、`fieldErrors`。

## 验证与证据

- **TDD：** 先写真实 socket HTTP 测试并以缺路由 `404` 得到 RED，再写最小控制器、JSON 配置和错误处理得到 GREEN；生成配置与模板是验证输入，不替代手写行为测试。
- **验证命令及预期：** `rtk run 'python3 scripts/verify-d6-02a.py'`（调用方提供固定 Java/Node 环境）返回 0；应报告 OpenAPI 验证、两次清空生成、前置输出等价/拒绝夹具、Java 编译、Node 锁定安装、`ES2022` 无 DOM TypeScript 检查、9 个真实 HTTP 用例、commit-message suite、`git diff --check` 全部通过。
- **证据位置：** `docs/development/D6-02A-generation-pipeline.md`（可移植命令和摘要）；任务 report（本地完整命令、退出码、原始重点输出和限制，按忽略规则不跟踪）。
- **交接：** 当前保持 `IN_REVIEW`；独立复核通过后下一实际任务为 D6-02B，随后按计划进入 D6-02C。D6-02B/C 不因本探针证据提前关闭。

## 风险与限制

- **风险编号：** `R-01`（候选版本与生成兼容性，D6-02B 才冻结）、`R-02`（跨边界交付规模与返工）；`R-04`（远端/CI 能力未准备）及 `R-05`（独立技术复核待安排）沿用计划台账。
- **复核轮次风险：** 本轮修复关闭了生成清空/漂移检测、TypeScript transport 泄漏、HTTP 类型/字段/数组断言、文档边界等审查项；它们仍需独立复核确认，不能视为人工批准。
- **当前限制：** 无远端 PR、CI、数据库、迁移、会话、客户端、生产运行态或完整业务契约；生成器 informational post-processing、生成 Java deprecated API、Spring 测试依赖动态 agent 等警告均已记录于证据和 report。
