"""Verify retained local test resources before reporting initialization success."""
import json
import subprocess

import psycopg2

from .contracts import BlacklakeError


def container_port(container_id, container_port, expected_image):
    result = subprocess.run([
        "docker", "inspect", "--format",
        '{{json .State.Running}}|{{.Config.Image}}|{{json .NetworkSettings.Ports}}', container_id],
        capture_output=True, text=True, check=True)
    running, image, encoded = result.stdout.strip().split("|", 2)
    bindings = json.loads(encoded).get(container_port, [])
    if running != "true" or image != expected_image or len(bindings) != 1 or bindings[0]["HostIp"] != "127.0.0.1":
        raise BlacklakeError("LOCAL_TEST_CONTAINER_IDENTITY_MISMATCH")
    return int(bindings[0]["HostPort"])


def validate_archive_schema(cur):
    cur.execute("SELECT column_name,data_type,is_nullable FROM information_schema.columns "
                "WHERE table_schema='blacklake' AND table_name='full_snapshot'")
    actual = {name: (kind, nullable) for name, kind, nullable in cur.fetchall()}
    expected = {name: ("text", "NO") for name in
                ("tenant_id", "source_factory_id", "run_id", "resource", "external_id", "content_hash")}
    expected.update(source_updated_at=("timestamp with time zone", "YES"),
                    normalized=("jsonb", "NO"), observed_at=("timestamp with time zone", "NO"))
    cur.execute("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                "WHERE conrelid='blacklake.full_snapshot'::regclass AND contype='p'")
    primary = cur.fetchall()
    if actual != expected or primary != [("PRIMARY KEY (tenant_id, source_factory_id, run_id, resource, external_id)",)]:
        raise BlacklakeError("ARCHIVE_SCHEMA_CONTRACT_MISMATCH")
    cur.execute("SELECT pg_get_expr(polqual,polrelid),pg_get_expr(polwithcheck,polrelid),polcmd,polroles "
                "FROM pg_policy WHERE polrelid='blacklake.full_snapshot'::regclass")
    policies = cur.fetchall()
    # Exact policy expressions prevent a permissive extra policy from bypassing isolation.
    expression = "((tenant_id = current_setting('blacklake.tenant_id'::text, true)) AND (source_factory_id = current_setting('blacklake.source_factory_id'::text, true)))"
    if policies != [(expression, expression, '*', [0])]:
        raise BlacklakeError("ARCHIVE_SCOPE_POLICY_CONTRACT_MISMATCH")
    cur.execute("SELECT has_table_privilege(current_user,'blacklake.full_snapshot','DELETE') "
                "OR has_table_privilege(current_user,'blacklake.full_snapshot','TRUNCATE') "
                "OR has_table_privilege(current_user,'blacklake.full_snapshot','REFERENCES') "
                "OR has_table_privilege(current_user,'blacklake.full_snapshot','TRIGGER') "
                "OR has_schema_privilege(current_user,'blacklake','CREATE')")
    if cur.fetchone()[0]:
        raise BlacklakeError("ARCHIVE_RUNTIME_EXCESS_PRIVILEGES")


def database_ready(evidence, *, require_archive=False):
    from psycopg2.extensions import parse_dsn
    destination = parse_dsn(evidence["runtime_dsn"])
    port = container_port(evidence["container_id"], "5432/tcp", "postgres:16-alpine")
    if (destination.get("host") != "127.0.0.1" or destination.get("dbname") != "blacklake_poc"
            or int(destination["port"]) != port):
        raise BlacklakeError("LOCAL_TEST_DATABASE_IDENTITY_MISMATCH")
    connection = psycopg2.connect(evidence["runtime_dsn"], connect_timeout=3)
    try:
        with connection.cursor() as cur:
            cur.execute("SELECT rolsuper,rolbypassrls FROM pg_roles WHERE rolname=current_user")
            if any(cur.fetchone()):
                raise BlacklakeError("DATABASE_ROLE_BYPASSES_RLS")
            cur.execute("SELECT c.relname,c.relrowsecurity,c.relforcerowsecurity "
                        "FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
                        "WHERE n.nspname='blacklake' AND c.relkind='r'")
            tables = cur.fetchall()
            names = {row[0] for row in tables}
            if require_archive and "full_snapshot" not in names:
                raise BlacklakeError("ARCHIVE_SCHEMA_MISSING")
            if "full_snapshot" in names:
                validate_archive_schema(cur)
            required = {"raw_record", "record", "cursor", "sync_run"}
            if (not required <= names or not names <= required | {"full_snapshot"}
                    or any(not row[1] or not row[2] for row in tables)):
                raise BlacklakeError("DATABASE_SCHEMA_OR_RLS_INCOMPLETE")
            for table in sorted(names):
                cur.execute("SELECT has_table_privilege(current_user,%s,'SELECT') "
                            "AND has_table_privilege(current_user,%s,'INSERT') "
                            "AND has_table_privilege(current_user,%s,'UPDATE')",
                            ("blacklake." + table,) * 3)
                if not cur.fetchone()[0]:
                    raise BlacklakeError("DATABASE_RUNTIME_GRANTS_INCOMPLETE")
                cur.execute("SELECT count(*) FROM pg_policy WHERE polrelid=%s::regclass "
                            "AND polname='scope' AND polqual IS NOT NULL AND polwithcheck IS NOT NULL",
                            ("blacklake." + table,))
                if cur.fetchone()[0] != 1:
                    raise BlacklakeError("DATABASE_SCOPE_POLICY_MISSING")
                # Fixed, internal identifiers; absence of scope must expose zero rows.
                cur.execute("SELECT count(*) FROM blacklake." + table)
                if cur.fetchone()[0] != 0:
                    raise BlacklakeError("DATABASE_RLS_EXPOSES_UNSCOPED_ROWS")
    finally:
        connection.close()
    return port
