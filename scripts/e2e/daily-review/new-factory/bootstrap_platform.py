"""Create the P14 factory and its first factory account through platform APIs.

This is preparation only. It never calls a factory business endpoint and never
touches SQL. Credentials are private JSON and are not copied to the receipt.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from urllib.parse import urlsplit

import requests


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:10064")
    parser.add_argument("--credentials", type=Path, required=True)
    parser.add_argument("--internal-credentials", type=Path,
                        help="Optional private runtime JSON containing internal")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--name", required=True)
    parser.add_argument("--industry-code", default="FOOD")
    parser.add_argument("--region-code", default="3101")
    parser.add_argument("--factory-credentials", type=Path, required=True,
                        help="Private JSON containing the generated factory account password")
    args = parser.parse_args()
    target = urlsplit(args.base_url)
    if (target.scheme, target.hostname, target.port, target.path, target.username,
            target.password, target.query, target.fragment) != (
                "http", "127.0.0.1", 10064, "", None, None, "", ""):
        raise ValueError("P14 platform bootstrap only permits http://127.0.0.1:10064")
    private = json.loads(args.credentials.read_text(encoding="utf-8"))
    username = private.get("username")
    password = private.get("password")
    internal_key = private.get("internalKey")
    if not internal_key and args.internal_credentials:
        internal_private = json.loads(args.internal_credentials.read_text(encoding="utf-8"))
        internal_key = internal_private.get("internal") or internal_private.get("internalKey")
    if not username or not password:
        raise ValueError("Private credential JSON requires username and password")
    factory_private = json.loads(args.factory_credentials.read_text(encoding="utf-8"))
    factory_password = factory_private.get("password")
    if not factory_password:
        raise ValueError("Factory credential JSON requires password")
    if not internal_key:
        raise ValueError("Private internal credentials are required to provision the first factory account")
    http = requests.Session()
    login = http.post(args.base_url + "/api/mobile/auth/unified-login",
                      json={"username": username, "password": password, "clientType": "web"}, timeout=30)
    login.raise_for_status()
    body = login.json()
    if not body.get("success"):
        raise RuntimeError(body.get("message"))
    data = body["data"]
    token = data.get("token") or data.get("accessToken")
    if not token or data.get("role") not in {"platform_admin", "super_admin"}:
        raise ValueError("Platform administrator login proof missing")
    http.headers["Authorization"] = "Bearer " + token
    args.out.mkdir(parents=True, exist_ok=True)
    receipt_path = args.out / "platform-bootstrap.json"
    if receipt_path.exists():
        receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
        if receipt.get("factoryName") != args.name or not receipt.get("factoryId"):
            raise ValueError("Existing P14 bootstrap receipt does not match requested factory; inspect manually")
        factory_id = receipt["factoryId"]
    else:
        created = http.post(args.base_url + "/api/platform/factories", json={
            "name": args.name, "industryCode": args.industry_code, "regionCode": args.region_code,
            "contactName": "P14验收联系人", "contactPhone": "13800009901",
            "subscriptionPlan": "BASIC", "aiWeeklyQuota": 50, "isActive": True}, timeout=60)
        created.raise_for_status()
        result = created.json()
        if not result.get("success"):
            raise RuntimeError(result.get("message"))
        factory = result["data"]
        factory_id = factory.get("id")
        if not factory_id:
            raise ValueError("Platform factory creation did not return id")
    account = factory_id.lower() + "_admin"
    receipt = {"factoryId": factory_id, "factoryName": args.name, "username": account,
               "accountStatus": "factory-created", "businessWrites": False,
               "endpoint": args.base_url + "/api/platform/factories"}
    receipt_path.write_text(json.dumps(receipt, indent=2), encoding="utf-8")
    provision = http.post(args.base_url + "/api/internal/field-visibility/" + factory_id + "/link-survey-company",
                          headers={"X-Internal-Key": internal_key},
                          json={"companyId": "p14-" + factory_id}, timeout=60)
    provision.raise_for_status()
    if not provision.json().get("success"):
        raise RuntimeError(provision.json().get("message"))
    reset = http.post(args.base_url + "/api/mobile/auth/reset-password",
                      data={"factoryId": factory_id, "username": account,
                              "newPassword": factory_password}, timeout=30)
    if not reset.json().get("success"):
        raise RuntimeError("Provisioned default users but account password reset failed")
    receipt["accountStatus"] = "provisioned-and-reset"
    receipt_path.write_text(json.dumps(receipt, indent=2), encoding="utf-8")
    print(json.dumps(receipt, ensure_ascii=False))


if __name__ == "__main__":
    main()
