# ERP-S305-CORRECTION-BOUNDARY-20261023

- Status: claimed. Base SHA: e754b5e37006d13d0046a9f50f0e23df0b86344d.
- Unique code Owner: /root/s305_correction_boundary. Root keeps integration, diff review, merge/release responsibility; original QA exclusively owns phone/browser/canonical CSV.
- Worktree: C:/Users/Steve/cretas-s305-report-correction-20261023; branch codex/s305-report-correction-boundary-20261023.
- Actual original QA has now opened OUTPUT 23989 and INPUT 23988/23990 correction dialogs at /production/approval using actual1552/F006. No type/unit is displayed; INPUT presents editable output. Evidence: private execution/s305-original-qa-readonly-20261023. Never submit these unsafe corrections before fix.

## Exact write scope

1. backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/ProductionReportCorrectionServiceImpl.java
2. backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/ProductionReportCorrectionServiceContractTest.java
3. backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/ProductionReportCorrectionFlowContractTest.java
4. web-admin/src/api/processProduction.ts
5. web-admin/src/views/production/approval/list.vue
6. web-admin/src/views/production/approval/__tests__/reportCorrectionFlow.spec.ts
7. web-admin/src/views/production/approval/__tests__/reportCorrectionKinds.spec.ts (new mounted test)
8. web-admin/src/views/workflow/pending.vue
9. web-admin/src/views/workflow/__tests__/reportCorrectionUnits.spec.ts (new mounted test)

Read existing helpers and legacy report-generation semantics before choosing a null-kind compatibility rule. Known INPUT/SEGMENT must never be corrected as output at submit or approval. OUTPUT and provable legacy integrated report compatibility must retain lawful behavior; UNKNOWN must not be guessed as output. Preserve existing assertions; make old fixture identity explicit only when it accurately models the original positive business branch, and add unknown/legacy negatives. Report/task aggregation must not include INPUT output or mix units. Expose type and trusted output unit in report rows, correction dialog and OA summary; missing unit must display unknown, never inferred scale. No inventory/WIP/physical count compensation is authorized by this scope.

## Acceptance commands and resources

- Java: from backend/java/cretas-api, mvn test -Dtest=ProductionReportCorrectionServiceContractTest,ProductionReportCorrectionFlowContractTest,ProductionReportCorrectionSecurityContractTest,ProductionReportCorrectionPermissionContractTest,WorkflowInstanceControllerTest,DecisionTypeMetadataRegistryTest,GateSelectorCoverageContractTest (use existing project wrapper/toolchain if actual available).
- Web: from web-admin, npm test -- --run src/views/production/approval/__tests__/reportCorrectionFlow.spec.ts src/views/production/approval/__tests__/reportCorrectionKinds.spec.ts src/views/production/approval-hub/__tests__/approvalHub.mount.spec.ts src/views/workflow/__tests__/reportCorrectionUnits.spec.ts src/views/workflow/__tests__/pendingRouteSync.spec.ts.
- Development: affected target tests only. Full/typecheck/build belongs to Root after final freeze; do not separately repeat full builds.
- Independent worktree/target/node_modules; no shared target, DB, ports or generated artifacts. At most 2 CPU and 2GiB per JVM, check available memory >=8GiB before Maven; use existing installed dependencies only if paths resolve to this WT, no foreign modifications.
- No Entity/Repository/JPQL/Security/controller/migration/permission/ACTIVE/canonical edits; no shared Orchestrator/Gateway/Skill/CI/release edits; no prod writes; no browser/phone; no grandchild agents. Return EXPANSION_REQUEST for necessary scope additions.
- Deliver exact task/Base/files/commit, target commands/exits/logs, original failure and positive/negative evidence, compatibility decision, untested items and SAFE_CODE_OWNER_RELEASED. Do not call source/test success S3-05 business acceptance.

## Root integration readback, 2026-10-23 00:35Z

Original implementation Owner released at 1c6944e8fa. Root is now the sole source Owner. Frozen HEAD 7a25c1f79e67b9309c63a6af23a16412295a2354 contains exactly the registered nine files; final mounted positive enters the actual OUTPUT row dialog and verifies stage, kg and quantity, with an INPUT disabled negative. Root broader nine Java classes85/0 failures/errors/skips, five Web files18 PASS, direct vue-tsc -b --force exit0; narrow independent risk review PASS. No production deployment or business acceptance is claimed. Original15d409-ratchet FAIL and original QA UI FAIL remain retained in private evidence. Plan-lock proofs are mock/static, not new real PostgreSQL concurrency evidence; downstream WIP/quantity compensation remains outside this source slice. PR/release and actual original QA remain pending.

## Combined S305/S311 final candidate — Root scope decision, 2026-10-23

The S305 nine-file slice at7a25c1f79e67b9309c63a6af23a16412295a2354 is independently target-validated and released by its implementation Owner. Root already owns S311 integration and now owns both source slices. Permit Root to merge these unchanged nine S305 files into the existing S311 worktree/PR1117 (Basee754), alongside its previously registered exact quality files and Root RN workflow configuration. There is no overlapping source write Owner and no expansion into Repository/Entity/Security/migrations/Orchestrator/Gateway/procurement. Preserve the disjoint main PR1113 content and S308 candidate.

Reason: S311's final Java full has deliberately not executed; combine the two frozen source slices before that final run, saving a second approximately66-minute full and a repeated backend/Web release. This must not change or wait-block the frozen S308 candidate. Build the new trusted combined backend/Web only after the S308 official release lock is free, while its original QA runs on the pinned S308 version. Before final1117push, merge the actual S308 main ancestry, resolve only known documentation overlap, verify exact component trees and no unintended product diff. Run one final full plus applicable real PostgreSQL/JPA gates, preserving all existing assertions/skips/failures. The old held b884 run is superseded, never a pass or a reason to rerun obsolete source.

Acceptance remains separate: originalQA S3-11 quality source/assignee, and S3-05 lawful correction/independentapproval/no-unapproved-or-duplicate-effects/downstream readback. S305 stock/WIP compensation is not invented; quantity-unchanged notes correction is only a proposed controlled probe, subject to original QA's unchanged criterion. No full S3 signoff from source or deployment.
