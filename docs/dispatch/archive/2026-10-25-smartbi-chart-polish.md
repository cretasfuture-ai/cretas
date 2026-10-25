# SMARTBI-CHART-POLISH-20261025

- Owner: Codex root, DIRECT; independent read-only production/financial review.
- Base: `b6e1e7fda8` (includes reviewed weighted-margin PR #1179).
- Scope: financial dashboard Vue/state/export helpers and tests, Python financial slicer selection and margin difference labels/tests. No Java, schema, permission or non-F006 business changes.

## Observed problems and changes

Before changes, captured all eight visible charts from F006 samples 5250–5255 and their rule conclusions. Evidence: `C:/Users/Steve/my-prototype-logistics/.playwright-mcp/smartbi-factory-20261025/`, `525*-before.png`. Initial Sankey animation capture was incomplete; the settled capture is retained separately.

1. Amount digits and their scale/unit could wrap separately: split number and intact unit, use existing typography/colors and adaptive grid columns.
2. Quarterly progress cards crowded narrow layouts: adaptive grid.
3. Percentage measures were offered as slicers: exclude normalized measures and ratio columns, including mixed revenue/cost/ratio data after #1179.
4. Margin differences said `%`: use `个百分点`.
5. Actual production PPT/PDF downloads from the conclusions tab had only three pages (cover/conclusions/end), silently omitting charts from other tabs. Export now renders every valid chart independently of mounted DOM; rendering failure fails the export. Missing-data charts remain excluded. Shared option decoration preserves existing graphics and user annotations.

6. PDF visual inspection exposed a latent scorecard gauge: its scaled amount used a different global suffix (17,891万元 became 17,891亿元), and the first gauge clipped at the edge. Gauge labels now exactly match the visible KPI text, and gauge centers/radii fit the export canvas.

## Verification

Combined Python regression/export/margin/polish suite: 153 passed, 9 existing deprecation warnings. Frontend state/export suite: 41 passed. Type check passed (frontend unchanged by final Python-only alias fix). Commands and full logs: `test-output/smartbi-polish/` (gitignored).

15 mutations cover wrong unit, lost number, only-first-chart export, invalid-chart inclusion, ignored empty image, swallowed rendering exception, measure-as-slicer, percentage label instead of points, mixed amount/ratio slicers, dropped annotation text, dropped original graphics moved annotation, omitted ratio-header aliases, scorecard unit corruption and gauge clipping. All must fail their visible-output assertions before release.

Actual local Vue downloads of sample 5250 from the conclusions tab: PPTX and PDF each seven pages/slides, with four chart images after the conclusions and before the ending. Production baseline files (three pages) retained. PowerPoint visual rendering remains unverified; PDF is rendered and inspected separately. Browser screenshot calibration for local responsive captures was inconsistent; those captures are retained as instrument failures and are not sufficient visual acceptance.

Candidate build, CI, merge, deployment and final production UI readback are separate receipts. No chart-recommendation implementation is included; stage 3 still requires Steve to review the factory chart catalog.
