# S1-07 authenticated deferred grant Tool/Skill caller slice

Owner 01a15272-374d-72c0-bb20-cdfa0bd823f1; Base 01434fc2393063ed0d10e455c0e0169edd1f3b80; parent ERP-STAGES-20261019. Same Source Owner, not transfer. Scope registered before product edits. Original QA retains browser/phone/CSV and preview178; no duplicate D.

Exact product/test scope:
- `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/purchase/PurchaseGrantExecuteTool.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/intent/PurchaseGrantCallerService.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/PurchaseGrantCallerController.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/intent/PurchaseGrantCallerContractTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/intent/DeferredPurchaseGrantPostgresLifecycleContractTest.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/config/DemoReadOnlyInterceptor.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/config/DeferredPurchaseGrantDemoBoundaryContractTest.java`
- `web-admin/src/api/controlledPurchase.ts`
- `web-admin/src/views/procurement/ai-purchase/index.vue`
- `web-admin/src/views/procurement/ai-purchase/__tests__/DeferredPurchaseGrant.spec.ts`

Commands:
- `mvn -B test -Dtest=PurchaseGrantCallerContractTest,DeferredPurchaseGrantPostgresLifecycleContractTest,DeferredPurchaseGrantDemoBoundaryContractTest,ControlledPurchaseScopeBoundaryContractTest,PurchaseTaskConfirmBoundaryContractTest`
- `npx vitest run src/views/procurement/ai-purchase/__tests__/DeferredPurchaseGrant.spec.ts`
- `npx vue-tsc --noEmit`

Interface: fixed purchase_grant_execute Tool and server-defined single-tool Skill; no caller-selected tool/skill/identity/purpose. Authenticated FACTORY_USER/request and current grant owner/factory/canonical purchase task remain authority, never payload/context aliases. Existing persisted confirmed grant is required; its server token snapshot fixes material/quantity/amount/action/expiry. No new grants, no client serverConfirmed, no ordinary-create tool escape. Current authorization/references/active/module/CAS/replay/UNKNOWN and independent refusal audit retained in canonical service. Synchronous Skill keeps request actor; no scheduler/async identity invention. Server marker only after real owner status read; canonical service revalidates at execution and beforeCommit.

Scheduler/TriggerChain/Gateway shared source is excluded; SkillExecutor is limited to the exact hunks below. Claude B1 exact8 and B2 future scope remain protected. No Entity/Repository/schema/production config changes. Existing normal UI execution invokes fixed Skill→Tool for an already-authorized task; no user-facing technical channel selector, no timer. Development only changed targets; final bound CI/JPA/PG13/17/build/manual merge/official deploy/original QA separate. Current 07 whole row remains UNTESTED.

Exact scope extension BEFORE shared edit: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/skill/impl/SkillExecutorImpl.java`. Only strip process-local server confirmation marker after existing W0/permission checks and before ToolCall JSON/execution context. Real mounted Skill positive must expose old serialization failure and prove marker absent at actual Tool. This file is not Claude B1 exact8; B2/B3 shared procurement changes remain unreleased. No second writer. Full shared guard semantics remain intact. Final scope11.

Within the same11, before the next semantic hunk: independent reviewer confirmed null-intent frontline regression. Only the actual PurchaseGrantExecuteTool gets server-fixed canonical PURCHASE_ORDER_CREATE intent in outer and Skill permission chains; never an intent from context/LLM or a generic fallback. Real FrontlineAiWriteGrantService/ToolPermissionChain/ToolRbacEnforcer/Guard regression covers permitted frontline and current permission / missing same-factory grant refusal. Existing ControlledPurchaseTaskExecutionService remains final fresh authorization. No shared permission engine or other Tool change.

Architecture completion within same11: both fixed server adapters use the existing deterministic SkillExecutor. TOOL is a server-named single-tool bridge, SKILL the fixed purchase workflow; both reach the actual registered PurchaseGrantExecuteTool. Do not claim standalone generic ToolDispatch/Gateway migration. No new direct ToolExecutor call site, no baseline/gate change, no extra cache/retry path. Actual architecture before/after and final-source PG evidence are required; earlier direct-branch PG13 is historical only.

## Frozen candidate validation (not delivery or whole S1-07 signoff)

- Final exact11 source hashes: private `execution/source-owner-next-actual-entry-20261022/source-freeze.json`; final-target-binding and independent reviewer report bind the same hashes. Reviewer `/root/grant_refusal_delta_risk`: APPROVED_NO_REMAINING_P1_P2, finite Source/evidence delta only.
- Real caller/whole architecture target19 PASS; raw old architecture growth1 FAIL retained. Actual marker serialization old1 FAIL and permitted-frontline old2 FAIL retained; three effective negative mutations and restored14 GREEN. The final bridge removes the direct call instead of growing or evading the frozen baseline.
- Final real PostgreSQL13.23 target39 PASS =25 persisted lifecycle +14 isolated caller contracts. PostgreSQL17.10 persisted lifecycle25 PASS. Both zero fail/error/skip, current source/class/XML binding, independent containers/volumes removed and ports closed. Prior direct-branch PG13 is history only. JSON fixture scalar seed provenance and unrelated DDL warnings remain visible; not production migration proof.
- Native mounted deferred UI11 PASS, vue-tsc --noEmit exit0. Existing pending/nullVersion revoke, same-mounted scope reset, no retry/fallback and strict task/CAS-only HTTP payload retained. Alert title fixture correction and original failure retained; no false product UI defect claimed.
- No Entity/Repository/schema/global policy change. Existing one-shot and all canonical expiry/revoke/current authorization/ownership/reference/caps/CAS/UNKNOWN/idempotency/beforeCommit guards remain in the unchanged controlled service. No scheduler/TriggerChain/Gateway/ToolDispatch/Claude source touched.
- Source locked/frozen for this candidate; this commit archives its in-flight row and scope map so scope release becomes effective only when the reviewed candidate is merged. Same private Source ownership/reservation retained until merge; no other writer or task transfer.
- Final CI, ordinary exact-head merge, official exact-main Java/Web release, original QA real UI/SQL are still pending at source freeze. Production remains78e; original QA D completed separately on that runtime. Whole07 stays UNTESTED and parent75 stays13PASS/1FAIL/61UNTESTED.


## Frozen-candidate fast-gate delta

PR1090/394ef fast run39691053284 retained 4485 cases/2 assertion failures/0errors/74skips. New execute/4 Long version caused the existing actor-scanner ambiguity rule to hide its unchanged SmartBI frozen station. Rename only the new service method to executeGrant and synchronize controller/caller/lifecycle calls within the same scope. No scanner, frozen baseline, permission or business assertion edits. Prior official artifact is staged history only until a new matching backend tree is built. Add unchanged HardcodedActorIdContractTest to the release selector and validate the final delta; existing Web tree and unchanged security controls reuse exact-content evidence.

Final2cf CI39695060665 and local artifact attempt failed before tests: three Demo fixture references were omitted from the same method rename. Preserve compile errors as setup failure/zero executed cases. Synchronize only those three test calls; all assertions/guards remain unchanged. Target execution now explicitly includes Demo + caller + architecture; unchanged product/lifecycle/caller hashes allow prior realPG evidence reuse, while final coherent CI proves all tests compile. No deployment from either failed attempt.
