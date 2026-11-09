# D6-24 通知与异常跟进任务卡

执行依据为 PRD V1.5 §13.1～13.4（通道边界、事件与通道、默认时限与升级、订阅提醒入口）、§9.5（共享池跟进责任、持续未处理升级、管理员角色恢复）与 §10（企业时区、工作日历、处理时限与升级阈值参数），页面按 IA D0.2 的消息中心、首页强提醒与管理员/库管异常清单接续。状态明细维护在第六阶段开发计划 §四.5 的 D6-24A～D；A 为后续三片的底座。

## 拆分依据（2026-11-09 调研）

- 参数底座已在 D6-05B 落地：业务参数快照固定九键，含 `timezone、workWeek、holidays、adjustedWorkdays、adminDecisionDeadlineWorkdays、warehouseClaimDeadlineWorkdays、warehouseFirstExecutionDeadlineWorkdays、handoverAcceptanceDeadlineNaturalDays、notificationEscalationThresholds`。前四键与时限键只有入库域真正使用（三段 `*_due_at`），`notificationEscalationThresholds` 在全仓无任何业务读取方，即 §13.3/§9.5 的升级规则尚未产生业务效果。
- 时限不必逐域加列：资产、入库、领用、借用、退还、调拨、盘点各对象在创建时已冻结 `parameter_snapshot_json`（V4/V5/V19/V22/V24/V26/V31），"仅对新对象生效、历史保留"可由快照推导实现。
- 提醒已有可复用范式：借用提醒扫描（V25 `fams_loan_reminder` 按借据、应还日、业务日与档位唯一去重 + `ON DUPLICATE KEY UPDATE id=id`，档位变化关闭旧待办）是 B 片泛化的模板；`fams_todo` 已有活跃唯一约束但无读取端点。
- 通道边界已具备端口：短信只有 `MiniSmsPort`（无实现即 503），微信订阅消息与模板 ID 全仓不存在；D 片按端口、发送记录与降级实现，真实触达仍属 D6-09B/10B。

## D6-24A 企业日历与时限底座

`com.fams.masterdata.BusinessCalendar` 提供共享的 `isWorkday(snapshot, date)` 与 `workdayDeadline(snapshot, zone, moment, workdays)`：补班日优先于节假日与周历；否则属 `workWeek` 且不在 `holidays`；截止时刻取第 n 个后续工作日次日的 00:00（按传入的企业时区），与入库原私有算法逐位等价。入库三段时限改为调用该底座，删除 `InboundService` 内重复的日历循环与两个集合判定私有方法；参数版本行缺失仍按原 `INBOUND_PARAMETERS_UNAVAILABLE` 冲突返回。

本片刻意不改变已复核行为：领用交接的接收截止用瞬时加自然日、调拨用当日对齐两种既有口径保持原样，其统一随 B 片的提醒判定一并处理，避免在底座片中改写 D6-12/D6-14 已验收事实。

## 验证

- `BusinessCalendarTest` 6 项：周末起点跳下个工作日、节假日跳过、补班日优先于节假日、跨年（1 月 1 日休 → 下个工作日为周一）、同一瞬时在企业时区与 UTC 下的起算日与截止时刻差异、`workdays<1` 拒绝。
- 回归行为不变：`IdentityMasterDataHttpTest` 24 项（参数九键校验与版本生效）、`InboundHttpTest` 1 项真实 HTTP/MySQL 全流程（含三段 `*_due_at` 前后稳定断言）在自有隔离 MySQL 8.4.11 通过，日志 output/d6-24-calendar-runtime.log。
- `FamsArchitectureTest` 与 `FamsArchitectureRulesTest` 各 3 项通过，inbound→masterdata 新增静态依赖未形成模块环；Checkstyle 与 SpotBugs 零报告。

## 尚待交付

B 片跨域提醒扫描与异常清单读取端点及双端消息中心；C 片值班提醒人与持续未处理升级、角色恢复可见性；D 片外部通道兜底、指标与 R-08 证据。真实短信、微信模板与真机触达仍依 D6-09B/10B，本卡不宣称已验证。
