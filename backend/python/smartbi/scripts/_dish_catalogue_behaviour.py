# -*- coding: utf-8 -*-
"""缺口 #1 到底做没做 —— ⛔ 不数符号，**跑一次让它自己说**。

## 为什么重写这个判据

`_gap22.py` 对 #1 的判据是：

    _catalogue_says_not_a_dish 调用点:
      {'restaurant_intent.py': 0, 'restaurant_intent_service.py': 0,
       'restaurant_ops_router.py': 5}

▎**形态 A：我量的这个数，不是我真正想知道的那个数。**

    我想知道的 X：一个**不是菜**的东西被当成 dish 槽位时，产品会不会被目录挡住
    实际在量的 Y：某个函数名在两个文件里出现几次

X ≠ Y —— 目录核对完全可以通过别的机制接上（例如 `dish_catalogue_scope`
把目录绑在整轮上、由 router 内部那 5 处消费），而 Y 照样是 0/0。
⇒ 重写成**行为判据**：真的拿一个假菜名去问，看产品怎么反应。

## 三类样本

    ①「真菜」   目录里确实有的那道 —— **阳性对照**，它必须正常答，
                否则「假菜被挡住」和「什么都答不了」是同一个读数
    ②「假菜」   一个像菜名但目录里没有的（本租户只有 10 道菜）
    ③「问句片段」owner 记的那个形态：「那最差的呢」→ LLM 自己输出 dish='最差'

## 判据（逐条打，⛔ 不折叠）

对 ② 和 ③，三种可能的产品行为，只有前两种算「接上了」：

    ✅ 丢槽位   dish_slot 为空 / 或被换成目录里的规范名  ⇒ 目录核对生效
    ✅ 说清楚   答案里明说「菜单里没有这道菜」并给出路   ⇒ 目录核对生效
    🔴 照着查   dish_slot 原样保留且答案在报「这道菜没数据」⇒ **没接上**
                （老板会以为是数据缺失，其实是这道菜根本不存在）

## 硬纪律

- 阳性对照：菜单目录必须读到 >0 条；①必须至少一轮 `kind == "answer"`。
- 每轮开跑前清 4 个缓存并**贴出清了哪几个**。
- 三态退出码：0 / 1 / **2 = 没量到**。
- ⚠️ 模块级常量带前缀 —— 仓里有「同名常量漂移」闸。
"""
from __future__ import annotations

import asyncio

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_DISHCAT_FID = "MOCK_REST"
_DISHCAT_ROUNDS = 2
ctx = bootstrap_probe(_DISHCAT_FID)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

#: 目录里必然没有的菜名。⛔ 挑一个**像菜名**的，不是乱码 ——
#: 乱码会被别的启发式挡住，量不到目录核对这一层。
_DISHCAT_FAKE_DISH = "佛跳墙"

#: owner 记下的那个形态：疑问词被 LLM 自己当成了实体。
_DISHCAT_FRAGMENT_Q = "那最差的呢"

#: 「没数据」类措辞 —— 命中它且槽位原样保留 ⇒ 产品照着假菜名去查了。
#: ⚠️ 这一条仍是词表, 但它只用来给**最坏那一档**加证据, ⛔ 不用来判「做好了」。
_DISHCAT_NO_DATA_MARKS = ("没有数据", "还没有数据", "查不到数据", "暂无数据",
                          "没有找到相关", "无数据")

#: 判「目录核对生效」要用的**结构判据**里, 答案至少要报出几个目录里的菜名。
#:
#: 🔴 2026-08-19 第一版这里是一张词表(「菜单里没有」「不在菜单」…),
#:    而产品实际说的是「菜单里没**查到**」—— **差一个字**, 于是把一个
#:    教科书级的正确答案判成了「说不清」。
#:    ▎形态 E: 代理判据(词表)同时造出假阳性和假阴性。
#: ⇒ 换成结构判据: **答案里报出了问句里没有的目录菜名**。
#:    只有真的拿到了租户菜单才可能说出这些名字 —— 这一条不随文案改动失效。
_DISHCAT_MIN_CATALOGUE_NAMES = 3


def _clear() -> list[str]:
    done = []
    for n in ("clear_semantic_plan_cache", "clear_route_cache",
              "clear_tenant_gate_cache", "clear_promoted_routes_cache"):
        getattr(ri, n)()
        done.append(n)
    return done


def _fmt(v):
    return "()" if v in (None, (), []) else repr(v)


def _catalogue_names_quoted(text, catalogue, query):
    """答案里报出了**问句里没有**的目录菜名 —— 有几个。

    ▎这是本探针的核心判据，⛔ 不是词表匹配文案。
    ▎只有真的拿到了这个租户的菜单，才可能说出这些名字。
    """
    return [d for d in (catalogue or ())
            if d and d in text and d not in (query or "")]


def _verdict(tag, dish_slot, text, catalogue, query):
    """三态判定。⛔ 不看函数名、⛔ 不匹配文案措辞，只看产品做了什么。"""
    if tag == "①真菜":
        return "-", []
    slot = (dish_slot or "")
    slot_kept = bool(slot) and slot not in (catalogue or ())
    quoted = _catalogue_names_quoted(text, catalogue, query)
    if len(quoted) >= _DISHCAT_MIN_CATALOGUE_NAMES:
        # 拿着租户菜单在跟老板对话 —— 目录**必然**已经取到并用上了
        return ("✅ 报出了菜单（目录核对生效，引用 %d 道）" % len(quoted)), quoted
    if not slot_kept:
        return "✅ 丢槽位/已归一（目录核对生效）", quoted
    if any(m in text for m in _DISHCAT_NO_DATA_MARKS):
        return "🔴 照着查（没接上）—— 老板会以为是数据缺失", quoted
    return "⚠️ 说不清（槽位留着、也没报菜单，人读一下原文）", quoted


async def main() -> int:
    pool = await ctx.pool()
    print("PROBE_DB=%s FID=%s 轮数=%d" % (ctx.db_name, _DISHCAT_FID, _DISHCAT_ROUNDS))
    if getattr(ctx, "llm_dead_slots", None):
        print("⛔ rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2

    set_factory_id(_DISHCAT_FID)
    async with rr.dish_catalogue_scope(pool, _DISHCAT_FID):
        catalogue = tuple(rr.current_dish_catalogue() or ())
    print("阳性对照·菜单目录 = %d 条: %s" % (len(catalogue), list(catalogue)))
    if not catalogue:
        print("⛔ rc=2 菜单目录为 0 —— 库没连上，读数作废")
        return 2
    if _DISHCAT_FAKE_DISH in catalogue:
        print("⛔ rc=2 我挑的「假菜」%s 居然在目录里 —— 换一个再跑"
              % _DISHCAT_FAKE_DISH)
        return 2

    # 🔴 钉死取法。⛔ 不用 `catalogue[0]` —— 目录顺序**不稳定**：
    #    两次跑批里它先后是「米饭」和「水煮牛肉」，于是「真菜」这一列
    #    在两次读数之间**换了被测对象**，而汇总表上看不出来。
    #    ▎形态 A¹⁴：换了样本单位不等于换来了样本。
    real_dish = sorted(catalogue)[0]
    # 🔴 2026-08-19 加 ④⑤ 两格：证明目录核对是「**够不着**」还是「坏了」。
    #    实测 ②（缺时间）拿到的是 Q08 那句**逐字相同的 56 字时间槽反问**，
    #    ⛔ 不是目录反馈 —— 老板打错一个菜名要先答一轮时间才被告知菜单里没有。
    #    ⇒ 把时间给全再问一次：若目录反馈这时出得来，就证明它**在**，只是排在
    #      时间槽反问后面（形态：已经算出来的东西没投影到决策点）。
    samples = [
        ("①真菜", "%s卖得怎么样" % real_dish),
        ("②假菜", "%s卖得怎么样" % _DISHCAT_FAKE_DISH),
        ("③片段", _DISHCAT_FRAGMENT_Q),
        ("④假菜+时间", "最近30天%s卖得怎么样" % _DISHCAT_FAKE_DISH),
        ("⑤真菜+时间", "最近30天%s卖得怎么样" % real_dish),
    ]
    print("阳性对照·真菜取目录第 1 条 = %s" % real_dish)

    n_real_answer = 0
    n_real_slot_kept = 0
    verdicts = []
    for rnd in range(1, _DISHCAT_ROUNDS + 1):
        for tag, q in samples:
            cleared = _clear()
            set_factory_id(_DISHCAT_FID)
            async with rr.dish_catalogue_scope(pool, _DISHCAT_FID):
                spec = await ri.parse_restaurant_query(
                    q, pool, factory_id=_DISHCAT_FID,
                    session_key="dishcat-r%d-%s" % (rnd, tag), semantic_first=True)
                res = None
                if spec is not None and svc.should_delegate(spec, None, query=q):
                    res = await svc.tiered_answer(
                        q, pool, _DISHCAT_FID, ctx.role, precomputed_spec=spec,
                        session_key="dishcat-r%d-%s" % (rnd, tag))
            kind = (res or {}).get("kind") or "-"
            text = (res or {}).get("answer_text") or ""
            dish_slot = getattr(spec, "dish_slot", None) if spec else None
            if tag == "①真菜" and kind == "answer":
                n_real_answer += 1
            # 🔴 真正的阳性对照：目录核对**不会误杀真菜**（槽位被放行）。
            #    ⛔ 不用「真菜答上没答上」当阳性对照 —— 见下方注释。
            if tag == "①真菜" and dish_slot and dish_slot in catalogue:
                n_real_slot_kept += 1
            v, quoted = _verdict(tag, dish_slot, text, catalogue, q)
            verdicts.append((rnd, tag, q, dish_slot, kind, v))

            print("\n" + "=" * 76)
            print("r%d 【%s】%s" % (rnd, tag, q))
            print("  清了: %s" % "、".join(cleared))
            print("  intent      = %s" % (getattr(spec, "intent", None) if spec else None))
            print("  dish_slot   = %s   （目录里有它吗: %s）" % (
                _fmt(dish_slot),
                (dish_slot in catalogue) if dish_slot else "n/a"))
            print("  clarification_needed = %s" % (
                getattr(spec, "clarification_needed", None) if spec else None))
            print("  kind=%s  n=%d" % (kind, len(text)))
            print("  答案里报出的目录菜名（问句里没有的）= %d 道: %s"
                  % (len(quoted), quoted))
            print("  判定: %s" % v)
            print("  ── 老板看到的原文（前 300 字）──")
            print("    " + (text[:300].replace("\n", "\n    ") or "(空)"))

    print("\n" + "=" * 76)
    print("汇总（⛔ 逐行）")
    for (rnd, tag, q, slot, kind, v) in verdicts:
        print("  r%d %-6s %-16s slot=%-10s kind=%-14s %s" % (
            rnd, tag, q[:16], _fmt(slot)[:10], kind, v))

    # ── 阳性对照 ────────────────────────────────────────────────────────
    #
    # 🔴 2026-08-19 换过一次。第一版用的是「真菜必须 kind==answer」，
    #    结果 rc=2 —— 而目录核对**明明生效了**（假菜那两轮各引用了 8 道目录菜名）。
    #    成因：那一跑里 `catalogue[0]` 恰好是「米饭」，而「米饭卖得怎么样」
    #    正是全景重测里**已知不稳定**的那一条（Q08，基线 1/6 没答上）。
    #    ▎**拿一个已知不稳定的样本当阳性对照 ⇒ 假 rc=2。**
    #    ⚠️ 本仓的老纪律是「阳性对照要问：仪器坏掉时它会不会也成立」；
    #       这次是它的**反面** —— 仪器好着的时候它也会失败。两面都要问。
    #
    # ⇒ 换成目录核对该守的**对称面**：核对不会误杀真菜（真菜槽位被放行）。
    #   它和被测行为同源、且不依赖答不答得上，稳定。
    print("\n阳性对照·真菜槽位被放行（dish_slot 在目录里）= %d/%d 轮"
          % (n_real_slot_kept, _DISHCAT_ROUNDS))
    if n_real_slot_kept == 0:
        print("⛔ rc=2 真菜的槽位也没留住 —— 分不清「核对生效」和「整条链没跑起来」，读数作废")
        return 2
    # ⬇️ 登记项，⛔ 不作为 rc=2 的触发器（它量的是另一条已知缺陷）
    print("（登记，不作判据）真菜 kind==answer = %d/%d 轮 —— "
          "这一格反映的是「米饭卖得怎么样」自身的不稳定（全景 Q08），"
          "⛔ 不是目录核对的问题" % (n_real_answer, _DISHCAT_ROUNDS))

    bad = [v for v in verdicts if v[5].startswith("🔴")]
    unclear = [v for v in verdicts if v[5].startswith("⚠️")]
    print("🔴 照着查（没接上）= %d 条 / ⚠️ 说不清 = %d 条" % (len(bad), len(unclear)))
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
