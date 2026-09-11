# S315 normal batch search PostgreSQL failure

- Task: ERP-S315-BATCH-SEARCH-20261023
- Base SHA: 4a48c5b49c3a5f5214c68a3add5081ee409d88b2
- Owner: /root/s305_correction_boundary; Root integrates and releases; original QA owns UI/CSV.
- Worktree: C:/Users/Steve/cretas-s315-batch-search-20261023
- Branch: codex/s315-batch-search-20261023
- State: claimed
- Exact writable scope: `backend/java/cretas-api/src/main/java/com/cretas/aims/repository/ProductionBatchRepository.java`; new `backend/java/cretas-api/src/test/java/com/cretas/aims/repository/ProductionBatchSearchPostgresRepositoryQueryValidationTest.java`.
- Trigger: original QA normal F006 production-manager batch-number search with empty status produced HTTP500 trace0DE72463; active service PostgreSQL could not determine nullable status parameter type.
- Preserve tenant isolation, status/supervisor AND filtering, CLERK_WIP inclusion, plan-number search and filtered pagination. No processing service/controller/Entity/migration/shared execution changes released.
- Acceptance: actual isolated PG13 real JPA repository tests with null and explicit status/supervisor, negative cross-tenant, batch/plan search and pagination; `mvn test -Dtest=ProductionBatchSearchPostgresRepositoryQueryValidationTest,ProductionBatchRepositorySearchByBatchNumberTest`; repository startup gate and final complete checks before merge; original QA same normal search with independent DB readback after official release.
- Worker must not start Maven without an exclusive Root compute/target/database slot, or change ACTIVE/CI. Root alone may add this exact test to the existing PG selector in `.github/workflows/java-gate-pr.yml` after review. No lowering or removing assertions/baselines. No production writes by this worker.
- This independent repair does not change S315 business acceptance status or replace the independent secondary-plan experiment.
