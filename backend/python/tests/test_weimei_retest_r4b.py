"""REVIEW-1981 的阻塞项反例 (Codex 独立审查 5 条 P1 + 1 条 P2 + 非阻塞建议)。

同 test_weimei_retest_r4: 断言跑在 general_analysis_stream 真实入口 (假连接 + 假 LLM)。
每条注释写明「改前」读数 (在 7a59700fbf 上跑出来的)。
"""
from __future__ import annotations

import pytest

from smartbi.services.upload_aggregate_cache import get_cache
from smartbi.services.upload_auto_select import UploadCandidate
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


async def test_p1_2_plan_vs_actual_rule_does_not_steal_a_question_about_another_metric(monkeypatch):
    plan = {"intent": "compute", "filters": [{"col": "日期", "op": "==", "value": "2026-10"}],
            "metrics": [{"name": "计划产量", "func": "sum", "col": "计划产量kg"},
                        {"name": "实际产量", "func": "sum", "col": "实际产量kg"}]}
    _, _, prompts, calls = await ask(monkeypatch, "10月计划与实际产量kg差多少",
                                     {5399: (plan_output_rows(), PLAN_OUTPUT_FIELDS)}, plans=[plan], candidates=PLAN_OUTPUT)
    assert len(calls) == 1                                  # 改前: 0 次 (规则抢走), 答 150/30 并标成品项数
    assert "品项" not in prompts[0] and "1200" in prompts[0].replace(",", "")    # 十月计划 1200 / 实际 900


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


async def test_p1_3_week_rollup_uses_the_mean_when_asked_and_never_sums_a_temperature(monkeypatch):
    # 求和: W41=70 < W42=108; 平均: W41=35 < W42=36。「平均值最高」要的是 W42 的 36, 求和口径会写成 108
    _, _, prompts, calls = await ask(monkeypatch, "10月哪一周温度平均值最高？", {5398: (temp_rows(), TEMP_FIELDS)},
                                     plans=[{"intent": "none"}], candidates=TEMP_CAND)
    block = prompts[0]
    assert "温度(按周平均)" in block and "| 2026-W42 | 36 |" in block and "108" not in block
    assert calls == []
    # 没说平均/合计、温度也不是可相加的量: 规则不替用户选口径, 交给规划器
    _, _, _, calls2 = await ask(monkeypatch, "10月哪一周温度最高？", {5398: (temp_rows(), TEMP_FIELDS)},
                                plans=[{"intent": "none"}], candidates=TEMP_CAND)
    assert len(calls2) == 1


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


@pytest.mark.parametrize("value", ["DEF", "#DEF"])
async def test_p1_4_double_hash_value_is_not_matched_by_single_hash_or_bare_value(monkeypatch, value):
    rows = code_rows()
    for r in rows:
        r["产品编码"] = "##DEF"                                  # 真实值就是两个 #
    plan = {"intent": "compute", "filters": [{"col": "产品编码", "op": "==", "value": value}]}
    _, _, prompts, _ = await ask(monkeypatch, "10月计划vs实产里产品编码" + value + "有几天", {5323: (rows, PLAN_FIELDS)},
                                 plans=[plan], candidates=PLAN_DETAIL)
    assert "命中 0 / 20 行" in prompts[0]                      # 改前: 都命中 ##DEF


# ============ P1-5 计划缓存: 连续两次 / 同表头不同取值 ============

async def test_p1_5_zero_row_answer_is_the_same_the_second_time_it_is_asked(monkeypatch):
    plan = {"intent": "compute", "filters": [{"col": "结论", "op": "==", "value": "停线"}]}
    q = "10月计划vs实产里有没有停线的品项？"
    for _ in range(2):
        _, _, prompts, _ = await ask(monkeypatch, q, {5323: (plan_rows(), PLAN_FIELDS)}, plans=[plan],
                                     candidates=PLAN_DETAIL)
        assert "命中 0 / 12 行" in prompts[0] and "行级计算未完成" not in prompts[0]   # 改前: 第二次 uncomputed


async def test_p1_5_cached_plan_is_not_reused_on_another_upload_with_same_headers_but_other_values(monkeypatch):
    q = "10月计划vs实产里哪些是计划外生产？"
    plan_a = {"intent": "compute", "filters": [{"col": "结论", "op": "==", "value": "计划外生产"}]}
    _, _, p1, _ = await ask(monkeypatch, q, {5323: (plan_rows(), PLAN_FIELDS)}, plans=[plan_a], candidates=PLAN_DETAIL)
    assert "命中 9 / 12 行" in p1[0]
    other = [dict(r, 结论="Outside") for r in plan_rows()]
    plan_b = {"intent": "compute", "filters": [{"col": "结论", "op": "==", "value": "Outside"}]}
    _, _, p2, calls = await ask(monkeypatch, q, {5323: (other, PLAN_FIELDS)}, plans=[plan_b], candidates=PLAN_DETAIL)
    assert len(calls) == 1 and "命中 12 / 12 行" in p2[0]      # 缓存的 A 计划对这份无效 -> 重新规划, 不套用
    assert "行级计算未完成" not in p2[0]


# ============ P2 Top 5 相关性 ============

SCRAP_FIELDS = [("日期", False, False, True), ("产线", False, True, False), ("报废重量", True, False, False)]
SCRAP_CAND = [UploadCandidate(5397, "报废记录", "报废记录.xlsx", 6, [f[0] for f in SCRAP_FIELDS])]


async def test_p2_single_measure_table_keeps_top5_when_the_column_is_not_recognised(monkeypatch):
    rows = [{"日期": f"2026-10-0{i + 1}", "产线": f"线{i % 3 + 1}", "报废重量": 10.0 + i} for i in range(6)]
    get_cache().set(5397, _bundle(SCRAP_FIELDS, ["报废重量"], "报废重量",
                                  {"产线": [{"label": "线1", "total": 25.0}, {"label": "线2", "total": 20.0}]}))
    events, _, _, _ = await ask(monkeypatch, "各产线的损耗是多少", {5397: (rows, SCRAP_FIELDS)}, candidates=SCRAP_CAND)
    assert any(t.startswith("Top 5 产线") and "报废重量" in t for t in _chart_titles(events)), _chart_titles(events)


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
