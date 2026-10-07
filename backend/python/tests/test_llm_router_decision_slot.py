"""SLOT.DECISION (读概率决策) 的闸。

契约在 top_logprobs 上, 不在正文上: 模型不支持 logprobs 时是**静默**的(200, 没有字段),
所以这里守三件事 ——
  1. 池里每一条都有实测证据(`_LOGPROB_VERIFIED`), 且都在计费白名单里
  2. 不追加文本地板(地板没在这个契约下测过)
  3. 正文为空不是失败(首 token 可能是换行, 字母在 top-5 里); 拿不到概率由 body_validator 拒
"""
import asyncio
from unittest import mock

import pytest

from common import llm_router
from common.llm_router import SLOT


def test_decision_pool_is_exactly_the_verified_entries_in_written_order():
    chain = llm_router.SLOT_MODELS[SLOT.DECISION]
    assert chain, "DECISION 链为空 —— 意图决策层会整体回落旧路径"
    expected = [p for p in llm_router._LOGPROB_ORDER if p in llm_router._LOGPROB_VERIFIED]
    assert chain == expected
    for pair in chain:
        assert pair in llm_router._SAFE_MODELS, f"{pair} 有能力证据但不在计费白名单"
        # registry 超龄时也必须可用, 否则 21 天后决策层静默死掉(与 VL 同一个坑)
        assert pair in llm_router._MINIMAL_SAFE_SET, f"{pair} 不在 _MINIMAL_SAFE_SET"


def test_decision_pool_rejects_an_unverified_pair(monkeypatch):
    """阳性对照: 登记表里去掉一条, 它就不能出现在池里 —— 证明池真的由登记表决定。"""
    head = llm_router._LOGPROB_ORDER[0]
    trimmed = {k: v for k, v in llm_router._LOGPROB_VERIFIED.items() if k != head}
    pool = [p for p in llm_router._LOGPROB_ORDER if p in trimmed]
    assert head not in pool and len(pool) == len(llm_router.SLOT_MODELS[SLOT.DECISION]) - 1


def test_decision_has_no_text_floor():
    assert SLOT.DECISION in llm_router._NO_TEXT_TAIL_SLOTS
    # 2026-10-07: 原断言「zhipu 不在 DECISION 链上」—— zhipu 出局后它恒真。
    #    改成直接比: 链 == 登记表筛出的池, 一条都没被追加(文本地板的成员
    #    aliyun_*_vl qwen3.8-flash 有两条同时在池里, 所以不能只看「地板成员在不在」)。
    expected = [p for p in llm_router._LOGPROB_ORDER if p in llm_router._LOGPROB_VERIFIED]
    assert llm_router.SLOT_MODELS[SLOT.DECISION] == expected
    assert ("aliyun_b_vl", "qwen3.8-flash") not in llm_router.SLOT_MODELS[SLOT.DECISION], (
        "文本地板里不在 logprob 登记表的那条被追加进来了")


def test_empty_content_is_not_invalid_for_decision_but_is_for_mapper():
    assert llm_router._validate_output(SLOT.DECISION, "") is None
    assert llm_router._validate_output(SLOT.DECISION, "\n") is None
    # 对照: 同样的空正文在别的槽仍然是失败 —— 豁免只对 DECISION 生效
    assert llm_router._validate_output(SLOT.MAPPER, "") == "empty"


def test_decision_profile_turns_thinking_off_on_aliyun_accounts():
    p = llm_router._apply_slot_params(SLOT.DECISION, "aliyun_a_vl", "qwen3.7-flash", {"messages": []})
    assert p["enable_thinking"] is False
    assert p["temperature"] == 0


class _Resp:
    def __init__(self, body):
        self.status_code = 200
        self._body = body
        self.text = "{}"

    def json(self):
        return self._body


def _body(with_logprobs: bool):
    choice = {"message": {"content": "\n"}}
    if with_logprobs:
        choice["logprobs"] = {"content": [{"token": "B", "top_logprobs": [{"token": "B", "logprob": -0.1}]}]}
    return {"choices": [choice]}


def test_body_validator_moves_past_a_200_without_logprobs(monkeypatch):
    """第一跳 200 但没有 logprobs (静默不支持), 第二跳有 —— 必须拿到第二跳。"""
    calls = []
    bodies = [_body(False), _body(True), _body(True)]

    async def fake_post(url, headers=None, json=None, timeout=None):
        calls.append(json["model"])
        return _Resp(bodies[len(calls) - 1])

    client = mock.Mock()
    client.post = fake_post
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)
    monkeypatch.setattr(llm_router, "_provider_config", lambda account: ("http://x", "k"))
    monkeypatch.setattr(llm_router, "_log_cache_and_record_budget", lambda *a, **k: None)
    for key in list(llm_router._CB_FAILURES):
        llm_router._CB_FAILURES.pop(key, None)

    def needs_logprobs(body):
        ch = body["choices"][0]
        return None if (ch.get("logprobs") or {}).get("content") else "no_logprobs"

    out = asyncio.run(llm_router.call_chain(SLOT.DECISION, {"messages": []}, body_validator=needs_logprobs))
    assert len(calls) == 2, calls
    assert out["choices"][0]["logprobs"]["content"][0]["token"] == "B"


def test_without_body_validator_the_first_200_is_accepted(monkeypatch):
    """对照: 不传 body_validator 时第一跳就被接受 —— 证明上一条的「换下一跳」来自校验器。"""
    calls = []

    async def fake_post(url, headers=None, json=None, timeout=None):
        calls.append(json["model"])
        return _Resp(_body(False))

    client = mock.Mock()
    client.post = fake_post
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)
    monkeypatch.setattr(llm_router, "_provider_config", lambda account: ("http://x", "k"))
    monkeypatch.setattr(llm_router, "_log_cache_and_record_budget", lambda *a, **k: None)
    asyncio.run(llm_router.call_chain(SLOT.DECISION, {"messages": []}))
    assert len(calls) == 1


@pytest.mark.parametrize("bad", [lambda b: 1 / 0])
def test_raising_body_validator_rejects_instead_of_crashing(monkeypatch, bad):
    async def fake_post(url, headers=None, json=None, timeout=None):
        return _Resp(_body(True))

    client = mock.Mock()
    client.post = fake_post
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)
    monkeypatch.setattr(llm_router, "_provider_config", lambda account: ("http://x", "k"))
    with pytest.raises(RuntimeError, match="body_validator_error_ZeroDivisionError"):
        asyncio.run(llm_router.call_chain(SLOT.DECISION, {"messages": []}, body_validator=bad))
