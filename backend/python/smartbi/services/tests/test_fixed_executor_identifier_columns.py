"""标识列(编码/批号/单号)必须按文本保存，不能被判成数值后清成 null。

回归对象：味美 upload 5323（计划 vs 实产表）——产品编码列大多是纯数字，夹着
"21014C" 这种带字母的，结构检测只抽样前几行判成 numeric，FixedExecutor._clean_numeric
对 float("21014C") 失败后返回 None，整列 9/110 行丢成 null；纯数字编码也被转成
float（前导零、整数位丢失）。

走的是真实入口：真 xlsx 字节 -> StructureDetector.detect(rule) -> FixedExecutor.execute_with_pandas
（auto_parse_excel 在 smartbi/api/excel.py 里就是这两步），不是直接调 _clean_numeric。
"""
from __future__ import annotations

import asyncio
import io

import openpyxl
import pytest

from smartbi.services.fixed_executor import FixedExecutor
from smartbi.services.semantic_mapper import FieldMapping, SemanticMappingResult
from smartbi.services.structure_detector import StructureDetector

HEADERS = ["日期", "产品编码", "批号", "计划量(kg)", "实产量(kg)", "备注"]


def _build_xlsx() -> bytes:
    """和 5323 同形状：前面全是纯数字编码，只有靠后几行夹带字母编码。"""
    wb = openpyxl.Workbook()
    ws = wb.active
    ws.append(HEADERS)
    for i in range(1, 31):
        if i == 22:
            code = "21014C"          # 带字母，位置在抽样窗口之外
        elif i == 25:
            code = "0012345"         # 文本格式的前导零
        else:
            code = 21000 + i         # 数值型编码
        batch = 261000 + i
        ws.append([f"2026-10-{(i % 28) + 1:02d}", code, batch, 300.0 + i, 320.5 + i, "ok"])
    buf = io.BytesIO()
    wb.save(buf)
    return buf.getvalue()


def _extract() -> "tuple[list[dict], dict]":
    content = _build_xlsx()
    structure = asyncio.run(StructureDetector().detect(content, force_method="rule"))
    mapping = SemanticMappingResult(
        field_mappings=[FieldMapping(original=h, standard=None, confidence=1.0, method="rule") for h in HEADERS]
    )
    extracted = FixedExecutor().execute_with_pandas(content, structure, mapping, options={"calculate_stats": False})
    assert extracted.success, extracted.error
    assert extracted.row_count == 30
    return extracted.rows, extracted.data_types


@pytest.fixture(scope="module")
def rows():
    return _extract()[0]


def test_alphanumeric_code_is_kept_not_null(rows):
    codes = [r["产品编码"] for r in rows]
    assert codes[21] == "21014C", f"带字母编码丢了: {codes[21]!r}"
    assert None not in codes


def test_leading_zero_code_is_kept(rows):
    assert rows[24]["产品编码"] == "0012345"


def test_pure_numeric_codes_stay_text_without_float_suffix(rows):
    assert rows[0]["产品编码"] == "21001"
    assert rows[0]["批号"] == "261001"
    assert all(isinstance(r["批号"], str) for r in rows)


def test_measure_columns_are_still_numeric_and_summable(rows):
    plan = [r["计划量(kg)"] for r in rows]
    actual = [r["实产量(kg)"] for r in rows]
    assert all(isinstance(v, float) for v in plan + actual)
    assert sum(plan) == pytest.approx(sum(300.0 + i for i in range(1, 31)))
    assert sum(actual) == pytest.approx(sum(320.5 + i for i in range(1, 31)))


def test_identifier_helper_rules():
    ex = FixedExecutor()
    # 名字像标识 -> 文本
    assert ex._is_identifier_column(("产品编码",), [1, 2], "numeric")
    assert ex._is_identifier_column(("批号",), [1, 2], "numeric")
    # 度量名即使带“批号”字样，尾字是量/数 -> 不算标识
    assert not ex._is_identifier_column(("批号数量",), [1, 2], "numeric")
    assert not ex._is_identifier_column(("重量",), [1.5, 2], "numeric")
    # 不依赖列名：数值列里出现带字母编码 -> 文本
    assert ex._is_identifier_column(("X1",), [1, 2, "21014C"], "numeric")
    # 度量列里的占位符不会把整列变文本
    assert not ex._is_identifier_column(("产量",), [1, 2, "-", "N/A", "待定"], "numeric")
    assert ex._clean_identifier(261001.0) == "261001"
    assert ex._clean_identifier(float("nan")) is None
