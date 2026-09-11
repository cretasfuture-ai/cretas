# S2 精确scope已释放；S6另审

{
  "taskId": "ERP-S2-READINESS-ENTRY-CLAUDE-20261022",
  "state": "claimed",
  "owner": "local_f6b118ce-f036-4ece-9a0b-322073b20c74",
  "actualClaudeSession": "e0a8b090-3310-4b3b-a971-aa296062628c",
  "worktree": "C:/Users/Steve/cretas-s2-readiness-entry-20261023",
  "branch": "codex/claude-s2-readiness-entry-20261023",
  "baseSha": "4a48c5b49c3a5f5214c68a3add5081ee409d88b2",
  "writeScope": [
    "backend/java/cretas-api/src/main/java/com/cretas/aims/blueprint/canonical/FactorySelfReadinessController.java",
    "backend/java/cretas-api/src/test/java/com/cretas/aims/blueprint/canonical/FactorySelfReadinessControllerContractTest.java",
    "backend/java/cretas-api/src/test/java/com/cretas/aims/blueprint/canonical/FactorySelfReadinessPostgresContractTest.java",
    "web-admin/src/api/factorySelfReadiness.ts",
    "web-admin/src/views/factory-readiness/index.vue",
    "web-admin/src/views/factory-readiness/__tests__/factoryReadinessHost.spec.ts",
    "web-admin/src/views/platform/factory-review/components/FactoryReadiness.vue",
    "web-admin/src/views/platform/factory-review/__tests__/FactoryReadiness.spec.ts",
    "web-admin/src/router/index.ts",
    "web-admin/src/components/layout/menuConfig.ts",
    "web-admin/src/components/layout/__tests__/menuConfig.spec.ts"
  ],
  "scopeReleased": true,
  "acceptedImplementation": false,
  "qaOwner": "01a15272-36e3-76c0-a6a1-977df6dba052",
  "commands": [
    "mvn -B test -Dtest=FactorySelfReadinessControllerContractTest,FactorySelfReadinessPostgresContractTest,FactoryReadinessContractTest,FactoryBusinessReadinessRepositoryQueryValidationTest",
    "npx vitest run src/views/factory-readiness src/views/platform/factory-review/__tests__/FactoryReadiness.spec.ts src/components/layout/__tests__/menuConfig.spec.ts src/components/layout/__tests__/routedPagesAreReachable.spec.ts --maxWorkers=1",
    "npm run type-check"
  ],
  "atUTC": "2026-10-23T14:49:19.261836+00:00"
}

## 接口裁决

1. 显式先拒绝非FACTORY_USER，再复用CanonicalBlueprintActorAuthority，现JWT租户/active/soft-delete链保留；不得放宽平台权限。
2. 新GET方法自身@Transactional(readOnly=true)。领取包称FactoryReadinessService.read已有readOnly不准确；不改service，在controller收只读事务边界。
3. 最小响应蓝图{id,version,rowVersion,lifecycleState}；无蓝图返回NO_CANONICAL_BLUEPRINT且不调read、不造READY。
4. loader精确类型(factoryId:string,blueprintId:string)=>Promise<FactoryReadinessReport>，默认platform loader不变；新host只取authStore.factoryId，用mobile current；无蓝图不挂报告，异步旧响应不得回灌。
5. menu roles=[factory_super_admin]并assemblyFeature:true，平台两项/FACTORY_ASSEMBLY_ROLES不动。
6. scope删除无需修改的routedPagesAreReachable.spec.ts（继续运行），新增独立FactorySelfReadinessPostgresContractTest。真实隔离PG13查询current→read，零生产写；原JPA gate复用。权限/跨厂/失效账号/gate-off/异常诚实/无写均覆盖。
7. 代码可立即实施；Java重测试先与Root确认机器窗口，不能争用S307 target或DB。本批Root正在修S307，不共享产品文件。依赖未知或必须越scope先回报。
8. 原QA独占UI/手机/CSV；没有正式蓝图只验无蓝图，正常READY展示保留未测。合并发布由Root，文件范围释放不等于实现/测试/发布/验收通过。

请原Owner按此11文件实施，回传实际文件、最终HEAD/PR、完整命令/exit/证据/未验/回滚。无需再次等用户确认。本回执不是已读取确认，Root不推定你已开工。
