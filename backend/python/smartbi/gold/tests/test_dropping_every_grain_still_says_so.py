# -*- coding: utf-8 -*-
"""老板问的那一层**全部**拆不出来时，也要说一句 —— ⛔ 不能两边都以为对方会说。

## prod 实测（`_dimension_not_served`，MOCK_REST，2/2 逐轮一致）

「最近30天**哪家店**折扣给得最多」→ `RESTAURANT_OPS_DISCOUNT_SUMMARY`
（能力表 `[]`，一个维度都不支持）。老板拿到的是**哪种券**的一张表：

    **折扣力度（2026-07-21 至 2026-08-19）：**
    - 折扣占营收 4.5% / 折扣总额 ¥939,770
    | 折扣类型 | 金额 | 占折扣 |
    | 美团团购套餐券 | ¥198,149 | 21.1% |

▎他问「哪家店」，拿到「哪种券」，**一个字没提「按门店拆不出来」**。

## 🔴 这是同日**另一处改动引入的回归**（本文件存在的理由）

`_drop_unanswerable_mislabeled_dimensions` 2026-08-19 把「门店那一格」接了进来
（为「哪家店缺货最严重」那一刀）。本地 A/B：

    剥之前: `_dimension_gap_advice` → 「你这句要按门店来看，而这次选中的算法
             不按维度拆，只能给一个整体结论。」                       ✅
    剥之后: advice → ''（`extra` 被剥空了）、note → ''                🔴 谁都不说

`_partial_grain_note` 在「能算的一层都不剩」时返回空串**让位给整份拒答**，
而剥维度这个动作**把那条路的输入清空了**。▎**两边都以为对方会说。**

⚠️ 全量回归**没抓到** —— 没有任何断言守着「`supported` 为空」这一格。
   ⇒ 本文件补上它。

## 修法（⛔ 不造第二份措辞）

advice 的语义是「**你问的**那一层拆不出来」，输入天然该是**老板问的**维度。
⇒ 把被剥掉的那层**加回去**再问**同一个**措辞函数。
（advice 那三支的措辞是拿 prod 实测打磨过两轮的 —— 两次都是因为它在编机制 /
 硬编码建议词。⛔ 在这里另写一句就是形态 D。）

## 断言各守什么（⛔ 不是同义的）

    承重   全部维度被剥掉 ⇒ 仍然给出说明，且**用的是 advice 那一份措辞**
    阴性1  **部分**能做那一格（Q09）⇒ 措辞**逐字不变**
    阴性2  什么都没剥 ⇒ 空串（⛔ 不无中生有）
    阴性3  老板**点了名**的门店 ⇒ 根本不该剥
    接线   借的是 `_dimension_gap_advice`，⛔ 不是新写的字符串
"""
from __future__ import annotations

import ast
import inspect
import textwrap

from smartbi.gold.restaurant import restaurant_intent as ri
from smartbi.gold.restaurant import restaurant_intent_service as svc

_ALLDROP_STORES = ("模拟·徐汇美罗城店", "模拟·静安嘉里中心店")
_ALLDROP_DISCOUNT = "RESTAURANT_OPS_DISCOUNT_SUMMARY"
_ALLDROP_INVENTORY = "RESTAURANT_OPS_INVENTORY_WARNING"
_ALLDROP_STORE_MARGIN = "RESTAURANT_OPS_STORE_MARGIN"


def _spec(code, dims, query):
    return ri._build_spec(
        code, query, confidence=0.95, tier="llm", planner_authority="llm",
        llm_semantics_authoritative=True, llm_dimensions=dims,
        clarification_needed=False, store_options=_ALLDROP_STORES,
    )


def _note(code, dims, query, *, store_named=False):
    plan = (code,)
    spec = _spec(code, dims, query)
    kept, dropped = svc._drop_unanswerable_mislabeled_dimensions(
        spec, plan, query, caller_judged={"store": store_named})
    return svc._partial_grain_note(tuple(dropped), kept, plan), tuple(dropped)


def test_precondition_the_discount_resolver_serves_no_dimension():
    """前提：这一刀量的是「能力表为空」那一格。

    ⚠️ 哪天 `DISCOUNT_SUMMARY` 补登了维度，本文件的承重断言就不再测那件事 ——
    ⛔ 让它明着红，不要静默变成量别的。
    """
    assert not svc._RESOLVER_DIMENSIONS.get(_ALLDROP_DISCOUNT), (
        "DISCOUNT_SUMMARY 补登了维度 —— 本文件的承重那条要换一个空能力表的 resolver")


def test_all_grains_dropped_still_tells_the_boss():
    """🔴 承重：老板问的那一层全拆不出来 ⇒ 仍然要说。"""
    note, dropped = _note(_ALLDROP_DISCOUNT, ("store",), "最近30天哪家店折扣给得最多")
    assert dropped == ("store",), f"前提：门店那一层没被剥（dropped={dropped}）"
    assert note, "全部维度被剥掉之后一句话都没有 —— 老板问「哪家店」拿到「哪种券」"
    assert "门店" in note, f"没说是哪一层拆不出来：{note!r}"


def test_it_reuses_the_refusal_wording_not_a_new_one():
    """⛔ 措辞只有一份（形态 D）——直接比对 advice 自己的输出。"""
    query = "最近30天哪家店折扣给得最多"
    note, dropped = _note(_ALLDROP_DISCOUNT, ("store",), query)
    from dataclasses import replace
    spec = _spec(_ALLDROP_DISCOUNT, ("store",), query)
    advice = svc._dimension_gap_advice(
        replace(spec, dimensions=tuple(dropped)), (_ALLDROP_DISCOUNT,))
    assert advice and note.startswith(advice), (
        f"note 不是 advice 那一份措辞 —— 长出了第二份\n note={note!r}\n advice={advice!r}")


def test_partial_coverage_wording_is_unchanged():
    """🔴 阴性1：**部分**能做那一格（Q09 那一刀）措辞逐字不变。

    这条一旦变了，说明我把两种情况混成了一句 —— 而它们对老板的含义不同：
    「只能按食材看」是**还有数可看**，「只能给一个整体结论」是**不按维度拆**。
    """
    note, dropped = _note(_ALLDROP_INVENTORY, ("store", "ingredient"),
                          "哪家店缺货最严重")
    assert dropped == ("store",)
    assert note == "**按门店拆不出来**，这次只能按食材看。下面是按食材的情况：\n\n"


def test_nothing_dropped_says_nothing():
    """阴性2：⛔ 不无中生有。"""
    note, dropped = _note(_ALLDROP_STORE_MARGIN, ("store",), "各门店营收是多少")
    assert dropped == ()
    assert note == ""


def test_a_named_store_is_never_dropped():
    """阴性3：老板点了名的门店 ⇒ 根本不该剥（那是他的题目）。"""
    _note_txt, dropped = _note(_ALLDROP_DISCOUNT, ("store",),
                               "模拟·徐汇美罗城店折扣多少", store_named=True)
    assert dropped == (), "把老板点名的门店剥掉了 —— 那是替他改题"


def test_the_note_borrows_the_advice_function():
    """接线（AST）：⚠️ 挡得住「有人在这里另写一句」，⛔ 挡不住 `if False`。"""
    tree = ast.parse(textwrap.dedent(inspect.getsource(svc._partial_grain_note)))
    called = {getattr(n.func, "id", None) for n in ast.walk(tree)
              if isinstance(n, ast.Call)}
    assert "_dimension_gap_advice" in called, (
        "没有借用 advice 那份措辞 —— 第二份措辞早晚会漂")
