# Procurement production release, 2026-10-17

## Scope and result

Released merged main `603c73fdc39d3d3fe468fbac5fbdc3279c57a701` to production Java and Web. This includes the procurement exception concurrency/actor fix (#1009), other decision acceptance (#1010), cumulative receiving correction (#1011), resupply navigation (#1012), and diagnostic clarification (#1013). No new business implementation was added during release.

Factory assembly remains disabled: the active JVM environment flag is unset and the deployed property defaults to false. There was no activation, template application, or existing-factory initialization. Existing factory IDs/count are unchanged (4); factory blueprint bindings remain 0.

## Release evidence

- Used the official unified release entry from a clean exact main, baseline `4b0209edd95e7f854a7bec659c8d50eccc168b3c`, serial backend first.
- Production preflight passed, including migration uniqueness. Existing exact-source Java CI (15,321 tests), real PostgreSQL/JPA gate (94 tests), and Web CI (5,611 passed, 5 existing skips) were reused from the merged PRs, not rerun.
- Trusted Java packaging selected 11 classes: 88 tests, zero failures/errors/skips, BUILD SUCCESS. Web production build passed. Initial local dependency restore was blocked by this worktree's running Vite/esbuild; only those two processes were stopped before retry.
- Prewarm transport was unreachable and skipped once. No CI artifact polling/retry; local trusted build and SHA cache staging were used. The deployment phase reused both artifacts, with build_count=0.
- Official build receipt: status=built, exit_code=0, staging=success. The external helper reported a trailing syntax error because it was edited while running; its official child build/staging had completed. The helper was corrected and syntax-checked before the separate deployment. No product tests were repeated for that helper correction.
- Deployment: DEPLOY_EXIT=0; exactly one RELEASE_FINAL_STATUS=deployed. Both Java and Web child outcomes are deployed. Blue slot health and post-switch observations pass. Web HTTP=200, entry chunk passes, and all four index hashes match.
- JAR SHA-256: `75e7f8007ee90a9ce13d8d8c0b40099f89af82047ac765a4324fc758661bc59e`.
- Web index SHA-256: `cb806c918650bdee7ac152fb440ed4506db2846657d4fe478923876706f4a109`.
- Active PurchaseServiceImpl class hash matches the trusted local class: `2823418af6561bcf934bd2e66426958f3baeb7efadea01b70a7cc97dc3b5f413`; ASCII getReceivedQuantity marker is present.
- All 18 newly applied migration versions report success; failed migration count is 0. Existing factories and binding counts were read in read-only transactions before/after release.

## Production read-only UI acceptance

Canonical filename MCP entry, one clean real UI login session, selected tenant-isolation and purchasing-readonly: both PASS. Purchase route mounts with one table and 10 rows; its real list, supplier, receiving-task and query-only summary requests succeed. No assembly link is exposed to the factory account. No console/page/HTTP errors, blocked mutations, or actual business write requests were recorded.

The MCP server only permits the primary workspace. Its checked-in canonical entry was used without copying scripts. Selected scenario modules, config, session/login/guard and evidence logic match the release worktree; differences are extra scenario registrations and console classification. Local harness validation passed 37 unit tests and 11 browser fixture tests; dry-run also passed. No second browser or historical storageState was used.

Private evidence: `assembly-integration-assessment-20261015/production-release/` (build/deploy receipts and logs), adjacent production baseline/readback logs, and primary-workspace `.playwright-mcp/production-purchase-20261017/` (sanitized JSON/Markdown, tenant screenshot and masked purchase screenshot). Credentials and raw business records are excluded from this brief.

## Limits and next boundary

This is service-level production release plus procurement read-only UI acceptance. No production receipt, exception decision, inventory/AP mutation, concurrency replay, or factory assembly activation was performed. Their accepted write-path evidence remains the isolated non-production evidence in #1009–#1012. Automated supplier notification and automatic replacement-PO generation remain outside the accepted resupply scope. Remaining business capability work continues in the existing isolated environment, reusing passed evidence.
