"""Opt-in interval worker; one verified resource/filter scope per process."""
import argparse
import json
import os
import time
from datetime import datetime, timedelta, timezone

from .client import BlacklakeConnector
from .contracts import BlacklakeError, Settings, load_contract
from .coordination import RedisCoordination
from .storage import PostgresStore
from .sync import Synchronizer, timestamp


def main():
    parser = argparse.ArgumentParser(description="Opt-in Blacklake incremental test worker")
    parser.add_argument("--contract", required=True)
    parser.add_argument("--resource", required=True)
    parser.add_argument("--start", required=True, help="Explicit initial UTC/offset timestamp")
    parser.add_argument("--interval", type=int, default=600)
    args = parser.parse_args()
    if not 300 <= args.interval <= 900:
        raise BlacklakeError("INTERVAL_MUST_BE_5_TO_15_MINUTES")
    if os.getenv("BLACKLAKE_ALLOW_RAW_STORAGE") != "true":
        raise BlacklakeError("RAW_STORAGE_AUTHORIZATION_REQUIRED")
    contract, settings = load_contract(args.contract), Settings.from_env()
    spec = contract["resources"][args.resource]
    if spec.get("updated_only_verified") is not True or spec.get("window_basis") != "updated":
        raise BlacklakeError("UPDATED_ONLY_NOT_VERIFIED")
    # Parent-constrained task/output queries must not share a global resource cursor.
    if spec.get("base_body") or args.resource == "stock_order":
        raise BlacklakeError("FILTERED_RESOURCE_REQUIRES_MANUAL_WINDOW_SYNC")
    import psycopg2
    import redis
    connection = psycopg2.connect(os.environ["BLACKLAKE_DATABASE_URL"])
    cache = redis.Redis.from_url(os.environ["BLACKLAKE_REDIS_URL"])
    coordination = RedisCoordination(cache, settings.account_key)
    client = BlacklakeConnector(settings, contract, coordination)
    store = PostgresStore(connection, settings)
    sync, first_start = Synchronizer(client, store, contract), timestamp(args.start)
    last_reconciliation = None
    try:
        while True:
            try:
                with coordination.lease():
                    cursor = store.cursor(args.resource)
                    start = cursor[0] if cursor else first_start
                    now = datetime.now(timezone.utc) - timedelta(minutes=2)
                    end = min(now, start + timedelta(days=6))
                    if end > start:
                        print(json.dumps(sync.sync_window(args.resource, start, end, incremental=True)), flush=True)
                    if last_reconciliation != now.date() and cursor:
                        print(json.dumps(sync.reconcile_window(args.resource, now - timedelta(days=1), now)), flush=True)
                        last_reconciliation = now.date()
            except BlacklakeError as exc:
                print(json.dumps({"success": False, "data": None, "message": str(exc)}), flush=True)
            time.sleep(args.interval)
    finally:
        client.close()
        cache.close()
        connection.close()


if __name__ == "__main__":
    main()
