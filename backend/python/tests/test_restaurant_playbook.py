"""L1 playbook — routing, content safety, resolver contract."""
from __future__ import annotations

import asyncio

import pytest

import smartbi.gold.restaurant.restaurant_playbook as pb
from smartbi.gold.restaurant.restaurant_intent import RestaurantQuerySpec
from smartbi.gold.restaurant.restaurant_ops_router import (
    match_restaurant_ops,
    reconcile_restaurant_ops_code,
    resolve_by_code,
)


@pytest.mark.parametrize("query,topic", [
    ("毛利率偏低的行业参考做法", "margin"),
    ("食材损耗偏高的行业参考做法", "wastage"),
    ("慢销菜品的行业参考做法", "slow_dish"),
    ("门店业绩差距的行业参考做法", "store_gap"),
    ("出餐慢的行业参考做法", "slow_serving"),
    ("客单价偏低的行业参考做法", "ticket_size"),
    ("差评偏多的行业参考做法", "review"),
    ("盘点差异的行业参考做法", "stocktake"),
    ("排班人效的行业参考做法", "staffing"),
    ("营收下滑的行业参考做法", "revenue_drop"),
])
def test_topic_matching(query, topic):
    assert pb.match_playbook_topic(query) == topic




def test_trigger_without_topic_returns_menu():
    assert pb.match_playbook_topic("有哪些行业参考做法") == "__menu__"










def test_content_has_no_internal_identifiers_or_fabricated_citations():
    import re
    for slug in pb._PLAYBOOKS:
        text = pb.render_playbook(slug)
        assert text
        assert not re.search(r"[A-Z]{2,}_[A-Z_]+", text)          # intent codes
        assert not re.search(r"\b(tool|SQL|JSON|sessionId)\b", text, re.I)
        for banned in ("研究表明", "专家指出", "据统计", "%的门店"):
            assert banned not in text, f"{slug} 含不可溯源断言: {banned}"
