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


def database_ready(evidence):
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
            if ({row[0] for row in tables} != {"raw_record", "record", "cursor", "sync_run"}
                    or any(not row[1] or not row[2] for row in tables)):
                raise BlacklakeError("DATABASE_SCHEMA_OR_RLS_INCOMPLETE")
            for table in ("raw_record", "record", "cursor", "sync_run"):
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
