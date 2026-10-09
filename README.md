# FAMS 固定资产管理系统

面向企业的固定资产管理项目，包含 PC 管理端、原生微信小程序和 Java 后端。围绕资产台账、入库、领用、借用归还、调拨与人员交接构建业务流程，并提供身份权限、主数据、附件和审计能力。

> 项目正在第六阶段「技术开发与实现」。已完成的内部能力经过本地开发验证和用户复核；远端 CI、正式 QA、生产验收及真实短信、微信和真机联调尚未完成。当前状态与下一步以 [项目交接入口](DESIGN-HANDOFF.md) 和 [开发计划](docs/superpowers/plans/2026-09-12-fams-stage6-development-plan.md) 为准。

## 当前能力

- PC 登录、会话、角色、数据范围和字段权限。
- 组织、人员、仓库、位置等主数据，以及资产台账。
- 常规入库、Excel 导入、期初核验发布及受控回滚。
- 附件隔离存储、扫描、受控下载与审计。
- 小程序身份会话、短信绑定与换绑的内部实现。
- 领用申请与审批、交出接收、借用归还、调拨和人员交接。

D6-14 已通过用户复核，下一内部开发项为 D6-15。外部通道及真机验收仍按计划后置；具体子任务状态和验证证据见开发计划及 `docs/development/` 下的任务卡。

## 技术栈

| 层次 | 技术 |
|---|---|
| PC 管理端 | Vue 3、TypeScript、Vite、Element Plus、Pinia |
| 小程序 | 原生 WXML/WXSS、TypeScript |
| 后端 | Java 21、Spring Boot 4.1.1、MyBatis、Spring Session JDBC |
| 数据与迁移 | MySQL 8.4.11、Flyway |
| 接口契约 | OpenAPI、Java / TypeScript 类型生成 |
| 开发验证 | Vitest、Node Test、JUnit、ArchUnit、Checkstyle、SpotBugs、JaCoCo |

依赖版本以各模块的 `pom.xml`、`package.json` 和锁文件为准。

## 目录结构

```text
apps/web/             PC 管理端
apps/miniprogram/     原生微信小程序
backend/              Java 服务、数据库迁移与测试
contracts/            OpenAPI 契约及示例
packages/api-types/   前端共享接口类型
config/               静态检查配置
tools/git/            提交信息校验、依赖锁文件与测试
.githooks/            项目共享 Git hooks
scripts/              开发验证与隔离运行环境脚本
docs/                 需求、设计、开发计划和验证证据
compose.yaml          本地 MySQL 服务
```

## 环境准备

| 工具 | 要求 |
|---|---|
| JDK | 21，确保 `JAVA_HOME` 与 `PATH` 指向同一版本 |
| Node.js | 24.18.0 |
| npm | 11.16.0 |
| Maven | Wrapper 固定 3.9.16，无需另装同版本 Maven |
| Python | Python 3，运行校验与开发脚本 |
| Docker | 支持 Compose，执行数据库与真实运行态验证时需启动 Docker |
| RTK | 现有 npm 脚本和验证入口依赖 `rtk` 命令 |

克隆项目并安装锁定依赖：

```sh
rtk git clone https://github.com/LiXuD/fams-manage.git
```

在终端执行 `cd fams-manage` 进入仓库目录，再安装依赖并启用提交检查：

```sh
rtk npm ci
rtk npm ci --prefix tools/git --ignore-scripts
rtk git config --local core.hooksPath .githooks
rtk ./mvnw --version
```

macOS 可通过系统 JDK 选择器配置 Java 21；其他系统使用对应的 JDK 安装路径。删除本机 `.java-version` 不会取消项目对 Java 21 的要求。

## 本地开发

### 后端

先检查契约并构建服务：

```sh
rtk ./mvnw -pl backend validate -DskipTests
rtk ./mvnw -pl backend -DskipTests package
```

产品服务需要显式启用 `fams-product` profile，并连接真实 MySQL。默认 profile 用于契约与传输验证，不提供完整产品运行环境。

启动前，在本地运行环境中设置以下配置：

| 环境变量 | 用途 |
|---|---|
| `SPRING_DATASOURCE_URL` | MySQL JDBC URL，数据库使用 UTF-8，时区为 UTC |
| `SPRING_DATASOURCE_USERNAME` | 数据库账号 |
| `SPRING_DATASOURCE_PASSWORD` | 数据库密码 |
| `FAMS_SECURITY_IDEMPOTENCY_HMAC_KEY` | 持久且保密的幂等摘要密钥 |
| `FAMS_EMPLOYEE_PHONE_KEY` | 人员与资产敏感字段加密密钥；应显式配置并稳定保存 |
| `FAMS_ATTACHMENTS_QUARANTINE_ROOT` | 启用附件能力时的本地隔离存储目录 |
| `FAMS_ATTACHMENTS_CLAMD_HOST` / `FAMS_ATTACHMENTS_CLAMD_PORT` | 附件扫描服务地址，默认 `127.0.0.1:3310` |

```sh
rtk ./mvnw -pl backend spring-boot:run -Dspring-boot.run.profiles=fams-product
```

后端默认端口为 `8080`。Flyway 按产品 profile 执行版本化迁移，不提供默认管理员账号；数据库初始化、账号与权限应按实际环境准备。附件扫描需另行配置 clamd，当前 Compose 文件只包含 MySQL。

若使用仓库的 `compose.yaml`，需提供 `FAMS_MYSQL_DATABASE`、`FAMS_MYSQL_USER`、`FAMS_MYSQL_PASSWORD`、`FAMS_MYSQL_ROOT_PASSWORD`，然后启动：

```sh
rtk docker compose up -d mysql
```

MySQL 默认绑定 `127.0.0.1:33306`，可用 `FAMS_MYSQL_PORT` 覆盖。数据库数据保存在命名卷中。Compose 可读取本地 `.env`；Java 进程不会自动读取该文件，应用所需配置必须单独导入运行环境。

### PC 管理端

```sh
rtk npm run dev --workspace @fams/web
```

开发入口为 `http://127.0.0.1:5173`，Vite 将 `/api` 代理到 `http://127.0.0.1:8080`。页面依赖真实后端、有效账号和对应权限。会话使用 HttpOnly、Secure Cookie；共享或部署环境需要正确的 HTTPS 入口。

### 微信小程序

```sh
rtk npm run test:mini
rtk proxy env FAMS_MINI_API_ORIGIN=https://your-corporate-api.example rtk npm run build --workspace @fams/miniprogram
```

将 `apps/miniprogram/dist` 导入微信开发者工具，并使用自己的 AppID 和合法 HTTPS 请求域名。示例 API 域名需要替换；未配置 API 源时页面会显示服务未就绪。详细配置与外部验证边界见 [小程序说明](apps/miniprogram/README.md)。

## 开发验证

前端与小程序的常用检查：

```sh
rtk npm run lint
rtk npm run typecheck
rtk npm run test:web
rtk npm run test:mini
rtk npm run build:web
```

当前产品的统一开发门禁：

```sh
rtk python3 scripts/verify-product-development.py
```

该门禁要求工作区干净、锁定工具链可用且 Docker 已启动；脚本创建独有数据库运行环境，执行 Java / TypeScript 质量、依赖完整性及服务恢复验证，并在结束时清理其创建的资源。本地通过仅表示对应候选的开发验证结果，不替代远端 CI、正式 QA 或生产验收。

调拨与交接的专项真实数据库 / HTTP 检查：

```sh
rtk python3 scripts/verify-d6-14-runtime.py
```

专项脚本当前使用 macOS JDK 选择器，其他平台需先适配工具链选择。浏览器、权限、持久化和重启证据见 [D6-14 任务卡](docs/development/D6-14-task-card.md)。

## 提交规范与本地文件

提交信息使用 `<type>(<scope>): <summary>`，并附背景、主要变化及四个独立字段：`Agent-Task`、`Agent-Model`、`Agent-Decision`、`Agent-Limitation`。规则详见开发计划 §四.4。

`.githooks/` 与 `tools/git/` 是共享的提交校验工具，应纳入版本控制。克隆后需安装其锁定依赖并配置 `core.hooksPath`，本地 hook 才会生效；本地 hook 不代表远端已配置强制检查。

`.aoci/`、AOCI 索引、`.claude/`、`CLAUDE.md`、`.java-version`、依赖目录、构建产物、运行日志和凭据仅留本地，由 `.gitignore` 排除。Maven Wrapper 的 `mvnw`、`mvnw.cmd` 和 `.mvn/wrapper/` 为共享构建入口，继续版本化。

## 项目文档

- [项目阶段与交接入口](DESIGN-HANDOFF.md)
- [产品需求 PRD V1.5](docs/superpowers/specs/2026-09-03-fixed-asset-product-design.md)
- [信息架构与交互设计](docs/superpowers/specs/2026-09-06-fixed-asset-ia-interaction-design.md)
- [第六阶段开发计划与任务台账](docs/superpowers/plans/2026-09-12-fams-stage6-development-plan.md)
- [D0.4 视觉设计交接](docs/superpowers/designs/2026-09-06-fams-ui/HANDOFF-D0.4.md)
