"""工厂级保密配置 (Steve 2026-11-03) 的 Python 侧: 读取规则、出外脱敏 (c) 开 / 关、提示词约束 (a) 拆句、
成本授权 fail-closed 路径 (a)。

只把「读配置表」(``factory_secrecy._read``) 和最内层 HTTP 换成假的; 出境口 ``_RedactingLLMClient``、
重排客户端、成本范围解析器都是真的。断言看真正发出去的请求体。
"""
from __future__ import annotations

import json
from pathlib import Path

import httpx
import pytest

from common import factory_secrecy as fs
from common import llm_client as lc
from common.process_secret_harness import (CROSS_FACTORY_HARNESS, PROCESS_SECRET_CLAUSE, PROCESS_SECRET_HARNESS,
                                           with_process_secret_harness, without_process_secret_clause)

SECRET = "华东冷链商贸有限公司的质检员：赵铁柱，电话 13912345678"
F = "F-PY-SEC"


@pytest.fixture(autouse=True)
def settings(monkeypatch):
    """内存里的配置表: {factory: {code: True/False/None}}; reads 记下每次读库。"""
    table: dict = {}
    reads: list = []

    async def fake_read(factory_id):
        reads.append(factory_id)
        if table.get("__broken__"):
            raise RuntimeError("db down")
        return dict(table.get(factory_id, {}))

    monkeypatch.setattr(fs, "_read", fake_read)
    fs.invalidate()
    yield table, reads
    fs.invalidate()


def _set(table, factory, code, value):
    table.setdefault(factory, {})[code] = value
    fs.invalidate(factory)


# ------------------------------------------------------------------ 读取规则


@pytest.mark.asyncio
async def test_no_row_means_off_and_the_audit_item_follows_process_secret(settings):
    table, _ = settings
    for code in fs.CODES:
        assert await fs.is_on(F, code) is False, code
    _set(table, F, fs.PROCESS_SECRET, True)
    assert await fs.is_on(F, fs.AI_SECRECY_AUDIT) is True
    _set(table, F, fs.AI_SECRECY_AUDIT, False)
    assert await fs.is_on(F, fs.AI_SECRECY_AUDIT) is False
    _set(table, F, fs.AI_SECRECY_AUDIT, None)   # 跟随
    assert await fs.is_on(F, fs.AI_SECRECY_AUDIT) is True


@pytest.mark.asyncio
async def test_unknown_factory_and_failed_reads_are_on_and_failures_are_not_cached(settings):
    table, reads = settings
    for factory in (None, "", "  ", "PLATFORM", "platform"):
        for code in fs.CODES:
            assert await fs.is_on(factory, code) is True
    assert reads == []   # 工厂不明不查库
    table["__broken__"] = True
    assert await fs.is_on(F, fs.EGRESS_REDACTION) is True
    table["__broken__"] = False
    assert await fs.is_on(F, fs.EGRESS_REDACTION) is False   # 失败没进缓存
    with pytest.raises(ValueError):
        await fs.is_on(F, "MADE_UP")


@pytest.mark.asyncio
async def test_reads_are_cached_ten_seconds_and_egress_reads_at_most_five(settings, monkeypatch):
    table, reads = settings
    now = [1000.0]
    monkeypatch.setattr(fs.time, "monotonic", lambda: now[0])
    assert await fs.is_on(F, fs.TRAINING_FILTER) is False
    _set(table, F, fs.EGRESS_REDACTION, True)
    reads.clear()
    assert await fs.is_on(F, fs.EGRESS_REDACTION) is True
    table[F][fs.EGRESS_REDACTION] = False      # 库里变了, 没清缓存
    now[0] += 6
    assert await fs.is_on(F, fs.PROCESS_SECRET) is False and len(reads) == 1   # 一般项: 10 秒内用缓存
    assert await fs.egress_redaction_on(F) is False                            # c: 超过 5 秒重读
    assert len(reads) == 2
    # 同步读只看缓存; 没有缓存 / 过期 → 开
    assert fs.cached_is_on(F, fs.EGRESS_REDACTION, max_age=fs.EGRESS_MAX_AGE_S) is False
    now[0] += 6
    assert fs.cached_is_on(F, fs.EGRESS_REDACTION, max_age=fs.EGRESS_MAX_AGE_S) is True
    assert fs.cached_is_on("F-NEVER-READ", fs.EGRESS_REDACTION) is True


def test_the_items_file_is_the_generated_copy_of_the_java_enum():
    data = json.loads((Path(fs.__file__).with_name("secrecy_items.json")).read_text(encoding="utf-8"))
    assert "SecrecyItem" in data["_generated"]
    assert [i["code"] for i in data["items"]] == list(fs.CODES)
    assert {i["code"] for i in data["items"] if i["default"] == "FOLLOW_PROCESS_SECRET"} == {fs.AI_SECRECY_AUDIT}


# ------------------------------------------------------------------ c: 出境口


class _Upstream:
    def __init__(self):
        self.bodies = []

    def handler(self, request):
        self.bodies.append(json.loads(request.content))
        return httpx.Response(200, json={"choices": [{"message": {"content": "好"}}]})


@pytest.fixture()
def wrapper(monkeypatch):
    up = _Upstream()

    async def no_names():
        return []

    monkeypatch.setattr(lc, "_protected_names_for_egress", no_names)
    return up, lc._RedactingLLMClient(httpx.AsyncClient(transport=httpx.MockTransport(up.handler)))


async def _post_as(factory, client, system=None):
    from common.llm_metrics import _llm_factory
    token = _llm_factory.set(factory)
    try:
        messages = [{"role": "user", "content": SECRET}]
        if system is not None:
            messages.insert(0, {"role": "system", "content": system})
        await client.post("https://llm.test/v1/chat/completions", json={"model": "m", "messages": messages})
    finally:
        _llm_factory.reset(token)


@pytest.mark.asyncio
async def test_egress_redaction_off_sends_the_original_text_only_for_that_known_factory(settings, wrapper):
    table, _ = settings
    up, client = wrapper
    _set(table, F, fs.EGRESS_REDACTION, True)
    await _post_as(F, client)
    _set(table, F, fs.EGRESS_REDACTION, False)
    await _post_as(F, client)
    on, off = (json.dumps(b, ensure_ascii=False) for b in up.bodies)
    assert "赵铁柱" not in on and "13912345678" not in on and "[人员A]" in on
    assert "赵铁柱" in off and "13912345678" in off and "[人员A]" not in off


@pytest.mark.asyncio
async def test_an_unknown_egress_factory_is_still_redacted_or_refused_with_every_factory_off(settings, wrapper, monkeypatch):
    table, _ = settings
    up, client = wrapper
    for factory in (F, "F-OTHER"):
        _set(table, factory, fs.EGRESS_REDACTION, False)
    # 工厂不明: 名单查询照旧要求工厂 → 拒发 (这里把名单查询换成「没有工厂就拒」的同款行为)
    from common.llm_client import EgressRedactionError

    async def needs_factory():
        raise lc.NoEgressFactoryError("no factory context for egress")

    monkeypatch.setattr(lc, "_protected_names_for_egress", needs_factory)
    with pytest.raises(EgressRedactionError):
        await _post_as(None, client)
    with pytest.raises(EgressRedactionError):
        await _post_as("PLATFORM", client)
    assert up.bodies == []


@pytest.mark.asyncio
async def test_a_failed_config_read_keeps_redacting(settings, wrapper):
    table, _ = settings
    up, client = wrapper
    table["__broken__"] = True
    await _post_as(F, client)
    assert "赵铁柱" not in json.dumps(up.bodies, ensure_ascii=False)


@pytest.mark.asyncio
async def test_food_kb_rerank_passes_through_only_when_the_known_factory_turned_redaction_off(settings, monkeypatch):
    from common.llm_metrics import _llm_factory
    from food_kb.services.reranker import DashScopeReranker

    table, _ = settings
    seen = []

    def handler(request):
        seen.append(json.loads(request.content))
        return httpx.Response(200, json={"output": {"results": [{"index": 0, "relevance_score": 0.9}]}})

    async def no_names():
        return []

    monkeypatch.setattr(lc, "_protected_names_for_egress", no_names)
    rr = DashScopeReranker()
    rr.configure(api_key="k", model="qwen3-rerank", enabled=True)
    rr._client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    docs = [{"title": "供应商", "content": SECRET}, {"title": "其他", "content": "无"}]
    token = _llm_factory.set(F)
    try:
        _set(table, F, fs.EGRESS_REDACTION, True)
        await rr.rerank("赵铁柱的供货", docs, top_n=1)
        _set(table, F, fs.EGRESS_REDACTION, False)
        await rr.rerank("赵铁柱的供货", docs, top_n=1)
    finally:
        _llm_factory.reset(token)
    on, off = (json.dumps(b, ensure_ascii=False) for b in seen)
    assert "赵铁柱" not in on and "赵铁柱" in off


def test_video_analyzer_prompt_passes_through_only_with_a_cached_off_for_the_known_factory(settings, monkeypatch):
    from common.llm_metrics import _llm_factory
    from efficiency_recognition.services import video_analyzer as va

    table, _ = settings
    sent = []

    class _Resp:
        status_code = 200

        def raise_for_status(self):
            return None

        def json(self):
            return {"choices": [{"message": {"content": "{}"}}]}

    analyzer = va.VideoEfficiencyAnalyzer.__new__(va.VideoEfficiencyAnalyzer)
    analyzer.base_url = "https://llm.test/v1"
    analyzer.api_key = "k"
    analyzer.model = "m"

    class _Client:
        def post(self, url, headers=None, json=None, **kw):
            sent.append(json)
            return _Resp()

    analyzer.client = _Client()
    context = {"factory_type": "联系赵铁柱 13912345678", "camera_enabled": True}
    token = _llm_factory.set(F)
    try:
        import asyncio
        _set(table, F, fs.EGRESS_REDACTION, False)
        asyncio.run(fs.is_on(F, fs.EGRESS_REDACTION))          # 缓存里有「关」
        _call_analyze_frame(analyzer, context)
        fs.invalidate(F)                                       # 缓存没了 → 同步路径按开处理
        _call_analyze_frame(analyzer, context)
    finally:
        _llm_factory.reset(token)
    texts = [json_dumps(s) for s in sent]
    assert len(texts) == 2, "两次都要真的发出去, 否则下面的对照无意义"
    assert "13912345678" in texts[0]
    assert "13912345678" not in texts[1]


def json_dumps(value):
    return json.dumps(value, ensure_ascii=False)


def _call_analyze_frame(analyzer, context):
    return analyzer.analyze_frame("aGVsbG8=", 0, context)


# ------------------------------------------------------------------ a: 提示词约束拆句


def test_the_harness_is_split_into_an_unconditional_cross_factory_sentence_and_a_process_secret_clause():
    assert PROCESS_SECRET_HARNESS == CROSS_FACTORY_HARNESS + PROCESS_SECRET_CLAUSE
    assert "其他工厂" in CROSS_FACTORY_HARNESS and "工艺保密" not in CROSS_FACTORY_HARNESS
    assert "工艺保密产品" in PROCESS_SECRET_CLAUSE and "其他工厂" not in PROCESS_SECRET_CLAUSE
    payload = {"messages": [{"role": "system", "content": with_process_secret_harness("你是分析师")},
                            {"role": "user", "content": PROCESS_SECRET_CLAUSE}]}
    out = without_process_secret_clause(payload)
    assert out["messages"][0]["content"].startswith(CROSS_FACTORY_HARNESS)
    assert PROCESS_SECRET_CLAUSE not in out["messages"][0]["content"]
    assert out["messages"][1]["content"] == PROCESS_SECRET_CLAUSE   # 只动系统消息
    parts = {"messages": [{"role": "system", "content": [{"type": "text", "text": with_process_secret_harness("x")}]}]}
    assert PROCESS_SECRET_CLAUSE not in without_process_secret_clause(parts)["messages"][0]["content"][0]["text"]
    assert payload["messages"][0]["content"].startswith(PROCESS_SECRET_HARNESS)   # 不改入参


@pytest.mark.asyncio
async def test_the_egress_drops_the_process_secret_clause_only_when_a_and_e_are_both_off(settings, wrapper):
    table, _ = settings
    up, client = wrapper
    system = with_process_secret_harness("你是成本分析师。")
    _set(table, F, fs.PROCESS_SECRET, True)
    await _post_as(F, client, system)
    _set(table, F, fs.PROCESS_SECRET, False)
    await _post_as(F, client, system)
    _set(table, F, fs.CUSTOMER_HELD, True)          # e 与 a 无关: e 开着照带
    await _post_as(F, client, system)
    texts = [b["messages"][0]["content"] for b in up.bodies]
    assert PROCESS_SECRET_CLAUSE in texts[0]
    assert PROCESS_SECRET_CLAUSE not in texts[1] and CROSS_FACTORY_HARNESS in texts[1]
    assert PROCESS_SECRET_CLAUSE in texts[2]


# ------------------------------------------------------------------ a: 成本授权的 fail-closed 路径


@pytest.mark.asyncio
async def test_cost_scope_without_a_request_is_full_only_when_a_and_e_are_off(settings):
    from smartbi.services.factory_charts import cost_scope

    table, _ = settings
    resolver = cost_scope.CostScopeResolver(None, F)        # 没有请求身份 (内部调用)
    _set(table, F, fs.PROCESS_SECRET, True)
    assert (await resolver.scope_for(["P1"])).is_unavailable
    _set(table, F, fs.PROCESS_SECRET, False)
    assert (await resolver.scope_for(["P1"])).is_full
    _set(table, F, fs.CUSTOMER_HELD, True)
    assert (await resolver.scope_for(["P1"])).is_unavailable
    table["__broken__"] = True
    fs.invalidate(F)
    assert (await cost_scope.CostScopeResolver(None, F).scope_for(["P1"])).is_unavailable, "读不到配置按开启"


@pytest.mark.asyncio
async def test_cost_scope_java_failure_is_full_only_when_a_and_e_are_off(settings, monkeypatch):
    from smartbi.services.factory_charts import cost_authorization, cost_scope

    table, _ = settings

    async def java_down(*a, **k):
        raise cost_authorization.CostAuthorizationError("java authority unavailable")

    monkeypatch.setattr(cost_authorization, "authorize_batches", java_down)
    _set(table, F, fs.PROCESS_SECRET, True)
    assert (await cost_scope.CostScopeResolver(object(), F).scope_for(["P1"])).is_unavailable
    _set(table, F, fs.PROCESS_SECRET, False)
    assert (await cost_scope.CostScopeResolver(object(), F).scope_for(["P1"])).is_full
