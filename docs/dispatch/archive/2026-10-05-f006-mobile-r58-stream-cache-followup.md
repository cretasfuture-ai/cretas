# F006 R58 stream cache replay follow-up

Task: `F006-MOBILE-R58-20261005`; Base: `b0e5aa97b287ebc1976aabda4da83541b53f5d9c`; Owner: root; queued, no source lock yet.

Actual immutable v3/v4 class bytes match. Offline real SseStreamingService + SemanticCacheServiceImpl with repository I/O fixtures replayed the same boss A+B rows and unitPrice for warehouse workers A/B and quality_manager (3 unsafe actors). Boss cached-emission positive and foreign-factory cache-miss + fresh NO_PERMISSION negative controls passed. Replay permission checks/tool execution/provider/network/business mutations were zero. Private evidence: r58-private-sse-cache-behavior-red.json, R58SseCacheReplayProbe.java, attempt1 compile/execution logs. This is not online leakage evidence.

Source: stream cache execution-result hit deserializes and emits result before current intent admission or tool/Gateway execution. Cache repository key is factory + input hash; no user dimension. REST handleSemanticCache does not replay execution result. F006 inventory skill metadata is enabled, but direct INVENTORY_ANALYSIS intent is absent; natural route/flags/provider selection still need acceptance.

Next: after R56 executor relinquishes source/target, register a narrow SseStreamingService + cache admission contract and existing relevant fixture scope. Shared cached business execution data must not replace fresh identity, permission, row, price or W0 checks. Reuse existing SSE harnesses; pre-fix assertion RED -> GREEN -> meaningful mutant RED -> restored GREEN, actor denied/SELF/boss/tenant/cache-hit controls. Preserve conversation, restaurant, Factory Pack, Mini cancellation and W0 behavior. Root adds no source writes while current R56 Maven is running.

Source scope registered: root owns separate `C:\Users\Steve\cretas-f006-mobile-r58-20261005`, branch `codex/f006-mobile-r58-sse-cache-admission-20261005`, same Base. This replaces the planned wait for R56: physically separate source and Maven target avoid contention. Exact allowed3 files:
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/SseStreamingService.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/execution/SseSemanticCacheAdmissionContractTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/execution/SseStreamingServiceRecentTurnsTest.java`

Factory-wide cache hit is metadata only; continue full fresh recognition, clarification/W0 and current admission/dispatch. Never trust old intent/parameters/result rows. Cache only recognition result, never business response. Real cache repository I/O plus SSE event/dispatch assertions and existing boundary suites; no provider/device/network/business writes. Root only ACTIVE writer; integrate after workers relinquish.

Frozen final candidate review and remaining gates: see `2026-10-05-f006-mobile-r54-r56-r58-acceptance.md`. Earlier isolated receipts remain historical; final source/selector SHA receipts supersede them only after the matching final gate passes. Root sole final source Owner; executor scope released.
