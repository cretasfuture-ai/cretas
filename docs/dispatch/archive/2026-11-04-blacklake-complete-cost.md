# BLACKLAKE-COMPLETE-20261104

Base: `5562832557f8cba5f193fcbf49b6dc07291c9733`. Owner: root.
Scope: exhaustive local evidence queue, original 44 omitted facts, fixed 26 eligible orders, cost/anomaly UI, authorization, local evidence and recovery. No Blacklake writes, production deployment, cloud-route change or source-snapshot rewrite.

## Contracts

- `analysis_evidence.build(..., include_all=True)` exposes all available facts; existing bounded callers retain default behavior.
- `analysis_batches.Queue` persists scoped inputs, leased FIFO tasks, original payloads/answers and reported usage in local SQLite. Completion requires a separate validated answer for every input fact. Restart reuses completed jobs; prioritization touches queued jobs only. Failed outputs do not count as completed analysis.
- Difficult per-order batches can be split down to individual facts. Superseded parents retain their original calls and usage; child jobs keep the original fact IDs. Active jobs cannot be split, and completion still requires the full original fact set.
- Outputs bind real names and numbers server-side and reject unsupported numbers, scope, certainty and complete-cost assertions. JSON schema fixes structure, roles and fact IDs, as supported by [Ollama structured outputs](https://docs.ollama.com/capabilities/structured-outputs); business validation remains independent.
- Exact full-coverage phrases may be rendered qualitatively only when the independently computed coverage equals one; original JSON is unchanged. No fully eligible order does not imply all fields are missing.
- Original 44 facts retain the original 109-order context. The eligible cohort has 75 section facts and 26 individual-order facts: 145 total. No representative answer substitutes for unanswered facts.
- The 26 cost rows are recomputed from retained observations and must exactly match full-cohort arithmetic. The corrected scope-bound BOM supplement is plan evidence, not actual consumption. No inferred lot allocation, price imputation or double counting of carrying value.

## SmartBI and authorization

The isolated “26张工单成本复核” navigation item shows cost data immediately and analysis as queued batches finish. It includes same-unit charts, stock/material documents, task payroll, final quantities, deterministic alerts, per-order requests and fact-specific follow-up. Display values use two decimals; calculations retain precision.

FULL cost authority plus tenant/factory/source binding gate `/complete-cost-analysis` and `/complete-cost-analysis/followup`. Only operator configuration supplies report paths. Stale run IDs and unavailable facts are rejected. Follow-up uses the selected saved evidence without new inference. Identity changes or access renewal failures clear old visible costs.

`BLACKLAKE_COMPLETE_COST_FILE` points to private `smartbi-complete-cost.json`, separate from historical date-filtered snapshots. Only the owned preview on 8095 is restarted; duplicate nightly scheduling is disabled. Original 8093, shared services and production are untouched.

## Run and recovery

```powershell
python -m blacklake.exhaustive_cost --input D:/CretasSmartBILab/local-analyst-distillation/lineage-20261104 --output D:/CretasSmartBILab/local-analyst-distillation/complete-cost-20261104
# After correcting an identified failure:
python -m blacklake.exhaustive_cost --input D:/CretasSmartBILab/local-analyst-distillation/lineage-20261104 --output D:/CretasSmartBILab/local-analyst-distillation/complete-cost-20261104 --retry-failed
```

Authority is checked before private reads and each batch. Calls share the existing local-only model FIFO and usage ledger. Unknown usage is explicit, not zero. Raw valid JSON and, after the adapter fix, unparsed/truncated text remain local; early malformed outputs discarded by the former adapter are marked unavailable while their reported token counts are retained. No malformed output becomes a conclusion.

Private root: `D:/CretasSmartBILab/local-analyst-distillation/complete-cost-20261104/`. Inputs, raw answers, parameters, completion/API/UI proof, human report, Git bundle and independent archive restoration stay local. Existing local model blobs and exact manifest are retained. No cloud teacher is needed; the original cumulative USD 10 cap remains.

## Validation and completion

Legacy PostgreSQL SCRAM/Redis chain: 364 passed. Targeted queue/authority/interpretation regression: 57 passed. Final queue/provider checks: 21 passed. Frontend targets: 22 passed, including progress recovery after a failed batch. Vue typecheck and frozen-source blacklake-mode build passed. Existing legacy CSS warning is unrelated.

Completion requires exact 44/44 original facts, 26/26 individual-order answers and all 145 fact IDs. Final accepted JSON revalidates against original raw answers; scope, unchanged cost arithmetic and exact model manifest are verified. Until private `completion-proof.json` exists, this run is in progress.

## Completed acceptance

Completed 2026-11-04 23:42 Beijing time: all 145 facts, including original 44 and 26 per-order answers, across 43 accepted batches. Every accepted batch revalidated against original raw model JSON; all 26 material/payroll/unit-cost calculations reconciled at both private Python and Vite proxy endpoints. Native UI displayed 44/44, 26/26 and 145/145, and scoped follow-up/stale-version rejection passed. Legacy period-analysis HTTP remained 200.

All 66 calls, including failed validation and retries, returned recorded usage: 194,406 input + 69,174 output = 263,580 local-model tokens. Unknown usage calls: zero. Three early malformed/truncated raw strings were discarded by the prior adapter; their usage is retained and this limitation is explicit. Every accepted result has its original answer, and subsequent unparsed text is retained privately. Paid cumulative conservative teaching cost remains USD 1.958427; no new cloud calls.

The live model tag matched the exact locally retained manifest SHA-256 `aaee06c39dcf2437cde036998d960e1fc1494b8191be7cc9657d01e509097813`. Source business writes remain zero. Actual batch genealogy remains unverified; completion describes evidence interpretation, not proof of full manufacturing cost or business causes. Full private report and independent archive restoration receipts are under the private root above. No merge or production deployment.
