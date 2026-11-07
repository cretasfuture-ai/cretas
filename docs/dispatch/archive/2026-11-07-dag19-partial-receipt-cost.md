# DAG19 partial receipt cost repair

- Task: DAG19-PARTIAL-RECEIPT-COST-20261107
- Owner: 01a1a954/root; Java implementation delegate to be assigned after registration.
- Base: 49009fd44ad1f348c5599a460744dd7ca177aebe
- Worktree: C:/Users/Steve/cretas-dag19-reason-20261107, branch codex/dag19-actual-reason-20261107.
- User explicitly transferred Food's receipt-cost source scope to this chat after the real QA failure. InquiryQuote remains frozen.
- Scope: ProductionPlanServiceImpl.java; ProductionWorkflowOutputCostAllocationTest.java; ProductionPlanSettlementTest.java; directly related receipt-cost ContractTest/PostgresContractTest if required. No Entity/Repository/DDL/Security/permissions/other Food source.
- Actual failure: reported .02 kg, cost .56; first .01 receipt computes unit56, second .01 changes settlement unit to28 but inventory remains56. POST response also has null cost while fresh GET has persisted cost.
- Repair: stable per-unit cost using reported output quantity, update existing inventory cost with authoritative output cost including explicit unavailable semantics, construct current transaction response after flushing persisted output costs.
- Acceptance: related allocation and incremental-receipt tests, meaningful real PG coverage for transaction/flush and inventory facts, official target build and required PR CI. Independent risk review required for monetary/inventory consistency. No migration expected. Production repair/revaluation not automatically authorized; retain exact QA inventory and retest via normal authorized business flow after deployment.
- Root concurrently owns only two independent Vue files and dispatch. Delegate must not edit them or dispatch; Maven target belongs exclusively to Java delegate until released. No production calls or SQL writes by delegate.
- Root also owns the shared CI selector integration: append ProductionReceiptCostPostgresContractTest to the explicit PG job, preserving latest main's full union. The PG test must execute the real receipt service and persist its derived inventory costs; a setter-and-save test cannot substitute for this behavior.

Same-cause sweep: received/reported quantity and existing-batch cost paths reviewed. The fix includes byproduct credit and both numerator/denominator for same-SKU multiple reported batches, plus existing inventory synchronization and response flush. Existing posted-plan rework recalculation is gated to completed receipts and is not broadened. The original production failure and dedicated real-service PG requirement remain separate from unit passes.

## Existing return-cost consistency integration

The group retains the original quality/rework maintenance ownership. Fresh ACTIVE and main checks found no competing writer or new-main change to ReturnedCostSynchronizationService. This task additionally owns that class and its existing ReturnedCostSynchronizationServiceTest only for a minimal unavailable-cost dependency guard, reusing existing repositories/native shipment queries. No Repository, Entity, new JPQL/HQL, migration or policy changes are authorized.

For a numeric unit-cost change, reuse the existing returned/disposal synchronization with the authenticated receivedBy argument and invalidate the existing cost indicators. Compare BigDecimal values numerically, not by scale-sensitive equals. For known cost becoming unavailable, permit honest null only when there are no downstream cost facts; otherwise reject the entire receipt with a specific actionable409. Reuse shipment/return/physical-adjustment evidence and verify expected prior receipt quantity, tenant and reservation state before changing the FG. Do not silently clear only the source while preserving stale return/disposal costs.

Correction: the original warehouse-POST null / admin-GET visible-cost comparison used different roles. ProductionOutputLineDTO already has PriceSensitive fields and the response advice masks warehouse prices. That comparison is not evidence of a flush defect and the former P2 inference is withdrawn. Do not change price masking, permissions or controllers. Internal service/SQL consistency and monetary rollback remain valid verification goals.

## Root final validation ownership

Root took exclusive source/test/target ownership after the delegate released it. The latest source must not be certified by the earlier incremental-build results: stale classes were detected and the isolated target/classes, target/test-classes and target/maven-status were removed after backing up reports. A fresh main compilation completed; javap confirms the null-cost downstream guard executes before adding this receipt delta and uses cumulative received minus this delta as prior quantity. The first test invocation was safely rejected for zero matching classes because a comma include was passed to a single-pattern property; it is FAILED, not a test pass. The corrected selector compiles tests with the default supported include.

Additional exact scope: native tenant/batch/deleted-scoped approved-disposal count in ReturnedCostSynchronizationService, fail-closed when unavailable; no Entity/Repository/DDL changes. Real PG cases inject the actual ReturnedCostSynchronizationService and repositories, test successful unavailable-cost clearing only without downstream facts, and approved legacy disposal rejection with full receipt rollback. Private native PG17 on localhost:59059 is Root-owned and must be stopped after the actual contract run; it is unrelated to export59057 and A0959058.

Latest source/bytecode hashes, commands and logs are private under six-closeout-20261107/dag19-cost-repair. Commit/push/PR/deployment and production reacceptance remain separate pending delivery facts. The old Inquiry freeze described above is historical: explicit user release and Food release were acknowledged, and A09 is now implemented in its own worktree.

## Final target results

Root fresh compiled main and all tests: ProductionWorkflowOutputCostAllocationTest6 + ProductionPlanSettlementTest66 + ReturnedCostSynchronizationServiceTest13 =85/85 PASS, zero skipped, BUILD SUCCESS at17:12:35+08. Subsequently only the period comment was corrected and the PG fault-injection fixture/rollback assertions strengthened; the monetary source behavior and 85 unit test cases did not change. The final official target artifact will bind the integrated exact commit.

Actual native PG17 receipt contract:4/4 PASS, zero skipped, BUILD SUCCESS at2026-11-07T17:20:13+08, exit0. It executed the real receipt service/repositories and real downstream SQL, verified stable cost28 for both .01 deltas, unavailable cost clearing without downstream facts, approved legacy disposal without adjustment-log rejection+full rollback, and forced output flush failure+full rollback. The earlier failed PG round (Spring proxy spy and owned cluster lock capacity) remains retained, not counted. Final source hashes and logs are private dag19-cost-repair/root-final-pg-source-hashes.json and root-final-pg-20261107-1716.log.

Owned native PG59059/system identifier7705353630265574792 was stopped after identity readback; listener0. The first failed round's one private schema is retained as failure evidence in stopped PGDATA, not represented as cleanup complete. The successful final round teardown passed. No other cluster/service was stopped. No production requests/writes occurred during the fix.

Vue reason mapping already has23 targeted Vitest PASS and vue-tsc/Vite build:check PASS; those unchanged source/spec results are reused. Mandatory PR CI, official candidate build, merge/deployment and own-QA production retest are pending separate gates.

Independent monetary/concurrency final review CLEAR on the frozen source/test hashes (ProductionPlanService9939F938E454327F508117BA7394BD4CAD6190BA333C52ADAE360BBFF8D82E5A; ReturnedCostD6D56F0B601EEB64D48E49AF0A2A4EBA4096D7608599A274E8D9A9EF55AE703E; PG B780F93838AD0013EB5210AB4D84FC1A084AB7B0C58DE6C12D7C81254926E89B). No P0/P1 source blocker; official build, PR mandatory CI and authorized release/production reacceptance remain pending. This final candidate archives the two task summaries and removes their ACTIVE additions; Root remains the unique code/release coordinator through PR handoff and actual release. No owner transfer or permission expansion occurs.

## Required CI fixture correction

PR1537 fast-contracts executed8078 tests and failed exactly one method: MultiOutputCostPolicyContractTest.costAllocatorStillCreditsAuthoredByProductsByNrv. Its helper populated receivedQuantity but omitted required reportedQuantity (Entity/DDL NOT NULL). The allocator now correctly uses reported output, so this incomplete in-memory fixture fails before its unchanged80/920 NRV assertions. Root additionally owns this directly related ContractTest helper to supply the already-authored report quantity; no production fallback to received quantity and no weaker assertion is allowed. All existing numerical/policy/negative assertions remain intact; rerun the10-method class and preserve the CI failure. No competing main/ACTIVE writer was found for this file.

Fixture-only review CLEAR: reportedQuantity set explicitly to the authored full-report amount; unchanged NRV80/920, conservation and negative assertions. Target MultiOutputCostPolicyContractTest10/10 PASS/0skip, BUILD SUCCESS2026-11-07T17:46:56+08, exit0. Final test SHA2561E63697BB2202227D283308F91DA2A32E1EADA4DD4E272F3B55054C8238D3CDA. Production hashes unchanged; prior85unit and4realPG remain valid for those ranges. Exact-head CI will rerun normally; do not bypass the failed old gate. A new official combination artifact will bind the new backend tree and127target cases.
