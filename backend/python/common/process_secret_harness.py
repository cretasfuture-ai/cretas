"""工艺保密 / 跨厂数据的系统约束 —— Python 侧唯一的一份 (与 Java ``ProcessSecretLlmCostScope.HARNESS_INSTRUCTION`` 同义)。

凡是把某家工厂的经营数据交给大模型做分析 / 解读的系统提示词, 都经 :func:`with_process_secret_harness` 带上这一段
(放在最前面, 原提示词末尾的输出格式要求仍是最后一句)。哪些路径接了它由
``tests/test_process_secret_harness_paths.py`` 用 AST 钉住; 新增这类路径要同时登记进那个测试。

Steve 2026-10-29: 授权用户的 AI 对话里可以出现工艺保密产品, 但这些内容不许流到别的工厂, 也不许被学走、在外部复现。

工厂级保密配置 (Steve 2026-11-03) 把这一段拆成两句:

- :data:`CROSS_FACTORY_HARNESS` (不许透露 / 对比 / 引用到其他工厂, 不许写进可共享内容): **永远带**, 不看任何开关。
- :data:`PROCESS_SECRET_CLAUSE` (工艺保密产品的配方 / 工艺 / 成本): 只在该厂「工艺保密」(a) 或「客户侧保管」(e)
  开着时带。拼提示词的地方照旧调 :func:`with_process_secret_harness` (两句都带, 偏向保密); 出境口
  (``common.llm_client._RedactingLLMClient``) 知道外发工厂后, 该厂 a、e 都关时用 :func:`without_process_secret_clause`
  去掉第二句 —— 工厂不明 / 读不到配置时照旧两句都带。
"""

from __future__ import annotations

from typing import Any

CROSS_FACTORY_HARNESS = (
    "【数据保密约束】下面提供的是某一家工厂的经营数据，只用于回答当前这位用户在本次对话中的问题："
    "不得把它透露、对比或引用到其他工厂；不得写入任何可共享或可复用的内容（共享报告、模板、知识库、示例），"
    "不得建议把这些内容发给他人或其他工厂。"
)

PROCESS_SECRET_CLAUSE = (
    "工艺保密产品的配方、工艺与成本只有当前用户有权查看时才会出现在数据里，"
    "数据里没有的不要推测或补全，也不得记住或复述。"
)

PROCESS_SECRET_HARNESS = CROSS_FACTORY_HARNESS + PROCESS_SECRET_CLAUSE


def with_process_secret_harness(system_prompt: str | None) -> str:
    """系统提示词前面带上 :data:`PROCESS_SECRET_HARNESS` (已带过的不重复)。"""
    prompt = system_prompt or ""
    if PROCESS_SECRET_HARNESS in prompt:
        return prompt
    return PROCESS_SECRET_HARNESS + ("\n\n" + prompt if prompt else "")


def _strip(text: Any) -> Any:
    return text.replace(PROCESS_SECRET_CLAUSE, "") if isinstance(text, str) else text


def without_process_secret_clause(payload: Any) -> Any:
    """去掉 /chat/completions payload 里系统消息中的 :data:`PROCESS_SECRET_CLAUSE` (跨厂那一句保留)。

    只动 role=system 的消息; content 是字符串或 ``[{"type": "text", "text": ...}]``。返回新对象, 不改入参。
    """
    if not isinstance(payload, dict) or not isinstance(payload.get("messages"), list):
        return payload
    messages = []
    changed = False
    for message in payload["messages"]:
        if isinstance(message, dict) and message.get("role") == "system":
            content = message.get("content")
            if isinstance(content, str) and PROCESS_SECRET_CLAUSE in content:
                message = {**message, "content": _strip(content)}
                changed = True
            elif isinstance(content, list):
                parts = []
                for part in content:
                    if isinstance(part, dict) and isinstance(part.get("text"), str) \
                            and PROCESS_SECRET_CLAUSE in part["text"]:
                        part = {**part, "text": _strip(part["text"])}
                        changed = True
                    parts.append(part)
                message = {**message, "content": parts}
        messages.append(message)
    return {**payload, "messages": messages} if changed else payload
