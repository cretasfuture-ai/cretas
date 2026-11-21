"""Exercise cache isolation and provider payloads through the real planner."""
import json
from datetime import date
from pathlib import Path
from types import SimpleNamespace

import pandas as pd
import pytest

from common.llm_router import LLMChainExhausted
from smartbi.services.external_production import UNAVAILABLE_MESSAGE
from common import smartbi_analysis_cache as analysis_result_cache
from tests.analysis_cache_fixtures import analysis_cache  # noqa: F401
from . import blacklake_production, conversation, engine


LIMIT_ENV = "FACTORY_CHART_PLANNER_CATALOG_LIMIT"
CATALOG = [{"id": "quantity", "familyId": "production.output", "unit": "kg"}]


@pytest.fixture
def provider(monkeypatch, analysis_cache):
    conversation.clear_contexts()
    monkeypatch.delenv(LIMIT_ENV, raising=False)
    calls = []

    async def call(_slot, payload, *, content_validator, **_kwargs):
        body = json.loads(payload["messages"][1]["content"])
        size = len(json.dumps(payload, ensure_ascii=False).encode("utf-8"))
        calls.append((body, size))
        ids_by_family = {item["familyId"]: item["id"] for item in body["catalog"]}
        content = json.dumps({"selected_ids": list(ids_by_family.values())[:4]})
        assert content_validator(content) is None
        return {"choices": [{"message": {"content": content}}]}

    monkeypatch.setattr("common.llm_router.call_chain", call)
    yield calls
    conversation.clear_contexts()


def _history(factory, role="manager"):
    return conversation.ConversationContext("context", factory, 7, role, "business", None, {}).history


@pytest.mark.asyncio
async def test_same_question_and_factory_hits_without_calling_provider(provider, caplog):
    history = _history("F001")
    selected, mode = await conversation.plan("看产量", CATALOG, history, factory_id="F001")
    assert selected == ["quantity"] and mode["cache"] == "miss"
    selected.clear()
    with caplog.at_level("INFO", logger=conversation.__name__):
        selected, mode = await conversation.plan("  看产量  ", CATALOG, history, factory_id="F001")
    assert selected == ["quantity"] and mode["mode"] == "ai" and mode["cache"] == "hit"
    assert len(provider) == 1
    assert "planner cache hit" in caplog.text


@pytest.mark.asyncio
async def test_identical_catalog_and_role_cannot_hit_across_factories(provider):
    for factory in ("F001", "F002"):
        selected, mode = await conversation.plan("看产量", CATALOG, _history(factory), factory_id=factory)
        assert selected == ["quantity"]
        assert mode["cache"] == "miss", f"cross-factory cache reuse for {factory}"
    assert len(provider) == 2


@pytest.mark.asyncio
@pytest.mark.parametrize("change", ["directory", "role", "subjects", "history"])
async def test_cache_misses_when_authorization_or_history_changes(provider, change):
    history = _history("F001")
    catalog, subjects = CATALOG, []
    await conversation.plan("看产量", catalog, history, "F001", subjects)
    if change == "directory":
        catalog = [{**CATALOG[0], "unit": "盒"}]
    if change == "role":
        history = _history("F001", role="operator")
    if change == "subjects":
        subjects = [{"id": "new-product", "label": "测试产品"}]
    if change == "history":
        history.append({"question": "刚才按日", "selectedIds": ["quantity"]})
    selected, mode = await conversation.plan("看产量", catalog, history, "F001", subjects)
    assert selected == ["quantity"] and mode["cache"] == "miss"
    assert len(provider) == 2


@pytest.mark.asyncio
async def test_authorized_directory_fingerprint_covers_excluded_items(provider, monkeypatch):
    monkeypatch.setenv(LIMIT_ENV, "1")
    other = {"id": "inventory", "familyId": "inventory.balance", "unit": "盒"}
    for unit in ("盒", "件"):
        catalog = CATALOG + [{**other, "unit": unit}]
        selected, mode = await conversation.plan("看产量", catalog, _history("F001"), factory_id="F001")
        assert selected == ["quantity"] and mode["cache"] == "miss"
    assert len(provider) == 2 and provider[0][0] == provider[1][0]


@pytest.mark.asyncio
async def test_cache_capacity_evicts_oldest_entry(provider, monkeypatch):
    monkeypatch.setattr(analysis_result_cache, "_MAX_ENTRIES", 2)

    async def ask(question, expect):
        _, mode = await conversation.plan(question, CATALOG, _history("F001"), factory_id="F001")
        assert mode["cache"] == expect

    await ask("A", "miss")
    await ask("B", "miss")
    await ask("A", "hit")
    await ask("C", "miss")
    await ask("B", "hit")
    await ask("A", "miss")
    await ask("B", "miss")
    assert len(provider) == 5


@pytest.mark.asyncio
async def test_rule_plan_does_not_mask_recovered_model(provider, monkeypatch):
    from common import llm_router

    success = llm_router.call_chain
    attempts = []

    async def fail(_slot, payload, **_kwargs):
        attempts.append(payload)
        raise LLMChainExhausted("chart", ["fixture: timeout"])

    monkeypatch.setattr(llm_router, "call_chain", fail)
    selected, mode = await conversation.plan("看产量", CATALOG, factory_id="F001")
    assert selected == ["quantity"] and mode == {
        "mode": "rule", "reason": "按问题关键词从授权图表目录选择", "cache": "miss", "cacheHit": False,
    }
    selected.clear()
    mode["mode"] = "changed by caller"
    monkeypatch.setattr(llm_router, "call_chain", success)
    selected, mode = await conversation.plan("看产量", CATALOG, factory_id="F001")
    assert selected == ["quantity"] and mode["mode"] == "ai" and mode["cache"] == "miss"
    assert len(attempts) == 1 and len(provider) == 1


@pytest.mark.asyncio
@pytest.mark.parametrize(("failure", "expected_reason"), [
    ("timeout", "AI 规划超时，关键词规则未命中"),
    ("invalid_planner selection count invalid", "当前授权目录没有与问题匹配的图表"),
])
async def test_unrelated_question_never_caches_an_empty_selection(provider, monkeypatch, failure, expected_reason):
    from common import llm_router

    success = llm_router.call_chain
    attempts = []

    async def fail(_slot, payload, **_kwargs):
        attempts.append(payload)
        raise LLMChainExhausted("chart", [f"fixture: {failure}"])

    monkeypatch.setattr(llm_router, "call_chain", fail)
    for _ in range(2):
        selected, mode = await conversation.plan("企鹅生活在哪里？", CATALOG, factory_id="F001")
        assert selected == []
        assert mode == {"mode": "unavailable", "reason": expected_reason}
    assert len(attempts) == 2
    monkeypatch.setattr(llm_router, "call_chain", success)
    selected, mode = await conversation.plan("企鹅生活在哪里？", CATALOG, factory_id="F001")
    assert selected == ["quantity"] and mode["mode"] == "ai" and mode["cache"] == "miss"
    assert len(provider) == 1


@pytest.mark.asyncio
async def test_successful_model_takes_precedence_over_rule_order(provider):
    catalog = [{"id": "stock", "familyId": "inventory.balance"}, *CATALOG]
    selected, mode = await conversation.plan("看产量", catalog, factory_id="F001")
    assert selected == ["stock", "quantity"] and mode["mode"] == "ai"
    assert len(provider) == 1


@pytest.mark.asyncio
async def test_rule_plan_uses_full_authorized_catalog_with_at_most_four_families(provider, monkeypatch):
    monkeypatch.setenv(LIMIT_ENV, "1")

    async def fail(_slot, payload, **_kwargs):
        assert len(json.loads(payload["messages"][1]["content"])["catalog"]) == 1
        raise LLMChainExhausted("chart", ["fixture: timeout"])

    monkeypatch.setattr("common.llm_router.call_chain", fail)
    catalog = [{"id": "unrelated", "familyId": "weather", "priority": "required"}]
    catalog += [
        {"id": f"output-{index}-{unit}", "familyId": f"output.metric_{index}", "unit": unit}
        for index in range(5)
        for unit in ("kg", "盒")
    ]
    selected, mode = await conversation.plan("看产量", catalog, factory_id="F001")
    assert mode["mode"] == "rule"
    assert selected == [f"output-{index}-kg" for index in range(4)]


@pytest.fixture(scope="module")
def liushanmen_catalog():
    rows = [
        {"日期": date(2026, 11, day).isoformat(), "产品编码": sku, "产品": name,
         "工序": stage, "单位": unit, "良品数": 12.0 * day, "不良数": 1.0}
        for day in (1, 2)
        for sku, name in (("C P2026100002", "测试产品甲"), ("C P2026100003", "测试产品乙"))
        for stage in ("成品入库", "出料", "包装")
        for unit in ("KG", "盒", "袋", "件")
    ]
    source = f"{blacklake_production.SOURCE_KEY} · 黑湖生产报工"
    candidates, gaps = engine.build([engine.Dataset(pd.DataFrame(rows), source)], SimpleNamespace(), False)
    assert any(gap["id"].startswith(f"{blacklake_production.SOURCE_KEY}.inputs:") for gap in gaps)
    other = [
        {"id": f"{family}@{index:012x}", "familyId": family, "familyLabel": label,
         "sourceKey": family.split(".")[0], "domain": domain, "metric": family,
         "chartType": kind, "unit": unit, "priority": "required"}
        for family, label, domain, kind in (
            ("production.output", "每日生产产量趋势", "生产", "line"),
            ("quality.defect_rate", "检验不良率趋势", "质检", "line"),
            ("quality.defects", "检验不良数", "质检", "bar"),
            ("purchase.receipts", "采购入库", "采购", "line"),
            ("inventory.balance", "库存余额", "库存", "bar"),
            ("sales.ranking", "每日产品销量排行", "销售", "bar_horizontal"),
            ("finance.cost", "单位成本趋势", "成本", "line"),
            ("process.throughput", "按工序统计产量", "生产", "bar_horizontal"),
        )
        for index, unit in enumerate(("KG", "盒", "袋", "件") * 3)
    ]
    return other + conversation.semantic_catalog(candidates), candidates


def _documented_questions():
    path = Path(__file__).resolve().parents[5] / "docs/smartbi/liushanmen-blacklake/questions.md"
    return [row.split("|")[1].strip() for row in path.read_text(encoding="utf-8").splitlines()
            if row.startswith("| 11 月")]


# Q11 and Q12 use the available production evidence. There is no authorized
# Blacklake yield or cost chart because the source lacks the required inputs.
EXPECTED = [
    ("finished.good", None), ("finished.ranking", None), ("finished.good", None),
    ("process.good", None), ("process.defects", None), ("process.defect_rate", None),
    ("finished.defect_rate", None), ("process.good", None), ("finished.defects", "盒"),
    ("finished.defect_rate", "KG"), ("finished.good", None), ("finished.good", None),
]
QUESTIONS = _documented_questions()
assert len(QUESTIONS) == len(EXPECTED) == 12


@pytest.mark.asyncio
@pytest.mark.parametrize("question,expected", zip(QUESTIONS, EXPECTED), ids=[f"Q{i}" for i in range(1, 13)])
async def test_liushanmen_questions_retain_expected_family(provider, liushanmen_catalog, question, expected):
    family, unit = expected
    catalog, _ = liushanmen_catalog
    _, mode = await conversation.plan(question, catalog, factory_id="LIUSHANMEN")
    assert mode["mode"] == "ai" and len(provider) == 1
    shortlist = provider[0][0]["catalog"]
    assert 0 < len(shortlist) <= 40 < 100 <= len(catalog)
    matches = [item for item in shortlist if item["familyId"] == f"{blacklake_production.SOURCE_KEY}.{family}"]
    assert matches, f"{family} missing for {question}"
    if unit is not None:
        assert unit in {item.get("sourceQuantityUnit", item["unit"]) for item in matches}


@pytest.mark.asyncio
@pytest.mark.parametrize("question,expected", zip(QUESTIONS, EXPECTED), ids=[f"Q{i}" for i in range(1, 13)])
async def test_liushanmen_rule_selects_expected_first_family(provider, liushanmen_catalog, monkeypatch,
                                                          question, expected):
    attempts = []

    async def fail(_slot, payload, **_kwargs):
        attempts.append(payload)
        raise LLMChainExhausted("chart", ["fixture: timeout"])

    monkeypatch.setattr("common.llm_router.call_chain", fail)
    catalog, real_candidates = liushanmen_catalog
    selected, mode = await conversation.plan(question, catalog, factory_id="LIUSHANMEN")
    by_id = {item["id"]: item for item in catalog}
    chosen = [by_id[item_id] for item_id in selected]
    families = [item["familyId"] for item in chosen]
    expected_family = f"{blacklake_production.SOURCE_KEY}.{expected[0]}"
    actual_family = families[0] if families else None
    print("RULE_QUESTION " + json.dumps({
        "q": question, "selected_family": actual_family, "expected": expected_family,
        "ok": actual_family == expected_family,
    }, ensure_ascii=False))

    assert len(attempts) == 1 and mode["mode"] == "rule"
    assert actual_family == expected_family, f"{question}: selected {families}"
    assert 1 <= len(families) == len(set(families)) <= 4
    if expected[1]:
        assert {item.get("sourceQuantityUnit", item["unit"]) for item in chosen} == {expected[1]}
    if "出成率" in question or "单位成本" in question:
        raw = {item["id"]: item for item in real_candidates}
        assert all(item_id in raw for item_id in selected)
        limits = conversation.limitations_for([raw[item_id] for item_id in selected], catalog, question)
        assert UNAVAILABLE_MESSAGE in limits
        if "单位成本" in question:
            assert "黑湖成本不含制造费用" in limits


@pytest.mark.asyncio
async def test_prefilter_reduces_real_provider_payload_bytes(provider, liushanmen_catalog, monkeypatch, caplog):
    question = QUESTIONS[0]
    catalog, _ = liushanmen_catalog
    with monkeypatch.context() as settings:
        settings.setenv(LIMIT_ENV, str(conversation.MAX_PLANNER_CATALOG))
        _, baseline = await conversation.plan(question, catalog, factory_id="LIUSHANMEN")
    with caplog.at_level("INFO", logger=conversation.__name__):
        _, filtered = await conversation.plan(question, catalog, factory_id="LIUSHANMEN")
    assert baseline["mode"] == filtered["mode"] == "ai" and len(provider) == 2
    (before, before_bytes), (after, after_bytes) = provider
    before_count, after_count = len(before["catalog"]), len(after["catalog"])
    print(f"planner provider payload: {before_count} -> {after_count} candidates, {before_bytes} -> {after_bytes} bytes")
    assert after_bytes < before_bytes / 2, f"provider bytes {before_bytes} -> {after_bytes}"
    assert before_count == len(catalog) and after_count == 40
    assert f"prefilter: {before_count} -> {after_count}" in caplog.text


@pytest.mark.asyncio
async def test_configured_limit_reserves_every_required_family(provider, monkeypatch):
    monkeypatch.setenv(LIMIT_ENV, "2")
    catalog = [{"id": f"sale-{i}", "familyId": "commerce-joint.sales.stock", "unit": "盒"} for i in range(4)]
    catalog += [{"id": "customer", "familyId": "commerce-joint.customer.channel", "unit": "KG"}]
    question = "关联销售发货、库存、客户和渠道做多表分析"
    _, mode = await conversation.plan(question, catalog, factory_id="F001")
    assert mode["mode"] == "ai" and len(provider) == 1
    shortlist = provider[0][0]["catalog"]
    assert len(shortlist) == 2
    assert {item["familyId"] for item in shortlist} == {"commerce-joint.sales.stock", "commerce-joint.customer.channel"}
