# SMARTBI-FUSED-AXIS-20261026

Owner: Root (DIRECT). Base: 690cd42ad5eb298cd8718bf4676b6b98e2f5a3d9.
Scope: factory_charts/business_metrics.py fused chart layout and this receipt. No arithmetic, source query, migration, Java, permissions or restaurant changes.

F006 positive acceptance of PR1186/1187 found the outer cost-axis text clipped and legend crowded against date labels. Original failure screenshot is preserved in `.playwright-mcp/smartbi-catalog-closure-20261025/live-operations-focus.png`. Root restored the previous Web index (755d8227...) and Python source (2184 old hashes match, health200) before fixing. Web rollback four-way hash/entry verification passed. Additive preference schema and all ten F006 uploads (21 original rows) are retained; no operational stock/sales/quality records were written.

The fused chart now reserves explicit space for both right axes, separates legend from date labels, rotates endpoint date labels for narrow cards, and uses the existing safe money formatter. Local acceptance uses the real Vue component, Python-computed chart and actual registered production theme. ECharts text bounds and date/legend separation are checked at desktop and 390px viewport; screenshots are inspected. Restoring the old layout must fail the same boundary/separation predicate. The local old-layout desktop case overlaps legend/date labels; it is not claimed as reproducing every production clipping condition.

Required validation: focused business metrics/closure/contract pytest; `web-admin/test-output/closure-axis-browser.mjs`; final-head CI; exact-main release preflight; official Python and unified Web release; resume existing F006 acceptance with no duplicate uploads and recheck affected chart before remaining three samples/preferences/exports.

Implementation scope released on final commit for PR. CI, merge, release and F006 acceptance remain separate facts, recorded in the final live delivery receipt. No other ACTIVE locks changed.
