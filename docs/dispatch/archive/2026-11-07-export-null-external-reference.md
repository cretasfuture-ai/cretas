# Export null external reference

Task EXPORT-NULL-EXTERNAL-REFERENCE-20261107, review; implementation frozen.
Owner 01a1a954/root; base 9032dd3a51454295c00f587f0a8203b479409c53.
Independent WT C:/Users/Steve/cretas-factory-export-restore-20261107,
branch codex/export-null-external-reference-20261107.

Actual F006 EXPORT33 refused because factory_configurations.template_id has an
FK to global factory_templates. Scoped read-only evidence proves F006 has two
configuration rows, both with NULL template_id; no shared row is needed.
Support only snapshot-proven zero external references. Any non-NULL reference
still refuses; no shared data is read/exported, owned, deleted or modified.

Root Java scope: FactoryDataSchemaMetadata.java, FactoryDataService.java,
FactoryDataPackageRestorePostgresContractTest.java, ops documentation and this
dispatch record. Core ScopeResolver, FactoryErasure, Catalog ownership, Entity,
Repository, migrations, permissions and shared deployment scripts are excluded.

Parallel task EXPORT-NULL-FK-CLI: aps_worker_helper owns only
scripts/backup/restore-factory-export-v2.py and
restore-factory-export-v2-local-fixture.py. It must independently reject forged
zero-reference proofs whose canonical JSON contains a non-NULL FK, bind factory
identity and exact source FK descriptors, and retain all existing gates.
No Maven or production accounts; no subdelegation. Root serializes final real
PG/Java ZIP CLI execution after both writers freeze.

Acceptance: true owned PG snapshot with two own NULL configurations and another
tenant's non-NULL configuration; no shared template rows in ZIP or restored DB;
non-NULL own references refuse before manifest. Full Java ZIP->CLI typed restore
and prior negative/cleanup cases, independent review, normal CI and release.
Original production failures 31 and 33 remain preserved; no blind replay.

Frozen verification: Python/owned PG 16/16 passed with no skips; Java Package
real PG/CLI 13/13 passed with no skips (terminal 23:15:45). The complete Java ZIP
restore independently reads back zero shared-template rows and two own NULL
configurations. Another tenant's non-NULL reference remains excluded. Own
non-NULL references and cross-schema/same-name parents refuse before manifest.
Source descriptors and target structure comparison now bind referencedSchema;
cross-schema or invisible schema is never guessed. Existing typed/vector,
credential, migration, constraint and two-target cleanup assertions remain.
No production shared template values were read, exported, changed or deleted.
