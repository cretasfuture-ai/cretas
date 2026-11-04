# BLACKLAKE-LINEAGE-20261104

Owner: Codex/root. Base: `0ebe902e16c772a5d123b34e1cf2ef0a64ed09ec`.
Scope: isolated Blacklake read-only cost traceability, per-order requests, shared analysis evidence and local teaching. No source business writes, historical snapshot rewrites, migration or production deployment.

## Implementation

- `blacklake.lineage_probe`: bounded read-only requests through existing allowlisted client, Redis coordination and source binding. Original cohort retained; outside orders excluded. Material parent filters compared by ID and code plus negative control. BOM requires `lastProductCode`; unsuccessful broad query retained in evidence and corrected with a separate supplement.
- `smartbi.services.cost_traceability`: source-neutral typed evidence diagnosis. Distinguishes business-document linkage, feed-process linkage, physical batch allocations, valuation, final quantities and payroll. Every request includes affected records, source state, responsible role and expected benefit. Failed query belongs to integration owner; unfinished production is a pending condition. Explicit zero remains distinct from missing.
- `blacklake.traceability`: maps observed fields without treating `originDetailId` as a stock batch. Existing custom-field values are retained as clues with unverified units/business meanings. Same-product producers remain candidates. No inferred upstream allocation or double counting of semi-finished carrying value.
- FULL cost-authorized `/api/smartbi/blacklake/cost-traceability?mode=latest|snapshot`. Latest file is configured by an absolute operator-only environment variable, bound to tenant, source factory and baseline. Snapshot mode uses original data only.
- Existing prototype “关联与估值补查” now has searchable, filterable per-order requests, human-readable documents/materials/processes, existing clues and two-decimal cost presentation. Latest original-cohort observations are visibly separate from old period filters.
- Shared manufacturing profile `.10`: batch boundary, per-order requests, custom-field semantics, carrying value, split/mixed batches and joint-product allocation. Stable control and order-vs-quantity coverage must be explained in prose. Only equivalent department-order aliases are normalized; unknown owners still fail.
- One-off teacher payloads contain fixed synthetic cases only. Original teacher outputs, local payloads/answers, provider token usage and validation failures are retained locally. No permanent cloud route or weight training.

## Configuration and execution

Credentials remain in existing DPAPI stores/local secret configuration, never in reports or Git. Existing approved test Java cost authority is rechecked before actual-cost inference and API reads.

```powershell
python -m blacklake.lineage_probe --output D:/CretasSmartBILab/new-immutable-probe
python -m blacklake.lineage_report --observations D:/CretasSmartBILab/probe/observations-private.json --bom D:/CretasSmartBILab/bom/bom-supplement-private.json --baseline D:/CretasSmartBILab/baseline-private.json --output D:/CretasSmartBILab/delivery
```

`BLACKLAKE_COST_TRACEABILITY_FILE` points to the generated private `traceability-latest-private.json`. It grants no source access or cost authorization itself.

## Evidence and limitations

Private evidence root: `D:/CretasSmartBILab/local-analyst-distillation/lineage-20261104/`.
Includes original observations, corrected BOM, custom metadata, machine report, complete text/XLSX worklist, teacher original answers, local 27B raw answers, validation, API readback, UI snapshots and independent archive restoration proof. No real factory rows were sent through the one-off cloud teacher.

- Required source queries: 565; custom-field metadata queries: 3. Original 109 orders returned; 14 outside orders excluded. Current 26 field-eligible, 83 blocked; 11 originally blocked improved and 1 formerly eligible regressed. These counts describe current fields, not verified complete manufacturing costs.
- 145 material-line business links, 14 feed-process links; no verified physical batch chain. 334 overlapping requests include ordinary unfinished conditions, not 334 distinct production anomalies.
- Local 27B: seven boards validated after normalizing department order, 31,842 tokens across two calls. Partial coverage: 44 available facts omitted; full 109-order request coverage is deterministic, not an AI-review claim.
- Existing backend chain initially 359 passed/1 failed because an old synthetic reader fixture omitted stock-order IDs. Fixture corrected to real source shape; all 27 period/traceability regression tests passed. Added traceability/teacher tests: 28 passed. Final analyst tests: 36 passed. Frontend targets: 12 passed; Vue typecheck and blacklake-mode build passed. Separate receipts retained.
- An additional default-day preview retry exposed an overly literal partial-period phrase check: explicit “数据未覆盖完整周期” was rejected. Equivalent explicit incomplete-period wording is now recognized; positive complete-period assertions still fail that check. Original rejected answers and retry usage remain preserved.
- Native in-app UI verified direct entry, 11-order recovered filter and specific blocked-order document/owner/benefit. Authenticated reads verified on private Python and Vite proxy; invalid token rejected.
- Paid cumulative conservative ledger: USD 1.958427, original USD 10 ceiling retained. No new permanent provider, no source mutation.

The local model explains representative evidence only. Missing physical batch allocations, historical valuations, approved quantity/unit meanings, and joint-product allocation cannot be invented by prompting. Official dynamic documents were inaccessible during this pass; existing verified contracts plus live observed shapes were used, not third-party old definitions.
