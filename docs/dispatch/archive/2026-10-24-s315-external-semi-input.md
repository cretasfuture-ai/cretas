# ERP-S315-EXTERNAL-SEMI-INPUT-20261024
State: in-progress
Owner: /root/s315_external_semi_input (sole code writer); Root integration/release; original QA phone/browser/canonical.
Base SHA: 8b1dbc6a4e04936dc2b07c592ffc1610ae869aae
Worktree: C:/Users/Steve/cretas-s315-semi-input-20261024
Branch: codex/s315-semi-input-20261024

Verified defect: original QA normal F006 administrator workflow configuration source-cell action only offers raw-material SKU; no existing external SEMI_FINISHED input. UI and independent DB readback show workflow212/rev339 unchanged. Exact evidence: execution/s315-canvas-entry-original-qa-20261024; coordination/s301-plan-batch-readback-20261024/s315-entry-after-v1. ERP-S3-EXTERNAL-SEMI-INPUT-001.

Only writable product files:
- web-admin/src/views/system/product-processes/workflow/ProductProcessWorkflowEditor.vue
- web-admin/src/views/system/product-processes/workflow/WorkflowMaterialNode.vue
- web-admin/src/views/system/product-processes/workflow/__tests__/ProductProcessWorkflowEditor.externalInput.spec.ts
- web-admin/src/views/system/product-processes/workflow/__tests__/WorkflowMaterialNode.spec.ts

Need: normal source-input chooser can bind an existing same-factory active SEMI_FINISHED SKU with its real unit as an external source and INPUT port. Preserve RAW add/substitute behavior, output binding behavior, existing graph topology and backend strict SKU/unit guards. No fake intermediate identities, API workaround, source WIP relabeling, published-config rewrite, permission widening or acceptance claim.

If existing backend schema/publication contract rejects the intended external input, STOP dependent changes and return exact needed file expansion to Root; no backend edits under this scope. Same chain stays with one writer after review. No package/lockfile/shared workflow/API/permission/schema changes. No ACTIVE/archive edits by worker. No production/API/DB/browser/phone mutations, merge/push/deploy. No grandchildren.

Acceptance: affected rendered-component tests exercising RAW regression plus external SEMI input selection, persisted INPUT/SKU/unit, cancellation, unavailable/wrong-category item refusal and source-vs-output distinction; no source-text-only substitute. Target command from web-admin: npm exec -- vitest run src/views/system/product-processes/workflow/__tests__/ProductProcessWorkflowEditor.externalInput.spec.ts src/views/system/product-processes/workflow/__tests__/WorkflowMaterialNode.spec.ts --maxWorkers=1. Reuse existing fixtures/helpers. Worker only targets, Root freezes and performs needed complete checks/build after review. Fresh node_modules is isolated; no junctions, target sharing or lockfile changes. Check current CPU/memory before package install/tests; Windows scope max3CPU/6GB, prefer1worker/2GBheap; no second heavy Java job. Initial CPU99/free14.89GB at08:26 was busy; source edit can proceed, defer heavy validation until safe.

Receipt: task/base, actual files/diff, target command+exit/log, commit, unmet items, exact Owner release/safe point. Preserve existing assertions, original FAIL and untested scenarios. Local software QA only; full S315 requires subsequent original QA real route/report/source/target/DB verification after official release.

## Root interface decision 2026-10-24 08:35 +08
Supersedes earlier Java prohibition only for two exact additional files:
- backend/java/cretas-api/src/main/java/com/cretas/aims/service/validation/ProductProcessWorkflowCatalogValidator.java
- backend/java/cretas-api/src/test/java/com/cretas/aims/service/validation/ProductProcessWorkflowCatalogValidatorTest.java
Same sole worker owns all six product files, Base unchanged. Fresh origin/main scope checked without conflicts. Runtime ProcessSheetServiceImpl.resolveEdges/consumeClerkSemiStrict already uses factory-scoped SemiFinishedInventory; ClerkSheet projects materialKind/skuId/unit/allowedSkuIds. No runtime/DTO/Entity/Repository/permission expansion.
Contract: data.inputSource exactly EXTERNAL, kind exactly SEMI_FINISHED, no incoming producer or output binding, exactly one valid SEMI_FINISHED INPUT edge/port to actual process and matching materialNodeId/handle. Same-factory undeleted active SEMI_FINISHED SKU and actual unit mandatory. Unknown/absent marker must retain previous producer gate; spoofed marker on FINISHED/RAW/output or disconnected/ambiguous graph rejects. Existing unit validator must actually cover this source or return further scope request. Preserve all existing topology/output/BOM guards; no silent pass for malformed edges.
Worker may now implement both backend and frontend as one coherent slice. Target Java test: ProductProcessWorkflowCatalogValidatorTest (real methods, no source-string assertion); relevant existing ProductProcessWorkflowUnitValidator tests must run unchanged if applicable. Use isolated worktree target only after resource check, no competing heavy JVM. No full suite/build/push/production by worker. Tests for valid path, foreign/inactive/deleted/wrong category/unit/SKU and marker/producer/port/edge negatives plus RAW/output regression. Root reviews security/data consistency before final checks/release. Original QA remains sole UI/CSV owner.
Integration 2026-10-24: worker507b4eee62 released six source files to Root review. Rebased onto official7d4603b6; equivalent source commit396de8e401, registration9467f34116. Target catalog44/unit7/Vitest21 results reusable only identical source/test content; final risk review active. No business acceptance claimed.

## Root scope decision 2026-10-24 09:14 +08

Same sole Owner resumes original six files from candidate83d5c60beb, integration Base7d4603b6d3f7373967a1217e2a275d228239418a. Risk review found first-input chooser gap and count-unit runtime rejection. Correct these before final checks. Original QA workflow212/rev339 has RAW+packaging inputs: this slice supports **external semi-finished input added to a valid RAW-containing route**. Pure-external roots/header-only BOM are outside this delivery; preserve all existing RAW-root/nonempty-BOM/readiness/topology gates and honestly show the limitation. No claim that every rework route is supported. Actual S315 acceptance still requires independent source production, matching eligible SEMI inventory, mixed input consumption and resulting output/lineage.

Original six-file contract amendments: always show source chooser on first add; RAW first input must not require a substitute relationship. Explain external input requires an otherwise valid RAW-containing route. Offer only active same-factory SEMI units whose existing workflow reporting projection is kg; backend must independently reject count/unknown dimensions using authoritative SKU unit, never invent a mass conversion. Preserve count-unit fixture as a rejection control. Use existing unit-contract helpers. Keep explicit EXTERNAL/edge/port/no-producer/tenant/active/deleted guards; do not change finished-output BOM validation to accept empty recipes.

Additional writable files, tests only:
- backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/ProductProcessWorkflowServiceImplTest.java
- backend/java/cretas-api/src/test/java/com/cretas/aims/service/workflow/ProductProcessWorkflowRuntimeCompilerTest.java
- backend/java/cretas-api/src/test/java/com/cretas/aims/service/workflow/WorkflowClerkSheetServiceTest.java
- backend/java/cretas-api/src/test/java/com/cretas/aims/service/processentry/ProcessSheetConfiguredUnitsTest.java

Validate actual mixed RAW+EXTERNAL SEMI publish service, runtime materialKind/skuId/unit/allowedSkuIds projection and kg save path with existing real-method helpers, keeping tenant/SKU/unit/stock negatives. Frontend mounted test must exercise first-input chooser and real save request serialization, not only in-memory graph. Run affected original tests plus these four and existing ProcessSheetSourceSkuValidationTest/ProcessSheetSemiSourceStatusGateContractTest/InterimSettleServiceTest/WipClerkOutputTest if needed for runtime claim; unchanged prior results may be reused by exact content with actual evidence. No new universal scaffolding/full suite/build in worker. Same worktree target sequential only, no competing heavy JVM.

Shared-scope adjudication: current7d ACTIVE/openPR/worktree review found no other writer on these exact tests. Old WorkProcess duplicate-governance and BOM substitute scopes remain held; no governance, substitute relationship, generic root validator, BOM implementation, readiness or topology production file is released. No Entity/Repository/migration/runtime ProcessSheet/Clerk production modification. If existing runtime cannot handle this supported mixed case, return exact failing method and needed file before editing; never relax guards to make test pass. Root holds final review/integration/merge/release, originalQA retains browser/phone/canonical.


Final source integration: current Base7d4603b6, implementation0138f53bb1 and serialization/negative followupb111257426. Worker sole source scope released to Root review. Target Java205 then changedCatalog48/0/0/0, unchangedUnit7, final editor9 and node15, all exit0. Original failure logs retained. Original source paths frozen; Root remains integration Owner under ERP-S0S3-ACCEPTANCE-20261023. Not merged/deployed/QA accepted yet. No new features beyond 09:14 scope.
