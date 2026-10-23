# Scheduling readiness and prediction provenance

- Task `ERP-S1-SCHEDULING-TRUTH-20261020`, Base `1b9f712ecf275fe7f784e58174f90e8e1b9c68bf`.
- Implementation Owner `/root/scheduling_truth`; coordinator `01a15272-374d-72c0-bb20-cdfa0bd823f1` alone writes ACTIVE/archive.
- Root-reviewed inputs: private `execution/coordination/s1-nextwave-reviewed-findings.md`. No repeated scouting, product PASS or physical confirmation is inferred.

Exact allowed product/test scope:

- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/scheduling/impl/SchedulingAIServiceImpl.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/scheduling/CompletionProbabilityResponse.java`
- New `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/SchedulingDiagnosticsController.java`
- New `backend/java/cretas-api/src/main/java/com/cretas/aims/service/scheduling/SchedulingReadinessService.java`
- New `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/scheduling/SchedulingReadinessResponse.java`
- New `backend/java/cretas-api/src/test/java/com/cretas/aims/service/scheduling/impl/SchedulingPredictionProvenanceContractTest.java`
- New `backend/java/cretas-api/src/test/java/com/cretas/aims/service/scheduling/impl/SchedulingReadinessContractTest.java`
- New `web-admin/src/api/schedulingDiagnosticsApi.ts`
- New `web-admin/src/views/scheduling/plans/SchedulingDiagnosticsPanel.vue`
- `web-admin/src/views/scheduling/plans/create.vue`
- New `web-admin/src/views/scheduling/plans/__tests__/SchedulingDiagnosticsPanel.spec.ts`

Implement non-mutating GET readiness/diagnostics with actual scoped resource selection, missing-input reasons and explicit source; stored line/capacity is not physical confirmation and cannot become a default selected resource. Preserve unavailable generate/no-interactions tests unchanged. Distinguish actual HTTP-adapter missing route, model/dependency failure, invalid response and real deterministic infeasible time windows. Preserve local probability as local estimation, never APS optimization. Diagnostics must not persist predictions/plans or other business rows. Production dependencies, roles, config, Entity/Repository queries, migrations, supplier and grant scopes are forbidden.

Target commands after coordinator grants the single Aurora Maven window: `mvn -B test -Dtest=SchedulingPredictionProvenanceContractTest,SchedulingReadinessContractTest,SchedulingProbabilityTenantBoundaryContractTest,SchedulingCapabilitiesContractTest,SchedulingPlanCrudExplicitInputContractTest -Dspring.profiles.active=test`; native frontend target `npx vitest run src/views/scheduling/plans/__tests__/SchedulingDiagnosticsPanel.spec.ts`. Real HTTP adapter tests use existing MockRestServiceServer/MockMvc helpers, not production fault injection. Original QA must later assess real UI/SQL and test-layer source evidence under original S1-02/10 criteria; no full-stage PASS predeclared. Return requested scope expansion instead of touching unlisted files.

Coordinator took the 11-file implementation scope back after worker SAFE. Independent review blocked the first implementation; strict diagnostics now reject missing verified model features without calling legacy estimation or a provider. Add exact new test backend/java/cretas-api/src/test/java/com/cretas/aims/repository/SchedulingReadinessRepositoryQueryValidationTest.java for real Hibernate/repository/service wiring, actual tenant-scoped line references and before/after scheduling table counts on isolated PG13/17. Native test fixtures are test-database-only; no production resource edits. Runtime admission target separately registers actual JWT/permission/module interceptors and a real Spring constructor-wired controller/service context (repository dependencies mocked there, not labeled PG). Multi-draft UI must show each draft and an empty-input state. Current original product criteria remain unpassed until QA.
