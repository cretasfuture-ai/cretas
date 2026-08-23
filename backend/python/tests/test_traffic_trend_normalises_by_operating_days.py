# -*- coding: utf-8 -*-
"""归因七步链第 ③ 步（客流再拆）与第 ⑤ 步（反问现实事件）。

## ③ 守的那一件事：⛔ 不许把「窗口覆盖」读成「经营变化」

📏 决定性读数（prod `smartbi_prod_db`，MOCK_REST，2026-08-18 实测）：

    current 2026-07-20~08-18   30/30 天有数据   打浦桥店 5,978 单
    prev    2026-06-20~07-19   21/30 天有数据   打浦桥店 4,200 单
                               ^^^^^^^ 该租户 agg_daily 从 2026-06-29 才开始

    比总额   (5978-4200)/4200 = **+42.3%**   ← 「客流暴涨」
    按天折算 199.3/天 vs 200.0/天 = **-0.4%** ← 真相：持平

▎两个数一个说暴涨一个说持平。**同一批数据，差别只在除不除以天数。**
⚠️ 而生产的 `period_comparison` 今天就在比总额 —— 实测对这个租户报
   `revenue.mom_pct = +42.0`。那条路本轮没动（登记在设计卡第五节），
   但归因这条路上第 ③ 步**从它诞生起就必须按天折算**，否则当天多一个假信号。

## ⑤ 守的那一件事：⛔ 不编事件、不对噪声发问

⑤a 盲区披露（跟着 ⑥ 走，不点名）— 它一个字都不断言，所以不存在「他去查一无所获」。
⑤b 具名反问（③ 读出真的掉了才有）— 📏 MOCK_REST 全店落在 -0.9%~-0.8%，
    阈值 -10% ⇒ **在唯一有数据的租户上恒不触发**。
⚠️ 形态 B⁴：所以「prod 上没触发」⛔ 不能证明这道判据是对的 ——
   它能不能红由本文件的**两侧**断言（-9.9% 不触发 / -10.1% 触发）证明。

## 📏 fixture 的来历（⛔ 不是随手编的数）

门店行是 MOCK_REST prod 2026-08-18 实拍：current 窗 10 家的营收/单量/天数，
prev 窗同样 10 家。⛔ 不换成整齐的数字 —— 「30 天 vs 21 天」这个形状正是被测的那一种。
"""
from __future__ import annotations

import ast
import inspect

import pytest

from smartbi.agent import synthesis_engine as se
from smartbi.agent.factbook import (
    MIN_DAYS_FOR_TREND,
    TRAFFIC_DROP_ASK_THRESHOLD_PCT,
    FactBook,
    compute_store_attribution,
    compute_traffic_trend,
    traffic_drop_warrants_asking,
)
from smartbi.agent.synthesis_engine import (
    _INVESTIGATION_LAST_RESORT,
    _REALITY_CHECK_BLIND_SPOT,
    ComprehensiveSynthesisEngine,
    _previous_equal_window,
    _reality_check_line,
)

_APPEND = ComprehensiveSynthesisEngine._append_investigation_directions

#: 📏 MOCK_REST prod 2026-07-20~08-18 实拍：(门店, 营收, 单量)。30/30 天有数据。
_CUR = (
    ("模拟·徐汇美罗城店", 2176848.82, 5978),
    ("模拟·陆家嘴正大店", 2161328.70, 5978),
    ("模拟·普陀真如社区店", 2160709.90, 5975),
    ("模拟·宝山大场社区店", 2159919.45, 5975),
    ("模拟·闵行莘庄社区店", 2156244.05, 5975),
    ("模拟·长宁龙之梦店", 2148038.46, 5978),
    ("模拟·静安嘉里中心店", 2147069.23, 5978),
    ("模拟·浦东金桥社区店", 2133285.23, 5975),
    ("模拟·杨浦五角场店", 2119665.47, 5978),
    ("模拟·打浦桥日月光店", 2119088.52, 5978),
)
_CUR_DAYS = 30

#: 📏 同一次读数的前一个等长窗 2026-06-20~07-19。**只有 21/30 天有数据**。
_PREV = (
    ("模拟·闵行莘庄社区店", 1537613.00, 4200),
    ("模拟·静安嘉里中心店", 1534597.63, 4200),
    ("模拟·打浦桥日月光店", 1528173.43, 4200),
    ("模拟·浦东金桥社区店", 1523885.22, 4200),
    ("模拟·宝山大场社区店", 1517940.23, 4200),
    ("模拟·长宁龙之梦店", 1510092.35, 4200),
    ("模拟·杨浦五角场店", 1500453.09, 4200),
    ("模拟·陆家嘴正大店", 1498473.34, 4200),
    ("模拟·普陀真如社区店", 1493702.35, 4200),
    ("模拟·徐汇美罗城店", 1488453.74, 4200),
)
_PREV_DAYS = 21

_LAGGARD = "模拟·打浦桥日月光店"


def _rows(stores, days):
    return [{"name": n, "revenue": r, "orderCount": b, "daysWithData": days}
            for n, r, b in stores]


def _trend(cur=_CUR, cur_days=_CUR_DAYS, prev=_PREV, prev_days=_PREV_DAYS,
           laggard=_LAGGARD):
    return compute_traffic_trend(
        _rows(cur, cur_days), _rows(prev, prev_days), laggard_name=laggard)


def _laggard_of(trend):
    return trend["laggard"]


def _prompt_block(factbook, heading="## 客流趋势"):
    """prompt 里那一段，到**空行**为止。

    ⛔ 不切固定行数 —— 那会把下一段的数字算进来，
       于是「这一段里没有数字」这条断言可能红在别人的数上。
    """
    lines = factbook.to_prompt_lines()
    start = next(i for i, ln in enumerate(lines) if ln.startswith(heading))
    block = []
    for ln in lines[start:]:
        if ln == "" and block:
            break
        block.append(ln)
    return "\n".join(block)


class TestTheFixtureReallyReproducesProd:
    """🔴 阳性对照：没有这条，下面每一条断言都可能在测一个我自己编的形状。"""

    def test_the_two_windows_really_have_different_coverage(self):
        assert _CUR_DAYS == 30 and _PREV_DAYS == 21, (
            "fixture 的两侧天数必须不同 —— 相同的话本文件最重要的那条断言变成恒真式")

    def test_raw_totals_really_do_say_the_traffic_exploded(self):
        """⛔ 先证明「比总额」真的会读出 +42% —— 否则下面那条断言没有对手。"""
        cur = dict((n, b) for n, _r, b in _CUR)[_LAGGARD]
        prev = dict((n, b) for n, _r, b in _PREV)[_LAGGARD]
        raw = 100.0 * (cur - prev) / prev
        assert raw > 40.0, f"比总额应该读出 +42% 量级，实际 {raw:.1f}%"

    def test_the_laggard_is_who_prod_says_it_is(self):
        att = compute_store_attribution(_rows(_CUR, _CUR_DAYS))
        assert (att["laggard"]["store_name"]) == _LAGGARD
        assert att["primary_cause"] == "客单价"


class TestStep3NormalisesByOperatingDays:
    """③ 的核心：按营业天数折算，⛔ 不比总额。"""

    def test_partial_coverage_does_not_read_as_growth(self):
        lag = _laggard_of(_trend())
        assert lag["bills_pct"] == pytest.approx(-0.4, abs=0.1), (
            "按天折算应该读出「持平」，读出别的说明分母没除天数")
        assert lag["bills_total_pct"] == pytest.approx(42.3, abs=0.2)
        assert lag["bills_pct"] < 0 < lag["bills_total_pct"], (
            "🔴 这两个数必须**方向相反** —— 这正是 ③ 存在的全部理由")

    def test_equal_coverage_collapses_to_one_number(self):
        """对照：天数相同时，按天和按总额必然相等，且不再提天数。"""
        trend = _trend(prev_days=_CUR_DAYS)
        lag = _laggard_of(trend)
        assert lag["bills_pct"] == pytest.approx(lag["bills_total_pct"], abs=0.05)
        assert trend["days_differ"] is False

    def test_day_counts_are_carried_in_the_output(self):
        """⛔ 不许只给百分比 —— 老板要自己判断那 9 天是关店还是没上传。"""
        lag = _laggard_of(_trend())
        assert lag["current_days"] == 30 and lag["previous_days"] == 21

    def test_per_day_values_are_reported_not_only_the_ratio(self):
        lag = _laggard_of(_trend())
        assert lag["bills_per_day"] == pytest.approx(199.3, abs=0.1)
        assert lag["prev_bills_per_day"] == pytest.approx(200.0, abs=0.1)


class TestStep3RefusesInsteadOfFabricating:
    """三态：`ok` / `not_comparable` / `no_data`。⛔ 「没量到」不许折叠进「没变化」。"""

    def test_empty_previous_window_is_not_comparable_not_minus_100(self):
        trend = compute_traffic_trend(_rows(_CUR, _CUR_DAYS), [], laggard_name=_LAGGARD)
        assert trend["status"] == "not_comparable"
        assert "-100" not in repr(trend), "⛔ 上一周期没数据时绝不许算成 -100%"
        assert trend["laggard"] is None

    def test_too_few_days_is_not_comparable(self):
        trend = _trend(prev_days=MIN_DAYS_FOR_TREND - 1)
        assert trend["status"] == "not_comparable"
        assert str(MIN_DAYS_FOR_TREND) in trend["reason"]

    def test_just_enough_days_is_comparable(self):
        """⛔ 阴性断言要配阳性 —— 否则 `not_comparable` 可能是恒真的。"""
        assert _trend(prev_days=MIN_DAYS_FOR_TREND)["status"] == "ok"

    def test_empty_current_window_is_no_data(self):
        assert compute_traffic_trend([], _rows(_PREV, _PREV_DAYS))["status"] == "no_data"

    def test_a_store_absent_from_the_previous_window_is_singled_out(self):
        """⛔ 一侧取不到时单列一栏，绝不用另一侧顶替。"""
        trend = _trend(prev=_PREV[:-1])   # 少了徐汇美罗城
        missing = [s for s in trend["stores"] if s["status"] == "not_comparable"]
        assert [s["store_name"] for s in missing] == ["模拟·徐汇美罗城店"]
        assert "bills_pct" not in missing[0], "⛔ 算不出来就不许给一个百分比"


class TestStep3ReachesThePrompt:
    """机制在 ≠ 接上了（形态 B）。这几条跑在真实渲染出口上。"""

    def _fb(self, trend):
        fb = FactBook()
        fb.attribution = compute_store_attribution(_rows(_CUR, _CUR_DAYS))
        fb.traffic_trend = trend
        return fb

    def test_the_day_counts_are_not_in_the_prompt(self):
        """🔴 天数属于**给老板的那一段**，⛔ 不属于 prompt。

        ⚠️ 这条断言的前一版写反了（当时天数在 prompt 里）。改它的理由不是
           「测试碍事」，是 prod A/B 证明了那个设计会让模型多说一个错数
           —— 需求变了，不是断言坏了（形态 C‴）。
           天数**必须给老板**这个要求由
           `TestStep3IsRenderedDeterministicallyNotNarrated` 那组钉着。
        """
        block = _prompt_block(self._fb(_trend()))
        assert "30 天" not in block and "21 天" not in block

    def test_not_comparable_is_rendered_never_silent(self):
        """沉默会被模型读成「客流稳定」——那正是「没量到」被折叠成「没问题」。"""
        trend = compute_traffic_trend(_rows(_CUR, _CUR_DAYS), [])
        text = "\n".join(self._fb(trend).to_prompt_lines())
        assert "算不出客流趋势" in text
        assert "不是「客流没变化」" in text

    def test_no_data_renders_nothing(self):
        trend = compute_traffic_trend([], [])
        text = "\n".join(self._fb(trend).to_prompt_lines())
        assert "客流趋势" not in text

    def test_the_trend_numbers_are_grounded_facts(self):
        """派生数要被守住，唯一的办法是**给它一个名字** —— 进 facts_index。"""
        idx = self._fb(_trend()).to_facts_index()
        assert idx["拖后腿门店日均客流"] == pytest.approx(199.3, abs=0.1)
        assert idx["拖后腿门店上期日均客流"] == pytest.approx(200.0, abs=0.1)
        assert idx["拖后腿门店客流变化率"] == pytest.approx(-0.4, abs=0.1)

    def test_not_comparable_grounds_nothing(self):
        idx = self._fb(compute_traffic_trend(_rows(_CUR, _CUR_DAYS), [])).to_facts_index()
        assert "拖后腿门店客流变化率" not in idx


class TestPreviousWindowIsAnEqualCalendarSpan:
    def test_it_is_the_immediately_preceding_equal_span(self):
        from datetime import date
        assert _previous_equal_window((date(2026, 7, 20), date(2026, 8, 18))) == (
            date(2026, 6, 20), date(2026, 7, 19))

    def test_an_open_ended_range_yields_nothing(self):
        from datetime import date
        assert _previous_equal_window((None, date(2026, 8, 18))) is None
        assert _previous_equal_window((date(2026, 7, 20), None)) is None


class TestStep3IsRenderedDeterministicallyNotNarrated:
    """🔴 这一组是被一次 prod A/B **当场抓出来的回归**逼出来的。

    📏 第一版把「每天 199.3 单 / 上一周期每天 200.0 单」写进了 prompt：

        BEFORE 正文里「每天」出现 **0/3** 轮
        AFTER  出现 **3/3** 轮，其中 1 轮是
               「每天只比平均少卖 **29,131** 元」 ← ¥29,131 是 **30 天**的差额

    ▎我给了模型一个新词，它把这个词安到了另一个数上。
    ⇒ 数字改成确定性渲染，prompt 里只留结论词（⛔ 不留数）。
    """

    def _fb(self, trend):
        fb = FactBook()
        fb.attribution = compute_store_attribution(_rows(_CUR, _CUR_DAYS))
        fb.traffic_trend = trend
        return fb

    def test_the_prompt_carries_no_traffic_numbers_for_the_model_to_requote(self):
        """⛔ prompt 的客流趋势段里不许出现具体单量/百分比。"""
        block = _prompt_block(self._fb(_trend()))
        assert not any(ch.isdigit() for ch in block), (
            f"⛔ 客流趋势那一段不许出现任何数字，实际:\n{block}")

    def test_the_prompt_still_states_the_verdict(self):
        """⛔ 但不许什么都不说 —— 沉默会让模型拿横截面去答时间维的问题。"""
        text = "\n".join(self._fb(_trend()).to_prompt_lines())
        assert "没有明显下降" in text

    def test_the_prompt_says_dropped_when_it_dropped(self):
        """阳性对照：结论词必须真的会翻面，否则上一条是恒真的。"""
        prev = tuple((n, r, 4200) for n, r, _b in _PREV)
        cur = tuple((n, r, 3000 if n == _LAGGARD else b) for n, r, b in _CUR)
        trend = compute_traffic_trend(
            _rows(cur, _CUR_DAYS), _rows(prev, _PREV_DAYS), laggard_name=_LAGGARD)
        text = "\n".join(self._fb(trend).to_prompt_lines())
        assert "明显下降" in text and "没有明显下降" not in text

    def test_the_numbers_reach_the_boss_deterministically(self):
        out = ComprehensiveSynthesisEngine._append_traffic_trend("原答案。", self._fb(_trend()))
        assert "199.3" in out and "200.0" in out
        assert _LAGGARD in out

    def test_it_states_both_day_counts_and_what_the_raw_total_would_say(self):
        """老板要自己判断那 9 天是关店还是没上传 ⇒ 两个数都给他。"""
        out = ComprehensiveSynthesisEngine._append_traffic_trend("原答案。", self._fb(_trend()))
        assert "30 天" in out and "21 天" in out
        assert "+42.3%" in out, "⛔ 必须把「比总额会读成什么」摆出来，否则他无从判断"
        assert "系统分不出来" in out

    def test_the_block_uses_no_bold(self):
        """粗体在这些追加段里的约定是「维度名」——⛔ 别的东西不许加粗。

        ⚠️ 既有的 catalog 闸只扫 ⑥ 那一段，够不到这一段（硬约束 8：
           同一个约定的**所有位置**都要有闸）。
        """
        out = ComprehensiveSynthesisEngine._append_traffic_trend(
            "原答案。", self._fb(_trend()))
        assert "**" not in out.replace("原答案。", "")

    def test_no_day_note_when_coverage_matches(self):
        out = ComprehensiveSynthesisEngine._append_traffic_trend(
            "原答案。", self._fb(_trend(prev_days=_CUR_DAYS)))
        assert "天数不一样" not in out

    def test_not_comparable_says_so_instead_of_going_silent(self):
        trend = compute_traffic_trend(_rows(_CUR, _CUR_DAYS), [])
        out = ComprehensiveSynthesisEngine._append_traffic_trend("原答案。", self._fb(trend))
        assert "算不出来" in out
        assert "不等于「客流没变化」" in out

    def test_no_trend_appends_nothing(self):
        assert ComprehensiveSynthesisEngine._append_traffic_trend(
            "原答案。", self._fb(None)) == "原答案。"

    def test_it_is_wired_on_the_same_number_of_paths_as_its_siblings(self):
        """硬约束 8：同一个结构的**所有位置**都要接上，⛔ 不许只接一处。"""
        tree = ast.parse(inspect.getsource(se))
        names = [
            n.func.attr for n in ast.walk(tree)
            if isinstance(n, ast.Call) and isinstance(n.func, ast.Attribute)
        ]
        assert names.count("_append_traffic_trend") == \
            names.count("_append_investigation_directions") == \
            names.count("_append_store_comparison_table") == 2

    def test_it_runs_before_the_investigation_block(self):
        """例 14 的顺序：③ 在 ⑥ 之前。"""
        src = inspect.getsource(se.ComprehensiveSynthesisEngine.synthesize)
        assert src.index("_append_traffic_trend") < \
            src.index("_append_investigation_directions")


class TestTheProducerReallyEmitsWhatTheConsumerReads:
    """🔴 变异 M7 露出来的洞：**没有一条断言钉着取数侧真的吐 `daysWithData`**。

    ⛔ 它是「机制在、没接上」最阴的一种 —— 拿掉那个键之后：
       `compute_traffic_trend` 读到 `None` → 天数当 0 → 恒 `not_comparable`
       ⇒ **③ 静默死掉，一条测试都不红。**

    ⚠️ 而我第一版变异探针没看见它，因为探针用 `inspect.getsource` 数文本，
       而 `store_comparison` 的 **docstring 里也写着 `daysWithData`** ——
       「grep 把注释也数了进去」，本仓记过三次的同一个形状。
    ⇒ 这条断言驱动**真的生产函数**，读它**真的返回**的那个 dict。
    """

    @pytest.mark.asyncio
    async def test_store_comparison_emits_days_with_data(self):
        from datetime import date

        from smartbi.gold.queries import store_comparison
        from tests.test_gold_ws1_endpoints import _ScriptedConn, _ScriptedPool

        conn = _ScriptedConn([[
            {"name": "旗舰店", "revenue": 10000, "order_count": 500,
             "avg_ticket": 20.0, "discount_amount": 1000, "gross_amount": 11000,
             "days_with_data": 30},
        ]])
        out = await store_comparison(
            _ScriptedPool(conn), "F", (date(2026, 7, 20), date(2026, 8, 18)))
        assert out["stores"][0]["daysWithData"] == 30, (
            "取数侧不吐 daysWithData ⇒ 第③步会安静地永远算不出趋势")

    @pytest.mark.asyncio
    async def test_the_sql_really_counts_distinct_dates(self):
        """⛔ 键在、值是编的也不行 —— SQL 必须真的按天去重计数。"""
        from datetime import date

        from smartbi.gold.queries import store_comparison
        from tests.test_gold_ws1_endpoints import _ScriptedConn, _ScriptedPool

        conn = _ScriptedConn([[]])
        await store_comparison(
            _ScriptedPool(conn), "F", (date(2026, 7, 20), date(2026, 8, 18)))
        sql = " ".join(conn.calls[0][1].split())
        assert "COUNT(DISTINCT a.date) AS days_with_data" in sql

    def test_the_consumer_reads_exactly_that_key(self):
        """形态 D：产出侧和消费侧用的必须是**同一个字面量**。"""
        from smartbi.agent import factbook as fbm
        tree = ast.parse(inspect.getsource(fbm.compute_traffic_trend))
        keys = {n.value for n in ast.walk(tree)
                if isinstance(n, ast.Constant) and isinstance(n.value, str)}
        assert "daysWithData" in keys, (
            "消费侧读的键名和产出侧对不上 —— 这一对永远不会有人报错，只会静默返空")


class TestStep5aAdmitsTheBlindSpot:
    """⑤a：承认有一类原因数据里根本没有。"""

    def _fb(self, trend=None):
        fb = FactBook()
        fb.attribution = compute_store_attribution(_rows(_CUR, _CUR_DAYS))
        fb.traffic_trend = trend
        fb.available_dimensions = []
        fb.missing_dimensions = [
            {"code": "channel", "label": "堂食/外卖/自提"},
            {"code": "meal_period", "label": "午晚市与时段"},
        ]
        return fb

    def test_it_appears_in_the_investigation_block(self):
        out = _APPEND("原答案", self._fb())
        assert _REALITY_CHECK_BLIND_SPOT in out

    def test_step7_no_longer_enumerates_operations_as_the_only_remainder(self):
        """🔴 ⑤a 存在的理由：⑦ 原来那句话把剩下的解释枚举成了运营一种。

        ⛔ 变异（把 ⑤a 那一行删掉）必须让这条红 —— 它守的不是措辞，
           是「数据查完之后，⑦ 之前必须先出现一档不是运营的解释」。
        """
        out = _APPEND("原答案", self._fb())
        blind = out.index("只有你和店长知道")
        ops = out.index("这家店自己的运营")
        assert blind < ops, (
            "盲区那一档必须排在「怀疑运营」前面 —— 否则 ⑦ 仍然是一句过头的话")

    def test_it_names_the_kinds_of_events_but_asserts_none_of_them(self):
        """⛔ 不编造事件 —— 只列类别，且必须是问句/条件句，不是断言句。"""
        line = _reality_check_line(self._fb())
        for kind in ("施工", "商场活动", "对面新开", "人流改道"):
            assert kind in line, kind
        for hallucination in ("可能是商场", "大概是", "应该是因为", "这是由于"):
            assert hallucination not in line, hallucination

    def test_it_says_the_data_will_never_exist_not_that_it_is_pending(self):
        """⛔ 这一类 ≠ ⑥ 那行「数据还没接进来」（那是等人去接）。"""
        assert "永远不会有数据" in _REALITY_CHECK_BLIND_SPOT
        assert "只有你和店长知道" in _REALITY_CHECK_BLIND_SPOT

    def test_step7_still_refuses_to_claim_the_checks_were_done(self):
        """既有保证不许因为改措辞而丢掉。"""
        for phrase in ("已排除", "都排除了", "已经排除", "排除完毕", "可以确定"):
            assert phrase not in _INVESTIGATION_LAST_RESORT, phrase
        assert "还没到那一步" in _INVESTIGATION_LAST_RESORT


class TestStep5bOnlyAsksWhenTheTrafficReallyMoved:
    """⑤b：具名反问要有数才问。两侧都断言 —— ⛔ 单侧的话它可能恒不触发。"""

    def _fb(self, pct):
        """构造一个跌幅恰好为 `pct` 的 laggard —— ⛔ 其余形状与 prod 一致。"""
        prev_bills = 4200
        cur_bills = int(round(prev_bills * (1 + pct / 100.0) * _CUR_DAYS / _PREV_DAYS))
        cur = tuple((n, r, cur_bills if n == _LAGGARD else b) for n, r, b in _CUR)
        fb = FactBook()
        fb.attribution = compute_store_attribution(_rows(_CUR, _CUR_DAYS))
        fb.traffic_trend = compute_traffic_trend(
            _rows(cur, _CUR_DAYS), _rows(_PREV, _PREV_DAYS), laggard_name=_LAGGARD)
        fb.available_dimensions = []
        fb.missing_dimensions = [{"code": "channel", "label": "堂食/外卖/自提"}]
        return fb

    def test_the_fixture_really_produces_the_drop_i_asked_for(self):
        """🔴 阳性对照：先证明构造出来的跌幅真的是我要的那个数。"""
        lag = self._fb(-12.0).traffic_trend["laggard"]
        assert lag["bills_pct"] == pytest.approx(-12.0, abs=0.3)

    def test_just_below_the_threshold_stays_generic(self):
        line = _reality_check_line(self._fb(TRAFFIC_DROP_ASK_THRESHOLD_PCT + 0.5))
        assert line == _REALITY_CHECK_BLIND_SPOT
        assert _LAGGARD not in line, "⛔ 没到阈值就不许点名"

    def test_past_the_threshold_names_the_store_and_the_number(self):
        line = _reality_check_line(self._fb(TRAFFIC_DROP_ASK_THRESHOLD_PCT - 2.0))
        assert _LAGGARD in line
        assert "每天" in line and "单" in line
        assert "?" in line or "？" in line, "⑤ 的产出是一句**反问**，不是一个数"

    def test_growth_never_triggers_the_ask(self):
        assert _reality_check_line(self._fb(+15.0)) == _REALITY_CHECK_BLIND_SPOT

    def test_real_mock_rest_numbers_stay_generic(self):
        """📏 反目标的直接落地：-0.4% 的店⛔不许被点名去查门口。"""
        fb = FactBook()
        fb.attribution = compute_store_attribution(_rows(_CUR, _CUR_DAYS))
        fb.traffic_trend = _trend()
        assert _reality_check_line(fb) == _REALITY_CHECK_BLIND_SPOT

    def test_not_comparable_never_triggers_the_named_ask(self):
        fb = FactBook()
        fb.traffic_trend = compute_traffic_trend(_rows(_CUR, _CUR_DAYS), [])
        assert _reality_check_line(fb) == _REALITY_CHECK_BLIND_SPOT

    def test_a_missing_trend_never_triggers_the_named_ask(self):
        fb = FactBook()
        fb.traffic_trend = None
        assert _reality_check_line(fb) == _REALITY_CHECK_BLIND_SPOT

    def test_the_named_ask_still_does_not_assert_a_cause(self):
        line = _reality_check_line(self._fb(-20.0))
        for hallucination in ("因为", "原因是", "是由于", "说明门口"):
            assert hallucination not in line, hallucination

    def test_neither_reality_check_line_uses_bold(self):
        """🔴 在这一段里粗体 = 「这是一个维度名，直接说它的名字就能看」。

        📏 既有的 `test_every_named_label_comes_from_the_catalog` 当场抓到过
        ⑤a 的粗体，但它的 fixture 没有 `traffic_trend` ⇒ **够不到 ⑤b**。
        ⇒ 硬约束 8：同一个结构的**所有位置**都要接上，这一条补的就是够不到的那处。
        """
        assert "**" not in _REALITY_CHECK_BLIND_SPOT
        assert "**" not in _reality_check_line(self._fb(-20.0))

    def test_the_gate_is_a_pure_function_of_the_trend(self):
        assert traffic_drop_warrants_asking(None) is False
        assert traffic_drop_warrants_asking({"status": "ok", "laggard": None}) is False


class TestItIsComputedInExactlyOnePlace:
    """形态 D：同一个东西有两份，它一定会漂。⛔ AST 数结构，不 grep 文本。"""

    def test_only_one_module_divides_bills_by_days(self):
        """按天折算只许有一处实现 —— 第二处出现时这条会红。"""
        tree = ast.parse(inspect.getsource(se))
        calls = [
            n for n in ast.walk(tree)
            if isinstance(n, ast.Call)
            and isinstance(n.func, ast.Name)
            and n.func.id == "compute_traffic_trend"
        ]
        assert len(calls) == 1, f"synthesis_engine 里应恰好一处调用，实际 {len(calls)}"

    def test_the_previous_window_is_fetched_wherever_attribution_is(self):
        """硬约束 8：③ 的取数必须与 ② 的取数在**同样多**的地方接上。"""
        tree = ast.parse(inspect.getsource(se))
        subs = [
            n for n in ast.walk(tree)
            if isinstance(n, ast.Subscript)
            and isinstance(n.value, ast.Name) and n.value.id == "tasks"
            and isinstance(n.slice, ast.Constant)
        ]
        keys = [n.slice.value for n in subs]
        assert keys.count("attribution") == keys.count("attribution_prev") == 1

    def test_the_threshold_is_a_named_constant(self):
        """⛔ 拍出来的数必须有名字才能被审、被替换。"""
        assert isinstance(TRAFFIC_DROP_ASK_THRESHOLD_PCT, float)
        tree = ast.parse(inspect.getsource(se))
        literals = [
            n for n in ast.walk(tree)
            if isinstance(n, ast.Constant) and n.value == TRAFFIC_DROP_ASK_THRESHOLD_PCT
        ]
        assert not literals, "阈值不许在 synthesis_engine 里以字面量再写一遍"
