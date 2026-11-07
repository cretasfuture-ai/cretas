# Export credential primary key

Task EXPORT-CREDENTIAL-PRIMARY-KEY-20261107; review, implementation frozen.
Owner 01a1a954/root, base 71a18f0bc81c63640b2b43451f2c10518d9fbdbe.
Independent WT C:/Users/Steve/cretas-factory-export-restore-20261107,
branch codex/export-credential-primary-key-20261107.
Scope: FactoryDataSchemaMetadata.java and
FactoryDataPackageRestorePostgresContractTest.java plus this record.
No Entity/Repository/migration/permission, shared deploy scripts or other owners.

Actual F006 EXPORT31 failed with zero package bytes because source
share_links.token is a varchar(64) primary key. Read-only catalog evidence
shows no credential FK or CHECK shape on this table. Keep its data redacted
with the existing package HMAC mapping and preserve distinct primary keys.
Credential FKs/aliases, incompatible widths/types/CHECKs must still refuse.
No source token values or source row changes are needed or authorized.
Read-only metadata also proves this column is neither generated nor identity.
Sensitive generated/identity columns and composite credential PKs refuse.

Acceptance: real PostgreSQL package tests, canonical JSON credential-PK
non-disclosure/distinctness/tenant scope, actual Java ZIP -> independent CLI
restore, FK alias and short-width refusal, existing vector/typed/mismatch
cleanup tests. Independent review, normal required CI and official release.
Original production failure remains preserved; no blind export replay.

Final real PostgreSQL run: 11/11 passed, zero skips, including the complete
Java ZIP -> CLI two-database restore, distinct/non-disclosed credential PKs,
and credential FK alias, narrow varchar, generated and composite PK negatives.
Existing typed/vector/source-ledger/second-baseline mismatch cleanup coverage
is retained. A tooling run with the PG switch omitted skipped all tests and is
preserved separately, never counted as a pass; the final run explicitly enabled
both PG verification and CLI roundtrip and compiled the changed classes.
