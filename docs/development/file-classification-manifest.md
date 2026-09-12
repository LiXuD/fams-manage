# FAMS 本地基线文件分类清单

> D6-01A · 2026-09-12 · 仅建立本地单仓基线，不代表产品代码、远端仓库或 CI 已存在。

## 分类原则

现有文件先按内容责任和可复现性分类，再纳入 Git。分类不删除、不移动任何本地材料；被排除的文件仍留在当前工作区。权威顺序仍是 `AGENTS.md`、`DESIGN-HANDOFF.md`、PRD V1.5、IA D0.2、D0.4 交接和第六阶段计划。

| 类别 | 版本控制处理 | 路径/范围 | 理由、保留与恢复路径 |
|---|---|---|---|
| 现役治理与人类文档 | 跟踪 | `AGENTS.md`、`DESIGN-HANDOFF.md`、`docs/**/*.md`、`docs/**/*.csv`、`FIXED_ASSET_SYSTEM_DESIGN.md` | 规则、需求、IA、D0.4 交接、评审与阶段记录是可审查来源；由 Git 历史恢复，链接中的外部 Figma 仍由外部服务提供。 |
| 原型与设计源 | 跟踪 | `docs/superpowers/prototypes/**`；D0.4 下的 `*.html`、`*.css`、`*.js`、`*.mjs`、`*.svg` | 可重放的静态原型、样式、组件和导出脚本；按源文件重新生成页面和导出。 |
| 必要的小型机器可读基线 | 跟踪 | D0.4 的 `page-inventory.json`、`source-baseline.json`、`styles.json`、`tokens.json`、`library/manifest.json`、`figma-native/*-map.json`、`figma-native/d04-screen-*.json`、`figma-native/screen-*.json`、`figma-native/d04-native-*.json`、`figma-native/d04-seeds.json`、`figma-native/d04-metric-*.json` 等 | 页面、组件、节点、字体和核验结果是设计交接所需的可复核小基线；由现有脚本及人工 Figma 读回流程更新。明确排除的大型日志/布局 dump 见下表。 |
| 批准设计审查导出 | 跟踪（例外） | `docs/superpowers/designs/2026-09-06-fams-ui/screens/*.png`、`figma-native/d04-live-*.png` | 40 页静态审查和 D0.4 原生交接直接引用这些导出；它们是生成物，但为保持批准设计可审查而保留。来源分别是 `screens.html`/原型脚本和 `figma-native/d04-index.html`；每个文件均远低于 100 MiB。 |
| 历史审查检查点 | 跟踪（例外） | `docs/superpowers/designs/2026-09-06-fams-ui/archive/*.zip` 及同目录历史 Markdown | D0.4 交接文档直接给出两个 ZIP 的校验值和恢复用途；文件小于 100 MiB，保留以支持历史审查和人工恢复。它们不是运行时依赖。 |
| 本地运行/浏览器证据 | 不跟踪 | `.playwright-cli/`、`output/` | 含截图、控制台日志和临时浏览器状态；每次本地验证按命令重新生成，交接文档保留验证边界但不把本地运行证据冒充实现证据。 |
| Codex 编排与临时任务材料 | 不跟踪 | `.superpowers/` | 本地任务 brief/report 和编排中间文件，按要求保留在本机但不成为产品仓库输入；由任务记录或 Git 提交恢复已落盘治理文档。 |
| Figma 操作流水与日志 | 不跟踪 | `**/mcp-client.log`、`**/operations.jsonl`、`*journal*.json`、`*capture-output.txt` | 大型运行日志/操作流水可能含连接上下文或敏感字段，不能作为设计源；由源脚本和 Figma 读回重新生成。 |
| 大型布局/结构生成物 | 不跟踪 | `figma-native/d04-layout-spec.json`、`layout-spec.json`、`native-page-structure.json` | 单个文件可能达到数 MiB，属于可从页面/脚本重生成的中间产物；保留本地，恢复路径为对应 D0.4 生成脚本。 |
| 业务参考模型 | 不跟踪 | `datae.ndm2` | 仅为早期业务参考，不直接转换为产品数据库；保留本地，恢复路径为原始参考文件或后续获批数据设计。 |
| 操作系统/依赖/构建物 | 不跟踪 | `.DS_Store`、`node_modules/`、`target/`、`dist/`、`coverage/`、`.cache/` | 环境或构建副产物，不能成为基线；由系统或包管理器按锁文件重新生成。 |
| 可能敏感的本地配置 | 不跟踪 | `.env*`（`!.env.example`）、`*.pem`、`*.key`、`*.p12`、`*.jks` | 凭证、证书和环境值禁止进入提交；通过受控环境配置重新提供。当前未发现需提交的凭证文件。 |

## 文件链接边界

设计文档中指向 `output/playwright/**`、`.playwright-cli/**` 或本地 Figma 操作日志的路径属于“当前工作区可复核、干净克隆不携带”的本地证据边界；这些链接不被当成代码、接口或生产验收证据。D0.4 的 40 页静态截图、40 页 `d04-live-*.png`、设计源和小型核验 JSON 已跟踪，以保持批准设计交接的核心审查入口可用。外部 Figma URL 依赖对应账号/服务，不由本仓库宣称已验证。

## 新鲜扫描记录

扫描命令（在暂存索引覆盖完整基线后执行）：

```text
rtk scripts/verify-d6-01a.py
```

检查范围包括现有文件是否仍在、`git ls-files` 中的单文件大小（GitHub 100 MiB 限制）、高风险名称/内容模式、`.gitignore` 的高风险路径命中、分支与 hook 配置，以及 53 行连续追溯矩阵。最新结果由 D6-01A 报告和验证脚本输出记录；本次基线不得把忽略目录的本地文件计作已跟踪内容。

2026-09-12 新鲜结果：命令退出码 `0`；暂存基线 `398` 个跟踪文件，`0` 个达到 100 MiB，敏感文件名 `0` 项，敏感内容模式 `0` 项；`8` 个高风险候选均被忽略；追溯矩阵 `53` 行连续且每行有 D6 责任；提交消息正/负行为夹具和 hook 正/负路径均通过。该结果仅覆盖本地索引与本地 hook，不覆盖 D6-01B、CI 或产品运行态。
