# FOOD-SOP-EQUIPMENT-MODULE-GATE-20261106

State in-progress; the prior canonical readiness executor has frozen and returned its source to root. Base278742b8efe8e65e96792ada5efeb9d5083609a2. Food root is the sole coordinator; planned code Owner artifact_merge_safety. Worktree C:/Users/Steve/cretas-food-dag-canvas-20261105; branch codex/sop-equipment-module-gate-20261106. Prior merged ordinary-parameter25a branch and evidence are retained.

## Confirmed defect and exact scope

Equipment SOP UI/service already exists. Do not reimplement it or infer missing software from absent acceptance. The confirmed P1 is the dynamic SOP resolver: a user with equipment role permissions can bypass the equipment factory-module switch or explicit account module DENY via attachment endpoints, while direct EquipmentController operations require that module. No production exploitation or denied-case live mutation was performed.

Allowed production file only:

- backend/java/cretas-api/src/main/java/com/cretas/aims/security/SopAttachmentAccessResolver.java

Preserve require(HttpServletRequest,String,EntityType,boolean) and typed active actor/factory namespace validation. For EQUIPMENT only, apply the exact direct ModuleEnabledInterceptor/Aspect semantics using com.cretas.aims.service.config.FactoryConfigService.isModuleEnabled(factoryId,"equipment") and com.cretas.aims.service.UserModuleAccessService.canAccessModule(User,"equipment"). Factory-module closure must also hold before returning any platform bypass success; platform identity must not be looked up as a factory User with a colliding numeric ID. Factory users must pass both module access and existing equipment read/write permissions. Unknown/null/error context must fail closed, and no signing/upload/metadata must proceed on denial. Preserve all existing WORK_PROCESS behavior, typed platform-role refresh, scope/bypass checks, process-secrecy VIEW_FULL/EXPORT/EDIT and audit fail-closed paths. Do not modify generic AttachmentPermissionResolver or relax permissions.

Allowed test files:

- backend/java/cretas-api/src/test/java/com/cretas/aims/controller/AttachmentSopSpringTest.java: existing fixture dependencies plus real dynamic endpoint denial/positive coverage
- backend/java/cretas-api/src/test/java/com/cretas/aims/security/SopEquipmentModuleGateContractTest.java (new if useful)

No Entity, Repository/query, DDL, other services, generic attachment policy, product Web/App/Auth, shared CI/release scripts, role/module configuration data, production accounts or device mutations. Root owns ACTIVE/archive/Git/PR/CI/normal merge/official release and controlled business acceptance. The scope was disclosed in Food status, no conflicting ACTIVE resolver Owner was found, audit's published state has no active source writer; report any actual ownership conflict before writing.

## Required evidence

Prove current factory module disabled and explicit user DENY block EQUIPMENT list/metadata/download/upload/register before storage signing or writing; read/write permissions are still independently required. Prove factory-module lookup/user-access failure and missing/inactive/wrong-factory actor fail closed. Prove typed platform actor with a colliding factory user ID is still checked in platform namespace, enabled module preserves authorized platform success, disabled module denies before bypass success. Preserve all existing WORK_PROCESS/secret/audit cases. Use real Spring endpoint context for the behavior, not only a constructor mock; no copied permission matrix or fabricated production evidence.

Development target selector AttachmentSopSpringTest,SopEquipmentModuleGateContractTest (omit the second only if no new class was created). Root final selector also includes existing AttachmentSopContractTest,AttachmentActorPermissionContractTest if present, and exact related guards located from current source; never invent an absent test name. Existing AttachmentSopSpringTest is explicitly selected in java-gate-pr.yml fast lane; a new source-reading gate must end ContractTest/StartupGuardTest. Same-worktree Maven is sequential. Use local mocks/fixtures only, no production network, credentials, accounts, factory configuration or hardware.

Return task/base/actual files/commands/counts/exits/log paths/SHAs and blockers. No commit/push/deployment by executor, no ACTIVE/archive edits, no grandchildren and do not revert other writers. Required expansion must be reported first. Tests/CI/merge/deployment/device and actual F006 business acceptance remain separate facts.

## Frozen implementation and local verification

Implementation is complete in the three allowed files. EQUIPMENT now requires both the factory module and factory-user module access before generic read/write authorization; typed platform identity retains its own namespace and must also pass the factory module switch. WORK_PROCESS and secrecy/audit behavior remain unchanged. Runtime lookup failures fail closed.

Root corrected two negative register fixtures to use valid EQUIPMENT storage paths and assert zero metadata registration, ACL/HEAD or signing calls. Final selector `AttachmentSopSpringTest,SopEquipmentModuleGateContractTest` passed 31 tests with zero failures/errors/skips on 2026-11-06. Resolver SHA-256 `10BF2EF7A187F55ED88CACA679BABD0010F16094FB90427D2C53360BA68F4FF6`. Independent risk review cleared the source and the fixture correction; no additional Maven run was claimed by the reviewer.

Source ownership returns to root and this narrow execution scope is released in ACTIVE. Root will integrate this commit with the independent user-disable safety candidate in its isolated worktree for one final related-gate build/PR, without changing retired intent policy. Commit/push, exact-head CI, normal merge, deployment and controlled equipment acceptance remain pending and are not claimed by the 31 local tests. No production factory-module, account, equipment or attachment writes were performed by this slice.
