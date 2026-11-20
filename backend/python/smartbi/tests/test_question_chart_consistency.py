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


# ---------------------------------------------------------------------------
# 问题 51 余项 (LSM-COSTBI-Q51-SHARE-TEXT, 11-19 prod F006 实测)：图是 C 43.99% / B 28.45% /
# A 27.56%，文字结论却写「各产品占比均为 100%」。原因：百分数白名单里无条件放了 100.0 (以及
# 「全部合计 = 100」)，LLM 拿每个产品自己当分母算出的 100% 被当成「对得上」原样保留。
# ---------------------------------------------------------------------------
_LLM_ALL_100 = "各产品的销售额占比均为 100%，分布一致。"


def test_all_100_percent_text_is_replaced_and_matches_chart_shares():
    """真实入口：chat.py 流式/非流式两条路径都调用 _reconcile_answer_with_question_chart。"""
    from smartbi.api.chat import _reconcile_answer_with_question_chart

    chart = _share_chart()
    qc = chart["question_chart"]
    assert not answer_matches_chart(_LLM_ALL_100, qc)
    fixed, changed = _reconcile_answer_with_question_chart(_LLM_ALL_100, [{"type": "bar"}, chart])
    assert changed is True
    assert "100%" not in fixed and "均为" not in fixed
    shares = qc["shares"]
    assert len(shares) == 3
    # 文字里的 3 项占比与图同源 (两位小数逐字一致)，合计约等于 100
    for s in shares:
        assert f"{s['pct']:.2f}%" in fixed
    assert sum(s["pct"] for s in shares) == pytest.approx(100.0, abs=0.05)
    assert all(s["pct"] < 100 for s in shares)


def test_per_item_100_percent_without_uniform_wording_is_replaced():
    """没有「均为」字样、逐项写 100% (每项拿自己当分母) 也不能放行：100% 不在白名单里。"""
    chart = _share_chart()
    text = "演示·藤椒掌中宝占比 100%；演示·酱卤牛肉占比 100%；演示·香辣鸡丁占比 100%。"
    assert not answer_matches_chart(text, chart["question_chart"])
    fixed, changed = reconcile_answer_with_chart(text, chart)
    assert changed and "100%" not in fixed


def test_uniform_share_claim_must_hold_for_every_item():
    chart = _share_chart()
    qc = chart["question_chart"]
    top = qc["shares"][0]["pct"]
    # 数字本身是图里的一个占比，但「均为」把它套到了每一项上 —— 不对
    claim = f"各产品占比均为 {top:.2f}%。"
    assert not answer_matches_chart(claim, qc)
    fixed, changed = reconcile_answer_with_chart(claim, chart)
    assert changed and "均为" not in fixed


def test_partial_cumulative_share_still_allowed():
    """前 k 项合计 (k < 项数) 仍是合法写法，不误伤。"""
    chart = _share_chart()
    shares = chart["question_chart"]["shares"]
    top2 = shares[0]["pct"] + shares[1]["pct"]
    text = f"前两项合计占 {top2:.1f}%，其余占 {100 - top2:.1f}%。"
    assert reconcile_answer_with_chart(text, chart) == (text, False)
