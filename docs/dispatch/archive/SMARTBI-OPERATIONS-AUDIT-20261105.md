# SMARTBI-OPERATIONS-AUDIT-20261105

Base: `57316daa120c42a1237e3cce55a50c179ae724c9`. Owner: root. Scope: the existing isolated Blacklake/六膳门 SmartBI prototype, production/inventory/period reconciliation and guidance. No Blacklake writes, cloud teacher calls, production Cretas deployment, database migration, or scheduler enablement.

## Correctness changes

- Operational views now use the authenticated locally archived November 4 recheck of the original 109-order cohort, matching the complete-cost view's 26 eligible orders. Scope, immutable baseline fingerprint, observation fingerprint, query controls, child identities, read-only receipt and BOM supplement are validated before projection. Child foreign keys are canonicalized in a copy to prevent string/number mismatches dropping wages.
- November 3 stock balances retain their independent collection timestamp. A later work-order recheck cannot refresh or reconstruct inventory. Periods without that snapshot expose unavailable balances, with a link to the actual snapshot date.
- Production separates order quantities, task hours and approved output quality. Same-name products use SKU/name/unit keys; process drilldown uses actual project IDs. Missing hours, missing quality counts and zero quality denominators stay unknown. Cumulative task hours do not imply worker hours or confirmed bottlenecks.
- Real archived output records omit their quantity unit. The reviewed UI suppresses aggregate good/defective quantities and rates until that unit is verified, retains a separate source defect signal, and resolves readable product names only through an unambiguous matching SKU. No finished-product unit is guessed for intermediate reporting. Quality/workshop owners are asked for the original reporting unit and quantities.
- Inventory findings distinguish negative balances, zero balances, absent records and missing fields. Findings scan all units even when a unit filter is active; locating a finding clears the conflicting filter. Warehouse totals are reconciled by warehouse identity and unit, never across units.
- Period review separates unfinished orders from completed orders with blocked costs; missing-date diagnostics use the selected date basis and carry affected IDs. Incomplete periods are explicit. Fingerprints include the stock clock and projection revision; scheduler reconciliation keys also track archived-source revisions. Existing disabled scheduler state is preserved.
- Reusable findings show the issue, possible reasons, responsible role, next action, and an independent contextual follow-up. Explanations are hypotheses, not asserted causes. Numeric amounts/quantities display to two decimals; counts remain integers. Material summaries show missing amount lines beside partial identified amounts.
- Focused operational questions recompute quantities and examples from authorized current rows instead of trusting numbers written in the question. A completed deterministic row check answers directly; the existing local AI report queue remains independent. This avoids making a negative-balance check wait for a full model enrichment. Authentication, FULL cost authority, run binding and alert ownership checks still precede this path.

## Actual-data reconciliation

Source: 109 orders = 91 complete + 15 in progress + 3 unstarted; 301 tasks; 272 approved output records. Product plan/actual groups and warehouse/unit balances reconcile to their source details. Three approved product/process quality groups contain defects; these require quality and workshop review, not an invented diagnosis.

Inventory: 183 balance lines, collected November 3 17:21 Beijing time, including 7 negative and 54 zero balances. Warehouse should verify negative balances against original receipts/issues, batches, units and physical counts. Planning should assess zero balances against actual production demand, inbound purchases and safety stock. Neither proves actual shortage by itself. Opening/closing balances plus fully approved same-boundary movements are still needed for period stock reconciliation.

| Completed-order window | Orders | Eligible material+labor unit cost | Stock rows |
| --- | ---: | ---: | ---: |
| November 3 | 10 | 2 | 183 |
| October 28–November 3 | 77 | 25 | 183 |
| October | 57 | 17 | unavailable |
| November, available data | 34 | 9 | 183 |
| Q4, available data | 91 | 26 | 183 |
| 2026, available data | 91 | 26 | 183 |

October and November IDs are disjoint and their union equals the current Q4/year cohort. Eligible monthly counts sum to 26. All 26 eligible order cost rows equal the previously retained complete-cost report, and each unit cost reconciles to `(material + labor) / finished good`. These are observed material/labor costs, not complete manufacturing cost, profit, or finalized period closing accounts.

## Evidence and validation

Private receipts and raw comparison outputs remain on this machine at `D:\CretasSmartBILab\local-analyst-distillation\operations-audit-20261105`; no factory responses or authentication material are committed.

- Frontend operational diagnostics, date-switch/identity workflows and contextual follow-up tests: 33 passed, including absent output units, a lazy-mounted diagnostic input, and reactive follow-up progress without mutating the source snapshot.
- Initial backend expanded selection: 109 passed; one standalone bootstrap subprocess hit its 40-second timeout during concurrent frontend checks. The unchanged failing case passed independently (1 passed); the timeout is retained in the receipt.
- Independent risk review found two issues (inventory wording clock and mixed ID representations); both fixed and individually verified, with no remaining material blocker.
- Final backend changed slice: 91 passed, including same-baseline operational-file revision requeue idempotency, canonical child IDs and split-clock wording. The last focused-answer/no-AI-wait API refinement additionally passed its 16-test section slice. Type checking and public build passed; preview browser outcomes are recorded in the local final receipt. No production/JPA/AI-completion acceptance is implied by unit tests.

Existing public URL and no-login read-only gateway boundary are preserved. Backend changes apply only to the owned loopback 8095 adapter; original 8093 and production services are untouched. The draft PR remains the review destination; no merge is claimed.
