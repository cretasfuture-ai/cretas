"""P0 — 共享客户端脱敏包装层单测: 验证所有 LLM 出境 payload 在发出前被脱敏。"""
from __future__ import annotations

import pandas as pd
import pytest

import common.llm_client as lc
from common.llm_client import _RedactingLLMClient
from common.llm_redactor import redaction_scope, register_df_in_scope


class _FakeResp:
    status_code = 200

    def json(self):
        return {"choices": [{"message": {"content": "ok"}}]}


class _FakeInner:
    """Captures the json actually handed to the real client (= what would egress)."""

    def __init__(self):
        self.last_json = None
        self.last_url = None

    async def post(self, url, *, json=None, **kw):
        self.last_url = url
        self.last_json = json
        return _FakeResp()

    def stream(self, method, url, *, json=None, **kw):
        self.last_url = url
        self.last_json = json

        class _CM:
            async def __aenter__(self_):
                return _FakeResp()

            async def __aexit__(self_, *a):
                return False

        return _CM()


URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"


@pytest.mark.asyncio
async def test_wrapper_redacts_pii_floor_no_scope():
    inner = _FakeInner()
    client = _RedactingLLMClient(inner)
    payload = {"messages": [{"role": "user", "content": "我电话 13812345678"}]}
    await client.post(URL, json=payload)
    sent = inner.last_json["messages"][0]["content"]
    assert "13812345678" not in sent  # PII floor applies even with no scope


@pytest.mark.asyncio
async def test_wrapper_redacts_chinese_names_with_scope():
    inner = _FakeInner()
    client = _RedactingLLMClient(inner)
    df = pd.DataFrame({"门店名称": ["青花椒大融城店"], "营业额": [12000]})
    with redaction_scope():
        register_df_in_scope(df)
        payload = {"messages": [{"role": "user", "content": "青花椒大融城店 营收 12000 最高"}]}
        await client.post(URL, json=payload)
    sent = inner.last_json["messages"][0]["content"]
    assert "青花椒大融城店" not in sent     # 客户专名不出境
    assert "门店A" in sent
    assert "12000" in sent                  # 数字不动


@pytest.mark.asyncio
async def test_wrapper_redacts_stream_path():
    inner = _FakeInner()
    client = _RedactingLLMClient(inner)
    payload = {"messages": [{"role": "user", "content": "邮箱 a@b.com"}]}
    cm = client.stream("POST", URL, json=payload)
    async with cm:
        pass
    assert "a@b.com" not in inner.last_json["messages"][0]["content"]


@pytest.mark.asyncio
async def test_wrapper_passthrough_non_llm_url():
    inner = _FakeInner()
    client = _RedactingLLMClient(inner)
    payload = {"messages": [{"role": "user", "content": "13812345678"}]}
    await client.post("https://example.com/other", json=payload)
    # 非 LLM URL 不脱敏 (原样)
    assert inner.last_json["messages"][0]["content"] == "13812345678"


@pytest.mark.asyncio
async def test_wrapper_fail_closed_on_redactor_error(monkeypatch):
    inner = _FakeInner()
    client = _RedactingLLMClient(inner)

    def _boom(_payload):
        raise RuntimeError("redactor exploded")

    monkeypatch.setattr("common.llm_redactor.redact_payload_for_egress", _boom)
    payload = {"messages": [{"role": "user", "content": "13812345678"}]}
    # fail-closed: 脱敏异常 → 请求被拦, 原文一个字都不出境
    with pytest.raises(lc.EgressRedactionError):
        await client.post(URL, json=payload)
    assert inner.last_json is None


@pytest.mark.asyncio
async def test_wrapper_stream_fail_closed_on_redactor_error(monkeypatch):
    inner = _FakeInner()
    client = _RedactingLLMClient(inner)

    def _boom(_payload, _names=None):
        raise RuntimeError("redactor exploded")

    monkeypatch.setattr("common.llm_redactor.redact_payload_for_egress", _boom)
    payload = {"messages": [{"role": "user", "content": "13812345678"}]}
    with pytest.raises(lc.EgressRedactionError):
        # 流式在进入时脱敏 (要先异步查保密产品名)
        async with client.stream("POST", URL, json=payload):
            pass
    assert inner.last_json is None


@pytest.mark.asyncio
async def test_wrapper_redacts_successfully_after_previous_failure(monkeypatch):
    """阳性对照: 同一个 client 在脱敏正常时仍然能发出 (证明不是整个入口坏了)。"""
    inner = _FakeInner()
    client = _RedactingLLMClient(inner)
    payload = {"messages": [{"role": "user", "content": "13812345678"}]}
    async with client.stream("POST", URL, json=payload):
        pass
    assert inner.last_json is not None
    assert inner.last_json["messages"][0]["content"] != "13812345678"


@pytest.mark.asyncio
async def test_get_llm_http_client_returns_wrapper():
    c = lc.get_llm_http_client()
    assert isinstance(c, _RedactingLLMClient)
    # 委托属性可达
    assert hasattr(c, "post") and hasattr(c, "stream")


if __name__ == "__main__":
    raise SystemExit(pytest.main([__file__, "-v"]))
