# D6-13 真实浏览器与借用恢复证据

执行日期：2026-10-09。本地隔离开发验证，不替代独立复核、QA 或生产验收。
首次业务实现：`e831604`；最终业务候选 `e99bca5` 修复 JDBC 可空返回值问题后，重新执行延期/归还关键链路及两次硬重启复验，结果一致。
入口：`scripts/run-d6-13-browser-runtime.py`，继承 D6-12 唯一自有 MySQL、实际 Spring Boot 与 Vite。
三个角色分别通过登录页输入夹具账号，退出旧会话后切换身份；未注入 Cookie 或 Token。

## 期初逾期借用的延期与归还

资产 `LOAN-INITIAL-20`、借用 1 是明确的合成期初夹具；不作为期初导入发布路径的证据。
原借出日 2020-01-01、原归还日 2020-02-01，员工 3 / 组织 1。

1. employee 从主导航进入借用页面，实际展示逾期状态及到期提醒履历；提请延期至 2026-11-09。
2. admin 通过页面批准延期。MySQL：唯一延期 1 为 APPROVED；原日期仍为 2020-02-01，当前日期为 2026-11-09，历史逾期区间为 2020-02-02 至 2026-10-09；借用仍 OPEN / 版本 1，资产仍 BORROWED / PERSONAL。
3. 使用 runtime 的 recover 对自有后端进程组执行 SIGKILL，同库重启。管理员会话恢复，员工重新登录后页面仍展示上述原日期、当前日期和历史区间。
4. employee 申请归还，admin 批准，warehouse 独占领取整单验收。
5. 仓库 Warehouse、仓位 Bin、错误编码 WRONG-CODE 被页面拒绝。MySQL：归还仍 WAREHOUSE_PROCESSING / 版本 2，借用仍 OPEN，资产仍 BORROWED / PERSONAL / 使用组织 1 / 责任员工 3，唯一 ASSET_RETURN 锁保留，未产生 RETURN_INSPECTED 审计。
6. 刷新后输入正确编码 LOAN-INITIAL-20，正常验收。MySQL：归还 COMPLETED / NORMAL / 版本 3；借用 RETURNED / 版本 2；资产 IN_STOCK / STORAGE、使用组织和员工均 NULL、仓库 1；归还业务锁释放；提请、批准、领取、验收审计各 1 条。
7. 再次 SIGKILL 并同库重启，页面仍展示“已归还 / 验收完成 / NORMAL”。截图：本地 `output/d6-13-browser-return-restored.jpg`。

## 日常借用申请、审批、分配与交出

employee 从“领用申请与分配”新建借用，预计归还日 2026-11-09、Desk / Standard / 数量 1，提交。
admin 批准当前版本；warehouse 领取唯一处理权，选择 RQ-ASSET-10，核对实物编码并记录交出。

申请 `REQ-cb622f3b-da47-4189-ad4e-b9fff05d87f2` 为 WAITING_HANDOVER / 版本 5。
数量守恒：申请 1、待分配 0、待交接 1、完成 0、无法满足 0、取消 0。
唯一分配为 DELIVERED_WAITING_RECEIPT，DELIVERED 事件 1 条；正式资产仍 IN_STOCK / STORAGE，唯一 REQUISITION 锁保留，没有提前创建新借用关系。
截图：本地 `output/d6-13-browser-borrow-handover.jpg`。浏览器本场景止于交出待接收；实际接收及 M05 延期/归还由固定候选的原生处理器→HTTP→MySQL 用例单独证明，不能混作真机证据。

## 证据与清理边界

数据库查询来自自有容器，只保留业务编号、状态、数量和审计汇总；截图无密码、Cookie、Token。
首次浏览器控制台 error 查询为空。最终候选关键链路复验的 SQL 状态与本节相同，恢复截图已更新为 e99bca5。清理工具补充 2230161 经实际启动/查询/停止验证，明确确认本次容器挂载的唯一匿名卷已移除、容器已移除、8080/5173 无监听，退出 0；此前未记录挂载身份的匿名卷不猜测清理。
真实短信、微信身份交换、HTTPS 真机与设备验收仍按用户安排后置。
