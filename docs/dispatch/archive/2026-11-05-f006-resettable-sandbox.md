# F006 resettable sandbox — implementation checkpoint

- Base: `41f58a822cf3a739db8b9c2a8c1ea281522d4913`.
- Branch: `codex/f006-resettable-sandbox-20261105`.
- Worktree: `C:/Users/Steve/cretas-f006-demo-20261105`.
- Scope IDs: F006-SANDBOX-API / RUNTIME / BASELINE / WEB / TESTS / LOCAL-RELAY, all suffixed `20261105`; root coordinates the scopes registered in ACTIVE.
- User decision: keep the completed flow available for trace/cost inspection; clear isolated transactions only when clicking **结束演示并清空**. Preserve pre-existing production demonstration records.

## Implemented

The existing 24-step frontend workflow now uses an explicitly entered, actor-bound isolated session. The gateway, loopback manager, independent Java/PostgreSQL/Redis slot, local attachment storage and reset proof fail closed. Production credentials never authenticate the isolated node. Real forms and normal business services remain in use. Exact read-only current-actor authorization endpoints remain available during preparation so route guards can render the control shell. Generic AI dispatch is blocked inside the round; exact pure form parsing can prefill visible fields before isolated form submission.

The baseline contains 29 approved demo-master tables / 209 rows, not existing production business transactions. Private files are outside Git with restricted ACLs. Source business rows are read-only and repeatable-read. Schema/catalog/default checks, foreign-key closure, tenant boundaries and file hashes are verified. The baseline is explicitly restricted material, not claimed to be fully anonymous. Actual extended contact/endpoint and schema/default scans found no retained nonempty sensitive columns or review findings.

Reset verifies fixed container/network/volume/database identities, independent JWT configuration, JAR/property hashes, reviewed pgvector digest and all 42 installed dependency files, complete table contents and schema hash. It stops only the task Java node, restores data transactionally, resets sequences, clears the task Redis and session attachment directory, then records ENDED. PostgreSQL/Redis volumes remain intact. No production transaction deletion, Docker daemon shutdown, WSL shutdown or other-project container operation is part of reset.

Java 10040 / Redis 6387 retain loopback-only HostConfig bindings; PostgreSQL has no binding. Internal-network HTTP uses fixed Docker exec curl with private headers/body on stdin and does not depend on host port mapping. This avoids the observed Docker Desktop internal-network port limitation. curl configuration is disabled by `-q`; origin, protocol, route, headers, size, redirects and timeouts are bounded. Startup waits at most 360 seconds.

## Verification at checkpoint

- Java `F006SandboxGatewayContractTest`: 23 passed, zero failure/error/skip; package successful. Includes real Spring component scanning, disabled mode, cookie identity, write fence, exact pure parse exception and role/username drift.
- JAR SHA-256: `02DB8FADD615FC1354427F51C5E1EF9B0633001D333BEA572C5ADF5187BF9DCF`.
- Python unittest discovery: 37 passed (`sandbox-python-tests-final37.log`), including real HTTP manager/relay tests, lifecycle, failed cleanup, attachment cleanup, in-flight drain, wrong actor, full dependency hashes, private roots, schema proof, internal transport, legacy JDBC preparation rejection and node-token renewal.
- Frontend targeted Vitest: 29 passed / 6 files (`sandbox-web-tests-final29.log`), including preparation routing, account switching, hydration, cache scope, deferred API-emitting layout children, cleanup lifecycle and resuming already-confirmed material picking.
- Final Vue type check (`vue-tsc -b`, 8 GiB Node heap) and production Vite build passed after the exact authorization-read addition.
- Source risk review: P0=0, P1=0; this is source review only.
- Real browser login, READY and one complete 24-step chain verified at `http://127.0.0.1:5186/demo-flow`; its same-run checkpoint was recovered after ten already-verified frontend steps. Final SO/PO: SO-20261105-0002 / PO-20261105-0002; batch PB-PLAN-1793878182044-3BD323D2-531, 4 boxes. The completed result remained viewable before the end action. End restored exact contents/schema of all 618 public tables, baseline hashes, Redis and attachments; all ended-session proxies rejected access. This is not claimed to be an uninterrupted fresh round.
- PostgreSQL URL pins `stringtype=unspecified` because PrimaryDataSourceConfig does not bind Hikari-specific driver properties. The old exact URL remains accepted only for identity/reset/cleanup; preparation rejects it before any slot operation.
- Preparation adds one disabled-login isolated finance-role fixture for the genuine independent approval route; reset removes it. The presenter remains f006_admin. A deliberately expired disk token was used to restart the clean manager; the next READY and approval steps succeeded with per-preparation token renewal.
- The second browser campaign stopped after step 10 when local test processes disappeared. Manager restart correctly marked its active session FAILED. Explicit end then restored all 618 table contents/schema, baseline/physical identities, empty Redis/attachments and rejected all nine old sessions. A fresh full round is being rerun; two complete rounds are not yet claimed.
- Two non-sandbox activity-event POST attempts during round transition received relay HTTP 403; they were blocked before forwarding, not successful production writes. Original request-attempt evidence is retained separately from response status evidence.
- Physical reset/content proof succeeded on preparation failures and on cleanup after real purchase creation plus a failed sales step. The stopped, clean slot was explicitly rebound to the corrected JDBC properties with all other physical identities unchanged. This is not a substitute for two successful complete business rounds.
- `check_dispatch_active.py` and `git diff --check` passed.

Private logs/screenshots and runtime configuration are under the user's private `Documents/F006演示-20261105` task directory and are not committed. Early unused private exports were retained after automatic approval rejected their deletion; they are not runtime baselines.

## Release boundary

Draft PR: https://github.com/cretasfuture-ai/cretas/pull/1477 . No merge or deployment is claimed. Fresh origin/main ACTIVE still contains `SEC-PLAN-AUDIT-MAIN-FREEZE-20261105`; this branch must not bypass that owner lock. The local development relay is not a production deployment. Formal production still has the older real-data flow until an authorized release completes. Menu-shortcut PR #1470 is separate and remains unmerged.

## Next acceptance

Finish two browser-driven 24-step rounds. After each user-facing end action require ENDED, unchanged physical identity, exact full table/schema baseline, empty Redis and attachment root, old-session proxy rejection and no production business mutations. Preserve screenshots and structured evidence; report any failed step as failed. Freeze final source and reuse completed checks unless source changes or a concrete failure requires a scoped rerun.
