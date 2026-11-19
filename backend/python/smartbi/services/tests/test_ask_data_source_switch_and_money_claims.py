"""问数据 (AI) 味美实测缺陷 A7 / A9 的回归断言 (2026-11-19)。

A7  同一对话切换数据源后, 新问题原样复读上一题答案 —— 上一轮 (另一份上传) 被当
    「延续上一轮的实体和数字」喂给了这一轮。修法: 会话 parent 只在它产生时的数据源上有效。
A9  数据里没有金额列, 行动建议却写「减少资源浪费 5-8 万/月」 —— 提示词还强制要求写
    「数字化收益 / 节约成本 5-10万/月」。修法: 提示词不再要求编金额 + 建议段金额后置校验。
"""
from __future__ import annotations

import inspect

from smartbi.api import chat as chat_mod
from smartbi.services.chat_session_service import parent_for_upload
from smartbi.services.llm_answer_cache import compute_cache_key
from smartbi.services.llm_guard import (
    ACTION_REC_GUARD_CLAUSE,
    MONEY_NEEDS_COST_DATA,
    strip_unsupported_money_claims,
)

# --------------------------------------------------------------------------- A7


def _parent(upload_id):
    return {
        "parent_query": "哪一周计划品项达成率最低？",
        "parent_answer_summary": "W42 0.69",
        "parent_upload_id": upload_id,
        "turns_history": [{"q": "q", "a_summary": "W42 0.69"}],
    }


def test_a7_parent_from_other_upload_is_dropped():
    # 5335(按周) 上问的, 切到 5323(计划vs实产) 再问: 不能带上一题
    assert parent_for_upload(_parent(5335), 5323) is None


def test_a7_same_upload_follow_up_keeps_parent():
    # 阴性对照: 同源追问仍要带上一轮 (承上启下不能被一并砍掉)
    p = _parent(5335)
    assert parent_for_upload(p, 5335) is p
    assert parent_for_upload(p, "5335") is p  # 路径参数是字符串也认同一份


def test_a7_unknown_upload_on_either_side_is_kept():
    # 综合分析写 None (全厂口径); 请求没带数据源 -> 不能凭空判定「换源」
    p_none = _parent(None)
    assert parent_for_upload(p_none, 5323) is p_none
    p = _parent(5335)
    assert parent_for_upload(p, None) is p
    assert parent_for_upload(None, 5323) is None


def test_a7_llm_answer_cache_key_contains_upload_and_question():
    base = compute_cache_key("F1", "哪些产品没按计划完成", 5335)
    assert base != compute_cache_key("F1", "哪些产品没按计划完成", 5323)  # 切源必须重算
    assert base != compute_cache_key("F1", "哪个产品最多", 5335)  # 换问题必须重算
    assert base == compute_cache_key("F1", "哪些产品没按计划完成", 5335)  # 同源同问仍命中缓存


def test_a7_stream_entry_really_calls_the_upload_check():
    # 接线: 这个检查必须在真实入口 general_analysis_stream 里被调用 (单测函数对, 入口没调等于没修)
    src = inspect.getsource(chat_mod.general_analysis_stream)
    assert "parent_for_upload(" in src
    # 且发生在 lookup 之后、使用 chat_session_parent 之前
    assert src.index("_svc.lookup(") < src.index("parent_for_upload(") < src.index("build_context_block(chat_session_parent)")


# --------------------------------------------------------------------------- A9

ANSWER = (
    "## 结论\n计划了但没生产的产品里 22002 最多，共 20 个日-品项。\n\n"
    "## 行动建议\n"
    "- 生产主管本周内核对 22002 的排产，减少资源浪费，预计节约 5-8 万/月\n"
    "- 计划员复核 5万人次的排班表\n"
)
NO_MONEY_DATA = "产品 22002 计划天数 20 良品 317,273.3 kg 废品 1,381 kg 人数 5"


def test_a9_invented_money_in_recommendation_is_rewritten():
    out, removed = strip_unsupported_money_claims(ANSWER, NO_MONEY_DATA)
    assert removed == ["5-8 万/月"]
    assert "5-8" not in out
    assert MONEY_NEEDS_COST_DATA in out
    # 其余内容不动
    assert "共 20 个日-品项" in out and "22002" in out


def test_a9_money_backed_by_data_is_kept():
    # 阴性对照: 数据里真有金额列 (125,000 元), 建议引用它 -> 不能被误杀
    data = "产品 22002 报废成本 125,000 元 良品 317273 kg"
    answer = "## 行动建议\n- 压缩报废，预计每月可节约 12.5万元\n- 复核 3000 元以内的小额领料"
    out, removed = strip_unsupported_money_claims(answer, data)
    assert "12.5万元" in out
    assert [r for r in removed if "12.5" in r] == []
    # 同一句里 3000 元数据里没有 -> 只改写这一处
    assert removed == ["3000 元"]


def test_a9_not_money_units_are_not_touched():
    out, removed = strip_unsupported_money_claims(ANSWER, NO_MONEY_DATA)
    assert "5万人次" in out  # 「万人」是人数不是金额
    assert "5万人次" not in removed


def test_a9_only_recommendation_section_is_checked():
    answer = "## 结论\n毛利约 9万元（按单价×产量算出）\n\n## 行动建议\n- 复核排产"
    out, removed = strip_unsupported_money_claims(answer, "单价 3 产量 30000")
    assert out == answer and removed == []


def test_a9_prompt_no_longer_demands_fabricated_benefit():
    # 修前: 条款强制要求「数字化收益 … 节约成本 5-10万/月」, 数据没有也得编
    assert "5-10万/月" not in ACTION_REC_GUARD_CLAUSE
    assert "数字化收益" not in ACTION_REC_GUARD_CLAUSE
    assert "需要成本数据" in ACTION_REC_GUARD_CLAUSE
    assert "收益区间" in ACTION_REC_GUARD_CLAUSE  # 有数据支撑时仍可写区间 (旧闸 test_llm_guard 依赖)


def test_a9_stream_entry_really_applies_the_money_check_and_overrides_answer():
    src = inspect.getsource(chat_mod.general_analysis_stream)
    assert "strip_unsupported_money_claims(" in src
    # 校验在写缓存 / done 之前, 并且 done 带 answer_replaced 让前端覆盖已流出的文字
    assert src.index("strip_unsupported_money_claims(") < src.index('"answer_replaced"')
    assert src.index("strip_unsupported_money_claims(") < src.index("_LAC(_lac_w_pool).set(")
