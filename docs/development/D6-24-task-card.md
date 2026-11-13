# D6-24 通知与异常跟进任务卡

- D 增加接收和盘点到期 24 小时仍未完成且未读的短信兜底。排队及发送前复核活动阶段、相同截止时间、当前接收人或执行人及账号资格；已读或责任变更后取消投递。隔离 MySQL 覆盖调拨接收、盘点、去重及两个发送前阻断场景。
## 2026-11-13 第四批开发记录

- D 补充投递工作器实际调用证据。受控短信适配器首发返回 UNKNOWN，独立构造的第二个工作器领取持久重试并沿用原投递键，记录一次接受结果。验证使用真实 MySQL 持久任务，适配器为测试替身，供应商真实去重与跨 JVM 恢复仍待验证。

- C3 追加 V72 和已有契约的两个 PC 查询，系统管理员在消息中心查看配置异常。角色缺失按企业去重，角色恢复后保留原异常与入库待办的参数版本和截止时间。通道检查使用 SMS 适配器与模板配置、微信模板配置；目前只验证配置事实，待扩展其他业务待办快照并执行实际 PC 验证。
- C4 账号启停、相关角色授予与撤销、员工启停在提交后立即触发异常与转派提醒扫描。分钟扫描补偿进程中断或临时失败。转派提醒按当前经办库管与开放待办登记，原经办恢复或待办关闭后结束，实际转派仍通过业务入口办理。隔离 MySQL 与真实 HTTP 已覆盖账号停用立即出现转派待办、恢复后关闭提醒、扫描重复去重、原经办保留和管理员恢复后原待办可见。首轮架构检查发现 auth/business 依赖环，事件移至身份域后 3 项架构检查通过。员工与角色入口联合复走、明确无法继续、超时和全域验证待补。
- D 追加 V73。值班未读升级的五段来源按当前业务阶段、冻结截止与接收人资格筛选，复用持久任务租约、重试和稳定投递键。隔离用例核对同一通知一条投递、同时只能有一名领取者、未知结果同键重试和旧执行代次写回被拒。外部发送实际实现、供应商对账、其余短信规则、小程序订阅入口、管理面抓取与提交 JVM 恢复待验。

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

## D6-24C1 值班提醒人配置底座

D6-24C 按"配置底座 → 阈值消费与升级 → 配置异常 → 停用转派与角色恢复"四段实施，本段是第一段。

V67 `fams_duty_assignment` 是每个共享池一条 ACTIVE 行的不可变链：`role_code` 由 CHECK 限 `ASSET_ADMIN`/`WAREHOUSE_OPERATOR`，`sequence_no` 自 1 递增并以 `(role_code,sequence_no)` 唯一，`(role_code,active_slot)`（`duty_status='ACTIVE'` 生成列）保证同池同时只有一个现任，被替换的旧行转 `SUPERSEDED` 并写 `superseded_at` 后整行保留，用于回答"当时谁在值班"。主备双方都挂 `fams_system_account` 外键，CHECK 要求替补与值班人不同，状态与时点由 CHECK 串联（ACTIVE 必空 `superseded_at`）。

值班语义严格按 §9.5：值班提醒人只接收升级通知，不取得独占审批权或处理权，因此这是一份通知元数据而不是业务身份。据此端点走 `MasterDataActorContext` 的配置口径而不是 `RequisitionActorContext.revalidateLocked`——后者按设计只接受业务身份，纯 `SYSTEM_ADMIN` 会话会被 `AUTH_FORBIDDEN` 拒绝（B2 已记录同一事实），用错口径会让"系统管理员配置"这条要求直接不可实现。候选有效性沿用既有共享池判定：账号 `ENABLED` 且非初始密码 + 员工 `ACTIVE` + 组织 `ACTIVE` + 持有该角色。

读取按角色收敛：系统管理员与资产管理员见两池，库管只见本池，其余角色由 `requireCatalogSelector` 直接 403。每次读取回传 `primary.active`/`backup.active`，因此值班人被停用或移出角色时历史配置继续可见但标记失效，供后续升级判定使用；配置端点自身不写任何业务表。

替换是版本守护 + 幂等的单事务：`If-Match-Version` 等于当前 `sequence_no`（首条为 0），否则 409 `DUTY_VERSION_CONFLICT`；`Idempotency-Key` 与 F1 规范化的载荷摘要写入既有 `fams_idempotency_record`，同键同载荷返回原回执且不产生第二条链行，同键不同载荷返回 409 `IDEMPOTENCY_PAYLOAD_CONFLICT`；成功后一事务写旧行 SUPERSEDED、新行 ACTIVE、一条 `DUTY_ASSIGNMENT_REPLACED` 审计。

契约新增 2 个 operation（`GET /duty-assignments`、`PUT /duty-assignments/{roleCode}`，内联 schema），只提供 PC 配置面，故不新增 mini 端点。PC 在消息中心页新增"值班提醒"区块：管理员与库管可读、仅系统管理员可登记，沿用同一 `identity + generation` 竞态守卫与"原请求重试值班登记"；新增 `reminder/api.ts` 承载 PUT 通道，因为既有 `loanRequest` 的写请求固定发 POST。

## 验证

- `BusinessCalendarTest` 6 项：周末起点跳下个工作日、节假日跳过、补班日优先于节假日、跨年（1 月 1 日休 → 下个工作日为周一）、同一瞬时在企业时区与 UTC 下的起算日与截止时刻差异、`workdays<1` 拒绝。
- 回归行为不变：`IdentityMasterDataHttpTest` 24 项（参数九键校验与版本生效）、`InboundHttpTest` 1 项真实 HTTP/MySQL 全流程（含三段 `*_due_at` 前后稳定断言）在自有隔离 MySQL 8.4.11 通过，日志 output/d6-24-calendar-runtime.log。
- `BusinessReminderRuntimeCase` 1 项（自有隔离 MySQL 8.4.11，`scripts/verify-d6-03a.py` 已登记 d6-24 独立库，日志 output/d6-24-reminder-runtime.log）固定时钟 2026-11-09（周一）：入库待确认（周五提交、2 个工作日截止）只得"1 个工作日未处理"一档、执行超期只得 `OVERDUE`、领取截止在下周零通知；交接接收 24 小时前与当日两档且接收人映射到本人账号；盘点两位执行人各 4 档共 8 行、无执行人整单进管理员池零提前通知；返厂 10-29/11-01/11-08 三档（周桶由到期日推导）；报失复核 2 档、报废处置 3 档。九组共 20 行；同日重跑仍 20 行；推进到 11-13 时领取段补出 `DUE_IN_1_DAY`+`OVERDUE`、接收段补出 `OVERDUE`、周桶仍为 1 行；改盘点到期日后按新截止时刻另发四档。
- `MessagesHttpRuntimeCase` 1 项（真实 HTTP + 自有隔离 MySQL 8.4.11，`scripts/verify-d6-03a.py` 已登记 d6-24b 独立库与授权，日志 output/d6-24-messages-runtime.log）：管理员/库管/员工三个真实会话各自只看到名下或本池的待办与提醒，`TECH_ASSISTANT` 池行不进入三者；同一（对象、阶段）只出现最新一次发生；提醒行回传 `objectStage`（`ADMIN_DECISION`）而待办回传空串；`view=EXCEPTION` 对员工 403、对 `view=BOGUS` 400；管理员已读后本人未读数由 3 降 2 而库管未读数不变，待办 `todo_status` 仍为 OPEN；员工改他人池中消息 404、管理员可代读、缺 `Idempotency-Key` 400，同一消息两位读者各存一条回执；mini 头会话读同一清单并写出本人回执；`pageSize=1` 分页两页内容不重叠。
- `MessageCenterView.test.ts` 3 项：两类来源的中文标签与未读数、异常清单切换按 `view=EXCEPTION` 重查、丢失回复后按同一 path/key 原样重试且已读不重复提交、员工看不到异常清单、账号切换后迟到的旧收件箱不Paint。`messages-pages.test.mjs` 4 项：同一断言口径覆盖原生工厂、403 清空并提示、仅报废/报失给出 `?id=` 深链、迟到的旧身份响应丢弃并回登录。
- Web 239 项、小程序 343 项、`vue-tsc`、`vite build`、小程序 `tsc --noEmit`/`build.mjs` 与 eslint `--max-warnings=0` 全绿；`BusinessReminderRuntimeCase` 在加入 V66 后复跑仍通过；Checkstyle、SpotBugs 零报告，架构 6 项（`FamsArchitectureTest` + `FamsArchitectureRulesTest`）通过证明 reminder→auth 未形成模块环；契约新增 4 个 operation 后本地静态契约的引用、唯一 operationId、示例与追溯检查通过，Java 侧 OpenAPI 校验无错误。

- `DutyHttpRuntimeCase` 1 项（真实 HTTP + 自有隔离 MySQL 8.4.11，`scripts/verify-d6-03a.py` 已登记 d6-24c 独立库、授权、`D6_24C_*` 环境变量与用例条目）：系统管理员首次以版本 0 登记两池得到 `version=1` 且主备都判为有效；资产管理员与库管发起登记均 403、员工读取列表 403；把不具备该角色的员工或同一个人同时填成主备分别得到 409 `DUTY_MEMBER_INACTIVE` 与 400 `DUTY_ASSIGNMENT_INVALID`；同一 `Idempotency-Key` 重放返回原回执且该池仍只有 2 行（未产生第三条链行），同键换载荷 409，旧版本 409；替换后 `(ACTIVE=1, 链上=3)`，历史行保留；三会话读取按角色收敛（管理员见两池、库管只见本池）；把在任值班人的员工记录停用后配置仍可见且 `primary.active=false`、`backup.active=true`；`fams_todo` 与 `fams_business_reminder` 仍为 0 行而 `DUTY_ASSIGNMENT_REPLACED` 审计恰 4 条（三次管理员 + 一次库管），证明配置面不触碰业务事实。
- `MessageCenterView.test.ts` 5 项（新增 2 项）：管理员与库管读到值班链与"已失效"标记且系统管理员才出现登记表单；提交按当前 `version` 发 PUT，丢失回复后按钮转为"原请求重试值班登记"且第二次提交的 path/key/body/版本与第一次逐字段相同，成功后列表版本推进为 2。
- 修正一处早于本片的编译回归：`1d28cec` 的 `MessageService.item(...)` 用了 11 对键值的 `Map.of`，超出 JDK 上限因而该提交无法编译（B2 当时记录的是加 `objectStage` 之前的运行结果）。本片改为 `Map.ofEntries` 后在自有隔离 MySQL 上复跑 `BusinessReminderRuntimeCase`（V65 档位与去重）与 `MessagesHttpRuntimeCase`（V66 可寻址性与个人已读）两项均通过，B1/B2 的运行时证据在当前树重新成立。

## C1 交接时的遗留项

C 片已交付 C1 值班提醒人配置底座；仍开放 C2 `notificationEscalationThresholds` 四阈值首次产生业务效果（未读满阈值升级值班人、超时再满 1 个工作日的全体强提醒、同一异常每日最多一次、已读也触发、"处理结果/有效转派/带下一跟进时间的核查记录"三类豁免判定）、C3 `fams_configuration_anomaly` 落地（该表与两个只读端点自 D6-02C 起只有契约没有实现，值班人失效与无人有效都依赖它）、C4 成员停用即生成去重的"需要转派"待办与角色恢复后的可见性回归；D 片外部通道兜底、指标与 R-08 证据。B2 之后仍开放：本会话未接入 GitNexus/AOCI，未运行 `impact`/`detect_changes`；实际 PC 浏览器与真机小程序联调、提交 JVM 重启恢复、完整 D6-03A 产品门禁与独立复核未做；`scripts/verify-d6-02c.py` 的 `assert_write_contracts` 在本片之前已有 170 个既有 operation 不通过（首个为库存观察下载授权被判定为"未分类的非业务写"），本片按既有 `readBusinessResultNotice` 同口径分类，未修改门禁口径；该门禁已由 D6-02C-C1 改为只读契约声明并机械归一（剩 152 条待 C2 取证），详见 [D6-02C 任务卡](D6-02C-task-card.md)；`fams_message_receipt` 不建跨表外键、TECH_ASSISTANT 池消息不经本端点、小程序仅两类深链，均如上登记。真实短信、微信模板与真机触达仍依 D6-09B/10B，本卡不宣称已验证。

## 2026-11-11 D6-24C2 阈值和升级定向验证

D6-24C2 固定候选 `1972d48` 已登记 `DutyEscalationRuntimeCase` 与独立 MySQL schema。升级读取原单冻结阈值和企业时区，修复值班主备双列读取、未读查询重复 SELECT，以及完整工作日边界。隔离 MySQL 8.4.11 定向用例与 6 项架构检查通过，Checkstyle/SpotBugs 零报告。统一产品入口在固定候选上已实际执行新增用例，1 项通过、零失败与跳过，日志 `output/first-batch-reminder-1972d48.txt`。用例每个阶段造 201 条，覆盖五段翻页、阈值前后、主备失效回退、个人已读、群提醒、有效转派、每日去重及零阈值。实际页面与提交后 JVM 的业务恢复并入 D6-24 联验。本批验证五个已有扫描阶段。C3 配置异常、C4 停用转派与角色恢复、D 投递和观测仍待交付。

2026-11-11 产品门禁：固定候选 `1972d48` 的 `verify-product-development.py` 退出 0。V68 升级扫描用例 1 项通过，Java 必需测试 532 项，Checkstyle/SpotBugs 零报告，行覆盖 93.18%、分支 75.95%。Web 241 项和小程序 343 项通过。类型、lint、双端构建、应用重启与同卷 MySQL 重启恢复及清理通过。D6-24C2 固定候选进入 `IN_REVIEW`。实际页面和提交后 JVM 的业务恢复由 D6-24 联验继续取证，D6-24 父任务仍为 `IN_PROGRESS`。日志 `output/first-batch-product-1972d48.log`。
