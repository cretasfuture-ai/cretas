"""PROTO-YDWM-B1: 数值列按内容认指标（温度/分钟/架次/小料kg），单位词不决定是不是指标。

味美 04 醒发蒸制表：整张表没有指标 -> 保存 422「上传数据暂无可分析图表」。
"""
import pandas as pd

from smartbi.services.field_classifier import classify_column
from smartbi.services.semantic_mapper import FieldMapping, cover_unmapped_columns


def test_温度分钟架次_数值列是指标():
    for name in ["面团温度", "醒发分钟", "蒸制分钟", "架次", "开机分钟", "小料kg", "小料(kg)", "良品kg"]:
        r = classify_column(name, inferred_dtype="NUMERIC")
        assert r["is_measure"] is True, (name, r)
        assert r["is_dimension"] is False, (name, r)


def test_时间带分钟单位的数值列是时长指标_不是时间轴():
    # 「醒发时间(分钟)」含「时间」关键词，修前被判成 is_time，整张表没有指标
    for name in ["醒发时间(分钟)", "蒸制时间（分钟）", "加工时间(h)", "停机时间 min", "等待时间(小时)"]:
        r = classify_column(name, inferred_dtype="NUMERIC")
        assert r["is_measure"] is True, (name, r)
        assert r["is_time"] is False, (name, r)


def test_阴性对照_真时间列和编码列仍不是指标():
    assert classify_column("生产日期", inferred_dtype="DATE")["is_time"] is True
    assert classify_column("开单时间", inferred_dtype="TEXT")["is_time"] is True
    assert classify_column("批次编码", inferred_dtype="NUMERIC", category_hint="category")["is_measure"] is False
    assert classify_column("产线号", inferred_dtype="NUMERIC", category_hint="category")["is_measure"] is False
    assert classify_column("备注", inferred_dtype="TEXT")["is_measure"] is False


def test_数值内容压过category提示():
    r = classify_column("架次", inferred_dtype="NUMERIC", category_hint="category")
    assert r["is_measure"] is True
    r2 = classify_column("架次备注", inferred_dtype="TEXT", category_hint="category")
    assert r2["is_dimension"] is True


def _frame():
    return pd.DataFrame({
        "日期": ["2026-10-01", "2026-10-02"],
        "面团温度": [25.5, 26.0],
        "架次": [3, 4],
        "小料kg": [1.5, 2.5],
        "备注": ["a", "b"],
        "Unnamed: 7": [None, None],
    })


def test_语义映射没认出的数值列也要生成_field_def():
    df = _frame()
    mapped = [FieldMapping(original="日期", standard="time_period", confidence=0.9,
                           method="regex_priority", category="time", data_type="DATE")]
    out = cover_unmapped_columns(mapped, list(df.columns), df)
    by = {m.original: m for m in out}
    assert set(by) == {"日期", "面团温度", "架次", "小料kg", "备注"}  # Unnamed 不进
    assert by["架次"].data_type == "NUMERIC"
    measures = {m.original for m in out
                if classify_column(m.original, m.data_type, m.category)["is_measure"]}
    assert measures == {"面团温度", "架次", "小料kg"}


def test_已映射列不动也不重复():
    df = _frame()
    mapped = [FieldMapping(original="架次", standard="x", confidence=0.9, method="llm",
                           category="amount", data_type="NUMERIC")]
    out = cover_unmapped_columns(mapped, list(df.columns), df)
    assert [m.original for m in out].count("架次") == 1
    assert out[0] is mapped[0]
