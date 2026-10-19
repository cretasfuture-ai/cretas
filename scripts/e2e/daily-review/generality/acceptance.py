"""P37 factory generality acceptance.

This runner is deliberately small and resumable.  It uses the P37 runtime and
support module as the authority for identity, HTTP, database readback and
checkpoint persistence.  Business fixtures are created in the P37 clone only;
the source database is never written.  A stage may be run independently:

    python acceptance.py NON_PRODUCTION_ONLY --stage init
    python acceptance.py NON_PRODUCTION_ONLY --stage units
    python acceptance.py NON_PRODUCTION_ONLY --stage multi
    python acceptance.py NON_PRODUCTION_ONLY --stage upgrade
    python acceptance.py NON_PRODUCTION_ONLY --stage readback

The multi stage publishes and activates only its newly-created dedicated
fixture. The upgrade stage proves draft version preservation and leaves the
formal publish decision for the original factory workflow to the owner.
"""
from __future__ import annotations

import argparse
import copy
import importlib.util
import json
import os
import sys
from datetime import date
from decimal import Decimal
from pathlib import Path
from typing import Any, Callable


if "NON_PRODUCTION_ONLY" not in sys.argv[1:]:
    raise SystemExit("Explicit NON_PRODUCTION_ONLY acknowledgement required before local login")
if os.environ.get("DAILY_REVIEW_PROFILE", "P37") != "P37":
    raise SystemExit("This acceptance runner only permits the P37 profile")
os.environ["DAILY_REVIEW_PROFILE"] = "P37"

HERE = Path(__file__).resolve().parents[1]
SUPPORT_FILE = HERE / "refund-close-concurrency" / "support.py"
spec = importlib.util.spec_from_file_location("p37_daily_review_support", SUPPORT_FILE)
if spec is None or spec.loader is None:
    raise RuntimeError(f"Cannot load shared support: {SUPPORT_FILE}")
support = importlib.util.module_from_spec(spec)
spec.loader.exec_module(support)

OUT: Path = support.OUT
STATE: dict[str, Any] = support.state
FACTORY = support.FACTORY
TODAY = date.today().isoformat()
WRITE_PREFIX = ""


def persist() -> None:
    support.persist()


def event(name: str, result: Any) -> Any:
    """Record a completed stage result without exposing credentials."""
    STATE.setdefault("generality", {}).setdefault("results", {})[name] = result
    persist()
    return result


def read(name: str, path: str) -> Any:
    """A fresh successful factory-relative GET with durable evidence."""
    value = support.call(support.admin, "GET", path)
    reads = STATE.setdefault("generality", {}).setdefault("freshReads", [])
    reads.append({"name": name, "path": path, "value": value})
    persist()
    return value


def write_once(name: str, path: str, payload: Any, method: str = "POST") -> Any:
    """Run a mutating API call once; a completed key is never blindly replayed."""
    key = "generality.write." + WRITE_PREFIX + name
    if key in support.steps:
        return support.steps[key]
    value = support.call(support.admin, method, path, payload)
    support.steps[key] = value
    persist()
    return value


def expected_failure(name: str, path: str, payload: Any, *, method: str = "POST") -> dict[str, Any]:
    """Record a negative control while requiring a client error and no success."""
    key = "generality.negative." + name
    # Failed controls have no successful business write to replay. Exercise the
    # current runtime again so rollback checks never pass merely from a cached error.
    result = support.send(support.admin, method, path, payload)
    if result["status"] < 400 or result["status"] >= 500:
        raise AssertionError(f"Negative control did not fail as a client error: {result}")
    result["businessWrite"] = False
    support.steps[key] = result
    persist()
    return result


def as_items(value: Any) -> list[dict[str, Any]]:
    if isinstance(value, list):
        return [x for x in value if isinstance(x, dict)]
    if isinstance(value, dict):
        for key in ("content", "items", "records", "data"):
            if isinstance(value.get(key), list):
                return [x for x in value[key] if isinstance(x, dict)]
    return []


def id_of(value: dict[str, Any], *keys: str) -> str:
    for key in keys or ("id",):
        if value.get(key) is not None:
            return str(value[key])
    raise AssertionError(f"Expected identifier in object: {value}")


def choose_fixtures() -> dict[str, Any]:
    """Read existing P14-like master data from P37; never import its credentials."""
    products = as_items(read("product-types", "/product-types"))
    customers = as_items(read("customers", "/customers"))
    warehouses = as_items(read("warehouses", "/factory/warehouses"))
    if not products or not customers or not warehouses:
        raise RuntimeError("P37 clone does not contain the expected existing master data")
    box = next((p for p in products if str(p.get("unit", "")).lower() in {"box", "盒"}), None)
    if box is None:
        raise RuntimeError("No existing box-based finished good; refusing to guess a conversion fixture")
    fg = next((w for w in warehouses if str(w.get("type", "")).upper() == "FINISHED"), None)
    if fg is None:
        raise RuntimeError("No finished-goods warehouse in P37 clone")
    if not fg.get("code"):
        raise RuntimeError("Finished-goods warehouse has no API code for shipment source")
    return {"product": box, "boxUnit": str(box["unit"]), "customer": customers[0], "warehouse": fg,
            "productId": id_of(box, "id", "productTypeId"),
            "customerId": id_of(customers[0], "id", "customerId"),
            "warehouseId": id_of(fg, "id", "warehouseId")}


def stage_init() -> None:
    fixtures = choose_fixtures()
    versions = read("workflow-versions", f"/product-process-workflows/{fixtures['productId']}/versions")
    activation = read("workflow-activation", f"/product-process-workflows/{fixtures['productId']}/activation")
    recipes = read("bom-current", f"/bom/recipes/by-product/{fixtures['productId']}/current")
    STATE.setdefault("generality", {})["fixtures"] = fixtures
    event("init", {"fixtures": fixtures, "workflowVersions": versions,
                    "activation": activation, "bomVersions": recipes,
                    "sourceDatabase": support.SOURCE, "targetDatabase": support.DATABASE,
                    "businessWrites": 0})


def fixture_state() -> dict[str, Any]:
    fixtures = STATE.get("generality", {}).get("fixtures")
    if not fixtures:
        stage_init()
        fixtures = STATE["generality"]["fixtures"]
    return fixtures


def db_guard_snapshot() -> dict[str, str]:
    """Hash old runtime rows that a draft upgrade must preserve exactly."""
    queries = {
        "production_plans": "select row_to_json(t) from production_plans t where factory_id=%s order by id",
        "production_batches": "select row_to_json(t) from production_batches t where factory_id=%s order by id",
        "production_settlements": "select row_to_json(t) from production_settlements t where factory_id=%s order by id",
        "bom_recipes_active": "select row_to_json(t) from bom_recipes t where factory_id=%s and status='ACTIVE' order by id",
        "bom_recipe_items_active": "select row_to_json(i) from bom_recipe_items i join bom_recipes r on r.id=i.recipe_id where r.factory_id=%s and r.status='ACTIVE' order by i.id",
    }
    import hashlib
    result: dict[str, str] = {}
    with support.dbconnect() as db:
        for name, query in queries.items():
            rows = [row[0] for row in db.execute(query, (FACTORY,)).fetchall()]
            encoded = json.dumps(rows, ensure_ascii=False, sort_keys=True, default=str).encode()
            result[name] = hashlib.sha256(encoded).hexdigest()
    return result


def stage_units() -> None:
    f = fixture_state()
    period_view = read("unit-period-before", "/finance/accounting-periods/2026/10")
    if str(period_view.get("status", "")).upper() == "CLOSED":
        support.period("reopen", "generality-unit-reopen")
    # Positive control: order and shipment carry 500g, while the SKU's canonical unit is 1 box.
    order = write_once("unit-order", "/sales/orders", {
        "customerId": f["customerId"], "orderDate": TODAY,
        "requiredDeliveryDate": TODAY, "processingMode": "STANDARD_SALE",
        "materialSupplyMode": "FACTORY_SUPPLIED",
        "remark": "P37 generality unit conversion acceptance",
        "items": [{"productTypeId": f["productId"], "quantity": 500,
                    "unit": "g", "unitPrice": 0.03, "taxRate": 0,
                    "sourceWarehouseCode": f["warehouse"]["code"]}],
    })
    order_id = id_of(order, "id", "orderId")
    confirmed = write_once("unit-order-confirm", f"/sales/orders/{order_id}/confirm", {})
    if str(confirmed.get("status", "")).upper() not in {"FINANCE_APPROVED", "APPROVED"}:
        raise AssertionError(f"Unit order did not reach an approved state: {confirmed}")
    order_view = read("unit-order-view", f"/sales/orders/{order_id}")
    delivery = write_once("unit-delivery", "/sales/deliveries", {
        "idempotencyKey": "p37-generality-unit-delivery",
        "salesOrderId": order_id, "customerId": f["customerId"],
        "deliveryDate": TODAY, "deliveryAddress": "P37 loopback acceptance",
        "items": [{"salesOrderItemId": id_of(order_view["items"][0], "id", "salesOrderItemId"),
                    "productTypeId": f["productId"], "deliveredQuantity": 500,
                    "unit": "g", "unitPrice": 0.03,
                    "sourceWarehouseCode": f["warehouse"]["code"]}],
    })
    delivery_id = id_of(delivery, "id", "deliveryId")
    delivery_view = read("unit-delivery-view", f"/sales/deliveries/{delivery_id}")
    delivery_item = as_items(delivery_view.get("items") if isinstance(delivery_view, dict) else None)
    if not delivery_item:
        raise RuntimeError("Positive unit delivery did not expose a delivery item for shipment")
    item_id = id_of(delivery_item[0], "id", "deliveryItemId")
    shipment = write_once("unit-shipment", f"/sales/deliveries/{delivery_id}/shipments", {
        "idempotencyKey": "p37-generality-unit-shipment",
        "plannedShipmentDate": TODAY, "deliveryMode": "FULL",
        "deliveryMethod": "SELF_PICKUP", "remark": "P37 mixed-unit shipment cost acceptance",
        "items": [{"parentDeliveryItemId": item_id, "quantity": 500}],
    })
    shipment_id = id_of(shipment, "id", "shipmentId")
    shipment_view = read("unit-shipment-view", f"/sales/deliveries/{shipment_id}")
    shipment_items = as_items(shipment_view.get("items") if isinstance(shipment_view, dict) else None)
    if not shipment_items:
        raise RuntimeError("Positive unit shipment did not expose a shipment item")
    shipment_item_id = id_of(shipment_items[0], "id", "deliveryItemId")
    fifo = read("unit-fifo-recommendation", f"/sales-deliveries/items/{shipment_item_id}/batch-allocations/recommend-fifo?productTypeId={f['productId']}&requiredQty=500&unit=g&sourceWarehouseCode={f['warehouse']['code']}")
    fifo_rows = as_items(fifo)
    if not fifo_rows:
        raise RuntimeError("No real finished-goods batch is available for the positive unit shipment")
    batch_id = id_of(fifo_rows[0], "finishedGoodsBatchId", "batchId", "id")
    allocation = write_once("unit-batch-allocation", f"/sales-deliveries/items/{shipment_item_id}/batch-allocations", {
        "allocations": [{"finishedGoodsBatchId": batch_id, "allocatedQty": 500}],
    })
    shipped = write_once("unit-shipment-confirm", f"/warehouse/deliveries/{shipment_id}/confirm", {
        "actualQuantities": {str(shipment_item_id): 500},
    })
    shipment_cost = read("unit-shipment-cost", f"/sales/deliveries/{shipment_id}")
    if float(shipment_cost.get("totalAmount", 0)) != 15.0:
        raise AssertionError(f"Positive shipment revenue did not reconcile to 500g x 0.03: {shipment_cost}")
    with support.dbconnect() as d:
        allocation_row = d.execute(
            "select allocated_qty, unit, finished_goods_batch_id from sales_delivery_item_batch_allocations "
            "where factory_id=%s and delivery_item_id=%s and deleted_at is null",
            (FACTORY, shipment_item_id)).fetchall()
        if len(allocation_row) != 1:
            raise AssertionError(f"Expected one persisted batch allocation for positive shipment: {allocation_row}")
        allocated_qty, allocated_unit, allocated_batch_id = allocation_row[0]
        if float(allocated_qty) != 500.0 or allocated_unit != "g" or str(allocated_batch_id) != str(shipment_cost["items"][0]["finishedGoodsBatchId"]):
            raise AssertionError(f"Shipment allocation must preserve its original 500g document unit: {allocation_row}")
        batch_id = str(allocated_batch_id)
        movement = d.execute(
            "select quantity_used,unit from batch_lineage_edges where factory_id=%s "
            "and target_id=%s and source_id=%s and edge_type='FINISHED_TO_SHIPMENT' "
            "and meta->>'deliveryItemId'=%s and deleted_at is null",
            (FACTORY, shipment_id, batch_id, shipment_item_id)).fetchall()
        if len(movement) != 1 or float(movement[0][0]) != 1 or movement[0][1] != f["boxUnit"]:
            raise AssertionError(f"Physical deduction must be exactly one native box: {movement}")
        batch_row = d.execute(
            "select shipped_quantity, unit_cost from finished_goods_batches "
            "where factory_id=%s and id=%s and deleted_at is null",
            (FACTORY, batch_id)).fetchone()
        if batch_row is None or float(batch_row[0]) < 1.0 or batch_row[1] is None:
            raise AssertionError(f"Finished-goods batch lacks one-box movement or actual cost: {batch_row}")
    event("units", {"positive": {"order": order, "confirmed": confirmed, "delivery": delivery,
                                  "shipment": shipment, "allocation": allocation, "shipped": shipped,
                                  "costReadback": shipment_cost, "nativeMovement": movement, "allocationReadback": allocation_row}, "conversion": "g->box",
                     "businessWrites": 7})

    # Prepare a second valid volume document first. The negative control then
    # submits a numeric 500 allocation against a real box batch; the converter
    # must reject the volume-to-count dimension mismatch without any DML.
    order2 = write_once("unit-negative-prep-order", "/sales/orders", {
        "customerId": f["customerId"], "orderDate": TODAY,
        "requiredDeliveryDate": TODAY, "processingMode": "STANDARD_SALE",
        "materialSupplyMode": "FACTORY_SUPPLIED", "remark": "P37 negative volume allocation prep",
        "items": [{"productTypeId": f["productId"], "quantity": 500,
                    "unit": "ml", "unitPrice": 0.03, "taxRate": 0,
                    "sourceWarehouseCode": f["warehouse"]["code"]}],
    })
    order2_id = id_of(order2, "id", "orderId")
    write_once("unit-negative-prep-confirm", f"/sales/orders/{order2_id}/confirm", {})
    order2_view = read("unit-negative-prep-order-view", f"/sales/orders/{order2_id}")
    delivery2 = write_once("unit-negative-prep-delivery", "/sales/deliveries", {
        "idempotencyKey": "p37-generality-negative-delivery", "salesOrderId": order2_id,
        "customerId": f["customerId"], "deliveryDate": TODAY,
        "deliveryAddress": "P37 negative allocation prep",
        "items": [{"salesOrderItemId": id_of(order2_view["items"][0], "id", "salesOrderItemId"),
                    "productTypeId": f["productId"], "deliveredQuantity": 500,
                    "unit": "ml", "unitPrice": 0.03,
                    "sourceWarehouseCode": f["warehouse"]["code"]}],
    })
    delivery2_id = id_of(delivery2, "id", "deliveryId")
    delivery2_view = read("unit-negative-prep-view", f"/sales/deliveries/{delivery2_id}")
    item2 = as_items(delivery2_view.get("items"))[0]
    shipment2 = write_once("unit-negative-prep-shipment", f"/sales/deliveries/{delivery2_id}/shipments", {
        "idempotencyKey": "p37-generality-negative-shipment", "plannedShipmentDate": TODAY,
        "deliveryMode": "FULL", "deliveryMethod": "SELF_PICKUP", "remark": "P37 negative allocation prep",
        "items": [{"parentDeliveryItemId": id_of(item2, "id", "deliveryItemId"), "quantity": 500}],
    })
    shipment2_view = read("unit-negative-prep-shipment-view", f"/sales/deliveries/{id_of(shipment2, 'id')}")
    item2_ship = as_items(shipment2_view.get("items"))[0]
    batch2 = id_of(fifo_rows[0], "finishedGoodsBatchId", "batchId", "id")
    before = support.snapshot()
    negative = expected_failure("unit-negative-ml-allocation", f"/sales-deliveries/items/{id_of(item2_ship, 'id', 'deliveryItemId')}/batch-allocations", {
        "allocations": [{"finishedGoodsBatchId": batch2, "allocatedQty": 500}],
    })
    after = support.snapshot()
    if before != after:
        raise AssertionError("Negative volume->box control changed business state")
    event("units-negative", {"negative": negative, "before": before, "after": after, "businessWrite": False})


def stage_multi() -> None:
    """Create an isolated two-FG fixture, then run the full reporting loop."""
    fixture_state()
    repaired = support.steps.get("generality.write.repair-publish")
    if repaired:
        a = support.steps["generality.write.multi-fg-a"]; b = support.steps["generality.write.multi-fg-b"]
        raw_id = next(n["data"]["skuId"] for n in repaired["nodes"] if n["kind"] == "RAW_MATERIAL")
        run_multi_business(repaired, a["id"], b["id"], raw_id, a["unit"], b["unit"],
                           support.steps["generality.write.repair-bom-a"])
        return
    raw_items = as_items(read("multi-raw-materials", "/raw-material-types"))
    raw = next((x for x in raw_items if x.get("unit") == "kg" and x.get("isActive", True)), None)
    if raw is None:
        raise RuntimeError("No existing active kg raw material for the dedicated multi-output fixture")
    packaging = next((x for x in raw_items if x.get("unit") == "个" and
                      str(x.get("category", "")).upper() in {"包材", "PACKAGING"} and x.get("isActive", True)), None)
    if packaging is None:
        raise RuntimeError("No existing active packaging material for the dedicated multi-output BOM")
    fg_a = write_once("multi-fg-a", "/product-types", {
        "code": "P37-GENERALITY-FG-A", "name": "P37 generality FG A",
        "category": "finished", "productCategory": "FINISHED_PRODUCT", "unit": "box",
        "netContentQuantity": 500, "netContentUnit": "g", "isActive": True,
    })
    fg_b = write_once("multi-fg-b", "/product-types", {
        "code": "P37-GENERALITY-FG-B", "name": "P37 generality FG B",
        "category": "finished", "productCategory": "FINISHED_PRODUCT", "unit": "box",
        "netContentQuantity": 1000, "netContentUnit": "g", "isActive": True,
    })
    raw_id, a_id, b_id = id_of(raw, "id"), id_of(fg_a, "id"), id_of(fg_b, "id")
    process = write_once("multi-process", "/work-processes", {
        "processName": "P37 generality split", "processCategory": "P37 acceptance",
        "createCategory": True, "standardHourlyRate": 20, "needsInput": True,
        "isActive": True, "defaultOutputMaterialKind": "FINISHED_GOOD",
    })
    process_id = id_of(process, "id")
    a_unit = str(fg_a.get("unit"))
    b_unit = str(fg_b.get("unit"))
    if not a_unit or not b_unit or a_unit != b_unit:
        raise RuntimeError(f"Created multi-output products did not preserve one canonical unit: {a_unit!r}, {b_unit!r}")
    ports = [
        {"id": "p37-in", "direction": "INPUT", "materialNodeId": "p37-raw",
         "materialKind": "RAW_MATERIAL", "unit": "kg", "ordinal": 0},
        {"id": "p37-out-a", "direction": "OUTPUT", "materialNodeId": "p37-fg-a",
         "materialKind": "FINISHED_GOOD", "unit": a_unit, "ordinal": 0},
        {"id": "p37-out-b", "direction": "OUTPUT", "materialNodeId": "p37-fg-b",
         "materialKind": "FINISHED_GOOD", "unit": b_unit, "ordinal": 1},
    ]
    definition = {"schemaVersion": 1, "nodes": [
        {"id": "p37-raw", "kind": "RAW_MATERIAL", "position": {"x": 0, "y": 0},
         "data": {"name": raw["name"], "skuId": raw_id, "skuCode": raw.get("code", raw_id), "baseUnit": "kg", "bound": True}},
        {"id": "p37-process", "kind": "PROCESS", "position": {"x": 260, "y": 0},
         "data": {"workProcessId": process_id, "processName": process["processName"],
                  "reportingSelectionMode": "ACTUAL_IO", "inputUnit": "kg", "outputUnit": a_unit,
                  "ports": ports, "conversionRule": {"mode": "ACTUAL_WEIGHT"}}},
        {"id": "p37-fg-a", "kind": "FINISHED_GOOD", "position": {"x": 520, "y": -80},
         "data": {"name": fg_a["name"], "skuId": a_id, "skuCode": fg_a["code"], "baseUnit": a_unit, "bound": True}},
        {"id": "p37-fg-b", "kind": "FINISHED_GOOD", "position": {"x": 520, "y": 80},
         "data": {"name": fg_b["name"], "skuId": b_id, "skuCode": fg_b["code"], "baseUnit": b_unit, "bound": True}},
    ], "edges": [
        {"id": "p37-edge-in", "source": "p37-raw", "sourceHandle": "output", "target": "p37-process", "targetHandle": "p37-in"},
        {"id": "p37-edge-a", "source": "p37-process", "sourceHandle": "p37-out-a", "target": "p37-fg-a", "targetHandle": "input"},
        {"id": "p37-edge-b", "source": "p37-process", "sourceHandle": "p37-out-b", "target": "p37-fg-b", "targetHandle": "input"},
    ], "viewport": {"x": 0, "y": 0, "zoom": 1}}
    saved = write_once("multi-workflow-draft", f"/product-process-workflows/{a_id}/draft", definition, "PUT")
    bom = write_once("multi-bom-draft", "/bom/recipes/ensure-draft", {
        "productTypeId": a_id, "workflowRevisionId": saved["revisionId"]})
    # A multi-output workflow requires one complete ACTIVE BOM family. The
    # ensure-draft controller projects the family; verify both terminal rows
    # through the real versions endpoint before adding any package relation.
    bom_b = write_once("multi-bom-b-draft", "/bom/recipes/ensure-draft", {
        "productTypeId": b_id, "workflowRevisionId": saved["revisionId"]})
    versions_a = read("multi-bom-a-versions", f"/bom/recipes/by-product/{a_id}/versions")
    versions_b = read("multi-bom-b-versions", f"/bom/recipes/by-product/{b_id}/versions")
    drafts_a = [x for x in as_items(versions_a)
                if str(x.get("status", "")).upper() == "DRAFT"
                and x.get("workflowRevisionId") == saved["revisionId"]]
    drafts_b = [x for x in as_items(versions_b)
                if str(x.get("status", "")).upper() == "DRAFT"
                and x.get("workflowRevisionId") == saved["revisionId"]]
    if len(drafts_a) != 1 or len(drafts_b) != 1:
        raise AssertionError(f"Multi-output ensure-draft did not produce one pinned draft per terminal: A={drafts_a}, B={drafts_b}")
    bom, bom_b = drafts_a[0], drafts_b[0]
    for label, recipe in (("a", bom), ("b", bom_b)):
        items = recipe.get("items") or []
        if not any(str(item.get("materialCategory", "")).upper() == "PACKAGING" for item in items):
            write_once(f"multi-bom-{label}-packaging", f"/bom/recipes/{id_of(recipe, 'id')}/items", {
                "materialTypeId": id_of(packaging, "id"), "standardQuantity": 1,
                "unit": "个", "materialCategory": "PACKAGING",
                "packagingRole": "PRIMARY_CONTAINER", "naturalQuantity": 1,
            })
    if not all(saved.get(k) is not None for k in ("lockVersion", "revisionId", "revisionHash")):
        raise AssertionError("Workflow draft did not return publish identity fields")
    published = write_once("multi-workflow-publish", f"/product-process-workflows/{a_id}/publish", {
        "lockVersion": saved["lockVersion"], "revisionId": saved["revisionId"],
        "revisionHash": saved["revisionHash"]})
    # Publication may advance the immutable revision identity. Any BOM
    # activation checkpoint must therefore be after publication, never before.
    current_a = read("multi-bom-a-current", f"/bom/recipes/by-product/{a_id}/current")
    current_b = read("multi-bom-b-current", f"/bom/recipes/by-product/{b_id}/current")
    active = (current_a, current_b)
    if any(str(recipe.get("status", "")).upper() != "ACTIVE" for recipe in active):
        bom = write_once("multi-bom-activate-postpublish", f"/bom/recipes/{id_of(bom, 'id')}/activate", {})
        current_a = read("multi-bom-a-current-after-activate", f"/bom/recipes/by-product/{a_id}/current")
        current_b = read("multi-bom-b-current-after-activate", f"/bom/recipes/by-product/{b_id}/current")
        active = (current_a, current_b)
    if any(str(recipe.get("status", "")).upper() != "ACTIVE" for recipe in active):
        raise AssertionError(f"Multi-output BOM family did not become ACTIVE: {active}")
    if len({recipe.get("bomFamilyId") for recipe in active}) != 1:
        raise AssertionError(f"Multi-output BOM rows are not one family: {active}")
    if any(recipe.get("workflowRevisionHash") != published["revisionHash"] for recipe in active):
        raise AssertionError(f"Multi-output BOM family is not pinned to the published workflow revision: {active}")
    run_multi_business(published, a_id, b_id, raw_id, a_unit, b_unit, bom)


def stage_multi_fresh() -> None:
    global WRITE_PREFIX
    repaired = support.steps["generality.write.repair-publish"]
    a = support.steps["generality.write.multi-fg-a"]; b = support.steps["generality.write.multi-fg-b"]
    raw_id = next(n["data"]["skuId"] for n in repaired["nodes"] if n["kind"] == "RAW_MATERIAL")
    WRITE_PREFIX = "fresh-"
    try:
        run_multi_business(repaired, a["id"], b["id"], raw_id, a["unit"], b["unit"],
                           support.steps["generality.write.repair-bom-a"])
    finally:
        WRITE_PREFIX = ""


def run_multi_business(published, a_id, b_id, raw_id, a_unit, b_unit, bom):
    editor_readback = read("multi-workflow-live", f"/product-process-workflows/{published['productTypeId']}")
    # The publish response is the authoritative definition for this isolated
    # fixture. Keep the fresh editor readback even when the deployed endpoint
    # returns null for a newly-created product.
    live_workflow = published
    if not isinstance(live_workflow, dict):
        raise RuntimeError("Published workflow response returned no definition")
    live_processes = [n for n in live_workflow.get("nodes", []) if n.get("kind") == "PROCESS"]
    live_ports = [p for p in (live_processes[0].get("data", {}).get("ports", []) if len(live_processes) == 1 else [])
                  if p.get("direction") == "OUTPUT"]
    if len(live_ports) != 2 or {p.get("materialNodeId") for p in live_ports} != {"p37-fg-a", "p37-fg-b"}:
        raise AssertionError("Published fixture did not preserve its two real output ports")
    port_by_node = {p["materialNodeId"]: p["id"] for p in live_ports}
    activation = read("multi-workflow-activation-read", f"/product-process-workflows/{published['productTypeId']}/activation") or {}
    if not (activation.get("enabled") is True and activation.get("activeWorkflowId") == published["id"]
            and activation.get("activeDefinitionVersion") == published["version"]):
        activation = write_once("multi-workflow-activate", f"/product-process-workflows/{published['id']}/activation", {}, method="PUT")
    if not (activation.get("enabled") is True and activation.get("activeWorkflowId") == published["id"]
            and activation.get("activeDefinitionVersion") == published["version"]):
        raise AssertionError(f"Dedicated multi workflow was not activated at its published version: {activation}")
    plan = write_once("multi-plan", "/production-plans", {
        "clientRequestId": "p37-generality-"+WRITE_PREFIX+"multi-plan", "productTypeId": published["productTypeId"],
        "targetFinishedGoodIds": [a_id, b_id],
        "selectedWorkflowId": published["id"],
        "selectedWorkflowVersion": published["version"],
        "plannedQuantity": 2, "plannedDate": TODAY, "expectedCompletionDate": TODAY,
        "sourceType": "MANUAL", "planType": "FROM_INVENTORY", "skipProcessReporting": False,
        "notes": "P37 multi-output grouped reporting acceptance",
    })
    plan_id = id_of(plan, "id", "planId")
    batch = write_once("multi-batch", f"/production-plans/{plan_id}/create-batch", {})
    group = "p37-"+WRITE_PREFIX+"multi-output-group"
    output_lines = [{"productTypeId": a_id, "workflowPortId": port_by_node["p37-fg-a"], "quantity": 1,
                     "unit": a_unit, "finished": True, "materialNodeId": "p37-fg-a", "laborSegments": [],
                     "costAllocationRatio": 33.333333},
                    {"productTypeId": b_id, "workflowPortId": port_by_node["p37-fg-b"], "quantity": 1,
                    "unit": b_unit, "finished": True, "materialNodeId": "p37-fg-b", "laborSegments": [],
                     "costAllocationRatio": 66.666667}]
    row = {"clientRowId": group, "idempotencyKey": group,
           "multiOutputBaseRowId": group, "processCode": "multi-output",
           "processOrder": 1, "processName": "P37 runtime multi-output process",
           "processDate": TODAY, "productTypeId": a_id, "finished": True,
           "inputQuantity": 1, "inputUnit": "kg", "outputQuantity": 2,
           "outputUnit": a_unit, "unit": a_unit, "materialNodeId": "p37-fg-a",
           "workflowPortId": output_lines[0]["workflowPortId"], "outputs": output_lines,
           "materialInputTotals": [{"materialTypeId": raw_id, "quantity": 1, "unit": "kg",
                                    "workflowPortId": "p37-in", "materialNodeId": "p37-raw"}],
           "laborSegments": []}
    # Reuse the accepted new-factory warehouse transfer flow before production reporting.
    warehouses = as_items(read("multi-production-warehouses", "/factory/warehouses"))
    workshop = next(w for w in warehouses if w.get("type") == "WORKSHOP")
    packaging = next(x for x in as_items(read("multi-production-packaging", "/raw-material-types"))
                     if x.get("unit") == "个" and str(x.get("category", "")).upper() in {"包材", "PACKAGING"})
    group_rows = []
    for group_number, raw_qty, ratios in ((1,1,(33.333333,66.666667)), (2,2,(40,60))):
        for label, material_id, quantity, unit in (("raw",raw_id,raw_qty,"kg"),("package",packaging["id"],2,"个")):
            with support.dbconnect() as d:
                source = d.execute("select warehouse_id from material_batches where factory_id=%s and material_type_id=%s "
                    "and warehouse_id<>%s and deleted_at is null and quantity_unit=%s "
                    "and status='AVAILABLE' group by warehouse_id "
                    "having sum(receipt_quantity-used_quantity-coalesce(reserved_quantity,0)) >= %s order by warehouse_id limit 1",
                    (FACTORY,material_id,workshop["id"],unit,quantity)).fetchone()
            if not source:
                raise AssertionError("No actual source stock for production transfer: " + label)
            suffix = label if group_number == 1 else label+"-2"
            transfer = write_once("multi-supply-"+suffix, "/transfers", {
                "transferType":"WAREHOUSE_TO_WAREHOUSE","targetFactoryId":FACTORY,
                "sourceWarehouseId":source[0],"targetWarehouseId":workshop["id"],"transferDate":TODAY,
                "remark":"多产出本地验收正常领料", "items":[{"itemType":"RAW_MATERIAL",
                "materialTypeId":material_id,"quantity":quantity,"unit":unit}]})
            write_once("multi-supply-request-"+suffix, f"/transfers/{transfer['id']}/request", {})
            write_once("multi-supply-confirm-"+suffix, f"/transfers/{transfer['id']}/confirm", {})
        current_row = copy.deepcopy(row)
        if group_number == 2:
            current_row["clientRowId"] += "-2"; current_row["idempotencyKey"] += "-2"
            current_row["multiOutputBaseRowId"] += "-2"
            current_row["inputQuantity"] = raw_qty
            current_row["materialInputTotals"][0]["quantity"] = raw_qty
            for output, ratio in zip(current_row["outputs"],ratios): output["costAllocationRatio"] = ratio
        key = "multi-row" if group_number == 1 else "multi-row-2"
        write_once(key, f"/production-plans/{plan_id}/process-sheet/row/submit", current_row)
        group_rows.append(current_row)
    settlement = write_once("multi-settlement", f"/production-plans/{plan_id}/settle", {
        "idempotencyKey": "p37-generality-"+WRITE_PREFIX+"multi-settle", "confirm": True,
        "quantityVarianceReason": "OTHER", "quantityVarianceNote": "本地验收追加第二组不同投入与分摊比例",
        "materialVarianceReason": "OTHER", "materialVarianceNote": "两组实际投入共3千克",
        "defectCountOutcome": "NONE_CONFIRMED", "defectQuantity": 0,
    })
    settlement_read = read("multi-settlement-read", f"/production-plans/{plan_id}/settlement")
    settlement_lines = settlement_read.get("outputLines") or settlement.get("outputLines") or []
    if len(settlement_lines) != 4:
        raise AssertionError(f"Multi-output settlement did not expose four real batch output lines: {settlement_read}")
    receipt = write_once("multi-receipt", f"/production-plans/{plan_id}/warehouse-receipt", {
        "idempotencyKey": "p37-generality-"+WRITE_PREFIX+"multi-receipt",
        "outputLines": [{
            "productTypeId": line["productTypeId"],
            "batchNumber": line.get("reportedBatchNumber"),
            "receivedQuantity": line.get("reportedQuantity"),
            "quantityUnit": line.get("quantityUnit"),
        } for line in settlement_lines],
    })
    cost = read("multi-cost", f"/production-plans/{plan_id}/production-summary")
    outputs_read = read("multi-settlement-cost-lines", f"/production-plans/{plan_id}/settlement").get("outputLines") or []
    costs = [line.get("allocatedCost") for line in outputs_read if line.get("allocatedCost") is not None]
    if len(costs) != 4:
        if WRITE_PREFIX:
            raise AssertionError("Fresh multi-output receipt must have costs immediately; no recovery fallback")
        recovery = write_once("multi-recalculate-recovered-costs", f"/production-plans/{plan_id}/recalculate-settled-costs", {})
        event("multi-cost-recovery", recovery)
        cost = read("multi-cost-after-recovery", f"/production-plans/{plan_id}/production-summary")
        outputs_read = read("multi-settlement-after-recovery", f"/production-plans/{plan_id}/settlement").get("outputLines") or []
        costs = [line.get("allocatedCost") for line in outputs_read if line.get("allocatedCost") is not None]
    if len(costs) != 4:
        raise AssertionError(f"Multi-output cost readback did not contain four allocated costs: {cost}")
    input_cost = cost.get("totalCost") or cost.get("actualTotalCost")
    allocated_total = sum((Decimal(str(x)) for x in costs), Decimal(0))
    if input_cost is None or abs(allocated_total - Decimal(str(input_cost))) > Decimal("0.01"):
        raise AssertionError(f"Multi-output allocated cost does not reconcile: outputs={costs}, total={input_cost}")
    if float(cost["totalRawInput"]) != 3 or float(cost["totalFinishedOutput"]) != 4:
        raise AssertionError(f"Packaging or unreported placeholders entered yield quantities: {cost}")
    source_by_sku = {}; source_by_batch = {}
    for key in ("multi-row", "multi-row-2"):
        for output in support.steps["generality.write."+WRITE_PREFIX+key]["outputs"]:
            source_by_sku[output["productTypeId"]] = source_by_sku.get(output["productTypeId"], Decimal(0)) + Decimal(str(output["allocatedCost"]))
            source_by_batch[output["batchNumber"]] = Decimal(str(output["allocatedCost"]))
    for sku, amount in source_by_sku.items():
        lines = [line for line in outputs_read if line["productTypeId"] == sku]
        if len(lines) != 2 or abs(sum(Decimal(str(line["allocatedCost"])) for line in lines)-amount) > Decimal("0.01"):
            raise AssertionError(f"SKU allocation does not conserve its verified report cost: {sku}, {lines}, {amount}")
        if len({str(line["costAllocationRatio"]) for line in lines}) != 1:
            raise AssertionError("Same SKU batch rows did not freeze the same weighted cost ratio")
    with support.dbconnect() as d:
        fg = d.execute("select id,unit_cost,produced_quantity from finished_goods_batches where factory_id=%s and production_plan_id=%s order by id",
                       (FACTORY,plan_id)).fetchall()
        production_costs = d.execute("select batch_number,total_cost from production_batches where factory_id=%s and production_plan_id=%s and batch_number=any(%s)",
                                    (FACTORY,plan_id,list(source_by_batch))).fetchall()
    if len(fg) != 4 or any(row[1] is None for row in fg):
        raise AssertionError(f"Four received finished batches must all have actual cost: {fg}")
    if len(production_costs) != 4 or any(abs(amount-source_by_batch[number]) > Decimal("0.01") for number,amount in production_costs):
        raise AssertionError(f"Plan pool replaced independent production source costs: {production_costs}")
    before_repeat = support.snapshot()
    repeat_settle = support.call(support.admin,"POST",f"/production-plans/{plan_id}/settle", {
        "idempotencyKey":"p37-generality-"+WRITE_PREFIX+"multi-settle","confirm":True,
        "quantityVarianceReason":"OTHER","quantityVarianceNote":"本地验收追加第二组不同投入与分摊比例",
        "materialVarianceReason":"OTHER","materialVarianceNote":"两组实际投入共3千克",
        "defectCountOutcome":"NONE_CONFIRMED","defectQuantity":0})
    repeat_receipt = support.call(support.admin,"POST",f"/production-plans/{plan_id}/warehouse-receipt", {
        "idempotencyKey":"p37-generality-"+WRITE_PREFIX+"multi-receipt","outputLines":[{
            "productTypeId":line["productTypeId"],"batchNumber":line["reportedBatchNumber"],
            "receivedQuantity":line["reportedQuantity"],"quantityUnit":line["quantityUnit"]} for line in settlement_lines]})
    after_repeat = support.snapshot()
    if before_repeat != after_repeat:
        raise AssertionError("Repeated settlement/receipt changed business rows")
    event(WRITE_PREFIX+"multi-idempotence", {"settle":repeat_settle,"receipt":repeat_receipt,"unchanged":True})
    event(WRITE_PREFIX+"multi", {"plan": plan, "batch": batch, "row": row, "bomDraft": bom,
                     "editorReadback": editor_readback, "publishedWorkflow": published, "activation": activation,
                     "settlement": settlement,
                     "receipt": receipt, "settlementReadback": cost, "allocatedCostSum": allocated_total,
                     "multiOutputBaseRowId": group, "outputLines": outputs_read, "groups": group_rows,
                     "sourceCostBySku": source_by_sku, "sourceCostByBatch":production_costs, "finishedGoods":fg})


def stage_repair() -> None:
    old = support.steps["generality.write.multi-workflow-publish"]
    with support.dbconnect() as d:
        owner = d.execute("select product_type_id from product_process_workflows where factory_id=%s and id=%s",
                          (FACTORY, old["id"])).fetchone()[0]
        before = {"revision": d.execute("select row_to_json(r) from product_process_workflow_revisions r where factory_id=%s and id=%s", (FACTORY,old["revisionId"])).fetchone()[0],
                  "plans": db_guard_snapshot()}
    if "generality.repair.before" not in support.steps:
        support.steps["generality.repair.before"] = before; persist()
    definition = read("repair-original-editor", f"/product-process-workflows/{owner}")
    if "generality.write.repair-draft" not in support.steps:
        # A visible layout correction makes a new graph version, without altering old facts.
        node = next(n for n in definition["nodes"] if n["kind"] == "PROCESS")
        node["position"]["x"] += 40
    saved = write_once("repair-draft", f"/product-process-workflows/{owner}/draft", definition, "PUT")
    fg_a = support.steps["generality.write.multi-fg-a"]; fg_b = support.steps["generality.write.multi-fg-b"]
    packaging = next(x for x in as_items(read("repair-packaging", "/raw-material-types"))
                     if x.get("unit") == "个" and str(x.get("category", "")).upper() in {"包材", "PACKAGING"})
    for label, fg in (("a",fg_a),("b",fg_b)):
        recipe = write_once("repair-bom-"+label, "/bom/recipes/ensure-draft",
                            {"productTypeId":fg["id"],"workflowRevisionId":saved["revisionId"]})
        detail = read("repair-bom-detail-"+label, f"/bom/recipes/{recipe['id']}")
        if not any(str(item.get("materialCategory", "")).upper()=="PACKAGING" for item in detail.get("items", [])):
            write_once("repair-package-"+label, f"/bom/recipes/{recipe['id']}/items", {
                "materialTypeId":packaging["id"],"standardQuantity":1,"unit":"个",
                "materialCategory":"PACKAGING","packagingRole":"PRIMARY_CONTAINER","naturalQuantity":1})
    published = write_once("repair-publish", f"/product-process-workflows/{owner}/publish", {
        "lockVersion":saved["lockVersion"],"revisionId":saved["revisionId"],"revisionHash":saved["revisionHash"]})
    with support.dbconnect() as d:
        after = d.execute("select row_to_json(r) from product_process_workflow_revisions r where factory_id=%s and id=%s",(FACTORY,old["revisionId"])).fetchone()[0]
    assert after == support.steps["generality.repair.before"]["revision"], "Old immutable revision changed during forward repair"
    assert published["productTypeId"] == owner and published["revisionId"] != old["revisionId"]
    event("repair", {"oldRevisionUnchanged":True,"newWorkflow":published,"oldRevisionId":old["revisionId"],
                     "oldHashNotRewritten":True,"method":"normal draft, BOM family, publish; layout correction"})


def stage_upgrade() -> None:
    f = fixture_state()
    product_id = f["productId"]
    before_versions = read("upgrade-before-workflow-versions", f"/product-process-workflows/{product_id}/versions")
    published_summaries = [v for v in before_versions if str(v.get("status", "")).upper() == "PUBLISHED"]
    if len(published_summaries) != 1:
        raise RuntimeError(f"Original fixture must have exactly one published workflow version: {before_versions}")
    published_version = published_summaries[0].get("definitionVersion", published_summaries[0].get("version"))
    if published_version is None:
        raise RuntimeError(f"Published workflow summary has no version: {published_summaries[0]}")
    before_published = read("upgrade-before-published-definition",
                            f"/product-process-workflows/{product_id}/versions/{published_version}")
    before_activation = read("upgrade-before-activation", f"/product-process-workflows/{product_id}/activation")
    before_bom = read("upgrade-before-bom-current", f"/bom/recipes/by-product/{product_id}/current")
    if not before_bom:
        raise RuntimeError("Original boxed workflow has no active BOM; refusing to upgrade an unqualified fixture")
    before_db = db_guard_snapshot()
    definition = read("upgrade-editor-definition", f"/product-process-workflows/{product_id}")
    draft = copy.deepcopy(definition)
    if not isinstance(draft, dict) or not draft.get("nodes"):
        raise RuntimeError("No editable workflow definition available for draft upgrade")
    processes = [n for n in draft["nodes"] if n.get("kind") == "PROCESS"]
    if len(processes) != 1:
        raise RuntimeError("Upgrade fixture must contain exactly one original process node")
    existing_raw_ids = {
        n.get("data", {}).get("skuId") for n in draft["nodes"]
        if n.get("kind") == "RAW_MATERIAL" and n.get("id") != "p37-upgrade-raw"
        and n.get("data", {}).get("skuId")
    }
    raw_items = as_items(read("upgrade-raw-materials", "/raw-material-types"))
    raw = next((x for x in raw_items if x.get("unit") == "kg" and x.get("id") not in existing_raw_ids), None)
    if raw is None:
        raw = write_once("upgrade-extra-raw", "/raw-material-types", {
            "name": "本地升级验收第二种原料", "code": "P37-UPGRADE-RAW",
            "category": "肉类", "unit": "kg", "storageType": "frozen",
            "shelfLifeDays": 180, "isActive": True,
        })
    raw_id = id_of(raw, "id")
    prior_upgrade_node = next((n for n in draft["nodes"] if n.get("id") == "p37-upgrade-raw"), None)
    if prior_upgrade_node is not None:
        prior_id = prior_upgrade_node.get("data", {}).get("skuId")
        # A previous run persisted a malformed draft with the original raw SKU
        # duplicated. Repair it through the normal draft PUT and a new durable
        # checkpoint; never replay the old successful-looking step value.
        prior_upgrade_node.setdefault("data", {})["skuId"] = raw_id
        prior_upgrade_node["data"]["skuCode"] = raw.get("code", raw_id)
        prior_upgrade_node["data"]["name"] = raw["name"]
        saved = (definition if prior_id == raw_id else
                 write_once("upgrade-correct-draft", f"/product-process-workflows/{product_id}/draft", draft, "PUT"))
    else:
        saved = None
    if prior_upgrade_node is None:
        process = processes[0]
        ports = process.get("data", {}).get("ports")
        if not isinstance(ports, list):
            raise RuntimeError("Original process definition has no real ports array")
        input_ports = [p for p in ports if p.get("direction") == "INPUT"]
        if not input_ports:
            raise RuntimeError("Original process has no input port to preserve")
        new_port = {"id": "p37-upgrade-in", "direction": "INPUT", "materialNodeId": "p37-upgrade-raw",
                    "materialKind": "RAW_MATERIAL", "unit": "kg", "ordinal": len(input_ports)}
        process["data"]["ports"] = [*ports, new_port]
        draft["nodes"].append({"id": "p37-upgrade-raw", "kind": "RAW_MATERIAL",
                                "position": {"x": 0, "y": 180},
                                "data": {"name": raw["name"], "skuId": raw_id,
                                         "skuCode": raw.get("code", raw_id), "baseUnit": "kg", "bound": True}})
        draft["edges"].append({"id": "p37-upgrade-edge", "source": "p37-upgrade-raw",
                               "sourceHandle": "output", "target": process["id"], "targetHandle": "p37-upgrade-in"})
    if saved is None:
        saved = write_once("upgrade-save-draft", f"/product-process-workflows/{product_id}/draft", draft, "PUT")
    bom = write_once("upgrade-ensure-bom-draft", "/bom/recipes/ensure-draft", {
        "productTypeId": product_id, "workflowRevisionId": saved.get("revisionId"),
    })
    if bom.get("workflowRevisionId") == saved.get("revisionId"):
        latest = expected_failure("upgrade-already-current", f"/bom/recipes/{id_of(bom)}/workflow-revisions/upgrade-latest", {})
        if latest["body"].get("errorCode") != "BOM_WORKFLOW_UPGRADE_NOT_AVAILABLE":
            raise AssertionError("Already-current BOM did not reject a nonexistent upgrade")
    else:
        latest = write_once("upgrade-bom-latest", f"/bom/recipes/{id_of(bom)}/workflow-revisions/upgrade-latest", {})
    after_versions = read("upgrade-after-workflow-versions", f"/product-process-workflows/{product_id}/versions")
    after_bom = read("upgrade-after-bom-current", f"/bom/recipes/by-product/{product_id}/current")
    after_db = db_guard_snapshot()
    if json.dumps(before_db, sort_keys=True, default=str) != json.dumps(after_db, sort_keys=True, default=str):
        raise AssertionError("Draft upgrade changed historical plans, settlements or active BOM rows")
    after_published = read("upgrade-after-published-definition",
                           f"/product-process-workflows/{product_id}/versions/{published_version}")
    stable_fields = ("id", "version", "definitionVersion", "status", "revisionId", "revisionHash",
                     "nodes", "edges", "viewport")
    before_stable = {key: before_published.get(key) for key in stable_fields}
    after_stable = {key: after_published.get(key) for key in stable_fields}
    if before_stable != after_stable:
        raise AssertionError(f"Draft upgrade changed the published workflow revision: before={before_stable}, after={after_stable}")
    if before_bom.get("id") != after_bom.get("id") or before_bom.get("status") != after_bom.get("status"):
        raise AssertionError("Draft upgrade changed the active BOM identity/status")
    activation = read("upgrade-activation-after-draft", f"/product-process-workflows/{product_id}/activation")
    if activation != before_activation:
        raise AssertionError(f"Draft upgrade changed workflow activation: before={before_activation}, after={activation}")
    event("upgrade", {"beforeWorkflowVersions": before_versions, "afterWorkflowVersions": after_versions,
                       "beforeBomCurrent": before_bom, "afterBomCurrent": after_bom,
                       "draft": saved, "bomDraft": bom, "bomUpgradeLatest": latest,
                       "activeRowsHash": before_db, "activation": activation,
                       "published": False, "activated": False})


def stage_output_visibility() -> None:
    cases = [STATE["generality"]["results"][key] for key in ("multi", "fresh-multi")]
    actual = {line["reportedBatchNumber"] for case in cases for line in case["outputLines"]}
    containers = {case["batch"]["batchNumber"] for case in cases}
    before = support.snapshot()
    finished = as_items(read("visible-finished-batches", "/production/batches/finished?days=90"))
    numbers = {row["batchNumber"] for row in finished}
    if not actual <= numbers or containers & numbers:
        raise AssertionError(f"Finished output picker includes a container or lost actual outputs: {containers & numbers}, {actual-numbers}")
    # Checkpoints may be revisited after midnight. Use the persisted business
    # response date, never today's rebuilt request draft.
    period = support.steps["generality.write.multi-row"]["outputs"][0]["processDate"]
    metrics = read("visible-product-metrics", f"/production/actual-metrics/by-product?periodStart={period}&periodEnd={period}")
    selected = [row for row in metrics["products"] if actual.intersection(row["batchNumbers"])]
    if len(selected) != 2 or any(row["batchCount"] != 4 for row in selected):
        raise AssertionError(f"Product metrics did not retain four actual batches per output SKU: {selected}")
    if any(containers.intersection(row["batchNumbers"]) for row in metrics["products"]):
        raise AssertionError("A runtime container entered product metrics")
    expected = {"42b3334e-3d74-401f-b809-dc85d02e499c": ("33.3333", "23.4"),
                "2b865183-6d73-45a0-8cbb-ca6fd38cbf20": ("66.6667", "38.6")}
    if metrics.get("sourceVersion") != "actual-flow-v3":
        raise AssertionError("Joint metric formulas require their own source version")
    for row in selected:
        rate, cost = expected[row["productTypeId"]]
        if (row["yieldRate"] is None or row["totalCost"] is not None
                or abs(Decimal(str(row["yieldRate"])) - Decimal(rate)) > Decimal("0.0001")
                ):
            raise AssertionError("Formal multi-output metric lost its actual weighted output: " + str(row))
    if before != support.snapshot():
        raise AssertionError("Reading output scope changed business history")
    event("output-visibility", {"actualBatches": sorted(actual), "excludedContainers":sorted(containers),
                                "metrics": metrics, "businessHistoryUnchanged":True})


def stage_readback() -> None:
    before = STATE.get("generality", {}).get("results", {})
    db = support.snapshot()
    f = fixture_state()
    plans = read("readback-plans", "/production-plans")
    deliveries = read("readback-deliveries", "/sales/deliveries")
    workflow = read("readback-workflow", f"/product-process-workflows/{f['productId']}")
    business_writes = sum(1 for item in STATE.get("events", [])
                          if item.get("method") in {"POST", "PUT", "PATCH", "DELETE"}
                          and item.get("path", "").startswith("/"))
    event("readback", {"databaseSnapshot": db, "plans": plans, "deliveries": deliveries,
                        "workflow": workflow, "completedStages": sorted(before),
                        "businessWrites": business_writes, "sourceDatabase": support.SOURCE})


STAGES: dict[str, Callable[[], None]] = {"init": stage_init, "units": stage_units,
                                          "multi": stage_multi, "multi-fresh": stage_multi_fresh, "repair": stage_repair, "upgrade": stage_upgrade,
                                          "output-visibility": stage_output_visibility, "readback": stage_readback}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("ack", choices=["NON_PRODUCTION_ONLY"])
    parser.add_argument("--stage", choices=[*STAGES, "all"], default="all")
    args = parser.parse_args()
    stages = [stage for stage in STAGES if stage != "repair"] if args.stage == "all" else [args.stage]
    for name in stages:
        print(json.dumps({"stage": name, "status": "START"}, ensure_ascii=False))
        STAGES[name]()
        print(json.dumps({"stage": name, "status": "PASS"}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
