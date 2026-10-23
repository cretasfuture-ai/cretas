# ERP-S305-EXPLICIT-PLAN-LOCK-20261023

- Status: review; source implementation frozen, production/business acceptance pending.
- Base SHA: ee69c87ba55419818afd1b7bbfd964bfb7034881.
- Code Owner: /root/s305_explicit_plan_lock; coordinator/dispatch writer Root.
- Existing qualified worktree: C:/Users/Steve/cretas-s107-timer-delivery-20261022.
- Branch: codex/s305-explicit-plan-lock-20261023, newly opened from fresh origin/main after #1120 squash merge.

## Confirmed defect and completion conditions

The correction submit/approval paths recheck terminal plan statuses, but do not check ProductionPlan.isLocked. The existing plan field explicitly forbids quantity edits and is enforced by other plan write paths. A nonterminal, explicitly locked plan can therefore enter correction persistence/workflow and later output application. The original QA has retained S3-05 FAIL; completed real UI/SQL roundtrip evidence from #1120 must be preserved and reused.

Add an explicit lock guard before correction persistence/workflow and on approval/application recheck. Keep existing terminal-state, settled-report, role, tenant, WIP, cost, approval, recovery and audit behavior. Show an actionable 409 with the existing plan-locked code. Never unlock a plan automatically. A true lock must not be bypassed by a null/legacy status.

## Exact writable product/test scope (three files, extended after read-only PG interface review)

1. backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/ProductionReportCorrectionServiceImpl.java
2. backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/ProductionReportCorrectionServiceContractTest.java
3. backend/java/cretas-api/src/test/java/com/cretas/aims/integration/ProductionReportCorrectionLockedStatePostgresContractTest.java

No other product/test files are released. No Entity/Repository/query/migration/config/Workflow/Gateway/Skill/Orchestrator/procurement/supplier edits. Root alone wires the new exact test selector in .github/workflows/java-gate-pr.yml without changing assertions, routing, resources or workflow policy. Root alone edits ACTIVE/archive. Original QA exclusively owns phone/browser/canonical CSV.

The additional PG class may narrowly reuse ProductProcessWorkflowRuntimePostgresIntegrationTest's existing DataJpaTest/DynamicPropertySource/DisposablePostgresTargetGuard skeleton. Critical Plan/Report/Correction reads must be real JPA against the owned disposable PG, with persisted/flush/clear isLocked=true or approved+settled=true, no private-guard-only call. Only external WorkflowEngineService/WipInventoryService may be explicit mocks; assert no prohibited transition/application. Use real submit/applyWorkflowAction entrypoints and before/after persisted row/count equality. Separate locked-unsettled and settled-unlocked vectors so each error is attributable. Preserve valid unlocked behavior.

Approval must reject a report settled after submit before workflowEngine.transitionNode, with the existing settlement-race error and the later apply recheck retained. Read-only review confirmed that current source checks settled only in applyApproved after transition; use a precise red test before any such source adjustment. Do not broaden permission or create production fixtures.

Root owns starting/stopping only its disposable PG13.23 instance at 55329, using the new database cretas_workflow_verify_s305_lock_20261023; the previous final-build database remains unchanged. Private runtime credentials stay outside Git. No shared WSL/Docker changes. The PG test remains opt-in and final PG lane must explicitly execute it. Never set failIfNoTests=false or count a skipped opt-in as pass.

Independent review identified an approval-retry regression in the new early guard: an already applied correction could be rejected after subsequent settlement/locking. Preserve the factory-scoped correction lookup and workflow-ID check, then make the same APPROVE of APPROVED_APPLIED return without transitions or business writes. Keep REJECT recovery unchanged. The settled PostgreSQL vectors must persist both APPROVED and settled=true. The real OUTPUT fixture covers locked/settled submit and approval independently, plus an unlocked control; compare complete persisted Plan/Report/Correction rows and counts around each rejection. External Workflow/WIP mocks are explicitly not production execution evidence.

## Target validation and receipt

Use native JDK21/Maven3.9.9, sequential Maven within this worktree, normal main compilation. Target selector:

`mvn.cmd -B -DskipTests=false -Dmaven.test.failure.ignore=false -Dtest=ProductionReportCorrectionServiceContractTest test`

Run from backend/java/cretas-api. Use the existing project selector/include helper if needed; no main.skip/manual javac/skipped assertions. First prove the real service's explicit-lock branch red on the published source, then green after the minimal fix. Verify no correction/workflow writes on rejected submit and no report/task/WIP application on rejected approval; include a lock acquired after initial submit. Existing unlocked positive behavior and settled/terminal negative tests remain intact. Same-content valid evidence is reused.

Private implementation evidence: C:/Users/Steve/Documents/cretas-optimization-20261019/execution/coordination/s305-explicit-plan-lock-implementation-20261023/.

Worker returns task ID, Base SHA, exact actual files/diff, red/green commands and exit codes, test inventory/logs, local commit, blockers and SAFE_CODE_OWNER_RELEASED. Local commit only, no push/PR/deployment or production/API/SQL writes. Root handles review, final applicable full/JPA/PostgreSQL checks, merge, official release and original QA signoff. Source/CI/release never auto-sign the original locked-state business criterion.

## Frozen implementation and ownership disposition

The code Owner returned SAFE_CODE_OWNER_RELEASED after commits 1876d2b695, b1981f66cc and e636036585. The explicit-lock/settlement guards and applied-APPROVE no-op preserve tenant lookup, workflow-ID mismatch and REJECT handling. Native JDK21/Maven target evidence records 23 service tests and five isolated PostgreSQL tests with zero failures/errors/skips. Original failing and mutation logs remain in the private evidence directory. The PostgreSQL cases use an explicit OUTPUT fixture and complete persisted row snapshots. Root adds the independent approval_status=APPROVED field to the two settled cases; that exact final test content must execute in the frozen official build and applicable CI lane before delivery.

Only the registered three source/test files and Root's single PostgreSQL selector append were changed. No Entity, Repository, query, migration, permission, runtime configuration or public interface changed. The code-writing reservation is released; Root retains the qualified source/release worktrees in the host ownership registry through integration, manual merge and exact-main deployment. No other Owner's reservation is released. Final build/checks, merged SHA, production receipt and original QA judgment are separate pending facts, tracked in private structured receipts. S3-05 remains FAIL until original QA evaluates the retained real UI/independent SQL roundtrip and exact locked/settled refusal evidence; no new production fixture or replay of the six completed mutations is authorized here.
