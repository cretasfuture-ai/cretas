"""把工厂经营数据交给大模型做分析 / 解读的系统提示词, 每条都带工艺保密 / 跨厂约束 (唯一一份常量)。

Steve 2026-10-29: 授权用户的 AI 对话里可以出现工艺保密产品, 但不许流到别的工厂, 也不许被学走、在外部复现。
Java 侧同义常量是 ``ProcessSecretLlmCostScope.HARNESS_INSTRUCTION``。

闸 (AST, 不是正则): 登记的每个模块里, 每个 ``{"role": "system", ...}`` 消息的内容 (字符串, 或
``[{"type": "text", "text": ...}]`` 里的 text) 都必须是 ``with_process_secret_harness(...)`` 调用;
每个模块的系统消息个数也钉住 —— 新加一条系统消息要么接上约束, 要么改这里并说明为什么不接。

没有登记 (有意不接) 的路径:
- ``smartbi/services/factory_charts/conversation.py``: 图表规划器, 只给目录 id, 不给经营数据;
- ``smartbi/api/ai_proxy.py``: 规则 / 状态机 / 表单 / 工厂初始化配置的解析, 不解读经营数据;
- 意图识别 (``ai/matcher``、``ai/router``): 用户原话 → 意图, 不带经营数据;
- 字段识别 / 映射 / 清洗 / 结构分析: 输出 JSON 映射, 不产出给人看的解读;
- 餐饮 (``smartbi/agent/orchestrator.py`` 的 SYSTEM_PROMPT、``smartbi/gold/restaurant``): 餐饮线已废弃 (Steve 10-08)。
"""

from __future__ import annotations

import ast
import asyncio
from pathlib import Path

import pytest

from common.process_secret_harness import PROCESS_SECRET_HARNESS, with_process_secret_harness

ROOT = Path(__file__).resolve().parents[1]

# 模块 → 其中系统消息个数 (每一条都必须接约束)
HARNESSED_PATHS = {
    # InsightGenerator: /api/chat/general-analysis (Java 成本分析经 Python 的通道)、chat.py 的下钻流式解读、洞察生成
    "smartbi/services/insights/llm_client.py": 4,
    "smartbi/services/insights/chart_insight_service.py": 1,
    "smartbi/agent/synthesis_engine.py": 1,
    "smartbi/api/financial_dashboard.py": 2,
    "smartbi/services/unified_analyzer.py": 1,
}

HARNESS_CALL = "with_process_secret_harness"


def _const(node: ast.AST):
    return node.value if isinstance(node, ast.Constant) else None


def _dict_get(node: ast.Dict, key: str):
    for k, v in zip(node.keys, node.values):
        if k is not None and _const(k) == key:
            return v
    return None


def _is_harness_call(node: ast.AST) -> bool:
    return isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == HARNESS_CALL


def _system_message_contents(source: str):
    """每个 {"role": "system"} 字典里真正送出的那段文本节点 (字符串内容或 text 块)。"""
    found = []
    for node in ast.walk(ast.parse(source)):
        if not isinstance(node, ast.Dict) or _const(_dict_get(node, "role") or ast.Constant(None)) != "system":
            continue
        content = _dict_get(node, "content")
        if isinstance(content, ast.List):
            texts = [_dict_get(el, "text") for el in content.elts if isinstance(el, ast.Dict)]
            found.append([t for t in texts if t is not None])
        else:
            found.append([content])
    return found


@pytest.mark.parametrize("rel_path,expected", sorted(HARNESSED_PATHS.items()))
def test_every_system_message_on_a_data_analysis_path_carries_the_harness(rel_path, expected):
    source = (ROOT / rel_path).read_text(encoding="utf-8")
    contents = _system_message_contents(source)

    assert len(contents) == expected, f"{rel_path}: 系统消息个数变了 ({len(contents)} != {expected}), 新的那条要接约束并更新登记"
    for texts in contents:
        assert texts, f"{rel_path}: 系统消息没有可识别的文本"
        for text in texts:
            assert _is_harness_call(text), f"{rel_path}:{text.lineno} 系统消息没有经 {HARNESS_CALL}(...)"


def test_the_ast_gate_sees_a_system_message_without_the_harness():
    """阳性对照: 闸自己能红 (否则上面那条是在守空气)。"""
    bare = 'payload = {"messages": [{"role": "system", "content": "你是分析师"}]}'
    wrapped = 'payload = {"messages": [{"role": "system", "content": with_process_secret_harness("你是分析师")}]}'
    assert not _is_harness_call(_system_message_contents(bare)[0][0])
    assert _is_harness_call(_system_message_contents(wrapped)[0][0])


def test_the_harness_goes_first_once_and_keeps_the_original_prompt():
    prompt = "你是分析师。严格以JSON格式回复。"
    out = with_process_secret_harness(prompt)
    assert out.startswith(PROCESS_SECRET_HARNESS)
    assert out.endswith(prompt)            # 原提示词末尾的输出格式要求仍是最后一句
    assert with_process_secret_harness(out) == out
    assert with_process_secret_harness(None) == PROCESS_SECRET_HARNESS
    for clause in ("其他工厂", "工艺保密产品", "有权查看", "不得记住"):
        assert clause in PROCESS_SECRET_HARNESS


def test_the_insight_generator_really_sends_the_harness_to_the_model(monkeypatch):
    """接上没有: 走真实 call_llm, 断言打在交给路由器的 payload 上。"""
    import smartbi.services.insights.llm_client as insights

    sent = []

    async def fake_call_chain(slot, payload, timeout=None):
        sent.append(payload)
        return {"choices": [{"message": {"content": "{}"}}]}

    monkeypatch.setattr(insights, "call_chain", fake_call_chain)
    result = asyncio.run(insights.call_llm("本月成本数据……", system_role="你是工厂成本分析师。"))

    assert result == "{}"
    assert len(sent) == 1
    system_text = sent[0]["messages"][0]["content"][0]["text"]
    assert system_text.startswith(PROCESS_SECRET_HARNESS)
    assert "你是工厂成本分析师。" in system_text
