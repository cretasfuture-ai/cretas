"""刀2 前置测量：本文件里判「这是不是通名」的几处，各自用**子串**还是**整名相等**，
以及每一处在**真实菜名 / 真实店名**上误伤几个。

⛔ 这个探针不改任何东西，它只回答一句话：
   ▎**这个位置真的需要子串吗？如果改成整名相等，会不会误伤？**

## 为什么要按「位置」量而不是按「词表」量

同一张词表在不同位置的匹配语义不一样：
  · `candidate in _DISH_GENERIC_TOKENS`      整名相等 —— 闭集假设成立
  · `any(tok in q for tok in (...))`          子串 —— 一个裸单字「菜」就能吃掉
                                              所有名字里带「菜」的真菜
所以**误伤要按位置量**，⛔ 不能按词表量。

## 判据

对每一个真实菜名 D，问三句话：
  ① 「D卖得怎么样」   —— 老板点名一道菜
  ② 「D的毛利率」
  ③ 「D的销量」
只要这道菜因为名字里带了某个通名字符而被判成「通名 / 榜单 / 非菜名」，就是误伤。

阳性对照：**真正的榜单问句**（「哪个菜卖得最好」）必须仍然被判成榜单 ——
否则「零误伤」可能只是因为那个判据已经彻底不响了。
"""
from __future__ import annotations

import asyncio
import json
import os
import sys

from smartbi.scripts._probe_bootstrap import bootstrap_probe

ctx = bootstrap_probe(os.environ.get("PROBE_FID", "MOCK_REST"))

from smartbi.gold.restaurant import restaurant_ops_router as R  # noqa: E402
from smartbi.scripts._probe_bootstrap import (  # noqa: E402
    RESTAURANT_TENANTS_WITH_DATA as TENANTS,
)
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

#: 老板**点名一道菜**的问法。
#:
#: 🔴 第一版只写了「{d}卖得怎么样 / 的毛利率 / 的销量」，读出「误伤 0/10」——
#:    而那是**假读数**：位置①是 `有菜品名词 AND 有排名词` 两个合取项，
#:    我的模板一个排名词都没有，第二个合取项恒假 ⇒ 无论第一项怎么误命中都不会响。
#:    ▎我想知道的是「真菜名会不会被当成榜单」，实际量的是「不带排名词的问句会不会」。
#:    ⇒ 必须**同时**给两类模板：不带排名词的（阴性）+ 带排名词的（真正的靶子）。
TEMPLATES_PLAIN = ("{d}卖得怎么样", "{d}的毛利率是多少", "最近30天{d}的销量")
TEMPLATES_RANKY = ("{d}卖得最好吗", "{d}最近销量最高吗", "{d}的毛利最低吗",
                   "最近30天{d}卖得好不好")
TEMPLATES = TEMPLATES_PLAIN + TEMPLATES_RANKY

#: 真正的榜单问句 —— 阳性对照，必须仍然判成榜单。
#: ⚠️ 第一版这里塞了「哪道菜毛利最低 / 单品销量排行 / 产品毛利排名」，实测
#:    只有 2/5 响 —— 那不是缺陷，是 `_DISH_RANK_*_RE` 本来就比我以为的窄。
#:    对照要挑**确实应该响**的，⛔ 不能拿一批我没读过定义的问句当对照。
RANKING_POSITIVE = (
    "哪个菜卖得最好", "卖得最差的菜是哪个", "哪个单品卖得最好",
)


async def dish_names():
    """逐租户取真实菜名。⚠️ 每个租户独立连接 + 会话级 GUC，并回读 RLS 状态 ——
    RLS 表上 0 行 ≠ 表是空的。"""
    from smartbi.gold.queries import tenant_conn
    from smartbi.config import get_pg_pool

    out = {}
    for fid in TENANTS:
        set_factory_id(fid)
        pool = await get_pg_pool()
        try:
            async with tenant_conn(pool, fid) as conn:
                who = await conn.fetchval(
                    "SELECT current_setting('app.factory_id', true)")
                rls = await conn.fetchval(
                    "SELECT relrowsecurity FROM pg_class WHERE relname='dim_product'")
                rows = await conn.fetch(
                    "SELECT DISTINCT name FROM dim_product "
                    "WHERE factory_id = $1 AND name IS NOT NULL", fid)
        except Exception as exc:  # noqa: BLE001
            print("  %-14s 取菜名失败: %r" % (fid, exc))
            continue
        names = sorted({r["name"] for r in rows if r["name"]})
        print("  %-14s rls_says=%-14s relrowsecurity=%s  菜名 %d 个"
              % (fid, who, rls, len(names)))
        out[fid] = names
    return out


#: 被测的「位置」—— 每个是 (标签, 匹配语义, 判断函数)
#: 判断函数返回 True = 这句话在这个位置被判成「不是一道具体的菜 / 是榜单」
POSITIONS = [
    ("router:520 dish_ranking_direction 的 ('菜','单品','产品')", "子串",
     lambda q: R.dish_ranking_direction(q) is not None),
    ("router:1729 extract 里的 candidate in _DISH_GENERIC_TOKENS", "整名相等",
     lambda q: R.extract_dish_candidate(q) is None),
    ("router:2484 extract_store_mentions 的停用词", "整名相等/子串混用",
     lambda q: False),  # 店名侧单列，见下
]


async def main() -> int:
    print("=== 真实菜名(逐租户独立连接) ===")
    catalogues = await dish_names()
    all_names = sorted({n for names in catalogues.values() for n in names})
    print("合计不重复菜名 = %d 个" % len(all_names))
    if not all_names:
        print("🔴 一个菜名都没取到 —— 仪器坏了，读数作废 (rc=2)")
        return 2

    # ── 阳性对照：榜单判据本身还响不响 ────────────────────────────────
    fires = [q for q in RANKING_POSITIVE if R.dish_ranking_direction(q) is not None]
    print("\n阳性对照·真榜单问句被判成榜单: %d/%d  %s"
          % (len(fires), len(RANKING_POSITIVE), fires))
    if len(fires) != len(RANKING_POSITIVE):
        miss = [q for q in RANKING_POSITIVE if q not in fires]
        print("🔴 榜单判据漏了: %s —— 下面的「零误伤」可能只是它不响了" % miss)

    # ── 误伤普查 ────────────────────────────────────────────────────
    print("\n=== 位置① router:520 `any(tok in q for tok in ('菜','单品','产品'))` ===")
    print("语义: **子串**，扫的是**整句问句**")
    hurt = {}
    for name in all_names:
        hits = [t for t in TEMPLATES
                if R.dish_ranking_direction(t.format(d=name)) is not None]
        if hits:
            hurt[name] = hits
    print("被误判成「榜单」的真菜名: %d / %d" % (len(hurt), len(all_names)))
    for name, hits in sorted(hurt.items()):
        why = [tok for tok in ("菜", "单品", "产品") if tok in name]
        print("   🔴 %-16s 触发词=%s  命中问法=%d/3  例: 「%s」-> %s"
              % (name, why, len(hits), hits[0].format(d=name),
                 R.dish_ranking_direction(hits[0].format(d=name))))

    print("\n=== 位置② router:1729 `candidate in _DISH_GENERIC_TOKENS` ===")
    print("语义: **整名相等**")
    lost = {}
    for name in all_names:
        miss = [t for t in TEMPLATES
                if R.extract_dish_candidate(t.format(d=name)) != name]
        if miss:
            lost[name] = [(t.format(d=name),
                           R.extract_dish_candidate(t.format(d=name)))
                          for t in miss]
    print("抽不出原菜名的: %d / %d" % (len(lost), len(all_names)))
    for name, rows in sorted(lost.items()):
        print("   · %-16s %s" % (name, json.dumps(rows[:2], ensure_ascii=False)))

    print("\n=== 位置③ 店名侧 `candidate in _STORE_MENTION_STOPWORDS` ===")
    print("语义: **整名相等**")
    from smartbi.gold.queries import tenant_conn
    from smartbi.config import get_pg_pool
    store_hurt = 0
    store_total = 0
    for fid in TENANTS:
        set_factory_id(fid)
        pool = await get_pg_pool()
        try:
            async with tenant_conn(pool, fid) as conn:
                rows = await conn.fetch(
                    "SELECT DISTINCT name FROM dim_store "
                    "WHERE factory_id = $1 AND name IS NOT NULL", fid)
        except Exception:  # noqa: BLE001
            continue
        for r in rows:
            nm = r["name"]
            store_total += 1
            if nm in R._STORE_MENTION_STOPWORDS:
                store_hurt += 1
                print("   🔴 真店名恰好等于停用词: %r" % nm)
    print("真店名撞停用词: %d / %d" % (store_hurt, store_total))
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
