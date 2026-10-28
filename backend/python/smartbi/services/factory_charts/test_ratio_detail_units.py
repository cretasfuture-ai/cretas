import pandas as pd

from smartbi.api.financial_dashboard import FactoryRecommendationRequest
from smartbi.services.factory_charts import detail_store, details
from smartbi.services.factory_charts.engine import Dataset, build


def request(**kwargs):
    return FactoryRecommendationRequest(use_ai=False, include_optional=True, **kwargs)


def _mixed_unit_dataset():
    return Dataset(pd.DataFrame([
        {"日期": "2026-10-01", "产品": "成品-kg", "单位": "kg",
         "产量": 100, "计划产量": 120, "良品数": 90},
        {"日期": "2026-10-01", "产品": "成品-box", "单位": "box",
         "产量": 10, "计划产量": 20, "良品数": 8},
    ]), "production · 混合单位质量率与计划达成率")


def test_ratio_candidates_bind_source_unit_before_metadata_and_detail_freeze():
    dataset = _mixed_unit_dataset()
    candidates, gaps = build([dataset], request(period_type="all"), True)

    quality = {
        item["engine"]["sourceQuantityUnit"]: item for item in candidates
        if item["id"].split("@", 1)[0].endswith("production.quality")
    }
    plan_rate = {
        item["engine"]["sourceQuantityUnit"]: item
        for item in candidates
        if item["id"].split("@", 1)[0].endswith("production.plan-rate")
        and item["chart"]["chartType"] == "gauge"
    }
    assert set(quality) == {"kg", "box"}
    assert {item["unit"] for item in quality.values()} == {"%"}
    assert set(plan_rate) == {"kg", "box"}
    assert {item["engine"]["sourceQuantityUnit"] for item in plan_rate.values()} == {"kg", "box"}

    selected = list(quality.values()) + list(plan_rate.values())
    owner = ("F006", 1309, "factory_super_admin")
    metadata = detail_store.prepare_shared_metadata(selected, [dataset], request(period_type="all"), owner, True)
    assert all(item.get("detailSnapshotKey") for item in metadata)

    details.clear_cache()
    try:
        registered = details.register(selected, [dataset], request(period_type="all"), owner, True)
        assert all(item.get("detailContext", {}).get("id") for item in registered)
        for item in registered:
            expected_unit = item["engine"]["sourceQuantityUnit"]
            result = details.page(
                {"context_id": item["detailContext"]["id"], "page": 1, "page_size": 20},
                owner, True, True,
            )
            rows = result["data"]["rows"]
            assert rows
            assert {str(row["单位"]).strip() for row in rows} == {expected_unit}
            assert len(rows) == 1
    finally:
        details.clear_cache()
