# SmartBI table and navigation refinement — 2026-11-05

- Task: SMARTBI-TABLE-UX-20261105. Owner: root. Base: a274f0b0492e3b3fed0d15e39012453e6b051b98. Test-only delivery on PR #1390.
- Shared CostDataTable adds header text/select/date/numeric filters, numeric sorting, missing-only filters and 20/50/100-row pagination. Filtering and sorting cover the full input before pagination; source arrays remain unchanged. Unknown amounts remain unknown. Header filters change the displayed list, not a saved analysis cohort.
- Applied to product/order/material/labor costs, period costs, production/processes, inventory, anomaly follow-up, traceability and data coverage. Product units stay visible; costs in different units are not treated as directly comparable.
- Production starts with product/process summaries and opens related work orders. Missing-data details open in an order-specific drawer with missing inputs, responsible role and expected benefit. Warehouse charts and technical analysis notes are collapsed.
- The latest 109-order traceability recheck explicitly identifies its independent scope rather than inheriting the historical period selector.

## Verification

- Target suite: 49 tests in 11 files, including 1,000-row sorting/filtering/pagination, numeric zero bounds, percentage bounds, missing values, and Element Plus registration probe rows in nested expansion slots.
- Browser: product minimum unit cost 50 filters 5/15 products; descending order verified as 67.31, 65.49, 64.58, 51.15, 51.15. Units remain visible. Production drill-down resolves 牛肉丝/片缓化料 to GD202611030001. Inventory minimum balance 10 filters 4/6 rows; descending balances verified as 114, 27, 25, 19, 4, 0. Missing-data drawer resolves S20260019-002-001 and lists responsible roles. Anomaly follow-up opens the selected zero-material-cost problem; no follow-up saved. Data coverage headers render. No new console errors after the rendering fix.
- Initial final typecheck hit Node's default 4 GB heap limit; rerun uses an 8 GB process-local heap. This does not change application configuration.
- Final typecheck/test/build logs and browser DOM receipts: D:/CretasSmartBILab/local-analyst-distillation/table-ux-20261105/. Final exit codes and content hashes are recorded in the local receipt.
- Browser acceptance covers desktop functional interactions; phone layout and exact pixel comparison are not claimed.

## Boundaries

- No backend, database schema, Blacklake business writes, payroll changes or production deployment in this slice. No model inference or new paid teacher calls.
- Existing 26-order cost data and saved analyses are reused. UI improvements do not certify previously unverified cost lineage or currencies.
- Branch changes and private acceptance evidence are retained locally; an incremental Git bundle preserves the implementation independently of the borrowed Mac mini.
