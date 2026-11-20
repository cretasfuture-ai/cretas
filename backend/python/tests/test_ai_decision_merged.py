"""合并理解 (INTENT-DISTILL E): 一次结构化调用 + 只在需要时 Choice。

每条「少调了」的断言都配一条能让它红的对照, 并且数的是假决策器实际收到的调用次数,
不是代码里写了几处调用。
"""
import asyncio
import json

from ai import registry
from ai.config import AIConfig
from ai.matcher.decision import (
    CLARIFY_KEY,
    NONE_KEY,
    RequestPurpose,
    Understanding,
    build_understand_messages,
    build_shortlist_messages,
    parse_understanding,
)
from ai.orchestrator import CANCEL_REPLY, Orchestrator

ROWS = [
    {"id": "1", "intent_code": "ORDER_LIST", "intent_name": "订单列表", "description": "查销售订单", "keywords": '["订单"]',
     "intent_category": "SALES", "is_active": True},
    {"id": "2", "intent_code": "ORDER_STATUS", "intent_name": "订单状态", "description": "查订单状态", "keywords": None,
     "intent_category": "SALES", "is_active": True},
    {"id": "3", "intent_code": "MATERIAL_BATCH_QUERY", "intent_name": "原料批次查询", "description": "查原料库存",
     "keywords": '["库存"]', "intent_category": "MATERIAL", "is_active": True},
]
CODES = {r["intent_code"] for r in ROWS}
MERGED = AIConfig(decision_business_types="FACTORY", decision_special_options=True, decision_merged=True,
                  decision_clarify_threshold=0.5, decision_choice_below=0.8,
                  decision_fallback="legacy")  # 合并失败后回到的分开调用走旧链; 组合退路另有测试


def u(**kw):
    base = dict(purpose=RequestPurpose.CURRENT, act="NEW_TASK", candidates=["ORDER_LIST", "ORDER_STATUS"],
                target="ORDER_LIST", confidence=0.95, alternatives=[], clarify_probability=0.02, clarify_reason="",
                parameters=[])
    base.update(kw)
    return Understanding(**base)


class Empty:
    async def match(self, *a, **k):
        return []


class FakeDecision:
    def __init__(self, understanding, probs=None):
        self.understanding, self.probs = understanding, probs
        self.calls = []

    async def understand(self, query, role, visible, history=None):
        self.calls.append("understand")
        return self.understanding

    async def request_purpose(self, query, role, history=None):
        self.calls.append("purpose")
        return RequestPurpose.CURRENT

    async def shortlist(self, query, role, visible, history=None):
        self.calls.append("shortlist")
        return ["ORDER_LIST"]

    async def choose(self, query, role, options, history=None, special_options=False):
        self.calls.append("choose")
        return dict(self.probs) if self.probs is not None else None


def run(dec, cfg=MERGED, monkeypatch=None):
    orch = Orchestrator(Empty(), Empty(), Empty(), decision_matcher=dec, candidate_retriever=Empty(), config=cfg)
    return asyncio.run(orch.match("看订单", "F006", "FACTORY", "u", "factory_super_admin", ROWS, [], 0.7))


def _no_registry(monkeypatch):
    monkeypatch.setattr(registry, "get_registry", lambda: registry.RegistrySnapshot(available=False))


# ---------------------------------------------------------------- parse
def test_parse_understanding_accepts_contract_and_drops_codes_outside_catalog():
    raw = json.dumps({"purpose": "CURRENT", "dialogue_act": "CHANGE_PARAM", "candidates": ["MADE_UP", "ORDER_STATUS"],
                      "target": "ORDER_LIST", "confidence": 0.9, "alternatives": ["MADE_UP", "ORDER_STATUS"],
                      "clarify_probability": 0.1, "clarify_reason": "",
                      "parameters": [{"name": "状态", "value": "已完成", "source": "explicit_turn", "evidence": "已完成"},
                                     {"name": "x", "value": "y", "source": "guessed"}]})
    got = parse_understanding(raw, CODES)
    assert got.target == "ORDER_LIST"
    assert got.candidates == ["ORDER_LIST", "ORDER_STATUS"]  # target 排第一, 目录外代码丢掉
    assert got.alternatives == ["ORDER_STATUS"]
    assert [p["source"] for p in got.parameters] == ["explicit_turn"]  # 来源不合法的参数不收


def test_parse_understanding_rejects_shape_errors_instead_of_guessing():
    ok = {"purpose": "CURRENT", "dialogue_act": "NEW_TASK", "candidates": [], "target": None, "confidence": 0.5,
          "alternatives": [], "clarify_probability": 0.5, "clarify_reason": "", "parameters": []}
    assert parse_understanding(json.dumps(ok), CODES) is not None
    for bad in ({"confidence": 1.2}, {"clarify_probability": None}, {"dialogue_act": "MAYBE"}, {"purpose": "X"},
                {"candidates": "ORDER_LIST"}, {"confidence": True}):
        assert parse_understanding(json.dumps({**ok, **bad}), CODES) is None, bad
    assert parse_understanding("not json", CODES) is None
    # 目录外的 target 不得变成可执行目标
    assert parse_understanding(json.dumps({**ok, "target": "MADE_UP"}), CODES).target is None


def test_understand_prompt_carries_the_same_catalog_block_as_shortlist():
    """目录块逐字相同 (同一个 _catalog_line), 合并调用不会悄悄换一份目录口径。"""
    a = build_understand_messages("看订单", "operator", ROWS)[0]["content"]
    b = build_shortlist_messages("看订单", "operator", ROWS)[0]["content"]
    block = b.split("：\n", 1)[1].split("\n\n", 1)[0]
    assert "ORDER_LIST｜订单列表（订单）" in block
    assert a.split("：\n", 1)[1].startswith(block + "\n\n")


# ---------------------------------------------------------------- orchestration
def test_confident_target_is_one_call_and_choice_is_skipped(monkeypatch):
    _no_registry(monkeypatch)
    dec = FakeDecision(u(), probs={"ORDER_LIST": 0.4, "ORDER_STATUS": 0.4})
    res = run(dec)
    assert dec.calls == ["understand"]
    assert res.bestMatch.intentCode == "ORDER_LIST" and not res.requiresConfirmation
    assert res.timingMs["choiceSkipped"] == 1 and res.timingMs["merged"] == 1
    # 对照: 同一份输出, 把把握降到门槛以下就必须去调 Choice
    dec2 = FakeDecision(u(confidence=0.6), probs={"ORDER_LIST": 0.9, NONE_KEY: 0.05, CLARIFY_KEY: 0.05})
    run(dec2)
    assert dec2.calls == ["understand", "choose"]


def test_alternatives_alone_do_not_trigger_choice(monkeypatch):
    _no_registry(monkeypatch)
    dec = FakeDecision(u(alternatives=["ORDER_STATUS"]), probs={"ORDER_LIST": 0.4, "ORDER_STATUS": 0.4})
    res = run(dec)
    assert dec.calls == ["understand"] and not res.requiresConfirmation


def test_low_confidence_choice_agreeing_executes_disagreeing_falls_to_choice_rules(monkeypatch):
    _no_registry(monkeypatch)
    agree = FakeDecision(u(confidence=0.6), probs={"ORDER_LIST": 0.5, "ORDER_STATUS": 0.3, NONE_KEY: 0.1, CLARIFY_KEY: 0.1})
    res = run(agree)
    assert res.bestMatch.intentCode == "ORDER_LIST" and not res.requiresConfirmation
    assert res.timingMs["choiceAgrees"] == 1
    disagree = FakeDecision(u(confidence=0.6), probs={"ORDER_STATUS": 0.5, "ORDER_LIST": 0.3, NONE_KEY: 0.1, CLARIFY_KEY: 0.1})
    res = run(disagree)
    assert res.requiresConfirmation  # Choice 首选 0.5 < 0.8 自动门槛 ⇒ 出按钮, 不执行任何一个
    assert [c.intentCode for c in res.topCandidates][:2] == ["ORDER_STATUS", "ORDER_LIST"]


def test_clarify_decision_comes_from_merged_field_and_choice_only_orders_buttons(monkeypatch):
    _no_registry(monkeypatch)
    dec = FakeDecision(u(clarify_probability=0.9, act="NEED_CLARIFY"),
                       probs={"ORDER_LIST": 0.95, "ORDER_STATUS": 0.03, NONE_KEY: 0.01, CLARIFY_KEY: 0.01})
    res = run(dec)
    assert dec.calls == ["understand", "choose"]
    assert res.requiresConfirmation and res.timingMs["mergedClarify"] == 1
    assert [c.intentCode for c in res.topCandidates][:2] == ["ORDER_LIST", "ORDER_STATUS"]
    # 对照: 低于门槛不问, 也不调 Choice
    dec2 = FakeDecision(u(clarify_probability=0.4), probs={"ORDER_LIST": 0.95})
    res2 = run(dec2)
    assert not res2.requiresConfirmation and dec2.calls == ["understand"]


def test_cancel_wins_over_information_only_purpose(monkeypatch):
    _no_registry(monkeypatch)
    dec = FakeDecision(u(act="CANCEL", purpose=RequestPurpose.INFORMATION_ONLY, target=None, candidates=[]))
    res = run(dec)
    assert res.bestMatch is None and not res.requiresConfirmation
    assert res.conversationMessage == CANCEL_REPLY and res.timingMs["decisionNone"] == 1
    # 对照: 不是取消、只是咨询 ⇒ 追问, 不是取消回复
    res2 = run(FakeDecision(u(purpose=RequestPurpose.INFORMATION_ONLY)))
    assert res2.requiresConfirmation and res2.conversationMessage != CANCEL_REPLY


def test_merged_path_never_exceeds_two_model_calls(monkeypatch):
    _no_registry(monkeypatch)
    probs = {"ORDER_LIST": 0.3, "ORDER_STATUS": 0.3, NONE_KEY: 0.2, CLARIFY_KEY: 0.2}
    for und in (u(), u(confidence=0.1), u(target=None), u(clarify_probability=1.0), u(purpose=RequestPurpose.UNRESOLVED),
                u(act="CANCEL")):
        dec = FakeDecision(und, probs=probs)
        run(dec)
        assert len(dec.calls) <= 2, (und, dec.calls)


def test_unusable_merged_output_falls_back_to_split_calls_and_says_so(monkeypatch):
    _no_registry(monkeypatch)
    dec = FakeDecision(None, probs={"ORDER_LIST": 0.95, NONE_KEY: 0.03, CLARIFY_KEY: 0.02})
    res = run(dec)
    assert dec.calls[0] == "understand" and {"purpose", "shortlist", "choose"} <= set(dec.calls)
    assert res.timingMs["mergedFallback"] == 1


def test_flag_off_keeps_the_old_split_path(monkeypatch):
    _no_registry(monkeypatch)
    off = AIConfig(decision_business_types="FACTORY", decision_special_options=True, decision_fallback="legacy")
    assert off.decision_merged is False
    dec = FakeDecision(u(), probs={"ORDER_LIST": 0.95, NONE_KEY: 0.03, CLARIFY_KEY: 0.02})
    run(dec, cfg=off)
    assert "understand" not in dec.calls and {"purpose", "shortlist", "choose"} <= set(dec.calls)


def test_merged_result_still_goes_through_visible_scope_filter(monkeypatch):
    """模型给的 target 只是候选: 不在本人可见目录里的代码, 解析阶段就丢掉, 不会执行。"""
    _no_registry(monkeypatch)
    raw = json.dumps({"purpose": "CURRENT", "dialogue_act": "NEW_TASK", "candidates": ["SECRET_TOOL"],
                      "target": "SECRET_TOOL", "confidence": 0.99, "alternatives": [], "clarify_probability": 0.0,
                      "clarify_reason": "", "parameters": []})
    parsed = parse_understanding(raw, CODES)
    assert parsed.target is None and parsed.candidates == []
    dec = FakeDecision(parsed, probs={"ORDER_LIST": 0.95, NONE_KEY: 0.03, CLARIFY_KEY: 0.02})
    res = run(dec)
    assert res.bestMatch is None or res.bestMatch.intentCode in CODES
