# ERP-S0-ROLE-001-FIX-20261019

Base SHA: dc1ce76c20dc9e8e06d876e70ffdaa4eb3ca5ead

Code coordinator: 01a1538b-d2a6-7be1-92fa-900b47919fb4. Scope and GO read back from private role-fix-scope.json. QA owns its separate browser, business data and CSV; this task must not modify them.

## Scope and ownership

- ERP-S0-ROLE-001-TELEMETRY-20261019; Owner telemetry executor; Base as above. Exclusive files: web-admin/src/utils/clientErrorReporter.ts and web-admin/src/utils/__tests__/clientErrorReporter.spec.ts. Acceptance: npx vitest run src/utils/__tests__/clientErrorReporter.spec.ts (coordinator runs serially after dependency installation). Strict boolean readonly marker only; normal reporting and server audit preserved. No other files, tests/builds, commit or deployment by executor.
- Coordinator owns plans/list.vue, productionPlanCustomerReadAccess.ts and the two scoped plan tests; ACTIVE/archive and private receipts. Acceptance: scoped Vitest, vue-tsc, permission-risk review, applicable hosted gates, manual merge, official exact-main Web release and same-role readonly acceptance.

Private evidence: C:/Users/Steve/Documents/cretas-optimization-20261019/execution/S0-role-fix. Original FAIL evidence remains unchanged. No backend, policy, harness guard or private CSV edits authorized in this slice.

## Reviewed delivery

Status: review. This archive and ACTIVE removal release the code scope when this commit merges; actual merge/release/acceptance state is recorded separately in the private receipt and GitHub, not inferred from this file.

- Customer reads wait for the authoritative effective permission snapshot and require sales/finance r or rw. No role blacklist, UI GRANT substitution, backend permission expansion or silent authorized API failure.
- The real plan page shows permission/pending/loading/error/empty states and retains authorized customer loading. A batched watcher and final identity/access check avoid the reviewed account-switch race.
- Optional client telemetry stops only for the strict boolean readonly marker; normal modes, Vue local handler, shared request failure handling and server audit remain intact. Suppressed events do not consume normal dedup quota.
- Independent permission-risk review: initial P1/P2 findings preserved privately and fixed; final source disposition PASS. No JPA/Entity/Repository/migration changes.
- Frozen validation: five scoped/neighbor Vitest files, 40 passed; vue-tsc --noEmit exit 0. Two deliberate mutants caused 5 failures (exit 1), original source hashes restored, then 40 tests/typecheck passed again.
- Canonical readonly harness prerequisites: 39 unit tests, 12 local fixture tests and dry-run passed. The first fixture launcher failed to resolve the upper-level Playwright dependency; the rerun used this isolated worktree's own installation.
- Hosted gates, manual PR merge, exact-main official Web-only release and same-role live readonly acceptance remain separate pending facts at candidate creation. QA transaction/UI safety acknowledgment is required before release. No native/Python/Java deployment is implied.
