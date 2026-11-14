# 夜间错峰执行 Runbook（2026-11-10 22:00 起，P3 功能闭环 + 按需门禁取证）

本文件是夜间执行的唯一自包含说明。执行者是一个全新会话，除本文件与仓库现状外没有上下文。白天用户已就四项中断点作出裁决（§1），因此本计划设计为**不需要中途提问**。

## 0. 开工顺序

1. 读 `AGENTS.md`、`DESIGN-HANDOFF.md`、`docs/superpowers/plans/2026-09-12-fams-stage6-development-plan.md` 的 §四.5 台账与 §六.3 末尾记录、`docs/development/D6-24-task-card.md`。
2. `git status --short --branch` 与 `git log --oneline -3` 确认起点。2026-11-10 白天的起点是 `5ef6eb7`（D6-24C1 值班配置底座），分支 `codex/d6-21-loss` ahead 6 未推送。
3. 若发现工作树不干净或有非本会话产生的改动，停止写入并在 `output/overnight-20261110.md` 记录，不得覆盖他人改动。

## 1. 用户预授权（2026-11-10 白天裁决，逐条生效）

1. **范围冲突处理**：默认只做功能闭环；当某任务的收口条件确实需要「完整 D6-03A 产品门禁」且被 D6-02C 写分类 RED 卡住时，允许**按需插入 D6-02C-C2 的分域取证**，顺序为 Loss 52（六项边界全缺）→ Inventory 47（缺 `x-error-codes`）+ 11（未分类）→ Requisition 24（未分类）→ AssetImport 8（未分类 + `uploadAssetImportBatch` 缺 `x-version`）→ 杂项 10。每域独立提交，必须逐条读控制器与服务的真实异常路径取证，**禁止凭空填写** `x-actor/x-version/x-transaction/x-audit-event/x-error-codes`。
2. **远端共享状态**：整晚**只本地提交**，不 push、不开 PR、不改仓库设置、CI 配置或分支保护。
3. **实际 PC 取证**：允许用 headless CDP（既有 `scripts/run-d6-*-browser-runtime.py` 入口或 `fams-layout-cdp-verify` 的三口径量测）跑真实页面，日志与截图留到 `output/` 或 `docs/development/evidence/`。真机小程序、真实短信、微信模板一律不做（属 D6-09B/10B/12B，按用户安排后置）。
4. **口径分歧**：按 §4 的默认口径继续，并把每个决策点写入对应任务卡与 §六.3 记录的「待复核决策点」小节；不得静默改写 PRD 或计划要求。
5. **独立复核**：P2 各送审项（D6-21B2b1/b2/b3、D6-24A/B1/B2/C1、D6-23A）由用户本人签署，执行者不得代签，相关任务保持 `IN_REVIEW`/`IN_PROGRESS`。

## 2. 执行规约（每一片都必须满足，缺一不可）

- 所有 shell 命令加 `rtk` 前缀。先将 `FAMS_JAVA21_HOME` 指向 Oracle GraalVM 21.0.7 的 JDK 根目录。后端固定 `rtk env JAVA_HOME="$FAMS_JAVA21_HOME" ./mvnw -o ...`；前端固定 `rtk env PATH="$HOME/.nvm/versions/node/v24.18.0/bin:$PATH" npm ...`（`rtk` 默认 Node 16 会破坏 vue-tsc/vitest）。
- Flyway 迁移只前向追加，编号以执行时仓库内最大 V 号顺延（白天为 V67）；已执行脚本不得编辑、不得回填历史。
- 每片自包含闭环，按此顺序：实现 → 契约（新 operation 必须带 `x-business-write` 与六项边界声明、PC 写必带 `CsrfToken`+`IdempotencyKey` 组件引用、mini 写声明 `x-non-applicable: [csrf]` 与 `x-exception-rationale`，标准头一律用组件引用）→ 双端接入（如涉及）→ 定向运行时用例（自有隔离 `mysql:8.4.11`，每片独立 schema，`--default-time-zone=+00:00`，就绪用带凭证 `SELECT 1` 判定，结束 `docker rm -f -v`，并在 `scripts/verify-d6-03a.py` 登记独立库、授权、`D6_24X_*` 环境变量与用例条目）→ Checkstyle `-Pquality validate` 与 `spotbugs:check` 零报告 → 架构 `FamsArchitectureTest,FamsArchitectureRulesTest` → 前端四道（`npm run lint`/`typecheck`/`test:web`/`build:web`）→ 文档三处（任务卡、计划 §四.5 行 + §六.3 记录、`DESIGN-HANDOFF.md`）→ 一次提交。
- 提交信息按计划 §四.4：`<type>(<scope>): <summary>`，空行后背景/动机与主要变化正文，空行后各占一行 `Agent-Task`、`Agent-Model`（写「无法核实」）、`Agent-Decision`、`Agent-Limitation`。
- 诚实口径：局部验证不得写成完整门禁、远端 CI、QA 或生产可用；`scripts/verify-d6-02c.py::assert_write_contracts` 在未插入取证时仍 RED（白天剩 152 条违例），照实登记，不得为转绿而放宽门禁。
- 停手条件：判断剩余时间不足以把当前片做到"自包含提交 + 文档登记"时，**不开新片**，转做 §5 收尾。禁止留下编译不过、迁移半截或测试未跑的提交。

## 3. 任务顺序（优先级 = 依赖 × 门禁杠杆 × 单元风险）

| 序号 | 任务 | 范围要点 | 收口证据 |
|---|---|---|---|
| T1 | D6-24C2 阈值消费与持续未处理升级 | 首次消费 `notificationEscalationThresholds` 四阈值：未读满阈值按 `fams_message_receipt` 判定并升级值班人→替补→其余有效管理员；超时再满 1 个工作日仍无 §4 三类事实时向全体有效管理员生成同一异常强提醒，已读也触发、同一异常每日最多一次；V68 用新迁移扩展 `reminder_type` CHECK 档位 | `DutyEscalationRuntimeCase` 固定时钟断言各档触发/抑制/每日一次/已读仍触发；档位推进不影响 V65 既有档位 |
| T2 | D6-24C3 配置异常落地 | 新增 `fams_configuration_anomaly`（`dedupKey` 唯一、OPEN/RESOLVED、`detectedAt/resolvedAt`），接通契约里已有的 `listConfigurationAnomalies`/`getConfigurationAnomaly` 两个只读端点与 PC 显示；只在无有效管理员/库管、值班与替补均失效时报警，系统管理员不得因此取得审批权 | 运行时用例断言去重、恢复后 RESOLVED、系统管理员可见不可办 |
| T3 | D6-24C4 停用转派与角色恢复 | 成员停用或登记无法继续时生成去重的"需要转派"待办（复用 `fams_todo` 活跃唯一键），仅超时推断时在超时检测后 5 分钟内生成；用回归证明角色重新授予后原待办与异常按原申请/版本/时限/超时历史即时可见，不重建单、不重置时限、不自动批准 | 运行时用例 + 消息中心可见性断言；不新增调度器 |
| T4 | D6-24D 内部兜底与观测 | 通知发送适配器经既有 `MiniSmsPort` 扩展，保存接收人/渠道/模板/请求时间/结果/读取时间，失败可重试且未触达不回滚业务；待办数、最老待办年龄、重试次数、处理延迟接入既有受限指标入口；崩溃恢复、租约争抢、旧代次写回被拒、重复投递去重的证据留存。真实短信与微信核验不做 | 自有 MySQL 运行时用例 + `scripts/verify-d6-03a.py` 受限指标入口观察 |
| T5 | D6-21B2b5b 报废后找回补齐 | CDP 实际 PC 跑找回登记→批准→认领→重新纳管；提交 JVM 重启恢复；**此处若需要完整 D6-03A 门禁，触发 §1.1 的按需取证** | 日志与截图入 `output/`；台账状态仍按"完整门禁 + 独立复核"如实标注 |
| T6 | D6-22 跨来源冲正 + D6-22E 内部联合验证 | 把仅支持独立报废的冲正扩展到维修/返厂/遗失来源，沿用 V62 活动冲正闸门与"原单逐字段不改写"原则；D6-22E 只收内部部分 | 跨来源冲正运行时用例 + 既有报废套件复跑 |
| T7 | D6-23B/C 维修与返厂状态期初承接 | 沿用 V64 的期初批次与 `(批次,行号)` 唯一键模式，不回填历史；发布不等于批准 | 期初 HTTP 全链路 + 既有期初套件复跑 |
| T8 | D6-18A/B 收口 | 完整 PC 页面（CDP）与损坏/盘亏真实后续、封存迟到不改写报告/计数/最近盘点日期 | CDP 日志 + 盘点既有套件复跑 |
| T9 | D6-18C 盘亏转报失闭环 | 盘亏→报失及后续结束/拒绝推进，跨任务 AC 全链路 | 新增跨域链路运行时用例 |
| T10 | D6-20D 返厂真实异常结束与恢复 | 收旧转报废、无法收回异常结束与恢复 | 返厂套件 + 恢复证据 |
| T11 | D6-21C 跨业务转换 + D6-21D 联合验证 | 归还/盘亏/维修报失、拒绝恢复、找回真实后续与原子转锁；联合验证只收内部部分 | 联合链路证据 |
| T12 | 若仍有余量 | D6-02C-C3 的 30 个绝对 `/api/v1/...` 路径键规范化；报废端点非 UUID `Idempotency-Key` 返回 500 → 统一 400 | 契约与生成物整批复跑 |

吞吐预期：每片实际 1～2.5 小时，一晚现实可完成 T1～T6 上界，T7 以后按序顺延。**宁可少做一片，不可留半片。**

## 4. 已定默认口径（避免中途提问，全部登记为"待复核"）

- 「带下一跟进时间的核查记录」等价物：`fams_loss_report.next_review_date`/`last_follow_up_at` 与 `fams_return_followup`；其他域若无等价字段，缺该记录即视为"仍在升级"并继续提醒，**不新增业务列**（若确需新增，登记待复核并另开片）。
- 「有效转派」= `fams_todo.assignee_account_id` 被改写且有对应审计；「业务处理结果」= 该域事件表新增一行或状态推进。
- 「同一异常每日最多一次」= 复用 V65 唯一键里的 `notice_date` 与 `recipient_slot`，不新建去重表。
- 「5 分钟」= 既有分钟级调度器周期（`BusinessReminderScheduler` 等），不新增调度器。
- 配置异常今晚只实现可真实判定的 `NO_ASSET_ADMIN`/`NO_WAREHOUSE_OPERATOR`（含值班链失效转告语义）；`SMS_CHANNEL_MISSING`/`WECHAT_TEMPLATE_MISSING` 等 D6-09B 有真实通道读取方后再触发。
- 跨来源冲正一律以"父单行锁 + 处置状态守卫"串联，已实际处置、遗失核销与厂家销毁继续拒绝冲正，与 D6-22D 已复核口径一致。

## 5. 收尾（无论完成到哪一步都要做）

1. 台账：完成片置 `DEV_VERIFIED` 并写证据数字；未开始的片保持 `NOT_STARTED`，被时间截断的保持 `IN_PROGRESS`。
2. `DESIGN-HANDOFF.md` 更新日期行与"下一步"，明确未完成项与被 §1.1 插入的取证片。
3. 写 `output/overnight-20261110.md`：提交清单（SHA + 标题）、每片证据数字（测试项数、覆盖率如有、静态零报告）、待复核决策点清单、仍 RED 的门禁与原因、留给早上的动作（P2 复核签署、真机与真实通道、push/PR/CI）。
4. `git status --short --branch` 与 `git diff --check` 必须干净；清理自有容器与临时脚本，不删他人文件。
5. 全程不推送。
