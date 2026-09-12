# 本地组件接入与设计交接

> 版本边界（2026-09-11）：正文保留D0.3组件接入说明；D0.4设计整改及原生同步已在第4步关闭，终态见[HANDOFF-D0.4](HANDOFF-D0.4.md)。当前项目尚未进入第6步产品开发，阶段状态见[根交接入口](../../../../DESIGN-HANDOFF.md)。

此目录是 D0.3 视觉设计交付，使用原生 HTML/CSS/JavaScript，无构建依赖、外部字体或服务端请求。可直接打开 `index.html`；通过 HTTP 预览便于浏览器验收。

原生 Figma 交接见 [实际导出索引](figma-native/index.html) 与 [验证记录](NATIVE-FIGMA-VERIFICATION.md)。组件与页面节点分别映射在 `figma-native/component-map.json`、`screens-map.json`；图层命名保留业务文本，按钮与状态标签引用本地原生组件。新页面采用固定视口坐标，实施时按本节的响应式与业务契约重新组织，不直接复制绝对定位坐标。

## 最小接入

```html
<link rel="stylesheet" href="tokens.css">
<link rel="stylesheet" href="styles.css">
<link rel="stylesheet" href="library/components.css">
<div id="app"></div>
<script src="library/components.js"></script>
<script>
  const app = document.getElementById('app');
  app.append(FamsUI.field({
    id: 'actual-location', label: '实际接收位置', required: true,
    hint: '填写实物实际存放位置', onChange: value => console.log(value)
  }));
  app.append(FamsUI.assetCard({
    code: 'FA-2026-00102', name: '办公笔记本',
    responsibility: '一号仓库 / A-03', target: '林晓 · 产品部 / 301',
    onSelect: selected => console.log(selected)
  }));
</script>
```

所有构造器返回 DOM 元素，由调用者挂载。属性文本通过 `textContent` 写入。`onClick/onChange/onSelect/onConfirm` 是回调接口；请求、校验、授权、错误重试由业务层负责。示例按钮没有真实提交含义。

## 状态与参数

完整状态轴、属性、适用页见 `library/manifest.json`。可视对照见 `component-library.html`。具体默认值与参数解构见 `library/components.js`；不支持的属性不会成为业务能力。

|工厂|常用参数|交互|
|---|---|---|
|button|label, kind, size, state, icon|onClick；Disabled/Loading 禁止点击|
|field|id, label, value, control, state, hint, required, options|onChange(value)；错误关联 hint|
|status|label, tone, domain|纯文字状态|
|navigation|id, mode, items, selected, itemState|onChange(index)，更新 aria-current|
|table|columns, rows, caption, state|可选 onSelect(row)，只读时不生成动作|
|notice|state, title, body, actionLabel|可选 onAction|
|metric|label, value, supporting, state|加载与未知值分开|
|assetCard|state, code, name, assetState, physicalState, responsibility, target|onSelect(checked)；不改变交接状态|
|selection|type, state, label, name, id|onChange(checked)；Radio 同组共用 name|
|steps|items: label/state/detail|当前步 aria-current=step|
|timeline|items: label/state/actor/time|区分已发生、当前和待执行|
|progress|value, max, pending, state|progress 原生语义，待上传单列|
|dialog|state, title, body, confirmLabel|openFrom(trigger)，onConfirm；Escape 取消、焦点返回|

输入与选择控件使用原生键盘语义。表格内部可横滚；实际框架若增加冻结列，要重新验证窄屏与键盘访问。示例 Tabs 作为普通导航呈现；若产品改为 ARIA tabs，应同时实现 tabpanel 和方向键协议。

## 页面实施顺序

1. 复用 `tokens.css` 与 8 个文字样式，先实现工作台/列表/详情/现场卡片四类骨架。
2. 按页面映射接入组件，保留正式责任、实物阶段、业务进度、证据状态的不同字段。
3. 对照 `prototype.html` 的局部演示落实动作前提、确认、结果和下一步。表单登录、设备能力、权限及接口在实际项目中实现并验证。
4. 对比 `screens/` 默认截图，另验收无权限、加载、空态、失败、冲突、窄屏和长中文。

原型视觉样式与组件库共享令牌和规则，`prototype.html` 的原业务脚本未替换为工厂调用。不要直接把原型示例数据与假动作当成生产逻辑。

## 重建与验证

在项目根目录运行：

```sh
rtk proxy python3 docs/superpowers/designs/2026-09-06-fams-ui/scripts/build_local.py
rtk proxy python3 docs/superpowers/designs/2026-09-06-fams-ui/scripts/audit_static.py
rtk proxy python3 -m http.server 8785 --bind 127.0.0.1 --directory docs/superpowers/designs/2026-09-06-fams-ui
```

构建会从 D0.2 生成视觉原型、页面清单、令牌、文字样式、离线可用清单脚本和画廊。修改视觉层文件，不直接修改生成的 `prototype.html`。规范页及组件页的 CSS/JS 引用携带内容哈希，避免浏览器缓存旧样式。

`scripts/verify-local.js` 是 Playwright CLI `run-code` 可执行的 async(page) 函数。默认服务地址为 `127.0.0.1:8785`，产物写入项目现有 `output/playwright/fams-ui/` 以及交付目录 `screens/`。该脚本覆盖 UI 设计回归，不能替代正式业务 E2E。

截图默认状态来自 D0.2 的示例情境（已接收 1 件，仍待交接），非真实账号数据。浏览器刷新会重置局部业务状态。查看其他阶段请使用评审模式中的“领用链情境”和“离线评审情境”。
