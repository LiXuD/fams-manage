# FAMS 界面设计完善（对 D0.3 的加法）

> 定位：**在团队 D0.3 设计系统基础上做加法**，不修改、不覆盖团队任何原交付文件。
> 可视化预览：`fams-ui-enhancement.html`（与团队 `tokens.css` / `visual.css` 同源，浏览器直接打开）。
> 需求基线：PRD V1.5；视觉基线：设计团队 D0.3（浅色深蓝 / `fams-` 令牌 / 85 组件变体 / 40 页）。

---

## 0 · 为什么是「完善」而不是「重做」

我的第一版方案用了另一套色板（indigo / sky），与团队 D0.3 的「浅色深蓝」规范冲突——这正是被判定「不行」的根因。正确做法是**以团队 D0.3 为唯一权威基线**，只在 PRD 明确要求而 D0.3 视觉层尚未覆盖的两处补齐：

1. **资产 11 状态色板**（PRD §7.1 + §17.2）
2. **移动优先布局系统**（PRD 明确移动优先；D0.3 以 `max-width` 桌面优先实现）

其余（令牌、文字样式、4px 节奏、组件库、40 页）一律沿用团队成果。

---

## 1 · 完善一：完整 11 状态资产色板

### 1.1 缺口

D0.3 的 Status 组件只有 5 档：Neutral / Info / Success / Warning / Danger（SPEC §5）。但 PRD §7.1 定义：

- **生命周期 8 态**：待入库 / 在库 / 在用 / 借出 / 维修中 / 返厂维修 / 待报废 / 已报废
- **异常 3 态**：正常 / 查找中 / 已遗失

5 档无法为 11 态提供可区分且带文字的专属色，导致「借出只能套用在库蓝」「待报废无处安放」等问题。PRD §17.2 同时要求状态**不只依赖颜色**——必须文字 + 图形双通道（团队已遵循，本扩展延续）。

### 1.2 扩展原则（与团队同源）

沿用团队「**50 底 + 700 字**」同款饱和度模式，在 4 个基础色调之上新增 **3 个协调冷调色相**，不引入暖紫或荧光色，保持「浅色深蓝」整体气质：

| 状态 | 档 | 底 / 字 | 来源 |
|---|---|---|---|
| 待入库 | neutral | gray50 / gray700 | 团队中性 |
| 在库 | info | blue50 / blue700 | 团队 |
| 在用 | success | green50 / green700 | 团队 |
| **借出** | **borrowed** | **indigo50 #EEF2FF / indigo700 #3730A3** | **新增** |
| 维修中 | warning | amber50 / amber700 | 团队 |
| **返厂维修** | **factory** | **orange50 #FFF1E6 / orange700 #9A3412** | **新增** |
| **待报废** | **pendscrap** | **rose50 #FFE9EC / rose700 #9F1239** | **新增** |
| 已报废 | neutral | gray50 / gray500 | 团队中性 |
| 正常（异常） | neutral | gray50 / gray500 | 团队中性 |
| 查找中（异常） | warning | amber50 / amber700 | 团队 |
| 已遗失（异常） | danger | red50 / red700 | 团队 |

**分列展示**是关键：生命周期与异常是资产卡上的两列，跨列同色（如维修中 / 查找中同为 amber）不冲突，因为列标题已界定语义。

### 1.3 对比度（AA）

700 级文字对 50 级底均 ≥ 7:1（远超 AA 4.5:1）；中性档 `text/secondary #53657D` 对 `gray50 #F0F3F8` ≈ 5.0:1，达标。

### 1.4 并入方式

```css
/* tokens.css —— 仅追加，不动既有 */
:root{
  --fams-palette-indigo50:#EEF2FF; --fams-palette-indigo700:#3730A3;
  --fams-palette-orange50:#FFF1E6; --fams-palette-orange700:#9A3412;
  --fams-palette-rose50:#FFE9EC;   --fams-palette-rose700:#9F1239;
  --fams-status-borrowed:var(--fams-palette-indigo700);  --fams-status-borrowed-bg:var(--fams-palette-indigo50);
  --fams-status-factory:var(--fams-palette-orange700);    --fams-status-factory-bg:var(--fams-palette-orange50);
  --fams-status-pendscrap:var(--fams-palette-rose700);    --fams-status-pendscrap-bg:var(--fams-palette-rose50);
}
/* visual.css —— Status 区块追加三条 */
.badge[data-tone=borrowed]{color:var(--fams-status-borrowed);background:var(--fams-status-borrowed-bg)}
.badge[data-tone=factory]{color:var(--fams-status-factory);background:var(--fams-status-factory-bg)}
.badge[data-tone=pendscrap]{color:var(--fams-status-pendscrap);background:var(--fams-status-pendscrap-bg)}
.badge[data-tone=neutral]{color:var(--fams-text-secondary);background:var(--fams-bg-subtle)}
```

SPECIFICATION.md §2 / §5 同步把 Status 组件说明从「5 档」扩写为「生命周期 8 + 异常 3 + 中性」。

---

## 2 · 完善二：移动优先布局系统

### 2.1 缺口

D0.3 视觉层用 `max-width` 桌面优先降级（visual.css：`≤1100` / `≤700`）。PRD 明确「移动优先、从 320 最小屏起步」，且小程序是主现场入口。需要一套显式的 `min-width` 渐进增强断点。

### 2.2 断点（对齐团队已渲染断点，翻转方向）

| 断点 | 宽度 | 形态 | 布局 |
|---|---|---|---|
| base | 320–389（参考 390） | 手机 | 单列；底部 Tab；主操作 ≥44，留白 16 |
| ≥576 | 大手机 | 单列→双列卡片 | `repeat(2,1fr)` |
| ≥768 | 平板 | 三列；表单同组两列 | `repeat(3,1fr)` |
| ≥1100 | PC | 侧栏常驻 190–216，内容自适应 | `repeat(4,1fr)` + 侧栏 |

核心规则：

- 卡片网格 `grid-template-columns: repeat(auto-fit, minmax(280px, 1fr))`，极小屏单列、空间允许自动增列。
- 表格在 `≤700` 时**内部横向滚动**（`min-width:650px`），固定身份列与操作列由框架实现，不挤压文字（D0.3 已这么做，本系统仅显式文档化）。
- PC 侧栏在 `<1100` 折叠为横向抽屉（团队 `appnav` 已用 `display:flex;overflow:auto`），`≥1100` 常驻。
- 安全区：`20px + env(safe-area-inset-bottom, 0px)`（团队 §4 已定义）。

### 2.3 并入方式（可选）

将 `min-width` 移动优先栅格并入 `visual.css` 响应式区，替代现有 `max-width` 写法，使 40 页在 320 起即正确。本增强目录不依赖团队原文件修改即可独立预览。

---

## 3 · 应用示例（已在预览页给出）

- **PC 工作台 P02**：复用团队 `.appbar / .pcbody / .appnav / .content / .stats / .tablewrap`，状态徽标替换为 11 状态档。
- **小程序首页 M02**：复用团队 `.mobile.frame / .mobilehead / .mobilecontent / .bottomnav`，借用项展示 `borrowed` 徽标。

---

## 4 · 交付物

1. `fams-ui-enhancement.html` — 可视化预览（现状对照 → 11 状态色板 → 移动优先布局 → 应用示例 → 并入步骤）。
2. 本文档 — 扩展原理、令牌、对比度与并入指引。

> 本增强为设计评审稿，不替代团队 D0.3 的组件库、40 页与验证报告；合并后须以团队 `VERIFICATION.md` 流程复测对比度与响应式。
