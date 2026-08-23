# -*- coding: utf-8 -*-
"""晋升表失效积压守望 —— 每天量一次「人审过的晋升，还有几条真的在生效」。

## 为什么有它（⛔ 不是「再加一条日志」）

2026-08-20 逐层查完的实测：

    ai_promoted_routes  40 行（domain=restaurant）
    指纹判有效                0 条
    _load_promoted_routes(MOCK_REST) 真跑一次   0 条生效
    DB 晋升路由回放命中: 08-09 之后连续 10 天 0 次

而这件事**当时已经在喊了** —— `restaurant_intent._load_promoted_routes` 里那句
`logger.warning("%d 条晋升因路由规则指纹不符被跳过...")` 在 prod 上喊了
**755 次、12 天**。逐层查消费方：

    cron            grep '指纹不符|stale_fingerprint' 于 /etc/cron.d /etc/crontab
                    /var/spool/cron → **0 命中**
    告警文件        无
    API /overview   只返回 route_count（=40），SELECT 里根本没取
                    routing_fingerprint ⇒ 永远报 40，而生效是 0
    web-admin UI    Overview.vue 里没有任何晋升条数/失效数的渲染

▎**净效果：喊了 755 次，零消费方。**
▎「喊在日志里」不构成消费方 —— 这个跑批就是那个消费方。

## ⚠️ 它守的是「积压」，⛔ 不是「缺陷」

那 40 条失效是**设计好的、有台账依据的行为**（裸 8-hex 旧格式一律判失效，见
`restaurant_intent.plan_semantics_segment` 的 docstring：取第一段比值会让 39 条
人审过的晋升**静默复活**，而没有台账依据说它们仍有效）。

⇒ 本跑批**只报不改**。它要解决的是「攒了很久没人看」这一件事，
   ⛔ 不碰判定，⛔ 不自动复活任何一条。复活必须人按逐条台账发起。

## 三态退出码（硬约束 4）

    rc=0  无积压（stale_count == 0）
    rc=1  有积压 —— 读数有效，且指向「该有人逐条复审」
    rc=2  **这次没量到** —— 表不存在 / 连不上库 / 判定的对照没过

⛔ 两态会把「没量到」折叠进「没问题」：一个连不上库的跑批会安静地天天绿，
   而它一个晋升条目都没看过。

🔴 rc=2 那一态**必须主动构造一次**才知道它到得了（它平时永远不会自然发生）：

    FLYWHEEL_STALE_TABLE=ai_promoted_routes_does_not_exist python -m \
        smartbi.scripts.flywheel_promotion_staleness

## 台账（硬约束 9）

**每次必往 `FLYWHEEL_STALE_LEDGER` 追加一行**，无论 rc 是几。
▎那是让「今天没告警」变得可判定的那个「必发生」读数 ——
▎⛔ 「探针崩在写告警之前」和「决定不喊」在 `[ -s 告警文件 ]` 里长得一模一样。
"""
from __future__ import annotations

import asyncio
import json
import os
import time

from smartbi.scripts._probe_bootstrap import bootstrap_probe

#: 平台级读法，与 `/overview` 一致（`list_route_promotions` 内部用
#: `__internal__` 哨兵读全量）。这里的 factory 只用于 bootstrap 的 ContextVar。
_FID = os.environ.get("FLYWHEEL_STALE_FACTORY", "MOCK_REST")
_DOMAIN = os.environ.get("FLYWHEEL_STALE_DOMAIN", "restaurant")
_LEDGER = os.environ.get(
    "FLYWHEEL_STALE_LEDGER",
    "/www/wwwroot/cretas/logs/flywheel-promotion-staleness-ledger.jsonl")
#: ⛔ 主动构造 rc=2 用（硬约束 4）。指到不存在的表 ⇒ 存活探针必须判「没量到」。
_TABLE = os.environ.get("FLYWHEEL_STALE_TABLE", "ai_promoted_routes")

ctx = bootstrap_probe(_FID)  # noqa: E402 —— 必须在 import 生产模块之前


def _append_ledger(line: dict) -> bool:
    try:
        os.makedirs(os.path.dirname(_LEDGER) or ".", exist_ok=True)
        # 硬约束 5: 文本模式 + newline=''，⛔ 不做行尾转换、⛔ 不用 bytes 字面量
        with open(_LEDGER, "a", encoding="utf-8", newline="") as fh:
            fh.write(json.dumps(line, ensure_ascii=False) + "\n")
        return True
    except Exception as exc:  # noqa: BLE001
        # ⛔ 台账写不进去必须说出来 —— 它是「今天没告警」可判定的那一半
        print("⛔ 台账写入失败（「今天没告警」将无法判定）: %r" % exc)
        return False


async def main() -> int:
    ts = time.strftime("%Y-%m-%dT%H:%M:%S")
    base = {"ts": ts, "db": ctx.db_name, "domain": _DOMAIN, "table": _TABLE}

    def _dead(reason: str, **extra) -> int:
        print("🔴 INSTRUMENT DEAD — %s" % reason)
        print("   ⇒ 本次读数作废。⛔ 这不是「没有积压」，是「这次没量到」。")
        _append_ledger({**base, "rc": 2, "instrument_dead_reason": reason, **extra})
        return 2

    try:
        pool = await ctx.pool()
    except Exception as exc:  # noqa: BLE001
        return _dead("拿不到连接池: %r" % exc)

    # ── 存活探针: 表在不在。⛔ 与读数本身分开 —— 「表不存在」和「表里 0 行」
    #    在 `len(rows)` 上长得一模一样，而它俩是完全不同的两件事。
    try:
        async with pool.acquire() as conn:
            reg = await conn.fetchval("SELECT to_regclass($1)", _TABLE)
            rls = await conn.fetchrow(
                "SELECT relrowsecurity FROM pg_class WHERE relname = $1", _TABLE)
    except Exception as exc:  # noqa: BLE001
        return _dead("存活探针查不动 pg_class: %r" % exc)
    if reg is None:
        return _dead("表 %r 不存在（migration 没跑？GRANT 没给？）" % _TABLE)
    print("存活探针: to_regclass(%s) = %s, relrowsecurity = %s"
          % (_TABLE, reg, rls and rls["relrowsecurity"]))

    from smartbi.gold.restaurant.restaurant_intent_promotion import (
        summarize_route_promotions,
    )

    try:
        counts = await summarize_route_promotions(pool, domain=_DOMAIN)
    except Exception as exc:  # noqa: BLE001
        return _dead("汇总失败: %r" % exc)

    # 🔴 判定自己的阳性/阴性对照没过时，`stale_count == route_count` 是
    #    **仪器坏**，⛔ 不是「全都失效了」—— 两者在数字上一模一样。
    if not counts.get("instrument_ok"):
        return _dead("指纹判定的阳性/阴性对照没过", counts=counts)

    route_count = int(counts["route_count"])
    stale = int(counts["stale_count"])
    effective = int(counts["effective_count"])

    print()
    print("=== 晋升表失效积压 (domain=%s) ===" % _DOMAIN)
    print("表里有            route_count    = %d" % route_count)
    print("指纹还有效(上界)  effective_count= %d" % effective)
    print("已失效待人审      stale_count    = %d" % stale)
    print("当前语义段        = %s" % counts["current_semantics_fingerprint"])
    print("口径: %s" % counts["counts_methodology"])

    rc = 1 if stale > 0 else 0
    _append_ledger({**base, "rc": rc, "route_count": route_count,
                    "stale_count": stale, "effective_count": effective,
                    "current_semantics_fingerprint":
                        counts["current_semantics_fingerprint"]})

    print()
    if rc == 1:
        print("🔴 BACKLOG — %d 条人审过的晋升当前不生效（回放会回落 planner，"
              "答案仍对、只是慢且费 token）。" % stale)
        print("   ⛔ 不要靠改判定来「修好」它 —— 那会让 %d 条人审静默复活。"
              % stale)
        print("   ⇒ 人按台账逐条复审后重新盖章。先看逐条「生效/已失效」:")
        # ⚠️ `--list-routes` 才是晋升表; `--list` 是**候选队列**(另一件事)。
        #    2026-08-20 实测: 第一版这句写的是 `--list`, 真跑一次才发现它打的是
        #    候选而不是晋升表 —— 一条把人引到错屏幕的行动指引, 比没有指引更糟。
        print("     python scripts/restaurant-intent-promote.py --list-routes")
    elif route_count == 0:
        # ⚠️ 「表里 0 条」和「都有效」都是 rc=0，但⛔ 不能说成同一句话 ——
        #    空是这张表的**合法状态**（还没人晋升过任何一条），
        #    而把它印成「都还有效」会让读的人以为量到了东西。
        print("✅ 无积压 — 但这是因为 domain=%s 下**一条晋升都没有**（合法状态，"
              "⛔ 不是「都还有效」）。" % _DOMAIN)
    else:
        print("✅ 无积压 — %d 条晋升的指纹都还是当前语义段。" % route_count)
    print()
    print("🔑 rc=%d （0=无积压 / 1=有积压待人审 / 2=这次没量到，读数作废）" % rc)
    return rc


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
