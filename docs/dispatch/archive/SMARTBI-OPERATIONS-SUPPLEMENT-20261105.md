# SMARTBI-OPERATIONS-SUPPLEMENT-20261105

Base: `641eee12522a98e0a87d70240de69fa0da5d23f1`. Root is the sole write owner. Existing isolated Blacklake/六膳门 test tenant, loopback 8095 and draft PR #1390 only; original 8093, production Cretas services and original source snapshots stay unchanged. Scheduler enablement is outside this slice.

## Source findings

The official reporting query returns `stockUnit`. The preceding lineage probe's output minimization whitelist had omitted that field and `productName`; this was an integration omission, not evidence that the factory failed to enter reporting units. The whitelist now retains those business fields while excluding reporting personnel and authentication fields.

A bounded read-only supplement made 33 authenticated allowlisted source queries. It fetched the same 272 output IDs; every previously retained numeric, status, parent and timestamp field matched exactly. All 272 units were recovered, with no unresolved reporting-unit records. No finished-product unit or conversion factor was inferred.

The inventory scan checked 2,365 visible product/warehouse combinations against current definitions, in batches of at most 100. It returned 198 balance rows as collected on November 5, 15:38 Beijing time: 6 negative and 59 zero balances. Another 2,167 combinations returned no record; this does not imply zero stock, shortage, or a request to purchase every item.

## Projection and user guidance

The supplement validates tenant, source factory, baseline, parent observation, parent output fingerprint, full supplement fingerprint, collection times, unique source IDs and queried product/warehouse membership. It only fills verified output units/names; changed source quantities or approvals remain unresolved rather than being merged into older cost facts. Canonical supplementary content is included in source/run/cache identities.

The November 3 balance snapshot is retained separately. A period selects the newest collected snapshot inside its date range; snapshots are never summed or relabeled as a period closing balance. Full-view inventory uses the latest snapshot. The inventory UI defaults to that full latest view, while explicit historical dates continue to select saved historical balances.

Production and work-order cost facts retain their November 4 collection clock; restored units and fresh stock have their own November 5 clock. Period report wording and the page timestamp distinguish these clocks. A new inventory capture does not turn older production records into today's production or finalize an unfinished month.

SmartBI shows a supplement outcome card and a compact, expandable data-request table with what is missing, the responsible role, the next action and the judgment enabled afterward. Reporting units are resolved. Remaining requests distinguish:

| Item | Responsible role | Next action | Enabled judgment |
| --- | --- | --- | --- |
| Combinations with no returned balance | Warehouse / data administrator | First distinguish inactive, never-stocked and visibility-limited combinations; supply balances only for items needing verification | Distinguish normal absence from a data gap, then compare stock with actual demand |
| Historical opening/closing balances and stocktake/adjustment evidence | Warehouse / finance | Supply the same-boundary historical inventory tables and stocktake evidence; reconcile against already available receipts/issues/transfers | Check opening + receipts − issues + adjustments = closing and locate differences |

Existing transaction records are available; the remaining historical-balance gap is not described as an absence of all transaction history. Latest balances cannot reconstruct historical closing inventory.

## Reconciliation and checks

Actual-data validation confirms all 26 eligible order cost rows exactly equal the retained complete-cost report. The original cohort stays 109 orders / 301 tasks / 272 output records. November 3 keeps 183 stock rows; November 5 has 198; October has no captured balance; the November and Q4 ranges select 198. Cost eligibility remains 17 in October plus 9 in November, totaling 26.

- Backend changed slice: 107 passed; the final supplement-message slice: 14 passed.
- Frontend diagnostics and page workflows: 34 passed. Type checking and the final public preview build passed.
- Independent read-only risk review closed its mixed-clock finding and reported no remaining material blocker in the reviewed scope.
- No source business writes, migrations, production release or paid teacher calls occurred. Model completion and browser acceptance remain distinct from these code/offline checks and are recorded in the local delivery receipt.

Private evidence, raw minimized supplement, local CSV worklists, validation outputs, queued analysis IDs and delivery artifacts are retained at `D:\CretasSmartBILab\local-analyst-distillation\operations-supplement-20261105`. Credentials remain in their existing local encrypted storage and are not copied into tracked files or logs.

## History-list performance refinement

Browser acceptance exposed intermittent thirty-second request timeouts. The history endpoint was returning about 6 MB of full results on each navigation, including every saved report's section analyses and model reviews. The page now requests `summaries_only=true`: history retains its dates/status/summary/observations/usage, while a report's full result remains unchanged at its individual endpoint. The default API response remains backward compatible. Authorization and factory/source scope checks still precede either response. The public gateway permits this parameter only on the history GET and only once with value `true`; other filters/values remain denied. The focused period/gateway suite passed 45 tests, the frontend slice passed 34, and type checking passed after this refinement. Measured history response size fell from 6,086,112 to 177,026 bytes, and a representative request fell from 7.22 to 0.95 seconds. The final delivery receipt records fresh browser readback; earlier timeout observations are retained rather than counted as successful acceptance.
