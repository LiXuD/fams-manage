# D6-15A 本地证据

业务候选 `8d542e1`；完整门禁候选 `f46fba6` 仅额外同步 GitNexus 计数。

- d6-15-runtime.log：V26→V27 升级 1 项、调拨 HTTP/MySQL 16 项，零失败、零跳过。
- d6-15-product-gate-final.log：完整产品门禁退出 0，Java 142，Checkstyle/SpotBugs 0，行 92.45%、分支 75.06%，小程序 30、依赖和数据库恢复通过。
- d6-15-product-gate.log：第一次门禁退出 1，原因是本机 workspace 链接缺失；d6-15-npm-ci.log 为锁文件重装记录。
- d6-15-reassigned.png、d6-15-completed.png：真实 PC 页面转派入口及新库管完成接收；PNG 保留在本地共享工作区，沿用忽略规则，不纳入 Git。
- browser-persistence.txt：完成后第二次同库硬重启的数据库直接输出；单头列为 id/status/version/processor/ownershipVersion，资产列为 code/lifecycle/custody/organization/person/location。两组事件分别为业务事件与审计；末尾无活动锁。
- d6-15-concurrency.log：两个管理员会话竞争，200/409、唯一处理权增量、唯一业务事件/审计，原责任和锁保持；任务容器及匿名卷清理确认。
- d6-15-contract-tests.log、d6-15-contract-baseline.log：旧设计包测试失败与 main 对照，不作为通过证据。

PC 测试另由 npm run test:web 取得 23/23、零跳过；构建及类型检查通过。该记录不代表独立复核、远端 CI、QA 或生产验收。
