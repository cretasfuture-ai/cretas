# Canvas execution gateway slice

Task FOOD-CANVAS-APPLY-GATEWAY-20261106; Base3a377b0a05c7a37ed4a93feb90c3d6f5076deb40; Food DAG/root Owner. Audit released these source paths in its06:49/06:55 status and coordinator confirmed. Independent worktree only; audit production worktree and release window remain untouched.

## Frozen implementation contract

- Controller may read a bound immutable snapshot and validate Canvas purpose, factory=tenant, user, submitted tool/version, UPDATE/EXECUTE binding. Gateway alone owns claim/resolve, execution ledger and audit. Do not use pending-only validateTokenForUser before Gateway because it blocks safe replay.
- Narrow approved expansion: PreviewTokenService and Impl expose readBoundSnapshot(token,factoryId,userId), reusing repository.findByToken. Return immutable metadata only: intentCode/entityType/entityId/operation/tool/version/mode/commandDigest/expiresAt/status. All states may be read for the same binding; no preview payload, secret, claimId or mutable Entity. Wrong factory/tenant/user or unbound legacy token returns empty. No Repository/query/Entity changes.
- Gateway command uses stable token-derived persistent fingerprint idempotency key. Existing ledger replay occurs before expiry/claim; new expired execution is rejected without a claim. Controller never directly claims/resolves/executes.
- Executable allowlist: canvas_toggle_module, canvas_toggle_tool, canvas_toggle_skill, canvas_update_field, canvas_update_trigger_chain, canvas_apply_template, canvas_work_process_catalog. Other canvas names and local preview tools cannot mint an executable proof.
- Map SUCCEEDED to SUCCESS; replay original SUCCEEDED to REUSED, original FAILED to FAILURE, other/missing/unknown replay to UNKNOWN; OUTCOME_UNKNOWN/TIMEOUT to UNKNOWN. Never turn UNKNOWN into a retryable failure.
- Six generic tools explicitly declare UPDATE/LOW/WRITE, permission check, roles factory_super_admin and permission_admin, domain canvas, version1.0.0, no executable preview. Approve only these six with FACTORY/CENTRAL_KITCHEN + HTTP_CONTROLLER + confirmation/idempotency REQUIRED_FOR_EXECUTION + DENY_ALL default. Catalog existing policy roles preserved; change its confirmation/idempotency to REQUIRED_FOR_EXECUTION while PREVIEW stays supported.
- Retire old canvas_product_work_process_config name from Registry/APPROVED. Rename to canvas_product_work_process_draft_preview with ANALYZE/READ, supportsPreview true and doExecute always rejecting. Preserve existing PRODUCT_WORK_PROCESS_DRAFT preview consumer. Keep old legacy write code unreachable or remove it. Do not create allowedModes schema expansion.
- Keep1479 retired restaurant boundaries and LlmEgressGuard. Remove RESTAURANT from Canvas prompt; current non-retired template behavior stays allowed and RetiredTemplatePolicy remains the execution backstop.

## Exact allowed production/resource paths

Under backend/java/cretas-api/src/main/:

- java/com/cretas/aims/controller/CanvasAIController.java
- java/com/cretas/aims/service/PreviewTokenService.java
- java/com/cretas/aims/service/impl/PreviewTokenServiceImpl.java
- java/com/cretas/aims/ai/tool/impl/canvas/CanvasToggleModuleTool.java
- java/com/cretas/aims/ai/tool/impl/canvas/CanvasToggleToolTool.java
- java/com/cretas/aims/ai/tool/impl/canvas/CanvasToggleSkillTool.java
- java/com/cretas/aims/ai/tool/impl/canvas/CanvasUpdateFieldTool.java
- java/com/cretas/aims/ai/tool/impl/canvas/CanvasUpdateTriggerChainTool.java
- java/com/cretas/aims/ai/tool/impl/canvas/CanvasApplyTemplateTool.java
- java/com/cretas/aims/ai/tool/impl/workprocess/ProductWorkProcessConfigTool.java
- resources/ai/tool/gateway/runtime-tool-policies.yaml
- resources/ai/tool/gateway/tool-descriptors.yaml

Do not change production DefaultToolExecutionGateway, ToolRuntimeRegistry, ToolConfirmationLease, PreviewTokenRepository, Entity, LegacyToolMigrationManifestLoader, RetiredTemplatePolicy, LlmEgressGuard or Web. No DDL, permissions data, deployment, production API, other AI configuration or unrelated files.

## Allowed tests and acceptance

Under backend/java/cretas-api/src/test/java/com/cretas/aims/:

- controller/CanvasAIPlanConfirmationContractTest.java
- controller/CanvasAIApplyDiffsResultTest.java
- controller/CanvasAIWorkflowConfigTest.java
- controller/CanvasAIGatewayApplyContractTest.java (new)
- service/impl/PreviewTokenServiceImplTest.java
- ai/tool/impl/workprocess/ProductWorkProcessConfigToolTest.java
- ai/tool/impl/canvas/CanvasToolActorGuardTest.java
- ai/tool/impl/canvas/CanvasConfigWriteGovernanceContractTest.java (new)
- ai/tool/gateway/DefaultToolExecutionGatewayTest.java
- ai/tool/gateway/ToolRuntimeRegistryTest.java
- ai/tool/gateway/ToolRuntimeClassificationContractTest.java
- ai/tool/gateway/descriptor/RuntimeToolPolicyLoaderTest.java
- ai/tool/gateway/descriptor/RuntimeToolDescriptorRegistryTest.java
- ai/tool/gateway/descriptor/ToolDescriptorCatalogTest.java
- ai/tool/gateway/descriptor/ToolDescriptorInventoryDriftTest.java
- ai/tool/gateway/descriptor/LegacyToolMigrationManifestLoaderTest.java
- ai/tool/ToolExecutionArchitectureTest.java
- ai/tool/ToolAccessModeDeclarationTest.java
- service/config/impl/RetiredTemplateBoundaryContractTest.java

Also test resource ai/tool/gateway/tool-execution-direct-call-baseline.tsv: remove only the Canvas direct-call entry. Expected semantic count changes: approved/runtime9->15, inventory493 unchanged, legacy467->461, REVIEW_REQUIRED472->466; architecture baseline files10->9/execute10->9/expressions11->10/lines11->10. Recalculate from real source; do not expand baselines or skip gates to pass.

Target selector: CanvasAIPlanConfirmationContractTest,CanvasAIApplyDiffsResultTest,CanvasAIWorkflowConfigTest,CanvasAIGatewayApplyContractTest,PreviewTokenServiceImplTest,ProductWorkProcessConfigToolTest,CanvasToolActorGuardTest,CanvasConfigWriteGovernanceContractTest,DefaultToolExecutionGatewayTest,ToolRuntimeRegistryTest,ToolRuntimeClassificationContractTest,RuntimeToolPolicyLoaderTest,RuntimeToolDescriptorRegistryTest,ToolDescriptorCatalogTest,ToolDescriptorInventoryDriftTest,LegacyToolMigrationManifestLoaderTest,ToolExecutionArchitectureTest,ToolAccessModeDeclarationTest,RetiredTemplateBoundaryContractTest. If new tests are merged into existing classes, remove only absent names from the selector. Same worktree Maven sequential.

Use real Gateway/lease/ledger tests for first claim exactly once, successful and failed replay, IN_DOUBT/EXECUTING UNKNOWN, replay after token expiry, first expired request no execution, tampered params/tool/digest, cross actor/tenant, allowlisted catalog confirmation requirements, disabled factory override and retirement/secret boundaries. A mocked Gateway response alone cannot prove lease/idempotency integration. Preserve existing positive workflow and retired-role tests. Root owns final diff review, high-risk review, commit/push/PR, latest-head CI, normal merge, build/stage/deploy and business acceptance.

## Root-approved narrow outcome classification expansion (2026-11-06 08:19 +08)

Independent review reproduced a P1: AbstractBusinessTool wraps doExecute maps in outer success=true; Gateway would resolve the lease true and persist SUCCEEDED before Controller sees nested success=false/PENDING_DDL, so a later replay would falsely become REUSED. Controller-only mapping cannot repair that ledger. Root expands this task's production scope to DefaultToolExecutionGateway.java only for pre-terminal outcome classification: outer success with nested data.success=false, or an actual status=PENDING_DDL at top/data/data.data, must route through existing outcomeUnknownAfterClaim (IN_DOUBT, no resolve(true), no automatic retry) before SUCCEEDED is stored. Keep reserve/classifyExisting ordering, single claim, existing audit and error redaction, all permission/runtime policies, ordinary successful results, and all unrelated execution paths intact. No Repository/Entity/DDL/schema or shared helper changes. Existing DefaultToolExecutionGatewayTest and controller ContractTest are allowed for real outcome/replay regression tests. Also prove existing EXECUTING reservation replay and explicit wrong tenant binding; prior mock-only coverage is insufficient. Root owns final diff/risk review and official combined artifact/CI.

## Candidate implementation freeze and integration plan

Implementation is frozen for root review. The original19 selector passed153 before the Gateway outcome correction; only the related4 classes78 and then the final real integration Contract9 cover that increment so far. Final ContractTest SHA256 is490095CB1660B49AEF2921FA65235BB17ED92A39F22BA4C3F613B47CC29407AF. Its repository fixture records actual first terminal state/outcome and replays the same record, including pure PENDING_DDL at three depths and nestedfalse at two depths. No Mockito-only Gateway response is used as proof of that integration.

Initial task Base3a377b0a05c7a37ed4a93feb90c3d6f5076deb40 is retained. The current integration/release Base is fb5a544f7141faef821dab7436a24d811d5b7ccf (normal merged ordinary-parameter A/B and product-copy fix). Root will normally integrate that main before the single final official package/stage. No candidate JAR from the old source is deployable against a changed backend tree.

Final official selector26 = the original19 plus ToolConfirmationLeaseTest, ToolGatewayStartupGuardTest, AiProductCreateControllerGatewayTest, ProductCreateToolContractTest, GateSelectorCoverageContractTest, JavaPrGateTieringContractTest, FlywayVersionUniquenessTest. The extra existing tests cover the shared Gateway's lease/startup, newly merged product consumer and mechanical gates. No Repository/query/Entity or DDL changes are introduced. Root owns normal PR/required CI/final full-candidate lane, exact-main release and separately recorded business acceptance; no production deployment or acceptance is claimed by this archive. This task is archived from ACTIVE for final review/delivery; other owners and their rows remain unchanged.

## Final full CI consumer regression fix

FOOD-CANVAS-CONSUMER-FIX-20261106; Base33860ff5074339c5bed00735b7fff4143081517c. Root delegates only controller/WorkProcessPreviewApplyContractTest.java to artifact_param_isolation. Preserve all three existing visible-preview/persisted-field assertions; use the same bound token service for controller and real Gateway lease, real digest/snapshot binding and actual ledger fixture transitions. Acceptance: WorkProcessPreviewApplyContractTest,CanvasAIGatewayApplyContractTest. No production, Repository, Entity, DDL, baseline growth or skip. Root retains other source ownership, official combined artifact, exact-head full CI and delivery. Full run42137817454 recorded19637 tests and4 new failures with baseline0; do not change that baseline.

Root owns a narrow classification resource correction in src/test/resources/ai/tool/gated-data-registrations.tsv: the renamed read-only product-work-process draft remains an administrator Canvas configuration surface (CanvasAIController chat and the tool both allow only factory_super_admin/permission_admin), not a general HR roster query. Use the existing RULED exact two-way role comparison; do not widen runtime roles, exempt classification, or grow the red-test baseline. Add GatedDataToolAiPathPermissionTest and WorkProcessPreviewApplyContractTest to the final official selector (28 total).

## Confirmed browser numeric round-trip fix

FOOD-CANVAS-NUMERIC-ROUNDTRIP-20261106; Base33860ff5074339c5bed00735b7fff4143081517c. Independent read-only review reproduced a P1: issued canonical Double25.0 is serialized by JS as25, changing the submitted command digest without any business change. Owner artifact_merge_safety, scope only CanvasAIController.java plus new controller/CanvasAIJsonNumberContractTest.java. Keep ToolCommandDigest/BoundTokenSnapshot/lease/ledger contracts unchanged. Recursively normalize only JS-lossless finite numbers within Canvas bind/apply, reject precision loss before proof issuance, preserve objects/keys/array ordering and numeric-vs-string distinctions. Compute submitted digest and build Gateway command from the same canonical submitted map. Invalid normalization must reject before Gateway/claim. Test public preview/apply round-trip plus real negative tampering/precision; existing consumer suite retains real Gateway/service persistence. No shared Maven while consumer Owner runs; this worker edits/reviews only, root runs final official29 selector after source freeze. No new permissions, Entity, Repository, DDL, global digest changes or baseline skips.

## Final browser and consumer fix freeze

Root target selector CanvasAIJsonNumberContractTest,WorkProcessPreviewApplyContractTest,CanvasAIGatewayApplyContractTest,GatedDataToolAiPathPermissionTest passed24/0fail/error/skip at10:06:48; actual Surefire counts4+3+9+8. The first new-test compile failure (AssertJ element API and JsonNode type) was preserved and corrected without weakening assertions. Production controller SHA6A68BC4353A1AAFE6F59104E3E5F6CE8A51BA8CC2BEBBA6115ED51EB3338401F passed independent narrow risk review. Normalization includes actual persisted JSON readback, small exponent, negative zero and nested values; malformed/tampered values do not reach apply Gateway. The original consumer fixture now freezes issued server parameters/digest/hash, shares the actual lease token service and ledger, and proves JS number25 persists through real Gateway/service with one claim and one resolve. Final official selector29 is the prior26 plus both existing consumer/classification tests and CanvasAIJsonNumberContractTest. Root will normally integrate the soon-ready four-workspace main resource before the single final package and final full-head CI, avoiding a known stale backend artifact. No merge/deployment/production business acceptance is claimed yet.
