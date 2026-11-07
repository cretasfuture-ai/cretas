# Export source constraint proof

Task EXPORT-SOURCE-CONSTRAINT-PROOF-20261107, review; implementation frozen.
Owner 01a1a954/root; base 726166cdbe38fa7eb76fe0a28023a751dd3f32ac.
Independent WT C:/Users/Steve/cretas-export-constraint-proof-20261107,
branch codex/export-source-constraint-proof-20261107.

Scope: scripts/backup/restore-factory-export-v2.py,
restore-factory-export-v2-local-fixture.py, test-source-constraint-cli.py,
docs/ops/factory-export-v2-restore.md,
and this dispatch record. PR1541 and its Java source/tests remain frozen.
Rebase only this follow-up onto the eventual merged PR1541 main before delivery.
No production database, migration, permissions or shared deploy script changes.
Delivery base: 71a18f0bc81c63640b2b43451f2c10518d9fbdbe (merged PR1541).

An actual protected native schema restore revealed source NOT VALID CHECK/FK
constraints. Preserve exact source structure fingerprints and validation state,
but prove loaded target rows satisfy those constraints by real PostgreSQL
VALIDATE inside a savepoint which is rolled back. Validation failure must refuse
and retain the existing two-target cleanup contract. Never disable constraints.

Acceptance: owned real-PG fixture tests for valid source CHECK/FK with unchanged
state, invalid loaded CHECK/FK rejection, existing structure mutants and typed
row contracts. Independent risk review and normal applicable PR checks required.

Parallel task EXPORT-CONSTRAINT-CLI-E2E: aps_worker_helper owns only new
scripts/backup/test-source-constraint-cli.py. Acceptance: actual two-database CLI
positive source NOT VALID CHECK/FK roundtrip and invalid CHECK/FK failures with
both target OIDs destroyed/read back. It may use only Root's identity-verified
private PG59060 (system 7705401759235219624); no other clients/owners share it.
Root owns the CLI, existing fixture, documentation and dispatch; no subdelegation.

Validation: 14/14 focused Python/owned PostgreSQL contracts passed without skips.
The complete CLI test independently restored two databases with legal NOT VALID
CHECK/FK definitions, read back exact original definition hashes/false validation
states and row contents, and asserted counts 2/2/2 (Cretas) and 1/1/1 (SmartBI).
Separate invalid CHECK/FK cases reached POST_DATA_STARTED with exact SQLSTATE
23514/23503 and independently proved both registered target OIDs destroyed.
The initial first-database cleanup failure is preserved privately; the fix
extends existing identity-checked cleanup to both initially-empty destinations.
No FORCE, other-session termination, row repair or production ALTER is used.

Actual native schema archives also restored locally (622/154 public tables),
with all six original NOT VALID constraint states retained after real validation.
That schema-only evidence is not a production tenant-package restore claim.
