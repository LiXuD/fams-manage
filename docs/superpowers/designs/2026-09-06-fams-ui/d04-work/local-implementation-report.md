# D0.4 本地修改与接手检查

日期：2026-09-11。此文件记录第4步D0.4源码整改与可复核证据；本地生成、静态审计、浏览器回归和Figma正文读回均已形成结果，D0.4设计范围现已关闭。第5步轻量功能覆盖核对已于2026-09-12完成，现役进度见[根交接入口](../../../../../DESIGN-HANDOFF.md)；本文件不负责后续开发或QA状态。设计证据见[直接MCP核验](../figma-native/d04-native-direct-mcp-verification.json)、[D0.4原生读回](../figma-native/d04-native-live-verification.json)和[视觉比选](visual-comparison-evidence.json)。

## 已落盘的内容

|文件|实际差异|
|---|---|
|visual.css|主表格14/22、上下12px padding、列对齐；移动导航、胶囊；M05/M14/P04/P23局部样式；暖灰/M02渐变候选|
|visual.js|tone表和文案匹配；状态维度推断；SVG导航和宿主装饰；M05/M14/P04/P23装饰；M12离线参数入口|
|library/components.js / components.css|组件库移动图标、表格数字列、只读长值样式|
|library/catalog.js|新增Input Focus、Select Error、Textarea ReadOnly压力样例|
|library/manifest.json|D0.4版本、排版/导航/异常规则和页面映射|
|component-library.html|组件库入口文案/链接调整|
|scripts/build_local.py|D0.4版本与表格规则已生成；source SHA 与 D0.2 业务脚本一致|
|d04-states.html / js / css|9个页面状态样张、3个Field样例、2个交互示例、10个对比画布；M12既有情境链接；表格候选通过`tableDensity`查询参数复现|

数量是源码中的声明数量；本地浏览器回归已核对85个变体、40张画廊图片和代表页面/状态样张。页面状态中的按钮大多只是静态动作说明；弹窗和扫描演示有本地事件，无真实服务。

## 优先问题与未验证项

|编号|证据/状态|接手处理与验收|
|---|---|---|
|H01|已修复：P23按钮仅在标签变化时写入，避免MutationObserver对自身文本更新持续触发|`output/playwright/fams-ui/local-verification.json`结构/交互检查通过；P23布局无溢出，仍保留静态模拟边界|
|H02|已处理：运行`build_local.py`后，生成数据与manifest/源码基线一致|`scripts/audit_static.py`通过：56 tokens、40 pages、8 families、85 variants、40 screenshots、0 broken links；SHA与D0.2业务脚本一致|
|H03|已处理：visual.js改为显式domainByLabel/pageDomain并补充前缀/数量匹配|`scripts/verify-d04-domains.js`覆盖40页；Figma D0.4 正文读回文字差异 0|
|H04|新P03无记录样张写“创建入口仍按库管员权限显示”，并静态显示创建按钮；简化pcShell把P09标在工作台|对照PRD核实创建权限、页面壳与可见动作。不将样张说明误当已实施权限；本次未裁定这些业务文案正确|
|H05|已修复：阅读顺序与Tab/焦点顺序在交付样张中分开记录|本地确认框交互检查通过：初始焦点取消、Tab/Shift+Tab、Escape关闭并返回触发按钮|
|H06|本地SVG几何已统一到24px线性图标定义；另在 Figma 独立样本 `71:13639` 通过 `create_node_from_svg` 导入8个图标，并接入 M02–M14 中存在底部导航的全部52个24×24导航槽|样本读回18个 VECTOR、8个导入根、8份 SVG 导出；正文导航另读回117个 VECTOR、52份 SVG 导出且无图片填充，详见 `../figma-native/d04-native-svg-import.json` 与 `../figma-native/d04-native-svg-nav-wiring.json`|
|H07|已完成本地回归：响应式CSS、Field压力样例、扫描状态、M12负面入口均有浏览器证据|`local-verification.json`记录40页画廊、1440/1024/390/320无溢出、85变体和13项交互；另有80个页面/视口实时浏览器检查，0 console error/warning、0 request failure。主机虽有 iOS Simulator runtime，但当前无 FAMS 可安装项目、无 booted simulator，`adb` 也未安装；真实设备/相机仍不在范围|
|H08|暖灰、渐变、M05/M14 分组以及 P03/P23 的 14/20 紧凑表格均已有同视口截图与取舍|`visual-comparison-evidence.json`记录候选选择，默认保留冷灰/纯色/14px/22px；不把候选截图当可用性研究|

## 本次实际检查结果

- `node --check`：visual.js、components.js、catalog.js、d04-states.js、local-figma-client.mjs 均退出0。
- `build_local.py` 已运行；`audit_static.py`通过，结果为56 tokens、40 pages、8 families、85 variants、8 typography styles、40 screenshots、0 broken links，7组对比度均通过。
- 原D0.2与当前prototype内联业务脚本一致，原始文件SHA256仍为 `61e88a51033fac847d0582492ef2c473785fd66047f640d31e4d47e748b41a11`。
- `local-verification.json`记录85个变体、0重复ID、0未标注输入、40张画廊图片；PC 1440/1024与移动390/320均无横向溢出，错误/请求失败为空。
- 2026-09-11 新鲜浏览器回归见 [browser-verification-2026-09-11.json](browser-verification-2026-09-11.json)：D0.4 样张24个、候选iframe 10个，1440/390/320均无溢出；焦点/扫描状态通过；P23 1024列宽和阶段操作链接通过。
- `d04-states-1440.png`、`d04-states-390.png`、`d04-states-320.png`与本地输出已保存；Figma样张和M02导出已在当前频道直接视觉检查。
- Figma D0.4 页面正文逐项同步已完成并由当前频道逐页读回：40 页、文字差异 0、越界候选 0、图片填充 0；14 个移动页的宿主胶囊几何也已核对，且修复了 Figma Noto 字体下 `···` 标题动作溢出并保持 16px 右内距。另在独立样本 `71:13639` 实际导入8个 SVG，扫描到18个 VECTOR并完成 PNG/SVG 导出核对；同一批真实 SVG 已接入 M02–M14 中存在底部导航的全部52个正文导航槽，扫描到117个 VECTOR并完成52份 SVG 导出；M01源页面没有底部导航。服务端权限/并发/真实设备验收仍未完成，不能将本地或样张结果扩大为生产通过。
- P03/P23 的 14/20 表格密度候选已用相同数据和视口截图比选；紧凑版仅略微减少纵向占用，默认仍保留 14px/22px，以阅读节奏优先。
- 候选截图通过 [capture-d04-table-candidates.js](../scripts/capture-d04-table-candidates.js) 以 `tableDensity=default|compact` 实际加载生成；哈希与[视觉比选证据](visual-comparison-evidence.json)一致。

## 当前新增证据

- `../figma-native/d04-native-live-verification.json`：2026-09-11，频道 `v7dkvp2o`，40 页当前节点逐页读回、文字/边界/图片填充检查、40 张当前 PNG 导出、代表页重导出路径及生产/SVG 边界。
- `../figma-native/d04-native-direct-mcp-verification.json`：2026-09-11 10:48（+08:00），当前频道直接结构读回以及独立 SVG 原生导入样本核验。
- `../figma-native/d04-native-svg-import.json`：独立样本 `71:13639` 的8个 SVG 导入根、18个 VECTOR、8份 SVG 导出和无图片填充检查。
- `../figma-native/d04-native-svg-nav-wiring.json`：M02–M14 中存在底部导航的52个正文导航槽、52个真实 SVG 导入根、117个 VECTOR、52份 SVG 导出、源文件 SHA-256 与无图片填充检查；M01源页面没有底部导航。
- `../figma-native/d04-index.html`：当前 40 页原生 PNG 画廊，1440/390 实际浏览器加载 40/40、无横向溢出、无 console error/warning。
- `visual-comparison-evidence.json`：P03/P23 冷灰 vs 微暖、M02 纯色 vs 微渐变、M05/M14 分组及 P03/P23 14/20 表格密度审查。
- `browser-verification-2026-09-11.json`：本次生成后的本地构建、状态画廊、P23 1024响应式和候选iframe回归摘要。
- `acceptance-boundary-2026-09-11.json`：只读探测确认当前工作区没有生产服务、客户端/小程序运行包或真实设备 harness；列出真实服务、权限/并发/离线与设备验收的关闭条件。
- 2026-09-11 11:20–11:29（+08:00）复核：运行边界探测仍为“只有静态预览、无可执行 FAMS runtime”；TalkToFigma `v7dkvp2o` 的 D0.4 页面、SVG样本、52槽导航和组件/组合核验均重跑通过。一次未设置 `FAMS_FIGMA_CHANNEL` 的 `verify-native-live.mjs` 调到了历史默认频道并超时，已用明确频道重跑通过；不作为产品缺陷或验收通过依据。随后用真实 Playwright CLI 复核 P23 1024px 和 D0.4 状态页三种视口，结果已追加至 `browser-verification-2026-09-11.json`。
- `../figma-native/d04-native-ellipsis-frame-repair.json` 与 `../figma-native/d04-native-live-pre-ellipsis-frame-repair.json`：14 个移动页标题 `···` 真实节点修复前后证据；当前每个文本右内距为 16px。

可复核文件清单和哈希见 [change-inventory.json](change-inventory.json)。
