"""OpenAI chat shape <-> Anthropic Messages translation (common.llm_anthropic, 2026-11-12).

Pure-function tests: no network, no env. Each contract the router relies on gets one assertion
plus, where an assertion could be vacuous, a control showing the input actually carried the thing.
"""
import json

import pytest

from common.llm_anthropic import (
    UntranslatableRequest, stream_event, thinking_off, to_chat_completion, to_messages_request,
)


def _req(messages, **extra):
    return {"model": "ignored", "messages": messages, **extra}


def test_system_and_developer_are_hoisted_and_partial_prefill_is_dropped():
    body = to_messages_request(_req([
        {"role": "system", "content": "规则一"},
        {"role": "developer", "content": "规则二"},
        {"role": "user", "content": "问题"},
        {"role": "assistant", "content": "答案：", "partial": True},
    ]), "claude-haiku-5-5", thinking_off_wanted=False)
    assert body["system"] == "规则一\n规则二"
    assert body["messages"] == [{"role": "user", "content": [{"type": "text", "text": "问题"}]}]
    assert body["model"] == "claude-haiku-5-5"


def test_fields_claude5_rejects_are_not_sent():
    """Claude 5: temperature/top_p 非默认值 400; json_object 不存在; logprobs/seed 无意义。"""
    src = _req([{"role": "user", "content": "输出 json"}], temperature=0, top_p=0.5, seed=1234,
               response_format={"type": "json_object"}, logprobs=True, top_logprobs=5,
               enable_thinking=False, stream_options={"include_usage": True}, max_tokens=77)
    body = to_messages_request(src, "claude-haiku-5-5", thinking_off_wanted=True)
    for key in ("temperature", "top_p", "seed", "response_format", "logprobs", "top_logprobs",
                "enable_thinking", "stream_options"):
        assert key not in body, key
    assert body["max_tokens"] == 77
    assert body["thinking"] == {"type": "disabled"}


def test_thinking_off_is_model_specific_and_unknown_models_get_nothing():
    assert thinking_off("claude-haiku-5-5") == {"type": "disabled"}
    # Sonnet 5.5 rejects "disabled" (400); between_tools is its thinking-off mode
    assert thinking_off("claude-sonnet-5-5") == {"type": "between_tools"}
    body = to_messages_request(_req([{"role": "user", "content": "x"}]), "claude-future-9",
                               thinking_off_wanted=True)
    assert "thinking" not in body
    body = to_messages_request(_req([{"role": "user", "content": "x"}]), "claude-haiku-5-5",
                               thinking_off_wanted=False)
    assert "thinking" not in body


def test_images_become_base64_or_url_sources():
    body = to_messages_request(_req([{"role": "user", "content": [
        {"type": "text", "text": "看图"},
        {"type": "image_url", "image_url": {"url": "data:image/png;base64,QUJD"}},
        {"type": "image_url", "image_url": {"url": "https://example.invalid/a.jpg"}},
    ]}]), "claude-haiku-5-5", thinking_off_wanted=True)
    blocks = body["messages"][0]["content"]
    assert blocks[0] == {"type": "text", "text": "看图"}
    assert blocks[1] == {"type": "image", "source": {"type": "base64", "media_type": "image/png", "data": "QUJD"}}
    assert blocks[2] == {"type": "image", "source": {"type": "url", "url": "https://example.invalid/a.jpg"}}


def test_tool_round_trip_shapes():
    tools = [{"type": "function", "function": {"name": "q", "description": "查",
                                                "parameters": {"type": "object", "properties": {"n": {"type": "integer"}}}}}]
    body = to_messages_request(_req([
        {"role": "user", "content": "查 3 号"},
        {"role": "assistant", "content": "", "tool_calls": [
            {"id": "c1", "type": "function", "function": {"name": "q", "arguments": '{"n": 3}'}},
            {"id": "c2", "type": "function", "function": {"name": "q", "arguments": '{"n": 4}'}}]},
        {"role": "tool", "tool_call_id": "c1", "content": "5 度"},
        {"role": "tool", "tool_call_id": "c2", "content": "6 度"},
    ], tools=tools, tool_choice="auto"), "claude-haiku-5-5", thinking_off_wanted=True)
    assert body["tools"] == [{"name": "q", "description": "查",
                              "input_schema": {"type": "object", "properties": {"n": {"type": "integer"}}}}]
    assert body["tool_choice"] == {"type": "auto"}
    assistant = body["messages"][1]
    assert [b["type"] for b in assistant["content"]] == ["tool_use", "tool_use"]
    assert assistant["content"][0]["input"] == {"n": 3}
    # two OpenAI tool messages merge into ONE user turn (Anthropic requires alternation)
    assert len(body["messages"]) == 3
    results = body["messages"][2]
    assert results["role"] == "user"
    assert [b["tool_use_id"] for b in results["content"]] == ["c1", "c2"]


@pytest.mark.parametrize("choice", ["required", {"type": "function", "function": {"name": "q"}}])
def test_forced_tool_choice_is_refused_not_rewritten(choice):
    """强制工具在 Claude 5.5 上 400; ⛔ 不悄悄改成 auto(那是降级)。"""
    tools = [{"type": "function", "function": {"name": "q", "parameters": {"type": "object"}}}]
    with pytest.raises(UntranslatableRequest):
        to_messages_request(_req([{"role": "user", "content": "x"}], tools=tools, tool_choice=choice),
                            "claude-haiku-5-5", thinking_off_wanted=True)


def test_audio_part_is_refused_not_dropped():
    with pytest.raises(UntranslatableRequest):
        to_messages_request(_req([{"role": "user", "content": [{"type": "input_audio", "input_audio": {}}]}]),
                            "claude-haiku-5-5", thinking_off_wanted=True)


def test_response_text_tools_usage_and_finish_reason():
    out = to_chat_completion({
        "id": "msg_1", "model": "claude-haiku-5-5", "stop_reason": "tool_use",
        "content": [{"type": "thinking", "thinking": ""}, {"type": "text", "text": "好的"},
                    {"type": "tool_use", "id": "t1", "name": "q", "input": {"n": 3}}],
        "usage": {"input_tokens": 10, "cache_read_input_tokens": 4, "output_tokens": 6},
    })
    msg = out["choices"][0]["message"]
    assert msg["content"] == "好的"
    assert msg["tool_calls"][0]["function"] == {"name": "q", "arguments": json.dumps({"n": 3})}
    assert out["choices"][0]["finish_reason"] == "tool_calls"
    assert out["usage"] == {"prompt_tokens": 14, "completion_tokens": 6, "total_tokens": 20,
                            "prompt_tokens_details": {"cached_tokens": 4}}


def test_refusal_text_is_dropped_so_slot_validation_falls_back():
    out = to_chat_completion({"stop_reason": "refusal", "content": [{"type": "text", "text": "partial"}],
                              "usage": {}})
    assert out["choices"][0]["message"]["content"] == ""
    assert out["choices"][0]["finish_reason"] == "content_filter"
    # control: same body with end_turn keeps the text
    keep = to_chat_completion({"stop_reason": "end_turn", "content": [{"type": "text", "text": "partial"}],
                               "usage": {}})
    assert keep["choices"][0]["message"]["content"] == "partial"


def test_error_body_maps_to_openai_error_shape():
    out = to_chat_completion({"type": "error", "error": {"type": "overloaded_error", "message": "busy"}})
    assert out == {"error": {"type": "overloaded_error", "message": "busy"}}


def test_stream_events():
    assert stream_event({"type": "content_block_delta", "delta": {"type": "text_delta", "text": "你"}}) == ("你", None, None)
    assert stream_event({"type": "content_block_delta", "delta": {"type": "input_json_delta"}}) == (None, None, None)
    assert stream_event({"type": "message_start", "message": {"usage": {"input_tokens": 9}}}) == (None, {"input": 9}, None)
    assert stream_event({"type": "message_delta", "delta": {"stop_reason": "end_turn"},
                         "usage": {"output_tokens": 3}}) == (None, {"output": 3}, None)
    assert stream_event({"type": "message_delta", "delta": {"stop_reason": "refusal"},
                         "usage": {"output_tokens": 1}})[2] == "refusal"
    assert stream_event({"type": "error", "error": {"type": "overloaded_error"}})[2] == "overloaded_error"
