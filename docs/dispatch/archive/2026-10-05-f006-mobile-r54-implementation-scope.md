# F006 R54 inventory sibling scope and authorized production selection

Task: `F006-MOBILE-R54-20261005`; Base: `b0e5aa97b287ebc1976aabda4da83541b53f5d9c`; source Owner: inventory executor; coordinator/docs Owner: root.

Worker WT: `C:\Users\Steve\cretas-f006-mobile-r54-20261005`; integration/docs WT: `C:\Users\Steve\cretas-f006-mobile-r56-20261005`.

User decision: same factory and valid current BOM matching batches may be selected across receiving users for production. This is not a change to ordinary inventory row scope or a permission grant.

Selection authorization boundary: status route admits existing production read/read_write OR inventory/warehouse read at its first gate, then the actual service validates the requested branch using the canonical current user and PermissionService. Production-capable actors may use the valid current BOM selection exception; warehouse-only actors must not gain cross-receiver scope merely by supplying productTypeId. Production-only actors with no inventory permission cannot use a generic no-BOM status request. Other WIP/expiry routes retain inventory/warehouse gates. Include production-capable SELF selection success, production-only generic denial and warehouse-only forged-selection denial controls; no new role list or grant. Unresolved authenticated actor/factory/scope cannot silently mean ALL. Any confirmed legitimate no-user background caller requires a separately reviewed decision.

Allowed source scope (8 files):

- `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/MaterialBatchController.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/MaterialBatchServiceImpl.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/repository/MaterialBatchRepository.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/controller/MaterialBatchInventoryAuxPermissionContractTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/MaterialBatchInventorySiblingDataScopeContractTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/MaterialBatchSelectionScopeContractTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/repository/MaterialBatchInventoryScopedRepositoryQueryValidationTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/testutil/MaterialBatchInventoryScopeFixture.java`

Acceptance: real service, Aspect, resolver, immutable mixed F006/F007 rows and DTO mapper; fresh HTTP permission denials prevent reads, existing role/price controls retained; status/WIP/expiring/expired and alert consumers obey creator scope; all overloads and ordinary no-BOM selector covered. Valid same-factory ACTIVE/current BOM selection cross-receiver positive; missing/nonmatching/inactive/foreign BOM negatives. Unsupported CUSTOM does not fall through ALL. Bounded scoped repository predicates before DTO mapping; any new repository declaration requires actual Hibernate JPA Context startup and executed query predicates in isolated test DB, no production DB. Actual pre-fix assertion RED then GREEN, meaningful mutation RED then restored GREEN.

Target selector: `MaterialBatchInventoryAuxPermissionContractTest,MaterialBatchInventorySiblingDataScopeContractTest,MaterialBatchSelectionScopeContractTest,MaterialBatchInventoryScopedRepositoryQueryValidationTest,MaterialBatchControllerGetMaterialBatchListPermissionTest,MaterialBatchLowStockDataScopeContractTest`. Maven sequential in worker WT, separate target from R56; root combines final reviewed changes before one trusted final build/CI/release. No other source/Entity/migration/grants/device/provider/network business write allowed. Return expansion request for additional file or background caller semantics.

Frozen final candidate review and remaining gates: see `2026-10-05-f006-mobile-r54-r56-r58-acceptance.md`. Earlier isolated receipts remain historical; final source/selector SHA receipts supersede them only after the matching final gate passes. Root sole final source Owner; executor scope released.
