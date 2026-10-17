# 采购异常补发：原采购单续收入口与真实 UI 闭环

- Task: `PURCHASE-EXCEPTION-RESUPPLY-UI-20261017`; Owner: root.
- Base: `a2afbe1ddecc6bb14d2bae2112e35fd1d0d0d80e`.
- Scope: exception-page next action and history-detail navigation, five mounted interaction tests, this acceptance note. Shared receipt-variance correction is isolated in PR #1011 and must merge first.
- Reuse: existing loopback Web, Java and PostgreSQL; existing private test attachment bucket and PDF. No production access/deployment, migrations, Docker or WSL.

## Behavior

REQUEST_RESUPPLY explains that supplemental receiving uses the original PO's remaining quantity and does not create another PO, inventory or payable. A successful decision offers a next action to the factory's canonical warehouse receiving page with the exact purchaseOrderId. A historical exception detail provides the same continuation. Users without warehouse access get a handoff explanation instead of a navigation action. Completed or closed orders retain the existing no-task explanation and cannot create another receiving task by navigation.

The existing warehouse approval, receipt attachment, confirmation, row-lock, stock posting and payable contracts are reused. This change creates no new write API and does not automatically issue another purchase order.

## Real UI result

1. Copy a PO through UI, submit, and approve through an independent account's OA task.
2. Receive 8 of 10 boxes of flour and all 10 packs of bags with a receipt document. The source PO remains PARTIAL_RECEIVED and the exception shows shortage 2 boxes.
3. Record REQUEST_RESUPPLY. The success prompt navigates to the exact PO's task, showing received 8 and remaining 2 boxes. Another fixture verifies leaving the prompt, refreshing, and continuing from historical exception detail.
4. Create the supplemental receipt for the remaining 2 boxes. Two real UI confirmations of the same receipt reach a before-send barrier: one returns 200 and one 409. Exactly one 20 kg batch and one 100.01 payable posting are created; the supplier balance increases by 100.01 once. The amount follows the stored PO price/tax rounding; it is not approximated as 100.00 in the assertion.
5. The first fixture exposed a false shortage after completion. PR #1011 fixes the shared cumulative-received comparison. Only that affected 8+2 receiving path is repeated on the corrected backend: the PO completes, the sole original exception remains RESOLVED / REQUEST_RESUPPLY, and no new shortage is generated. This fixture has two confirmed receipts, one original PO, and no new return orders.
6. Completed-order navigation shows no available receiving task. Inventory UI agrees with SQL: flour 587 kg and bags 750 个 across the retained fixtures; supplier UI agrees with the final 3154.01 balance.

The failed pre-fix fixture and its spurious exception are retained, not deleted or rewritten. The two-confirmation stock/AP proof is reused after the narrow exception-generation correction; the corrected fixture verifies the new no-false-exception assertion.

## Restoration and evidence

Temporary procurement permission was granted and removed through Canvas UI (200 each). The original factory override map, settings, organization/roles and data-scope configuration match the baseline. The fresh existing-factory control is unchanged across all 505 compared tables.

Private evidence in the existing assembly-integration-assessment-20261015/target-ui directory:

- resupply-real-ui-evidence.json, resupply-assertions.json and return-db-resupply-*.json.
- resupply-decision-next-action.png, resupply-remaining-task.png, resupply-concurrent-confirmation.png, resupply-completed-no-task.png.
- resupply-fixed-history-task.png, resupply-fixed-completed.png, resupply-final-inventory.png, resupply-final-supplier.png, resupply-permission-restored.png.
- resupply-control-comparison.json: unchanged=true, 505 tables.

The original network capture retains two client-errors telemetry POSTs with 500 during the test-runtime restart interval, before the corrected fixture. Their root cause was not established and telemetry delivery is not accepted here. The receipt/decision/accounting paths have the expected statuses, and the corrected fixture completes without a failed business write. Stale dialog/link locators and Canvas loading timing were corrected without replaying completed writes.

## Necessary verification

- Five mounted interaction tests: historical precise navigation is zero-write; no warehouse access has no link; other decisions have no resupply action; successful decision submits once and navigates; failed decision does not navigate or lose its form.
- Final related Web slice: 12 tests in two files pass. Production Web build succeeds (1m22s). Source/test content is frozen; these local checks are not repeated for documentation or PR updates.
- Shared Java regression/mutation and final Java CI belong to #1011. UI PR runs its own applicable exact-head Web/metadata checks, followed by manual merge only after #1011 is merged.
- No new full local product suite, production deployment or production acceptance. Automated supplier notification and automatically generated replacement POs remain outside this slice; the accepted replenishment path uses the original PO.
