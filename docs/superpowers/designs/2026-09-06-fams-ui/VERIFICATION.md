# D0.3 本地 UI 验证记录

> 版本边界（2026-09-12）：本文保留D0.3规范/交付记录。D0.4本地回归证据另见 [local-verification.json](../../../../output/playwright/fams-ui/local-verification.json)、[static-audit.json](../../../../output/playwright/fams-ui/static-audit.json)、`d04-states-1440/390/320.png` 和 [视觉比选证据](d04-work/visual-comparison-evidence.json)；D0.4 Figma逐页读回另见 [d04-native-live-verification.json](figma-native/d04-native-live-verification.json) 与 [D0.4导出索引](figma-native/d04-index.html)。第4步设计和第5步轻量功能覆盖核对均已关闭，阶段状态见[根交接入口](../../../../DESIGN-HANDOFF.md)。

2026-09-08 新增的原生 Figma 交付已完成结构与导出核对，见 [原生稿验证](NATIVE-FIGMA-VERIFICATION.md)。下文保留 2026-09-07 本地浏览器验收结果。

日期：2026-09-07。范围：用户授权的本地视觉风格、组件库、设计系统与 D0.2 的 40 页视觉应用。

## 当前结果

|检查|结果与证据|
|---|---|
|原 D0.2 未修改|SHA-256 `61e88a51033fac847d0582492ef2c473785fd66047f640d31e4d47e748b41a11`；生成稿内联业务脚本逐字一致|
|基础规范|56 令牌、8 文字样式；JSON/CSS 同源生成|
|组件结构|8 组件族、85 变体；页面实际渲染数量 85；无重复 ID；输入控件均具标签|
|视口布局|86 组通过：26 PC 页 × 1440/1024，14 小程序页 × 390/320，规范页与组件页各 1440/390/320|
|移动点击目标|390/320 下全部可见 `.mobile button` 至少 44×44；不把这一结果扩大为全部平台无障碍认证|
|页面截图|40 页默认状态真实浏览器截图；画廊 40 张均能载入|
|浏览器错误|本轮 0 pageerror，0 请求失败；样式与脚本成功加载|
|交互检查|13 项通过，见下表|
|配色对比度|7 组通过正文 4.5:1 / 控件边界 3:1 的检查阈值|
|本地 HTML 链接|规范、原型、组件、画廊的 href/src 均可解析到现有文件|

直接通过 file:// 打开组件库也已检查：85 个变体正常渲染，按钮圆角计算值为 8px，无需 HTTP 服务即可查看。

执行工具：已有 Playwright CLI + Chromium，真实本地 HTML 页面，无注入登录状态、无 mock 服务。完整脚本 `scripts/verify-local.js`；结构与配色脚本 `scripts/audit_static.py`。没有为本轮安装依赖。

本轮前预览端口 8784 的旧服务虽持有监听句柄，但实际 HTTP 返回空响应；确认故障后使用 8785 完成全部验证。最初 M12 检查误以为所有情境都要点击上传；查明过期/身份失效切换即出裁决，按真实页面行为调整。M09 首次精确文本断言没有命中段落中的“部分完成”，截图与源码确认正确结果后改为检查完整业务句子，最终完整验收通过。

## 交互结果

|场景|实际观察|
|---|---|
|组件空表单提交|字段 aria-invalid，显示实际位置必填错误，焦点定位到输入框|
|组件提交加载|按钮 disabled/aria-busy，完成后恢复；本页模拟保存，不提交服务端|
|组件资产选择|初始未选中，勾选后显示已选 1 项、未完成接收|
|组件导航|切换待领取，更新 aria-current 和当前入口文本|
|确认框焦点|打开后焦点在取消|
|Escape 取消|关闭对话框，焦点回到触发按钮|
|危险确认|展示对象和处置后果，确认仅更新本页示例结果|
|M09 未选择接收|明确拦截，要求先勾选已核对且收到的资产|
|M09 逐项接收|2 件已收到，1 件无法满足，原申请仍为部分完成|
|M11 更换房间|确认切换后可保存位置差异，待上传变为 3 条|
|M12 迟到|迟到待复核，原任务进度仍 28/40|
|M12 过期|72h 首传过期失效，未计入已盘，提示补盘|
|M12 身份失效|明确拒绝；不再显示原资产证据|

## 视觉检查

人工查看了规范封面、组件库封面、Button 36 变体、Field 15 状态、组合模式，以及 P02/P03/P12/P16、M09/M11/M12 的最终截图。检查中文折行、内容密度、文字与状态层级、焦点外环、边框和独立画布。其他页面执行统一布局检查并保留截图，不声称每页都经过独立人工业务审查。

修正了扫码示意继承草图灰底和固定尺寸的问题，独立画布隐藏评审工具及页尾说明。浅色冷灰画布、白色卡片、深蓝主动作、状态文字和既有页面结构保持一致。390/320 没有页面横向溢出；宽表格使用内部滚动。

## 证据位置

- 默认页面截图：[screens.html](screens.html) / `screens/P01.png` 至 `P26.png`、`M01.png` 至 `M14.png`。
- [浏览器检查 JSON](../../../../output/playwright/fams-ui/local-verification.json)
- [完整 CLI 输出](../../../../output/playwright/fams-ui/local-verification-output.txt)
- [静态结构与对比度报告](../../../../output/playwright/fams-ui/static-audit.json)
- `output/playwright/fams-ui/` 中的 `library-button/field/asset/compositions.png`、`component-danger-dialog.png` 与 `M09-received-local.png`、`M11-room-local.png`、`M12-*-local.png`。

## 适用边界

证明范围是本地 UI 设计、组件表现与 D0.2 示例交互。没有证明真实登录、权限校验、服务端并发、离线持久化、相机扫码、短信、附件或生产上线。配色与键盘抽查不等于完整无障碍认证，字体与安全区仍需实际小程序设备适配。

本节所记 2026-09-07 验收期间尚未写入云端。2026-09-08 的续建结果以 `NATIVE-FIGMA-VERIFICATION.md` 和 `figma-native/` 为准；早期记录在 `figma-state.json` 和 `archive/` 保留。

## Framelink 校准复验（2026-09-07）

通过 Framelink_Figma_MCP 实际读取源结构与下载 3 张原图。源节点引用的 7 个文字样式，其字号、行高、字重与本地逐项一致。浏览器复验确认：PC 字段 44px；Primary Loading 保留 rgb(36, 87, 214)，同时 disabled/aria-busy；Disabled 与 Loading 视觉可区分；移动按钮/输入 48px；页面区块标题字重 500。

来源对照页在 1440/390/320 下均无页面横向溢出，3 张原图全部正常载入。校准后重新运行完整验收，86 组视口、13 项交互通过，40 张页面截图已刷新，0 pageerror / 0 请求失败。

证据：[Framelink 专项报告](../../../../output/playwright/fams-ui/framelink-verification.json)、[来源对照页](figma-reference/index.html)、`scripts/verify-framelink.js`。此次只校准本地文件，未写入云端。
