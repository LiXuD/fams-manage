# V87 运行时用例预存红 · 交接单

> 提请 `backend/src/test/java/com/fams/local/LocalMiniServerApplication.java` 的责任会话处理。
> 发现时间：2026-11-24 · 仓库：fams main · 状态：**未修改任何代码**（用户裁决"只登记，转给原会话"）

## 1. 症状

完整 Java 运行时门禁（`scripts/verify-d6-03a.py` 的 `verify_java_quality` 序列，约 83 步）
在第 51 步终止：

```
步骤：WeChat subscription one-shot quotas and in-app fallback V87
命令：./mvnw -pl backend -Pquality -Dd6.24.wechat.subscription.runtime=true
        -Dtest=WechatSubscriptionHttpRuntimeCase test
断言：WechatSubscriptionHttpRuntimeCase.subscriptionGrantsAreOneShotQuotasWithServerSideOwnershipAndInAppFallback
        :89 -> verifySubscriptionAndDeliver:99 -> miniLogin:386 -> ok:299
结果：{"code":"INTERNAL_ERROR","message":"The request could not be completed."}
      ==> expected: <200> but was: <500>
```

即 `POST /api/v1/mini/auth/login` 返回 500。前面的 V65/V66/V86 均 exit=0。

## 2. 归因：与 CONV 系列无关，是主线预存红

把该用例单独在 **`6431f06` 的纯净 `git archive` 副本**（不含 CONV-01~07 任何改动、无未跟踪文件）
重跑，得到**逐字符相同的失败**（`expected: <200> but was: <500>`，同一 `miniLogin:386 -> ok:299` 栈）。
因此这不是 CONV 系列引入的回归。

## 3. 定位线索

`backend/src/test/java/com/fams/local/LocalMiniServerApplication.java` 现在是**已跟踪文件**：

```java
@SpringBootApplication(scanBasePackages = "com.fams")
public class LocalMiniServerApplication {
    @Configuration
    static class LocalDoubles {   // 提供 MiniWechatIdentityPort / MiniSmsPort 的本地替身 Bean
```

它在**测试源集**里、带 `scanBasePackages = "com.fams"`，且 `LocalDoubles` 注册
`MiniWechatIdentityPort` 与 `MiniSmsPort` 的本地替代实现。任何扫描 `com.fams` 的 Spring 测试上下文
都会把它当组件拾取，从而与真实实现形成重复 Bean —— 这正是 `/mini/auth/login` 500 的成因。
系列记忆中该文件"尚未跟踪"时已记录过完全相同的症状与相同的失败用例，现在固化进了主线。

## 4. 候选修法（任选其一，需原会话按其联调需求定）

1. 给 `LocalDoubles`（或整个 `LocalMiniServerApplication`）加 `@Profile("local-mini")`，
   联调时显式激活该 profile，测试上下文默认不加载。
2. 把该类移出 `com.fams` 扫描面（例如换到 `com.fams.localdev` 且不给 `scanBasePackages`，
   或迁到独立 source set / 独立模块）。
3. 用 `@ConditionalOnMissingBean` 让本地替身仅在真实实现缺席时注册。

## 5. 影响

在该红修复前，CONV 系列的**运行时验证层不可用**：167 个 docker 支撑的并发/幂等/原子性用例无法跑完，
因此 CONV-07 的 138 条写链首次进事务、以及 CONV-08 将补的 70 个无事务入口，都只能拿单元测试与
人工读码作为证据。这是当前整改线最大的不确定性来源。
