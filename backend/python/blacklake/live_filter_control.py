"""Negative time-filter control, without local business writes."""
import json
import os
from pathlib import Path

import redis

from .client import BlacklakeConnector
from .contracts import Settings, load_contract
from .coordination import RedisCoordination
from .local_environment import apply_source_binding, cache_url
from .secrets import save_local_secret
from .bootstrap import shape


def main():
    os.environ.setdefault("BLACKLAKE_TENANT_ID", "BLACKLAKE_POC_TEST")
    apply_source_binding()
    settings = Settings.from_env()
    contract = load_contract(str(Path(__file__).parent / "verified_contract.json"))
    cache = redis.Redis.from_url(cache_url())
    coordination = RedisCoordination(cache, settings.account_key)
    client = BlacklakeConnector(settings, contract, coordination)
    results = {}
    try:
        with coordination.lease():
            for resource in ("project", "output"):
                response = client.query(resource, {"updatedAtGte": "2000-01-01 00:00:00",
                                                   "updatedAtLte": "2000-01-02 00:00:00",
                                                   "page": {"pageNum": 1, "pageSize": 100}})
                total = response.get("page", {}).get("total")
                results[resource] = {"records": total if type(total) is int else None,
                                     "expected": 0, "null_items": response.get("data") is None,
                                     "response_shape": shape(response)}
        passed = all(value["records"] == 0 for value in results.values())
        report = {"success": passed, "negative_time_filter_control": results}
        save_local_secret(Path(os.environ["LOCALAPPDATA"]) / "Cretas/Blacklake/filter-control.dpapi", report)
        print(json.dumps(report))
        return 0 if passed else 1
    finally:
        client.close()
        cache.close()


if __name__ == "__main__":
    raise SystemExit(main())
