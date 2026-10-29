# SC77 WIP graph labels

- Base 9fb7482b8909d2eb73cc55a7eaea5d52a1652de6; Root coordinator, sc77_executor code owner.
- Actual Web sc74-wip-actual-card.png now paints Sankey, but independent visual review FAIL: full batch node labels seriously overlap and clip at top/right. Previous BLOCKED and FAIL evidence must remain.
- Scope only backend/python/smartbi/services/factory_charts/engine.py and test_wip_plan_flow.py, limited to WIP chart label/layout presentation and its regression. SQL, graph identities/links, monetary/quantity/unit calculations, source rows, role/security, migration and external services are frozen.
- Acceptance: concise unambiguous displayed node labels, full canonical node names retained in graph, tooltip and original detail; no quantity or edge mutation; narrow-screen chart margins/labels remain readable. Run related WIP/graph tests and retain skips honestly. Root owns independent diff review, CI, merge, official Python deploy and actual Web/Xiaomi visual retest. No phone or child agents for worker.

## Frozen candidate

- WIP-only #suffix aliases are unique and bounded even when original batch tails collide. Per-node labels are centered inside the nodes with a white stroke and reserved chart margins. The user-visible attention note explains the short labels; canonical node names, links, quantities and original MC rows remain unchanged.
- WIP + extended target tests: 15 passed, 2 skipped. Both skips require a disposable local PostgreSQL DSN; no SQL/data-path changes were made and skipped tests are not counted as PG PASS. Exact command/hash/exit evidence is retained in private test-output/sc77-test-receipt.json.
- Actual F006 frozen input has 16 canonical nodes and 11 links. Root used the real local ECharts 6 and current production formatter source to preview 360/440-pixel widths at 240/320-pixel chart heights, with no network requests or console errors. Final four PNGs have readable labels with no observed clipping; these are layout previews, not production or physical-device acceptance. First r1 previews still clipped and remain preserved.
- No new Android OTA or Web build was required for this Python option-only increment.

## Final delivery

- PR1329 merged normally at 3b8d88e9482dad93be064511e0b684241bbde340; reviewed head 39a24286da1df17ea6a2855feab4c5eca2976698 has the identical tree. CI actually executed WIP tests (8 pass / 2 skip); full Python result 10928 passed, 188 skipped, 153 deselected, 2 xfailed, with the sole pre-existing model-registry audit-date failure retained under the user's exclusion. Source/field/secret checks passed; no required-check or admin bypass was used.
- From clean exact main, release preflight and official deploy-smartbi-python.sh --env prod exited 0. All 148 migrations were already current and skipped; Python 3.11 service/runtime/rollback contract and HTTP200 passed. Private full deployment log: final-release test-output/sc77-python-deploy.log. Java/Web/OTA artifacts were not republished for this increment.
- Production Web canonical MCP single-family scenario and independent actual-card review PASS, actualBusinessWrites=0, blocked mutations / HTTP / failed requests / console / page errors all zero. Evidence: web-sc77-final/report.json and sc43-native/sc77-web-wip-visual.json; the initial directory-only screenshot and old overlapping card remain retained separately.
- Physical Xiaomi normal update acceptance and fresh full-factory WIP b13r1 graph/details PASS, followed by independent visual PASS; 16 readable # nodes, 11 edges, 1090 kg, 9/11 visible original rows. Native old FAIL is retained in priorResults. Full private delivery receipt: sc43-native/sc81-final-delivery-receipt.json.
- Status: merged, deployed, verified within this scope, archived and source scope released. Second Android/iPhone physical-device coverage is outside the available hardware; no simulator result substitutes for it.
