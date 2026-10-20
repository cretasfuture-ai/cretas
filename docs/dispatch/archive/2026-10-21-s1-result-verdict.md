# ERP-S1-RESULT-VERDICT-20261021 — queued

Owner `01a15272-374d-72c0-bb20-cdfa0bd823f1`, parent `ERP-STAGES-20261019`, registration Base `1b9f712ecf275fe7f784e58174f90e8e1b9c68bf`. Actual implementation Base must be refreshed after S104 releases its ToolDispatch scope. This queue entry does not release the existing in-flight PR1066 file lock.

Root's latest interface decision: malformed/missing-success normal result audit/cache acceptance is a pre-existing Base gap. Supplier81cd returns explicit Boolean false and rejects correctly. General result hardening is independent; it does not block supplier81cd or justify cancellation/rerun of Full39462429957.

Exact planned write scope after S104 release (two files):

1. `backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/ToolDispatchService.java`
2. NEW `backend/java/cretas-api/src/test/java/com/cretas/aims/service/execution/ToolDispatchResultVerdictContractTest.java`

Minimal contract: accept normal tool results as successful only under explicit valid positive success verdict; absent/malformed/negative verdict must retain accurate failure in dispatch audit/cache/replay. Cover missing, malformed, explicit false and valid positive controls through real methods. Do not change identity/RBAC, supplier availability, production roles/configuration, Entity/Repository/migration, Gateway/Skill/scheduler or unrelated tool contracts. Claude S6 remains excluded from ToolDispatchService.

Target acceptance, centrally serialized on the approved Java resource after source freeze:

```text
backend/java/cretas-api: mvn -B test -Dtest=ToolDispatchResultVerdictContractTest,SupplierComparisonDispatchContractTest,ToolDispatch*Test
```

Retain before-fix red, restored green and applicable mutation with unchanged assertions; bind source/test hashes, actual exits/counts and baseline failure evidence. Required CI/release checks apply to changed content; reuse proven unchanged content. Batch compatible source/tests before one necessary final Full, never duplicate unrelated full measurements for docs or this existing gap. Ordinary PR/manual exact-head merge, official release and original QA affected business acceptance remain separate. No implementation/tests/commit/PR/deployment is claimed by this registration.
