# R12: merged order production action

- Task: FIX-MERGE-ORDER-STATE-R12-20260913
- Base: 730690eb2a74753af77cf1e44d452d1d495bdaf9
- Owner: current task. User explicitly transferred this narrow scope from the Workflow/BOM parent lock on 2026-09-13.
- Scope: sales detail action resolver, its order ID wiring, and focused tests. No API, Entity, Repository, migration, production data or other plan changes.

The corresponding canonical merged-order-state-readonly scenario and run-suite/generated-entry wiring are included. It inspects an existing active merged plan from the current list page and checks the associated order UI, or returns UNVERIFIED when suitable data is absent. Local actual-dist fixtures prove PASS and missing-data UNVERIFIED, with no writes. The response matcher was corrected to exclude HTML navigation responses; the initial fixture failure is retained locally. 83 harness tests pass. An initial build was rejected because the worktree changed while adding this scenario; it is not a deployable receipt. The final committed tree is built again without concurrent writes.

main46 reproduction: a merged plan retains the primary order line ID and display quantity. The extra order therefore found no matching line and incorrectly offered Start production. The backend batch-from-so path consumes the remaining matching product lines from every extra order; plannedQuantityForSalesOrderItem also treats that explicit extra-order/product membership as already planned. The UI now observes that same contract, with the current sales order ID supplied by the actual detail page. A matching SKU alone is insufficient. Primary-order partial quantities remain based on sourceDisplayQuantity; delivered quantities are subtracted from demand, and no-plan data never claims production completion.

Validation: 24 target Vitest tests pass, including six new cases covering extra membership, different products, same-product lines, mixed display units, partial primary demand, delivered quantities, cancellation/rejection/return, completion and permission/loading failures. Actual Vue detail with local fixtures passed active/completed/cancelled states and disabled controls, with zero business writes and zero mutation attempts. These are local fixtures, not production transaction evidence. Prior exact-base Java merge contract tests passed 16/16; Java is unchanged.

Detailed local evidence: C:/Users/Steve/cretas-visual-fixes-20260912-r3/.playwright-mcp/merge-state-r12/. Build, CI and release receipts follow the standard exact-main process. main46 remains open until its broader persisted flow and associated UI have production evidence. No whole-page visual score is inferred from three button states. This narrow lock releases on merge; the parent retains all other scopes.
