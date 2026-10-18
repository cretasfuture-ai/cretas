"""Local, pre-shipment QC acceptance using existing factory business APIs.

Reuse NewFactoryFlow/P0 production; never seed inspections, stock or approvals in SQL.
Record inspections with the existing UI, then run controls/review/shipping stages.
"""
from __future__ import annotations

import argparse
from datetime import date
import json
from pathlib import Path
import secrets

import psycopg
import requests

from new_factory_flow import NewFactoryFlow


class QualityReleaseFlow(NewFactoryFlow):
    def __init__(self, args):
        runtime = json.loads(args.runtime.read_text(encoding="utf-8"))
        if runtime.get("database") != "p14_new_factory_20261016" or runtime.get("clockAgent"):
            raise ValueError("Only the owned, real-clock P14 clone is permitted")
        self.database = runtime["database"]
        super().__init__("http://127.0.0.1:10064", args.credentials, args.out,
                         args.factory_id, args.username, args.receipt_file)
        self.run_key = "p16"
        source = json.loads(args.source_state.read_text(encoding="utf-8"))
        if source.get("factoryId") != self.factory:
            raise ValueError("Source master data belongs to another factory")
        for key in ("sku_a", "sku_b", "raw", "packaging", "logistics_warehouse", "workshop", "customer"):
            self.state["entities"].setdefault(key, source["entities"][key])
        self.reviewer_file = args.reviewer_credentials
        self.persist()

    def dbread(self, sql, params=()):
        with psycopg.connect(host="127.0.0.1", port=55447, dbname=self.database,
                             user="assembly_executor", options="-c default_transaction_read_only=on") as db:
            assert db.execute("select current_database()").fetchone()[0] == self.database
            return db.execute(sql, params).fetchall()

    def save(self, name, value):
        (self.out / (name + ".json")).write_text(
            json.dumps(value, ensure_ascii=False, indent=2, default=str), encoding="utf-8")

    def prepare(self):
        e = self.state["entities"]
        if not self.reviewer_file.exists():
            self.reviewer_file.write_text(json.dumps({"username": "p16_qc_review",
                "password": secrets.token_urlsafe(24)}, indent=2), encoding="utf-8")
        account = json.loads(self.reviewer_file.read_text(encoding="utf-8"))
        self.create("reviewer", "/users", {**account, "fullName": "新厂质检复核员",
                    "roleCode": "quality_manager", "department": "quality", "isActive": True})
        transfer = self.create("p16_pack_transfer", "/transfers", {
            "transferType": "WAREHOUSE_TO_WAREHOUSE", "targetFactoryId": self.factory,
            "sourceWarehouseId": e["logistics_warehouse"]["id"], "targetWarehouseId": e["workshop"]["id"],
            "transferDate": date.today().isoformat(), "remark": "出货前质检验收样本包材，仅本地",
            "items": [{"itemType": "RAW_MATERIAL", "materialTypeId": e["packaging"]["id"], "quantity": 40, "unit": "个"}]})
        self.create("p16_transfer_requested", "/transfers/" + transfer["id"] + "/request", {})
        self.create("p16_transfer_confirmed", "/transfers/" + transfer["id"] + "/confirm", {})
        self.normal_production()
        self.finish_costs()
        for key, quantity in (("a", 4), ("b", 10)):
            self.prepare_delivery(key, quantity)
        self.save("prepared", {"factoryId": self.factory, "inspectorId": self.actor_id,
            "reviewerId": e["reviewer"]["id"], "samples": {key: {
                "batch": self.call("GET", "/processing/batches/" + str(e["p16_batch_" + key]["id"])),
                "shipmentId": e["p16_shipment_" + key]["id"]}
                for key in ("a", "b")}, "noShipmentConfirmed": True})

    def prepare_delivery(self, key, quantity):
        e = self.state["entities"]; sku = e["sku_" + key]; today = date.today().isoformat()
        order = self.create("p16_order_" + key, "/sales/orders", {
            "customerId": e["customer"]["id"], "orderDate": today, "requiredDeliveryDate": today,
            "processingMode": "STANDARD_SALE", "materialSupplyMode": "FACTORY_SUPPLIED",
            "remark": "P16出货前质检验证，禁止实际发运", "items": [{"productTypeId": sku["id"],
                "quantity": quantity, "unit": sku["unit"], "unitPrice": 30 if key == "a" else 15,
                "taxRate": 0, "sourceWarehouseCode": "WH-FG"}]})
        confirmed = self.create("p16_order_confirm_" + key, "/sales/orders/" + order["id"] + "/confirm", {})
        assert confirmed["status"] == "FINANCE_APPROVED"
        order = self.call("GET", "/sales/orders/" + order["id"])
        master = self.create("p16_delivery_" + key, "/sales/deliveries", {
            "idempotencyKey": "p16-master-" + key, "salesOrderId": order["id"],
            "customerId": e["customer"]["id"], "deliveryDate": today,
            "deliveryAddress": "仅本地合成验收，禁止实际发运", "items": [{
                "salesOrderItemId": line["id"], "productTypeId": line["productTypeId"],
                "deliveredQuantity": line["quantity"], "unit": line["unit"], "unitPrice": line["unitPrice"],
                "sourceWarehouseCode": "WH-FG"} for line in order["items"]]})
        master = self.call("GET", "/sales/deliveries/" + master["id"])
        child = self.create("p16_shipment_" + key, "/sales/deliveries/" + master["id"] + "/shipments", {
            "idempotencyKey": "p16-child-" + key, "plannedShipmentDate": today,
            "deliveryMode": "FULL", "deliveryMethod": "SELF_PICKUP", "items": [
                {"parentDeliveryItemId": line["id"], "quantity": line["deliveredQuantity"]} for line in master["items"]]})
        child = self.call("GET", "/sales/deliveries/" + child["id"])
        for line in child["items"]:
            self.create("p16_allocation_" + str(line["id"]), "/sales-deliveries/items/" + str(line["id"]) + "/batch-allocations", {
                "allocations": [{"finishedGoodsBatchId": e["p16_finished_receipt_" + key]["finishedGoodsBatchId"],
                                 "allocatedQty": line["deliveredQuantity"]}]})
        return child

    def snapshot(self, key="b"):
        e = self.state["entities"]; order = e["p16_order_" + key]["id"]
        return {"stock": self.dbread("select id,status,produced_quantity,shipped_quantity,reserved_quantity,unit from finished_goods_batches where factory_id=%s and id=%s", (self.factory, e["p16_finished_receipt_" + key]["finishedGoodsBatchId"])),
            "delivery": self.dbread("select id,status,updated_at from sales_delivery_records where factory_id=%s and id=%s", (self.factory, e["p16_shipment_" + key]["id"])),
            "receivables": self.dbread("select id,amount,balance_after,source_id from ar_ap_transactions where factory_id=%s and sales_order_id=%s order by id", (self.factory, order)),
            "invoices": self.dbread("select id,status,total_amount from invoice_records where factory_id=%s and sales_order_id=%s order by id", (self.factory, order))}

    def rejected(self, name, method, path, payload, status, session=None):
        before = self.snapshot()
        response = (session or self.http).request(method, self.base + "/api/mobile/" + self.factory + path, json=payload, timeout=60)
        body = response.json(); after = self.snapshot()
        self.save(name, {"httpStatus": response.status_code, "body": body, "before": before, "after": after})
        assert response.status_code == status and body.get("success") is False, body
        assert before == after, "Rejected request changed stock/delivery/AR/invoice"
        return body

    def confirm_payload(self, key):
        child = self.call("GET", "/sales/deliveries/" + self.state["entities"]["p16_shipment_" + key]["id"])
        return {"actualQuantities": {str(line["id"]): line["deliveredQuantity"] for line in child["items"]}}

    def controls(self):
        e = self.state["entities"]
        for key, result in (("a", "PASS"), ("b", "FAIL")):
            inspections = self.call("GET", "/processing/quality/inspections?batchId=" + str(e["p16_batch_" + key]["id"]) + "&page=1&size=20")
            rows = [row for row in inspections["content"] if row["result"] == result]
            assert len(rows) == 1, "Record exactly one real inspection with the existing UI first"
            assert rows[0]["inspectorId"] == self.actor_id
            e["p16_qc_" + key] = rows[0]
        self.persist()
        assert self.snapshot()["stock"][0][1] == "DEFECTIVE"
        path = "/warehouse/deliveries/" + e["p16_shipment_b"]["id"] + "/confirm"
        body = self.rejected("failed-inspection-blocked-shipment", "POST", path, self.confirm_payload("b"), 409)
        assert body.get("errorCode", body.get("code")) == "FINISHED_GOODS_BATCH_NOT_SALEABLE"
        application = self.create("p16_application", "/quality-disposition/execute", {
            "batchId": e["p16_batch_b"]["id"], "inspectionId": e["p16_qc_b"]["id"],
            "actionCode": "CONDITIONAL_RELEASE", "executorId": self.actor_id,
            "operatorComment": "本地合成复核：确认仅包装外观异常，独立复核后条件放行；禁止实际发运"})
        assert application["approvalInitiated"] and application["status"] == "PENDING_APPROVAL"
        assert self.snapshot()["stock"][0][1] == "DEFECTIVE"
        self.rejected("pending-approval-blocked-shipment", "POST", path, self.confirm_payload("b"), 409)
        approval = "/quality-disposition/" + application["auditLogId"] + "/approve"
        self.rejected("self-approval-rejected", "POST", approval,
            {"approved": True, "approverId": self.actor_id, "approverName": "申请人不能自审批", "comment": "阴性对照"}, 403)
        self.save("controls-completed", {"status": "PASS", "application": application, "inspections": {key: e["p16_qc_" + key] for key in ("a", "b")}})

    def reviewer(self):
        credentials = json.loads(self.reviewer_file.read_text(encoding="utf-8"))
        session = requests.Session()
        response = session.post(self.base + "/api/mobile/auth/unified-login", json={**credentials, "clientType": "web"}, timeout=30)
        response.raise_for_status(); body = response.json(); assert body["success"]
        actor = body["data"]
        assert actor["factoryId"] == self.factory and actor["userId"] == self.state["entities"]["reviewer"]["id"] and actor["userId"] != self.actor_id and actor["role"] == "quality_manager"
        session.headers["Authorization"] = "Bearer " + (actor.get("token") or actor["accessToken"])
        return session, actor

    def release(self):
        e = self.state["entities"]; session, actor = self.reviewer()
        path = "/quality-disposition/" + e["p16_application"]["auditLogId"] + "/approve"
        payload = {"approved": True, "approverId": actor["userId"], "approverName": "新厂质检复核员",
            "comment": "本地模拟独立复核：包装外观偏差不影响内容物，附条件批准；无真实检验或发运", "conditions": "仅本地合成验证，不允许真实发运"}
        if "p16_release" not in e:
            response = session.post(self.base + "/api/mobile/" + self.factory + path, json=payload, timeout=60)
            body = response.json(); self.save("release-response", {"status": response.status_code, "body": body})
            assert response.status_code == 200 and body["success"], body
            e["p16_release"] = body["data"]; self.persist()
        assert self.snapshot()["stock"][0][1] == "AVAILABLE", "Approved release did not restore saleability"
        self.rejected("repeated-approval-rejected", "POST", path, payload, 409, session)
        self.save("release-readback", {"status": "PASS", "reviewerId": actor["userId"], "stock": self.snapshot()["stock"]})

    def ship(self):
        e = self.state["entities"]
        # Verify persisted pre-shipment evidence rather than trusting saved UI/API state.
        for key, expected in (("a", "PASS"), ("b", "FAIL")):
            inspection = self.dbread("select result,inspector_id,created_at from quality_inspections where factory_id=%s and id=%s and production_batch_id=%s",
                (self.factory, e["p16_qc_" + key]["id"], e["p16_batch_" + key]["id"]))
            assert len(inspection) == 1 and inspection[0][0] == expected and inspection[0][1] == self.actor_id
            assert self.snapshot(key)["delivery"][0][1] != "SHIPPED", "Do not accept post-shipment inspection as pre-shipment release"
            if key == "b":
                approval = self.dbread("select approval_status,approver_id,approved_at from decision_audit_logs where factory_id=%s and id=%s",
                    (self.factory, e["p16_application"]["auditLogId"]))
                assert len(approval) == 1 and approval[0][0] == "APPROVED"
                assert approval[0][1] == e["reviewer"]["id"] and approval[0][1] != inspection[0][1]
                assert inspection[0][2] < approval[0][2]
        for key in ("a", "b"):
            result = self.create("p16_shipped_" + key, "/warehouse/deliveries/" + e["p16_shipment_" + key]["id"] + "/confirm", self.confirm_payload(key))
            assert result["status"] == "SHIPPED"
        self.normal_readback()
        self.save("shipped-readback", {"status": "PASS", "a": self.snapshot("a"), "b": self.snapshot("b")})


def main():
    p = argparse.ArgumentParser()
    p.add_argument("stage", choices=["prepare", "controls", "release", "ship"])
    for name in ("credentials", "source-state", "runtime", "reviewer-credentials", "out", "receipt-file"):
        p.add_argument("--" + name, type=Path, required=True)
    p.add_argument("--factory-id", required=True); p.add_argument("--username", required=True)
    p.add_argument("--allow-test-writes", choices=["NON_PRODUCTION_ONLY"], required=True)
    args = p.parse_args(); flow = QualityReleaseFlow(args); getattr(flow, args.stage)()


if __name__ == "__main__":
    main()
