# 侦察：新建演示租户（牛业）建租户/建档/建批次正规入口（origin/main `8c04949d54`，只读）

> recon 子代理 2026-09-14 产出（无 Bash，路径/行号来自 Read/Grep）。organizer 裁定见 `2026-09-14-lineage-and-source-individual-briefs.md` 末节「C1」。

## 1. 建工厂/租户

- `entity/Factory.java`：`id`(String PK)、`name`(全局唯一)、`type`(`FactoryType`，默认 `FACTORY`)、`industry`、`industryCode`(大写字母正则)、`regionCode`。
- `FactoryType`：FACTORY / RESTAURANT / HEADQUARTERS / BRANCH / CENTRAL_KITCHEN / LOGISTICS。屠宰加工厂用默认 `FACTORY`。
- `POST /api/platform/factories`（`PlatformController.java:159-170`，`super_admin`/`platform_admin`）→ `FactoryServiceImpl.createFactory` L76-108。请求体 `CreateFactoryRequest`：name/industryCode/regionCode/address/contact*/subscriptionPlan/aiWeeklyQuota/isActive，**无 id、无 type**。
- 🔴 ID 由 `generateFactoryId` 生成：`{industryCode}_{regionCode}_{seq:03d}`（L239-250）——**拿不到字面量 ID**。
- 内部通道 `POST /api/internal/onboarding/create-factory`（`OnboardingController.java:49-57`，`X-Internal-Key`）：同一 createFactory + `provisionDefaultUsers` + FactoryFeatureConfig + FormTemplate + AlertThreshold。
- 没有「一键建演示工厂」脚本；`scripts/e2e/liushanmen-demo/seed-demo-chain.py` 针对已存在的 F006（`FACTORY_ID="F006"` 写死）。
- `SourceIndividual`/`source_individuals`/EAR_TAG 在 main 上零命中（Line B 在做）。

## 2. 新工厂配套初始化

- `UserServiceImpl.provisionDefaultUsers` L828-888：幂等；建 `{fid}_admin`(factory_super_admin) / `{fid}_workshop`(workshop_supervisor) / `{fid}_worker1`(operator)；**密码随机 12 位**。只被 OnboardingController 与 InternalFieldVisibilityController 调用；平台建厂 API 不自动调。
- `seedDefaultSchedulingRule` 在 createFactory 内自动跑（L105，失败不阻断）。
- 模块开关：`FactoryConfigServiceImpl.getEffectiveConfig` 默认 `moduleEnabled=true`（L93），无配置行时 `@RequireModule` 不拦 ⇒ 新厂零配置即可用各模块。
- `role_definitions` 是全局表，无需按厂初始化。

## 3. 基础档案接口

| 实体 | 创建端点 | 导入 |
|---|---|---|
| RawMaterialType | `POST /api/mobile/{fid}/raw-material-types`（`RawMaterialTypeController.java:76-105`；需 `production:read_write`/`warehouse:read_write` **且** 角色 `warehouse_manager`/`factory_super_admin`） | 无 |
| ProductType | `POST /api/mobile/{fid}/product-types`（L150-163） | 有：`import/template` + `import/preview` + `import/confirm`（四工作表 Excel，只导 ProductType） |
| WorkProcess | `POST /api/mobile/{fid}/work-processes`（`WorkProcessController.java:33-41`） | 无 |

## 4. 工艺族图（可整图 JSON 导入）

- `PUT /api/mobile/{fid}/product-process-workflows/{productTypeId}/draft`（`ProductProcessWorkflowController.java:138-145`，body = `ProductProcessWorkflowDTO`）→ `POST .../{productTypeId}/publish-and-activate`（L175-191）。权限 `production:read_write` + 角色 factory_super_admin/workshop_supervisor/department_admin。
- DTO（`dto/ProductProcessWorkflowDTO.java:26-58`）：`{nodes:[{id,kind,position,data}], edges:[{id,source,sourceHandle,target,targetHandle}], viewport}`。
- 真实示例：`ProductProcessWorkflowServiceImplTest.java:1237-1355`（`validDefinition()`「猪蹄→拆分→红烧」）：物料节点 kind `RAW_MATERIAL`/`SEMI_FINISHED`/`FINISHED_GOOD`，`data={name,skuId,skuCode}`；工序节点 kind `PROCESS`，`data={workProcessId,processName,reportingSelectionMode:"ACTUAL_IO",inputUnit,outputUnit,ports:[{id,direction,materialNodeId,materialKind,unit,ordinal}],conversionRule:{mode:"ACTUAL_WEIGHT"}}`。20 个分割端口 = 一个 PROCESS 节点 `ports` 里 20 个 OUTPUT。

## 5. 原料批次与生产链

- 🔴 `POST /api/mobile/{fid}/material-batches` 已墓碑（`MaterialBatchController.java:147-162` 直接 409「普通批次页面已关闭」）；`replenish`、批量同样。
- 可用路径：① 采购收货链（`PurchaseController` `/purchase/orders` → submit → approve → `receives` → confirm；实例 `seed-demo-chain.py:370-476`）；② **期初建账 `POST /material-batches/opening`**（L172-194，`OpeningInventoryRequest{items,remark,batchKey}`，幂等、无应付）——演示种子推荐；③ 其它 sourceDocType 无对外 REST。
- 生产链（`seed-demo-chain.py` 实测）：`POST /production-plans` → `POST /processing/batches` → `POST /processing/batches/{id}/start?supervisorId=` → `GET .../output-options` → `POST /production/batches/{id}/spawn-tasks` → `POST /production/batches/{id}/reports` → `POST /labels {batchId,type:"BATCH"}` → `GET /labels/scan/{labelCode}`。
- ⚠️ memory `project_workflow_settlement_is_web_sheet_only`（标已修复）：留意纯 API 报工能否结单。

## 6. 公开溯源二维码

- `TraceabilityServiceImpl.generateTraceCode` L646-663（EncodingRuleService 规则，退化 `TRACE-{batch}-{uuid8}`）；`POST /api/mobile/{fid}/labels {batchId,type:"BATCH"}` 返回 labelCode/traceCode。
- 公开：`GET /api/public/trace/{batchNumber}`（`TraceabilityController.java:195-220`）、`GET /api/public/trace/code/{traceCode}`（L229+）。

## 7. 现成脚本

- `scripts/e2e/liushanmen-demo/seed-demo-chain.py`：纯 API 链（SO→PO→收货→付款→计划→批次→报工→撤回→盘点→报损→研发→扫码），SSH 到服务器本地 curl（`ApiClient._ssh_curl` L137-165），登录写死 `f006_admin/123456`。**不含建厂。**
- `scripts/e2e/f006-role-chain/*.mjs`：Playwright RBAC 回归，不通用。
- `SupplyChainOrchestrator`：事件驱动服务端自动化，不是脚本。

## 最短种子路径建议

**方案 A 全 API（推荐）**：platform/factories → provisionDefaultUsers（走 onboarding 内部端点或等价）→ raw-material-types/product-types/work-processes → 工艺草稿+发布 → opening 批次 ×6 → 计划→批次→报工 → labels → public trace。坑：ID 非字面量；默认用户密码随机；opening 绕过供应商流程；RawMaterialType 需 admin 角色。

**方案 B SQL 直插 factories**：只在字面量 ID 是硬性要求时用，且只插这一行（要显式 `deleted_at=NULL`、补 scheduling rule、先查 name/id 撞号），其余全部回到 API；⛔ 不 SQL 插批次/工艺图（绕开 `MaterialBatchSourceDocContractTest` 等不变量与校验器）。
