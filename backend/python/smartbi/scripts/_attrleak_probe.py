# -*- coding: utf-8 -*-
"""同一条路上的**第二个**金额投影点会不会也漏（硬约束 8：数的是整条路，不是一个符号）。

`tiered_answer` 在拼菜品表**之前**还调了 `_maybe_append_attribution`，
而 `attribution.render` 里满是 `_money()`（营收/客单价/贡献三项）。

## 为什么要单独驱动它

用问句去撞它两轮 6/6 全落到 `TREND_ANALYSIS`，而它只认 `SALES_SUMMARY`
⇒ **一次都没触发**。「没触发」不等于「不漏」——那只是我没量到。
⇒ 这里用一个 `intent=SALES_SUMMARY / analysis_action=diagnose / 有窗口`
   的 spec **直接驱动生产函数本体**（⛔ 不重写它的逻辑，只是给它满足前置条件
   的输入），在 prod 真数据上看它吐不吐 ¥。

## 阳性对照

`fired` —— 返回值必须**长于**传入的 `answer_text`（即真的拼了一段）。
没变长就是它 fail-open 提前返回了，本次读数作废（rc=2），
⛔ 不许把「没触发」读成「不漏」。
"""
from __future__ import annotations

import asyncio
import os
import re

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_FID = os.environ.get("PROBE_FID", "MOCK_REST")
_ctx = bootstrap_probe(_FID)

from smartbi.gold.restaurant import restaurant_intent_service as _svc  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

_MONEY_TOKEN = re.compile(r"[¥￥]\s?-?[\d,]+(?:\.\d+)?")

_SENTINEL = "<<ANSWER-BODY>>"


class _Spec:
    """满足 `_maybe_append_attribution` 三个前置条件的最小 spec。

    ⛔ 不是桩掉被测函数 —— 被测的是 `_maybe_append_attribution` 本体，
       这里只提供它读的那三个属性（形态 B‴：桩的形状要是真实上游给得出的，
       而 SALES_SUMMARY + diagnose + 有窗口 正是产品自己会产出的组合）。
    """

    def __init__(self, start, end):
        self.intent = "RESTAURANT_OPS_SALES_SUMMARY"
        self.analysis_action = "diagnose"
        self.date_range = (start, end)


async def main() -> int:
    import datetime

    set_factory_id(_FID)
    pool = await _ctx.pool()
    end = datetime.date.today()
    start = end - datetime.timedelta(days=29)
    spec = _Spec(start, end)
    print("spec: intent=%s analysis_action=%s date_range=%s"
          % (spec.intent, spec.analysis_action, spec.date_range))
    print("_ATTRIBUTION_INTENTS = %s" % sorted(_svc._ATTRIBUTION_INTENTS))
    print("签名: _maybe_append_attribution%s"
          % (__import__("inspect").signature(_svc._maybe_append_attribution),))

    # 修复前签名只有 4 个位置参数，修复后 `role` 是关键字必填。
    # ⛔ 不用 try/except 吞掉 TypeError —— 那会让「这是旧树」和「新树上我传错了」
    #    变成同一个读数。按签名分支，并把走的是哪一支打出来。
    import inspect

    from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES

    has_role = "role" in inspect.signature(
        _svc._maybe_append_attribution).parameters
    print("签名里有 role = %s   (False ⇒ 这是修复前的树)" % has_role)
    if not has_role:
        # 🔴 rc=2, ⛔ 不是 rc=0。这棵树上**问不出**「无权限角色会不会拿到金额」——
        #    因为这个函数还没有 role 这个概念。第一版这里返回 0，于是修复前的树
        #    打出「OK 归因块不吐金额」，而同一行里就写着 ¥token=10 ——
        #    「没量到」被折叠进了「没问题」（硬约束 4）。
        out = await _svc._maybe_append_attribution(pool, _FID, spec, _SENTINEL)
        print("¥token=%d  (这棵树上归因块吐不吐金额与角色无关)"
              % len(_MONEY_TOKEN.findall(out)))
        print("XX rc=2 没量到 —— 这棵树的 `_maybe_append_attribution` 签名里没有 "
              "role, 本探针要问的那个问题在这里不存在")
        return 2
    roles = ("restaurant_manager", "restaurant_waiter")

    rc = 0
    for rnd, role in [(r, x) for r in (1, 2) for x in roles]:
        out = await _svc._maybe_append_attribution(
            pool, _FID, spec, _SENTINEL, role=role)
        fired = len(out) > len(_SENTINEL)
        toks = _MONEY_TOKEN.findall(out)
        priced = role in PRICE_VIEW_ROLES
        print("\n#### r%d role=%s priced=%s  fired(返回值变长)=%s  ¥token=%d"
              % (rnd, role, priced, fired, len(toks)))
        print("  ¥ tokens: %s" % (toks[:10],))
        print("  ┌" + "─" * 74)
        for line in (out or "<空>").splitlines():
            print("  │" + line)
        print("  └" + "─" * 74)
        # 阳性对照：**有**价格权限那一侧必须真的拼上一段、且真的有 ¥。
        # ⛔ 它读空时无权限侧的「0 个 ¥」什么都不说明（仪器坏了也长这样）。
        if priced and not fired:
            print("XX rc=2 阳性对照没过 —— 有权限侧归因块也没拼上, 本次读数作废")
            return 2
        if priced and not toks:
            print("XX rc=2 有权限侧一个 ¥ 都读不到 —— 我的尺子读空了, 作废")
            return 2
        if (not priced) and toks:
            print("   🔴 无价格权限的角色拿到了 %d 个 ¥" % len(toks))
            rc = 1
    if rc:
        print("XX rc=1 归因块本身会吐金额 —— 它是这条路上的**第二个**金额投影点")
    else:
        print("OK rc=0 归因块不吐金额")
    return rc


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
