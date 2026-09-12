# Visual acceptance follow-up, 2026-09-12

Base: `a1428f86110c242cfceb01f625ee6a1cef1f6af0`.
Coordinator integration: `codex/visual-round2-integration-20260912`.

## FIX-PURCHASE-EDIT-FEEDBACK-20260912

B02 candidate `5b61a63ee145c2c024bf13341c5d8cccb640fe71`; integrated as `c5aa93be6d38ddc5ee61a6fdc2f8ab0921f51236`.
Scope: procurement orders list and its explicit browser regression only.
An edit read failure displayed its toast before query cleanup. Navigation's existing global message cleanup removed it after about 1.3 ms. The page now awaits query cleanup before showing its persistent error or non-draft warning. Its read uses the existing silent-request option so one page-owned message remains; global guards are unchanged.

Validation: type check; two Vitest files, 11 tests; real Vue local browser fixture, four fresh-context cases (HTTP 500, unsuccessful response, non-draft, normal multiline draft). Negative messages remain visible at 4.5 s, occur once and can be closed. No business writes or page errors. Explicit Node browser fixture is not claimed as an automatic CI test.
Unified trusted Web build succeeded once: Web tree `ef6c656b01e2d885209dd2905948aa70870fb8bb`; index SHA-256 `6a29b76c7fd095a90c8e5eadb76022e3372e18e24b2a9fe2d36ccc81b2df6962`. Later harness/docs commits do not change that tree. Build is not deployment evidence.

## VERIFY-VISUAL-FOLLOWUP-20260912

B01 candidate `3d5128a370668bbdbfeabe649004d8a3dbee0332`; integrated as `47c5b3ee1d` with coordinator follow-up for asynchronous draft leave and retained failure evidence.
Scope: three opt-in scenarios, run-suite registration, fixture/unit tests and generated MCP entry. No product, mutation guard or read-only POST registry changes.

Validation: 33 unit tests; eight fixture cases, including wrong route, wrong selected tab, queue no-op, and delayed viewport autosave blocked before send. Coordinator's delayed-confirmation negative control reproduced TOOL_ERROR; asynchronous confirmation then passed. Failure evidence is retained rather than changing an unverified production outcome to PASS.

Production B01 run used one clean F006 browser: authentication 1, business writes 0, blocked attempts 0, HTTP/console errors 0. Five-page navigation and query-tab scenario passed. Workflow full-screen/AI collapse/exit/reentry preserved nodes and layout; fit is explicitly unverified. Date input normalization, invalid feedback and calendar recovery were observed, but the initial client-side leave timed out. Subsequent date-only evidence is retained separately. No save, native device, real AI execution or database persistence acceptance is implied.

The old local workflow fixture had no editable permission store. A new real VueFlow fixture establishes canEdit/canWrite=true: four layout states each wait 3100 ms without a write; a real pointer pan then causes one LOCAL mocked PUT. Local fit did not reproduce a write. This does not authorize a production fit or pan.

## Review and remaining work

Evidence root (local, untracked): `C:/Users/Steve/cretas-visual-acceptance-20260912`.
Reports: B01-followup-acceptance.json, B02-feedback-fix-receipt.md, B03-report.md, B04-report.md, B05-testenv.md and coordinator-review.md.
Thirty original Drive rows received dated supplements and score updates with readback before this integration. Original requirements remain open where only a sub-operation was verified. App byproduct SKU selection, native touch, actual persistence, AI execution, keyboard close/focus, cramped reference panes and continued-entry headers remain separately recorded.

These two implementation scopes are released when this integration merges. Other ACTIVE entries and worktrees are preserved. The coordinator records actual PR/CI/deployment and remaining acceptance outcomes in the local final receipt and original Drive workbook; this archive does not claim an unperformed deployment.
