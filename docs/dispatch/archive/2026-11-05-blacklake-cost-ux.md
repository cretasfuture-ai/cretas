# BLACKLAKE-UX-20261105

Owner: root. Base: `87b45146521b4d0b2b201eb2c0190c63e03f68ea`.
Environment: existing isolated Blacklake SmartBI preview, PR #1390 (draft).

## User flow

- Review source completion dates, default to the latest seven available data days, then filter product, issue family or order number. Custom inclusive dates and undated orders are available separately.
- Order tables show 20 rows per page, optionally 50/100. Search, date and issue changes return to page one. Aggregate chart/KPI calculations use the filtered orders, not just the visible page.
- Open one order in a drawer: cost figures, that order's interpretation, responsible roles/actions, materials and processes. Follow-up explicitly names that order. Per-order answers no longer appear again in the detached specialty list.
- Distinguish cost signals, data quality issues and comparison prerequisites; a signal is not a confirmed production fault. Cross-filter historical comparisons are labelled explicitly.
- Keep the V5 palette/components. Move scope/methodology, scheduler information and token accounting out of the primary business flow. Preserve usage under optional details and keep data/tenant/source validation.
- Shared section interpretation presents the finding, issue and next action once. Secondary material/labor detail is collapsed until requested. Period selection remains day/week/month/quarter/year/custom; an initially empty day after the source cutoff opens the available source day instead.
- Display amount/quantity values to two decimals; timestamps use Beijing MM-DD HH:mm. Period labels retain years when crossing a year boundary.

## Data handling

Source completion/creation dates are separate metadata, not inferred from order codes or material dates. `project_dates` reuses the existing timezone-aware parser and returns null for unavailable dates. The saved 26-order report was enriched from the matching source probe with all 26 IDs verified. Its cost rows and 145 accepted analysis results are unchanged; original file retained locally. No Blacklake business write, production release, migration or paid inference was performed.

## Acceptance

Local evidence root: `D:/CretasSmartBILab/local-analyst-distillation/ux-20261105/`.

- TypeScript check, targeted Vue tests and Blacklake preview build: recorded in `typecheck-frozen.log`, `tests-frozen.log`, `build-frozen.log`.
- Source-date unit test: `python -m pytest backend/python/tests/test_blacklake_review_dates.py -q`, 1 passed. Missing dates never derive from an order code.
- UI tests include 1000 synthetic orders with 20-row pagination, page reset, source-date filtering, per-order interpretation isolation, authorization failure and cross-tenant stale response rejection. This establishes UI list behavior, not a live 1000-order backend load test.
- Native in-app browser readback: default seven-day range, full 26-order second page, custom single-day selection, issue-family filter, order search, unit switching, named order drawer and successful follow-up. Private DOM evidence saved locally. Desktop interaction verified; phone viewport/pixel-level visual acceptance remains unverified.
- Data enrichment proof: `date-enrichment-proof.json`; original costs/analysis compared structurally before replacement.

The current source is still the fixed approved PoC cohort. Client filtering does not create a new AI period report; per-order explanations stay attached to their original order, while optional specialty reports explicitly retain their full cohort. Other sources keep their existing ingestion/authorization paths.
