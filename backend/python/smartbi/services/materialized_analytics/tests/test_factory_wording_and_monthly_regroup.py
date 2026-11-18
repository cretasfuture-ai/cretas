"""LSM-COSTBI 第 9/10 项: 工厂数据不出现餐饮口吻; 问「月度/按月/每月」按月重聚合; 图标题不露 D/W/M。"""
from __future__ import annotations

import json
from datetime import date, timedelta

import pytest

from smartbi.services.materialized_analytics import llm_materializer as lm
from smartbi.services.materialized_analytics.compute.polars_backend import PolarsBackend
from smartbi.services.materialized_analytics.query_router import format_cached_as_sse
from smartbi.services.materialized_analytics.schema import DataSchema, Domain, Field, FieldRole
from smartbi.services.materialized_analytics.templates.base import TemplateResult
from smartbi.services.materialized_analytics.templates.monthly_trend import MonthlyTrend

RESTAURANT_WORDS = ("套餐", "会员", "门店", "店长", "促销")


def _schema(domain):
    return DataSchema(
        upload_id=1, factory_id="F001", domain=domain,
        fields=(Field("date", FieldRole.TIME, "datetime"), Field("产量", FieldRole.MEASURE, "float")),
        row_count=4, primary_measure="产量", time_field="date",
    )


def _rows(values, start=date(2026, 1, 1)):
    return [{"date": (start + timedelta(days=i)).isoformat(), "产量": float(v)} for i, v in enumerate(values)]


@pytest.mark.parametrize("domain", [Domain.UNKNOWN, Domain.PRODUCTION])
@pytest.mark.parametrize("values", [[100, 1000, 900, 950], [900, 1000, 950, 980]])
def test_factory_trend_has_no_restaurant_wording(domain, values):
    # 谷值低于峰值 ≥30% 分支 (旧文案「定向促销 + 套餐 + 会员唤回」) 与 <30% 分支都查
    r = MonthlyTrend().run(PolarsBackend.from_rows(_rows(values)), _schema(domain))
    assert r.applies
    hits = [w for w in RESTAURANT_WORDS if w in r.insight_text]
    assert hits == [], (values, hits, r.insight_text)


def test_restaurant_history_keeps_its_wording():
    # 阳性对照: 历史餐饮上传仍是原文案, 说明上面断言守的是分支而不是删光了文案
    r = MonthlyTrend().run(PolarsBackend.from_rows(_rows([100, 1000, 900, 950])), _schema(Domain.RESTAURANT))
    assert "套餐" in r.insight_text


def test_chart_title_uses_chinese_frequency():
    r = MonthlyTrend().run(PolarsBackend.from_rows(_rows([1, 2, 3])), _schema(Domain.UNKNOWN))
    text = r.chart_config["title"]["text"]
    assert "(按日)" in text and "(D)" not in text


def _cached(series, freq, agg="sum"):
    return {
        "code": "monthly_trend", "title": "时间趋势",
        "data": {"series": series, "freq": freq, "aggregation": agg},
        "kpis": {"period_count": len(series)},
        "insight_text": "规则洞察",
        "chart_config": {
            "title": {"text": f"产量 时间趋势 ({freq})"},
            "xAxis": {"type": "category", "data": [s["period"] for s in series]},
            "series": [{"name": "产量", "type": "line", "data": [s["total"] for s in series]}],
        },
    }


DAILY = [
    {"period": "2026-01-30", "total": 10.0},
    {"period": "2026-01-31", "total": 20.0},
    {"period": "2026-02-01", "total": 5.0},
    {"period": "2026-02-02", "total": 7.0},
    {"period": "2026-03-01", "total": 1.0},
]


@pytest.mark.parametrize("q", ["月度趋势", "按月看产量趋势", "每月产量走势", "每月产量变化"])
def test_monthly_question_routes_to_trend_template(q):
    from smartbi.services.materialized_analytics.query_router import match_template
    assert match_template(q) == "monthly_trend", q


@pytest.mark.parametrize("q", ["月度趋势", "按月看产量趋势", "每月产量走势"])
def test_monthly_question_regroups_sum_series_by_month(q):
    out = format_cached_as_sse(_cached(DAILY, "D"), q)
    assert [s["period"] for s in out["data"]["series"]] == ["2026-01", "2026-02", "2026-03"]
    assert [s["total"] for s in out["data"]["series"]] == [30.0, 12.0, 1.0]
    opt = out["charts"][0]["option"]
    assert opt["xAxis"]["data"] == ["2026-01", "2026-02", "2026-03"]
    assert opt["series"][0]["data"] == [30.0, 12.0, 1.0]
    assert "(按月)" in opt["title"]["text"]
    assert "2026-01 30" in out["answer"]


def test_non_monthly_question_keeps_daily_series_but_translates_title():
    out = format_cached_as_sse(_cached(DAILY, "D"), "产量趋势")
    assert len(out["data"]["series"]) == 5
    assert out["charts"][0]["option"]["title"]["text"] == "产量 时间趋势 (按日)"


def test_avg_measure_is_not_summed_and_says_so():
    out = format_cached_as_sse(_cached(DAILY, "D", agg="avg"), "月度趋势")
    assert len(out["data"]["series"]) == 5  # 没有被相加
    assert "平均值" in out["answer"] and "不能" in out["answer"]


def test_weekly_regroup_explains_cross_month_weeks():
    weekly = [{"period": "2026-01-26", "total": 70.0}, {"period": "2026-02-02", "total": 30.0}]
    out = format_cached_as_sse(_cached(weekly, "W"), "月度趋势")
    assert [s["total"] for s in out["data"]["series"]] == [70.0, 30.0]
    assert "跨月" in out["answer"]


# ---- LLM 洞察 ----

@pytest.fixture
def _api_key(monkeypatch):
    class _S:
        llm_api_key = "test-key"
        llm_insight_model = "x"
    monkeypatch.setattr("config.get_settings", lambda: _S())

    async def _no_pool():
        return None
    monkeypatch.setattr("smartbi.config.get_pg_pool", _no_pool)


def _results():
    return [TemplateResult(code="monthly_trend", title="趋势", data={}, kpis={"peak_value": 1}, insight_text="rule")]


def test_system_role_by_domain():
    factory = lm.system_role_for("unknown")
    assert "食品工厂" in factory and "门店老板" not in factory and "店长一看就懂" not in factory
    assert "餐饮经营" in lm.system_role_for("restaurant")
    assert "店长" in lm._build_prompt([], "restaurant")
    assert "店长" not in lm._build_prompt([], "unknown")


@pytest.mark.asyncio
async def test_factory_llm_output_with_restaurant_words_is_dropped(monkeypatch, _api_key):
    seen = {}

    async def fake(prompt, system_role=None, **kw):
        seen["role"] = system_role
        return json.dumps({"executive_summary": "建议推套餐拉会员复购。",
                           "insights": {"monthly_trend": "谷值月建议门店做套餐促销。"}}, ensure_ascii=False)
    monkeypatch.setattr("smartbi.services.insights.llm_client.call_llm", fake)
    results = _results()
    summary = await lm.generate_llm_insights(results, "unknown")
    assert "食品工厂" in seen["role"]
    assert summary is None
    assert results[0].llm_insight is None  # 回落到规则洞察


@pytest.mark.asyncio
async def test_factory_llm_output_without_restaurant_words_is_kept(monkeypatch, _api_key):
    async def fake(prompt, system_role=None, **kw):
        return json.dumps({"executive_summary": "二月产量偏低, 主要是原料到货晚。",
                           "insights": {"monthly_trend": "二月停线检修, 建议三月均衡排产。"}}, ensure_ascii=False)
    monkeypatch.setattr("smartbi.services.insights.llm_client.call_llm", fake)
    results = _results()
    summary = await lm.generate_llm_insights(results, "unknown")
    assert summary and "原料" in summary
    assert results[0].llm_insight and "排产" in results[0].llm_insight
