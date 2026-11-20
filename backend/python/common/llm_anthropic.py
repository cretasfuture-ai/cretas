"""OpenAI chat-shape <-> Anthropic Messages API translation (2026-11-12, LLM-PROVIDER-SWITCH).

Why a translation layer and not Anthropic's OpenAI-compatible endpoint:
  · the compat layer returns 400 for ``response_format: json_object`` and for any
    ``temperature`` below 1 on Claude 5 models — both are sent by our CHART / MAPPER /
    DECISION slots;
  · it cannot turn thinking off (``disabled`` is not accepted there) and Anthropic documents
    it as "not considered production-ready".
Why raw HTTP and not the ``anthropic`` SDK: every LLM egress must pass through the shared
redacting client (``common.llm_client``) — the SDK would open its own HTTP client and bypass
the 工艺保密 / PII choke point. The router therefore redacts the chat-shaped payload first,
translates it here, posts it through the shared client, and translates the answer back.

Pure functions only: no I/O, no env reads — the router owns those.
"""
from __future__ import annotations

import json
from typing import Any, Dict, List, Optional, Tuple

ANTHROPIC_VERSION = "2023-06-01"
DEFAULT_MAX_TOKENS = 2048


class UntranslatableRequest(ValueError):
    """The chat payload uses something with no Anthropic equivalent (audio/file part, forced tool).

    The router treats it as "this candidate cannot serve this request" (next candidate, no circuit
    breaker strike) — the provider is healthy, the request shape is the problem.
    """

#: thinking value that means "off" per model (2026-11-12 probe + API docs):
#:   claude-haiku-5-5   accepts {"type": "disabled"} at effort <= high (default effort medium)
#:   claude-sonnet-5-5  rejects "disabled" (400); "between_tools" is its thinking-off mode
_THINKING_OFF: Dict[str, Dict[str, Any]] = {
    "claude-haiku-5-5": {"type": "disabled"},
    "claude-sonnet-5-5": {"type": "between_tools"},
}

#: OpenAI finish_reason for each Anthropic stop_reason. ``refusal`` maps to
#: ``content_filter`` and its text is dropped, so slot validation sees "empty" and falls back.
_FINISH = {
    "end_turn": "stop",
    "stop_sequence": "stop",
    "max_tokens": "length",
    "tool_use": "tool_calls",
    "pause_turn": "stop",
    "refusal": "content_filter",
}


def thinking_off(model: str) -> Optional[Dict[str, Any]]:
    """The thinking setting that turns reasoning off for ``model``; None = unknown model (omit)."""
    value = _THINKING_OFF.get(model)
    return dict(value) if value is not None else None


def _text_of(content: Any) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "".join(str(p.get("text", "")) for p in content
                       if isinstance(p, dict) and p.get("type") == "text")
    return "" if content is None else str(content)


def _image_block(part: Dict[str, Any]) -> Dict[str, Any]:
    url = part.get("image_url")
    if isinstance(url, dict):
        url = url.get("url")
    url = str(url or "")
    if url.startswith("data:"):
        head, _, data = url.partition(",")
        media_type = head[5:].split(";", 1)[0] or "image/jpeg"
        return {"type": "image", "source": {"type": "base64", "media_type": media_type, "data": data}}
    return {"type": "image", "source": {"type": "url", "url": url}}


def _user_content(content: Any) -> Any:
    if not isinstance(content, list):
        return _text_of(content)
    blocks: List[Dict[str, Any]] = []
    for part in content:
        if not isinstance(part, dict):
            continue
        kind = part.get("type")
        if kind == "text":
            blocks.append({"type": "text", "text": str(part.get("text", ""))})
        elif kind == "image_url":
            blocks.append(_image_block(part))
        else:
            # input_audio / file: no Anthropic equivalent here — fail loudly, never drop silently
            raise UntranslatableRequest(f"unsupported content part for anthropic: {kind!r}")
    return blocks


def _tool_use_blocks(tool_calls: Any) -> List[Dict[str, Any]]:
    blocks = []
    for call in tool_calls or []:
        fn = (call or {}).get("function") or {}
        args = fn.get("arguments")
        if isinstance(args, str):
            args = json.loads(args) if args.strip() else {}
        blocks.append({"type": "tool_use", "id": str(call.get("id") or ""),
                       "name": str(fn.get("name") or ""), "input": args or {}})
    return blocks


def _append(messages: List[Dict[str, Any]], role: str, blocks: Any) -> None:
    """Merge consecutive same-role turns (tool results arrive as several OpenAI messages)."""
    if isinstance(blocks, str):
        blocks = [{"type": "text", "text": blocks}] if blocks else []
    if not blocks:
        return
    if messages and messages[-1]["role"] == role:
        prev = messages[-1]["content"]
        if isinstance(prev, str):
            prev = [{"type": "text", "text": prev}] if prev else []
        messages[-1]["content"] = prev + list(blocks)
    else:
        messages.append({"role": role, "content": list(blocks)})


def to_messages_request(payload: Dict[str, Any], model: str, *, thinking_off_wanted: bool) -> Dict[str, Any]:
    """Translate an (already redacted) OpenAI chat payload into a Messages API body.

    Dropped on purpose (Claude 5 rejects or ignores them): temperature / top_p (non-default -> 400),
    seed, response_format (the slot validator still enforces JSON and falls back on bad JSON),
    logprobs, DashScope-only fields (enable_thinking / enable_search / extra_body), stream_options.
    A trailing assistant ``partial`` prefill is dropped: Claude 5 rejects prefill with 400.
    """
    system_parts: List[str] = []
    messages: List[Dict[str, Any]] = []
    for m in payload.get("messages") or []:
        role = m.get("role")
        if role in ("system", "developer"):
            text = _text_of(m.get("content"))
            if text:
                system_parts.append(text)
        elif role == "user":
            _append(messages, "user", _user_content(m.get("content")))
        elif role == "assistant":
            if m.get("partial"):
                continue
            blocks: List[Dict[str, Any]] = []
            text = _text_of(m.get("content"))
            if text:
                blocks.append({"type": "text", "text": text})
            blocks += _tool_use_blocks(m.get("tool_calls"))
            _append(messages, "assistant", blocks)
        elif role == "tool":
            _append(messages, "user", [{"type": "tool_result",
                                        "tool_use_id": str(m.get("tool_call_id") or ""),
                                        "content": _text_of(m.get("content"))}])
        else:
            raise UntranslatableRequest(f"unsupported message role for anthropic: {role!r}")
    if not messages:
        raise UntranslatableRequest("anthropic request needs at least one user message")
    max_tokens = payload.get("max_tokens") or payload.get("max_completion_tokens") or DEFAULT_MAX_TOKENS
    body: Dict[str, Any] = {"model": model, "max_tokens": int(max_tokens), "messages": messages}
    if system_parts:
        body["system"] = "\n".join(system_parts)
    if payload.get("stop"):
        stops = payload["stop"] if isinstance(payload["stop"], list) else [payload["stop"]]
        body["stop_sequences"] = [s for s in stops if isinstance(s, str) and s.strip()]
    tools = payload.get("tools")
    if tools:
        body["tools"] = [{"name": t["function"]["name"],
                          "description": t["function"].get("description", ""),
                          "input_schema": t["function"].get("parameters") or {"type": "object", "properties": {}}}
                         for t in tools if isinstance(t, dict) and t.get("function")]
        choice = payload.get("tool_choice")
        if choice == "none":
            body["tool_choice"] = {"type": "none"}
        elif choice in (None, "auto"):
            body["tool_choice"] = {"type": "auto"}
        else:
            # forced tool use returns 400 on Claude 5.5 models; never rewrite it to "auto" silently
            raise UntranslatableRequest(f"unsupported tool_choice for anthropic: {choice!r}")
    if thinking_off_wanted:
        off = thinking_off(model)
        if off is not None:
            body["thinking"] = off
    if payload.get("stream"):
        body["stream"] = True
    return body


def to_chat_completion(body: Dict[str, Any]) -> Dict[str, Any]:
    """Translate a Messages API response into the OpenAI chat-completion shape the router expects."""
    if body.get("type") == "error" or "error" in body:
        err = body.get("error") or {}
        return {"error": {"type": err.get("type", "error"), "message": err.get("message", "")}}
    stop = body.get("stop_reason")
    text_parts: List[str] = []
    tool_calls: List[Dict[str, Any]] = []
    for block in body.get("content") or []:
        kind = block.get("type")
        if kind == "text" and stop != "refusal":
            text_parts.append(block.get("text", ""))
        elif kind == "tool_use":
            tool_calls.append({"id": block.get("id", ""), "type": "function",
                               "function": {"name": block.get("name", ""),
                                            "arguments": json.dumps(block.get("input") or {}, ensure_ascii=False)}})
    message: Dict[str, Any] = {"role": "assistant", "content": "".join(text_parts)}
    if tool_calls:
        message["tool_calls"] = tool_calls
    usage = body.get("usage") or {}
    prompt = int(usage.get("input_tokens") or 0) + int(usage.get("cache_read_input_tokens") or 0) \
        + int(usage.get("cache_creation_input_tokens") or 0)
    completion = int(usage.get("output_tokens") or 0)
    return {
        "id": body.get("id", ""),
        "object": "chat.completion",
        "model": body.get("model", ""),
        "choices": [{"index": 0, "message": message, "finish_reason": _FINISH.get(str(stop or ""), "stop")}],
        "usage": {"prompt_tokens": prompt, "completion_tokens": completion, "total_tokens": prompt + completion,
                  "prompt_tokens_details": {"cached_tokens": int(usage.get("cache_read_input_tokens") or 0)}},
    }


def stream_event(obj: Dict[str, Any]) -> Tuple[Optional[str], Optional[Dict[str, int]], Optional[str]]:
    """One SSE ``data`` object -> (text delta, usage update, error label).

    usage update carries ``input`` (message_start) or ``output`` (message_delta) token counts.
    """
    kind = obj.get("type")
    if kind == "content_block_delta":
        delta = obj.get("delta") or {}
        if delta.get("type") == "text_delta":
            return delta.get("text") or None, None, None
        return None, None, None
    if kind == "message_start":
        usage = ((obj.get("message") or {}).get("usage")) or {}
        return None, {"input": int(usage.get("input_tokens") or 0)
                      + int(usage.get("cache_read_input_tokens") or 0)
                      + int(usage.get("cache_creation_input_tokens") or 0)}, None
    if kind == "message_delta":
        usage = obj.get("usage") or {}
        stop = (obj.get("delta") or {}).get("stop_reason")
        return None, {"output": int(usage.get("output_tokens") or 0)}, ("refusal" if stop == "refusal" else None)
    if kind == "error":
        err = obj.get("error") or {}
        return None, None, str(err.get("type") or "error")
    return None, None, None


__all__ = ["ANTHROPIC_VERSION", "UntranslatableRequest", "to_messages_request", "to_chat_completion", "stream_event", "thinking_off"]
