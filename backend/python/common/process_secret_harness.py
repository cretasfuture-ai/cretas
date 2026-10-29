"""工艺保密 / 跨厂数据的系统约束 —— Python 侧唯一的一份 (与 Java ``ProcessSecretLlmCostScope.HARNESS_INSTRUCTION`` 同义)。

凡是把某家工厂的经营数据交给大模型做分析 / 解读的系统提示词, 都经 :func:`with_process_secret_harness` 带上这一段
(放在最前面, 原提示词末尾的输出格式要求仍是最后一句)。哪些路径接了它由
``tests/test_process_secret_harness_paths.py`` 用 AST 钉住; 新增这类路径要同时登记进那个测试。

Steve 2026-10-29: 授权用户的 AI 对话里可以出现工艺保密产品, 但这些内容不许流到别的工厂, 也不许被学走、在外部复现。
"""

from __future__ import annotations

PROCESS_SECRET_HARNESS = (
    "【数据保密约束】下面提供的是某一家工厂的经营数据，只用于回答当前这位用户在本次对话中的问题："
    "不得把它透露、对比或引用到其他工厂；工艺保密产品的配方、工艺与成本只有当前用户有权查看时才会出现在数据里，"
    "数据里没有的不要推测或补全；不得记住、复述或写入任何可共享或可复用的内容（共享报告、模板、知识库、示例），"
    "不得建议把这些内容发给他人或其他工厂。"
)


def with_process_secret_harness(system_prompt: str | None) -> str:
    """系统提示词前面带上 :data:`PROCESS_SECRET_HARNESS` (已带过的不重复)。"""
    prompt = system_prompt or ""
    if PROCESS_SECRET_HARNESS in prompt:
        return prompt
    return PROCESS_SECRET_HARNESS + ("\n\n" + prompt if prompt else "")
