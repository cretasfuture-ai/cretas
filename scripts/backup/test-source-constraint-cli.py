#!/usr/bin/env python3
"""Real two-database CLI proof for NOT VALID CHECK/FK restoration.

This is a private acceptance harness.  It creates only random databases on the
explicitly owned restore cluster, invokes the real v2 CLI, and records no
credentials.  It deliberately keeps source NOT VALID constraints unchanged;
the CLI must validate them in a savepoint and restore the original state.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import uuid
import zipfile

import psycopg2
from psycopg2 import sql


FACTORY = "F006"


def load_restore(path: Path):
    spec = importlib.util.spec_from_file_location("restore_factory_export_v2", path)
    if spec is None or spec.loader is None:
        raise RuntimeError("cannot load restore CLI")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def sha256(body: bytes) -> str:
    return hashlib.sha256(body).hexdigest()


def qident(value: str) -> sql.Identifier:
    return sql.Identifier(value)


def connect(host: str, port: int, user: str, database: str):
    return psycopg2.connect(host=host, port=port, user=user, dbname=database,
                            connect_timeout=15)


def create_database(control, name: str) -> None:
    # CREATE/DROP DATABASE cannot run in a transaction. Use a fresh control
    # connection so a preceding catalog read can never poison this DDL.
    ddl = connect(args_global.host, args_global.port, args_global.user, "postgres")
    ddl.autocommit = True
    try:
        with ddl.cursor() as cur:
            cur.execute("SELECT current_user")
            owner = cur.fetchone()[0]
            cur.execute(sql.SQL("CREATE DATABASE {} OWNER {}").format(qident(name), qident(owner)))
    finally:
        ddl.close()


def drop_database(control, name: str) -> None:
    ddl = connect(args_global.host, args_global.port, args_global.user, "postgres")
    ddl.autocommit = True
    try:
        with ddl.cursor() as cur:
            cur.execute(sql.SQL("DROP DATABASE IF EXISTS {}").format(qident(name)))
    finally:
        ddl.close()


def exec_sql(conn, statement: str) -> None:
    with conn.cursor() as cur:
        cur.execute(statement)
    conn.commit()


def current_major(conn) -> int:
    with conn.cursor() as cur:
        cur.execute("SELECT current_setting('server_version_num')")
        return int(cur.fetchone()[0]) // 10000


def database_oid(control, name: str) -> int:
    with control.cursor() as cur:
        cur.execute("SELECT oid FROM pg_database WHERE datname=%s", (name,))
        row = cur.fetchone()
    if row is None:
        raise AssertionError(f"database was not created: {name}")
    return int(row[0])


def exists(control, name: str) -> bool:
    with control.cursor() as cur:
        cur.execute("SELECT count(*) FROM pg_database WHERE datname=%s", (name,))
        return cur.fetchone()[0] != 0


def migration(database: str) -> dict:
    if database == "CRETAS":
        rows = [{"installedRank": 1, "version": "proof-1", "description": "constraint proof",
                 "type": "SQL", "script": "Vproof.sql", "checksum": 7,
                 "installedBy": "proof", "installedOn": "2026-11-07T00:00:00+00:00",
                 "executionTime": 1, "success": True}]
        return {"format": "cretas-factory-source-migrations/2", "sourceSchema": "public",
                "available": True, "ledgerTable": "flyway_schema_history", "ledgerKind": "FLYWAY",
                "rows": rows, "fingerprintAlgorithm": "SHA-256"}
    rows = [{"installedRank": 1, "version": "proof-1", "description": "Vproof.sql",
             "type": "SMARTBI", "script": "Vproof.sql", "checksum": 7,
             "installedBy": "proof", "installedOn": "2026-11-07T00:00:00+00:00",
             "executionTime": 1, "success": True}]
    return {"format": "cretas-factory-source-migrations/2", "sourceSchema": "public",
            "available": True, "ledgerTable": "smartbi_migrations", "ledgerKind": "SMARTBI",
            "rows": rows, "fingerprintAlgorithm": "SHA-256"}


def source_schema(restore, conn, database: str, tables: list[dict], snapshot_name: str) -> dict:
    structure = restore.read_target_structure(conn, {
        "sourceSchema": "public",
        "tables": tables,
        "columns": [],
    })
    schema = {
        "format": "cretas-factory-source-schema/2",
        "database": database,
        "sourceSchema": "public",
        "tables": tables,
        "columns": structure["columns"],
        "constraints": structure["constraints"],
        "externalReferences": [],
        "indexes": structure["indexes"],
        "sequences": structure["sequences"],
        "triggers": structure["triggers"],
        "dependencyTables": [],
        "snapshot": {"database": snapshot_name, "pgSnapshot": "proof", "transactionId": 1,
                      "capturedAt": "proof", "crossDatabaseAtomic": False},
        "restoreContract": "COMPATIBLE_SCHEMA_REQUIRED",
        "restoreStatus": "REQUIRES_PROTECTED_SCHEMA_BASELINE",
        "rawDdlIncluded": False,
        "coveredObjects": ["tables", "columns", "constraints", "indexes", "sequences", "triggers"],
        "fingerprintAlgorithm": "SHA-256",
    }
    schema["fingerprint"] = restore.structural_fingerprint(conn, schema)
    return schema


def migration_metadata(restore, database: str, snapshot_name: str) -> dict:
    value = migration(database)
    value["snapshot"] = {"database": snapshot_name, "pgSnapshot": "proof", "transactionId": 1,
                          "capturedAt": "proof", "crossDatabaseAtomic": False}
    value["fingerprint"] = restore.migration_digest(value["rows"])
    return value


def json_bytes(value) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def build_package(restore, root: Path, sources: dict[str, tuple[str, list[dict], list[dict]]],
                  factory_id: str) -> tuple[Path, Path, dict[str, str], int]:
    files: dict[str, bytes] = {}
    db_manifests = []
    fingerprints: dict[str, str] = {}
    for database, (dbname, tables, rows_by_table) in sources.items():
        conn = connect(args_global.host, args_global.port, args_global.user, dbname)
        try:
            schema = source_schema(restore, conn, database, tables, dbname)
            migrations = migration_metadata(restore, database, dbname)
        finally:
            conn.close()
        schema_path = f"{database.lower()}/metadata/schema.json"
        migrations_path = f"{database.lower()}/metadata/migrations.json"
        schema_body = json_bytes(schema)
        migrations_body = json_bytes(migrations)
        files[schema_path] = schema_body
        files[migrations_path] = migrations_body
        table_entries = []
        total = 0
        for table, rows in zip((t["table"] for t in tables), rows_by_table):
            path = f"{database.lower()}/json/{table}.json"
            body = json_bytes(rows)
            files[path] = body
            entry = {"table": table, "category": "BUSINESS", "rows": len(rows),
                     "files": [{"path": path, "sha256": sha256(body)}]}
            table_entries.append(entry)
            total += len(rows)
        db_manifests.append({"database": database, "rows": total, "tables": table_entries,
                             "sourceSchema": {"path": schema_path, "sha256": sha256(schema_body), "sourceTime": True},
                             "migrationMetadata": {"path": migrations_path, "sha256": sha256(migrations_body), "sourceTime": True},
                             "notExported": [], "foreignHitCount": 0})
        fingerprints[database] = schema["fingerprint"]
        fingerprints[f"{database}:migration"] = migrations["fingerprint"]
    manifest = {"format": "cretas-factory-export/2", "redaction": {"algorithm": "HMAC-SHA256",
                "mapping": "package-scoped", "keyExported": False}, "factoryId": factory_id,
                "factoryName": "constraint proof", "generatedAt": "2026-11-07T00:00:00Z",
                "requestId": "constraint-proof", "operator": {"type": "PLATFORM_ADMIN", "id": 1},
                "requestedFileFormats": ["JSON"], "fileFormats": ["JSON"], "attachments": {"mode": "SKIP", "objects": 0}}
    manifest["databases"] = db_manifests
    entries = []
    for path, body in files.items():
        rows = len(json.loads(body)) if "/json/" in path else 0
        entries.append({"path": path, "rows": rows, "bytes": len(body), "sha256": sha256(body)})
    manifest["files"] = entries
    package = root / "package.zip"
    with zipfile.ZipFile(package, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path, body in files.items():
            archive.writestr(path, body)
        archive.writestr("manifest.json", json_bytes(manifest))
    baseline = root / "baseline-manifest.json"
    baseline.write_text(json.dumps({db: {"schemaFingerprint": fingerprints[db],
                                         "migrationFingerprint": fingerprints[f"{db}:migration"]}
                                     for db in sources}, indent=2), encoding="utf-8")
    probe = connect(args_global.host, args_global.port, args_global.user, next(iter(sources.values()))[0])
    try:
        major = current_major(probe)
    finally:
        probe.close()
    return package, baseline, fingerprints, major


def dump_schema(pg_bin: Path, host: str, port: int, user: str, database: str, output: Path) -> None:
    executable = pg_bin / ("pg_dump.exe" if os.name == "nt" else "pg_dump")
    result = subprocess.run([str(executable), "-h", host, "-p", str(port), "-U", user, "-d", database,
                             "--format=custom", "--schema-only", "--file", str(output)],
                            capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError("pg_dump failed")


def sidecar(dump: Path, fingerprint: str, migration_fp: str, major: int) -> None:
    dump.with_suffix(dump.suffix + ".json").write_text(json.dumps({
        "format": "cretas-factory-schema-baseline/1", "archiveSha256": sha256(dump.read_bytes()),
        "schemaFingerprint": fingerprint, "migrationFingerprint": migration_fp, "postgresMajor": major}), encoding="utf-8")


def create_sources(control, root: Path, scenario: str) -> tuple[dict, list[str]]:
    names = [f"proof_src_{scenario}_cretas_{uuid.uuid4().hex[:8]}",
             f"proof_src_{scenario}_smartbi_{uuid.uuid4().hex[:8]}"]
    for name in names:
        create_database(control, name)
    cretas_tables = [{"table": "proof_parents", "category": "BUSINESS", "depth": 0,
                      "rows": 1, "selection": "FACTORY", "primaryKeyColumn": "id", "tenantColumns": ["factory_id"]},
                     {"table": "proof_children", "category": "BUSINESS", "depth": 1,
                      "rows": 1, "selection": "FACTORY", "primaryKeyColumn": "id", "tenantColumns": ["factory_id"]},
                     {"table": "proof_orphans", "category": "BUSINESS", "depth": 0,
                      "rows": 1, "selection": "FACTORY", "primaryKeyColumn": "id", "tenantColumns": ["factory_id"]}]
    smartbi_tables = [{"table": "proof_rows", "category": "BUSINESS", "depth": 0,
                       "rows": 1, "selection": "FACTORY", "primaryKeyColumn": "id", "tenantColumns": ["factory_id"]}]
    cretas = connect(args_global.host, args_global.port, args_global.user, names[0])
    smartbi = connect(args_global.host, args_global.port, args_global.user, names[1])
    try:
        exec_sql(cretas, "CREATE TABLE proof_parents (id bigint PRIMARY KEY, factory_id varchar(64) NOT NULL, label text NOT NULL);"
                 "CREATE TABLE proof_children (id bigint PRIMARY KEY, factory_id varchar(64) NOT NULL, parent_id bigint NOT NULL);"
                 "CREATE TABLE proof_orphans (id bigint PRIMARY KEY, factory_id varchar(64) NOT NULL, note text NOT NULL);"
                 "INSERT INTO proof_parents VALUES (1,'F006','ok'); INSERT INTO proof_children VALUES (2,'F006',1);"
                 "INSERT INTO proof_orphans VALUES (3,'F006','orphan-source-negative');"
                 "CREATE TABLE flyway_schema_history (installed_rank int, version varchar(50), description varchar(200), type varchar(20), script varchar(200), checksum int, installed_by varchar(100), installed_on timestamp, execution_time int, success boolean);"
                 "INSERT INTO flyway_schema_history VALUES (1,'proof-1','constraint proof','SQL','Vproof.sql',7,'proof','2026-11-07 00:00:00',1,true);")
        if scenario == "check_bad":
            exec_sql(cretas, "ALTER TABLE proof_parents ADD CONSTRAINT proof_parent_check CHECK (length(label) > 100) NOT VALID;")
        else:
            exec_sql(cretas, "ALTER TABLE proof_parents ADD CONSTRAINT proof_parent_check CHECK (length(label) > 0) NOT VALID;")
        if scenario == "fk_bad":
            exec_sql(cretas, "UPDATE proof_children SET parent_id=999;")
        exec_sql(cretas, "ALTER TABLE proof_children ADD CONSTRAINT proof_child_parent_fk FOREIGN KEY (parent_id) REFERENCES proof_parents(id) NOT VALID;")
        exec_sql(smartbi, "CREATE TABLE proof_rows (id bigint PRIMARY KEY, factory_id varchar(64) NOT NULL, value text NOT NULL);"
                 "INSERT INTO proof_rows VALUES (1,'F006','smartbi');"
                 "ALTER TABLE proof_rows ADD CONSTRAINT proof_rows_check CHECK (length(value) > 0) NOT VALID;"
                 "CREATE TABLE smartbi_migrations (filename varchar(100), version varchar(50), checksum integer, applied_at timestamptz, applied_by varchar(100), duration_ms integer);"
                 "INSERT INTO smartbi_migrations VALUES ('Vproof.sql','proof-1',7,'2026-11-07 00:00:00+00','proof',1);")
        return {
            "CRETAS": (names[0], cretas_tables,
                        [[{"id": 1, "factory_id": FACTORY, "label": "ok"}],
                         [{"id": 2, "factory_id": FACTORY, "parent_id": 999 if scenario == "fk_bad" else 1}],
                         [{"id": 3, "factory_id": FACTORY, "note": "orphan-source-negative"}]]),
            "SMARTBI": (names[1], smartbi_tables, [[{"id": 1, "factory_id": FACTORY, "value": "smartbi"}]]),
        }, names
    finally:
        cretas.close()
        smartbi.close()


def run_cli(cli: Path, root: Path, package: Path, baseline: Path, dumps: dict[str, Path], targets: dict[str, str],
            receipt: Path) -> int:
    command = [sys.executable, str(cli), "--package", str(package),
               "--cretas-schema-baseline", str(dumps["CRETAS"]), "--smartbi-schema-baseline", str(dumps["SMARTBI"]),
               "--baseline-manifest", str(baseline), "--target-host", args_global.host,
               "--target-port", str(args_global.port), "--target-user", args_global.user,
               "--target-system-id", args_global.system_id, "--cretas-target-db", targets["CRETAS"],
               "--smartbi-target-db", targets["SMARTBI"], "--pg-bin", str(args_global.pg_bin),
               "--ownership-receipt", str(args_global.ownership_receipt), "--receipt", str(receipt), "--apply"]
    result = subprocess.run(command, capture_output=True, text=True)
    (root / f"{receipt.stem}-cli-output.txt").write_text(result.stdout + result.stderr, encoding="utf-8")
    return result.returncode


def verify_owner_cli(cli: Path, pg_bin: Path, host: str, port: int, user: str,
                     system_id: str, receipt: Path) -> None:
    result = subprocess.run([sys.executable, str(cli), "--verify-owner-only",
                             "--target-host", host, "--target-port", str(port),
                             "--target-user", user, "--target-system-id", system_id,
                             "--pg-bin", str(pg_bin), "--ownership-receipt", str(receipt)],
                            capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError("verify-owner-only failed")


def assert_receipt(receipt: Path, status: str, targets: dict[str, str], target_ids: dict[str, int],
                   control, success: bool) -> dict:
    body = json.loads(receipt.read_text(encoding="utf-8"))
    if body.get("status") != status:
        raise AssertionError(f"unexpected receipt status: {body.get('status')}")
    states = body.get("databases", {})
    for key, target in targets.items():
        state = states[key]
        if (state.get("database") != target or not isinstance(state.get("actualDbId"), int)
                or state["actualDbId"] != target_ids[key]):
            raise AssertionError(f"actual database identity missing for {key}")
        if success:
            expected_count = 2 if key == "CRETAS" else 1
            validation = state.get("constraintValidation", {})
            if (state.get("stage") != "VERIFIED"
                    or validation.get("sourceValidationStatePreserved") is not True
                    or validation.get("sourceNotValidCount") != expected_count
                    or validation.get("validatedCount") != expected_count
                    or validation.get("stateRestoredCount") != expected_count):
                raise AssertionError(f"constraint proof missing for {key}")
        else:
            expected_stage = "POST_DATA_STARTED" if key == "CRETAS" else "PREFLIGHT_VERIFIED"
            if state.get("stage") != expected_stage:
                raise AssertionError(f"unexpected failure stage for {key}: {state.get('stage')}")
            if state.get("cleanup") != "DESTROYED_AND_VERIFIED":
                raise AssertionError(f"cleanup not proven for {key}")
            if exists(control, target):
                raise AssertionError(f"failed target remains: {target}")
    return body


def constraint_state(database: str) -> list[tuple]:
    conn = connect(args_global.host, args_global.port, args_global.user, database)
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT r.relname,c.conname,c.contype,c.convalidated,md5(pg_get_constraintdef(c.oid)) "
                        "FROM pg_constraint c JOIN pg_class r ON r.oid=c.conrelid "
                        "JOIN pg_namespace n ON n.oid=r.relnamespace "
                        "WHERE n.nspname='public' AND r.relname LIKE 'proof_%' AND c.contype IN ('c','f') "
                        "ORDER BY r.relname,c.conname")
            return cur.fetchall()
    finally:
        conn.close()


def assert_orphan_readback(database: str) -> dict:
    conn = connect(args_global.host, args_global.port, args_global.user, database)
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT count(*), min(note) FROM proof_orphans WHERE factory_id=%s", (FACTORY,))
            count, note = cur.fetchone()
        if count != 1 or note != "orphan-source-negative":
            raise AssertionError(f"orphan source row was changed: count={count}, note={note!r}")
        return {"database": database, "count": count, "note": note}
    finally:
        conn.close()


def run_scenario(restore, cli: Path, control, root: Path, scenario: str, invalid: bool) -> None:
    sources, source_names = create_sources(control, root, scenario)
    try:
        package, baseline, fingerprints, major = build_package(restore, root, sources, FACTORY)
        dumps = {db: root / f"{db.lower()}-{scenario}.dump" for db in ("CRETAS", "SMARTBI")}
        for db, (name, _, _) in sources.items():
            dump_schema(args_global.pg_bin, args_global.host, args_global.port, args_global.user, name, dumps[db])
            sidecar(dumps[db], fingerprints[db], fingerprints[f"{db}:migration"], major)
        targets = {"CRETAS": f"restore_constraint_{scenario}_cretas_{uuid.uuid4().hex[:8]}",
                   "SMARTBI": f"restore_constraint_{scenario}_smartbi_{uuid.uuid4().hex[:8]}"}
        for name in targets.values(): create_database(control, name)
        try:
            target_ids = {key: database_oid(control, name) for key, name in targets.items()}
            receipt = root / f"{scenario}-receipt.json"
            exit_code = run_cli(cli, root, package, baseline, dumps, targets, receipt)
            receipt_body = assert_receipt(receipt, "RESTORE_FAILED" if invalid else "RESTORED_AND_VERIFIED",
                                          targets, target_ids, control, not invalid)
            cli_output = (root / f"{receipt.stem}-cli-output.txt").read_text(encoding="utf-8")
            if invalid:
                expected_sqlstate = "23514" if scenario == "check_bad" else "23503"
                if expected_sqlstate not in cli_output:
                    raise AssertionError(f"missing expected SQLSTATE {expected_sqlstate}")
            if invalid and exit_code == 0:
                raise AssertionError("invalid constraint scenario unexpectedly succeeded")
            if not invalid and exit_code != 0:
                raise AssertionError(f"legal scenario failed: {exit_code}")
            if not invalid:
                source_constraints = constraint_state(sources["CRETAS"][0]) + constraint_state(sources["SMARTBI"][0])
                target_constraints = constraint_state(targets["CRETAS"]) + constraint_state(targets["SMARTBI"])
                if source_constraints != target_constraints or any(row[3] is not False for row in target_constraints):
                    raise AssertionError("target NOT VALID constraint state or definition differs from source")
            orphan_readback = None if invalid else assert_orphan_readback(targets["CRETAS"])
            if invalid:
                for name in targets.values():
                    if exists(control, name): raise AssertionError(f"target not destroyed: {name}")
            else:
                for name in targets.values(): drop_database(control, name)
            return {"scenario": scenario, "exitCode": exit_code, "targetIds": target_ids,
                    "targets": targets, "receipt": str(receipt), "receiptStatus": receipt_body["status"],
                    "orphanReadback": orphan_readback}
        finally:
            for name in targets.values():
                if exists(control, name): drop_database(control, name)
    finally:
        for name in source_names:
            if exists(control, name): drop_database(control, name)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pg-bin", type=Path, required=True)
    parser.add_argument("--target-host", default="127.0.0.1")
    parser.add_argument("--target-port", type=int, required=True)
    parser.add_argument("--target-user", required=True)
    parser.add_argument("--target-system-id", required=True)
    parser.add_argument("--ownership-receipt", type=Path, required=True)
    parser.add_argument("--cli", type=Path, required=True)
    parser.add_argument("--evidence-root", type=Path, required=True)
    global args_global
    args_global = parser.parse_args()
    args_global.host = args_global.target_host
    args_global.port = args_global.target_port
    args_global.user = args_global.target_user
    args_global.system_id = args_global.target_system_id
    args_global.evidence_root.mkdir(parents=True, exist_ok=True)
    restore = load_restore(args_global.cli)
    verify_owner_cli(args_global.cli, args_global.pg_bin, args_global.host, args_global.port,
                     args_global.user, args_global.system_id, args_global.ownership_receipt)
    verify_args = argparse.Namespace(ownership_receipt=args_global.ownership_receipt,
                                     target_system_id=args_global.system_id, target_port=args_global.port,
                                     target_user=args_global.user, pg_bin=args_global.pg_bin)
    with connect(args_global.host, args_global.port, args_global.user, "postgres") as control:
        restore.verify_owned_connection(control, verify_args)
        control.commit()
        results = [
            run_scenario(restore, args_global.cli, control, args_global.evidence_root, "legal", False),
            run_scenario(restore, args_global.cli, control, args_global.evidence_root, "check_bad", True),
            run_scenario(restore, args_global.cli, control, args_global.evidence_root, "fk_bad", True),
        ]
    (args_global.evidence_root / "run-summary.json").write_text(
        json.dumps({"status": "SOURCE_CONSTRAINT_CLI_E2E_VERIFIED", "scenarios": results},
                   ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": "SOURCE_CONSTRAINT_CLI_E2E_VERIFIED", "scenarios": 3,
                      "evidence": str(args_global.evidence_root)}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
