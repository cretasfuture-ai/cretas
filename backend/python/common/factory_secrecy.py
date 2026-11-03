"""工厂级保密配置 —— Python 侧的读取 (与 Java ``FactorySecrecyConfig`` 同一张表、同一套规则)。

Steve 2026-11-03: 每一项保密都由平台按工厂开 / 关。配置项的唯一来源是 Java 枚举 ``SecrecyItem``,
这里读它生成的 ``secrecy_items.json`` (Java ``SecrecyItemsJsonDriftContractTest`` 逐字节核对)。

规则 (改这里要与 Java 一起改):

- 直接读 cretas 主库 ``factory_secrecy_settings`` (平台专属表; 工厂端没有写入口)。
- 某工厂某项没有行 = 默认: 关; ``AI_SECRECY_AUDIT`` 跟随 ``PROCESS_SECRET``。
- 工厂为空 / ``PLATFORM`` / 工厂表里没有这家工厂 / 读库失败或超时 → 一律当开启 (偏向保密)。失败不缓存。
- 按工厂缓存 10 秒; 出外脱敏 (c) 的判断用不超过 5 秒的缓存 (:data:`EGRESS_MAX_AGE_S`)。
- ⛔ 按「数据所属的工厂」判, 不按请求者; 跨厂隔离闸 (require_factory_match / tenant_ctx / RLS) 不读任何一项。
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
from pathlib import Path
from typing import Dict, Optional, Tuple

logger = logging.getLogger(__name__)

_ITEMS_FILE = Path(__file__).with_name("secrecy_items.json")
ITEMS = tuple(json.loads(_ITEMS_FILE.read_text(encoding="utf-8"))["items"])
CODES = tuple(item["code"] for item in ITEMS)

PROCESS_SECRET = "PROCESS_SECRET"
PLATFORM_RECIPE_BLOCK = "PLATFORM_RECIPE_BLOCK"
EGRESS_REDACTION = "EGRESS_REDACTION"
TRAINING_FILTER = "TRAINING_FILTER"
CUSTOMER_HELD = "CUSTOMER_HELD"
AI_SECRECY_AUDIT = "AI_SECRECY_AUDIT"

# 生成的 JSON 少了 / 多了一项, 进程起不来 (而不是静默按默认值跑)
_KNOWN = {PROCESS_SECRET, PLATFORM_RECIPE_BLOCK, EGRESS_REDACTION, TRAINING_FILTER, CUSTOMER_HELD, AI_SECRECY_AUDIT}
if set(CODES) != _KNOWN:  # pragma: no cover - 由 test_factory_secrecy 钉住
    raise RuntimeError(f"secrecy_items.json 与 factory_secrecy.py 不一致: {sorted(set(CODES) ^ _KNOWN)}")
_FOLLOWS_PROCESS_SECRET = {item["code"] for item in ITEMS if item.get("default") == "FOLLOW_PROCESS_SECRET"}

CACHE_TTL_S = 10.0
EGRESS_MAX_AGE_S = 5.0
READ_TIMEOUT_S = 5.0

SETTINGS_SQL = "SELECT item_code, enabled FROM factory_secrecy_settings WHERE factory_id = $1"
# 工厂表里没有这家工厂 → 每一项都当开启 (与 Java FactorySecrecyConfig 一致; 审查 #3)
FACTORY_EXISTS_SQL = "SELECT EXISTS (SELECT 1 FROM factories WHERE id = $1)"

# factory_id -> (读到的时间, {item_code: True/False/None(跟随)})
# factory_id -> (读到的时间, 配置; None = 工厂表里没有这家工厂)
_cache: Dict[str, Tuple[float, Optional[Dict[str, Optional[bool]]]]] = {}


def _unknown_factory(factory_id: Optional[str]) -> bool:
    return factory_id is None or not str(factory_id).strip() or str(factory_id).strip().upper() == "PLATFORM"


def resolve(stored: Optional[Dict[str, Optional[bool]]], code: str) -> bool:
    """存的值 -> 生效值。没有行 / 跟随 (None) 按默认: 关, f 跟随 a。stored 为 None (工厂不存在) → 开。"""
    if code not in _KNOWN:
        raise ValueError(f"unknown secrecy item: {code}")
    if stored is None:
        return True
    value = stored.get(code, "UNSET")
    if value is True:
        return True
    if value is False:
        return False
    if code in _FOLLOWS_PROCESS_SECRET:
        return resolve(stored, PROCESS_SECRET)
    return False


async def _read(factory_id: str) -> Optional[Dict[str, Optional[bool]]]:
    from smartbi.config import get_cretas_pool

    pool = await get_cretas_pool()
    if pool is None:
        raise RuntimeError("cretas database pool unavailable")
    async with pool.acquire() as conn:
        if not await conn.fetchval(FACTORY_EXISTS_SQL, factory_id):
            return None
        rows = await conn.fetch(SETTINGS_SQL, factory_id)
    return {r["item_code"]: r["enabled"] for r in rows if r["item_code"] in _KNOWN}


async def is_on(factory_id: Optional[str], code: str, *, max_age: Optional[float] = None) -> bool:
    """该厂 ``code`` 此刻是否开启。工厂不明 / 读不到 → True。"""
    if code not in _KNOWN:
        raise ValueError(f"unknown secrecy item: {code}")
    if _unknown_factory(factory_id):
        return True
    key = str(factory_id).strip()
    ttl = CACHE_TTL_S if max_age is None else min(CACHE_TTL_S, max_age)
    cached = _cache.get(key)
    now = time.monotonic()
    if cached is not None and now - cached[0] < ttl:
        return resolve(cached[1], code)
    try:
        stored = await asyncio.wait_for(_read(key), timeout=READ_TIMEOUT_S)
    except Exception as e:  # 读不到配置: 一律当开启, 不缓存失败
        logger.error("[secrecy-config] read failed for factory %s, treating every item as ON: %s",
                     key, type(e).__name__)
        return True
    _cache[key] = (now, stored)
    return resolve(stored, code)


def cached_is_on(factory_id: Optional[str], code: str, *, max_age: Optional[float] = None) -> bool:
    """同步调用方用 (自建同步客户端): 只看缓存, 不查库; 缓存没有或过期 → True (偏向保密)。"""
    if code not in _KNOWN:
        raise ValueError(f"unknown secrecy item: {code}")
    if _unknown_factory(factory_id):
        return True
    cached = _cache.get(str(factory_id).strip())
    ttl = CACHE_TTL_S if max_age is None else min(CACHE_TTL_S, max_age)
    if cached is None or time.monotonic() - cached[0] >= ttl:
        return True
    return resolve(cached[1], code)


async def egress_redaction_on(factory_id: Optional[str]) -> bool:
    """出外脱敏 (c): 缓存不超过 5 秒。"""
    return await is_on(factory_id, EGRESS_REDACTION, max_age=EGRESS_MAX_AGE_S)


async def process_secret_inert(factory_id: Optional[str]) -> bool:
    """该厂此刻没有任何生效的产品级保密: 工艺保密 (a) 与客户侧保管 (e) 都关着 (e 与 a 无关, 要一起看)。
    读不到 / 工厂不明 → False (按开启)。"""
    return not await is_on(factory_id, PROCESS_SECRET) and not await is_on(factory_id, CUSTOMER_HELD)


def invalidate(factory_id: Optional[str] = None) -> None:
    """清缓存 (测试用; 进程之间不同步, 平台改设置后最多 10 秒生效)。"""
    if factory_id is None:
        _cache.clear()
    else:
        _cache.pop(str(factory_id).strip(), None)


__all__ = [
    "AI_SECRECY_AUDIT", "CODES", "CUSTOMER_HELD", "EGRESS_REDACTION", "ITEMS", "PLATFORM_RECIPE_BLOCK",
    "PROCESS_SECRET", "SETTINGS_SQL", "TRAINING_FILTER", "cached_is_on", "egress_redaction_on", "invalidate",
    "is_on", "process_secret_inert", "resolve",
]
