# F006 R55 authorization availability — 2026-10-05

Owner /root DIRECT; Base c0417f2e5665b849cc65cb773d0f3728145a137e. The frozen R51-R53 candidate is blocked by independent P1: canonical module-list repository failures are converted into absent layers, which can restore hardcoded allows over configured denies. No trusted build, push, PR or production action has occurred for that candidate.

Exact additional/reopened source scope:

- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/PermissionService.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/PermissionServiceImpl.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/gateway/ToolPrincipalPolicy.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/gateway/ToolPrincipalPolicyTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/gateway/ToolPrincipalModulePermissionsContractTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/gateway/ToolGatewayPriceBoundaryContractTest.java`

Expose a canonical strict authorization snapshot, reusing the existing layer/axis/level logic without a second role matrix or duplicated lookup. Throw on unavailable L1/L2 repositories or query errors, distinguish known empty successful queries from outages, and have Gateway principal rehydration reject unavailable/null snapshots. Preserve normal precedence, wildcard, role grants and known-absent fallback. Do not alter unrelated login/list resolver and per-cell cache semantics in this batch. Their existing outage fallback remains a separately tracked source risk, not claimed repaired.

Before implementation, actual old Gateway/canonical permission-repository failures must yield behavioral RED for configured L1 and L2 denies (not missing-new-API compilation). GREEN must reject before business query and naturally clean state, then reuse the same worker after repository recovery, preserving known absent fallback, explicit grants/denies, boss and masked-price controls. Reuse R51 fixture and contracts; update only related mocks/price fixture to supply successfully queried empty authorization repositories. No Entity/Repository query declarations, grants, JWT, global config, provider/device/DB writes or deployment edits. Independent final review and one trusted selector build follow all fixes; actual fast/full CI still mandatory.

Acceptance: existing validated 35-class selector plus `PermissionServiceImplResolveModuleLevelsContractTest`; confirm that class exists before use. Targeted RED compiles existing Gateway contract tests against old API. Sequential Java21 Maven in this worktree; full logs and structured receipts private. Root owns docs, integration and release; no concurrent source owner.

Candidate source frozen and scope released in this final candidate commit. Real L1/L2 query-outage behavior RED2 (SUCCEEDED instead of DENIED), restored five-class regression GREEN35; known absent, explicit deny, L2/L1, boss/worker price controls and same-worker recovery retained. Two final null/unsupported API unit assertions await the final trusted build after review. R56 records remaining per-cell/login outage fallback. No trusted build/PR/merge/deploy claimed. Reopen scope before further source edits.
