"""Independent local Flyway baseline/upgrade and Spring/JPA startup probe.

The probe uses a uniquely named database and keeps all generated logs and
argument files under the final-closure evidence directory.  It never touches
the existing P37 database.
"""
from __future__ import annotations

import json
import os
import re
import shutil
import socket
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
EVIDENCE = Path(r"C:\Users\Steve\Documents\cretas-connect-20261015\factory-data-p37\final-closure-20261019\migration")
PG = Path(r"C:\Program Files\PostgreSQL\17\bin\psql.exe")
PGPORT = "55447"
PGUSER = "assembly_executor"
BASELINE = ROOT / "ops/db-baseline/prod-schema-baseline.sql"
FLYWAY_DIR = ROOT / "backend/java/cretas-api/src/main/resources/db/flyway"
CLASSPATH_FILE = Path(r"C:\Users\Steve\Documents\assembly-integration-assessment-20261015\migrations\flyway-classpath.txt")
RUNNER = ROOT / "scripts/tests/PlatformAssemblyFlywayRunner.java"
P37 = Path(r"C:\Users\Steve\Documents\cretas-connect-20261015\factory-data-p37")


def run(cmd: list[str], *, check=True, capture=True, timeout=300, log=None):
    result = subprocess.run(cmd, text=True, encoding="utf-8", errors="replace",
                            capture_output=capture, timeout=timeout)
    if log and result.stdout:
        log.write_text(log.read_text(encoding="utf-8") + result.stdout, encoding="utf-8")
    if check and result.returncode:
        raise RuntimeError(f"command failed rc={result.returncode}: {Path(cmd[0]).name}")
    return result


def psql(db: str, sql: str, *, check=True, capture=True):
    env = os.environ.copy()
    env["PGCLIENTENCODING"] = "UTF8"
    result = subprocess.run([str(PG), "-h", "127.0.0.1", "-p", PGPORT, "-U", PGUSER,
                             "-d", db, "-v", "ON_ERROR_STOP=1", "-At", "-c", sql],
                            text=True, encoding="utf-8", errors="replace", capture_output=capture,
                            env=env, timeout=300)
    if check and result.returncode:
        raise RuntimeError(f"psql failed rc={result.returncode}: {result.stderr[-400:]}")
    return result.stdout.strip() if capture else ""


def port_free(port: int) -> bool:
    with socket.socket() as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            s.bind(("127.0.0.1", port))
            return True
        except OSError:
            return False


def free_local_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return int(s.getsockname()[1])


def flyway_pass(db: str, classes: Path, locations: str):
    cp = CLASSPATH_FILE.read_text(encoding="utf-8").strip()
    url = f"jdbc:postgresql://127.0.0.1:{PGPORT}/{db}"
    return run(["java", "-cp", f"{classes};{cp}", "PlatformAssemblyFlywayRunner",
                url, PGUSER, "local-trust", locations, "20261101.37"], timeout=600)


def main() -> int:
    if len(sys.argv) != 2 or sys.argv[1] != "NON_PRODUCTION_ONLY":
        print("usage: migration_probe.py NON_PRODUCTION_ONLY", file=sys.stderr)
        return 2
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    db = f"factory_closure_probe_{stamp}_{os.getpid()}".lower()
    log = EVIDENCE / f"migration-probe-{stamp}-{os.getpid()}.log"
    app_log = EVIDENCE / f"spring-jpa-{stamp}-{os.getpid()}.log"
    args_out = EVIDENCE / f"spring-args-{stamp}-{os.getpid()}.args"
    meta = {"task_id": "FACTORY-FINAL-MIGRATION-20261019", "base_sha": "a1d096b8198374c027d335ce770a7bc66cd36373",
            "database": db, "pg": f"127.0.0.1:{PGPORT}", "evidence": str(EVIDENCE), "started": stamp}
    log.write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    created = False
    app = None
    try:
        if not PG.exists():
            raise RuntimeError(f"psql not found: {PG}")
        if not CLASSPATH_FILE.exists():
            raise RuntimeError(f"classpath not found: {CLASSPATH_FILE}")
        baseline_text = BASELINE.read_text(encoding="utf-8")
        m = re.search(r"flyway_schema_history 最大版本:\s*([0-9.]+)", baseline_text)
        if not m:
            raise RuntimeError("baseline version header not found")
        baseline_version = m.group(1)
        if baseline_version != "20261101.37":
            raise RuntimeError(f"unexpected baseline version: {baseline_version}")
        migrations = sorted(p.name for p in FLYWAY_DIR.glob("V*.sql"))
        expected = ["V20261103_63__assembly_binding_unapplied_version.sql"]
        if any(x not in migrations for x in expected):
            raise RuntimeError("current migration V63 missing")
        v64 = [x for x in migrations if x.startswith("V20261103_64")]
        v65 = [x for x in migrations if x.startswith("V20261103_65")]
        meta.update({"baseline_version": baseline_version, "migration_count": len(migrations),
                     "latest_files": migrations[-5:], "v63": True, "v64": v64, "v65": v65})
        log.write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

        run([str(PG), "-h", "127.0.0.1", "-p", PGPORT, "-U", PGUSER, "-d", "postgres",
             "-v", "ON_ERROR_STOP=1", "-c", f'CREATE DATABASE "{db}"'], timeout=120)
        created = True
        psql(db, "DROP SCHEMA public CASCADE; CREATE SCHEMA vector_ext; CREATE EXTENSION vector SCHEMA vector_ext; CREATE SCHEMA public; ALTER EXTENSION vector SET SCHEMA public;")
        # The baseline is a plain SQL dump.  Remove its one schema declaration,
        # because the schema was created above with the vector extension rule.
        baseline_copy = EVIDENCE / f"baseline-{db}.sql"
        baseline_copy.write_text("\n".join(line for line in baseline_text.splitlines() if line.strip() != "CREATE SCHEMA public;") + "\n", encoding="utf-8")
        run([str(PG), "-h", "127.0.0.1", "-p", PGPORT, "-U", PGUSER, "-d", db,
             "-v", "ON_ERROR_STOP=1", "-f", str(baseline_copy)], timeout=600)
        psql(db, "DROP TABLE IF EXISTS public.flyway_schema_history;")
        psql(db, "INSERT INTO factory_type_blueprints (id,name,description,industry_type,default_config,form_templates,rule_templates,product_type_templates,department_templates,is_active,version,created_at,updated_at,deleted_at) VALUES ('closure-sentinel','Closure sentinel','migration control row','TEST','{}'::json,'{}'::json,'{}'::json,'{}'::json,'{}'::json,true,1,current_timestamp,current_timestamp,null);")
        before = psql(db, "SELECT (SELECT count(*) FROM product_types)||':'||(SELECT count(*) FROM raw_material_types)||':'||(SELECT count(*) FROM factory_configurations)||':'||(SELECT count(*) FROM factory_type_blueprints);")
        sentinel = psql(db, "SELECT md5(row(id,name,description,industry_type,default_config,form_templates,rule_templates,product_type_templates,department_templates,is_active,version)::text) FROM factory_type_blueprints WHERE id='closure-sentinel';")
        classes = EVIDENCE / "flyway-runner-classes"
        classes.mkdir(exist_ok=True)
        run(["javac", "-cp", CLASSPATH_FILE.read_text(encoding="utf-8").strip(), "-d", str(classes), str(RUNNER)], timeout=120)
        flyway_pass(db, classes, f"filesystem:{FLYWAY_DIR}")
        first_history = psql(db, "SELECT max(version::text)||':'||count(*) FROM flyway_schema_history WHERE success;")
        flyway_pass(db, classes, f"filesystem:{FLYWAY_DIR}")
        second_history = psql(db, "SELECT max(version::text)||':'||count(*) FROM flyway_schema_history WHERE success;")
        after = psql(db, "SELECT (SELECT count(*) FROM product_types)||':'||(SELECT count(*) FROM raw_material_types)||':'||(SELECT count(*) FROM factory_configurations)||':'||(SELECT count(*) FROM factory_type_blueprints);")
        sentinel_after = psql(db, "SELECT md5(row(id,name,description,industry_type,default_config,form_templates,rule_templates,product_type_templates,department_templates,is_active,version)::text) FROM factory_type_blueprints WHERE id='closure-sentinel';")
        migration_ok = first_history == second_history and before == after and sentinel == sentinel_after
        meta.update({"first_history": first_history, "second_history": second_history, "business_rows_before_after": [before, after],
                     "sentinel_before_after": [sentinel, sentinel_after], "migration_pass": migration_ok})
        if not migration_ok:
            raise RuntimeError(f"migration sentinel/history changed: {meta}")

        # Start from the already-built P37 classes and isolate every DB/port/output override.
        if not port_free(10074):
            raise RuntimeError("port 10074 is occupied")
        object_port = free_local_port()
        raw_args = [json.loads(line) for line in (P37 / "entry-java.args").read_text(encoding="utf-8").splitlines() if line.strip()]
        overrides = {"--spring.datasource.url": f"jdbc:postgresql://127.0.0.1:{PGPORT}/{db}?stringtype=unspecified",
                     "--smartbi.postgres.url": f"jdbc:postgresql://127.0.0.1:{PGPORT}/{db}?stringtype=unspecified",
                     "--server.port": "10074", "--spring.flyway.enabled": "true",
                     "--spring.jpa.hibernate.ddl-auto": "none", "--spring.jpa.generate-ddl": "false",
                     "--spring.jpa.properties.hibernate.hbm2ddl.auto": "none", "--spring.sql.init.mode": "never",
                     "--logging.file.name": str(app_log)}
        # LocalOssAcceptanceConfiguration rejects paths inside the P37 repo;
        # keep the object store beside the evidence, with no external endpoint.
        overrides["--p0.local-objects.root"] = str(Path(r"C:\Users\Steve\Documents") / f"cretas-migration-objects-{db}")
        overrides["--p0.local-objects.port"] = str(object_port)
        args = []
        seen = set()
        for item in raw_args:
            key = item.split("=", 1)[0] if item.startswith("--") else item
            if key in overrides:
                args.append(key + "=" + overrides[key]); seen.add(key)
            else:
                args.append(item)
        for key, value in overrides.items():
            if key not in seen:
                args.append(key + "=" + value)
        args_out.write_text("\n".join(json.dumps(x, ensure_ascii=False) for x in args) + "\n", encoding="utf-8")
        app_stream = app_log.open("a", encoding="utf-8")
        app = subprocess.Popen(["java", *args], cwd=str(P37), stdout=app_stream, stderr=subprocess.STDOUT)
        deadline = time.time() + 180
        lines = []
        health = False
        while time.time() < deadline:
            app_stream.flush()
            try:
                lines = app_log.read_text(encoding="utf-8", errors="replace").splitlines()
            except OSError:
                lines = []
            jpa_ready = any("Initialized JPA EntityManagerFactory" in x or "inventory_audit_ready persistenceBean=primaryEntityManagerFactory" in x for x in lines)
            if any("Started CretasBackendApplication" in x for x in lines) and jpa_ready:
                try:
                    with urllib.request.urlopen("http://127.0.0.1:10074/api/mobile/health", timeout=3) as response:
                        health = response.status == 200
                except Exception:
                    pass
                if health:
                    break
            if app.poll() is not None:
                break
            time.sleep(0.2)
        startup_ok = any("Started CretasBackendApplication" in x for x in lines)
        jpa_ok = any("Initialized JPA EntityManagerFactory" in x or "inventory_audit_ready persistenceBean=primaryEntityManagerFactory" in x for x in lines)
        meta.update({"app_pid": app.pid if app else None, "app_port": 10074, "spring_started": startup_ok,
                     "jpa_initialized": jpa_ok, "health_http_200": health, "app_exit_during_probe": app.poll()})
        log.write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        if not (startup_ok and jpa_ok and health):
            raise RuntimeError(f"Spring/JPA/API startup incomplete: {meta}")
        print(json.dumps({"status": "PASS", "database": db, "app_pid": app.pid, "port": 10074, "evidence": str(EVIDENCE)}, ensure_ascii=False))
        return 0
    except Exception as exc:
        meta.update({"status": "FAIL", "error": str(exc)})
        log.write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"status": "FAIL", "database": db, "evidence": str(EVIDENCE), "error": str(exc)}, ensure_ascii=False), file=sys.stderr)
        return 1
    finally:
        if app is not None and app.poll() is None:
            app.terminate()
            try:
                app.wait(timeout=20)
            except subprocess.TimeoutExpired:
                app.kill()
        try:
            app_stream.close()
        except (NameError, AttributeError):
            pass
        # Keep the probe database for parent review; cleanup requires root decision.
        if created:
            psql("postgres", f"SELECT 1 FROM pg_database WHERE datname='{db}'", check=True)


if __name__ == "__main__":
    raise SystemExit(main())
