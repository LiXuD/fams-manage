# 2026-10-04 主线整合交接

统一基准：`codex/d6-08-durable-jobs` 交接 `b007a5f`，产品代码候选 `6d94db8`。用户本人本轮明确“复核全部 pass”，授权整合全部工作树、分支成果到根目录 `main`。D6-08A/B/C/D 及父任务为本地 `DEV_VERIFIED`，第六阶段仍 `IN_PROGRESS`。

## 整合方式与成果

- `e1498d4` 保留全部原功能提交，将 `b007a5f` 合并到 main；产品文件树与该交接完全一致。
- `6290f63` 将预 squash 备份历史纳入 main：`60770c0^{tree}` 与已包含的 `69e81bf^{tree}` 同为 `5ebd625c44d0927c8a4ae601b5760eafe0f7e228`。使用 ours 保留后续复核版本，不复活旧设计和旧状态。
- 其余 10 个分支（包括旧 main）均已是 `b007a5f` 祖先，不需要重复合并或 cherry-pick。
- 11 个工作树逐一检查：9 个隔离工作树干净；D6-04 的未提交追溯 CSV 与其 HEAD 解析后的 54 行完全一致，仅引号/行尾格式不同，保留补丁后恢复；根目录本地治理资产保存后纳入统一工作区。
- 保留原分支和工作树作为恢复入口；后续开发以根目录 main 为基线，不从旧工作树的过期台账恢复任务。

## 原分支清单

| 分支 | 整合前 HEAD | 处置 |
|---|---|---|
| `codex/backup-d6-02c-pre-squash-20260914` | `60770c0` | 等价树证明后纳入历史，保持最新内容 |
| `codex/d6-05-master-data` | `351a7e9` | 已纳入统一 main 的祖先链 |
| `codex/d6-06-asset-ledger` | `2b50d83` | 已纳入统一 main 的祖先链 |
| `codex/d6-07-inbound` | `5314414` | 已纳入统一 main 的祖先链 |
| `codex/d6-08-durable-jobs` | `b007a5f` | 已纳入统一 main 的祖先链 |
| `codex/d6-09-attachments` | `4b0f173` | 已纳入统一 main 的祖先链 |
| `feat/D6-02A-contract-generation` | `c9069f1` | 已纳入统一 main 的祖先链 |
| `feat/D6-02B-runtime-compatibility` | `9a181cd` | 已纳入统一 main 的祖先链 |
| `feat/D6-02C-first-design-package` | `5676bd9` | 已纳入统一 main 的祖先链 |
| `feat/D6-03A-local-quality-gate` | `5f4bc8a` | 已纳入统一 main 的祖先链 |
| `feat/D6-04-pc-login-permissions` | `dbb1057` | 已纳入统一 main 的祖先链 |
| `main` | `8b148e3` | 已纳入统一 main 的祖先链 |

## 本地资产与恢复

整合前完整工作树/分支清单、根目录本地文件归档、AGENTS 原文、根目录补丁、D6-04 CSV 补丁与合并消息保存在 `/Users/lixd/.codex/integration-backups/fams-20261004/`。根目录 `.gitignore` 的 IDE 与机器配置排除、AOCI/GitNexus 规则、`.gitattributes` 与 `.java-version` 保留；机器运行日志不提交。根目录意外字符串 `7 -0` 不纳入有效忽略规则，其原文保存在归档。

## 验证与证据边界

- 代码候选 `6d94db8` 的[原始产品门禁](evidence/D6-08-product-gate-6d94db8.txt)退出 0：59 项必需 Java 测试、Checkstyle/SpotBugs 0、行 91.99%、分支 75.03%；Web lint/typecheck、依赖及应用/MySQL 重启恢复通过。
- 本轮不修改产品符号、契约、依赖或迁移；验证产品路径与 `b007a5f` 完全一致。GitNexus 已从旧 `8b148e3` 重建索引；初次变更检测仅覆盖旧图，不能用其低风险结论代表全部产品代码。
- 合并提交消息经同一提交校验器及本地 hook 校验；所有现有分支 HEAD 必须可从最终 main 到达，全部工作树最终状态另行核对。
- AOCI 0.1.0-rc12 首次收尾返回 `stopped/blocked`、`aligned=false`、空候选批次：480 个新增对象缺失，5 个已有对象漂移，11 个观察项待确认；无未决事务、Recovery 或第三方冲突，正式索引/基线未写入。旧索引及正式治理资产作为可恢复资产保留，新增产品对象尚未对齐；不猜写语义或缩小覆盖范围。
- main 固定候选 `3b901ff` 在安装现有锁文件依赖后，[完整产品门禁](evidence/main-product-gate-3b901ff.txt)退出 0：59 项必需 Java 测试，Checkstyle/SpotBugs 0，行 5879/6391（91.99%）、分支 2176/2900（75.03%）；Web lint/typecheck、依赖检查、受限指标、应用及 MySQL 重启恢复通过，Compose 资源清理和干净工作树后检通过。最终交接只追加文档/证据，不改产品代码。
- 初次启动因提交消息正文超长被 hook 拒绝并导致门禁干净预检拒绝；修正正文后提交通过。随后根目录缺少前端依赖导致 lint 退出 127；现有锁文件 `npm ci` 退出 0、审计 0 漏洞后重跑上述完整门禁通过。失败原始记录保存在外部恢复目录，不覆盖失败事实。
- `aoci scope status` 进一步确认 `observed_evidence_review_required`，另有 `requires_human_review=3`；本轮保留该真实治理停点，不绕过审批、不猜写认知。正式索引对齐待单独按当前 Guide 处理。

## 下一步

从根目录 main 继续现役计划：准备 D6-09B 实际外部适配资源，或按顺序推进 D6-10。D6-01B/03B 仍待远端权限；远端 CI、第七阶段 QA、共享/生产迁移及上线尚未完成。D6-08D 测量仅为开发冒烟。
