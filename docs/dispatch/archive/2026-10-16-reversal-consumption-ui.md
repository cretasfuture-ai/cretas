# Withdrawal consumption cleanup and re-report settlement

Task `REVERSAL-CONSUMPTION-UI-20261016`, coordinator root, base `eb8222c69fe1ec9c727d7b64b82431158c17f297`. Nonproduction synthetic factory only. Reuses the isolated BOM/Workflow environment accepted in #996; no production access, deployment, feature enablement or migration.

## Defect and change

Whole-order report withdrawal retired reports and process-sheet rows but retained delayed, unposted MaterialConsumption rows. A later report in the same batch was accumulated with those withdrawn inputs. The real UI baseline rejected production summary with bag consumption20 against available10.

The reversal transaction now soft-deletes only its authoritative factory/batch's delayed consumption, including null-plan WIP rows. It preserves quantities and posted rows. Before stock-production summary, historical cleanup enumerates every authoritative plan batch, including old steps whose process rows are already deleted. Only matching WHOLE_ORDER/DONE receipts authorize cleanup. The stable cutoff is approvedAt, falling back conservatively to createdAt; mutable updatedAt is never authority because WIP-history repair can update a completed receipt. Re-reports after that anchor remain active.

Eligible delayed sources are RAW_MATERIAL, PACKAGING, SEASONING and SEMI_FINISHED, traced to Clerk/ProcessSheet writers. AUTO_BOM and ADJUSTMENT may already affect inventory despite a null interim marker and are preserved, as are rework/unknown/null sources. No source-writer or marker semantics were changed.

Both collaborators are required in Spring; cleanup joins the parent's transaction with MANDATORY propagation. Plan and target batch are locked before reversal reads its report snapshot; historical cleanup locks the plan before enumerating all batches. Batches and consumption rows are locked in stable order; consumption state is refreshed and rechecked after locking.

Actual UI readback caught that the existing formal-batch finder intentionally excludes CLERK_WIP. A separate factory/plan/undeleted batch finder is added only for cleanup; the original formal-business query is unchanged. Its real JPA context and semantic regression must pass. No Entity, DTO, front-end or Flyway change.

## Verification

- New ReversalConsumptionCleanupServiceContractTest:9 tests passed, 0 failures/errors, BUILD SUCCESS, process exit0 (`reversal_cleanup_wip_final.log`). Controls cover null-plan input, same-batch re-report cutoff, mutable audit time, foreign lineage, missing/future timestamps, posted/refresh-changed state, delayed versus immediate sources, required Spring/callsite wiring, and plan-before-batch locking.
- ProductionBatchRepositoryQueryValidationTest:3 tests passed, 0 failures/errors, real Hibernate/JPA context (`reversal_wip_jpa_semantic.log`). Persisted REGULAR/CLERK_WIP/foreign-factory controls prove cleanup includes WIP while the original formal query still excludes it.
- Regression failure evidence retained: initial cleanup behavior mutation produced2 assertion failures/0 errors; restored selection passed. Existing related reversal47 and interim31 passed before final safety refinements; final frozen PR gate independently verifies their final content.
- CI selection: `.github/workflows/java-gate-pr.yml` fast-contracts selects `*ContractTest`; final full-ready suite includes these tests instead of duplicating the fast suite. Exact-head execution and counts must be read from final CI before manual merge.
- Existing Web build, unchanged frontend tests, and unchanged scripts/tests evidence from #996 are reused; no unrelated local full-suite repetition.

## Real UI closure:3/3 passed

| Scenario | Real UI action | Independent persisted readback |
| --- | --- | --- |
| New withdrawal | Operator1 reports dough1.6kg, submits withdrawal; independent supervisor2 approves log3 | Report7, process row7 and delayed MC14/15 soft-deleted; raw used quantities remain0; no FG created |
| Re-report and settle | Same plan re-reports dough1.6kg and bread10个, then production summary200 and stop200 | Exactly4 active consumption rows posted once; withdrawn2 remain retired; one AVAILABLE finished batch10个; source used quantities exact and reservations0; plan COMPLETED |
| Historical plan | Existing failed plan production summary changes409→200; dedicated WIP fix followed by another real summary | Old MC1–5 retired, new MC6–9 preserved/posted; second summary deducted0 and created0 FG; original one FG remains10个; stop200 and plan COMPLETED |

The intermediate historical summary returned200 but left null-plan WIP MC1/2. That database assertion remained FAIL until the dedicated all-batch query was verified and the second UI summary retired them. No assertion was removed and no direct SQL repair was used. Original FAIL screenshots/readbacks remain available.

Negative readback: the previously accepted unrelated plan in the same factory retains identical plan,2 batches,2 reports,4 consumptions,2 process rows and1 finished batch. Separate existing-factory fingerprint:504 tables unchanged. Existing BOM/Workflow publication, receiving and unrelated business UI were not repeated. Default hourly-cost/warehouse fallback warnings were inherited and are not cost-configuration acceptance.

Final local runtime PID74140 uses these rebuilt classes with real PostgreSQL; Spring/Hibernate startup succeeded. Private scenario table and evidence: `Documents/assembly-integration-assessment-20261015/target-ui/reversal-consumption-scenarios.md`, `reversal-ui-assert-{withdrawn,legacy-closed,rereport-closed}.json`, `reversal-unrelated-controls.json`, `reversal-control-comparison.json`, `reversal-state-*.json` and scoped screenshots. No credentials or raw runtime arguments are committed. Exact final PR CI and manual merge are independent facts recorded in the PR.

## Limits

This changes unposted delayed consumption only. It does not reverse inventory already posted by automatic BOM, adjustment, rework or a production summary. Existing approval, downstream-consumption, shipped-goods and repeated-withdrawal guards remain. Simultaneous withdrawal/summary may encounter the existing inverse inventory lock order and require transaction retry; this slice does not claim concurrent UI acceptance. Unknown or ambiguous history is preserved rather than guessed. No direct SQL business-state repair.
