"""意图飞轮只读脚本 (导出 / 意图缺口汇总) 的自检: 导出 SQL 不读原文列, 反应推导三态, 缺口按相近说法聚类且不把好答案算缺口。"""
from scripts import intent_flywheel_export as export
from scripts import intent_flywheel_gaps as gaps


def test_export_self_test_passes():
    assert export.self_test() == []


def test_export_sql_never_selects_raw_columns():
    for sql in (export.select_sql(False, False), export.select_sql(True, True)):
        selected = {c.strip() for c in sql[len("SELECT "):sql.index(" FROM ")].split(",")}
        assert not selected & set(export.RAW_COLUMNS)
        assert "user_input_export" in selected


def test_gaps_self_test_passes():
    assert gaps.self_test() == []


def test_self_test_entry_points_exit_zero(capsys):
    assert export.main(["--self-test"]) == 0
    assert gaps.main(["--self-test"]) == 0
