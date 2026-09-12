# FAMS D0.3 原生 Figma 验证

> 版本边界（2026-09-12）：正文保留D0.3原生验证历史；D0.4设计整改、正文同步和导航SVG接入已在第4步关闭，终态见[HANDOFF-D0.4](HANDOFF-D0.4.md)。第5步功能覆盖核对已完成，当前进度见[根交接入口](../../../../DESIGN-HANDOFF.md)。

> 当前 D0.4 读回（2026-09-11）不改写下面的 D0.3 历史结果：频道 `v7dkvp2o` 已逐页读回并重新导出40页 D0.4 正文，文字差异 0、越界候选 0、图片填充 0；另在独立样本 `71:13639` 导入8个 SVG并核验18个 VECTOR与8份 SVG 导出，再接入 M02–M14 中存在底部导航的52个正文导航槽并核验117个 VECTOR与52份 SVG 导出。证据入口为 [D0.4 原生读回](figma-native/d04-native-live-verification.json)、[SVG 导入证据](figma-native/d04-native-svg-import.json)、[正文导航接入证据](figma-native/d04-native-svg-nav-wiring.json) 与 [D0.4 导出索引](figma-native/d04-index.html)。真实服务与设备属于后续开发和QA，不是本设计记录的关闭条件。

日期：2026-09-08。执行范围：用户授权加入 `ju3ayfbw`，沿用既有风格补齐原生组件与 40 页设计。

2026-09-09 按最新指令加入 `pmnn5fxj`，重新读取实际 Figma 页面和组件：8 个原生组件集、85 个变体、40 页、10 个组合示例均存在，40 页图层与最终导出时的信息完全一致。当前连接证据见 [live-verification.json](figma-native/live-verification.json)。

[Figma 40 页](https://www.figma.com/design/N4BgxhOTtSFpsoxcN7t4XD?node-id=3-5) · [Figma 组件库](https://www.figma.com/design/N4BgxhOTtSFpsoxcN7t4XD?node-id=3-4) · [实际导出索引](figma-native/index.html)。

|检查|实测结果|
|---|---|
|组件类型|8 个 COMPONENT_SET、85 个 COMPONENT；逐个 ID 与组件映射一致|
|组件覆盖|Button 36、Field 15、Status 5、Navigation 9、TableRow 5、Notice 7、Quantity 3、AssetCard 5|
|组合模式|Selection、Steps、Dialog、Progress、Timeline 五类及补充样例，共 10 组可编辑 Frame|
|页面数量|PC P01–P26 共 26 页，小程序 M01–M14 共 14 页，均在 Screens & Handoff|
|可编辑内容|40 页共 4,381 个节点，含 1,612 个 TEXT、225 个 INSTANCE；图像填充为 0|
|文字一致性|40 页按图层顺序归一化空白后，与本地设计捕获的文字逐页一致，差异 0|
|画板尺寸|PC 宽 1440，小程序宽 390；高度跟随原设计内容，逐页误差小于 0.1px|
|画板位置|40 个画板互不重叠；最终文字外框超出页面的候选数 0|
|真实导出|40 张页面 PNG、8 张组件集 PNG、10 张组合示例 PNG 均由实际 Figma 节点导出|
|交付画廊|1440/390 两个视口下，40 页画廊和 10 例组合画廊图片全部载入，无横向溢出，0 页面脚本错误|
|原始业务基线|D0.2 SHA-256 仍为 `61e88a51033fac847d0582492ef2c473785fd66047f640d31e4d47e748b41a11`，本地生成稿内联业务脚本一致|

实际查看了全部 8 类组件导出、PC 工作台/台账/交出/差异复核、小程序申请/资产/接收/回执，以及两类确认框、步骤与履历。逐页结构、文字、画板边界检查覆盖全部 40 页；不把代表页视觉检查扩大为每页独立业务审查。

本轮修正了统计卡被按普通按钮实例处理、中文长句换行、标题编号裁切、行内文字重叠、字段下拉标记和文本域顶对齐、小程序顶部省略号宽度，以及弹窗动画采样导致的错位。默认 Inter 无法完整输出中文，中文沿用既有 Noto Sans SC 图层；数字与部分符号采用 Inter。导出后的校验使用实际节点信息。

## 可复核证据

- [native-audit.json](figma-native/native-audit.json)：40 页文字、数量、实例、尺寸与边界核对。
- [画廊浏览器验证](figma-native/gallery-verification.json)：桌面和手机宽度的实际加载与布局检查。
- [组件映射](figma-native/component-map.json)、[页面映射](figma-native/screens-map.json)、[组合映射](figma-native/composition-map.json)。
- `figma-native/native-sets-verified.json`、`native-components-verified.json`、`native-page-structure.json`：本地 MCP 实际返回。
- `figma-native/screen-P01.json` 至 `screen-P26.json`、`screen-M01.json` 至 `screen-M14.json`：逐页图层信息。
- `figma-native/operations.jsonl` 与写入步骤台账：记录节点创建、更新与导出过程。
- `figma-native/d04-native-svg-nav-wiring.json`：M02–M14 52 个正文导航槽的真实 SVG 节点、VECTOR 扫描、SVG/PNG 导出与源资产哈希；M01源页面无底部导航。

只读核对命令：

```sh
rtk proxy node docs/superpowers/designs/2026-09-06-fams-ui/scripts/audit-native-delivery.mjs
rtk proxy python3 docs/superpowers/designs/2026-09-06-fams-ui/scripts/audit_static.py
```

## 交付边界

页面是可编辑的静态视觉设计，采用固定视口坐标；Button、Field、Status 保留原生组件与自动布局结构，复杂页面不是整页自动布局组件。交互情境与键盘行为使用本地 `prototype.html` 和组件库演示，没有在 Figma 新增原型跳转连线。

新图层采用现有令牌的实际色值；现有 MCP 没有变量创建、别名更新或绑定接口，因此未新增变量绑定。部分浏览器 13px/26px 文字收敛到现有 Figma 12px/24px 层级，保留中文内容并重新处理换行。原生稿与本地截图不是逐像素相同的渲染。

本轮证明设计文件、原生图层和导出结果；真实账号、服务端权限、并发、扫码设备、离线持久化等仍属于后续产品实施，不在此验收范围。
