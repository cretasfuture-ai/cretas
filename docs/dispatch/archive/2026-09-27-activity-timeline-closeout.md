# OBS-ACTIVITY-TIMELINE-20260927 — closeout

- Status: merged. Base `4f708720ed12ea1b9938358735a29aba03f791d5`; PR [#831](https://github.com/cretasfuture-ai/cretas/pull/831), squash merge `5787bf2b19f812cc7575258b09a740787d1cfa2f`.
- Scope: tenant activity events for React Native screens and controls, Web Admin controls, authenticated API outcomes, operation-log timeline, PostgreSQL migration `V20261101_45`. The event detail allowlist excludes form values, request bodies, credentials, and tokens.
- Validation: target Java contract tests, Web tests/build/typecheck, React Native typecheck, disposable PostgreSQL migration and committed JDBC test passed. Final full Java CI suite and its ratchet passed. Hosted Fresh DB progress job did not start due to GitHub billing; equivalent local isolated PostgreSQL 17 gate passed.
- Production: unified release receipt `C:\Users\Steve\.cache\cretas\release-reports\cretas-1790490213-1130.json` reports `exit_code=0`, one `RELEASE_FINAL_STATUS=deployed`, Java green port 10020, Web four-way hash pass. Flyway row success and `activity_events` table read back. Authenticated F006 `GET /alerts` produced one tenant-scoped `api/GET` event, then the admin timeline returned it.
- Android OTA: production runtime `1.0.4`, bundle timestamp `1790490396927` registered. Manifest points to this bundle, lists 39 assets, and launch asset returned HTTP 200.
- Limits: no physical-device UI acceptance yet. Generic controls lacking stable names can appear with generic action labels; no arbitrary input values are stored. Observe event volume, queue errors, and table/index growth after real usage.
- Scope lock released on closeout. No business data was changed for this acceptance; the read-only alert request created one activity event by design.
