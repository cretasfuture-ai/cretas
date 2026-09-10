# DRIVE-20260910-B06-SHIPMENT-READ

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
