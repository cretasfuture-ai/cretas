# F006-MOBILE-R16-BATCH-TENANT-20261004

Base defc450e3e2c455a1737a3e465cd161ba1ced2dc; Owner root DIRECT; isolated WT C:/Users/Steve/cretas-f006-mobile-r16-20261004.

R10 independent review identified that cancelWorkerAssignment accepted factoryId but resolved and modified a work session solely by batchId/employeeId. This is source evidence, not a performed cross-tenant production exploit. No foreign-tenant mutation was sent.

Locked code scope: backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/ProcessingServiceImpl.java only cancelWorkerAssignment; src/test/java/com/cretas/aims/service/impl/ProcessingBatchWorkerTenantContractTest.java. Reuse existing ProductionBatchRepository.findByIdAndFactoryId, validate before any session lookup/mutation; retain same-factory cancellation and ResourceNotFoundException contract. No Entity, Repository/query, migration, RBAC grant or global Security changes.

Acceptance: JDK21 Maven target ProcessingBatchWorkerTenantContractTest,GateSelectorCoverageContractTest with **/*ContractTest.java test includes; independent risk review; trusted build selector plus FlywayVersionUniquenessTest before release. Missing/foreign batch means zero session lookup/save; assigned and working own-factory sessions retain success; no active own-factory session means no save. Final commit/PR/CI/deployment/device acceptance pending. Root serializes trusted cache/build/deploy with R12.

Final target validation: ProcessingBatchWorkerTenantContractTest actually executed six parameter-expanded behavior cases; GateSelectorCoverageContractTest four; total ten PASS, zero failures/errors/skips, BUILD SUCCESS. Private r16-tenant-target.log. Independent source risk review P0/P1 zero. Final source is four-line tenant lookup guard only; no existing query or mapping changed. Integration with R12 into a single backend candidate is planned; combined trusted selector includes both scopes and Flyway uniqueness. No production/native acceptance yet.
