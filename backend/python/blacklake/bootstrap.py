"""One login to inspect the real wire shape, without emitting response values."""
import hashlib
import json
import logging
import os
import re

from .client import BlacklakeConnector
from .contracts import BlacklakeError, LOGIN, Settings
from .coordination import RedisCoordination
from .local_environment import cache_url


def shape(value, prefix="", depth=0):
    if depth > 7:
        return {prefix: "depth_limit"}
    if isinstance(value, dict):
        result = {}
        for key, child in value.items():
            if key in ("reference", "customFieldValues", "customFieldMetadata"):
                result[prefix + "." + key if prefix else key] = "metadata_omitted"
                continue
            # Dynamic keys may contain personal information; only conventional keys emitted.
            key = key if re.fullmatch(r"[A-Za-z_][A-Za-z_0-9]{0,63}", key) else "REDACTED_KEY"
            result.update(shape(child, prefix + "." + key if prefix else key, depth + 1))
        return result
    if isinstance(value, list):
        return shape(value[0], prefix + "[]", depth + 1) if value else {prefix: "empty_array"}
    return {prefix: type(value).__name__}


def main():
    for name in ("httpx", "httpcore", "redis"):
        logging.getLogger(name).disabled = True
    import redis
    cache = client = None
    try:
        # These are unbound local diagnostic labels, never used for storage or BI.
        os.environ.setdefault("BLACKLAKE_TENANT_ID", "UNBOUND_DIAGNOSTIC")
        os.environ.setdefault("BLACKLAKE_SOURCE_FACTORY_ID", "UNBOUND_DIAGNOSTIC")
        settings = Settings.from_env()
        cache = redis.Redis.from_url(cache_url())
        coordination = RedisCoordination(cache, settings.account_key)
        client = BlacklakeConnector(settings, {}, coordination)
        with coordination.lease():
            response = client._send(LOGIN, {"type": 0, "phone": settings.phone,
                                            "password": hashlib.sha3_224(settings.password.encode()).hexdigest()},
                                    login_diagnostic=True)
            result = {"success": response is not None, "data": {"shape": shape(response)},
                      "message": "schema only; login success and factory scope still require validation"}
            # Status codes can be shown; no arbitrary upstream strings are printed.
            if isinstance(response, dict) and re.fullmatch(r"[0-9]{1,8}", str(response.get("code", ""))):
                result["data"]["business_code"] = response["code"]
            # Preserve login response encrypted for local inspection, not stdout/Git.
            if response is not None:
                from .secrets import save_local_secret
                save_local_secret(os.environ["BLACKLAKE_LOGIN_EVIDENCE_FILE"], response)
            print(json.dumps(result))
            return 0 if response is not None else 1
    except Exception as exc:
        print(json.dumps({"success": False, "data": None,
                          "message": str(exc) if isinstance(exc, BlacklakeError) else "BOOTSTRAP_ERROR"}))
        return 1
    finally:
        if client:
            client.close()
        if cache:
            cache.close()


if __name__ == "__main__":
    raise SystemExit(main())
