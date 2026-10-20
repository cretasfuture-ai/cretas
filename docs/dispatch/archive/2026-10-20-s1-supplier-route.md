# Supplier comparison route repair

- Task `ERP-S1-SUPPLIER-ROUTE-20261020`; parent `ERP-STAGES-20261019`.
- Owner `01a15272-374d-72c0-bb20-cdfa0bd823f1`, Base `1b9f712ecf275fe7f784e58174f90e8e1b9c68bf`, branch `codex/s1-supplier-route-20261020`.
- First exact scope: `backend/java/cretas-api/src/main/java/com/cretas/aims/config/IntentKnowledgeBase.java`; new `backend/java/cretas-api/src/test/java/com/cretas/aims/config/SupplierComparisonPhraseContractTest.java`.
- First target: existing serial-lock Maven wrapper, `-B test -Dtest=SupplierComparisonPhraseContractTest -Dspring.profiles.active=test` in an isolated Aurora task directory. Prove original real methods red before modifying phrase routing; same-class ranking controls stay green. Target-only tests during development; freeze all compatible changes before required final Java checks.

The original buyer UI now sends manual queries correctly, but price phrases still map to `SUPPLIER_RANKING` and receive its administrator-only refusal. A read-only production config query also found no `SUPPLIER_PRICE_COMPARISON` row for F006/platform. A phrase edit alone cannot prove the complete Tool route. Further metadata/tool/test files must be named here before edits, preserving existing inactive/tenant/permission restrictions. No ranking permission expansion, production config seed, role borrowing, false quotations, shared permission-engine/Entity/Repository/migration edits are included.

Original QA remains sole CSV/browser/device Owner. Actual requests, configuration readback, target red/green/mutation, final checks, normal PR/merge, deployment and original QA UI/SQL receipts remain separate under private `execution/s1-supplier-route-20261020/`. S1-04 is FAIL until original QA accepts the original criterion. Root scouts separately cover S1-07 and S1-02/10; do not duplicate that investigation.

## Registered READ wiring slice

The real initialized knowledge-base methods produced 3 failures / 3 ranking-control passes, exit 1, on the isolated Aurora serial Maven wrapper. Supplier price/quote questions selected ranking/list rather than comparison. Additional exact files now locked before implementation:

- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/IntentExecutionOrchestrator.java`: only the comparison metadata resolution seam.
- New `backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/SupplierComparisonReadIntent.java`.
- `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/crm/SupplierPriceComparisonTool.java`.
- New `backend/java/cretas-api/src/test/java/com/cretas/aims/service/execution/SupplierComparisonReadIntentContractTest.java`.
- `backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/impl/crm/SupplierPriceComparisonContractTest.java`.
- New `backend/java/cretas-api/src/test/java/com/cretas/aims/repository/config/SupplierComparisonIntentRepositoryQueryValidationTest.java`.

Use the existing code-owned missing-intent metadata pattern, not a production seed. Only the registered READ comparison tool may supply absent configuration metadata; preserve explicit configured inactive/tenant/role restrictions, and require existing procurement/finance permissions through the unchanged intent/central Tool gates. No ranking permission or shared permission engine change. Reuse the existing repository query without modifying Entity/Repository source. Typed unavailable result must preserve `success=false`, honest no-quote cause and tool identity; it is not a completed comparison. Targets add the named comparison metadata/tool contracts and real JPA repository test; PostgreSQL 13/17 verification and independent permission/tenant review apply before final delivery.

Independent review found descriptor inventory must track this tool's new explicit permission/version getters. Before edit, add exact scope backend/java/cretas-api/src/main/resources/ai/tool/gateway/tool-descriptors.yaml (only supplier_price_comparison row), and run existing ToolDescriptorInventoryDriftTest before merge. This is checked-in descriptor inventory, not production role/intent configuration or shared permission-engine code. Include original mounted-query wording with intervening 的 in actual phrase regression; preserve SUPPLIER_LIST/performance controls.


Registered dispatch failure slice before edit: backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/ToolDispatchService.java only normal-result audit/cache and cached status preservation; new test backend/java/cretas-api/src/test/java/com/cretas/aims/service/execution/SupplierComparisonDispatchContractTest.java. Independent review found success:false recorded/cached SUCCESS and cached replay top-level SUCCESS; accurate unavailable semantics require this seam. No shared permission engine, identity, Entity/Repo, query or production config changes. Worker supplier_dispatch owns exactly these two files; coordinator retains other files and all ledger writes. Actual-method red before mutation, then green/mutation; success/cache positive controls retained.


Descriptor target actually ran 4 tests and exposed the frozen explicit-override count baseline. Extend exact test scope backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/gateway/descriptor/ToolDescriptorInventoryDriftTest.java only counts for this new explicit comparison permission/permission-set/version override (+1 each); retain exact inventory parity and all other assertions. First combined target was 126run/1FAIL, this expected baseline drift only, preserved in private target-batch evidence.


## Frozen supplier candidate, separate from pending scheduling work

Supplier semantics, code-owned READ metadata, typed no-quote result and failed dispatch audit/cache handling are frozen. Supplier ranking permissions, production roles/configuration and the accepted frontend event repair are unchanged. The original buyer UI must still be independently accepted after deployment; current S1-04 and ERP-S1-SUPPLIER-ENTRY-001 remain FAIL/OPEN.

Evidence retained privately under execution/s1-supplier-route-20261020: original phrase RED 6 run / 3 fail, dispatch RED 3 / 2 fail, bounded development target 129 PASS (including parallel scheduling targets), final phrase mutant 7 / 4 fail, final dispatch mutant 8 / 5 fail, restored 15 PASS. Real PostgreSQL 13.23 and 17.11 each ran supplier metadata JPA 4 PASS plus the independent no-schedule readiness 1 PASS, no errors/skips; test DB product/major/driver/catalog and owned container cleanup are bound. Native fixture inserts test read authority, not AIIntentConfig Entity write compatibility. The earlier PG setup/type failure is preserved and is not a reproduced production Entity defect.

Checked-in descriptor inventory and its three explicit-override counts are updated exactly for this tool, preserving inventory parity. New metadata uses the existing procurement/finance read_write any-of policy; it does not claim all ordinary supplier GET endpoints have that policy, nor does it make requiredRoles jointly mandatory where existing permission-first semantics apply. Explicit inactive/foreign/configured metadata restrictions retain their current behavior.

Independent supplier source review found no remaining new deterministic blocker. Required final PR/Java Full checks, exact-head ordinary merge, official backend-only release and original QA real manual click/CtrlEnter plus F006 business SQL controls are still pending. No frontend or scheduling changes enter this supplier product candidate. Scheduling readiness/provenance and deferred grants retain separate in-progress scope locks and are not released by this candidate.

