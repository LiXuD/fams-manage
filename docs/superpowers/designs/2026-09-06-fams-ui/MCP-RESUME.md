# Figma 本地 MCP 续建记录

> 2026-09-12终态说明：本文主要保留D0.3时期的Figma工具记录，频道与工具可用性均可能失效。D0.4的40页正文、状态样张和52个导航槽已经完成同步与读回；第5步功能覆盖核对也已完成。继续项目先读[根交接入口](../../../../DESIGN-HANDOFF.md)，只有PRD变更或开发发现设计缺口时才使用本文的工具线索，并先重新核对频道与节点。

最新连接：2026-09-09 `pmnn5fxj`。已重新读取并核验全部 85 个原生组件、40 页和 10 个组合示例，页面与最终导出一致。当前脚本默认使用此频道，也可通过 `FAMS_FIGMA_CHANNEL` 指定。

2026-09-08 已按用户最新指令连接 `ju3ayfbw`，并完成本轮原生组件和页面写入。此前 `0hh48l21` 的超时状态已经被本轮成功读写替代。

目标文件：[FAMS D0.3](https://www.figma.com/design/N4BgxhOTtSFpsoxcN7t4XD)。通过既有 Foundations、Components、Screens & Handoff 页面及节点身份核对目标；本地 MCP 未直接返回 fileKey。

- 8 个原生组件集、85 个原生变体，见 `figma-native/component-map.json`。
- 40 个可编辑页面画板，见 `figma-native/screens-map.json`。
- 10 个组合示例，见 `figma-native/composition-map.json`。
- 实际 PNG 导出、图层信息、结构和文本核对见 `figma-native/`。

本轮没有修改本地 MCP 服务或 Figma 插件代码；通过其现有接口写入，借助克隆既有原生组件保留 COMPONENT / COMPONENT_SET 类型。没有把普通 Frame 冒称原生组件，没有以整页图片替代编辑图层。新变量绑定不在该 MCP 接口能力内。

后续如需改稿，先核对现有映射与实际节点，再修改指定页面，避免重新批量创建。`scripts/local-figma-client.mjs` 保存本轮频道，频道变更时需要更新；各写入脚本有持久化步骤记录，不应删除记录后重新跑整批。审计脚本 `scripts/audit-native-delivery.mjs` 是只读本地证据核对，可直接执行。

交付范围与验证结果见 [NATIVE-FIGMA-VERIFICATION.md](NATIVE-FIGMA-VERIFICATION.md)。

## D0.4 工具接续注意事项

- `scripts/local-figma-client.mjs` 从项目根目录运行，使用本机Bun和既有MCP SDK；依赖路径写在文件中，换机器需核对。默认频道pmnn5fxj，可用FAMS_FIGMA_CHANNEL覆盖。插件保持连接，先读节点确认文件。
- 官方Figma MCP在本轮读取后触发Starter配额限制，本地TalkToFigma此前仍可读写；不要反复撞相同配额。原插件和MCP服务代码没有被修改。
- 中文优先克隆Foundation中的Noto Sans SC；现有body14/22可参考4:57，body-medium14/22为6:7，16/26为4:54，24/36为4:16。32/44数字种子为42:6643。使用前读回核对字体及样式。
- CUA原生Figma UI本轮已成功操作数字设置。每次UI动作后刷新AX状态；MCP改变选区后也要刷新，不能复用旧索引。数字功能不能靠名称或只读OpenType字段假装写入。
- `verify-native-live.mjs`对Figma只读，但会覆盖本地screen JSON和live-verification缓存；运行前备份。D0.4当前证据由专用读回文件维护，不以D0.3脚本结果替代。
- 旧native-renderer/complete-native-screens等含持久化操作记录；不要删除日志再重跑，也不要套旧日志跳过新树。D0.4专用同步和读回脚本已经存在，改稿前先读`HANDOFF-D0.4.md`中的当前映射与备份规则。
- 校准节点见 `figma-native/d04-seeds.json`；独立SVG样本与52个正文导航槽已接入，证据见`d04-native-svg-import.json`和`d04-native-svg-nav-wiring.json`。校准Frame不计入40业务页。
