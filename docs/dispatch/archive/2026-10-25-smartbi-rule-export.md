# SMARTBI-RULE-EXPORT-20261025

- Owner: Codex root; DIRECT implementation with read-only independent production/contract review.
- Base: `a8dde4421135b9a3acf34361debedb4e3b342cbe`.
- Worktree: `C:/Users/Steve/cretas-smartbi-rule-export-20261025`.
- Scope: Python financial export API/generators/shared conclusion rows/tests, PDF text verification dependency, Web export request types/call sites and wiring tests. No Java, migration, tenant or business-data changes.
- Behavior: PPT/PDF include the current dashboard headline and good/attention/suggestions after the cover and before KPI/chart/AI content. Missing or whitespace-only conclusions create no extra page. Long conclusions paginate without truncation. PDF preserves literal markup and supports Chinese without an installed external font. Excel uses the same conclusion-row function.

## Verification

Local baseline: 116 tests passed before implementation.

Final commands (2026-10-25):

```text
cd backend/python
python -m pytest smartbi/tests/test_financial_dashboard.py smartbi/tests/test_financial_dashboard_correctness.py smartbi/tests/test_financial_data.py smartbi/services/financial/tests/test_rule_exports.py -q -p no:cacheprovider
# 128 passed, 9 warnings, exit 0
cd web-admin
npx vitest run src/views/smart-bi/__tests__/financialDashboardState.spec.ts
# 33 passed, exit 0
git -c core.whitespace=cr-at-eol diff --check
# exit 0; existing source line endings preserved
```

The real API responses are parsed as PPTX/PDF. Assertions inspect slide/page order, every rule section and exact visible numbers/text, old requests with absent/null/blank conclusions, headline-only/findings-only input, literal `<b>`/`&` text, and pagination through the last of 35 findings. PDF conclusion page rendered with Poppler and visually inspected; Chinese, margins and full text visible. PPT is structurally inspected; PowerPoint visual rendering remains unverified locally.

13 mutations killed: remove headline, remove each of good/attention/suggestions, disconnect PPT headline, disconnect PDF findings, create an empty conclusion page, truncate after four rows, remove PDF markup escaping, and disconnect each of the two frontend fields from each export. The first escaping mutation survived because `< 预算` was treated as literal text; strengthening the fixture to include literal `<b>原表</b>` made that mutation fail. Original evidence retained.

Evidence directory: `C:/Users/Steve/cretas-smartbi-rule-export-20261025/test-output/smartbi-rule-export/` (gitignored); `pytest-final.log`, `vitest-final.log`, `mutations.json`, `mutant-*.log`, `conclusions.pdf`, `conclusions.pptx`, `conclusions-2.png`.

## Live baseline and remaining acceptance

Fresh UI session proves F006. Samples 5250–5255 have live headline/findings; all eight dashboard-nav charts captured before changes. Screenshots: `C:/Users/Steve/my-prototype-logistics/.playwright-mcp/smartbi-factory-20261025/`.

Observed separate baseline defects: 5254 headline says unchanged while gross-margin chart shows +0.1 pp; KPI amount/unit can wrap awkwardly; capability/F006 returns HTTP 503. These are not repaired by this export slice.

Commit/PR, CI, merge, Python/Web deployment and post-release UI downloads are separate pending evidence at candidate creation. No production export acceptance is claimed here. Implementation scope is released to PR integration; no child writer holds it.
