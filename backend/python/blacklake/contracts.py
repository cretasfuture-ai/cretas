"""Explicit wire contracts: unknown response paths must never be guessed."""
import hashlib
import json
import os
from dataclasses import dataclass, field
from typing import Any, Dict


class BlacklakeError(RuntimeError):
    """Only constant, non-sensitive messages may cross the connector boundary."""


ENDPOINTS = {
    "project": "/api/dytin/external/project/queryList3",
    "project_expand": "/api/dytin/external/project/queryList/expand",
    "task": "/api/dytin/external/task/queryList2",
    "output": "/api/dytin/external/output/queryList3",
    "product": "/api/dytin/external/product/queryList2",
    "process": "/api/dytin/external/process/queryList",
    "routing": "/api/dytin/external/processRouting/queryList2",
    "materials": "/api/dytin/external/projectMaterials/queryList2",
    "stock_order": "/api/dytin/external/stock/order/queryList3",
    "stock": "/api/dytin/external/stock/queryStockInfoDetail",
    "warehouse": "/api/dytin/external/warehouse/queryList",
    "unit": "/api/dytin/external/unit/queryList2",
    "bom": "/api/dytin/external/materials/queryList",
    # Read-only supplements (live_extras.py); probed 2026-11-12, one page each.
    "project_cost": "/api/dytin/external/projectCost/queryList",
    "project_cost_detail": "/api/dytin/external/projectCost/detail/queryList",
    "inspection_item": "/api/dytin/external/inspectionItem/queryList",
    "inspection_spec": "/api/dytin/external/inspectionSpecifications/queryList",
    "inspection_order": "/api/dytin/external/inspection/order/queryList",
    "inspection_result": "/api/dytin/external/inspection/order/result/detail/queryList",
    "collaborative_task": "/api/dytin/external/collaborativeTask/queryList",
    "sale_order": "/api/dytin/external/saleOrder/queryList2",
    "purchase_order": "/api/dytin/external/purchaseOrder/queryList2",
    "purchase_requirement": "/api/dytin/external/purchaseRequirement/queryList",
    "customer": "/api/dytin/external/customer/queryList",
    "vendor": "/api/dytin/external/vendor/queryList",
    "vendor_price": "/api/dytin/external/purchase/vendor/product/price/queryList",
    "customer_price": "/api/dytin/external/sale/customer/product/price/queryList",
    "receivable": "/api/dytin/external/receivable/queryList",
    "receipt": "/api/dytin/external/receipt/queryList",
    "payable": "/api/dytin/external/payable/queryList",
    "payment": "/api/dytin/external/payment/queryList",
    "customer_ledger": "/api/dytin/external/querySaleWorkbenchCustomerLedger",
    "vendor_ledger": "/api/dytin/external/queryPurchaseWorkbenchVendorLedger",
    "invoicing": "/api/dytin/external/queryInvoicingList",
    "outsource_task": "/api/dytin/external/outsource/task/queryList",
    "outsource_deliver_return": "/api/dytin/external/outsource/deliverReturnMaterial/queryList",
    "outsource_receive_return": "/api/dytin/external/outsource/receiveReturn/queryList",
    "equipment": "/api/dytin/external/equipment/queryList",
    "owner_project": "/api/dytin/external/owner/project/queryList",
    "operation_log": "/api/mmtin/external/operationLog/list",
}
LOGIN = "/api/user/v1/users/_login"


def at(value: Any, path: str) -> Any:
    """Dot-separated dictionary keys only; no expression evaluation."""
    for part in path.split("."):
        if not isinstance(value, dict) or part not in value:
            raise BlacklakeError("CONTRACT_FIELD_MISSING")
        value = value[part]
    return value


def put(value: dict, path: str, item: Any):
    parts = path.split(".")
    for part in parts[:-1]:
        child = value.setdefault(part, {})
        if not isinstance(child, dict):
            raise BlacklakeError("CONTRACT_PARAMETER_COLLISION")
        value = child
    value[parts[-1]] = item


@dataclass(repr=False)
class Settings:
    tenant_id: str
    source_factory_id: str
    phone: str = field(repr=False)
    password: str = field(repr=False)
    base_url: str = "https://liteweb.blacklake.cn"
    # Official OpenAPI login type=1: factory code + username + password (no SMS step).
    factory_code: str = field(default="", repr=False)
    username: str = field(default="", repr=False)

    def __post_init__(self):
        if self.base_url != "https://liteweb.blacklake.cn":
            raise BlacklakeError("UNAPPROVED_ORIGIN")
        identity = self.phone or (self.factory_code and self.username)
        if not all((self.tenant_id, self.source_factory_id, identity, self.password)):
            raise BlacklakeError("MISSING_CONFIGURATION")

    @property
    def login_body_identity(self):
        if self.username:
            return {"type": 1, "code": self.factory_code, "username": self.username}
        return {"type": 0, "phone": self.phone}

    @property
    def account_key(self):
        # Shared by every worker/tenant using the same upstream account.
        who = self.phone if not self.username else self.factory_code + "/" + self.username
        return "blacklake:" + hashlib.sha256(who.encode()).hexdigest()

    @classmethod
    def from_env(cls):
        phone, password = os.environ.get("BLACKLAKE_PHONE", ""), os.environ.get("BLACKLAKE_PASSWORD", "")
        code = username = ""
        if os.environ.get("BLACKLAKE_SECRET_FILE"):
            from .secrets import load_local_secret
            secret = load_local_secret(os.environ["BLACKLAKE_SECRET_FILE"])
            phone, password = secret.get("phone", ""), secret["password"]
            code, username = secret.get("code", ""), secret.get("username", "")
        return cls(os.environ.get("BLACKLAKE_TENANT_ID", ""),
                   os.environ.get("BLACKLAKE_SOURCE_FACTORY_ID", ""), phone, password,
                   base_url=os.environ.get("BLACKLAKE_BASE_URL", "https://liteweb.blacklake.cn"),
                   factory_code=code, username=username)


def load_contract(path: str) -> Dict:
    with open(path, encoding="utf-8") as handle:
        contract = json.load(handle)
    if contract.get("wire_verified") is not True:
        raise BlacklakeError("WIRE_CONTRACT_NOT_VERIFIED")
    for key in ("code_path", "token_path", "resources"):
        if not contract.get(key):
            raise BlacklakeError("INCOMPLETE_CONTRACT")
    return contract
