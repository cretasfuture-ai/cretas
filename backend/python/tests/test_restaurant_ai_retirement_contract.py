"""Contract tests for the retired restaurant-specific AI namespace."""

from datetime import date

import pytest


def test_restaurant_runtime_catalog_is_not_model_visible():
    from smartbi.agent.runtime.descriptors import restaurant_descriptors
    from smartbi.agent.runtime.restaurant_read_tools import build_restaurant_read_registry

    assert restaurant_descriptors() == ()
    assert build_restaurant_read_registry().names() == ()


def test_restaurant_ops_dispatch_fails_closed():
    from smartbi.gold.restaurant import restaurant_ops_router as router

    assert router.match_restaurant_ops("本月营收趋势") is None
    assert router.is_supported_restaurant_ops_code("RESTAURANT_OPS_SALES_SUMMARY") is False
    assert router.reconcile_restaurant_ops_code(
        "本月营收", "RESTAURANT_OPS_SALES_SUMMARY"
    ) is None


@pytest.mark.asyncio
async def test_restaurant_intent_and_resolver_cannot_execute():
    from smartbi.gold.restaurant import restaurant_intent
    from smartbi.gold.restaurant import restaurant_ops_router as router

    assert restaurant_intent._VALID_CODES == frozenset()
    assert await restaurant_intent.parse_restaurant_query(
        "本月营收", None, factory_id="QHJ001"
    ) is None
    assert await router.resolve_by_code(
        "RESTAURANT_OPS_SALES_SUMMARY", None, "QHJ001", role="owner"
    ) is None


def test_retired_public_registry_does_not_disable_explicit_entity_helper():
    from smartbi.gold.restaurant.restaurant_intent import (
        contextualize_restaurant_followup,
    )

    parent = {
        "parent_query": "本月全部门店销量最高的10个菜是什么",
        "parent_template_code": "RESTAURANT_OPS_GROSS_MARGIN",
        "structured_context": {
            "focus_entity": {"type": "dish", "name": "招牌青花椒味(单人份)"},
            "window_label": "本月",
            "requested_metrics": ["sales_volume"],
            "topic_kind": "dish_ranking",
            "store_scope": "all",
        },
    }
    query = "本月全部门店米饭销量是多少，表现怎么样"

    effective, inherited = contextualize_restaurant_followup(query, parent)

    assert inherited is False
    assert effective == query
def test_restaurant_promotion_paths_do_not_write_or_embed():
    from smartbi.gold.restaurant import restaurant_intent_promotion as promotion

    assert promotion.load_promoted_samples() == {}
    assert promotion.merge_samples({"RESTAURANT_OPS_SALES_SUMMARY": ["本月营收"]}) == {}
    assert promotion.apply_promotions([
        {"query": "本月营收", "code": "RESTAURANT_OPS_SALES_SUMMARY"}
    ])["added"] == []


@pytest.mark.asyncio
async def test_synthesis_engine_returns_explicit_unavailable_for_restaurant_scope():
    from smartbi.agent.synthesis_engine import ComprehensiveSynthesisEngine

    response = await ComprehensiveSynthesisEngine(None).synthesize(
        "QHJ001", "本月营收", (date(2026, 1, 1), date(2026, 1, 31))
    )
    assert response.source == "restaurant_ai_retired"
    assert "已下线" in response.answer
