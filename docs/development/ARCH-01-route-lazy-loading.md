# ARCH-01 路由懒加载与死依赖清理（架构批第 1 批）

基线：`codex/d6-20-factory-ui` HEAD `8c0ad54`（UI-21）。本批起为**架构批**（ARCH 系列），依据 Vue 最佳实践技能对 `apps/web` 做分层与复用调整，与 UI-01～UI-21 的文案/模板批不重叠。本批不动任何视图模板与 CSS：构建 CSS 仍为 `31.42 kB / gzip 5.65 kB`，与 UI-12 以来逐字节一致。

体检口径来自代码实测而非估算：`apps/web/src/router.ts` 原以 38 条**静态 import** 引入全部视图，`vite build` 产出**单个 652.37 kB（gzip 174.19 kB）JS chunk**，vite 自身已报 `chunks are larger than 500 kB` 警告；`element-plus@2.14.6` 在 `apps/web` 全仓 **0 个文件使用**（仅存在于 package.json 与 lockfile）。

## 范围

### 一、`apps/web/src/router.ts`：35 条业务路由改按需加载

- 入口三页 `HomeView` / `LoginView` / `PasswordChangeView` 保持同步 import（首屏与鉴权跳转目标，改懒加载会多一次往返）。
- 其余 35 个视图把 `import X from "@/views/X.vue"` 改为 `const X = () => import("@/views/X.vue")`，**路由表逐字未改**：`path`、`name`、`props: true`、`meta` 四键（`requiresAuth`/`uiGroup`/`uiTitle`/`uiDesc`）与 38 条顺序全部保持，声明位置仍在 `createRouter` 之前，不产生使用先于声明。
- 未引入 `<Suspense>` 或加载骨架：Vue Router 在解析完异步组件前不卸载旧路由，本批不新增可见空窗期语义。

### 二、依赖清理

- `apps/web/package.json` 删除 `dependencies.element-plus`；`package-lock.json` 以 `npm install --package-lock-only` 同步，lockfile 内 `element-plus` 条目由 6 处降为 0。
- `qrcode` 经复查有真实使用者（`views/AssetDetailView.vue` 与其测试），保留。

### 三、新增 1 个测试用例

`apps/web/src/router.test.ts` 增加「除入口三页外每条路由都是可解析的懒加载工厂」：断言懒加载路由恰 35 条、每条 `route.components.default` 是函数，并**实际调用工厂**取得模块命名空间、断言 `default` 为组件对象。目的不是测构建产物，而是证明 35 个动态 import 路径没有拼错、没有指向已被搬走的文件。

## 验证

| 口径 | 改前 | 改后 |
| --- | --- | --- |
| `vite build` JS chunk 数 | 1 | 47 |
| 首屏必载 JS（`index.html` 内 script + modulepreload） | 652.37 kB / **gzip 174.19 kB** | 127.60 kB / **gzip 49.65 kB**（`index-*.js` 20.64 + `AppChrome-*.js` 29.01） |
| 全量 JS 合计 gzip | 174.19 kB | 226.48 kB（跨 chunk 失去共享压缩上下文，代价 +30%） |
| vite `chunk > 500 kB` 警告 | 有 | 无 |
| CSS | 31.42 kB / gzip 5.65 kB | 逐字节同前 |

- `vue-tsc --noEmit`：0 错误（含 `route.components` 类型，测试内以 `unknown` 收窄，未新增 `any`，全仓 `any` 仍为 0）。
- `eslint --max-warnings=0`（根 `npm run lint`）：无问题。**注意 `.vue` 仍不在 `sourceFiles` 覆盖内**，本批未改变该盲区，留待 ARCH-02。
- `vitest run`：64 文件 / **264 用例**全绿（改前 263，净 +1 即上述新用例；原有用例断言一字未动）。
- 浏览器实测懒加载生效：`vite --port 5199` 起 dev server，以页面内 fetch 桩替掉 `/api/v1/auth/me`、`/auth/permissions`、`/inbound-receipts` 三个接口（不依赖后端），从 `/login` 走客户端跳转 `router.push('/inbound')`。`performance` 资源计时显示**跳转瞬间才新请求** `/src/views/InboundView.vue` 与 `/src/inbound/api.ts`；到位后 `h1` = 「入库管理」，桩单号 `RK-BROWSER-1` 的行渲染成功，控制台仅 2 条 vite debug、无 error/warning。

## 已知限制

1. 懒加载的**生产产物**网络加载未实测：dev server 证明的是「按需请求模块」，生产按 chunk 请求的等价性由 `router.test.ts` 的工厂解析断言 + 构建产物 chunk 化共同支撑，未做 `vite preview` 下的逐路由点击普查（38 条逐条点击成本高于本批收益，且多数路由需真实权限快照）。
2. 浏览器验证用桩接口，未接真实后端与真实短信；不替代远端 CI、正式 QA 与生产验收。
3. `AppChrome` 被 35 个视图共用，故仍在首屏 modulepreload 里（73.45 kB）；它的进一步拆分属后续批次，不在本批范围。
4. `.vue` 文件依旧在 ESLint 覆盖外；`@fams/api-types` 依旧 0 引用；`shallowRef` 依旧 0 使用——分别由 ARCH-02、ARCH-07、ARCH-05 处理。

## 下一批

ARCH-02：引入 eslint-plugin-vue，把 `apps/web/src/**/*.vue` 纳入 `sourceFiles` 与 `--max-warnings=0` 门禁，只先启用 vue essential 与模板安全规则（`v-html`、`v-for` key、`v-if` + `v-for` 同元素），并修首跑暴露的既存违规。
