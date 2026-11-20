from datetime import datetime, timedelta, timezone

import pytest
import os,json,base64

from blacklake.labor_supplements import Batch, Segment, TaskSupplement, attach, catalog, status, store


@pytest.fixture(autouse=True)
def platform_test_codec(monkeypatch):
    if os.name!='nt':
        # A deterministic fixture only; runtime has no plaintext/non-Windows fallback.
        import blacklake.labor_supplements as module
        monkeypatch.setattr(module,'_seal',lambda value:base64.b64encode(json.dumps(value).encode()).decode())
        monkeypatch.setattr(module,'_unseal',lambda value:json.loads(base64.b64decode(value)))


def analysis(wages=(100, 50)):
    return {"runId": "run-1", "cost": {"projects": [
        {"projectId": 1, "projectCode": "P1", "product": "A", "laborCost": 150, "processes": [
            {"taskId": 11, "process": "切配", "laborCost": wages[0], "laborEvidence": {"source": "x"}},
            {"taskId": 12, "process": "包装", "laborCost": wages[1], "laborEvidence": {"source": "x"}},
        ]},
        {"projectId": 2, "projectCode": "P2", "product": "B", "laborCost": 200, "processes": [
            {"taskId": 21, "process": "切配", "laborCost": 200, "laborEvidence": {"source": "x"}},
        ]},
    ]}}


def entry(task, project=1, worker="anonymous-1", start=None, **kw):
    start = start or datetime(2026, 1, 1, tzinfo=timezone.utc)
    return TaskSupplement(projectId=project, taskId=task, segments=[Segment(start=start, end=start + timedelta(hours=2), workers=[worker])], confirmed=True, source="manual", reference="shift", **kw)


def cfg(tmp_path):
    return {"tenant_id": "t1", "source_factory_id": "f1", "run_id": "run-1", "labor_supplements_path": str(tmp_path / "supp.sqlite")}


def test_validation_and_catalog_require_timezone_and_task_id():
    with pytest.raises(ValueError): Segment(start=datetime(2026, 1, 1), end=datetime(2026, 1, 1, 1), workers=["x"])
    with pytest.raises(ValueError): TaskSupplement(projectId=1, taskId=1, confirmed=False, source="manual", reference="x")
    assert catalog(analysis())[0]["signature"]


def test_store_status_attach_and_no_worker_id_leak(tmp_path):
    c = cfg(tmp_path); a = analysis()
    result = store(c, "run-1", [entry(11), entry(12, worker="anonymous-2")], a, "operator")
    assert result["revision"] == "1"
    out = status(c, a)
    assert out["summary"]["complete"] is False  # project 2 remains unconfirmed
    assert out["tasks"][0]["participants"] == 1
    assert "anonymous-1" not in str(out) and "worker" not in str(out)
    assert attach(a, c)["laborSupplements"] == out


def test_overlap_revision_stale_and_tenant_isolation(tmp_path):
    c = cfg(tmp_path); a = analysis()
    store(c, "run-1", [entry(11)], a, "operator")
    store(c, "run-1", [entry(12, worker="anonymous-2")], a, "operator")
    with pytest.raises(ValueError, match="WORKER_SEGMENT_OVERLAP"):
        store(c, "run-1", [entry(12, start=datetime(2026, 1, 1, 1, tzinfo=timezone.utc), expectedRevision=1)], a, "operator")
    changed = analysis((999, 50))
    assert status(c, changed)["tasks"][0]["status"] == "stale"
    other = {**c, "tenant_id": "other"}
    assert status(other, a)["summary"]["complete"] is False


def test_zero_confirmation_is_valid_but_not_a_guessed_person(tmp_path):
    c = cfg(tmp_path); a = analysis()
    zero = TaskSupplement(projectId=1, taskId=11, segments=[], confirmed=True, zeroConfirmed=True, source="excel", reference="zero")
    store(c, "run-1", [zero], a, "operator")
    row = status(c, a)["tasks"][0]
    assert row["status"] == "ready" and row["participants"] is None and row["personHours"] == 0


def test_complete_orders_allocate_independently_and_keep_roster_dedup(tmp_path):
    c = cfg(tmp_path); a = analysis()
    base=datetime(2026, 1, 1, tzinfo=timezone.utc)
    entries=[entry(11, worker="w1", start=base), entry(12, worker="w2", start=base+timedelta(hours=3)),
             entry(21, project=2, worker="w1", start=base+timedelta(hours=6))]
    # Task 11/12 are a 2h + 1h order; project 2 is an independent 2h order.
    entries[1]=entry(12, worker="w2", start=base+timedelta(hours=3))
    store(c, "run-1", entries, a, "operator")
    out=status(c,a)["summary"]
    assert out["complete"] is True
    orders={x["projectId"]:x for x in out["orders"]}
    assert orders[1]["total"] == 150 and orders[2]["total"] == 200
    assert orders[1]["rosterComplete"] and orders[1]["participants"] == 2
    assert sum(x["allocated"] for x in out["processes"]) == 350
