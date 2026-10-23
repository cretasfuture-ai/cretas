# ERP-S305-WIP-QUANTITY-20261023

- Base SHA: `7b211d798fdd7f7519d820ba2503b7cea72eb32f`.
- Unique implementation write Owner: `/root/s305_wip_quantity`; Root retains integration/review/release decisions. Root's previous S305 source is frozen, immutable in PR1117; Root will not concurrently edit these product files. This follow-up must not push to, cancel, expand, or block PR1117 without a new concrete reason.
- Worktree: `C:/Users/Steve/cretas-s305-wip-quantity-20261023`, branch `codex/s305-wip-quantity-20261023`.
- Confirmed defect evidence: private `execution/coordination/s305-downstream-quantity-gap-20261023/receipt.json`. Actual F006 report23989 OUTPUT0.81kg, task1989, WIP383 produced0.81/consumed0.80/available0.01. Current correction applies report/task output changes without WIP reconciliation. No unsafe correction has been executed.

## Exact product write scope

1. backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/ProductionReportCorrectionServiceImpl.java
2. backend/java/cretas-api/src/main/java/com/cretas/aims/service/wip/WipInventoryService.java
3. backend/java/cretas-api/src/main/java/com/cretas/aims/service/wip/impl/WipInventoryServiceImpl.java
4. backend/java/cretas-api/src/main/java/com/cretas/aims/service/wip/WipLedgerEntries.java
5. backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/ProductionReportCorrectionServiceContractTest.java
6. backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/ProductionReportCorrectionFlowContractTest.java
7. backend/java/cretas-api/src/test/java/com/cretas/aims/service/wip/WipOutputCorrectionContractTest.java (new)
8. backend/java/cretas-api/src/test/java/com/cretas/aims/service/wip/WipOutputCorrectionPostgresContractTest.java (new)

## Root interface extension after 84cfb4f8aa — 2026-10-23

The executor released the initial eight files cleanly at84cfb4f8aa6db943c34452d5a77ad5e3ead06682 with28 target passes including2 actual isolated PostgreSQL17 tests. This is not integration acceptance: the executor and Root both confirmed that existing report reversal rejects report-linked ADJUST and `replayMovingAverage` ignores the new correction ADJUST. This necessary consumer compatibility is now released to the same sole executor, with unchanged Base7b211 and no change to frozen PR1117. ACTIVE/Owner scan found no separate active reversal writer.

Additional exact write files:

9. backend/java/cretas-api/src/main/java/com/cretas/aims/service/reversal/impl/ReportReversalServiceImpl.java
10. backend/java/cretas-api/src/test/java/com/cretas/aims/service/reversal/ReplayMovingAverageTest.java
11. backend/java/cretas-api/src/test/java/com/cretas/aims/service/reversal/ReportCorrectionLedgerReplayContractTest.java (new)

Replay must incorporate only the explicitly identified quantity-correction ADJUST into net produced quantity while preserving the original total cost, unknown-cost propagation and all ordinary stocktake/reversal semantics. A downstream INPUT reversal after consuming corrected stock must not erase the correction quantity. Direct reversal of an already quantity-corrected source report may conservatively reject before any mutation with a specific actionable conflict explaining the supported correction route; do not misreport its valid correction ledger as corruption, or implement a broad new reversal workflow. Unknown/ordinary ADJUST keeps all existing rejection behavior.

The initial implementation unconditionally clears WIP unitCost even with a known accumulated cost. Correct this within the original WIP scope: unchanged known accumulated cost and changed positive produced quantity give a derived unit cost using existing rounding conventions; unknown cost stays unknown. A real zero cost may remain zero; never synthesize unknown cost as zero, never discard known cost without a concrete unsupported-path rejection. Keep immutable original ledger and capture honest correction snapshots.

Prevent precision drift between report/task/WIP persistence: verify actual quantity column/DTO semantics, and reject unsupported proposed precision before mutation rather than silently round differently. No Entity/schema edit is released.

Target selector extends the four original classes with `ReplayMovingAverageTest,ReportCorrectionLedgerReplayContractTest,BusinessException409HintCoverageContractTest`. The existing hint ratchet is read/run only; fix product hints rather than its baseline or assertions. All new409 branches require actionable hints. Preserve original tests; add known/unknown cost, positive/negative correction replay, downstream reversal, direct reversal refusal, precision refusal and scoped rollback assertions. The earlier strict-Mockito fixture failure is not behavioral red proof; preserve it as a tool/test-fixture failure, then capture one actual guard/wiring mutant-red and restored-green with exact source hashes and only the affected target. No repeated full/build and no production operations. Return updated exact files/commit/tests/real-PG/unsupported paths and SAFE_CODE_OWNER_RELEASED.

No Entity/Repository/query/migration/Security/controller/frontend/CI/release/ACTIVE/canonical edits by the executor. Necessary additions return an exact EXPANSION_REQUEST before modification. Only Root may later append the new real PostgreSQL test to the existing CI PG selector after reviewing its execution contract; no whole-workflow rewrite.

## Bounded behavior and acceptance

Trace actual normal posting semantics first, including output/good/defect quantities, semiOutputs, FINISHED/multiple postings and existing cost/audit rules; do not assume every report maps to one WIP row. Keep submit/approval tenant, role, report/plan locks, terminal/settled and duplicate guards. A notes-only zero inventory delta must preserve stock and avoid fake adjustment rows.

Implement a dedicated atomic correction operation for proven simple, same-unit, unconsumed WIP only. Lock the actual WIP using existing repository methods, preserve accrued cost/null semantics, recompute quantity/availability and append immutable clearly identified report-correction ledger evidence (correction ID and report ID). Never mislabel it as ordinary reversal or guess kg/box conversions. Recheck on approval before workflow transition. Existing downstream consumption, missing/ambiguous posting, unit mismatch, unsupported multi-output or other unreconciled stock path must reject quantity-changing corrections with actionable 409 and zero report/task/workflow/WIP/ledger effects; do not reject lawful notes-only correction solely because an output was consumed. Existing real consumed383 must reject unsafe quantity changes; choose an existing genuinely unconsumed QA output for later business validation only after Root fresh readback, not by inventing inventory.

Tests must prove old behavior red, safe increase/decrease and zero delta, actual consumed/missing/unit/complex-path refusal, idempotency and rollback. Preserve every existing assertion and permission/tenant/approval/settlement guard; no known-red, skip-as-pass, fixture weakening or fake success. Use actual PostgreSQL transaction testing for atomic rollback and competing consumption/recheck; mocks alone do not close stock correctness. No schema mutation is authorized; an unavoidable query/Entity change requires explicit scope expansion and applicable true JPA gate.

Development target selector: `ProductionReportCorrectionServiceContractTest,ProductionReportCorrectionFlowContractTest,WipOutputCorrectionContractTest,WipOutputCorrectionPostgresContractTest` with the repository's existing real-PG opt-in flags/wrapper, recording actual executed classes and failures. Read existing helpers; do not rebuild an E2E scaffold. No full suite/build/deployment by executor. Root later freezes the follow-up once, runs necessary final gates and reviews actual version integration with1117; no duplicate full or candidate rebuild during development.

Resources: Windows native isolated worktree/target/generated files; at most2CPU/2GiB per JVM, free physical memory>=8GiB before Maven, only one Maven process in this worktree, sequential targeted commands. Original Windows-light runner remains3CPU/6GiB and Aurora full remains untouched. Any real-PG test cluster must use existing installed binaries, a new owned data directory/database/port55328, never shared55327 or Docker/WSL maintenance. No browser/phone/production mutation or grandchildren. Stop at a safe checkpoint if resources unavailable and return evidence, not a fabricated pass.

Receipt: task/Base/actual files/commit/commands+exit+executed classes/logs, original red/restored green, real-PG atomic evidence, unsupported paths and remaining business acceptance; `SAFE_CODE_OWNER_RELEASED`. No whole S3-05 PASS from source or notes-only acceptance.
