# UI-U1～U7 暖陶玻璃拟态改造

## 背景与裁决

用户提示词要求以「暖陶色模糊渐变背景 + 玻璃拟态」替换老旧灰底白卡，并落地六个交互：
液态高亮菜单、鼠标随光倾斜、模块原位放大、水滴筛选、命令面板、底部停靠栏。
四项裁决（AskUserQuestion，2026-11-22）：

1. 接入方式：**令牌层 + 组件层**，38 个视图模板原则上零改动（样板页除外）。
2. 主行动色：**保留蓝 #2457d6**，暖陶只作背景/表面/文字/描边语义。
3. 底部停靠栏与左侧菜单：**共存**，dock 放顶级分组快捷 + 搜索 + 全部功能。
4. 水滴筛选与模块放大：**各选 1–2 个样板页**（台账、消息中心、工作台）。

本改造覆盖 D0.4 视觉基线中「灰底白卡」的表面层，页头契约与排版刻度不变。

## 批次明细

| 批 | 内容 | 关键文件 | 提交 |
|---|---|---|---|
| U1 | 暖陶色板与玻璃令牌；body 多层径向光斑固定背景；panel/card/metric/topbar/sidebar/auth-card/表格壳/对话框玻璃化；深色压底卡 `[data-tone=deep]`；遮罩与阴影转暖 | `styles/tokens.css`、`base.css`、`layout.css`、`components/panel.css`、`table.css`、`dialog.css` | `3d2a0a7` |
| U2 | 侧栏液态高亮胶囊：FLIP + WAAPI 三段关键帧（行进拉长→落位压扁→spring 回弹），时长随位移 340–560ms；无 WAAPI/降级动效/零布局直接落位；ResizeObserver 无动画重定位；窄屏标签条隐藏胶囊 | `composables/useLiquidNavHighlight.ts`、`AppChrome.vue`、`navigation.css`、`layout.css` | `f3c119f` |
| U3 | 主内容区整域 pointermove 委托：光标写 `--glow-x/y` 柔光坐标 + perspective 微 3D 倾斜（±3.2°），边框提亮；移出平滑复原；仅鼠标指针生效 | `composables/useGlowTilt.ts`、`AppChrome.vue`、`panel.css` | `7be5daa` |
| U4 | ⌘K/Ctrl+K 命令面板：Teleport 中央玻璃搜索、背景压暗、实时过滤 mark 高亮、方向键选择、回车直达、esc 关闭、焦点还原；命令源取路由 meta（requiresAuth+uiGroup+uiTitle），`:id` 详情路由不入列 | `AppCommandPalette.vue(.test)`、`command.css`、`AppChrome.vue` | `8d59e80` |
| U5 | 水滴筛选胶囊（台账「借用逾期」）：点开后选项错峰分裂弹出、选中融回、即时 restart 不整页刷新；useCountUp 数字滚动（首值直出、后续 rAF 三次缓出 520ms），接台账总数与消息中心未读/总数 | `FamsDropletFilter.vue(.test)`、`useCountUp.ts(.test)`、`droplet.css`、`AssetLedgerView.vue`、`MessageCenterView.vue` | `1998293` |
| U6 | 工作台六模块舞台化：点「展开」模块原位放大，其余向下缩出并落进底部玻璃缩略条；点缩略原位切换、Esc/「沿原路收回」原路退出，不改路由；面板组件以宿主 div 包裹保活 | `useModuleFocus.ts(.test)`、`HomeView.vue`、`modules.css` | `a41b199` |
| U7 | 底部居中悬浮玻璃 dock：分组图标直达组内首个可见条目，最左「全部功能」展开全屏玻璃启动面板（复用 navGroups 权限过滤），搜索图标唤起 ⌘K；磁吸放大纯 CSS（:hover 1.32、:has 兄弟联动 1.14/1.05）；上方玻璃浮签显名称；≤900px 隐藏 | `AppDock.vue(.test)`、`dock.css`、`AppChrome.vue`、`layout.css` | `d434b9a` |

## 验证

- 四道门禁（gates.sh，2026-11-22）：
  - vue-tsc `--noEmit`：OK。
  - vitest run：**Test Files 97 passed / Tests 424 passed**（本系列新增 11 例：面板 2、水滴 2、滚动 2、舞台 2、dock 3）。
  - vite build：OK，`dist/assets/index-*.css 46.08 kB │ gzip 8.05 kB`（改造前基线未单列 CSS 体积，本批起以此值为对照）。
  - 全仓 eslint：apps/web 范围 0 错；唯一报错在 `apps/miniprogram/src/target-change-pages.ts`
    （并行会话在制品，两次运行报错位置漂移），不属于本系列，未处理。
- 整页样张：`/tmp/fams-glass-shots/1440x900login.png`、`390x844login.png`。
  登录页可见暖陶渐变、磨砂玻璃卡、细白描边与大圆角，U1 底座成立。
- 页头契约量测（layout-probe）：**未执行**。本机 8080 为真实 Spring 后端，
  匿名会话被路由守卫弹回 /login，探针无登录能力；契约数值（64/42、113/37）
  待有会话环境后复测。本系列未改 `--fams-topbar-height`、`--fams-tabbar-height`、
  `--fams-content-padding` 与页头结构，预期不变。
- 交互回归：U2/U3/U5/U6/U7 的悬停、拖移、滚动类效果只有单测口径与代码保证，
  未做浏览器内磁吸/倾斜数值实测。

## 已知局限

- `backdrop-filter` 玻璃层数按页面控制；低端集显设备上大面积模糊可能降帧。
- `@property --fams-glow` 不受支持的旧浏览器退化为瞬时发光。
- dock 与 U6 缩略条同屏时垂直位置（104px/20px）未做浏览器实测校齐。
- 深色压底卡 `[data-tone=deep]` 令牌与样式已就绪，首批消费页面留待后续批次。
