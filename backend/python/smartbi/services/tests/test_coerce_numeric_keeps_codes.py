"""查询/出图阶段的 coerce_numeric_columns 不能把带字母/「+」的编码清成 NaN。

链路：真 xlsx 字节 -> StructureDetector -> FixedExecutor（上传入口，#1958 已让编码列按文本保存）
-> 入库后读出来全是字符串 -> coerce_numeric_columns（chat/chart/insight 共用）。
修之前：编码列 >50% 是纯数字，整列被 to_numeric 强转，21014C / 26004+24004 变 NaN。
阴性对照：金额/重量列（含 "-" 占位符）照旧转成数字。
"""
from __future__ import annotations

import asyncio
import io

import numpy as np
import openpyxl
import pandas as pd
import pytest

from smartbi.config import coerce_numeric_columns
from smartbi.services.charts.common import coerce_numeric_columns as chart_coerce
from smartbi.services.fixed_executor import FixedExecutor
from smartbi.services.semantic_mapper import FieldMapping, SemanticMappingResult
from smartbi.services.structure_detector import StructureDetector

# 「物料」列名刻意不含 编码/批号 等关键词，只靠值判断
COERCE_TEST_HEADERS = ["日期", "物料", "金额", "重量(kg)"]


def _amount(i: int) -> float:
    # 非等差，避免被图表侧误当序号列
    return 1000.5 + i * i * 3.7


def _uploaded_rows_as_stored() -> pd.DataFrame:
    wb = openpyxl.Workbook()
    ws = wb.active
    ws.append(COERCE_TEST_HEADERS)
    for i in range(1, 31):
        code = {22: "21014C", 24: "26004+24004"}.get(i, 21000 + i)
        weight = "-" if i == 5 else 300.0 + i  # 度量列里的占位符
        ws.append([f"2026-10-{(i % 28) + 1:02d}", code, _amount(i), weight])
    buf = io.BytesIO()
    wb.save(buf)
    content = buf.getvalue()

    structure = asyncio.run(StructureDetector().detect(content, force_method="rule"))
    mapping = SemanticMappingResult(
        field_mappings=[FieldMapping(original=h, standard=None, confidence=1.0, method="rule") for h in COERCE_TEST_HEADERS]
    )
    extracted = FixedExecutor().execute_with_pandas(content, structure, mapping, options={"calculate_stats": False})
    assert extracted.success, extracted.error
    assert extracted.row_count == 30
    # 入库后经 JSON 读回，全部是字符串
    return pd.DataFrame([{k: (None if v is None else str(v)) for k, v in r.items()} for r in extracted.rows])


def test_mixed_code_column_keeps_lettered_values():
    for fn in (coerce_numeric_columns, chart_coerce):
        df = fn(_uploaded_rows_as_stored())
        codes = df["物料"].tolist()
        assert codes[21] == "21014C", fn
        assert codes[23] == "26004+24004", fn
        assert df["物料"].notna().all(), fn
        assert codes[0] == "21001", fn


def test_measure_columns_still_numeric_with_placeholder():
    for fn in (coerce_numeric_columns, chart_coerce):
        df = fn(_uploaded_rows_as_stored())
        assert pd.api.types.is_numeric_dtype(df["金额"]), fn
        assert pd.api.types.is_numeric_dtype(df["重量(kg)"]), fn
        # "-" 占位符转 NaN（不是编码），其余照常求和
        assert np.isnan(df["重量(kg)"].iloc[4])
        assert df["金额"].sum() == pytest.approx(sum(_amount(i) for i in range(1, 31)))
