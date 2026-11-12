"""One-page discovery for the full extraction, no business values in logs."""
import json
import argparse
import os
from pathlib import Path

import psycopg2
import redis

from .bootstrap import shape
from .client import BlacklakeConnector
from .contracts import BlacklakeError, Settings, load_contract
from .coordination import RedisCoordination
from .local_environment import apply_source_binding, cache_url
from .secrets import load_local_secret, save_local_secret
from .storage import PostgresStore


def context():
    directory = Path(os.environ["LOCALAPPDATA"]) / "Cretas/Blacklake"
    os.environ.setdefault("BLACKLAKE_SECRET_FILE", str(directory / "account.dpapi"))
    os.environ["BLACKLAKE_TENANT_ID"] = "BLACKLAKE_POC_TEST"
    apply_source_binding()
    settings = Settings.from_env()
    db = load_local_secret(directory / "test-database.dpapi")
    if db.get("initialization_complete") is not True:
        raise BlacklakeError("TEST_DATABASE_READINESS_NOT_VERIFIED")
    connection = psycopg2.connect(db["runtime_dsn"])
    store = PostgresStore(connection, settings)
    cache = redis.Redis.from_url(cache_url())
    coordination = RedisCoordination(cache, settings.account_key)
    contract = load_contract(str(Path(__file__).parent / "verified_contract.json"))
    client = BlacklakeConnector(settings, contract, coordination)
    return directory, client, store, cache


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--extra", action="store_true")
    args = parser.parse_args()
    directory, client, store, cache = context()
    first = store.records("project")[0][0].split(":", 1)[1]
    first_product = store.records("project")[0][1]["product_id"]
    cases = [(resource, {"page": {"pageNum": 1, "pageSize": 1}}) for resource in
             ("product", "process", "routing", "warehouse", "unit", "bom")]
    cases += [("project", {"status": status, "page": {"pageNum": 1, "pageSize": 1}})
              for status in ("0", "10", "20", "30")]
    cases += [("stock_order", {"orderType": direction, "page": {"pageNum": 1, "pageSize": 1}})
              for direction in (10, 20)]
    cases += [("project_expand", {"projectIds": [int(first)]}), ("materials", {"projectId": int(first)})]
    if args.extra:
        product = load_local_secret(directory / "full-probe-product.dpapi")["data"]["data"][0]
        warehouse = load_local_secret(directory / "full-probe-warehouse.dpapi")["data"]["data"][0]["simpleVO"]
        active = load_local_secret(directory / "full-probe-project-10.dpapi")["data"][0]
        cases = [("bom", {"lastProductCode": first_product, "page": {"pageNum": 1, "pageSize": 1}}),
                 ("stock", {"stockInfoQryOpenApiCOList": [{"productId": product["id"], "warehouseId": warehouse["id"]}]}),
                 ("project_expand", {"projectIds": [active["id"]]})]
    results = []
    try:
        with client.coordination.lease():
            for resource, body in cases:
                try:
                    response = client.query(resource, body)
                    label = resource + ("-" + str(body.get("status", body.get("orderType", ""))) if resource in ("project", "stock_order") else "")
                    save_local_secret(directory / ("full-probe-" + label + ".dpapi"), response)
                    total = response.get("page", {}).get("total")
                    if isinstance(response.get("data"), dict):
                        total = response["data"].get("total", total)
                    report = {"resource": label, "success": True, "total": total, "shape": shape(response)}
                except BlacklakeError as exc:
                    report = {"resource": resource, "success": False, "error": str(exc)}
                    if client.last_business_error:
                        save_local_secret(directory / ("full-probe-error-" + resource + ".dpapi"), client.last_business_error)
                results.append(report)
                print(json.dumps(report), flush=True)
        save_local_secret(directory / ("full-probes-extra.dpapi" if args.extra else "full-probes.dpapi"), results)
        return 0 if all(row["success"] for row in results) else 1
    finally:
        client.close()
        store.connection.close()
        cache.close()


if __name__ == "__main__":
    raise SystemExit(main())
