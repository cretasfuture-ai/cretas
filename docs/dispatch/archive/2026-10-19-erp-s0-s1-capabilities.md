# ERP S0/S1 first delivery batch

Coordinator: `/root`, thread `01a1523e-881c-7a92-b7ab-f4b8c5a552df`.
Registered Base SHA: `6c4ecfe93ff7751066ea7323e5c6750a7eae11fe`.
Worktree: `C:/Users/Steve/cretas-erp-s0-s1-20261019`.
Branch: `codex/erp-s0-s1-20261019`.

This archive describes the engineering batch. It does not certify completion of S0, S1, or any business acceptance scenario. The overall staged task remains active. Candidate review, build, hosted checks, merge, release and business acceptance are separate states; their final facts are recorded in the private execution receipts and PR checks.

## Batch and ownership

The following leaf scopes are returned to the coordinator for candidate review and released by this commit when it is merged. No child may continue writing these scopes. The parent `ERP-STAGES-20261019` retains responsibility for remaining acceptance and future, separately registered implementation.

| Task ID | Owner | Exact responsibility |
| --- | --- | --- |
| ERP-S1-BACKEND-20261019 | /root/s1_backend | Capability DTO/controller; scheduling AI, CRUD and line services; supplier comparison tool; their named contract tests |
| ERP-S1-WEB-20261019 | /root/s1_web | Scheduling API mapping, create/detail/assignment pages, manual form helper and their exact API/component tests |
| ERP-S1-ASSIGNMENT-TENANT-20261019 | /root/s1_assignment | WorkerAssignmentServiceImpl and WorkerAssignmentTenantBoundaryContractTest |
| ERP-S0-READONLY-TELEMETRY-20261019 | /root | Optional activity tracker and canonical production readonly harness init marker/fixture assertions |

No Repository, Entity, query declaration, database migration, lockfile or production dataset is changed by this batch. A temporary third code worker was admitted for independent Web/Java file scopes; Maven/target ownership remained serial. Root alone owns the task ledger and business scenario/defect tables.

## Resulting behavior

- Authenticated scheduling capabilities distinguish unavailable AI generation/worker optimization, reason recording, and explicit manual schedule drafts. AI generation fails before repository access; it cannot invent a production line or capacity.
- Manual drafts use explicit same-factory active lines and eligible batches, positive basic-unit quantity, and local start/end times. Worker assignments remain an explicit separate action. This does not prove capacity, material, changeover or due-date feasibility.
- Schedule details, status changes, assignment operations and single/batch probability reads validate their real non-deleted parent plan's tenant before metadata or side effects. Assign/check-in require active workers; remove/check-out can close an existing same-factory inactive worker's assignment.
- The Web API maps real Java DTO names and sends `workerIds`. Components display the actual basic unit, worker names and planned/assigned values; missing information is not replaced with fabricated values.
- Reschedule describes its existing reason-only behavior. Supplier comparison reports unavailable through the real outer tool result, without pretending that a supplier directory is a price comparison.
- The canonical readonly harness opts out of optional UI telemetry before application startup. The before-send mutation guard and whitelist are unchanged; older deployed Web code that still attempts telemetry continues to fail the readonly run.

## Verification and retained evidence

Private evidence root: `C:/Users/Steve/Documents/cretas-optimization-20261019/execution/`.
The canonical 75-case business scenario table is in the sibling `final-plan/` directory. Factory data, identities, runtime details and screenshots stay outside tracked files.

- `S1-backend/`: the original capability red, meaningful capability-status mutation red, and 23 passing tests across five classes, including the actual outer supplier tool wrapper. Final XML files are retained independently of Maven's mutable target directory.
- `S1-assignment/`: six initial passing tests, inactive-check-in guard mutation red, and restored green; a seventh mixed-history filtering case was subsequently added, with a separate filter mutation and restored result. Initial wrong-selector/no-tests failure is a tooling failure. The first restore reused stale mutant bytecode due to a restored timestamp; it is retained as a failed attempt and replaced by an explicitly recompiled green, not relabeled as a pass.
- `S1-backend/probability-*`: scoped metadata and actual Permission/Module interceptor coverage. The final test result is recorded by its own Maven receipt and by the candidate package, not inferred from source review.
- `S1-web/`: seven target files / 15 tests passed; the subsequent basic-unit DOM assertion passed its one changed file / six tests. Typecheck passed. Independent Web/harness review passed.
- `S0-ui/telemetry-fix/`: activity tracker fail-first and ten passing tests; 37 canonical unit tests; meaningful pre-login marker mutation red; 12 restored fixture tests. An earlier asynchronous fixture log named red actually passed and is explicitly excluded from fail-first proof.
- Two opt-in canonical scheduling scenarios additionally require the observed real capability GET and the mounted unavailable controls/manual explanation, with screenshots. They do not broaden default campaigns or perform writes. The expanded canonical unit run has 39 passing tests; it includes negative controls for a missing/mismatched capability response and inconsistent UI.
- `coordination/`: bounded independent source reviews, exact ownership/contracts, and subsequent candidate/release receipts. Final builds and checks must pass before merge/release.

Required final Java selector:

```text
SchedulingCapabilitiesContractTest,SchedulingWorkerOptimizationUnavailableContractTest,SchedulingPlanCrudExplicitInputContractTest,SupplierPriceComparisonContractTest,LineScheduleTenantBoundaryContractTest,WorkerAssignmentTenantBoundaryContractTest,SchedulingProbabilityTenantBoundaryContractTest
```

The Java PR gate (`java-gate-pr.yml`, compile/high-signal step) selects `*ContractTest`, including these classes, when the full-suite label is absent. The artifact workflow is manual-only and does not automatically package this PR. Do not claim hosted execution until that run's logs confirm actual tests. No new JPA query startup gate is triggered by this diff because no Repository, Entity or query declaration is modified; the normal PR gate's existing checks still apply.

## Unfinished business acceptance

At this checkpoint only the runtime/registered-route smoke case has passed (1/75). The original production readonly run failed when an activity-events POST was blocked before send: actual business writes were zero, blocked attempts were one, and safetyPassed was false. Preserve that FAIL and rerun affected scenarios only after the legitimate Web release, using a fresh evidence directory and the user's explicit same-source CLI exception.

The representative existing product is a linked SOP sample, not a verified production-ready recipe. Yield/conversion factors do not establish ingredient input quantities. Role-by-role UI, purchase/receipt/report persistence and time baselines remain unaccepted. No measured ROI is claimed.

Task-scoped revocable grants, approval/version binding, expiry, and recovery of the original business result after uncertain responses are a subsequent S1 batch. Existing confirmation TTL and idempotency alone do not satisfy those requirements. The proposed next pilot is one requisition draft line, but its write path has not been admitted or enabled by this batch. S2 remains preparation.
