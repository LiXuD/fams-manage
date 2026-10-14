# D6-16 开发运行记录

状态IN_PROGRESS。0555b27及fc4410a已有固定候选完整门禁；当前兼容性修复尚须绑定新候选，独立复核未完成。

## 已取得证据

专用MySQL 8.4.11重建V30只读结构基线81表；只导出metadata与SHOW CREATE TABLE，未导出业务行。[结构基线](evidence/d6-16/schema-evidence-v30.json)。V31新增资产域公开只读投影及盘点任务/快照/分配/参与/事件表，既有V1–V30未改；填充真实V30资产全行和所有旧列元数据的升级、重复迁移、Flyway校验已通过，不冒充生产数据副本。

PC真实浏览器使用分离的管理员与员工账号，经正常登录及Vite公开代理到Spring/MySQL：创建草稿、预览2件、发布冻结；实际待入库草稿未进入集合。发布后数据库为READY；第一次SIGKILL进程组并启动新JVM后，持久任务自动进入IN_PROGRESS。管理员页面将第一件分配给员工，员工页面只见该件，没有草稿/发布/全员目录入口。第二次SIGKILL新JVM后，版本、分配、完整快照及唯一开始事件保持。资产业务锁和最近盘点日期效果均为0。

证据：[发布后](evidence/d6-16/browser-before-restart.json)、[分配后](evidence/d6-16/browser-after-assignment.json)、[二次重启后](evidence/d6-16/browser-after-two-restarts.json)、[管理员HTTP](evidence/d6-16/browser-admin-requests.txt)、[员工HTTP](evidence/d6-16/browser-employee-requests.txt)、[员工渲染快照](evidence/d6-16/browser-employee-final-snapshot.txt)、[管理员页面](evidence/d6-16/admin-assignment.png)、[员工页面](evidence/d6-16/employee-snapshot.png)。浏览器和本轮Compose容器/网络/卷已清理；未操作其他既有容器。

首次页面加载发现错误使用调拨分类目录404，已改用分类域有效引用接口并从页面重跑成功。浏览器控制台既有未登录401和favicon404分列，不冒充所有控制台消息为零。早期定向失败包括测试清理表名/外键顺序及复制JSON整数节点误判，均保留output原始过程；失败不计通过。

## 仍需完成

7项真实HTTP场景（含权限撤销、开始并发、原生处理器真实HTTP、任务外手工结果边界）及填充升级1项均通过。Web32、mini53、类型与构建、lint及SpotBugs0、验证器32项通过。已修复列表访问器并仅精确豁免Spring注入的db/json字段。随后运行固定候选统一完整门禁并独立送审。任务取消/终止保护的结果边界由测试直接种入result_count，现场采集仍归D6-17；不得称已采集真实观察。原生微信交换/HTTPS桥接是明确测试替代，真机、真实短信、远端CI、正式QA及生产另验。AOCI截图策展仍有未决债务，不宣称治理全对齐。

## 跨JVM原键重试

另起全新专用数据库及正常登录会话，创建/预览/发布后，在手动开始请求上通过Playwright route.fetch实际提交到真实Spring，再丢弃浏览器响应（没有返回伪造业务响应）。页面显示结果未确认并冻结编辑。SQL确认IN_PROGRESS/version2后SIGKILL后端进程组并启动新JVM，点击页面“重试原操作”返回200；重试前后任务、快照和事件完整投影完全相同。首次开始为net::ERR_FAILED，重试为200，唯一开始事件保持1。最初尝试对发布注入时CLI语法失败，发布正常成功；不将该尝试算作发布丢响应证明。

证据：[丢响应后](evidence/d6-16/lost-start-before-restart.json)、[跨JVM重试后](evidence/d6-16/lost-start-after-retry.json)、[渲染](evidence/d6-16/lost-start-after-retry-snapshot.txt)、[HTTP元数据](evidence/d6-16/lost-start-requests.txt)、[定向运行摘要](evidence/d6-16/inventory-runtime-summary.txt)、[开发检查](evidence/d6-16/development-checks.json)。本轮浏览器及Compose容器/网络/卷也已清理。生产微信/HTTPS/真机及正式验收边界保持。

## 固定候选0555b27

统一完整门禁退出0：Java198、Checkstyle/SpotBugs0，行11028/11867=92.93%、分支3955/5235=75.55%；Web32组件和生产构建、mini53及原生构建、TS检查、依赖完整性、应用与MySQL同卷重启恢复、专用资源清理及干净postflight通过。[完整日志](evidence/d6-16/product-gate-0555b27.txt)。这不替代历史治理、独立复核、QA或生产。后续中文状态及责任人选择调整须绑定新候选另跑，不回填0555b27。

最终界面调整：PC生命周期中文显示，当前责任人由管理员最小人员目录选择（含已有停用责任人的明确标识），原生任务状态中文及设备本地时间。7项HTTP及升级1项、Web32、mini53再次通过。根类型检查曾因上一门禁finally清理生成目标而缺少ContractProbe生成模块失败；独立双端类型检查通过，最终完整门禁将重新生成并验证根类型链路，不把该次失败算通过。

最终PC界面再次在新专用库正常登录并从导航打开，中文生命周期和当前责任人选择显示正确，管理员personnel最小目录真实HTTP返回200且页面无业务错误。证据[页面](evidence/d6-16/final-browser-snapshot.txt)、[HTTP](evidence/d6-16/final-browser-requests.txt)；会话及专用容器/网络/卷已清理。

## 范围目录和大整数兼容性修复

fc4410a统一门禁退出0：Java198、静态检查0，行11027/11867=92.92%、分支3955/5235=75.55%，Web32、mini53及构建、类型、依赖、同卷恢复和清理通过。[历史门禁](evidence/d6-16/product-gate-fc4410a.txt)。该结果不覆盖其后修复。

基础资料停用不表示历史资产退出盘点，因此新增masterdata所有者只读目录，返回id/name/status并包含停用项，盘点管理员鉴权后调用；不复用只允许有效引用的新建资产选择器。范围JSON统一输出字符串ID，兼容既有数值JSON及旧幂等回执，读取时规范化，原始记录及旧回执字节不重写。真实HTTP9项和填充升级1项通过，SpotBugs检查0。

实际PC页面选择停用分类9007199254740993、保存草稿、预览2项并确认发布成功；数据库范围与两条冻结快照中的分类ID均为精确字符串，发布事件1次，资产业务锁及最近盘点日期效果均0。[持久化](evidence/d6-16/scope-large-id-browser.json)、[页面](evidence/d6-16/scope-large-id-browser-snapshot.txt)、[HTTP](evidence/d6-16/scope-large-id-browser-requests.txt)。普通员工目录拒绝、非法目录种类拒绝、旧回执重放不改写由真实HTTP测试覆盖。浏览器及本次专用数据库资源已清理。

部署顺序约束：启用盘点发布前，所有期初导入回滚写入实例必须升级到新的下游使用检查，或在维护窗口禁用旧回滚路径。外键只能保护资产存在，不能阻止旧代码取消已被盘点引用的资产；产生快照后不支持任意回退到旧写入实例，应优先前向修复。

## 最终功能候选a57a9f6

完整SHA a57a9f6f80ef43300f5b99f2c2e5748484596e12，统一产品门禁退出0：Java200、Checkstyle/SpotBugs0，行11084/11910=93.06%、分支3973/5249=75.69%；Web32组件/生产构建、mini53/原生构建、根TS与依赖、应用新JVM和MySQL同卷恢复、专用资源清理及干净postflight通过。[完整门禁](evidence/d6-16/product-gate-a57a9f6.txt)。

A/B/C实现与本地证据送审，D独立复核材料已备，结论待用户指定非实施者填写；父项未关闭。文本认知13项已更新，十四张截图策展未决。提交后GitNexus256MiB重建因WAL轮转失败，保留失败，门禁期间未重建；门禁后用更大阈值重试，不将图状态冒充测试结论。
