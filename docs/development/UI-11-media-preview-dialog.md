# UI-11 图片与凭证预览改为原生 dialog 焦点层

实施分支 `codex/d6-20-factory-ui`，紧接 UI-10（工作区未提交改动之上）。UI-10 的审查发现 `styles/components/dialog.css` 的 `.fams-dialog` / `::backdrop` 在全仓 `.vue` 里零使用者，而 5 处图片与凭证预览仍是页内 `<figure>` 展开——照片被压在卡片流里、读图时页面其余内容仍在抢夺注意力。本批按用户选定口径「混合：确认块留在页面流内做纯 CSS 焦点层（UI-10 已完成）+ 预览改用原生 `<dialog>` + `showModal`」补齐这一层，新增一个预览容器组件，5 个预览站点各改外壳标签。测试一行未改。

## 范围

| 文件 | 变化 |
|---|---|
| `components/FamsMediaPreview.vue` | 新增 28 行：只有 `<dialog>` 外壳与生命周期，内容走默认插槽。`onMounted` 里 `showModal()`，`showModal` 不存在时（jsdom 未实现 `HTMLDialogElement.showModal/close`）退化为 `open` 属性，渲染同一棵子树；`@close`（Esc 或 `close()`）与遮罩点击（`event.target === dialog`）都 `emit('dismiss')`，由父级原有的 clear 撤下 `v-if` 并 revoke blob；`onBeforeUnmount` 先走一次原生 `close()`。 |
| `styles/components/dialog.css` | `.fams-dialog` 补 `margin: auto`（`base.css` 的 `*` 重置会清掉 UA 的 auto 边距，弹窗会贴顶）；新增 `.fams-media-preview`：`width: min(880px, 100vw - 32px)`、`max-height: calc(100dvh - 64px)`、`padding 24`、`overflow: auto`；`figure` 纵向 flex + `gap 12`；`img` 居中、`max-height: calc(100dvh - 216px)`、`object-fit: contain`、`--fams-bg-subtle` 底与 8 圆角；`figcaption` 12/16 次要色 + `overflow-wrap: anywhere`（UUID 文件名不撑破弹窗）；`.fams-button { align-self: flex-start }`。 |
| `views/RepairView.vue:220` | 故障照片预览：`<figure v-if class="fams-card">` → `<FamsMediaPreview v-if @dismiss="clearPhoto">` + 内层 `<figure>`。 |
| `views/RepairInventoryPhoto.vue:58` | 盘点原图：同上，`@dismiss="clear"`。 |
| `views/InventoryPhotoEvidence.vue:64` | 原始观察照片：同上，`@dismiss="closePreview"`。 |
| `views/RepairTechnicalEvidence.vue:27` | 科技协助依据：同上，`@dismiss="clear"`；`<img v-if="mime.startsWith('image/')">` 与 `<a :href :download>保存已授权依据` 原样进插槽。 |
| `views/RepairFactoryPanel.vue:340` | 厂家凭证：同上，`@dismiss="clearPreview"`；PDF 分支的 `<p>PDF原件已读取，可保存查看。</p>` 与 `.fams-inline` 动作行原样进插槽。 |

5 处预览的内层标签（`img` / `figcaption` / `a` / `button` 及其绑定表达式、`data-kind`、`data-size`）逐字节未动，只是从 `figure` 外面套了一层组件；`figure` 上的 `class="fams-card"` 去掉，卡片外观改由 `.fams-dialog` 承担。

## 不变量比对

- 5 个父级 `<script>` 块与 HEAD 逐行 diff：**每个文件只多一行** `import FamsMediaPreview from "@/components/FamsMediaPreview.vue";`（`RepairView` 149→150、`RepairInventoryPhoto` 53→54、`InventoryPhotoEvidence` 50→51、`RepairTechnicalEvidence` 22→23、`RepairFactoryPanel` 193→194），函数、变量、状态与请求逻辑零改动。
- 按钮文案：5 条关闭文案（关闭故障照片 / 关闭原图并清理 / 关闭照片 / 关闭依据并清理 / 关闭厂家凭证）与 2 条下载文案（保存已授权依据 / 保存这份厂家原件）逐字保留；未新增或删除任何用户可见文本。
- 测试文件未改：`InventoryPhotoEvidence.test.ts:28-29`（`find("img").attributes("src")` 与按文案点「关闭照片」后 `revokeObjectURL` 恰好一次）、`RepairInventoryPhoto.test.ts:23-26`、`RepairTechnicalEvidence.test.ts:10-12`、`RepairFactoryPanel.test.ts:91-92` 全部原样通过——证明 `wrapper.find("img")` 能穿透到子组件渲染的 dialog 子树，且 jsdom 的 `open` 属性退化路径成立。

## 验证

- `npx vue-tsc --noEmit` 退出 0；`npx eslint . --max-warnings 0` 无告警；`npx vitest run` 46 个测试文件 / 180 条用例全部通过；`npx vite build` 通过。
- CSS 30.38 kB（gzip 5.52 kB），较 UI-10 的 29.81 kB（gzip 5.41 kB）增 570 B / gzip 增 110 B，全部来自 `.fams-media-preview` 一档规则。
- **真实浏览器（headless Chrome + `showModal`）量测**，dev 5173 + 临时 stub 8080：
  - 故障照片（`/repairs/19`）：1440 视口弹窗 `880×675`、顶边 `y=112`（垂直居中）、内边距 24、圆角 16、`::backdrop` 实测 `rgba(24, 38, 61, 0.4)`（UI/UX 口径 40—60% 的下沿）、阴影 `rgba(24,38,61,.14) 0 8`；`img` 实显 `830×553`（1200×800 源图等比，受宽度约束），`figcaption` 显示「故障现场.jpg」。
  - 390 视口同一站点：弹窗 `358×339`（= 390 − 32，`max-width` 生效）、`img` `308×205`、关闭按钮走 UI-10 的 44 触控档。
  - 盘点原图（`RepairInventoryPhoto`）：弹窗 `880×647`、`img 830×553`、按钮「关闭原图并清理」、焦点落在弹窗内。
  - 协助依据（`/repair-assistance/8101`）：弹窗 `880×683`、`img 830×553` + 下载链「保存已授权依据」+ 按钮「关闭依据并清理」。
  - 三条关闭路径均实测有效：Esc → 弹窗数 0、`img` 数 0（blob 已 revoke）；点关闭按钮 → 同上；点遮罩（`event.target === dialog`）→ 弹窗数 0。打开时 `dialog.contains(document.activeElement) = true`（原生焦点进入弹窗）。
  - `z-index` 计算值为 `auto`：模态 dialog 由浏览器画在顶层（top layer），不依赖 `--fams-z-dialog`，也不会与页内固定元素争层级。
- **复核中定位并修掉的问题**：① 弹窗贴顶不居中 → `base.css` 的 `* { margin: 0 }` 覆盖了 UA 的 `margin: auto`，在 `.fams-dialog` 显式声明；② flex 纵向容器把关闭按钮拉成 830px 整行 → `align-self: flex-start`；③ 样张不可读 → 临时 stub 的 1×1 PNG 换成运行时生成的 1200×800 渐变 PNG（`sizeBytes` 由 `PNG.length` 派生，组件的体积校验仍成立）。
- 样张：`/tmp/fams-ui11-preview-1440.png`、`/tmp/fams-ui11-preview-390.png`、`/tmp/fams-ui11-invphoto-1440.png`、`/tmp/fams-ui11-evidence-1440.png`、`/tmp/fams-ui11-faultphoto-1440.png`（含修 ② 前的一张）。

## 已知限制

- 5 个站点里 3 个取到了真实浏览器样张；`InventoryPhotoEvidence`（需先进入盘点发现的观察详情）与 `RepairFactoryPanel` 厂家凭证（需把轮次推到可读状态）未做浏览器采样，只有单元测试覆盖其渲染与 revoke 路径。
- 关闭后 `document.activeElement` 回到 `body`，未回到触发按钮。实测原因：`onMounted` 时 `document.activeElement` 已是 `body`——触发按钮在同一次 Vue patch 中失焦，UA 与手写 `focus()` 都无从归位（隔离页面里原生 `dialog.close()` 的归位是有效的）。这与改造前的页内 `<figure>` 行为一致，不是本批引入的回归，但键盘用户仍需自行 Tab 回列表。
- jsdom 无 `showModal/close`，单测走 `open` 属性退化分支；真实焦点陷阱与顶层渲染只在浏览器里验证。
- 弹窗未加 enter/exit 动效与 `data-kind` 破坏性语义档——本批按用户选择未纳入。
- `.fams-toast-region` / `.fams-toast` 仍零使用者。
- 根 `eslint.config.mjs` 的 `sourceFiles` 只覆盖 `*.{ts,tsx}`，`.vue` 模板不经 lint（既有缺口）。
- 验证口径仍为「单测 + 构建 + 静态样张」，未接真实后端；UI-10 与本批改动均未提交。

## 下一批

无既定批次。可选后续（需另行确认）：① 关闭后焦点归位——把 5 处预览的触发按钮改为受控 `ref` 或在父级 clear 时显式聚焦；② 弹窗/折叠动效一档（`--fams-motion-*` 已有令牌）；③ 破坏性动作的 `data-kind` 语义档与确认弹窗统一；④ 给 `.fams-toast` 找第一个使用者或删掉该段。
