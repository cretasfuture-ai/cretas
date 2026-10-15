# Platform assembly: BOM / Workflow publication and production UI closure

Task `PLATFORM-BOM-WORKFLOW-UI-20261015`, Owner root, initial main base `6fb3d66f322aa563de98c2a4d6fb496e98a5044e`. This is a nonproduction, synthetic-factory acceptance record. Platform-only continuation changes are separate from shared production repairs in PR #995. Production was not accessed or changed; default-off/platform-only assembly access remains unchanged.

## Delivered platform behavior

- Applying factory configuration does not publish its Workflow. The applied blueprint now keeps the platform operator on the assembly screen and permits only BOM/Workflow draft continuation in PUBLISHED/APPLIED states; other capabilities retain their existing lifecycle gates.
- Explicit raw-to-product conversion facts expose BOM intent without inventing auxiliary-material facts. BOM output remains one finished basic unit, and line yields remain 100.
- Workflow materialization records platform actors in typed columns with the legacy factory-user FK null. Merge and snapshot/replace pass the exact current server-owned ID, definition version and lock version to the unchanged strict business service.
- Formal execution revalidates the approved Workflow candidates against current blueprint identity/version/source, rather than reconstructing an empty candidate list and rejecting an applied blueprint. Current draft, pin, actor, approval and replay guards remain in force.

## Actual UI acceptance

One existing Vite/Java/native PostgreSQL test environment was reused. The database was already migrated through V20261103.63; no migration files or versions changed. Only loopback test services were used.

| Boundary | Evidence / observed result |
| --- | --- |
| Applied blueprint → production drafts | Platform UI creates/merges Workflow and BOM after application; no factory-role canvas redirect or authority drift failure |
| Prerequisites | Actual SKU UI sets 160g per finished unit; material UI creates counted food bags; normal pre-apply packaging draft fixes packaging lineage; warehouse UI configures production/default warehouses and the required logistics warehouse |
| BOM promotion | Three-line formal BOM version 2: each 1 个 output uses flour 0.1kg, water 0.06kg, bag 1 个; readback retains Workflow revision 2 and port identities |
| Independent publication | Platform requester and separate platform approver confirm the same plan; final receipt ACTIVATED and verified runtime ACTIVE; typed platform audit, legacy user FK null |
| Negative controls | Missing net content blocks promotion; changed product pin blocks stale draft; missing packaging blocks publication with zero activation writes; self-approval and approver execution are rejected; before publication the production plan confirm button is disabled |
| Actual production | Factory UI creates an independent stock-production plan, generates and confirms a three-material requisition, transfers and receives it, reports dough 1.6kg and bread 10 个 through both process steps |
| Units and single allocation | Baking request carries main input 1.6kg plus separately counted bags; response contains exactly one bag allocation of 10, not a second automatic BOM allocation |
| Settlement and closure | Actual UI production summary succeeds; database asserts four posted consumption rows (flour 1kg, water 0.6kg, bags 10 个, upstream dough 1.6kg), exact source-batch used quantities, and one AVAILABLE finished batch of 10 个; UI stops/closes the plan |
| Inventory UI | Warehouse inventory batch view shows the resulting finished batch: produced 10, shipped 0, reserved 0, available 10, unit 个, status 可用 |
| Existing factory | Independent before/after fingerprint: 504 factory-scoped tables unchanged, changedTables empty |

The final positive plan is synthetic `1161c49d-1015-49f9-8121-5aa4cf03f525`. It pins formal BOM `c0da2f5b-8ab8-450b-8bd8-a663dd021552` version 2 and Workflow revision 2/hash `015d452e739d332776e8a76aea98f3184bfd2f8b275a04e1d09a17c05bdc6e49`. The resulting finished batch is `FG-PLAN-1792080619017-37E9EF5E-S1-c08ecccd`. Final plan state is COMPLETED.

Opening material stock and test login accounts were explicit synthetic fixtures, not procurement/receiving UI acceptance. Business reports, requisitions, transfers, publication, settlement and closure used the real mounted UI; no direct SQL business-state repair was performed.

## Verification and evidence reuse

- Platform Java target classes: continuation 12, raw-recipe intent 1, approved execution 3, actor/edit identity 4; all passed. Edit identity regression: 2 assertion failures/0 errors before fix, then 4/4 passed. Platform ledger regression also reproduced before correction.
- Platform mounted panel/continuation tests: 33 passed; assembly surface: 22 passed. Continuation visibility initially had 2 assertion failures, then passed.
- Shared PR #995: actor-family plus existing BOM service controls 24 passed; final process requirements/unit target selection 31 passed, including packaging duplication red 1 failure/0 errors → green 9/9 in that class. Mixed-input card/table cases red 2 → green; related process-sheet scope 197 passed across 30 files, utility/context selection 38 passed.
- Frozen Web `npm run build:check` passed. An earlier successful build was not reused after the later process-sheet source change; the final build covers that change. No unchanged local full-suite reruns were used to manufacture evidence.
- `python -m pytest scripts/tests -q`: 16 passed, 2 opt-in PostgreSQL-wrapper skips. Existing native PostgreSQL lifecycle/migration evidence was reused because scripts and migrations did not change. Dispatch gate and diff whitespace checks passed.
- Independent scoped security/data-integrity reviews passed after concrete findings were corrected. Exact final PR CI results remain independent of local target-test evidence and must be checked before manual merge; auto merge is prohibited.
- Shared PR #995 was manually squash-merged as `2817ca2c87e549c375d82e5583a6fb8c4815570a`. Its final head `04b170e1552747248d88c4dcdb0df87b2b3cfeb6` passed Java Gate run `38771184344`: 15110 tests, zero failures, empty baseline, ratchet success, PostgreSQL concurrency success; all other PR checks passed. Earlier five real failures were confined to a stale platform-token mock and missing actor columns in the R10 test schema; focused verification passed 12/12 before the final full gate. They were not hosted billing failures. No production implementation or migration was changed to repair those fixtures.
- Platform-only source and related test blobs remain identical to the accepted UI head `d1cb58325b60b9d1615dc61553f1614c1cb079c6` after rebasing onto this merged main; 26 blobs verified by private `source-evidence-map-*.json` receipts. No UI or local product full-suite repetition is needed for that parent/docs change. The platform PR still requires its own final merged-result CI.

Private evidence is retained under `Documents/assembly-integration-assessment-20261015/target-ui/`: `workflow-active-receipt.png`, `bom-packaging-formal-promoted.png`, negative-control screenshots, `frozen-positive-production-reports.png`, `frozen-positive-plan-closed.png`, `frozen-positive-finished-inventory.png`, source database readbacks and `frozen-production-acceptance.json`. Final closed database readback SHA-256: `77d1e673525aee379b60ca64c924f39bc59f6011770f7af09ae2d3c26a833b65`. Raw credentials/runtime files and screenshots are not committed.

## Known failure and limits

- A separate pre-fix synthetic plan (`e0096224-ad1f-4163-b520-89f65d5054be`) retains a failed reversal case. Reversal soft-deleted reports/rows but left unposted `material_consumptions`; later settlement correctly rejected the cumulative overdraw. That case is not counted as passed, was not repaired by direct SQL, and must not be reused as a positive sample. The reversal module was not expanded in this slice. Its investigation/repair needs its own scope and regression evidence.
- No production deployment, production feature enablement, existing-factory initialization, native-device acceptance, food-safety certification or shipping acceptance is claimed. Test cost settings were not configured; existing explicit default-hourly-cost warnings were retained. Warehouse prerequisites were configured through the UI, not claimed as automatic blueprint provisioning.
- Intermediate full-CI attempts were cancelled when genuine UI findings required new source changes. Only evidence matching the frozen final source/test content can justify merge; cancellation and hosted billing failures, if any, are separate from product test failures.
