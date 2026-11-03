"""Negative authorization and update-filter controls, through the read-only gate."""
import json
import os

import redis

from .client import BlacklakeConnector
from .contracts import BlacklakeError, ENDPOINTS, Settings, load_contract
from .coordination import RedisCoordination
from .local_environment import cache_url


def main():
    os.environ.setdefault("BLACKLAKE_TENANT_ID", "UNBOUND_DIAGNOSTIC")
    os.environ.setdefault("BLACKLAKE_SOURCE_FACTORY_ID", "UNBOUND_DIAGNOSTIC")
    settings = Settings.from_env()
    cache = redis.Redis.from_url(cache_url())
    coordination = RedisCoordination(cache, settings.account_key)
    client = BlacklakeConnector(settings, {"code_path": "code"}, coordination)
    captured = {}

    def inspect(response):
        response.read()
        captured["http_status"] = response.status_code
        try:
            value = response.json()
        except ValueError:
            return
        if isinstance(value, dict):
            captured["fields"] = {k: type(v).__name__ for k, v in value.items() if k in ("code", "statusCode", "message", "msg", "data")}
            for key in ("code", "statusCode"):
                code = value.get(key)
                if code is not None and str(code).isdecimal() and len(str(code)) <= 8:
                    captured[key] = code

    client.http.event_hooks["response"] = [inspect]
    try:
        with coordination.lease():
            try:
                client._send(ENDPOINTS["project"], {
                    "updatedAtGte": "2000-01-01 00:00:00", "updatedAtLte": "2000-01-02 00:00:00",
                    "page": {"pageNum": 1, "pageSize": 1}}, "BLACKLAKE_NEGATIVE_AUTH_TEST")
            except BlacklakeError as exc:
                captured["connector_error"] = str(exc)
        print(json.dumps(captured))
    finally:
        client.close()
        cache.close()


if __name__ == "__main__":
    main()
