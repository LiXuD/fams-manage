# D6-04 task card — PC 登录与权限

## 任务身份与目标

- **ID：** D6-04 PC 登录与权限。
- **目标：** 在 D6-02C 已审查的身份/权限契约与 D6-03A 本地质量门禁上，交付第一个真实产品纵切片：PC 账号登录、首次密码修改、安全 Cookie 会话、当前身份与权限快照、账号/角色/数据范围配置，以及 P01 登录和权限感知的 PC 外壳。
- **状态：** `IN_PROGRESS`（2026-09-21 已形成续接交接；没有子任务达到关闭条件）。
- **分支 / 基线：** `feat/D6-04-pc-login-permissions`；`5f4bc8ae842dbd2ee3f2c7f6ea8a2ae3d0071513`。
- **任务启动提交：** `423fd8a`；该提交之后的产品草稿当前全部未提交。
- **前置条件：** D6-02C、D6-03A 均为 `DEV_VERIFIED`；D6-01B、D6-03B 不阻塞本地纵切片。
- **规模：** L；按后端会话、账号授权、PC 页面与真实验收四个独立闭环拆分。

## 已批准范围

1. 实现 `login`、`changeInitialPassword`、`getCurrentUser`、`getMyPermissions`、`logout`，响应和错误形态服从 `contracts/fams-core-v1.yaml`。
2. 采用 Spring Session JDBC 与 `FAMS_SESSION` 安全 Cookie；固定 `Secure`、`HttpOnly`、`SameSite=Lax`、`Path=/api`，写请求使用 `X-CSRF-Token`。
3. 登录后轮换会话；首次改密更新认证纪元、注销当前会话并签发新会话；注销、账号/员工停用、重置密码、角色或授权变化后旧会话在下一请求失效。
4. 系统管理员可维护系统账号、显式角色和受控数据范围/字段权限；`SYSTEM_ADMIN` 不自动取得资产业务权限。
5. PC 实现 P01 登录、首次改密和登录后权限感知外壳；客户端只根据服务端动作控制可见性，服务端仍逐请求授权。
6. 安全事件记录登录成功/失败、CSRF、注销、改密和授权失效，不记录密码、摘要、完整 Cookie 或 CSRF 值。

## 明确排除

- 不实现微信登录、短信绑定、换绑、解绑或真机证据；这些属于 D6-10A/B。
- 不实现基础资料、资产、入库、附件或其他生命周期业务；它们分别属于 D6-05～D6-09。
- 不以静态页面、内存账号、Mock 会话、浏览器状态注入或 H2 代替真实 MySQL 和真实 Cookie 链路。
- 不配置 GitHub 远端 CI、分支保护或生产 HTTPS；本地证据不升级为 QA、生产或安全专项结论。

## 必需子任务

| 子任务 | 交付 | 状态 | 当前停点 |
|---|---|---|---|
| D6-04A 后端认证与会话 | 产品迁移、密码校验、Cookie/CSRF、会话轮换/失效、当前身份与权限、安全审计 | `IN_PROGRESS` | 已有迁移、密码/CSRF/会话版本校验等局部草稿和 11 项单元测试；尚无控制器、持久化、真实 Session/Cookie 或 MySQL 产品迁移证据 |
| D6-04B 账号与授权配置 | 账号状态、显式角色、受控数据范围/字段权限、授权版本和旧会话失效 | `IN_PROGRESS` | 已有角色、数据范围、权限快照和授权表草稿；尚无配置 API、持久化更新、字段授权执行或真实旧会话失效链路 |
| D6-04C PC 登录与权限外壳 | Vue 3 + TypeScript P01、首次改密、当前会话恢复、权限感知导航与错误反馈 | `IN_PROGRESS` | 已有 Vue/Vite 外壳、API、会话 store、路由及页面草稿，8 项单元测试通过；类型检查/构建仍失败 |
| D6-04D 真实链路与复核 | 真实 MySQL、真实 Cookie/CSRF、A-04-01/02、真实浏览器及非实施者复核 | `NOT_STARTED` | 尚无可运行全链路、浏览器证据或独立复核 |

## 2026-09-21 交接检查点

### 已完成并已提交

- 任务范围、必需子任务、关闭条件与隔离分支已由 `423fd8a` 落盘。
- D6-02C、D6-03A 前置仍为 `DEV_VERIFIED`；本地开始 D6-04 不依赖尚未取得的远端权限。

### 未提交的后端草稿

- `backend/pom.xml` 增加 `spring-security-crypto`。
- `backend/src/main/java/com/fams/auth/` 包含账号状态、账号快照、角色、数据范围、权限快照、密码规则、CSRF 常量时间比较和会话认证纪元/授权版本校验等领域原语。
- `backend/src/main/resources/db/migration/d6_04/V1__create_authentication.sql` 草拟系统账号、账号角色、授权、数据范围、字段权限、安全事件及两张 Spring Session 表。
- `backend/src/test/java/com/fams/auth/` 有 4 个测试类、11 项认证/授权单元测试，当前均通过。
- 这些文件尚未实现或证明 HTTP 端点、登录失败统一响应、密码摘要读写、Spring Security/Session 配置、Cookie 属性、CSRF 过滤、持久化仓储、安全事件写入、会话轮换/注销及真实失效链路。

### 未提交的 PC 草稿

- 根 `package.json`、`package-lock.json` 与 `apps/web/` 已加入 Vue 3、Pinia、Vue Router、Element Plus、Vite/Vitest 依赖和脚本。
- `apps/web/src/auth/` 已有 Cookie 通道 API、CSRF 请求头和会话 store 草稿；页面包含 P01 登录、首次改密、首页和权限感知外壳。
- 8 项 Vitest 用例当前通过，覆盖 Cookie 通道、不写 Authorization、本地不持久化会话、CSRF/幂等头、初始密码路由和稳定错误码等局部行为。
- `build:web` 当前失败：测试断言对象类型不完整、测试数据的 `roles` 推断为 `string[]`、`@` 路径别名未被 TypeScript 解析，并缺少 `node:url` 类型。故 D6-04C 不可送审。
- `apps/web/.vitest/json/output.json` 是未提交的本地测试输出；续接时先按文件分类规则决定是否保留为正式证据，不要把它误作源代码提交。

### 本轮实际验证

| 命令 | 结果 | 能证明 | 不能证明 |
|---|---|---|---|
| `rtk mvn -f backend/pom.xml test` | 退出 0；34 项运行、0 失败、0 错误、3 skipped；其中 D6-04 auth 11/11 通过 | 局部 Java 领域规则和既有默认测试通过 | 3 项既有 `RuntimeCompatibilityHttpTest` 因缺少 `d6.02b.runtime` 跳过；不证明真实 MySQL、HTTP、Session 或 Cookie |
| `rtk proxy env PATH=/Users/lixd/.nvm/versions/node/v24.18.0/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin npm run test:web` | 退出 0；8/8 测试通过 | 局部 API/store/登录表单行为通过 | 不证明类型检查、构建、真实后端或浏览器链路 |
| 同一固定 Node `PATH` 下运行 `npm run build:web` | 退出 2 | 暴露当前 TypeScript/配置阻断 | 不能把单元测试绿灯升级为可构建客户端 |

## 恢复与续接

1. 进入 `/Users/lixd/IdeaProjects/Git/CodexProject/fams/.worktrees/d6-04-pc-login-permissions`，确认分支为 `feat/D6-04-pc-login-permissions`，不要 reset、checkout 或清理现有未提交草稿。
2. 以 `423fd8a` 作为最后一个已提交任务起点；先审阅工作树差异，再按功能边界拆分提交，禁止把后端、PC 和交接文档压成一个大提交。
3. 先完成 D6-04A：补齐应用服务、控制器、持久化、Spring Security/Session/Cookie/CSRF 和安全事件，并在真实 MySQL 上验证迁移、登录、初始改密、轮换、注销、失效和重启。
4. 再完成 D6-04B：实现账号/角色/范围/字段授权配置及授权版本更新，用拒绝用例证明系统管理员没有隐含资产业务权限、撤权后旧会话失效。
5. 保留现有 Web RED；修复上述类型与别名问题，使 `test:web` 和 `build:web` 同时退出 0，再接真实 API 完成 D6-04C。
6. 最后执行 D6-04D：运行 D6-03A 保留门禁，以真实表单、真实 Cookie/CSRF、真实 MySQL 和真实浏览器完成 A-04-01/02，并由非实施者复核 reviewed HEAD。
7. D6-01B、D6-03B 仍由 GitHub 私有仓库与权限阻塞；这不阻止本地 D6-04，但不得声称远端 CI 或分支保护通过。

## 实施与验证顺序

1. 先以失败测试锁定产品迁移、登录统一失败形态、首次改密、Cookie/CSRF、会话轮换和即时失效。
2. 最小实现 D6-04A，在真实 MySQL 上验证迁移、Session JDBC、审计和重启后会话行为。
3. 以授权版本和认证纪元为失效边界实现 D6-04B，并为系统管理员越权、角色撤销、数据范围和敏感字段建立拒绝用例。
4. 以真实 API 实现 D6-04C，不注入 token 或存储状态；客户端不缓存可绕过服务端的新权限。
5. 运行 D6-04 定向入口及 D6-03A 保留门禁；再以真实浏览器完成 P01 登录、首次改密、注销、停用/撤权和 CSRF 拒绝证据。

## 关闭条件

1. D6-04A～D 全部完成，A-04-01/02 在真实 MySQL 与真实会话链路通过。
2. P01 使用真实表单登录；浏览器流量经过真实后端，会话 Cookie 与 CSRF 传输属性符合契约。
3. 账号停用、密码变化、角色/授权变化和注销后的旧会话均不能恢复权限；系统管理员无资产业务默认权。
4. Java/TypeScript、契约漂移、迁移、质量、覆盖率、依赖/敏感扫描与 `git diff --check` 全部通过，无跳过或替身冒充。
5. 非实施者完成规格、权限与质量复核；控制器对 reviewed HEAD 完整复跑后才可写 `DEV_VERIFIED`。

## 当前边界

当前已形成未提交的后端与 PC 局部草稿，但没有任何 D6-04 子任务满足关闭条件。后端只完成单元层部分规则，Web 尚不可构建，真实产品迁移、登录、Cookie/CSRF、权限变更失效、浏览器证据、独立复核、远端 CI、微信、短信、QA、生产部署和正式安全专项均未验证。
