"""一道菜没有成本卡时, 它不许被凭空判进四象限。

背景(2026-08-20): `sections/menu_engineering.py` 以前对查不到成本卡的菜用
`revenue * 0.4` 兜底 —— 凭空给它 60% 毛利, 然后拿这个编出来的数判它是明星
还是瘦狗。本文件钉住修复后的行为。

## 承重断言是**成对**的

① 没有成本卡的菜不再被分类, 且**被说出来**   -> test_uncosted_*
② 有成本卡的菜, 分类结果**不受这次改动影响** -> test_positive_control_*

⛔ 少了 ②, 这一刀可能把整个功能改坏而看不出来。

## ⚠️ ② 的字面版本**不成立**, 且不该成立

「有成本卡的那几道分类逐字不变」放在**整个输入集**上是错的: 把编造的菜剔出去
必然改变中位数 —— 那正是这次修复的目的(实测编造值把 marginMedian 拖到恰好
0.6)。要让它字面成立, 只能把编造的菜留在中位数里, 等于不修。

⇒ 真正该守的不变量更窄, 也正是它在守数学:
     analyze(恰好那几道有卡的菜) 的结果, 改动前后逐字相同。
   也就是说这次只改了**谁进来**, 没改**怎么判**。

## ⚠️ `summary` 是归属的**有损投影**, 不要只断言它

实测: 把 米饭 的成本从 3,505,113 改成 1.0, 四个格子的**人数**仍然是
{star 1, cow 2, puzzle 2, dog 1}, 而**谁在哪个格子**变了两处
(藤椒鸡 puzzle->dog, 米饭 dog->puzzle)。既有的 5 个测试全部只断言 counts,
所以它们对「重新分配」是盲的。本文件一律断言**归属**。
"""
from __future__ import annotations

import pandas as pd
import pytest

from smartbi.services.restaurant.menu_engineering import (
    EXCLUSION_TEXT,
    MIN_COMPARABLE_ITEMS,
    ExclusionReason,
    MenuEngineeringAnalyzer,
)
from smartbi.services.restaurant.sections.base import SectionRequest, SectionStatus
from smartbi.services.restaurant.sections.menu_engineering import MenuEngineeringHandler

# ── 实测数据: MOCK_REST 近 30 天, smartbi_prod_db + cretas_prod_db ─────────
# 桥接键 product_source_pk = product_types.id (⛔ 不是 product_id, 那一列全 0)。
# 探针跑了 2 轮, 输出逐字相同。
# name, qty, revenue, 每份成本卡(None = 库里没有这道菜的成本卡行)
MOCK_REST_MEASURED = [
    ("鲈鱼",     52560, 4625280.0, 32.9390),
    ("水煮牛肉", 52885, 3596180.0, 28.1460),
    ("藤椒鸡",   51916, 3011128.0, 19.3340),
    ("干锅花菜", 52648, 2000624.0, 6.2080),
    ("红糖糍粑", 52226, 1357876.0, 2.7810),
    ("米饭",     43273,  129819.0, 81.0000),
    ("罗氏虾",   43014, 5505792.0, 0.0000),   # 卡在, has_price_data = FALSE
    ("娃娃菜",   42426,  933372.0, None),     # 没有成本卡行
    ("凉拌木耳", 43128,  776304.0, None),
    ("酸梅汤",   43397,  520764.0, None),
]

CARDED_NAMES = {"鲈鱼", "水煮牛肉", "藤椒鸡", "干锅花菜", "红糖糍粑", "米饭"}
UNCARDED_NAMES = {"罗氏虾", "娃娃菜", "凉拌木耳", "酸梅汤"}


def _pos_df():
    return pd.DataFrame(
        [{"商品名称": n, "数量": q, "实收额": r} for n, q, r, _ in MOCK_REST_MEASURED]
    )


def _sku_costs(zero_shape: str) -> dict:
    """`sku_food_costs` 是**总额**字典(见 handler 的既有 fixture), 所以乘 qty。

    `zero_shape` 模拟上游对「卡在但没进价」的两种写法 —— 产品结论**不许**
    因为这个选择而不同。
    """
    out = {}
    for name, qty, _rev, unit in MOCK_REST_MEASURED:
        if unit is None:
            continue
        if unit == 0.0 and zero_shape == "ABSENT":
            continue
        out[name] = unit * qty
    return out


def _run(zero_shape: str):
    resp = MenuEngineeringHandler().compute(
        SectionRequest(factory_id="MOCK_REST", upload_id=None, sub_sector="川菜"),
        context={"pos_df": _pos_df(), "sku_food_costs": _sku_costs(zero_shape)},
    )
    assert resp.status == SectionStatus.OK
    return resp.data


# ══ ① 没有成本卡的菜不再被分类, 且被说出来 ═══════════════════════════════


@pytest.mark.parametrize("zero_shape", ["ABSENT", "ZERO"])
def test_uncosted_dishes_never_enter_the_quadrants(zero_shape):
    data = _run(zero_shape)
    classified = {c["name"] for c in data["classifications"]}
    assert classified == CARDED_NAMES, "只有能真的算出毛利的菜才许进象限"

    in_quadrants = {
        item["name"] for bucket in data["quadrants"].values() for item in bucket
    }
    assert in_quadrants == CARDED_NAMES, "quadrants 是给前端画图的, 同样不许混进来"


@pytest.mark.parametrize("zero_shape", ["ABSENT", "ZERO"])
def test_uncosted_dishes_are_listed_with_a_reason(zero_shape):
    data = _run(zero_shape)
    excluded = {e["name"]: e for e in data["excluded"]}
    assert set(excluded) == UNCARDED_NAMES, "算不出的菜必须**被列出来**, 不是消失"
    for name, e in excluded.items():
        assert e["reason"] in {r.value for r in ExclusionReason}
        assert e["reasonText"], f"{name} 没有给出人话的原因"


@pytest.mark.parametrize("zero_shape", ["ABSENT", "ZERO"])
def test_excluded_dishes_report_none_not_zero(zero_shape):
    """⛔「算不出」和「真的是 0」在下游必须长得不一样。

    本仓栽过: 消费方拿不到条目就落到 `0.0` 默认值, 两者从此不可区分。
    """
    for e in _run(zero_shape)["excluded"]:
        for key in ("foodCost", "marginRatio", "quadrant"):
            assert e[key] is None, f"{e['name']}.{key} 是 {e[key]!r}, 应该是 None"


@pytest.mark.parametrize("zero_shape", ["ABSENT", "ZERO"])
def test_the_prose_and_the_machine_readable_side_say_the_same_thing(zero_shape):
    """产出侧和正文必须说同一件事 —— 本仓踩过只改正文而结构侧照旧发 0。"""
    data = _run(zero_shape)
    summary = data["excludedSummary"]
    assert summary["count"] == len(data["excluded"])
    assert summary["revenue"] == pytest.approx(
        sum(e["revenue"] for e in data["excluded"]))

    prose = "\n".join(data["recommendations"])
    for e in data["excluded"]:
        assert e["name"] in prose, f"{e['name']} 只在结构里, 正文没提"
    for slot in summary["byReason"].values():
        assert slot["text"] in prose, "正文和 byReason 用的不是同一句话(会漂)"

    cov = data["coverage"]
    assert cov["dishCount"] == len(data["classifications"])
    assert cov["totalDishCount"] == len(data["classifications"]) + len(data["excluded"])
    assert cov["revenueRatio"] == pytest.approx(0.6555, abs=1e-4), "实测 65.55%"


def test_the_same_unknown_yields_the_same_verdict_however_upstream_encodes_it():
    """🔴 这是本文件最重要的一条。

    改动前实测: 罗氏虾(营收 ¥5,505,792, 占该租户 24.5%)
      上游写成「不在字典里」-> ratio 被编成 0.6000 -> dog  -> 正文「建议淘汰」
      上游写成「0.0」        -> ratio 变成 1.0000 -> puzzle-> 正文「建议推广」
    同一个「我不知道」, 结论**正好相反**, 而差别只是上游的一个编码习惯。
    """
    a, z = _run("ABSENT"), _run("ZERO")
    assert {c["name"]: c["quadrant"] for c in a["classifications"]} == \
           {c["name"]: c["quadrant"] for c in z["classifications"]}
    assert a["marginMedian"] == z["marginMedian"]
    assert a["popularityMedian"] == z["popularityMedian"]
    assert a["coverage"] == z["coverage"]


def test_the_two_encodings_still_give_different_REASONS():
    """阴性对照: 上面那条不许是靠「把两种输入抹成一样」达成的。

    结论必须相同, 而**补救动作不同** —— 没建卡 vs 建了卡但食材没进价,
    是两件要做的事。⛔ 合成一句「数据不全」等于告诉老板「你去查吧」。
    """
    a = {e["name"]: e["reason"] for e in _run("ABSENT")["excluded"]}
    z = {e["name"]: e["reason"] for e in _run("ZERO")["excluded"]}
    assert a["罗氏虾"] == ExclusionReason.NO_COST_CARD.value
    assert z["罗氏虾"] == ExclusionReason.COST_CARD_WITHOUT_PRICE.value
    assert EXCLUSION_TEXT[ExclusionReason.NO_COST_CARD] != \
        EXCLUSION_TEXT[ExclusionReason.COST_CARD_WITHOUT_PRICE]


def test_no_dish_is_ever_assigned_the_fabricated_sixty_percent_margin():
    """阴性对照, 直接打在被删掉的那个行为上。

    `revenue * 0.4` 会让每一道被兜底的菜的 marginRatio **恰好**等于 0.6。
    改动前实测: 4 道菜同时是 0.6000, 于是 marginMedian 也被拖到 0.6 ——
    切分线本身变成那个编造的常数。
    """
    for zero_shape in ("ABSENT", "ZERO"):
        data = _run(zero_shape)
        ratios = [c["marginRatio"] for c in data["classifications"]]
        assert ratios.count(0.6) == 0, "0.6 是兜底的指纹, 不该再出现"
        assert data["marginMedian"] != pytest.approx(0.6, abs=1e-9)


# ══ ② 阳性对照: 有成本卡的菜, 判法没被这次改动碰过 ═══════════════════════


def _carded_only_df():
    return pd.DataFrame([
        {"name": n, "sold_qty": q, "revenue": r, "food_cost": u * q}
        for n, q, r, u in MOCK_REST_MEASURED if n in CARDED_NAMES
    ])


#: 改动**之前**的 analyzer 在恰好这 6 道菜上的输出, 从 HEAD~ 的实现跑出来。
#: ⛔ 不是手算的期望值 —— 手算的期望值只能证明我会算数, 证明不了行为没变。
_PRE_CHANGE_ASSIGNMENT = {
    "鲈鱼": "cash_cow",
    "水煮牛肉": "cash_cow",
    "藤椒鸡": "puzzle",
    "干锅花菜": "star",
    "红糖糍粑": "puzzle",
    "米饭": "dog",
}
_PRE_CHANGE_POPULARITY_MEDIAN = 52393.0


def test_positive_control_carded_dishes_are_judged_exactly_as_before():
    """给定同一个输入集, 判法逐字不变(见模块 docstring 为什么是这个形状)。"""
    report = MenuEngineeringAnalyzer().analyze(_carded_only_df())
    assert {c.name: c.quadrant.value for c in report.classifications} == \
        _PRE_CHANGE_ASSIGNMENT
    assert report.popularity_median == _PRE_CHANGE_POPULARITY_MEDIAN
    assert report.excluded == [], "这 6 道都有成本, 不该有人被排除"
    assert report.comparable_sufficient is True


def test_positive_control_is_not_vacuous():
    """阳性对照本身要能红 —— 否则它守的是空气。

    ⚠️ 实测教训: 第一版拿 `summary` 计数做这个检查, 读出「没变化」——
       而真相是**计数是归属的有损投影**, 归属确实变了两处。
    """
    df = _carded_only_df()
    df.loc[df["name"] == "米饭", "food_cost"] = 1.0
    mutated = MenuEngineeringAnalyzer().analyze(df)
    assert {c.name: c.quadrant.value for c in mutated.classifications} != \
        _PRE_CHANGE_ASSIGNMENT, "变异没有改变归属 ⇒ 上面那条断言什么都没守"


def test_the_output_contract_only_grew():
    """⛔ 不许删条目 —— 消费方按 key 精确相等地读, 删掉就落到默认值。"""
    data = _run("ZERO")
    for key in ("classifications", "popularityMedian", "marginMedian",
                "summary", "recommendations", "quadrants"):
        assert key in data, f"既有的 {key} 被删了"
    assert set(data["quadrants"]) == {"star", "cash_cow", "puzzle", "dog"}


# ══ 【2】基数守卫 ════════════════════════════════════════════════════════


@pytest.mark.parametrize("n", [0, 1, 2, 3])
def test_median_split_refuses_below_the_minimum_comparable_count(n):
    """先过滤再取中位数的地方, 必须守**过滤后的**基数。

    本仓刚踩过: 两条各自正确的剔除规则叠加后把比较集缩到 1 个元素,
    于是 max 和 min 返回同一个 —— 「最高月 2026-07 / 最低月 2026-07」。
    """
    rows = [
        {"name": f"菜{i}", "sold_qty": 100 + i, "revenue": 1000.0 + i,
         "food_cost": 400.0}
        for i in range(n)
    ]
    rows.append({"name": "没卡的", "sold_qty": 50, "revenue": 500.0,
                 "food_cost": None})
    report = MenuEngineeringAnalyzer().analyze(pd.DataFrame(rows))

    assert report.comparable_sufficient is False
    assert report.classifications == [], "基数不够时不许硬切一刀"
    assert report.summary["total_items"] == 0
    assert "比不出来" in report.recommendations[0]
    assert str(n) in report.recommendations[0], "要说清可比的只有几道"


def test_min_comparable_boundary_is_live_on_both_sides():
    """阳性对照: 恰好到达门槛时**必须**能分类, 否则守卫是个恒真的拒答。"""
    rows = [
        {"name": f"菜{i}", "sold_qty": 100 + i * 10,
         "revenue": 1000.0 + i * 100, "food_cost": 300.0 + i * 50}
        for i in range(MIN_COMPARABLE_ITEMS)
    ]
    report = MenuEngineeringAnalyzer().analyze(pd.DataFrame(rows))
    assert report.comparable_sufficient is True
    assert len(report.classifications) == MIN_COMPARABLE_ITEMS


def test_zero_revenue_dish_is_excluded_not_divided_by_a_substituted_one():
    """旧版把分母 0 悄悄换成 1(`replace(0, 1)`) —— 又一个把「不知道」编成数。"""
    rows = [
        {"name": f"菜{i}", "sold_qty": 100 + i * 10,
         "revenue": 1000.0 + i * 100, "food_cost": 300.0 + i * 50}
        for i in range(MIN_COMPARABLE_ITEMS)
    ]
    rows.append({"name": "白送的", "sold_qty": 7, "revenue": 0.0, "food_cost": 50.0})
    report = MenuEngineeringAnalyzer().analyze(pd.DataFrame(rows))

    assert "白送的" not in {c.name for c in report.classifications}
    excluded = {e.name: e.reason for e in report.excluded}
    assert excluded["白送的"] == ExclusionReason.NON_POSITIVE_REVENUE


def test_axis_spread_is_reported_so_a_flat_axis_is_visible():
    """⛔ 只报离散度, **不**据它自动隐藏象限(会误报的提示比没有提示更糟)。

    实测 MOCK_REST 有卡的 6 道菜销量 CV 只有 6.74%(最畅销的比最不畅销的只多卖
    22%) —— 那条「高销/低销」线切的基本是噪声。数摆出来, 判断留给读的人。
    """
    data = _run("ZERO")
    spread = data["comparable"]["axisSpread"]
    assert spread["popularity"]["cv"] == pytest.approx(0.0674, abs=1e-4)
    assert spread["popularity"]["flat"] is False
    assert data["comparable"]["classifiedCount"] == 6
    assert data["comparable"]["minRequired"] == MIN_COMPARABLE_ITEMS


def test_perfectly_flat_popularity_is_flagged():
    """阳性对照: `flat` 这个位真的能翻。"""
    rows = [
        {"name": f"菜{i}", "sold_qty": 100, "revenue": 1000.0 + i * 100,
         "food_cost": 300.0 + i * 50}
        for i in range(MIN_COMPARABLE_ITEMS)
    ]
    report = MenuEngineeringAnalyzer().analyze(pd.DataFrame(rows))
    assert report.axis_spread["popularity"]["flat"] is True
    assert report.axis_spread["marginRatio"]["flat"] is False


# ══ 【3】接线: 下游消费方不许再吃到编造的成本 ═════════════════════════════


def test_boss_decision_brief_does_not_ingest_excluded_dishes():
    """跑在**真实消费方**上, 不是我自己模拟一份。

    `BossDecisionBriefHandler._menu_item_metrics` 按 key 精确相等地从
    classifications / quadrants / topProducts / products / items / allProducts
    捞菜品指标, 并据 `foodCost` 推 `hasCost`。改动前每道菜都有一个(编造的)
    foodCost ⇒ 它对每道菜都报 hasCost=True。
    """
    from smartbi.services.restaurant.sections.boss_decision_brief import (
        BossDecisionBriefHandler,
    )

    metrics = BossDecisionBriefHandler._menu_item_metrics(_run("ZERO"))
    assert set(metrics) == CARDED_NAMES, (
        "算不出的菜漏进了下游 —— 说明我新加的键撞上了它精确匹配的那几个之一")
    assert all(m["hasCost"] for m in metrics.values())


def test_new_keys_do_not_collide_with_the_downstream_exact_match_list():
    """⛔ 新键不许叫 items / products / topProducts / allProducts /
    classifications —— 那几个是下游按字面捞的桶。"""
    data = _run("ZERO")
    poached = {"items", "products", "topProducts", "allProducts"}
    assert poached.isdisjoint(set(data)), f"新键撞车: {poached & set(data)}"
