# -*- coding: utf-8 -*-
"""⛔ 别建议老板去做他**已经做过**的事。

## 同一个毛病的第三次

产出被闸驳回时那句兜底建议：

    你可以把问题问得更小一点（限定到某家店、某道菜或某个时间段），
    那条路我能给你确定的数。

它**已经因为「假建议」改过一次**（2026-08-17）：原版硬编码
「例如把**为什么**换成…」，实测发给了根本没问「为什么」的老板。
当时的修法是**换成不假设问句形状**的笼统说法。

🔴 **而那正是现在失败的原因**（prod `_grounding_fallback_face`，
8 条问句里 2 条撞上这条兜底，**2/2 都混着他已经做过的事**）：

    「最近30天哪个时段毛利最高」            已限定【时间段】
    「模拟·徐汇美罗城店最近30天为什么毛利低」  已限定【门店 + 时间段】

▎建议他做的事，他已经做了。⇒ ⛔ 不能再换一句更笼统的（那会第三次回来）。
▎方向沿用本文件既有纪律：**建议必须由手边的事实算出来，⛔ 不写死。**

## ⚠️ 「限定了」的判据要减掉**我们替他补的默认**

时间窗常是产品补默认来的（`time_range_defaulted`）。那不算他限定的 ——
建议他去限定反而是对的。⇒ 判据只认他自己说出口的。

## 断言各守什么（⛔ 不是同义的）

    承重1  已限定的那几样**不出现**在建议里
    承重2  三样都限定了 ⇒ **一个字都不建议**（少一句废话 > 多一句做不到的指引）
    阴性1  什么都没限定 ⇒ 三样都建议（⛔ 不把有用的建议也砍掉）
    阴性2  时间是**补默认**来的 ⇒ 仍然建议限定时间
    接线   兜底那段真的用了这个函数
"""
from __future__ import annotations

import ast
import datetime as _dt
import inspect
import textwrap

from smartbi.gold.restaurant import restaurant_intent as ri
from smartbi.gold.restaurant import restaurant_intent_service as svc
from smartbi.gold.restaurant.restaurant_intent_service import _narrowing_suggestion

_NARROW_STORES = ("模拟·徐汇美罗城店", "模拟·静安嘉里中心店")
_NARROW_ALL = ("某家店", "某道菜", "某个时间段")


def _spec(*, store=None, dish=None, window=None, time_defaulted=False):
    """直接构造那一刻的 spec —— 兜底那段拿到的就是它。

    ⚠️ 用 `dataclasses.replace` 而不是走 `_build_spec`：本文件量的是
       **措辞函数**对给定槽位的反应，⛔ 不是规划器怎么填这些槽位
       （那是别的文件的事，混在一起会让红了之后分不清是哪一头）。
    """
    from dataclasses import replace
    base = ri._build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN", "随便问一句", confidence=0.9,
        tier="llm", planner_authority="llm", llm_semantics_authoritative=True,
        clarification_needed=False, store_options=_NARROW_STORES)
    return replace(
        base,
        store_slots=(store,) if store else (),
        dish_slot=dish,
        window_label=window or "全部历史",
        date_range=((_dt.date(2026, 7, 21), _dt.date(2026, 8, 19))
                    if window else (None, None)),
        time_range_defaulted=time_defaulted,
    )


def test_precondition_the_suggestion_lists_exactly_three_things():
    """前提：建议里就这三样。多一样少一样，下面每条断言的分母都变了。"""
    text = _narrowing_suggestion(_spec())
    for item in _NARROW_ALL:
        assert item in text, f"建议里少了「{item}」：{text!r}"


def test_it_does_not_suggest_a_store_he_already_named():
    """承重1（门店那一半）。"""
    text = _narrowing_suggestion(_spec(store=_NARROW_STORES[0]))
    assert "某家店" not in text, f"他已经点了门店，还建议他限定门店：{text!r}"
    assert "某道菜" in text and "某个时间段" in text, text


def test_it_does_not_suggest_a_time_he_already_gave():
    """承重1（时间那一半）—— prod 实测撞上的正是这一格。"""
    text = _narrowing_suggestion(_spec(window="最近30天"))
    assert "某个时间段" not in text, f"他已经给了时间，还建议他限定时间：{text!r}"


def test_it_does_not_suggest_a_dish_he_already_named():
    text = _narrowing_suggestion(_spec(dish="水煮牛肉"))
    assert "某道菜" not in text, f"他已经点了菜，还建议他限定菜：{text!r}"


def test_the_real_prod_case_only_suggests_what_is_left():
    """🔴 承重1 的合成用例：prod 实测那句
    「模拟·徐汇美罗城店最近30天为什么毛利低」—— 已限定【门店 + 时间段】。"""
    text = _narrowing_suggestion(
        _spec(store=_NARROW_STORES[0], window="最近30天"))
    assert "某家店" not in text and "某个时间段" not in text, (
        f"建议里还有他做过的两样：{text!r}")
    assert "某道菜" in text, f"把唯一有用的那一样也砍掉了：{text!r}"


def test_everything_pinned_says_nothing_at_all():
    """🔴 承重2：三样都限定了 ⇒ **一个字都不建议**。

    ⛔ 少一句废话，比多一句他做不到的指引好 —— 反目标最重那条：
    一条误发的提示，烧掉的是「这东西说的话能信」。
    """
    text = _narrowing_suggestion(
        _spec(store=_NARROW_STORES[0], dish="水煮牛肉", window="最近30天"))
    assert text == "", f"三样都限定了还在给建议：{text!r}"


def test_nothing_pinned_suggests_all_three():
    """阴性1：⛔ 不把有用的建议也砍掉。"""
    text = _narrowing_suggestion(_spec())
    for item in _NARROW_ALL:
        assert item in text


def test_a_defaulted_window_is_not_something_he_gave():
    """🔴 阴性2：时间是**我们替他补的默认** ⇒ 不算他限定的。

    ⛔ 把补默认当成「他给了时间」，就会漏掉一条真正有用的建议 ——
    而那正是「量的不是想知道的那个东西」。
    """
    text = _narrowing_suggestion(_spec(window="最近30天", time_defaulted=True))
    assert "某个时间段" in text, (
        f"补默认的窗口被当成他限定的了：{text!r}")


def test_the_fallback_actually_uses_it():
    """接线（AST）：⚠️ 挡得住「有人把硬编码那句写回去」。"""
    src = inspect.getsource(svc.tiered_answer)
    tree = ast.parse(textwrap.dedent(src))
    called = {getattr(n.func, "id", None) for n in ast.walk(tree)
              if isinstance(n, ast.Call)}
    assert "_narrowing_suggestion" in called, "兜底那段没接上这个函数"
    # 硬编码的那句必须没了 —— 它是这一刀要消灭的东西
    assert "限定到某家店、某道菜或某个时间段" not in src, (
        "硬编码那句还在 —— 它对已经限定过的老板就是废话")
