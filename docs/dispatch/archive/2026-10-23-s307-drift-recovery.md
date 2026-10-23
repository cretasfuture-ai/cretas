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

Root集成附加scope（2026-10-23）：仅 `.github/workflows/java-gate-pr.yml` 的现有真实PG selector追加 `StocktakeDriftRecoveryPostgresContractTest`。唯一写Owner Root；保持原selector、判据、资源及路由不变。原14产品文件仍由原worker独占。

产品实现已在 fe7fbbc673969aa20f9dc7c8534c46f52aa555a3 冻结，worker已释放写权限，Root接管本worktree集成与验证。Web真实挂载验证通过；独立风险复核为 PASS_WITH_VALIDATION_PENDING，Java、真实PG、JPA与迁移结果不得由该结论替代。当前任务未合并或部署；通过对应门禁后才归档释放。

回滚边界：迁移仅追加可空字段与索引。首次产生 RECOUNT_REQUIRED 业务行前可以按官方发布流程回滚；产生后旧二进制不认识新枚举，只能使用兼容新状态的修复版本前滚，不自动回退旧JAR或恢复生产数据库。原始快照、审批、失败与审计均须保留。

最终候选归档（2026-10-24）：本提交合入前仍由Root唯一持有集成scope，合入后实现scope随ACTIVE移除释放，发布与原QA复验责任由Root续接；不宣称当前已合并、部署或关闭恢复缺陷。独立PG13 v6已实际验证3个恢复测试（0失败/错误/跳过）；真实迁移首次及重放、旧快照保留已通过。完整检查和官方发布仍为硬前置。批次搜索修复共用一次最终检查/发布；不改变S3-07原已通过判据。私有证据位于协调目录s307-drift-recovery-implementation-20261023与s307-migration-pg13-20261023，原FAIL保留。

2026-10-24 final-check correction: frozen d8e498f21 failed the unchanged full-suite gate only at BusinessException409HintCoverageContractTest.repoWideHintlessCountDoesNotGrow (197 -> 205). Root retains unique delivery scope and adds actionable withHint to the eight newly introduced recount 409 branches. No error code, status, permission, tenant, workflow, inventory, query, migration, test assertion or known-red change. Actual original full/PG evidence remains in coordination/s307-s315-final-ci-1124-20261024; first full=1 failure, PG=155/0/0/0. Run affected hint gate plus FactoryStocktakeM12ContractTest, then freeze corrected candidate for required final checks and official artifact; unchanged Web source/build evidence is reused. Not merged/deployed/business accepted.
