"""先问口径按工厂分档的契约闸 (INTENT-DISTILL-20261111, Steve 2026-11-14: 工厂自己选程度, 默认激进)。

走真实 HTTP 入口 POST /api/ai/intent/match (ai.api.router) → 真实 Orchestrator → 真实 DecisionMatcher.choose_luna /
ask_probability → ai.ask_policy.resolve; 只替换最外层网络 (Luna Choice ``call_decision_choice`` 与 Luna 判断题
``call_decision_predicate``) 和目录快照。

守的行为:
  - 同一句话: 请求带 CONSERVATIVE 的工厂先问, 带 AGGRESSIVE 的工厂直接办 (判断题规则与门槛都按档位);
  - 判断题规则按档位发 (保守 = ASK_PREDICATE_RULES, 激进 = ASK_PREDICATE_RULES_AGGRESSIVE);
  - 请求没带 / 认不出档位 ⇒ 激进 (默认档), 结果上标 askPolicyDefaulted 并计数 intent_ask_policy_total;
  - 全局开关优先: v3 ⇒ 不管请求带什么都按保守; v1 ⇒ 判断题根本不发;
  - 两档都不放宽写: 写目标只交 Java 出确认卡 (把握不够照样先问), 激进档与保守档写门槛相同。
"""
import asyncio  # noqa: F401  (TestClient 内部跑事件循环)

import pytest
from fastapi import FastAPI, Request
from fastapi.testclient import TestClient

from ai import api, ask_policy, registry
from ai.config import AIConfig
from ai.db import IntentSnapshot
from ai.matcher.decision import (ASK_PREDICATE_RULES, ASK_PREDICATE_RULES_AGGRESSIVE, DecisionMatcher,
                                 RequestPurpose)
from ai.orchestrator import Orchestrator
from common import llm_router

ROWS = [
    {"id": "1", "intent_code": "MATERIAL_BATCH_QUERY", "intent_name": "原料批次查询", "description": "查原料批次",
     "keywords": None, "intent_category": "MATERIAL", "business_type": "FACTORY", "factory_id": None,
     "is_active": True, "sensitivity_level": "LOW"},
    {"id": "2", "intent_code": "TRACE_BATCH", "intent_name": "批次追溯", "description": "查批次来路",
     "keywords": None, "intent_category": "TRACE", "business_type": "FACTORY", "factory_id": None,
     "is_active": True, "sensitivity_level": "LOW"},
    {"id": "3", "intent_code": "MATERIAL_UPDATE", "intent_name": "修改原料批次", "description": "改原料批次",
     "keywords": None, "intent_category": "MATERIAL", "business_type": "FACTORY", "factory_id": None,
     "is_active": True, "sensitivity_level": "HIGH"},
]
CODES = [r["intent_code"] for r in ROWS]
READ = {"MATERIAL_BATCH_QUERY": 0.60, "TRACE_BATCH": 0.15, "MATERIAL_UPDATE": 0.05, "NONE": 0.05, "CLARIFY": 0.15}


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
    monkeypatch.setattr(api, "get_current_snapshot", lambda: IntentSnapshot(rows=ROWS))
    state = {"predicate": [], "p": 0.7, "dist": READ}
    monkeypatch.setattr(llm_router, "decision_choice_enabled", lambda: True)

    async def choice(messages, choices, *, name="intent", timeout=6.0):
        return {v: state["dist"].get(d.split("：", 1)[0], 0.0) for v, d in choices}

    async def predicate(instructions, text, *, name="needs_clarification", timeout=6.0):
        state["predicate"].append(instructions)
        return state["p"](instructions) if callable(state["p"]) else state["p"]
    monkeypatch.setattr(llm_router, "call_decision_choice", choice)
    monkeypatch.setattr(llm_router, "call_decision_predicate", predicate)

    async def fallback_must_not_run(*a, **k):
        raise AssertionError("Luna 答了就不该走退路")
    from ai import orchestrator as orch_mod
    monkeypatch.setattr(orch_mod, "run_fallback", fallback_must_not_run)
    yield state
    registry.set_registry(registry.RegistrySnapshot())


def _post(query="查下这批可可粉", ask_policy_value=None, factory="F_TIER", **cfg):
    conf = dict(decision_business_types="FACTORY", decision_special_options=True, decision_fallback="combo")
    conf.update(cfg)
    app = FastAPI()

    @app.middleware("http")
    async def local_auth(request: Request, call_next):
        request.state.auth_method = "internal"
        request.state.factory_id = factory
        return await call_next(request)
    app.include_router(api.router)
    app.state.ai_orchestrator = Orchestrator(
        semantic_matcher=Empty(), classifier_matcher=Empty(), llm_matcher=LegacyMustNotRun(),
        decision_matcher=Matcher(), candidate_retriever=Empty(), config=AIConfig(**conf))
    body = {"query": query, "factoryId": factory, "businessType": "FACTORY", "userId": "7",
            "role": "warehouse_worker", "history": []}
    if ask_policy_value is not None:
        body["askPolicy"] = ask_policy_value
    resp = TestClient(app).post("/api/ai/intent/match", json=body, headers={"X-Factory-Id": factory})
    assert resp.status_code == 200 and resp.json()["success"] is True, resp.text
    return resp.json()["data"]


def _asked(data):
    return data["requiresConfirmation"] is True


def _counter(tier, source):
    if ask_policy.ASK_POLICY_TOTAL is None:
        pytest.skip("prometheus_client 不在, 计数器退化为 no-op")
    return ask_policy.ASK_POLICY_TOTAL.labels(tier=tier, source=source)._value.get()


def test_same_sentence_asks_in_conservative_factory_and_executes_in_aggressive(_isolate):
    """同一句话: 判断题按各档规则作答 (保守规则 0.9 / 激进规则 0.1, 形如实测「记客户需求」0.86 / 0.07),
    保守档的工厂先问, 激进档的工厂按默认直接办。"""
    _isolate["p"] = lambda rules: 0.9 if rules == ASK_PREDICATE_RULES else 0.1
    cons = _post(ask_policy_value="CONSERVATIVE")
    agg = _post(ask_policy_value="AGGRESSIVE")
    assert _asked(cons) and cons["timingMs"]["lunaAsk"] == 1 and cons["timingMs"]["askTierConservative"] == 1
    assert not _asked(agg) and agg["bestMatch"]["intentCode"] == "MATERIAL_BATCH_QUERY"
    assert agg["timingMs"]["lunaReadWithDefaults"] == 1 and agg["timingMs"]["askTierAggressive"] == 1
    assert "askPolicyDefaulted" not in agg["timingMs"]


def test_ask_threshold_follows_the_factory_tier(_isolate):
    """门槛也按档: 判断题 0.5 夹在激进门槛 (0.4) 与保守门槛 (0.6) 之间 ⇒ 激进档先问、保守档直接办。"""
    cfg = AIConfig()
    assert cfg.luna_ask_threshold_aggressive <= 0.5 < cfg.luna_ask_threshold  # 前提: 0.5 确实夹在两档之间
    _isolate["p"] = 0.5
    assert _asked(_post(ask_policy_value="AGGRESSIVE"))
    assert not _asked(_post(ask_policy_value="CONSERVATIVE"))


def test_predicate_rules_follow_the_factory_tier(_isolate):
    _isolate["p"] = 0.1
    _post(ask_policy_value="CONSERVATIVE")
    _post(ask_policy_value="AGGRESSIVE")
    _post(ask_policy_value="保守")
    assert _isolate["predicate"] == [ASK_PREDICATE_RULES, ASK_PREDICATE_RULES_AGGRESSIVE, ASK_PREDICATE_RULES]
    assert ASK_PREDICATE_RULES != ASK_PREDICATE_RULES_AGGRESSIVE


@pytest.mark.parametrize("value,source", [(None, "default_missing"), ("MEDIUM", "default_unknown")])
def test_missing_or_unknown_tier_uses_default_and_is_counted(_isolate, value, source):
    before = _counter("aggressive", source)
    _isolate["p"] = 0.2
    data = _post(ask_policy_value=value)
    assert not _asked(data) and data["timingMs"]["askTierAggressive"] == 1  # 默认档 = 激进
    assert data["timingMs"]["askPolicyDefaulted"] == 1
    assert _isolate["predicate"] == [ASK_PREDICATE_RULES_AGGRESSIVE]
    assert _counter("aggressive", source) == before + 1


def test_global_switch_overrides_the_factory_tier(_isolate):
    _isolate["p"] = lambda rules: 0.9 if rules == ASK_PREDICATE_RULES else 0.1
    forced = _post(ask_policy_value="AGGRESSIVE", decision_luna_policy="v3")
    assert _asked(forced) and forced["timingMs"]["askTierConservative"] == 1
    assert _isolate["predicate"] == [ASK_PREDICATE_RULES]
    forced = _post(ask_policy_value="CONSERVATIVE", decision_luna_policy="v4")
    assert not _asked(forced) and forced["timingMs"]["askTierAggressive"] == 1
    _isolate["predicate"].clear()
    rolled_back = _post(ask_policy_value="AGGRESSIVE", decision_luna_policy="v1")
    assert _isolate["predicate"] == [] and "lunaPolicyV3" not in rolled_back["timingMs"]
    assert _asked(rolled_back)  # v1: 首选 0.60 < 0.80 ⇒ 出按钮


def test_aggressive_tier_does_not_loosen_writes(_isolate):
    """激进档: 写目标照旧只交 Java (Java 一律先出确认卡); 把握不够 (< 写门槛) 照样先问, 与保守档同一门槛。"""
    _isolate["p"] = 0.1
    _isolate["dist"] = {"MATERIAL_UPDATE": 0.5, "MATERIAL_BATCH_QUERY": 0.2, "TRACE_BATCH": 0.1, "NONE": 0.05,
                        "CLARIFY": 0.05}
    card = _post("把可可粉 B0312 的库存改成 40 袋", ask_policy_value="AGGRESSIVE")
    assert card["bestMatch"]["intentCode"] == "MATERIAL_UPDATE" and card["timingMs"]["lunaWriteToConfirm"] == 1
    _isolate["dist"] = dict(_isolate["dist"], MATERIAL_UPDATE=0.3)
    weak = _post("把可可粉改一下", ask_policy_value="AGGRESSIVE")
    assert _asked(weak) and weak["timingMs"]["lunaWriteWeak"] == 1
    cfg = AIConfig()
    assert ask_policy.tier_of(cfg, ask_policy.AGGRESSIVE).write_threshold == \
        ask_policy.tier_of(cfg, ask_policy.CONSERVATIVE).write_threshold
