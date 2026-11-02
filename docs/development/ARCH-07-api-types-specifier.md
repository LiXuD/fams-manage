# ARCH-07 契约类型包改为包说明符引用（架构批第 6 批，编号 ARCH-07 提前执行）

基线：`ARCH-05` `bd87431`。本批只改导入说明符与包导出声明，**类型面变化为零**：`vite build` 产物与本批开始前**逐字节同尺寸**（入口 `index` 54.40 kB / gzip 20.77、共享块 73.47 kB / gzip 29.02、CSS 31.42 kB / gzip 5.65），因为 14 处全是 `import type`，编译后完全擦除。

## 更正先前判断

架构体检开头我报过「`@fams/api-types` 生成后无人消费」——该判断不准，实测纠正：`apps/web` 有 **14 处**、`apps/miniprogram` 有 **5 处**在用它，只是形式是 `../../../../packages/api-types/src/loan` 这种四层相对深引用。真实问题因此不是「接线空转包」，而是：

1. 包未被声明为 `apps/web` 的依赖（`package.json` 里没有它），依赖关系只由相对路径隐式成立；
2. 包没有 `exports` 面，任何消费者都得知道内部目录结构（`src/<domain>.d.ts`），包内一改结构全站断；
3. 相对层级把 `apps/web/src/views` 与仓库根绑死，ARCH-06 一旦移动目录层级，这 14 处会连带断掉。

## 范围

| 文件 | 变化 |
| --- | --- |
| `packages/api-types/package.json` | 新增 `exports`：`.` → `./src/index.ts`（原 `types` 面保持），`./loan`、`./requisition`、`./transfer` 三个子路径指向对应 `.d.ts`，并暴露 `./package.json` |
| `apps/web/package.json` | `dependencies` 新增 `"@fams/api-types": "*"`（npm workspaces 就地链接，node_modules/@fams/api-types 已是软链），`package-lock.json` 以 `npm install --package-lock-only` 同步 |
| `apps/web` 14 处 | `../../../../packages/api-types/src/<domain>` → `@fams/api-types/<domain>`，含 2 处多行 `import type {…}` 的续行写法 |

未一并改 `apps/miniprogram` 的 5 处：本批批准范围是 web 前端；miniprogram 的 tsconfig 解析口径未验证，同形替换应单独成批（改法相同，风险在另一份配置）。

## 验证

| 口径 | 结果 |
| --- | --- |
| **契约变更可在编译期暴露（本批的目的）** | 正控探针：把 `loan.d.ts` 的 `expectedReturnDate` 改名一位，`vue-tsc --noEmit` 立刻报 **4 处** TS2339（`LoanView.vue:73`、`LoanDetailView.vue:60/93/122`），随后 `git checkout` 还原该文件，`git status` 确认只剩本批应有改动 |
| `vue-tsc --noEmit` | 还原后 0 错误（同时证明 `moduleResolution: Bundler` 下子路径 `exports` 解析成立） |
| `vitest run` | 66 文件 / 285 用例全绿，与 ARCH-05 同一集合，无断言改动 |
| `npm run lint` | 含 `.vue`、`--max-warnings=0`，exit 0 |
| `vite build` | 三个产物尺寸与本批前逐字节同（类型 import 擦除，包说明符不产生运行时代码） |
| 残留 | `apps/web/src` 内 `packages/api-types` 相对引用 **0** 处 |

## 已知限制

1. `exports` 的三个子路径指向 `.d.ts`，因此**只能承载 `import type`**。web 的 14 处已逐条核实为类型导入（`grep` 无值导入）；miniprogram 的 5 处顺带查看也全是 `import type`，但其形式将在它自己的批次里再核。若将来要从这里导出值（例如枚举对象），必须先把 `.d.ts` 改为 `.ts` 并给出可运行的 JS 产物，否则会在运行时解析失败。
2. 根导出 `.` 仍指向 `src/index.ts`，而它 `export` 自 `../target/generated-sources/openapi/...`；本地 `packages/api-types/target` **不存在**（由 Maven 生成）。故 `import from "@fams/api-types"`（根说明符）在本地会失败——这不是本批引入的，且现在无人使用根说明符；也正因如此本批刻意让 web 只走三个子路径，不把 14 处并到根导入上。同理未跑包自身的 `npm run typecheck -w @fams/api-types`（它同样依赖 `target/`）。
3. `apps/web` 与包之间的版本约束用 `"*"`（npm workspaces 就地解析），不是精确版本；workspace 内部包这样处理是常规做法，但意味着 CI 若改用 `npm ci --omit=workspace` 之类口径需另行验证。
4. 验证为单测 + 构建 + 探针，未接真实后端；不替代远端 CI、正式 QA 与生产验收。

## 下一批

ARCH-06：`apps/web/src` 按业务域重构为 `features/<domain>/{api,views,panels}` 切片，18 个 `*Panel.vue` 从 `views/` 归位，同步改 router 35 条懒加载路径、66 个测试路径与 `@/` 引用。本批已完成的前置：HTTP 底座与 composable 都收敛在少数文件内，导入说明符已不含跨层相对路径，因此目录移动的 diff 只剩路径本身。
