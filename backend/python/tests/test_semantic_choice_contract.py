"""Choice rendering contracts; simulated scores never establish live accuracy."""
from ai.matcher.decision import build_choice_messages, intent_text, ROUTING_BOUNDARY_RULES
from dataclasses import replace
from tests.test_ai_decision_layer import _orch, _run, FakeDecision, _empty_registry, CFG


def test_choice_preserves_late_capability_boundary_for_both_transports():
    marker = "能力边界：只支持按金额排序，不支持按数量排序。"
    row = dict(intent_code="GENERIC_A", intent_name="汇总", description="业务功能说明。" * 25 + marker)
    rendered = intent_text(row)
    messages = build_choice_messages("修改统计条件", None, [row])
    assert marker in rendered and rendered in messages[1]["content"]
    assert ROUTING_BOUNDARY_RULES in messages[0]["content"]


def test_choice_retains_distinct_scopes_with_shared_long_introduction():
    prefix = "业务对象查询。" * 20
    rows = [dict(intent_code="COLLECTION", intent_name="查询", description=prefix + "返回对象集合。"),
            dict(intent_code="ONE_ENTITY", intent_name="查询", description=prefix + "返回单个已选择对象详情。")]
    body = build_choice_messages("这个对象", None, rows)[1]["content"]
    assert "返回对象集合。" in body and "返回单个已选择对象详情。" in body
    assert len(intent_text(dict(intent_code="BOUND", description="界" * 2000))) < 600


def test_prompt_change_does_not_promote_low_probability_to_execution():
    matcher = FakeDecision(["MATERIAL_BATCH_QUERY", "CLOCK_OUT"],
                           {"MATERIAL_BATCH_QUERY": .79, "CLOCK_OUT": .21})
    result = _run(_orch(matcher, cfg=replace(CFG, decision_auto_threshold=.80)), query="读取业务对象")
    assert result.requiresConfirmation is True
    assert result.bestMatch.intentCode == "MATERIAL_BATCH_QUERY"
    assert result.confidence == .79
    assert result.topCandidates[0].confidence == .79
