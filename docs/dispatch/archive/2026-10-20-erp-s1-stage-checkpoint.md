# ERP S1 acceptance checkpoint and next bounded repair

- Parent task: `ERP-STAGES-20261019`; status `in-progress`.
- Initial base: `d58fc9c03003118d0d749583c3b89281c41e1413`.
- Source coordinator: `01a1523e-881c-7a92-b7ab-f4b8c5a552df`.
- Next coordinator: existing, actually verified Chat `01a15272-374d-72c0-bb20-cdfa0bd823f1`.
- Canonical CSV/browser/device QA remains `01a15272-36e3-76c0-a6a1-977df6dba052`.

This checkpoint transfers neither a running transaction nor an unfinished test/build/deployment. The implementation Owner and original QA have exited safely. The next Chat completed read-only preflight with the expected worktree, branch, main, and actual thread ID. Its repository execution remains held until this documentation PR, required checks, registry, final worktree readback, and release handshake are complete. The source coordinator then stops writing the transferred scope. The local handoff registry retains protection; this is not cleanup authorization.

## Completed evidence

S1-05 implementation PR #1057 was normally squash merged as `caedcd589f68d0649708a3cefde23f25700e3474`; documentation PR #1062 was merged as the initial base above. Quantity, supplier, material, action, tool, and execution-mode changes were independently rejected with actual committed database assertions. The legal original proof produced one draft, and replay produced no duplicate. Target tests: 47 passed; PostgreSQL 13 and 17: 26 passed each, including six new boundary cases each, no failures/errors/skips. Authorization/reference adapters are fixtures; this is not a claim of six new production UI negatives.

The single final Full run [39407065608](https://github.com/cretasfuture-ai/cretas/actions/runs/39407065608) had 16,168 passed and 119 original skipped unique cases. All 16,281 previous case IDs/states remained; exactly six new cases passed. No original assertions/cases/skips were removed or relabeled. Five genuine same-head integration checks passed; the conditional caller skip is not the real Full. Temporary witness PR #1061 was closed without merging. Historical old-base Ops failure remains recorded. Protection/ruleset API denial remains an evidence limit; ordinary immediate merge acceptance does not independently prove every protection configuration.

The original QA independently accepted S1-05 under its original criteria. Product `src/main` and `pom.xml` remained equal to the deployed backend, and the test content remained equal after integration. This batch changed tests/docs only and required no deployment. S1-06 separately passed real original OA UI actions, negative input/self-approval boundaries, independent finance approval, and SQL readback. Neither CI nor deployment was substituted for business acceptance.

## Remaining original criteria

Canonical state after the next actual QA wave: 75 scenes, 9 passed / 2 failed / 64 untested; 18 defects, 16 closed / 2 open. Original FAIL history is retained.

- S1-07: real natural preview-token expiry and old-proof refusal passed as subcases. Refusal was DTO `@Future`, not grant/permission revocation. Confirmed pending grant, Tool/Skill/scheduled entry, and administrator current-authorization coverage remain incomplete. Current one-shot confirmation issues and consumes a grant in one transaction; do not manufacture a pending production fixture or rename token expiry to close this criterion.
- S1-02: missing-line UI validation and unavailable-generation server refusal were observed with unchanged resource/plan rows. An existing active line/capacity means the no-confirmed-resource premise was not proved. No resource was deleted, zeroed, or fabricated for acceptance.
- S1-10: six browser-local capability faults across two real pages kept automation disabled and were restored to actual responses. This is not a production service outage or Python/model-failure acceptance. Model exception and business-infeasible response provenance remain incomplete. Seven relevant classes in the frozen current Full had 44 actual passed cases; the old S0 total 35 is not reused as the current count.
- S1-03 remains blocked on the actual dispatcher login/role UI; no identity borrowing or reset. S0 physical recipe/packaging/process/shift/capacity and human ROI facts are not invented. No new user material is needed for the queued UI repair below.

## Queued repair: ERP-S1-SUPPLIER-ENTRY-20261020

- Status: `queued` until the actual next Owner is released.
- Owner: next coordinator above; DIRECT execution is appropriate for this bounded reversible repair. Any delegated code worker requires its own registered scope first.
- Base: initial base above. Recheck actual main and all file owners before writing.
- Defect: original QA runtime `ERP-S1-SUPPLIER-ENTRY-001`, S1-04.
- Allowed product files: `web-admin/src/views/workdesk/PurchaserWorkdesk.vue`; new `web-admin/src/views/workdesk/__tests__/PurchaserWorkdesk.queryMode.spec.ts`.
- Bookkeeping: coordinator only may update this task's ACTIVE row and dated archive. Original QA alone writes canonical CSV, browser, and device evidence.
- Forbidden: backend, intent registration or production configuration, Entity/Repository/migration/Security, quote service, pricing logic, source fixtures in production, shared runner/release scripts, dependency/lockfile churn, other Owner files.

The real normal send click submitted a custom supplier-comparison query with `PURCHASER_WEEKLY_PLAN`, returning missing intent configuration before reaching the comparison tool. Source shows `@click="sendQuery"` and `@keydown.enter.ctrl="sendQuery"` pass event objects into `sendQuery(autoTrigger = false)`. The event is truthy and selects the automatic weekly-plan branch. Function-body inspection alone missed this mounted event path. Preserve the observed runtime failure, and verify the cause with a real mounted event regression before changing it.

The narrow fix should explicitly select manual mode for both user event paths while preserving explicit automatic `triggerWeeklyPlan()` behavior. The current supplier tool remains honestly unavailable without real quotations; do not fabricate lowest-price output or seed a missing default intent merely to hide this defect. Default weekly-plan configuration is a separate boundary if it still fails after the manual path is repaired.

Acceptance commands, with cwd `web-admin`:

- `npx vitest run src/views/workdesk/__tests__/PurchaserWorkdesk.queryMode.spec.ts`: real button MouseEvent and Ctrl+Enter KeyboardEvent must reproduce red before repair; manual calls preserve input and omit forced intent. A stub that drops the event cannot prove this regression. Verify explicit automatic/default action still carries its intended code.
- `npx vue-tsc --noEmit`: required type check.
- At final frozen product/test content, one necessary full `npm test` and Web build/required CI, reusing exact-content evidence rather than duplicating local and CI Full. Do not run Java Full for a Vue-only change.
- Applicable normal PR/main and deployment gates remain. Deploy only after required checks and ordinary merge, through the project release skill and exact-main entry.
- Original QA then repeats only S1-04's affected real normal UI paths and independent SQL checks. A supplier-unavailable response is an honest capability result, not a lowest-price success. New issue or routing gaps remain FAIL/unknown; do not weaken the original criterion.

## Resource and evidence boundaries

The two local CI lanes remain Aurora 4 CPU/8 GiB for one heavy Java job and Windows-light 3 CPU/6 GiB/swap 0 without Java routing. Billing restoration is not needed. No foreign jobs were canceled, no retired runner restored, and shared WSL/Docker were not restarted. Full queue/execution/total seconds changed 460/3881/4341 to 184/3882/4066 with six additional cases; execution did not improve. Max single-process RSS changed 3,617,524 to 4,005,436 kB; this is not VM peak memory. DB initialization remains nested/unknown; no test grouping was implemented or credited with speedup.

Private raw SQL, screenshots, receipts, canonical CSV, immutable inventories and performance evidence remain under `C:/Users/Steve/Documents/cretas-optimization-20261019/`. Read `execution/coordination/CURRENT.md`, `execution/s1-scope-negatives-20261020/implementation-receipt.json`, original QA receipts for S1-05/06/07, and `execution/s1-honesty-20261020/receipt.json`. Credentials and business row contents are not copied into this archive. The local registry must be updated and read back before ownership becomes `KEEP_ACTIVE`; until then, the next Chat is preflight only.
