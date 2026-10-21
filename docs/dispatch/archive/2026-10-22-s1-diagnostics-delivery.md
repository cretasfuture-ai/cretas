# S1-02/10 diagnostics delivery

Task ERP-S1-SCHEDULING-TRUTH-20261020; sole Owner 01a15272-374d-72c0-bb20-cdfa0bd823f1.
Historical Base1b9 retained; integration Base fe1eb117c68e1a4efc4b9ca571570478172c94b0. Qualified WT C:\Users\Steve\cretas-supplier-release-main-1076-20261021, branch codex/s1-diagnostics-delivery-20261022.

Exact product/test scope:
- `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/SchedulingDiagnosticsController.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/scheduling/CompletionProbabilityResponse.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/scheduling/SchedulingReadinessResponse.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/scheduling/impl/SchedulingAIServiceImpl.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/scheduling/SchedulingReadinessService.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/repository/SchedulingReadinessRepositoryQueryValidationTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/scheduling/impl/SchedulingPredictionProvenanceContractTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/scheduling/impl/SchedulingReadinessContractTest.java`
- `web-admin/src/api/schedulingDiagnosticsApi.ts`
- `web-admin/src/views/scheduling/plans/SchedulingDiagnosticsPanel.vue`
- `web-admin/src/views/scheduling/plans/create.vue`
- `web-admin/src/views/scheduling/plans/__tests__/SchedulingDiagnosticsPanel.spec.ts`
- `web-admin/src/views/scheduling/plans/detail.vue`
- `web-admin/src/views/scheduling/plans/__tests__/schedulingDetailWire.spec.ts`

Targets: npx vitest run src/views/scheduling/plans/__tests__/SchedulingDiagnosticsPanel.spec.ts src/views/scheduling/plans/__tests__/schedulingDetailWire.spec.ts; npx vue-tsc --noEmit. Java selector SchedulingPredictionProvenanceContractTest,SchedulingReadinessContractTest,SchedulingReadinessRepositoryQueryValidationTest,SchedulingProbabilityTenantBoundaryContractTest,SchedulingCapabilitiesContractTest,SchedulingPlanCrudExplicitInputContractTest.

Reuse exact unchanged five production Java + provenance/readiness test hashes, real loopback26, PG17.11/H2 fixture15 (14 deferred+1readiness), source-manifest B822623; no false PG13 claim. Final integration gate binds actual main-based source. Keep supplier typedactor/session/cache/strictverdict: ToolDispatch excluded from this delta. Preserve f9a6 computed permission and reason-only payload; detail diagnostics must request existing schedule mode alone. Red mounted actual API params before fix, candidate green/mutation/restored green.

No production resource/data mutation or fake capacity/APS. Source/CI/release/UI+SQL are independent. OriginalQA1552/F006 only;07 actor1698 is reserved for later explicit QA lifecycle handoff. Resource route Aurora direct verified SSH; independent target/database/port/artifact and existing serial Maven wrapper, fresh resource admission before launch; no second heavy JVM. Grant8 retained separately, refusal audit needs actual domain contract; not an Ops release prerequisite.
