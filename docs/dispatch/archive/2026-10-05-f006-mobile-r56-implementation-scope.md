# F006 R56 permission repository availability

Task: `F006-MOBILE-R56-20261005`; Base: `b0e5aa97b287ebc1976aabda4da83541b53f5d9c`; source Owner: permission executor; coordinator/docs Owner: root.

Actual offline RED: configured L1/L2 denies grant on read failure and persist via cache after recovery. Separate from prior strict Gateway repair; no online leakage proved.

Allowed source scope (16 files):

- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/PermissionServiceImpl.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/PermissionService.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/PermissionRepositoryAvailabilityContractTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/PermissionServiceImplResolveModuleLevelsContractTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/PermissionServiceImplResolveModuleLevelsQueryBudgetContractTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/PermissionServiceImplWarehouseWorkerReceiveTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/PermissionServiceImplQualityInspectorTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/PermissionServiceImplProductionManagerClerkSplitTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/RestaurantRolesPermissionTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/mobile/impl/MobileAuthServiceImplLoginModulePermissionsContractTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/security/PriceViewPermissionTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/security/FinanceRbacBugFixTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/controller/MyWageControllerSelfScopeTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/controller/ListSummaryEntityPermissionContractTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/gateway/ToolPrincipalModulePermissionsContractTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/testutil/PermissionRepositoryFixtures.java`

Acceptance: actual MockMvc + PermissionInterceptor + GlobalExceptionHandler + PermissionServiceImpl; outage HTTP503 with no protected execution/no erroneous cache; recovery without invalidation retries authoritative deny; successful empty rows preserve fallback, healthy cache and invalidation/tenant isolation, bulk/login unavailable propagation, W9 deny, boss/role/price positive and negative controls. New tests precede production edits and yield actual assertion RED, then GREEN; meaningful wrong-behavior mutation RED then restored GREEN. Missing fixture injection only, retain all unrelated assertions.

Target selector: `PermissionRepositoryAvailabilityContractTest,PermissionServiceImplResolveModuleLevelsContractTest,PermissionServiceImplResolveModuleLevelsQueryBudgetContractTest,PermissionServiceImplWarehouseWorkerReceiveTest,PermissionServiceImplQualityInspectorTest,PermissionServiceImplProductionManagerClerkSplitTest,RestaurantRolesPermissionTest,MobileAuthServiceImplLoginModulePermissionsContractTest,PriceViewPermissionTest,FinanceRbacBugFixTest,MyWageControllerSelfScopeTest,ListSummaryEntityPermissionContractTest,ToolPrincipalModulePermissionsContractTest`. Maven sequential in this WT, Java21; no skip-tests/package/deploy/DB/device/provider. No query/entity/migration changes. Additional file needs returned expansion request. Root reviews and integrates/releases after gates.
Root risk review follow-up: worker selector174/0 is an earlier milestone, not evidence for added tests. Within the same registered16-file scope, root added L2 HTTP503 then recovered authoritative403 with zero protected execution/zero L1 reads; successful-empty HTTP200 control; actual cache hit/5-minute TTL expiration/invalidate and same-user factory-key controls. Actual MobileAuthServiceImpl.unifiedLogin now has real PermissionServiceImpl L1/L2 outage503 tests and ordinary administrator wildcard login controls. Ordinary administrator snapshots enumerate healthy L1/L2 names using soft availability; strict tool authorization still requires the fresh available snapshot, preserving R55. Existing future_axis positive must remain. No grants, Repository, Entity, JWT or production data changes. Root follow-up target evidence is private r56-root-followup-test-report.json; final combined evidence still required.

Frozen final candidate review and remaining gates: see `2026-10-05-f006-mobile-r54-r56-r58-acceptance.md`. Earlier isolated receipts remain historical; final source/selector SHA receipts supersede them only after the matching final gate passes. Root sole final source Owner; executor scope released.
