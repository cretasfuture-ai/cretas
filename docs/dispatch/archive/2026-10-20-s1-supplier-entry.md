# S1 supplier query manual-entry repair

- Task: `ERP-S1-SUPPLIER-ENTRY-20261020`; parent `ERP-STAGES-20261019`.
- Defect: `ERP-S1-SUPPLIER-ENTRY-001`, original QA S1-04.
- Owner: `01a15272-374d-72c0-bb20-cdfa0bd823f1`; original QA remains `01a15272-36e3-76c0-a6a1-977df6dba052`.
- Registered Base: `d58fc9c03003118d0d749583c3b89281c41e1413`; execution starts at checkpoint main `1654ba6a674f4e0c3438f284679f665ca2ec1ca6` on new branch `codex/erp-s1-supplier-entry-20261020`.
- Ownership: actual thread/worktree/branch/main and SAFE original QA read back; explicit release and registry KEEP_ACTIVE verified before repository writes. Source coordinator no longer writes this scope.

## Behavior and scope

The actual button MouseEvent and Ctrl+Enter KeyboardEvent entered `sendQuery(autoTrigger = false)` as truthy arguments. Manual supplier questions therefore forced `PURCHASER_WEEKLY_PLAN` and also requested sales forecasts. Both template bindings now explicitly call `sendQuery(false)`. Automatic mount and explicit reload still call `triggerWeeklyPlan()` and preserve the weekly-plan and sales-forecast requests.

Only `PurchaserWorkdesk.vue` and its new `__tests__/PurchaserWorkdesk.queryMode.spec.ts` change product/test content. The regression mounts the actual component with real Element Plus input/button components and checks the exact outbound payload and call count. Request/auth boundaries are mocked; this is not live supplier-tool or business acceptance. No backend/configuration/quote/permission/dependency/runner/release-script changes.

## Validation and pending delivery

With cwd `web-admin`, `npx vitest run src/views/workdesk/__tests__/PurchaserWorkdesk.queryMode.spec.ts`:

- Original: 2 manual-event failures, 2 controls passed.
- Fixed: 4 passed.
- Mutant restoring both original bindings: the same 2 manual-event failures, 2 controls passed.
- Restored fix: 4 passed, unchanged test hash.

`npx vue-tsc --noEmit` exited 0. Root tsconfig uses project references; the required CI project build-mode type check remains necessary and is not replaced by this command. Frozen-content full Web tests will run once in the Web Admin Gate, rather than repeating a local Full. Web artifact build uses the unified release entry; Java tests/builds are not applicable to this Vue-only change.

Private logs and hash-bound receipts: `execution/s1-supplier-entry-20261020/` under the existing private evidence root. Initial sandbox/tool failures and original QA FAIL remain retained. PR/checks, ordinary merge, production release and original QA are separate pending facts at this commit.

Original QA alone will repeat S1-04 after deployment: normal click and Ctrl+Enter must reach the supplier comparison tool, with independent database controls and no business writes. `SUPPLIER_PRICE_COMPARISON_UNAVAILABLE` must remain an honest no-quote capability result; it is not lowest-price success. Default weekly-plan missing configuration, if still present, is separate and is not patched here. No full-S1/S2/ROI acceptance claim.
