# S307 漂移后重盘恢复

```json
{
  "taskId": "ERP-S307-DRIFT-RECOVERY-20261023",
  "baseSha": "4a48c5b49c3a5f5214c68a3add5081ee409d88b2",
  "owner": "/root/s305_explicit_plan_lock",
  "coordinatorThreadId": "01a1523e-881c-7a92-b7ab-f4b8c5a552df",
  "worktree": "C:\\Users\\Steve\\cretas-s307-drift-recovery-20261023",
  "branch": "codex/s307-drift-recovery-20261023",
  "state": "in-progress",
  "writeScope": [
    "backend/java/cretas-api/src/main/java/com/cretas/aims/entity/factory/FactoryStocktake.java",
    "backend/java/cretas-api/src/main/java/com/cretas/aims/repository/factory/FactoryStocktakeRepository.java",
    "backend/java/cretas-api/src/main/java/com/cretas/aims/service/factory/FactoryStocktakeService.java",
    "backend/java/cretas-api/src/main/java/com/cretas/aims/service/factory/impl/FactoryStocktakeServiceImpl.java",
    "backend/java/cretas-api/src/main/java/com/cretas/aims/controller/StocktakeController.java",
    "backend/java/cretas-api/src/main/java/com/cretas/aims/dto/factory/StocktakeDTO.java",
    "backend/java/cretas-api/src/main/java/com/cretas/aims/dto/factory/StocktakeRecountRequest.java",
    "backend/java/cretas-api/src/main/resources/db/flyway/V20261103_77_1__stocktake_drift_recount_audit.sql",
    "backend/java/cretas-api/src/test/java/com/cretas/aims/service/factory/impl/FactoryStocktakeM12ContractTest.java",
    "backend/java/cretas-api/src/test/java/com/cretas/aims/repository/FactoryStocktakeAndDeliveryRepositoryQueryValidationTest.java",
    "backend/java/cretas-api/src/test/java/com/cretas/aims/controller/StocktakeRecountPermissionContractTest.java",
    "backend/java/cretas-api/src/test/java/com/cretas/aims/integration/StocktakeDriftRecoveryPostgresContractTest.java",
    "web-admin/src/views/warehouse/stocktakes/index.vue",
    "web-admin/src/views/warehouse/stocktakes/__tests__/stocktakeRecount.mount.spec.ts"
  ],
  "acceptanceCommands": [
    "mvn test -Dtest=FactoryStocktakeM12ContractTest,FactoryStocktakeAndDeliveryRepositoryQueryValidationTest,StocktakeRecountPermissionContractTest,StocktakeDriftRecoveryPostgresContractTest,FlywayVersionUniquenessTest",
    "npx vitest run src/views/warehouse/stocktakes/__tests__/stocktakeRecount.mount.spec.ts --maxWorkers=1",
    "python scripts/ci/check_dispatch_active.py"
  ],
  "resources": "No Java launch until Root confirms exclusive compute/target/DB/port slot; no browser/device/canonical rights",
  "completion": "Preserve old snapshots and approval history; drift-only versioned permissioned recovery; no inventory apply; fresh count requires normal OA; required real JPA+PG and final checks; Root merge/release then original QA UI and independent DB",
  "forbidden": [
    "purchase",
    "supplier",
    "Orchestrator",
    "Gateway",
    "S2/S6",
    "ACTIVE (worker)",
    "production SQL writes",
    "shared build configs",
    "existing migration edits"
  ],
  "atUTC": "2026-10-23T14:44:00.800152+00:00"
}
```

实测来源：S3-07 原QA已通过；旧APPROVED快照被409拒绝后无法通过正常入口重盘。此次仅修恢复，保留既有验收与原始失败。迁移版本77_1预留（77<77.1<78），不触碰Claude的78/79/80；避免先发更高版本阻断S6。Root负责集成发布；原QA独占UI/手机/canonical。
