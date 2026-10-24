# ERP-S3-INTEGRATED-RELEASE-20261024

State review/source-freeze; Root is sole integration writer, originalQA owns UI/devices/canonical.
Registered integration Base SHA: 4b6aa0334d50b123df2653e34e17a8c1e6129ead. Existing slice Base7d4603b6 remains historical.
Combine already reviewed S302 opening/cancel, S315 external semi input, S309 identity, S301 camera/network slices; no S2/S6 product files included.

Frozen allowed paths:
- backend/java/cretas-api/src/main/java/com/cretas/aims/controller/inventory/SalesController.java
- backend/java/cretas-api/src/main/java/com/cretas/aims/controller/StocktakeController.java
- backend/java/cretas-api/src/main/java/com/cretas/aims/dto/factory/StocktakeCancelRequest.java
- backend/java/cretas-api/src/main/java/com/cretas/aims/dto/factory/StocktakeDTO.java
- backend/java/cretas-api/src/main/java/com/cretas/aims/entity/factory/FactoryStocktake.java
- backend/java/cretas-api/src/main/java/com/cretas/aims/repository/factory/FactoryStocktakeRepository.java
- backend/java/cretas-api/src/main/java/com/cretas/aims/service/factory/FactoryStocktakeService.java
- backend/java/cretas-api/src/main/java/com/cretas/aims/service/factory/impl/FactoryStocktakeServiceImpl.java
- backend/java/cretas-api/src/main/java/com/cretas/aims/service/factory/impl/StocktakeBulkImportServiceImpl.java
- backend/java/cretas-api/src/main/java/com/cretas/aims/service/validation/ProductProcessWorkflowCatalogValidator.java
- backend/java/cretas-api/src/main/resources/db/flyway/V20261103_77_2__stocktake_cancellation_audit.sql
- backend/java/cretas-api/src/test/java/com/cretas/aims/controller/inventory/SalesFinishedGoodsPriceContractTest.java
- backend/java/cretas-api/src/test/java/com/cretas/aims/controller/StocktakeCancellationContractTest.java
- backend/java/cretas-api/src/test/java/com/cretas/aims/integration/StocktakeDriftRecoveryPostgresContractTest.java
- backend/java/cretas-api/src/test/java/com/cretas/aims/repository/FactoryStocktakeAndDeliveryRepositoryQueryValidationTest.java
- backend/java/cretas-api/src/test/java/com/cretas/aims/service/factory/impl/FactoryStocktakeServiceImplTest.java
- backend/java/cretas-api/src/test/java/com/cretas/aims/service/factory/impl/StocktakeBulkImportServiceImplTest.java
- backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/ProductProcessWorkflowServiceImplTest.java
- backend/java/cretas-api/src/test/java/com/cretas/aims/service/processentry/ProcessSheetConfiguredUnitsTest.java
- backend/java/cretas-api/src/test/java/com/cretas/aims/service/validation/ProductProcessWorkflowCatalogValidatorTest.java
- backend/java/cretas-api/src/test/java/com/cretas/aims/service/workflow/ProductProcessWorkflowRuntimeCompilerTest.java
- backend/java/cretas-api/src/test/java/com/cretas/aims/service/workflow/WorkflowClerkSheetServiceTest.java
- docs/dispatch/ACTIVE.md
- docs/dispatch/archive/2026-10-24-s301-camera-denied-manual.md
- docs/dispatch/archive/2026-10-24-s302-opening-scope.md
- docs/dispatch/archive/2026-10-24-s309-ui-identity.md
- docs/dispatch/archive/2026-10-24-s315-external-semi-input.md
- frontend/CretasFoodTrace/src/__tests__/unit/qualityInspectorBatchLookupScreen.test.tsx
- frontend/CretasFoodTrace/src/__tests__/unit/qualityInspectorDynamicStandard.test.tsx
- frontend/CretasFoodTrace/src/__tests__/unit/screens/qualityInspectionSubmissionLogic.test.ts
- frontend/CretasFoodTrace/src/screens/quality-inspector/QIFormScreen.tsx
- frontend/CretasFoodTrace/src/screens/quality-inspector/QIScanScreen.tsx
- frontend/CretasFoodTrace/src/screens/quality-inspector/qualityInspectionSubmission.logic.ts
- web-admin/src/views/system/product-processes/workflow/__tests__/ProductProcessWorkflowEditor.externalInput.spec.ts
- web-admin/src/views/system/product-processes/workflow/__tests__/WorkflowMaterialNode.spec.ts
- web-admin/src/views/system/product-processes/workflow/ProductProcessWorkflowEditor.vue
- web-admin/src/views/system/product-processes/workflow/WorkflowMaterialNode.vue
- web-admin/src/views/warehouse/shipments/__tests__/customerRoster.mount.spec.ts
- web-admin/src/views/warehouse/shipments/list.vue
- web-admin/src/views/warehouse/stocktakes/__tests__/stocktakeCancel.mount.spec.ts
- web-admin/src/views/warehouse/stocktakes/index.vue

Acceptance: unchanged target receipts reused. Final20-selector official build includes actual PostgreSQL opt-in, Repository startup, Flyway uniqueness. Final java-full-ready PR gate plus PostgreSQL/Web/RN/security/source gates; no skipped PostgreSQL accepted. Manual merge only after applicable checks. Clean exact origin/main official backend-first publication, structured exit0/one final status receipt, then originalQA cancellation/reimport with independent readback.
Rollback: before CANCELLED writes additive columns permit old binary; after first CANCELLED old enum binary is unsafe. Use compatible roll-forward; never alter audit/history to roll back.
No deployment or business pass claimed by this archive. Child source scopes release with merged final candidate; Root continues acceptance under ERP-S0S3-ACCEPTANCE-20261023.
