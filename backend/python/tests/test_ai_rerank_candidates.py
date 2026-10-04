"""候选重排路 (2026-10-04): 向量 Top-N → 微调重排 → 前 K, 取代 LLM 粗选; 失败 / 带历史退回粗选。

每条「走了重排」都配一条「没走 / 退回」的对照。
"""
import asyncio
from dataclasses import replace

import pytest

from ai import registry
from ai.config import AIConfig
from ai.dto import CandidateIntentDto, MatchMethod
from ai.matcher.candidates import BlendRetriever
from ai.matcher.rerank import RerankClient
from ai.orchestrator import Orchestrator

ROWS = [
    {"id": str(i), "intent_code": c, "intent_name": n, "description": d, "keywords": None,
     "intent_category": "QUERY", "is_active": True}
    for i, (c, n, d) in enumerate([
        ("MATERIAL_LOW_STOCK_ALERT", "低库存预警", "库存低于安全线的原料"),
        ("MATERIAL_BATCH_CREATE", "原料入库", "新到原料登记入库"),
        ("CLOCK_OUT", "下班打卡", "员工下班签退"),
        ("ORDER_TODAY", "今日订单", "今天的订单"),
    ])
]
CFG = AIConfig(decision_business_types="FACTORY", decision_auto_threshold=0.7)
CFG_RERANK = replace(CFG, decision_candidates="rerank", rerank_pool_k=50, rerank_top_k=2)


class FakeBlend(BlendRetriever):
    """isinstance(BlendRetriever) 才会走重排路; 记录调用时要的 top_k。"""

    def __init__(self, order):
        super().__init__(pool=None)
        self.order, self.calls = order, []

    async def match(self, query, factoryId, businessType, top_k=None):
        self.calls.append(top_k)
        return [CandidateIntentDto(intentCode=c, confidence=0.5, matchMethod=MatchMethod.SEMANTIC) for c in self.order]


class FakeRerank:
    def __init__(self, scores):
        self.scores_out, self.calls = scores, []

    async def scores(self, query, documents):
        self.calls.append(documents)
        return self.scores_out


class FakeDecision:
    def __init__(self):
        self.short_calls, self.seen = 0, None

    async def shortlist(self, query, role, visible, history=None):
        self.short_calls += 1
        return ["ORDER_TODAY"]

    async def choose(self, query, role, options, history=None, special_options=False):
        self.seen = [o["intent_code"] for o in options]
        return {self.seen[0]: 0.95, **{c: 0.01 for c in self.seen[1:]}}


class Empty:
    async def match(self, *a, **k):
        return []


def _orch(retriever, rerank, cfg=CFG_RERANK, dec=None):
    return Orchestrator(semantic_matcher=Empty(), classifier_matcher=Empty(), llm_matcher=Empty(),
                        decision_matcher=dec or FakeDecision(), candidate_retriever=retriever, config=cfg,
                        rerank_client=rerank)


def _run(orch, query="盐快没了", history=None):
    return asyncio.run(orch.match(query=query, factoryId="F006", businessType="FACTORY", userId="u",
                                  role="warehouse_worker", visible_intents=ROWS, history=history or [],
                                  min_confidence=0.7, enable_llm=True))


@pytest.fixture(autouse=True)
def _empty_registry():
    registry.set_registry(registry.RegistrySnapshot())
    yield
    registry.set_registry(registry.RegistrySnapshot())


VEC = ["MATERIAL_BATCH_CREATE", "CLOCK_OUT", "MATERIAL_LOW_STOCK_ALERT", "ORDER_TODAY"]


def test_rerank_path_orders_candidates_and_skips_the_llm_shortlist():
    ret, rr, dec = FakeBlend(VEC), FakeRerank([0.1, 0.0, 0.9, 0.2]), FakeDecision()
    res = _run(_orch(ret, rr, dec=dec))
    assert ret.calls == [50]                                   # 向量取 50 个
    assert dec.short_calls == 0                                # 没调 LLM 粗选
    assert dec.seen == ["MATERIAL_LOW_STOCK_ALERT", "ORDER_TODAY"]   # 重排后前 2
    assert res.timingMs.get("rerankOk") == 1
    assert rr.calls[0][2].startswith("低库存预警：")            # 文档 = intent_text, 与训练同格式


def test_rerank_failure_falls_back_to_the_llm_shortlist():
    ret, rr, dec = FakeBlend(VEC), FakeRerank(None), FakeDecision()
    res = _run(_orch(ret, rr, dec=dec))
    assert dec.short_calls == 1
    assert res.timingMs.get("rerankFallback") == 1
    assert "rerankOk" not in res.timingMs
    assert dec.seen[0] == "ORDER_TODAY"                        # 粗选结果排在前面 (旧路)


def test_turns_with_history_keep_the_llm_shortlist():
    ret, rr, dec = FakeBlend(VEC), FakeRerank([0.1, 0.0, 0.9, 0.2]), FakeDecision()
    res = _run(_orch(ret, rr, dec=dec), query="好了",
               history=[{"role": "user", "content": "开始包装工序"}, {"role": "assistant", "content": "已开始"}])
    assert rr.calls == [] and dec.short_calls == 1
    assert res.timingMs.get("rerankSkippedHistory") == 1


def test_default_mode_never_calls_the_reranker():
    ret, rr, dec = FakeBlend(VEC), FakeRerank([0.1, 0.0, 0.9, 0.2]), FakeDecision()
    res = _run(_orch(ret, rr, cfg=CFG, dec=dec))
    assert rr.calls == [] and dec.short_calls == 1
    assert ret.calls == [None]                                 # 旧路: 用构造时的 top_k
    assert "rerankOk" not in res.timingMs and "rerankFallback" not in res.timingMs


# ---------------- RerankClient ----------------

class _Resp:
    def __init__(self, status, payload):
        self.status_code, self._p = status, payload

    def json(self):
        return self._p


def _patch_client(monkeypatch, resp=None, exc=None, seen=None):
    class _C:
        def __init__(self, timeout):
            seen.append(("timeout", timeout)) if seen is not None else None

        async def __aenter__(self):
            return self

        async def __aexit__(self, *a):
            return False

        async def post(self, url, json, headers):
            if seen is not None:
                seen.append(("post", url, headers.get("X-Rerank-Key"), len(json["documents"])))
            if exc:
                raise exc
            return resp

    monkeypatch.setattr("ai.matcher.rerank.httpx.AsyncClient", _C)


def test_client_returns_scores_in_document_order(monkeypatch):
    seen = []
    _patch_client(monkeypatch, _Resp(200, {"scores": [0.2, 0.7]}), seen=seen)
    out = asyncio.run(RerankClient("http://127.0.0.1:18190/rerank", "k", 0.5).scores("q", ["a", "b"]))
    assert out == [0.2, 0.7]
    assert ("post", "http://127.0.0.1:18190/rerank", "k", 2) in seen and ("timeout", 0.5) in seen


@pytest.mark.parametrize("resp,exc", [
    (_Resp(401, {"detail": "bad key"}), None),
    (_Resp(200, {"scores": [0.2]}), None),          # 条数不符
    (_Resp(200, {"oops": 1}), None),
    (None, TimeoutError("read timeout")),
])
def test_client_failures_return_none(monkeypatch, resp, exc):
    _patch_client(monkeypatch, resp, exc)
    assert asyncio.run(RerankClient("u", "k").scores("q", ["a", "b"])) is None
