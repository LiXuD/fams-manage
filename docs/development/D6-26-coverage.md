# D6-26 联调覆盖准备

页面和流程依据 [IA D0.2](../superpowers/specs/2026-09-06-fixed-asset-ia-interaction-design.md)，验收项见 [AC 追溯矩阵](traceability-matrix.csv)。下表核对 `ba14e11` 的路由、页面模板和角色入口，用于准备 D6-26A 的回归范围。最终候选及真实通道确定后，逐页补验动作、拒绝路径及运行证据。

## PC 页面与模板

入口以 [Web 路由](../../apps/web/src/router.ts) 为准。共用路由下按业务和角色分别回归。

| 编号 | 页面 | 候选入口及应核对的范围 |
|---|---|---|
| P01 | PC 登录 | `/login`、`/password/change`，登录、初次改密、失效拒绝 |
| P02 | 工作台 | `/`，角色待办、快捷入口、报表及资产下钻 |
| P03 | 资产列表 | `/assets`，筛选、分页、授权导出 |
| P04 | 资产详情 | `/assets` 内的详情，另有 `/asset-lookup` 的受限查询；逐项核对详情页签 |
| P05 | 建卡与复制 | `/assets` 内建卡表单与详情复制入口 |
| P06 | 导入与后台任务 | `/asset-imports`，上传、预检、执行及错误明细 |
| P07 | 期初核验 | `/asset-import-reviews`、`/asset-initialization`，核验、承接、发布、回滚 |
| P08 | 标签打印 | `/assets` 的详情标签动作；批量选择、尺寸模板及预览需逐项核对 |
| P09 | 共享审批池 | `/` 的待办与 `/inbound`、`/requisitions` 等单域审批；跨域统一筛选及竞争反馈需联验 |
| P10 | 库管执行池 | `/`、`/inbound`、`/requisitions`、`/transfers`，领取、续办、其他库管只读 |
| P11 | 统一业务详情 | `/inbound`、`/requisitions`、`/loans`、`/transfers` 等域内详情；逐域核对后续与履历 |
| P12 | 分配与交出 | `/requisitions`，分配、无法满足、逐项交出与收回 |
| P13 | 入库、退库和归还验收 | `/inbound`、`/loans`，实物验收及异常后续 |
| P14 | 调拨与目标变更 | `/transfers`、`/transfer-target-changes`、`/requisition-target-changes` |
| P15 | 盘点任务 | `/inventory-tasks`，任务范围、发布、分配、终止与封存 |
| P16 | 差异与证据复核 | `/inventory-tasks` 的复核、封存及迟到分区 |
| P17 | 报修与返厂 | `/repairs/:id?`，修理过程、返厂轮次、返还与入库 |
| P18 | 科技协助 | `/repair-assistance/:id?`，受邀意见与只读边界 |
| P19 | 报失与找回 | `/loss-reports/:id?`，报失核实、继续跟进及找回 |
| P20 | 报废与处置 | `/scrap-requests/:id?`，审批、执行、处置与纠正 |
| P21 | 交接异常和代确认 | `/proxy-confirmations`、`/business-result-reviews`，依据、批准、执行与核查 |
| P22 | 受控转派 | `/inbound`、`/requisitions`、`/loans`、`/transfers`、`/repairs/:id?` 的域内面板 |
| P23 | 报表与抽样 | `/` 的资管报表区；14 个专题端点+汇总/分布/抽样已在实际 Chrome 完成查询、翻页、下钻与 CSV 导出，封存明细与执行人下钻已用封存 fixture 补验（记录 output/d6-25-report/browser-run.md，CSV 转义整改在 1d3a41a）；角色切换实测与抽样真实样本仍待联验 |
| P24 | 基础资料和人员交接 | `/transfers` 有人员交接入口；组织与人员已在 29251bb 接入 /identity 并通过实际 Chrome 和 MySQL 验证；五类资料目录已在 723d355 接入 /catalogs 并通过实际 Chrome 和 MySQL 验证；身份导入已在 33f5278 接入 /identity-imports 并通过实际 Chrome、MySQL 验证；手机号纠正已在 7d92dc0 接入 /employee-phone-corrections 并通过实际 Chrome、MySQL 验证；完整动作复验继续接续 |
| P25 | 系统设置 | `/messages` 有值班及部分配置异常入口；账号启停、角色授撤、新建及密码重置已在后继 adbdfcd 接入 /system-accounts；数据范围和敏感字段已在 83d0337 通过实际 Chrome 与 MySQL 验证；业务参数已在 3d1a976 接入 /business-parameters 并通过实际 Chrome 和 MySQL 验证；编号规则已在 ea13045 接入 /numbering-rules 并验证真实建卡分配；通知通道配置与系统操作日志只读查询已在 0518820 接入 /notification-channels、/operation-logs，并通过实际 Chrome 与隔离 MySQL 验证（管理员合并读取、脱敏、分页、审计员授权/撤权即时生效、通道版本链与审计）；全页动作复验继续接续 |
| P26 | PC 消息中心 | `/messages`，收件箱、异常、个人已读及原单下钻 |

## 小程序页面与模板

入口以 [原生注册清单](../../apps/miniprogram/native/app.json) 为准。真实微信授权、扫码设备和弱网证据随设备联调补齐。

| 编号 | 页面 | 候选入口及应核对的范围 |
|---|---|---|
| M01 | 绑定与资料异常 | `pages/login/index`，短信绑定、换绑及异常求助 |
| M02 | 首页 | `pages/mine/index`，登录落地页兼作业务入口；核对状态提醒 |
| M03 | 申请中心和业务表单 | `pages/request-center/index`、`pages/repairs/index`、`pages/loss-reports/index`、`pages/scrap-requests/index` |
| M04 | 我的申请和详情 | `pages/requests/index`，状态、分配和当前后续 |
| M05 | 我的资产 | `pages/loans/index`，借用、退库资产及归还动作 |
| M06 | 部门公用资产 | `pages/department-requests/index` 有公用申请变体；公用资产清单及普通员工报修入口仍需核对 |
| M07 | 部门资产与代办 | `pages/department-requests/index`，按权限代办；部门资产查阅范围需联验 |
| M08 | 扫码与资产概要 | `pages/asset-lookup/index` 提供编码查询；扫码入口另见 `pages/receipts/index`、`pages/inventory-collection/index` |
| M09 | 待接收和核对结果 | `pages/receipts/index`、`pages/transfer-receipts/index`，当前接收人逐件核对 |
| M10 | 我的盘点任务 | `pages/inventory-tasks/index`，当前任务、应盘范围与后续入口 |
| M11 | 连续盘点 | `pages/inventory-collection/index`，识别、实物确认和差异采集 |
| M12 | 待上传与回执 | `pages/inventory-pending/index`，本机持久化、重试、回执 |
| M13 | 消息与责任告知 | `pages/messages/index`、`pages/business-result-notices/index`，个人已读和责任结果 |
| M14 | 我的与账号 | `pages/mine/index`、`pages/login/index`，绑定状态、退出、解绑和待上传提示 |

## 业务流程

| 编号 | 流程 | 核对入口 | 任务依据 |
|---|---|---|---|
| F01 | 共享审批与整单执行 | P09、P10、P11、P13 | D6-07、D6-24 |
| F02 | 建卡入库与期初发布 | P05、P06、P07、P13 | D6-06、D6-07、D6-08、D6-23 |
| F03 | 领用、借用与部分履行 | P11、P12、M03、M04、M09 | D6-11、D6-12、D6-13 |
| F04 | 退库、归还与关联继续处理 | P11、P13、M04、M05 | D6-12、D6-13、D6-15 |
| F05 | 调拨与人员组织交接 | P14、P24、M07、M09 | D6-05、D6-12、D6-14 |
| F06 | 盘点、差异与封存 | P15、P16、M10、M11 | D6-16、D6-17、D6-18 |
| F07 | 离线采集与迟到证据 | P16、M11、M12 | D6-17、D6-18 |
| F08 | 报修、科技协助与多轮返厂 | P17、P18、M03、M09 | D6-19、D6-20 |
| F09 | 报失、报废、处置和纠正 | P19、P20、M03 | D6-21、D6-22 |
| F10 | 交接异常、代确认与转派 | P21、P22、P26、M09、M13 | D6-12、D6-15、D6-24 |
