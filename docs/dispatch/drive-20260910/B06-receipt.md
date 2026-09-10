# DRIVE-20260910-B06-SHIPMENT-READ

## 2026-09-11 cross-review P1 correction

Code commit 0c1757acc853b53bdfe2b9eabb12f061bccfad77 fixes the independently reproduced legacy footer tenant mismatch. Parent list now keys LegacyShipments by factoryId, replacing the entire legacy component including real summary state and dialogs; old async responses can only update the unmounted instance. Shared helper unchanged. Legacy summary errors now render explicitly and hide footer/AI actions instead of showing an empty summary.

New tracked test: web-admin/src/views/sales/shipments/__tests__/legacySummaryIsolation.spec.ts mounts the actual parent, LegacyShipments, TableFooter and useListSummary; only network/store/router/dependent actions are mocked. Two regression cases: A summary pending → B list/B summary → A 999 arrives → B footer remains; A summary already loaded → B pending removes A → B 403 displays an error with no stale footer. A and B each issue their own summary request. This converts the scenario in B01's independent B06-cross-review.md into positive isolation assertions.

Final verification: target Vitest command below exit 0, 3 files / 10 tests (original 8 retained plus 2 isolation cases), 13.22s at 00:01:00. Application vue-tsc --noEmit -p tsconfig.app.json exit 0. Existing browser.mjs rerun exit 0 (7 fixture checks, zero page errors), result.json refreshed. git diff --check passed. Preview 5188 stopped. No formal build, PR, merge, deployment, shared helper or ACTIVE edits.

Drive only main!F82:G82 appended correction/commit/validation on 2026-09-11, status still 已修复待验证. Readback exact values passed; A-E and all A-G formats/validation/notes unchanged. Evidence .playwright-mcp/b06/p1-readback.json. Production business writes remain 0; production acceptance still outstanding. Original receipt below describes the preceding implementation, superseded by this P1 correction for complete legacy footer isolation.

- Owner: task 01a08b80-5014-7453-97d8-3541ba868d2b
- Base SHA: e03fc7cf0c7c100d35cb8e907067e4b7e7e118af
- Worktree: C:/Users/Steve/cretas-drive-20260910-b06
- Branch: codex/drive-20260910-b06
- Code commit: b16b1c944631f7cf058f7adcefc8e8e081b698b8

## Implementation

Default tab uses existing GET /sales/deliveries with one-based pagination. MASTER, SHIPMENT/parentDeliveryId, and LEGACY single deliveries are distinguished. Product quantities retain individual units; no amount/quantity aggregation. Navigation uses salesOrderId only; unlinked records cannot navigate. No formal filters, old shipment summary or single-product return action. Legacy component mounts only in the history tab. Request generations invalidate stale formal/history list responses on factory change or unmount. Factory change also clears open legacy dialogs.

Changed files:

- web-admin/src/views/sales/shipments/list.vue
- web-admin/src/views/sales/shipments/FormalDeliveries.vue
- web-admin/src/views/sales/shipments/LegacyShipments.vue (existing legacy view relocated, request isolation/error state added)
- web-admin/src/views/sales/shipments/__tests__/formalDeliveries.spec.ts
- web-admin/src/views/warehouse/shipments/__tests__/legacyShipmentWriteFreeze.source.spec.ts (reads relocated legacy component)

## Validation

- `npx vitest run src/views/sales/shipments/__tests__ src/views/warehouse/shipments/__tests__/legacyShipmentWriteFreeze.source.spec.ts`: exit 0, 2 files / 8 tests passed. Includes pagination, role/unit display, correct order route, no-order disabled, empty/error/retry, malformed response, factory response race, tab remount race, legacy write freeze.
- `node node_modules/vue-tsc/bin/vue-tsc.js --noEmit -p tsconfig.app.json`: exit 0.
- `git diff --check`: passed. Code reviewed against entity fields, PageResponse and original legacy component; no backend or shared API changes.
- `node .playwright-mcp/b06/browser.mjs`: exit 0, actual app at isolated localhost:5188, fresh browser context, all API calls locally fulfilled; external origins aborted. Seven checks passed, no page errors. First run stopped at existing 403 modal intercepting retry; harness now acknowledges the modal normally, second run passed. No product workaround was added.
- Browser evidence: C:/Users/Steve/cretas-drive-20260910-b06/web-admin/.playwright-mcp/b06/{browser.mjs,result.json,01-formal.png,02-page2.png,03-legacy.png,04-empty.png,05-error.png,05-403.png}. Formal screenshot visually inspected. Own preview stopped after verification.
- Production business writes: 0. The sole POST in fixture requests is the existing legacy list-summary query, locally mocked. No production connections.

## Drive readback

Only main!F82:G82 in spreadsheet 1TyIqNP_z8bBiPyLeXbr3YYvwE1WCuHFhgYAHiJC4w4M updated. F82 is 已修复待验证; G82 appends implementation/tests/code commit and explicitly unmerged/undeployed/unverified production state. Latest existing notes preserved. Readback compared A-E and all A-G userEnteredFormat/dataValidation/note fields: unchanged. Exact F/G values matched. API format inspection used; no Google-rendered visual check. Evidence: web-admin/.playwright-mcp/b06/drive-readback.json.

## Remaining / next

No PR, merge, release Web build, deployment, production data inspection or user acceptance performed. No Maven needed (frontend only). Coordinator should integrate after PR762 release, review and run one unified final Web release build. Production existence of formal records remains unverified. No changes to ACTIVE, warehouse business view, backend, permissions, shared files or old shipment write endpoint freeze.
