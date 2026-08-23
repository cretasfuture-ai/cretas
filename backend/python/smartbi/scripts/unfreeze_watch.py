# -*- coding: utf-8 -*-
"""挂账项状态守望 —— 每天量一次，**状态翻面就喊**。

## 为什么有它

设计卡里每条「⛔ 本轮不做」都配了解冻条件。2026-08-19 盘点：
**6 条里 6 条没有触发通道** —— 判据都写好了，但**没有一个会自己跑**。

▎按「解冻条件必须有触发通道，否则等于不存在」：
▎那 6 条当时**等于不存在**。再写多少张卡也一样。

⇒ 本模块就是那个通道。⛔ 它不阻断任何东西，产出是台账 + 告警行，给人看。

## 形状（照 `margin_parity_probe` / `replay-equivalence-daily`，⛔ 不自己发明）

每条挂账项登记四样：**哪张卡 / 什么时候该重开 / 登记当天量到的状态 / 怎么量**。
每天量一次，与登记状态比：

    一致   ⇒ 保持挂起（正常）
    翻面   ⇒ 🔴 告警：那张卡该重新看了
    没量到 ⇒ ⚠️ 单独告警（rc=2），⛔ **不折叠进「没问题」**

## 三态退出码（硬约束 4）

    rc=0  所有登记项状态未变
    rc=1  有项翻面 —— 读数有效且指向「该重开某张卡」
    rc=2  **这次没量到** —— 阳性对照没过 / 样本 0 / 仪器起不来

⛔ 两态跑批会把「没量到」折叠进「没问题」，然后安静地天天绿。
   本仓有先例：52 断言的回归电池随租户收敛一起死了 4 天。

## 台账（硬约束 9）

**每次必往 `UNFREEZE_LEDGER` 追加一行**，无论结果如何。
▎那是让「今天没告警」变得**可判定**的那个「必发生」读数 ——
▎没有它，「崩了」和「没事」在告警文件上长得一模一样。
"""
from __future__ import annotations

import asyncio
import json
import os
import time
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, Optional, Tuple

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_UW_FID = os.environ.get("UNFREEZE_FACTORY", "MOCK_REST")
_UW_ROUNDS = int(os.environ.get("UNFREEZE_ROUNDS", "2"))
_UW_LEDGER = os.environ.get(
    "UNFREEZE_LEDGER", "/www/wwwroot/cretas/logs/unfreeze-watch-ledger.jsonl")
#: ⛔ 主动构造 rc=2 用（硬约束 4：第三态平时永远不会自然发生，不构造就不知道它到不到得了）。
_UW_FORCE_DEAD = os.environ.get("UNFREEZE_FORCE_INSTRUMENT_DEAD") == "1"
#: 连续这么多天「没有样本」⇒ 升级成翻面：那条判据实际上从不生效，该重新设计。
_UW_NO_SAMPLE_ESCALATE = int(os.environ.get("UNFREEZE_NO_SAMPLE_DAYS", "7"))


def _no_sample_streak(item_id: str) -> int:
    """台账里这条挂账项**连续**没有样本的次数（含本次之前的历史）。

    ⛔ 只数**连续**的：中间只要有一次量到了，之前的就不算 ——
       否则一个偶尔量不到的项会慢慢累加到阈值，喊出一个假警报。
    """
    try:
        with open(_UW_LEDGER, "r", encoding="utf-8", newline="") as fh:
            lines = fh.readlines()
    except Exception:
        return 0
    streak = 0
    for raw in reversed(lines):
        try:
            rec = json.loads(raw)
        except Exception:
            continue
        # ⛔ 跳过人为构造 rc=2 的那些行：它们是验证第三态可达时写的，
        #    既不算「量到了」也不算「没样本」。不跳过的话，构造一次就会把
        #    真实的连续计数清零 —— 一个**验证动作**不该改变被验证的读数。
        if rec.get("forced"):
            continue
        got = (rec.get("items") or {}).get(item_id)
        if not isinstance(got, dict):
            continue
        if got.get("no_sample"):
            streak += 1
        else:
            break
    return streak

ctx = bootstrap_probe(_UW_FID)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

_UW_FAKE_STORE = "北京三里屯店"
_UW_NEARMISS_DISH = "水煮牛肉丝"
_UW_RUN = "%d-%d" % (os.getpid(), int(time.time()))


@dataclass
class Item:
    """一条挂账项。

    `expected` 是**登记当天实测**到的状态字符串。⛔ 不写「应该是什么」——
    写「当时是什么」。状态与它不一致就喊，方向不限（变好也要知道）。
    """
    id: str
    card: str
    reopen_when: str
    expected: str
    measure: Callable[..., Any]
    needs_llm: bool = False
    #: 判不了的登记在 `_UW_NO_INSTRUMENT`，⛔ 不放进这里假装能量。
    note: str = ""


@dataclass
class Reading:
    """一次读数。

    🔴 **「没量到」有两种，它们的处置完全不同**（第一次真跑就撞上了）：

        仪器坏     连不上库 / 阳性对照失败 / 判据抛异常
                   ⇒ rc=2，**当天就喊**（硬约束 4）
        没有样本   仪器好好的，只是**这一轮没出现可测的输入**
                   （实测：`store-absent` 那条要「模型提名了那家店」，
                     而模型两轮全给 `store=null`）
                   ⇒ 记台账、**不当天喊**；连续 `_UW_NO_SAMPLE_ESCALATE` 天
                     还是没样本 ⇒ rc=1 翻面：**这条判据实际上从不生效，
                     该重新设计**（那才是真问题）

    ⚠️ 这**不是**把「没能做决定」收掉（形态 E′ 警告的那件事）——
       仪器坏那一态一天都不宽限。收窄的对象是「哪一类不该当天喊」，
       ⛔ 不是「哪些情况我不再看」：没样本照样逐天记台账，还会自己升级。
    ⛔ 反过来也不许把「仪器坏」标成「没样本」来图清静 —— 那就是把崩溃变成静默。
    """
    state: str
    positive_ok: bool
    evidence: Dict[str, Any] = field(default_factory=dict)
    #: 阳性对照没过时的原因，⛔ 不留空
    dead_reason: str = ""
    #: True = 仪器好的，只是这一轮没有可测的输入
    no_sample: bool = False


def _clear():
    for n in ("clear_semantic_plan_cache", "clear_route_cache",
              "clear_tenant_gate_cache", "clear_promoted_routes_cache"):
        getattr(ri, n)()


async def _ask(pool, q, skey):
    set_factory_id(_UW_FID)
    async with rr.dish_catalogue_scope(pool, _UW_FID):
        spec = await ri.parse_restaurant_query(
            q, pool, factory_id=_UW_FID, session_key=skey, semantic_first=True)
        res = None
        if spec is not None and svc.should_delegate(spec, None, query=q):
            res = await svc.tiered_answer(
                q, pool, _UW_FID, ctx.role, precomputed_spec=spec, session_key=skey)
    return spec, (res or {})


# ══ U1 · 门店提及判据的误伤面（纯 DB，便宜）═══════════════════════════
async def _m_store_harm_sample(pool) -> Reading:
    """P1（「X 的那家店」是描述不是名字）当天没上，理由是**误伤面太薄**。

    实测：`dim_store` 里只有 1 个租户有门店、10 个店名，且全带「模拟·」前缀，
    而这条判据的先例（`_INTERROGATIVE_MARKERS` 换代）量的是 198 个跨 4 租户。
    ⇒ 误伤面够得着 100+ 跨租户真店名时，P1 可以重开。
    """
    async with pool.acquire() as conn:
        rows = await conn.fetch(
            "SELECT factory_id, count(*) AS n FROM dim_store"
            " GROUP BY factory_id ORDER BY n DESC")
    tenants = len(rows)
    names = sum(r["n"] for r in rows)
    if names == 0:
        return Reading("?", False, {"tenants": 0, "names": 0},
                       "dim_store 一行都读不到 —— 连库/RLS 的问题，不是「真的没有」")
    enough = tenants >= 2 and names >= 100
    return Reading(
        "够得着" if enough else "不足",
        True,
        {"tenants": tenants, "names": names,
         "per_tenant": {r["factory_id"]: r["n"] for r in rows}},
    )


# ══ U2 · 模型提名了那家店时，还会不会静默顶替 ════════════════════════
async def _m_store_absent_substitution(pool) -> Reading:
    """2026-08-19 那一刀只在**模型提名了那家店**时生效。

    ⇒ 守的是「在它生效的那一格上，它还生效吗」。
    ⚠️ 模型全给 `store=null` 的那些轮**不算数** —— 那是已知的、登记过的另一半。
       全部轮次都是 null ⇒ **这次没量到**（rc=2），⛔ 不报「没问题」。
    """
    seen = []
    real_t3 = ri._semantic_spec_from_t3

    def spy(parsed, query, **kw):
        seen.append(parsed.get("store"))
        return real_t3(parsed, query, **kw)

    ri._semantic_spec_from_t3 = spy
    try:
        rounds = []
        for i in range(_UW_ROUNDS):
            _clear()
            n0 = len(seen)
            spec, res = await _ask(
                pool, "%s卖得怎么样" % _UW_FAKE_STORE,
                "uw-%s-store-%d" % (_UW_RUN, i))
            named = _UW_FAKE_STORE in seen[n0:]
            rounds.append({
                "model_named": named,
                "kind": res.get("kind"),
                "missing_slot": getattr(spec, "missing_slot", None) if spec else None,
                "compiled": len(seen) > n0,
            })
    finally:
        ri._semantic_spec_from_t3 = real_t3

    compiled = [r for r in rounds if r["compiled"]]
    if not compiled:
        return Reading("?", False, {"rounds": rounds},
                       "一轮都没编译出计划 —— 仪器起不来，不是产品行为")
    named_rounds = [r for r in compiled if r["model_named"]]
    if not named_rounds:
        # ⚠️ 仪器是好的（计划编译出来了），只是这一轮**没有可测的输入**。
        #    ⛔ 不当成「还好着」，也⛔ 不当天当仪器坏喊 —— 见 `Reading` 的说明。
        return Reading(
            "?", False,
            {"rounds": rounds, "compiled": len(compiled), "named": 0},
            "模型这几轮全给 store=null —— 这一刀生效的那一格**没被触到**，"
            "本次读数不能当成「还好着」",
            no_sample=True)
    bad = [r for r in named_rounds if r["kind"] != "clarification"]
    return Reading(
        "已修" if not bad else "顶替回来了",
        True,
        {"named_rounds": len(named_rounds), "still_substituting": len(bad),
         "rounds": rounds},
    )


# ══ U3 · 近似菜名够不着目录反馈（纯函数，便宜）═════════════════════
async def _m_dish_nearmiss(pool) -> Reading:
    """近似菜名（「水煮牛肉丝」）会不会被判成「另一道菜」。

    🔴 2026-08-19 **当天就修掉了**，所以登记状态从「仍放行」翻成「已否决」——
       这次翻面是**我自己造成的**，⇒ 同一次改动里把登记状态一起改掉，
       ⛔ 不留一个明天必响的假警报。
    ⇒ 从此它守的是**回归**：又变回「仍放行」⇒ 这一刀被别的改动顶掉了。

    ⚠️ 判据用的是产品侧那个**组合**（`not_a_dish` ∨ `a_longer_name`），
       ⛔ 不只问其中一条 —— 只问一条就会在另一条被删掉时静默通过（形态 D）。
    """
    set_factory_id(_UW_FID)
    async with rr.dish_catalogue_scope(pool, _UW_FID):
        cat = tuple(rr.current_dish_catalogue() or ())
        if len(cat) < 4:
            return Reading("?", False, {"catalogue": len(cat)},
                           "菜单目录不足 4 条 —— 判据本身跑不动")
        # 阳性对照：一个明显不是菜的词必须被否决，否则这把尺子是坏的
        control = rr._catalogue_says_not_a_dish("佛跳墙")
        nearmiss = (rr._catalogue_says_not_a_dish(_UW_NEARMISS_DISH)
                    or rr._catalogue_says_a_longer_name(_UW_NEARMISS_DISH))
    if not control:
        return Reading("?", False, {"control_佛跳墙": control},
                       "阳性对照失败：连明显不是菜的词都没被否决，判据坏了")
    return Reading(
        "已否决" if nearmiss else "仍放行",
        True,
        {"nearmiss": _UW_NEARMISS_DISH, "says_not_a_dish": nearmiss,
         "in_catalogue": _UW_NEARMISS_DISH in cat, "catalogue": len(cat)},
    )


# ══ U4 · Q08：点了时间按钮之后到底拿不拿得到答案 ═════════════════════
async def _m_q08_second_turn(pool) -> Reading:
    """「米饭卖得怎么样」这一句，老板**第一轮**拿到的是什么。

    🔴 2026-08-19 晚：**这条登记项的语义变了。**
       原来守的是「点一下按钮就能拿到答案 —— 多一步，不是白问」，
       那是 Q08 裁定「⛔ 不修」的全部理由。
       当天做了二次编译之后，实测**第一轮直接 `answer` 3/3**：

           「米饭」最近30天（2026-07-21 至 2026-08-19）销量 42,337 份、
           营收 ¥127,011.00、覆盖订单 17774 单。

       ⇒ 那一步**不再需要**。登记状态改成「第一轮直接答上」，
         从此它守的是**回归**：变回反问 ⇒ 二次编译被顶掉了。

    ⚠️ 顺带留痕：原探针的阳性对照是「第一轮必须是 clarification」——
       修好之后它当场 `rc=2`。▎**修好仪器/产品之后要重想一遍期望**，
       旧期望是在它还坏着的时候写的（今天第二次踩）。
    """
    _clear()
    skey = "uw-%s-q08" % _UW_RUN
    spec, res = await _ask(pool, "米饭卖得怎么样", skey)
    kind = res.get("kind")
    if kind == "answer":
        return Reading(
            "第一轮直接答上", True,
            {"window": getattr(spec, "window_label", None) if spec else None,
             "intent": getattr(spec, "intent", None) if spec else None,
             "authority": getattr(spec, "planner_authority", None) if spec else None,
             "head": (res.get("answer_text") or "")[:120]},
        )
    opts = tuple(getattr(spec, "clarification_options", ()) or ()) if spec else ()
    if kind != "clarification" or not opts:
        return Reading("?", False, {"first_kind": kind, "options": list(opts)},
                       "第一轮既不是答案也不是带按钮的反问 —— 量不到这条")
    # 退回反问了 ⇒ 仍然把「点一下拿不拿得到」量出来，供判断严重程度
    _clear()
    spec2, res2 = await _ask(pool, opts[0], skey)
    return Reading(
        "退回反问·点按钮能拿到" if res2.get("kind") == "answer" else "退回反问·点了也答不上",
        True,
        {"button": opts[0], "second_kind": res2.get("kind"),
         "window": getattr(spec2, "window_label", None) if spec2 else None},
    )


async def _m_margin_asked_revenue_given(pool) -> Reading:
    """问**毛利**，拿到的是**营收 / 单量** —— 指标不支持，⛔ 不是维度不支持。

    2026-08-19 实测（`_dimension_not_served` 同一轮顺带量到）:
    「最近30天外卖和堂食的毛利分别是多少」→ `CHANNEL_MIX`（支持 channel，
    所以维度那道判据**判不到它**），而答案是营收/单量表。

    🔴 **实际形状比登记的更糟**（`_margin_by_channel_full` 贴全文才看清）:
       全文 1031 字，「毛利」只出现 **1 次**、在第 942 字，那一行是

           往下可以拆开看：堂食和外卖各自的每单平均消费、**毛利分别是多少**。

       ▎**产品把老板刚问的那个问题，当成「下一步可以问什么」推荐回给了他。**

    ⛔ 判据订正（守望第一次跑就翻面，而**翻的是判据不是行为**）:
       第一版写 `"毛利" in text` —— 子串判据，命中了上面那句**建议**。
       ⇒ 换成**结构**判据: 表头里有「毛利」，或者「毛利」近旁真有一个数。
       ▎问「给没给毛利」要看**有没有毛利的数**，⛔ 不是「正文提没提这两个字」。
    """
    _clear()
    set_factory_id(_UW_FID)
    q = "最近30天外卖和堂食的毛利分别是多少"
    spec, res = await _ask(pool, q, "uw-%s-margin" % _UW_RUN)
    text = (res or {}).get("answer_text") or ""
    if (res or {}).get("kind") != "answer" or not text:
        return Reading("?", False, {"kind": (res or {}).get("kind")},
                       "这一轮没出答案 —— 量不到「答的是哪个指标」")
    # 阳性对照：答案里必须有渠道词，否则它根本不是这条路的产出
    if not any(w in text for w in ("外卖", "堂食")):
        return Reading("?", False, {"head": text[:120]},
                       "答案里没有渠道词 —— 走的不是这条路，本次读数无意义")
    import re as _re

    header_has = any(
        "毛利" in ln for ln in text.split("\n")
        if ln.strip().startswith("|") and "---" not in ln)
    # 「毛利」后面 24 字内出现货币/百分比/千分位数字 ⇒ 算真的给了数。
    near_number = bool(_re.search(r"毛利[^\n]{0,24}?(¥|\d[\d,]*\.?\d*\s*%|\d{3,})", text))
    gave = header_has or near_number
    # 🔴 2026-08-19：**两件事分开守**，⛔ 不折叠成一个 bool。
    #    「给没给毛利」是能力（这条路确实给不了）；
    #    「说没说」是我这一刀做的（`_metric_gap_note`）。
    #    只守前者的话，我加的那句说明被删掉也不会翻面。
    told = "给不了" in text and "毛利" in text[:120]
    if gave:
        state = "给了毛利"
    else:
        state = "没给毛利·已说明" if told else "没给毛利·没说明"
    return Reading(
        state, True,
        {"intent": getattr(spec, "intent", None) if spec else None,
         "header_has_margin": header_has, "margin_near_number": near_number,
         "told_it_cannot": told,
         "margin_word_count": text.count("毛利"),
         "margin_first_at": text.find("毛利"), "length": len(text),
         "head": text[:160]},
    )


async def _m_grounding_gate_fallback(pool) -> Reading:
    """接地闸兜底话术：讲产品**内部流程**，出路老板往往已经做过了。

    2026-08-19 实测「最近30天哪个时段毛利最高」拿到的就是这句 ——
    与同日 dish 那一刀修掉的**是同一句话**，说明它的面比预想的宽。
    """
    _clear()
    set_factory_id(_UW_FID)
    q = "最近30天哪个时段毛利最高"
    spec, res = await _ask(pool, q, "uw-%s-ground" % _UW_RUN)
    body = ((res or {}).get("answer_text") or "") or (
        getattr(spec, "clarification_question", None) or "")
    if not body:
        return Reading("?", False, {"kind": (res or {}).get("kind")},
                       "这一轮既没答案也没反问 —— 量不到")
    hit = "不把推测说成结论" in body
    return Reading(
        "仍是接地闸兜底" if hit else "换成了别的",
        True,
        {"kind": (res or {}).get("kind"), "head": body[:160]},
    )


async def _m_rejected_with_kpis_in_hand(pool) -> Reading:
    """产出被接地闸驳回时，`kpis` 手边有几个 —— 而老板一个数都没拿到。

    2026-08-19 实测（`_rejected_but_kpis_in_hand`）: 撞上兜底的 3 条
    **手边都有 kpis**（4 / 3 / 2 个），`unsupported_requirements` 全空。
    ⇒ `if gap_labels:` 那条纪律（「说不出理由的数字贴在拒答旁边就是顶替」）
      的代价是**老板一个数都拿不到，而数就在手边**。

    ⛔ 那条纪律是 owner 写的，本轮**不改** —— 这里只把代价挂在明面上，
       让它每天自己报一次，等 owner 裁定。
    """
    from smartbi.gold.restaurant import restaurant_intent_service as _svc

    seen = []
    real = _svc.missing_capability_labels

    def spy(unsupported):
        import sys as _sys
        caller = _sys._getframe(1).f_locals
        tr = caller.get("tiered_result")
        kpis = getattr(tr, "kpis", None)
        seen.append(len(kpis) if isinstance(kpis, list) else 0)
        return real(unsupported)

    _svc.missing_capability_labels = spy
    try:
        _clear()
        set_factory_id(_UW_FID)
        _spec, res = await _ask(pool, "最近30天营收为什么下滑",
                                "uw-%s-rej" % _UW_RUN)
    finally:
        _svc.missing_capability_labels = real

    body = (res or {}).get("answer_text") or ""
    if "不把推测说成结论" not in body:
        # ⚠️ 这一轮没撞上兜底 ⇒ 没有样本，⛔ 不是「没问题」
        return Reading("?", False, {"head": body[:120]},
                       "这一轮没撞上接地闸兜底 —— 这一格没被触到",
                       no_sample=True)
    if not seen:
        return Reading("?", False, {}, "钩子没抓到拒答现场（monkeypatch 打空）")
    # 🔴 2026-08-19 **修好之后重想一遍期望**（本仓已记两次的那条）。
    #
    #    原来这里只问「手边有几个数」，而那个数**修完之后照样非零** ——
    #    数一直都在手边，改的是「给不给他」。
    #    ▎照旧读的话，这道闸从明天起每天报一次「手边有数却不给」，
    #    ▎而它已经给了 —— 一道天天误报的闸，结局是被关掉（形态 E）。
    #
    # ⇒ 判据换成**两个读数并排**：手边有几个 / 老板拿到几个。
    #    ⛔ 单看任何一个都判不了：手边非零是常态，老板拿到零也可能是
    #      「手边本来就没有」。真正的缺陷是**手边有、他没拿到**。
    shipped = (res or {}).get("kpis")
    n_shipped = len(shipped) if isinstance(shipped, list) else 0
    n_hand = max(seen)
    return Reading(
        "手边有数却不给" if (n_hand > 0 and n_shipped == 0) else "手边有数已给他",
        True, {"kpis_at_refusal": seen, "kpis_shipped": n_shipped},
    )


_UW_ITEMS = (
    Item("store-mention-harm-sample",
         "2026-08-19-点名一家不存在的门店-静默顶替-设计卡.md",
         "误伤面够得着 100+ 跨租户真店名 ⇒ P1（结构判据）可以重开",
         "不足", _m_store_harm_sample),
    Item("store-absent-substitution",
         "2026-08-19-点名一家不存在的门店-静默顶替-设计卡.md",
         "模型提名了那家店却仍直接作答 ⇒ 这一刀被别的改动顶掉了",
         "已修", _m_store_absent_substitution, needs_llm=True),
    Item("dish-nearmiss-substring",
         "2026-08-19-菜名比菜单里的长-设计卡.md",
         "近似菜名又被放行了 ⇒ 这一刀被别的改动顶掉了",
         "已否决", _m_dish_nearmiss),
    Item("margin-asked-revenue-given",
         "2026-08-19-问毛利拿到营收表-设计卡.md",
         "变成「给了毛利」⇒ 能力补上了，可关登记；"
         "变回「没给毛利·没说明」⇒ 我加的那句说明被删掉了",
         "没给毛利·已说明", _m_margin_asked_revenue_given, needs_llm=True),
    Item("grounding-gate-fallback",
         "2026-08-19-维度全被剥掉时两边都以为对方会说-设计卡.md",
         "不再是那句讲内部流程的兜底话 ⇒ 接地闸话术改好了，可以关掉登记",
         "仍是接地闸兜底", _m_grounding_gate_fallback, needs_llm=True),
    Item("rejected-with-kpis-in-hand",
         "2026-08-19-三条能力缺口的读数与裁定-设计卡.md",
         # 🔴 2026-08-19 傍晚 **这一格已经做掉了**（brief 任务 3）：
         #    拒答手边有数时一起发给他。prod 实测
         #    （`_numbers_only_in_prose`，MOCK_REST，2 轮）
         #    「手边有数却不给」**4/12 → 0/12**。
         # ⇒ 期望翻面：现在守的是**它别退回去**，⛔ 不再是「等 owner 裁定」。
         "退回「手边有数却不给」⇒ 这一刀被别的改动顶掉了",
         "手边有数已给他", _m_rejected_with_kpis_in_hand, needs_llm=True),
    Item("q08-first-turn-answers",
         "2026-08-19-Q08-repair清了clarify却错过补默认窗口-设计卡.md",
         "退回反问 ⇒ 二次编译被别的改动顶掉了",
         "第一轮直接答上", _m_q08_second_turn, needs_llm=True),
)

#: ⛔ **机器判不了的，显式列出来**，不折叠进「没问题」，也不假装有判据。
_UW_NO_INSTRUMENT = (
    # ✅ `store-absent-P2` 2026-08-19 **当天做完**，已从这里撤掉。
    #    ⛔ 登记表只留**还没做**的 —— 留着做完的会让这份清单慢慢失去意义
    #    （每天打印一堆已完成项，人就不再逐行读它了）。
    #    做完的痕迹在设计卡里，⛔ 不在这。
    ("q08-recipe-cost-in-real-traffic",
     "2026-08-19-Q08-repair清了clarify却错过补默认窗口-设计卡.md",
     "RECIPE_COST 命中率在**真实流量**里超过 1/3。"
     "⚠️ 当前没有真人（问答 18,398 : 餐饮反馈 5，唯一率 3.7%）⇒ 量不到"),
    ("q08-owner-ruling",
     "2026-08-19-Q08-repair清了clarify却错过补默认窗口-设计卡.md",
     "owner 重新裁定「有默认值就压掉澄清」。**人的决定，机器判不了**"),
)


async def main() -> int:
    pool = await ctx.pool()
    print("UNFREEZE WATCH  DB=%s  FID=%s  轮数=%d  RUN=%s"
          % (ctx.db_name, _UW_FID, _UW_ROUNDS, _UW_RUN))
    if _UW_FORCE_DEAD:
        print("⚠️ UNFREEZE_FORCE_INSTRUMENT_DEAD=1 —— 主动构造 rc=2，"
              "验证第三态到得了（硬约束 4）")

    llm_dead = list(getattr(ctx, "llm_dead_slots", None) or ())
    results = []
    for item in _UW_ITEMS:
        if _UW_FORCE_DEAD:
            r = Reading("?", False, {}, "人为构造：验证 rc=2 这一态可达")
        elif item.needs_llm and llm_dead:
            r = Reading("?", False, {"llm_dead_slots": llm_dead},
                        "LLM 槽没有活账号 —— 需要 LLM 的项这次量不到")
        else:
            try:
                r = await item.measure(pool)
            except Exception as exc:      # ⛔ 不 continue：失败要计数并贴出来
                r = Reading("?", False, {"exception": repr(exc)[:300]},
                            "判据本身抛异常")
        results.append((item, r))

    print("\n" + "=" * 78)
    print("%-30s %-20s %-20s %s" % ("挂账项", "登记状态", "今天量到", "判定"))
    n_flip = n_dead = n_nosample = 0
    streaks = {}
    for item, r in results:
        if r.no_sample:
            # ⛔ 连续没样本要**自己升级**：不然一条永远测不到的判据会安静地
            #    天天「没样本」，而那和「这条判据从不生效」是同一件事。
            streaks[item.id] = _no_sample_streak(item.id) + 1
            n_nosample += 1
            if streaks[item.id] >= _UW_NO_SAMPLE_ESCALATE:
                verdict = ("🔴 连续 %d 天没样本 —— 这条判据实际上从不生效，该重新设计"
                           % streaks[item.id])
                n_flip += 1
            else:
                verdict = "· 这轮没样本（%d/%d 天，仪器是好的）" % (
                    streaks[item.id], _UW_NO_SAMPLE_ESCALATE)
        elif not r.positive_ok:
            verdict = "⚠️ 仪器坏 —— 本次那条读数作废"
            n_dead += 1
        elif r.state != item.expected:
            verdict = "🔴 翻面 —— 那张卡该重新看了"
            n_flip += 1
        else:
            verdict = "✅ 未变（保持挂起）"
        print("%-30s %-20s %-20s %s" % (item.id, item.expected, r.state, verdict))

    print("\n逐条（⛔ 不折叠）")
    for item, r in results:
        print("\n▸ %s" % item.id)
        print("   卡: %s" % item.card)
        print("   什么时候该重开: %s" % item.reopen_when)
        print("   登记状态=%r  今天=%r" % (item.expected, r.state))
        if r.no_sample:
            print("   · 这轮没样本（连续 %d 天，阈值 %d）: %s"
                  % (streaks.get(item.id, 1), _UW_NO_SAMPLE_ESCALATE, r.dead_reason))
        elif not r.positive_ok:
            print("   ⚠️ 仪器坏: %s" % r.dead_reason)
        print("   证据: %s" % json.dumps(r.evidence, ensure_ascii=False)[:400])

    print("\n⚠️ 机器判不了的（⛔ 显式列出，不折叠进「没问题」）")
    for iid, card, why in _UW_NO_INSTRUMENT:
        print("   · %-32s %s" % (iid, why))
        print("     卡: %s" % card)

    # ⛔ 仪器坏优先于翻面：读数作废时「翻没翻面」这个问题本身没有意义。
    rc = 2 if n_dead else (1 if n_flip else 0)

    # ── 台账：**每次必追加一行**（硬约束 9 的「必发生」读数）──────────
    line = {
        "ts": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "run": _UW_RUN, "db": ctx.db_name, "factory": _UW_FID,
        "rounds": _UW_ROUNDS, "rc": rc, "flipped": n_flip,
        "forced": _UW_FORCE_DEAD,
        "instrument_dead": n_dead, "no_sample": n_nosample,
        "items": {i.id: {"expected": i.expected, "today": r.state,
                         "ok": r.positive_ok,
                         "no_sample": r.no_sample,
                         "no_sample_streak": streaks.get(i.id, 0),
                         "dead_reason": r.dead_reason,
                         "evidence": r.evidence}
                  for i, r in results},
        "no_instrument": [i for i, _c, _w in _UW_NO_INSTRUMENT],
    }
    try:
        os.makedirs(os.path.dirname(_UW_LEDGER) or ".", exist_ok=True)
        with open(_UW_LEDGER, "a", encoding="utf-8", newline="") as fh:
            fh.write(json.dumps(line, ensure_ascii=False) + "\n")
        print("\n台账已追加: %s" % _UW_LEDGER)
    except Exception as exc:
        # ⛔ 台账写不进去要说出来 —— 它是「没告警」可判定的那一半
        print("\n⛔ 台账写入失败（「今天没告警」将无法判定）: %r" % exc)

    print("\n🔑 rc=%d  （0=都没变 / 1=有翻面 / 2=仪器坏，本次读数作废）" % rc)
    print("   翻面 %d 项 · 仪器坏 %d 项 · 这轮没样本 %d 项（没样本⛔不当天喊，"
          "连续 %d 天升级成翻面）" % (n_flip, n_dead, n_nosample, _UW_NO_SAMPLE_ESCALATE))
    return rc


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
