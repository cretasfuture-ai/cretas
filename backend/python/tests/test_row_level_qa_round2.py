"""问数据 (AI) 味美复测剩下的 6 条 (第 15 任复测, 17 条对 14 半对 3) 的回归。

合成表, 不放客户真实数据。LLM 只出查询计划, 这里用桩; 规划层的确定性改动 (规则 / 缓存 / 重试)
用「会犯错的桩」覆盖: 桩给出错误计划时, 结果也必须是对的。
"""
from __future__ import annotations

import json

import pandas as pd
import pytest

from smartbi.services import row_level_qa as rl

RATE = "出成率(良品/(良品+废品+次品))"


@pytest.fixture(autouse=True)
def _fresh_plan_cache():
    rl._PLAN_CACHE.clear()
    yield


class SeqPlanner:
    """按调用顺序依次返回计划 JSON (用来模拟 LLM 两次采样不一样)。"""

    def __init__(self, *plans):
        self.plans, self.prompts = list(plans), []

    async def __call__(self, prompt: str, system: str) -> str:
        self.prompts.append(prompt)
        p = self.plans[min(len(self.prompts) - 1, len(self.plans) - 1)]
        return p if isinstance(p, str) else json.dumps(p, ensure_ascii=False)


async def ask(question, df, *plans):
    planner = SeqPlanner(*plans)
    out = await rl.answer_row_level(question, df, rl.all_field_names(None, df), planner)
    return out, planner


def film():
    rows = [("2026-10-01", "FIL20-C", 30), ("2026-10-01", "FIL55", 20),
            ("2026-10-02", "FIL20-C", 22), ("2026-10-02", "FIL55", 25), ("2026-10-02", "FIL30", 36)]
    return pd.DataFrame(rows, columns=["日期", "卷膜编码", "用量(卷)"])   # FIL20-C 52, 合计 133


def plan_daily():
    rows = [("2026-10-01", 5, 4, 16868.2), ("2026-10-02", 6, 4, 16705.6), ("2026-10-09", 7, 4, 9000.0),
            ("2026-10-23", 4, 1, 100.0), ("2026-10-30", 4, 1, 120.0)]
    df = pd.DataFrame(rows, columns=["日期", "原表计划品项数", "已产出计划品项数", "实产良品kg"])
    df["计划品项达成率"] = df["已产出计划品项数"] / df["原表计划品项数"]
    return df


# ------------------------------ 1. 一共/总共 要求和 ------------------------------

async def test_1_total_question_reports_sum_of_all_groups_not_largest_group():
    plan = {"intent": "compute", "group_by": ["卷膜编码"],
            "metrics": [{"name": "用量合计", "func": "sum", "col": "用量(卷)"}],
            "sort_by": "用量合计", "descending": True, "limit": 3}
    out, _ = await ask("10月卷膜用量最大的是哪种？全月一共用了多少卷？", film(), plan)
    assert out.result.rows[0][:2] == ["FIL20-C", 52]          # 最大项是 52
    assert "133" in out.text                                   # 总数是 133 (改前结果块里根本没有这个数)
    assert "用量合计 = 133" in out.text and "不是总数" in out.text
    assert out.result.totals == {"用量合计": 133}


async def test_1_rows_mode_plan_that_picked_the_max_row_still_carries_the_total():
    plan = {"intent": "compute", "sort_by": "用量(卷)", "descending": True, "limit": 1}
    out, _ = await ask("全月一共用了多少卷？", film(), plan)
    assert out.result.totals == {"用量(卷)合计": 133}


# ------------------------------ 2. 计划产量: 确定性 ------------------------------

async def test_2_plan_quantity_without_quantity_column_answers_by_item_count_every_time():
    df = plan_daily()
    q = "10月哪天计划产量最高？"
    # 桩第一次说「没有这个指标」, 第二次拿实产良品顶替 —— 复测里同一原话的两种结果
    first, p1 = await ask(q, df, {"intent": "no_metric", "missing": "计划产量"})
    second, p2 = await ask(q, df, {"intent": "compute", "sort_by": "实产良品kg", "descending": True, "limit": 1})
    for out in (first, second):
        assert out.kind == "computed"
        row = dict(zip(out.result.columns, out.result.rows[0]))
        assert (row["日期"], row["原表计划品项数"], row["已产出计划品项数"]) == ("2026-10-09", 7, 4)
        assert any("没有数量列" in n and "品项数" in n for n in out.result.notes)
    assert first.text == second.text
    assert p1.prompts == [] and p2.prompts == []              # 不靠 LLM 采样


async def test_2_plan_quantity_with_no_plan_columns_at_all_refuses():
    df = pd.DataFrame({"日期": ["2026-10-01"], "良品kg": [10.0], "废品kg": [1.0]})
    out, planner = await ask("哪天计划产量最高", df, {"intent": "compute", "sort_by": "良品kg"})
    assert out.kind == "refuse" and "计划产量" in out.text and "良品kg" in out.text
    assert planner.prompts == []


async def test_2_table_with_a_real_plan_quantity_column_is_not_rerouted():
    df = pd.DataFrame({"日期": ["2026-10-01", "2026-10-02"], "计划产量kg": [100.0, 300.0]})
    out, planner = await ask("哪天计划产量最高", df,
                             {"intent": "compute", "sort_by": "计划产量kg", "descending": True, "limit": 1})
    assert out.result.rows[0][0] == "2026-10-02" and len(planner.prompts) == 1


async def test_2_request_without_upload_identity_uses_its_own_plan():
    df = plan_daily()
    q = "按日看哪天已产出品项最多"
    a = {"intent": "compute", "sort_by": "原表计划品项数", "descending": True, "limit": 1}
    b = {"intent": "compute", "sort_by": "实产良品kg", "descending": True, "limit": 1}
    first, _ = await ask(q, df, a, b)
    second, p2 = await ask(q, df, b, a)
    assert first.result.rows[0][0] == "2026-10-09"
    assert second.result.rows[0][0] == "2026-10-01"
    assert len(p2.prompts) == 1


# ------------------------------ 3. 按日表问「哪一周」 ------------------------------

async def test_3_week_question_on_daily_table_is_rolled_up_by_week_by_the_program():
    """按日表问「哪一周」: 程序由日期折算 ISO 周后按周汇总 (不再把某一天说成某一周), 且不经 LLM 采样。"""
    plan = {"intent": "compute", "sort_by": "计划品项达成率", "descending": False, "limit": 1}
    out, planner = await ask("哪一周计划品项达成率最低？是多少？", plan_daily(), plan)
    assert planner.prompts == []                                # 确定性: 同一问题每次一致
    weeks = {r[0]: r[1] for r in out.result.rows}
    # 10/01,10/02 -> W40 (8/11); 10/09 -> W41 (4/7); 10/23 -> W43 (1/4); 10/30 -> W44 (1/4)
    assert out.result.rows[0][0] in ("2026-W43", "2026-W44") and abs(out.result.rows[0][1] - 0.25) < 1e-9
    assert abs(weeks["2026-W40"] - 8 / 11) < 1e-9 and abs(weeks["2026-W41"] - 4 / 7) < 1e-9
    assert any("按日表" in n and "按周汇总" in n for n in out.result.notes)
    assert "25.00%" in out.text and "2026-W41" in out.text      # 率按加权百分数给出


async def test_3_week_rollup_weights_the_rate_instead_of_averaging_daily_rates():
    df = pd.DataFrame([("2026-10-05", 10, 1), ("2026-10-06", 1, 1)], columns=["日期", "原表计划品项数", "已产出计划品项数"])
    df["计划品项达成率"] = df["已产出计划品项数"] / df["原表计划品项数"]       # 逐日 0.1 / 1.0, 简单平均 0.55
    out, _ = await ask("哪一周计划品项达成率最低", df, {"intent": "none"})
    assert abs(out.result.rows[0][1] - 2 / 11) < 1e-9           # 加权 2/11, 不是 0.55


async def test_3_planner_that_groups_by_a_nonexistent_week_column_is_retried_once():
    # 没有可解析日期列、没法折算周: 仍走规划器; 字段对不上时交回重写一次
    df = plan_daily().rename(columns={"日期": "当天"})
    bad = {"intent": "compute", "group_by": ["周"],
           "metrics": [{"name": "达成率", "func": "mean", "col": "计划品项达成率"}], "sort_by": "达成率"}
    good = {"intent": "compute", "sort_by": "计划品项达成率", "descending": False, "limit": 1}
    out, planner = await ask("哪一周计划品项达成率最低？", df, bad, good)
    assert out.kind == "computed"
    assert len(planner.prompts) == 2 and "字段不存在: 周" in planner.prompts[1]
    assert "没有「周」列" in planner.prompts[0]
    assert any("按日表" in n for n in out.result.notes)         # 折算不了周就明说是按日表


# ------------------------------ 4. 缺口 = 计划 − 产出 ------------------------------

async def test_4_gap_is_planned_minus_produced_not_the_planned_count():
    wrong = {"intent": "compute", "sort_by": "原表计划品项数", "descending": True, "limit": 5}  # 复测里的错法
    out, planner = await ask("哪些产品没按计划完成？", plan_daily(), wrong)
    cols = out.result.columns
    gap_col = next(c for c in cols if c.startswith("缺口数"))
    top = dict(zip(cols, out.result.rows[0]))
    assert (top["日期"], top[gap_col]) == ("2026-10-09", 3)    # 7 个计划产出 4 个, 缺 3 个, 不是 7
    gaps = [r[cols.index(gap_col)] for r in out.result.rows]
    assert gaps == sorted(gaps, reverse=True) and max(gaps) == 3 and all(g > 0 for g in gaps)
    assert planner.prompts == []


def test_4_gap_column_exists_only_when_plan_and_done_columns_pair_up():
    assert any(c.startswith("缺口数") for c in rl.with_gap_columns(plan_daily()).columns)
    plain = pd.DataFrame({"日期": ["2026-10-01"], "良品kg": [1.0]})
    assert list(rl.with_gap_columns(plain).columns) == ["日期", "良品kg"]


# ------------------------------ 5. 编码不加千分位 ------------------------------

async def test_5_product_codes_are_not_thousand_separated():
    df = pd.DataFrame({"产品编码": [22002, 22002, 260403, 210016],
                       "实产良品kg": [1500.5, 0.0, 2500.0, 10.0]})
    plan = {"intent": "compute", "group_by": ["产品编码"],
            "metrics": [{"name": "良品合计", "func": "sum", "col": "实产良品kg"}],
            "sort_by": "良品合计", "descending": True, "limit": 5}
    out, _ = await ask("各产品编码良品合计", df, plan)
    assert "| 260403 |" in out.text and "| 22002 |" in out.text
    assert "22,002" not in out.text and "260,403" not in out.text and "22002.0" not in out.text
    assert "2,500" in out.text                                   # 阴性对照: 普通数量仍有千分位


async def test_5_code_column_in_row_mode_is_also_plain():
    df = pd.DataFrame({"日期": ["2026-10-01"], "产品编码": [210016.0], "良品kg": [12345.5]})
    out, _ = await ask("列出", df, {"intent": "compute", "filters": [{"col": "良品kg", "op": ">", "value": 1}]})
    assert "| 210016 |" in out.text and "210,016" not in out.text and "12,345.5" in out.text


# ------------------------------ 6. 页脚指标跟问题相关 ------------------------------

COLS = ["人数", "出勤率", "批次数", "良品kg", "废品kg", RATE]


def test_6_relevant_columns_follow_the_question_not_the_first_three_numeric_columns():
    from smartbi.services.question_chart import relevant_measure_columns
    assert relevant_measure_columns("哪天产量最高？", COLS) == ["良品kg"]
    assert relevant_measure_columns("哪天出成率最低？当天废品多少公斤？", COLS) == ["废品kg", RATE]
    assert relevant_measure_columns("哪天人数最多", COLS) == ["人数"]
    assert relevant_measure_columns("你好", COLS) == []          # 对不上: 保持默认选列


def _daily_df():
    rows = [("2026-10-01", "2026-W40", 47, 0.89, 4, 16868.2, 153.0, 0.9910),
            ("2026-10-02", "2026-W40", 46, 0.87, 4, 16705.6, 186.1, 0.9890),
            ("2026-10-03", "2026-W40", 18, 1.00, 1, 2553.0, 15.0, 0.9942)]
    return pd.DataFrame(rows, columns=["日期", "周", "人数", "出勤率", "批次数", "良品kg", "废品kg", RATE])


def _series(query):
    from smartbi.api.chat import _build_charts_for_query
    df = _daily_df()
    charts = _build_charts_for_query(query, df, df.to_dict("records"))
    assert charts, "没出图"
    return charts[0]["option"]


def test_6_footer_chart_for_output_question_draws_yield_not_headcount():
    opt = _series("哪天产量最高？")
    assert [s["name"] for s in opt["series"]] == ["良品kg"]
    assert opt["xAxis"]["data"] == ["2026-10-01", "2026-10-02", "2026-10-03"]   # 「哪天」按日, 不是各周


def test_6_footer_chart_for_rate_question_is_scaled_to_percent():
    opt = _series("哪天出成率最低？")
    s = opt["series"][0]
    assert s["name"].startswith("出成率") and s["data"] == [99.1, 98.9, 99.42]


def test_6_footer_chart_unrelated_question_keeps_default_columns():
    opt = _series("随便看看")
    assert [s["name"] for s in opt["series"]][0] == "人数"
