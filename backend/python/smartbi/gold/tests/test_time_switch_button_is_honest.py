"""换时间按钮不许骗人 —— 标签承诺的窗口必须就是点下去真的会看到的窗口。

## 缺陷长相 (2026-08-18 端到端复测, 9/9)

老板问「营收怎么样」(没说时间)。规划层把默认窗口接在问句**尾巴**上, 于是
`resolver_query_seed = '营收怎么样 最近30天'`。换时间按钮拿这个 seed 去拼:
`_split_time_scope` 按纪律**只动开头**, 剥不掉尾巴那个 ⇒ 拼出
`'本月营收怎么样 最近30天'`。按钮写着「看本月」, 点下去老板看到的还是**最近30天**。

## 为什么是**闸**而不是**剥尾巴**

⛔ 让 `_split_time_scope` 去剥尾巴会碰句中时间词 ——「六月比七月高多少」是反例。
▎宁可不给这个按钮, 也不给一个点了会骗人的。

## 判据是「问一次那个函数」, ⛔ 不是「按规则推一遍」

同一批实测里 `'最近7天营收怎么样 最近30天'` 解出来的是 **最近7天**(头部赢),
而 `'本月营收怎么样 最近30天'` 解出来的是 **最近30天**(尾部赢) —— 谁赢取决于
`_resolve_sales_query_spec` 的分支顺序, **不是位置**。任何位置规则都会推错。
"""
from __future__ import annotations

import pytest

from smartbi.gold.restaurant import restaurant_intent_service as svc
from smartbi.gold.restaurant.restaurant_ops_router import _resolve_sales_query_spec


def _time_buttons(seed: str, *, intent: str = "RESTAURANT_OPS_SALES_SUMMARY",
                  window_label: str = "最近30天", store_options=()):
    return svc._time_window_switch_followups({
        "intent": intent,
        "question_seed": seed,
        "window_label": window_label,
        "store_options": list(store_options),
        "store_scope": "all",
    })


def _promised(button) -> str:
    """标签 `看X` 里承诺的那个窗口。"""
    assert button["label"].startswith("看"), button
    return button["label"][1:]


# ── 阳性对照: 仪器活着 ────────────────────────────────────────────────
# ⚠️ 「一个按钮都没有」和「按钮全是诚实的」在下面那条断言里长得一模一样。
#    没有这一条, 闸把所有按钮删光也会全绿。

def test_honest_seed_still_gets_buttons():
    """阳性对照 —— 用户自己把时间词写在**开头**时, 按钮照给, 且诚实。

    🔴 这同时是缺陷【1】的**阴性对照**: 这条 seed 修前就 0/3 不一致
       (两个按钮都对), 修后必须**仍然**两个按钮都在、都对。
       它证明这道闸不是「把按钮全删了」。
    """
    buttons = _time_buttons("最近30天全部门店营收和订单量分别是多少")
    assert len(buttons) == 2, f"诚实的 seed 上按钮被删了: {buttons}"
    for b in buttons:
        assert _resolve_sales_query_spec(b["question"]).window_label == _promised(b), (
            f"阳性对照自己就不自洽, 仪器坏了: {b}")
    assert [b["question"] for b in buttons] == [
        "本月全部门店营收和订单量分别是多少",
        "上个月全部门店营收和订单量分别是多少",
    ], "阳性对照的问句变了 —— 逐字比, 不是只比 kind"


# ── 被守的行为 ────────────────────────────────────────────────────────

@pytest.mark.parametrize("seed", [
    "营收怎么样 最近30天",
    "毛利率是多少 最近30天",
    "订单量怎么样 本月",
    "客单价多少 上个月",
])
def test_no_button_promises_a_window_it_will_not_deliver(seed: str):
    """承重断言: 任何一个换时间按钮, 点下去解出来的窗口 == 标签承诺的那个。

    ⛔ 不是「按钮数量对不对」, 是「它说的和它做的一不一致」—— 后者才是老板
       会撞上的那件事。
    """
    for b in _time_buttons(seed):
        actual = _resolve_sales_query_spec(b["question"]).window_label
        assert actual == _promised(b), (
            f"按钮在骗人: seed={seed!r} label={b['label']!r} "
            f"question={b['question']!r} 承诺={_promised(b)!r} 实际={actual!r}")


def test_the_reported_seed_loses_its_lying_buttons():
    """缺陷【1】原样复现的那条 seed: 「看本月」「看上个月」必须消失。

    ⚠️ 它们消失之后剩下的「看最近7天」是**诚实**的(实测头部赢), 所以这里
       ⛔ 不断言「一个按钮都没有」—— 那会把闸的行为写死成一个更粗的形状。
    """
    labels = [b["label"] for b in _time_buttons("营收怎么样 最近30天")]
    assert "看本月" not in labels, f"骗人的「看本月」还在: {labels}"
    assert "看上个月" not in labels, f"骗人的「看上个月」还在: {labels}"


def test_comparison_grammar_override_is_covered():
    """判据必须用生产真正的那一条 `_resolve_sales_query_spec().window_label`。

    🔴 `_resolve_sales_date_range(...)[1]` 是它的**中间产物** ——
       `_resolve_sales_query_spec` 之后还有一条比较语法的覆盖分支会改写窗口。
       2026-08-20 实测 11 条候选里差 1 条:

           '上个月营收和上个月比怎么样'   date_range='上个月'   query_spec='本月'

       用中间产物当判据, 这一类按钮会被**放行**而仍然在骗人。
       这条测试就是钉住「闸读的是生产那一份」。
    """
    from smartbi.gold.restaurant.restaurant_ops_router import _resolve_sales_date_range
    probe = "上个月营收和上个月比怎么样"
    # 前提断言: 这两个口径**真的**在这条输入上分岔。分岔没了这条测试就没意义,
    # 到时候要的是重新找一条分岔样本, ⛔ 不是把这条删掉。
    assert _resolve_sales_date_range(probe)[1] != _resolve_sales_query_spec(probe).window_label, (
        "两个口径不再分岔 —— 这条测试守的东西消失了, 重新找样本, 别删断言")
    assert svc._button_window_is_honest(probe, "本月") is True
    assert svc._button_window_is_honest(probe, "上个月") is False, (
        "闸读的是 _resolve_sales_date_range 这个中间产物 —— 它会放行骗人的按钮")


def test_one_definition_only():
    """形态 D: 两个合成器读**同一份**判据。各算一份必漂, 而漂的症状不报错。"""
    import ast
    import inspect

    for fn in (svc._compose_clarification_question, svc._time_window_switch_followups):
        tree = ast.parse(inspect.getsource(fn).lstrip())
        called = {
            n.func.id for n in ast.walk(tree)
            if isinstance(n, ast.Call) and isinstance(n.func, ast.Name)
        }
        assert "_button_window_is_honest" in called, (
            f"{fn.__name__} 没有调用那道闸 —— 它要么没接上, 要么又自己算了一份")
