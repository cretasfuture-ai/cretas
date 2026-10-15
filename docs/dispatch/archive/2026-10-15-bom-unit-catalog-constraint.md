# BOM-UNIT-CATALOG-20261015

Base: `a39038c68e36ff23d32eef5398bb87afe33fd746`. Owner: Codex root.

Shared business repair, delivered separately from platform factory assembly.
V20261028_69 restored a static BOM unit whitelist that excluded governed units.
Add V20261101_58 to retain nonblank database validation while the existing
UnitContractService continues to authorize business writes. No business DML,
service changes, production connection, or deployment.

Frozen validation:

- `BomRecipeCanonicalUnitMigrationContractTest`: 2 passed.
- `FlywayVersionUniquenessTest`: 1 passed. Maven BUILD SUCCESS, exit 0.
- Native PostgreSQL 17: run `scripts/tests/bom-unit-constraint-native-pg.sql`
  using psql with ON_ERROR_STOP=1 against a private local fixture. Creates
  a temporary table, applies the exact migration, retains kg and 个, accepts
  roll and m, rejects empty, whitespace and NULL; 4 rows, then ROLLBACK.
- Mutation: replace the new migration with a no-op; the same native gate
  fails at INSERT roll/m with chk_bri_unit check_violation. Restore bytes;
  the gate passes. This is a runtime constraint failure, not compilation.
- Source hygiene: 14,489 files, 0 NUL; dispatch gate passed; diff check passed.

Migration order: .58 precedes the separate assembly V20261103_01 and V42.
The assembly source V20261103_51 must not also be imported. No production
migration or existing-factory UI acceptance is claimed by these local gates.
