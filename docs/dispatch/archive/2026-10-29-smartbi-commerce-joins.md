# SC29-COMMERCE-JOINT / SC29-COMMERCE-PG

Owner: Root; Base `166e3f3d7faf803da32a892c6db706ccc00cd7e7`; status review. Scope is released on merge; remaining live acceptance stays under `SMARTBI-F006-QA-20261028`.

Adds fixed sales-order-line → confirmed delivery → finished-goods-batch evidence and customer → explicitly configured order channel → product analysis. It uses stored IDs, same-factory/customer/SKU/unit checks, delivery conservation and ownership guards. Customer acquisition source is never substituted for an order channel. Unavailable channel configuration does not remove otherwise qualified sales-stock facts. Amounts require price permission and order-line reconciliation; order totals are never allocated across products.

Original details preserve separate ORDER_LINE, SHIPMENT_RELATION and INVENTORY_BATCH grains. Current batch balances remain independent of order lines and can be recomputed without duplication; they do not reconstruct historical stock or imply availability to a particular customer's order. Colliding names do not merge distinct IDs. Amount-candidate identity also includes the original quantity unit. Unsupported point drilling is explicitly disabled in both local and shared detail contexts, while original whole-chart rows remain available. Upload dimension and financial guards are unchanged.

Percentage quality/plan charts now bind the original quantity unit explicitly before snapshot generation. Null/blank finished-goods identity or physical-unit rows become coverage gaps; a malformed current snapshot cannot silently produce a complete stock KPI.

Frozen validation before main integration:

- Independent risk review: original blockers retained, final source review PASS including the last null-identity defect.
- Related Python target tests: 47 passed.
- Actual disposable PostgreSQL/RLS target: 9 passed, zero skipped. Covers missing/shadow channel columns and definitions, MASTER, tenant/ownership/deletion, split deliveries, moneyless column privileges and allocation precedence.
- Final factory suite after main integration and allocation fix: 432 passed, one unchanged independent-schema case skipped with its prior proof retained; source hashes unchanged. All nine new PostgreSQL tests ran in this lane.
- Actual deployed PostgreSQL schema: six candidate source SQL statements ran in an F006 read-only transaction as restricted `cretas_user` (not superuser/bypass); base/channel each six rows, shipment six and inventory seventeen. Zero business/schema writes. This validates source SQL, not deployment or UI acceptance of this candidate.

Live preflight exposed a normal-storage defect: Java records the first deducted batch on the delivery item for traceability while retaining complete allocations. The source now prefers active same-factory allocations, using direct pointers only when none exist; invalid active allocations never reactivate the pointer. Raw duplicate-path rejection remains unchanged. Real PG proves mirror/direct-only/invalid/soft-deleted/cross-factory/multi-batch cases. Independent review of this final delta passed.

The same F006 rows now yield three unit-separated sales-stock candidates and one explicit-channel amount candidate in the frozen local builder. Three legacy box-versus-inner-unit relations and five missing channels remain explicit gaps. Candidates state that validated rows do not represent all sales. No business data was repaired or seeded to obtain these results; live deployment/UI acceptance remains separate.

The skipped case, earlier failed fixture/tool runs and prior failed review snapshots remain in private evidence. Java/Entity/Repository, migrations, permissions and native source are outside this change.
