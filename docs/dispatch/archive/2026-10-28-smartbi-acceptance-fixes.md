# SmartBI F006 acceptance fixes, 2026-10-28

- Task: SMARTBI-F006-QA-20261028. Owner: Root.
- Base: 96bf2707b860bb5892ee0735fc01e4faa1f11f68.
- Branch: codex/smartbi-acceptance-fixes-20261028.
- Previous implementation: PR1267, merge99e253de58; Java/Web receipt at8b13eb6623, Python exact source and Android OTA published. Separate device/functional evidence remains pending.
- Sole private scenario ledger: `.playwright-mcp/smartbi-complete-20261028/scenarios.json` in main workspace.
- SC28-WEB-QI-REVISION: dynamic quality form displays raw internal revision fingerprint as heading. Replace visible heading with business label, preserve validation/revision payload.
- SC28-FG-MIXED-UNIT: current finished-goods inventory chart sums boxes/bags/pieces into 2992 quantity, contrary to its own limitation. Require unit-separated quantities and no cross-unit total/rank/fact. Check inventory/WIP builders for same cause; no conversion inferred.
- Original AI follow-up unavailable evidence retained in web-initial. Retry with fresh required UI-login options passed all overview/quality/joint/trio assertions; zero writes and zero blocked mutation attempts. Reliability scenarios are independent and not yet accepted.
- Before March snapshot repair: F006 closed March period, zero snapshots, 226 unique material/unit rows exactly equal closed February keys; every opening matches prior closing. Narrow deployed repair API created226, idempotent retry created0/existing226; independent SQL confirms unchanged period audit and no duplicate content. Private evidence retained.
- Scope/validation listed in ACTIVE. Do not alter unrelated dirty worktrees, credentials, reviewer policies, permission matrices, Entity or migration scope. Three explicitly registered SmartBI Repository methods are covered by SC28-SCOPED-QUERY.

## Frozen repair scope

- SC28-UNIT-FIX: inventory, shipment and purchase quantity candidates split by actual trimmed unit; no conversion inferred. Final factory-chart suite: 396 passed, one unchanged schema-only case has separate previous actual-schema proof.
- SC28-LEGACY-AI: exact unit/price aliases; authoritative page coverage; internal facts excluded from public chart data; per-claim quantity units and exact decimal loss amount/currency guards; no unsourced thresholds. Owner verified before cache or source reads; PostgreSQL metadata determines source and never falls back to a colliding legacy id. Empty analyses fail explicitly.
- SC28-SCOPED-QUERY: sales/finance upload reads and PostgreSQL analysis cache use factory-scoped queries. New real Hibernate/Spring Data test exercises same upload ids across tenants with H2 and task-owned PostgreSQL branches.
- SC28-LEDGER-DRAFT: inbound quantity and value require CONFIRMED receipt status. Existing historical confirmed rows without batch linkage remain eligible. Actual Hibernate/PostgreSQL test includes non-confirmed, wrong tenant/date and deleted negatives.
- Root: Web/native expired detail refresh retains guided source selection and original upload trio; dynamic quality heading uses business wording; chart-family rows expose stable ids for acceptance.
- SC28-LIFECYCLE-QA: twelve grounded context-only turns, natural expiry and original-detail recovery; uploaded-trio exports have independent three-format download assertions. Previous business-joint exports cannot prove uploaded exports.
- SC28-CATALOG-MATRIX: bounded family batches inspect qualification, render, detail and actual export. Optional data gaps remain visible; restaurant families excluded. Python gateway HTTP failures are recorded by the common network recorder.

## Verification and acceptance boundaries

- Passed before final Java gates: Web 18 tests, native 17 tests, both typechecks; canonical Node 167 tests; existing common/lifecycle local browser fixtures 14 tests plus catalogue fixture one test. These are implementation/tool evidence, not production business acceptance.
- Target Java attempts retained: initial Windows argument parsing error; test import/generic compile errors; then H2 JSONB fixture quoting and an expected-message mismatch. Corrected fixtures preserve actual repositories and negative isolation assertions. Formal reruns and PostgreSQL results are required before release.
- Sole private scenario ledger remains authoritative. Original production failures stay recorded until fixed source is deployed and corresponding real UI/data assertions pass. Current Android is the only available device; old OTA applied proof does not verify this pending native fix.
- No approval/reviewer configuration changed. Previously denied credential-to-ADB action was not retried. No other-tenant business writes.
