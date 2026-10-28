# ERP-S302-OPENING-SCOPE-20261024

## Final source freeze and integrated delivery

Backend14/Web2 source scopes are frozen and returned to Root's integrated release. Child task rows retire in this final source commit; business acceptance remains under ERP-S0S3-ACCEPTANCE-20261023. No merge/deployment/production cancellation is claimed at freeze.

Actual PG13 final target: StocktakeDriftRecoveryPostgresContractTest5/0fail/0error/0skip, exit0,102.6s; evidence coordination/s302-opening-scope-20261024/root-cancel-actual-pg-v4-exit.json. Exact test source SHA256 EE4D15011FE524E15442504DE7FC1F63F3C60C7E1BE1FA7D055F51CAAE560210. Besides exact migration and committed audit/version, real ExcelUtil→bulk preview/confirm→real stocktake service→independent transaction reads now prove85 existing batches: manual snapshot85 retained after cancellation; only two explicit existing batch numbers yield2 COUNTING items, expected quantities, no submit/approval/apply/workflow and unchanged complete batch rows. No fake opening inventory, voucher or workflow call. The prior4-skipped run and v3 failure remain. v3 failed because this migration fixture changes Hibernate varchar to actual migration TEXT after prepared statements existed; only this test's pool connections are retired after DDL, matching production application startup. Product configuration and assertions unchanged; v4 passes all5.

Service49, bulk13, controller2, Repository JPA2 and Web8 target evidence remains reusable by unchanged content. Final integrated source checks/build, official publication, real normal-UI cancellation/reimport and originalQA/independent SQL remain mandatory. No completed whole-stage claim.

## 10:40 source handoff and pending acceptance

Both execution Owners returned frozen scopes to Root; Root now owns only these registered backend14/Web2 integration paths. Web0705960064590e3ab5f7b52fee1fb7cf27799ad0 has8 mounted checks passed; cancellation backend remains uncommitted pending realPG. Independent review identified a test-only mismatch: the persisted CANCELLED row must remain in history; exclusion is checked through findActiveStocktakesForWarehouseAndMonth, not the unfiltered monthly history query. Root corrected that assertion while retaining independent persisted audit/item/inventory assertions. The earlier opt-in PG run skipped4 tests and remains NOT PASSED. Do not merge/deploy/sign business acceptance from targets alone. Windows currently has other Owners' JVMs; no shared process stopped or additional heavy run started.

OriginalQA registered ERP-S3-OPENING-PREVIEW-SCOPE-001; existing COUNTING task85items and two zero-quantity batches remain untouched. Private readback preparation is not production evidence. After valid final gates and official release, originalQA must cancel through normal UI, verify audit/history/zero inventory independently, then retry exactly the existing two batch IDs through normal scoped import and independent approval.

State in-progress. Sole source Owner /root/s315_external_semi_input; Root interface/integration/release; originalQA phone/browser/canonical. Base7d4603b6d3f7373967a1217e2a275d228239418a, starting candidate b11125742632bec731fc10c45f904210c4638260 in C:/Users/Steve/cretas-s315-semi-input-20261024.

Actual originalQA preview2, confirm85: stocktake02cb04b2-3235-411f-920b-ceef9ecf2f81, COUNTING, two counted QA rows plus83 unrelated uncounted snapshots. Independent created SQL HOLD,20 protected groups unchanged, targettwo new batches quantity0/nullprice, no OA/finance. Never submit/apply or fill unrelated rows with assumed values. Original failure retained in execution/s302-stock-postrelease-original-qa-20261024 and coordination/s302-stock-preflight-20261024.

Root interface decision: OPENING import is the selected opening lines, not permission to inventory the whole warehouse. Add an internal explicit scoped-opening service entry using already resolved materialBatchIds from import; retain existing public/manual/NORMAL full-warehouse semantics. Do not accept arbitrary client scope in REST DTO. Validate exact nonempty unique IDs, all same-factory/same-warehouse eligible undeleted batches; no silently dropped/missing/foreign identity. Reuse existing snapshot quantity, unsettled consumption, cutoff, duplicate month, transaction, updateItems, approval, drift/recovery, apply and posting logic. All selected rows still must be counted. No skip-empty/default0/autoapproval. OPENING recount/recovery must preserve the original selected batch identity, not re-expand to whole warehouse. Reject unsupported scopes honestly.

Exact writable files (no other product files):
- backend/java/cretas-api/src/main/java/com/cretas/aims/service/factory/FactoryStocktakeService.java
- backend/java/cretas-api/src/main/java/com/cretas/aims/service/factory/impl/FactoryStocktakeServiceImpl.java
- backend/java/cretas-api/src/main/java/com/cretas/aims/service/factory/impl/StocktakeBulkImportServiceImpl.java
- backend/java/cretas-api/src/test/java/com/cretas/aims/service/factory/impl/FactoryStocktakeServiceImplTest.java
- backend/java/cretas-api/src/test/java/com/cretas/aims/service/factory/impl/StocktakeBulkImportServiceImplTest.java
- backend/java/cretas-api/src/test/java/com/cretas/aims/integration/StocktakeDriftRecoveryPostgresContractTest.java

No Entity/Repository/queries/migration/controller/security/DTO/frontend/package/lockfile changes. Existing origin/main ACTIVE has no other writer on these six files; S2 retains its11 files. Other candidate source frozen, do not touch. Scope expansion must return exact reason/files before edit.

Acceptance: real methods import two selected lines among additional existing warehouse batches -> exactlytwo saved snapshots and updates; NORMAL/manual still full snapshot. Missing/foreign/otherwarehouse/deleted/duplicate/empty IDs rejected transactionally, selected row uncounted remains blocked. Existing submitted/approved drift protection and independent approval retained; scoped recount does not widen. Preserve all assertions and guards. Target Maven FactoryStocktakeServiceImplTest,StocktakeBulkImportServiceImplTest first (single isolated target, max2CPU/4GiB combined JVM); extend existing real PostgreSQL contract for scoped snapshot/recovery if needed, Root executes opt-in realPG final gate against owned disposable database. Worker no full suite/build/release/production/API/DB/browser/CSV/ACTIVE writes. No child agents. Receipt actualpaths/commit/command/exit/log/unverified/release; source and tests frozen together before single final combined pipeline.

## 09:55 confirmed recovery gap and bounded extension

The actual COUNTING task cannot be cancelled through any existing normal UI/API. It holds the warehouse/month lock and prevents the corrected retry. Do not fake REJECTED, fill83 uncounted rows, remove evidence or SQL-edit production. Add a real audited CANCELLED terminal state solely for this unsubmitted lifecycle. Public PG13 catalog v2 confirms status VARCHAR(30), no status enum/check; adding dedicated audit fields still requires the migration below. Original wide catalog HOLD (same-named archive table) is retained and superseded by exact public schema evidence.

Backend sole Owner remains /root/s315_external_semi_input. Finish any currently running target test before this extension. In addition to the six paths above, release exactly:
- backend/java/cretas-api/src/main/java/com/cretas/aims/entity/factory/FactoryStocktake.java
- backend/java/cretas-api/src/main/java/com/cretas/aims/repository/factory/FactoryStocktakeRepository.java
- backend/java/cretas-api/src/main/java/com/cretas/aims/dto/factory/StocktakeDTO.java
- backend/java/cretas-api/src/main/java/com/cretas/aims/dto/factory/StocktakeCancelRequest.java
- backend/java/cretas-api/src/main/java/com/cretas/aims/controller/StocktakeController.java
- backend/java/cretas-api/src/main/resources/db/flyway/V20261103_77_2__stocktake_cancellation_audit.sql
- backend/java/cretas-api/src/test/java/com/cretas/aims/repository/FactoryStocktakeAndDeliveryRepositoryQueryValidationTest.java
- backend/java/cretas-api/src/test/java/com/cretas/aims/controller/StocktakeCancellationContractTest.java

V20261103_77_2 is reserved to this task after fresh origin/main filenames and ACTIVE checks; S6 V78/79/80 scopes remain untouched. Do not modify old migrations. Add nullable cancelled_by BIGINT, cancelled_at TIMESTAMP, cancel_reason TEXT; preserve existing notes, reject fields and all business rows. CANCELLED is excluded from both existing active-stocktake queries; no deleted records or permission expansion. DTO exposes cancelledBy/cancelledAt/cancelReason and current version.

Frozen interface for independent Web Owner: POST /api/mobile/{factoryId}/stocktakes/{stocktakeId}/cancel with typed JSON {reason:string, expectedVersion:number}; existing ApiResponse<StocktakeDTO>, success only after persisted CANCELLED. Reason required trimmed1..500. Actor only authenticated request userId, never body. Existing warehouse:read_write permission plus service lookup verifies current active same-factory factory user and **initiatedBy == actorId**. No administrator override or other-person cancellation in this slice. Reuse factory-scoped pessimistic lock and expectedVersion; reject stale versions and all statuses except INITIATED/COUNTING. Reject any workflow/submitted/approved/applied evidence; reject already cancelled. Persist cancellation metadata/version atomically, preserve all items, snapshots, inventory, approvals and financial data. No calls that create workflows, adjustments, consumption or vouchers. Refresh exposes truthful CANCELLED history and releases same-month active block.

Add real-method/HTTP positive and negative tests for maker, non-maker, inactive/foreign/missing actor, stale version, empty/too-long reason, submitted/applied/contradictory state, repeat cancellation and immutable item/inventory side effects. Actual JPA query startup and real PostgreSQL contract must pass, including cancelled exclusion and persisted audit readback. In the existing opt-in random-schema PG contract exercise the exact new Flyway script against a legacy schema shape (not Hibernate-only proof), then persist/read cancellation. Preserve previous drift/recount contracts. Root executes final opt-in PG/build and final high-risk review; no skipped PG counted as passed.

Migration is additive. Before any cancellation write, old app rollback can ignore nullable columns; after CANCELLED is persisted an old app lacking that enum is not a safe rollback target. Recovery then requires compatible roll-forward/rollback code, never reclassifying/deleting cancellation history. Record this restriction in delivery evidence. This scope is Web+backend only; RN cancellation UI is not claimed.

## ERP-S302-CANCEL-WEB-20261024

Base7d4603b6, unique Web Owner /root/s302_cancel_web; same candidate worktree with distinct files, no shared Git index writes until Root grants a safe commit window. Exact paths:
- web-admin/src/views/warehouse/stocktakes/index.vue
- web-admin/src/views/warehouse/stocktakes/__tests__/stocktakeCancel.mount.spec.ts

Normal list/detail cancel button only canWrite plus current actor is initiatedBy and state INITIATED/COUNTING. Use the frozen interface above, explicit nonblank reason and confirmation, no auto-cancel or repeated sends. Preserve reason on failure and show true server error; accept success only actual CANCELLED, then refresh status and audit detail. Cancelled records readonly/no count/submit/apply. Reuse existing request/auth/components, no API helper/package/lock/permissions edits. Existing stocktakeRecount.mount.spec.ts must remain passing unchanged.

UX Flow: warehouse maker sees their unsubmitted task -> chooses Cancel stocktake -> supplies reason and reads that no inventory is changed -> confirms once -> sees Cancelled with reason and time after refresh. No default reason or ambiguous close/back-as-cancel. Cancellation is a deliberate business action, separate from closing a dialog.

Web targets: rendered stocktakeCancel.mount.spec.ts and existing stocktakeRecount.mount.spec.ts, 1worker max2GiB, actual request payload/status/error/repeatedclick controls. No browser/production/API/CSV/ACTIVE or backend edits by Web worker, no subagents/fullbuild. Root collects exact file/command/exit/commit/unverified receipt. Existing shared worker is sole Java writer.
## 10:11 migration ordering correction before freeze
Root found application-pg and application-pg-prod have spring.flyway.out-of-order=false. ReservingV81 before pendingS6 V78/79/80 would make those migrations lower than the applied version and block later integration. Fresh origin/main c0f832ec contains maxV77_1; fresh ACTIVE has no77_2 reservation. Release exact NEW path V20261103_77_2__stocktake_cancellation_audit.sql instead of uncommittedV81; no deployed migration is renamed. Sole backendOwner renames its own new file and updates its test reference after current atomic target test completes. V81 reservation released; S6 files unchanged. Include actual order77_1→77_2 and compatibility with later78/79 in final migration evidence. Do not enable out-of-order or modify existing migration checksums. Earlier V81 registration is historical planning and superseded before commit/release.
