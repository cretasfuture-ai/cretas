# SMARTBI-MARGIN-20261025

- Owner: Codex root, DIRECT, independent read-only financial/production review.
- Base: `a8dde4421135b9a3acf34361debedb4e3b342cbe`.
- Scope: `backend/python/smartbi/services/financial/{metrics.py,gross_margin_trend.py,tests/test_margin_contract.py}` and `financial_{dashboard,data_normalizer,findings,headline}.py`.
- Shared metric: `gross_margins`, computed once per prepared dashboard. Revenue plus cost or reported gross-profit amounts take priority over percentages; cumulative margin is total gross profit / total revenue. Both subject-row and subject-column tables are accepted. Ratio-only tables retain an explicitly labelled monthly arithmetic mean. Headline, findings, chart KPIs and data-table values share these facts; displayed differences subtract the two displayed one-decimal percentages.
- Missing period costs or zero revenue remain non-computable, with visible reason, rather than zero costs, fabricated margins or a silent change to averaging.

## Evidence

The eight new scenarios failed against the starting implementation. After the change:

```text
cd backend/python
python -m pytest smartbi/tests/test_financial_dashboard.py smartbi/tests/test_financial_dashboard_correctness.py smartbi/tests/test_financial_data.py smartbi/services/financial/tests/test_margin_contract.py -q -p no:cacheprovider
# 135 passed, 9 warnings, exit 0
```

21 mutations killed: arithmetic instead of weighted total; omit reported gross profit; ignore amount columns; independently round the raw difference; treat a missing cost as zero; zero denominator as zero margin; ignore selected period; wrong headline, findings, chart KPI and chart-month values; hide the simple-average label; sum synonymous profit rows; ignore conflicting profits; compare incomplete years; ignore row units; misclassify numeric-year columns; ignore the requested reference year; hide the entirely absent comparison-period note; ignore wide header units; multiply wide row/column units twice. The assertions read visible response text/KPIs/table values, including 14.0% versus the incorrect 30.0% mean, 40.0% prior-year margin, -26.0 pp difference, and 10.0% after selecting February.

Independent review found four gaps in the initial 124-test candidate; none was merged. Follow-up cases cover both identical and synonymous reported-profit duplication/conflict, partially and wholly absent comparison periods, long/wide row-unit precedence without duplicate multipliers, wide month-header units, and explicit year columns in reverse order plus an unrelated year. The final 135-test candidate (19 new scenarios) and all 21 mutations pass. Original failed-review evidence is retained separately.

Local logs: `C:/Users/Steve/cretas-smartbi-margin-20261025/test-output/smartbi-margin/`; red baseline `C:/Users/Steve/cretas-smartbi-margin-before.log`. Tests live under `smartbi/services` and are executed by Python Gate's `Run pytest` job. No Java, JPA, migration or frontend changes in this slice; no duplicate Web build required.

Source/test changes are frozen at candidate verification. CI/merge/deployment and F006 upload with persisted readback are separate pending acceptance steps. The existing 5254 percentage-only sample is the negative control; the new unequal-revenue sample is the positive control. Scope is released to PR integration on submission.
