"""Create one password-protected, isolated local PoC database. Never resets it."""
import json
import os
import secrets
import subprocess
import time
from pathlib import Path

import psycopg2

from .contracts import BlacklakeError
from .secrets import save_local_secret, load_local_secret
from .readiness import database_ready


def main():
    directory = Path(os.environ["LOCALAPPDATA"]) / "Cretas/Blacklake"
    evidence = directory / "test-database.dpapi"
    if evidence.exists():
        saved = load_local_secret(evidence)
        database_ready(saved)
        saved["initialization_complete"] = True
        save_local_secret(evidence, saved)
        print(json.dumps({"success": True, "message": "existing test database and RLS verified"}))
        return
    admin_password, runtime_password = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
    environment = dict(os.environ, POSTGRES_PASSWORD=admin_password)
    name = "cretas-blacklake-realdata-pg-20261103"
    # Passing only the variable name keeps credentials off the command line.
    result = subprocess.run(["docker", "run", "-d", "--name", name, "--cpus", "1", "--memory", "512m",
                             "-e", "POSTGRES_PASSWORD", "-e", "POSTGRES_DB=blacklake_poc",
                             "-p", "127.0.0.1::5432", "postgres:16-alpine"],
                            env=environment, capture_output=True, text=True)
    if result.returncode:
        raise BlacklakeError("TEST_CONTAINER_CREATE_FAILED_NO_RESET_PERFORMED")
    container_id = result.stdout.strip()
    mapping = subprocess.run(["docker", "port", container_id, "5432"],
                             check=True, capture_output=True, text=True).stdout.strip()
    if not mapping.startswith("127.0.0.1:"):
        raise BlacklakeError("TEST_DATABASE_MUST_BE_LOOPBACK_ONLY")
    port = int(mapping.rsplit(":", 1)[1])
    admin_dsn = f"postgresql://postgres:{admin_password}@127.0.0.1:{port}/blacklake_poc"
    runtime_dsn = f"postgresql://blacklake_poc_runtime:{runtime_password}@127.0.0.1:{port}/blacklake_poc"
    # Persist recovery evidence before initialization: a failed migration never loses access.
    save_local_secret(evidence, {"admin_dsn": admin_dsn, "runtime_dsn": runtime_dsn,
                                 "container_id": container_id, "container_name": name,
                                 "initialization_complete": False})
    for _ in range(40):
        try:
            conn = psycopg2.connect(admin_dsn, connect_timeout=2)
            break
        except psycopg2.OperationalError:
            time.sleep(1)
    else:
        raise BlacklakeError("TEST_DATABASE_START_TIMEOUT")
    try:
        with conn:
            with conn.cursor() as cur:
                cur.execute((Path(__file__).parent / "migrations/001_blacklake.sql").read_text())
                cur.execute("CREATE ROLE blacklake_poc_runtime LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD %s",
                            (runtime_password,))
                cur.execute("GRANT USAGE ON SCHEMA blacklake TO blacklake_poc_runtime")
                cur.execute("GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA blacklake TO blacklake_poc_runtime")
    finally:
        conn.close()
    saved = load_local_secret(evidence)
    database_ready(saved)
    saved["initialization_complete"] = True
    save_local_secret(evidence, saved)
    print(json.dumps({"success": True, "data": {"host": "127.0.0.1", "port": port},
                      "message": "isolated password-protected test database initialized"}))


if __name__ == "__main__":
    main()
