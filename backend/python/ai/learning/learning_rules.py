"""学用户原话之前的统一判断 (Steve 2026-10-29) —— 与 Java ``LearningContentRules`` 同一套规则。

工艺保密产品可以出现在授权用户的对话里, 但不许被学走、在别的工厂复现:

- 全局 (factory_id 为空 / 空白 / ``*``) 一律不由系统学习写入: 全局行对所有工厂可见;
- 本厂: 撤回训练 / 查不到授权状态不学; 提到本厂工艺保密产品不学;
  配方 / 工艺参数、具体客户名、人名不学 (``utterance_has_blocked_content``)。

判断所需的查询出错一律按「不能学」处理 (fail-closed)。授权状态和保密产品名的查询函数
由调用方注入 (测试用假的; 生产用 :func:`default_consent_scope` / :func:`default_protected_names`)。
"""
from __future__ import annotations

import logging
from typing import Awaitable, Callable, Iterable, Optional

logger = logging.getLogger(__name__)

ConsentLookup = Callable[[str], Awaitable[str]]
NamesLookup = Callable[[str], Awaitable[Iterable[str]]]


def is_global_factory(factory_id: Optional[str]) -> bool:
    return factory_id is None or not str(factory_id).strip() or str(factory_id).strip() == "*"


async def default_consent_scope(factory_id: str) -> str:
    """training_consent 在 SmartBI 库; 查不到行 = 'none' (默认参与, 可学), 出错向上抛。"""
    from smartbi.config import get_pg_pool
    from smartbi.services.training_consent import fetch_consent_scope

    pool = await get_pg_pool()
    if pool is None:
        raise RuntimeError("smartbi database pool unavailable")
    async with pool.acquire() as conn:
        return await fetch_consent_scope(conn, factory_id)


async def default_protected_names(factory_id: str) -> Iterable[str]:
    from smartbi.services.process_secret_names import protected_product_names

    return await protected_product_names(factory_id)


async def factory_learnable(factory_id: Optional[str], text: Optional[str], *,
                            consent_scope: ConsentLookup,
                            protected_names: NamesLookup) -> bool:
    """这段原话能不能学进 ``factory_id`` 这一家 (全局一律不行)。"""
    from smartbi.services.process_secret_names import mentions_any
    from smartbi.services.training_consent import is_withdrawn, utterance_has_blocked_content

    if is_global_factory(factory_id) or not text or not text.strip():
        return False
    if utterance_has_blocked_content(text):
        return False
    try:
        scope = await consent_scope(factory_id)
    except Exception as e:  # 查不到可能掩盖了撤回 -> 不学
        logger.warning("learning: training_consent lookup failed for %s, not learning (%s)",
                       factory_id, type(e).__name__)
        return False
    if is_withdrawn(scope):
        return False
    try:
        names = await protected_names(factory_id)
    except Exception as e:
        logger.warning("learning: process-secret lookup failed for %s, not learning (%s)",
                       factory_id, type(e).__name__)
        return False
    return not mentions_any(names, text)
