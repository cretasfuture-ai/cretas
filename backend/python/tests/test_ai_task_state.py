"""INTENT-DISTILL-20261111 第二批「任务状态」: 识别层收到的任务状态摘要只含白名单字段。

钉住三件事:
1. 白名单是写死的字面量, 且与 Java TaskStateSummary 的两份白名单逐项相同 (两份一定会漂, 所以对校)。
2. 白名单外的字段 (助手正文、原话、会话 id、工厂、来源 …) 一律进不了提示词 —— 阴性对照。
3. 不带 taskState 时提示词与改动前逐字相同。
"""
from __future__ import annotations

import re
from pathlib import Path

from ai.dto import IntentMatchRequest
from ai.matcher import decision
from ai.matcher.decision import (
    TASK_STATE_TASK_KEYS,
    TASK_STATE_TOP_KEYS,
    build_choice_messages,
    history_block,
    sanitize_task_state,
    task_state_text,
    with_task_state,
)

JAVA_SUMMARY = (Path(__file__).resolve().parents[2] / "java" / "cretas-api" / "src" / "main" / "java"
                / "com" / "cretas" / "aims" / "service" / "execution" / "TaskStateSummary.java")

STATE = {
    "tasks": [
        {"level": 0, "intentCode": "MATERIAL_INVENTORY_QUERY", "params": {"page": "2", "size": "20"}},
        {"level": 1, "intentCode": "PROCESSING_BATCH_COMPLETE", "params": {"batchId": "B-1827", "quantity": "已填"}},
    ]
}


def test_whitelist_is_the_literal_set():
    # 往白名单里加字段必须改这一行 —— 这就是「加字段测试要红」
    assert TASK_STATE_TOP_KEYS == frozenset({"tasks"})
    assert TASK_STATE_TASK_KEYS == frozenset({"level", "intentCode", "params"})


def _java_set(name: str) -> set:
    src = JAVA_SUMMARY.read_text(encoding="utf-8")
    m = re.search(name + r"\s*=\s*Set\.of\(([^)]*)\)", src)
    assert m, f"{name} not found in {JAVA_SUMMARY}"
    return set(re.findall(r'"([^"]+)"', m.group(1)))


def test_python_whitelist_equals_java_whitelist():
    assert _java_set("TOP_KEYS") == set(TASK_STATE_TOP_KEYS)
    assert _java_set("TASK_KEYS") == set(TASK_STATE_TASK_KEYS)


def test_sanitized_tasks_only_carry_whitelisted_keys():
    dirty = {
        "tasks": [{**STATE["tasks"][1], "assistantText": "好的，已为您查询张三的库存", "sessionId": "s-1",
                   "factoryId": "F002", "sources": {"batchId": "USER"}, "originalInput": "张三报工"}],
        "factoryId": "F002",
        "messages": [{"role": "assistant", "content": "助手正文"}],
    }
    tasks = sanitize_task_state(dirty)
    assert len(tasks) == 1
    assert set(tasks[0]) == set(TASK_STATE_TASK_KEYS)
    text = task_state_text(dirty)
    for leaked in ("张三", "助手正文", "s-1", "F002", "USER", "assistantText", "sessionId", "originalInput"):
        assert leaked not in text, leaked
    assert "PROCESSING_BATCH_COMPLETE" in text and "batchId=B-1827" in text


def test_malformed_layers_are_dropped():
    bad = {"tasks": [
        {"level": "0", "intentCode": "X_Y", "params": {}},          # level 不是整数
        {"level": True, "intentCode": "X_Y", "params": {}},         # 布尔不算整数
        {"level": 1, "intentCode": "drop table", "params": {}},     # 意图码格式不对
        {"level": 2, "intentCode": "OK_INTENT", "params": {"bad key": "v", "k": {"nested": 1}, "n": 3}},
    ]}
    tasks = sanitize_task_state(bad)
    assert tasks == [{"level": 2, "intentCode": "OK_INTENT", "params": {"n": "3"}}]


def test_long_values_are_truncated():
    tasks = sanitize_task_state({"tasks": [{"level": 0, "intentCode": "A_B", "params": {"k": "x" * 500}}]})
    assert len(tasks[0]["params"]["k"]) == decision.MAX_TASK_STATE_VALUE_CHARS


def test_no_task_state_leaves_prompt_byte_identical():
    history = [{"role": "user", "content": "先看下库存"}]
    assert with_task_state(history, None) == history
    assert with_task_state(history, {"tasks": []}) == history
    opts = [{"intent_code": "A_B", "intent_name": "甲"}]
    assert build_choice_messages("报工", "operator", opts, with_task_state(history, None)) == \
        build_choice_messages("报工", "operator", opts, history)


def test_task_state_reaches_the_decision_prompt_and_does_not_eat_history_turns():
    history = [{"role": "user", "content": f"第{i}句"} for i in range(decision.MAX_HISTORY_TURNS)]
    block = history_block(with_task_state(history, STATE))
    assert all(f"第{i}句" in block for i in range(decision.MAX_HISTORY_TURNS))
    assert "被打断挂起（第1层）：PROCESSING_BATCH_COMPLETE" in block
    assert "正在做：MATERIAL_INVENTORY_QUERY" in block
    # 只有任务状态、没有最近对话时也带上
    assert "PROCESSING_BATCH_COMPLETE" in history_block(with_task_state([], STATE))
    opts = [{"intent_code": "PROCESSING_BATCH_COMPLETE", "intent_name": "报工"}]
    msgs = build_choice_messages("报工", "operator", opts, with_task_state([], STATE))
    assert any("batchId=B-1827" in str(m.get("content")) for m in msgs)


def test_request_body_only_adds_an_optional_field():
    base = {"query": "报工", "factoryId": "F002", "userId": "1", "role": "operator", "businessType": "FACTORY"}
    assert IntentMatchRequest.model_validate(base).taskState is None
    assert IntentMatchRequest.model_validate({**base, "taskState": STATE}).taskState == STATE


# ---------------- N3: 任务状态是独立字段, 不算最近对话 ----------------

def test_task_state_reaches_decision_prompts_but_not_history_count_or_legacy():
    import asyncio
    from tests.test_ai_decision_layer import CFG, FakeDecision, ROWS, _orch
    from ai import registry

    registry.set_registry(registry.RegistrySnapshot())
    dec = FakeDecision(["CLOCK_OUT"], {"CLOCK_OUT": 0.95})
    res = asyncio.run(_orch(dec).match(
        query="好了", factoryId="F006", businessType="FACTORY", userId="u", role="operator",
        visible_intents=ROWS, history=[], min_confidence=0.7, enable_llm=True, task_state=STATE))
    # 两个决策提示词都看到了任务状态 (清洗后渲染的那一条)
    assert [stage for stage, _ in dec.seen_history] == ["shortlist", "choose"]
    for _, seen in dec.seen_history:
        assert [t["role"] for t in seen] == [decision.TASK_STATE_ROLE]
        assert "PROCESSING_BATCH_COMPLETE" in seen[0]["content"]
    # 最近对话轮数不被任务状态算进去
    assert res.timingMs.get("historyTurns") == 0
    assert res.timingMs.get("taskState") == 1
    assert CFG is not None


def test_without_task_state_decision_prompts_are_unchanged():
    import asyncio
    from tests.test_ai_decision_layer import FakeDecision, ROWS, _orch
    from ai import registry

    registry.set_registry(registry.RegistrySnapshot())
    dec = FakeDecision(["CLOCK_OUT"], {"CLOCK_OUT": 0.95})
    res = asyncio.run(_orch(dec).match(
        query="好了", factoryId="F006", businessType="FACTORY", userId="u", role="operator",
        visible_intents=ROWS, history=[], min_confidence=0.7, enable_llm=True))
    assert dec.seen_history == [("shortlist", []), ("choose", [])]
    assert "taskState" not in (res.timingMs or {})
