# ARCH-02 把 65 个 SFC 纳入 ESLint 门禁（架构批第 2 批）

基线：`ARCH-01` `89b0b79`。本批只动 lint 配置、根 lint 脚本与 1 处被新门禁拦下的既存违规，**不改任何模板语义、不改任何运行时行为**：构建 CSS 仍 `31.42 kB / gzip 5.65 kB`，首屏仍 `index 54.15 kB / gzip 20.64 kB` + `AppChrome 73.45 kB / gzip 29.01 kB`，与 ARCH-01 逐字节同体积。

体检实测的盲区：`eslint.config.mjs` 的 `sourceFiles` 只含 `.ts/.tsx`，全仓 **65 个 `.vue` 文件的模板与 `<script setup>` 从未进过 ESLint**（UI-21 提交正文亦自认「既有盲区」）。这些 SFC 此前只有 `vue-tsc` 兜类型，模板安全（`v-html`、`v-for` key、`v-if` + `v-for` 同元素）与 SFC 内 TS 的代码质量无任何门禁。

## 范围

### 一、依赖与命令

- 根 `package.json` 加 devDependency `eslint-plugin-vue@^10.12.0`（`npm i -D` 净增 7 个包；同次安装顺带真删了 ARCH-01 从 lockfile 移除的 element-plus 子树，node_modules 减 20 个包）。
- 根 `lint` 脚本的第二段 glob 由 `apps/web/src/**/*.{ts,tsx}` 改为 `{ts,tsx,vue}`；`sourceFiles` 同步改动，使既有 `@eslint/js` 与 `typescript-eslint` recommended 规则一并覆盖 SFC 的 `<script>` 块。

### 二、`eslint.config.mjs` 的解析器与档位

- 先展开 `...vue.configs["flat/essential"]`（85 条 vue 规则）。
- **末尾追加**一个 `files: ["apps/web/src/**/*.vue"]` 块，把 `languageOptions.parser` 重新指回 vue-eslint-parser（取自 `vue.configs["flat/base"]`，不新增未声明的直接依赖），并用 `parserOptions.parser = typescriptEslint.parser` 让 `<script>` 块走 TS 解析。该块必须排在 TS 配置之后：TS recommended 的 `files` 现已含 `.vue`，若把 vue 解析器声明放前面会被它覆盖回 TS 解析器，65 个 SFC 全部报 `Parsing error: '>' expected`（本批实际踩过）。
- 显式钉住 `"vue/no-v-html": "error"`：`--print-config` 复核确认该规则只在 `flat/recommended` 档里，essential 与 strongly-recommended 都不含，而它恰是最容易被一次模板改写重新引入的 XSS 面；全仓 `.vue` 现有 `v-html` 使用者 **0 处**，钉住零成本。
- `vue/component-name-in-template-casing` 设 PascalCase（与既有 `<AppChrome />` 等写法一致），`vue/multi-word-component-names` 关闭（`App.vue` 与既有单名组件）。
- 不上 `flat/strongly-recommended`/`flat/recommended`：其排版类规则（`max-attributes-per-line` 等）与本仓刻意的密集单行模板冲突，启用只会产出大规模纯格式化 diff。

### 三、首跑暴露的既存违规（2 处，1 个文件）

`views/RepairFactoryPanel.vue:106` 的 `let path = "", …, label = ""` 被 `no-useless-assignment` 判定初值永不读取——`prepare()` 的分支链在每个可达路径上都会先覆盖 `path` 与 `label` 才使用。改为无初值声明 `let path: string, body: unknown, …, label: string;`，与同行 `body/chosen/form` 的既有写法统一，由 TS 控制流分析保证「先赋值后使用」，`vue-tsc` 通过即为该不变量的机器证明。除此之外 65 个 SFC 一次通过，未新增任何 `eslint-disable`。

## 验证

| 口径 | 结果 |
| --- | --- |
| 根 `npm run lint`（`--max-warnings=0`，glob 含 `.vue`） | 0 问题 |
| `eslint --print-config` 对 `.vue` | `vue/*` 生效 **86** 条、`@typescript-eslint/*` 生效 **20** 条，parser 为 `vue-eslint-parser@10.4.1` |
| **门禁非空转探针** | 临时放入 `views/__lint_probe.vue`（`v-for` 无 key + `v-html`），ESLint 如实报 `vue/require-v-for-key`、`vue/no-v-html` 2 个 error；探针随即删除，未进提交 |
| `vue-tsc --noEmit` | 0 错误 |
| `vitest run` | 64 文件 / 264 用例全绿（与 ARCH-01 同） |
| `vite build` | 体积同 ARCH-01，chunk 图变化仅体现在 `RepairFactoryPanel` 所属块的文件名 hash |

## 已知限制

1. 档位是 **essential + 1 条手工钉住的 `no-v-html`**，不是全量 recommended；本仓模板若日后重排成多行属性，可另批评估 strongly-recommended。
2. 仍是**非类型感知** lint（未配 `parserOptions.project`/`projectService`），与既有 `.ts` 面保持同档；`no-unsafe-*` 这类需要类型信息的高价值规则未启用。
3. `apps/miniprogram/**/*.ts` 与 `packages/api-types` 的规则面未变；本批只扩 web 的 `.vue`。
4. 本地 hook 与远端 CI 仍未配置，lint 门禁靠人工执行，不替代远端 CI 与正式 QA。

## 下一批

ARCH-03：新增 `src/api/http.ts` 单一 fetch 底座（CSRF、`Idempotency-Key`、`If-Match-Version`、错误映射、共享 `Page<T>`），把 12 个域 api 的 4 种互不兼容签名变体改为委托，保持导出函数签名与请求头逐字节不变，并收掉 5 处视图内裸 `fetch()`。
