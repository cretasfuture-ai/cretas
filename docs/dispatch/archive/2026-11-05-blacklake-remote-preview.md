# BLACKLAKE-REMOTE-PREVIEW-20261105

- Owner root; base 1a5db0c8adf896247d665f347664c0aabdff8a65; PR #1390. User explicitly requested remote access to this 六膳门 prototype without account/password.
- Independent HTTPS share path on test.cretaceousfuture.com. The unpredictable share path is stored outside Git; the root and old login routes do not expose the prototype. No production Cretas app release, database migration or upstream switch.
- Public Vite mode uses BASE_URL for assets, session metadata and analytical requests. Browser receives viewer metadata only, never a Cretas/Blacklake credential. Existing local mode keeps its original guarded session path.
- Standalone gateway binds local 5188 and forwards only allowlisted analytics to local 8095 using a server-only, verified test-factory session. The reverse SSH listener is remote loopback 19378. Factory scope remains BLACKLAKE_POC_TEST. No Blacklake business writes or public follow-up-record writes.
- POST contracts positively validate each route and nested history/period fields. Bodies are bounded; force is disabled in shared period requests. Read and analysis execution slots are separate; pending backlog is bounded with retry responses. Edge pressure controls apply to API requests, not initial static-resource loading. There is no daily/monthly analysis allowance.
- Credential/contact/person fields are removed; formatted contact strings are redacted. Aggregate material/labor costs and audited business chart series names remain intact. Raw employee/payroll APIs are never exposed.

## Verification

- 25 Python tests (19 gateway + 6 encrypted-session tests), 24 frontend tests, Vue typecheck and blacklake-public build passed. PowerShell parsing, shell parsing, dispatch hygiene and diff whitespace checks passed. Final source/artifact hashes and exit codes are in the private receipt.
- Real server-side broker returned the fixed test identity and a 26-order report. Actual 26-order snapshot audit preserved all tested cost fields, reduced contact matches from 1 to 0 and preserved chart series names.
- Actual GET families checked: complete cost, analysis, traceability, period list, period detail and schedule. Each response passed contact-field scans. Real cost follow-up succeeded using saved evidence (zero new model tokens). Model-generated section follow-up and new period submissions retain their contracts but were not invoked as extra acceptance traffic.
- Public browser: direct entry without login form; product to GD202610310003 work-order drill-down; cost explanation; successful follow-up; inventory balances; public follow-up-record buttons disabled. UI receipts are local.
- Negative checks: domain root 404; old local-session route 404; Origin-less public-session POST 403; no session cookie or JWT issued to browser.
- Full stop/reopen was exercised: HTTPS restored to retired 410, local 5188 and remote 19378 released, then task/tunnel/test-vhost restored. Production admin/API/upstream configuration hashes unchanged.
- During acceptance, 5179 was found occupied by an existing SmartBI page and was left untouched. Dedicated port 5188 and service-identity verification prevent exposing that unrelated service.
- Scheduled tasks run outside desktop package file virtualization. The encrypted config is resolved to its existing physical path; it is not copied or logged. Runtime/state use a stable D: directory.

## Operations and recovery

- Runtime: D:/CretasSmartBILab/remote-preview-runtime/. Sharing metadata and process identities contain no API credential. Acceptance artifacts: D:/CretasSmartBILab/local-analyst-distillation/remote-preview-20261105/.
- Windows task: Cretas-Blacklake-Remote-Preview. Starts at user logon and reconnects its own gateway/tunnel. The Windows host and existing isolated Java/Python test services must remain running; it is not an independent hosted backend.
- Stop: run scripts/windows/blacklake-remote-preview.ps1 -Stop. It revokes HTTPS first, requests graceful cleanup, then uses PID/start-time/command-line identity checks and verifies both listening ports are closed. -Uninstall also removes the scheduled task.
- Resume: read the local sharing metadata and run the script with -Install -Dist and -PreviewPath, then scripts/deploy/publish-blacklake-preview.ps1 -ReceiptRoot. No password is supplied to these scripts.
- Only the retired test-domain Nginx vhost is changed. Publisher requires a validated 410 backup; malformed configuration rolls back before activation. Disable verifies the 410 response with bounded reload propagation checks.
- All implementation, builds, private sharing settings and receipts remain on this Windows host. No paid teacher/model calls or Mac mini compute were used for this change.
