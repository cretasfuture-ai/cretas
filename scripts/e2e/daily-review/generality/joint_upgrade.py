"""Resumable, P37-only formal version-switch and joint-metric acceptance.

Reuses the existing ordinary factory login, HTTP checkpoints and read-only SQL
helpers. No business SQL writes, source-database writes, or production hosts.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from decimal import Decimal
from pathlib import Path
from urllib.parse import urlsplit

import requests
import acceptance as a

s = a.support
a.WRITE_PREFIX = "joint-upgrade-"
OUT = a.OUT / "joint-upgrade-20261019"
OUT.mkdir(exist_ok=True)
PRODUCT = "cab6e6ca-2bd1-456e-b06d-48a11afc6e24"
RAW = "RMT_5e81e725-90cc-4de9-91a8-d8f3fff5b40b"
EXTRA = "RMT_eb3e8fe4-aa56-4ed3-8417-7b03b8516416"
PACK = "RMT_eb0f1367-4824-4712-8b94-0b805981c420"
NEW_BOM = "f1245df3-0ecd-4a76-8536-78122410d526"
OLD_BOM = "8e287f1e-ee1b-4e6b-910a-86707f33ea28"


def save(name, value):
    (OUT / (name + ".json")).write_text(json.dumps(value, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    return value


def checkpoint(name, getter):
    file = OUT / (name + ".json")
    return json.loads(file.read_text(encoding="utf-8")) if file.exists() else save(name, getter())


def read(name, path):
    return a.read("joint-upgrade-" + name, path)


def write(name, path, body, method="POST"):
    return a.write_once(name, path, body, method)


def immutable_history():
    # Inventory balances change through legitimate consumption. The historical
    # transaction rows, financial records and settled batches must not change.
    return {table: rows for table, rows in s.snapshot().items()
            if table not in {"material_batches", "indicators", "indicator_versions"}}


def assert_history():
    before = checkpoint("historical-before", immutable_history)
    after = immutable_history()
    counts = {}
    published = s.steps.get("generality.write." + a.WRITE_PREFIX + "formal-publish", {})
    rebound = set(published.get("planRebind", {}).get("reboundPlanNumbers", []))
    transitions = []
    pin_fields = {"updated_at", "selected_workflow_id", "selected_workflow_version", "selected_bom_recipe_id",
                  "selected_bom_version", "selected_workflow_revision_id", "selected_workflow_revision_hash",
                  "selected_bom_family_id", "selected_bom_recipe_ids_by_product", "selected_bom_versions_by_product"}
    for table, rows in before.items():
        actual = {str(row["id"]): row for row in after[table]}
        for old in rows:
            current = actual.get(str(old["id"]))
            if table == "production_plans" and old.get("plan_number") in rebound:
                assert old["status"] == "PENDING" and current["status"] == "PENDING"
                assert not any(b.get("production_plan_id") == old["id"] for b in before["production_batches"])
                assert not any(b.get("production_plan_id") == old["id"] for b in before["production_settlements"])
                changes = {k: [old.get(k), current.get(k)] for k in old if old.get(k) != current.get(k)}
                assert set(changes) <= pin_fields, changes
                transitions.append({"planId": old["id"], "planNumber": old["plan_number"], "changes": changes})
            else:
                assert current == old, ("Historical row changed", table, old["id"])
        counts[table] = len(rows)
    return save("historical-proof", {"checkedExistingRows": counts, "unstartedPlanVersionTransitions": transitions,
                "historicalTransactionsAndStartedPlansUnchanged": True,
                "sha256": hashlib.sha256(json.dumps(before, sort_keys=True).encode()).hexdigest()})


def assert_formal_content():
    old_workflow = json.loads((OUT / "old-published.json").read_text(encoding="utf-8"))
    old_bom = json.loads((OUT / "old-bom.json").read_text(encoding="utf-8"))
    workflow = read("old-published-after", f"/product-process-workflows/{PRODUCT}/versions/1")
    bom = read("old-bom-after", f"/bom/recipes/{OLD_BOM}")
    # Formal lifecycle flags may change on activation; the historical recipe,
    # graph, quantities, units and revision pins must remain byte-equivalent.
    lifecycle = {"status", "isCurrent", "updatedAt"}
    for before, after in ((old_workflow, workflow), (old_bom, bom)):
        assert {k: v for k, v in before.items() if k not in lifecycle} == {
            k: v for k, v in after.items() if k not in lifecycle}, "Old formal content rewritten"
    return save("formal-content-proof", {"oldWorkflowContentUnchanged": True, "oldBomContentUnchanged": True,
                "oldBomStatus": bom["status"], "oldBomCurrent": bom["isCurrent"]})


def transfer(key, material, qty, unit):
    warehouses = a.as_items(read(key + "-warehouses", "/factory/warehouses"))
    workshop = next(w for w in warehouses if w["type"] == "WORKSHOP")
    with s.dbconnect() as d:
        source = d.execute("select warehouse_id from material_batches where factory_id=%s and material_type_id=%s "
            "and warehouse_id<>%s and deleted_at is null and quantity_unit=%s and status='AVAILABLE' "
            "group by warehouse_id having sum(receipt_quantity-used_quantity-coalesce(reserved_quantity,0)) >= %s "
            "order by warehouse_id limit 1", (a.FACTORY, material, workshop["id"], unit, qty)).fetchone()
    if "generality.write." + a.WRITE_PREFIX + key not in s.steps:
        assert source, ("No actual stock for transfer", material)
    value = write(key, "/transfers", {"transferType": "WAREHOUSE_TO_WAREHOUSE", "targetFactoryId": a.FACTORY,
        "sourceWarehouseId": source[0] if source else None, "targetWarehouseId": workshop["id"],
        "transferDate": a.TODAY, "remark": "正式版本切换本地验收领料",
        "items": [{"itemType": "RAW_MATERIAL", "materialTypeId": material, "quantity": qty, "unit": unit}]})
    write(key + "-request", f"/transfers/{value['id']}/request", {})
    write(key + "-confirm", f"/transfers/{value['id']}/confirm", {})


def report(key, extra):
    # Omit selected workflow/version: normal new plans must choose current
    # activation themselves. The pre-switch report makes its old pin immutable.
    plan = write(key + "-plan", "/production-plans", {"clientRequestId": "p37-joint-" + key,
        "productTypeId": PRODUCT, "plannedQuantity": 2, "plannedDate": a.TODAY,
        "expectedCompletionDate": a.TODAY, "sourceType": "MANUAL", "planType": "FROM_INVENTORY",
        "skipProcessReporting": False, "notes": "正式版本切换本地验收：" + key})
    plan_id = plan["id"]
    batch = write(key + "-batch", f"/production-plans/{plan_id}/create-batch", {})
    transfer(key + "-raw", RAW, 1, "kg")
    transfer(key + "-pack", PACK, 2, "个")
    if extra:
        transfer(key + "-extra", EXTRA, 0.25, "kg")
    inputs = [{"materialTypeId": RAW, "quantity": 1, "unit": "kg",
               "workflowPortId": "in-raw", "materialNodeId": "raw"}]
    if extra:
        inputs.append({"materialTypeId": EXTRA, "quantity": 0.25, "unit": "kg",
                       "workflowPortId": "p37-upgrade-in", "materialNodeId": "p37-upgrade-raw"})
    row = write(key + "-report", f"/production-plans/{plan_id}/process-sheet/row/submit", {
        "clientRowId": "p37-joint-" + key, "idempotencyKey": "p37-joint-" + key,
        "processCode": "cook", "processOrder": 1, "processName": "P0体验熟制包装B", "processDate": a.TODAY,
        "productTypeId": PRODUCT, "finished": True, "inputQuantity": 1.25 if extra else 1,
        "inputUnit": "kg", "outputQuantity": 2, "outputUnit": "盒", "unit": "盒",
        "materialNodeId": "finished", "workflowPortId": "out-fg", "materialInputTotals": inputs,
        "laborSegments": []})
    return save(key, {"plan": plan, "batch": batch, "report": row})


def plan_pin(plan_id):
    with s.dbconnect() as d:
        row = d.execute("select row_to_json(p) from production_plans p where p.factory_id=%s and p.id=%s",
                        (a.FACTORY, plan_id)).fetchone()[0]
    return {key: value for key, value in row.items() if "workflow" in key or "bom" in key}


def prepare():
    checkpoint("historical-before", immutable_history)
    checkpoint("old-published", lambda: read("old-published", f"/product-process-workflows/{PRODUCT}/versions/1"))
    checkpoint("old-bom", lambda: read("old-bom", f"/bom/recipes/{OLD_BOM}"))
    old = report("before-switch", False)
    pin = checkpoint("before-switch-pin", lambda: plan_pin(old["plan"]["id"]))
    assert pin.get("selected_workflow_version") == 1, pin
    save("prepared", {"oldPlan": old["plan"]["id"], "pin": pin, "submittedBeforeSwitch": True})


def stock():
    supplier = a.as_items(read("suppliers", "/suppliers"))[0]
    write("supplier-extra", f"/suppliers/{supplier['id']}/materials", {"materialTypeId": EXTRA,
          "defaultPurchasePrice": 20, "purchaseUnit": "kg", "currency": "CNY", "active": True})
    order = write("extra-order", "/purchase/orders", {"supplierId": supplier["id"], "orderDate": a.TODAY,
        "expectedDeliveryDate": a.TODAY, "remark": "本地版本切换新增原料正常采购",
        "items": [{"materialTypeId": EXTRA, "quantity": 10, "unit": "kg", "quantityUnit": "kg",
                   "unitPrice": 20, "priceUnit": "kg", "taxRate": 0}]})
    write("extra-order-submit", f"/purchase/orders/{order['id']}/submit", {})
    order = read("extra-order", f"/purchase/orders/{order['id']}")
    warehouse = next(w for w in a.as_items(read("raw-warehouses", "/factory/warehouses")) if w["type"] == "LOGISTICS")
    receipt = write("extra-receipt", "/warehouse/receiving/receipts", {"purchaseOrderId": order["id"],
        "supplierId": supplier["id"], "receiveDate": a.TODAY, "warehouseId": warehouse["id"],
        "remark": "本地合成验收凭证", "items": [{"purchaseOrderItemId": order["items"][0]["id"],
        "materialTypeId": EXTRA, "receivedQuantity": 10, "unit": "kg", "priceUnit": "kg", "unitPrice": 20}]})
    attachment_key = "generality.write." + a.WRITE_PREFIX + "extra-attachment"
    if attachment_key not in s.steps:
        # Locally generated receipt preview; no factory photograph is uploaded.
        import io
        from PIL import Image, ImageDraw
        picture = Image.new("RGB", (800, 300), "white")
        ImageDraw.Draw(picture).text((30, 80), "LOCAL TEST RECEIPT: 10 kg x CNY 20 = CNY 200", fill="black")
        stream = io.BytesIO(); picture.save(stream, format="PNG"); data = stream.getvalue()
        signed = write("extra-upload", "/attachments/upload-url", {"fileName": "joint-upgrade-receipt.png",
                         "fileType": "image/png", "fileSize": len(data)})
        target = urlsplit(signed["uploadUrl"])
        assert target.scheme == "http" and target.hostname == "127.0.0.1" and target.port == 18402
        response = requests.put(signed["uploadUrl"], data=data, headers={"Content-Type": "image/png"}, timeout=30)
        response.raise_for_status()
        write("extra-attachment", "/attachments", {"entityType": "PURCHASE_RECEIPT", "entityId": receipt["id"],
            "fileName": "joint-upgrade-receipt.png", "fileUrl": signed["fileUrl"], "fileSize": len(data),
            "fileType": "image/png", "fileHash": hashlib.sha256(data).hexdigest(),
            "description": "本地合成收货凭证", "uploadSource": "API"})
    write("extra-receipt-confirm", f"/warehouse/receiving/receipts/{receipt['id']}/confirm", {})


def activate():
    assert (OUT / "prepared.json").exists(), "Submit old-version business before switching"
    stock()
    bom = read("draft-bom", f"/bom/recipes/{NEW_BOM}")
    fields = ("materialTypeId", "standardQuantity", "unit", "materialCategory", "sortOrder", "isOptional",
              "substitutes", "packagingSpecId", "packagingRole", "naturalQuantity", "remark", "perPortion",
              "semiFinishedRefCode", "subProductTypeId", "primaryCode", "primaryCodeRef", "workflowMaterialNodeId",
              "workflowInputPortId", "workflowEdgeId", "costScope", "costScopeKey")
    items = [{k: row[k] for k in fields if k in row} for row in bom["items"]]
    for item in items:
        if item["materialTypeId"] == RAW: item["standardQuantity"] = 0.5
        if item["materialTypeId"] == EXTRA: item["standardQuantity"] = 0.125
    write("bom-quantities", f"/bom/recipes/{NEW_BOM}", {"productName": bom["productName"],
           "notes": "本地正式切换：每盒原料0.5kg、追加原料0.125kg，包装复用原配置", "items": items}, "PUT")
    draft = read("editor-before-publish", f"/product-process-workflows/{PRODUCT}")
    body = {k: draft[k] for k in ("lockVersion", "revisionId", "revisionHash")}
    body.update(definitionVersion=draft.get("definitionVersion", draft["version"]), idempotencyKey="p37-joint-formal-activate")
    published = write("formal-publish", f"/product-process-workflows/{PRODUCT}/publish-and-activate", body)
    active = read("activation-after", f"/product-process-workflows/{PRODUCT}/activation")
    assert active["activeWorkflowId"] == 8 and active["activeDefinitionVersion"] == 2, active
    current_raw = read("bom-after", f"/bom/recipes/by-product/{PRODUCT}/current")
    current = [current_raw] if isinstance(current_raw, dict) and current_raw.get("id") else a.as_items(current_raw)
    assert any(b["id"] == NEW_BOM and b["status"] == "ACTIVE" for b in current), current
    old = json.loads((OUT / "before-switch.json").read_text(encoding="utf-8"))
    assert plan_pin(old["plan"]["id"]) == json.loads((OUT / "before-switch-pin.json").read_text()), "Started old plan was rebound"
    save("activated", {"published": published, "activation": active, "bom": current, "startedOldPlanPinUnchanged": True})
    assert_history()
    assert_formal_content()


def finish(key):
    case = json.loads((OUT / (key + ".json")).read_text(encoding="utf-8"))
    plan_id = case["plan"]["id"]
    read(key + "-prefill", f"/production-plans/{plan_id}/settlement-prefill")
    write(key + "-settle", f"/production-plans/{plan_id}/settle", {"idempotencyKey": "p37-joint-" + key + "-settle",
        "confirm": True, "defectCountOutcome": "NONE_CONFIRMED", "defectQuantity": 0,
        "quantityVarianceReason": "OTHER", "quantityVarianceNote": "本地验收实际生产2盒",
        "materialVarianceReason": "OTHER", "materialVarianceNote": "按该计划锁定版本的实际原料投入"})
    settlement = read(key + "-settlement", f"/production-plans/{plan_id}/settlement")
    lines = settlement["outputLines"]
    assert len(lines) == 1 and lines[0]["productTypeId"] == PRODUCT, lines
    write(key + "-receipt", f"/production-plans/{plan_id}/warehouse-receipt", {
        "idempotencyKey": "p37-joint-" + key + "-receipt", "outputLines": [{"productTypeId": PRODUCT,
        "batchNumber": lines[0]["reportedBatchNumber"], "receivedQuantity": 2, "quantityUnit": "盒"}]})
    summary = read(key + "-summary", f"/production-plans/{plan_id}/production-summary")
    return save(key + "-complete", {"planId": plan_id, "pin": plan_pin(plan_id), "summary": summary,
                "settlement": read(key + "-final-settlement", f"/production-plans/{plan_id}/settlement")})


def business():
    assert (OUT / "activated.json").exists()
    old = finish("before-switch")
    assert old["pin"] == json.loads((OUT / "before-switch-pin.json").read_text())
    report("after-switch", True)
    new = finish("after-switch")
    assert new["pin"] != old["pin"], (old["pin"], new["pin"])
    assert NEW_BOM in json.dumps(new["pin"]) and OLD_BOM in json.dumps(old["pin"]), (old["pin"], new["pin"])
    assert_history()
    assert_formal_content()
    save("formal-upgrade-business", {"old": old, "new": new, "historyUnchanged": True})


def close(actual, expected):
    if expected is None:
        assert actual is None, ("Missing facts must not publish a cost", actual)
        return
    assert actual is not None and abs(Decimal(str(actual)) - Decimal(str(expected))) <= Decimal("0.0001"), (actual, expected)


def complete_costs():
    # Reuse the accepted reporting/settlement/receipt flow with a NEW plan.
    # Historical partial-cost samples stay unchanged as negative controls.
    prefix = a.WRITE_PREFIX
    a.WRITE_PREFIX = "joint-complete-"
    original = a.STATE["generality"]["results"]["fresh-multi"]
    a.run_multi_business(original["publishedWorkflow"], "42b3334e-3d74-401f-b809-dc85d02e499c",
                         "2b865183-6d73-45a0-8cbb-ca6fd38cbf20", RAW, "盒", "盒", original["bomDraft"])
    case = a.STATE["generality"]["results"]["joint-complete-multi"]
    a.WRITE_PREFIX = prefix
    plans = [case["plan"]["id"]] + [json.loads((OUT / (key + "-complete.json")).read_text(encoding="utf-8"))["planId"]
                                   for key in ("before-switch", "after-switch")]
    before = s.snapshot()
    rejected = a.expected_failure("joint-multi-nonzero-cost-allocation",
        f"/production-plans/{case['plan']['id']}/costs?equipmentCost=1&otherCost=0", {}, method="PUT")
    assert before == s.snapshot(), "Rejected ambiguous cost allocation changed business rows"
    save("nonzero-allocation-negative", {"response": rejected, "businessRowsUnchanged": True})
    for plan in plans:
        write("confirm-demo-zero-costs-" + plan, f"/production-plans/{plan}/costs?equipmentCost=0&otherCost=0", {}, "PUT")
        summary = read("cost-complete-" + plan, f"/production-plans/{plan}/production-summary")
        save("cost-confirmed-" + plan, summary)
        with s.dbconnect() as d:
            batches = [r[0] for r in d.execute("select row_to_json(b) from production_batches b where b.factory_id=%s "
                "and b.production_plan_id=%s and b.deleted_at is null and (b.batch_type is null or b.batch_type<>'CLERK_WIP') order by b.id", (a.FACTORY, plan))]
            lines = [r[0] for r in d.execute("select row_to_json(o) from production_settlement_output_lines o where o.factory_id=%s "
                "and o.production_plan_id=%s and o.deleted_at is null order by o.id", (a.FACTORY, plan))]
            fg = [r[0] for r in d.execute("select row_to_json(f) from finished_goods_batches f where f.factory_id=%s "
                "and f.production_plan_id=%s and f.deleted_at is null order by f.id", (a.FACTORY, plan))]
        assert batches and all(b["equipment_cost"] == b["other_cost"] == 0 for b in batches)
        close(sum(Decimal(str(line["allocated_cost"])) for line in lines), summary["totalCost"])
        close(sum(Decimal(str(b["unit_cost"])) * Decimal(str(b["produced_quantity"])) for b in fg), summary["totalCost"])
        save("cost-db-" + plan, {"productionBatches": batches, "settlementLines": lines, "finishedGoods": fg})
    assert_history()
    save("new-joint-complete", {"planId": case["plan"]["id"], "confirmedEquipmentCost": 0,
                               "confirmedOtherCost": 0, "simulationOnly": True, "historicalPartialCasesUnchanged": True})


def metrics():
    codes = ["FACTORY_LU_YIELD_RATE", "FACTORY_GOOD_OUTPUT_RATE", "FACTORY_LU_UNIT_COST", "FACTORY_ACTUAL_TOTAL_COST"]
    results = {}
    old_versions = checkpoint("metric-history-before", lambda: s.snapshot()["indicator_versions"])
    denied = s.admin.get(f"http://127.0.0.1:{s.PORT}/api/mobile/F006/production/actual-metrics/by-product?periodStart=2026-10-18&periodEnd=2026-10-18", timeout=30)
    assert denied.status_code == 403, ("Cross-factory read was not rejected", denied.status_code)
    save("tenant-negative", {"accountFactory": a.FACTORY, "requestedFactory": "F006", "status": denied.status_code})
    for period, expected in (("2026-10-18", (100, 100, None, None)),
                             ("2026-10-19", ("95.2381", 100, "11.6", 58))):
        suffix = f"?periodStart={period}&periodEnd={period}"
        values = {}
        for code, value in zip(codes, expected):
            current = write("metric-" + period + "-" + code, f"/indicators/{code}/recompute" + suffix, {})
            close(current["value"], value)
            assert current.get("source") == f"REAL_BUSINESS:{code}:actual-flow-v3", current
            fresh = read("metric-" + period + "-" + code, f"/indicators/{code}/value" + suffix)
            close(fresh["value"], value)
            history = read("history-" + period + "-" + code,
                f"/indicators/{code}/versions?windowStart={period}&windowEnd={period}&exactPeriod=true&page=1&size=50")
            rows = a.as_items(history)
            assert value is None or any(row.get("periodStart") == period and row.get("periodEnd") == period
                       and row.get("value") is not None and abs(Decimal(str(row["value"])) - Decimal(str(value))) <= Decimal("0.0001") for row in rows), history
            values[code] = {"recomputed": current, "fresh": fresh, "history": history}
        products = read("products-" + period, "/production/actual-metrics/by-product" + suffix)
        by_id = {p["productTypeId"]: p for p in products["products"]}
        for product_id, wanted in (("42b3334e-3d74-401f-b809-dc85d02e499c", ("33.3333", 100, "11.7", "11.7")),
                                   ("2b865183-6d73-45a0-8cbb-ca6fd38cbf20", ("66.6667", 100, "9.65", "19.3"))):
            product = by_id[product_id]
            if period == "2026-10-18": wanted = (wanted[0], wanted[1], None, None)
            for field, value in zip(("yieldRate", "goodOutputRate", "unitCost", "totalCost"), wanted): close(product[field], value)
            assert product["batchCount"] == (4 if period == "2026-10-18" else 2), product
        if period == "2026-10-19":
            product = next(p for p in products["products"] if p["productTypeId"] == PRODUCT)
            for field, value in zip(("yieldRate", "goodOutputRate", "unitCost", "totalCost"), ("88.8889", 100, "13.5", 27)): close(product[field], value)
        results[period] = {"indicators": values, "products": products}
    save("metrics-acceptance", results)
    new_versions = {row["id"]: row for row in s.snapshot()["indicator_versions"]}
    assert all(new_versions.get(row["id"]) == row for row in old_versions), "Prior metric snapshots were rewritten"
    save("metric-history-proof", {"unchangedOldRows": len(old_versions), "newSource": "actual-flow-v3"})


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("ack", choices=["NON_PRODUCTION_ONLY"])
    parser.add_argument("--stage", required=True, choices=["prepare", "activate", "business", "complete-costs", "history", "metrics"])
    args = parser.parse_args()
    {"prepare": prepare, "activate": activate, "business": business, "complete-costs": complete_costs, "history": assert_history, "metrics": metrics}[args.stage]()
    print(json.dumps({"stage": args.stage, "status": "PASS", "evidence": str(OUT)}, ensure_ascii=False))
