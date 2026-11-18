# 分支统一记录（2026-11-18）

用户要求把各分支全部合并 main 并清理多余分支。整合前所有已注册工作树的已跟踪及普通未跟踪状态均干净；忽略的依赖、数据库与运行证据留在原地，不删除工作树目录。

## 合并依据与取舍

以远端前端/功能集成线 `f204c8e` 快进 main，保留 ARCH-01～21 的 features 目录结构及其已吸收的 `05216ed` 功能线。使用保留全部父历史的多父合并归并四条剩余历史线，显式保留上述最新业务文件树，并补入归档文档、更新说明及本机 IDE 忽略规则。不是逐分支用旧快照覆盖最新实现，也没有 squash 丢弃提交身份。

- `750bb1f` 验证线：分叉增量共396个文件，其末端全部 blob 均可在 `f204c8e` 的可达历史中找到，验证候选不能覆盖后继实现。
- `ba1c056` 期初维修线：21个增量文件中15个 blob 已在集成历史；其余6个对应 `9bab5ee` 整合实现，四个双端维修文件的差异为增加内部维修转报失，原期初字段仍保留；契约旧路径及值全等，旧 schema 全等且后继增加 schema；计划差异是内部维修与联合验证接续状态。保留集成后的实现。
- `5965ed8` 根归档：35个增量文件中23个 blob 已在集成历史，包含全部业务实现及测试。其余为忽略规则、入口及历史文档。采用独有的归档说明与更准确的通用文档，保留更新的现役任务台账。D6-21新增结论已在现役任务卡，D6-24后继验证继续保留；原始全文仍由提交身份恢复。
- `20abf3d` 第四批末端：仅3个文档的用户复核记录，D6-02父项/C2/C3关闭及2026-11-14执行记录均已在现役计划和任务卡中，保留较新交接。
- 其余分支均是以上五个末端的祖先。删除前再次验证所有原始分支 tip 都是 main 的祖先；远端 main 推送核对成功后才删除分支。占用旧分支的工作树切换 detached HEAD，保留目录和本机忽略资产。

## 恢复与验证边界

整合前 refs、完整 Git bundle 和逐文件 blob 审计保存在仓库外 `/Users/lixd/.codex/backups/fams-branch-consolidation-20261118/`，bundle verify 通过。即使删除分支，其提交仍可从 main 父历史或该 bundle 恢复。备份未打包 Git 忽略的运行文件，这些文件保留原位。

业务文件与 `f204c8e` 完全一致，本次只合并历史、文档和忽略配置。Git祖先覆盖、静态检查及实际执行结果另见现役计划本次执行记录；没有将既有完整产品门禁失败改成通过，不代表新增独立复核、真实外部联调、正式QA或生产验收。

## 原始引用

| 引用 | 整合前完整提交 |
|---|---|
| `refs/heads/codex/d6-03b-ci` | `05216eda72d1dececeb63889836cc2532d7c830f` |
| `refs/heads/codex/d6-18c-inventory-loss` | `5ddccc7b6464ec14cb6e30dda9d896495bb323e1` |
| `refs/heads/codex/d6-20c1-form-recovery` | `9b2831a83e9a77a93d457333060bacfb73b1525a` |
| `refs/heads/codex/d6-20c2-replacement` | `af83d20705749869ac7e6ca27623a2571c309ee8` |
| `refs/heads/codex/d6-21-loss` | `5965ed89ed1aef8bae39aaf562dc654cdf76269a` |
| `refs/heads/codex/d6-21c-repair-loss` | `9b1a1308b67ecabbbc54531a3665da0cf2805881` |
| `refs/heads/codex/d6-21c-return-loss` | `86aefa5ac2657bf823a79dc4d941e9e60a89448c` |
| `refs/heads/codex/d6-22-second-batch` | `b3e6cc9e8392c14eb371c98e14fc66cf696b99b5` |
| `refs/heads/codex/d6-23-initial-repair` | `ba1c05622bfe429fbc70eb0e51a267f7e8abb0f8` |
| `refs/heads/codex/d6-fourth-batch` | `20abf3d1f15933f3521884d3c2f8b79e4d888446` |
| `refs/heads/codex/d6-stage6-report-validation` | `6560e2279665426d625bd3aa8824b9699865a3bc` |
| `refs/heads/codex/d6-stage6-transfer-validation` | `750bb1fbc632ac2402f6737a617aec6cb3f1d926` |
| `refs/heads/codex/d6-stage6-v78-validation` | `da1d90520a82a55175b98ee437539660b2c73432` |
| `refs/heads/codex/d6-stage6-v80-validation` | `f1ded395b208bf95c8780671721d2f18ba527642` |
| `refs/heads/codex/d6-stage6-v81-validation` | `690e7b059792c785abaad3a7123d0834b7029910` |
| `refs/heads/codex/d6-stage6-v82-validation` | `e6fceba2e2bf550cc8b22203a0a9b3fdc42be0d6` |
| `refs/heads/codex/d6-stage6-validation` | `32a40d1426899286044d28db0be84a8bfb243706` |
| `refs/heads/codex/d6-third-batch` | `10aa05e4069bd7430c72226894a2c547f3d34b3c` |
| `refs/heads/main` | `455ba00410cbef5b807769b3ba5c251a5ac93f8f` |
| `refs/remotes/origin/codex/d6-03b-ci` | `05216eda72d1dececeb63889836cc2532d7c830f` |
| `refs/remotes/origin/codex/d6-20-factory-ui` | `e0f404c88ba837429869e091ada4f54cb639a192` |
| `refs/remotes/origin/codex/d6-21-loss` | `5965ed89ed1aef8bae39aaf562dc654cdf76269a` |
| `refs/remotes/origin/codex/d6-21-loss-ui` | `f204c8e5c4d5a014304e6256380afcf214850016` |
| `refs/remotes/origin/codex/d6-21c-repair-loss` | `9b1a1308b67ecabbbc54531a3665da0cf2805881` |
| `refs/remotes/origin/codex/d6-fourth-batch` | `2b8a3ff0560489614d1ffd429398425ae17b5601` |
| `refs/remotes/origin/main` | `455ba00410cbef5b807769b3ba5c251a5ac93f8f` |

## AOCI 本机认知状态

本次仅收到完整 Overview 的第1/56块，未完成全量交付、未提交确认或 Attestation，不宣称完整系统认知已验证。稳定状态调用一次 Maintain 后返回 stopped/blocked：本机旧根索引存在70个旧路径 orphan、293个 missing 和139个 stale，未签发创作候选，要求显式处理旧路径移除或治理阻塞。没有未决写事务、Recovery 或第三方冲突。保留索引及待办，不猜写前端重构后的语义；该本机认知治理遗留不改变本次 Git 历史和业务树一致性验证。
