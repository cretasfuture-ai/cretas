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
