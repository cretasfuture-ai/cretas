# 采购异常多操作者并发决定

- Task: `PURCHASE-EXCEPTION-CONCURRENT-20261016`
- Base: `394a3183c0d2761c2668ecd5d52b89ad0e23eeae`
- Owner: root; implementation/target verification: purchase_return_unit.
- Scope: purchase exception decision serialization, atomic return creation/association, the exception-number collision that blocked the real UI fixture, corresponding tests, and PostgreSQL CI selection.
- Production: no access or changes. Existing loopback Java, Web, PostgreSQL and private test attachment storage are reused.

## Problem and required behavior

Two operators could both read the same pending exception. Its return creation used a separate committed transaction; a later exception-state conflict could leave an unassociated return. Decision state, return creation and association must commit together. An already handled exception must reject another decision before creating another return.

Preparing the real UI fixture also exposed a pending-count-based exception number collision after earlier exceptions were resolved. The failed receiving transaction rolled back; its original draft and attachment remain the continuation point.

## Verification scope

- Two independent UI login sessions process the same exception concurrently.
- Exactly one effective return is linked; no extra source-order return remains.
- Submit, business review, financial review and completion remain explicit UI actions.
- Inventory and approved payable adjustment each take effect once.
- Child and association failure injection must hit the write path and prove full rollback.
- Repository query initialization must run in a real Hibernate context; PostgreSQL tests must execute in the opt-in CI job.
- Web source tree is unchanged: `8822aaddeefbb3f9f9f123797e4a26249517ccd3`. Reuse the preceding 5,606 passing Web tests, five historical skips and successful build; do not rerun them for backend-only changes.

## Frozen implementation and target checks

- The decision query locks the matching factory/exception row. A later decision returns 409.
- Return creation joins the decision transaction. Unexpected child failures propagate; association failure rolls back the already inserted return as well as the exception state.
- The entry accepts only a validated factory actor belonging to the path factory. Platform actors, missing typed identity and other-factory actors are rejected before service writes; a platform numeric ID cannot become a factory owner ID.
- Number allocation uses a factory/day advisory transaction lock, a numeric suffix query and fail-closed parsing. Resolved records remain in the sequence; malformed suffixes are excluded without loading all factory history.
- Entity changes are comments only; no mapping or migration changes.
- `PurchaseExceptionServiceTest` and `PurchaseExceptionConcurrencyContractTest`: 31 passing tests.
- `PurchaseExceptionActorContractTest`: 5 passing tests; `PurchaseExceptionReturnUnitContractTest`: 13 passing tests.
- `PurchaseExceptionConcurrencyPostgresContractTest`: 3 passing tests, including confirmed child-write and association-write failure injection.
- `PurchaseExceptionRepositoryQueryValidationTest`: 1 passing real Hibernate/Spring Data/JpaTransactionManager/PostgreSQL test covering tenant lookup, row-lock blocking/read-after-commit and concurrent number allocation.
- Remove `@Lock`: actual repository test fails because the second call does not wait (1 failure, 0 errors).
- Change child propagation back to `REQUIRES_NEW`: association failure leaves one return; expected-zero assertion fails (1 failure, 0 errors).
- Restore both changes: all target groups pass with `BUILD SUCCESS`.
- Real UI then exposed PostgreSQL bind inference with the application's `stringtype=unspecified` connection setting. The repository test now uses that setting: it reproduced SQLSTATE 42P18 before the explicit text casts and passes after them. Only that affected real JPA test was rerun; unchanged service, actor, unit and rollback evidence is reused.
- First final CI run executed 15,318 tests and found one new failure: the global 409-hint coverage contract. The resolved-exception 409 now tells the operator to refresh the exception list and view the effective decision; the existing resolved-case test checks status, semantic code, hint and zero downstream writes. The failed gate and affected decision tests are rerun before a new final CI; the baseline is unchanged. Core UI/stock/payable evidence is reused because this increment only adds the action hint.
- PostgreSQL CI explicitly selects both new database test classes; the actor contract matches the normal contract selector. Final CI results belong to the PR's exact-head checks and delivery comment.
- Workflow gates: 12 merge-result assertions and 5 selector assertions pass. The ACTIVE gate passes while the task is registered; its final scope release is checked again at delivery.

## Evidence

Private evidence directory: `Documents/assembly-integration-assessment-20261015/target-ui/exception-*` (not committed).

## Real UI and database result

- Two real factory accounts, IDs 1 and 2, logged in through independent browser contexts. Both opened the same pending exception and confirmed concurrently; a before-send barrier observed both requests before releasing either.
- Account 2 succeeded (200); account 1 received `PURCHASE_EXCEPTION_ALREADY_RESOLVED` (409). Exactly one source-order return was created and linked to the exception; orphan count is zero. The draft decision changed neither stock nor payable balance.
- Through UI, the same return progressed `DRAFT → SUBMITTED → APPROVED → FINANCE_APPROVED → COMPLETED`. The return was for 10 kg, amount 50; its number is `RT-PUR-20261017-0001`.
- Stock readback: flour 287 → 277 kg; bags remain 450 个. Exactly one new inventory ledger row deducts 10 kg for that return.
- Exactly one payable adjustment of -50 was created. It remained pending until the independent second account approved it through UI. Supplier balance 1,563.99 → 1,513.99; the supplier drawer and inventory UI agree with SQL.
- Final business network capture contains exactly the one expected 409 and no unexpected failures. Post-restoration readback still contains only the same effective return and adjustment.
- Temporary procurement/finance overrides were cleared through Canvas UI; inventory tolerance was restored. Factory settings business values, module configuration, organization, roles and users agree with the baseline. Configuration version/audit metadata reflects legitimate temporary saves.
- The existing-factory control in the same test database is unchanged across all 505 compared tables. No production access or changes occurred.
- The local machine reboot interrupted processes during the slice; the same database was recovered and its key tables matched the pre-reboot snapshot. An attempted isolated class JAR was abandoned after a local proxy bootstrap failure; final acceptance used the original classpath runtime with the frozen source content.

## Completion boundary

Source and target checks are frozen. Only the exact-head PR checks and manual merge remain after this archive commit; their final run/job IDs, counts and merge SHA are recorded in the PR delivery comment and private final receipt. No production deployment, production migration or production UI acceptance is part of this task. Other procurement exception decision types and unrelated capabilities are not newly accepted by this slice.
