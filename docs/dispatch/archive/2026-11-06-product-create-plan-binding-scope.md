# FOOD-PRODUCT-CREATE-PLAN-BINDING-20261106

Base SHA:7872d98a83c370a4d284a071e9c65601cf4f5cbf. Owner Food root 01a1a954-13e5-7182-9572-00ff306c8d31 plus artifact_param_isolation. Worktree C:/Users/Steve/cretas-food-capability-gateway-20261106, branch codex/product-create-plan-binding-20261106, reusing the clean merged Canvas worktree and retaining its old branch. No conflicting ACTIVE owner was found. Parent delivery PR1510 remains frozen in its separate User/SOP worktree; this execution must not change that source or its metadata.

## Confirmed narrow P1 and scope

ProductCreate preview resolves defaults/source chain, but the controller token binds only raw request parameters. Execution resolves again and copyChain reads latest again. A source-chain/default edit between preview and confirmation can therefore change an unconfirmed plan. Gateway idempotency alone does not bind that business revision. Existing product partial-create/copy-failure behavior from1502 is valid and must be preserved.

Allowed production files under backend/java/cretas-api/src/main/java/com/cretas/aims:

- ai/tool/impl/dataop/ProductCreateTool.java
- controller/AiProductCreateController.java
- service/ProductWorkProcessService.java (immutable nested records allowed)
- service/impl/ProductWorkProcessServiceImpl.java

Worker may change only these four Java files and the exact tests below. No Entity, Repository/query, migration, global Gateway, PreviewTokenService, ToolDispatch, user-disable/SOP source, role/permission data or UI/config changes. Root alone later changes the two product_create metadata stanzas to2.0.0 after PR1510 is safe, integrates fresh main and owns their existing consumer tests/CI selectors. Keep HTTP_CONTROLLER only; AI_INTENT_DISPATCH is already denied and must remain denied. No capability registration or source-policy expansion.

Allowed tests under src/test/java/com/cretas/aims:

- ai/tool/impl/dataop/ProductCreateToolContractTest.java
- controller/AiProductCreateControllerGatewayTest.java
- service/impl/ProductWorkProcessServiceImplTest.java
- ai/tool/impl/dataop/ProductCreatePlanBindingContractTest.java (new)
- integration/ProductCreatePlanBindingPostgresContractTest.java (new if necessary for persisted revision/roundtrip evidence; root owns its later CI selector)

## Required behavior

Preview resolves once and creates a server-owned immutable binding: stable source product ID, ordered copied-chain digest/count, master revision/selectability pins, and all resolved target defaults. Do not let caller/model supply the authoritative binding. Controller persists the server plan in existing BoundTokenRequest.parameters, and confirmation checks public request fields against that token then uses only persisted parameters. Reject legacy/unbound proofs before any product creation. Preserve tenant/principal/purpose/version, current permission intersection, process-secrecy guards, redaction and existing egress limits. Do not expose the private bound snapshot to LLM output or logs.

Execute prepares the source chain once and compares against the confirmed digest before creating the product. A changed/deleted/reordered chain, changed master revision/unselectable/foreign master or invalid source must reject with zero product writes when seen at this preflight. Use the immutable prepared snapshot for copy, never a second read of source latest. Existing copyChain behavior and signature for unrelated callers remain compatible.

Digest covers exactly the14 copied business fields: workProcessId,processOrder,unitOverride,estimatedMinutesOverride,reportingRequired,allowSemiFinishedInjection,allowMultipleUpstreamSources,allowFinishedGoodsSource,defaultCostCategory,packagingTemplate,auxAllocMethod,standardYieldRate,auxUnitPrice,auxBasis. Exclude new binding IDs, factory/target IDs and timestamps, responsibleWorkerId/assignees/source isActive because the copy does not copy them. Packaging JSON keys sort, arrays preserve order; decimal comparison is mathematical/lossless. Master pin includes ID, lockVersion, same factory/nondeleted/selectable. Avoid cached managed-entity revalidation: refresh/re-read persisted master versions and flush/clear/reload target as appropriate; do not claim a Mockito read as fresh JPA evidence. Sorted current-master locking in the narrow service is permissible without changing repositories, but do not introduce unrelated locks or schema.

After copy transaction commits, read back the actual target chain and verify its copied-field count/digest. Source/master changes seen later or a readback/copy failure after product creation must retain the unique created product ID and return explicit UNKNOWN/partial/manual-inspection semantics, never pretend zero writes, automatically retry, delete the product or invent compensation. Keep1502's actual partial-create behavior.

The token's persisted JSON must roundtrip a binding exactly (avoid the prior Canvas25.0→25 digest trap); a canonical server binding string is acceptable. Do not change global ToolCommandDigest to accommodate this feature.

## Verification and resources

Worker runs only the four product classes above plus existing ProductCreateToolContractTest/AiProductCreateControllerGatewayTest/ProductWorkProcessServiceImplTest as applicable, sequential Maven within this WT. New source-reading tests must end ContractTest. Preserve all original partial-create/unknown/permission/source assertions, thresholds and baseline. Required cases: preview A→edit/reorder/delete toB→confirm creates0 products; unchanged A copies and persisted readback matches; forged caller binding/public field change denied; master version/selectability/scope drift denied; JSON numeric/key-order roundtrip; one Gateway lease claim/replay no duplicate; copy/readback unknown retains product ID/no retry. Root final official/CI selects actual existing class names and integrates1510 metadata, so a local mock-policy test is not claimed as full runtime registration proof.

No production/API/browser/account/credential actions by worker. If real PG is needed, only the owned stopped PG17 cluster C:/Users/Steve/.codex/tmp/food-dag-20261105/param-pg57048 on127.0.0.1:57048 and a new cretas_workflow_verify_product_binding_test DB, after exact path/PID/port checks, hidden process startup and finally only own stop. Confirm Root's1510 build has completed and released it (it has at12:56); never touch other PG/Docker/WSL. Return exact files, final hashes, commands/exits/counts/logs, owner/resource release and concrete blockers. No commit/push/ACTIVE edits or grandchildren; root owns integration/review/final build/PR/normal merge/deploy/business acceptance. You are not alone in the repository; preserve all other owners' work.

2026-11-06T14:41:54+08:00 Owner/scheduling update: Root changed only the two product_create metadata version stanzas in this isolated WT to2.0.0 (one line each); waiting for1510 had no file/resource dependency and is no longer required. Worker owns Tool/Controller source version2 and, from this checkpoint, exactly three additional consumer tests: ToolDescriptorPolicyResolverTest, descriptor/RuntimeToolPolicyLoaderTest and descriptor/ApprovedToolSourceMetadataTest. Root's prepared exact script sync-product-create-consumers2.py may be executed once after in-flight Maven safely finishes; Root will not edit these tests concurrently. Metadata/workflow/Git/dispatch remain Root-owned. PG57048 was actually released after1510 stage at14:02 with stop0; Product may use its independent_test DB with path/PID guards. Preserve all original PG failures and distinguish fixture jsonb seed failures from product cache/readonly-lock behavior. No main-workspace writes or relative patches are permitted.

2026-11-06T15:47+08 checkpoint: first binding implementation is preserved as a WIP milestone, NOT PR-ready. Focused54 and earlier real-PG3 passed; latest PG helper bytes still require final rerun. Independent review HOLD: terminal confirmed-token replay cannot retrieve the original product ID, and copy/commit/readback failure outcomes need durable scoped references. No release or production business write in this checkpoint. Root will integrate current main then register the justified narrow receipt/terminal-read expansion before further source edits.
