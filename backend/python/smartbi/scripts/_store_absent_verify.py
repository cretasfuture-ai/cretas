# -*- coding: utf-8 -*-
"""部署后验证 · 点名一家不存在的门店，老板到底拿到什么。

## 本探针要回答的**三**件事（⛔ 不是一件）

1. **接上没有**（形态 B）—— 判据收窄成「只信模型提名的、且出自老板原话的名字」，
   代价登记在设计卡第 5b 节：**模型给 `store=null` 时这一刀够不着**。
   ⇒ 这里把 `parsed["store"]` / `parsed["stores"]` **打出来**。
   读不到那个假店名 ⇒ 这一刀在 prod 上没接上，⛔ 别报「修好了」。
2. **修好没有** —— 点名假店 ⇒ 反问，且正文里出现那家店的名字。
3. **按钮还活着没有**（承重 7）—— 半 B 最大的风险：动态文案会让延续轮认不出
   槽位，把按钮变成死的。⛔ 单测证明不了它（会话表是真库），必须真跑两轮。

   🔴 **判据订正（第一版是错的，而它是在修好之前写的）**：
   第一版写「第二轮点『全部门店』必须 `kind=answer`」，实测 **0/3**，
   我差点报「按钮死了」。读原文才看清 r1 第二轮拿到的是

       你想看哪个时间范围？请选择本月、上个月、最近7天或最近30天。

   ——**按钮是活的**：延续轮被正确识别、消费掉门店那一答、接着问下一个缺项。
   「时间 → 门店按钮 → 答案」本来就是**三轮**链，第二轮问时间是正常的。
   ▎旧期望是在系统还坏着的时候写的（那时的设想是「点全部门店 → 拿到全店数」），
   ▎而修好之后**正确行为恰恰是不给那个全店数**。
   ⇒ 判据换成 `is_clarification_continuation` —— 它直接回答
     「这一轮被当成延续了吗」，⛔ 不用 `kind` 去猜。
     两者的区别是承重的: 按钮死了会**重走 T3**（`is_continuation=False`），
     而那与「按钮活着但产品还要再问一格」在 `kind` 上长得一模一样。

## 阳性/阴性对照（⛔ 不许省）

- 阳性①：门店名单 ≥ 2 家
- 阳性②：`_t3_spy` 调用次数 > 0 —— 为 0 说明 monkeypatch 打空了（形态 B⁶），
  那么「`parsed['store']` 是空的」这个读数**没有意义**
- 阴性①：点名**真店** ⇒ 仍然直接作答（⛔ 不误伤）
- 阴性②：**没点名门店**（「哪家店最差」）⇒ 仍然补默认全部门店
  ⇒ ⛔ 没把「门店范围不再是必答题」改回必答题
"""
from __future__ import annotations

import asyncio
import os
import time

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_SV_FID = "MOCK_REST"
_SV_ROUNDS = 3
_SV_RUN = "%d-%d" % (os.getpid(), int(time.time()))
ctx = bootstrap_probe(_SV_FID)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

_SV_FAKE = "北京三里屯店"
_SV_NO_STORE_Q = "哪家店最差"

_SV_SEEN = {"calls": 0, "parsed": []}
_SV_REAL_T3 = ri._semantic_spec_from_t3


def _t3_spy(parsed, query, **kw):
    _SV_SEEN["calls"] += 1
    _SV_SEEN["parsed"].append({
        "query": query,
        "store": parsed.get("store"),
        "stores": parsed.get("stores"),
        "store_scope": parsed.get("store_scope"),
        "missing_fields": parsed.get("missing_fields"),
        "dimensions": parsed.get("dimensions"),
    })
    return _SV_REAL_T3(parsed, query, **kw)


ri._semantic_spec_from_t3 = _t3_spy


def _clear():
    for n in ("clear_semantic_plan_cache", "clear_route_cache",
              "clear_tenant_gate_cache", "clear_promoted_routes_cache"):
        getattr(ri, n)()


async def _ask(pool, q, skey):
    set_factory_id(_SV_FID)
    async with rr.dish_catalogue_scope(pool, _SV_FID):
        spec = await ri.parse_restaurant_query(
            q, pool, factory_id=_SV_FID, session_key=skey, semantic_first=True)
        res = None
        if spec is not None and svc.should_delegate(spec, None, query=q):
            res = await svc.tiered_answer(
                q, pool, _SV_FID, ctx.role, precomputed_spec=spec, session_key=skey)
    return spec, (res or {})


async def main() -> int:
    pool = await ctx.pool()
    print("PROBE_DB=%s  RUN=%s  轮数=%d" % (ctx.db_name, _SV_RUN, _SV_ROUNDS))
    if getattr(ctx, "llm_dead_slots", None):
        print("⛔ rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2

    set_factory_id(_SV_FID)
    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute("SELECT set_config('app.factory_id', $1, false)", _SV_FID)
            rows = await conn.fetch(
                "SELECT name FROM dim_store WHERE factory_id = $1 ORDER BY name", _SV_FID)
    names = tuple(r["name"] for r in rows)
    print("阳性对照①·门店名单 = %d 家" % len(names))
    if len(names) < 2 or _SV_FAKE in names:
        print("⛔ rc=2 名单不合用")
        return 2
    real = sorted(names)[0]

    cases = [
        ("②假店", "%s卖得怎么样" % _SV_FAKE),
        ("①真店", "%s卖得怎么样" % real),
        ("③没点名", _SV_NO_STORE_Q),
    ]
    rows_out = []
    for rnd in range(1, _SV_ROUNDS + 1):
        for tag, q in cases:
            _clear()
            skey = "sv-%s-r%d-%s" % (_SV_RUN, rnd, tag)
            n0 = len(_SV_SEEN["parsed"])
            spec, res = await _ask(pool, q, skey)
            seen = _SV_SEEN["parsed"][n0:]
            kind = res.get("kind") or "-"
            text = res.get("answer_text") or ""
            cq = getattr(spec, "clarification_question", None) if spec else None
            opts = tuple(getattr(spec, "clarification_options", ()) or ()) if spec else ()

            # 第二轮：点第一个按钮（承重 7 —— 按钮不能变成死的）
            second_kind = second_note = second_cont = None
            if tag == "②假店" and kind == "clarification" and opts:
                _clear()
                spec2, res2 = await _ask(pool, opts[0], skey)
                second_kind = (res2 or {}).get("kind")
                # 🔑 承重判据：这一轮**被当成延续了吗**。⛔ 不用 kind 去猜 ——
                #    「按钮死了重走 T3」与「按钮活着但还要再问一格」kind 相同。
                second_cont = getattr(spec2, "is_clarification_continuation", None)
                second_note = (
                    ((res2 or {}).get("answer_text") or "")
                    or (getattr(spec2, "clarification_question", "") or "")
                )[:140]

            rows_out.append((rnd, tag, kind, second_cont,
                             getattr(spec, "store_scope", None) if spec else None,
                             getattr(spec, "store_scope_defaulted", None) if spec else None,
                             _SV_FAKE in (cq or ""), seen))
            print("\n" + "=" * 76)
            print("r%d 【%s】%s" % (rnd, tag, q))
            for s in seen:
                print("   🔑 模型给的: store=%r  stores=%r  store_scope=%r" % (
                    s["store"], s["stores"], s["store_scope"]))
                print("      missing=%r  dims=%r" % (s["missing_fields"], s["dimensions"]))
            print("   kind=%s  store_scope=%r  defaulted=%s  missing_slot=%r" % (
                kind, getattr(spec, "store_scope", None) if spec else None,
                getattr(spec, "store_scope_defaulted", None) if spec else None,
                getattr(spec, "missing_slot", None) if spec else None))
            print("   按钮 = %s" % list(opts)[:4])
            if kind == "clarification":
                print("   ── 老板看到的反问（原文）──")
                print("   " + (cq or "(空)").replace("\n", "\n   "))
            else:
                print("   ── 老板看到的答案（前 200 字）──")
                print("   " + text[:200].replace("\n", "\n   "))
            if second_kind is not None:
                print("   ▸ 第二轮点「%s」⇒ kind=%s  被当成延续=%s"
                      % (opts[0], second_kind, second_cont))
                print("     " + (second_note or "").replace("\n", " "))

    print("\n" + "=" * 76)
    print("阳性对照②·_t3_spy 被调用 = %d 次" % _SV_SEEN["calls"])
    if not _SV_SEEN["calls"]:
        print("⛔ rc=2 monkeypatch 打空了（形态 B⁶）—— parsed 的读数没有意义")
        return 2

    fake = [r for r in rows_out if r[1] == "②假店"]
    realr = [r for r in rows_out if r[1] == "①真店"]
    nostore = [r for r in rows_out if r[1] == "③没点名"]

    # ① 接上没有：模型有没有把那个假店名放进 parsed
    got_name = sum(1 for r in fake for s in r[7] if s["store"] == _SV_FAKE)
    total_parsed = sum(len(r[7]) for r in fake)
    print("\n🔑 ① 接上没有（形态 B）")
    print("   ②假店里 parsed['store'] == %r 的次数 = %d / %d 次编译"
          % (_SV_FAKE, got_name, total_parsed))
    if total_parsed and not got_name:
        print("   ⛔ 模型没把店名放进 parsed ⇒ 收窄后的判据在 prod 上**够不着**")
        print("      —— 这一刀没接上，⛔ 别报「修好了」。逐行看上面的 store=…")

    print("\n🔑 ② 修好没有")
    n_clar = sum(1 for r in fake if r[2] == "clarification")
    n_named = sum(1 for r in fake if r[6])
    n_ans = sum(1 for r in fake if r[2] == "answer")
    print("   反问 = %d/%d   反问里说了是哪家店 = %d/%d   仍直接作答 = %d/%d"
          % (n_clar, len(fake), n_named, len(fake), n_ans, len(fake)))

    print("\n🔑 ③ 按钮还活着没有（承重 7）")
    seconds = [r[3] for r in fake if r[3] is not None]
    n_ok2 = sum(1 for c in seconds if c is True)
    print("   第二轮**被当成延续轮** = %d/%d" % (n_ok2, len(seconds)))
    print("   ⚠️ 判据是 `is_clarification_continuation`，⛔ 不是 `kind==answer`：")
    print("      「时间→门店按钮→答案」本来就是三轮链，第二轮问时间是正常的。")
    if seconds and n_ok2 < len(seconds):
        print("   ⛔ 有轮次没被当成延续 —— 按钮死了，问句被重走 T3（半 B 的风险面）")

    print("\n阴性对照①·真店仍直接作答 = %d/%d"
          % (sum(1 for r in realr if r[2] == "answer"), len(realr)))
    print("阴性对照②·没点名门店仍补默认 all = %d/%d"
          % (sum(1 for r in nostore if r[4] == "all"), len(nostore)))

    ok = (n_clar == len(fake) and n_named == len(fake)
          and (not seconds or n_ok2 == len(seconds))
          and sum(1 for r in realr if r[2] == "answer") == len(realr)
          and sum(1 for r in nostore if r[4] == "all") == len(nostore))
    print("\n⇒ %s" % ("✅ 三件事都成立" if ok else "🔴 逐行看上面，⛔ 别下单一结论"))
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
