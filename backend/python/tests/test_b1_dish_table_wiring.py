"""B-1 接线闸 —— 断言跑在**产品真实入口** `_maybe_append_dish_table` 上。

## 为什么单独有这一条

`daily_table` 那道闸只证明「渲染函数排版正确」。它对**没人调它**是盲的 ——
而这正是本轮要修的那个形态: `output_preference` 被算了出来、放进了响应,
**零消费端**, 于是「列个表」和不说话出来的东西一模一样(形态 B)。

⇒ 这里断言的是「谁在什么条件下调它」, ⛔ 不是「它排版对不对」。

## fail-open 也要断言

拼表格炸了不许让问答失败, 但**必须留痕** —— 否则「表格从来没出现过」
会长得和「这个租户没有菜品数据」一模一样。
"""
import asyncio
import logging

import pytest

from smartbi.gold.restaurant import restaurant_intent_service as svc
from smartbi.gold.restaurant.restaurant_intent import (
    OUTPUT_FORM_CHART,
    OUTPUT_FORM_TABLE,
    OUTPUT_FORM_TEXT,
)

MARGIN = "RESTAURANT_OPS_GROSS_MARGIN"
SUMMARY = "RESTAURANT_OPS_SALES_SUMMARY"
BASE = "今天罗氏虾卖得最好。"

#: 有 / 没有价格权限的两个角色。⛔ 不硬编码「谁有权限」——问 `PRICE_VIEW_ROLES`，
#: 并在下面钉一条前提断言：名单改了而这两个常量没跟着改时，**测试自己会说**
#: （否则名单一变，这一族断言会静默变成「两个都没权限」而全部恒真）。
ROLE_PRICED = "restaurant_manager"
ROLE_PLAIN = "restaurant_waiter"


def test_precondition_the_two_roles_are_on_opposite_sides():
    """前提断言 —— 它红了，下面所有权限断言的读数都作废。"""
    from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES

    assert ROLE_PRICED in PRICE_VIEW_ROLES, f"{ROLE_PRICED} 不再有价格权限"
    assert ROLE_PLAIN not in PRICE_VIEW_ROLES, f"{ROLE_PLAIN} 现在有价格权限了"


class _Spec:
    def __init__(self, intent, date_range=(None, None)):
        self.intent = intent
        self.date_range = date_range


def _fake_data():
    return {
        "dishes": [
            {"name": "罗氏虾", "qty": 10, "revenue": 500.0, "totalCost": 200.0,
             "grossProfit": 300.0, "marginRate": 0.6, "hasCost": True, "isEstimated": False},
        ],
        "totalRevenue": 500.0, "totalProfit": 300.0, "industryDefaultCostRatio": 0.32,
    }


@pytest.fixture
def stub_margins(monkeypatch):
    """桩掉出数, ⛔ 只桩外部 IO —— 判定逻辑走真的。"""
    calls = []

    async def _fake(pool, factory_id, **kwargs):
        calls.append((factory_id, kwargs))
        return _fake_data()

    # ⚠️ 必须打在 `dish_margin` 模块上 —— 被测函数是**函数内 import**,
    #    每次调用重新取属性, 所以打得到(见 tests/test_mutation_targets_are_reachable)。
    monkeypatch.setattr(
        "smartbi.gold.restaurant.dish_margin.compute_dish_margins", _fake
    )
    return calls


def _run(spec, pref, role=ROLE_PRICED):
    return asyncio.run(
        svc._maybe_append_dish_table(
            object(), "MOCK_REST", spec, BASE, pref, role=role
        )
    )


def test_table_appended_when_preference_asks_for_it(stub_margins):
    out = _run(_Spec(MARGIN), (OUTPUT_FORM_TEXT, OUTPUT_FORM_TABLE))
    assert out.startswith(BASE), "⛔ 原答案不许被表格顶掉 —— 表格是追加不是替换"
    assert "| 菜品 | 营收 | 成本 | 毛利 |" in out
    assert len(stub_margins) == 1, "应当且仅当需要时才查一次"


def test_no_table_when_preference_is_text_only(stub_margins):
    """🔴 这条正是「说『别用表格』反而锁死表格」修好之后才有意义的那一条。"""
    out = _run(_Spec(MARGIN), (OUTPUT_FORM_TEXT,))
    assert out == BASE
    assert stub_margins == [], "⛔ 不要表格时不该白查一次库"


def test_table_appended_for_daily_close_intent(stub_margins):
    """🔴 日结主路 —— 老板打烊那句话的产出者就是 SALES_SUMMARY 的 resolver。

    我第一版把它**排除**了(理由: 门店级概览下塞菜品表是换了粒度)。
    理由本身站得住, 但它是从意图描述的字面推的 ——
    ⇒ 结果是表格在**唯一要它的那条路上永远不出现**, 接线接了个寂寞。
    这条断言就是钉住那个错误不许回来。
    """
    out = _run(_Spec(SUMMARY), (OUTPUT_FORM_TEXT, OUTPUT_FORM_TABLE))
    assert out.startswith(BASE)
    assert "| 菜品 | 营收 | 成本 | 毛利 |" in out
    assert len(stub_margins) == 1


def test_no_table_for_unrelated_intent(stub_margins):
    """⛔ 不是所有意图都配一张菜品表 —— 库存预警下塞菜品毛利表是答非所问。"""
    out = _run(_Spec("RESTAURANT_OPS_INVENTORY_WARNING"),
               (OUTPUT_FORM_TEXT, OUTPUT_FORM_TABLE))
    assert out == BASE
    assert stub_margins == []


def test_chart_preference_alone_does_not_trigger_table(stub_margins):
    out = _run(_Spec(MARGIN), (OUTPUT_FORM_TEXT, OUTPUT_FORM_CHART))
    assert out == BASE
    assert stub_margins == []


def test_spec_date_range_is_forwarded(stub_margins):
    """时间窗只是**参数** —— 它必须原样传下去, ⛔ 不许被默认 30 天顶掉。"""
    import datetime

    rng = (datetime.date(2026, 8, 15), datetime.date(2026, 8, 15))
    _run(_Spec(MARGIN, date_range=rng), (OUTPUT_FORM_TEXT, OUTPUT_FORM_TABLE))
    assert stub_margins[0][1] == {"date_range": rng}


def test_failure_is_fail_open_but_leaves_a_trace(monkeypatch, caplog):
    async def _boom(pool, factory_id, **kwargs):
        raise RuntimeError("pg down")

    monkeypatch.setattr(
        "smartbi.gold.restaurant.dish_margin.compute_dish_margins", _boom
    )
    with caplog.at_level(logging.WARNING):
        out = _run(_Spec(MARGIN), (OUTPUT_FORM_TEXT, OUTPUT_FORM_TABLE))
    assert out == BASE, "拼表格失败 ⛔ 不许让一次问答失败"
    assert any("dish table render failed" in r.message for r in caplog.records), (
        "⛔ 静默失败 —— 那样「表格从来没出现过」会长得和「没有菜品数据」一模一样"
    )


def test_the_helper_is_actually_called_and_its_result_is_kept():
    """🔴 上面七条**全都直接调** helper —— 接线被删掉它们照样绿。

    这正是本轮要修的形态本身: 机制在、测试绿、**生产上没人调它**。
    ⇒ 这一条守的是「谁调它」, 而且守到「调完之后结果有没有被留住」——
      赋给别的变量 = 算了但丢了(产出端有了 ≠ 消费端收得到)。

    ⛔ 用 AST 数真正的 `Call` 节点, ⛔ 不用字符串计数 ——
       docstring 里提到函数名的那几行会被文本 grep 数进去(本仓记过三次)。
    """
    import ast
    import inspect

    tree = ast.parse(inspect.getsource(svc))
    assigned_to_answer = []
    for node in ast.walk(tree):
        if not isinstance(node, ast.Assign):
            continue
        value = node.value
        if isinstance(value, ast.Await):
            value = value.value
        if not (isinstance(value, ast.Call) and isinstance(value.func, ast.Name)):
            continue
        if value.func.id != "_maybe_append_dish_table":
            continue
        targets = [t.id for t in node.targets if isinstance(t, ast.Name)]
        assigned_to_answer.extend(targets)

    assert assigned_to_answer, (
        "⛔ 生产路径上没有 `answer_text = await _maybe_append_dish_table(...)` —— "
        "接线断了, 上面七条断言全部沦为「守着一个没人调的函数」"
    )
    assert assigned_to_answer == ["answer_text"], (
        f"结果被赋给了 {assigned_to_answer} 而不是 answer_text —— 算了但丢了"
    )


def test_empty_result_does_not_append_an_empty_table(monkeypatch, caplog):
    async def _empty(pool, factory_id, **kwargs):
        return {"dishes": [], "totalRevenue": 0.0, "totalProfit": 0.0}

    monkeypatch.setattr(
        "smartbi.gold.restaurant.dish_margin.compute_dish_margins", _empty
    )
    with caplog.at_level(logging.WARNING):
        out = _run(_Spec(MARGIN), (OUTPUT_FORM_TEXT, OUTPUT_FORM_TABLE))
    assert out == BASE, "⛔ 不拼一张只有表头的空表"
    assert any("dish table empty" in r.message for r in caplog.records)


# ══════════════════════════════════════════════════════════════════════════
# 无金额权限的角色: 一个 ¥ 都不许发出去 (2026-08-19)
#
# prod 实测(MOCK_REST / restaurant_waiter / 2 轮)的缺陷长相 —— 同一屏里:
#   「…当前角色不能查看金额。」  然后紧接着  「| 鲈鱼 | ¥2,926,000.00 | …」
# 16 条 waiter 读数里 10 条正文带 ¥, 每一条的正文尾巴都**逐字等于**
# 单独渲染出来的那张菜品表(结构判据, ⛔ 不是子串匹配)。
# ══════════════════════════════════════════════════════════════════════════


def test_no_dish_table_for_role_without_price_view(stub_margins, caplog):
    """底线: 无价格权限 ⇒ 原样返回, 且**一次库都不查**。"""
    with caplog.at_level(logging.WARNING):
        out = _run(_Spec(MARGIN), (OUTPUT_FORM_TEXT, OUTPUT_FORM_TABLE),
                   role=ROLE_PLAIN)
    assert out == BASE, "⛔ 无价格权限的角色不许拿到菜品金额表"
    assert stub_margins == [], "⛔ 都不发给他了, 不该白查一次库"
    assert any("dish table withheld" in r.message for r in caplog.records), (
        "⛔ 静默扣下 —— 「表格从来没出现过」会长得和「这个租户没有菜品数据」一样"
    )


def test_dish_table_still_appended_for_role_with_price_view(stub_margins):
    """阳性对照: 有权限那一侧**必须**照常拿到表。

    ⚠️ 它回答的是「仪器坏掉时上面那条会不会也成立」—— 会:
       我把闸写成 `return answer_text` 无条件早退, 上面那条照样绿。
       这一条会红。
    """
    out = _run(_Spec(MARGIN), (OUTPUT_FORM_TEXT, OUTPUT_FORM_TABLE),
               role=ROLE_PRICED)
    assert "| 菜品 | 营收 | 成本 | 毛利 |" in out
    assert "¥" in out, "有价格权限的角色反而看不到金额了 —— 修过头"
    assert len(stub_margins) == 1


@pytest.mark.parametrize("role", [None, "", "unknown_role_never_seen_before"])
def test_missing_or_unknown_role_is_treated_as_no_price_view(stub_margins, role):
    """`None` / 空 / 没见过的角色一律**没有**权限。

    ⛔ 不许「没传就当有权限」—— 兜底的默认值会把「我不知道」翻译成一个假读数,
       而这里那个假读数就是把金额发出去(形态 A¹⁰)。
    """
    out = _run(_Spec(MARGIN), (OUTPUT_FORM_TEXT, OUTPUT_FORM_TABLE), role=role)
    assert out == BASE
    assert "¥" not in out


def test_role_is_keyword_required_not_defaulted():
    """`role` 必须是**关键字必填** —— 少传一处当场炸, ⛔ 不静默走「有权限」。

    ⚠️ 这一条守的是「下一个人加第二个调用点时会被拦住」。给默认值的话,
       新调用点忘了传 role 就会**静默沿用默认**, 而默认无论取哪一侧都错:
       取有权限=漏金额, 取无权限=悄悄不给表。
    """
    import inspect

    for fn in (svc._maybe_append_dish_table, svc._maybe_append_attribution):
        sig = inspect.signature(fn)
        assert "role" in sig.parameters, f"{fn.__name__} 的签名里没有 role"
        param = sig.parameters["role"]
        assert param.kind is inspect.Parameter.KEYWORD_ONLY, (
            f"{fn.__name__}: role 必须是关键字参数, ⛔ 不许靠位置传"
        )
        assert param.default is inspect.Parameter.empty, (
            f"{fn.__name__}: role 不许有默认值 —— 少传一处就会静默走默认那一侧"
        )


def test_every_money_projection_point_on_the_path_takes_role():
    """硬约束 8: 数的是**整条路**上的金额投影点, ⛔ 不是一个符号。

    `tiered_answer` 里改写 `answer_text` 的地方不止一处。两处会吐金额的
    (拼表 / 归因) 都必须收到 role —— 只修一处的后果是「金额从一道闸挪到下一道」。

    ⛔ 用 AST 数 `Call` 节点, 不用文本 grep(docstring 里提到函数名的行会被数进去)。
    """
    import ast
    import inspect

    tree = ast.parse(inspect.getsource(svc))
    money_appenders = {"_maybe_append_dish_table", "_maybe_append_attribution"}
    seen = {}
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call):
            continue
        name = getattr(node.func, "id", None)
        if name not in money_appenders:
            continue
        seen.setdefault(name, []).append(
            {k.arg: k.value for k in node.keywords if k.arg}
        )

    assert set(seen) == money_appenders, (
        f"⛔ 生产路径上少了投影点: 只找到 {sorted(seen)} —— "
        f"要么接线断了, 要么有人新加了一个不带 role 的出口"
    )
    for name, callsites in seen.items():
        assert callsites, f"{name} 一个调用点都没有"
        for kwargs in callsites:
            assert "role" in kwargs, (
                f"⛔ {name} 有调用点没传 role —— 那一处就是新的漏金额出口"
            )
            # 🔴 传的必须是**那个变量** `role`(调用者手上的真实角色),
            #    ⛔ 不许是字面量。写死一个有权限的角色 = 闸还在、恒真,
            #    而这正是本轮修的缺陷换个写法回来。
            #    ⚠️ 这一条是变异 M4 逼出来的: 第一版只断言「kwargs 里有 role」,
            #      我把 `role=role` 改成 `role='restaurant_manager'` 之后
            #      **一条都没红** —— 不是断言在守空气, 是变异打在了它看不见的
            #      地方(形态 C″ 第二种成因)。
            value = kwargs["role"]
            assert isinstance(value, ast.Name) and value.id == "role", (
                f"⛔ {name} 的调用点把 role 写成了字面量 "
                f"{ast.dump(value)[:80]} —— 闸会恒真"
            )
