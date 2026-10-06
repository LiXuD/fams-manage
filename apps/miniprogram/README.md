# FAMS 原生小程序

当前 D6-10A 提供 M01 登录/短信绑定与换绑、M14 账号/解绑/退出，以及持久会话传输。其他业务页面按后续计划实施。

在仓库根目录执行：

```sh
rtk npm ci
rtk npm run test:mini
rtk proxy env FAMS_MINI_API_ORIGIN=https://your-corporate-api.example rtk npm run build --workspace @fams/miniprogram
```

将 `apps/miniprogram/dist` 导入微信开发者工具，选择企业自己的 AppID；HTTPS 域名须在企业微信小程序后台配置为合法请求域名。示例域名不是可用服务，AppID/AppSecret/短信密钥不得写入客户端。未配置 API 源时仍可构建，页面显示服务未就绪。

构建输出 CommonJS JavaScript、app.json、WXML/WXSS 和项目配置；`dist` 为忽略的生成物。`src/config.ts` 只声明空默认值，构建时通过 `FAMS_MINI_API_ORIGIN` 写入输出配置，不修改源码。所有身份请求均走服务端 `MiniWechatIdentityPort`；短信由 `MiniSmsPort` 发送至与登记摘要匹配的号码。生产适配未配置时服务返回 503，不回退为自动绑定。

后端管理员更正：`POST /api/v1/employees/{id}/phone-correction`，携带 PC Cookie、CSRF、幂等键、员工版本及 phone/verificationMethod/reason。管理员解除绑定：`POST /api/v1/system-accounts/{id}/mini-binding/unbind`，携带相同安全头及绑定版本和核验方式/原因。管理员没有直接绑定接口。普通人员 PATCH/CSV UPDATE 中非空 phone 返回 `EMPLOYEE_PHONE_CORRECTION_REQUIRED`，号码必须通过核验更正；CSV CREATE 可初次登记。

验证码有效 5 分钟、发送间隔 60 秒、按 UTC 日期每人/设备最多 10 次；连续 5 次验证错误锁绑定 30 分钟。验证码、工号输入和手机号仅留页面内存，不保存到缓存；离开页面销毁验证上下文。确认丢失时在当前页面以原幂等键重试。换绑撤销原绑定及所有旧会话，号码更正推进员工版本并使旧验证码失效，但保留既有绑定。

`test/binding.test.mjs` 验证同一原生页面处理器与 wx 端口；`test/runtime-binding-http.mjs` 只由真实 MySQL/HTTP 测试启动，驱动页面处理器访问真实 Spring 服务并核对持久化效果。该测试的微信身份、短信捕获和 loopback 传输均是明确替身，不是 WXML 渲染、真实短信、HTTPS 或真机证据。外部通道及真机归 D6-10B。
