"""Shanghai Telecom AI Store —— 通道已于 2026-10-07 清出, 本文件守它留下的东西。

🔴 2026-10-07 Steve 复审裁定「清掉死通道, 大模型后备只留我们自己的阿里云账号」:
   aistore 三条(09-13 已过期, prod 探针 401)从 `_SAFE_MODELS` / `_MINIMAL_SAFE_SET` /
   各槽池 / `_provider_config` 删除。「它真的不在了」由
   test_llm_router_registry.py::test_only_our_aliyun_accounts_remain_after_the_2026_10_07_ruling
   与 ::test_removed_channel_cannot_be_revived_by_env_alone 守。

⛔ 原来那几条「aistore 是五个槽的链首 / Qwen3-32B 只在 SIMPLE_TEXT / key 不回落」
   的断言随通道一起删 —— 它们的主语不在了, 改成别的模型去断言位置就不是同一件事。

本文件留下来守两件仍然成立的事:
  1. **thinking 翻译机制**(`_AISTORE_THINKING_OBJECT_MODELS` + `_apply_slot_params`
     的 aistore 分支)按「删条目不删机制」保留 —— 它的实测结论表与精确型号匹配
     仍然要钉住, 否则哪天加回来又是 #580 那种「少了 thinking 注入那一半」。
     ⚠️ 原版按 SLOT_MODELS 里**链上**的 aistore 条目遍历; 链上已经没有了,
     那样写会「一个都没扫到」(闸空转)。⇒ 改成**每个槽 × 登记表里每个型号**直接
     打 `_apply_slot_params`, 不再依赖链成员。
  2. 2xx-带-error-体 的回退(call_chain 与 call_chain_stream)—— 与账号无关的机制,
     原来借 aistore→deepseek 两跳来测, 现在借在册的 aliyun_c_vl→aliyun_a_vl。
"""

from __future__ import annotations

import datetime
import json

import pytest

from common import llm_router
from common.llm_router import SLOT, call_chain, call_chain_stream


@pytest.fixture(autouse=True)
def _reset_router_state():
    llm_router._CB_FAILURES.clear()
    llm_router._CB_LAST_FAIL.clear()
    llm_router._QUOTA_EXHAUSTED_UNTIL.clear()
    yield
    llm_router._CB_FAILURES.clear()
    llm_router._CB_LAST_FAIL.clear()
    llm_router._QUOTA_EXHAUSTED_UNTIL.clear()


# ⛔ 不能写成「所有 aistore 模型都必须拿到开关」—— 那是错的, 见 DeepSeek-V4-Flash。
_AISTORE_THINKING_VERDICT: dict[str, tuple[bool, str]] = {
    # 模型: (是否必须拿到关闭开关, 依据)
    "DeepSeek-V4-Flash-A": (
        True,
        "2026-08-15 生产端点实测: 带 thinking:disabled 1.43/1.43/1.56s、"
        "reasoning_tokens=None; 去掉该字段 8.03/8.13/8.95s、reasoning_tokens=800 "
        "(把 max_tokens 全烧在思考上)、content_len=0、finish_reason=length。",
    ),
    "DeepSeek-V4-Flash": (
        False,
        "2026-08-15 同批实测: 生产路径**不**注入该字段, 而它 2.17/2.24s、"
        "reasoning_tokens=None、finish_reason=stop ⇒ 这个变体默认就不思考。",
    ),
    "Qwen3-235B-A22B": (
        True,
        "随 _AISTORE_THINKING_OBJECT_MODELS 一并登记(2026-08-13 上线时的实测结论)。",
    ),
    "Qwen3-32B": (
        True,
        "同 Qwen3-235B-A22B。",
    ),
}


def test_aistore_thinking_translation_matches_the_recorded_verdict_on_every_slot():
    """机制钉的是**耦合**: 登记表的结论 × 槽 profile ⇒ 注入与否。

    与链成员无关(链上已经没有 aistore) —— 每个槽 × 每个登记型号直接过一遍
    `_apply_slot_params`。最后 assert 扫到的组合数 > 0, 防闸空转。
    """
    base = {"messages": [{"role": "user", "content": "return json"}]}
    checked = 0
    for slot in SLOT:
        wants_off = (llm_router._SLOT_PARAMS.get(slot) or {}).get(
            "enable_thinking") is False
        for model, (needs_switch, why) in _AISTORE_THINKING_VERDICT.items():
            expected = needs_switch and wants_off
            out = llm_router._apply_slot_params(slot, "aistore", model, base)
            got = out.get("thinking") == {"type": "disabled"}
            assert got == expected, (
                f"{slot.value}/{model}: 登记 needs_switch={needs_switch} × "
                f"槽 profile 要求关思考={wants_off} ⇒ 应注入={expected}, "
                f"实际={got}。依据: {why}"
            )
            # enable_thinking 是 DashScope 的参数, 不该出现在 aistore 的 payload 里
            assert "enable_thinking" not in out, (
                f"{slot.value}/{model}: 混进了 DashScope 的 enable_thinking"
            )
            checked += 1
    assert checked == len(list(SLOT)) * len(_AISTORE_THINKING_VERDICT)


def test_aistore_thinking_switch_is_keyed_by_the_exact_model_string():
    """阳性对照: 型号名差一个字母, 开关就静默失效 —— 这就是上面那道闸的理由。"""
    base = {"messages": [{"role": "user", "content": "return json"}]}
    real = llm_router._apply_slot_params(
        SLOT.REVIEW, "aistore", "DeepSeek-V4-Flash-A", base)
    assert real["thinking"] == {"type": "disabled"}

    typo = llm_router._apply_slot_params(
        SLOT.REVIEW, "aistore", "DeepSeek-V4-Flash-B", base)
    assert "thinking" not in typo, (
        "如果这条红了, 说明注入不再依赖精确型号名 —— 那是好事, "
        "上面那道闸可以放宽; 但要先确认新的判定依据是什么。"
    )


# ── 2xx + error 体的回退: 与账号无关的机制, 用在册的两个阿里云账号来测 ──────────
_TWO_HOPS = [
    ("aliyun_c_vl", "qwen3.8-flash"),
    ("aliyun_a_vl", "qwen3.8-flash"),
]


def _patch_two_hops(monkeypatch):
    monkeypatch.setenv("LLM_ALIYUN_C_API_KEY", "fake-c")
    monkeypatch.setenv("LLM_ALIYUN_A_API_KEY", "fake-a")
    monkeypatch.setattr(llm_router, "_today", lambda: datetime.date(2026, 10, 7))
    monkeypatch.setitem(llm_router.SLOT_MODELS, SLOT.CHAT, list(_TWO_HOPS))


class _Response:
    status_code = 200

    def __init__(self, body):
        self._body = body
        self.text = json.dumps(body, ensure_ascii=False)

    def json(self):
        return self._body


@pytest.mark.asyncio
async def test_http_200_error_body_falls_back(monkeypatch):
    _patch_two_hops(monkeypatch)
    good = {"choices": [{"message": {"content": "后备模型正常返回"}}]}

    class _Client:
        def __init__(self):
            self.calls = 0

        async def post(self, *_args, **_kwargs):
            self.calls += 1
            if self.calls == 1:
                return _Response({"error": {"code": "model_not_found"}})
            return _Response(good)

    client = _Client()
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)
    result = await call_chain(
        SLOT.CHAT,
        {"messages": [{"role": "user", "content": "测试"}]},
    )
    assert result == good
    assert client.calls == 2


@pytest.mark.asyncio
async def test_stream_error_event_before_content_falls_back(monkeypatch):
    _patch_two_hops(monkeypatch)

    class _StreamResponse:
        status_code = 200

        def __init__(self, lines):
            self._lines = lines

        async def aiter_lines(self):
            for line in self._lines:
                yield line

    class _StreamContext:
        def __init__(self, response):
            self.response = response

        async def __aenter__(self):
            return self.response

        async def __aexit__(self, *_args):
            return False

    class _Client:
        def __init__(self):
            self.calls = 0

        def stream(self, *_args, **_kwargs):
            self.calls += 1
            if self.calls == 1:
                lines = ['data: {"error":{"code":"model_not_found"}}', "data: [DONE]"]
            else:
                lines = [
                    'data: {"choices":[{"delta":{"content":"后备流正常"}}]}',
                    "data: [DONE]",
                ]
            return _StreamContext(_StreamResponse(lines))

    client = _Client()
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)
    events = [
        event
        async for event in call_chain_stream(
            SLOT.CHAT,
            {"messages": [{"role": "user", "content": "测试"}]},
        )
    ]
    assert events == [{"type": "delta", "text": "后备流正常"}]
    assert client.calls == 2
