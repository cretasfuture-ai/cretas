# ERP S1 task-grant PostgreSQL CI wiring

Task `ERP-S1-GRANT-CI-20261020`, status `in-progress`; Owner `/root` DIRECT. Base `6556994ed9658e719ca6786a6548e63f8998f0f7`, worktree `C:/Users/Steve/cretas-erp-s1-task-grants-20261020`, branch `codex/erp-s1-task-grants-20261020`.

Exclusive source scope: `.github/workflows/java-gate-pr.yml`, only the existing isolated PostgreSQL Maven selector. Before-edit blob `286e3e6637536bc5ad17e9b7d68a4fb277db3d62`. Add the two exact new test classes from the separately owned six-file core task: `AiPurchaseTaskGrantServiceContractTest,AiPurchaseTaskGrantRepositoryQueryValidationTest`. Existing17 test classes, PostgreSQL wrapper, runner labels, triggers, permissions and command options are preserved. Root alone edits dispatch; no code child may touch this workflow.

Why: the ordinary PR name selector covers both classes on H2, but the actual PostgreSQL job has a fixed list that omits both. A green unmodified PG job cannot establish the grant's migration/concurrency/atomicity behavior. This change wires the actual required database lane without altering infrastructure.

Acceptance: parse workflow YAML and assert the unique PostgreSQL test selector retains its existing17 classes and adds exactly those two once; `git diff --check`; `python scripts/ci/check_dispatch_active.py`. Actual final-commit PR PG execution and named JPA gate remain separately pending until core source/tests freeze; do not call a syntax/selector assertion CI success. No local Maven from root while `/root/grant_core` owns that target. No CI run, runner modification, merge or deployment in this direct source slice.

Final frozen selector semantic validation PASS: all17 original classes retained in order, exactly two new classes appended once, all other workflow semantics unchanged. Source scope released for final candidate; root owns subsequent actual CI/PR/merge via parent ERP-STAGES. Real PostgreSQL12/0/0/0 and complete baseline/Flyway/primaryvalidate passed separately; actual PR logs must still prove both new classes executed on realPG and every required job succeeded. No runner labels, sharding, test deletion, assertion/skip changes or weaker aggregate gate.
