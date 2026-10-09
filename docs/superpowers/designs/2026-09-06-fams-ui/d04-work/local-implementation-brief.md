# D0.4 local visual implementation brief

> 历史执行说明：本文件是2026-09-09的D0.4实施任务书，不是当前待办。D0.4已于2026-09-11在第4步设计范围内关闭，第5步轻量功能覆盖核对已于2026-09-12完成；当前状态见[项目阶段与交接入口](../../../../../DESIGN-HANDOFF.md)。

Read this first. You own local design sources only; root agent owns all Figma/native tooling and synchronization. User explicitly authorized applying the existing adjudication plan to design file via pmnn5fxj. This directory is not a Git repository. No commits, worktrees or git-only skill scripts. Original D0.2 business source and inline business script must remain unchanged. Preserve 40 page IDs, roles, actions, counts. Reversible visual DOM enhancements may live in visual.js/new d04.js; do not edit business handlers.

Files you may edit: visual.css, visual.js, styles.json/css, tokens.json/css and generation script build_local.py as necessary to keep generated source coherent; library/components.css, components.js, catalog.js/css, manifest.json/js; component-library.html; new d04-states.html/js/css and aesthetic comparison artifacts. Do not edit Figma scripts/native-renderer/capture scripts/figma-native or other docs (root owns). If inserting new script/styles in prototype.html necessary preserve inline business script exactly and tell root. No other subagents.

Implement all local presentation requirements below as one coherent visual source slice. P1 first, P2 afterwards. Root will review rendered results and choose final optional warm/gradient directions. Provide local A/B compare page or query modes for warm canvas vs original and M02 pure vs gradient; make each independently togglable without mutating business data. Keep default baseline color until comparison reviewed by root. Use supported/simple editable SVG or CSS geometry icons, not image/font icon dependencies. Flag capture implications. Keep exact Action hooks/data-go targets for semantic changes to button styling. Ensure idempotent DOM decoration with existing MutationObserver.

New example gallery must expose each example as a capture-ready distinct element with stable data-d04-example ID and sensible 1440 or390 root width; include page-level empty/no-result/no-access/conflict/camera failure and critical scan states, Field stress examples, focus flow and scan motion handoff; examples explicitly simulated. No real camera/permissions/backend calls. Existing M12 scenarios remain linked instead of duplicate implementation. Avoid claiming existing Figma tnum state: root verifies native separately.

Verification: run syntax checks and targeted real browser CLI checks for work you own. Use the locally installed Playwright CLI; do not depend on a prior npm cache path. Local HTTP may be alive127.0.0.1:8785. Use your own named session fams-d04-local so root can independently capture. CLI tool may need sandbox escalation autoapproved for localnetwork. Never programmatic Playwright test runner. Shell commands prefix rtk. Reports should distinguish actual checks from pending integrated checks.

Write full report to d04-work/local-implementation-report.md including files, exact checks and output, unresolved concerns. Return only DONE/DONE_WITH_CONCERNS/BLOCKED and concise summary.

## Binding remediation scope



下列 D4 项均为待实施建议。先修共享规则，再调代表页，最后同步受影响页面与交付证据。每项独立验收，不把一轮视觉修订扩大成业务重构。

### D4-01 / P1：统一表格与主要业务文字

- 范围：TableRow、P03/P09/P10/P15/P23–P26，并按文字使用位置检查其余 PC 页。
- 修改：规范、本地样式、组件样式与 Figma 同步；主表格正文、唯一编码、责任/位置、业务阶段用 14/22，辅助时间说明和短标签可保留 12/20。不要仅把全部字体按比例放大。先用 P03/P23 验证列宽，确有需要再比较 15/24。
- 验收：以实际节点与浏览器计算样式核对，不只检查规范表；关键字段可完整读出，PC 1440/1024 下没有文字覆盖操作，宽表内部滚动。选择 14 或 15 的最终方案在所有来源中一致。

### D4-02 / P1：统一状态维度与语义映射

- 范围：Status/Notice、manifest、visual.js、P03/P04/P09–P12、M02/M04/M11/M12。
- 修改：沿用既有五种 tone，按稳定状态及场景显式指定，避免以任意展示文案决定颜色。补“领用待交接”等组合文案；统一库管处理中等映射；M02 借用逾期提示从普通信息层级调整为需要关注的提示。
- 验收：在库/在用/正常借出不被误读为成功或故障；待交接与已接收分开；“批准”“交出”“接收”不混成完成；带前缀或数量的文案不静默退回错误语义。灰度下凭文字仍能判断状态和下一步。所有新配色重新验证对比度。

### D4-03 / P1：重排 M05/M14 操作与卡片层级

- 范围：M05 个人/借用资产卡、M14 记录列表；M02 只作同风格复核。
- 修改：M05 卡头保留名称、编码、位置/归还期限；概要作为详情入口；常用申请按两列或合适分组排列，风险入口单列低强调。M14 普通记录入口改为带分隔线的导航行，账号动作另组；退出前的数据提示继续可见。
- 验收：390/320 下名称、唯一编码、逾期和未上传提示不被截断；主要动作高 48、触控区至少 44×44，双列长文案允许回单列。相同视口对比首张卡完整内容所需滚动量；所有原有入口仍可找到，权限和动作语义不变。不能以隐藏报废或缩小点击区域来达成“更紧凑”。

### D4-04 / P1：补齐页面异常状态集与扫码说明

复用 Notice 等既有组件，第一批建议增加以下页面状态样张。它们属于原页面的状态，不增加页面编号或业务角色。

|样张|内容和可见退路|
|---|---|
|P03 / 无记录、筛选无结果|分别解释“尚无资产”和“筛选未命中”；无结果提供清除筛选，创建入口仍取决于权限|
|P04 / 无权访问|不呈现受限资产字段及附件，返回可见列表；不能只在完整详情上盖一层提示|
|P09 / 他人已处理|明确本次未保存，展示已处理人、时间与最新只读结论；不给重复批准入口|
|M08 / 相机拒绝或启动失败|给原因与重试/编码查询/返回；取消属于退出，不冒充错误或成功。实施时根据真实 API 区分系统相机界面和自定义扫描容器|
|M11 / 无法继续扫描|保留当前任务、房间与已有待上传记录；提供可用的编码识别退路或返回任务。身份失效仍按既有规则隐藏敏感内容，不因为降级保留访问|

同时给 M08/M11 的无效标签、无权对象、离线未知建立“已有文字规则→实际状态样张/交互情境”的索引；M12 迟到、过期、身份失效直接链接现有情境，避免重复设计。

验收：每一态都有触发条件、显示内容、可用动作及计数影响；本地失败不显示已提交；本地已记录不等于服务端已计入。设计样张、可点击示例、实际相机/服务验收分别记账。完整异常集合按业务路径确定，不被“补 3–4 页”这个数量限制。

### D4-05 / P1：补 Field 排版压力样例

- 范围：Input/Select/Textarea，在组合示例区展示短标签、两行长标签、长位置值、空值和两行错误说明，至少覆盖三种控件与 Focus/Error/ReadOnly。
- 修改：复用现有 15 变体；长文案作为实例属性样例。Select 图标、Textarea 顶部起笔、必填标记与标签保持一致。
- 验收：390/320 和 PC 表单中标签不覆盖控件，错误信息不挤掉操作，聚焦和只读可区分；长值有可读呈现，不能只剩无从核对的省略号。

### D4-06 / P2：统一小程序导航与宿主示意

- 四个 24px 单色图标配文字，选中态同时有品牌色和位置标记；未选中仍可读。保留首页、扫码、我的、消息的顺序。
- 统一顶部系统保留区和微信宿主示意，删除“信号、电量”占位文字；不得因此承诺特定设备已适配。
- 验收覆盖 14 页、长内容、底部安全区及表单键盘；图标不得挤小既有 44px 触控区。不扩展成整套品牌图标库。

### D4-07 / P2：P04/P23 局部布局和交互交付补充

- P04 对基本字段做有意义的双列分组，保持标签与值的阅读顺序；其他页签按内容决定单/双列。
- P23 保留阶段数字、4/2 的含义和各自下钻目标，调整查看按钮重量和文案；不牺牲键盘访问来消除边框。
- 弹窗补焦点顺序说明，扫描补开始/停止/成功/失败及减少动态效果说明。单主动作页面的固定底栏仅在能证明减少滚动且不遮挡时采用。
- 验收：1440/1024 与 390/320 的受影响布局对比；焦点顺序、返回位置和关键动作不因视觉调整改变。


## Aesthetic supplement


2026-09-09 补充输入：用户关于暖灰、状态权重、紧凑排版、等宽数字、边框光影、嵌套圆角及移动渐变顶部的建议。以下 7 项追加到前述 20 项，不改变前一轮业务边界；当前仍为待实施建议。

这一轮允许在现有风格内改善美学。建议方向是：蓝色集中用于主要动作，背景减少冷色偏向；用字号、对齐和空间区分信息，减少重复轮廓。暖灰与渐变是待比较的视觉选择，不能直接推导出缓解视觉疲劳或提高效率的心理学结论。

|编号|建议与事实核验|裁决及落点|
|---|---|---|
|A01|冷灰背景加少量暖调。现有画布为 #F7F9FC；“含 1% 暖灰”没有说明混合色、色彩空间或明确目标值，无法成为可执行规范|条件采纳，P2：保留 #2457D6 品牌蓝及白色内容面，比较现有冷灰与轻微偏暖的近中性画布。#F8F8F6 可作为试色候选，不直接更新令牌；背景、装饰分隔线须成组评估，不能只改一个色值。未验证“降低疲劳”|
|A02|改为浅底深字 Soft Badges。实际 Status/Notice 已采用浅背景：Warning #FFF4DE / #8A5300，Danger #FFF0EF / #B4232E，见 SPECIFICATION 与 components.css|保留已有方案，补充权重规则：普通事实用轻量标签，真正需要处理的异常用警告提示。Dot 可用于少量紧凑状态，须保留文字；不为所有状态加圆点，也不继续淡化错误文字。与 D4-02 合并，不重建变体|
|A03|全局 14px/1.65，应为表格改 1.3–1.4。实际 visual.css 为 14px/1.6，移动内容为 16px/1.65；正式 Body 为 14/22。表格单元格上下各 16px，行内按钮至少 40px，行高并非密度的唯一来源|部分采纳，P1：D4-01 默认仍统一 14/22；高密度 PC 表格可比较 14/20 紧凑方案，并将垂直 padding 从 16 比较到 12。先调整垂直空间、列宽与按钮占位，不强制全部 1.3。多行说明、中文表单和移动正文保留舒适行高，不新增用户密度切换功能|
|A04|启用等宽数字。Metric、数量卡已有 tabular-nums，表格列未见统一数值对齐规则；Figma 全部数字是否启用对应字形功能，现有快照不足以证明|采纳，P1，并入 D4-01：金额/数量按列右对齐，金额统一精度和单位；资产编号作为身份列保持左对齐，编号中的数字使用等宽数字。检查实际字体支持及 Figma 文字样式/字形设置；不能仅新增一个名为 tnum 的 Token 就声称生效，也不把资产编号自动改成货币式右对齐|
|A05|减少硬边框，以白色块和柔和阴影区分层级。P23 有外层 panel 和内层 tablewrap 的双重轮廓；M05/M14 卡内又叠多个按钮框，重复边界确实可见|采纳减少重复边框，P1，并入 D4-03/D4-07。普通容器优先色块+间距；表格保留必要行分隔、输入保留可识别边界、选中与焦点标记保留。阴影只在确需表达抬升的容器试用，不能把每个边框换成一个阴影；“2–4px”未指定模糊、扩散或位移，不能直接作为完整阴影规范|
|A06|所有外层圆角应等于内层圆角加 Padding|不采纳通用强制公式，采纳针对性检查。公式适合等距、同心的相邻轮廓；有标题、文字、较大留白的卡片和独立按钮不属于同一个轮廓。保留卡片 12、控件 8；只修紧贴嵌套面、选中内框及裁切处的角部间距。不能因 16px padding 就把所有外卡片改成 24px 圆角|
|A07|M01–M14 用沉浸式渐变 Header 建立首屏焦点|局部试用，P2：仅在 M02 内容顶部比较淡蓝到画布色的静态微渐变，与纯色版本比较；保留问候、当前待办和关键数量，不扩大装饰高度。M03/M09/M11 以表单、待接收/任务信息为焦点，M05 以资产身份为焦点，不统一套大 Header。产品内容渐变与系统状态栏、微信宿主区域分别定义|

### 7.1 美学实施口径

**色彩：**主色不急于更换。先比较画布冷暖，再处理蓝色出现面积与警告出现频率。区分“降低背景饱和度”和“降低文字对比度”，不能将二者混为一谈。暖灰、淡蓝顶部同时使用时，要检查过渡是否干净，避免灰底偏脏。

**节奏：**默认正文 14/22；紧凑表格 14/20 仅作候选。建议行高按最高内容计算，而非强制固定行高：`行高 ≥ max(文本块高度, 控件高度) + 上下内边距 + 分隔线`。例如 40px 按钮在行内时，单纯把文字行高从 22 改成 20 不会使该行变矮。先对同一数据量的 P03/P23 做比较，不能通过减小编码字号来增加行数。

**数字：**等宽数字与列对齐共同落地。金额保持相同小数精度，单位放在列头或一致位置；数量用一致的位数分组规则；日期格式统一。`tabular-nums` 控制数字字形宽度，不会自动对齐小数点，也不会把字母、连字符全部变成等宽。参考 [MDN：font-variant-numeric](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Properties/font-variant-numeric)。Figma 与 Web 分别以实际文字测量核验。

**容器：**一层视觉分组优先用一种主要边界表达。白底卡片已足以分组时不再叠重边框和阴影；卡片中的表格、导航列表可取消重复外框，保留行分隔。独立输入框、可选择资产卡的交互边界不随装饰框一起删除。浮层与普通内容卡继续区分。

**圆角：**以两个实际轮廓间的距离判断同心关系，不把 CSS padding 与轮廓距离无条件等同。CSS 自身的边框内缘半径会扣除边框厚度，内容边缘再考虑 padding；这不意味着任意后代按钮必须按该规则改变半径。几何依据见 [W3C：Corner Shaping](https://www.w3.org/TR/css-backgrounds-3/#corner-shaping)；对子元素的适用范围是本轮设计判断。

**移动焦点：**M02 先凸显“现在需要处理什么”，再考虑渐变。M05 通过卡头与操作分组形成焦点；M11 通过当前房间、资产身份和一个主动作形成焦点。背景装饰不能与这些内容争夺注意，也不能推迟首个实际操作出现的位置。

### 7.2 新增 D4-08 / P2：少量代表页视觉比选

本项用于决定 A01 与 A07 的试色是否采用，不扩展成品牌重塑项目。

1. 先完成 D4-01 的字号统一，再以同样的内容、视口和缩放比较样稿，避免将字体差异误认为配色收益。
2. P03/P23 比较当前画布与近中性微暖画布；逐项比较容器减框效果。M02 比较纯色与微渐变顶部；M05/M14 检查分组节奏。每次改变一个主要变量，不一次把配色、字号、圆角和阴影全部改掉。
3. 保留既有关键字段、待办数量、警告、动作与首屏内容量。检查 1440/1024、390/320，比较对齐、灰度层级、文字可读性、容器边界和滚动长度。渐变上有文字时检查其覆盖范围内最不利背景的对比度。
4. 视觉选择由实际样稿比较得出；没有用户实验就不写“显著提升效率/降低疲劳”。选定后才更新令牌、规范、本地页面、Figma 节点和导出图。本轮尚未生成这些对比稿，也未改动色值或画板。

新增 A01–A07 已纳入整改记录；D4-01、D4-02、D4-03、D4-07 按本节补充，新增 D4-08。以上为任务下达时的历史口径；当前完成结果见[终态台账](progress.md)，不要据此重启已完成施工。
