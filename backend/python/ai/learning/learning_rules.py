"""学用户原话之前的统一判断 (Steve 2026-10-29) —— 与 Java ``LearningContentRules`` 同一套规则。

工艺保密产品可以出现在授权用户的对话里, 但不许被学走、在别的工厂复现:

- 全局 (factory_id 为空 / 空白 / ``*``) 一律不由系统学习写入: 全局行对所有工厂可见;
- 本厂: 撤回训练 / 查不到授权状态不学; 提到本厂工艺保密词 (全名 / 编码 / ID) 不学;
  配方 / 工艺参数词、工资 / 考勤词带具体数值、本厂人员姓名 / 客户名称不学
  (``utterance_has_blocked_content``, 人名客户名按本厂表精确匹配)。

判断结果三态 (:class:`Verdict`): 能学 / 不能学 / **这次判断不了** (授权状态或本厂名单查询出错)。
判断不了的原话不学, 但也不是「不能学」—— 学习任务把它记下来, 下一轮续读重判
(:class:`RetryQueue`), 不因为一次查库失败就永久跳过 (2026-10-29 待办第 2 条)。
授权状态和本厂名单的查询函数由调用方注入 (测试用假的; 生产用 :func:`default_consent_scope` /
:func:`default_factory_lists`)。
"""
from __future__ import annotations

import enum
import logging
from typing import Any, Awaitable, Callable, Dict, Hashable, List, Optional

logger = logging.getLogger(__name__)

ConsentLookup = Callable[[str], Awaitable[str]]
ListsLookup = Callable[[str], Awaitable[Any]]   # -> process_secret_names.FactoryLists


class Verdict(enum.Enum):
    LEARN = "learn"
    SKIP = "skip"
    RETRY = "retry"   # 判断不了 (查询出错): 这次不学, 下一轮续读


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


async def default_factory_lists(factory_id: str):
    from smartbi.services.process_secret_names import factory_lists

    return await factory_lists(factory_id)


async def judge(factory_id: Optional[str], text: Optional[str], *,
                consent_scope: ConsentLookup,
                factory_lists: ListsLookup) -> Verdict:
    """这段原话能不能学进 ``factory_id`` 这一家 (全局一律不行)。"""
    from smartbi.services.training_consent import (
        UTTERANCE_MARKERS,
        is_withdrawn,
        pay_word_with_value,
        process_word_with_value,
    )

    if is_global_factory(factory_id) or not text or not text.strip():
        return Verdict.SKIP
    if process_word_with_value(text, UTTERANCE_MARKERS) or pay_word_with_value(text):
        return Verdict.SKIP
    try:
        scope = await consent_scope(factory_id)
    except Exception as e:  # 查不到可能掩盖了撤回 -> 这次不学, 下一轮再判
        logger.warning("learning: training_consent lookup failed for %s, retry later (%s)",
                       factory_id, type(e).__name__)
        return Verdict.RETRY
    if is_withdrawn(scope):
        return Verdict.SKIP
    try:
        lists = await factory_lists(factory_id)
    except Exception as e:
        logger.warning("learning: factory lists lookup failed for %s, retry later (%s)",
                       factory_id, type(e).__name__)
        return Verdict.RETRY
    return Verdict.SKIP if lists.names_any(text) else Verdict.LEARN


async def factory_learnable(factory_id: Optional[str], text: Optional[str], *,
                            consent_scope: ConsentLookup,
                            factory_lists: ListsLookup) -> bool:
    """只要「能不能学」的调用方用 (判断不了 = 不学)。"""
    return await judge(factory_id, text, consent_scope=consent_scope,
                       factory_lists=factory_lists) is Verdict.LEARN


class RetryQueue:
    """学习任务的续读队列: 这一轮判断不了的行留到下一轮再判 (按 key 去重, 有上限)。

    学习任务每 5 分钟读最近 1 小时的行; 查库失败持续超过窗口时, 这些行就再也读不到了 ——
    所以判断不了的行自己记下来。超过上限时丢最早的并记 WARNING (不静默)。"""

    def __init__(self, limit: int = 2000):
        self.limit = limit
        self._rows: Dict[Hashable, Any] = {}

    def add(self, key: Hashable, row: Any) -> None:
        self._rows.pop(key, None)
        self._rows[key] = row
        while len(self._rows) > self.limit:
            oldest = next(iter(self._rows))
            self._rows.pop(oldest)
            logger.warning("learning retry queue full (%d), dropped the oldest row", self.limit)

    def drain(self) -> List[Any]:
        rows = list(self._rows.values())
        self._rows.clear()
        return rows

    def __len__(self) -> int:
        return len(self._rows)
