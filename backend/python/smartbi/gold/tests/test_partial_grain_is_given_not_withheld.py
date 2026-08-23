# -*- coding: utf-8 -*-
"""一半能做时，把能做的那一半**发出去**，并说清另一半为什么不能。

## prod 实测（MOCK_REST，全景重测 24 问句 × 2 轮，2/2 轮逐字相同）

老板问「哪家店缺货最严重」，拿到：

    我不确定你要看的是哪一层的数，所以这次我没敢算。按食材我能算，但你这句还要求
    再按门店拆一层，这次选中的算法拆不出这一层。想看的话分开问：先只按食材问同一件事。

而「按食材我能算」**是真的** —— 照那条出路真的走一遍（`_inventory_followthrough`）：

    **库存预警（2026-08-19）:** 需要立即补货 **4 项**, 关注 0 项, 正常 21 项
    | 需立即补货 | 鲈鱼 | 水产 | 1689.4 kg | 4826.8 kg | 3137.4 kg |

▎**产品手里有一份完整答案，却因为老板多问了一层而整份扣下不发。**

## 断在哪（两处）

1. `_drop_unanswerable_mislabeled_dimensions` 本来就是干这件事的，
   它的两个条件对这句**都成立**（store 上没点名 + 计划里无 resolver 支持 store），
   但 `namers` 里**只登记了 `ingredient`** ⇒ `store` 的 `namer is None` ⇒ 不动。
2. 就算剥掉了，它**只写 log 不投影** ⇒ 老板问按店、拿到按食材，
   **没有一句话解释门店那一层去哪了** —— 那是静默降级，比整句作废更贵。

## 五条断言各守什么（⛔ 不是五条同义的）

    承重   Q09 形态：store 被剥掉 ⇒ 不再撞维度闸
    阴性1  剥掉了就**必须**在答案里说「按门店拆不出来」 ⛔ 不静默降级
    阴性2  那句说明必须在**第一句** ⛔ 不许先甩数再解释
    阴性3  老板**点了名**的门店不许被剥        ⛔ 不把用户说的东西扔掉
    阴性4  resolver 真能按店出时不许剥          ⛔ 不降级本来能答的

设计卡 `docs/decisions/2026-08-19-哪家店缺货最严重-部分能做-设计卡.md`。
"""
from __future__ import annotations

import ast

import pytest

from smartbi.gold.restaurant.restaurant_intent_service import (
    _drop_unanswerable_mislabeled_dimensions,
    _partial_grain_note,
)

_INV = ("RESTAURANT_OPS_INVENTORY_WARNING",)
_Q09 = "哪家店缺货最严重"


class _Spec:
    """最小 spec 桩 —— `_drop_...` 只读 `dimensions`，`_replace` 需要 dataclass。

    ⚠️ 用真 dataclass，⛔ 不用 SimpleNamespace：产品代码走
       `dataclasses.replace`，桩不是 dataclass 会 TypeError，
       那不是缺陷，是我造了一个真实上游产不出的形状（形态 B‴）。
    """


def _spec(dimensions):
    from dataclasses import make_dataclass
    Spec = make_dataclass("Spec", [("dimensions", tuple)])
    return Spec(dimensions=tuple(dimensions))


# ── 前提：这条路必须真的走得到 ─────────────────────────────────────────
def test_precondition_inventory_warning_does_not_serve_store():
    """⛔ 前提没了，下面五条都没量到东西。"""
    from smartbi.gold.restaurant.restaurant_intent_service import _RESOLVER_DIMENSIONS

    caps = _RESOLVER_DIMENSIONS["RESTAURANT_OPS_INVENTORY_WARNING"]
    assert "ingredient" in caps, "前提：它必须能按食材出，否则「部分能做」无从谈起"
    assert "store" not in caps, (
        "前提：它必须**不能**按门店出 —— 能出的话这条路根本不该被剥")


def test_store_is_dropped_when_nobody_named_one():
    """承重：没点名门店 + 计划里无人服务 store ⇒ 剥掉，⇒ 不再撞维度闸。"""
    spec, dropped = _drop_unanswerable_mislabeled_dimensions(
        _spec(("store", "ingredient")), _INV, _Q09,
        caller_judged={"store": False},
    )
    assert "store" not in spec.dimensions, (
        "没人点名门店、计划里也没人能按门店出，却把它留着 —— "
        "老板会撞维度闸，而按食材那份完整答案本来就在手上")
    assert "ingredient" in spec.dimensions, "把能做的那一半也剥掉了"
    assert dropped == ("store",), f"被剥掉的维度没被报出来：{dropped}"


def test_dropping_must_be_told_to_the_boss():
    """阴性1：剥了就必须说 —— ⛔ 静默降级比整句作废更贵。"""
    spec, dropped = _drop_unanswerable_mislabeled_dimensions(
        _spec(("store", "ingredient")), _INV, _Q09,
        caller_judged={"store": False},
    )
    note = _partial_grain_note(dropped, spec, _INV)
    assert note, "剥掉了门店那一层却一个字都没说 —— 老板不知道他问的那层去哪了"
    assert "门店" in note, f"说明里没提被剥掉的那一层：{note!r}"
    assert "食材" in note, f"说明里没说还能按什么看：{note!r}"
    # ⛔ 技术词不给老板（owner 措辞规范）
    for jargon in ("resolver", "维度", "算法", "字段", "表"):
        assert jargon not in note, f"说明里出现技术词「{jargon}」：{note!r}"
    # ⛔「我没敢算」是交付定义①点名禁止的
    assert "没敢算" not in note, f"说明里还在说「没敢算」：{note!r}"


def test_the_note_comes_first_not_after_the_numbers():
    """阴性2：说明必须在第一句。

    逐字沿用 `partial_coverage_answer` 的 docstring（owner 2026-08-12）：
    ⛔ 反过来写（先甩数再解释）用户读到的就是「赚了这么多」——
       **那是「相邻指标顶替」换个位置重演**。
    """
    spec, dropped = _drop_unanswerable_mislabeled_dimensions(
        _spec(("store", "ingredient")), _INV, _Q09,
        caller_judged={"store": False},
    )
    note = _partial_grain_note(dropped, spec, _INV)
    body = "**库存预警**: 需要立即补货 4 项"
    combined = note + body
    assert combined.index("门店") < combined.index("需要立即补货"), (
        "说明排在数字后面 —— 老板读到的第一眼是数字，会以为那就是按门店的答案")


def test_a_named_store_is_never_dropped():
    """阴性3：老板**点了名**的门店不许被剥。

    🔴 这一条是本轮差点踩的坑：执行入口那个 `store_mention` 变量
       被 `plan` 门控（只在 STORE_MARGIN/GROSS_MARGIN/SALES_SUMMARY 时才算），
       对 `INVENTORY_WARNING` **恒为 None** —— 拿它当「有没有点名」的信号，
       会把老板说出口的门店静默扔掉。
    ⇒ 判据必须用**已过目录核对**的 store_mentions，⛔ 不用那个被门控的变量。
    """
    spec, dropped = _drop_unanswerable_mislabeled_dimensions(
        _spec(("store", "ingredient")), _INV, "模拟·徐汇美罗城店缺货最严重",
        caller_judged={"store": True},
    )
    assert "store" in spec.dimensions, (
        "老板点了名的门店被剥掉了 —— 那是把他说出口的东西扔了")
    assert dropped == (), f"不该剥却报了剥：{dropped}"


def test_a_dimension_the_resolver_can_serve_is_never_dropped():
    """阴性4：resolver 真能按店出时不许剥 —— ⛔ 不降级本来能答的。"""
    store_capable = ("RESTAURANT_OPS_STORE_MARGIN",)
    from smartbi.gold.restaurant.restaurant_intent_service import _RESOLVER_DIMENSIONS

    assert "store" in _RESOLVER_DIMENSIONS[store_capable[0]], (
        "前提：这个 resolver 必须真能按店出，否则本条没量到东西")

    spec, dropped = _drop_unanswerable_mislabeled_dimensions(
        _spec(("store",)), store_capable, "哪家店毛利最低",
        caller_judged={"store": False},
    )
    assert "store" in spec.dimensions, "能按店出却把 store 剥了 —— 把能答的降级了"
    assert dropped == ()


def test_ingredient_path_is_unchanged():
    """阴性5（回归面）：原来那条 `ingredient` 的路径逐字不变。

    ⛔ 本次只**新增** store 这一格，不许顺手改动既有行为
       （硬约束 8：改共享结构前后各数一次）。
    """
    # 「食材成本占营收多少」—— 出现了「食材」但一个食材都没点名
    spec, dropped = _drop_unanswerable_mislabeled_dimensions(
        _spec(("ingredient",)), ("RESTAURANT_OPS_SALES_SUMMARY",),
        "最近30天食材成本占营收多少",
    )
    assert "ingredient" not in spec.dimensions, (
        "既有的 ingredient 剥离行为被改坏了")
    assert dropped == ("ingredient",)


class TestTheWiringIsActuallyThere:
    """🔴 上面那些全是**直接调 helper**，它们证明不了「生产上谁保证它被调用」。

    📏 变异对照当场抓到：把调用点里的 `"store"` 键改个名（接线断开），
    上面 10 条断言**一条都没红** ——
    ▎**形态 B：我测的是 helper，不是接线。**
    （本仓 memory 记过同型：「同一个『测了 helper 不是接线』在一个分支上犯两次」。）

    ⚠️ 这两条是 **AST 结构断言**，它们的能力边界要写清楚：
      ✅ 挡得住「删掉接线」「改键名」「换成别的变量」
      ⛔ **挡不住「用 `if False` 绕过」** —— 要证明那个，只有走真实入口。
    ⇒ 行为那一半由 **prod 真跑一次** 补（交付判据②，见设计卡与部署账）。
    """

    def _call_kwargs(self):
        import inspect
        from smartbi.gold.restaurant import restaurant_intent_service as svc

        tree = ast.parse(inspect.getsource(svc))
        out = []
        for node in ast.walk(tree):
            if (isinstance(node, ast.Call)
                    and getattr(node.func, "attr",
                                getattr(node.func, "id", None))
                    == "_drop_unanswerable_mislabeled_dimensions"):
                out.append(node)
        return out

    def test_the_call_site_passes_a_store_judgement(self):
        calls = self._call_kwargs()
        assert len(calls) == 1, (
            f"调用点从 1 处变成 {len(calls)} 处 —— 来这里过一眼，"
            "确认新增的那处也传了 store 判定，⛔ 不是又写了一份")
        keys = []
        for kw in calls[0].keywords:
            if kw.arg == "caller_judged" and isinstance(kw.value, ast.Dict):
                keys = [k.value for k in kw.value.keys
                        if isinstance(k, ast.Constant)]
        assert "store" in keys, (
            "生产调用点没有把门店那一格传进来 —— "
            "「哪家店缺货最严重」会照旧撞维度闸，而按食材那份答案就在手上。"
            f"实际传进去的键：{keys}")

    def test_the_note_is_wired_into_the_answer(self):
        """剥掉的维度必须真的走到 `_partial_grain_note` —— ⛔ 不是算出来就扔。"""
        import inspect
        from smartbi.gold.restaurant import restaurant_intent_service as svc

        tree = ast.parse(inspect.getsource(svc))
        n = sum(1 for node in ast.walk(tree)
                if isinstance(node, ast.Call)
                and getattr(node.func, "attr", getattr(node.func, "id", None))
                == "_partial_grain_note")
        assert n >= 1, (
            "`_partial_grain_note` 一次都没被调用 —— 说明算出来了却没发给老板，"
            "那正是这次要修的那个形态（形态 B 第 7 例：投影丢失）")


@pytest.mark.parametrize("dim", ["channel", "meal_period", "customer"])
def test_unjudgeable_dimensions_are_left_alone(dim):
    """阴性6：判不了的粒度**一律不动** —— 逐字沿用那个函数自己的纪律。

    ⛔ 本次只登记 store（调用点真的算过它），
       ⛔ 不顺手把别的粒度也放进来 —— 猜错的代价是答非所问。
    """
    spec, dropped = _drop_unanswerable_mislabeled_dimensions(
        _spec((dim,)), _INV, "随便问一句",
    )
    assert spec.dimensions == (dim,), f"判不了的 {dim} 被动了"
    assert dropped == ()
