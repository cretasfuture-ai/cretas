# -*- coding: utf-8 -*-
"""「X 的那家店」是描述还是店名 —— 判据候选的误伤实测。

## 为什么需要它

`_named_store_in_question` 现在只信模型提名的名字。prod 实测（`_store_absent_verify`
第二轮）：模型 **3/3 给 `store=null`**，于是判据够不着，静默顶替回来了 3/3。
⇒ 必须补上「老板自己措辞里的门店提及」那一路。

而第一版就是因为它被回归打红：

    extract_store_mention("拖后腿的那家店是哪家") == "拖后腿的那家店"

`extract_store_mentions` 的注释**自己登记过这一格**：

> ⚠️ 下面这些不是疑问词, 是**排名/极值词**和指标名, 属于另外两类:
>    排名/极值 → 另一个封闭集合 … 这两类单独登记、单独做。

⛔ 但本探针不去补那个词表（「拖后腿」之后还有「垫底」「掉队」「不给力」…，
   永远补不完，而且撞词表棘轮 88/88 零余量）。
▎换一个**结构**判据：「X **的**那家店 / 这家店 / 那个店」是**描述性定语**，
▎不是名字。名字里不会有「的那家」「的这家」。

## 判据（三个候选，⛔ 逐个量，不先挑）

    C1  含「的那家」/「的这家」/「的那个」/「的这个」
    C2  去掉结尾的指代量词之后，剩下的以「的」结尾
    C3  C1 或 C2

## 阳性/阴性对照（⛔ 不许省）

- **误伤面**：拿**真实门店名**跑一遍，命中数必须为 0
  （沿用 `_INTERROGATIVE_MARKERS` 换代时同一条做法：那次量的是
   198 个真店名、封闭集合命中 0 个）
- **命中面**：一组「按门店拆但没点名」的问句必须被判成「不是名字」
- **阴性**：一组**真的点名了**的问句必须**仍然**被判成「是名字」
- ⚠️ 逐租户 `set_config` 单独会话读 `dim_store` —— RLS 下不设会话变量恒 0 行
"""
from __future__ import annotations

import asyncio
import re

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_MN_FID = "MOCK_REST"
ctx = bootstrap_probe(_MN_FID)

from smartbi.gold.restaurant.restaurant_ops_router import (  # noqa: E402
    extract_store_mention,
)

#: 指代量词 —— 结尾出现时先剥掉，再看剩下的是不是一个「定语」。
_MN_REF_TAIL = re.compile(r"(?:这|那|哪)(?:一)?(?:家|个|间)?(?:门店|店|分店)$")


def _c1(c: str) -> bool:
    return any(t in c for t in ("的那家", "的这家", "的那个", "的这个",
                                "的那间", "的这间"))


def _c2(c: str) -> bool:
    stripped = _MN_REF_TAIL.sub("", c)
    return bool(stripped) and stripped != c and stripped.endswith("的")


def _c3(c: str) -> bool:
    return _c1(c) or _c2(c)


_MN_CRITERIA = (("C1 含「的那家」类", _c1), ("C2 剥指代后以「的」结尾", _c2),
                ("C3 C1 或 C2", _c3))

#: 命中面：按门店拆、但**没有点名**任何一家店的问句。
_MN_SHOULD_REJECT = [
    "拖后腿的那家店是哪家", "最差的那家店", "亏钱的那家店是哪个",
    "卖得最好的那家店", "毛利最低的那家店在哪", "生意最差的这家店怎么回事",
]
#: 阴性对照：**真的点名了**的问句 —— 必须仍被判成「是名字」。
_MN_SHOULD_KEEP = [
    "北京三里屯店卖得怎么样", "模拟·徐汇美罗城店卖得怎么样",
    "和平店最近30天营收", "浦东店和静安店比一比",
]


async def main() -> int:
    pool = await ctx.pool()
    print("PROBE_DB=%s" % ctx.db_name)

    # ── 误伤面：真实门店名 ────────────────────────────────────────────
    # 🔴 租户名单**从表里枚举**，⛔ 不用我自己维护的 `SURVIVING_TENANTS`：
    #    第一版用了它，只读到 10 个真店名（另外三家 dim_store 0 行），
    #    而这条判据的先例（`_INTERROGATIVE_MARKERS` 换代）量的是 **198 个**。
    #    误伤面的分母小一个量级，把握就不是一回事。
    async with pool.acquire() as conn:
        tenants = [r["factory_id"] for r in await conn.fetch(
            "SELECT DISTINCT factory_id FROM dim_store ORDER BY factory_id")]
    print("dim_store 里有门店的租户 = %d 家: %s" % (len(tenants), tenants))
    real_names = []
    for fid in tenants:
        async with pool.acquire() as conn:      # ⚠️ 每个租户独立会话
            async with conn.transaction():
                await conn.execute(
                    "SELECT set_config('app.factory_id', $1, false)", fid)
                rows = await conn.fetch(
                    "SELECT name FROM dim_store WHERE factory_id = $1", fid)
                got = await conn.fetchval("SELECT current_setting('app.factory_id')")
        print("  %-14s 会话上下文=%-14s 门店 %d 家" % (fid, got, len(rows)))
        real_names.extend(r["name"] for r in rows if r["name"])
    print("真实门店名合计 = %d 个" % len(real_names))
    if len(real_names) < 10:
        print("⛔ rc=2 真店名不足 10 个 —— 误伤面量不出来，读数作废")
        return 2

    # 🔴 分母订正（形态 A：我量的数不是我想知道的那个数）：
    #    第一版报「该拒的拒了 2/6」，而另外 4 条 `extract_store_mention`
    #    **本来就返回 None**（已被既有的 最高/最低/最好/最差 那组挡掉）。
    #    ▎判据只能对「抽取器真的产出了候选」的那些负责 —— 分母是它，不是 6。
    with_candidate = [q for q in _MN_SHOULD_REJECT if extract_store_mention(q)]
    print("\n该拒的那组里，抽取器**真的产出候选**的 = %d/%d（其余本来就返回 None）"
          % (len(with_candidate), len(_MN_SHOULD_REJECT)))

    print("\n" + "=" * 76)
    print("%-24s %-10s %-12s %-12s" % ("判据", "误伤(必须0)", "该拒的拒了", "该留的留了"))
    best = []
    for tag, fn in _MN_CRITERIA:
        harm = [n for n in real_names if fn(n)]
        rejected = [q for q in with_candidate
                    if (m := extract_store_mention(q)) and fn(m)]
        kept = [q for q in _MN_SHOULD_KEEP
                if (m := extract_store_mention(q)) and not fn(m)]
        print("%-24s %-10s %-12s %-12s" % (
            tag, len(harm), "%d/%d" % (len(rejected), len(with_candidate)),
            "%d/%d" % (len(kept), len(_MN_SHOULD_KEEP))))
        if harm:
            print("     ⛔ 误伤的真店名: %s" % harm[:6])
        best.append((tag, len(harm), len(rejected), len(kept)))

    print("\n逐条：该拒的那组，`extract_store_mention` 抽到了什么")
    for q in _MN_SHOULD_REJECT:
        m = extract_store_mention(q)
        print("  %-22s -> %-18r  C1=%s C2=%s C3=%s" % (
            q, m, _c1(m or ""), _c2(m or ""), _c3(m or "")))
    print("\n逐条：该留的那组（阴性对照）")
    for q in _MN_SHOULD_KEEP:
        m = extract_store_mention(q)
        print("  %-22s -> %-18r  C3=%s（True 就是误伤）" % (q, m, _c3(m or "")))

    ok = [t for (t, h, r, k) in best
          if h == 0 and r == len(with_candidate) and k == len(_MN_SHOULD_KEEP)]
    print("\n🔑 三条都满足的判据: %s" % (ok or "（没有 —— ⛔ 别硬选一个）"))
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
