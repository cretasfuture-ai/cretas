#!/usr/bin/env python3
"""Initialize a NEW loopback-only restore cluster; never reuse or delete PGDATA."""
from __future__ import annotations

import argparse
import importlib.util
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import csv
import io
import atexit
import time

import psycopg2


def protect_private_directory(root: Path) -> None:
    if os.name == "nt":
        identity = subprocess.run(["whoami", "/user", "/fo", "csv", "/nh"],
            capture_output=True, text=True, check=True)
        sid = next(csv.reader(io.StringIO(identity.stdout.strip())))[1]
        subprocess.run(["icacls", str(root), "/inheritance:r", "/grant:r",
            f"*{sid}:(OI)(CI)F", "*S-1-5-18:(OI)(CI)F"],
            capture_output=True, text=True, check=True)
    else:
        root.chmod(0o700)


def stop_failed_startup(root: Path, data: Path, pg_bin: Path, started_after: int,
                        state: dict, restore) -> None:
    if state.get("complete"):
        return
    result = {"status": "INITIALIZATION_FAILED", "partialClusterRequiresOwnerAction": True}
    try:
        pid_file = data / "postmaster.pid"
        for _ in range(8):
            if pid_file.exists():
                break
            time.sleep(0.25)
        if not pid_file.exists():
            # An absent pidfile after a failed/timed-out spawn cannot prove
            # there is no late-starting postmaster. Preserve this uncertainty.
            result["cleanup"] = "NO_PIDFILE_STARTUP_UNVERIFIED"
        else:
            lines = pid_file.read_text().splitlines()
            if (len(lines) < 4 or Path(lines[1]).resolve() != data.resolve()
                    or int(lines[2]) < started_after):
                raise RuntimeError("failed-startup PGDATA/start identity mismatch")
            identity = restore.process_identity(int(lines[0]))
            executable = pg_bin / ("postgres.exe" if os.name == "nt" else "postgres")
            if identity["executable"] != os.path.normcase(str(executable.resolve())):
                raise RuntimeError("failed-startup executable mismatch")
            with (root / "failed-initialization-stop.log").open("w", encoding="utf-8") as output:
                subprocess.run([str(pg_bin / ("pg_ctl.exe" if os.name == "nt" else "pg_ctl")),
                    "-D", str(data), "-w", "stop", "-m", "fast"], stdin=subprocess.DEVNULL,
                    stdout=output, stderr=output, check=True, timeout=90)
            if pid_file.exists():
                raise RuntimeError("owned postmaster pidfile still exists after stop")
            result.update(cleanup="OWNED_POSTMASTER_STOPPED_AND_PIDFILE_ABSENT",
                          partialClusterRequiresOwnerAction=False)
    except Exception:
        result["cleanup"] = "REFUSED_OR_FAILED_REQUIRES_OWNER_ACTION"
    (root / "initialization-failure-receipt.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--pg-bin", type=Path, required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--task-id", required=True)
    parser.add_argument("--owner-thread-id", required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    if root.exists() or not 1024 <= args.port <= 65535:
        parser.error("root must not exist and port must be a local high port")
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", args.port))
    spec = importlib.util.spec_from_file_location("restore_v2", Path(__file__).with_name("restore-factory-export-v2.py"))
    restore = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(restore)
    root.mkdir(parents=True, exist_ok=False)
    protect_private_directory(root)
    data = root / "data"
    role = "factory_restore_owner"
    password = secrets.token_urlsafe(48)
    init_password = root / "init-password.private"
    init_password.write_text(password + "\n", encoding="utf-8")
    try:
        restore.run_tool(args.pg_bin, "initdb", ["-D", str(data), "-U", role,
            "--auth-local=scram-sha-256", "--auth-host=scram-sha-256", "--pwfile", str(init_password),
            "--encoding=UTF8", "--no-locale"])
    finally:
        init_password.unlink()
    passfile = root / "pgpass.private"
    passfile.write_text(f"127.0.0.1:{args.port}:*:{role}:{password}\n", encoding="utf-8")
    passfile.chmod(0o600)
    os.environ["PGPASSFILE"] = str(passfile)
    with (data / "postgresql.conf").open("a", encoding="utf-8") as config:
        config.write(f"\nlisten_addresses='127.0.0.1'\nport={args.port}\n"
                     "archive_mode=off\nmax_connections=20\nmax_locks_per_transaction=1024\n")
    startup_state = {"complete": False}
    atexit.register(stop_failed_startup, root, data, args.pg_bin, int(time.time()) - 1, startup_state, restore)
    # Windows postgres may inherit a PIPE handle from pg_ctl after pg_ctl has
    # exited. File handles avoid communicate() waiting for the running server.
    with (root / "pg-ctl-start.log").open("w", encoding="utf-8") as output:
        subprocess.run([str(args.pg_bin / ("pg_ctl.exe" if os.name == "nt" else "pg_ctl")),
            "-D", str(data), "-l", str(root / "postgres.log"), "-w", "start"],
            stdout=output, stderr=output, stdin=subprocess.DEVNULL, check=True, timeout=90)
    connection = psycopg2.connect(host="127.0.0.1", port=args.port, user=role, dbname="postgres")
    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT system_identifier::text,extract(epoch FROM pg_postmaster_start_time())::text "
                           "FROM pg_control_system()")
            system_id, sql_start = cursor.fetchone()
        pid_lines = (data / "postmaster.pid").read_text().splitlines()
        marker = {"format": "cretas-owned-restore-pg/1", "purpose": "factory-export-restore",
                  "nonce": secrets.token_hex(32), "taskId": args.task_id,
                  "ownerThreadId": args.owner_thread_id, "systemIdentifier": system_id,
                  "dataDirectory": str(data.resolve()), "authMethod": "scram-sha-256"}
        (data / ".cretas-restore-owner.json").write_text(json.dumps(marker, indent=2) + "\n", encoding="utf-8")
        receipt = dict(marker, port=args.port, databaseUser=role, pid=int(pid_lines[0]),
                       pidStartEpoch=pid_lines[2], sqlStartEpoch=sql_start,
                       processIdentity=restore.process_identity(int(pid_lines[0])))
        receipt_path = root / "ownership-receipt.json"
        receipt_path.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
        restore.verify_owned_connection(connection, argparse.Namespace(ownership_receipt=receipt_path,
            target_system_id=system_id, target_port=args.port, target_user=role, pg_bin=args.pg_bin))
        print(json.dumps({"status": "OWNED_CLUSTER_CREATED_AND_VERIFIED", "receipt": str(receipt_path),
                          "port": args.port, "systemIdentifier": system_id}))
        startup_state["complete"] = True
    finally:
        connection.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
