# 采购补发验收：两次错误上报 500 的归因补记

- Task: `PURCHASE-UI-TELEMETRY-EVIDENCE-20261017`; Owner: root.
- Base: `92d309afc5ba893dc572e6dfa79f9c4165d97ab7` (PR #1012 merged).
- Scope: resolve the two previously unclassified client-errors responses in the #1011/#1012 private acceptance capture. Documentation only; no product source, tests, runtime configuration or production changes.

## Historical cause confirmed

The retained Vite proxy error log records both requests:

| Time on 2026-10-17 | Request | Actual proxy error |
| --- | --- | --- |
| 13:43:39 | POST /api/mobile/{testFactory}/client-errors | connect ECONNREFUSED 127.0.0.1:10051 |
| 13:44:39 | POST /api/mobile/{testFactory}/client-errors | connect ECONNREFUSED 127.0.0.1:10051 |

The same private Java runtime log records startup beginning at 13:43:19.242 and completing at 13:45:12.156. Both failures fall inside that interval. Vite could not connect to the restarting loopback backend; these were proxy responses, not exceptions returned by the Java collector or procurement/accounting services. Notification polling also encountered the same connection refusal. The original failed capture remains unchanged.

## Narrow recovery verification

The existing services were reused without another restart. A clean browser session logged in through the real UI as the expected synthetic factory actor. A controlled ErrorEvent was dispatched to the application's installed window listener on the Canvas page; the normal clientErrorReporter sent it through the real Vite proxy to the Java collector.

- Collector response: HTTP 200 / success=true.
- Expected safe metadata was received: window_error category, current route, synthetic script basename and line/column.
- The raw synthetic error-message marker was absent from the request body; no credentials, payload or headers were stored in the evidence.
- Factory-scoped operation_logs count before and after the diagnostic remained 651. Browser diagnostics were not inserted as business operations.
- No procurement, stock, payable, permission or configuration action was performed.

This verifies healthy **browser-to-collector intake**. The private runtime explicitly sets its com.cretas.aims logger to OFF; persistent diagnostic-log delivery was not tested and is not claimed. Production telemetry, production deployment and production acceptance remain outside scope.

## Evidence and checks

Private artifacts in the existing assembly-integration-assessment-20261015/target-ui directory: telemetry-root-cause-assertions.json, telemetry-restored-browser-evidence.json and telemetry-restored-browser.png. The source proxy/runtime logs are retained there. Assertions confirmed the two connection-refusal lines, restart window, restored HTTP 200, omitted raw message and zero business-audit-row delta.

All product source and test blobs are unchanged. The accepted #1011 Java/PostgreSQL and #1012 Web evidence is reused; no product tests, full build or procurement UI chain is repeated for this documentation update. Applicable documentation checks are verified before manual merge; no auto merge or production work.
