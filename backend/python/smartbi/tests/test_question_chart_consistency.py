"""LSM-COSTBI-F3 (问题 51 返工)：AI 问答出图 — 文字结论与图同源、占比口径、合计约等于 100%。

BP 第 35 版现象：LLM 文字「产品A 65.0%」，同一张环形图是「DEMO产品C 43.99%」。
"""
import pytest

from smartbi.services.question_chart import (
    answer_matches_chart,
    build_question_chart,
    chart_conclusion,
    reconcile_answer_with_chart,
)
from smartbi.tests.test_question_chart import _sales_df

_LLM_WRONG = "按销售金额看，产品A占 65.0%，排第一；产品B占 20.0%。"
_QUESTION = "各产品的销售额占比是多少？"


def _share_chart(df=None):
    return build_question_chart(_QUESTION, _sales_df() if df is None else df)


def test_chart_shares_use_one_total_and_sum_to_100():
    qc = _share_chart()["question_chart"]
    shares = qc["shares"]
    assert [s["label"] for s in shares] == ["演示·藤椒掌中宝", "演示·酱卤牛肉", "演示·香辣鸡丁"]
    total = 333.56 + 600.0 + 900.0 + 460.0
    assert qc["total"] == pytest.approx(total)
    assert shares[0]["pct"] == pytest.approx(933.56 / total * 100, abs=0.01)
    assert sum(s["pct"] for s in shares) == pytest.approx(100.0, abs=0.05)
    assert all(0 < s["pct"] <= 100 for s in shares)  # 没有 62031.8 这种乱数


def test_conclusion_numbers_all_come_from_chart_data():
    chart = _share_chart()
    qc = chart["question_chart"]
    text = chart_conclusion(qc)
    top = qc["shares"][0]
    assert f"{top['label']}占 {top['pct']:.2f}%" in text
    assert answer_matches_chart(text, qc)  # 代码结论自己一定对得上


def test_llm_text_disagreeing_with_chart_is_replaced_by_code_conclusion():
    chart = _share_chart()
    assert not answer_matches_chart(_LLM_WRONG, chart["question_chart"])
    fixed, changed = reconcile_answer_with_chart(_LLM_WRONG, chart)
    assert changed is True
    assert "65.0%" not in fixed and "产品A" not in fixed
    top = chart["question_chart"]["shares"][0]
    assert f"{top['pct']:.2f}%" in fixed


def test_llm_text_matching_chart_is_kept_verbatim():
    chart = _share_chart()
    pct = chart["question_chart"]["shares"][0]["pct"]
    good = f"演示·藤椒掌中宝占 {pct:.1f}%，是最大一项。"  # 一位小数在容差内
    assert reconcile_answer_with_chart(good, chart) == (good, False)


def test_answer_without_percent_or_without_question_chart_untouched():
    chart = _share_chart()
    assert reconcile_answer_with_chart("藤椒掌中宝最高。", chart) == ("藤椒掌中宝最高。", False)
    assert reconcile_answer_with_chart(_LLM_WRONG, None) == (_LLM_WRONG, False)
    assert reconcile_answer_with_chart(_LLM_WRONG, {"type": "bar"}) == (_LLM_WRONG, False)


def test_chat_entry_helper_replaces_llm_answer_using_charts_list():
    """接线：chat.py 流式/非流式路径调用的就是这个 helper。"""
    from smartbi.api.chat import _reconcile_answer_with_question_chart

    chart = _share_chart()
    fixed, changed = _reconcile_answer_with_question_chart(_LLM_WRONG, [{"type": "bar"}, chart])
    assert changed and "65.0%" not in fixed
    assert _reconcile_answer_with_question_chart(_LLM_WRONG, [{"type": "bar"}]) == (_LLM_WRONG, False)


def test_negative_values_give_no_share_and_say_so():
    df = _sales_df()
    df.loc[[1, 4], "销售金额"] = -100.0  # 香辣鸡丁整体退货冲减成负数
    qc = _share_chart(df)["question_chart"]
    assert all(s["pct"] is None for s in qc["shares"])
    text = chart_conclusion(qc)
    assert "未计算占比" in text and "%" not in text.replace("未计算占比", "")
    assert not answer_matches_chart("藤椒掌中宝占 65.0%", qc)
