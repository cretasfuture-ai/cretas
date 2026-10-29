# SC49 material cost accumulation

- Task SC49-COST-ACCUMULATION-20261029; Base 5519fd672a26bd2bc072cbfeab2732262ad37a30.
- Owner Root; execution sc49_cost_fix; independent review sc49_cost_risk_review.
- Source scope: ProcessingServiceImpl, BatchConsumptionServiceImpl, shared
  MaterialConsumptionCostTotals and three associated contract test files.
  No Entity/Repository/query/migration changes. Source implementation frozen;
  task enters review and source locks released. SC43 native acceptance remains
  in progress, separately coordinated in the native closeout worktree.
- Canonical factory-scoped signed consumption totals preserve manual, AUTO_BOM
  and adjustment costs. Foreign source stock rejected before writes. Both manual
  overloads roll back stock, consumption and cost on canonical read failure.
- Reviewed source 7f06b5ac3ce610fde2f367b3151bbed10fdc92d1: 25 target tests passed,
  zero failures/errors/skips. Review's remaining compatibility-overload test
  condition closed by 2d28b117811b96031783a11eef65eb6699922058: only transaction
  test changed, both Spring-proxied rollback cases passed. Other 24 reused.
- Release selector: ProductionBatchMaterialCostAccumulationContractTest,
  BatchConsumptionShortfallContractTest,BatchConsumptionSummaryNoFakeRateTest,
  ProcessingServiceImplBatchSourceTypeTest,
  ProductionBatchMaterialConsumptionTransactionContractTest.
- Build/PR/CI/merge/deployment and F006 11017 normal recalculation/readback are
  independent pending delivery steps; no production repair claimed here.
