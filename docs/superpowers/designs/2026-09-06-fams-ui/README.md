# FAMS UI 设计 · D0.3 交付与 D0.4 整改

沿用已有浅色深蓝风格，完成 D0.2 的视觉设计与规范阶段。需求基线 PRD V1.5；40 页与业务状态机保持原样，原始 D0.2 文件未修改。2026-09-08 已通过本地 TalkToFigma MCP 补齐原生 Figma 组件与页面。

2026-09-09 已通过最新频道 `pmnn5fxj` 重新核验全部原生组件与页面，确认与最终交付一致。

2026-09-09 外部评审已完成逐项裁决，见 [D0.4 整改建议](REVIEW-ADJUDICATION-D0.4.md)：纠正现状误判，明确采纳项、优先级与验收条件；2026-09-09 已获用户授权开始实施。

**当前进度（2026-09-12）：第4步视觉设计与规范已完成，D0.4 本地回归、40页Figma正文同步、当前频道直接读回、视觉比选及52个移动导航SVG槽接入均有证据。第5步已按PRD功能覆盖的轻量口径完成，第6步开发尚未开始。项目总状态与接手顺序见[根交接入口](../../../../DESIGN-HANDOFF.md)，第5步结果见[功能覆盖验证](../../specs/2026-09-11-fams-prototype-validation-usability-test.md)。**

## 打开设计

- [Figma 原生稿与实际导出索引](figma-native/index.html)：8 个原生组件集、85 个变体、40 个可编辑画板。
- [D0.4 当前原生页面导出](figma-native/d04-index.html)：当前 TalkToFigma 频道读回的 40 页正文、逐页 PNG 与 Figma 节点链接。
- [Figma 40 页](https://www.figma.com/design/N4BgxhOTtSFpsoxcN7t4XD?node-id=3-5) / [Figma 组件库](https://www.figma.com/design/N4BgxhOTtSFpsoxcN7t4XD?node-id=3-4)。
- [设计系统总览](index.html)：视觉方向、色彩、字体、间距与页面入口。
- [D0.4 状态与视觉比选](d04-states.html)：含状态、Field、焦点/扫码交互和10个同视口候选 iframe；取舍记录见 [视觉比选证据](d04-work/visual-comparison-evidence.json)，本地回归摘要见 [browser-verification-2026-09-11.json](d04-work/browser-verification-2026-09-11.json)。
- [D0.4 SVG 原生导入证据](figma-native/d04-native-svg-import.json)：独立样本 `71:13639`，8 个导入根、18 个 VECTOR、8 份 SVG 导出。
- [D0.4 正文导航接入证据](figma-native/d04-native-svg-nav-wiring.json)：M02–M14 中存在底部导航的52个24×24导航槽，117个 VECTOR、52份 SVG 导出与源资产哈希；M01源页面没有底部导航。
- [完整组件状态库](component-library.html)：8 个组件族、85 个显式变体、5 类组合模式及交互试验区。
- [40 页视觉画廊](screens.html)：PC 26 页、小程序 14 页，每页有截图和独立画布入口。
- [可点击视觉原型](prototype.html?page=P02)：保留 D0.2 的情境选择和局部业务交互。
- [独立工作台画布](prototype.html?present=1&page=P02)：隐藏画布外的评审工具。

直接双击 HTML 即可使用。也可在项目根目录执行：

```sh
rtk proxy python3 -m http.server 8785 --bind 127.0.0.1 --directory docs/superpowers/designs/2026-09-06-fams-ui
```

然后打开 [本地预览](http://127.0.0.1:8785/index.html)。无需安装依赖或登录。

## 文件与使用

|文件|用途|
|---|---|
|SPECIFICATION.md|视觉规则、组件契约、状态语言和页面模板|
|DEVELOPMENT.md|原生 DOM/CSS 组件接入、属性回调与重建方式|
|tokens.json / tokens.css|56 个基础及语义令牌，同源生成|
|styles.json / styles.css|8 个文字样式及阴影、动效、布局规则|
|library/manifest.json|组件族、变体轴、属性与页面示例映射|
|library/components.js / components.css|可复用组件实现，无框架依赖|
|visual.css / visual.js|原型视觉层与独立预览模式|
|page-inventory.json|40 页编号、角色、规则、预览与截图映射|
|screens/|40 张真实浏览器截图|
|VERIFICATION.md / GOAL-AUDIT.md|D0.3 历史验证范围；当前 D0.4 证据见交接与 `d04-work/`|
|source-baseline.json|原始 D0.2 摘要与业务脚本一致性|

页面默认样例中的数量和身份为虚构数据。此交付用于 UI 评审与前端实施；短信、相机、附件上传、服务端权限、离线持久化和并发控制未实现。

## Figma 来源、续建与验证

2026-09-07 通过 Framelink_Figma_MCP 实际读取既有文件，下载基础规范、按钮和字段 3 张原图，并校准本地字重、字段尺寸与加载态。[当时的来源对照](figma-reference/index.html) 保留为历史快照。

2026-09-08 按用户指令连接 `ju3ayfbw`，实际写入 8 个原生组件集、85 个变体、40 个画板与 10 个组合示例。D0.3 历史交付保留原始数量证据；2026-09-11 在当前 TalkToFigma 频道 `v7dkvp2o` 对 D0.4 40 页正文逐页直接读回并重新导出，正文结构证据为 1,598 个文字图层、219 个组件实例、0 个图片填充；文字差异和越界候选均为 0。另在独立样本 `71:13639` 导入8个导航 SVG，扫描到18个 VECTOR并完成8份 SVG 导出，再将同一资产接入 M02–M14 中存在底部导航的52个正文导航槽，扫描到117个 VECTOR并完成52份 SVG 导出。P03/P23 的 14/20 表格密度候选已完成同视口比选，默认保留 14px/22px。详见 [直接 MCP 核验](figma-native/d04-native-direct-mcp-verification.json)、[SVG 样本证据](figma-native/d04-native-svg-import.json)、[正文导航接入](figma-native/d04-native-svg-nav-wiring.json)、[D0.4 原生读回](figma-native/d04-native-live-verification.json) 与 [D0.4 导出索引](figma-native/d04-index.html)。

原生页面是固定视口的静态视觉稿，交互情境仍在本地 `prototype.html`；不把页面 Frame 声称为整页自动布局组件。Button、Field、Status 保留原生组件和自动布局结构，其余复杂内容使用可编辑分组。新图层沿用令牌色值，未新增变量绑定；中文按现有 Noto Sans SC 字号层级校准。

`figma-state.json`、原 Figma API 辅助脚本和 `archive/` 保留历史记录。当前 Figma 节点以 `figma-native/component-map.json`、`screens-map.json`、`composition-map.json` 和 `figma-local-mcp-state.json` 为准。

当前边界：8 个 SVG 已通过本地扩展提供的 `create_node_from_svg` 在独立 Figma 样本中验证，并已接入 M02–M14 中存在底部导航的全部52个正文导航槽；M01源页面没有底部导航。工作区是本地静态原型/设计文档，没有生产后端、真实账号权限、相机或真实设备验收环境；只读探测与关闭条件见[验收边界证据](d04-work/acceptance-boundary-2026-09-11.json)。
