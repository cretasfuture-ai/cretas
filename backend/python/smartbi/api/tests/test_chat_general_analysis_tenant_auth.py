from __future__ import annotations

import asyncio
from datetime import date
from types import SimpleNamespace

import pytest
from fastapi import HTTPException

import smartbi.api.chat as chat


def _http_request(factory_id="F006"):
    return SimpleNamespace(state=SimpleNamespace(
        factory_id=factory_id,
        role="factory_super_admin",
        user_id=9,
    ))


def test_general_analysis_context_cannot_override_authenticated_tenant():
    request = chat.GeneralAnalysisRequest(
        query="工厂毛利",
        context={"factory_id": "F_OTHER", "store_name": "人民路店"},
    )

    with pytest.raises(HTTPException) as exc_info:
        asyncio.run(chat.general_analysis(request, _http_request("F006")))

    assert exc_info.value.status_code == 422
    assert "analysis context field is not supported" in str(exc_info.value.detail)


@pytest.mark.parametrize("context", [
    {"start_date": "2026/07/20", "end_date": "2026-07-21"},
    {"start_date": "2026-07-22"},
    {"start_date": "2026-07-22", "end_date": "2026-07-21"},
    {"comparison_start_date": "2026-07-20", "comparison_end_date": "2026-07-21"},
    {"store_id": "S" * 65},
    {"store_name": "门店" * 81},
    {
        "start_date": "2025-12-30", "end_date": "2026-12-31",
        "time_anchor_date": "2026-12-31",
    },
    {
        "start_date": "2026-07-20", "end_date": "2026-07-21",
        "comparison_start_date": "2025-01-01", "comparison_end_date": "2026-01-02",
        "time_anchor_date": "2026-07-22",
    },
    {
        "start_date": "2026-07-10", "end_date": "2026-07-20",
        "comparison_start_date": "2026-07-20", "comparison_end_date": "2026-07-21",
        "time_anchor_date": "2026-07-22",
    },
    {
        "start_date": "2026-07-20", "end_date": "2026-07-21",
        "comparison_start_date": "2026-07-18", "comparison_end_date": "2026-07-19",
    },
    {
        "start_date": "2026-07-20", "end_date": "2026-07-23",
        "time_anchor_date": "2026-07-22",
    },
    {
        "start_date": "2024-07-21", "end_date": "2024-07-22",
        "time_anchor_date": "2026-07-22",
    },
])
def test_factory_analysis_context_rejects_invalid_or_unbounded_values(context):
    with pytest.raises(HTTPException) as exc_info:
        chat._validated_analysis_context(context)
    assert exc_info.value.status_code == 422


def test_factory_analysis_context_accepts_disjoint_ranges_at_two_year_boundary():
    validated = chat._validated_analysis_context({
        "start_date": "2025-07-23",
        "end_date": "2026-07-22",
        "comparison_start_date": "2024-07-22",
        "comparison_end_date": "2025-07-22",
        "time_anchor_date": "2026-07-22",
    })

    assert validated["start_date"] == date(2025, 7, 23)
    assert validated["comparison_start_date"] == date(2024, 7, 22)
    assert validated["time_anchor_date"] == date(2026, 7, 22)


def test_factory_analysis_context_allows_time_anchor_without_date_ranges():
    validated = chat._validated_analysis_context(
        {"time_anchor_date": "2026-07-22"},
    )

    assert validated == {"time_anchor_date": date(2026, 7, 22)}


def test_factory_context_does_not_allow_tenant_or_legacy_domain_overrides():
    for context in ({"factory_id": "F_OTHER"}, {"table_type": "restaurant_ops"}):
        with pytest.raises(HTTPException) as exc_info:
            chat._validated_analysis_context(context)
        assert exc_info.value.status_code == 422
