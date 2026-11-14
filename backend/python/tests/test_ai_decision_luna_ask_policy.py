"""Luna 主路径 v3 口径的契约闸 (INTENT-DISTILL-20261111 第三批, 2026-11-14, 按史浩禾 39 题校准)。

走真实 Orchestrator + 真实 DecisionMatcher.choose_luna / ask_probability; 只替换最外层网络:
Luna Choice (``call_decision_choice``) 与 Luna 判断题 (``call_decision_predicate``)。

守的行为:
  - 判断题 >= 门槛 ⇒ 先问, 哪怕 Choice 首选把握很高 (「查下这批可可粉」类);
  - 判断题低 ⇒ 只读首选 0.30 (旧 0.80 门槛下会先问) 按默认值直接办;
  - 写目标只交写确认卡 (requiresConfirmation=False 由 Java 出确认卡), 把握不够就先问; 判断题高照样先问;
  - 判断题拿不到 ⇒ 这一轮照旧 v1 (0.80); AI_DECISION_LUNA_POLICY=v1 ⇒ 判断题根本不发。
⛔ 不真调 OpenAI: 没打开 Luna 的用例里, 两个假函数被调用就直接炸。
"""
import asyncio

import pytest

from ai import registry
from ai.config import AIConfig
from ai.matcher.decision import ASK_PREDICATE_NAME, ASK_PREDICATE_RULES, DecisionMatcher, RequestPurpose
from ai.orchestrator import Orchestrator
from common import llm_router

ROWS = [
    {"id": "1", "intent_code": "MATERIAL_BATCH_QUERY", "intent_name": "原料批次查询", "description": "查原料批次",
     "keywords": None, "intent_category": "MATERIAL", "is_active": True, "sensitivity_level": "LOW"},
    {"id": "2", "intent_code": "TRACE_BATCH", "intent_name": "批次追溯", "description": "查批次来路",
     "keywords": None, "intent_category": "TRACE", "is_active": True, "sensitivity_level": "LOW"},
    {"id": "3", "intent_code": "MATERIAL_UPDATE", "intent_name": "修改原料批次", "description": "改原料批次",
     "keywords": None, "intent_category": "MATERIAL", "is_active": True, "sensitivity_level": "HIGH"},
]
CODES = [r["intent_code"] for r in ROWS]
FACTORY = "F_LUNA_ASK"


def _cfg(**kw):
    base = dict(decision_business_types="FACTORY", decision_special_options=True, decision_fallback="combo")
    base.update(kw)
    return AIConfig(**base)


class Matcher(DecisionMatcher):
    async def shortlist(self, query, role, visible, history=None):
        return list(CODES)

    async def request_purpose(self, query, role, history=None):
        return RequestPurpose.CURRENT


class Empty:
    async def match(self, *a, **k):
        return []


class LegacyMustNotRun:
    async def match(self, *a, **k):
        raise AssertionError("Luna 答了就不该回落旧四级")


@pytest.fixture(autouse=True)
def _isolate(monkeypatch, egress_as_factory_request):
    registry.set_registry(registry.RegistrySnapshot())
    for k in ("LLM_EGRESS_PROXY", "LLM_OPENAI_API_KEY", "AI_DECISION_LUNA_POLICY"):
        monkeypatch.delenv(k, raising=False)
    state = {"choice": 0, "predicate": [], "fallback": 0}

    async def never(*a, **k):
        raise AssertionError("这条用例没打开 Luna, 却发了 Decisions 请求")
    monkeypatch.setattr(llm_router, "call_decision_choice", never)
    monkeypatch.setattr(llm_router, "call_decision_predicate", never)

    async def fallback_must_not_run(*a, **k):
        state["fallback"] += 1
        raise AssertionError("Luna 答了就不该走退路")
    from ai import orchestrator as orch_mod
    monkeypatch.setattr(orch_mod, "run_fallback", fallback_must_not_run)
    yield state
    registry.set_registry(registry.RegistrySnapshot())


def _luna(monkeypatch, state, dist, ask=None, ask_raises=None):
    monkeypatch.setattr(llm_router, "decision_choice_enabled", lambda: True)

    async def choice(messages, choices, *, name="intent", timeout=6.0):
        state["choice"] += 1
        # 选项值是字母, 说明以「意图码：」开头; 两个出口的码是 NONE / CLARIFY
        return {v: dist.get(d.split("：", 1)[0], 0.0) for v, d in choices}

    async def predicate(instructions, text, *, name="needs_clarification", timeout=6.0):
        state["predicate"].append((instructions, text, name))
        if ask_raises is not None:
            raise ask_raises
        return ask
    monkeypatch.setattr(llm_router, "call_decision_choice", choice)
    monkeypatch.setattr(llm_router, "call_decision_predicate", predicate)


def _run(query="查下这批可可粉", cfg=None):
    orch = Orchestrator(semantic_matcher=Empty(), classifier_matcher=Empty(), llm_matcher=LegacyMustNotRun(),
                        decision_matcher=Matcher(), candidate_retriever=Empty(), config=cfg or _cfg())
    return asyncio.run(orch.match(query=query, factoryId=FACTORY, businessType="FACTORY", userId="u",
                                  role="warehouse_worker", visible_intents=ROWS, history=[], min_confidence=0.7,
                                  enable_llm=True))


READ_WEAK = {"MATERIAL_BATCH_QUERY": 0.30, "TRACE_BATCH": 0.20, "MATERIAL_UPDATE": 0.05, "NONE": 0.05, "CLARIFY": 0.05}
READ_STRONG = {"MATERIAL_BATCH_QUERY": 0.90, "TRACE_BATCH": 0.03, "MATERIAL_UPDATE": 0.02, "NONE": 0.02,
               "CLARIFY": 0.03}


def test_ask_predicate_high_asks_even_when_choice_is_confident(monkeypatch, _isolate):
    _luna(monkeypatch, _isolate, READ_STRONG, ask=0.93)
    res = _run()
    assert res.requiresConfirmation is True and res.timingMs["lunaAsk"] == 1
    assert res.timingMs["lunaAskP"] == 930 and res.timingMs["decisionClarify"] == 1
    # 判断题正文 = 身份行 + 用户说 (不带候选), 说明 = 校准过的规则
    instructions, text, name = _isolate["predicate"][0]
    assert instructions == ASK_PREDICATE_RULES and name == ASK_PREDICATE_NAME
    assert text.endswith("用户说：查下这批可可粉") and "仓管员" in text and "MATERIAL_BATCH_QUERY" not in text


def test_ask_predicate_low_executes_weak_read_with_defaults(monkeypatch, _isolate):
    """首选只读 0.30: v1 (0.80) 会先问; v3 判断题 0.1 ⇒ 交给 Java 按默认值直接办。"""
    _luna(monkeypatch, _isolate, READ_WEAK, ask=0.1)
    res = _run("看看今天到货的批次")
    assert res.requiresConfirmation is False and res.bestMatch.intentCode == "MATERIAL_BATCH_QUERY"
    assert res.timingMs["lunaReadWithDefaults"] == 1 and "decisionClarify" not in res.timingMs


def test_threshold_boundary_comes_from_config(monkeypatch, _isolate):
    """同一输入 (判断题 0.6): 缺省门槛 0.6 ⇒ 先问; 门槛 0.7 ⇒ 直接办。阴阳两侧。"""
    _luna(monkeypatch, _isolate, READ_WEAK, ask=0.6)
    assert _run().requiresConfirmation is True
    assert _run(cfg=_cfg(luna_ask_threshold=0.7)).requiresConfirmation is False


def test_read_below_threshold_and_no_margin_still_asks(monkeypatch, _isolate):
    flat = {"MATERIAL_BATCH_QUERY": 0.12, "TRACE_BATCH": 0.11, "MATERIAL_UPDATE": 0.05, "NONE": 0.05, "CLARIFY": 0.05}
    _luna(monkeypatch, _isolate, flat, ask=0.1)
    res = _run()
    assert res.requiresConfirmation is True and res.timingMs["lunaTwoWay"] == 1


def test_clarify_option_above_every_candidate_still_asks(monkeypatch, _isolate):
    _luna(monkeypatch, _isolate, {"MATERIAL_BATCH_QUERY": 0.2, "TRACE_BATCH": 0.1, "MATERIAL_UPDATE": 0.05,
                                  "NONE": 0.05, "CLARIFY": 0.4}, ask=0.1)
    res = _run("改成这周排班")
    assert res.requiresConfirmation is True and res.timingMs["decisionClarify"] == 1
    assert "lunaReadWithDefaults" not in res.timingMs


def test_write_target_goes_to_confirmation_card_or_asks_never_more(monkeypatch, _isolate):
    """写目标: 把握 >= 0.4 ⇒ 只把首选交给 Java (Java 一律先出写确认卡); < 0.4 ⇒ 先问; 判断题高 ⇒ 先问。"""
    write = {"MATERIAL_UPDATE": 0.5, "MATERIAL_BATCH_QUERY": 0.2, "TRACE_BATCH": 0.1, "NONE": 0.05, "CLARIFY": 0.05}
    _luna(monkeypatch, _isolate, write, ask=0.2)
    res = _run("把可可粉 B0312 的库存改成 40 袋")
    assert res.bestMatch.intentCode == "MATERIAL_UPDATE" and res.requiresConfirmation is False
    assert res.timingMs["lunaWriteToConfirm"] == 1

    weak = dict(write, MATERIAL_UPDATE=0.3)
    _luna(monkeypatch, _isolate, weak, ask=0.2)
    res = _run("把可可粉改一下")
    assert res.requiresConfirmation is True and res.timingMs["lunaWriteWeak"] == 1

    _luna(monkeypatch, _isolate, dict(write, MATERIAL_UPDATE=0.9), ask=0.97)
    res = _run("改下这批可可粉")
    assert res.requiresConfirmation is True and res.timingMs["lunaAsk"] == 1


def test_predicate_failure_falls_back_to_v1_for_this_turn(monkeypatch, _isolate):
    _luna(monkeypatch, _isolate, READ_WEAK, ask_raises=llm_router.LLMChainExhausted("decision_predicate", ["x: timeout"]))
    res = _run()
    assert res.requiresConfirmation is True and "lunaPolicyV3" not in res.timingMs  # 0.30 < 0.80
    _luna(monkeypatch, _isolate, READ_STRONG, ask_raises=llm_router.LLMChainExhausted("decision_predicate", ["x"]))
    res = _run()
    assert res.requiresConfirmation is False and res.bestMatch.intentCode == "MATERIAL_BATCH_QUERY"


def test_policy_v1_does_not_send_the_predicate(monkeypatch, _isolate):
    _luna(monkeypatch, _isolate, READ_WEAK, ask=0.0)
    res = _run(cfg=_cfg(decision_luna_policy="v1"))
    assert _isolate["predicate"] == [] and _isolate["choice"] == 1
    assert res.requiresConfirmation is True and "lunaPolicyV3" not in res.timingMs


def test_env_selects_policy_and_thresholds(monkeypatch):
    monkeypatch.setenv("AI_DECISION_LUNA_POLICY", "V1")
    monkeypatch.setenv("AI_DECISION_LUNA_ASK_THRESHOLD", "0.75")
    cfg = AIConfig.from_env()
    assert cfg.decision_luna_policy == "v1" and cfg.luna_ask_threshold == 0.75
    monkeypatch.delenv("AI_DECISION_LUNA_POLICY")
    monkeypatch.delenv("AI_DECISION_LUNA_ASK_THRESHOLD")
    cfg = AIConfig.from_env()
    assert (cfg.decision_luna_policy, cfg.luna_ask_threshold, cfg.luna_read_threshold, cfg.luna_margin,
            cfg.luna_write_threshold) == ("v3", 0.6, 0.15, 0.3, 0.4)
