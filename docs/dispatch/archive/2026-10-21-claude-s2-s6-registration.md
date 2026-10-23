# Claude S2 implementation / S6 read-only registration — 2026-10-21

Current S2 delivery: PR1069 actual Base47059, exact9, review/HOLD; see dated acceptance below. Initial Base1b9/seven-file registration remains historical. S6 writeScope=[] unchanged.

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

## 2026-10-21 actual PR1069 delivery received — review / HOLD

Task ERP-S2-READINESS-CLAUDE-20261021. Existing source Owner remains local_f6b118ce-f036-4ece-9a0b-322073b20c74, coordinator01a15272-374d-72c0-bb20-cdfa0bd823f1. Observed clean WT C:/Users/Steve/my-prototype-logistics/.claude/worktrees/claude-tasks-login-guide-47cc2c, branch codex/claude-erp-s2-readiness-20261021, candidate aa058799b6b632a099a7ab06abd17c35d77d92e6, parent619a650d90d4f7adda046ef7aee82b345cfccc5a, actual Base47059a443e2b41218578c03a4cd8b293f2de1914. Original registered Base1b9 remains history; original seven files have no1b9..47059 delta. Candidate exact9 independently matched Git objects and actual GitHub PR files. Receipt of prior work is not advance ACK or retroactive approval; no Owner transfer or branch change by coordinator.

Coordinator now accepts actual Base and precisely the two additional Reader files as necessary separation of read-only business fact acquisition and its contract coverage from the v1 facade/report. This changes only this task's exact scope to the following9; no Repository/Entity/migration or shared module scope is implicitly granted:

- backend/java/cretas-api/src/main/java/com/cretas/aims/blueprint/canonical/FactoryBusinessReadinessReader.java
- backend/java/cretas-api/src/main/java/com/cretas/aims/blueprint/canonical/FactoryReadinessReport.java
- backend/java/cretas-api/src/main/java/com/cretas/aims/blueprint/canonical/FactoryReadinessService.java
- backend/java/cretas-api/src/test/java/com/cretas/aims/blueprint/canonical/FactoryBusinessReadinessReaderContractTest.java
- backend/java/cretas-api/src/test/java/com/cretas/aims/blueprint/canonical/FactoryBusinessReadinessRepositoryQueryValidationTest.java
- backend/java/cretas-api/src/test/java/com/cretas/aims/blueprint/canonical/FactoryReadinessContractTest.java
- web-admin/src/api/factoryReadiness.ts
- web-admin/src/views/platform/factory-review/__tests__/FactoryReadiness.spec.ts
- web-admin/src/views/platform/factory-review/components/FactoryReadiness.vue

No other scope overlaps were introduced: existing supplier/Orchestrator/ToolDispatch/purchase/grant, shared Gateway/Skill/scheduler/permission, canvas/router/auth, release/runner scripts, canonical CSV/device/browser and production data remain excluded. S6writeScope=[]; a broad first draft is not an approved interface packet. Only this coordinator edits ACTIVE. Original QA01a15272-36e3-76c0-a6a1-977df6dba052 exclusively owns canonical CSV/Xiaomi/browser. Existing production selector release is independent of this candidate; no production blueprint/fixtures are created.

### Three blocking findings and bounded fixes

Fixed-head GitHub COMMENT review5594617203 and private coordination/s2-readiness1069-review-20261021.md are source review, not approval. Keep review/HOLD until fixed and verified:

1. P1: PURCHASE READY must not derive solely from active supplier/material/warehouse independent counts. Use existing scoped read APIs to require a same-factory active SupplierMaterial relationship with valid active endpoints, preserving the original PO guard. Real JPA missing relationship/inactive/foreign relationship negatives and valid relationship positive are required. Clarify any unchecked price/current-permission conditions; do not claim a business can proceed when mandatory facts are unverified. No fake quote/SQL production data or purchase guard edits. If an existing read API cannot express this, return exact necessary Repository/query expansion before any out-of-scope edit.
2. P1: preserve counts proving documents exist, but arbitrary PO/plan/shipment counts, especially DRAFT, cannot imply FIRST_BUSINESS DONE/已完成. Independently verified terminal completion is required for DONE. If not available within scope, return UNKNOWN/UNVERIFIED with explicit completion-unverified wording while retaining counts. Real draft-only JPA negative must never complete the stage. Include a real supported completion positive, or explicitly prove UNKNOWN remains for unsupported completion facts rather than inventing a positive. Distinguish applicability, readiness, existence and terminal completion.
3. P2: WAREHOUSE_DEFAULTS cannot promise direct repair then land on workflow canvas. Within current component scope use an actually supported destination, or show complete manual navigation steps to factory configuration -> warehouse management -> default purpose with correct factory context and existing role boundary. No implicit new query parser, shared canvas/router edits or authority. Component tests must cover actual displayed destination/steps and re-read, not only supplier link or local READY toggling.

### Exact commands and evidence conditions

```text
backend/java/cretas-api: mvn -B clean test -Dtest=FactoryReadinessContractTest,FactoryBusinessReadinessReaderContractTest,FactoryBusinessReadinessRepositoryQueryValidationTest
web-admin: npx vitest run src/views/platform/factory-review/__tests__/FactoryReadiness.spec.ts src/views/platform/factory-onboarding/__tests__/factoryBasicsHandoff.spec.ts
web-admin: npx vue-tsc -b
```

The Java selector must also run with the existing isolated realPG13 and realPG17 wrapper/environment, actual product/version/driver/read-only scoped facts and query/context startup evidence; do not run every Repository test. Use one centrally available Java slot, no second JVM or shared target/DB/port. No new Java is released in this receipt while actual existing CI owns it and the bridge remains offline. Above commands are acceptance requirements, not executed facts. Clean compilation/source+bytecode binding is required after test overlays/mutations; a plain-new Service unit test is not Spring wiring proof. Only changed targets/new risk assertions need revalidation; preserve partial historical backend19/PG17cluster3/Web24/tsc0/mutation evidence without treating it as semantic correctness of all assertions or independent SHOW ofPG17.7. Freeze exact final hashes/HEAD before any applicable final gates. Existing running CI is not rerun/cancelled by this receipt. Do not merge/release1069 just because CI is green.

### Owner confirmation and completion receipt still required

Claude must supply actual taskId/sourceOwner/coordinator/worktree/branch/currentHEAD/actualBase/originalBase, exact9 scope and two-file reason, exclusions, acknowledgement of existing three findings, v1/authority/UNKNOWN contract and no-production-write boundary. Mark observed prior delivery as such; do not backdate ACK. Coordinator has not observed this complete ACK and does not claim Claude read this receipt or began fixes. After matching Owner ACK, the existing authorized exact9 fixes need no new user approval; any additional file/interface requires a precise packet/conflict check. Fix receipt must identify changed files/hashes, real commands/exits/XML cases/driver-version, each finding's RED/GREEN and meaningful mutation/restoration, actual CI/commit/PR and remaining untested items. Independent final review must bind the actual final candidate before merge/release.

F006 and DEMO_2026_001 four blueprint tables were read-only confirmed0 by Root; this proves only those two factories. No inference about all5 platform-list factories, no blueprint fixture or whole S2 acceptance. Current canonical9PASS/2FAIL/64UNTESTED and18defects16closed2OPEN are unchanged. This receipt is an actual local uncommitted registration, not an ACK, product fix, CI approval, release or QA result.
