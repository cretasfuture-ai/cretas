# SMARTBI-LABOR-UX-20261105

Owner: root. Base: `30ead06adaf62b03b0a0dba0ae4bab23679f9225`. Draft PR #1390.
Scope: common read-only labor analysis, Blacklake product/order drill-down, native MES cost-audit component wiring, read-only payroll-analysis denominator fix and regression tests. No Entity/Repository/migration/payroll mutation or production deployment.

## Existing Cretas rules checked

Checked against fetched `origin/main` `66339d797e2cce03beb5db587106a3b3c76ca63d`; the relevant Java cost implementations matched before this change. Only the read-only payroll-analysis method is changed below.

- `ClerkProcessEntryServiceImpl.computeLaborCost`: sum segment duration × segment worker count × rate; overnight duration supported. This is wage calculation, not an instruction to add another cost on top of already-recorded wages.
- `OrderCostBreakdownService.accumulateLaborDetails`: exposes duration, person-hours, per-segment worker count and amount. Person-hours already include worker count; multiplying them again is wrong. Known wages with missing time detail remain known money, while time-based verification remains unavailable.
- `WipCostAllocation.byQuantity` and `ReportedOutputCostAllocationService`: preserve total cost across valid output allocations, use the explicit allocation basis and carry rounding remainder. Finished goods and semi-finished goods cannot each receive the entire same cost pool.
- `StandardCostServiceImpl`: standard labor may be an R&D estimate per kg; standard and actual labor are different measurements. New panel consumes collected actual detail only.
- **Payroll report fix:** `WageCalculationService.analyzeLaborCost` used a whole-period wage numerator with `countWorkersByDate(factoryId, periodEnd)`. It now uses distinct worker IDs from existing `PayrollRecordRepository.findByDateRange`, whose factory/period predicates match the existing wage sums. Incomplete IDs or wage amounts leave per-person averages null; repeated records for the same worker are counted once. The report runs in a read-only repeatable-read transaction. Payroll calculation/payment records are not modified; no Repository method/query, Entity or mapping changed. `WageLaborAnalysisTest` covers the former final-day denominator error and incomplete/empty populations. Source fix/test does not establish a deployed Java runtime fix.

## Common analysis contract

- Input is an already-authorized cost scope: collected process wages, final output quantity/unit, and optional proven person-hours/actual participant IDs.
- Process contribution: recorded process wage / recorded total wage. Unit labor: wage / final output once, not the sum of intermediate process outputs.
- Person average: scope wage / distinct actual participants, only when roster completeness and cost-detail reconciliation are established. An operator/reporter ID is not treated as a participant roster.
- Hourly labor: scope wage / complete person-hours. Blacklake `outputDurationForSecond` is kept as reported duration and never silently re-labelled as person-hours.
- Optional person-hour allocation is explicitly a **read-only comparison**, separate from recorded wages. Requires nonnegative complete person-hours and a reconciled wage pool; cent remainder stays with the final positive-weight process, intermediate rounded amounts cannot exceed remaining cents. Total is conserved; no evenly-split fallback, negative shares or payroll writes.
- Missing data stays null. Requests state what is missing, which role should provide it, and what can then be concluded. Zero wages trigger confirmation of separate/combined pay or missed entry, not a claim of free work.

## Wiring and administrator flow

- `LaborCostAnalysisPanel` is shared by the Blacklake product/order views and native `M67YieldCost` audit. Native input uses authoritative `cb.laborDetails`, guarded by `cb && !cb.priceMasked && cb.laborCost != null`; it does not recover masked prices from fallback step data.
- Native person-hour input is `laborHours` once. Per-segment headcount averages require an actual start/end time segment; whole-period unique headcount is not synthesized from the sum/max of segment counts.
- Product list comes before charts. Unit comparisons are collapsed. Product detail removes duplicate overview KPIs and places long evidence/calculation notes behind details. Action/owner and the exact affected order remain prominent. Custom date selection starts with a valid source range.
- Existing 26-order data and 145 saved model results are unchanged. No paid teacher/model calls or Blacklake writes.

## Evidence and limits

Private local evidence root: `D:/CretasSmartBILab/local-analyst-distillation/labor-ux-20261105/`.

- 41 frontend tests passed: weighted/native adapters, distinct workers across processes, unknown staff/hours, known cost with incomplete time detail, cent reconciliation, tiny pools, no negative allocation, preview amount/share/unit consistency, permission wiring, product drill-down and existing 1000-order pagination. Final logs: `tests-frozen.log`, `typecheck-frozen.log`, `build-frozen.log`.
- 19 Java tests passed (4 new analysis regressions plus 15 existing wage-mode tests): `mvn '-Dtest=WageLaborAnalysisTest,WageCalculationModeTest' '-Dcretas.testIncludes=**/service/Wage*Test.java' test`, exit 0. Log: `java-tests-final.log`. Initial comma-separated test-compile glob matched no sources and was rejected by Surefire; that run is not counted as passing. Existing Repository queries/mappings are untouched, so no new query startup gate is claimed.
- Native in-app browser readback: real product/order labor breakdown; missing people/person-hours shown with owners/actions; old roster-free wage fields are not turned into a numeric person average. DOM artifacts retained locally.
- Native MES adapter/component fixture and source wiring verified; live native MES populated-order/production and phone visual acceptance are **not** established by these tests.
- Current Blacklake task/output sample contains no participant IDs or headcounts. Actual six-shan-men per-person cost and person-hour allocation therefore remain **awaiting source data**, not zero or a fabricated result.
