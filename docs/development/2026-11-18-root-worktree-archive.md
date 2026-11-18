# 根工作树历史改动归档（2026-11-18）

用户要求读取根工作树的历史未提交改动并一并提交、推送。本次在原分支 `codex/d6-21-loss` 保存，不合并 main，也不将历史文件覆盖到现役 `codex/d6-03b-ci`。

## 保存范围

根工作树基线为 `08c38b2e15d50e3c34df5a162774e4c75e00d267`，fetch 后与远端同名分支一致。读取了 31 个已跟踪改动，以及未跟踪的提醒升级测试、第三批证据 JSON 和本机 IDE 目录。

- 提醒升级按原单冻结阈值、时区和完整工作日判断，修复未读 SQL 与主备值班读取，并登记独立测试库和运行用例。
- 报废完成后保留纠正、冲正、找回结果，已完成结果不能再次执行。纠正待认领对库管共享池可见，认领后仅当前经办人可见。
- 内部维修关联报废尚未处置时允许原实物冲正，保留原维修、报废和已终止借用事实。
- PC、小程序及附件端点的非法 UUID 幂等头返回 400，对应真实 HTTP 和双端断言同步。
- 契约写分类、标准头与相对路径，以及第一至第四批的历史任务卡、复核及交接记录一并保存。历史测试数字和复核签署不改成此次重新验收。
- `backend/.openidea` 仅有个人 IDE 状态和绑定本机 Maven 路径的配置，按既有本机配置排除原则补充 Git 忽略，文件保留本机、不上传。

## 与已有交付的关系

通过 Git 历史逐文件比较，以下文件与已推送开发历史中的对应版本字节一致：

| 文件范围 | 对应提交 |
|---|---|
| `DutyEscalationService.java`、`DutyEscalationRuntimeCase.java`、`scripts/test_verify_d6_02c.py`、`scripts/verify-d6-03a.py` | `1972d48` |
| `ScrapReadService.java`、`ScrapReviewService.java` | `325bc84` |
| `LossHttpRuntimeCase.java`、`ScrapSubmitHttpRuntimeCase.java`、`contracts/fams-core-v1.yaml` | `345fbcb` |

报废错误处理、PC 组件测试、两个原生 HTTP 脚本、维修 HTTP 测试、D6-20 任务卡、RTK 调用修正及第三批证据 JSON 也与已推送 `05216ed` 树中的文件一致。这证明本次保留的是历史在途改动，不能把它当作当前开发分支的全部代码。

## 当前验证与接续

本次 Java 主源码及测试编译通过；报废 PC 定向组件 12 项、契约测试 72 项及契约验证器 25 项、门禁验证器 33 项全部通过；674 个操作的静态契约引用、写入边界、示例和追溯校验通过，`git diff --check` 无问题。GitNexus 变更检测为 LOW；索引输出包含文档同名匹配，范围以实际 Git 文件清单及源码核对为准。最终结果同步本分支开发计划末尾；原始日志留在被忽略的 `output/root-archive-*-20261118.log`。本次没有对旧根分支重跑完整产品门禁、真实业务数据库或浏览器流程，不将历史候选的通过结论移植为本分支的新验收。

现役开发基线为已经推送的 `05216eda72d1dececeb63889836cc2532d7c830f`，分支 `codex/d6-03b-ci`。其最新完整候选的 npm 高危审计失败、编号规则的后继定向验证、剩余设置页面、独立复核及 D6-26/27 接续顺序均见[现役交接快照](https://github.com/LiXuD/fams-manage/blob/05216eda72d1dececeb63889836cc2532d7c830f/docs/development/2026-11-18-current-progress-handoff.md)。继续开发时从现役分支接续，不从本归档分支重做已交付功能。
