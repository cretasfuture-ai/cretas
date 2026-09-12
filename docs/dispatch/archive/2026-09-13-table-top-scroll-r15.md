# R15 top scrollbar acceptance

- Task QA-TABLE-TOP-SCROLL-R15-20260913; Owner current task, isolated DIRECT worktree.
- Base/product version e789be64283e8f18417df1a9b8aa9fa179b65b34.
- Actual scope canonical top-scroll scenario, registry, generated entry, target tests and this archive. No product code, API, permission or data changes. Scope releases on merge; no product deployment needed.

main62 requested an external top horizontal scrollbar with a visible position/extent. Actual production sales, purchase and transfer tables passed 1440/1024 top-wheel, reverse scrolling, both endpoints, no overlap, equal viewport width and tab reactivation. Headed Chromium screenshots show native thumbs/arrows; headless screenshot omission is not a product defect. Sales/purchase passed run2026-09-12T18-52-47-914Z. Transfer passed run2026-09-12T18-57-53-590Z using a real horizontal content gesture because its EP bottom bar is display:none; no forced hidden-element clicks.

Headed sales end offsets632/1040, purchase138/546, transfer70/478px. Top/header/body agree; overlap0, width difference0, bar height16. Top scroll range includes2px border difference, while actual endpoint offsets synchronize. Console/HTTP errors and business writes/blocked attempts0; each headed run retains10 navigation-aborted GETs. Earlier local homepage permission failure and transfer tool-selector failures are preserved, not counted as success.

85 harness tests pass; actual Vue fixture on the deployed dist passed sales geometry and synchronization. The final transfer-only production run exercises the added horizontal-body gesture branch. Design专项37/40: placement10, synchronization10, position readability9, layout8. This validates the original three-page scope, not every page, native pointer/DPI, touch, elapsed-time efficiency or unrelated currency wrapping.

Evidence: C:/Users/Steve/cretas-visual-fixes-20260912-r3/production-top-scroll-r15-headed/.playwright-mcp/run/ and production-top-scroll-r15-transfer-verified/.playwright-mcp/run/. Review and Sheet receipt: table-top-scroll-r15-review.md and sheet-update-r15-receipt.json in the same evidence root.
