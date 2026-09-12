# FAMS D0.3 视觉设计规范

> 版本边界（2026-09-11）：正文保留D0.3规范基线；D0.4设计整改及原生同步已在第4步关闭，终态见[HANDOFF-D0.4](HANDOFF-D0.4.md)。当前项目阶段与下一步见[根交接入口](../../../../DESIGN-HANDOFF.md)。

日期：2026-09-07。需求基线 PRD V1.5，页面及交互基线 D0.2。此稿为视觉评审稿，不新增功能、角色或审批层级。

## 1. 方向与适用边界

沿用现有设计文件中的浅色深蓝方向：蓝色主操作、冷灰背景、白色内容面，统一处理 PC 密集信息与小程序现场操作。本地规范与可点击原型已完成；2026-09-08 按用户续建指令同步补齐原生 Figma 组件与 40 页静态视觉稿。

视觉不改变权限和业务含义。审批按钮写“批准申请”，库管动作写“开始处理”“记录交出”，员工动作写“确认收到所选资产”。不得全部简写成“确认”或使用同一个绿色勾表示三种业务已完成。

## 2. Token 与主题

56 个变量：18 个原始颜色、19 个语义颜色、8 个间距、4 个圆角、7 个尺寸。历史 Figma 快照中两个集合分别为 Primitives / Value 与 Semantics / Light。语义颜色引用原始颜色，不重复维护十六进制值。所有变量有作用域与 `var(--fams-...)` Web 语法。

本轮仅提供 Light，不承诺暗色主题。`tokens.json` 是可读交付格式，不声称符合某版外部 token 标准。`tokens.json` 与 `tokens.css` 从同一构建脚本生成，本地值是实施依据。

本地 QA 将 `border/strong` 从 gray300 (#9AA8BA) 改为 gray500 (#53657D)，在白底对比度从 2.42 提高到 5.96，供可交互控件边界使用；装饰性分隔线仍为 border/default。本次 Figma 字段与页面控件已使用该实际色值；本地 MCP 不提供变量别名和绑定接口，因此没有声称同步了变量集合中的别名关系。

|用途|前景|背景|规则|
|---|---|---|---|
|主要操作|#FFFFFF|#2457D6|页面一个主要业务动作|
|正文|#18263D|#FFFFFF / #F7F9FC|不使用浅灰承载正文|
|辅助文字|#53657D|#FFFFFF / #F7F9FC|说明、时间、次级元数据|
|已完成结果|#206A45|#E8F5EE|仅用于已确认的结果|
|注意/待处理|#8A5300|#FFF4DE|待交接、异常待复核等|
|错误/危险|#B4232E|#FFF0EF|错误、失效、破坏性动作|

## 3. 字体与尺寸

Figma 使用可用的 Noto Sans SC，Regular / Medium / Bold。Web 优先系统中文字体 PingFang SC、Microsoft YaHei，然后 Noto Sans SC。该差异属于平台字体适配，开发时需要重新检查长标题与换行。

|样式|字号/行高|字重|使用|
|---|---|---|---|
|Display|36/48|Bold|规范封面|
|Heading|24/36|Bold|业务页标题；PC 可用 26px 光学校正|
|Section|18/28|500|卡片标题|
|Body|14/22|Regular|PC 正文、表格|
|Body Strong|14/22|Medium|字段标签与按钮|
|Mobile Body|16/26|Regular|现场操作正文|
|Label|12/20|Medium|标签及辅助信息|
|Metric|32/44|600|指标；本地紧凑卡片可用 30px|

编号与数量用 tabular-nums；日期使用明确的年月日/时间。资产名称可两行，身份编号完整显示或有复制/查看入口；不以省略号隐藏唯一识别信息。

## 4. 布局规范

PC 以 1440 宽为设计参考，产品侧栏 190—216，顶栏 64，内容边距 28—32，区块间距 20—24。低保真评审器的目录是画布外工具，不是产品第二套菜单。生产布局去掉评审器。表格可内部横向滚动，固定身份列与操作列由实际框架实现；本地原型没有宣称已实现冻结列。

小程序以 390 宽检验，左右留白 16，主要动作高 48，最小触控区域 44，内容卡片内边距 16。底部导航保留首页、扫码、我的、消息的顺序。安全区使用 `20px + env(safe-area-inset-bottom, 0px)`；浏览器验证覆盖 390/320，仍需小程序实施时适配设备 API。

间距：0/4/8/12/16/24/32/48。控件圆角 8，内容卡片 12，标签 4。默认内容不用阴影；浮层使用 `0 8px 40px #18263d24`。文字、阴影、动效与布局值见 `styles.json`；8 个文字样式类见 `styles.css`。本地设备画布阴影是评审容器装饰，不是产品卡片层级。

## 5. 组件契约

|组件|变体数|状态与实施规则|
|---|---:|---|
|Button|36|PC/Mobile × Primary/Secondary/Danger × Default/Hover/Pressed/Focus/Disabled/Loading；40/48 高|
|Field|15|Input/Select/Textarea × Default/Focus/Error/Disabled/ReadOnly；只读 Select 显示文本值|
|Status|5|Neutral/Info/Success/Warning/Danger；生命周期、业务、实物、证据分域|
|Navigation|9|Sidebar/Tabs/Mobile × Default/Selected/Disabled；当前入口带背景与标记|
|TableRow|5|Header/Default/Hover/Selected/ReadOnly；身份、生命周期、责任、实物分列|
|Notice|7|Info/Warning/Error/Success/Empty/NoAccess/Conflict；结果、原因、下一步|
|Quantity|3|Default/Loading/Unavailable；未知显示 —，加载不伪造 0|
|AssetCard|5|Selectable/Selected/Received/ReadOnly/Conflict；默认不全选，选中不等于接收|

总计 85 个显式变体，完整对照见 `component-library.html`，轴、属性和页面应用见 `library/manifest.json`。本地原生 DOM/CSS 构造器实现上述状态；表格、资产卡片和数量组件可按属性配置数据，不使用 HTML 字符串注入用户文本。

组合模式包括 Checkbox/Radio、Steps、Dialog、Progress、Timeline。复选支持 Mixed，单选不支持 Mixed。弹窗使用原生 modal dialog，默认焦点在取消，Escape 关闭并返回触发点。进度有确定/不确定两种状态，待上传数单列。步骤与履历明确区分批准、交出、接收。

`data-state` 的 Hover/Pressed/Focus 用于静态状态格；真实交互仍由鼠标与键盘伪类触发。Navigation 使用普通导航按钮和 `aria-current`，不冒充需要专门方向键协议的 ARIA tablist。图标只作辅助，使用单色 20/24 网格并配文字；本轮不交付独立图标库。


## 6. 状态、反馈与业务边界

- 生命周期：在库、在用、借出等显示为事实标签；待交接只显示在业务/实物阶段。
- 部分履行：3 申请 = 1 已完成 + 1 待交接 + 1 无法满足，不能因为已分配的两项接收完成就把整单显示“全部完成”。
- 空态：没有任何记录时解释出现条件；筛选无结果时提供清除筛选；无权限时不显示原数据；他人处理中显示处理人和只读提示。
- 离线：本地保存后“待上传”；已接纳才计入已盘；迟到证据不得改原报告；T+72h 过期转补盘提示；身份失效时隐藏原资产证据。
- 错误：行内错误关联字段；全局失败提示当前操作未保存及重试入口；冲突展示最新只读事实，不能静默覆盖。
- 破坏性操作：对话框列出对象、动作、前置依据与后果。没有确认遗失依据时不能以遗失核销提交。
- 通知：已读与已处理分开；代确认执行成功后通知责任人，不要求员工再次确认接收。
- 加载：保留布局，主按钮显示进行中且阻止重复动作；列表加载用骨架或明确文本，不能伪装暂无数据。
- 动效：120—180ms 控件反馈；不使用持续脉冲或夸张转场；尊重减少动态效果设置。

## 7. 页面到模板映射

|页面|模板与复用|
|---|---|
|P01、M01|身份表单 / Field / Notice / Button|
|P02、M02|任务工作台 / Quantity / Notice / Navigation|
|P03、P09、P10、P15、P23—P26|列表工作区 / TableRow / Filter / Status / 分区工具栏|
|P04、P11、P17—P22|概要+分组详情 / Status / Timeline / Notice / 对象级动作|
|P05—P08|表单或批次向导 / Field / Steps / 行级校验 / 后台反馈|
|P12—P14|业务执行 / Quantity / AssetCard 或 TableRow / 动作前后对比|
|P16|三方证据对照 / 快照、现场、当前 / 每类差异分别处理|
|M03、M04|申请表单/数量进度 / Field / Quantity / Steps|
|M05—M09|现场资产卡片 / 个人与公用分区 / 接收人核对|
|M10—M12|现场任务与回执 / Progress / 常驻房间 / Notice / AssetCard|
|M13、M14|消息与账号 / 结果和下一步 / 本地记录退出提示|

全部 40 页通过 `page-inventory.json` 可追溯至页面编号、名称、角色和 D0.2 规则。本地只统一视觉层，未将所有表单演示变成真实功能。

## 8. 交付与验证

交付包含可视规范页、完整组件状态库、40 页视觉原型及独立画布、40 张页面截图、tokens 与 styles 文件、可复用组件源码、页面映射、源文件摘要和验证报告。按 `README.md` 打开，按 `DEVELOPMENT.md` 接入。

`prototype.html` 保留 D0.2 业务脚本，视觉层使用相同令牌与组件规则；为了保持原交互，未强制把原脚本全部替换为组件工厂。`?present=1&page=P03` 隐藏评审工具，仅展示产品画布；评审模式保留情境选择器用于负面路径审查。扫码区域是设计示意，不连接相机。

验证范围与证据详见 `VERIFICATION.md`。颜色对比度按实际配色计算，不等于完整无障碍认证；本地 UI 设计不证明后端权限、持久化、并发、短信或设备功能。

Figma 已补齐 8 个原生组件集、85 个变体与 40 页可编辑画板。`figma-native/` 保存当前节点映射、实际导出与结构核对；`figma-state.json` 与原 API 辅助脚本保留早期历史。原生稿验证及固定视口、字体、变量绑定边界见 `NATIVE-FIGMA-VERIFICATION.md`。

## 9. Framelink 实际文件校准

2026-09-07 通过用户指定的 Framelink_Figma_MCP 读取文件并下载 3 个节点原图。Section 字重统一为 500；PC 字段控件高 44；加载按钮保留原操作颜色并禁用，避免与不可用状态混淆。颜色、按钮 40/48、字段圆角 8 与源文件一致。

源图、实际数据与本地扩展说明见 [Figma 来源对照](figma-reference/index.html)。本轮读取返回 7 个被节点引用的文字样式、24 个 Button 变体和 5 个 Field 状态；不能据此声称已读取全部变量或有 40 张云端页面。交互边框、焦点间隔、Secondary 状态差异和响应式布局保留已验证的本地适配。
