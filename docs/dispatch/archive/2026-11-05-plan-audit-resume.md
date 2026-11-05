# Claude plan-audit execution resumed, 2026-11-05

Owner: `01a1a954-e8fa-7a10-8eda-3f5ba0872102`. Source main at first checkout: `5e59baa7e9f6a798cd66cd0032cc34aa2b27dd4f`.
Local evidence directory: `C:\Users\Steve\cretas-audit-evidence-20261105` (not a source or credential directory).

## Boundaries and original state

The main checkout and all other task worktrees remain untouched. The original plan-audit tree at `1c08ebd515` retains its staged `scripts/ops/backup-db.sh` mode-only change (`100644` to `100755`), without content changes. The original Claude JSONL has a chmod/backup-db tool call at line 15669, timestamp `2026-10-26T12:32:42.395Z`; this supports historical provenance but cannot by itself prove the current index writer. Historical Claude receipts are evidence rather than fresh instructions. Scheduling authorization collection, new training/cross-customer exclusion development, template co-building and withdrawal cleanup remain paused. Mall is stopped; this batch does not resume it.

## Security PR continuation

- #1451 original head `6f5b1bc87c388b69a65bd118f50f7c2871d29f35`: existing path/filter and gateway-rule changes reviewed; current non-final checks passed. Added `java-full-ready` to invoke final candidate validation, run `41949436789`. No bypass of its final result.
- #1457 original head `b883f92905c5884c1c854d1d03c97b9196ceb227`: independent review reproduced Unicode role aliases accepted by `equalsIgnoreCase` but missed by the platform assignment boundary. Scope: platform role classification, exact locale-stable annotation matching, create/legacy-token/Excel/import/invitation regression coverage. The proposed fix retains ordinary job titles and real factory/platform identities. No data cleanup is required or performed.
- #1457 target command: `mvn -B test -Dtest=PlatformRole*ContractTest,RequireRole*Test,RequestAuditSupportContractTest,InertPreAuthorizeGapsAuthorizationTest,RawMaterialTypeRoleGateMvcTest,ScheduledTaskControllerTest,SmartBIConfigControllerIsolationTest` with JDK21: 63 tests, zero failures/errors/skips, exit 0. New legacy/Excel tests first failed on the original code; the typed enum role field independently rejects Unicode input as HTTP 400, while the nullable role plus position bypass is required to return 403. Final source matching uses `Locale.ROOT` exact lowercase equality for annotation grants and conservatively classifies every legacy case-insensitive platform alias as restricted for assignments.
- #1438 original head `91f9184f5431a4604fa97ca5d404c6b2ba478ef8`: independent review identified `.25` below production's already-applied `20261104.50`, while out-of-order migration is disabled. A separate worktree owns renumbering to `V20261105_91`, real PostgreSQL upgrade validation and a platform-account AI call limit. No production migration has been attempted by this task.

## Export, erasure and backup evidence

`run-isolated-contracts.ps1` created its own Windows PG17 cluster on a random loopback port and shut down only that cluster on completion. At source `5e59baa7e9`, Maven executed 50 tests, failures/errors/skips `0/0/0`, exit 0:

- FactoryDataCatalog 6, FactoryDataScope PostgreSQL 8, FactoryDataWiring 1, FactoryErasure PostgreSQL 16.
- TenantBackup PostgreSQL 11, TenantBackupRules 5, real AgeBackupEncryptor 3.

The real age test generates its own disposable identity, decrypts ciphertext and compares all plaintext bytes; this is not a production backup restore.

F006 fresh platform login was proven as `userId=1`, `factoryId=PLATFORM`, `role=super_admin`. Preview 24 and export 25 succeeded. The export is CSV+JSON with attachment index, 16,518,621 bytes, SHA-256 `12458918d7999690736ab4a74a11571d90a24be6fd947916c8879e22783ee817`. All 500 manifest file hashes passed, covering 249 tables and 123,766 rows; ledger status and package SHA matched. Payload was verified in process memory and not saved as a plaintext package. No erase, erasure-request, cancel or factory configuration endpoint was called.

The current source runbook already records the October 27 disposable-factory erasure acceptance. A fresh production read-only query confirmed `ERASETEST_20261027A` has zero factory rows and all four receipts 5/6/7/9 remain `SUCCEEDED`. This reuses historical deletion and negative-control evidence; it is not a new deletion drill.

F006's daily/7-day backup plan is enabled, with nine successful runs. Most recent run 14 on November 5 references export 22 and has 8,523,464 encrypted bytes. These records establish scheduled execution, not decryption or recovery of the existing platform-key package. Its offline private-key path remains required for that separate recovery step.

## Java Gate and daily AI evaluation

Post-#1414 Java run `41862828378`: PG job `126689082058` succeeded in 23m23s; its Maven lifecycle took 21m12s. The log confirms `PlatformAiAnalysisGrantMigrationPostgresContractTest` executed 1 test with no failure/error/skip. It used main `4a95f3225a`, so it does not validate subsequent main changes. One successful sample does not establish a cancellation-rate improvement.

November 5 scheduled evaluation: read 8/10, write 1/7. The former login 403 is resolved; this is a real failing result, not an absent measurement. Failure list: production-query clarification rather than the expected batch answer; return-summary wording; delete-customer intent fallback; viewer purchase-order permission denial; reset-config intent mismatch; feature-toggle fallback; READ-mode delete fallback; intermittent missing READ mode tag.

A controlled preview-only repeat executed the seven write-path checks using the existing F006 evaluator account, without `/confirm`: 2/7 passed, five failed, exit 1. The improved READ tag is variable; other failures remain. Before/after rows and sorted content hashes across customers, suppliers, products, purchase orders, sales orders, production plans, production batches, material batches, factory settings and the F006 factory row were identical. Both snapshot files have SHA-256 `19d4e503a410843d592c7f508705960d5ab8a97f2605159856431d3ee9ab76ac`. No business mutation is inferred from a preview status alone; this evidence is limited to the named tables.

The registry probe cron is installed at 03:17 and executed on November 5. Four registered provider/model candidates returned 403; no new paid model was probed or registered. Alert delivery beyond logs is not implemented by this batch.

Fresh online GET-only role matrix: explicit platform super-admin reads tenant backup list (200); F006 factory admin is rejected there (403), reads its own menu-permission list (200), and is rejected for F002 (403). This is an API authorization check, not visual acceptance or a claim that write/grant endpoints were exercised.

The already-compiled `UserMenuPermissionOperatorIdentityAuthorizationTest` and `SmartBICostProductAccessControllerContractTest` were then executed with `mvn -B surefire:test -Dtest=UserMenuPermissionOperatorIdentityAuthorizationTest,SmartBICostProductAccessControllerContractTest`: 11 tests, failures/errors/skips 0/0/0, exit 0. Independent final read-only review of the #1457 repair reported no remaining high/medium code blockers; final candidate CI still applies.

## Release coordination

Main coordinator `01a1a94c-b516-7090-ac89-2dd42889f67a` assigned this group's release priority before food-DAG and business acceptance. Existing release locks and active atomic operations take precedence. This task has not entered the release window, merged a PR or deployed a component at this checkpoint. Production active upstream was read as 10020; it must be re-read at actual release time. Live coordination is in the task's own `协调状态/方案审计.md` file, not in another group's status.
