"""「全部数据」按问题选表 (味美 11-19 B3)。

样本是味美 FOOD_0000_001 prod 上 8 张表的真实列名 / 表名 (2026-11-19 从
smart_bi_pg_field_definitions 读出)。修复前没选数据源时一律取行数最多的
机台停机表 (177 行), 下面每条断言在修复前都会选成 5320。
"""
import pytest

from smartbi.services.upload_auto_select import (
    UploadCandidate,
    auto_select_notice,
    choose_upload,
    score_candidate,
)

WEIMEI = [
    UploadCandidate(5319, "日汇总", "味美2026年10月生产补充数据.xlsx", 27,
                    ["日期", "周", "人数", "出勤率", "批次数", "出成率(良品/(良品+废品+次品))",
                     "产能利用率(良品/理论)", "良品kg", "废品kg", "次品kg", "理论产能kg", "当日说明"]),
    UploadCandidate(5320, "机台开机停机", "味美10月-02-机台开机停机.xlsx", 177,
                    ["日期", "实际开机", "实际开机分钟", "机台", "机台组", "计划开机", "计划开机分钟",
                     "停机次数", "停机分钟", "停机时段", "停机原因/说明"]),
    UploadCandidate(5321, "卷膜消耗", "味美10月-03-卷膜消耗.xlsx", 52,
                    ["日期", "周", "卷膜编码", "用量(卷)"]),
    UploadCandidate(5323, "计划vs实产_品项", "味美10月-07-计划vs实产_品项.xlsx", 110,
                    ["日期", "周", "产品编码", "结论", "计划产线", "计划内", "实产良品kg"]),
    UploadCandidate(5327, "拌馅日汇总", "味美10月-06-拌馅_日汇总.xlsx", 39,
                    ["日期", "产品编码", "产品", "拌馅趟次", "平均肉温", "肉温超出3到5度趟次",
                     "小料kg", "加水L", "总馅重kg"]),
    UploadCandidate(5330, "和面日汇总", "味美10月-05-和面_日汇总.xlsx", 60,
                    ["日期", "产品编码", "未写面粉批号趟次", "车间", "和面趟次", "面粉kg", "加水kg",
                     "平均和面分钟", "平均面团温度"]),
    UploadCandidate(5334, "计划品项达成率_按日", "味美10月-08a-计划品项达成率_按日.xlsx", 27,
                    ["日期", "已产出计划品项数", "计划品项达成率", "计划外生产品项数", "备注",
                     "原表计划品项数", "实产良品kg"]),
    UploadCandidate(5335, "计划品项达成率_按周", "味美10月-08b-计划品项达成率_按周.xlsx", 5,
                    ["周", "已产出计划品项数", "计划品项达成率", "计划外生产品项数", "原表计划品项数",
                     "实产良品kg", "备注"]),
]


@pytest.mark.parametrize("question, expected", [
    ("10月一共生产了多少批？总产量多少？", 5319),
    ("10月总产量多少公斤？整体出成率多少？", 5319),
    ("10月哪天出成率最低？是多少？当天废品多少公斤？", 5319),
    ("10月哪台机停机时间最长？主要是什么原因？", 5320),
    ("10月卷膜用量最大的是哪种？全月一共用了多少卷？", 5321),
    ("10月哪些产品计划了但没有生产？哪个最多？", 5323),
    ("哪些产品没按计划完成？", 5323),
    ("10月拌馅肉温有没有超出3到5度的？是哪几天、哪个产品、几趟？", 5327),
    ("有多少面粉没写批号？", 5330),
    ("10月哪天计划产量最高？", 5334),
    ("按日看，原表计划品项数最多的是哪一天？那天已产出几个？", 5334),
    ("哪一周计划品项达成率最低？是多少？", 5335),
])
def test_weimei_questions_pick_the_table_that_holds_the_answer(question, expected):
    picked = choose_upload(question, WEIMEI)
    assert picked is not None, question
    assert picked.upload_id == expected, (question, picked.label)
    # 阴性对照: 修复前的选法 (行数最多) 对这些题都选错, 证明断言能暴露缺陷
    largest = max(WEIMEI, key=lambda c: c.row_count)
    if expected != largest.upload_id:
        assert largest.upload_id != picked.upload_id


def test_unrelated_question_returns_none_so_caller_keeps_old_fallback():
    assert choose_upload("你好", WEIMEI) is None
    assert choose_upload("", WEIMEI) is None


def test_weekly_grain_beats_daily_twin_table():
    q = "哪一周计划品项达成率最低？"
    assert score_candidate(q, WEIMEI[7]) > score_candidate(q, WEIMEI[6])


def test_notice_names_the_table():
    assert "卷膜消耗" in auto_select_notice(WEIMEI[2])


def test_chat_stream_uses_question_matched_upload_for_both_cache_and_data_load():
    """event_stream 是产品入口 (整个 SSE 生成器无法在单测里端到端驱动), 按 AST 钉住接线:
    没选数据源时先调 pick_upload_for_question; 取数据那一步用选中的上传,
    而不是再取一次「行数最多」; 并把选中的表通过 data_source 事件告诉前端。"""
    import ast
    from pathlib import Path

    src = (Path(__file__).resolve().parents[1] / "smartbi" / "api" / "chat.py").read_text(encoding="utf-8")
    tree = ast.parse(src)
    stream = next(n for n in ast.walk(tree) if isinstance(n, ast.AsyncFunctionDef)
                  and n.name == "event_stream" and "pick_upload_for_question" in ast.unparse(n))
    code = ast.unparse(stream)
    assert "pick_upload_for_question(" in code
    assert "'data_source'" in code or '"data_source"' in code
    # 取数据: upload_id = validated_upload_id or (_auto_pick.upload_id ...)
    loads = [n for n in ast.walk(stream) if isinstance(n, ast.Assign)
             and any(isinstance(t, ast.Name) and t.id == "upload_id" for t in n.targets)
             and "validated_upload_id" in ast.unparse(n.value)]
    assert any("_auto_pick" in ast.unparse(n.value) for n in loads), [ast.unparse(n) for n in loads]
    # 自动选表必须在「行数最多」兜底之前
    assert code.index("pick_upload_for_question(") < code.index("ORDER BY row_count DESC")
