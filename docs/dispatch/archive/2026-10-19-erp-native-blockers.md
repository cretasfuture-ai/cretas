# ERP native blockers, 2026-10-19

Base SHA: `76f4e456e3a874bcb00d055dc19f8e4d627f2497`.
Coordinator: `/root`; worktree `C:/Users/Steve/cretas-erp-w3-acceptance-20261019`, branch `codex/erp-native-blockers-20261019`.
This batch addresses two blockers found during authorized F006 S0 acceptance. It does not promote S0/S1 scenarios from local test results. Runtime, USB phone and the canonical private CSV remain exclusively owned by Chat `01a15272-36e3-76c0-a6a1-977df6dba052`.

## Scope locks

- `ERP-W4-OPERATOR-PICKER-20261019`, Owner `/root/operator_picker`: new task-bound production-material candidate Controller/Service/DTO and corresponding tests; RN `components/processing/MaterialBatchPicker.tsx`, `screens/processing/YieldStepReportScreen.tsx`, `services/api/yieldReportApi.ts` and direct focused tests. No edits to YieldReportServiceImpl/YieldLimitsDTO, generic MaterialBatchController/Service, Security, repositories, entities, migrations, dependency files, ACTIVE or other worktrees. If an existing helper or query cannot safely support the new endpoint, return a scope request before modifying it.
- `ERP-W4-YIELD-UNIT-20261019`, Owner `/root/yield_unit`: `service/yield/impl/YieldReportServiceImpl.java`, `dto/yield/YieldLimitsDTO.java`, new yield unit resolver helper and focused yield tests. No edits to Controller, RN, generic inventory, Security, repositories, entities, migrations, dependency files, ACTIVE or other worktrees. Unit projection and arithmetic must share authoritative task/port snapshot semantics; do not relabel a dimensionless scalar as a quantity unit.
- Root alone writes this archive and ACTIVE, reviews diffs, serializes all Maven/target operations, commits, pushes, merges and releases. Workers do not launch Maven without the explicit token and never run device/browser/SQL/production operations.
- `ERP-W4-OA-GRAPH-20261019`, Owner `/root/oa_graph`: `web-admin/src/views/platform/approval-workflow-editor/index.vue` and its direct focused tests only. Restore actual rendered edges for existing definitions, preserving load/switch/AI paths and loss-prevention guards. No permission/approval-policy/backend/production changes. The Web scope, artifacts and command resources are disjoint from the two Java/RN scopes; this justifies temporary three code Owners. Release the extra slot when its implementation/target proof is returned; Web tests may run independently, Maven remains root-exclusive.
- The picker worker additionally owns `ERP-S0-UNIT-001` within its already locked YieldStepReportScreen path: remove cross-unit fallback from finished-product batch planned quantity to task input quantity/ceiling. Only use proven same-unit authority or explicit verified source conversion; absence must remain unknown. Do not invent BOM dosage from yield ratio. Unit-helper/service ownership remains with yield_unit.
- Root rejected the initial unit/picker/OA drafts after independent source and actual test review. Unit implementation/test ownership has returned to root. The first Maven attempt selected no tests (tool error); corrected actual run executed 152 tests, with 8 legacy yield errors, preserved privately. Neither result is a release sign-off.
- Root product decision for cross-unit limits: apply the configured dimensionless yield proportion in the authoritative input quantity, then convert the resulting quantity to the authoritative output unit using existing `FgQuantityUnitConverter` and frozen finished-product grams-per-unit where required. This does not introduce a historical standard-rate snapshot. Missing, conflicting or inapplicable quantity basis remains an explicit failure.
- Picker scope extension: reuse existing `WarehouseResolver`/FEFO-by-warehouse query, production warehouse only, company-owned raw stock, no PRODUCTION_BATCH pseudo-WIP; scoped reference eligibility includes positive quantity, truthful stock unit and aggregate available quantity. Existing generic inventory permissions remain unchanged. Root owns wiring this validator into both report/material-input entry points before cost/save and tenant-scoping material cost lookup. No new Repository declaration is required.
- `ERP-W4-REPORT-RESERVATION-20261019`, Owner `/root/reservation_guard`: only `WorkshopReportReservationService.java` reservation path and direct focused tests. Keep release/reversal/settlement semantics intact. Revalidate factory-scoped locked row, permitted production warehouse, AVAILABLE company-owned raw source and requested aggregate <= available before any reservation write. A missing/foreign/invalid row raises a business error and the enclosing transaction rolls back; never warn-and-skip a new reservation. No Entity/Repository/migration change. Root owns shared Maven; worker does not launch it. This independent file/test scope replaces the completed extra OA code slot, so active code WIP remains bounded.
- Reservation test scope also includes the three existing direct constructor users: `WorkshopReportReserveOnSubmitContractTest`, `WorkshopReportReservationSettlementContractTest`, `WorkshopReportReservationReleasePathsContractTest`. Inject an explicit mocked warehouse resolver in tests and preserve the original business assertions; do not add a production constructor returning a warehouse code as a fake warehouse ID. Such an initial compatibility draft was rejected by root.

## Acceptance

- Final quantity contract: candidate available quantities are converted to the immutable task input code using existing scientific conversions; unconvertible package stock is not offered. Requests speak task units. After every ref validates, the server normalizes refs to each stock row's native quantity/unit before cost, persisted reference and reservation writes. A workflow report must reconcile raw task-quantity sum plus its explicit/source WIP component to aggregate input; material-input reconciles raw refs to warehouseOutQuantity while feedInQuantity stays independently factual. Legacy tasks without snapshots are not assigned invented historical conversions. Tests include mixed500g/0.5kg, nonconvertible packaging, mismatched/null aggregate quantity, distinct warehouse-out/feed-in, true Spring/JPA rollback and a PG-only simultaneous-reservation case. Pending test/deployment evidence is reported separately.

- `ERP-W4-RESERVE-PG-20261019`, Owner `/root/s1_assignment`: only existing `integration/InventoryAuditTransactionContractTest.java` and its nested test configuration. Reuse the existing real Spring/JPA/DataSource/transaction scaffold; this class already runs automatically in both fast-contracts and the explicit PostgreSQL gate. Wire actual YieldReportServiceImpl and WorkshopReportReservationService with actual MaterialBatch/ProductionReport/WorkProcessTask repositories; mock only unrelated remote/AI collaborators. Exercise a normal INPUT report with two existing material rows where the second cannot reserve, prove the exception and fresh transaction readback shows no report/clientRequestId or stock-reservation delta, plus a successful positive control. Assert actual required reservation bean wiring. No application, Entity/Repository/Query, CI/workflow, migration, ACTIVE, other tests or runtime edits. Root owns Maven; preserve all old inventory-audit assertions and fixture behavior. This independent test-only scope does not overlap root or the native worker.

- Same-cause quantity display correction: packaging task snapshots use plannedUnit=output (boxes), while the input port remains kg. Root extends YieldLimitsDTO with explicit authoritative inputUnit/outputUnit (legacy unit remains output for compatibility); operator_picker owns the existing RN screen/API scope plus focused tests to retain task-bound authority across empty quantity changes, invalidate stale task responses and show a retryable error/block writes if authority is unavailable. Never use plannedUnit or kg as an invented input-unit fallback. Root alone changes Java after the currently running target test completes; API Java delivery precedes the dependent JS OTA.

- `ERP-W4-HEADCOUNT-20261019`, Owner `/root/s1_assignment`: `WorkerAssignmentServiceImpl.java`, `SchedulingPlanCrudServiceImpl.java`, `SchedulingPlanDTO.java`, optional new scheduling headcount helper and direct `*ContractTest` tests only. Product decision: totalWorkers means distinct actually assigned user IDs across this plan's valid tenant-bound schedules, not summed slots or required staffing. Persist the summary on assignment/removal and derive truthful DTO summaries for existing plans using existing repository methods; no Entity, Repository, query or migration edits. Preserve factory guards and duplicate-assignment semantics. Test one person, repeated assignment, two schedules/same person, two distinct people, removal and foreign tenant zero writes. Root exclusively runs Maven; worker returns frozen files and evidence, not ACTIVE edits. This bounded backend scope is independent of frozen native/OA scopes and root's yield tests.

1. Existing generic inventory denial stays intact. New candidate reads require live factory equality, existing production permission/module, visible batch/task and actual actor; another assigned operator, foreign factory, missing task, material outside the immutable task/BOM source, unavailable stock and absent snapshot fail closed. Return minimum fields without prices/supplier-sensitive data. No SQL writes, wide permission grants or new shortcut whitelist.
2. Limits and submission quantity calculations use explicit input/output/WIP units. Cover kg/kg, kg/box with an explicit conversion and missing/invalid/reversed conversion, historical g/box/case/Chinese units, missing/ambiguous runtime authority, route-batch/task mismatch. No forged target/max or unit fallback. Same-cause scan includes submission overreceipt and material-selection sibling callers.
3. Focused tests invoke real services/controllers and mounted RN behavior. Required source-reading guards must end ContractTest/StartupGuardTest. Coordinator runs one frozen target selection via Maven, relevant RN tests/types, and required CI; any Entity/Repository scope expansion requires a real JPA Context gate before merge.
4. After merge: official exact-main Java release and applicable RN build/OTA path; retained original FAIL plus real same-role phone retest, exact report/stock readback and negative controls. Source/App provenance must be recorded; no claim of deployed phone code before verified delivery.

## UX Flow Analysis

### PG concurrency follow-up scope

`ERP-W4-REPORT-RESERVATION-20261019` transfers to `/root/reservation_refresh` after PG attempt4 reached two actual blocked submitters and one winner, but the losing submit raised an optimistic-lock exception instead of the required business refusal. Allowed files: WorkshopReportReservationService.java and its four existing direct contract tests (ReservationGuard, ReserveOnSubmit, ReservationSettlement, ReservationReleasePaths). Inject a required primary EntityManager; retain sorted all-rows-before-write locking and refresh actual database state under PESSIMISTIC_WRITE before validating and reserving. Use a factory-scoped entity lookup before refresh and validate the refreshed identity/factory afterward; never discard pending state with a global clear or weaken the invariant. Do not alter Entity/Repository/query/Security declarations or release/settlement behavior. Root owns real PostgreSQL, Maven and final integration. Completion requires actual two-waiter one-winner/BATCH_OVER_CONSUMED plus fresh one-report/reserved7 and rollback/guard regressions. No null EntityManager fallback. This is a current bounded code task, not business acceptance.

### 用户画像
操作员在车间手机上完成当前任务领料和报工，需直接识别物料及可领量。

### 用户旅程
| 步骤 | 用户看到 | 用户操作 | 期望结果 |
| --- | --- | --- | --- |
| 1 | 当前产品、批次、工序和已确认单位 | 打开领料 | 保留已有输入并只读加载任务允许原料 |
| 2 | 物料名、批次号、真实单位及可用量 | 选择批次 | 不展示价格或其他任务无关库存 |
| 3 | 投入与产出各自单位和有效边界 | 确认数量 | 缺少合法换算时明确提示并保留输入 |
| 4 | 可重试错误或明确无可领批次原因 | 重试或返回任务 | 不清表单、不发写请求、不伪造空成功 |

### 摩擦点清单
| # | 摩擦点描述 | 严重程度 | 来源规则 |
| --- | --- | --- | --- |
| F1 | 通用仓储数据范围阻止合法任务选料 | HIGH | Rule 5 |
| F2 | unitless/混合单位边界误导数量填写 | HIGH | Rule 1 |
| F3 | 仅批号不足以识别当前工序物料 | MED | Rule 2 |

### 每个摩擦点的设计回应
- F1: 复用现有选择界面并接任务绑定只读候选接口，保持通用库存权限。
- F2: 各数量使用其权威单位，转换不成立即明确阻断；不捏造上限。
- F3: 保留物料名称、批次号和真实可用量；点击目标至少44pt，错误可重试且保留输入。

Private evidence: `C:/Users/Steve/Documents/cretas-optimization-20261019/execution/revalidation-wave3/`; no factory data or credentials in this tracked archive.
