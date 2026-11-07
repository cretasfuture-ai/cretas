#!/usr/bin/env python3
"""Focused CLI regression tests. Actual Java-generated ZIP roundtrip is separate."""
from __future__ import annotations

import argparse
import copy
from datetime import date, datetime, time
from decimal import Decimal
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import uuid
import zipfile

import psycopg2
from psycopg2 import sql

spec = importlib.util.spec_from_file_location("restore_v2", Path(__file__).with_name("restore-factory-export-v2.py"))
restore = importlib.util.module_from_spec(spec)
spec.loader.exec_module(restore)
ARGS = None


class PureContract(unittest.TestCase):
    def test_decimal_exact_beyond_context_precision(self):
        value = Decimal("123456789012345678901234567890.12345678901234567890")
        self.assertEqual(restore.typed_value(value, {"dataType": "numeric"}),
                         restore.typed_value(str(value), {"dataType": "numeric"}))
        self.assertNotEqual(restore.typed_value(value, {"dataType": "numeric"}),
                            restore.typed_value(value + 1, {"dataType": "numeric"}))

    def test_values_have_unambiguous_boundaries(self):
        self.assertNotEqual(restore.row_hash({"a": "x|b=y", "b": "z"}, ["a", "b"]),
                            restore.row_hash({"a": "x", "b": "y|b=z"}, ["a", "b"]))

    def test_date_time_are_typed_and_offsets_normalized(self):
        for value, text, dtype in ((date(2026, 11, 7), "2026-11-07", "date"),
                (time(12, 1, 2, 345678), "12:01:02.345678", "time without time zone")):
            self.assertEqual(restore.typed_value(value, {"dataType": dtype}), restore.typed_value(text, {"dataType": dtype}))
        dtype = {"dataType": "timestamp with time zone"}
        self.assertEqual(restore.typed_value("2026-11-07T12:01:02.123456+08:00", dtype),
                         restore.typed_value("2026-11-07T04:01:02.123456Z", dtype))

    def test_null_numeric_and_negative_zero_are_distinct(self):
        self.assertNotEqual(restore.typed_value(None, {"dataType": "jsonb"}), restore.typed_value("null", {"dataType": "jsonb"}))
        self.assertNotEqual(restore.typed_value(-0.0, {"dataType": "double precision"}), restore.typed_value(0.0, {"dataType": "double precision"}))
        with self.assertRaises(restore.RestoreRefused):
            restore.parse_json_bytes(b'{"a":1,"a":2}', "duplicate")

    def test_vector_scalar_codec_refuses_nonfinite_and_nested_values(self):
        descriptor = {"dataType": "USER-DEFINED", "udtName": "vector"}
        self.assertEqual(restore.typed_value('[0.1,-2,3.25]', descriptor),
                         restore.typed_value('[0.10000000149011612,-2.0,3.250000]', descriptor))
        for invalid in ('[]', '[NaN]', '[Infinity]', '[true]', '[[1]]', '[1e1000]'):
            with self.subTest(value=invalid), self.assertRaises(restore.RestoreRefused):
                restore.typed_value(invalid, descriptor)

    def test_zip_duplicate_and_unregistered_entries_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "bad.zip"
            with zipfile.ZipFile(path, "w") as package:
                package.writestr("manifest.json", "{}")
                package.writestr("manifest.json", "{}")
            with self.assertRaises(restore.RestoreRefused):
                restore.verify_package(path)

    def test_zero_row_table_and_exact_fields_cannot_be_omitted(self):
        rows = [{"factory_id": "F006", "id": 9007199254740993}]
        entries = [{"table": name, "category": "BUSINESS", "rows": count}
                   for name, count in (("owned", 1), ("empty_owned", 0))]
        schema = {"tables": entries, "columns": [{"table": name, "column": column}
                  for name in ("owned", "empty_owned") for column in ("factory_id", "id")]}
        files = []
        table_entries = []
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w") as archive:
            for table, values in (("owned", rows), ("empty_owned", [])):
                path = f"cretas/json/{table}.json"
                body = json.dumps(values).encode()
                archive.writestr(path, body)
                files.append({"path": path, "sha256": restore.sha256_bytes(body), "rows": len(values)})
                table_entries.append({"table": table, "category": "BUSINESS", "rows": len(values), "files": [files[-1]]})
        for kind in ("schema", "migrations"):
            files.append({"path": f"cretas/metadata/{kind}.json", "sha256": kind, "sourceTime": True})
        database = {"database": "CRETAS", "tables": table_entries, "rows": 1, "foreignHitCount": 0,
                    "sourceSchema": files[-2], "migrationMetadata": files[-1]}
        manifest = {"factoryId": "F006", "databases": [database], "files": files}
        with zipfile.ZipFile(output) as archive:
            restore.validate_database_manifest(archive, manifest, "CRETAS", schema)
            missing = copy.deepcopy(manifest)
            missing["databases"][0]["tables"].pop()
            with self.assertRaises(restore.RestoreRefused):
                restore.validate_database_manifest(archive, missing, "CRETAS", schema)
            changed = copy.deepcopy(schema)
            changed["columns"].pop(1)
            with self.assertRaises(restore.RestoreRefused):
                restore.validate_database_manifest(archive, manifest, "CRETAS", changed)
            malformed = copy.deepcopy(manifest)
            malformed["databases"][0]["tables"][0]["files"] = [1]
            with self.assertRaises(restore.RestoreRefused):
                restore.validate_database_manifest(archive, malformed, "CRETAS", schema)


@unittest.skipUnless(ARGS, "explicit owned PG arguments required")
class OwnedPostgresContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.control = psycopg2.connect(host=ARGS.target_host, port=ARGS.target_port,
                                      user=ARGS.target_user, dbname="postgres")
        restore.verify_owned_connection(cls.control, ARGS)
        cls.control.commit()
        cls.control.autocommit = True
        cls.db = "restore_cli_contract_" + uuid.uuid4().hex
        with cls.control.cursor() as cur:
            cur.execute(sql.SQL("CREATE DATABASE {}").format(sql.Identifier(cls.db)))
        cls.conn = psycopg2.connect(host=ARGS.target_host, port=ARGS.target_port,
                                   user=ARGS.target_user, dbname=cls.db)

    @classmethod
    def tearDownClass(cls):
        cls.conn.close()
        with cls.control.cursor() as cur:
            cur.execute(sql.SQL("DROP DATABASE {}").format(sql.Identifier(cls.db)))
            cur.execute("SELECT count(*) FROM pg_database WHERE datname=%s", (cls.db,))
            assert cur.fetchone()[0] == 0
        cls.control.close()

    def tearDown(self):
        self.conn.rollback()

    def test_source_not_valid_check_and_fk_are_proven_without_state_change(self):
        with self.conn.cursor() as cur:
            cur.execute("CREATE TABLE proof_parent(id integer PRIMARY KEY); "
                        "CREATE TABLE proof_child(id integer PRIMARY KEY,parent_id integer,value integer); "
                        "INSERT INTO proof_parent VALUES(1); INSERT INTO proof_child VALUES(1,1,2); "
                        "ALTER TABLE proof_child ADD CONSTRAINT proof_positive CHECK(value>0) NOT VALID; "
                        "ALTER TABLE proof_child ADD CONSTRAINT proof_parent_fk FOREIGN KEY(parent_id) REFERENCES proof_parent(id) NOT VALID")
            cur.execute("SELECT conname,convalidated,pg_get_constraintdef(oid) FROM pg_constraint "
                        "WHERE conrelid='proof_child'::regclass ORDER BY conname")
            before=cur.fetchall()
        seed={"sourceSchema":"public","tables":[{"table":name,"rows":1}
            for name in ("proof_parent","proof_child")]}
        source=dict(seed,**restore.read_target_structure(self.conn,seed))
        fingerprint=restore.structural_fingerprint(self.conn,source)
        result=restore.validate_restored_constraints(self.conn,"fixture")
        self.assertEqual(result['sourceNotValidCount'],2)
        self.assertTrue(result['loadedRowsIntegrityVerified'])
        self.assertTrue(result['sourceValidationStatePreserved'])
        for count in ('expectedCount','validatedCount','stateRestoredCount'):
            self.assertEqual(result[count],2)
        self.assertRegex(result['definitionsFingerprint'],r'^[0-9a-f]{64}$')
        with self.conn.cursor() as cur:
            cur.execute("SELECT conname,convalidated,pg_get_constraintdef(oid) FROM pg_constraint "
                        "WHERE conrelid='proof_child'::regclass ORDER BY conname")
            self.assertEqual(cur.fetchall(),before)
        self.assertEqual(restore.structural_fingerprint(self.conn,source),fingerprint)

    def test_source_not_valid_check_rejects_invalid_loaded_row(self):
        with self.conn.cursor() as cur:
            cur.execute("CREATE TABLE proof_bad_check(value integer); INSERT INTO proof_bad_check VALUES(-987654321); "
                        "ALTER TABLE proof_bad_check ADD CONSTRAINT bad_positive CHECK(value>0) NOT VALID")
        with self.assertRaisesRegex(restore.RestoreRefused,"SQLSTATE=23514") as caught:
            restore.validate_restored_constraints(self.conn,"fixture")
        self.assertNotIn("987654321",str(caught.exception))
        with self.conn.cursor() as cur:
            cur.execute("SELECT convalidated FROM pg_constraint WHERE conname='bad_positive'")
            self.assertEqual(cur.fetchone(),(False,))
            cur.execute("SELECT count(*) FROM proof_bad_check WHERE value=-987654321")
            self.assertEqual(cur.fetchone(),(1,))

    def test_source_not_valid_fk_rejects_orphan_without_repairing_data(self):
        with self.conn.cursor() as cur:
            cur.execute("CREATE TABLE proof_bad_parent(id integer PRIMARY KEY); "
                        "CREATE TABLE proof_bad_child(parent_id integer); INSERT INTO proof_bad_child VALUES(987654321); "
                        "ALTER TABLE proof_bad_child ADD CONSTRAINT bad_parent_fk FOREIGN KEY(parent_id) "
                        "REFERENCES proof_bad_parent(id) NOT VALID")
        with self.assertRaisesRegex(restore.RestoreRefused,"SQLSTATE=23503") as caught:
            restore.validate_restored_constraints(self.conn,"fixture")
        self.assertNotIn("987654321",str(caught.exception))
        with self.conn.cursor() as cur:
            cur.execute("SELECT convalidated FROM pg_constraint WHERE conname='bad_parent_fk'")
            self.assertEqual(cur.fetchone(),(False,))
            cur.execute("SELECT count(*) FROM proof_bad_parent")
            self.assertEqual(cur.fetchone(),(0,))

    def test_unvalidated_domain_constraint_is_not_silently_ignored(self):
        with self.conn.cursor() as cur:
            cur.execute("CREATE DOMAIN proof_domain AS integer; "
                        "ALTER DOMAIN proof_domain ADD CONSTRAINT domain_positive CHECK(VALUE>0) NOT VALID")
        with self.assertRaisesRegex(restore.RestoreRefused,"不支持未验证约束类型"):
            restore.validate_restored_constraints(self.conn,"fixture")

    def test_wide_types_actual_insert_readback(self):
        ddl = "CREATE TEMP TABLE typed_values (n numeric(60,20),i bigint,f double precision,d date,t time,ts timestamptz,b bytea,j jsonb,a bigint[])"
        descriptors = [{"dataType": dtype, "udtName": udt} for dtype, udt in
                       (("numeric", "numeric"), ("bigint", "int8"), ("double precision", "float8"),
                        ("date", "date"), ("time without time zone", "time"),
                        ("timestamp with time zone", "timestamptz"), ("bytea", "bytea"),
                        ("jsonb", "jsonb"), ("ARRAY", "_int8"))]
        values = [Decimal("123456789012345678901234567890.12345678901234567890"), 9007199254740993,
                  -0.0, "2026-11-07", "12:01:02.345678", "2026-11-07T12:01:02.345678+08:00",
                  "\\x00017fff", '{"n":9007199254740993.12345678901234567890,"v":null}', [9007199254740993, None]]
        with self.conn.cursor() as cur:
            cur.execute(ddl)
            cur.execute("INSERT INTO typed_values VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)",
                        [restore.bind_value(value, descriptor) for value, descriptor in zip(values, descriptors)])
            cur.execute("SELECT n,i,f,d,t,ts,b,j::text,a FROM typed_values")
            actual = cur.fetchone()
        for value, returned, descriptor in zip(values, actual, descriptors):
            self.assertEqual(restore.typed_value(value, descriptor), restore.typed_value(returned, descriptor), descriptor)

    def test_wrong_receipt_and_pid_identity_rejected_before_ddl(self):
        receipt = restore.read_json(ARGS.ownership_receipt)
        for field, value in (("nonce", "0" * 64), ("pidStartEpoch", "1"), ("systemIdentifier", "1")):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as directory:
                changed = dict(receipt, **{field: value})
                path = Path(directory) / "wrong-owner.json"
                path.write_text(json.dumps(changed), encoding="utf-8")
                args = copy.copy(ARGS)
                args.ownership_receipt = path
                with self.assertRaises(restore.RestoreRefused):
                    restore.verify_owned_connection(self.conn, args)

    def test_same_count_definition_mutations_are_rejected(self):
        ddl = """CREATE TABLE guard_rows(id integer PRIMARY KEY, value integer,
                     CONSTRAINT positive_value CHECK(value > 0));
                 CREATE INDEX guard_value_idx ON guard_rows(value) WHERE value > 1;
                 CREATE FUNCTION guard_fn() RETURNS trigger LANGUAGE plpgsql AS
                     $$BEGIN NEW.value := NEW.value + 1; RETURN NEW; END$$;
                 CREATE TRIGGER guard_trigger BEFORE INSERT ON guard_rows
                     FOR EACH ROW EXECUTE FUNCTION guard_fn();"""
        with self.conn.cursor() as cur:
            cur.execute("SET search_path=public,pg_catalog")
            cur.execute(ddl)
        seed = {"sourceSchema": "public", "tables": [{"table": "guard_rows", "rows": 0}]}
        source = dict(seed, **restore.read_target_structure(self.conn, seed))
        original = restore.structural_fingerprint(self.conn, source)
        mutations = {
            "check": "ALTER TABLE guard_rows DROP CONSTRAINT positive_value; ALTER TABLE guard_rows ADD CONSTRAINT positive_value CHECK(value >= 0)",
            "function": "CREATE OR REPLACE FUNCTION guard_fn() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN NEW.value := NEW.value + 2; RETURN NEW; END$$",
            "trigger": "DROP TRIGGER guard_trigger ON guard_rows; CREATE TRIGGER guard_trigger BEFORE UPDATE ON guard_rows FOR EACH ROW EXECUTE FUNCTION guard_fn()",
            "index": "DROP INDEX guard_value_idx; CREATE INDEX guard_value_idx ON guard_rows(value) WHERE value > 2",
        }
        for name, mutation in mutations.items():
            with self.subTest(mutation=name):
                with self.conn.cursor() as cur:
                    cur.execute("SAVEPOINT mutation")
                    cur.execute(mutation)
                with self.assertRaises(restore.RestoreRefused):
                    restore.structural_fingerprint(self.conn, source)
                with self.conn.cursor() as cur:
                    cur.execute("ROLLBACK TO SAVEPOINT mutation")
                self.assertEqual(original, restore.structural_fingerprint(self.conn, source))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--target-host", default="127.0.0.1")
    parser.add_argument("--target-port", type=int)
    parser.add_argument("--target-user")
    parser.add_argument("--target-system-id")
    parser.add_argument("--ownership-receipt", type=Path)
    parser.add_argument("--pg-bin", type=Path)
    arguments = parser.parse_args()
    if arguments.target_port:
        ARGS = arguments
        # unittest decorators are evaluated during import; explicitly enable
        # only after the complete owner gate can be checked by setUpClass.
        OwnedPostgresContract.__unittest_skip__ = False
    unittest.main(argv=[__file__], verbosity=2)
