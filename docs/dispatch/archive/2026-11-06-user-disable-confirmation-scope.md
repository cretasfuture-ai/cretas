# FOOD-USER-DISABLE-CONFIRMATION-20261106

State: review. Original Base SHA: fb5a544f7141faef821dab7436a24d811d5b7ccf. Owner: Food root 01a1a954-13e5-7182-9572-00ff306c8d31 plus artifact_param_isolation. Worktree: C:/Users/Steve/cretas-food-dag-ota-20261105; branch codex/user-disable-confirmation-20261106. The old merged product branch27ce is retained.

## Confirmed defect and frozen behavior

The approved HIGH user_disable runtime requires confirmation, but its dormant tool cannot preview, and its business target userId/username collides with trusted actor injection. Harden that internal contract with mandatory stable targetUserId only. Do not accept mutable username targets or fall back to the actor userId. The trusted actor userId injected by ToolDispatch remains a separate principal field and must not itself invalidate a valid targetUserId. Preview performs no business mutation. Preview and execute must both enforce the same real hr_employee factory-module and per-user module-access gates as the direct API, with an active current factory actor and current hr:read_write permission. Unknown actor/context/service results fail closed. Execution revalidates the target and authoritative write protection.

Retirement correction from independent review: V20261101_49 deliberately disables FACTORY USER_DISABLE chat intent. Public recognition/explicit preview/confirm enforce active intent; policy continues to allow AI_CHAT only, while generic dispatch execution uses AI_INTENT_DISPATCH. Preserve both existing boundaries. This slice does not activate the intent, broaden source policy, restore a public AI flow or claim that deliberate retirement is a product defect. Its delivery is dormant-tool contract hardening plus the direct management service's authoritative atomic protection. Actual acceptance must use an existing authorized management route and an owned synthetic account, or honestly record the intentionally disabled AI entry; never reactivate it just to produce a positive demo.

Deactivation must atomically update only isActive=false and updatedAt, conditioned on exact id/factoryId/nondeleted/active and NULL-safe exclusion of protected roleCode or position (factory_super_admin, platform_admin and existing Chinese compatibility values; permission_admin is not automatically protected). Use a modifying query with flushAutomatically and clearAutomatically true. On zero changed rows, freshly distinguish missing, wrong factory, protected and already disabled; only a known same-factory, unprotected, already-disabled target may retain the existing idempotent success. Unknown/query failure must not become success. No generic stale entity save after the atomic update. This guarantees protection at the deactivation write, not all unrelated user-state writers or full Capability twelve dimensions.

Version the breaking target schema as3.0.0 in the tool and both descriptor/runtime user_disable stanzas, supportsPreview=true; keep permissions, sources, business-type allowlists, HIGH risk, required confirmation/idempotency and egress restrictions. Old v2 proof/version must fail closed. Gateway/lease remains the unique claim owner and uses persisted parameters. Preserve retired restaurant and Canvas changes when later integrating latest main.

## Exact production scope

Under backend/java/cretas-api/src/main:

- java/com/cretas/aims/ai/tool/impl/user/UserDisableTool.java
- java/com/cretas/aims/repository/UserRepository.java: one atomic conditional deactivation query only
- java/com/cretas/aims/service/impl/UserServiceImpl.java: deactivation method and its narrow private validation only
- resources/ai/tool/gateway/tool-descriptors.yaml: user_disable stanza only
- resources/ai/tool/gateway/runtime-tool-policies.yaml: user_disable stanza only

No Entity, migration, UserService interface, ToolDispatch, TrustedExecutionContext, Gateway, PreviewToken, other user-write flows, other owner files or production/API/account changes. New schema/role data is not authorized by this code scope. Root owns ACTIVE, archive, Git/PR/CI-selector union, normal merge, releases and separately scoped acceptance.

## Exact test scope

Under backend/java/cretas-api/src/test/java/com/cretas/aims:

- ai/tool/impl/user/UserDisableConfirmationContractTest.java (new)
- repository/UserDisableRepositoryQueryValidationTest.java (new real JPA startup/query test)
- integration/UserDisableConcurrencyPostgresContractTest.java (new real PG barrier)
- engine/AutonomousScheduledWriteIsolatedPostgresContractTest.java (existing target fixture/schema compatibility only; preserve all autonomous denial assertions)
- ai/tool/gateway/descriptor/ApprovedToolSourceMetadataTest.java
- ai/tool/gateway/descriptor/RuntimeToolPolicyLoaderTest.java
- ai/tool/gateway/ToolDescriptorPolicyResolverTest.java
- ai/tool/gateway/ToolRuntimeRegistryTest.java
- ai/tool/gateway/descriptor/ToolDescriptorInventoryDriftTest.java

Update only actual changed metadata expectations, never baselines, skip lists, thresholds or unrelated tests. Root will include RuntimeToolDescriptorRegistryTest, ToolGatewayStartupGuardTest, ToolDescriptorCatalogTest, GateSelectorCoverageContractTest and ToolParameterDescriptionLeakScanTest in official final selection. New PG/query tests must be actually executed: root owns the additive CI selector union, retaining all existing other-owner entries. Report any required expansion before writing.

Required behavior: actor versus target identity separation; mandatory target ID and old username-only rejection; module-disabled/user-DENY/actor-inactive/missing/cross-factory/self/protected-role and position negatives; preview zero-write; role promotion after preview rejected on execute; permission revocation rejected by actual Gateway; one claim and no second write on successful replay; atomic query updates only active and audit time. PG barrier must prove a concurrent role update holds the row, commits a protected value, then the waiting atomic deactivation re-evaluates and changes0, keeping the account active. Use the actual repository query, not a copied SQL predicate as sole evidence. Real JPA context is mandatory.

## Local execution resources and stopping rules

The executor owns only the listed files and new private user-disable-* helpers/logs. Same-worktree Maven is sequential. Existing private build-param-writer-b-with-pg.ps1 is a read-only lifecycle reference, not an execution entry to overwrite. If needed, the owned PostgreSQL17 cluster C:/Users/Steve/.codex/tmp/food-dag-20261105/param-pg57048 on127.0.0.1:57048 is currently stopped and may be reused only after path/version/port/PID checks; create a separate disposable cretas_workflow_verify_user_disable_test database and always stop only this owned instance in finally. No other PG, Docker, WSL, host processes, production network or account mutations. Do not print credentials. Preserve prior evidence and old branches. Return task/base/files/tests/counts/exits/logs/SHAs and any blocker; no commit/push/PR/deployment by the executor, no grandchildren.

## Frozen local evidence and integration handback

Independent review found and corrected the trusted actor userId rejection, stale autonomous target fixtures/bean wiring, and misleading wrong-version permission/source negatives. Stable target parsing now rejects fractional, nonfinite, overflow and boolean values exactly. Actual ToolDispatch preview rebinds a malicious caller userId to the trusted actor while retaining targetUserId and performing no write. Explicit per-user module DENY and lookup exception reach their own branches with valid prerequisites and zero business-writer interactions.

Final affected unit selector `UserDisableConfirmationContractTest,ToolDescriptorPolicyResolverTest,ToolRuntimeRegistryTest` passed22 tests with zero failures/errors/skips (2026-11-06 12:20). The prior PG class run passed14 of15 autonomous cases plus the new real Repository barrier, with the remaining cron-edit fixture timing out on an old target shape. After correcting that one fixture, `AutonomousScheduledWriteIsolatedPostgresContractTest#aCronChangedElsewhereOnlyReschedulesAndNeverRunsTheOldBinding,UserDisableConcurrencyPostgresContractTest` passed2 tests with zero failures/errors/skips (12:23–12:24); owned PG stopped with exit0. The barrier asserts a real PostgreSQL product/dialect, observes an actual database lock wait, commits the concurrent protected role, and proves the Repository update re-evaluates to zero. Initial failures remain preserved; the earlier83-test result is not substituted for these final changed-path results.

Independent final source/test review is CLEAR with production hashes unchanged. Root's workflow change is additive only: include UserDisableConcurrencyPostgresContractTest and UserDisableRepositoryQueryValidationTest in the existing PG union, retaining every previous entry. Root takes back Source ownership for integration with current main7872 and the independent equipment-SOP commit8df, then one official full related selector, exact-head CI, normal PR merge and deployment. That final combination and actual controlled F006 management acceptance are pending; no production account/permission/module data was changed during this execution slice. FACTORY USER_DISABLE retirement was independently read back as inactive; no public AI reachability is claimed.
