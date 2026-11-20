"""Explicit operator CLI, with no automatic credentials discovery or network on import."""
import argparse
import json
import logging
import os
from pathlib import Path

from .client import BlacklakeConnector
from .contracts import BlacklakeError, Settings, load_contract
from .coordination import RedisCoordination
from .metrics import task_metrics
from .storage import PostgresStore
from .sync import Synchronizer, timestamp


def main():
    parser = argparse.ArgumentParser(description="Blacklake read-only PoC")
    parser.add_argument("command", choices=["probe", "sync", "metrics", "reconcile"])
    parser.add_argument("--contract", required=True)
    parser.add_argument("--resource", default="project")
    parser.add_argument("--request-file", help="Local JSON, never credentials")
    parser.add_argument("--start")
    parser.add_argument("--end")
    parser.add_argument("--incremental", action="store_true")
    args = parser.parse_args()
    # Prevent inherited DEBUG loggers from exposing X-AUTH or login bodies.
    for name in ("httpx", "httpcore", "redis", "psycopg2"):
        logging.getLogger(name).disabled = True
    client, connection, redis = None, None, None
    try:
        contract = load_contract(args.contract)
        settings = Settings.from_env()
        body = json.loads(Path(args.request_file).read_text(encoding="utf-8")) if args.request_file else {}
        if args.command == "sync" and os.getenv("BLACKLAKE_ALLOW_RAW_STORAGE") != "true":
            raise BlacklakeError("RAW_STORAGE_AUTHORIZATION_REQUIRED")
        if args.command == "metrics" and args.resource != "task":
            raise BlacklakeError("FIRST_METRICS_SUPPORT_TASK_GRAIN_ONLY")
        if args.command != "probe":
            import psycopg2
            connection = psycopg2.connect(os.environ["BLACKLAKE_DATABASE_URL"])
            store = PostgresStore(connection, settings)
        if args.command == "metrics":
            # External IDs and payroll are intentionally omitted from stdout.
            result = {"success": True, "data": [
                {k: v for k, v in task_metrics(row).items() if k != "labor_cost"}
                for _, row in store.records("task")], "message": "single-task metrics"}
        else:
            import redis as redis_module
            redis = redis_module.Redis.from_url(os.environ["BLACKLAKE_REDIS_URL"])
            coordination = RedisCoordination(redis, settings.account_key)
            client = BlacklakeConnector(settings, contract, coordination)
            with coordination.lease():
                if args.command == "probe":
                    # Output schema types only; no values, identifiers, salary or raw bodies.
                    pages, count = 0, 0
                    for rows in client.pages(args.resource, body):
                        pages += 1
                        count += len(rows)
                    result = {"success": True, "data": {"pages": pages, "rows": count},
                              "message": "pagination checked; source identity not yet proven"}
                elif args.command == "reconcile":
                    result = Synchronizer(client, store, contract).reconcile_window(
                        args.resource, timestamp(args.start), timestamp(args.end), extra=body)
                else:
                    result = Synchronizer(client, store, contract).sync_window(
                        args.resource, timestamp(args.start), timestamp(args.end),
                        incremental=args.incremental, extra=body)
        print(json.dumps(result, ensure_ascii=False))
        return 0
    except Exception as exc:
        code = str(exc) if isinstance(exc, BlacklakeError) else "CONFIGURATION_OR_RUNTIME_ERROR"
        print(json.dumps({"success": False, "data": None, "message": code}))
        return 1
    finally:
        if client:
            client.close()
        if connection:
            connection.close()
        if redis:
            redis.close()


if __name__ == "__main__":
    raise SystemExit(main())
