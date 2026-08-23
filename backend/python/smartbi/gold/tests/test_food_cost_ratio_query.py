"""Production regressions for daypart synonyms and period food-cost ratio."""
from __future__ import annotations

import asyncio
from datetime import date

import pytest

from smartbi.gold.restaurant.restaurant_intent import (
    _detect_requested_metrics,
    _is_daypart_business_query,
    _is_food_cost_ratio_query,
    _semantic_spec_from_t3,
)
from smartbi.gold.restaurant.restaurant_ops_router import resolve_recipe_cost


def _t3_payload(**overrides):
    payload = {
        "intent": "RESTAURANT_OPS_SALES_SUMMARY",
        "time_range": None,
        "wants_margin": False,
        "asks_profitability": False,
        "requested_metrics": ["recipe_cost", "revenue"],
        "analysis_action": "lookup",
        "dimensions": ["ingredient"],
        "dish": None,
        "store": None,
        "stores": [],
        "store_scope": "all",
        "confidence": 0.96,
        "clarification_needed": False,
        "missing_fields": [],
        "clarification_question": None,
        "clarification_options": [],
    }
    payload.update(overrides)
    return payload


@pytest.mark.parametrize("query", [
    "全部门店最近30天食材成本占营收多少",
    "过去30天全店食材成本率是多少",
    "本月原料成本占营业额的比例",
])
def test_all_store_food_cost_ratio_is_one_atomic_metric(query):
    assert _is_food_cost_ratio_query(query) is True
    assert _detect_requested_metrics(query) == ("recipe_cost",)


@pytest.mark.parametrize("query", [
    "罗氏虾的食材成本占营收多少",
    "哪个菜的食材成本最高",
    "最近30天总营收是多少",
])
def test_named_or_unrelated_questions_do_not_enter_period_ratio(query):
    assert _is_food_cost_ratio_query(query) is False


def test_t3_food_cost_ratio_repairs_adjacent_composite_plan():
    spec = _semantic_spec_from_t3(
        _t3_payload(),
        "全部门店最近30天食材成本占营收多少",
    )

    assert spec.intent == "RESTAURANT_OPS_RECIPE_COST"
    assert spec.planned_intents == ("RESTAURANT_OPS_RECIPE_COST",)
    assert spec.requested_metrics == ("recipe_cost",)
    assert spec.dimensions == ()
    assert spec.planner_authority == "llm_contract_repair"
    assert spec.clarification_needed is False


def test_business_revenue_synonym_routes_to_daypart_performance():
    query = "过去30天午市、下午茶、晚市和夜宵哪个时段营业额最高"
    assert _is_daypart_business_query(query) is True
    spec = _semantic_spec_from_t3(
        _t3_payload(
            intent="RESTAURANT_OPS_TREND_ANALYSIS",
            requested_metrics=["revenue"],
            dimensions=["time"],
        ),
        query,
    )
    assert spec.intent == "RESTAURANT_OPS_DAYPART_PERFORMANCE"
    assert spec.planned_intents == ("RESTAURANT_OPS_DAYPART_PERFORMANCE",)
    assert spec.planner_authority == "llm_contract_repair"


class _Conn:
    def __init__(self, row):
        self.row = row
        self.execute_calls = []
        self.fetchrow_calls = []

    async def execute(self, sql, *args):
        self.execute_calls.append((sql, args))
        return "SELECT 1"

    async def fetchrow(self, sql, *args):
        self.fetchrow_calls.append((sql, args))
        return self.row


class _Pool:
    def __init__(self, row):
        self.conn = _Conn(row)

    def acquire(self):
        conn = self.conn

        class _Context:
            async def __aenter__(self):
                return conn

            async def __aexit__(self, *_exc):
                return False

        return _Context()


def test_no_revenue_at_all_still_says_the_three_things():
    """🔴 第三个分支此前一条断言都没有 —— 它照样要说清三件事。

    ⚠️ 这一支的「他要干什么」不是盘点（连营业额都没有，盘点也算不出来），
       是**去确认收银数据同步**。⛔ 三件事的内容按分支变，不是同一句话。
    """
    answer = asyncio.run(resolve_recipe_cost(
        _Pool({
            "revenue": 0.0,
            "transaction_count": 0,
            "food_cost": None,
            "line_count": 0,
            "source_types": None,
            "categories": None,
        }),
        "MOCK_REST",
        date_range=(date(2026, 7, 10), date(2026, 8, 8)),
        window_label="最近30天",
        food_cost_ratio=True,
    ))
    text = answer.answer_text
    assert answer.meta["no_pos_data"] is True
    assert "算不出来" in text, text
    assert "收银" in text, f"没说清是哪一侧没有\n{text}"
    assert "同步" in text, f"没说他要干什么\n{text}"
    assert "凑一个数" in text, f"没说明不会降级\n{text}"
    # ⛔ 阴性对照：这一支不该谈盘点 —— 连营业额都没有，盘了也算不出来
    assert "各盘一次库" not in text, f"没有营业额却让他去盘库\n{text}"
    for jargon in ("净营收事实", "同口径", "用同一种算法"):
        assert jargon not in text, f"黑话漏给了店长: {jargon}\n{text}"


#: 「这段时间领了多少料的钱」—— 今天**真的算得出来**的那个替代数。
#: prod 实测(MOCK_REST, 2026-07-21~08-19): ¥7,790,565.73 / 29 天有数。
FOOD_COST_RATIO_SUBSTITUTE_ROW = {
    "revenue": 21_497_651.2,
    "transaction_count": 60_000,
    "food_cost": None,
    "line_count": 0,
    "source_types": None,
    "categories": None,
    "req_cost": 7_790_565.73,
    "req_days": 29,
    # prod 实测: 近 30 天窗口内 4 个盘点日全部 COMPLETED
    "stocktaking_days": 4,
}


def _ratio_branch(**row_overrides):
    row = dict(FOOD_COST_RATIO_SUBSTITUTE_ROW)
    row.update(row_overrides)
    return asyncio.run(resolve_recipe_cost(
        _Pool(row),
        "MOCK_REST",
        date_range=(date(2026, 7, 10), date(2026, 8, 8)),
        window_label="最近30天",
        food_cost_ratio=True,
    ))


def test_missing_period_food_cost_facts_fail_closed_without_recipe_ranking():
    """⛔ 说了算不出来就不许再拿配方成本凑一个数。

    🔴 2026-08-18 这几条断言守的是**字面**，而那几个字面正是要消灭的黑话。
       形态 C‴：断言好好的，是**需求变了** —— 症状是**红得理直气壮**。
       ⛔ 改断言最容易滑成删断言：把守的东西从字面抬到**性质**，并补阴性对照。
    """
    answer = _ratio_branch()
    text = answer.answer_text
    assert answer.meta["food_cost_fact_missing"] is True
    # ① 明确说了算不出来（⛔ 不是给个 0 或拿别的数凑）
    assert "算不出来" in text, text
    assert "菜品食材成本前" not in text
    # ② 说清缺的是什么，且点明配方成本 ≠ 实际耗用
    assert "实际用掉" in text, text
    # 🔴 阴性对照：数仓黑话不许回来（「事实」在这里是 fact table 的意思）
    for jargon in ("净营收事实", "食材成本事实", "理论成本快照", "同口径",
                   "用同一种算法"):
        assert jargon not in text, f"黑话漏给了店长: {jargon}\n{text}"


def test_it_does_not_send_him_to_count_stock_because_that_will_not_help():
    """🔴 2026-08-19 prod 取证：这一支原来的「出路」是**假的**，两条实测各打掉一半。

    (a) 已被现状证伪：MOCK_REST 的 `fact_restaurant_stocktaking` 有 **1,970 行、
        8 个盘点日全部 COMPLETED**（近 30 天窗口内 4 个），而 `fact_cost_line`
        **仍是 0 行**（全库所有租户都是 0）。
        ▎老板照那句话去做，**已经做完了，什么也没拿到**。
    (b) 结构上也不可能：`fact_restaurant_stocktaking` 16 列里没有**库存金额** ——
        只有 system_qty / actual_qty / difference_qty / difference_cost。
        「期初库存 ＋ 进货 − 期末库存」这条公式**两端都没有地方记**。
    (c) 「进货金额系统里已经有了」同样是假的：`agg_restaurant_daily_ops` 里
        purchase_amount / purchase_cost / inbound_amount **各 0 行**。

    ⇒ 形态 D′：**排在最前面的那个命中，他去查会一无所获** ——
      一个会误报的提示比没有提示更糟，它烧掉的是「敢说不准」的全部本钱。
    """
    text = _ratio_branch().answer_text
    for lie in ("各盘一次库", "盘点数据进了系统", "进货金额系统里已经有了",
                "头和尾各盘一次"):
        assert lie not in text, f"假承诺回来了: {lie}\n{text}"
    # ⛔ 不是删掉就完了 —— 得告诉他「盘了也补不上」，否则他还是会自己去盘
    assert "差额" in text, f"没说清盘点记的是盘盈亏差额而不是库存总金额\n{text}"
    assert "盘了 4 次" in text, f"盘点次数没有由数据说\n{text}"
    # ④ 「他自己要干什么」这一支的正确答案是**眼下没有**（形态 A⁹ 的 (b) 结构性）
    #    owner 已挂账：说清代价让他选，⛔ 不推着他去做。
    assert "不用你" in text, f"没说清这一栏不用他操作\n{text}"


def test_it_gives_the_number_it_can_actually_compute_today():
    """「部分能做」⇒ 能做的先给。⛔ 不因为一半不行就整句作废。

    📏 prod 实测(MOCK_REST, 2026-07-21~08-19): 领料成本 ¥7,790,565.73 /
       营收 ¥20,782,887.14 = 37.49% —— 这个数今天真的算得出来，而修前一个字没提。
    """
    answer = _ratio_branch()
    text = answer.answer_text
    ratio = 7_790_565.73 / 21_497_651.2 * 100
    assert f"{ratio:.2f}%" in text, f"没给出今天算得出来的那个数\n{text}"
    assert "7,790,565.73" in text, text
    # 机器可读侧也要有 —— ⛔ 数字只在正文里 = 下游拿不到
    assert answer.meta["requisition_cost"] == 7_790_565.73
    assert round(answer.meta["requisition_ratio_pct"], 2) == round(ratio, 2)


def test_the_substitute_is_never_passed_off_as_the_food_cost_ratio():
    """🔴 顶替防线：给替代数的那一刻，就是最容易把它说成正主的那一刻。

    两条，⛔ 缺一条都拦不住:
      ① 抬头的限定语在（「不是实际用掉」）
      ② **带百分比的那一行自己**要说这是「领」的料 ——
         ⛔ 只断言「领」出现在全文里不够: 限定语在开头、数字在十行之后，
            老板扫读时看到的是那一行。
    """
    answer = _ratio_branch()
    text = answer.answer_text
    ratio_mark = "%.2f%%" % (7_790_565.73 / 21_497_651.2 * 100)
    assert "不是实际用掉" in text, f"限定语没了\n{text}"
    carrying = [ln for ln in text.splitlines() if ratio_mark in ln]
    assert carrying, f"没有任何一行带那个百分比\n{text}"
    for ln in carrying:
        assert "领" in ln, f"带百分比的那一行没说这是领的料，会被读成食材成本率:\n{ln}"
    # ⛔ 它不许自称食材成本率
    assert "食材成本率 **%s" % ratio_mark not in text, text
    assert "食材成本率是 %s" % ratio_mark not in text, text


def test_no_requisition_data_means_no_percentage_at_all():
    """三态，⛔ 不是两态：没有领料数据时**一个百分比都不许出现**。

    形态 A¹⁰：兜底的默认值会把「我不知道」翻译成「是 0」，
    而「领料占营收 0.00%」会被老板读成「这段时间几乎没成本」——
    那比不给数更糟。`SUM(...)` 空集给 NULL（⛔ 不是 0），桩按真实上游的形状喂。
    """
    answer = _ratio_branch(req_cost=None, req_days=0)
    text = answer.answer_text
    assert "算不出来" in text, text
    assert "%" not in text, f"没有领料数据却出现了百分比\n{text}"
    assert "0.00" not in text, f"把「我不知道」翻译成了「是 0」\n{text}"
    assert answer.meta.get("requisition_cost") is None
    assert answer.meta.get("requisition_ratio_pct") is None
    # 阳性对照：这一支照样要说清算不出来和缺什么，⛔ 不是整段消失
    assert "实际用掉" in text, text


def test_a_tenant_that_never_counted_stock_is_not_told_it_already_did():
    """⛔「你已经盘过库了」对一个从没盘过库的租户是**假话**。

    形态 D′：一个会误报的提示比没有提示更糟 —— 它烧掉的是「敢说不准」的全部本钱。
    ⚠️ 这一条是本轮**自己的修复**上差点犯的错：第一版把这句话写死了，
       在 MOCK_REST（4 个盘点日）上读起来完全正常 ——
       **验收环境恰好不含能让它红的那种输入**（形态 B⁴）。
    """
    counted = _ratio_branch(stocktaking_days=4).answer_text
    never = _ratio_branch(stocktaking_days=0).answer_text
    assert "你已经盘过库了" in counted, counted
    assert "盘了 4 次" in counted, counted
    # ⛔ 没盘过的租户: 不许说他盘过, 也不许出现一个次数
    assert "你已经盘过库了" not in never, never
    assert "盘了 0 次" not in never, never
    # 但两边都要挡住「他自己跑去盘库」这个错误动作
    for text in (counted, never):
        assert "差额" in text, f"没说清盘点记的是差额\n{text}"
        assert "不用你" in text, text


def test_registered_period_facts_are_reported_as_reference_not_true_dish_margin():
    answer = asyncio.run(resolve_recipe_cost(
        _Pool({
            "revenue": 1_000_000.0,
            "transaction_count": 2_500,
            "food_cost": 250_000.0,
            "line_count": 30,
            "source_types": ["accounting_import"],
            "categories": ["食材成本"],
        }),
        "RESTAURANT_TEST",
        date_range=(date(2026, 7, 1), date(2026, 7, 30)),
        window_label="最近30天",
        food_cost_ratio=True,
    ))

    # 同上（形态 C‴）：「参考比率」「单菜真实毛利率」是黑话字面，
    # 守的**性质**是「这个数只能当参考」和「它不是单道菜的毛利率」。
    text = answer.answer_text
    assert "25.00%" in text
    assert "只能当参考看" in text, text
    assert "不是单道菜的毛利率" in text, text
    assert "不是拿配方成本推出来的" in text, text
    for jargon in ("参考比率", "净营收事实", "同口径", "用同一种算法", "快照"):
        assert jargon not in text, f"黑话漏给了店长: {jargon}\n{text}"
    assert answer.meta["food_cost_source_types"] == ["accounting_import"]
