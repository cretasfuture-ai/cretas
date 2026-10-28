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

## Final local gate results, 2026-10-29

- Java/H2 target gate: 67 discovered, 65 passed and two PostgreSQL-only tests skipped in this lane; zero failures/errors. Actual PostgreSQL separately executed the full three-case ledger class, zero skips/failures/errors, including the two environment-only cases.
- PostgreSQL scoped-repository class: two real Hibernate/Spring Data tests passed, zero skips. Original ledger fixture failure was JSONB String binding before quantity assertions; fixed with PostgreSQL driver `stringtype=unspecified`. Explicit managed entities/package scanning and all query, tenant, status, date, deletion, period-lock and rollback assertions remain. Redundant archive autodetection disabled in the fixture.
- Independent final Legacy AI code risk review passed frozen product commit 91097e1f4f. Subsequent ledger test change only aligns fixture binding and avoids redundant metadata scanning; no production changes after that review.
- Completed child implementation scopes SC28-UNIT-FIX, SC28-LEGACY-AI, SC28-SCOPED-QUERY, SC28-LEDGER-DRAFT, SC28-LIFECYCLE-QA and SC28-CATALOG-MATRIX are archived here and released when this commit merges. Parent retains release and actual F006 acceptance only; no blanket business PASS.
- Local private logs: `sc28-final-java-unit-v4.log`, `sc28-final-java-realpg-v1.log` (repository PASS, original ledger fixture FAIL retained), `sc28-final-ledger-realpg-v2.log` (ledger PASS), `sc28-harness-final-matrix-upload-export.log`.

## SC29-GATE-CLOSURE

- Owner Root; incremental Base 458cf2047e3d2619e9d69ee6b0e48f04092a29ef; PR1285. Scope: LegacyAiAnalysisGuard/ContractTest and two dynamic-quality form/page specs. No query, Entity, permission or revision-protocol changes.
- Original PR Web gate: two assertions still expected the previous revision-bearing title. Fixed to the business label; refresh test additionally preserves the entered answer and resubmits the actual rev-8 payload. Target Vitest: seven passed. No assertion was removed to hide a product failure.
- Original Guard rejected proven unit box; standalone execution reproduced it. Added singular box recognition while retaining exact source-unit comparison and alphabetic boundaries. Seven original-unit positives plus seventeen existing negative/positive probe assertions passed; added the ninth JUnit case, which must execute in the final candidate build.
- Original candidate at 458cf built successfully using the unified entry and staged an immutable artifact without deployment. This delta requires a new candidate; do not deploy the earlier build as if it contained the box fix.
- Independent incremental code risk review passed. Query-startup CI run40726690781 failed with Java heap exhaustion; raw failure retained privately. It is not a successful gate. Changed repository declarations/tests are unmodified; final-commit local real-JPA evidence and applicable PR checks are required under the documented infrastructure-failure rule.
- Implementation scope released on merge; parent retains release and F006 actual acceptance. Current Android auth events supplied no logout-cause evidence; no credentials entered and no approval configuration changed.
# Final full-suite fixture alignment (2026-10-29)

- Task `SC29-FINAL-CI-FIXTURE`; Owner Root; Base `4b13960f65c0816f82fc07d980546b4d1ae1820f`; candidate in PR1285, merge/release pending. Scope: five Java tests below, this archive and transient ACTIVE registration; no production/auth/query/policy source changes.
- CI40733443573 actually observed 18158 cases: 17874 passed, 258 skipped, 6 assertion failures and 20 errors. Empty failure baseline retained. Twenty-two failures exposed old fixtures lacking the existing process-secret guard or same-factory authenticated actor. Three stale catalogue assertions predated the approved `smartbi_factory_analysis` entry. One stale history assertion expected assistant answers despite the existing privacy boundary forwarding user turns only.
- Updated `ProductWorkProcessServiceImplTest`, `TriggerChainControllerTest`, `AIIntentServiceImplPythonIntegrationTest`, `LegacyToolMigrationRegistryTest`, `ToolDescriptorCatalogTest`. Preserve business assertions, request cleanup, exact catalogue sets/counts, cache assertions, and all production permission/egress behavior. Test-only scope is released with this candidate; failures remain recorded until a fresh final-head gate passes.
- Validation required: unified candidate selector adds all five classes to the prior twelve; final-head full CI must execute them and pass the unchanged zero-failure ratchet before merge. Logs/private acceptance ledger retain the original failed run. No deployment or native acceptance is implied by this fixture update.
