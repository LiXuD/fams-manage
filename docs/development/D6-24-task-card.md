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

## D6-24B1 跨域提醒扫描与去重

B 片按闭环再分两片：B1 为只写提醒事实的扫描引擎，B2 为待办与超期读取端点及双端消息中心。

V65 `fams_business_reminder` 一行代表一次不可变的通知事实，唯一键为（业务类型、对象、阶段、冻结截止时刻、档位、发生日、`recipient_slot`）。`recipient_slot` 是 `COALESCE(recipient_account_id,0)` 的 STORED 生成列：MySQL 唯一键放行任意多个 NULL，直接用可空接收人列做键会让池级提醒每轮重复插入；生成列同时保留接收人外键。阶段、档位与接收池三段 CHECK 防止新域写入口径外的值，`reminder_business_date>=notice_date` 保证只会记录已发生的档位。

`BusinessReminderService.scan()` 七段扫描全部以对象自身已冻结的快照与时区推导档位，不新增逐域时限列，也不改写任何业务状态：

- 入库三段（`ADMIN_DECISION`/`WAREHOUSE_CLAIM`/`FIRST_EXECUTION`）读 `*_due_at`；管理员确认额外用 `BusinessCalendar.nthWorkday(snapshot, zone, submitted_at, 1)` 推导"1 个工作日未处理"档，因为确认截止本身可能还有数个工作日。
- 领用交接与调拨实物接收（`HANDOVER_RECEIPT`）用快照时区的 24 小时前、当日、逾期三档；接收人经 `fams_system_account` 的 ENABLED 账号映射，映射不到或逾期责任在管理员时回退 `ASSET_ADMIN` 池。领用段以 `fams_requisition_allocation` 为分页驱动表（`business_id` 即分配行号），与既有 `REQUISITION_ALLOCATION` 待办口径一致。
- 盘点截止（`INVENTORY_COMPLETION`）对 `DISTINCT executor_account_id` 逐人通知，无执行人时整单进管理员池；借用归还继续走已复核的 V25 `fams_loan_reminder`，本片不重复建第二条事实。
- 返厂收回、报失复核、报废处置按 DATE 字段与 ACTIVE 参数版本的时区取当日 24:00 作为截止时刻。返厂的 `OVERDUE_EVERY_7_DAYS` 发生日由到期日推导（`due + 7×max(1,⌊(today-due)/7⌋)`）而非 `today`，因此续跑或重启仍落在同一周桶，不会每天新增一条逾期提醒。

每档只在 `today>=noticeDate` 时写 `INSERT ... ON DUPLICATE KEY UPDATE id=id`，故同档位同发生日重跑零新增；扫描按 `x.id>? ORDER BY x.id LIMIT 200` 游标分页，JOIN 参数版本表不会放大或丢行。`BusinessReminderScheduler` 与借用扫描同为分钟级固定延迟，站内可达不依赖外部通道。

DATETIME 经当前驱动映射为 `LocalDateTime`（UTC 墙钟），`instant()` 与既有 `RepairFactoryGuard` 同口径按 `ZoneOffset.UTC` 换算，避免直接强转 `Timestamp` 抛 ClassCastException。

## D6-24B2 待办与超期读取端点及双端消息中心

V66 `fams_message_receipt` 以（账号、消息类别、消息号）为主键保存个人已读时间，`message_kind` 限 `TODO`/`REMINDER`。跨表引用不建外键：待办与提醒分属两张表，`kind` 已把两者分开，且回执是只增不改的旁路事实，删除业务行不应牵连读取状态；这一点在卡片的开放项中如实登记。

`MessageService` 用一条 UNION ALL 同时读取两个权威事实源，不在应用层拼分页：

- `INBOX`（我的消息）：`fams_todo` 中仍 OPEN 且"指派给本人或本人角色所属共享池"的待办，加上 `fams_business_reminder` 中"接收人为本人或接收池属本人角色"的提醒。提醒只取每个（对象、阶段）的最新一次发生（`id = MAX(id)`），历史提醒继续保留但不重复占用消息中心。
- `EXCEPTION`（异常清单）：只取档位词表中的逾期类（`OVERDUE`、`OVERDUE_EVERY_7_DAYS`、`UNRESOLVED_AFTER_1_WORKDAY`），资产管理员看全部池，库管看 `WAREHOUSE_OPERATOR` 池与本人；其他角色直接 403 `MESSAGE_SCOPE_FORBIDDEN`。借用域的逾期待办与提醒共用同一档位词表，因此 `LoanReminderService` 生成的档位待办自然进入同一清单。
- `read`（`POST /api/v1/messages/{kind}/{id}/read`，mini 同形）：先按同一可寻址谓词 `FOR UPDATE` 校验该消息当前确属本人/本池（管理员对所有池负责），再写一条个人回执；`ON DUPLICATE KEY UPDATE message_id=message_id` 使重复请求无副作用。已读不关闭待办、不改资产锁、不改时限，符合"某人阅读不等于已处理"。

`pools()` 只把 `ASSET_ADMIN`/`WAREHOUSE_OPERATOR`/`EMPLOYEE` 角色映射到同名池，TECH_ASSISTANT 刻意排除：该角色按 `revalidateRepairParticipantLocked` 口径核验，用普通业务身份重校验会直接被 `AuthOperationException.forbidden()` 拒绝，其技术意见待办继续在维修邀请页读取。DATETIME 继续按驱动 `LocalDateTime` 的 UTC 墙钟口径换算成瞬时。

PC 侧新增 `views/MessageCenterView.vue`（`/messages`，AppChrome 导航"消息中心与异常清单"，异常清单按钮仅管理员与库管可见），沿用既有 `generation + identity` 竞态守卫与"原请求重试已读登记"；小程序侧新增 `src/messages-pages.ts` 工厂（`view` 参数产出消息中心与异常清单两页，`pages/messages`、`pages/exceptions` 与 `native/app.json` 注册），提示语明确"已读登记只记录本人状态，业务办理仍以原单据为准"。两端跳转仅对原生/PC 上能按业务身份重新校验的页面给出深链：小程序只链接报废与报失详情，其余显示单据号由用户在对应业务页处理。

## 验证

- `BusinessCalendarTest` 6 项：周末起点跳下个工作日、节假日跳过、补班日优先于节假日、跨年（1 月 1 日休 → 下个工作日为周一）、同一瞬时在企业时区与 UTC 下的起算日与截止时刻差异、`workdays<1` 拒绝。
- 回归行为不变：`IdentityMasterDataHttpTest` 24 项（参数九键校验与版本生效）、`InboundHttpTest` 1 项真实 HTTP/MySQL 全流程（含三段 `*_due_at` 前后稳定断言）在自有隔离 MySQL 8.4.11 通过，日志 output/d6-24-calendar-runtime.log。
- `BusinessReminderRuntimeCase` 1 项（自有隔离 MySQL 8.4.11，`scripts/verify-d6-03a.py` 已登记 d6-24 独立库，日志 output/d6-24-reminder-runtime.log）固定时钟 2026-11-09（周一）：入库待确认（周五提交、2 个工作日截止）只得"1 个工作日未处理"一档、执行超期只得 `OVERDUE`、领取截止在下周零通知；交接接收 24 小时前与当日两档且接收人映射到本人账号；盘点两位执行人各 4 档共 8 行、无执行人整单进管理员池零提前通知；返厂 10-29/11-01/11-08 三档（周桶由到期日推导）；报失复核 2 档、报废处置 3 档。九组共 20 行；同日重跑仍 20 行；推进到 11-13 时领取段补出 `DUE_IN_1_DAY`+`OVERDUE`、接收段补出 `OVERDUE`、周桶仍为 1 行；改盘点到期日后按新截止时刻另发四档。
- `MessagesHttpRuntimeCase` 1 项（真实 HTTP + 自有隔离 MySQL 8.4.11，`scripts/verify-d6-03a.py` 已登记 d6-24b 独立库与授权，日志 output/d6-24-messages-runtime.log）：管理员/库管/员工三个真实会话各自只看到名下或本池的待办与提醒，`TECH_ASSISTANT` 池行不进入三者；同一（对象、阶段）只出现最新一次发生；提醒行回传 `objectStage`（`ADMIN_DECISION`）而待办回传空串；`view=EXCEPTION` 对员工 403、对 `view=BOGUS` 400；管理员已读后本人未读数由 3 降 2 而库管未读数不变，待办 `todo_status` 仍为 OPEN；员工改他人池中消息 404、管理员可代读、缺 `Idempotency-Key` 400，同一消息两位读者各存一条回执；mini 头会话读同一清单并写出本人回执；`pageSize=1` 分页两页内容不重叠。
- `MessageCenterView.test.ts` 3 项：两类来源的中文标签与未读数、异常清单切换按 `view=EXCEPTION` 重查、丢失回复后按同一 path/key 原样重试且已读不重复提交、员工看不到异常清单、账号切换后迟到的旧收件箱不Paint。`messages-pages.test.mjs` 4 项：同一断言口径覆盖原生工厂、403 清空并提示、仅报废/报失给出 `?id=` 深链、迟到的旧身份响应丢弃并回登录。
- Web 239 项、小程序 343 项、`vue-tsc`、`vite build`、小程序 `tsc --noEmit`/`build.mjs` 与 eslint `--max-warnings=0` 全绿；`BusinessReminderRuntimeCase` 在加入 V66 后复跑仍通过；Checkstyle、SpotBugs 零报告，架构 6 项（`FamsArchitectureTest` + `FamsArchitectureRulesTest`）通过证明 reminder→auth 未形成模块环；契约新增 4 个 operation 后本地静态契约的引用、唯一 operationId、示例与追溯检查通过，Java 侧 OpenAPI 校验无错误。

## 尚待交付

C 片值班提醒人与持续未处理升级、角色恢复可见性；D 片外部通道兜底、指标与 R-08 证据。B2 之后仍开放：本会话未接入 GitNexus/AOCI，未运行 `impact`/`detect_changes`；实际 PC 浏览器与真机小程序联调、提交 JVM 重启恢复、完整 D6-03A 产品门禁与独立复核未做；`scripts/verify-d6-02c.py` 的 `assert_write_contracts` 在本片之前已有 170 个既有 operation 不通过（首个为库存观察下载授权被判定为"未分类的非业务写"），本片按既有 `readBusinessResultNotice` 同口径分类，未修改门禁口径；该门禁已由 D6-02C-C1 改为只读契约声明并机械归一（剩 152 条待 C2 取证），详见 [D6-02C 任务卡](D6-02C-task-card.md)；`fams_message_receipt` 不建跨表外键、TECH_ASSISTANT 池消息不经本端点、小程序仅两类深链，均如上登记。真实短信、微信模板与真机触达仍依 D6-09B/10B，本卡不宣称已验证。
