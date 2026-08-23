# -*- coding: utf-8 -*-
"""老板打的菜名**比菜单里的更长**时，那是简称还是另一道菜？

## prod 实测（`_audit_nearmiss`，2 轮一致）先把缺陷说准

问「最近30天水煮牛肉丝卖得怎么样」（菜单里只有「水煮牛肉」），老板拿到：

    具体菜品我这次跑出来了，但里面有几句话没达到我们「不把推测说成结论」的
    要求，所以我没发给你 …… 你可以把问题问得更小一点（限定到某家店、某道菜
    或某个时间段），那条路我能给你确定的数。

⛔ **不是**静默顶替（它没把水煮牛肉的数说成水煮牛肉丝的）—— 这点是好的。
🔴 但：① 那是一句讲**产品自己内部流程**的话；② 真正的原因（菜单里没这道菜）
产品**手上就有**；③ 它给的出路「限定到某道菜」老板**已经做了** ——
**一条他做不到的指引**。⇒ 交付定义⑤ 三件事一件都没给。

⚠️ 而「具体菜品我这次跑出来了」说明执行链**真的拿水煮牛肉的数据算了** ——
接地闸只是最后一道保险，拦下的理由还是别的。⇒ 修法必须在更前面。

## 被测的那条规则

`_catalogue_says_not_a_dish` 里有**两个方向**的子串放行：

    candidate in name          老板说了**半个**菜名（「水煮」→「水煮牛肉」）  合理
    name in candidate          老板说的**比菜名长**（「水煮牛肉丝」）          ← 被测

▎第二条把「另一道菜 / 打错字」也放行了。

## 判据候选（⛔ 逐个量，不先挑）

    P0  现状：两个方向都放行
    P1  只留 `candidate in name`（长的一律判「不是这道菜」）
    P2  P1 + 先剥掉口语后缀（「这道菜」「那道菜」…）再比

## 阳性/阴性对照（⛔ 不许省）

- **误伤面**：菜单里的真菜名本身 / 半个 / 带口语后缀，**必须仍被放行**
- **命中面**：比菜名长的近似写法必须被判「不是这道菜」
- **阳性对照**：一个完全不像的假菜（麻婆豆腐）在所有候选下都必须被判「不是菜」
- ⚠️ 目录必须 ≥4 条，否则判据跑不动
"""
from __future__ import annotations

import asyncio
import os
import time

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_DL_FID = "MOCK_REST"
_DL_RUN = "%d-%d" % (os.getpid(), int(time.time()))
ctx = bootstrap_probe(_DL_FID)

from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

#: 口语后缀 —— 剥掉之后再比（P2）。⛔ 封闭集合，不是黑名单。
_DL_SPOKEN_TAILS = ("这道菜", "那道菜", "这个菜", "那个菜", "这道", "那道")


def _strip_tail(c: str) -> str:
    for t in _DL_SPOKEN_TAILS:
        if c.endswith(t):
            return c[: -len(t)]
    return c


def _p0(c: str, names) -> bool:
    """现状：两个方向都放行。返回 True = 判「不是菜」。"""
    if not names or not c:
        return False
    if c in names:
        return False
    for n in names:
        if c in n:
            return False
        if len(n) >= 2 and n in c:
            return False
    return True


def _p1(c: str, names) -> bool:
    """只留「老板说了半个菜名」这个方向。"""
    if not names or not c:
        return False
    if c in names:
        return False
    for n in names:
        if c in n:
            return False
    return True


def _p2(c: str, names) -> bool:
    """P1 + 先剥口语后缀。"""
    return _p1(_strip_tail(c), names)


def _generic_tail_consumable(tail: str) -> bool:
    """尾巴能不能被**已登记的**通用词整个消掉。

    ⛔ 零新增词表：用的是 `_DISH_GENERIC_TOKENS`（棘轮 88/88 零余量，
       新增任何词表/正则都会当天弄红一道闸）。
    ⚠️ 只对**已经与菜名切开**的尾巴用 —— 直接对整个候选贪心剥尾会把
       「干锅花**菜**」剥成「干锅花」（「菜」就在那张表里）。
    """
    from smartbi.gold.restaurant.restaurant_ops_router import _DISH_GENERIC_TOKENS
    t = tail
    changed = True
    while t and changed:
        changed = False
        for tok in sorted(_DISH_GENERIC_TOKENS, key=len, reverse=True):
            if tok and t.endswith(tok):
                t = t[: -len(tok)]
                changed = True
                break
    return t == ""


def _p3(c: str, names) -> bool:
    """P3：先切出「菜单名 + 尾巴」，只判尾巴是不是口语。

    「凉拌木耳这道菜」→ 菜名=凉拌木耳 / 尾巴=这道菜（=「这道」+「菜」）⇒ 放行
    「水煮牛肉丝」    → 菜名=水煮牛肉 / 尾巴=丝                    ⇒ 判「另一道菜」
    「干锅花菜」      → 它本身在菜单里, 第一条就放行, ⛔ 不会被拆
    """
    if not names or not c:
        return False
    if c in names:
        return False
    for n in names:
        if c in n:                       # 老板说了半个 —— 合理
            return False
    for n in names:
        if len(n) >= 2 and c.startswith(n):
            return not _generic_tail_consumable(c[len(n):])
    # 菜名出现在中间/末尾而不是开头 ⇒ 保守放行，⛔ 不下断言
    return False


def _p4(c: str, names) -> bool:
    """P4 = 现状 ∨ P3 —— **实际要上的那个**。

    🔴 判据订正（形态 A，这次是在我自己的仪器上）：
       第一版给 P3 配的阳性对照是「麻婆豆腐必须被拒」，实测 P3=False，
       我差点把它读成「P3 不合格」。而 P3 是**故意窄**的：它只回答
       「比菜单里的那道长」。麻婆豆腐由**现有的** `_catalogue_says_not_a_dish`
       拒掉（P0=True）。
       ▎我想知道的 X = 「接上去之后覆盖全不全」；
       ▎我实际量的 Y = 「P3 单独覆盖全不全」。X ≠ Y。
    ⇒ 生产上的接法就是 `P0 or P3`，所以判据也要按 `P0 or P3` 量。
    """
    return _p0(c, names) or _p3(c, names)


_DL_CRITERIA = (("P0 现状", _p0), ("P1 只留半个菜名", _p1),
                ("P2 P1+剥口语后缀", _p2), ("P3 切菜名只判尾巴", _p3),
                ("P4 = P0 或 P3（要上的）", _p4))


async def main() -> int:
    pool = await ctx.pool()
    set_factory_id(_DL_FID)
    async with rr.dish_catalogue_scope(pool, _DL_FID):
        cat = tuple(rr.current_dish_catalogue() or ())
    print("PROBE_DB=%s  目录 = %d 条  RUN=%s" % (ctx.db_name, len(cat), _DL_RUN))
    print("菜单: %s" % list(sorted(cat)))
    if len(cat) < 4:
        print("⛔ rc=2 目录不足 4 条，判据跑不动")
        return 2

    # ── 先看菜单**自己**有没有互为子串的（那会让 P1 误伤真菜）────────
    print("\n菜单内部的子串关系（⛔ 有的话 P1 会误伤真菜）:")
    inner = [(a, b) for a in cat for b in cat if a != b and a in b]
    print("  %s" % (inner or "（无）"))

    ordered = sorted(cat)
    real = ordered[0]
    longest = max(ordered, key=len)

    # 误伤面：这些**必须**仍被放行（判「不是菜」= False）
    must_keep = []
    for n in ordered:
        must_keep.append((n, "真菜名本身"))
        if len(n) >= 3:
            must_keep.append((n[:2], "半个菜名(前2字)"))
        must_keep.append((n + "这道菜", "真菜名+口语后缀"))
    # 命中面：这些**必须**被判「不是这道菜」
    must_reject = [
        ("水煮牛肉丝", "比菜单里的长一个字"),
        ("米饭套餐", "比菜单里的长"),
        ("藤椒鸡腿堡", "比菜单里的长"),
        ("麻婆豆腐", "完全不像（阳性对照）"),
    ]

    print("\n" + "=" * 76)
    print("%-18s %-14s %-14s %s" % ("判据", "误伤(必须0)", "该拒的拒了", "阳性对照"))
    rows = []
    for tag, fn in _DL_CRITERIA:
        harm = [(c, why) for c, why in must_keep if fn(c, cat)]
        rejected = [(c, why) for c, why in must_reject if fn(c, cat)]
        control = fn("麻婆豆腐", cat)
        print("%-18s %-14s %-14s %s" % (
            tag, len(harm), "%d/%d" % (len(rejected), len(must_reject)),
            "OK" if control else "🔴 连麻婆豆腐都没拒"))
        if harm:
            print("     ⛔ 误伤: %s" % harm[:6])
        rows.append((tag, len(harm), len(rejected), control))

    print("\n逐条：该拒的那组")
    for c, why in must_reject:
        print("  %-14s %-18s P0=%-6s P1=%-6s P2=%-6s P3=%s"
              % (c, why, _p0(c, cat), _p1(c, cat), _p2(c, cat), _p3(c, cat)))
    print("\n逐条：误伤面**全量**（P3 的分母就是它）")
    for c, why in must_keep:
        mark = "  🔴" if _p3(c, cat) else ""
        print("  %-16s %-18s P0=%-6s P2=%-6s P3=%s%s"
              % (c, why, _p0(c, cat), _p2(c, cat), _p3(c, cat), mark))

    # ⚠️ 抽取器会不会把口语后缀带进 dish_slot —— 决定 P2 是不是必要的
    print("\n抽取器对带口语后缀的问句抽出什么（决定 P2 是不是多余）:")
    for q in ("%s这道菜卖得怎么样" % real, "%s卖得怎么样" % real,
              "水煮牛肉丝卖得怎么样"):
        print("  %-22s -> %r" % (q, rr.extract_dish_candidate(q)))

    ok = [t for (t, h, r, c) in rows if h == 0 and r == len(must_reject) and c]
    print("\n🔑 三条都满足的判据: %s" % (ok or "（没有 —— ⛔ 别硬选一个）"))
    print("⚠️ 口径：误伤面 = %d 条构造样本（真菜 %d 道 × 三种写法），"
          "⛔ 不是真实流量。" % (len(must_keep), len(ordered)))
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
