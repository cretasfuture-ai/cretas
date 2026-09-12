# ENH-FACTORY-AUDIT-DETAIL-20260912

- Base: `a1428f86110c242cfceb01f625ee6a1cef1f6af0`
- Coordinator: `/root`; branch `codex/factory-audit-detail-20260912`.
- State: review; implementation frozen and scope released for publication. Merge and deployment are evidenced by GitHub and the release receipt, not this pre-publication record.
- User scope: improve factory operation log detail and reliability after warehouse incident investigation.

## Completed ownership scope

Root owns new Java `audit/inventory/**`, `controller/datacenter/OperationLogController.java` (only long-ID audit lookup), `integration/InventoryAuditTransactionContractTest.java` and inventory audit tests; Web `views/system/operation-log/**`; `.github/workflows/java-gate-pr.yml` (only this inventory PG test selector); `docs/ops/factory-operation-audit.md` and this task's dispatch entries.

CLIENT owner `/root/audit_client_finish` owns new Java `audit/client/**` and corresponding tests, Web `api/request.ts`, `main.ts`, `utils/clientErrorReporter.ts` and client reporter tests. The earlier executor was interrupted before its receipt; this owner continues the retained files. No other task changes may be reverted.

No business services, Entity/Repository mappings, migrations, security/permissions configuration, credentials, OS clocks or AI quota audit changes. No fabricated production stock writes.

## Acceptance

- `mvn test -Dtest=InventoryAuditTransactionContractTest,ClientError*Test -Dspring.profiles.active=test`.
- Same selector under `scripts/ci/run-with-test-postgres.sh`, with actual primary persistence configuration, transaction rollback, audit failure rollback, both inventory ownerships, warehouse quantities, source/target tenant isolation, detail records, soft delete and long ID readback.
- Target client reporter/request Vitest tests and Web TypeScript check.
- Read-only independent risk review before final release build; normal required CI checks, exact-main release scripts and deployment receipt.
- Initial testing exposed an unwired Hibernate customizer in the application's custom primary factory; registration moved to the actual factory initialization and is observed in real PG startup.
- Final development PostgreSQL run: 13 inventory + 4 client tests passed, zero failures/skips (`2026-09-12 17:38:19 +08`). Includes actual 30-row JDBC batching with complete audits, new parent/three children in one flush, mapped numeric precision versus SQL readback, and swallowed audit failure rolling back stock.
- Web reporter: 10 tests passed; shared request regression: 10 tests passed; final client typecheck passed. Full Web build passed before the final stack-parser refinement; the release build must build the final source.
- Required PR checks and clean candidate release build remain publication gates. No production business mutations were used for verification.

Evidence directory: `D:/Temp/cretas-factory-audit-detail-20260912` (local, excluded from Git).
