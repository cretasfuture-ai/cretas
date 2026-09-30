# BUG-F006-REPORT-WIP-COST-20260930

- Base SHA: `2e9a7754478ac9f38d50a052ccd0e28a18209f71`.
- Scope: `WipInventoryServiceImpl`, `YieldReportServiceImpl`, their target tests, and dispatch records. No Entity, Repository, migration, or Web changes.
- Cause: each report stage added all batch INPUT cost to its own transferred WIP cost; partial OUTPUT posted cumulative cost twice; batch yield summed transferred WIP again.
- Fix: allocate each stage's own material and labor once, post the incremental cost for explicit partial OUTPUT, and exclude transferred WIP from batch material total. Unit comparison uses the canonical unit contract.
- Verification: six selected test classes, 251 tests passed; candidate Java build succeeded and was staged with SHA-256 `df486fcb9d828f1d16f794605c6b7e13c47f3888363a0d16c7fe2927469f610f`. Staging did not install or deploy it. PR #848 carries the implementation.
- F006 batch 10943 retains its historical incorrect WIP ledger until a separately authorized official reversal and rebuild. The code release alone does not rewrite historical inventory transactions.
