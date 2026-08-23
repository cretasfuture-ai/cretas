# -*- coding: utf-8 -*-
"""KPI 说「算不出」，同一屏的表里算出了 6 道。

## 判据是一个**合取式**（结构的，⛔ 不是子串）

    kpis 里「总毛利」的 rawValue is None   AND   表里有毛利的行数 > 0

两者同时成立 = 老板同屏看到两句互相矛盾的话。修完这个合取式必须为假。

⛔ 「表里有毛利的行数」用**解析 markdown 表格**得到（按 `|` 切列，
   判那一格是不是金额），⛔ 不是数「¥」出现几次 ——
   数字符会把抬头、实收营收、建议动作里的金额全数进来。

## 成因（读码，我自己推的，⛔ 不照转述）

    KPI 块  restaurant_ops_router.resolve_gross_margin
            L5092 `pos_rows = matched_rows` ⇒ 点名单菜时 pos_rows 已被过滤成
            **那一道**  ⇒ enriched=1 ⇒ 罗氏虾没成本卡 ⇒ 总毛利算不出 ✅ 对的
    表      **另一个模块** daily_table.py（`_row(["菜品","营收","成本","毛利"])`）
            按**全菜品**出，10 行 ✅ 也是对的

▎两个口径都对，**没有一个字告诉老板它们不是一回事**。
⚠️ 这与已上线的「实收营收（全部门店、全部菜品）」是**同一形态**：口径没标。

## 阳性对照（⛔ 不许省）

**鲈鱼有成本卡** ⇒ 它的 KPI 与表**本来就一致**（总毛利算得出）。
- 它必须读出 `rawValue is not None` —— 读不出说明本探针的 KPI 解析坏了，`rc=2`
- 修完它必须**逐字不变**（本探针把全文 md5 打出来供前后对照）

## 阴性对照

`spec.planner_authority == 'llm_unavailable'` 计数必须为 0
（⛔ 顶层 res 没有这个键，写成 res['planner_authority'] 是恒真式），
非 0 ⇒ rc=2 作废，⛔ 不折叠进「答不上」。
"""
from __future__ import annotations

import asyncio
import hashlib
import os
import re
import time

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_KT_FID = "MOCK_REST"
_KT_ROUNDS = 3
_KT_RUN = "%d-%d" % (os.getpid(), int(time.time()))
ctx = bootstrap_probe(_KT_FID)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

# 🔴 2026-08-19 **样本重新分类**（留痕，⛔ 不删）——
#
# 第一版把鲈鱼标成「阳性对照·它的 KPI 与表**本来就一致**，修完必须逐字不变」。
# **那个假设被实测推翻了**：鲈鱼的 KPI 总毛利读出 ¥19,766,090，
# 而它自己表内的毛利是 ¥2,894,006 —— 既不是它自己的，也不是表里 6 行之和。
# ⇒ 鲈鱼是**被测**样本（有卡那一档），⛔ 不是对照。
#
# 真正「修完必须逐字不变」的是**不点名**那一句：它的分母修前修后同一个值，
# 行为应当完全不变。⇒ 把它加进来当**不变性对照**。
# （纪律 12：改完产品立刻回头问「期望翻面了吗」——
#   旧期望是在我还以为鲈鱼一致的时候写的。）
_KT_CASES = [
    ("被测·无成本卡", "罗氏虾卖得怎么样"),
    ("被测·有成本卡", "鲈鱼卖得怎么样"),
    ("不变性对照·不点名", "各个菜的毛利怎么样"),
]

_KT_MONEY_CELL = re.compile(r"^¥?\s*-?[\d,]+\.\d{2}$")


def _clear() -> list:
    """⚠️ 逐个贴出清了哪几个（纪律 4）。
    `clear_promotion_cache` **不存在**，写了会静默 no-op。"""
    done = []
    for n in ("clear_semantic_plan_cache", "clear_route_cache",
              "clear_tenant_gate_cache", "clear_promoted_routes_cache"):
        fn = getattr(ri, n, None)
        if fn is None:
            done.append("%s(⛔不存在)" % n)
            continue
        fn()
        done.append(n)
    return done


def _parse_tables(text: str):
    """解析正文里的 markdown 表格 → [(表头, [数据行, …]), …]。

    ⛔ 不数「¥」出现几次 —— 抬头/实收营收/建议动作里也全是金额。
    """
    tables, cur, header = [], [], None
    for raw in text.split("\n"):
        line = raw.strip()
        if line.startswith("|") and line.endswith("|"):
            cells = [c.strip() for c in line.strip("|").split("|")]
            if set("".join(cells)) <= set("-: "):     # 分隔行
                continue
            if header is None:
                header = cells
            else:
                cur.append(cells)
            continue
        if header is not None:
            tables.append((header, cur))
            cur, header = [], None
    if header is not None:
        tables.append((header, cur))
    return tables


def _margin_rows(text: str):
    """表里**毛利那一列是金额**的行 → [(菜名, 毛利), …]。"""
    out = []
    for header, rows in _parse_tables(text):
        if "毛利" not in header:
            continue
        i = header.index("毛利")
        name_i = 0
        for cells in rows:
            if len(cells) <= i:
                continue
            cell = cells[i].replace("*", "").strip()
            if _KT_MONEY_CELL.match(cell):
                out.append((cells[name_i].replace("*", "").strip(), cell))
    return out


def _kpi(kpis, title: str):
    for k in (kpis or []):
        if isinstance(k, dict) and k.get("title") == title:
            return k
    return None


async def main() -> int:
    pool = await ctx.pool()
    print("PROBE_DB=%s FID=%s 轮数=%d RUN=%s"
          % (ctx.db_name, _KT_FID, _KT_ROUNDS, _KT_RUN))
    if getattr(ctx, "llm_dead_slots", None):
        print("⛔ rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2

    rows = []
    n_unavailable = 0
    for rnd in range(1, _KT_ROUNDS + 1):
        for tag, q in _KT_CASES:
            cleared = _clear()
            set_factory_id(_KT_FID)
            skey = "kt-%s-r%d-%s" % (_KT_RUN, rnd, q)
            async with rr.dish_catalogue_scope(pool, _KT_FID):
                spec = await ri.parse_restaurant_query(
                    q, pool, factory_id=_KT_FID, session_key=skey,
                    semantic_first=True)
                res = None
                if spec is not None and svc.should_delegate(spec, None, query=q):
                    res = await svc.tiered_answer(
                        q, pool, _KT_FID, ctx.role,
                        precomputed_spec=spec, session_key=skey, history=[])
            res = res or {}
            authority = getattr(spec, "planner_authority", None) if spec else None
            if authority == "llm_unavailable":
                n_unavailable += 1
            text = res.get("answer_text") or ""
            kpis = res.get("kpis")
            gp = _kpi(kpis, "总毛利")
            best = _kpi(kpis, "最赚菜品")
            mrows = _margin_rows(text)
            gp_raw = gp.get("rawValue") if gp else "<无此 KPI>"
            gp_val = gp.get("value") if gp else "<无此 KPI>"
            # 🔑 合取式判据
            contradiction = (gp is not None and gp.get("rawValue") is None
                             and len(mrows) > 0)
            md5 = hashlib.md5(text.encode("utf-8")).hexdigest()[:12]
            rows.append((rnd, tag, q, res.get("kind"), authority,
                         gp_val, gp_raw,
                         (best or {}).get("value"), (best or {}).get("rawValue"),
                         len(mrows), mrows[:3], contradiction, md5, len(text)))

            print("\n" + "=" * 78)
            print("r%d 【%s】%s" % (rnd, tag, q))
            print("   清了: %s" % "、".join(cleared))
            print("   kind=%s  authority=%r  正文 %d 字  md5=%s"
                  % (res.get("kind"), authority, len(text), md5))
            print("   KPI 总毛利   : value=%r  rawValue=%r" % (gp_val, gp_raw))
            print("   KPI 最赚菜品 : value=%r  rawValue=%r"
                  % ((best or {}).get("value"), (best or {}).get("rawValue")))
            print("   表里有毛利的行 = %d 行" % len(mrows))
            for nm, v in mrows[:6]:
                print("        %-10s %s" % (nm, v))
            print("   🔑 合取式（KPI 说算不出 ∧ 表里算出了）: %s"
                  % ("🔴 成立 —— 同屏矛盾" if contradiction else "—"))

    print("\n" + "=" * 78)
    print("阴性对照·planner_authority == 'llm_unavailable' = %d 次" % n_unavailable)
    if n_unavailable:
        print("⛔ rc=2 有 fail-closed 拒答混在里面，读数作废")
        return 2

    # 阳性对照：**任何**一轮必须读得出一个非 None 的总毛利 ——
    # 读不出说明本探针的 KPI 解析坏了（⛔ 不是产品不给数）。
    pos = [r for r in rows if r[6] not in (None, "<无此 KPI>")]
    print("阳性对照·读得出非 None 的总毛利 = %d/%d 轮" % (len(pos), len(rows)))
    if not pos:
        print("⛔ rc=2 一轮都读不出总毛利 —— 本探针的 KPI 解析坏了，读数作废")
        return 2
    # 阳性对照第二格：表格解析器必须至少读到一行毛利 ——
    # 读不到的话「合取式不成立」会变成一个**伪造的**「修好了」。
    with_rows = [r for r in rows if r[9] > 0]
    print("阳性对照·表格解析读到毛利行 = %d/%d 轮" % (len(with_rows), len(rows)))
    if not with_rows:
        print("⛔ rc=2 表格解析器一行都没读到 —— 合取式恒不成立，读数作废")
        return 2

    print("\n🔑 判定 · 逐轮（⛔ 不折叠）")
    for r in rows:
        print("   r%d %-18s 总毛利raw=%-12r 最赚菜品=%-10r 表内毛利行=%2d  %s  md5=%s"
              % (r[0], r[1], r[6], r[7], r[9],
                 "🔴" if r[11] else "—", r[12]))

    bad = [r for r in rows if r[11]]
    print("\n🔑 合取式成立 = %d/%d 轮" % (len(bad), len(rows)))
    for tag in sorted({r[1] for r in rows}):
        grp = [r for r in rows if r[1] == tag]
        print("   %-18s %d/%d" % (tag, len([r for r in grp if r[11]]), len(grp)))
    print("\n🔑 **不变性对照**的全文 md5（这一句修前修后必须逐字相同）")
    for r in rows:
        if r[1].startswith("不变性对照"):
            print("   r%d md5=%s  %d 字" % (r[0], r[12], r[13]))
    print("\n🔑 被测组的全文 md5（⚠️ 这几句**本来就该变** —— 数从错的变成对的）")
    for r in rows:
        if r[1].startswith("被测"):
            print("   r%d %-14s md5=%s  %d 字" % (r[0], r[1], r[12], r[13]))

    print("\n⚠️ 口径：MOCK_REST / %d 轮 / **评测题库**，⛔ 不是真实流量。" % _KT_ROUNDS)
    print("⛔ 本探针不下修法结论 —— 上面是事实。")
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
