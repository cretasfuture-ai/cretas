"""Read-only, encrypted-evidence PoC probe. No business values reach stdout."""
import argparse
import base64
import json
import logging
import os
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

from .bootstrap import shape
from .client import BlacklakeConnector
from .contracts import BlacklakeError, Settings
from .coordination import RedisCoordination
from .secrets import load_local_secret, save_local_secret
from .local_environment import cache_url, apply_source_binding


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--resource", choices=["project", "task", "output", "stock_order"], default="project")
    parser.add_argument("--updated-only", action="store_true")
    parser.add_argument("--plan-start-only", action="store_true")
    parser.add_argument("--page", type=int, default=1)
    parser.add_argument("--size", type=int, default=1)
    parser.add_argument("--order-type", type=int, choices=[10, 20])
    parser.add_argument("--parent-project", action="store_true", help="Constrain task/output by the sampled project ID")
    args = parser.parse_args()
    if not 1 <= args.size <= 100 or args.page < 1:
        raise BlacklakeError("INVALID_PAGINATION")
    for name in ("httpx", "httpcore", "redis"):
        logging.getLogger(name).disabled = True
    import redis
    directory = Path(os.environ["LOCALAPPDATA"]) / "Cretas/Blacklake"
    login = load_local_secret(directory / "login-response.dpapi")
    if login.get("statusCode") != 200 or not isinstance(login.get("data"), str):
        raise BlacklakeError("SUCCESSFUL_LOGIN_EVIDENCE_REQUIRED")
    encoded = login["data"].split(".")[1]
    claims = json.loads(base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4)))
    # Local test destination identity; never equated with an existing ERP customer.
    os.environ.setdefault("BLACKLAKE_TENANT_ID", "BLACKLAKE_POC_TEST")
    apply_source_binding()
    settings = Settings.from_env()
    if settings.source_factory_id != str(claims["orgId"]):
        raise BlacklakeError("EXPECTED_SOURCE_FACTORY_MISMATCH")
    cache = redis.Redis.from_url(cache_url())
    coordination = RedisCoordination(cache, settings.account_key)
    contract = {"code_path": "code", "login_code_path": "statusCode", "login_success_code": 200,
                "token_path": "data", "login_factory_claim": "orgId"}
    client = BlacklakeConnector(settings, contract, coordination)
    end = datetime.now(ZoneInfo("Asia/Shanghai")).replace(hour=0, minute=0, second=0, microsecond=0)
    start = end - timedelta(days=7)
    body = {"page": {"pageNum": args.page, "pageSize": args.size}}
    if args.resource == "project":
        keys = ("updatedAtGte", "updatedAtLte") if args.updated_only else ("planStartTime", "planEndTime")
        if args.plan_start_only:
            keys = ("planStartTimeGte", "planStartTimeLte")
    elif args.resource in ("task", "output"):
        keys = ("updatedAtGte", "updatedAtLte") if args.updated_only else ("createdAtGte", "createdAtLte")
    else:
        if args.order_type is None:
            raise BlacklakeError("STOCK_ORDER_TYPE_REQUIRED")
        body["orderType"] = args.order_type
        keys = ("updatedAtStart", "updatedAtEnd") if args.updated_only else ("bizAtStart", "bizAtEnd")
    body[keys[0]], body[keys[1]] = (value.strftime("%Y-%m-%d %H:%M:%S") for value in (start, end))
    if args.parent_project:
        sample = load_local_secret(directory / "project-sample.dpapi")
        rows = sample.get("data")
        if not isinstance(rows, list) or not rows:
            raise BlacklakeError("SAMPLED_PROJECT_REQUIRED")
        body["projectIds"] = [rows[0]["id"]]
    result = None
    try:
        with coordination.lease():
            if not coordination.get_token():
                coordination.set_token(login["data"], settings.source_factory_id)
            result = client.query(args.resource, body)
            label = args.resource + ("-updated" if args.updated_only else "-sample")
            if args.resource == "stock_order":
                label += "-" + str(args.order_type)
            save_local_secret(directory / (label + ".dpapi"), result)
            save_local_secret(directory / (label + "-page-" + str(args.page) + "-size-" + str(args.size) + ".dpapi"), result)
            page = result.get("page", {})
            data = result.get("data")
            print(json.dumps({"success": True, "data": {
                "resource": args.resource, "updated_only": args.updated_only,
                "business_code": result.get("code"), "response_shape": shape(result),
                "page": {k: v for k, v in page.items() if k in ("pageNum", "pageSize", "total") and type(v) is int},
                "returned_rows": len(data) if isinstance(data, list) else None,
                "window_days": 7}, "message": "read-only; evidence encrypted locally"}))
            return 0
    except Exception as exc:
        print(json.dumps({"success": False, "data": None,
                          "message": str(exc) if isinstance(exc, BlacklakeError) else "PROBE_ERROR"}))
        return 1
    finally:
        client.close()
        cache.close()


if __name__ == "__main__":
    raise SystemExit(main())
