# FACTORY-EXPORT-RESTORE-20261107

## Frozen delivery candidate

Delivery Base SHA: `8d2e9e2ecd3ae39c7818d46b1eba1d305044d308` after normal
integration of current main; original task-start Base `49009fd44ad1f348c5599a460744dd7ca177aebe`
is retained below. The final PR diff is limited to this export slice.

Owner: 01a1a954/root. The previous BLOCK dispositions below are historical;
the final candidate implements canonical JSON in every v2 package (CSV/XLSX
remain optional views), source schema and migration descriptors, package-scoped
typed redaction, and a protected-baseline restore CLI. Unknown sensitive
non-text columns, incompatible constraints and unavailable migration ledgers
refuse before a successful manifest. Numeric token usage exceptions bind exact
table/column/type/scale; unknown token names remain sensitive.

The restore independently checks target definitions, type signatures (including
pgvector dimensions), rows, migrations and sequences. Actual extension-owned
vector types use a finite float32 codec; unknown UDTs and non-default array
bounds refuse. ZIP/file/table/zero-row/column closure is mandatory. The private
target gate binds SCRAM credentials, PGDATA nonce, system/PID/start/executable,
empty owned databases and database OIDs. A second-database failure destroys
both registered partial targets without FORCE and records per-database proof.

Validation on owned PostgreSQL 17: 26 Java tests passed with zero skips
(Catalog 8, Scope 11, Package 7), including Java-generated two-database ZIP
restore, broad scalar/array/vector types, CSV-to-canonical-JSON and a changed
CHECK in the second baseline followed by verified cleanup of both targets.
The frozen run also covers shadow vector/custom UUID/fixed-width credential and
missing-ledger rejection before manifest; focused Python contracts passed 10/10,
including same-count CHECK/function/trigger/index mutations and owner tampering.
Exact local logs/hashes are in the private six-item acceptance evidence root.
Independent final risk review is CLEAR. Both owned fixture clusters were
stopped after fresh identity/client checks; pidfiles and listeners are absent,
with data directories retained. The last system identifier was
7705401759235219624 on port 59060.
No Entity/Repository, migration, Security, erasure or backup scheduler code changed.

Commit/push/PR/merge/production deployment and a production-generated F006 bundle
restore remain separate delivery facts; these local results do not claim them.
The old redacted run14 remains failed/irrecoverable, while the earlier full SQL
restore proof is retained separately. No data is restored into production.

Owner 01a1a954/root, implementation export_worker. Base49009fd44ad1f348c5599a460744dd7ca177aebe. Independent WT C:/Users/Steve/cretas-factory-export-restore-20261107, branch codex/factory-export-restore-contract-20261107.

Scope lock: service/factorydata/FactoryDataPackageWriter.java, FactoryDataCatalog.java, FactoryDataService.java, FactoryDataXlsxBook.java and new export schema/redaction helpers only; corresponding factorydata tests. Root alone owns ACTIVE and this archive. No FactoryErasure*, ScopeResolver, audit retention, TenantBackup scheduler/repository, Security/permissions, production backup-db.sh, food SOP/material/plan modules, Blacklake or AI configs.

Root-only integration scope also includes the explicit PG test selector in `.github/workflows/java-gate-pr.yml`: append FactoryDataPackageRestorePostgresContractTest, preserving the latest main selector union. No worker edits this shared workflow. Source tests are not rerun solely for this CI registration or evidence documentation.

## Second review disposition

The second independent review remains BLOCK. Adopt a bounded `COMPATIBLE_SCHEMA_REQUIRED` v2 restoration contract rather than a partial DDL generator. Export must include every exportable scoped table, including zero-row tables, complete safe structure fingerprints, real migration-ledger content/fingerprints for both databases, separate database snapshot/time identities and `crossDatabaseAtomic=false`. Raw default/CHECK/index/function DDL must not leak through metadata.

Root will supply the new protected acceptance bundle with actual source schema baselines and migration metadata and restore it in owned databases. Public export metadata must strictly bind those baselines. Unknown or incompatible structures fail closed; a test-only subset importer cannot establish completeness. The implementation worker additionally owns only new `scripts/backup/restore-factory-export-v2.py` and `docs/ops/factory-export-v2-restore.md` if needed for this actual baseline-validation/import contract; no changes to other backup/operations scripts or policy.

Credential detection must cover unknown token spellings. Numeric usage/ID exceptions require exact source table/column/type/scale proof; Root captured schema-only metadata for both real source databases in the private export-source-schema-contract-readonly.json receipt. Redaction must use stable package-scoped mapping of original values, type/width/constraint compatibility and credential FK alias propagation; reject unsupported cases before declaring a successful manifest. Do not call the 17-test partial fixture result full restore approval.

Root integration review requires an actual local baseline-tool round trip before delivery. A sidecar assertion plus row counts is insufficient. The worker may add one shared read-only catalog-profile resource under `src/main/resources/factorydata/` and corresponding focused Python/tool tests so Java export and the restore tool compute the same safe structural fingerprint from actual catalog reads. The target must independently recompute and match structure, migration rows, relevant sequence state and full typed row content. Preserve Decimal precision, generated/identity semantics, safe empty-target guards and both independent source/target databases. Production baseline sidecars are Root's later acceptance artifact; synthetic baseline sidecars can be generated from the owned source fixtures and are not an external blocker to the local tool test.

Requirement: numeric usage and ID fields must retain their typed values; credentials remain removed, distinct credential values must not collapse unique constraints; include source-time restore structure and migration metadata rather than borrow current schema. Preserve ZIP path/hash integrity, F006 scope closure, fail closed on unsupported restoration. No invented lost original values or production restore. New packages remain protected/private. Genuine PG integration must create a package, restore into independent schema/database and verify types, constraints, sequences, row content and negative tenant scope. Do not label the old failed run14/export22 as repaired.

Acceptance: FactoryDataCatalogContractTest, FactoryDataWiringContractTest, actual FactoryDataScopePostgresContractTest and new FactoryData*PostgresContractTest using an owned disposable PG; target command through release --tests later. Tests reading src/main must use ContractTest naming. Existing real-PG helper reused. No new migration unless independently justified and coordinated. Coordinator reviews risk, integration/CI, commits/push/PR and exact-main production release. No production business writes delegated.
