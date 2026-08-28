"""具名 tool_choice 必须明确 400，⛔ 不许烂成 500、也⛔ 不许被悄悄降级成 auto。

🔴 2026-08-28 生产实测（同一个端点、同一份 tools）：

    tool_choice = "auto"                 -> HTTP 200，正常返回 tool_calls
    tool_choice = "get_inventory_stats"  -> HTTP 500 {"code":"INTERNAL_ERROR"}

Python 侧日志给出真因：

    [llm_router] slot=chat aistore/DeepSeek-V4-Flash-A error status=400
    InvalidParameter: tool_choice is one of the strings that should be ["none","auto"]

供应商只认 none/auto，具名工具是 OpenAI 风格的能力。路由原样透传拿到 400，
兜底链走完之后对外吐一个不说人话的 500 —— 调用方分不出「我传错了参数」还是「服务挂了」。

⚠️ 这条缺陷藏了多久不好说，但**藏住它的是一条恒绿的 Java 断言**
（`ToolExecutionTest.testForcedToolChoice` 拿到错误响应时走 else 分支 println 然后通过）。
"""
from __future__ import annotations

import pytest
from fastapi import HTTPException

from llm.api.endpoints import _SUPPORTED_TOOL_CHOICES, _validate_tool_choice


class TestSupportedValuesPass:
    """阳性对照 —— 供应商真的支持的取值必须放行。"""

    @pytest.mark.parametrize("value", sorted(_SUPPORTED_TOOL_CHOICES))
    def test_supported_string_passes(self, value: str) -> None:
        _validate_tool_choice(value)  # 不抛就是通过

    def test_none_passes(self) -> None:
        """没传 tool_choice 是绝大多数调用的形状，不能被误伤。"""
        _validate_tool_choice(None)


class TestUnsupportedValuesRejected:
    def test_named_tool_rejected_with_400(self) -> None:
        """生产上撞到的那个形状。"""
        with pytest.raises(HTTPException) as exc:
            _validate_tool_choice("get_inventory_stats")

        assert exc.value.status_code == 400, "必须是 4xx —— 这是调用方参数问题，不是服务故障"
        detail = str(exc.value.detail)
        assert "get_inventory_stats" in detail, "错误信息要说清是哪个值被拒了"
        assert "auto" in detail, "要告诉调用方能用什么"

    def test_openai_style_object_rejected(self) -> None:
        """OpenAI 的 {"type":"function","function":{"name":...}} 形式同样不支持。"""
        with pytest.raises(HTTPException) as exc:
            _validate_tool_choice(
                {"type": "function", "function": {"name": "get_inventory_stats"}}
            )
        assert exc.value.status_code == 400

    def test_required_rejected(self) -> None:
        """"required" 是 OpenAI 的取值，当前供应商同样不认 —— 别放它过去变 500。"""
        with pytest.raises(HTTPException) as exc:
            _validate_tool_choice("required")
        assert exc.value.status_code == 400


class TestNoSilentDowngrade:
    """⛔ 阴性对照：不许把具名工具悄悄改写成 auto。

    降级会让调用方以为强制生效了，而模型其实可以自由选别的工具或干脆不调
    —— 那是「返回假数据」的一种，本仓明令禁止。
    """

    def test_validator_never_returns_a_rewritten_value(self) -> None:
        # 校验器的契约是「放行或抛」，不产出新值 —— 没有任何降级的余地
        assert _validate_tool_choice("auto") is None

    def test_named_tool_does_not_fall_through(self) -> None:
        with pytest.raises(HTTPException):
            _validate_tool_choice("some_tool_name")


class TestWiredIntoEndpoints:
    """🔴 只测函数是不够的 —— 必须证明它**接在端点上**。

    2026-08-28 实测: 把 `_validate_tool_choice(req.tool_choice)` 从 `/tool-call`
    端点里删掉之后，上面那 8 条断言**全绿** —— 校验器好好的，只是没人调它，
    而 500 会原样回来。这就是本仓记过的「机制在、没接上」。

    ⇒ 这几条走真的 HTTP 入口（FastAPI TestClient），改坏接线就会红。
    ⚠️ 被拒的请求在 call_chain 之前就返回，所以**不产生任何网络调用**。
    """

    @staticmethod
    def _client():
        from fastapi import FastAPI
        from fastapi.testclient import TestClient

        from llm.api.endpoints import router

        app = FastAPI()
        app.include_router(router, prefix="/api/llm")
        return TestClient(app, raise_server_exceptions=False)

    @staticmethod
    def _body(tool_choice):
        return {
            "messages": [{"role": "user", "content": "显示库存统计"}],
            "slot": "chat",
            "tools": [
                {
                    "type": "function",
                    "function": {
                        "name": "get_inventory_stats",
                        "description": "获取库存统计数据",
                        "parameters": {"type": "object", "properties": {}},
                    },
                }
            ],
            "tool_choice": tool_choice,
        }

    def test_tool_call_endpoint_rejects_named_tool(self) -> None:
        resp = self._client().post(
            "/api/llm/tool-call", json=self._body("get_inventory_stats")
        )
        assert resp.status_code == 400, (
            f"具名 tool_choice 必须在入口被拒 (400)，实际 {resp.status_code} —— "
            "500 就说明校验没接上，生产那个 INTERNAL_ERROR 会回来"
        )
        assert "get_inventory_stats" in resp.text

    def test_chat_endpoint_rejects_named_tool(self) -> None:
        resp = self._client().post(
            "/api/llm/chat", json=self._body("get_inventory_stats")
        )
        assert resp.status_code == 400, "/chat 也收 tool_choice，同样要接上"
