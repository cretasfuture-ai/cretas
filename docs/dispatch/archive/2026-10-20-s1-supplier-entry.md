# S1 supplier query manual-entry repair

- Task: `ERP-S1-SUPPLIER-ENTRY-20261020`; parent `ERP-STAGES-20261019`.
- Defect: `ERP-S1-SUPPLIER-ENTRY-001`, original QA S1-04.
- Owner: `01a15272-374d-72c0-bb20-cdfa0bd823f1`; original QA remains `01a15272-36e3-76c0-a6a1-977df6dba052`.
- Registered Base: `d58fc9c03003118d0d749583c3b89281c41e1413`; execution starts at checkpoint main `1654ba6a674f4e0c3438f284679f665ca2ec1ca6` on new branch `codex/erp-s1-supplier-entry-20261020`.
- Ownership: actual thread/worktree/branch/main and SAFE original QA read back; explicit release and registry KEEP_ACTIVE verified before repository writes. Source coordinator no longer writes this scope.

## Behavior and scope

The actual button MouseEvent and Ctrl+Enter KeyboardEvent entered `sendQuery(autoTrigger = false)` as truthy arguments. Manual supplier questions therefore forced `PURCHASER_WEEKLY_PLAN` and also requested sales forecasts. Both template bindings now explicitly call `sendQuery(false)`. Automatic mount and explicit reload still call `triggerWeeklyPlan()` and preserve the weekly-plan and sales-forecast requests.

Only `PurchaserWorkdesk.vue` and its new `__tests__/PurchaserWorkdesk.queryMode.spec.ts` change product/test content. The regression mounts the actual component with real Element Plus input/button components and checks the exact outbound payload and call count. Request/auth boundaries are mocked; this is not live supplier-tool or business acceptance. No backend/configuration/quote/permission/dependency/runner/release-script changes.

## Validation and delivery

With cwd `web-admin`, `npx vitest run src/views/workdesk/__tests__/PurchaserWorkdesk.queryMode.spec.ts`:

- Original: 2 manual-event failures, 2 controls passed.
- Fixed: 4 passed.
- Mutant restoring both original bindings: the same 2 manual-event failures, 2 controls passed.
- Restored fix: 4 passed, unchanged test hash.

`npx vue-tsc --noEmit` exited 0. Root tsconfig uses project references; the complete `npx vue-tsc -b --force` check separately passed in the PR Web Gate. That single full Web run had 6,174 passed / 5 skipped tests, 778 passed / 1 skipped files, with zero failures. Run `39423316450`, job `118629484021`, on `windows-light-cretas`, is bound to candidate `0c8f5168050764793a51e25efd6cfc898504b870`. Four PR checks succeeded. No local full test run or Java test/build was added.

PR [#1064](https://github.com/cretasfuture-ai/cretas/pull/1064) was ordinarily squash merged at exact candidate head as `ca589d38680f718374aaad8fbb538931232948d1`; auto/admin/force merge were not used. Candidate and merged content matched. The unified release built the candidate Web artifact once, then deployed from clean exact main, reusing its manifest. Deploy exited 0 with exactly one `RELEASE_FINAL_STATUS=deployed`, Web entry HTTP 200 and matching four-way index hashes. Java was neither rebuilt nor deployed. The deploy took 24 seconds; this is not a general performance claim.

Private logs and hash-bound receipts: `execution/s1-supplier-entry-20261020/` under the existing private evidence root. Initial sandbox/tool failures and original QA FAIL remain retained. `release-deploy-receipt.json` and `qa-after/receipt.json` separately record service delivery and business acceptance. Documentation-only closeout does not trigger another product test or release.

## Original QA result and released scope

The same original QA was resumed through the existing official CLI only after prior SAFE/exit 0, process uniqueness and successful release checks. It independently executed the real normal button click and Ctrl+Enter paths, preserved the original query, observed no forced weekly intent and no extra sales forecast, and read back 14 business count/hash controls unchanged after each path. Business writes were zero. Browser/routes were closed, pending business requests/transactions were zero, and the QA process exited 0.

**S1-04 remains FAIL and `ERP-S1-SUPPLIER-ENTRY-001` remains OPEN.** Both queries were recognized as `SUPPLIER_RANKING`, then refused with `NO_PERMISSION` for the original purchasing role. No comparison tool result, tool name or request ID was provided; scoped audit readback supplied no tool invocation record. The frontend event subcause is fixed, but the original criterion requiring the correct tool/capability explanation has not passed. `SUPPLIER_PRICE_COMPARISON_UNAVAILABLE` was not observed; no lowest-price or real quote success is claimed. Natural default-weekly missing configuration remains a separate unchanged limitation.

Canonical totals stay 75 scenes: 9 passed / 2 failed / 64 untested; 18 defects: 16 closed / 2 open. Original FAIL fields/evidence and other rows are preserved by QA. S0/S1 remain incomplete, with no S2 or ROI acceptance claim.

This bounded frontend implementation scope is complete/merged and released by this closeout; parent coordination and the open business defect remain. No backend write scope is acquired. The next narrow investigation should trace `IntentKnowledgeBase.java:4706-4708` and `IntentExecutionOrchestrator.java:2808-2815` against the real comparison request and authoritative required-role/tool mapping before registering any further implementation scope. It must preserve the original buyer identity, tenant guard and no-fabricated-quote behavior; switching to an administrator or globally broadening permissions is not acceptance.
