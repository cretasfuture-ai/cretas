# Explicit deferred purchase authorization

- Task `ERP-S1-DEFERRED-GRANT-20261020`; parent `ERP-STAGES-20261019`.
- Coordinator and sole ledger Owner `01a15272-374d-72c0-bb20-cdfa0bd823f1`; implementation Owner `/root/supplier_dispatch` (rework after the first worker returned SAFE).
- Base `1b9f712ecf275fe7f784e58174f90e8e1b9c68bf`; isolated worktree `C:/Users/Steve/cretas-s1-deferred-grant-20261020`, branch `codex/s1-deferred-grant-20261020`.
- This is a subsequent compatible S1-07 slice, not a claim that the original scene has passed. Keep the accepted default atomic preview-confirm-create path unchanged.

Exact implementation/test scope (no other repository files):

- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/intent/ControlledPurchaseTaskExecutionService.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/AiPurchaseTaskGrantController.java`
- New `backend/java/cretas-api/src/test/java/com/cretas/aims/service/intent/DeferredPurchaseGrantContractTest.java`
- New `backend/java/cretas-api/src/test/java/com/cretas/aims/repository/ai/DeferredPurchaseGrantRepositoryQueryValidationTest.java`
- `web-admin/src/api/controlledPurchase.ts`
- `web-admin/src/views/procurement/ai-purchase/index.vue`
- New `web-admin/src/views/procurement/ai-purchase/__tests__/DeferredPurchaseGrant.spec.ts`

Implement a separate explicit authorize-for-later operation and pending execute action, never an extra required step in the original one-shot operation. Reuse the real persisted proof claim service and current authenticated actor/permission/tenant/reference checks; request DTOs cannot manufacture trusted identity or grant scope. Grant expiry cannot exceed the original persisted preview expiry or existing TTL. Issuance and preview resolution must be one transaction, no order write. Pending execution must lock and bind the confirmed preview, grant, descriptor, approved snapshot, current permissions and references, expected version, canonical idempotency key and terminal replay. Do not interpret UNKNOWN as retry permission. Existing revoke/status must expose real pending, expired and revoked grants. No new Entity, Repository query, migration, production fixture, role/config mutation, shared permission engine or orchestrator/dispatch edits.

Tool/Skill/scheduled entries are not delivered by a controller wrapper. Any such missing runtime entry remains unavailable and must be named honestly; a later exact interface scope can reuse the same guard without fabricating a human SecurityContext or inventing system delegation.

Target validation: coordinator-only serial Aurora Maven `-Dtest=DeferredPurchaseGrantContractTest,DeferredPurchaseGrantRepositoryQueryValidationTest,ControlledPurchaseTaskExecutionContractTest,ControlledPurchaseScopeBoundaryContractTest,AiPurchaseTaskGrantServiceContractTest,AiPurchaseTaskGrantRepositoryQueryValidationTest` with actual PostgreSQL 13/17 and transaction/no-order count assertions. Native Vitest target `npx vitest run src/views/procurement/ai-purchase/__tests__/DeferredPurchaseGrant.spec.ts`. Worker must not run Maven/Full/CI/production/browser/SQL/commit/PR/ledger edits. Real original QA later uses the existing authorized F006 guarded path; original expiry and incomplete coverage evidence stays intact.

Coordinator and independent review rejected the first worker implementation before tests: source-only test assertions, an undefined test identifier, missing commit-time admission, UNKNOWN retry/pending reference overwrite and non-strict DTOs. No test/merge/deploy claim is accepted. Rework remains within the same seven files; no source-text tests may stand in for real methods/JPA. Existing one-shot source and tests remain intact. Exact helper extension must be registered before any additional file edits.
