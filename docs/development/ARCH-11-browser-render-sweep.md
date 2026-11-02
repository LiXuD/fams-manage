# ARCH-11 真实浏览器逐路由渲染普查（ARCH-08～10 的运行时等价判据）

基线：`ARCH-08` 前最后一个提交 `bdb6eb0`；被验对象 `46bb20a`（ARCH-08/09/10 六批的 HEAD）。

ARCH-08～10 六批全部主张「不改用户可见行为」，此前的证据是判据脚本 + 类型检查 + 308 用例 + 模板结构/文案不变量，**没有真实浏览器**。三次批次都记了这条局限。本批把它补上：同一后端、同一库状态、同一份路由清单，让 HEAD 与 `bdb6eb0` 各渲染一遍，逐路由比对。

## 结论

```
compared=117 {"SAME":117,"STATUS_DIFF":0,"STRUCT_DIFF":0,"TEXT_DIFF":0,
              "CONTROL_DIFF":0,"ISSUE_DIFF":0,"PROBE_DIFF":0,"MISSING":0}
```

39 条路由 × 3 个角色（admin / warehouse / employee）= 117 个渲染单元，**全部一致**：路由解析后的最终 URL、`#app` 的 DOM 结构签名、掩码后的 `innerText` 指纹、控件总数与禁用数、翻页探针结果、运行期 console/异常/HTTP≥400 事件集合逐项相同。两侧文本总长同为 **39,622 字符**，掩码时间戳同为 **20 处**。

## 栈与数据

| 部件 | 实测口径 |
| --- | --- |
| MySQL | 容器由本次运行自建自删，镜像固定 digest `mysql:8.4.11@sha256:3466ba4a…881faa26`；口令随机，仅落 `/tmp` 信息文件 |
| 后端 | `./mvnw -B -DskipTests package` 13.2s，产物 `contract-generation-probe-0.1.0-SNAPSHOT.jar` 59,130,903 B；`--spring.profiles.active=fams-product`，Flyway 建库 |
| 前端两份 | HEAD 走 `apps/web` 的 vite dev（5173）；基线走 `git worktree add /tmp/fams-base-bdb6eb0 bdb6eb0` + 根/`apps/web` 两级 `node_modules` 软链，vite dev 5174。两者 `/api` 都代理到同一个 8080 |
| 种子 | 主数据（2 组织 / 2 分类 / 1 仓库 / 3 位置 / 4 员工 / 4 账号 3 角色 / 编号规则 / 参数版本）+ 10 张覆盖各状态的资产卡 + 24 张同形状在库卡（让台账跨过 20 行分页边界） |
| 单据 | **只走产品自己的 HTTP API**（登录→建草稿→提交→批准→领取），不手写库内快照：入库 2（其中 1 已批准并被库管领取）、报修 2、领用 1（已批准）+ 借用 1（草稿）、盘点任务 1（草稿）、调拨 1（草稿）、退库 1。共 9 张，覆盖 6 个域 |
| 造数据未通的一步 | 入库逐项验收返回 `409 INBOUND_ASSET_STATE_CONFLICT`——草稿卡在验收入库前的状态机不匹配；本轮未继续追，验收面板因此停在未验收态 |

种子形状受库内 CHECK 约束约束（`ck_asset_custody` 要求 STORAGE 带仓库+位置且不带使用组织/责任人，PERSONAL/SHARED 不得带仓库；`ck_asset_lifecycle` 限定 8 个生命周期值；`ck_fams_numbering_rule_target` 只允许 `ASSET`/`INBOUND_RECEIPT`），脚本报 `PARTIAL` 时逐条打印失败语句，不静默跳过。

## 判据（为什么这样比）

- **文本与结构分开指纹**：文案变一个字符 → TEXT_DIFF；DOM 形状变（多/少一个 wrapper、class 组合变化）→ STRUCT_DIFF。两类回归性质不同，混在一个哈希里会互相掩盖。
- **易变文本掩码**：`YYYY-MM-DD[ T]HH:MM`、`M/D/YY, H:MM`、`HH:MM:SS` 掩为 `«T»`，掩码处数如实计入报告（本次 20 处），不静默吞差异。
- **控件清单**：`button/a/input/select/textarea` 总数与禁用数 + 前 40 个按钮文案 + 前 40 个字段占位符。ARCH-10 的 `busyRegistry` 改的就是「谁在忙 → 哪个按钮禁用」，控件计数与按钮文案集是它最直接的落点。
- **只读交互探针**：找文案以「下一页」开头的按钮，`absent / disabled / clicked` 三态之一；clicked 时再采一次掩码文本指纹与页码文本。资产台账 admin/warehouse 两格 `clicked@第 2 页`（页码 1→2 且第二页行文本一致），employee 因 `403` 停在 disabled。其余 45 格按钮存在但因单量不足 20 行而禁用，4 格视图该态无该按钮，66 格未标探针。
- **运行期事件**：`Runtime.consoleAPICalled(error|warning)`、`Runtime.exceptionThrown`、`Log.entryAdded(error)`、`Network` 的 4xx/5xx 与 `loadingFailed`。favicon 缺失按 url 过滤，属本地栈噪声。

## 运行时噪声（两侧完全相同，故不构成差异）

33 条 HTTP≥400 事件、22 个端点，逐端点两侧计数一致：

| 类别 | 端点样本 | 判定 |
| --- | --- | --- |
| 角色权限 | `/api/v1/repair-collaborations`、`/asset-initialization-windows/operators`、`/inventory-tasks/catalog/organization`、`/proxy-confirmations`、`/requisition-target-changes`、`/assets`（employee）等 403 | 服务端按当前会话重新校验的预期拒绝，视图渲染其无权态 |
| 占位 id | `/api/v1/loans/1`、`/assets/1`、`/inventory-tasks/1?page=…` 404 | 本轮没造该域单据，路由回退占位 id，视图渲染「未找到」 |
| 栈配置缺口 | `/api/v1/inbound-receipts/1/attachments` **500** ×2 | 本栈未设 `fams.attachments.quarantine-root`，`AttachmentProductConfiguration` 的存储 Bean 不成立；属测试栈缺件，不是前端回归，也**未在产品配置下复验** |

41 个单元渲染的是空态、20 个单元渲染出真实资产行（文本含 `FA-` 编码），后者是 ARCH-08 行派生改造的真正受试面。

## 会话注入的口径（一处妥协，必须写明）

后端把 `FAMS_SESSION` 标了 `Secure`，http 回环下 Chrome 拒收该 Cookie，**登录表单在真实浏览器里点不通**（不是前端缺陷，是本地栈用 http 的后果）。因此脚本改为：调用真实 `/api/v1/auth/login` 拿到会话与 CSRF 令牌，再用 CDP 写入同名非 Secure Cookie + `Page.addScriptToEvaluateOnNewDocument` 在应用首个脚本前把令牌放进 `sessionStorage("fams.csrf")`。被替代的只有「输入账号密码」这一步，之后每个路由都是真实渲染、真实接口调用。

## 已知限制

1. **只测读路径**。写入按钮一律不点——点了两侧库状态就会分叉，等价性对比失效。确认式幂等提交（ARCH-10 的 `useConfirmedSubmit`）因此只验到「按钮可用性与文案」，未验到真实提交往返；它的写路径正确性目前仍靠 308 用例与 D6 系列的真实链路批次。
2. **覆盖不均**。loan / 异常代确认 / 结果通知 / 批量导入 / 期初窗口 5 个域本轮没有单据，其列表与详情走空态或「未找到」；调拨/退库只有草稿态。
3. 附件 500 未在配好 `quarantine-root` 的栈上复验。
4. 基线那份前端共用主仓的 `node_modules` 与 `@fams/api-types` 包（ARCH-07 的说明符解析走主仓 `packages/api-types`）；两批之间该包无变化，故不影响结论，但严格说基线并非完全自足的依赖树。
5. 掩码只覆盖日期/时间三类形态；若某视图渲染了相对时长（「剩 2 天」），其随墙钟变化的差异不会被掩掉——本轮 117/117 一致，说明未出现该情形。
6. 本地验证，不替代远端 CI、正式 QA 与生产验收。

## 复现

```bash
./mvnw -B -DskipTests package                                  # Java 21
python3 scripts/run-arch-ui-stack.py --web-port 5173 &         # MySQL + fams-product + vite dev(HEAD)
python3 scripts/seed-arch-ui-docs.py                           # 9 张单据，只走 HTTP API
python3 scripts/arch-ui-routes.py                              # router.ts → 39 条路由 + 探针标记
git worktree add /tmp/fams-base-bdb6eb0 bdb6eb0
ln -sfn <repo>/node_modules /tmp/fams-base-bdb6eb0/node_modules
ln -sfn <repo>/apps/web/node_modules /tmp/fams-base-bdb6eb0/apps/web/node_modules
(cd /tmp/fams-base-bdb6eb0/apps/web && npx vite --port 5174 --strictPort)
PW=$(python3 -c "import json;print(json.load(open('/tmp/fams-arch-stack.json'))['password'])")
node scripts/arch-ui-cdp-sweep.mjs --mode sweep --base http://127.0.0.1:5173 --routes /tmp/arch-routes.json --password "$PW" --out /tmp/sweep-head.json --port 9411 --settle 700
node scripts/arch-ui-cdp-sweep.mjs --mode sweep --base http://127.0.0.1:5174 --routes /tmp/arch-routes.json --password "$PW" --out /tmp/sweep-base.json --port 9421 --settle 700
node scripts/arch-ui-cdp-sweep.mjs --mode diff --left /tmp/sweep-base.json --right /tmp/sweep-head.json
```

三个脚本：`scripts/run-arch-ui-stack.py`（栈与种子）、`scripts/seed-arch-ui-docs.py`（造单据）、`scripts/arch-ui-routes.py`（路由清单）+ `scripts/arch-ui-cdp-sweep.mjs`（CDP 采集与比对）。Chrome 路径写死为 `/Applications/Google Chrome.app`，端口 8080/5173 写死（vite 代理目标即 8080）。

## 下一批

ARCH-08～10 的运行时等价性已实测收口。若继续，按本批限制 1/2 排两个候选：把写入交互纳入可对比口径（需要两侧各起一个独立库、比对落库事实而非渲染文本），以及补齐 loan/异常/导入三域的行级渲染。
