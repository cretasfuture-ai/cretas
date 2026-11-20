"""REVIEW-1981 的阻塞项反例 (Codex 独立审查 5 条 P1 + 1 条 P2 + 非阻塞建议)。

同 test_weimei_retest_r4: 断言跑在 general_analysis_stream 真实入口 (假连接 + 假 LLM)。
每条注释写明「改前」读数 (在 7a59700fbf 上跑出来的)。
"""
from __future__ import annotations

from dataclasses import replace

import pytest

from smartbi.services.upload_aggregate_cache import get_cache
from smartbi.services.upload_auto_select import UploadCandidate
from smartbi.services import row_level_qa
from tests.test_weimei_retest_r3b import FILM_FIELDS, FILM_Q, PLAN_FIELDS, plan_rows
from tests.test_weimei_retest_r4 import (  # noqa: F401  (autouse fixture 要一起导入)
    PLAN_DAY, PLAN_DAY_FIELDS, PLAN_DETAIL, _bundle, _chart_titles, _clean_agg_cache, _series_names, ask, code_rows,
    plan_day_rows,
)


# ============ P1-1 合计校验: 局部小计保留, 千分位不当分句 ============

def two_group_rows(a_qty, b_qty):
    return [{"周": "2026-W40", "日期": "2026-10-01T00:00:00", "用量(卷)": float(a_qty), "卷膜编码": "A产品"},
            {"周": "2026-W41", "日期": "2026-10-20T00:00:00", "用量(卷)": float(b_qty), "卷膜编码": "B产品"}]


GROUP_PLAN = {"intent": "compute", "group_by": ["卷膜编码"],
              "metrics": [{"name": "总用量", "func": "sum", "col": "用量(卷)"}],
              "sort_by": "总用量", "descending": True, "limit": 5}


async def test_p1_1_local_subtotal_of_a_named_group_is_kept_and_only_the_whole_total_is_fixed(monkeypatch):
    tables = {5321: (two_group_rows(52, 81), FILM_FIELDS)}
    good = "A产品一共 52 卷，全月一共 133 卷。"
    _, done, _, _ = await ask(monkeypatch, FILM_Q, tables, answer=good, plans=[GROUP_PLAN])
    assert done["answer"] == good and done["answer_corrected"] is False        # 改前: A 的 52 被改成 133
    bad = "A产品一共 52 卷，全月一共 52 卷。"
    _, done, _, _ = await ask(monkeypatch, FILM_Q, tables, answer=bad, plans=[GROUP_PLAN])
    assert done["answer"].startswith("A产品一共 52 卷，") and "全月一共 52 卷" not in done["answer"]
    assert "133" in done["answer"] and done["answer_corrected"] is True


async def test_p1_1_thousands_separator_inside_a_number_is_not_a_clause_boundary(monkeypatch):
    tables = {5321: (two_group_rows(2, 1998), FILM_FIELDS)}
    right = "全月一共 2,000 卷，其中 B产品 1,998 卷。"
    _, done, _, _ = await ask(monkeypatch, FILM_Q, tables, answer=right, plans=[GROUP_PLAN])
    assert done["answer"] == right and done["answer_corrected"] is False        # 改前: 切坏成残留 ",000 卷"


@pytest.mark.parametrize(("answer", "expected"), [
    ("全月所有 A产品和 B产品一共 52 卷。全月一共 133 卷。", "全月所有 A产品和 B产品一共 133 卷。全月一共 133 卷。"),
    ("全月一共 999 卷，另有备注共 133 字。", "全月一共 133 卷，另有备注共 133 字。"),
    ("全月一共 999 卷和 133 字。全月一共 52 卷。", "全月一共 999 卷和 133 字。全月一共 133 卷。"),
    ("全月卷膜用量合计 52 卷。全月一共 133 卷。", "全月卷膜用量合计 133 卷。全月一共 133 卷。"),
    ("总计 52 卷。全月一共 133 卷。", "总计 133 卷。全月一共 133 卷。"),
    ("全月报废量一共 88 卷。全月一共用了 999 卷。", "全月报废量一共 88 卷。全月一共用了 133 卷。"),
])
async def test_p1_1_each_whole_total_is_checked_against_its_own_metric(monkeypatch, answer, expected):
    _, done, prompts, _ = await ask(
        monkeypatch, FILM_Q, {5321: (two_group_rows(52, 81), FILM_FIELDS)}, answer=answer,
        plans=[dict(GROUP_PLAN, limit=1)],
    )
    assert "总用量 = 133" in prompts[0]
    assert done["answer"] == expected and done["answer_corrected"] is True


@pytest.mark.parametrize(("labels", "subtotal"), [
    (("A产品", "B产品"), "全月 A产品 52 卷和 B产品 81 卷"),
    (("A", "B"), "A一共 52 卷"),
    (("A10", "A100"), "全月 A100 一共 81 卷"),
    (("A10", "A100"), "全月所有 A100 一共 81 卷"),
    (("A", "B"), "A 总计 52 卷"),
    (("A", "B"), "一共 52 卷"),
])
async def test_p1_1_ambiguous_or_local_claim_is_unchanged(monkeypatch, labels, subtotal):
    answer = f"{subtotal}。全月一共 133 卷。"
    rows = [dict(row, 卷膜编码=label) for row, label in zip(two_group_rows(52, 81), labels)]
    _, done, prompts, calls = await ask(
        monkeypatch, FILM_Q, {5321: (rows, FILM_FIELDS)}, answer=answer, plans=[dict(GROUP_PLAN, limit=1)],
    )
    assert len(calls) == 1 and "总用量 = 133" in prompts[0]
    assert done["answer"] == answer and done["answer_corrected"] is False


async def test_review_8_corrects_every_global_total_and_keeps_interleaved_subtotals(monkeypatch):
    answer = "全月一共 52 卷。A产品一共 52 卷。全表总用量为 81 卷。B产品一共 81 卷。整月合计 133 卷。"
    expected = "全月一共 133 卷。A产品一共 52 卷。全表总用量为 133 卷。B产品一共 81 卷。整月合计 133 卷。"
    _, done, prompts, _ = await ask(
        monkeypatch, FILM_Q, {5321: (two_group_rows(52, 81), FILM_FIELDS)}, answer=answer, plans=[GROUP_PLAN],
    )
    assert "总用量 = 133" in prompts[0]
    assert done["answer"] == expected and done["answer_corrected"] is True


@pytest.mark.parametrize("topic", ["A产品方面", "其中A产品", "A产品中的", "A产品这一类", "A产品的话"])
async def test_r3_scoped_group_total_is_left_alone_across_the_comma(monkeypatch, topic):
    answer = f"{topic}，全月一共 52 卷。全月所有 A产品和 B产品一共 133 卷。"
    _, done, prompts, calls = await ask(
        monkeypatch, FILM_Q, {5321: (two_group_rows(52, 81), FILM_FIELDS)}, answer=answer,
        plans=[dict(GROUP_PLAN, limit=1)],
    )
    assert len(calls) == 1 and "总用量 = 133" in prompts[0]
    assert done["answer"] == answer and done["answer_corrected"] is False


async def test_r3_bare_group_answer_can_precede_a_whole_table_total(monkeypatch):
    answer = "用量最大的是FIL20-C，全月一共52卷。"
    rows = [dict(row, 卷膜编码=label) for row, label in zip(two_group_rows(52, 81), ("FIL20-C", "FIL30-C"))]
    _, done, prompts, calls = await ask(
        monkeypatch, FILM_Q, {5321: (rows, FILM_FIELDS)}, answer=answer, plans=[GROUP_PLAN],
    )
    assert len(calls) == 1 and "总用量 = 133" in prompts[0]
    assert done["answer"] == "用量最大的是FIL20-C，全月一共133卷。"
    assert done["answer_corrected"] is True


# ============ P1-2 「计划与实际」规则: 别的指标 / 时间范围 ============

PLAN_OUTPUT_FIELDS = [("日期", False, False, True), ("计划工单数", True, False, False),
                      ("已完成计划工单数", True, False, False), ("计划产量kg", True, False, False),
                      ("实际产量kg", True, False, False)]
PLAN_OUTPUT = [UploadCandidate(5399, "计划与实际_工单", "计划与实际_工单.xlsx", 4, [f[0] for f in PLAN_OUTPUT_FIELDS])]


def plan_output_rows():
    return [{"日期": "2026-09-10", "计划工单数": 100, "已完成计划工单数": 20, "计划产量kg": 1000.0, "实际产量kg": 900.0},
            {"日期": "2026-09-20", "计划工单数": 40, "已完成计划工单数": 8, "计划产量kg": 400.0, "实际产量kg": 400.0},
            {"日期": "2026-10-05", "计划工单数": 6, "已完成计划工单数": 1, "计划产量kg": 700.0, "实际产量kg": 500.0},
            {"日期": "2026-10-20", "计划工单数": 4, "已完成计划工单数": 1, "计划产量kg": 500.0, "实际产量kg": 400.0}]


@pytest.mark.parametrize("guard", ["column_role", "question_metric"])
async def test_p1_2_plan_vs_actual_rule_does_not_steal_a_question_about_another_metric(monkeypatch, guard):
    rows, fields = plan_output_rows(), PLAN_OUTPUT_FIELDS
    question = "10月计划与实际差多少"
    if guard == "question_metric":
        rename = {"计划工单数": "原表计划品项数", "已完成计划工单数": "已产出计划品项数"}
        rows = [{rename.get(k, k): v for k, v in row.items()} for row in rows]
        fields = [(rename.get(n, n), m, d, t) for n, m, d, t in fields]
        question = "10月计划与实际产量kg差多少"
    plan = {"intent": "compute", "filters": [{"col": "日期", "op": "==", "value": "2026-10"}],
            "metrics": [{"name": "计划产量", "func": "sum", "col": "计划产量kg"},
                        {"name": "实际产量", "func": "sum", "col": "实际产量kg"}]}
    candidates = [replace(PLAN_OUTPUT[0], field_names=[f[0] for f in fields])]
    _, _, prompts, calls = await ask(monkeypatch, question, {5399: (rows, fields)}, plans=[plan], candidates=candidates)
    assert len(calls) == 1
    block = prompts[0].split("## 全表精确计算结果")[1]
    assert "命中 2 / 4 行" in block
    assert "| 计划产量 | 实际产量 |" in block and "| 1,200 | 900 |" in block


async def test_p1_2_plan_vs_actual_rule_keeps_the_month_scope(monkeypatch):
    rows = [{"日期": "2026-09-10", "原表计划品项数": 50, "已产出计划品项数": 5, "计划品项达成率": 0.1,
             "计划外生产品项数": 7, "备注": None, "实产良品kg": 1.0}] + plan_day_rows()
    _, _, prompts, calls = await ask(monkeypatch, "10月计划与实际差多少？", {5334: (rows, PLAN_DAY_FIELDS)},
                                     plans=[{"intent": "none"}], candidates=PLAN_DAY)
    assert "| 75 | 55 | 20 | 73.33% | 9 |" in prompts[0]        # 改前: 125 / 60 (九月也算进去)
    assert "命中 10 / 11 行" in prompts[0] and calls == []


# ============ P1-3 按周汇总: 月份范围 + 聚合口径 ============

def day_rate_rows():
    base = {"计划外生产品项数": 0, "备注": None, "实产良品kg": 1.0}
    return [dict(base, 日期="2026-09-10", 原表计划品项数=20, 已产出计划品项数=1, 计划品项达成率=0.05),
            dict(base, 日期="2026-10-05", 原表计划品项数=10, 已产出计划品项数=5, 计划品项达成率=0.5),
            dict(base, 日期="2026-10-20", 原表计划品项数=10, 已产出计划品项数=8, 计划品项达成率=0.8)]


@pytest.mark.parametrize(("question", "date_mode", "filters", "matched", "value"), [
    ("2025年10月计划与实际差多少", "cross_year", [{"col": "日期", "op": "==", "value": "2025-10"}], 1, 4),
    ("2025年10月计划与实际差多少", "single_year", [{"col": "日期", "op": "==", "value": "2025-10"}], 0, "(空)"),
    ("9月和10月计划与实际差多少", "single_year", [], 3, 40),
    ("10月5日之后哪一周计划品项达成率最低", "single_year", [{"col": "日期", "op": ">", "value": "2026-10-05"}], 1, 10),
    ("10月哪一周计划品项达成率最低", "no_date", [], 3, 40),
    ("10月计划与实际差多少", "no_date", [], 3, 40),
    ("10月计划与实际差多少", "cross_year", [{"col": "日期", "op": "==", "value": "2026-10"}], 2, 20),
    ("10月哪一周计划品项达成率最低", "cross_year", [{"col": "日期", "op": "==", "value": "2026-10"}], 2, 20),
    ("哪一周计划品项达成率最低", "cross_year", [], 4, 44),
    ("上月计划与实际差多少", "single_year", [], 3, 40),
    ("近两个月计划与实际差多少", "single_year", [], 3, 40),
    ("10月前两周计划与实际差多少", "single_year", [], 3, 40),
])
async def test_p1_2_3_unsupported_time_scopes_are_left_to_the_planner(
        monkeypatch, question, date_mode, filters, matched, value):
    rows, fields = day_rate_rows(), PLAN_DAY_FIELDS
    if date_mode == "cross_year":
        rows.append(dict(rows[0], 日期="2025-10-01", 原表计划品项数=4))
    if date_mode == "no_date":
        # 数据集有月份标签，能通过能力检查，但无法按自然日折算周或年份。
        rows = [{**{k: v for k, v in row.items() if k != "日期"}, "月份": row["日期"][:7]}
                for row in rows]
        fields = [f for f in fields if f[0] != "日期"] + [("月份", False, False, True)]
    candidates = [replace(PLAN_DAY[0], field_names=[f[0] for f in fields])]
    plan = {"intent": "compute", "filters": filters,
            "metrics": [{"name": "规划器计划数", "func": "sum", "col": "原表计划品项数"}]}
    _, _, prompts, calls = await ask(monkeypatch, question, {5334: (rows, fields)}, plans=[plan], candidates=candidates)
    assert len(calls) == (2 if matched == 0 else 1)
    block = prompts[0].split("## 全表精确计算结果")[1]
    assert f"命中 {matched} / {len(rows)} 行" in block
    assert "| 规划器计划数 |" in block and f"| {value} |" in block


async def test_p1_3_week_rollup_keeps_the_month_scope(monkeypatch):
    _, _, prompts, calls = await ask(monkeypatch, "10月哪一周计划品项达成率最低？", {5334: (day_rate_rows(), PLAN_DAY_FIELDS)},
                                     plans=[{"intent": "none"}], candidates=PLAN_DAY)
    block = prompts[0]
    assert "2026-W37" not in block and "| 2026-W41 | 50.00% |" in block      # 改前: 九月 W37 5% 排第一
    assert "月份(按日期折算) == 10" in block and calls == []


TEMP_FIELDS = [("日期", False, False, True), ("温度℃", True, False, False), ("醒发分钟", True, False, False)]
TEMP_CAND = [UploadCandidate(5398, "醒发温度", "醒发温度.xlsx", 5, [f[0] for f in TEMP_FIELDS])]


def temp_rows():
    return [{"日期": d, "温度℃": t, "醒发分钟": 50.0} for d, t in
            [("2026-10-05", 30.0), ("2026-10-06", 40.0), ("2026-10-12", 36.0), ("2026-10-13", 36.0), ("2026-10-14", 36.0)]]


@pytest.mark.parametrize(("question", "column"), [
    ("10月的前两周，哪一周良品产量最高", "良品产量kg"),
    ("上月哪一周良品产量最高", "良品产量kg"),
    ("近两个月哪一周良品产量最高", "良品产量kg"),
    ("哪一周良品产量最高？各线均需列出", "良品产量kg"),
    ("10月哪一周累计产量最高", "累计产量kg"),
    ("10月哪一周库存重量最高", "库存重量kg"),
    ("哪一周余额数量最高", "余额数量"),
    ("哪一周单位产量kg/人最高", "单位产量kg/人"),
    ("哪一周单位产量kg/人的合计最高", "单位产量kg/人"),
    ("哪一周计划品项达成率最低", "计划品项达成率"),
    ("哪一周良品占比最高", "良品占比%"),
    ("10月哪一周温度最高", "温度℃"),
    ("10月哪一周温度平均值最高", "温度℃"),
])
async def test_weekly_scope_or_metric_outside_whitelist_calls_planner(monkeypatch, question, column):
    fields = [("日期", False, False, True), (column, True, False, False), ("产线", False, True, False)]
    rows = [{"日期": f"2026-10-{day:02d}", column: value, "产线": line}
            for day, value, line in [(1, 10, "A"), (2, 50, "B"), (5, 60, "A"), (20, 1000, "B")]]
    candidates = [replace(TEMP_CAND[0], field_names=[f[0] for f in fields])]
    _, _, _, calls = await ask(monkeypatch, question, {5398: (rows, fields)}, candidates=candidates)
    assert len(calls) == 1
    assert "周(按日期折算)" in calls[0]


@pytest.mark.parametrize(("question", "ranking"), [
    ("10月哪一周良品产量最高", ["| 2026-W41 | 60 |", "| 2026-W40 | 40 |"]),
    ("每周良品产量合计", ["| 2026-W40 | 840 |", "| 2026-W41 | 60 |"]),
])
async def test_weekly_additive_flow_is_summed_for_whitelisted_question(monkeypatch, question, ranking):
    column = "良品产量kg"
    fields = [("日期", False, False, True), (column, True, False, False)]
    rows = [{"日期": date, column: value}
            for date, value in [("2026-09-30", 800), ("2026-10-01", 10), ("2026-10-02", 30),
                                ("2026-10-05", 20), ("2026-10-06", 40)]]
    candidates = [replace(TEMP_CAND[0], field_names=[f[0] for f in fields])]
    _, _, prompts, calls = await ask(monkeypatch, question, {5398: (rows, fields)}, candidates=candidates)
    assert calls == []
    block = prompts[0].split("## 全表精确计算结果")[1]
    assert [line for line in block.splitlines() if line.startswith("| 2026-W")] == ranking


@pytest.mark.parametrize(("metric", "column"), [("单件重量", "单件重量kg"), ("醒发用时", "醒发用时kg"),
                                                ("单件产量", "单件产量kg")])
async def test_r3_weekly_sum_requires_an_additive_flow_column(monkeypatch, metric, column):
    fields = [("日期", False, False, True), (column, True, False, False)]
    rows = [{"日期": date, column: value} for date, value in
            [("2026-10-01", 10), ("2026-10-02", 10), ("2026-10-05", 15)]]
    candidates = [replace(TEMP_CAND[0], field_names=[f[0] for f in fields])]
    plan = {"intent": "compute", "group_by": [row_level_qa.WEEK_ROLLUP_COL],
            "metrics": [{"name": "周均值", "func": "mean", "col": column}],
            "sort_by": "周均值", "descending": True}
    _, done, prompts, calls = await ask(
        monkeypatch, f"10月哪一周{metric}最高", {5398: (rows, fields)}, candidates=candidates,
        plans=[plan], answer="2026-W41 最高，为 15。",
    )
    assert len(calls) == 1 and row_level_qa.WEEK_ROLLUP_COL in calls[0]
    block = prompts[0].split("## 全表精确计算结果")[1]
    assert [line for line in block.splitlines() if line.startswith("| 2026-W")] == [
        "| 2026-W41 | 15 |", "| 2026-W40 | 10 |",
    ]
    assert done["answer"] == "2026-W41 最高，为 15。"


@pytest.mark.parametrize("non_additive", [False, True])
async def test_weekly_header_ratio_requires_two_additive_count_columns(monkeypatch, non_additive):
    denominator = "库存件数" if non_additive else "生产件数"
    ratio = f"良品率(良品件数/{denominator})"
    fields = [("日期", False, False, True), ("良品件数", True, False, False),
              (denominator, True, False, False), (ratio, True, False, False)]
    rows = [{"日期": day, "良品件数": count, denominator: total, ratio: count / total}
            for day, count, total in [("2026-10-05", 1, 10), ("2026-10-06", 2, 2)]]
    candidates = [replace(TEMP_CAND[0], field_names=[f[0] for f in fields])]
    _, _, prompts, calls = await ask(monkeypatch, "哪一周良品率最低", {5398: (rows, fields)}, candidates=candidates)
    if non_additive:
        assert len(calls) == 1
    else:
        assert calls == []
        assert "| 2026-W41 | 25.00% |" in prompts[0]


@pytest.mark.parametrize("numerator_columns", [("合格品项数",), (), ("合格品项数A", "合格品项数B")],
                         ids=["explicit-pair", "missing", "ambiguous-prefix"])
async def test_r3_weekly_ratio_uses_the_explicit_formula_or_calls_planner(monkeypatch, numerator_columns):
    ratio = "计划品项达成率(合格品项数/抽检品项数)"
    rows = [{"日期": date, "原表计划品项数": planned, "已产出计划品项数": 8,
             "抽检品项数": inspected, ratio: 8 / inspected, **dict.fromkeys(numerator_columns, 8)}
            for date, planned, inspected in [("2026-10-01", 10, 40), ("2026-10-05", 40, 10)]]
    fields = [("日期", False, False, True)] + [(name, True, False, False) for name in rows[0] if name != "日期"]
    candidates = [replace(TEMP_CAND[0], field_names=[f[0] for f in fields])]
    _, done, prompts, calls = await ask(
        monkeypatch, "10月哪一周计划品项达成率最高", {5398: (rows, fields)}, candidates=candidates,
    )
    if numerator_columns == ("合格品项数",):
        assert calls == []
        block = prompts[0].split("## 全表精确计算结果")[1]
        assert "Σ合格品项数 / Σ抽检品项数" in block
        assert [line for line in block.splitlines() if line.startswith("| 2026-W")] == [
            "| 2026-W41 | 80.00% |", "| 2026-W40 | 20.00% |",
        ]
    else:
        assert len(calls) == 1 and ratio in calls[0]
        assert "## 全表精确计算结果" not in prompts[0]
    assert done["answer"] == "（桩）"


async def test_cross_year_weekly_ranking_uses_iso_week_groups_from_planner(monkeypatch):
    column = "良品产量kg"
    fields = [("日期", False, False, True), (column, True, False, False)]
    rows = [{"日期": date, column: value}
            for date, value in [("2025-12-24", 20), ("2025-12-29", 35), ("2026-01-02", 40),
                                ("2026-01-07", 70)]]
    candidates = [replace(TEMP_CAND[0], field_names=[f[0] for f in fields])]
    plan = {"intent": "compute", "group_by": ["周(按日期折算)"],
            "metrics": [{"name": "周产量", "func": "sum", "col": column}],
            "sort_by": "周产量", "descending": True}
    _, _, prompts, calls = await ask(
        monkeypatch, "哪一周良品产量最高", {5398: (rows, fields)}, plans=[plan], candidates=candidates,
    )
    assert len(calls) == 1 and "使用「周(按日期折算)」分组" in calls[0]
    block = prompts[0].split("## 全表精确计算结果")[1]
    assert "| 周(按日期折算) | 周产量 |" in block
    assert [line for line in block.splitlines() if line.startswith("| 20")] == [
        "| 2026-W01 | 75 |", "| 2026-W02 | 70 |", "| 2025-W52 | 20 |",
    ]


async def test_unavailable_derived_week_does_not_resolve_to_date(monkeypatch):
    column = "良品产量kg"
    fields = [("日期", False, False, True), (column, True, False, False)]
    rows = [{"日期": "2026-02-30", column: 10}]
    candidates = [replace(TEMP_CAND[0], field_names=[f[0] for f in fields])]
    week_plan = {"intent": "compute", "group_by": ["周(按日期折算)"],
                 "metrics": [{"name": "周产量", "func": "sum", "col": column}]}
    day_plan = {"intent": "compute", "sort_by": column, "descending": True, "limit": 1}
    _, _, prompts, calls = await ask(
        monkeypatch, "哪一周良品产量最高", {5398: (rows, fields)},
        plans=[week_plan, day_plan], candidates=candidates,
    )
    assert len(calls) == 2 and "字段不存在: 周(按日期折算)" in calls[1]
    assert "未按周汇总" in prompts[0] and "| 日期 | 周产量 |" not in prompts[0]


@pytest.mark.parametrize("with_time", [False, True])
async def test_review_6_weekly_ranking_preserves_both_date_bounds(monkeypatch, with_time):
    fields = [("日期", False, False, True), ("良品产量kg", True, False, False)]
    rows = [{"日期": "2026-09-30", "良品产量kg": 800}]
    rows += [{"日期": f"2026-10-{day:02d}" + ("T14:30:00" if with_time else ""), "良品产量kg": kg}
             for day, kg in [(1, 10), (5, 20), (15, 30), (16, 900), (23, 1000)]]
    candidates = [replace(TEMP_CAND[0], field_names=[f[0] for f in fields])]
    plan = {"intent": "compute", "filters": [
        {"col": "日期", "op": ">=", "value": "2026-10-01"},
        {"col": "日期", "op": "<=", "value": "2026-10-15"},
    ], "group_by": ["周(按日期折算)"],
        "metrics": [{"name": "良品区间合计", "func": "sum", "col": "良品产量kg"}]}
    _, _, prompts, calls = await ask(
        monkeypatch, "10月1日到10月15日，哪一周良品产量最高", {5398: (rows, fields)},
        plans=[plan], candidates=candidates,
    )
    assert len(calls) == 1
    block = prompts[0].split("## 全表精确计算结果")[1]
    assert "命中 3 / 6 行" in block
    ranking = [line for line in block.splitlines() if line.startswith("| 2026-W")]
    assert ranking == ["| 2026-W42 | 30 |", "| 2026-W41 | 20 |", "| 2026-W40 | 10 |"]


# ============ P1-4 前缀语义: 只对日期字段 / 整列约定 ============

SKU_FIELDS = PLAN_FIELDS + [("SKU", False, True, False)]


def sku_rows():
    rows = plan_rows()
    for i, r in enumerate(rows):
        r["SKU"] = "2026-10-SKU" if i < 3 else ("2026-10-05" if i < 6 else "OTHER")   # 后者: 长得像日期的 SKU, 但这列不是日期字段
    return rows


async def test_p1_4_month_prefix_match_is_for_date_fields_only(monkeypatch):
    plan = {"intent": "compute", "filters": [{"col": "SKU", "op": "==", "value": "2026-10"}]}
    _, _, prompts, _ = await ask(monkeypatch, "10月计划vs实产里SKU是2026-10的有几行", {5323: (sku_rows(), SKU_FIELDS)},
                                 plans=[plan], candidates=PLAN_DETAIL)
    assert "命中 0 / 12 行" in prompts[0]                      # 改前: 命中 3 行 (SKU「2026-10-SKU」被前缀吃进来)


@pytest.mark.parametrize(("op", "matched"), [("==", 0), ("!=", 12)])
async def test_p1_4_date_shaped_sku_uses_literal_equality(monkeypatch, op, matched):
    fields = PLAN_FIELDS + [("SKU日期", False, True, False)]
    rows = [dict(r, SKU日期="2026-10-05") for r in plan_rows()]
    plan = {"intent": "compute", "filters": [{"col": "SKU日期", "op": op, "value": "2026-10"}]}
    _, _, prompts, _ = await ask(
        monkeypatch, "10月计划vs实产里筛选SKU日期2026-10，列出记录", {5323: (rows, fields)},
        plans=[plan], candidates=PLAN_DETAIL,
    )
    block = prompts[0].split("## 全表精确计算结果")[1]
    assert f"命中 {matched} / 12 行" in block


async def test_p1_4_invalid_iso_date_is_not_a_month_match(monkeypatch):
    rows = [dict(r, 日期="2026-10-32") for r in plan_rows()]
    plan = {"intent": "compute", "filters": [{"col": "日期", "op": "==", "value": "2026-10"}]}
    _, _, prompts, calls = await ask(
        monkeypatch, "10月计划vs实产里列出日期", {5323: (rows, PLAN_FIELDS)}, plans=[plan], candidates=PLAN_DETAIL,
    )
    assert len(calls) == 2
    assert "命中 0 / 12 行" in prompts[0].split("## 全表精确计算结果")[1]


async def test_p1_4_hash_in_ordinary_notes_is_literal(monkeypatch):
    fields = PLAN_FIELDS + [("备注", False, True, False)]
    rows = [dict(r, 备注="#ABC") for r in plan_rows()]
    for value, matched in [("ABC", 0), ("#ABC", 12)]:
        plan = {"intent": "compute", "filters": [{"col": "备注", "op": "==", "value": value}]}
        _, _, prompts, _ = await ask(
            monkeypatch, f"10月计划vs实产里备注是{value}的记录", {5323: (rows, fields)},
            plans=[plan], candidates=PLAN_DETAIL,
        )
        block = prompts[0].split("## 全表精确计算结果")[1]
        assert f"命中 {matched} / 12 行" in block
        if matched:
            assert "| #ABC |" in block and '"备注": "#ABC"' in prompts[0]


async def test_p1_4_hash_prefix_is_stripped_only_when_the_whole_column_uses_it(monkeypatch):
    rows = code_rows("#")
    rows[0]["产品编码"] = "22002"                               # 一列里 #22002 和 22002 并存 = 两个不同的值
    plan = {"intent": "compute", "filters": [{"col": "产品编码", "op": "==", "value": "22002"}]}
    _, _, prompts, _ = await ask(monkeypatch, "10月计划vs实产里产品编码22002有几天", {5323: (rows, PLAN_FIELDS)},
                                 plans=[plan], candidates=PLAN_DETAIL)
    block = prompts[0].split("## 全表精确计算结果")[1]
    assert "命中 1 / 20 行" in block                           # 改前: 命中 7 行 (#22002 与 22002 混在一起)
    plan2 = {"intent": "compute", "filters": [{"col": "产品编码", "op": "==", "value": "#22002"}]}
    _, _, prompts2, _ = await ask(monkeypatch, "10月计划vs实产里产品编码#22002有几天", {5323: (rows, PLAN_FIELDS)},
                                  plans=[plan2], candidates=PLAN_DETAIL)
    assert "| #22002 |" in prompts2[0] and "命中 6 / 20 行" in prompts2[0]   # 混用的列不去前缀显示


async def test_p1_4_filtering_first_does_not_turn_a_mixed_code_column_into_escaped(monkeypatch):
    rows = code_rows("#")
    rows[-1]["产品编码"] = "22002"
    plan = {"intent": "compute", "filters": [
        {"col": "结论", "op": "==", "value": "计划了未生产"},
        {"col": "产品编码", "op": "==", "value": "22002"},
    ]}
    _, _, prompts, _ = await ask(
        monkeypatch, "10月计划vs实产里查询未生产的产品编码22002", {5323: (rows, PLAN_FIELDS)},
        plans=[plan], candidates=PLAN_DETAIL,
    )
    assert "命中 0 / 20 行" in prompts[0].split("## 全表精确计算结果")[1]


@pytest.mark.parametrize("value", ["DEF", "#DEF"])
async def test_p1_4_double_hash_value_is_not_matched_by_single_hash_or_bare_value(monkeypatch, value):
    rows = code_rows()
    for r in rows:
        r["产品编码"] = "##DEF"                                  # 真实值就是两个 #
    plan = {"intent": "compute", "filters": [{"col": "产品编码", "op": "==", "value": value}]}
    _, _, prompts, _ = await ask(monkeypatch, "10月计划vs实产里产品编码" + value + "有几天", {5323: (rows, PLAN_FIELDS)},
                                 plans=[plan], candidates=PLAN_DETAIL)
    assert "命中 0 / 20 行" in prompts[0]                      # 改前: 都命中 ##DEF


async def test_p1_4_literal_double_hash_is_displayed_and_can_be_queried(monkeypatch):
    rows = [dict(r, 产品编码="##DEF") for r in code_rows()]
    plan = {"intent": "compute", "filters": [{"col": "产品编码", "op": "==", "value": "##DEF"}]}
    _, _, prompts, _ = await ask(
        monkeypatch, "10月计划vs实产里产品编码##DEF有几天", {5323: (rows, PLAN_FIELDS)},
        plans=[plan], candidates=PLAN_DETAIL,
    )
    block = prompts[0].split("## 全表精确计算结果")[1]
    assert "命中 20 / 20 行" in block and "| ##DEF |" in block
    assert '"产品编码": "##DEF"' in prompts[0]


def _disjoint_filter_rows():
    rows = code_rows()[:2]
    rows[0].update(产品编码="#A-01", 计划产线="A线", 日期="2026-10-01", 结论="实际A")
    rows[1].update(产品编码="#B-02", 计划产线="B线", 日期="2026-11-01", 结论="实际B")
    return rows


@pytest.mark.parametrize(("column", "value"), [
    ("产品编码", "#A-01"), ("产品编码", "A-01"), ("日期", "2026-10"),
])
@pytest.mark.parametrize("reverse_order", [False, True])
async def test_review_7_existing_values_can_have_an_empty_intersection(monkeypatch, column, value, reverse_order):
    filters = [{"col": "计划产线", "op": "==", "value": "B线"}, {"col": column, "op": "==", "value": value}]
    if reverse_order:
        filters.reverse()
    plan = {"intent": "compute", "filters": filters}
    alternate = {"intent": "compute", "filters": [filters[0]]}
    question = f"计划明细里 B 线且{column}为{value}有几行"
    tables = {5323: (_disjoint_filter_rows(), PLAN_FIELDS)}
    _, _, prompts, calls = await ask(monkeypatch, question, tables, plans=[plan, alternate], candidates=PLAN_DETAIL)
    assert len(calls) == 1
    block = prompts[0].split("## 全表精确计算结果")[1]
    assert "命中 0 / 2 行" in block and "无满足条件的行" in block
    _, _, cached, cache_calls = await ask(monkeypatch, question, tables, plans=[alternate], candidates=PLAN_DETAIL)
    assert cache_calls == [] and "命中 0 / 2 行" in cached[0].split("## 全表精确计算结果")[1]


async def test_review_7_missing_value_is_validated_after_an_empty_intersection(monkeypatch):
    filters = [{"col": "计划产线", "op": "==", "value": "B线"},
               {"col": "产品编码", "op": "==", "value": "A-01"}]
    invalid = {"intent": "compute", "filters": [*filters, {"col": "结论", "op": "==", "value": "未知"}]}
    corrected = {"intent": "compute", "filters": filters}
    _, _, prompts, calls = await ask(
        monkeypatch, "计划明细里 B 线的 A-01 产品有几行", {5323: (_disjoint_filter_rows(), PLAN_FIELDS)},
        plans=[invalid, corrected], candidates=PLAN_DETAIL,
    )
    assert len(calls) == 2 and "字段「结论」没有取值「未知」" in calls[1]
    assert "实际A / 实际B" in calls[1]
    assert "命中 0 / 2 行" in prompts[0].split("## 全表精确计算结果")[1]


# ============ P1-5 计划缓存: 连续两次 / 同表头不同取值 ============

async def test_p1_5_zero_row_answer_is_the_same_the_second_time_it_is_asked(monkeypatch):
    plan = {"intent": "compute", "filters": [{"col": "结论", "op": "==", "value": "停线"}]}
    q = "10月计划vs实产里有没有停线的品项？"
    for _ in range(2):
        _, _, prompts, calls = await ask(monkeypatch, q, {5323: (plan_rows(), PLAN_FIELDS)}, plans=[plan],
                                     candidates=PLAN_DETAIL)
        assert "命中 0 / 12 行" in prompts[0] and "行级计算未完成" not in prompts[0]   # 改前: 第二次 uncomputed
        assert len(calls) == 2 and "上一次的计划无效" in calls[1]
        assert not row_level_qa._PLAN_CACHE


@pytest.mark.parametrize("change", ["upload_id", "column", "field_role", "filter_values"])
async def test_p1_5_valid_plan_is_reused_only_for_a_compatible_upload(monkeypatch, change):
    q = "10月计划vs实产里哪些是计划外生产？"
    plan_a = {"intent": "compute", "filters": [{"col": "结论", "op": "==", "value": "计划外生产"}]}
    rows = plan_rows()
    _, _, p1, calls1 = await ask(monkeypatch, q, {5323: (rows, PLAN_FIELDS)}, plans=[plan_a], candidates=PLAN_DETAIL)
    assert len(calls1) == 1 and "命中 9 / 12 行" in p1[0]
    plan_b = {"intent": "compute", "filters": [{"col": "结论", "op": "==", "value": "Outside"}]}
    _, _, hit, hit_calls = await ask(monkeypatch, q, {5323: (rows, PLAN_FIELDS)}, plans=[plan_b], candidates=PLAN_DETAIL)
    assert hit_calls == [] and "命中 9 / 12 行" in hit[0]

    uid = 6999 if change == "upload_id" else 5323
    # 旧值仍可通过严格检查，唯有 filter_values 组测试缓存失效后重规划。
    other = [dict(r, 结论="计划外生产" if i == 0 and change != "filter_values" else "Outside")
             for i, r in enumerate(rows)]
    fields = PLAN_FIELDS
    if change == "column":
        other = [dict(r, 备注="新字段") for r in other]
        fields = PLAN_FIELDS + [("备注", False, False, False)]
    if change == "field_role":
        fields = [(n, m, False if n == "计划产线" else d, t) for n, m, d, t in PLAN_FIELDS]
    candidates = [replace(PLAN_DETAIL[0], upload_id=uid, field_names=[f[0] for f in fields])]
    events, _, p2, calls = await ask(monkeypatch, q, {uid: (other, fields)}, plans=[plan_b], candidates=candidates)
    expected = 12 if change == "filter_values" else 11
    assert any(e == "data_source" and d["uploadId"] == uid for e, d in events)
    assert len(calls) == 1 and f"命中 {expected} / 12 行" in p2[0].split("## 全表精确计算结果")[1]
    assert "上一次的计划无效" not in calls[0]
    assert "行级计算未完成" not in p2[0]


@pytest.mark.parametrize("intent", ["none", "no_metric"])
async def test_p1_5_unvalidated_planner_outcomes_are_not_cached(monkeypatch, intent):
    q = "10月计划vs实产里哪些是计划外生产？"
    rows = plan_rows()
    _, done, prompts, calls = await ask(
        monkeypatch, q, {5323: (rows, PLAN_FIELDS)}, plans=[{"intent": intent}], candidates=PLAN_DETAIL,
    )
    assert len(calls) == 1
    if intent == "no_metric":
        assert done["source"] == "field_metric_mismatch_refusal"
    else:
        assert "## 全表精确计算结果" not in prompts[0]
    plan = {"intent": "compute", "filters": [{"col": "结论", "op": "==", "value": "计划外生产"}]}
    for uid in [6999, 5323]:
        candidates = [replace(PLAN_DETAIL[0], upload_id=uid)]
        _, _, computed, again = await ask(monkeypatch, q, {uid: (rows, PLAN_FIELDS)}, plans=[plan], candidates=candidates)
        assert len(again) == 1 and "命中 9 / 12 行" in computed[0].split("## 全表精确计算结果")[1]


# ============ P2 Top 5 相关性 ============

SCRAP_FIELDS = [("日期", False, False, True), ("产线", False, True, False), ("报废重量", True, False, False)]
SCRAP_CAND = [UploadCandidate(5397, "报废记录", "报废记录.xlsx", 6, [f[0] for f in SCRAP_FIELDS])]


@pytest.mark.parametrize("question", ["各产线的损耗是多少", "哪条产线损耗最高"])
async def test_p2_single_measure_table_keeps_top5_when_the_column_is_not_recognised(monkeypatch, question):
    rows = [{"日期": f"2026-10-0{i + 1}", "产线": f"线{i % 3 + 1}", "报废重量": 10.0 + i} for i in range(6)]
    get_cache().set(5397, _bundle(SCRAP_FIELDS, ["报废重量"], "报废重量",
                                  {"产线": [{"label": "线1", "total": 25.0}, {"label": "线2", "total": 20.0}]}))
    events, _, _, _ = await ask(monkeypatch, question, {5397: (rows, SCRAP_FIELDS)}, candidates=SCRAP_CAND)
    top = next(c for e, charts in events if e == "charts" for c in charts
               if c["title"] == "Top 5 产线 (按 报废重量)")
    assert top["option"]["xAxis"]["data"] == ["线1", "线2"]
    assert top["option"]["series"][0]["data"] == [25.0, 20.0]


BUY_FIELDS = [("日期", False, False, True), ("产线", False, True, False), ("采购金额", True, False, False),
              ("废品kg", True, False, False)]
BUY_CAND = [UploadCandidate(5396, "采购与废品", "采购与废品.xlsx", 6, [f[0] for f in BUY_FIELDS])]


async def test_p2_explicit_other_metric_beats_the_money_default(monkeypatch):
    rows = [{"日期": f"2026-10-0{i + 1}", "产线": f"线{i % 3 + 1}", "采购金额": 100.0 + i * 7 % 5, "废品kg": [5.0, 9.0, 7.0, 12.0, 6.0, 10.0][i]}
            for i in range(6)]
    get_cache().set(5396, _bundle(BUY_FIELDS, ["采购金额", "废品kg"], "采购金额",
                                  {"产线": [{"label": "线1", "total": 250.0}, {"label": "线2", "total": 200.0}]}))
    events, _, _, _ = await ask(monkeypatch, "10月哪天废品最多？", {5396: (rows, BUY_FIELDS)}, candidates=BUY_CAND)
    assert not any("采购金额" in t for t in _chart_titles(events)), _chart_titles(events)   # 改前: Top 5 产线 (按 采购金额)
    assert "废品kg" in _series_names(events)


def test_p2_relevance_cases():
    from smartbi.services.question_chart import top5_chart_relevant as rel
    assert rel("哪条产线损耗最高", ["报废重量"], "报废重量") is True                  # 单指标: 问什么都是它
    assert rel("各产线的损耗是多少", ["报废重量"], "报废重量") is True                # 单指标 (问法里没有排名词也保留)
    assert rel("各产线的损耗是多少", ["人数", "良品kg"], "人数") is False             # 多指标、识别不到、非排名非概览: 不出
    assert rel("当日说明有什么特殊情况", ["人数", "良品kg"], "人数") is False          # 与指标无关
    assert rel("哪个产线最高", ["人数", "良品kg"], "人数") is True                      # 排名类问法: 识别不到列也保留
    assert rel("10月哪天废品最多", ["采购金额", "废品kg"], "采购金额") is False          # 明确问另一列
    assert rel("销售情况怎么样", ["销售额", "人数"], "销售额") is True                  # 金额默认口径


# ============ 非阻塞建议: 缓存包新增/删除指标列也算过期; 补「A 产品一共 52 卷」 ============

def test_nb_bundle_with_a_removed_or_added_measure_is_stale_even_if_shared_roles_match():
    from smartbi.services.upload_aggregate_cache import bundle_is_stale_for_field_meta as stale
    base = [("日期", False, False, True), ("良品kg", True, False, False)]

    def meta(rows):
        return [{"original_name": n, "is_measure": m, "is_dimension": d, "is_time": t} for n, m, d, t in rows]

    assert stale(_bundle(base, [], None, {}), meta(base + [("废品kg", True, False, False)])) is True
    assert stale(_bundle(base, [], None, {}), meta(base[:1])) is True
    assert stale(_bundle(base, [], None, {}), meta(base)) is False
