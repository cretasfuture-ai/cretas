"""第四批: Python 外发出口按 Java 同一套规则脱敏, 回答换回真名 (Steve 2026-10-27)。

只把最内层的 HTTP (httpx MockTransport) 换成假的: 出境口 (common.llm_client._RedactingLLMClient)、
llm_router.call_chain / call_chain_stream、各出口函数都是真的。断言看「真正发到网上的请求体」。

覆盖的出口 (代表各类走法):
- 洞察纯文本 generate_text_analysis (call_chain 非流式) —— 公司名 / 人名 / 号码不出境, 回答还原 (含去括号、全角括号)
- 洞察流式 call_llm_stream_text (call_chain_stream) —— 代号被拆在两段增量里也能还原
- Excel 结构分析 LLMStructureAnalyzer._call_llm (call_chain)
- 意图匹配 ai.matcher.llm._call_llm —— 原话模式: 只抹号码, 公司名人名原样 (与 Java 一致)
- 食品知识库重排 (自建客户端): 脱敏后才发; 脱敏不了不发, 用原顺序
- 保密产品名按本厂现查; 查不了不发
- 闸: 往外部模型发请求的模块只能走出境口, 例外逐个登记
"""
from __future__ import annotations

import json
import re
from pathlib import Path

import httpx
import pytest

from common import llm_client as lc
from common import llm_router
from common.llm_router import SLOT

SECRET = "华东冷链商贸有限公司的质检员：赵铁柱，电话 13912345678"


class _Upstream:
    """假的模型服务: 记下收到的请求体, 回放预设回答 (非流式 JSON / 流式 SSE)。"""

    def __init__(self, answer: str = "", deltas=None):
        self.bodies = []
        self.answer = answer
        self.deltas = deltas

    def handler(self, request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        self.bodies.append(body)
        if body.get("stream"):
            lines = "".join(
                "data: " + json.dumps({"choices": [{"delta": {"content": d}}]}, ensure_ascii=False) + "\n\n"
                for d in self.deltas) + "data: [DONE]\n\n"
            return httpx.Response(200, content=lines.encode("utf-8"),
                                  headers={"content-type": "text/event-stream"})
        return httpx.Response(200, json={"choices": [{"message": {"content": self.answer}}]})

    def sent_text(self) -> str:
        return json.dumps(self.bodies, ensure_ascii=False)


@pytest.fixture()
def upstream(monkeypatch):
    up = _Upstream()
    inner = httpx.AsyncClient(transport=httpx.MockTransport(up.handler))
    wrapper = lc._RedactingLLMClient(inner)
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: wrapper)
    monkeypatch.setattr(lc, "get_llm_http_client", lambda: wrapper)
    for slot in SLOT:
        monkeypatch.setitem(llm_router.SLOT_MODELS, slot, [("aliyun_a", "qwen-plus")])
    monkeypatch.setattr(llm_router, "_refuse_reason", lambda a, m: None)
    monkeypatch.setattr(llm_router, "_cb_should_skip", lambda k: False)
    monkeypatch.setattr(llm_router, "_quota_should_skip", lambda k: False)
    monkeypatch.setattr(llm_router, "_provider_config", lambda a: ("https://llm.test/v1", "key"))

    async def no_names():
        return []

    monkeypatch.setattr(lc, "_protected_names_for_egress", no_names)
    return up


def _assert_nothing_secret_sent(up: _Upstream):
    sent = up.sent_text()
    assert up.bodies, "没有发出任何请求 —— 断言无意义"
    for raw in ("华东冷链商贸有限公司", "赵铁柱", "13912345678"):
        assert raw not in sent, raw
    assert "[单位A]" in sent and "[人员A]" in sent and "[电话A]" in sent


@pytest.mark.asyncio
async def test_insight_text_analysis_sends_tokens_and_answers_with_real_names(upstream):
    from smartbi.services.insights.llm_client import generate_text_analysis

    upstream.answer = "单位A 的 【人员 A】 请回电 [电话A]"
    out = await generate_text_analysis(SECRET)

    _assert_nothing_secret_sent(upstream)
    assert "华东冷链商贸有限公司 的 赵铁柱 请回电 13912345678" in out


@pytest.mark.asyncio
async def test_insight_stream_restores_a_token_split_across_deltas(upstream):
    from smartbi.services.insights.llm_client import call_llm_stream_text

    upstream.deltas = ["结论：[单", "位A] 的质", "检员是人员", "A。"]
    chunks = [c async for c in call_llm_stream_text(SECRET)]
    text = "".join(c if isinstance(c, str) else c.get("text", "") for c in chunks)

    _assert_nothing_secret_sent(upstream)
    assert "结论：华东冷链商贸有限公司 的质检员是赵铁柱。" in text
    assert "[单" not in text and "人员A" not in text


@pytest.mark.asyncio
async def test_excel_structure_analysis_prompt_is_redacted(upstream):
    from smartbi.services.llm_structure_analyzer import LLMStructureAnalyzer

    upstream.answer = '{"header_row": 0, "note": "单位A"}'
    analyzer = LLMStructureAnalyzer.__new__(LLMStructureAnalyzer)
    out = await analyzer._call_llm("表头样本: 客户=" + SECRET)

    _assert_nothing_secret_sent(upstream)
    assert "华东冷链商贸有限公司" in out


@pytest.mark.asyncio
async def test_intent_matcher_keeps_the_original_wording_and_only_hides_numbers(upstream):
    from ai.matcher.llm import _call_llm

    upstream.answer = '{"intent_code": "SUPPLIER_QUERY"}'
    await _call_llm("用户: " + SECRET, timeout_s=5)

    sent = upstream.sent_text()
    assert "13912345678" not in sent and "[电话A]" in sent
    assert "华东冷链商贸有限公司" in sent and "赵铁柱" in sent  # 原话模式不认公司名人名 (同 Java utterance)


@pytest.mark.asyncio
async def test_protected_product_names_of_the_request_factory_are_hidden(upstream, monkeypatch):
    from common.llm_metrics import _llm_factory
    from smartbi.services.insights.llm_client import generate_text_analysis

    asked = []

    async def names():
        asked.append(_llm_factory.get())
        return ["秘制酱牛肉"]

    monkeypatch.setattr(lc, "_protected_names_for_egress", names)
    upstream.answer = "保密产品A 出成率偏低"
    token = _llm_factory.set("F006")
    try:
        out = await generate_text_analysis("秘制酱牛肉的出成率为什么低")
    finally:
        _llm_factory.reset(token)

    assert asked == ["F006"]
    assert "秘制酱牛肉" not in upstream.sent_text() and "[保密产品A]" in upstream.sent_text()
    assert "秘制酱牛肉 出成率偏低" in out


@pytest.mark.asyncio
async def test_when_protected_names_cannot_be_read_nothing_is_sent(upstream, monkeypatch):
    async def broken():
        raise RuntimeError("db down")

    monkeypatch.setattr(lc, "_protected_names_for_egress", broken)
    with pytest.raises(lc.EgressRedactionError):
        await llm_router.call_chain(SLOT.CHAT, {"messages": [{"role": "user", "content": SECRET}]})
    assert upstream.bodies == []


@pytest.mark.asyncio
async def test_a_refused_redaction_sends_nothing(upstream, monkeypatch):
    """脱敏后仍有残留 (规则层判定) → 出境口拒绝, 一个字节都不发。"""
    from common import llm_egress_text

    # 结果里带着文本: 只有「拒绝就不发」那一步能拦住它 (不是靠缺文本崩掉)
    monkeypatch.setattr(llm_egress_text, "redact_texts",
                        lambda texts, *a, **k: llm_egress_text.Result(texts=list(texts), refusal="残留手机号"))
    with pytest.raises(lc.EgressRedactionError):
        await llm_router.call_chain(SLOT.CHAT, {"messages": [{"role": "user", "content": SECRET}]})
    assert upstream.bodies == []


@pytest.mark.asyncio
async def test_protected_names_are_read_fresh_for_every_request(monkeypatch):
    """不走 60 秒缓存: 刚设为保密的产品名下一次请求就不能再发出去。"""
    from common.llm_metrics import _llm_factory
    queries = []

    class _Conn:
        async def fetch(self, sql, factory_id):
            queries.append(factory_id)
            return [{"name": "秘制酱牛肉"}] if len(queries) > 1 else []

    class _Acquire:
        async def __aenter__(self):
            return _Conn()

        async def __aexit__(self, *exc):
            return False

    class _Pool:
        def acquire(self):
            return _Acquire()

    async def pool():
        return _Pool()

    import smartbi.config as cfg
    monkeypatch.setattr(cfg, "get_cretas_pool", pool)
    token = _llm_factory.set("F006")
    try:
        first = await lc._protected_names_for_egress()
        second = await lc._protected_names_for_egress()
    finally:
        _llm_factory.reset(token)
    assert (first, second) == ([], ["秘制酱牛肉"])
    assert queries == ["F006", "F006"]


@pytest.mark.asyncio
async def test_protected_names_lookup_errors_propagate(monkeypatch):
    """库连不上 / 查询出错: 向上抛 (出境口据此不发), 不能当成「没有保密产品」。"""
    from common.llm_metrics import _llm_factory
    import smartbi.config as cfg

    async def broken_pool():
        raise RuntimeError("db down")

    monkeypatch.setattr(cfg, "get_cretas_pool", broken_pool)
    token = _llm_factory.set("F006")
    try:
        with pytest.raises(RuntimeError):
            await lc._protected_names_for_egress()
    finally:
        _llm_factory.reset(token)


@pytest.mark.asyncio
async def test_food_kb_rerank_sends_only_redacted_text(monkeypatch):
    from food_kb.services.reranker import DashScopeReranker

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

    out = await rr.rerank("赵铁柱 13912345678 的供货", docs, top_n=1)

    sent = json.dumps(seen, ensure_ascii=False)
    assert seen and "13912345678" not in sent and "华东冷链商贸有限公司" not in sent and "赵铁柱" not in sent
    assert out[0]["content"] == SECRET  # 返回的是原文档 (只用了分数)


@pytest.mark.asyncio
async def test_food_kb_rerank_does_not_send_when_redaction_fails(monkeypatch):
    from food_kb.services.reranker import DashScopeReranker

    seen = []

    async def broken():
        raise RuntimeError("db down")

    monkeypatch.setattr(lc, "_protected_names_for_egress", broken)
    rr = DashScopeReranker()
    rr.configure(api_key="k", model="qwen3-rerank", enabled=True)
    rr._client = httpx.AsyncClient(transport=httpx.MockTransport(lambda r: seen.append(r) or httpx.Response(200)))
    docs = [{"title": "a", "content": SECRET}, {"title": "b", "content": "x"}]

    out = await rr.rerank("问题", docs, top_n=2)

    assert seen == []
    assert [d["title"] for d in out] == ["a", "b"]


# ---------------------------------------------------------------- 闸: 只能走出境口

_PY_ROOT = Path(__file__).resolve().parents[1]
_LLM_HOSTS = re.compile(r"dashscope|aliyuncs\.com|/chat/completions|api\.deepseek|bigmodel|volces|openai\.com")
_OWN_CLIENT = re.compile(r"httpx\.(?:Async)?Client\(|requests\.(?:post|Session)\(|aiohttp\.ClientSession\(")
# 自己建 HTTP 客户端、又提到外部模型地址的模块: 每个都要有理由
REGISTERED = {
    "common/llm_client.py": "出境口本身 (内层真实客户端)",
    "food_kb/services/reranker.py": "重排接口不走 /chat/completions: 在发出前按同一套规则脱敏 (本文件有测试)",
    "common/vl_client.py": "视觉模型, 只发图片 + 固定提示词; 全仓没有调用方",
    "efficiency_recognition/services/video_analyzer.py": "视觉模型, 只发画面 + 固定提示词; 路由按摄像头开关拦截 (#1236)",
}


def _scanned_modules():
    for p in _PY_ROOT.rglob("*.py"):
        rel = p.relative_to(_PY_ROOT).as_posix()
        if "/tests/" in f"/{rel}" or rel.startswith(("tests/", "scripts/", "venv")) or "/scripts/" in rel \
                or rel.split("/")[-1].startswith("test_"):
            continue
        yield rel, p.read_text(encoding="utf-8", errors="replace")


def test_modules_that_call_external_models_go_through_the_egress_choke_point():
    offenders = {}
    seen_registered = set()
    for rel, src in _scanned_modules():
        if _OWN_CLIENT.search(src) and _LLM_HOSTS.search(src):
            if rel in REGISTERED:
                seen_registered.add(rel)
            else:
                offenders[rel] = "自建 HTTP 客户端又提到外部模型地址"
    assert offenders == {}, offenders
    # 登记的例外都还存在 (删了就从名单里删, 名单不能只增不减)
    assert seen_registered == set(REGISTERED), set(REGISTERED) - seen_registered
