# FAMS 设计与整改交接 · 2026-09-11

当前状态：**第4步视觉设计与规范已完成。D0.4 本地回归、40页Figma正文同步、当前频道逐页读回、视觉比选和52个移动导航SVG槽接入均已形成证据；D4-01～D4-08在设计声明范围内关闭。第5步已按PRD功能覆盖的轻量口径完成，第6步开发尚未开始。**

截止 2026-09-11，已重新比对本地文件与整改前 ZIP、检查语法及生成数据一致性，完成本地静态/浏览器回归；在实际频道 `v7dkvp2o` 逐页读取并验证 D0.4 正文，重新导出40页 PNG。当前频道的直接结构读回见 [D0.4 直接 MCP 核验](figma-native/d04-native-direct-mcp-verification.json)，文字等价与导出核验见 [D0.4 原生读回](figma-native/d04-native-live-verification.json)，首次增量记录仍保留在 [D0.4 原生同步记录](figma-native/d04-native-direct-sync-evidence.json)。生产/真实设备边界仅说明这些能力尚未实现，属于后续开发与QA，不再作为第4步关闭条件。

## 1. 接手先读

1. 继续项目先读[项目阶段与交接入口](../../../../DESIGN-HANDOFF.md)：它负责八步进度与当前下一步。
2. 第5步结果见[设计稿功能覆盖验证](../../specs/2026-09-11-fams-prototype-validation-usability-test.md)及[覆盖清单](../../specs/2026-09-11-fams-usability-test-matrix.csv)。
3. 需要核对或修改D0.4时再读本文、[整改终态台账](d04-work/progress.md)和[本地修改报告](d04-work/local-implementation-report.md)。
4. [整改裁决](REVIEW-ADJUDICATION-D0.4.md)、[D0.3 原生验证](NATIVE-FIGMA-VERIFICATION.md)和[Figma工具记录](MCP-RESUME.md)均为历史依据，不负责当前阶段状态。

工作目录：`.`。这是文档/原型工作区，没有 Git 仓库；不应虚构 commit、分支或推送记录。

D0.4 后续只在PRD变更或开发发现设计缺口时增量改稿；不从旧计划重新执行已完成的8个任务包，不重建整套文件，不增加业务能力或初始化Git。

## 2. 哪些已经交付

|范围|截至检查点的事实|证据与限制|
|---|---|---|
|产品与信息架构|PRD V1.5；PC P01–P26，小程序 M01–M14|[PRD V1.5](../../specs/2026-09-03-fixed-asset-product-design.md)、[IA 与交互规范](../../specs/2026-09-06-fixed-asset-ia-interaction-design.md)|
|D0.3 本地设计|56 个令牌、8 个文字样式、8 个组件族、85 个变体、40 页原型及截图|旧截图与验证仍对应 D0.3；当前本地源码已混入 D0.4 修改|
|D0.3 原生 Figma|8 个真实 COMPONENT_SET、85 个 COMPONENT、40 个可编辑页面、10 个组合示例|2026-09-09 全量复核记录：1,612 个 TEXT、225 个 INSTANCE、0 整页 IMAGE 填充；[历史验证](figma-native/live-verification.json)|
|D0.3 导出|40 页、8 组件集、10 组合示例实际 PNG 已保存|[原生画廊](figma-native/index.html)、[组合画廊](figma-native/final-compositions/index.html)；不能作为 D0.4 导出|
|D0.4 评审裁决|范围与验收条件已形成，用户已要求实施|[整改建议](REVIEW-ADJUDICATION-D0.4.md)|
|D0.4 本地源码|8 个已有文件修改，3 个状态样张文件新增|[SHA256 差异清单](d04-work/change-inventory.json)；语法通过不代表视觉或交互通过|
|D0.4 Figma|40 个业务页根节点与页面正文已同步为 D0.4；当前频道逐页读回：1,598 个 TEXT、219 个 INSTANCE、0 个图片填充，文字差异 0、越界候选 0；14 个小程序宿主胶囊均为72×28；M02–M14 中存在底部导航的52个导航槽已接入8个真实 SVG 资产，独立扫描为117个 VECTOR并完成52份 SVG 导出；新增状态/字段压力样张和原生说明|[直接 MCP 核验](figma-native/d04-native-direct-mcp-verification.json)、[文字/导出读回](figma-native/d04-native-live-verification.json)、[SVG 样本证据](figma-native/d04-native-svg-import.json)、[导航接入证据](figma-native/d04-native-svg-nav-wiring.json)、[D0.4 导出索引](figma-native/d04-index.html)、`47:6647`样张、`47:6650` Notice、`47:6715` Field、`47:6735`移动说明；生产/设备验收仍未完成；有限回滚摘要见 `figma-native/d04-native-pre-sync-summary.json`|

D0.3 原生页面属于固定视口静态视觉稿。Button、Field、Status 有原生组件/自动布局，复杂页面主要为可编辑 Frame；未新增整页自动布局、Figma 原型跳转或新变量绑定。真实短信、相机、附件、服务端权限、离线存储、并发和生产验收均不在完成声明内。

## 3. Figma 接续坐标与实际改动

文件：[FAMS](https://www.figma.com/design/N4BgxhOTtSFpsoxcN7t4XD)。本轮当前实际频道 `v7dkvp2o`；频道可能随插件重开失效，接手先核对插件实际频道。

|对象|节点|用途|
|---|---|---|
|原基础规范|`4:2`，所在页 `0:1`|现有 Noto Sans SC 字体种子与规范|
|组件页|`3:4`|8 个组件集与 10 个组合示例|
|页面页|`3:5`|P01–P26 / M01–M14，保留原根节点 ID|
|D0.4 校准 Frame|`42:6642`|位于 Screens 页 x=9200、y=400，1000×500；不是新增业务页|
|数字样本|`42:6643`|Inter Semi Bold，32px / 44px；已在 Figma UI 启用 Numbers 的 Monospace uppercase/lining，关闭 Kerning pairs|
|D0.4 状态与字段压力样本|`47:6647`|位于校准 Frame 右侧；含7个既有 Notice 原生实例、Input Focus / Select Error / Textarea ReadOnly 三个 Field 原生实例；明确不计入40页|
|D0.4 移动说明|`47:6735`|M02/M05/M14 的宿主、导航和动作分组说明；当前14页正文另有可读的72×28宿主胶囊图层；8个 SVG 在独立样本 `71:13639` 核验后，已接入 M02–M14 中存在底部导航的52个24×24导航槽，接入节点与导出见 `d04-native-svg-nav-wiring.json`|

[校准节点](https://www.figma.com/design/N4BgxhOTtSFpsoxcN7t4XD?node-id=42-6642) · [种子映射](figma-native/d04-seeds.json) · [数字测量](figma-native/d04-metric-tnum-verification.json)

数字功能通过原生 UI 选择后，以每个数字重复 20 次的克隆文本测量：除 4 为 429px，其余为 430px，差值 1px。只声明设置已开启及测量结果，不声明所有数字测量完全相等。临时测量克隆已移除。Noto Sans SC 14/16 数字样本也有[测量记录](figma-native/d04-numeric-widths.json)。

8个导航 SVG 已通过本地扩展新增的 `create_node_from_svg` 粘贴到独立样本 `71:13639`；样本读回18个 VECTOR并完成8份 SVG 导出。随后以相同真实 SVG 资产替换 M02–M14 中存在底部导航的52个原有24×24占位槽，独立扫描读回117个 VECTOR并完成52份 SVG 导出；M01源页面没有底部导航。D0.4 页面正文已同步，Figma Noto 字体下14页标题 `···` 的右侧溢出已修复并以 [修复记录](figma-native/d04-native-ellipsis-frame-repair.json) 读回确认。

现有根节点映射继续使用 [screens-map](figma-native/screens-map.json)、[component-map](figma-native/component-map.json)、[composition-map](figma-native/composition-map.json)。新增状态样张应另建映射，不能增加 P/M 页面编号或重复创建85变体。

## 4. D0.4 历史执行顺序

以下步骤已经完成，仅用于理解证据、恢复设计，或在PRD变更及开发发现设计缺口后做定向修改。不要把它们当作当前待办重新执行。

### 第一步：先使本地修改形成可验证版本

- 先处理[本地检查问题 H01～H08](d04-work/local-implementation-report.md)，重点是 P23 的 MutationObserver 自触发风险、manifest 与生成数据不一致、状态维度仍依赖文案推断。
- 保留 D0.2 业务源文件与页面/动作钩子，不直接手改生成的 `prototype.html`。
- 修改完成后运行生成脚本；输出当前 D0.4 数据、版本、缓存哈希与清单。先备份旧证据，因为现有回归脚本默认会覆盖 `screens/` 和 `output/playwright/fams-ui/`。

从项目根目录执行，以下命令已在本检查点执行并保留结果：

```sh
rtk proxy python3 docs/superpowers/designs/2026-09-06-fams-ui/scripts/build_local.py
rtk proxy python3 docs/superpowers/designs/2026-09-06-fams-ui/scripts/audit_static.py
rtk proxy python3 -m http.server 8785 --bind 127.0.0.1 --directory docs/superpowers/designs/2026-09-06-fams-ui
```

若 8785 已占用，先确认是不是已有本项目预览服务。页面入口：`prototype.html?page=P03`、`component-library.html`、`d04-states.html`。H01 已修复后，本地浏览器回归结果在 `output/playwright/fams-ui/local-verification.json`，D0.4 状态截图为 `d04-states-1440/390/320.png`。

浏览器沿用 Playwright CLI；[verify-local.js](scripts/verify-local.js) 是 `async(page)` 的 run-code 函数，不是可直接用 Node 执行的独立脚本。本次沿用 `output/playwright/fams-ui/` 保存D0.4回归与截图，同时保留D0.3基线归档。

### 第二步：完成代表页审查及必要回归

- PC 1440/1024：重点 P03/P04/P09/P10/P15/P23–P26 的正文、列宽、数字对齐和操作可见性。
- 小程序 390/320：M02/M05/M14 层级、全部14页导航及触控区、长值/长标签、宿主区和底部安全区。
- 异常状态：无记录、无结果、无权、已处理冲突、扫描失败、无效标签、离线未知；核对文案权限、退路与计数影响。
- 保留原接收/代理接收/盘点负面路径；验证 M12 的 `offline=late/expired/invalid` 入口，以及弹窗初始取消焦点、Tab/Shift+Tab/Escape、关闭后返回触发点。
- 暖灰仅为 `canvas=warm` 候选，M02 渐变仅为 `m02header=gradient` 候选。已完成同视口截图比选并记录取舍；默认规则尚未改为暖灰或渐变。14/20 紧凑表格已在 P03/P23 建立独立候选截图，默认仍保留 14/22，记录见 [视觉比选证据](d04-work/visual-comparison-evidence.json)。

### 第三步：增量同步到既有 Figma（已完成）

1. 重新读取目标页与映射节点，确认当前文件及可能存在的人工修改。创建新的 D0.4 操作日志与回滚快照。
2. 扩展 [capture-figma-layout.js](scripts/capture-figma-layout.js)：识别导航 SVG、宿主胶囊几何、新样张；当前已在 Figma 建立独立 SVG 样本，8个根节点、18个 VECTOR和8份 SVG 导出，并已将52个真实导入根接入 M02–M14 的导航槽；映射和前后导出见 [样本证据](figma-native/d04-native-svg-import.json) 与 [导航接入证据](figma-native/d04-native-svg-nav-wiring.json)。
3. 修正 [native-renderer.mjs](scripts/native-renderer.mjs) 的文字策略：中文克隆既有 Noto Sans SC；32px 指标优先克隆 `42:6643` 以保留已设置数字特性。不可继续静默把主要业务正文压为12px。
4. 已通过 `scripts/sync-d04-pages.mjs` 完成40页正文同步；当前 `scripts/verify-d04-native-live.mjs` 在频道 `v7dkvp2o` 逐页读回，检查文字差异、画板边界、图片填充并重新导出40页。不要重跑旧全量创建脚本；如需继续，只用 D0.4 专用脚本并先建立备份。
5. 保留40页面及8组件集的根节点，明确备份后仅修改受影响的自有内容。新状态示例另区安排，保持可编辑图层，不整页贴图。
6. 页面正文与当前 PNG 已完成；`figma-native/d04-index.html` 是当前 D0.4 画廊，`d04-native-live-verification.json` 是当前读回入口。D0.3 的数量通过不等于 D0.4 视觉/生产通过。

技术续接详见 [MCP-RESUME.md](MCP-RESUME.md)。当前官方 Figma MCP 在本轮触发 Starter 配额限制；本地 TalkToFigma 仍曾成功读写。不要反复重试耗尽配额的官方接口；接手时重新验证可用性，不将历史工具连接视作当前事实。

2026-09-11 当前核验：频道 `v7dkvp2o` 直接逐页读回40个 D0.4 根节点；正文结构总量为1,598个 TEXT、219个 INSTANCE、0个图片填充，14个宿主胶囊均为72×28；文字差异 0、越界候选 0、40张 PNG 已重新导出；修复14页 `···` 标题动作的 Figma 字体溢出并保持16px右内距。独立样本 `71:13639` 导入8个导航 SVG，扫描到18个 VECTOR并完成8份 SVG 导出；随后 M02–M14 中存在底部导航的52个24×24导航槽已接入真实 SVG，扫描到117个 VECTOR并完成52份 SVG 导出。完成 P03/P23/M02 画布/顶部候选及 P03/P23 14/20 表格密度候选比选，默认保留冷灰、纯色和 14/22；新增 P23 1024px 响应式回归和可复现的10个候选 iframe；D0.4 状态页24个示例在1440/390/320均无溢出，焦点/扫描状态通过。生产/真实设备不属于第4步证据，相关边界见[验收边界证据](d04-work/acceptance-boundary-2026-09-11.json)。

### 第四步：关闭整改与交付（已完成）

每个 D4 项要求的实际修改文件、前后截图、Figma 节点、目标视口结果、必要原型交互和未覆盖边界均已记录，D4-01～D4-08已在设计声明范围内关闭。第5步已按PRD功能覆盖口径完成；更深入的真实用户体验优化留到上线后的二期、三期，生产/真实设备属于开发后的QA边界。

## 5. 文档责任与恢复方式

|事实类别|唯一维护入口|状态/修改规则|
|---|---|---|
|业务需求|PRD V1.5 与 IA 规范|用户确定范围；整改不能擅自增加审批、角色和服务能力|
|本轮范围与裁决|REVIEW-ADJUDICATION-D0.4.md|保留裁决理由；进度只链接到台账|
|整改执行状态|d04-work/progress.md|D4-01～D4-08终态台账；后续项目进度不再在此维护|
|本次检查点说明|HANDOFF-D0.4.md|日期限定快照；后续更新须说明新证据时间|
|本地改动证据|d04-work/change-inventory.json|记录2026-09-10源码SHA与轻量检查；后续生成新检查记录|
|D0.3 交付历史|NATIVE-FIGMA-VERIFICATION / VERIFICATION / GOAL-AUDIT 与原导出|保留版本边界，不能把旧“通过”改名为 D0.4 通过|
|当前原生身份|`figma-native/d04-native-live-verification.json`、`d04-index.html`、`screens-map.json` 与 d04-seeds.json；`d04-screen-*.json` 是当前逐页读回|读回确认后维护；覆盖前备份；旧 D0.3 `screen-*.json` 与首次增量记录保留为历史证据|

整改前基线 `2026-09-09-before-d04.zip`：7,720,804 字节；SHA256 `e35675690c9b6b68ffb801e5d032f18e6e1a401a0fe2b40639fb8b339122a8b2`。2026-10-09 解包复核发现包含8个日志/布局转储，原“未包含历史日志/操作流水”结论更正；现已移至仓库外归档，见 [归档恢复说明](archive/README.md)。

[本次 D0.4 源码检查点 ZIP](archive/2026-09-10-d04-source-checkpoint.zip)：仅包含8个已修改源码、3个新增状态文件、差异清单、整改裁决与字体校准证据，不是整个工作区或 Figma 文件备份。交接文档本身在工作目录中维护。

回滚时先解压到独立临时目录，比对后恢复指定文件；不要直接覆盖后续接手人的改动。ZIP 不能自动回滚 Figma；原生恢复须使用节点快照/后续更新前建立的原生备份。

初始交接校验记录为12份文档、106个本地链接无断链，两个ZIP完整性通过；其后新增的 D0.4 SVG 原生导入切片与证据见 [变更清单](d04-work/change-inventory.json) 和 [SVG 样本核验](figma-native/d04-native-svg-import.json)。

## 6. 可直接交给下一位执行者的说明

> 在 `.` 继续项目。先读根目录 `DESIGN-HANDOFF.md`：第1～5步已完成，第6步开发尚未开始。第5步已按用户确认的轻量口径核对PRD V1.5全部一期功能，不恢复大规模用户测试。启动开发后以F01～F10、P01～P26、M01～M14和AC-01～AC-53作为范围；只有PRD变更或开发发现设计缺口时，才用本文的节点与恢复证据增量改稿。
