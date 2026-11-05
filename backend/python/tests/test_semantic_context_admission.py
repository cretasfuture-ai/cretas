"""Controlled route contracts; model outputs are simulated, not live accuracy."""
import pytest
from ai import registry
from ai.dto import MatchMethod
from ai.matcher.decision import history_block
from ai.orchestrator import _with_task_context
from tests.test_ai_decision_layer import FakeDecision, _run, _orch, _empty_registry, TURNS


@pytest.mark.parametrize("context", [None, {"contextStatus": "UNKNOWN"}, {"contextStatus": "EXPIRED"},
    {"contextStatus": "PRESENT"}, {"contextStatus": "PRESENT", "taskStatus": "COMPLETED"},
    {"contextStatus": "NEW", "hasPageReference": True}])
@pytest.mark.parametrize("query", ["查看详情", "上个月呢", "第一名库存呢", "取消"])
def test_contextual_exact_matches_are_candidates_not_routing_authority(context, query):
    key = registry.normalize(query, [])
    registry.set_registry(registry.build_snapshot([{"factory_id": "F006", "business_type": "FACTORY",
        "normalized_text": key, "intent_code": "CLOCK_OUT"}], []))
    registry.get_registry().standalone_routes = frozenset({("F006", "FACTORY", key, "CLOCK_OUT")})
    dec = FakeDecision(["MATERIAL_BATCH_QUERY"], {"MATERIAL_BATCH_QUERY": .98, "CLOCK_OUT": .02})
    result = _run(_orch(dec), query=query, task_context=context)
    assert result.bestMatch.intentCode == "MATERIAL_BATCH_QUERY"
    assert dec.choose_calls == 1 and "CLOCK_OUT" in dec.seen_options
    assert result.timingMs["registryCandidateOnly"] == 1


def test_approved_label_without_standalone_route_review_still_uses_model():
    registry.set_registry(registry.build_snapshot([{"factory_id": "F006", "business_type": "FACTORY",
        "normalized_text": "嗯签个退", "intent_code": "CLOCK_OUT"}], []))
    dec = FakeDecision(["MATERIAL_BATCH_QUERY"], {"MATERIAL_BATCH_QUERY": .98})
    result = _run(_orch(dec))
    assert result.matchMethod == MatchMethod.LLM and dec.choose_calls == 1


def test_unknown_context_does_not_fall_back_to_context_blind_legacy():
    dec = FakeDecision(["CLOCK_OUT"], None)
    orch = _orch(dec)
    result = _run(orch, task_context={"contextStatus": "UNKNOWN"})
    assert result.bestMatch is None and result.requiresConfirmation
    assert result.timingMs["contextFallbackBlocked"] == 1
    assert orch.llm_matcher.calls == 0


def test_structured_parameters_survive_prompt_history_character_limit():
    context = {"contextStatus": "PRESENT", "lastIntentCode": "ORDER_LIST", "references": [
        {"type": "READ_QUERY", "id": "ORDER_LIST", "parameters": {"startDate": "2026-10-01", "status": "CONFIRMED"}}]}
    prompt = history_block(_with_task_context(TURNS, context))
    assert "2026-10-01" in prompt and "CONFIRMED" in prompt


@pytest.mark.parametrize("reason", ["disabled", "missing", "request_disabled"])
def test_contextual_decision_is_fail_closed_when_not_available(reason):
    import asyncio
    from dataclasses import replace
    from tests.test_ai_decision_layer import ROWS, CFG
    dec = None if reason == "missing" else FakeDecision(["CLOCK_OUT"], {"CLOCK_OUT": .99})
    orch = _orch(dec, cfg=replace(CFG, decision_business_types="") if reason == "disabled" else CFG)
    result = asyncio.run(orch.match(query="上个月呢", factoryId="F006", businessType="FACTORY", userId="u", role="operator",
        visible_intents=ROWS, history=[], min_confidence=.7, enable_llm=reason != "request_disabled",
        task_context={"contextStatus": "PRESENT"}))
    assert result.bestMatch is None and result.requiresConfirmation
    assert result.timingMs["contextFallbackBlocked"] == 1 and orch.llm_matcher.calls == 0
