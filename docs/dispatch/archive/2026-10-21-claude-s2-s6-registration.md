# Claude S2 implementation / S6 read-only registration — 2026-10-21

Sole ACTIVE writer: `01a15272-374d-72c0-bb20-cdfa0bd823f1`, parent `ERP-STAGES-20261019`. Code/trace Owner for both tasks: actual Claude session `local_f6b118ce-f036-4ece-9a0b-322073b20c74`. Base `1b9f712ecf275fe7f784e58174f90e8e1b9c68bf`. This registration permits the bounded local S2 implementation after Owner ACK; it is not a stage acceptance, production release or QA authorization.

## ERP-S2-READINESS-CLAUDE-20261021 — claimed

Verified current worktree `C:/Users/Steve/my-prototype-logistics/.claude/worktrees/claude-tasks-login-guide-47cc2c`, current branch `claude/claude-tasks-login-guide-47cc2c`, clean HEAD equals Base. Claude must change its own branch to `codex/claude-erp-s2-readiness-20261021`, then ACK actual worktree/branch/HEAD/Base/sole Owner and the seven-file scope before edits. Coordinator does not change Claude's branch.

Exclusive product/test write scope (exactly seven files):

1. `web-admin/src/views/platform/factory-review/components/FactoryReadiness.vue`
2. `web-admin/src/views/platform/factory-review/__tests__/FactoryReadiness.spec.ts`
3. `web-admin/src/api/factoryReadiness.ts`
4. `backend/java/cretas-api/src/main/java/com/cretas/aims/blueprint/canonical/FactoryReadinessService.java`
5. `backend/java/cretas-api/src/main/java/com/cretas/aims/blueprint/canonical/FactoryReadinessReport.java`
6. `backend/java/cretas-api/src/test/java/com/cretas/aims/blueprint/canonical/FactoryReadinessContractTest.java`
7. NEW `backend/java/cretas-api/src/test/java/com/cretas/aims/blueprint/canonical/FactoryBusinessReadinessRepositoryQueryValidationTest.java`

The seventh name replaces the proposed `FactoryBusinessReadinessJpaIT.java`, which is excluded. No duplicate IT or unselected test claim.

Root's authoritative interface decisions apply: retain existing top-level `factory-readiness/v1` and old field meanings; optional stages/businessReadiness are additive. A v2 change requires a separately reviewed compatibility/release contract. Distinguish confirmed information, blueprint application, formal publication, business readiness, and first completed business, each with authoritative provenance. `businessWriteCount=0` is not authoritative absence of business; draft counts are not first completed business. Without reliable terminal facts return UNKNOWN. Verify supplier/customer soft-delete/active semantics, bound warehouse validity and actual applicability; no implicit-create resolver in a read path, no null-as-not-applicable or invented package/RD requirement.

Platform view may show missing facts and factory-admin completion location with factory/product context. Show actionable links only under existing proven role/tenant access; no new cross-role authority. Re-read actual backend state after completion, never mark ready locally. No Entity, Repository interface/query, migration, permission, supplier/purchase/grant/Orchestrator, shared CI/release, canonical CSV or other Owner evidence writes. A required query expansion needs a precise scope/interface decision first. Do not touch the main worktree's old dirty ACTIVE/AGENTS/Yield/MultiOutputActualMetricsService.

Acceptance commands in Claude's independent workdir:

```text
web-admin: npx vitest run src/views/platform/factory-review/__tests__/FactoryReadiness.spec.ts src/views/platform/factory-onboarding/__tests__/factoryBasicsHandoff.spec.ts
web-admin: npx vue-tsc -b
backend/java/cretas-api: mvn -q test -Dtest=FactoryReadinessContractTest,FactoryBusinessReadinessRepositoryQueryValidationTest
```

Real PG13/17 with actual JPA context, product/version/driver, scoped facts and zero business change are required for frozen source via the existing controlled entry. Java resources require central release first: Aurora4CPU/8GiB allows one heavy Java task, currently supplier Full39462429957; Windows-light has no Java allocation. Do not run every Repository test or duplicate Full. Required coverage: READY unpublished, published but business facts missing, proven applicability/non-applicability/unknown, unavailable or failed facts never ready, correct completion context, actual reread, and old-consumer compatibility. Existing two files/12 baseline tests do not prove these additions. Production platform capability currently disabled per Claude read-only report; do not enable production configuration or invent a test environment to bypass it.

## ERP-S6-EXECUTION-ARCH-CLAUDE-20261021 — claimed, read-only

`writeScope=[]`. Same actual Owner and Base. Proposed worktree `C:/Users/Steve/cretas-s6-execution-arch-20261021` / branch `codex/claude-erp-s6-execution-arch-20261021` has not been created/ACKed; no transfer is claimed. Only trace actual Gateway/principal/task, Skill→Tool, scheduler→Skill/Tool and existing purchase compatibility. Deliver actual call graph, coverage matrix, interface/status/transaction/compatibility/rollback proposal, exact first file scope and target commands. Code acceptance commands are PENDING_INTERFACE_PROPOSAL, not registered by the initial broad request.

No blanket Gateway/Skill/scheduler/permission write lock. Each interface/file batch needs conflict review and explicit release. Supplier candidate PR1066, IntentExecutionOrchestrator, ToolDispatchService, tool descriptors, existing purchase grant and production configuration are excluded. Do not migrate production purchasing, start production timed jobs, grant task identity, or call this complete S6 coverage.

## QA and release ownership

Original QA `01a15272-36e3-76c0-a6a1-977df6dba052` retains sole canonical CSV/browser/Xiaomi ownership. Claude's separate read-only platform session is not original QA acceptance and transfers none of those resources. No production deployment/purchase migration/scheduling/permission authority is granted here. Root retains no product/ACTIVE writes; coordinator preserves other ACTIVE entries and its in-flight S1 work. Implementation, tests, PR, merge, release and business acceptance remain separate.
