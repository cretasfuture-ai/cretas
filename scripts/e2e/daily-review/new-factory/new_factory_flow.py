"""P14 new-factory business flow.

This runner reuses the P0 business methods where their API contract is factory
relative, while replacing P0's fixed identity, warehouse and customer values.
It deliberately has no metric configuration/write step: metrics must come from
the platform factory-create transaction under test.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from datetime import date
from pathlib import Path
from urllib.parse import urlsplit

import requests

P0 = Path(__file__).resolve().parents[1] / "p0_business_flow.py"
spec = importlib.util.spec_from_file_location("p0_business_flow", P0)
if spec is None or spec.loader is None:
    raise RuntimeError(f"Cannot load P0 helper: {P0}")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class NewFactoryFlow(module.BusinessFlow):
    """P0 helper with explicit new-factory and loopback guards."""

    def __init__(self, url: str, credentials: Path, out: Path, factory_id: str, username: str, receipt_file: Path):
        target = urlsplit(url)
        if (target.scheme, target.hostname, target.port, target.path, target.username,
                target.password, target.query, target.fragment) != (
                    "http", "127.0.0.1", 10064, "", None, None, "", ""):
            raise ValueError("P14 only permits owned loopback Java runtime http://127.0.0.1:10064")
        if not factory_id or factory_id in {"VISION-ENTRY-A", "F006"} or "/" in factory_id:
            raise ValueError("P14 requires the newly-created factoryId, never a fixture factory")
        self.run_key = "p14"
        self.factory = factory_id
        self.username = username
        self.receipt_file = receipt_file.resolve()
        self.out = out.resolve()
        self.out.mkdir(parents=True, exist_ok=True)
        self.state_path = self.out / "business-state.json"
        self.state = (json.loads(self.state_path.read_text(encoding="utf-8"))
                      if self.state_path.exists() else {"factoryId": factory_id, "entities": {}, "events": []})
        if self.state.get("factoryId") != factory_id:
            raise ValueError("Saved acceptance belongs to another factory")
        account = json.loads(credentials.read_text(encoding="utf-8"))
        password = account.get("password")
        if not password:
            raise ValueError("Credential file must contain password; keep it outside tracked files")
        self.base = url.rstrip("/")
        self.http = requests.Session()
        response = self.http.post(self.base + "/api/mobile/auth/unified-login",
                                  json={"username": username, "password": password, "clientType": "web"},
                                  timeout=30)
        response.raise_for_status()
        body = response.json()
        if not body.get("success"):
            raise RuntimeError(body.get("message"))
        actor = body["data"]
        if actor.get("factoryId") != factory_id or actor.get("username") != username:
            raise ValueError("New factory user login proof missing or factory mismatch")
        if actor.get("role") not in {"factory_super_admin", "factory_admin"}:
            raise ValueError("New factory account is not an allowed factory administrator")
        self.actor_id = actor.get("userId")
        token = actor.get("token") or actor.get("accessToken")
        if not token:
            raise ValueError("Login response did not contain an access token")
        self.http.headers["Authorization"] = "Bearer " + token
        self.state["identity"] = {k: actor.get(k) for k in ("userId", "username", "factoryId", "role")}
        self.persist()

    def call(self, method: str, path: str, payload=None):
        if not path.startswith("/") or ".." in path or "://" in path:
            raise ValueError("Factory-relative business path required")
        response = self.http.request(method, self.base + "/api/mobile/" + self.factory + path,
                                     json=payload, timeout=60)
        try:
            body = response.json()
        except ValueError:
            body = {"success": False, "message": "Non-JSON response"}
        event = {"method": method, "path": path, "httpStatus": response.status_code,
                 "success": body.get("success"), "message": body.get("message")}
        self.state["events"].append(event)
        self.persist()
        if response.status_code >= 400 or not body.get("success"):
            raise RuntimeError(json.dumps(event, ensure_ascii=False))
        return body.get("data")

    def prepare_stock(self):
        """Create warehouses through the normal API and set the production default."""
        for key, code, name, kind in (
                ("logistics_warehouse", "P14-RAW", "P14验收原料仓", "LOGISTICS"),
                ("workshop", "P14-WORKSHOP", "P14验收生产仓", "WORKSHOP"),
                ("finished_warehouse", "WH-FG", "P14验收成品仓", "FINISHED")):
            self.create(key, "/factory/warehouses", {"code": code, "name": name, "type": kind})
        if "workshop_default" not in self.state["entities"]:
            self.state["entities"]["workshop_default"] = self.call(
                "PUT", "/factory/warehouse-defaults",
                {"purpose": "WORKSHOP_DEFAULT", "warehouseId": self.state["entities"]["workshop"]["id"]})
            self.persist()
        if "route_defaults" not in self.state["entities"]:
            self.state["entities"]["route_defaults"] = {}
            for purpose, warehouse in (
                    ("LOGISTICS_DEFAULT", "logistics_warehouse"),
                    ("PURCHASE_INBOUND_DEFAULT", "logistics_warehouse"),
                    ("SALES_OUTBOUND_DEFAULT", "finished_warehouse"),
                    ("PRODUCTION_RAW_DEFAULT", "logistics_warehouse")):
                self.state["entities"]["route_defaults"][purpose] = self.call(
                    "PUT", "/factory/warehouse-defaults",
                    {"purpose": purpose, "warehouseId": self.state["entities"][warehouse]["id"]})
            self.persist()
        supplier = self.state["entities"]["supplier"]
        packaging = self.state["entities"]["packaging"]
        self.create("pack_supplier_material", "/suppliers/" + supplier["id"] + "/materials", {
            "materialTypeId": packaging["id"], "defaultPurchasePrice": 0.5,
            "purchaseUnit": "个", "currency": "CNY", "active": True})
        pack_order = self.create("pack_po", "/purchase/orders", {
            "supplierId": supplier["id"], "orderDate": date.today().isoformat(),
            "expectedDeliveryDate": date.today().isoformat(), "remark": "P14正常包材收料",
            "items": [{"materialTypeId": packaging["id"], "quantity": 100, "unit": "个",
                       "quantityUnit": "个", "unitPrice": 0.5, "priceUnit": "个", "taxRate": 0}]})
        self.create("pack_po_submitted", "/purchase/orders/" + pack_order["id"] + "/submit", {})
        pack_order = self.call("GET", "/purchase/orders/" + pack_order["id"])
        pack_receipt = self.create("pack_receipt", "/warehouse/receiving/receipts", {
            "purchaseOrderId": pack_order["id"], "supplierId": supplier["id"],
            "receiveDate": date.today().isoformat(), "warehouseId": self.state["entities"]["logistics_warehouse"]["id"],
            "remark": "P14正常包材收料", "items": [{"purchaseOrderItemId": pack_order["items"][0]["id"],
            "materialTypeId": packaging["id"], "receivedQuantity": 100, "unit": "个",
            "priceUnit": "个", "unitPrice": 0.5}]})
        self.attach_receipt(pack_receipt, "pack_receipt_attachment")
        self.create("pack_receipt_confirmed", "/warehouse/receiving/receipts/" + pack_receipt["id"] + "/confirm", {})

    def receipt(self):
        """Receive the raw-material PO through the ordinary receiving API."""
        order = self.call("GET", "/purchase/orders/" + self.state["entities"]["purchase_order"]["id"])
        receipt = self.create("p14_raw_receipt", "/warehouse/receiving/receipts", {
            "purchaseOrderId": order["id"], "supplierId": self.state["entities"]["supplier"]["id"],
            "receiveDate": date.today().isoformat(),
            "warehouseId": self.state["entities"]["logistics_warehouse"]["id"],
            "remark": "P14新厂正常收料验收",
            "items": [{"purchaseOrderItemId": order["items"][0]["id"],
                       "materialTypeId": self.state["entities"]["raw"]["id"],
                       "receivedQuantity": 100, "unit": "kg", "priceUnit": "kg", "unitPrice": 10}]})
        self.attach_receipt(receipt, "p14_raw_receipt_attachment")
        self.create("p14_raw_receipt_confirmed", "/warehouse/receiving/receipts/" + receipt["id"] + "/confirm", {})
        self.state["entities"]["receipts"] = self.call("GET", "/warehouse/receiving/receipts/by-order/" + order["id"])
        transfer = self.create("production_transfer", "/transfers", {
            "transferType": "WAREHOUSE_TO_WAREHOUSE", "targetFactoryId": self.factory,
            "sourceWarehouseId": self.state["entities"]["logistics_warehouse"]["id"],
            "targetWarehouseId": self.state["entities"]["workshop"]["id"],
            "transferDate": date.today().isoformat(), "remark": "P14正常生产领料",
            "items": [{"itemType": "RAW_MATERIAL", "materialTypeId": self.state["entities"]["raw"]["id"], "quantity": 40, "unit": "kg"},
                      {"itemType": "RAW_MATERIAL", "materialTypeId": self.state["entities"]["packaging"]["id"], "quantity": 30, "unit": "个"}]})
        self.create("production_transfer_requested", "/transfers/" + transfer["id"] + "/request", {})
        self.create("production_transfer_confirmed", "/transfers/" + transfer["id"] + "/confirm", {})
        self.persist()

    def attach_receipt(self, receipt, state_key):
        if state_key in self.state["entities"]:
            return self.state["entities"][state_key]
        if not self.receipt_file.is_file():
            raise FileNotFoundError("Receipt attachment fixture is required: " + str(self.receipt_file))
        data = self.receipt_file.read_bytes()
        signed = self.call("POST", "/attachments/upload-url", {
            "fileName": self.receipt_file.name, "fileType": "image/png", "fileSize": len(data)})
        target = urlsplit(signed["uploadUrl"])
        if target.scheme != "http" or target.hostname != "127.0.0.1" or target.port != 18394:
            raise ValueError("P14 object upload must target owned loopback object service 127.0.0.1:18394")
        upload = requests.put(signed["uploadUrl"], data=data, headers={"Content-Type": "image/png"}, timeout=30)
        upload.raise_for_status()
        registered = self.create(state_key, "/attachments", {
            "entityType": "PURCHASE_RECEIPT", "entityId": receipt["id"],
            "fileName": self.receipt_file.name, "fileUrl": signed["fileUrl"],
            "fileSize": len(data), "fileType": "image/png", "fileHash": hashlib.sha256(data).hexdigest(),
            "description": "P14新厂正常收料凭证", "uploadSource": "API"})
        self.persist()
        return registered

    def sales(self):
        customer = self.create("customer", "/customers", {
            "name": "P14验收客户", "contactPerson": "验收联系人",
            "phone": "13800009901", "shippingAddress": "P14本地验收地址",
            "billingAddress": "P14本地验收地址", "status": "ACTIVE", "notes": "P14新厂正常API验收"})
        items = [{"productTypeId": self.state["entities"]["sku_" + key]["id"],
                  "quantity": qty, "unit": self.state["entities"]["sku_" + key]["unit"],
                  "unitPrice": price, "taxRate": 0,
                  "sourceWarehouseCode": "WH-FG"}
                 for key, qty, price in (("a", 4, 30), ("b", 10, 15))]
        order = self.create("p14_sales_order", "/sales/orders", {
            "customerId": customer["id"], "orderDate": date.today().isoformat(),
            "requiredDeliveryDate": date.today().isoformat(), "processingMode": "STANDARD_SALE",
            "materialSupplyMode": "FACTORY_SUPPLIED", "remark": "P14新厂双SKU出货验收", "items": items})
        confirmed = self.create("p14_sales_confirmed", "/sales/orders/" + order["id"] + "/confirm", {})
        if confirmed.get("status") != "FINANCE_APPROVED":
            raise RuntimeError("Sales order did not reach FINANCE_APPROVED; complete normal OA first")
        order = self.call("GET", "/sales/orders/" + order["id"])
        master = self.create("p14_delivery_master", "/sales/deliveries", {
            "idempotencyKey": "p14-delivery-master", "salesOrderId": order["id"],
            "customerId": customer["id"], "deliveryDate": date.today().isoformat(),
            "deliveryAddress": "P14本地验收地址", "remark": "P14新厂双SKU出货验收",
            "items": [{"salesOrderItemId": line["id"], "productTypeId": line["productTypeId"],
                       "deliveredQuantity": line["quantity"], "unit": line["unit"],
                       "unitPrice": line["unitPrice"], "sourceWarehouseCode": "WH-FG"}
                      for line in order["items"]]})
        master = self.call("GET", "/sales/deliveries/" + master["id"])
        shipment = self.create("p14_shipment", "/sales/deliveries/" + master["id"] + "/shipments", {
            "idempotencyKey": "p14-child-shipment", "plannedShipmentDate": date.today().isoformat(),
            "deliveryMode": "FULL", "deliveryMethod": "SELF_PICKUP", "remark": "P14本地验收出货",
            "items": [{"parentDeliveryItemId": line["id"], "quantity": line["deliveredQuantity"]}
                      for line in master["items"]]})
        shipment = self.call("GET", "/sales/deliveries/" + shipment["id"])
        for line in shipment["items"]:
            key = next(k for k in ("a", "b") if self.state["entities"]["sku_" + k]["id"] == line["productTypeId"])
            self.create("p14_allocation_" + str(line["id"]),
                        "/sales-deliveries/items/" + str(line["id"]) + "/batch-allocations", {
                            "allocations": [{"finishedGoodsBatchId": self.state["entities"]["p14_finished_receipt_" + key]["finishedGoodsBatchId"],
                                              "allocatedQty": line["deliveredQuantity"]}]})
        shipped = self.create("p14_shipped", "/warehouse/deliveries/" + shipment["id"] + "/confirm",
                              {"actualQuantities": {str(line["id"]): line["deliveredQuantity"] for line in shipment["items"]}})
        (self.out / "sales-readback.json").write_text(json.dumps(shipped, ensure_ascii=False, indent=2), encoding="utf-8")

    def metrics_readback(self):
        """Read platform-created definitions and same-period values; never configure them."""
        definitions = self.call("GET", "/canvas-indicators")
        codes = {item.get("code") for item in definitions}
        expected = {"FACTORY_LU_YIELD_RATE", "FACTORY_GOOD_OUTPUT_RATE",
                    "FACTORY_LU_UNIT_COST", "FACTORY_ACTUAL_TOTAL_COST"}
        missing = sorted(expected - codes)
        if missing:
            raise AssertionError("Platform auto-initialization missing metrics: " + ",".join(missing))
        period = date.today().isoformat()
        values = {code: self.call("GET", "/indicators/" + code + "/value?periodStart=" + period + "&periodEnd=" + period)
                  for code in sorted(expected)}
        (self.out / "metrics-readback.json").write_text(json.dumps({"definitions": definitions, "values": values}, ensure_ascii=False, indent=2), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:10064")
    parser.add_argument("--credentials", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--factory-id", required=True)
    parser.add_argument("--username", required=True)
    parser.add_argument("--receipt-file", type=Path,
                        default=Path.home() / "Documents/cretas-connect-20261015/factory-data-p0/01-purchase-receipt-synthetic.png")
    parser.add_argument("--allow-test-writes", choices=["NON_PRODUCTION_ONLY"], required=True)
    parser.add_argument("step", choices=["identity", "master", "workflows", "stock", "purchase", "receipt", "normal", "costs", "readback", "sales", "metrics-readback"])
    args = parser.parse_args()
    flow = NewFactoryFlow(args.url, args.credentials, args.out, args.factory_id, args.username, args.receipt_file)
    steps = {"master": flow.master_data, "purchase": flow.purchase, "receipt": flow.receipt,
             "workflows": flow.workflows, "stock": flow.prepare_stock, "normal": flow.normal_production,
             "readback": flow.normal_readback, "costs": flow.finish_costs,
             "sales": flow.sales, "metrics-readback": flow.metrics_readback}
    if args.step == "identity":
        print(json.dumps(flow.state["identity"], ensure_ascii=False))
    else:
        steps[args.step]()


if __name__ == "__main__":
    main()
