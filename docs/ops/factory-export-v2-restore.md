# Factory export v2 restore contract

v2 packages are tenant-row exports, not standalone database dumps. The package
contains safe source descriptors and fingerprints; it deliberately does not
contain raw defaults, CHECK/index/function DDL, credentials, or a redaction key.

## Required inputs

The restore operator must supply, separately and privately:

- a schema-only PostgreSQL custom archive for `CRETAS`;
- a schema-only PostgreSQL custom archive for `SMARTBI`;
- one sidecar JSON per archive with format
  `cretas-factory-schema-baseline/1`, archive SHA-256, schema fingerprint,
  migration fingerprint, and the source PostgreSQL major version;
- two owned, empty target databases and an explicit target system identifier;
- an ownership receipt from `create-owned-factory-restore-pg.py`. This helper
  only initializes a previously nonexistent directory. The receipt binds the
  PGDATA nonce, system identifier, loopback port, database role, postmaster PID,
  creation identity and executable. A restart requires a fresh verified receipt;
  an arbitrary local PostgreSQL instance is never an implicit restore target.

The target must be loopback and a high port, and its database names must start
with `restore_` or `cretas_restore_`. No production host, database, default
connection, or password is accepted implicitly.

`--verify-owner-only` performs the same read-only identity check before a test
fixture creates its databases. `--ownership-receipt` is mandatory with `--apply`.
The default requires the source PostgreSQL major to match the target. For an
explicitly selected isolated PG13-to-PG17 restoration, `--allow-pg13-to-pg17`
records both versions in each database receipt. This option does not relax any
schema, migration, sequence or typed row comparison; compatibility is confirmed
only by that package's successful complete restore. Other major transitions are
refused. A different backup's earlier restore never proves this package passed.

## Restore order

Run `scripts/backup/restore-factory-export-v2.py` first without `--apply`.
It verifies ZIP hashes, v2 metadata, HMAC redaction policy, schema and
migration fingerprints, and both baseline archives. Only after that validation
may the operator repeat with `--apply`:

1. `pg_restore --section=pre-data` for each protected baseline;
2. insert typed tenant JSON rows, checking every declared tenant column;
3. `pg_restore --section=post-data` to enable the real constraints, indexes,
   sequences and trigger objects from the baseline;
4. verify every exported row as a typed multiset (including high-precision
   numeric values), migration-ledger rows, per-sequence last-value/is-called
   state, structure descriptors/fingerprint, and that every constraint is
   validated.

The two databases are separate transactions. The package records
`crossDatabaseAtomic=false`; a successful restore in one database never claims
the other database succeeded. A missing or mismatched baseline, unknown type,
external FK dependency, unsupported sensitive CHECK/unique shape, or target
system mismatch is a hard refusal. Constraints are never disabled and missing
rows from another tenant are never copied to satisfy an FK.

Both empty destinations are checked before the first schema restore. Rows and
migration rows share a transaction per database. PostgreSQL sequence changes
and external `pg_restore` stages cannot form a cross-database transaction: on
failure the CLI closes its own connections and attempts plain `DROP DATABASE`
only for its registered, initially empty, identity-verified targets. It never
uses FORCE or terminates other sessions. `--receipt` records each database's
last stage, cleanup proof, and `partialTargetRequiresDestroy`; a blocked cleanup
remains a failure requiring the owner. A previously verified first database is
also destroyed if the second restore fails.

`restore-factory-export-v2-local-fixture.py` now contains focused negative and
typed readback tests, using randomized targets only after the ownership gate.
The actual package roundtrip is the separate Java
`FactoryDataPackageRestorePostgresContractTest`, with CLI execution explicitly
enabled. Neither test substitutes for a production-generated bundle restore.

The redaction mapping is package-scoped HMAC-SHA256. Its key is never included
in the package, and equal source credential values map consistently while
distinct values remain distinct for supported widths.
