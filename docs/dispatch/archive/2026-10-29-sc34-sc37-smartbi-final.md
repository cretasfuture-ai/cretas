# SmartBI F006 final Web and purchase/WIP batch (2026-10-29)

Base: `0655f609d365278261a3f5980e1ccd4479170f85`. Owner: Root.
Task IDs: SC34-QI-ITEM-IDENTITY-20261029, SC35-SCALE-UNITS-20261029,
SC36-PURCHASE-INVENTORY-20261029, SC37-F006-RECEIVING-20261029.

## Implementation and checks

- SC34: quality fixture and production assertion use the actual product-standard item and binding IDs. F006 batch 11003 form displays the readable standard title and required temperature range 0–4°C. Form was cancelled; zero business writes.
- SC35: 100 stored products are selected at the first, middle and last positions. Each produces one joint family; exact raw product, source, unit and five detail rows are checked. The 500-row original upload is paged twice with 100 distinct rows. Mixed kg/盒/袋 units are retained.
- SC36: preserve the required confirmed-receipt chart; append optional procurement/current batch inventory analysis. The four quantities are confirmed input, used, reserved and available. Reciprocal receipt/batch links, tenant, status, unique batch and units are mandatory. Current balances are explicitly distinguished from historical closing inventory.
- Details without price permission use fixed source/family quantity contracts. Local and shared PostgreSQL details isolate kg and 箱 for purchase and WIP; no amount/cost fields are exposed. The old WIP unit-filter bypass is removed.
- Source frozen Python factory gate: `sc28-python-final-sc36-final-v1`: **469 passed, 1 existing independent-schema skip**, exit 0, source hashes unchanged. Purchase focused set: 61 passed; WIP mixed-unit local/shared: 8 passed. Exact-source risk review PASS. Canonical JavaScript unit/drift set: **99 passed**; quality browser fixtures 4 passed, scale fixture 1 passed. Existing source production-schema EXPLAIN supplemented with the frozen purchase/WIP queries; no business rows or writes.

## Actual Web evidence

Private evidence root: `.playwright-mcp/smartbi-complete-20261028` in the main workspace.

- `web-final-quality-standard-v3-20261029/report.json`: PASS, zero business writes, blocked attempts, HTTP, console and response-body errors.
- `web-final-scale-v3-20261029/report.json`: PASS with the same zero-error/zero-write checks.
- These Java/Web checks use the deployed SC30 fixes included in main `39dc717deebb40be8709addad876580b4f37e1e6`. Complete release receipt: `cretas-1793246732-28072.json`. Root reused that completed shared release rather than redeploying unchanged Java/Web.

## Receiving exception and remaining acceptance

SC37 verified live F006 admin identity, exact approved purchase parent and one draft receipt before upload. A clearly labelled QA-only PNG was selected through the normal receiving dialog. The grant returned 200, but the object PUT was aborted before send by the task guard and surfaced as `ERR_BLOCKED_BY_CLIENT.Inspector`. This is a tooling failure without an OSS service verdict; no policy, URL or transport workaround was attempted. Fresh independent SQL confirms attachment=0, material batch=0, original 1 kg draft unchanged. No receipt confirmation was sent.

Purchase-stock positive acceptance therefore remains conditional on qualified confirmed source data; this batch does not invent attachment metadata or confirm a document without its real prerequisite. WIP flow production UI remains to be checked after the Python deployment. Native App acceptance is explicitly deferred by the user, including Android/iOS and native exports. No current-month close, historical reversal, migration, Entity/Repository, Java or permissions change is included.

The four scope locks are released by this final batch commit. Merge, production Python deployment and final catalog/AI Web acceptance remain separate facts, recorded in the private scenario ledger and deployment receipts when actually completed.
