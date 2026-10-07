"""function calling 的合法答案是 `tool_calls`，正文为空是**正常**的 —— 别判成 empty。

🔴 2026-10-06 生产实测 (python-service.log 11:54:33-35, Java 的 AI 参数提取):

    POST /api/llm/tool-call  (slot=chat, tools=[...])
      aistore/DeepSeek-V4-Flash-A   refused (expired)
      deepseek/deepseek-v4-flash    HTTP 401
      aliyun_c_vl/qwen3.8-flash     HTTP 200 -> "output invalid (empty) — falling back"
      aliyun_a_vl/qwen3.8-flash     HTTP 200 -> "output invalid (empty) — falling back"
      aliyun_b_vl/qwen3.8-flash     403 quota
      zhipu/glm-4.5-air             429
    -> "All providers exhausted for chat" -> 裸 500 -> 用户被再问一遍已经说过的任务号

两个 HTTP 200 不是坏答案: qwen 在 function calling 里返回
`{"content": "", "tool_calls": [{"function": {"name", "arguments"}}]}`，
而 `call_chain` 的 Layer-4 校验只看 `content`。

这个文件钉两件事:
  A. 路由层: 带 tools 的请求拿到合法 tool_calls，正文空也算成功 (第一家就收, 不回退);
     ⛔ 但「正文空且没有 tool_calls」「没要 tools 却空正文」「tool_calls 畸形」照旧拒。
  B. 端点: 全链耗尽时 /api/llm/tool-call 给明确的 503 + 项目统一错误体, ⛔ 不是裸 500。

每条「被拒」的断言都配一条「必收」的对照 (同一个测试里, 否则恒真)。
"""
from __future__ import annotations

import datetime
import json
from typing import Any, Dict, List, Tuple
from unittest.mock import MagicMock

import pytest

from common import llm_router
from common.llm_router import SLOT, call_chain

# 2026-10-07: aistore / deepseek 清出注册表(Steve 复审裁定), 夹具换成在册的阿里云两个
# 逻辑账号 —— 正好也是 10-06 那次生产事故里**真的回了 200 + tool_calls** 的两跳。
_CHAIN = [("aliyun_c_vl", "qwen3.8-flash"), ("aliyun_a_vl", "qwen3.8-flash")]
#: 两跳同一个 DashScope 端点, 只能按 Authorization 里的 key 认账号。
_KEY_TO_ACCOUNT = {"key_c_fake": "aliyun_c_vl", "key_a_fake": "aliyun_a_vl"}

_TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "extract_params",
            "description": "extract tool params",
            "parameters": {"type": "object", "properties": {"taskId": {"type": "integer"}}},
        },
    }
]


def _resp(status: int, body: str = "", payload: Dict[str, Any] | None = None) -> MagicMock:
    r = MagicMock()
    r.status_code = status
    r.text = body or json.dumps(payload or {})
    r.json = MagicMock(return_value=payload or {})
    return r


def _completion(content: str, tool_calls: Any = None) -> Dict[str, Any]:
    msg: Dict[str, Any] = {"role": "assistant", "content": content}
    if tool_calls is not None:
        msg["tool_calls"] = tool_calls
    return {"model": "stub", "choices": [{"index": 0, "message": msg, "finish_reason": "stop"}]}


def _qwen_tool_call(arguments: Any = '{"taskId": 1827}', name: str = "extract_params") -> List[Dict[str, Any]]:
    return [
        {
            "id": "call_1",
            "type": "function",
            "index": 0,
            "function": {"name": name, "arguments": arguments},
        }
    ]


class _Client:
    """httpx.AsyncClient 替身: 按 URL 判账号, 返回罐头响应, 记录每次尝试。"""

    def __init__(self, by_account: Dict[str, MagicMock]):
        self.by_account = by_account
        self.call_log: List[Tuple[str, str]] = []

    async def post(self, url, headers=None, json=None, timeout=None):  # noqa: A002
        auth = (headers or {}).get("Authorization", "")
        account = next((a for k, a in _KEY_TO_ACCOUNT.items() if k in auth), "?")
        self.call_log.append((account, (json or {}).get("model", "?")))
        return self.by_account[account]


@pytest.fixture(autouse=True)
def _chain(monkeypatch):
    llm_router._CB_FAILURES.clear()
    llm_router._CB_LAST_FAIL.clear()
    llm_router._QUOTA_EXHAUSTED_UNTIL.clear()
    monkeypatch.setenv("LLM_ALIYUN_C_API_KEY", "key_c_fake")
    monkeypatch.setenv("LLM_ALIYUN_A_API_KEY", "key_a_fake")
    monkeypatch.setattr(llm_router, "_today", lambda: datetime.date(2026, 10, 7))
    monkeypatch.setitem(llm_router.SLOT_MODELS, SLOT.CHAT, _CHAIN)
    yield
    llm_router._CB_FAILURES.clear()
    llm_router._CB_LAST_FAIL.clear()
    llm_router._QUOTA_EXHAUSTED_UNTIL.clear()


def _install(monkeypatch, by_account: Dict[str, MagicMock]) -> _Client:
    client = _Client(by_account)
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)
    return client


_MSG = [{"role": "user", "content": "开始1827号工序任务"}]
_GOOD_TEXT = _resp(200, payload=_completion("今日入库3批，均合格。"))


# ════════════════════════════════════════════════════════════════════════
# A. call_chain: tool_calls 是合法答案
# ════════════════════════════════════════════════════════════════════════

@pytest.mark.asyncio
async def test_empty_content_with_tool_calls_is_accepted_on_first_provider(monkeypatch):
    qwen_like = _completion("", _qwen_tool_call())
    client = _install(monkeypatch, {
        "aliyun_c_vl":_resp(200, payload=qwen_like),
        "aliyun_a_vl":_GOOD_TEXT,
    })

    result = await call_chain(SLOT.CHAT, {"messages": _MSG, "tools": _TOOLS, "tool_choice": "auto"})

    assert client.call_log == [_CHAIN[0]], f"应第一家就收, 实际尝试了 {client.call_log}"
    assert result == qwen_like
    calls = result["choices"][0]["message"]["tool_calls"]
    assert calls[0]["function"]["name"] == "extract_params"
    assert json.loads(calls[0]["function"]["arguments"]) == {"taskId": 1827}
    # 对照: 熔断计数也要记成功 (被判 invalid 的不会记)
    assert llm_router._CB_FAILURES.get(f"{_CHAIN[0][0]}/{_CHAIN[0][1]}", 0) == 0


@pytest.mark.asyncio
async def test_dict_arguments_are_also_well_formed(monkeypatch):
    """部分兼容网关把 arguments 直接给对象而不是 JSON 串 —— 同样是合法的。"""
    body = _completion("", _qwen_tool_call(arguments={"taskId": 1827}))
    client = _install(monkeypatch, {"aliyun_c_vl":_resp(200, payload=body), "aliyun_a_vl":_GOOD_TEXT})
    result = await call_chain(SLOT.CHAT, {"messages": _MSG, "tools": _TOOLS})
    assert result == body
    assert client.call_log == [_CHAIN[0]]


@pytest.mark.asyncio
async def test_control_empty_content_without_tool_calls_still_falls_back(monkeypatch):
    """对照: 正文空、也没有 tool_calls (带 tools 的请求) —— 仍判 empty, 落下一跳。"""
    client = _install(monkeypatch, {
        "aliyun_c_vl":_resp(200, payload=_completion("")),
        "aliyun_a_vl":_GOOD_TEXT,
    })
    result = await call_chain(SLOT.CHAT, {"messages": _MSG, "tools": _TOOLS})
    assert client.call_log == _CHAIN, f"空答案必须回退到第二家, 实际 {client.call_log}"
    assert result == _GOOD_TEXT.json()


@pytest.mark.asyncio
async def test_control_normal_chat_empty_content_still_rejected(monkeypatch):
    """对照: 普通聊天 (没要 tools) 的空正文照旧拒。"""
    client = _install(monkeypatch, {
        "aliyun_c_vl":_resp(200, payload=_completion("")),
        "aliyun_a_vl":_GOOD_TEXT,
    })
    result = await call_chain(SLOT.CHAT, {"messages": _MSG})
    assert client.call_log == _CHAIN
    assert result == _GOOD_TEXT.json()


@pytest.mark.asyncio
async def test_control_tool_calls_without_a_tools_request_do_not_rescue_empty_content(monkeypatch):
    """对照: 没要 tools 却带回 tool_calls + 空正文 —— 不是调用方要的东西, 仍拒。"""
    client = _install(monkeypatch, {
        "aliyun_c_vl":_resp(200, payload=_completion("", _qwen_tool_call())),
        "aliyun_a_vl":_GOOD_TEXT,
    })
    result = await call_chain(SLOT.CHAT, {"messages": _MSG})
    assert client.call_log == _CHAIN
    assert result == _GOOD_TEXT.json()


@pytest.mark.parametrize(
    "bad_calls",
    [
        [],                                                              # 空数组
        "not-a-list",                                                    # 类型不对
        [{"type": "function"}],                                          # 没有 function
        [{"function": {"name": "", "arguments": "{}"}}],                 # 空名字
        [{"function": {"name": "  ", "arguments": "{}"}}],               # 空白名字
        [{"function": {"name": "extract_params", "arguments": "{oops"}}],  # arguments 不是 JSON
        [{"function": {"name": "extract_params", "arguments": "[1,2]"}}],  # arguments 不是对象
        [{"function": {"name": "extract_params"}}],                      # 没有 arguments
        [_qwen_tool_call()[0], {"function": {"name": "x", "arguments": "{bad"}}],  # 一好一坏 → 整体拒
    ],
    ids=["empty-list", "not-list", "no-function", "empty-name", "blank-name",
         "args-not-json", "args-not-object", "no-args", "one-good-one-bad"],
)
@pytest.mark.asyncio
async def test_control_malformed_tool_calls_are_rejected(monkeypatch, bad_calls):
    """对照: 畸形 tool_calls 不能被当成合法答案 (否则「放宽」变成「什么都收」)。"""
    client = _install(monkeypatch, {
        "aliyun_c_vl":_resp(200, payload=_completion("", bad_calls)),
        "aliyun_a_vl":_GOOD_TEXT,
    })
    result = await call_chain(SLOT.CHAT, {"messages": _MSG, "tools": _TOOLS})
    assert client.call_log == _CHAIN, f"畸形 tool_calls 应回退, 实际 {client.call_log}"
    assert result == _GOOD_TEXT.json()


@pytest.mark.asyncio
async def test_non_empty_content_behaviour_is_unchanged(monkeypatch):
    """既有行为不动: 带 tools 但正文有字 —— 照旧按正文收。"""
    body = _completion("好的，我来开始。", _qwen_tool_call())
    client = _install(monkeypatch, {"aliyun_c_vl":_resp(200, payload=body), "aliyun_a_vl":_GOOD_TEXT})
    result = await call_chain(SLOT.CHAT, {"messages": _MSG, "tools": _TOOLS})
    assert result == body
    assert client.call_log == [_CHAIN[0]]


# ════════════════════════════════════════════════════════════════════════
# B. /api/llm/tool-call 端点: tool_calls 送得到调用方; 耗尽给明确错误体
# ════════════════════════════════════════════════════════════════════════

def _http_client():
    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    from llm.api.endpoints import router

    app = FastAPI()
    app.include_router(router, prefix="/api/llm")
    return TestClient(app, raise_server_exceptions=False)


def _tool_call_body() -> Dict[str, Any]:
    return {"messages": _MSG, "slot": "chat", "tools": _TOOLS, "tool_choice": "auto"}


def test_endpoint_returns_tool_calls_to_the_caller(monkeypatch):
    qwen_like = _completion("", _qwen_tool_call())
    client = _install(monkeypatch, {"aliyun_c_vl":_resp(200, payload=qwen_like), "aliyun_a_vl":_GOOD_TEXT})

    resp = _http_client().post("/api/llm/tool-call", json=_tool_call_body())

    assert resp.status_code == 200, resp.text
    msg = resp.json()["choices"][0]["message"]
    assert msg["content"] == ""
    assert msg["tool_calls"][0]["function"]["name"] == "extract_params"
    assert json.loads(msg["tool_calls"][0]["function"]["arguments"]) == {"taskId": 1827}
    assert client.call_log == [_CHAIN[0]]


def test_endpoint_exhaustion_is_an_explicit_503_not_a_bare_500(monkeypatch):
    """生产那一刻的形状: 每家都没给出可用答案。

    对照 (同一个测试里): 同一个端点, 一家给出合法答案 -> 200。
    """
    # 必收对照先跑: 证明这条路在「有答案」时是通的, 下面的 503 才有意义
    _install(monkeypatch, {"aliyun_c_vl":_resp(200, payload=_completion("", _qwen_tool_call())),
                           "aliyun_a_vl":_GOOD_TEXT})
    assert _http_client().post("/api/llm/tool-call", json=_tool_call_body()).status_code == 200

    llm_router._CB_FAILURES.clear()
    llm_router._CB_LAST_FAIL.clear()
    client = _install(monkeypatch, {
        "aliyun_c_vl":_resp(200, payload=_completion("")),              # 空答案
        "aliyun_a_vl":_resp(401, "invalid api key"),                   # 死 key
    })
    resp = _http_client().post("/api/llm/tool-call", json=_tool_call_body())

    assert client.call_log == _CHAIN, "两家都应被尝试过, 否则不是「耗尽」"
    assert resp.status_code == 503, f"耗尽必须是 503 不是 500, 实际 {resp.status_code}: {resp.text}"
    body = resp.json()
    assert body["success"] is False
    assert body["code"] == "SERVICE_UNAVAILABLE"
    assert body["code"] != "INTERNAL_ERROR", "Java 要能把它和真正的内部错误区分开"
    assert body["data"]["reason"] == "LLM_PROVIDERS_EXHAUSTED"
    assert body["data"]["slot"] == "chat"
    # 每家为什么失败要带出来 (运维看得见是 empty 还是 401)
    assert "invalid_empty" in body["message"]
    assert "http 401" in body["message"]


def test_endpoint_unrelated_runtime_error_is_not_mislabelled_as_exhaustion(monkeypatch):
    """对照: 不是「全链耗尽」的 RuntimeError 不能被装成 503 —— 那会把真 bug 藏进「供应商不可用」。"""
    async def boom(*_a, **_k):
        raise RuntimeError("some unrelated bug")

    import llm.api.endpoints as ep
    monkeypatch.setattr(ep, "call_chain", boom)
    resp = _http_client().post("/api/llm/tool-call", json=_tool_call_body())
    assert resp.status_code == 500
