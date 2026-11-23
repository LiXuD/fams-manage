# CONV-02 · Controller 异常收敛与 A4 口径纠正

> 用户批准第 2 方案（A 级 + B2）系列第二批。基线 `4ec93f1`（CONV-01）。
> 日期：2026-11-23～24 · 分支：main · 执行方式：3 个并行子代理按域分组（G-A/G-B/G-C）
> 状态：已实施、审计、合并门禁通过（运行时门禁见 §5，与本批记录并行进行）。

## 1. 本批最重要的产出：A4 原口径大部分是误判

审查报告把 41 处 Controller `catch` + 26 处 error-return 笼统记为"违反 Controller 不捕获异常"。
逐处读码后，只有 **2 处**是真正的规范缺口，其余 3 类都各有成立的理由：

| 型 | 数量 | 实况 | 处置 |
|---|---|---|---|
| T1 `catch (NumberFormatException)` | 15 | `parseId` 把非数值路径参数转成**域 404 + 具名 code** | **保留**，登记为有意设计（见 §2.1） |
| T2 `catch (CannotAcquireLockException)` | 8 | 不是错误映射，而是**首次锁冲突透明重试一次**、二次才重抛交 Advice | **保留**，登记（见 §2.2） |
| T3 `catch (XxxService.ExportLimit)` | 15 | 产出 **422 + 文本体 `REPORT_EXPORT_LIMIT`**（不是审查报告写的 200+截断 csv） | **15 处重复骨架抽为 helper** |
| T4 `catch (RuntimeException)` → 域异常 | 3 | 防外部微信/短信异常裸透传；其中一处 catch 还承载审计副作用 | **保留**，登记 |
| T5 `catch (IOException)` → `IAE(code)` | 1 | 真缺口 | **迁入 `AssetImportErrorHandler`** |
| error-return 空体 400/403/404 | 26 | 契约 `responses: {'400': {description}}` **无 content**，即要求空体 | **保留**，改异常即违约 |

## 2. 三类"保留"的判据（子代理逐处读码证据）

### 2.1 T1 强类型化会反转鉴权顺序并引入解析差异

首选改法是把 `@PathVariable String id` 改成 `long`，由
`MethodArgumentTypeMismatchException` handler 产出同样响应。G-B 证明其不可行：

- **鉴权顺序反转**：八处 `parseId` 调用点全部位于 `actorContext.requireSystemAdministrator()`
  之后（如 `masterdata/WarehouseController.java:67-68`）。类型转换发生在参数解析阶段、方法体之前，
  改后"非管理员 + 非法 ID"由 **403 变 404**，等于向未授权调用方泄露资源存在性。
- **message 逐控制器不同**：`MasterDataOperationException.notFound(subject)` 的 message 是
  `subject + " does not exist."`，八个 subject 各异；`basePackages` 级单一 handler 只能按 URI
  重建映射表，那是新推导逻辑，不可证明等价。
- G-A 实测另有一条：**Spring 绑定用 `NumberUtils.parseNumber`**，`"0x10"`→16、`" 10 "`→10 会
  绑定成功进入查询，而原 `parseLong` 直接 404。据此主代理**回退**了 G-A 已做的 asset/reminder
  强类型改动（`AssetController`、`AssetPreferenceController`、`AssetErrorHandler`、
  `ConfigurationAnomalyController`、新建的 `ReminderErrorHandler`），避免把 403 退化为 404。
- auth 三处结构上不可改：`employeeId/accountId/systemAccountId` 被只读契约接口
  （`MiniIdentitySupportApi`/`MiniBindingApi`/`MiniBindingApi`）锁死为 `String`，`@Override` 无法
  改 `long`，也不会产生 TypeMismatch；且 `AccountAdminController.parseId` 还服务请求体字段。

### 2.2 T2 是重试环而非错误映射

四处 inventory + 四处 repair 的实际结构：

```java
for (attempt = 0; ; attempt++) {
    try { return service.call(...); }
    catch (CannotAcquireLockException ex) { if (attempt >= 1) throw ex; }
}
```

`inventory/InventorySealController.java:53-54` 有注释明示这是有意设计（代理服务已回滚，重试同一
操作一次）。删除 catch 会让今天靠重试拿到 2xx 的请求直接变 409，且副作用执行次数改变，属行为重设计。
`LossController` 的 retry helper 被约 30 个写端点共用，影响面更大。

兜底事实（供后续批次引用）：`CannotAcquireLockException` 在 inventory 域仅由 `@Order(-2)` 的
`InventoryErrorHandler` 兜底（`INVENTORY_WRITE_CONFLICT`），其余 5 个 `@Order(-3)`
assignableTypes handler 不映射它；loss 由 `LossErrorHandler.race()`（`LOSS_WRITE_CONFLICT`）；
repair 由 `RepairErrorHandler:65-68`（`REPAIR_WRITE_CONFLICT`，assignableTypes 覆盖 4 个控制器）。
即**重抛末端 code 本可逐字对上**，但重试语义不可丢。

## 3. 本批实际改动

- 新建 `backend/src/main/java/com/fams/report/ReportCsvExport.java`：包私有 helper，承载
  「导出 200 + `Cache-Control: no-store` + `Content-Disposition` + `ExportLimit` → 422 文本体」
  这一在 14 个文件中逐字重复的骨架。15 个调用点（`SealedInventoryReportController` 两处）。
  各控制器自己的 `valid` 判定表达式、文件名与 `var actor` **留在控制器**，helper 只承载形状。
- `assetimport/AssetImportController.java:60` 删除 try/catch，`upload` 声明 `throws IOException`；
  `assetimport/AssetImportErrorHandler.java` 增 `@ExceptionHandler(IOException.class)` →
  `response(400, "ASSET_IMPORT_INVALID_XLSX")`，与原 `catch→IAE→invalid()` 的
  `ErrorResponse(code, code, id, Map.of())` 逐字节同形。已 grep 证明 assetimport 全包仅
  `upload` 的 `file.getBytes()` 会产生未捕获 IOException，新 handler 不劫持其它端点。
- **未**新建 `report/ReportErrorHandler.java`：report 域 15 处 catch 全部归 helper，域内已无
  可删 catch；此时挂 advice 会把其它导出端点原本自由传播的异常改写为错误响应，属改语义。

## 4. 等价性判据（为何 helper 不是行为变化）

`ReportCsvExport.csv(fileName, supplier, isLimit)` 与原形状的对应关系逐点核对：响应状态、
`Cache-Control`、`Content-Disposition` 字面量值、`Content-Type: text/csv;charset=UTF-8`、body 由
supplier 产生、limit 分支 `unprocessableContent().body("REPORT_EXPORT_LIMIT")` = 422 + 文本体。
非 `ExportLimit` 异常在 helper 内**原样 rethrow**，传播路径与今天一致（继续落到全局 auth 兜底）。
谓词 `ExportLimit.class::isInstance` 与原 catch 的类型集合完全相同。`OperationsReportController:54`
是 `IllegalArgumentException` + `"REPORT_EXPORT_LIMIT".equals(message)` 双条件，谓词完整保留该判定。

## 5. 门禁证据

合并 CONV-02 + CONV-03 后，主树 `-Pquality checkstyle:check@check-java-conventions test`：
`Tests run: 648, Failures: 0, Errors: 0, Skipped: 167`、`0 Checkstyle violations`、BUILD SUCCESS，
与 `6431f06` 基线逐项相同。

含 docker MySQL 8.4.11 的完整 `verify-d6-03a` 运行时门禁在独立 rsync 副本
（`/tmp/conv-gate`，排除 `.git/target/node_modules/.qoder/.worktrees/.idea/output`）实跑，
驱动脚本 `/tmp/java01-verify.py`，日志 `/tmp/conv-gate.txt`。**结果见 CONV-03 记录 §5**
（两批改动在同一副本内一并验证，不重复跑）。

## 6. 过程坑账（供后续批次直接取用）

- **审查报告的分类必须以读码为准**：本轮 T2/T3 两类共 23 处，规格里的行为描述（"catch → 409"、
  "ExportLimit → 200+截断 csv"）**全部与实际不符**，是子代理读码纠正的。给子代理的分类前提
  只能作为假设，规格里必须写明"与前提不符即停手报裁决点"——本轮该条款生效 3 次，避免了
  8 处重试语义被误删。
- **首选方案要先证明鉴权顺序不被提前**。把路径参数改成强类型看似最规范，但它把失败点从方法体
  （鉴权之后）移到参数绑定（鉴权之前），会把 403 变成 404 并形成存在性 oracle。
- **主代理必须回退子代理"合规但改行为"的产物**，并在记录里写清回退理由，否则下轮审查会重复发现。
