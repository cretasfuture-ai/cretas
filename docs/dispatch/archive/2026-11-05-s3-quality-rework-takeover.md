# TASKS-S3-QUALITY-REWORK-20261105

Owner: 01a1a954/root. Base SHA: 91bfa2c9b42ac5e98ca67b02e0de6686339b5913.

允许整合来源：PR1441 head0b95eaec72951c9dc0018309223855efb62625e4 与 PR1446 head7a408120571ba82a96d24002ce438a562a79b78c。原WT/feature分支只读，不清理dirty。独立WT/branch cretas-quality-rework-20261105 / codex/tasks-quality-rework-integration-20261105。

验收：目标契约/权限/库存/结单测试；真实JPA与PG gate；Web/RN相关测试/typecheck；高风险独立复核；正式build/stage、当前head必需CI、PR/main部署及受影响F006 UI。原测试/变异仅绑定原版本，不当当前全部通过。软件执行Owner不代签真实业务代表/QA。

范围排除：其他组SOP/参数偏离/物料替换、附件/全局inventory迁移、发布脚本/AI配置、他人业务数据与设备。共享router/menu只保留本组入口，整合最新main时保留他组原Owner变化。

允许文件（两冻结PR原diff并集，对应修复限同scope）：

新增迁移与真实库存测试scope：`backend/java/cretas-api/src/test/java/com/cretas/aims/integration/QualityDispositionInventoryPostgresContractTest.java`（唯一写Owner root/qc_pg；Base91bfa2c9；parent冻结后统一Maven/PG串行运行）及 `ProductionSettlementManualOverrideMigrationPostgresContractTest.java`（root）。未发布结单迁移由旧V20261104_61顺延V20261105_93，避开main91与方案组预留92；发布需先合/应用较小版本并最终重检。

补充对应测试scope：`backend/java/cretas-api/src/test/java/com/cretas/aims/service/wip/WipOutputCorrectionPostgresContractTest.java`。原PR1446新增mandatory持久化注入导致此既有JDBC-only PG fixture启动失败；仅补测试注入依赖，禁止删生产refresh或把该fixture称为Hibernate验证。真实缓存/锁并发由SecondaryHoldPostgresContractTest执行。
- `.github/workflows/java-gate-pr.yml`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/quality/QualityCheckUpdateTool.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/quality/QualityDispositionExecuteTool.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/quality/QualityResultSubmitTool.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/annotation/RequireModule.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/config/ModuleEnabledInterceptor.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/DisposalController.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/factory/FactoryMaterialRequisitionController.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/ProductionPlanController.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/QualityDispositionController.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/sales/SalesDeliveryBatchAllocationController.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/SemiFinishedInventoryController.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/production/ProductionSettlementRequest.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/production/ProductionSettlementResponse.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/sales/BatchAllocationDTO.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/yield/OrderCostBreakdownDTO.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/enums/QualityStatus.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/ProductionSettlement.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/repository/ProductionBatchRepository.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/repository/SemiFinishedInventoryRepository.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/ProcessingServiceImpl.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/ProductionPlanServiceImpl.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/QualityDispositionRuleServiceImpl.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/QualityInspectionServiceImpl.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/UserModuleAccessServiceImpl.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/inventory/FgQuantityUnitConverter.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/lineage/SemiSourceBatchResolver.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/orchestration/SupplyChainOrchestrator.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/processentry/impl/ClerkProcessEntryServiceImpl.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/processentry/impl/ProcessSheetServiceImpl.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/production/SecondaryPlanFeedService.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/production/SettlementSemiStockingService.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/quality/FinishedGoodsQuarantine.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/quality/QualityDispositionInventoryService.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/quality/release/QcInspectionPolicy.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/QualityDispositionRuleService.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/reversal/impl/ReportReversalServiceImpl.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/sales/impl/SalesDeliveryBatchAllocationServiceImpl.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/sales/SalesDeliveryBatchAllocationService.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/UserModuleAccessService.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/wip/impl/WipInventoryServiceImpl.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/wip/WipInventoryService.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/workprocess/WorkflowStepUnlockGuard.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/yield/impl/InterimSettleServiceImpl.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/yield/impl/PendingInterimFeedServiceImpl.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/yield/impl/YieldReportServiceImpl.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/yield/OrderCostBreakdownService.java`
- `backend/java/cretas-api/src/main/resources/db/flyway/V20261105_93__production_settlement_manual_override_and_semi_stocking.sql`
- `backend/java/cretas-api/src/main/resources/platform/factory-menu-paths.json`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/controller/DisposalFinanceApprovalAccessContractTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/controller/factory/FactoryMaterialRequisitionWarehousePickingContractTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/controller/sales/BatchAllocationRulesContractTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/gate/SecondaryFeedEntryWiringContractTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/integration/SecondaryHoldPostgresContractTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/repository/QcReleaseRepositoryQueryValidationTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/ProcessingServiceImplDynamicQualityStandardContractTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/ProductionPlanServiceSecondaryTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/production/ProductionPlanCancelConcurrencyTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/production/ProductionPlanSettlementTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/production/ProductionPlanStartValidationTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/production/ProductionSettlementConfirmContractTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/production/ProductionStartEntryPointsConvergeContractTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/production/SecondaryPlanStartAndSettleContractTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/production/SecondaryReworkFeedContractTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/production/SettlementLineageWiringTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/production/WorkshopReportReservationSettlementContractTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/quality/QualityBatchDispositionContractTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/quality/QualityDispositionInventoryServiceTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/wip/impl/WipInventoryServiceImplTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/wip/WipBalanceAdjustmentFormulaTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/wip/WipCrossCustomerDrawTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/wip/WipInventoryServiceSecondaryTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/workprocess/WorkflowStepUnlockGuardContractTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/yield/InterimSettleServiceTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/yield/SettlementSemiTransferCostContractTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/yield/YieldReportServiceImplTest.java`
- `frontend/CretasFoodTrace/src/__tests__/gates/deliveryConfirmPreflightContract.test.ts`
- `frontend/CretasFoodTrace/src/__tests__/unit/dynamicQualityFormLogic.test.ts`
- `frontend/CretasFoodTrace/src/__tests__/unit/qiResultDispositionNotice.test.tsx`
- `frontend/CretasFoodTrace/src/__tests__/unit/qualityInspectorDynamicStandard.test.tsx`
- `frontend/CretasFoodTrace/src/__tests__/unit/screens/warehouseBatchAllocation.test.ts`
- `frontend/CretasFoodTrace/src/screens/quality-inspector/dynamicQualityForm.logic.ts`
- `frontend/CretasFoodTrace/src/screens/quality-inspector/QIFormScreen.tsx`
- `frontend/CretasFoodTrace/src/screens/quality-inspector/QIResultScreen.tsx`
- `frontend/CretasFoodTrace/src/screens/warehouse/outbound/WHDeliveryConfirmScreen.tsx`
- `frontend/CretasFoodTrace/src/services/api/salesDeliveryBatchAllocationApiClient.ts`
- `frontend/CretasFoodTrace/src/types/qualityInspector.ts`
- `tests/e2e-yield-mixed-sku/headed-matrix-fullchain.mjs`
- `tests/e2e-yield-mixed-sku/headed-settlement-edge.mjs`
- `tests/e2e-yield-mixed-sku/nonprod-business-flow-audit.mjs`
- `web-admin/src/components/layout/__tests__/financeDisposalApprovalEntry.spec.ts`
- `web-admin/src/components/layout/__tests__/menuConfig.spec.ts`
- `web-admin/src/components/layout/menuConfig.ts`
- `web-admin/src/router/index.ts`
- `web-admin/src/views/factory/material-requisitions/__tests__/warehousePickingButtons.source.spec.ts`
- `web-admin/src/views/factory/material-requisitions/list.vue`
- `web-admin/src/views/finance/voucher/__tests__/VoucherDetail.integration.spec.ts`
- `web-admin/src/views/finance/voucher/VoucherDetail.vue`
- `web-admin/src/views/production/plans/__tests__/externalWipSettlement.source.spec.ts`
- `web-admin/src/views/production/plans/list.vue`
- `web-admin/src/views/quality/disposals/list.approvalButtons.integration.test.ts`
- `web-admin/src/views/quality/disposals/list.vue`
- `web-admin/src/views/sales/__tests__/packagingConversion.spec.ts`
- `web-admin/src/views/sales/orders/__tests__/packagingMismatchConfirm.source.spec.ts`
- `web-admin/src/views/sales/orders/detail.vue`
- `web-admin/src/views/sales/packagingConversion.ts`
- `web-admin/src/views/warehouse/shipments/__tests__/packagingConfirm.integration.test.ts`
- `web-admin/src/views/warehouse/shipments/list.vue`

## 整合修复检查点（尚未验证通过）

原1446五处冲突已解，PG selector65+64取并集66再新增两项真实PG为68，无删除。保留alsoAllowRoles与alsoAllowModules两种端点例外，原工厂主开关、显式DENY及独立权限层不变。

发现真实P1：clerk独立幂等键可同计划并发双占来源。takeHold现在先锁计划并refresh，严格factory/deleted后从锁后真值判来源，再查ledger/扣WIP；新增真实锁等待、3kg充足余额和恰一次占用的确定性PG竞态，不用余额不足造绿。独立源码复核通过，但运行证据仍待补。

原PG启动修复首跑：JDBC WipOutput5通过，SecondaryHold8仍缺最新main财务required bean，新增两项测试依赖后待复验；未删生产refresh。质量原API断言适配1469新窄API且保留旧API阴性，3项重测通过。Web九套首轮55通过/1旧API断言失败原件保留，其余同内容证据复用；新报废菜单正式catalog补齐。RN五套43项通过，typecheck和原生UI待做。

子任务TASKS-S3-QC-PG-20261105仅新增五场景真实库存测试文件，静态diff检查通过、未运行Maven或连接数据库，未commit；root已收回唯一写Owner并统一纳入待运行36类选择器。原未发布迁移61已顺延93，增加实际Flyway/PG/SpringJPA旧行/新行两场景，明确仅结单表投影验证，不冒称全应用迁移回放。
# 最终全量新增的 H2 仪器修复范围

538 最终全量运行 19643 项，58 error + 1 fail，集中于 8 类 H2 集成 fixture；公共原因是新增的真实计划 refresh 触发 JsonBinaryType 在 H2 中的空 Map/List 双编码。真实 PG 同 head 门禁成功。保留生产锁/refresh 和全部原断言，仅把这些单产品 fixture 未使用的四个默认 JSON 快照置 null；另以真实 PG 验证非空快照刷新不变。范围为 CcpMonitoringContractTest、ProductionPotContractTest、ClerkProcessEntryIntegrationTest、ProcessSheetInventoryTest、ProcessSheetRowsTest、ProcessSheetServiceImplTest、QcReleaseWebDrawContractTest、InterimSettleRawDeductionIntegrationTest，以及 SecondaryHoldPostgresContractTest 新增用例。Root 为此 H2 修复唯一写者；不改食品组新增参数用例或其 worktree，合并时保留其冻结增量。

修后 9 类目标实测 91 tests / 0 failures / 0 errors / 0 skipped（含真实 PG 10、H2 81），TARGET_EXIT=0；临时 PG15442 已停止。原 59 红日志/棘轮/归一化清单保留，不扩基线、不跳原测试。独立复核确认 null 只用于本批未使用快照；真实 PG 非空 Map/List 刷新及无扣账断言已执行成功。最终整合候选需新 CI 和扩大至 44 类的官方制品门禁。

## 2026-11-05 最终交付与 scope 释放

代码状态 merged：#1480 已于 14:55:21Z 正常 squash 合入 c24e11bb0a2c8e2714981d51b86849bd6fa2a8de，最终 feature 92ef535b11599c633883fdfd812c95264e66f886 与合并整树一致。本代码任务归档并释放代码 scope；后续发布、OTA 和 F006 QA 由父任务继续负责，不把合并算部署或业务验收。

最终整合已含 #1481，保留双方 PG 选择器和工序表测试。官方 52 类/56 XML 实跑 793 tests，0 failures/errors/skipped；Web 7 套 74 项与类型检查通过，RN 相同内容 43 项及类型检查证据复用。run42040325350 最终 full 19664/0 failures/0 errors/311 skipped，真实 PG 同 head success，其余门禁 success；fast 按最终候选配置 skipped。实际 whole tree a5c37558da0467222da12c52230b8b8805aec4fd 与本地相同。原失败和修复历史保留，不扩基线。

Java tree 259b316ad02b3cc44214bf9f86d8980cfa552455，JAR SHA256 6f662eb2eccf0c1e9d7370b9527ea4e3124e26b166a32f239aad1fe60c563812；Web tree48bae0e0f442e70c2bc034d9fd303af3e9f93c97。官方 build/stage exit0/FINAL=built 一次，尚不代表正式部署。V20261105_93 未发布，线上只读最新91；92已由原Owner放弃，禁止 out-of-order，正式发布前再核。独立模拟器验收明确标注模拟器，实体不再使用；S6-10实际业务代表/QA签署不能代签。
