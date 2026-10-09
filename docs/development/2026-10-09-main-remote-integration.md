# 2026-10-09 main 整合与远端推送

用户本人确认 D6-14 本次复核全部通过，授权整合当前已完成成果到 main，绑定指定远端并推送。

## 整合事实

- 原 main：`df8cb6d7c2576396cb7e0de8a4f029d060732b9a`。
- 已复核开发成果末端：`a7521575bc3c15b11b58c972a2f01bffcee3386a`；当前 root main 从原 main 快进到该提交，无冲突，无业务树重写。
- 12 个工作树均干净；17 个本地分支 HEAD 全部是该候选祖先，保留旧分支和工作树。
- 包含 D6-10A、D6-11、D6-12A、D6-13、D6-14 等已完成内部能力；外部验收未完成的父任务保持原边界。
- D6-14 用户复核关闭：固定业务候选 `f983439`、证据 `2c6c9ac`、认知治理 `a752157`，父任务及 A/B/C/D 本地 DEV_VERIFIED。

## 分支纳入清单

| 分支 | 原 HEAD | 已纳入 |
|---|---|---|
| codex/backup-d6-02c-pre-squash-20260914 | `60770c036b8c` | 是 |
| codex/d6-05-master-data | `351a7e9441fd` | 是 |
| codex/d6-06-asset-ledger | `2b50d83b6737` | 是 |
| codex/d6-07-inbound | `5314414e8787` | 是 |
| codex/d6-08-durable-jobs | `b007a5fc8d91` | 是 |
| codex/d6-09-attachments | `4b0f173486c9` | 是 |
| codex/d6-10a-session | `00c795c03431` | 是 |
| codex/d6-11-requisition | `701da4d2546d` | 是 |
| codex/d6-12-handover | `de338a0ad8be` | 是 |
| codex/d6-13-borrowing | `4aa548b9b68e` | 是 |
| codex/d6-14-transfer | `a7521575bc3c` | 是 |
| feat/D6-02A-contract-generation | `c9069f10a5a5` | 是 |
| feat/D6-02B-runtime-compatibility | `9a181cdd5924` | 是 |
| feat/D6-02C-first-design-package | `5676bd9823e2` | 是 |
| feat/D6-03A-local-quality-gate | `5f4bc8ae842d` | 是 |
| feat/D6-04-pc-login-permissions | `dbb1057026b9` | 是 |
| main | `df8cb6d7c257` | 是 |

## 工作树清单

| 路径 | HEAD | 未提交变化 |
|---|---|---|
| `.` | `a7521575bc3c` | 无 |
| `<local-agent-home>/worktrees/d6-04a-verify/fams` | `dbb1057026b9` | 无 |
| `<local-agent-home>/worktrees/d6-05-master-data/fams` | `351a7e9441fd` | 无 |
| `<local-agent-home>/worktrees/d6-06-asset-ledger/fams` | `2b50d83b6737` | 无 |
| `<local-agent-home>/worktrees/d6-07-inbound/fams` | `5314414e8787` | 无 |
| `<local-agent-home>/worktrees/d6-09-attachments/fams` | `b007a5fc8d91` | 无 |
| `<local-agent-home>/worktrees/d6-13-gate/fams` | `f9834399d587` | 无 |
| `.worktrees/d6-02a-generation-pipeline` | `c9069f10a5a5` | 无 |
| `.worktrees/d6-02b-runtime-compatibility` | `9a181cdd5924` | 无 |
| `.worktrees/d6-02c-first-design-package` | `5676bd9823e2` | 无 |
| `.worktrees/d6-03a-local-quality-gate` | `5f4bc8ae842d` | 无 |
| `.worktrees/d6-04-pc-login-permissions` | `dbb1057026b9` | 无 |

## 远端与验证边界

- origin：`https://github.com/LiXuD/fams-manage.git`。绑定前无远端；实际 ls-remote 与 fetch 确认远端无分支。GitHub API 确认当前仓库公开，具有 admin/maintain/push 权限。
- 按用户指定目标推送 main，设置 origin/main 为 upstream；推送后用 ls-remote、fetch 和 rev-parse 核对远端与本地一致，不使用 force、不推送旧功能分支。最终提交身份以远端 main 为准。
- 远端建立及推送不等于 D6-01B/03B 已验收。计划原私有仓库目标与当前公开状态分别记录，协作设置、工作流、分支保护及 required-ci 尚待配置和验证；当前树没有 .github 工作流。
- main 快进保持已验证业务树：f983439 完整产品门禁退出0，Java137、Checkstyle/SpotBugs0、行92.41%/分支75.13%；PC22/小程序30、真实调拨浏览器和同库硬重启通过。此次只改验收与整合文档，不重复无变化业务测试。
- AOCI 在当前受管理文档最终稳定后维护；完整索引不因压缩重复加载。治理对齐和完整模型认知是不同事实。
- 未建立远端CI通过、正式QA、生产或真实短信/微信/HTTPS/真机通过证据；下一内部开发项为D6-15。

## 恢复材料

- 整合前清单及全分支 bundle：`<local-agent-home>/integration-backups/fams-20261009/`，仅本机保存。
- 功能提交历史全部保留；原 main df8cb6d 与候选 a752157 可用于核对或创建恢复分支；不重写已推送历史。
