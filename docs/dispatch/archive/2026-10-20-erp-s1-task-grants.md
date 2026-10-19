# ERP S1 task-grant core

Task `ERP-S1-GRANT-CORE-20261020`, status `claimed`, Base `6556994ed9658e719ca6786a6548e63f8998f0f7`.
Coordinator `/root`; code Owner `/root/grant_core`. Worktree `C:/Users/Steve/cretas-erp-s1-task-grants-20261020`, branch `codex/erp-s1-task-grants-20261020`.

This is a bounded implementation slice within S1. Existing S0 real-device/business retests run under the separate sole QA Owner. No S0 signoff, deployed capability, production migration or complete S1 acceptance is implied by this core.

## Exclusive code scope

Only these six NEW files may be written by the code Owner:

1. `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/ai/AiPurchaseTaskGrant.java`
2. `backend/java/cretas-api/src/main/java/com/cretas/aims/repository/ai/AiPurchaseTaskGrantRepository.java`
3. `backend/java/cretas-api/src/main/java/com/cretas/aims/service/intent/PurchaseTaskGrantService.java`
4. `backend/java/cretas-api/src/main/resources/db/flyway/V20261103_69__ai_purchase_task_grants.sql`
5. `backend/java/cretas-api/src/test/java/com/cretas/aims/service/intent/AiPurchaseTaskGrantServiceContractTest.java`
6. `backend/java/cretas-api/src/test/java/com/cretas/aims/repository/ai/AiPurchaseTaskGrantRepositoryQueryValidationTest.java`

No existing Tool/Gateway/Preview/PurchaseService, supplier module, permissions, UI, dependencies, CI, production or runtime changes. No child agents. Root alone edits ACTIVE/archive. Scope expansion requires a receipt before further edits; do not silently change a shared contract.

## Contract

Create a durable, tenant/user/tool/version/input-bound, 15-minute default, single-use task authorization for one `purchase_order_create` PurchaseOrder DRAFT. It must represent supplier/material/unit/quantity and amount caps, explicit input digest/version, expiry, revoke, consumed request/idempotency and resulting order ID. Identity comes from server-side caller wiring, never from opaque tool parameters; this core is not externally exposed yet. Same-factory/actor scope is checked again under the execution lock.

Pilot monetary decision: explicit CNY only, positive explicit unit price per kg, zero explicit tax, one material row, exact kg input, no conversion. Missing price/currency/unit authority fails closed; no historical estimates or zero-price fallback. The existing PurchaseOrder has no currency field: grant records the expressly confirmed CNY scope and linked DRAFT, not platform-wide multi-currency support. Wiring must later display and bind this exact scope before activation. Manual ERP behavior is outside this slice.

Issue/inspect/revoke and execute semantics must be narrow. Execution locks the grant in the same Spring transaction as existing `PurchaseService.createPurchaseOrder`; validates tenant/actor/tool/version/digest/supplier/material/unit/qty/amount/expiry/revocation and writes consumed/result state atomically. Failure rolls back both. Same-key/same-input successful replay may return only the recorded same-tenant draft; different keys/inputs cannot create another draft. Uncertain results never automatically create a second one. Revoke-versus-execute ordering must be deterministic under the same lock. Returned persisted amounts and resulting DRAFT are checked before commit, not after a separate transaction.

Core must not claim gateway, UI confirmation or policy admission. Real chat currently reaches the legacy direct executor; Workdesk and Skill are separate bypass paths. They must later be wired through the same grant or fail closed, using server-held token binding, before S1 can become available. Do not retrofit this by trusting client-supplied grant IDs.

## Validation ownership

Code Owner may run only target tests, sequentially in this worktree. Root retains aggregate CI/build, migration/review, integration, merge and release. Reuse existing repository test helpers and a private disposable PostgreSQL wrapper; never stop shared Docker/WSL/services/databases or borrow production/test credentials. No simultaneous Maven in this target directory.

Target selector:
`AiPurchaseTaskGrantServiceContractTest,AiPurchaseTaskGrantRepositoryQueryValidationTest`

Acceptance invocation from `backend/java/cretas-api`:
`mvn -B test -Dtest=AiPurchaseTaskGrantServiceContractTest,AiPurchaseTaskGrantRepositoryQueryValidationTest -Dcretas.testIncludes=**/*AiPurchaseTaskGrant*Test.java`

The compiler include is one glob; the original comma-valued include was attempted, selected no test classes and is preserved as a command configuration failure. The Surefire selector remains the two exact class names.

The actual JPA Context must start and exercise lock/query parsing. PostgreSQL evidence must cover one-shot persistence, cross-tenant/actor negatives, expiry/revoke/version/cap rejection, rollback, and concurrent execution/revoke as appropriate. H2/mock-only tests cannot establish PostgreSQL concurrency or complete production purchase integration. Report skipped/failed/unrun separately. If a local disposable PostgreSQL wrapper is unavailable, preserve exact attempted evidence and return that test pending; do not fabricate pass or change global runtime.

Retain commands, exact HEAD/content evidence, exit codes and relevant logs under private evidence. Return task/base/files/tests/commit/PR/blocker/next action. No commit, PR or deployment until root reviews final scope and integrated evidence.

## Revision2 after independent review

Initial six-file implementation produced7 target tests passing on PostgreSQL17:3 repository and4 service tests. The actual business PurchaseService was mocked, migration was not applied by Flyway, and this evidence does not prove purchase/grant atomicity. Original source hashes and test/tool failure logs remain private historical evidence. No commit/PR/runtime activation occurred.

Independent review requires changes before acceptance: real PurchaseService returns a header without populated item collection, so the initial returned-object check would reject valid orders. Keep existing protected purchase source unchanged; flush and scope-read the real header/items inside the grant transaction. Fix canonical tool admission and recompute a server-owned digest from a defensively copied complete approved/actual DTO before execute or replay. Explicitly reject unrepresentable mapped numeric precision/scale and verify database values after flush. Remove convenience implicit CNY/zero-tax defaults; pilot facts must be explicit.

The same Owner and exact six-file scope remain. Strengthen existing tests with the real PurchaseServiceImpl critical create method and real PostgreSQL orders/items, both-writes rollback, same-key/changed-body/key recovery, execute/execute and revoke/execute races, plus actual new migration application/constraint-index readback and ddl-auto=validate. Other unused collaborators may be mocked; do not mock the purchase create itself. No shared source modification or new file without scope expansion. Use revision2 evidence paths and bind final dirty content hashes to actual commands/results; do not call the original7 tests full integration.

Complete primary startup, all AI entry-point/token/gateway wiring, public UI and production activation remain separate later gates. This unexposed core must not be described as exact-input-bound, atomically purchase-validated or completeS1 until the relevant revised proofs actually pass.

## Native full-primary acceptance slice

Task `ERP-S1-PRIMARY-GATE-20261020`, Owner `/root/grant_core`, same Base/worktree. Six product files remain frozen at revision2 attempt05, real PostgreSQL12/0/0/0 and independent core review accepted; no commit/PR/merge/deployment for that core yet.

Only private files under `C:/Users/Steve/Documents/cretas-optimization-20261019/execution/s1-grant-core/primary-startup/` may be implemented: native-PG/Windows-Maven bridge helpers and exact per-attempt evidence. The Owner also exclusively uses this worktree's Maven target while running the gate. No tracked product/shared-script/config/CI modification, dependency installation, Docker/WSL/service restart, borrowed port/process/database or production I/O.

Reuse the existing ciworker disposable PostgreSQL wrapper, baseline replay semantics and real Spring profile from fresh-db-gate.sh. Probe PG vector availability without changing it; missing dependency is explicit prerequisite failure, not permission to install or switch shared databases. When available, replay exact recorded baseline with shape checks, apply the complete current Flyway history including newV69, then boot actual production PrimaryDataSourceConfig/primaryEntityManagerFactory with primary ddl-auto=validate. Secondary SmartBI, if required, is another database in the same owned isolated cluster. All datasource/Python/network settings must stay test/local; no real credentials. Reuse existing source already compiled where possible, separate build elapsed from startup elapsed; no product tests rerun just to package the app.

Acceptance command is the new private run-primary-gate-wsl.sh attempt-01 through the existing official wrapper. Bind six frozen hashes, actual cluster/port/PID, baseline shape/version, migration outcomes, primary EMF validation/startup and local health result; success requires all applicable phases, failure/timeout/unmeasured separately retained. Only the exact owned app PID/process tree may be terminated; capture migration/schema evidence before owned wrapper cleanup. No hibernate update/create for primary, grant-only EMF, swallowed schema failures or health-only replacement for schema evidence. No shared Maven/DB/ports; stop on failed prerequisite with a precise readback. Root reviews any resulting scope expansion separately.

## Frozen core acceptance and final-candidate scope release

Six files completed and frozen: revision2 attempt05 real PostgreSQL17.11 target tests12/0fail/0error/0skip, including real PurchaseServiceImpl create/independent readback, actual both-writes rollback, separate overlapping execute/execute and revoke/execute transactions. Source before/after hashes identical. Independent risk review accepted this unexposed core only, with no remaining P0/P1; no UI/gateway/token-policy admission is claimed.

Full-primary attempt08 now PASS: recorded production-shaped baseline502tables/2044indexes/181unique/227FK/8812columns replayed exactly; current Flyway applied44 incremental migrations with0failed through20261103.69. Actual PrimaryDataSourceConfig/primaryEMF started with ddl-auto=validate, Spring125.543s and loopbackhealth200. NativePG17.11/vector0.8.6; no dependency install/shared restart/production writes/external calls. Own app process tree stopped and disposable PG wrapper cleanup completed. Attempts01–07 are retained as classified bootstrap/Windows bridge failures, never counted as successful application gates. Private evidence primary-startup/primary-gate-receipt-20261020.json and final-core-review-20261020.md.

Root retains sole frozen-candidate source/PR/merge ownership. Implementation and private validation microtask locks are released in this candidate commit; parent ERP-STAGES remains active and captures pending actual final-commit CI/build/merge plus subsequent S1 integration. Initial Base6556994 and current integration maina8539649847199cadbb4bd11a2164a0822b98a25 have identical Java product tree9dd7d78c6dda1439bd5746a1e8723253bc6b0acf, so unchanged source-hash-bound proofs are reused. No S1 production migration/deployment, external endpoint, chat/Workdesk/Skill/gateway wiring or business signoff has occurred.

## Ordinary-lane preflight correction

ERP-S1-GRANT-PORTABLE-20261020, Owner grant_core, Base6556994. Root ordinary-lane preflight retained17tests/5errors/1skip: service reference-fixture INSERT ON CONFLICT is unsupported in H2, while GateSelectorCoverage4/FlywayUniqueness1 and repository ordinary3 passed; actual PG-only migration test was correctly unmeasured in H2 and already passed on PG. This is a test fixture portability defect found before CI, not a product failure or a passed final gate.

Exact sole code scope: src/test/java/com/cretas/aims/service/intent/AiPurchaseTaskGrantServiceContractTest.java, only four seedPurchaseReferences SQL statements may change to semantically idempotent portable SQL. No assertion, case, skip/assumption, production file, migration, workflow or shared configuration change. Private revision3 proof scripts/evidence under execution/s1-grant-core/ and sole owned target may be used; no concurrent Maven in this WT. Preserve final-preflight failure evidence. Acceptance: unchanged test method/negative/concurrency/rollback assertions; four seed statements only; realPG12/0/0/0 on new testhash; Root then reuses compiled classes for separate ordinary four-class preflight. Six product/migration files except this one test remain frozen; fullprimary proof stays valid for unchanged production source. No full-suite/primary rerun without new concrete concern; no skip or deleting assertions to make CI green. Structured receipt/SAFE and release to Root required.

Portability correction completed SAFE: four statements now INSERT...SELECT...WHERE NOT EXISTS, same fixture values and all methods/assertions preserved. Revision3 attempt02 realPG12/0/0/0; exact same testcase identities as revision2; final service-test SHA25678088c9b8cfa63d8b275c445a1c742ff66789ce0b064e47be1c542ed4d2ea60f, other five source hashes identical. Root ordinary H2 actual Maven success executed11 assertions with one existing PG-only migration assumption, separately covered by realPG. Unchanged GateSelectorCoverage4 and FlywayUniqueness1 passed earlier and are reused with clean source hash/version proof. A private preflight helper correctly rejected two stale XMLs after targeted compilation removed those classes; it is retained as instrument failure, not a false claim that four classes executed in one invocation. Detailed final-preflight-reconciled.json binds every reused/new proof. Only test-fixture SQL changed after primary08; unchanged production/migration full-primary evidence is reused.

Final candidate additionally carries separately owned root Java evidence instrumentation (archive2026-10-20-ci-java-evidence.md) so its already-required full suite produces durable resource/case data. One final-full-ready PR will cover frozen core, explicit19-class PG selector and instrumentation; do not launch a second full benchmark or weaken any gate. Core, fixture and private-validation source locks released; parent ERP-STAGES/Root retains PR/CI/merge and later integration. No production deployment is part of this unexposed foundation slice.
